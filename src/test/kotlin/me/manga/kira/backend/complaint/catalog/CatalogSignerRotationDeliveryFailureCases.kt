package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredDeploymentJsonV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationDeliveryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.IOException
import java.sql.Timestamp
import java.util.HexFormat
import java.util.UUID

/** Negative faults only on actual owned SQL/native/file routes. No lease-expiry shortcut, transaction replacement or repaired command. */
internal class CatalogSignerRotationDeliveryFailureCases(private val f: CatalogSignerRotationDeliveryFixture) {
    private val observed = CatalogSignerRotationDeliveryAssertions(f)

    fun currentBindingGatesFrozenTupleAndLiveLeaseRefuse() {
        val state = f.freeze.state()
        val leaves = f.freeze.snapshotLeaves()
        CatalogSignerRotationDeliveryRoot(f).use { fresh ->
            fresh.prepare()
            // Live historical authority must not be mistaken for the new one-shot process, even with the exact real signed prefix.
            assertTrue(f.databaseNow().isBefore((f.historicalLease["lease_expires_at"] as Timestamp).toInstant()))
            val live = fresh.begin()
            f.core.refused { fresh.publish(live) }
            fresh.assertReleased(live)
            assertEquals(state, f.freeze.state())
            assertEquals(f.historicalLease, f.leaseRow())
            assertEquals(1, fresh.jdbc.steps.count { it == "lease-acquire" }, "The valid prefix reached the genuine live-lease CAS refusal.")
            assertEquals(1, fresh.jdbc.steps.count { it == "delivery-gates" })
            assertTrue(fresh.jdbc.steps.none { it.startsWith("final-") })
            val control = f.observer.queryForMap(
                "SELECT * FROM complaint_journal_control WHERE data_scope_id = ?",
                ComplaintDataScope.LIVE.id,
            )
            val other = UUID.fromString("99999999-9999-4999-8999-999999999999")
            val faults = linkedMapOf<String, Any>(
                "desired_generation" to 2L,
                "desired_configuration_hash" to ByteArray(32) { 31 },
                "database_identity" to other,
                "restore_identity" to other,
                "event_writer_generation" to other,
                "accepted_catalog_generation" to 2L,
                "accepted_catalog_hash" to ByteArray(32) { 32 },
                "trust_bundle_hash" to ByteArray(32) { 33 },
                "catalog_writer_generation" to other,
                "maintenance_closed" to false,
                "creation_closed" to false,
            )
            faults.forEach { (column, value) ->
                try {
                    assertEquals(
                        1,
                        f.observer.update(
                            "UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?",
                            value,
                            ComplaintDataScope.LIVE.id,
                        ),
                    )
                    refuseWithoutAcquisition(fresh, column)
                } finally {
                    // Restore only this explicit negative fixture field, never the lease, selected-D receipt, or operation authority.
                    assertEquals(
                        1,
                        f.observer.update(
                            "UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?",
                            control[column],
                            ComplaintDataScope.LIVE.id,
                        ),
                    )
                }
                assertEquals(state, f.freeze.state())
            }
            val frozen = f.envelope.copyOf()
            val frozenHash = (f.freeze.row()["envelope_hash"] as ByteArray).copyOf()
            try {
                val corrupt = frozen.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                // Keep the database's bytes/hash constraint valid so the real snapshot/frozen-tuple boundary must refuse.
                assertEquals(
                    1,
                    f.observer.update(
                        "UPDATE complaint_catalog_mutations SET envelope_bytes = ?, envelope_hash = ? WHERE operation_token = ?",
                        corrupt,
                        HexFormat.of().parseHex(Sha256.hex(corrupt)),
                        f.freeze.token,
                    ),
                )
                val calls = fresh.jdbc.calls.size
                refuseWithoutAcquisition(fresh, "frozen-envelope")
                assertTrue(fresh.jdbc.calls.drop(calls).any { it.path === PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT })
            } finally {
                assertEquals(
                    1,
                    f.observer.update(
                        "UPDATE complaint_catalog_mutations SET envelope_bytes = ?, envelope_hash = ? WHERE operation_token = ?",
                        frozen,
                        frozenHash,
                        f.freeze.token,
                    ),
                )
            }
        }
        CatalogSignerRotationDeliveryRoot(f).use { changed ->
            val document = f.freeze.d7.document.copy(
                catalogSignerRotation = checkNotNull(f.freeze.d7.document.catalogSignerRotation).copy(totalAttemptMillis = 29_999),
            )
            changed.prepare(ComplaintDesiredDeploymentJsonV1.parse(CatalogSignerRotationD7Inputs.bytes(document)), sameD = false)
            val beforeReads = f.http.read.requests.size
            refuseWithoutAcquisition(changed, "different-actual-D")
            assertTrue(changed.jdbc.calls.isEmpty())
            assertEquals(beforeReads, f.http.read.requests.size)
        }
        assertEquals(state, f.freeze.state())
        f.freeze.assertLeavesUnchanged(leaves)
        observed.preparedHead1()
        assertEquals(0, f.http.put.createdClients)
        assertTrue(f.http.put.requests.isEmpty() && f.http.bodies.isEmpty())
    }

