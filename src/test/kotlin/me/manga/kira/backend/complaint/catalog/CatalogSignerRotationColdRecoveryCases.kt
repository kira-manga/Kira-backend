package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationDeliveryStateV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationDeliveryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import java.sql.Timestamp

/** Fresh authority comes only from another actual TARGET root, raw reads and released SQL, never these diagnostic observations. */
internal class CatalogSignerRotationColdRecoveryCases(private val f: CatalogSignerRotationDeliveryFixture) {
    private val observed = CatalogSignerRotationDeliveryAssertions(f)
    private val cold = CatalogSignerRotationColdRecoveryAssertions(f)
    private val sql = CatalogSignerRotationColdRecoverySql(f)

    fun knownPendingNeedsFreshLease() {
        val (oldRoot, oldOriginal) = retiredKnownPrefix()
        livePendingRefuses()
        recoverAndFinish(oldRoot, oldOriginal, CatalogSignerRotationColdState.PENDING)
        assertTrue(f.freeze.complete(CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME))
        assertTrue(f.freeze.complete(CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME))
    }

    fun completeArmReconcilesActualState(cut: CatalogSignerRotationColdCommitCut) {
        val fault = interrupted(CatalogSignerRotationColdCommitStage.COMPLETE, cut)
        val state = if (cut === CatalogSignerRotationColdCommitCut.UNKNOWN_ROLLBACK) {
            CatalogSignerRotationColdState.PREPARED
        } else {
            CatalogSignerRotationColdState.PENDING
        }
        recoverAndFinish(fault.root, fault.original, state)
        // Even a new known COMPLETE cannot fill the gap belonging to the old arm's different lease.
        assertFalse(f.freeze.exists(CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME))
        assertTrue(f.freeze.complete(CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME))
        fault.assertOriginalOutcome()
    }

    fun projectArmReconcilesActualState(cut: CatalogSignerRotationColdCommitCut) {
        val fault = interrupted(CatalogSignerRotationColdCommitStage.PROJECT, cut)
        val state = if (cut === CatalogSignerRotationColdCommitCut.UNKNOWN_ROLLBACK) {
            CatalogSignerRotationColdState.PENDING
        } else {
            CatalogSignerRotationColdState.PROJECTED
        }
        recoverAndFinish(fault.root, fault.original, state)
        assertTrue(f.freeze.complete(CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME))
        assertFalse(f.freeze.exists(CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME))
        fault.assertOriginalOutcome()
    }

    /** Reused only to create genuine pending/projected negative prefixes; neither returned reference is supplied as authority. */
    fun retiredKnownPrefix(projected: Boolean = false): Pair<CatalogSignerRotationDeliveryRoot, CatalogSignerRotationDeliveryV1> {
        f.awaitActualLeaseExpiry()
        f.http.replicateOnPut = true
        lateinit var prior: Pair<CatalogSignerRotationDeliveryRoot, CatalogSignerRotationDeliveryV1>
        lateinit var lease: Map<String, Any?>
        CatalogSignerRotationDeliveryRoot(f).use { root ->
            root.prepare()
            sql.observeProviderSeparation(root)
            val original = root.begin()
            assertEquals(CatalogSignerRotationDeliveryStateV1.PROJECTION_PENDING, root.publish(original).state)
            val completed = observed.pendingHead2()
            observed.pendingOwner(original)
            observed.acquisitionRecord(CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED, "complete-armed")
            observed.acquisitionRecord(
                CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME,
                "completed2-pending2",
                head2 = true,
                times = listOf(completed),
            )
            if (projected) {
                assertEquals(CatalogSignerRotationDeliveryStateV1.PROJECTED, root.project(original).state)
                observed.projectedHead2(completed)
                observed.acquisitionRecord(
                    CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME,
                    "projected2",
                    head2 = true,
                    times = listOf(completed, f.freeze.row()["projected_at"] as Timestamp),
                )
            }
            observed.completedPhases(root, original)
            observed.bindings(root)
            observed.singlePut()
            lease = f.leaseRow()
            original.close()
            root.assertReleased(original)
            prior = root to original
        }
        assertTrue(prior.first.cleanupVerified)
        assertEquals(lease, f.leaseRow(), "Physical retirement is not lease relinquishment or expiration.")
        return prior
    }

