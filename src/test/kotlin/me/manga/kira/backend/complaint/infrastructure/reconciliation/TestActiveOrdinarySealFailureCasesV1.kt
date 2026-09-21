package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

internal enum class ActiveSealProviderCut { WRONG_ROLE, VERSION, WIRE, METADATA, LOCK_MODE, RETENTION }
internal enum class ActiveSealCompletionCut { BEFORE_COMMIT, AFTER_COMMIT, DEFERRED_COMMIT_UNKNOWN, UNRESOLVED_RELEASE }
internal enum class ActiveSealLifetimeCut { CANCELLATION, LEASE_EXPIRED, LEASE_REPLACED, LATE_NATIVE_CLOSE }
private enum class ActiveSealProviderPhase { SETUP, CASE, CLEANUP }

/** Failure-only injection at raw HTTP/real transaction boundaries. No successful proof is supplied. */
internal object TestActiveOrdinarySealFailureCasesV1 {
    fun provider(tls: VersionBoundPersistenceConnectedFixture, cut: ActiveSealProviderCut) {
        var phase = ActiveSealProviderPhase.SETUP
        try {
            withActiveSealFixture(tls) { fixture ->
                phase = ActiveSealProviderPhase.CASE
                provider(fixture, cut)
                phase = ActiveSealProviderPhase.CLEANUP
            }
        } catch (failure: Throwable) {
            // Fixed labels AFTER failure, including stackless failures with suppression disabled.
            // Never print provider/SQL material, wrap the original or turn later cleanup into proof.
            println("ACTIVE_SEAL_PROVIDER_BOUNDARY cut=${cut.name} phase=${phase.name}")
            throw failure
        }
    }

