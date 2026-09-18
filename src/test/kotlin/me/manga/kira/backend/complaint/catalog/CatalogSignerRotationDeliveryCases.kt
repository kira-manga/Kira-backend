package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier.Overlap2Readback.State
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationDeliveryStateV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationDeliveryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.IOException
import java.sql.Timestamp

/** Actual initial D7 author -> one conditional SDK PUT -> fresh read-only delivery -> same-owner PROJECT, on the existing carrier. */
internal class CatalogSignerRotationDeliveryCases(private val f: CatalogSignerRotationDeliveryFixture) {
    private val observed = CatalogSignerRotationDeliveryAssertions(f)

    fun lostAcknowledgementLagAndFreshReadOnlyCompletion() {
        f.awaitActualLeaseExpiry()
        publishWithLostAcknowledgement()
        val armed = f.freeze.snapshotLeaves()
        // Local retirement did not relinquish/expire the server lease. Each new owner waits its actual unchanged expiry.
        f.awaitActualLeaseExpiry()
        recoverPrimaryOnly()
        f.freeze.assertLeavesUnchanged(armed, allowAdditionalLeaves = true)
        val lag = f.freeze.snapshotLeaves()
        f.awaitActualLeaseExpiry()
        f.http.completeReplication() // Copies only the bytes accepted by the actual first PUT, never a preseeded G2 object.
        CatalogSignerRotationDeliveryRoot(f).use { fresh ->
            fresh.prepare()
            val original = fresh.begin()
            val before = f.leaseRow()
            val lower = f.databaseNow()
            assertEquals(CatalogSignerRotationDeliveryStateV1.PROJECTION_PENDING, fresh.recover(original).state)
            val upper = f.databaseNow()
            val completed = observed.pendingHead2()
            observed.acquiredLease(fresh, before, lower, upper)
            observed.completedPhases(fresh, original)
            observed.rawReadback(original, State.PREPARED_DUAL_COPY)
            observed.pendingOwner(original)
            observed.acquisitionRecord(CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED, "complete-armed")
            observed.acquisitionRecord(
                CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME,
                "completed2-pending2",
                head2 = true,
                times = listOf(completed),
            )
            assertEquals(1, fresh.jdbc.steps.count { it == "final-complete" })
            assertEquals(0, fresh.jdbc.steps.count { it == "final-project" })
            assertSame(original, fresh.active())
            assertFalse(poolTestField<Boolean>(original, "released"))
            assertFalse(poolTestField<Boolean>(original, "closed"))
            assertNull(ownedCutField(original, "closeFailure"))
            assertTrue(f.freeze.complete(CatalogSignerRotationReleaseLeafV1.PUBLICATION_DUAL_COPY))
            assertTrue(f.freeze.complete(CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED))
            assertTrue(f.freeze.complete(CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME))
            assertFalse(f.freeze.exists(CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED))
            assertFalse(f.freeze.exists(CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME))
            f.freeze.assertLeavesUnchanged(lag, allowAdditionalLeaves = true)
            val pending = f.freeze.snapshotLeaves()
            val retainedBudget = original.budget
            val retainedCustody = ownedCutField(original, "custody")
            val continuation = ownedCutField(original, "pending")
            val calls = fresh.jdbc.calls.size
            val reads = f.http.read.requests.size
            val owners = fresh.phases.size
            // Neither a diagnostic pending result nor another owner receives the original typed pending continuation.
            f.core.refused { fresh.recover(fresh.begin()) }
            f.core.refused { fresh.project(fresh.begin()) }
            assertSame(original, fresh.active())
            assertEquals(calls, fresh.jdbc.calls.size)
            assertEquals(reads, f.http.read.requests.size)
            observed.pendingOwner(original)
            assertEquals(CatalogSignerRotationDeliveryStateV1.PROJECTED, fresh.project(original).state)
            assertSame(retainedBudget, original.budget)
            assertSame(retainedCustody, ownedCutField(original, "custody"))
            assertSame(continuation, ownedCutField(original, "pending"))
            assertTrue(poolTestField<Boolean>(checkNotNull(continuation), "spent"))
            assertEquals(owners + 1, fresh.phases.size)
            assertEquals(PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PROJECT, fresh.jdbc.calls.last().path)
            assertEquals(
                reads,
                f.http.read.requests.size,
                "The private exact pending continuation needs no caller evidence or new provider round.",
            )
            fresh.assertReleased(original)
            observed.projectedHead2(completed)
            observed.completedPhases(fresh, original)
            observed.bindings(fresh)
            observed.singlePut()
            f.freeze.assertLeavesUnchanged(pending, allowAdditionalLeaves = true)
            assertTrue(f.freeze.complete(CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED))
            assertTrue(f.freeze.complete(CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME))
            observed.acquisitionRecord(
                CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED,
                "project-armed",
                head2 = true,
                times = listOf(completed),
            )
            observed.acquisitionRecord(
                CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME,
                "projected2",
                head2 = true,
                times = listOf(completed, f.freeze.row()["projected_at"] as Timestamp),
            )
            assertSpentOwnerCannotDispatch(fresh, original)
            fresh.assertPurposeClosed() // Head2/pending-clear cannot turn this root into ordinary D5, Sign or epoch authority.
        }
    }