    private fun livePendingRefuses() {
        val state = f.freeze.state()
        val lease = f.leaseRow()
        val leaves = f.freeze.snapshotLeaves()
        CatalogSignerRotationDeliveryRoot(f).use { root ->
            root.prepare()
            sql.observeProviderSeparation(root)
            val original = root.begin()
            assertTrue(f.databaseNow().isBefore((lease["lease_expires_at"] as Timestamp).toInstant()))
            f.core.refused { root.recover(original) }
            root.assertReleased(original)
            assertEquals(1, root.jdbc.steps.count { it == "pending-lease-acquire" }, "Reach the genuine live pending-lease CAS refusal.")
            assertTrue(root.jdbc.steps.none { it.startsWith("final-") || it == "lease-acquire" })
            sql.effectCounts(root, completes = 0, projects = 0)
            assertEquals(state, f.freeze.state())
            assertEquals(lease, f.leaseRow())
            f.freeze.assertLeavesUnchanged(leaves)
            observed.singlePut()
            observed.preserved()
        }
    }

    private fun interrupted(
        stage: CatalogSignerRotationColdCommitStage,
        cut: CatalogSignerRotationColdCommitCut,
    ): CatalogSignerRotationColdRecoveryFault {
        f.awaitActualLeaseExpiry()
        f.http.replicateOnPut = true
        lateinit var fault: CatalogSignerRotationColdRecoveryFault
        lateinit var lease: Map<String, Any?>
        CatalogSignerRotationDeliveryRoot(f).use { root ->
            root.prepare()
            sql.observeProviderSeparation(root)
            val original = root.begin()
            fault = CatalogSignerRotationColdRecoveryFault(f, root, original, stage, cut)
            if (stage === CatalogSignerRotationColdCommitStage.COMPLETE) {
                fault.interrupt { root.publish(original) }
                if (cut === CatalogSignerRotationColdCommitCut.UNKNOWN_ROLLBACK) {
                    observed.preparedHead1()
                } else {
                    observed.pendingHead2()
                }
                assertNull(ownedCutField(original, "pending"))
                assertFalse(f.freeze.exists(CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME))
                assertFalse(f.freeze.exists(CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED))
                sql.effectCounts(root, completes = 1, projects = 0) // An attempted statement is not a claimed committed effect.
            } else {
                assertEquals(CatalogSignerRotationDeliveryStateV1.PROJECTION_PENDING, root.publish(original).state)
                val completed = observed.pendingHead2()
                observed.acquisitionRecord(
                    CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME,
                    "completed2-pending2",
                    head2 = true,
                    times = listOf(completed),
                )
                val held = cold.pending(original, actualComplete = true)
                val before = cold.mutationJson()
                val xmin = f.core.mutationRowVersion()
                fault.interrupt { root.project(original) }
                assertSame(held, ownedCutField(original, "pending"))
                assertTrue(poolTestField<Boolean>(held, "spent"))
                if (cut === CatalogSignerRotationColdCommitCut.UNKNOWN_ROLLBACK) {
                    assertEquals(before, cold.mutationJson())
                    assertEquals(xmin, f.core.mutationRowVersion())
                    assertEquals(completed, observed.pendingHead2())
                } else {
                    observed.projectedHead2(completed)
                }
                observed.acquisitionRecord(
                    CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED,
                    "project-armed",
                    head2 = true,
                    times = listOf(completed),
                )
                assertFalse(f.freeze.exists(CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME))
                sql.effectCounts(root, completes = 1, projects = 1)
            }
            observed.acquisitionRecord(CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED, "complete-armed")
            observed.singlePut()
            observed.preserved()
            lease = f.leaseRow()
        }
        assertTrue(fault.root.cleanupVerified)
        assertEquals(lease, f.leaseRow())
        fault.assertOriginalOutcome()
        return fault
    }

