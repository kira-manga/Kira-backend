package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Files

internal enum class CatalogSignerRotationColdRefusalCut { PARTIAL_COMPLETE_OUTCOME, WRONG_PENDING_TOKEN, PROJECTED_REPLICA_VERSION }

/** Three representative negative cuts, each after a genuine published prefix in its own existing TLS/PG fixture. */
internal class CatalogSignerRotationColdRecoveryRefusalCases(private val f: CatalogSignerRotationDeliveryFixture) {
    private val observed = CatalogSignerRotationDeliveryAssertions(f)

    fun refuses(cut: CatalogSignerRotationColdRefusalCut) {
        CatalogSignerRotationColdRecoveryCases(f).retiredKnownPrefix(
            projected = cut === CatalogSignerRotationColdRefusalCut.PROJECTED_REPLICA_VERSION,
        )
        when (cut) {
            CatalogSignerRotationColdRefusalCut.PARTIAL_COMPLETE_OUTCOME -> {
                val leaf = CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME
                assertTrue(f.freeze.complete(leaf))
                Files.delete(f.freeze.marker(leaf)) // Genuine content remains with a missing completion marker; never repaired for a later success.
                assertTrue(f.freeze.exists(leaf))
                assertFalse(f.freeze.complete(leaf))
                refuse(cut)
            }

            CatalogSignerRotationColdRefusalCut.WRONG_PENDING_TOKEN -> wrongPendingToken(cut)

            CatalogSignerRotationColdRefusalCut.PROJECTED_REPLICA_VERSION -> {
                assertEquals(CatalogGenesisPublishHttpFixture.OVERLAP2_VERSION, f.http.replicaVersion)
                f.http.replicaVersion = "different-projected-replica-version" // Raw server metadata only; no accepted proof is fabricated.
                refuse(cut)
            }
        }
        observed.preserved()
        observed.singlePut()
    }

    private fun wrongPendingToken(cut: CatalogSignerRotationColdRefusalCut) {
        val genuineOtherToken = f.freeze.d7.genesisRow()["operation_token"]
        try {
            assertEquals(
                1,
                f.observer.update(
                    "UPDATE complaint_journal_control SET pending_projection_token = ? WHERE data_scope_id = ?",
                    genuineOtherToken, // An existing G1 FK is schema-valid but contradicts the actual pending2 row/head.
                    ComplaintDataScope.LIVE.id,
                ),
            )
            assertEquals(genuineOtherToken, f.head()["pending_projection_token"])
            refuse(cut)
        } finally {
            // Restore this explicit negative control field only. No lease, row lifecycle, outcome gap or subsequent recovery success is changed.
            assertEquals(
                1,
                f.observer.update(
                    "UPDATE complaint_journal_control SET pending_projection_token = ? WHERE data_scope_id = ?",
                    f.freeze.token,
                    ComplaintDataScope.LIVE.id,
                ),
            )
        }
    }

    private fun refuse(cut: CatalogSignerRotationColdRefusalCut) {
        val before = f.freeze.state()
        val lease = f.leaseRow()
        val leaves = f.freeze.snapshotLeaves()
        val xmin = f.core.mutationRowVersion()
        val reads = f.http.read.createdClients
        CatalogSignerRotationDeliveryRoot(f).use { root ->
            root.prepare()
            CatalogSignerRotationColdRecoverySql(f).observeProviderSeparation(root)
            val original = root.begin()
            f.core.refused { root.recover(original) }
            root.assertReleased(original)
            when (cut) {
                CatalogSignerRotationColdRefusalCut.PARTIAL_COMPLETE_OUTCOME -> {
                    assertTrue(root.jdbc.calls.isEmpty())
                    assertEquals(reads, f.http.read.createdClients)
                }

                CatalogSignerRotationColdRefusalCut.WRONG_PENDING_TOKEN -> {
                    assertTrue(root.jdbc.calls.any { it.path === PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT })
                    assertEquals(reads, f.http.read.createdClients)
                }

                CatalogSignerRotationColdRefusalCut.PROJECTED_REPLICA_VERSION -> assertTrue(f.http.read.createdClients > reads)
            }
            assertTrue(root.jdbc.steps.none { it == "lease-acquire" || it == "pending-lease-acquire" || it.startsWith("final-") })
            assertEquals(before, f.freeze.state(), cut.name)
            assertEquals(lease, f.leaseRow())
            assertEquals(xmin, f.core.mutationRowVersion())
            f.freeze.assertLeavesUnchanged(leaves)
            f.assertNoFurtherSign()
        }
    }
}
