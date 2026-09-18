package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseCampaignV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationStateV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** One genuine committed PREPARE3/pre-Sign cut; a new allowance uses the exact one-shot witness on the same retained process. */
internal class CatalogSignerRotationActivationContinuationCases(private val f: CatalogSignerRotationActivationFixture) {
    private val observed = CatalogSignerRotationActivationAssertions(f)
    private val sql = CatalogSignerRotationActivationSql(f)
    private val flow = CatalogSignerRotationActivationCases(f)

    fun knownUnattemptedSameProcessOnly() {
        f.delivery.awaitActualLeaseExpiry()
        CatalogSignerRotationActivationRoot(f).use { root ->
            root.prepare()
            val previous = cutAfterCommittedPrepare(root)
            val witness = ActivationUnattemptedObservation(previous)
            val leaves = f.snapshotLeaves()
            val row = f.mutationJson()
            val version = f.rowVersion()
            foreignProcessRefuses(root, previous)
            assertEquals(row, f.mutationJson())
            assertEquals(version, f.rowVersion())
            f.assertLeavesUnchanged(leaves)
            witness.unchanged(consumed = false)
            val before = f.delivery.leaseRow()
            f.delivery.awaitActualLeaseExpiry() // The exact process/root remains retained; no SQL expiry or clock advance.
            assertEquals(
                PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED,
                assertThrows<PersistenceBoundaryException> { previous.budget.remainingMillis(1) }.code,
            )
            val next = root.begin() // Only now create the distinct explicit total allowance.
            assertNotSame(previous.budget, next.budget)
            assertTrue(poolTestField<Long>(next.budget, "startedAtNanos") > poolTestField<Long>(previous.budget, "startedAtNanos"))
            f.http.replicateOnPut = true
            f.beforeSign = {
                witness.unchanged(consumed = true)
                assertSame(root.process, next.process)
                assertSame(previous, ownedCutField(next, "continuationPrior"))
                assertNull(ownedCutField(next, "preparedOperation"))
                assertNotSame(ownedCutField(previous, "custody"), ownedCutField(next, "custody"))
                assertNotSame(ownedCutField(previous, "campaign"), ownedCutField(next, "campaign"))
                assertNotSame(ownedCutField(previous, "dispatchWindow"), ownedCutField(next, "dispatchWindow"))
                assertEquals(row, f.mutationJson())
                assertEquals(version, f.rowVersion(), "Continuation has not rewritten or recharged the genuine unsigned row.")
                observed.prepared(signed = false)
                sql.effects(root, next, prepares = 0, signatures = 0, completes = 0, projects = 0)
            }
            val lower = f.delivery.databaseNow()
            val result = try {
                root.continueUnattempted(next, previous)
            } finally {
                f.beforeSign = {}
            }
            val upper = f.delivery.databaseNow()
            assertEquals(CatalogSignerRotationActivationStateV1.PROJECTION_PENDING, result.state)
            witness.unchanged(consumed = true)
            sql.acquired(root, next, before, lower, upper)
            sql.effects(root, next, prepares = 0, signatures = 1, completes = 1, projects = 0)
            flow.projectOnce(root, next, actualComplete = true, prepares = 0, signatures = 1, initiallySigned = false)
            observed.oneSignAndPut()
            f.assertLeavesUnchanged(leaves, CONTINUATION_LEAVES)
            oneShotRefuses(root, previous)
            witness.unchanged(consumed = true)
            root.assertReleased(previous)
        }
    }