    private fun provider(f: TestActiveOrdinarySealFixtureV1, cut: ActiveSealProviderCut) {
        f.native.changeSts = { stage, reply ->
            if (stage == 3 && cut === ActiveSealProviderCut.WRONG_ROLE) {
                val changed = reply.bytes.toString(Charsets.UTF_8).replace("AROA" + "B".repeat(17), "AROA" + "C".repeat(17)).toByteArray()
                assertEquals(reply.bytes.size, changed.size); changed.copyInto(reply.bytes)
            }
        }
        f.native.beforeS3 = { request ->
            if (request.kind == "GET" && cut === ActiveSealProviderCut.WIRE) {
                val value = checkNotNull(f.native.stored)
                f.native.stored = value.copy(bytes = value.bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            }
        }
        f.native.changeS3 = { request, reply -> if (request.kind == "GET") {
            val change = when (cut) {
                ActiveSealProviderCut.VERSION -> "x-amz-version-id" to "foreign-version"
                ActiveSealProviderCut.METADATA -> "x-amz-meta-kira-journal-event-id" to "A".repeat(43)
                ActiveSealProviderCut.LOCK_MODE -> "x-amz-object-lock-mode" to "GOVERNANCE"
                ActiveSealProviderCut.RETENTION -> "x-amz-object-lock-retain-until-date" to Instant.now().minusSeconds(1).toString()
                else -> null
            }
            change?.let { (name, value) -> reply.headers = reply.headers.filterKeys { !it.equals(name, true) } + (name to listOf(value)) }
        } }
        val original = f.begin()
        assertThrows<TestActiveOrdinarySealExceptionV1> { f.seal(original) }
        f.assertSqlReleased(); f.native.assertDisposed(); f.ordinary.assertDisposed()
        assertEquals("SEAL_PREPARED", f.control()["seal_state"]); assertNull(f.control()["seal_verification_bytes"])
        assertFalse(f.probe.calls.any { it.step === TestActiveOrdinarySealStepV1.VERIFY })
        assertEquals(0, f.native.order.count { it == "DECRYPT" }, "Cheap winner/wire/metadata rejection precedes AEAD/KMS.")
        if (cut === ActiveSealProviderCut.WRONG_ROLE) {
            assertEquals("CANONICAL", f.paid()["state"]); assertTrue(f.native.requests.isEmpty())
        } else assertEquals("WIRE_FROZEN", f.paid()["state"])
        val image = f.image(); val providers = f.native.order.toList()
        assertThrows<TestActiveOrdinarySealExceptionV1> { f.seal(original) }
        assertThrows<RuntimeException> { TestActiveOrdinarySealV1.begin(f.captured, f.registration, f.first.assembly) }
        assertEquals(image, f.image()); assertEquals(providers, f.native.order)
    }

    fun completion(tls: VersionBoundPersistenceConnectedFixture, step: TestActiveOrdinarySealStepV1, cut: ActiveSealCompletionCut) = withActiveSealFixture(tls) { f ->
        require(step in setOf(TestActiveOrdinarySealStepV1.CANONICAL, TestActiveOrdinarySealStepV1.FREEZE, TestActiveOrdinarySealStepV1.VERIFY))
        val counterImage = f.first.counters()
        val resource = Any(); val sentinel = Any()
        var bound = false
        var selected: PersistencePhaseContext? = null
        var before: Map<String, List<String>>? = null
        f.probe.before = { if (it.step === step && before == null) before = f.image() }
        f.probe.after = { call ->
            val statement = when (step) {
                TestActiveOrdinarySealStepV1.CANONICAL -> TestActiveOrdinarySealSqlV1.prepareControl
                TestActiveOrdinarySealStepV1.FREEZE -> TestActiveOrdinarySealSqlV1.freeze
                else -> TestActiveOrdinarySealSqlV1.verifyControl
            }
            if (selected == null && call.step === step && call.sql == statement) {
                selected = call.phase
                if (cut === ActiveSealCompletionCut.DEFERRED_COMMIT_UNKNOWN) {
                    val jdbc = JdbcTemplate(f.runtime.pools.catalogCoordinator.dataSource)
                    jdbc.execute("CREATE TEMP TABLE kira_active_seal_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                    assertEquals(2, jdbc.update("INSERT INTO kira_active_seal_commit_cut VALUES (1), (1)"))
                } else TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun beforeCommit(readOnly: Boolean) {
                        if (cut === ActiveSealCompletionCut.BEFORE_COMMIT) error("Synthetic ACTIVE seal pre-COMMIT failure.")
                    }
                    override fun afterCommit() {
                        if (cut === ActiveSealCompletionCut.UNRESOLVED_RELEASE) {
                            TransactionSynchronizationManager.bindResource(resource, sentinel); bound = true
                        }
                        if (cut !== ActiveSealCompletionCut.BEFORE_COMMIT) error("Synthetic ACTIVE seal lost COMMIT acknowledgment.")
                    }
                })
            }
        }
        val original = f.begin()
        try {
            assertThrows<TestActiveOrdinarySealExceptionV1> { f.seal(original) }
            if (cut === ActiveSealCompletionCut.UNRESOLVED_RELEASE) {
                assertTrue(bound); assertSame(selected, PersistencePhaseOwnership.current()); assertTrue(checkNotNull(selected).quarantined())
                if (step === TestActiveOrdinarySealStepV1.FREEZE) {
                    assertEquals(1L, f.process.publicationLanes.activeOwners().totalOwners)
                    assertEquals(0, f.native.kms.closedClients, "Unknown physical release cannot be relabelled as native cleanup.")
                }
            }
        } finally {
            f.probe.before = {}; f.probe.after = {}
            if (bound) assertSame(sentinel, TransactionSynchronizationManager.unbindResource(resource))
            requireConnectionFree() // Reclaim original quarantine only, never repair that original's success.
            if (cut === ActiveSealCompletionCut.UNRESOLVED_RELEASE) checkNotNull(f.process.ordinarySeal).close()
        }
        f.assertSqlReleased(); f.native.assertDisposed()
        val outcome = when (cut) {
            ActiveSealCompletionCut.BEFORE_COMMIT -> PersistenceDatabaseOutcome.ROLLED_BACK
            ActiveSealCompletionCut.DEFERRED_COMMIT_UNKNOWN -> PersistenceDatabaseOutcome.UNKNOWN
            else -> PersistenceDatabaseOutcome.COMMITTED
        }
        assertEquals(outcome, checkNotNull(selected).databaseOutcome())
        if (outcome !== PersistenceDatabaseOutcome.COMMITTED) assertEquals(before, f.image())
        assertEquals(counterImage, f.first.counters(), "A never adds/refunds the paid C slot, including failed COMMIT.")
        if (step === TestActiveOrdinarySealStepV1.CANONICAL) assertTrue(f.native.order.isEmpty())
        if (step !== TestActiveOrdinarySealStepV1.VERIFY) assertTrue(f.native.requests.isEmpty())
        if (step === TestActiveOrdinarySealStepV1.VERIFY && outcome === PersistenceDatabaseOutcome.COMMITTED) assertEquals("SEAL_VERIFIED", f.control()["seal_state"])
        val image = f.image(); val providers = f.native.order.toList(); val calls = f.probe.calls.size
        assertThrows<TestActiveOrdinarySealExceptionV1> { f.seal(original) }
        assertThrows<RuntimeException> { TestActiveOrdinarySealV1.begin(f.captured, f.registration, f.first.assembly) }
        assertEquals(image, f.image()); assertEquals(providers, f.native.order); assertEquals(calls, f.probe.calls.size)
    }

