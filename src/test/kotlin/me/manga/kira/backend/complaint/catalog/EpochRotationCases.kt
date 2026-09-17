package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDriverAttemptPolicy
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhysicalEntry
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseCampaignV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual PostgreSQL phases and original native custody. No seal, checkpoint, activation or complete-D qualification. */
internal class EpochRotationCases(private val f: EpochRotationTestFixture) {
    fun requestReleaseAndFreshExclusiveCapture() {
        f.prepare()
        val outside = f.outsideRotationAndLease()
        val initial = f.row()
        assertEquals(0L, initial.sequence)
        assertNull(initial.slot)
        val leader = f.acquire()
        val trace = captureWhileShared(leader.campaign, renewWhileWaiting = true)
        val cutoff = trace.outcome.getOrThrow()
        assertEquals(initial.epoch, trace.requested.epoch)
        assertEquals(1L, trace.requested.sequence)
        assertCutoff(cutoff, f.row())
        assertEquals(leader.receipt.owner, cutoff.requestOwner)
        assertEquals(leader.receipt.token, cutoff.requestToken)
        assertEquals(cutoff.requestOwner, cutoff.captureOwner)
        assertEquals(cutoff.requestToken, cutoff.captureToken)
        assertEquals(outside, f.outsideRotationAndLease())

        f.resource.requestShutdown()
        assertEquals(PersistenceLifecycleObservation.EPOCH_ROTATION_LOCAL_ENDED, f.resource.observeShutdown())
        assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, f.resource.prepare())
        f.tls.tlsPid(f.process.pools.ordinary) // Stopping this nonpooled role does not replace or stop the ordinary pool/root.
        f.released()
    }

    fun durableRetriesAndCommitRefusals() {
        f.prepare()
        val outside = f.outsideRotationAndLease()
        val initial = f.row()
        val first = f.acquire()
        f.deferredCommitRefusal(null) {
            val failure = assertThrows<PersistencePhaseException> { f.protocol.requestScan(first.campaign) }
            assertFailure(failure)
            assertEquals(PersistenceDatabaseOutcome.UNKNOWN, failure.databaseOutcome)
            assertTrue(failure.cleanupProven)
            assertEquals(initial, f.row(), "A deferred FK COMMIT refusal rolls back the request; it is not a persisted lost-reply case.")
            assertTrue(f.entries().isEmpty())
            f.released()
        }

        val second = f.successor(first.campaign)
        val failed = f.deferredCommitRefusal("REQUESTED") { captureWhileShared(second.campaign) }
        val failure = assertInstanceOf(PersistencePhaseException::class.java, failed.outcome.exceptionOrNull())
        assertFailure(failure)
        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, failure.databaseOutcome)
        assertEquals(failed.requested, f.row(), "The failed capture COMMIT leaves the exact durable REQUESTED slot, not CAPTURED.")
        val requested = checkNotNull(failed.requested.slot)

        val third = f.successor(second.campaign)
        val recovered = captureWhileShared(third.campaign)
        assertEquals(failed.requested, recovered.requested)
        assertNotEquals(failed.pid, recovered.pid, "A successor capture gets a fresh backend, not a returned rotation pool lease.")
        assertNotSame(failed.entry, recovered.entry)
        val cutoff = recovered.outcome.getOrThrow()
        assertCutoff(cutoff, f.row())
        assertEquals(requested.id, cutoff.rotationId)
        assertEquals(requested.request.owner, cutoff.requestOwner)
        assertEquals(requested.request.token, cutoff.requestToken)
        assertEquals(third.receipt.owner, cutoff.captureOwner)
        assertEquals(third.receipt.token, cutoff.captureToken)
        assertNotEquals(cutoff.requestToken, cutoff.captureToken)

        val fourth = f.successor(third.campaign)
        val captured = f.row()
        val replay = assertInstanceOf(CatalogEpochRotationV1.ResumeResult.Captured::class.java, f.protocol.resume(fourth.campaign))
        assertCutoff(replay.cutoff, captured)
        assertEquals(captured, f.row(), "Known-success CAPTURED retry is an exact locked read, never another epoch increment.")
        assertTrue(f.entries().isEmpty())
        assertFailure(assertThrows<PersistencePhaseException> { f.protocol.requestScan(fourth.campaign) })
        assertEquals(captured, f.row())
        assertEquals(outside, f.outsideRotationAndLease())
        f.released()
    }

    fun leaseAndBindingRefusals() {
        f.prepare()
        val outside = f.outsideRotationAndLease()
        val first = f.acquire()
        val expired = refusedWhileShared(first.campaign) {
            assertEquals(
                1,
                f.observer.update(
                    "UPDATE complaint_journal_control SET lease_expires_at = updated_at WHERE data_scope_id = ? AND lease_owner IS NOT NULL",
                    ComplaintDataScope.LIVE.id,
                ),
            )
        }
        val second = f.successor(first.campaign)
        val drifted = refusedWhileShared(second.campaign) { f.genesis.setDesired(ByteArray(32) { 0x55.toByte() }) }
        assertEquals(expired.requested, drifted.requested)
        val third = f.successor(second.campaign)
        val recovered = captureWhileShared(third.campaign)
        assertEquals(expired.requested, recovered.requested)
        assertCutoff(recovered.outcome.getOrThrow(), f.row())
        assertEquals(outside, f.outsideRotationAndLease())
        f.released()
    }

    fun discoveryDeadlineAndNonrevival() {
        f.prepare()
        val first = f.acquire()
        assertInstanceOf(CatalogEpochRotationV1.ResumeResult.NoPending::class.java, f.protocol.resume(first.campaign))
        val before = f.genesis.state()
        val returnedAt = System.nanoTime()
        val allowance = f.resource.descriptor().maximumRotationMillis
        // Real monotonic elapsed time after the released discovery, not a replacement clock or a restarted allowance.
        awaitLifecycleFact(allowance + 2_000) { System.nanoTime() - returnedAt >= TimeUnit.MILLISECONDS.toNanos(allowance) }
        val expired = assertThrows<PersistencePhaseException> { f.protocol.requestScan(first.campaign) }
        assertFailure(expired)
        assertEquals(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED, expired.code)
        assertEquals(PersistenceDatabaseOutcome.NONE, expired.databaseOutcome)
        assertEquals(before, f.genesis.state())
        assertTrue(f.entries().isEmpty())
        f.released()
        assertFailure(assertThrows<PersistencePhaseException> { f.protocol.resume(first.campaign) })
        assertFailure(assertThrows<PersistencePhaseException> { f.protocol.requestScan(first.campaign) })
        assertEquals(before, f.genesis.state(), "Cleanup or another API call cannot revive the failed original discovery attempt.")

        val successor = f.successor(first.campaign)
        val trace = captureWhileShared(successor.campaign)
        assertEquals(1L, trace.requested.sequence)
        assertCutoff(trace.outcome.getOrThrow(), f.row())
        f.released()
    }

    private fun refusedWhileShared(campaign: CatalogCoordinatorLeaseCampaignV1, change: () -> Unit): CaptureTrace {
        val original = AtomicReference<String?>()
        val trace = captureWhileShared(
            campaign,
            whileWaiting = {
                original.set(f.genesis.controlRow())
                change()
            },
            afterFailedCleanup = { f.genesis.restoreControl(checkNotNull(original.get())) },
        )
        assertFailure(assertInstanceOf(PersistencePhaseException::class.java, trace.outcome.exceptionOrNull()))
        assertEquals(trace.requested, f.row())
        return trace
    }

    /** Real COMMITTED/returned request precedes the observed exclusive wait; the main caller owns the independent shared holder. */
    private fun captureWhileShared(
        campaign: CatalogCoordinatorLeaseCampaignV1,
        renewWhileWaiting: Boolean = false,
        whileWaiting: () -> Unit = {},
        afterFailedCleanup: () -> Unit = {},
    ): CaptureTrace = f.sharedEpochHolder { blocker, observer, holderPid ->
        OwnedCallerTestScope().use { callers ->
            val returnedRequest = AtomicReference<CatalogEpochRotationV1.Request?>()
            val returnedCutoff = AtomicReference<CatalogEpochRotationV1.Cutoff?>()
            val selectedEntry = AtomicReference<PersistencePhysicalEntry?>()
            val worker = callers.launch {
                val discovery = f.protocol.resume(campaign)
                val request = when (discovery) {
                    is CatalogEpochRotationV1.ResumeResult.NoPending -> f.protocol.requestScan(campaign)
                    is CatalogEpochRotationV1.ResumeResult.Requested -> discovery.request
                    else -> error("Expected an empty or exact pending slot before capture.")
                }
                returnedRequest.set(request)
                f.released() // Original request caller has neither Spring resources nor its coordinator permit/lease.
                val outcome = runCatching { f.protocol.captureEpoch(request).also(returnedCutoff::set) }
                if (outcome.isFailure) {
                    f.awaitReclaimed(checkNotNull(selectedEntry.get()))
                    afterFailedCleanup()
                    val beforeLate = f.genesis.state()
                    assertFailure(assertThrows<PersistencePhaseException> { f.protocol.captureEpoch(request) })
                    assertEquals(beforeLate, f.genesis.state(), "The SAME failed caller/request cannot revive after actual reclamation/restoration.")
                }
                f.released()
                outcome
            }
            var selected: Pair<Int, PersistencePhysicalEntry>? = null
            var requested: EpochRotationObservedRow? = null
            try {
                val waiting = f.waitingCapture(holderPid)
                selected = waiting
                selectedEntry.set(waiting.second)
                assertNotNull(returnedRequest.get())
                assertNull(returnedCutoff.get())
                val row = f.row()
                requested = row
                assertTrue(row.scanRequested)
                assertEquals("REQUESTED", checkNotNull(row.slot).state)
                assertEquals(row.epoch, row.slot.epochBefore)
                assertEquals(1, f.entries().size)
                assertEquals(1, f.resource.descriptor().capacity)
                assertFalse(f.resource.descriptor().pooled)
                assertEquals(PersistenceDriverAttemptPolicy.TRACKED_EPOCH_ROTATION_CONJUNCTION, waiting.second.policy)
                assertFalse(waiting.first in f.pooledPids)
                assertEquals(waiting.first, f.tls.observeTlsPid(waiting.first))
                f.released()
                assertEquals(
                    0L,
                    observer.queryForObject(
                        "SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid = l.relation WHERE l.pid = ? " +
                            "AND c.relname IN ('complaint_journal_control','complaint_catalog_mutations','complaint_capacity_counters')",
                        Long::class.java,
                        waiting.first,
                    ),
                    "No control/catalog/counter lock may be held while the fresh session waits for the exclusive fence.",
                )
                if (renewWhileWaiting) {
                    val renewed = f.coordinator.lease.renew(campaign)
                    assertEquals(campaign.owner, renewed.owner)
                    assertEquals(campaign.token, renewed.token)
                    assertEquals(row, f.row(), "Concurrent genuine renewal must preserve the exact original request provenance.")
                    f.released()
                }
                whileWaiting()
            } finally {
                blocker.rollback() // Always release the actual PG holder before awaiting either original caller or fixture cleanup.
            }
            val outcome = worker.value()
            val waiting = checkNotNull(selected)
            f.awaitReclaimed(waiting.second)
            CaptureTrace(waiting.first, waiting.second, checkNotNull(requested), outcome)
        }
    }

    private fun assertCutoff(cutoff: CatalogEpochRotationV1.Cutoff, row: EpochRotationObservedRow) {
        val slot = checkNotNull(row.slot)
        val captured = checkNotNull(slot.capture)
        assertEquals("CAPTURED", slot.state)
        assertFalse(row.scanRequested)
        assertEquals(slot.id, cutoff.rotationId)
        assertEquals(row.sequence, cutoff.sequence)
        assertEquals(slot.writer, cutoff.writer)
        assertEquals(slot.epochBefore, cutoff.epoch)
        assertEquals(Math.addExact(cutoff.epoch, 1L), cutoff.epochAfter)
        assertEquals(slot.epochAfter, cutoff.epochAfter)
        assertEquals(cutoff.epochAfter, row.epoch)
        assertEquals(slot.request.owner, cutoff.requestOwner)
        assertEquals(slot.request.token, cutoff.requestToken)
        assertEquals(slot.request.at, cutoff.requestedAt)
        assertEquals(captured.owner, cutoff.captureOwner)
        assertEquals(captured.token, cutoff.captureToken)
        assertEquals(captured.at, cutoff.capturedAt)
    }

    private fun assertFailure(failure: PersistencePhaseException) {
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private data class CaptureTrace(
        val pid: Int,
        val entry: PersistencePhysicalEntry,
        val requested: EpochRotationObservedRow,
        val outcome: Result<CatalogEpochRotationV1.Cutoff>,
    )
}
