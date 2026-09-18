package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationDeliveryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeFailureV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/** The committed tail cut is deliberately NOT named UNKNOWN: no TLS COMMIT-response-loss transport is synthesized. */
internal enum class CatalogSignerRotationColdCommitCut { UNKNOWN_ROLLBACK, COMMITTED_AFTER_COMMIT }

internal enum class CatalogSignerRotationColdCommitStage(val step: String, val path: PersistencePhasePath) {
    COMPLETE("final-head", PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_COMPLETE),
    PROJECT("final-clear-pending", PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PROJECT),
}

/** Faults execute only on the original real TLS datasource/holder, never by replacing a phase, resource map or outcome. */
internal class CatalogSignerRotationColdRecoveryFault(
    private val f: CatalogSignerRotationDeliveryFixture,
    val root: CatalogSignerRotationDeliveryRoot,
    val original: CatalogSignerRotationDeliveryV1,
    private val stage: CatalogSignerRotationColdCommitStage,
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
            val refused = f.core.refused(action)
            if (cut === CatalogSignerRotationColdCommitCut.UNKNOWN_ROLLBACK) {
                assertEquals(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN, refused.code)
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
            // Before physical retirement, even a genuinely new operation on this same live root cannot take its retained UNKNOWN slot.
            val calls = root.jdbc.calls.size
            val reads = f.http.read.createdClients
            val puts = f.http.put.createdClients
            f.core.refused { root.recover(root.begin()) }
            assertSame(original, root.active())
            assertSame(phase, ownedCutField(original, "originalPhase"))
            assertEquals(PersistenceDatabaseOutcome.UNKNOWN, phase.databaseOutcome())
            assertEquals(calls, root.jdbc.calls.size)
            assertEquals(reads, f.http.read.createdClients)
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
                owned.execute("CREATE TEMP TABLE kira_overlap2_cold_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                assertEquals(2, owned.update("INSERT INTO kira_overlap2_cold_commit VALUES (1), (1)"))
            }

            CatalogSignerRotationColdCommitCut.COMMITTED_AFTER_COMMIT -> {
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        afterCommitReached = true // Actual callback after a successful DB commit, not an assigned phase outcome.
                        error("Synthetic fixed overlap2 committed completion-tail failure.")
                    }
                })
            }
        }
        assertSame(phase, PersistencePhaseOwnership.current())
        assertEquals(resources.keys, TransactionSynchronizationManager.getResourceMap().keys)
        assertSame(
            resources.getValue(root.coordinator.dataSource),
            TransactionSynchronizationManager.getResource(root.coordinator.dataSource),
        )
        reached = true
    }

    /** Recheck after a replacement root succeeds: its new authority must never settle or revive this original UNKNOWN owner. */
    fun assertOriginalOutcome() {
        assertSame(originalBudget, original.budget)
        assertSame(retainedCustody, ownedCutField(original, "custody"))
        assertEquals(retainedLeaseStart, poolTestField<Long>(original, "leaseStartedAtNanos"))
        assertSame(retainedPending, ownedCutField(original, "pending"))
        assertTrue(poolTestField<Boolean>(original, "completeIssued"))
        if (stage === CatalogSignerRotationColdCommitStage.PROJECT) {
            assertTrue(poolTestField<Boolean>(original, "projectIssued"))
            assertTrue(poolTestField<Boolean>(checkNotNull(retainedPending), "spent"))
        } else {
            assertNull(retainedPending)
            assertNull(ownedCutField(original, "completeOperation"))
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
        val puts = f.http.put.createdClients
        f.core.refused { root.recover(original) }
        f.core.refused { root.project(original) }
        assertEquals(calls, root.jdbc.calls.size)
        assertEquals(reads, f.http.read.createdClients)
        assertEquals(puts, f.http.put.createdClients)
        f.assertNoFurtherSign()
    }
}