    fun rawCopyConflictsCannotCompleteOrReput() {
        f.awaitActualLeaseExpiry()
        val prefix = f.freeze.snapshotLeaves()
        CatalogSignerRotationDeliveryRoot(f).use { publishing ->
            publishing.prepare()
            val original = publishing.begin()
            f.http.replicateOnPut = true
            f.http.afterPutAccepted = { f.http.duplicatePrimary = true }
            try {
                f.core.refused { publishing.publish(original) }
            } finally {
                f.http.afterPutAccepted = {}
            }
            publishing.assertReleased(original)
            assertEquals(CatalogGenesisPublishHttpFixture.OVERLAP2_VERSION, f.http.replicaVersion)
            observed.preparedHead1()
            observed.singlePut()
            assertTrue(f.freeze.complete(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED))
            assertFalse(f.freeze.exists(CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED))
        }
        f.freeze.assertLeavesUnchanged(prefix, allowAdditionalLeaves = true)
        val published = f.freeze.snapshotLeaves()
        val genuinePrimary = f.http.primaryBytes.copyOf()
        val genuineReplica = f.http.replicaBytes.copyOf()
        val genuineVersion = f.http.replicaVersion
        val genuineRetention = f.http.replicaRetention
        // Fault only already-returned raw server metadata/bytes. Recovery never receives a manufactured accepted result.
        CatalogSignerRotationDeliveryRoot(f).use { recovery ->
            recovery.prepare()
            for (cut in CatalogSignerRotationDeliveryCopyCut.entries) {
                f.http.duplicatePrimary = cut === CatalogSignerRotationDeliveryCopyCut.DUPLICATE_VERSION
                f.http.deleteMarkerRole = if (cut === CatalogSignerRotationDeliveryCopyCut.DELETE_MARKER) "REPLICA" else null
                f.http.replicaVersion = if (cut === CatalogSignerRotationDeliveryCopyCut.VERSION_MISMATCH) {
                    "different-copy-version"
                } else {
                    genuineVersion
                }
                f.http.replicaRetention = if (cut === CatalogSignerRotationDeliveryCopyCut.RETENTION_MISMATCH) {
                    genuineRetention + 1
                } else {
                    genuineRetention
                }
                f.http.primaryBytes = genuinePrimary.copyOf()
                f.http.replicaBytes = genuineReplica.copyOf().also {
                    if (cut === CatalogSignerRotationDeliveryCopyCut.BYTE_MISMATCH) it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
                }
                val state = f.freeze.state()
                val start = recovery.jdbc.calls.size
                val reads = f.http.read.requests.size
                val original = recovery.begin()
                f.core.refused { recovery.recover(original) }
                recovery.assertReleased(original)
                assertEquals(state, f.freeze.state(), cut.name)
                assertTrue(f.http.read.requests.size > reads, cut.name)
                assertTrue(recovery.jdbc.calls.drop(start).none { it.step == "lease-acquire" || it.step.startsWith("final-") }, cut.name)
                f.freeze.assertLeavesUnchanged(published)
                observed.preparedHead1()
                observed.singlePut()
            }
        }
        // Do not restore a valid cloud result and claim a completed finalization: these are refusal observations only.
        assertFalse(f.freeze.exists(CatalogSignerRotationReleaseLeafV1.PRIMARY_COPY))
        assertFalse(f.freeze.exists(CatalogSignerRotationReleaseLeafV1.REPLICA_COPY))
        assertFalse(f.freeze.exists(CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME))
    }

