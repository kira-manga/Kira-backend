package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeFailureV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

internal enum class CatalogSignerRotationActivationCommitStage(val step: String, val path: PersistencePhasePath) {
    SIGNATURE("activation-signature", PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_SIGNATURE),
    COMPLETE("activation-head", PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_COMPLETE),
    PROJECT("activation-clear-pending", PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PROJECT),
}

/** Same honest recovery4 fault semantics: actual deferred-constraint rollback/UNKNOWN, or actual COMMITTED afterCommit failure, never simulated TLS ACK loss. */
internal class CatalogSignerRotationActivationFault(
    private val f: CatalogSignerRotationActivationFixture,
    val root: CatalogSignerRotationActivationRoot,
    val original: CatalogSignerRotationActivationV1,
    private val stage: CatalogSignerRotationActivationCommitStage,
    val cut: CatalogSignerRotationColdCommitCut,
) {
    private var reached = false
    private var afterCommitReached = false
    private val originalBudget = original.budget
    private var retainedCustody: Any? = null
    private var retainedPending: Any? = null
    private var retainedLeaseStart: Long? = null
    lateinit var phase: PersistencePhaseContext
        private set

    fun interrupt(action: () -> Unit) {
        root.jdbc.afterSql = { step -> if (step == stage.step) installFault() }
        try {
            val refusal = f.core.refused(action)
            if (cut === CatalogSignerRotationColdCommitCut.UNKNOWN_ROLLBACK) {
                assertEquals(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN, refusal.code)
            }
        } finally {
            root.jdbc.afterSql = {}
        }
        assertTrue(reached)
        assertSame(phase, root.phases.last())
        assertEquals(cut === CatalogSignerRotationColdCommitCut.COMMITTED_AFTER_COMMIT, afterCommitReached)
        retainedCustody = checkNotNull(ownedCutField(original, "custody"))
        retainedPending = ownedCutField(original, "pending")
        retainedLeaseStart = poolTestField<Long>(original, "leaseStartedAtNanos")
        assertOriginalOutcome()
        if (cut === CatalogSignerRotationColdCommitCut.UNKNOWN_ROLLBACK) {
            val calls = root.jdbc.calls.size
            val reads = f.http.read.createdClients
            val signs = f.signing.createdClients
            val puts = f.http.put.createdClients
            f.core.refused { root.recover(root.begin()) }
            // A genuine UNKNOWN and already-armed original cannot issue a continuation witness on its still-live retained root.
            f.core.refused { root.continueUnattempted(root.begin(), original) }
            assertFalse(poolTestField<Boolean>(original, "continuationConsumed"))
            assertSame(original, root.active())
            assertSame(phase, ownedCutField(original, "originalPhase"))
            assertEquals(PersistenceDatabaseOutcome.UNKNOWN, phase.databaseOutcome())
            assertEquals(calls, root.jdbc.calls.size)
            assertEquals(reads, f.http.read.createdClients)
            assertEquals(signs, f.signing.createdClients)
            assertEquals(puts, f.http.put.createdClients)
        }
    }

    private fun installFault() {
        assertFalse(reached)
        phase = checkNotNull(PersistencePhaseOwnership.current())
        assertEquals(stage.path, poolTestField<PersistencePhasePath>(phase, "path"))
        val resources = TransactionSynchronizationManager.getResourceMap()
        assertEquals(setOf(root.coordinator.dataSource), resources.keys)
        when (cut) {
            CatalogSignerRotationColdCommitCut.UNKNOWN_ROLLBACK -> {
                val owned = JdbcTemplate(root.coordinator.dataSource)
                owned.execute("CREATE TEMP TABLE kira_activation3_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                assertEquals(2, owned.update("INSERT INTO kira_activation3_commit_cut VALUES (1), (1)"))
            }

            CatalogSignerRotationColdCommitCut.COMMITTED_AFTER_COMMIT -> {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        afterCommitReached = true
                        error("Synthetic activation3 committed completion-tail failure.")
                    }
                })
            }
        }
        assertSame(phase, PersistencePhaseOwnership.current())
        assertEquals(resources.keys, TransactionSynchronizationManager.getResourceMap().keys)
        assertSame(resources.getValue(root.coordinator.dataSource), TransactionSynchronizationManager.getResource(root.coordinator.dataSource))
        reached = true
    }

    /** Fresh successful authority must not settle this original phase, slot, budget, custody or already-spent pending grant. */
    fun assertOriginalOutcome() {
        assertSame(originalBudget, original.budget)
        assertSame(retainedCustody, ownedCutField(original, "custody"))
        assertEquals(retainedLeaseStart, poolTestField<Long>(original, "leaseStartedAtNanos"))
        assertSame(retainedPending, ownedCutField(original, "pending"))
        assertTrue(poolTestField<Boolean>(original, "signArmed"))
        assertTrue(poolTestField<Boolean>(original, "signatureSqlIssued"))
        assertFalse(poolTestField<Boolean>(original, "continuationConsumed"))
        when (stage) {
            CatalogSignerRotationActivationCommitStage.SIGNATURE -> {
                assertNull(ownedCutField(original, "signatureOperation"))
                assertFalse(poolTestField<Boolean>(original, "completeIssued"))
                assertFalse(poolTestField<Boolean>(original, "putConstructionIssued"))
                assertNull(retainedPending)
            }

            CatalogSignerRotationActivationCommitStage.COMPLETE -> {
                assertTrue(poolTestField<Boolean>(original, "completeIssued"))
                assertNull(ownedCutField(original, "completeOperation"))
                assertNull(retainedPending)
            }

            CatalogSignerRotationActivationCommitStage.PROJECT -> {
                assertTrue(poolTestField<Boolean>(original, "completeIssued"))
                assertTrue(poolTestField<Boolean>(original, "projectIssued"))
                assertNull(ownedCutField(original, "projectOperation"))
                assertTrue(poolTestField<Boolean>(checkNotNull(retainedPending), "spent"))
            }
        }
        if (cut === CatalogSignerRotationColdCommitCut.UNKNOWN_ROLLBACK) {
            assertSame(phase, ownedCutField(original, "originalPhase"))
            assertEquals(PersistenceDatabaseOutcome.UNKNOWN, phase.databaseOutcome())
            assertTrue(poolTestField<Boolean>(original, "outcomeUncertain"))
            assertSame(original, root.active())
            assertFalse(poolTestField<Boolean>(original, "released"))
            assertFalse(poolTestField<Boolean>(original, "cleanupProven"))
            assertEquals(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN, f.core.refused(original::close).code)
        } else {
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertFalse(poolTestField<Boolean>(original, "outcomeUncertain"))
            assertNull(ownedCutField(original, "originalPhase"))
            root.assertReleased(original)
        }
        val calls = root.jdbc.calls.size
        val reads = f.http.read.createdClients
        val signs = f.signing.createdClients
        val puts = f.http.put.createdClients
        f.core.refused { root.recover(original) }
        f.core.refused { root.project(original) }
        assertEquals(calls, root.jdbc.calls.size)
        assertEquals(reads, f.http.read.createdClients)
        assertEquals(signs, f.signing.createdClients)
        assertEquals(puts, f.http.put.createdClients)
    }
}
