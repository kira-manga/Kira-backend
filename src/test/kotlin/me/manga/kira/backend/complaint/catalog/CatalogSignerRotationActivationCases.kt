package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier.Activation3Readback.State
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationStateV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.IOException
import java.nio.file.Files
import java.sql.Timestamp

/** One actual new-key producer and bounded recovery continuations, using only the existing carrier/provider seams. */
internal class CatalogSignerRotationActivationCases(private val f: CatalogSignerRotationActivationFixture) {
    private val observed = CatalogSignerRotationActivationAssertions(f)
    private val sql = CatalogSignerRotationActivationSql(f)
    private val ownership = CatalogSignerRotationActivationOwnership(f)

    fun producesFromProjectedOverlap() {
        liveHistoricalLeaseRefuses()
        f.delivery.awaitActualLeaseExpiry()
        f.http.replicateOnPut = true
        CatalogSignerRotationActivationRoot(f).use { root ->
            root.prepare()
            val original = root.begin()
            val before = f.delivery.leaseRow()
            f.beforeSign = {
                assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.PREPARED))
                assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_ARMED))
                observed.prepared(signed = false)
                sql.effects(root, original, prepares = 1, signatures = 0, completes = 0, projects = 0)
                sql.healthy(root, original, initiallySigned = false)
            }
            f.http.beforePut = {
                observed.prepared(signed = true)
                assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_PERSISTED))
                assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME))
                assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED))
                sql.effects(root, original, prepares = 1, signatures = 1, completes = 0, projects = 0)
            }
            val lower = f.delivery.databaseNow()
            val result = try {
                root.activate(original)
            } finally {
                f.beforeSign = {}
                f.http.beforePut = {}
            }
            val upper = f.delivery.databaseNow()
            assertEquals(CatalogSignerRotationActivationStateV1.PROJECTION_PENDING, result.state)
            val completed = observed.pendingHead3()
            sql.acquired(root, original, before, lower, upper)
            sql.effects(root, original, prepares = 1, signatures = 1, completes = 1, projects = 0)
            sql.healthy(root, original, initiallySigned = false)
            ownership.raw(original, State.PREPARED_DUAL_COPY)
            observed.acquisitionRecord(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED, "publication-armed")
            observed.acquisitionRecord(CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED, "complete-armed")
            observed.acquisitionRecord(
                CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME, "completed3-pending3", head3 = true, times = listOf(completed),
            )
            projectOnce(root, original, actualComplete = true, prepares = 1, signatures = 1, initiallySigned = false)
            observed.oneSignAndPut()
        }
    }

    fun lostAcknowledgementAndLag() {
        f.delivery.awaitActualLeaseExpiry()
        val published = publishWithLostAcknowledgement()
        val armed = f.snapshotLeaves()
        f.delivery.awaitActualLeaseExpiry()
        val lag = recoverPrimaryOnly(published)
        f.assertLeavesUnchanged(armed, setOf(CatalogSignerRotationReleaseLeafV1.PUBLICATION_AWAIT_REPLICATION))
        val beforeDual = f.snapshotLeaves()
        f.delivery.awaitActualLeaseExpiry()
        f.http.completeReplication() // Only the actual accepted PUT3 body is copied; no preseeded third object.
        CatalogSignerRotationActivationRoot(f).use { root ->
            root.prepare()
            val original = root.begin()
            val before = f.delivery.leaseRow()
            val lower = f.delivery.databaseNow()
            assertEquals(CatalogSignerRotationActivationStateV1.PROJECTION_PENDING, root.recover(original).state)
            val upper = f.delivery.databaseNow()
            ownership.fresh(root, original, lag.first, lag.second)
            ownership.raw(original, State.PREPARED_DUAL_COPY)
            sql.acquired(root, original, before, lower, upper)
            sql.effects(root, original, prepares = 0, signatures = 0, completes = 1, projects = 0)
            sql.healthy(root, original, initiallySigned = true)
            f.assertLeavesUnchanged(
                beforeDual,
                setOf(
                    CatalogSignerRotationReleaseLeafV1.PRIMARY_COPY,
                    CatalogSignerRotationReleaseLeafV1.REPLICA_COPY,
                    CatalogSignerRotationReleaseLeafV1.PUBLICATION_DUAL_COPY,
                    CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED,
                    CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME,
                ),
            )
            projectOnce(root, original, actualComplete = true)
            observed.oneSignAndPut()
            assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED))
        }
    }

    private fun liveHistoricalLeaseRefuses() {
        val before = f.freeze.state()
        val lease = f.delivery.leaseRow()
        CatalogSignerRotationActivationRoot(f).use { root ->
            root.prepare()
            val original = root.begin()
            assertTrue(f.delivery.databaseNow().isBefore((lease["lease_expires_at"] as Timestamp).toInstant()))
            f.core.refused { root.activate(original) }
            root.assertReleased(original)
            assertEquals(
                1, root.jdbc.steps.count { it == "lease-acquire" }, "Reach the genuine live historical lease CAS, not a local lock refusal.",
            )
            sql.effects(root, original, prepares = 0, signatures = 0, completes = 0, projects = 0)
            assertEquals(before, f.freeze.state())
            assertEquals(lease, f.delivery.leaseRow())
            assertFalse(Files.exists(f.allocation))
            assertEquals(0, f.signing.createdClients)
            assertEquals(0, f.http.put.createdClients)
            observed.head2(allocated = false)
        }
    }

    private fun publishWithLostAcknowledgement(): Pair<CatalogSignerRotationActivationRoot, CatalogSignerRotationActivationV1> {
        lateinit var previous: Pair<CatalogSignerRotationActivationRoot, CatalogSignerRotationActivationV1>
        lateinit var lease: Map<String, Any?>
        CatalogSignerRotationActivationRoot(f).use { root ->
            root.prepare()
            val original = root.begin()
            f.http.afterPutAccepted = { throw IOException("Synthetic activation3 lost raw PUT acknowledgement.") }
            try {
                f.core.refused { root.activate(original) }
            } finally {
                f.http.afterPutAccepted = {}
            }
            root.assertReleased(original)
            sql.effects(root, original, prepares = 1, signatures = 1, completes = 0, projects = 0)
            sql.healthy(root, original, initiallySigned = false)
            observed.prepared(signed = true)
            observed.oneSignAndPut()
            assertTrue(f.complete(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED))
            assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED))
            assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED))
            assertEquals(CatalogGenesisPublishHttpFixture.ACTIVATION3_VERSION, f.http.primaryVersion)
            assertNull(f.http.replicaVersion)
            assertEquals(0, f.http.put.replies.single().closes, "The lost ACK returned no response stream.")
            spentOwnerRefuses(root, original)
            lease = f.delivery.leaseRow()
            previous = root to original
        }
        assertTrue(previous.first.cleanupVerified)
        assertEquals(lease, f.delivery.leaseRow(), "Original physical retirement does not expire or relinquish its real DB lease.")
        return previous
    }

    private fun recoverPrimaryOnly(
        previous: Pair<CatalogSignerRotationActivationRoot, CatalogSignerRotationActivationV1>,
    ): Pair<CatalogSignerRotationActivationRoot, CatalogSignerRotationActivationV1> {
        lateinit var retained: Pair<CatalogSignerRotationActivationRoot, CatalogSignerRotationActivationV1>
        val row = f.mutationJson()
        val version = f.rowVersion()
        CatalogSignerRotationActivationRoot(f).use { root ->
            root.prepare()
            val original = root.begin()
            val before = f.delivery.leaseRow()
            val lower = f.delivery.databaseNow()
            assertEquals(CatalogSignerRotationActivationStateV1.AWAIT_REPLICATION, root.recover(original).state)
            val upper = f.delivery.databaseNow()
            root.assertReleased(original)
            ownership.fresh(root, original, previous.first, previous.second)
            ownership.raw(original, State.PREPARED_AWAIT_REPLICATION)
            sql.acquired(root, original, before, lower, upper)
            sql.effects(root, original, prepares = 0, signatures = 0, completes = 0, projects = 0)
            sql.healthy(root, original, initiallySigned = true)
            assertEquals(row, f.mutationJson())
            assertEquals(version, f.rowVersion())
            assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED))
            observed.prepared(signed = true)
            observed.oneSignAndPut()
            retained = root to original
        }
        assertTrue(retained.first.cleanupVerified)
        return retained
    }

    internal fun projectOnce(
        root: CatalogSignerRotationActivationRoot,
        original: CatalogSignerRotationActivationV1,
        actualComplete: Boolean,
        prepares: Int = 0,
        signatures: Int = 0,
        initiallySigned: Boolean = true,
    ) {
        val completed = observed.pendingHead3()
        val allowance = original.budget
        val custody = ownedCutField(original, "custody")
        val pending = ownership.pending(original, actualComplete)
        val before = f.snapshotLeaves()
        val createRecords = !f.exists(CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED)
        val calls = root.jdbc.calls.size
        val reads = f.http.read.createdClients
        val phases = root.phases.size
        f.core.refused { root.recover(root.begin()) }
        f.core.refused { root.project(root.begin()) }
        assertSame(original, root.active())
        assertEquals(calls, root.jdbc.calls.size)
        assertEquals(reads, f.http.read.createdClients)
        assertSame(pending, ownership.pending(original, actualComplete))
        assertEquals(CatalogSignerRotationActivationStateV1.PROJECTED, root.project(original).state)
        assertSame(allowance, original.budget)
        assertSame(custody, ownedCutField(original, "custody"))
        assertSame(pending, ownedCutField(original, "pending"))
        assertTrue(poolTestField<Boolean>(pending, "spent"))
        assertEquals(phases + 1, root.phases.size)
        assertEquals(PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PROJECT, root.jdbc.calls.last().path)
        assertEquals(reads, f.http.read.createdClients, "PROJECT uses its exact private pending grant without another provider round.")
        root.assertReleased(original)
        observed.projectedHead3(completed)
        sql.effects(root, original, prepares, signatures, completes = if (actualComplete) 1 else 0, projects = 1)
        sql.healthy(root, original, initiallySigned)
        if (createRecords) {
            observed.acquisitionRecord(
                CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED, "project-armed", head3 = true, times = listOf(completed),
            )
            observed.acquisitionRecord(
                CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME, "projected3", head3 = true,
                times = listOf(completed, f.row()["projected_at"] as Timestamp),
            )
            f.assertLeavesUnchanged(
                before, setOf(CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED, CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME),
            )
        } else {
            assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME))
            f.assertLeavesUnchanged(before)
        }
        spentOwnerRefuses(root, original)
        root.assertPurposeClosed()
    }

    internal fun spentOwnerRefuses(root: CatalogSignerRotationActivationRoot, original: CatalogSignerRotationActivationV1) {
        val calls = root.jdbc.calls.size
        val reads = f.http.read.createdClients
        val signs = f.signing.createdClients
        val puts = f.http.put.createdClients
        val leaves = f.snapshotLeaves()
        f.core.refused { root.recover(original) }
        f.core.refused { root.project(original) }
        // The actual armed/signed original is not an unsigned known-unattempted witness, even on this same retained process.
        f.core.refused { root.continueUnattempted(root.begin(), original) }
        assertFalse(poolTestField<Boolean>(original, "continuationConsumed"))
        assertEquals(calls, root.jdbc.calls.size)
        assertEquals(reads, f.http.read.createdClients)
        assertEquals(signs, f.signing.createdClients)
        assertEquals(puts, f.http.put.createdClients)
        assertNull(root.active())
        f.assertLeavesUnchanged(leaves)
    }
}