    private fun publishWithLostAcknowledgement() {
        CatalogSignerRotationDeliveryRoot(f).use { publishing ->
            publishing.prepare()
            val original = publishing.begin()
            assertNotSame(f.prefix.operator.budget, original.budget)
            val before = f.leaseRow()
            f.http.beforePut = {
                assertSame(original, publishing.active())
                observed.completedPhases(publishing, original)
                observed.rawReadback(original, State.PREPARED_UNPUBLISHED)
                assertTrue(f.freeze.complete(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED))
                observed.acquisitionRecord(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED, "publication-armed")
                assertFalse(f.freeze.exists(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED))
                assertEquals(f.http.read.createdClients, f.http.read.closedClients)
            }
            f.http.afterPutAccepted = { throw IOException("synthetic-private-overlap2-lost-acknowledgement") }
            val lower = f.databaseNow()
            try {
                f.core.refused { publishing.publish(original) }
            } finally {
                f.http.beforePut = {}
                f.http.afterPutAccepted = {}
            }
            val upper = f.databaseNow()
            publishing.assertReleased(original)
            observed.acquiredLease(publishing, before, lower, upper)
            observed.completedPhases(publishing, original)
            observed.preparedHead1()
            observed.singlePut()
            assertEquals(CatalogGenesisPublishHttpFixture.OVERLAP2_VERSION, f.http.primaryVersion)
            assertNull(f.http.replicaVersion)
            assertEquals(0, f.http.put.replies.single().closes, "The deliberately lost ACK never returned a response stream.")
            assertTrue(f.freeze.complete(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED))
            assertFalse(f.freeze.exists(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED))
            assertNoCompletionLeaves()
            assertSpentOwnerCannotDispatch(publishing, original)
        }
    }

    private fun recoverPrimaryOnly() {
        CatalogSignerRotationDeliveryRoot(f).use { recovery ->
            recovery.prepare()
            val original = recovery.begin()
            val before = f.leaseRow()
            val lower = f.databaseNow()
            assertEquals(CatalogSignerRotationDeliveryStateV1.AWAIT_REPLICATION, recovery.recover(original).state)
            val upper = f.databaseNow()
            recovery.assertReleased(original)
            observed.acquiredLease(recovery, before, lower, upper)
            observed.completedPhases(recovery, original)
            observed.rawReadback(original, State.PREPARED_AWAIT_REPLICATION)
            observed.preparedHead1()
            observed.singlePut()
            assertTrue(f.freeze.complete(CatalogSignerRotationReleaseLeafV1.PUBLICATION_AWAIT_REPLICATION))
            assertNoCompletionLeaves()
            assertSpentOwnerCannotDispatch(recovery, original)
        }
    }

    private fun assertSpentOwnerCannotDispatch(root: CatalogSignerRotationDeliveryRoot, original: CatalogSignerRotationDeliveryV1) {
        val calls = root.jdbc.calls.size
        val reads = f.http.read.requests.size
        val puts = f.http.put.createdClients
        val leaves = f.freeze.snapshotLeaves()
        f.core.refused { root.publish(original) }
        f.core.refused { root.recover(original) }
        f.core.refused { root.project(original) }
        assertEquals(calls, root.jdbc.calls.size)
        assertEquals(reads, f.http.read.requests.size)
        assertEquals(puts, f.http.put.createdClients)
        assertNull(root.active())
        f.freeze.assertLeavesUnchanged(leaves)
        f.assertNoFurtherSign()
    }

    private fun assertNoCompletionLeaves() {
        val leaves = listOf(
            CatalogSignerRotationReleaseLeafV1.PRIMARY_COPY,
            CatalogSignerRotationReleaseLeafV1.REPLICA_COPY,
            CatalogSignerRotationReleaseLeafV1.PUBLICATION_DUAL_COPY,
            CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED,
            CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME,
            CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED,
            CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME,
        )
        leaves.forEach { assertFalse(f.freeze.exists(it), it.name) }
    }
}

internal enum class CatalogSignerRotationDeliveryCopyCut { DUPLICATE_VERSION, DELETE_MARKER, VERSION_MISMATCH, RETENTION_MISMATCH, BYTE_MISMATCH }