    fun nativeCleanupAndSpentArmNeverGrantAnotherPut() {
        // A returned raw-client close failure is sticky before acquisition; no replacement slot, SQL or provider result is synthesized.
        CatalogSignerRotationDeliveryRoot(f).use { closing ->
            closing.prepare()
            val original = closing.begin()
            val before = f.freeze.state()
            var reached = false
            f.http.afterReadClientClose = {
                reached = true
                throw IOException("synthetic-private-overlap2-read-close")
            }
            try {
                assertEquals(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN, f.core.refused { closing.publish(original) }.code)
            } finally {
                f.http.afterReadClientClose = {}
            }
            assertTrue(reached)
            assertSticky(closing, original)
            assertEquals(before, f.freeze.state())
            assertTrue(closing.jdbc.steps.none { it == "lease-acquire" || it.startsWith("final-") })
            closing.retireRoot()
            assertStickyAfterRetirement(closing, original)
        }
        f.awaitActualLeaseExpiry()
        CatalogSignerRotationDeliveryRoot(f).use { publishing ->
            publishing.prepare()
            val original = publishing.begin()
            var armed = false
            f.http.beforePut = {
                assertTrue(f.freeze.complete(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED))
                armed = true
                // The actual outer native prepare has entered, but the synthetic inner executable does not return.
                throw IOException("synthetic-private-overlap2-unreturned-native-prepare")
            }
            try {
                assertEquals(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN, f.core.refused { publishing.publish(original) }.code)
            } finally {
                f.http.beforePut = {}
            }
            assertTrue(armed)
            assertEquals(1, f.http.put.createdClients)
            assertEquals(1, f.http.put.closedClients)
            assertTrue(f.http.put.requests.isEmpty() && f.http.put.replies.isEmpty() && f.http.bodies.isEmpty())
            assertNull(f.http.primaryVersion)
            assertNull(f.http.replicaVersion)
            assertFalse(f.freeze.exists(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED))
            assertSticky(publishing, original)
            observed.preparedHead1()
            publishing.retireRoot()
            assertStickyAfterRetirement(publishing, original)
        }
        val leaves = f.freeze.snapshotLeaves()
        f.awaitActualLeaseExpiry()
        // Even fresh publish with write credentials and an actual new lease cannot reinterpret empty cloud as an unspent arm.
        CatalogSignerRotationDeliveryRoot(f).use { recovery ->
            recovery.prepare()
            val before = f.leaseRow()
            val original = recovery.begin()
            val lower = f.databaseNow()
            assertEquals(CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED, f.core.refused { recovery.publish(original) }.code)
            val upper = f.databaseNow()
            recovery.assertReleased(original)
            observed.acquiredLease(recovery, before, lower, upper)
            observed.completedPhases(recovery, original)
            observed.preparedHead1()
            assertEquals(1, f.http.put.createdClients)
            assertTrue(f.http.put.requests.isEmpty())
            f.freeze.assertLeavesUnchanged(leaves)
        }
        observed.preparedHead1()
    }

    private fun refuseWithoutAcquisition(root: CatalogSignerRotationDeliveryRoot, label: String) {
        val before = f.freeze.state()
        val leaves = f.freeze.snapshotLeaves()
        val start = root.jdbc.calls.size
        val original = root.begin()
        f.core.refused { root.publish(original) }
        root.assertReleased(original)
        assertEquals(before, f.freeze.state(), label)
        assertTrue(root.jdbc.calls.drop(start).none { it.step == "lease-acquire" || it.step.startsWith("final-") }, label)
        assertEquals(0, f.http.put.createdClients)
        f.freeze.assertLeavesUnchanged(leaves)
    }

    private fun assertSticky(root: CatalogSignerRotationDeliveryRoot, original: CatalogSignerRotationDeliveryV1) {
        assertSame(original, root.active())
        assertFalse(poolTestField<Boolean>(original, "released"))
        assertFalse(poolTestField<Boolean>(original, "cleanupProven"))
        assertEquals(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN, f.core.refused(original::close).code)
        val calls = root.jdbc.calls.size
        val reads = f.http.read.createdClients
        val puts = f.http.put.createdClients
        f.core.refused { root.recover(root.begin()) }
        f.core.refused { root.project(original) }
        assertSame(original, root.active())
        assertEquals(calls, root.jdbc.calls.size)
        assertEquals(reads, f.http.read.createdClients)
        assertEquals(puts, f.http.put.createdClients)
        f.assertNoFurtherSign()
    }

    private fun assertStickyAfterRetirement(root: CatalogSignerRotationDeliveryRoot, original: CatalogSignerRotationDeliveryV1) {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertSame(original, root.active())
        assertFalse(poolTestField<Boolean>(original, "released"))
        assertEquals(CatalogSignerRotationFreezeFailureV1.CLEANUP_UNPROVEN, f.core.refused(original::close).code)
        assertFalse(poolTestField<Boolean>(original, "cleanupProven"))
    }
}