    fun lifetime(tls: VersionBoundPersistenceConnectedFixture, cut: ActiveSealLifetimeCut) = withActiveSealFixture(tls) { f ->
        var observed = false
        f.native.beforeS3 = { request -> if (request.kind == "GET") {
            when (cut) {
                ActiveSealLifetimeCut.CANCELLATION -> { observed = true; throw CancellationException("Synthetic original ACTIVE seal cancellation.") }
                ActiveSealLifetimeCut.LEASE_EXPIRED -> {
                    assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET lease_expires_at = clock_timestamp() - interval '1 millisecond' WHERE data_scope_id = ?", f.scope)); observed = true
                }
                ActiveSealLifetimeCut.LEASE_REPLACED -> {
                    assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET lease_owner = ?, lease_token = lease_token + 1 WHERE data_scope_id = ?", UUID.randomUUID(), f.scope)); observed = true
                }
                else -> Unit
            }
        } }
        f.native.onNativeClose = {
            assertEquals(1L, f.process.publicationLanes.activeOwners().totalOwners)
            if (!observed && cut === ActiveSealLifetimeCut.LATE_NATIVE_CLOSE) { observed = true; f.native.offsetNanos += 11_000_000_000L }
        }
        val original = f.begin()
        if (cut === ActiveSealLifetimeCut.CANCELLATION) {
            assertThrows<CancellationException> { f.seal(original) }
            assertThrows<CancellationException> { f.seal(original) }
        } else {
            assertThrows<TestActiveOrdinarySealExceptionV1> { f.seal(original) }
            assertThrows<TestActiveOrdinarySealExceptionV1> { f.seal(original) }
        }
        f.native.onNativeClose = {}
        assertTrue(observed); f.assertSqlReleased(); f.native.assertDisposed()
        assertEquals(0L, f.process.publicationLanes.activeOwners().totalOwners)
        assertEquals("SEAL_PREPARED", f.control()["seal_state"]); assertNull(f.control()["seal_verification_bytes"])
        assertFalse(f.probe.calls.any { it.step === TestActiveOrdinarySealStepV1.VERIFY })
    }

    fun failedNativeCloseKeepsOriginalLane(tls: VersionBoundPersistenceConnectedFixture) {
        var asserted = false
        assertThrows<RuntimeException> { withActiveSealFixture(tls) { f ->
            var refused = false
            f.native.onNativeClose = {
                assertEquals(1L, f.process.publicationLanes.activeOwners().totalOwners)
                if (!refused) { refused = true; error("Synthetic native close refusal.") }
            }
            val original = f.begin()
            assertThrows<TestActiveOrdinarySealExceptionV1> { f.seal(original) }
            assertTrue(refused); assertEquals(1L, f.process.publicationLanes.activeOwners().totalOwners)
            assertFalse(f.probe.calls.any { it.step === TestActiveOrdinarySealStepV1.VERIFY })
            f.native.assertDisposed(requireReturnedClose = false)
            val closes = listOf(f.native.sts.closedClients, f.native.kms.closedClients, f.native.s3Closed)
            assertThrows<RuntimeException> { checkNotNull(f.process.ordinarySeal).close() }
            assertEquals(closes, listOf(f.native.sts.closedClients, f.native.kms.closedClients, f.native.s3Closed))
            assertEquals(1L, f.process.publicationLanes.activeOwners().totalOwners, "Reentrant close cannot invent a native release receipt.")
            asserted = true
        } }
        assertTrue(asserted, "The expected failure must be the deliberately retained native close, not setup.")
    }
}