    private fun recoverAndFinish(
        oldRoot: CatalogSignerRotationDeliveryRoot,
        oldOriginal: CatalogSignerRotationDeliveryV1,
        state: CatalogSignerRotationColdState,
    ) {
        val before = f.leaseRow()
        val leaves = f.freeze.snapshotLeaves()
        val row = cold.mutationJson()
        val xmin = f.core.mutationRowVersion()
        val priorCompleted = f.freeze.row()["completed_at"] as Timestamp?
        f.awaitActualLeaseExpiry() // No operation budget is started while waiting for the unchanged server lease.
        CatalogSignerRotationDeliveryRoot(f).use { root ->
            root.prepare()
            sql.observeProviderSeparation(root)
            val original = root.begin()
            val lower = f.databaseNow()
            val result = root.recover(original)
            val upper = f.databaseNow()
            cold.newOwner(root, original, oldRoot, oldOriginal)
            sql.acquired(root, before, lower, upper, state)
            cold.raw(original, state)
            sql.healthyPhases(root, original)
            cold.onlyAppended(leaves, emptySet()) // No old arm/outcome is repaired by either fresh read or retried COMPLETE.
            val actualComplete = state === CatalogSignerRotationColdState.PREPARED
            sql.effectCounts(root, completes = if (actualComplete) 1 else 0, projects = 0)
            if (!actualComplete) {
                assertEquals(row, cold.mutationJson())
                assertEquals(xmin, f.core.mutationRowVersion(), "Cold observation must not rewrite even an identical mutation row.")
            }
            if (state === CatalogSignerRotationColdState.PROJECTED) {
                assertEquals(CatalogSignerRotationDeliveryStateV1.PROJECTED, result.state)
                root.assertReleased(original)
                observed.projectedHead2(checkNotNull(priorCompleted))
                spentOwnerRefuses(root, original)
            } else {
                assertEquals(CatalogSignerRotationDeliveryStateV1.PROJECTION_PENDING, result.state)
                val completed = observed.pendingHead2()
                if (actualComplete) {
                    assertFalse(completed.toInstant().isBefore(lower) || completed.toInstant().isAfter(upper))
                } else {
                    assertEquals(priorCompleted, completed)
                }
                cold.pending(original, actualComplete)
                projectOnce(root, original, completed, actualComplete)
            }
            root.assertPurposeClosed()
            observed.singlePut()
            observed.preserved()
        }
    }

    private fun projectOnce(
        root: CatalogSignerRotationDeliveryRoot,
        original: CatalogSignerRotationDeliveryV1,
        completed: Timestamp,
        actualComplete: Boolean,
    ) {
        val leaves = f.freeze.snapshotLeaves()
        val createRecords = !f.freeze.exists(CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED)
        val allowance = original.budget
        val custody = ownedCutField(original, "custody")
        val held = cold.pending(original, actualComplete)
        val calls = root.jdbc.calls.size
        val reads = f.http.read.requests.size
        val phases = root.phases.size
        f.core.refused { root.recover(root.begin()) }
        f.core.refused { root.project(root.begin()) }
        assertSame(original, root.active())
        assertEquals(calls, root.jdbc.calls.size)
        assertEquals(reads, f.http.read.requests.size)
        assertSame(held, cold.pending(original, actualComplete))
        assertEquals(CatalogSignerRotationDeliveryStateV1.PROJECTED, root.project(original).state)
        assertSame(allowance, original.budget)
        assertSame(custody, ownedCutField(original, "custody"))
        assertSame(held, ownedCutField(original, "pending"))
        assertTrue(poolTestField<Boolean>(held, "spent"))
        assertEquals(phases + 1, root.phases.size)
        assertEquals(PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PROJECT, root.jdbc.calls.last().path)
        assertEquals(reads, f.http.read.requests.size, "Separate PROJECT consumes its exact private pending grant, not another provider round.")
        root.assertReleased(original)
        observed.projectedHead2(completed)
        sql.healthyPhases(root, original)
        sql.effectCounts(root, completes = if (actualComplete) 1 else 0, projects = 1)
        val appended = if (createRecords) {
            observed.acquisitionRecord(CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED, "project-armed", head2 = true, times = listOf(completed))
            observed.acquisitionRecord(
                CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME,
                "projected2",
                head2 = true,
                times = listOf(completed, f.freeze.row()["projected_at"] as Timestamp),
            )
            setOf(CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED, CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME)
        } else {
            assertFalse(f.freeze.exists(CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME))
            emptySet()
        }
        cold.onlyAppended(leaves, appended)
        spentOwnerRefuses(root, original)
    }

    private fun spentOwnerRefuses(root: CatalogSignerRotationDeliveryRoot, original: CatalogSignerRotationDeliveryV1) {
        val calls = root.jdbc.calls.size
        val reads = f.http.read.createdClients
        val leaves = f.freeze.snapshotLeaves()
        f.core.refused { root.recover(original) }
        f.core.refused { root.project(original) }
        assertEquals(calls, root.jdbc.calls.size)
        assertEquals(reads, f.http.read.createdClients)
        assertNull(root.active())
        f.freeze.assertLeavesUnchanged(leaves)
        observed.singlePut()
    }
}
