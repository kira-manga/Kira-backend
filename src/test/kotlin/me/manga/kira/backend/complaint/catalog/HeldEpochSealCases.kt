package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.JsonNull
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCutoffAttemptV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicReference

/** Genuine original-caller continuation, not an observed-session test or a wire-ready/publication claim. */
internal class HeldEpochSealCases(private val h: HeldEpochSealTestFixture) {
    fun committedHeldAndFreshSuccessor() {
        val leader = h.capture()
        val captured = h.f.row()
        val higher = h.history.events.single { it.tuple.epoch == 2L }
        val higherBefore = h.history.row(higher)
        val original = AtomicReference<CatalogCutoffAttemptV1?>()
        h.clock.afterPrepareCommit = {
            val phase = h.clock.preparations.last()
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertFalse(phase.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
            assertEquals(0L, h.lanes.activeOwners().totalOwners, "A known COMMIT with the original holder unreleased cannot start seal acquisition.")
            assertEquals(h.wire.sts.closedClients, h.wire.sts.createdClients)
            assertEquals(h.wire.kms.closedClients, h.wire.kms.createdClients)
        }
        h.wire.beforeStsFactory = { if (it == 1) original.set(checkNotNull(h.activeAttempt())) }
        val held = h.open(leader.campaign)
        val attempt = checkNotNull(original.get())
        h.assertHeld(held, attempt)
        val canonical = h.assertCanonical(leader.receipt.token)
        assertEquals(listOf("STS_SOURCE", "STS_ASSUME", "STS_TARGET", "KMS_GENERATE"), h.wire.requests)
        assertEquals(2, h.wire.stsFactories)
        assertEquals(1, h.wire.kmsFactories)
        assertEquals(2, h.clock.postPrepareRenewals, "Actual renewal after PREPARE and again before KMS must both commit.")
        assertEquals(h.history.cutoffEvents.size, h.history.wire.generated())
        assertTrue(h.history.cutoffEvents.all { h.history.state(it) == "VERIFIED" })
        assertEquals(higherBefore, h.history.row(higher))
        assertEquals(captured, h.f.row())
        h.wire.assertProtocol(canonical)

        val extra = List(h.history.routing.journalConfiguration.declaration().limits.capacity.routinePublicationLanes - 1) {
            checkNotNull(h.lanes.tryRoutinePublication())
        }
        try {
            assertNull(h.lanes.tryRoutinePublication(), "HELD must occupy a real routine lane in every admission sum.")
            h.factory().use { ordinary -> assertThrows<JournalPublicationExceptionV1> { ordinary.reserveCutoff() } }
            assertSame(attempt, h.activeAttempt())
        } finally {
            extra.forEach { it.close() }
        }
        held.close()
        h.assertReleased()
        assertEquals(canonical, h.sealRow(), "Abandoning held ciphertext must not replace, clear or verify the canonical intent.")

        val ordinaryTraffic = h.history.wire.requests.size to h.history.wire.kms.requests.size
        val successor = h.f.successor(leader.campaign)
        val next = h.open(successor.campaign)
        val nextAttempt = checkNotNull(h.activeAttempt())
        assertNotSame(attempt, nextAttempt)
        assertNotSame(attempt.budget, nextAttempt.budget)
        assertNotEquals(leader.receipt.token, successor.receipt.token)
        assertEquals(canonical, h.assertCanonical(leader.receipt.token), "The new current campaign preserves the original preparing token/key/bytes.")
        assertEquals(ordinaryTraffic, h.history.wire.requests.size to h.history.wire.kms.requests.size)
        assertEquals(List(2) { listOf("STS_SOURCE", "STS_ASSUME", "STS_TARGET", "KMS_GENERATE") }.flatten(), h.wire.requests)
        assertEquals(4, h.wire.stsFactories, "One consumed lower adapter cannot be cached at process scope.")
        assertEquals(2, h.wire.kmsFactories)
        held.close() // An old idempotent cleanup cannot erase the next concrete owner or issuer registration.
        assertSame(nextAttempt, h.activeAttempt())
        h.assertHeld(next, nextAttempt)
        next.close()
        h.assertReleased()
        assertEquals(canonical, h.sealRow())
        assertEquals(captured, h.f.row())
    }

    fun prepareFailure(cut: HeldSealPreparationCut) {
        val leader = h.capture()
        val empty = h.sealRow()
        assertTrue(empty.values.all { it === JsonNull })
        when (cut) {
            HeldSealPreparationCut.ROLLBACK -> h.clock.beforePrepareCommit = { error(HeldEpochSealHttpFixture.PRIVATE_TEXT) }
            HeldSealPreparationCut.UNKNOWN_COMMIT -> Unit
            HeldSealPreparationCut.COMMITTED_UNRELEASED -> h.clock.afterPrepareCommit = { error(HeldEpochSealHttpFixture.PRIVATE_TEXT) }
        }
        val failure = if (cut == HeldSealPreparationCut.UNKNOWN_COMMIT) {
            h.f.deferredSealCommitRefusal { assertThrows<PersistencePhaseException> { h.open(leader.campaign) } }
        } else {
            assertThrows<PersistencePhaseException> { h.open(leader.campaign) }
        }
        h.wire.assertSanitized(failure)
        assertEquals(cut.outcome, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        assertTrue(h.wire.requests.isEmpty())
        assertEquals(0, h.wire.stsFactories)
        assertEquals(0, h.wire.kmsFactories)
        val stored = if (cut == HeldSealPreparationCut.COMMITTED_UNRELEASED) h.assertCanonical(leader.receipt.token) else empty
        assertEquals(stored, h.sealRow())
        assertTrue(h.history.cutoffEvents.all { h.history.state(it) == "VERIFIED" })
        h.assertReleased()

        h.clock.beforePrepareCommit = {}
        h.clock.afterPrepareCommit = {}
        h.wire.assertSanitized(assertThrows<PersistencePhaseException> { h.open(leader.campaign) })
        assertTrue(h.wire.requests.isEmpty(), "Removing a fixture failure cannot revive its stopped original campaign.")
        val successor = h.f.successor(leader.campaign)
        h.open(successor.campaign).close()
        val preparing = if (cut == HeldSealPreparationCut.COMMITTED_UNRELEASED) leader.receipt.token else successor.receipt.token
        h.assertCanonical(preparing)
        h.assertReleased()
    }

    fun currentOwnerRefusal(cut: HeldSealCurrentCut) {
        val leader = h.capture()
        var exactBeforeFault: String? = null
        when (cut) {
            HeldSealCurrentCut.MISSING_D4, HeldSealCurrentCut.REPLACEMENT_LANES -> Unit

            HeldSealCurrentCut.CAMPAIGN_AFTER_SOURCE -> h.wire.changeStsReply = { stage, reply ->
                if (stage == 1) reply.onClose = leader.campaign::close
            }

            HeldSealCurrentCut.DB_BINDING_BEFORE_KMS -> h.wire.changeStsReply = { stage, reply ->
                if (stage == 3) {
                    reply.onClose = {
                        h.wire.preserveAssertions {
                            // Test observer mutates only its exact row; production SDK callbacks never issue SQL.
                            exactBeforeFault = h.f.genesis.controlRow()
                            h.f.genesis.setDesired(ByteArray(32) { 87 })
                        }
                    }
                }
            }

            HeldSealCurrentCut.CAMPAIGN_DURING_KMS -> h.wire.changeKmsReply = { it.beforeRead = leader.campaign::close }
        }
        try {
            val failure = if (cut == HeldSealCurrentCut.REPLACEMENT_LANES) {
                JournalPublicationLanesV1(h.history.routing.journalConfiguration).use { other ->
                    h.factory(other).use { factory -> assertThrows<PersistencePhaseException> { h.issuer.openPreparedSeal(leader.campaign, factory) } }
                }
            } else {
                assertThrows<PersistencePhaseException> { h.open(leader.campaign) }
            }
            h.wire.assertSanitized(failure)
            assertEquals(cut.stsRequests, h.wire.sts.requests.size)
            assertEquals(if (cut == HeldSealCurrentCut.CAMPAIGN_DURING_KMS) 1 else 0, h.wire.kms.requests.size)
            assertNull(h.activeAttempt())
            h.assertReleased()
        } finally {
            exactBeforeFault?.let(h.f.genesis::restoreControl) // Exact own-fault restoration, not a new accepted-head/lease producer.
        }
    }
}

internal enum class HeldSealPreparationCut(val outcome: PersistenceDatabaseOutcome) {
    ROLLBACK(PersistenceDatabaseOutcome.ROLLED_BACK),
    UNKNOWN_COMMIT(PersistenceDatabaseOutcome.UNKNOWN),
    COMMITTED_UNRELEASED(PersistenceDatabaseOutcome.COMMITTED),
}

internal enum class HeldSealCurrentCut(val stsRequests: Int) {
    MISSING_D4(0),
    REPLACEMENT_LANES(0),
    CAMPAIGN_AFTER_SOURCE(1),
    DB_BINDING_BEFORE_KMS(3),
    CAMPAIGN_DURING_KMS(3),
}
