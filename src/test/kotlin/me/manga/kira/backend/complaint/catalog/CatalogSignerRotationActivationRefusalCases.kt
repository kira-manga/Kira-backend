package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier.Activation3Readback.State
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationStateV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Files
import java.sql.Timestamp
import java.util.UUID

/** Exactly three grouped negative fixtures. Explicit corruption is never repaired into a claimed producer/recovery success. */
internal class CatalogSignerRotationActivationRefusalCases(private val f: CatalogSignerRotationActivationFixture) {
    private val observed = CatalogSignerRotationActivationAssertions(f)
    private val sql = CatalogSignerRotationActivationSql(f)
    private val ownership = CatalogSignerRotationActivationOwnership(f)

    fun wrongPredecessorAndSignerPolicy() {
        val policy = f.manifest.requiredSignerPolicy
        val cases = listOf(
            f.manifest.copy(previousEnvelopeSha256 = Sha256.hex(f.freeze.d7.envelope)),
            f.manifest.copy(requiredSignerPolicy = policy.copy(members = listOf(OfflineCatalogRotationFixture.member("catalog-old")))),
            f.manifest.copy(
                operation = "ROTATION_OVERLAP",
                requiredSignerPolicy = policy.copy(
                    mode = "ROTATION_OVERLAP",
                    members = listOf(OfflineCatalogRotationFixture.member("catalog-old"), OfflineCatalogRotationFixture.member("catalog-new")),
                ),
            ),
        )
        val state = f.freeze.state()
        val lease = f.delivery.leaseRow()
        CatalogSignerRotationActivationRoot(f).use { root ->
            root.prepare()
            cases.forEachIndexed { index, manifest ->
                val request = f.freeze.request(manifest) // Same protected root with the genuine projected overlap2 sibling.
                assertEquals(f.request.releaseRoot, request.releaseRoot)
                val original = root.begin()
                val reads = f.http.read.createdClients
                f.core.refused { root.activate(original, request) }
                root.assertReleased(original)
                val calls = sql.calls(root, original)
                if (index == 0) {
                    assertTrue(calls.any { it.step == "snapshot" }, "Wrong immediate predecessor reaches the real local snapshot.")
                } else {
                    assertTrue(calls.isEmpty(), "Wrong SINGLE/new-key policy fails at the real input parser/derived profile.")
                }
                sql.effects(root, original, prepares = 0, signatures = 0, completes = 0, projects = 0)
                assertTrue(calls.none { it.path === PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE })
                assertEquals(reads, f.http.read.createdClients)
                assertEquals(0, f.signing.createdClients)
                assertEquals(0, f.http.put.createdClients)
                assertFalse(Files.exists(f.allocation))
                assertEquals(state, f.freeze.state())
                assertEquals(lease, f.delivery.leaseRow())
                observed.head2(allocated = false)
            }
        }
        storedGenesisSignatureDrift()
    }