    private fun cutAfterCommittedPrepare(root: CatalogSignerRotationActivationRoot): CatalogSignerRotationActivationV1 {
        val previous = root.begin()
        var reached = false
        root.clock.onSample = {
            if (poolTestField<Boolean>(previous, "preparedMarkerWritten") && ownedCutField(previous, "preparedOperation") != null &&
                !poolTestField<Boolean>(previous, "signArmIssued")
            ) {
                root.clock.onSample = {} // Disable before throwing so cleanup consumes actual remaining time normally.
                reached = true
                assertEquals("READBACK", ownedCutField(previous, "stage").toString())
                throw IOException("Synthetic activation3 clean committed PREPARE before any Sign arm.")
            }
        }
        try {
            f.core.refused { root.activate(previous) }
        } finally {
            root.clock.onSample = {}
        }
        assertTrue(reached)
        assertEquals(0L, root.clock.extraNanos)
        root.assertReleased(previous)
        assertTrue(previous.budget.remainingMillis(1) > 0, "Original cleanup was proven before its real deadline.")
        observed.prepared(signed = false)
        sql.effects(root, previous, prepares = 1, signatures = 0, completes = 0, projects = 0)
        sql.healthy(root, previous, initiallySigned = false)
        assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.PREPARED))
        assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_ARMED))
        assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED))
        assertEquals(0, f.signing.createdClients)
        assertEquals(0, f.http.put.createdClients)
        return previous
    }

    private fun foreignProcessRefuses(root: CatalogSignerRotationActivationRoot, previous: CatalogSignerRotationActivationV1) {
        CatalogSignerRotationActivationRoot(f).use { foreign ->
            foreign.prepare()
            assertNotSame(root.process, foreign.process)
            val attempt = foreign.begin()
            val reads = f.http.read.createdClients
            val lease = f.delivery.leaseRow()
            f.core.refused { foreign.continueUnattempted(attempt, previous) }
            foreign.assertReleased(attempt, reserved = false)
            assertTrue(foreign.jdbc.calls.isEmpty(), "A foreign actual process is refused before even snapshot SQL, not by a file lock.")
            assertEquals(reads, f.http.read.createdClients)
            assertEquals(0, f.signing.createdClients)
            assertEquals(0, f.http.put.createdClients)
            assertEquals(lease, f.delivery.leaseRow())
            assertFalse(poolTestField<Boolean>(previous, "continuationConsumed"))
            assertNull(ownedCutField(attempt, "custody"))
        }
    }

    private fun oneShotRefuses(root: CatalogSignerRotationActivationRoot, previous: CatalogSignerRotationActivationV1) {
        val calls = root.jdbc.calls.size
        val reads = f.http.read.createdClients
        val row = f.mutationJson()
        val version = f.rowVersion()
        val leaves = f.snapshotLeaves()
        val next = root.begin()
        f.core.refused { root.continueUnattempted(next, previous) }
        root.assertReleased(next, reserved = false)
        assertNull(ownedCutField(next, "custody"))
        assertEquals(calls, root.jdbc.calls.size)
        assertEquals(reads, f.http.read.createdClients)
        assertEquals(row, f.mutationJson())
        assertEquals(version, f.rowVersion())
        f.assertLeavesUnchanged(leaves)
        observed.oneSignAndPut()
    }

    companion object {
        private val CONTINUATION_LEAVES = setOf(
            CatalogSignerRotationReleaseLeafV1.SIGN_ONE_ARMED,
            CatalogSignerRotationReleaseLeafV1.SIGN_ONE_RETURNED,
            CatalogSignerRotationReleaseLeafV1.SIGNATURE_ONE,
            CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_ARMED,
            CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_PERSISTED,
            CatalogSignerRotationReleaseLeafV1.ENVELOPE,
            CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME,
            CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED,
            CatalogSignerRotationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED,
            CatalogSignerRotationReleaseLeafV1.PRIMARY_COPY,
            CatalogSignerRotationReleaseLeafV1.REPLICA_COPY,
            CatalogSignerRotationReleaseLeafV1.PUBLICATION_DUAL_COPY,
            CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED,
            CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME,
            CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED,
            CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME,
        )
    }
}

/** Read-only captures of the actual predecessor, not a fabricated input/grant or a replacement cleanup receipt. */
private class ActivationUnattemptedObservation(private val original: CatalogSignerRotationActivationV1) {
    private val budget = original.budget
    private val budgetStarted = poolTestField<Long>(budget, "startedAtNanos")
    private val custody = checkNotNull(ownedCutField(original, "custody"))
    private val campaign = poolTestField<CatalogCoordinatorLeaseCampaignV1>(original, "campaign")
    private val window = checkNotNull(ownedCutField(original, "dispatchWindow"))
    private val leaseStarted = poolTestField<Long>(original, "leaseStartedAtNanos")
    private val prepared = poolTestField<CatalogSignerRotationActivationOperationV1>(original, "preparedOperation")
    private val phase = poolTestField<PersistencePhaseContext>(prepared, "phase")

    fun unchanged(consumed: Boolean) {
        assertSame(budget, original.budget)
        assertEquals(budgetStarted, poolTestField<Long>(budget, "startedAtNanos"))
        assertEquals(30_000_000_000L, poolTestField<Long>(budget, "allowanceNanos"))
        assertSame(custody, ownedCutField(original, "custody"))
        assertSame(campaign, ownedCutField(original, "campaign"))
        assertSame(window, ownedCutField(original, "dispatchWindow"))
        assertEquals(leaseStarted, poolTestField<Long>(original, "leaseStartedAtNanos"))
        assertNull(poolTestField<AtomicReference<Any?>>(campaign, "window").get())
        assertNotSame(campaign, poolTestField<AtomicReference<Any?>>(campaign.custody, "active").get())
        assertSame(prepared, ownedCutField(original, "preparedOperation"))
        assertSame(prepared.observation, ownedCutField(original, "retainedHistory"))
        assertEquals(PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PREPARE, poolTestField<PersistencePhasePath>(phase, "path"))
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        assertTrue(phase.signerRotationActivationCleanupProven(original))
        assertNull(ownedCutField(original, "originalPhase"))
        assertFalse(poolTestField<Boolean>(original, "outcomeUncertain"))
        assertFalse(poolTestField<Boolean>(original, "sqlCleanupUnproven"))
        assertFalse(poolTestField<Boolean>(original, "signArmIssued"))
        assertFalse(poolTestField<Boolean>(original, "signConstructionIssued"))
        assertFalse(poolTestField<Boolean>(original, "signatureSqlIssued"))
        assertEquals(consumed, poolTestField<Boolean>(original, "continuationConsumed"))
        original.requireActualCleanup()
    }
}
