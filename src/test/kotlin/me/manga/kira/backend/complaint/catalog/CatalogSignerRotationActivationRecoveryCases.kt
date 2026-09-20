package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier.Activation3Readback.State
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationStateV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import java.sql.Timestamp

/** Cold actual-state reconciliation, with old acquisition-bound gaps immutable and no Sign/PUT construction. */
internal class CatalogSignerRotationActivationRecoveryCases(private val f: CatalogSignerRotationActivationFixture) {
    private val observed = CatalogSignerRotationActivationAssertions(f)
    private val sql = CatalogSignerRotationActivationSql(f)
    private val ownership = CatalogSignerRotationActivationOwnership(f)
    private val flow = CatalogSignerRotationActivationCases(f)

    fun completeOrProjectGap(stage: CatalogSignerRotationActivationCommitStage, cut: CatalogSignerRotationColdCommitCut) {
        check(stage !== CatalogSignerRotationActivationCommitStage.SIGNATURE)
        val fault = interruptFinalization(stage, cut)
        val state = when {
            stage === CatalogSignerRotationActivationCommitStage.COMPLETE && cut === CatalogSignerRotationColdCommitCut.UNKNOWN_ROLLBACK ->
                ActualState.PREPARED

            stage === CatalogSignerRotationActivationCommitStage.PROJECT && cut === CatalogSignerRotationColdCommitCut.COMMITTED_AFTER_COMMIT ->
                ActualState.PROJECTED

            else -> ActualState.PENDING
        }
        val row = f.mutationJson()
        val version = f.rowVersion()
        val leaves = f.snapshotLeaves()
        val priorCompleted = f.row()["completed_at"] as Timestamp?
        val previousLease = f.delivery.leaseRow()
        f.delivery.awaitActualLeaseExpiry()
        CatalogSignerRotationActivationRoot(f).use { root ->
            root.prepare()
            val original = root.begin()
            val lower = f.delivery.databaseNow()
            val result = root.recover(original)
            val upper = f.delivery.databaseNow()
            ownership.fresh(root, original, fault.root, fault.original)
            val rawState = when (state) {
                ActualState.PREPARED -> State.PREPARED_DUAL_COPY
                ActualState.PENDING -> State.PROJECTION_PENDING_DUAL_COPY
                ActualState.PROJECTED -> State.PROJECTED3
            }
            ownership.raw(original, rawState)
            sql.acquired(
                root,
                original,
                previousLease,
                lower,
                upper,
                head3 = state !== ActualState.PREPARED,
                pending = state === ActualState.PENDING,
            )
            sql.healthy(root, original, initiallySigned = true)
            val needsComplete = state === ActualState.PREPARED
            sql.effects(root, original, prepares = 0, signatures = 0, completes = if (needsComplete) 1 else 0, projects = 0)
            f.assertLeavesUnchanged(leaves) // No new acquisition can fill the old arm's historical outcome gap.
            if (!needsComplete) {
                assertEquals(row, f.mutationJson())
                assertEquals(version, f.rowVersion(), "Read-only reconciliation does not rewrite an identical operation3 row.")
            }
            if (state === ActualState.PROJECTED) {
                assertEquals(CatalogSignerRotationActivationStateV1.PROJECTED, result.state)
                root.assertReleased(original)
                assertNull(ownedCutField(original, "pending"))
                assertNull(ownedCutField(original, "completeOperation"))
                assertNull(ownedCutField(original, "projectOperation"))
                observed.projectedHead3(checkNotNull(priorCompleted))
                flow.spentOwnerRefuses(root, original)
            } else {
                assertEquals(CatalogSignerRotationActivationStateV1.PROJECTION_PENDING, result.state)
                val completed = observed.pendingHead3()
                if (needsComplete) {
                    assertFalse(completed.toInstant().isBefore(lower) || completed.toInstant().isAfter(upper))
                } else {
                    assertEquals(priorCompleted, completed)
                }
                flow.projectOnce(root, original, actualComplete = needsComplete)
            }
            if (stage === CatalogSignerRotationActivationCommitStage.COMPLETE) {
                assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME))
                assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME))
            } else {
                assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME))
                assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME))
            }
            observed.oneSignAndPut()
            observed.preserved()
            fault.assertOriginalOutcome()
        }
    }

    /** One retained-signature/SQL-UNKNOWN case, not a lost-Sign matrix or a new publication capability. */
    fun retainedSignaturePersistsWithoutAnotherSignOrPut() {
        f.delivery.awaitActualLeaseExpiry()
        lateinit var fault: CatalogSignerRotationActivationFault
        lateinit var lease: Map<String, Any?>
        CatalogSignerRotationActivationRoot(f).use { root ->
            root.prepare()
            val original = root.begin()
            fault = CatalogSignerRotationActivationFault(
                f,
                root,
                original,
                CatalogSignerRotationActivationCommitStage.SIGNATURE,
                CatalogSignerRotationColdCommitCut.UNKNOWN_ROLLBACK,
            )
            fault.interrupt { root.activate(original) }
            observed.prepared(signed = false)
            observed.oneSignAndPut(puts = 0)
            sql.effects(root, original, prepares = 1, signatures = 1, completes = 0, projects = 0)
            val retained = listOf(
                CatalogSignerRotationReleaseLeafV1.SIGN_ONE_RETURNED,
                CatalogSignerRotationReleaseLeafV1.SIGNATURE_ONE,
                CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_ARMED,
                CatalogSignerRotationReleaseLeafV1.ENVELOPE,
            )
            retained.forEach { leaf -> assertTrue(f.complete(leaf), leaf.name) }
            assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_PERSISTED))
            assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME))
            assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED))
            lease = f.delivery.leaseRow()
        }
        assertTrue(fault.root.cleanupVerified)
        assertEquals(lease, f.delivery.leaseRow())
        val leaves = f.snapshotLeaves()
        val returned = f.read(CatalogSignerRotationReleaseLeafV1.ENVELOPE)
        f.delivery.awaitActualLeaseExpiry()
        CatalogSignerRotationActivationRoot(f).use { root ->
            root.prepare()
            val original = root.begin()
            val lower = f.delivery.databaseNow()
            assertEquals(CatalogSignerRotationActivationStateV1.SIGNED_UNPUBLISHED, root.recover(original).state)
            val upper = f.delivery.databaseNow()
            root.assertReleased(original)
            ownership.fresh(root, original, fault.root, fault.original)
            ownership.raw(original, State.PREPARED_UNSIGNED)
            sql.acquired(root, original, lease, lower, upper)
            sql.effects(root, original, prepares = 0, signatures = 1, completes = 0, projects = 0)
            sql.healthy(root, original, initiallySigned = false)
            observed.prepared(signed = true)
            assertArrayEquals(returned, observed.signedBytes())
            assertArrayEquals(
                f.read(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_ARMED),
                f.read(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_PERSISTED),
            )
            f.assertLeavesUnchanged(
                leaves,
                setOf(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_PERSISTED, CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME),
            )
            assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED))
            observed.oneSignAndPut(puts = 0)
            flow.spentOwnerRefuses(root, original)
            fault.assertOriginalOutcome() // Byte-only signature receipt fill never turns the old UNKNOWN into COMMITTED or releases its slot.
        }
    }

    private fun interruptFinalization(
        stage: CatalogSignerRotationActivationCommitStage,
        cut: CatalogSignerRotationColdCommitCut,
    ): CatalogSignerRotationActivationFault {
        f.delivery.awaitActualLeaseExpiry()
        f.http.replicateOnPut = true
        lateinit var fault: CatalogSignerRotationActivationFault
        lateinit var lease: Map<String, Any?>
        CatalogSignerRotationActivationRoot(f).use { root ->
            root.prepare()
            val original = root.begin()
            fault = CatalogSignerRotationActivationFault(f, root, original, stage, cut)
            if (stage === CatalogSignerRotationActivationCommitStage.COMPLETE) {
                fault.interrupt { root.activate(original) }
                if (cut === CatalogSignerRotationColdCommitCut.UNKNOWN_ROLLBACK) {
                    observed.prepared(signed = true)
                } else {
                    observed.pendingHead3()
                }
                assertNull(ownedCutField(original, "pending"))
                assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME))
                assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED))
                sql.effects(root, original, prepares = 1, signatures = 1, completes = 1, projects = 0)
            } else {
                assertEquals(CatalogSignerRotationActivationStateV1.PROJECTION_PENDING, root.activate(original).state)
                val completed = observed.pendingHead3()
                val pending = ownership.pending(original, actualComplete = true)
                val row = f.mutationJson()
                val version = f.rowVersion()
                fault.interrupt { root.project(original) }
                assertSame(pending, ownedCutField(original, "pending"))
                assertTrue(poolTestField<Boolean>(pending, "spent"))
                if (cut === CatalogSignerRotationColdCommitCut.UNKNOWN_ROLLBACK) {
                    assertEquals(row, f.mutationJson())
                    assertEquals(version, f.rowVersion())
                    assertEquals(completed, observed.pendingHead3())
                } else {
                    observed.projectedHead3(completed)
                }
                observed.acquisitionRecord(
                    CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED,
                    "project-armed",
                    head3 = true,
                    times = listOf(completed),
                )
                assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME))
                assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME))
                sql.effects(root, original, prepares = 1, signatures = 1, completes = 1, projects = 1)
            }
            observed.acquisitionRecord(CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED, "complete-armed")
            observed.oneSignAndPut()
            observed.preserved()
            lease = f.delivery.leaseRow()
        }
        assertTrue(fault.root.cleanupVerified)
        assertEquals(lease, f.delivery.leaseRow())
        fault.assertOriginalOutcome()
        return fault
    }

    private enum class ActualState { PREPARED, PENDING, PROJECTED }
}