    private fun storedGenesisSignatureDrift() {
        f.delivery.awaitActualLeaseExpiry()
        val genesis = f.freeze.d7.genesisRow()
        val changed = (genesis["signer_one_signature"] as ByteArray).copyOf().apply {
            this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
        }
        val counters = f.core.counterBalances()
        val control = controlWithoutLease()
        val overlapVersion = f.core.mutationRowVersion()
        assertEquals(
            1,
            f.observer.update(
                "UPDATE complaint_catalog_mutations SET signer_one_signature = ? WHERE operation_token = ?",
                changed, genesis["operation_token"],
            ),
        )
        val negative = f.core.genesisJson()
        val version = f.observer.queryForObject(
            "SELECT xmin::text FROM complaint_catalog_mutations WHERE operation_token = ?", String::class.java, genesis["operation_token"],
        )
        CatalogSignerRotationActivationRoot(f).use { root ->
            root.prepare()
            val original = root.begin()
            val lease = f.delivery.leaseRow()
            val lower = f.delivery.databaseNow()
            f.core.refused { root.activate(original) }
            val upper = f.delivery.databaseNow()
            root.assertReleased(original)
            ownership.raw(original, State.HEAD2)
            sql.acquired(root, original, lease, lower, upper)
            val calls = sql.calls(root, original)
            val history = calls.single { it.step == "activation-head-history-read" }
            assertEquals(2, root.jdbc.returnedRowCounts.getValue(history))
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, history.phase.databaseOutcome())
            assertTrue(history.phase.signerRotationActivationCleanupProven(original))
            sql.effects(root, original, prepares = 0, signatures = 0, completes = 0, projects = 0)
            assertEquals(negative, f.core.genesisJson())
            assertEquals(
                version,
                f.observer.queryForObject(
                    "SELECT xmin::text FROM complaint_catalog_mutations WHERE operation_token = ?", String::class.java, genesis["operation_token"],
                ),
            )
            val current = f.freeze.d7.genesisRow()
            assertArrayEquals(changed, current["signer_one_signature"] as ByteArray)
            assertArrayEquals(genesis["envelope_bytes"] as ByteArray, current["envelope_bytes"] as ByteArray)
            assertArrayEquals(genesis["envelope_hash"] as ByteArray, current["envelope_hash"] as ByteArray)
            assertEquals(control, controlWithoutLease())
            assertEquals(counters, f.core.counterBalances(), "The initial READ may verify counters, but cannot charge them.")
            assertEquals(overlapVersion, f.core.mutationRowVersion())
            assertNull(ownedCutField(original, "preparedOperation"))
            assertFalse(Files.exists(f.allocation))
            assertEquals(0, f.signing.createdClients)
            assertEquals(0, f.http.put.createdClients)
            f.freeze.assertLeavesUnchanged(f.projectedLeaves)
            f.delivery.assertNoFurtherSign()
        }
        // Explicit negative stored signature remains for normal owned-fixture deletion; no repair-then-success claim.
    }

    fun independentLeaseOwnerAndPendingTokenDrift() {
        f.delivery.awaitActualLeaseExpiry()
        f.http.replicateOnPut = true
        refuseLeaseOwnerDrift()
        refusePendingTokenDrift() // A separate fresh owner reaches snapshot validation; not the first owner's spent pending grant.
        observed.pendingHead3()
        observed.oneSignAndPut()
    }

    private fun refuseLeaseOwnerDrift() {
        val root = CatalogSignerRotationActivationRoot(f)
        var lease: Map<String, Any?>? = null
        val negativeOwner = UUID.randomUUID()
        try {
            root.use {
                root.prepare()
                val original = root.begin()
                assertEquals(CatalogSignerRotationActivationStateV1.PROJECTION_PENDING, root.activate(original).state)
                val completed = observed.pendingHead3()
                observed.acquisitionRecord(
                    CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME,
                    "completed3-pending3",
                    head3 = true,
                    times = listOf(completed),
                )
                val pending = ownership.pending(original, actualComplete = true)
                val actualLease = f.delivery.leaseRow()
                assertFalse(negativeOwner == actualLease["lease_owner"])
                assertTrue(f.delivery.databaseNow().isBefore((actualLease["lease_expires_at"] as Timestamp).toInstant()))
                lease = actualLease
                changeControl("lease_owner", negativeOwner)
                assertEquals(actualLease.toMutableMap().apply { this["lease_owner"] = negativeOwner }, f.delivery.leaseRow())
                val before = f.freeze.state()
                val row = f.mutationJson()
                val version = f.rowVersion()
                val leaves = f.snapshotLeaves()
                val reads = f.http.read.createdClients
                val start = root.jdbc.calls.size
                assertSame(original, root.active())
                f.core.refused { root.project(original) }
                root.assertReleased(original)
                assertSame(pending, ownedCutField(original, "pending"))
                assertTrue(poolTestField<Boolean>(pending, "spent"))
                val calls = root.jdbc.calls.drop(start)
                assertEquals(listOf("activation-authenticate", "activation-pending-control"), calls.map { it.step })
                assertTrue(calls.all { it.path === PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PROJECT })
                assertEquals(actualLease["lease_owner"], calls.last().arguments[9], "The real locked B12 query still expects its actual owner.")
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, calls.last().phase.databaseOutcome())
                assertTrue(calls.last().phase.signerRotationActivationCleanupProven(original))
                assertEquals(before, f.freeze.state())
                assertEquals(row, f.mutationJson())
                assertEquals(version, f.rowVersion())
                assertEquals(reads, f.http.read.createdClients)
                sql.effects(root, original, prepares = 1, signatures = 1, completes = 1, projects = 0)
                observed.pendingHead3()
                f.assertLeavesUnchanged(leaves, setOf(CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED))
                assertFalse(f.exists(CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME))
            }
        } finally {
            lease?.let { saved ->
                assertTrue(root.cleanupVerified, "Restore the one explicitly negative field only after original physical disposal.")
                changeControl("lease_owner", saved["lease_owner"])
                assertEquals(saved, f.delivery.leaseRow(), "No lease token, expiry or updated_at was changed for this negative.")
            }
        }
    }

    private fun refusePendingTokenDrift() {
        val root = CatalogSignerRotationActivationRoot(f)
        val lease = f.delivery.leaseRow()
        val originalToken = f.delivery.head()["pending_projection_token"]
        assertEquals(f.token, originalToken)
        val negativeToken = f.freeze.token // An existing real overlap2 FK, not a fabricated operation or alternate lease owner.
        try {
            changeControl("pending_projection_token", negativeToken)
            assertEquals(negativeToken, f.delivery.head()["pending_projection_token"])
            val before = f.freeze.state()
            val version = f.rowVersion()
            val leaves = f.snapshotLeaves()
            val reads = f.http.read.createdClients
            root.use {
                root.prepare()
                val original = root.begin()
                f.core.refused { root.recover(original) }
                root.assertReleased(original)
                assertTrue(root.jdbc.calls.any { it.step == "snapshot" })
                assertTrue(root.jdbc.calls.all { it.path === PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT })
                assertEquals(reads, f.http.read.createdClients)
                assertEquals(before, f.freeze.state())
                assertEquals(version, f.rowVersion())
                assertEquals(lease, f.delivery.leaseRow())
                sql.effects(root, original, prepares = 0, signatures = 0, completes = 0, projects = 0)
                f.assertLeavesUnchanged(leaves)
                observed.preserved()
                observed.oneSignAndPut()
            }
        } finally {
            assertTrue(root.cleanupVerified)
            changeControl("pending_projection_token", originalToken)
            assertEquals(lease, f.delivery.leaseRow())
        }
    }

    fun boundedHistoryRejectsExtraProjectedFourthRow() {
        f.delivery.awaitActualLeaseExpiry()
        val previous = publishLaggingThird()
        val negative = insertNegativeFourth()
        val row = f.mutationJson()
        val version = f.rowVersion()
        val leaves = f.snapshotLeaves()
        val lease = f.delivery.leaseRow()
        val head = controlWithoutLease()
        val counters = f.core.counterBalances()
        f.delivery.awaitActualLeaseExpiry()
        CatalogSignerRotationActivationRoot(f).use { root ->
            root.prepare()
            assertNotSame(previous.first.process, root.process)
            val original = root.begin()
            val lower = f.delivery.databaseNow()
            f.core.refused { root.recover(original) }
            val upper = f.delivery.databaseNow()
            root.assertReleased(original)
            ownership.raw(original, State.PREPARED_AWAIT_REPLICATION)
            sql.acquired(root, original, lease, lower, upper)
            val calls = sql.calls(root, original)
            val history = calls.single { it.step == "activation-prepared-history-lock" }
            assertSame(history, calls.last())
            assertEquals(4, root.jdbc.returnedRowCounts.getValue(history), "The unchanged bounded query actually returned all four real SQL rows.")
            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, history.phase.databaseOutcome())
            assertTrue(history.phase.signerRotationActivationCleanupProven(original))
            assertTrue(calls.none { it.step == "counters" || it.step.startsWith("charge:") })
            sql.effects(root, original, prepares = 0, signatures = 0, completes = 0, projects = 0)
            assertEquals(row, f.mutationJson())
            assertEquals(version, f.rowVersion())
            assertEquals(head, controlWithoutLease())
            assertEquals(counters, f.core.counterBalances())
            assertEquals(negative, negativeFourthJson())
            assertNull(f.http.replicaVersion)
            f.assertLeavesUnchanged(leaves)
            observed.preserved(extraRows = 1)
            observed.oneSignAndPut()
        }
        // Keep the conflicting fourth row until normal post-disposal isolation; never remove it and claim recovery success.
    }

    private fun publishLaggingThird(): Pair<CatalogSignerRotationActivationRoot, CatalogSignerRotationActivationV1> {
        lateinit var previous: Pair<CatalogSignerRotationActivationRoot, CatalogSignerRotationActivationV1>
        CatalogSignerRotationActivationRoot(f).use { root ->
            root.prepare()
            val original = root.begin()
            assertEquals(CatalogSignerRotationActivationStateV1.AWAIT_REPLICATION, root.activate(original).state)
            root.assertReleased(original)
            observed.prepared(signed = true)
            observed.oneSignAndPut()
            sql.effects(root, original, prepares = 1, signatures = 1, completes = 0, projects = 0)
            previous = root to original
        }
        assertTrue(previous.first.cleanupVerified)
        return previous
    }

    private fun insertNegativeFourth(): String {
        val token = UUID.randomUUID()
        f.retainNegativeToken(token)
        // Deliberately synthetic schema-valid completed/projected conflict, never a signed/accepted G4 or a cloud object.
        assertEquals(
            1,
            f.observer.update(
                "INSERT INTO complaint_catalog_mutations SELECT ?, 'SIGNER_ROTATION_ACTIVATION', data_scope_id, test_only, 3, ?, 4, " +
                    "catalog_writer_generation, approval_bytes, approval_hash, canonicalizer, unsigned_bytes, unsigned_hash, signer_policy, " +
                    "signer_one_id, signer_one_algorithm, signer_one_signature, signer_two_id, signer_two_algorithm, signer_two_signature, " +
                    "envelope_bytes, envelope_hash, ?, object_version, retain_until, primary_evidence_bytes, primary_evidence_hash, " +
                    "replica_evidence_bytes, replica_evidence_hash, state, created_at, completed_at, projected_at " +
                    "FROM complaint_catalog_mutations WHERE operation_token = ?",
                token, f.row()["envelope_hash"], CatalogReadbackProtocol.key(4), f.freeze.d7.genesisRow()["operation_token"],
            ),
        )
        val row = f.observer.queryForMap("SELECT * FROM complaint_catalog_mutations WHERE operation_token = ?", token)
        assertEquals("COMPLETED", row["state"])
        checkNotNull(row["projected_at"])
        assertEquals(3L, row["predecessor_generation"])
        assertEquals(4L, row["successor_generation"])
        observed.preserved(extraRows = 1)
        return negativeFourthJson()
    }

    private fun negativeFourthJson(): String = checkNotNull(
        f.observer.queryForObject("SELECT to_jsonb(m)::text FROM complaint_catalog_mutations m WHERE successor_generation = 4", String::class.java),
    )

    private fun controlWithoutLease(): String = checkNotNull(
        f.observer.queryForObject(
            "SELECT (to_jsonb(c) - ARRAY['lease_owner','lease_token','lease_expires_at','updated_at'])::text " +
                "FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, ComplaintDataScope.LIVE.id,
        ),
    )

    private fun changeControl(column: String, value: Any?) {
        check(column == "lease_owner" || column == "pending_projection_token")
        assertEquals(
            1, f.observer.update("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", value, ComplaintDataScope.LIVE.id),
        )
    }
}
