package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

internal enum class TestActivationDeliveryBoundaryCut { LIVE_ORIGINAL_LEASE, UNARMED_RECOVERY, FULL_D, GLOBAL_D, SIGNED_ROW, NON_CANONICAL }
internal enum class TestActivationDeliveryRaceCut { GLOBAL_D_AFTER_PUT, LEASE_OWNER_AFTER_PUT, ROW_SCOPE_AFTER_PUT }
internal enum class TestActivationPendingReplayCut { PENDING_TOKEN, HEAD_HASH, COMPLETED_VERSION, MISSING_REPLICA }

/** New delivery entries still require exact genuine signed custody and the original full global preimage/lease. */
internal object CatalogTestRunActivationCompletionBoundaryCases {
    fun unsignedDiagnosticCannotPublish(tls: VersionBoundPersistenceConnectedFixture) = withSignedActivationRows(tls) { f ->
        val diagnostic = f.rows.prepare(f.rows.begin())
        f.rows.assertPrepared(diagnostic)
        val row = f.rows.preparedRow()
        val counters = f.rows.counters.snapshot()
        var putConstructions = 0
        f.withFreshOwner { fresh ->
            for (recovery in listOf(false, true)) {
                val original = f.beginDelivery(fresh, {
                    putConstructions++
                    error("A diagnostic unsigned row cannot authorize raw PUT construction.")
                }, f.rows.http::httpClient)
                assertThrows<CatalogTestRunActivationExceptionV1> {
                    if (recovery) original.recoverCompletion(f.root, f.rows.intent, S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
                    else original.deliverAndComplete(f.root, f.rows.intent, CatalogGenesisPublishHttpFixture.PUT_CREDENTIALS,
                        S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
                }
                original.requireActualCleanup()
                assertNull(SignedActivationObservation.active(fresh.pools.catalogCoordinator))
            }
            assertEquals(row, f.rows.preparedRow())
            assertEquals(counters, f.rows.counters.snapshot())
            assertEquals(0, putConstructions)
            assertEquals(0, f.signing.createdClients)
            assertFalse(Files.exists(f.allocation), "No missing custody or Sign history is retrofitted onto an unsigned diagnostic checkpoint.")
            f.rows.assertClosedAndHeadUnchanged()
            f.assertNoLostAssertions()
        }
    }

    fun prePutRefusal(tls: VersionBoundPersistenceConnectedFixture, cut: TestActivationDeliveryBoundaryCut) = withCompletionActivationRows(tls) { f ->
        val action: (VersionBoundPersistenceConnectedFixture) -> Unit = { selected ->
            if (cut == TestActivationDeliveryBoundaryCut.GLOBAL_D) assertEquals(1, f.rows.observer.update(
                "UPDATE complaint_journal_control SET desired_configuration_hash = ? WHERE data_scope_id = ?",
                ByteArray(32) { 0x5a }, ComplaintDataScope.LIVE.id,
            ))
            if (cut == TestActivationDeliveryBoundaryCut.SIGNED_ROW) {
                // Valid but different PSS bytes in the owned negative SQL input, not another actual provider Sign.
                val alternative = CatalogTestRunActivationEvidenceFixture.signed(f.rows.evidence.manifest)
                val bytes = CatalogTestRunActivationEvidenceFixture.bytes(alternative)
                assertFalse(bytes.contentEquals(f.envelope))
                assertEquals(1, f.rows.observer.update(
                    "UPDATE complaint_catalog_mutations SET signer_one_signature = ?, envelope_bytes = ?, envelope_hash = ? WHERE operation_token = ?",
                    Base64.getDecoder().decode(alternative.signatures.single().signatureBase64), bytes,
                    HexFormat.of().parseHex(Sha256.hex(bytes)), f.signed.token,
                ))
            }
            val row = f.rows.preparedRow()
            val lease = f.rows.lease()
            val leaves = f.signed.leaves()
            val original = if (cut == TestActivationDeliveryBoundaryCut.FULL_D) {
                f.begin(selected, process = f.rows.evidence.processOn(selected.pools, desiredGeneration = 8))
            } else f.begin(selected)
            assertThrows<CatalogTestRunActivationExceptionV1> {
                if (cut == TestActivationDeliveryBoundaryCut.UNARMED_RECOVERY) f.recover(original)
                else f.deliver(original, if (cut == TestActivationDeliveryBoundaryCut.NON_CANONICAL) f.rows.intent + ' '.code.toByte() else f.rows.intent)
            }
            f.assertCleanFailure(original, selected)
            assertEquals(row, f.rows.preparedRow())
            assertEquals(lease, f.rows.lease(), "Refused input/live original lease never becomes a replacement lease.")
            assertEquals(leaves, f.signed.leaves())
            assertEquals(f.initialCounters, f.rows.counters.snapshot())
            assertEquals(0, f.http.put.createdClients)
            assertEquals(1, f.signed.signing.createdClients)
            assertFalse(f.signed.exists(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ARMED))
            assertTrue(f.signed.probe(selected).steps.none { it == "test-complete" || it == "test-mark-pending" })
            f.rows.assertClosedAndHeadUnchanged()
        }
        if (cut == TestActivationDeliveryBoundaryCut.LIVE_ORIGINAL_LEASE) action(tls)
        else f.signed.withFreshOwner(action = action)
    }

    fun postPutBindingRace(tls: VersionBoundPersistenceConnectedFixture, cut: TestActivationDeliveryRaceCut) = withCompletionActivationRows(tls) { f ->
        f.http.replicateOnPut = true
        f.signed.withFreshOwner { selected ->
            val original = f.begin(selected)
            val budget = original.budget
            var reached = false
            var negativeRow: String? = null
            f.http.afterPutAccepted = {
                f.signed.releasedSql()
                assertFalse(reached)
                reached = true
                when (cut) {
                    TestActivationDeliveryRaceCut.GLOBAL_D_AFTER_PUT -> assertEquals(1, f.rows.observer.update(
                        "UPDATE complaint_journal_control SET desired_configuration_hash = ? WHERE data_scope_id = ?",
                        ByteArray(32) { 0x6c }, ComplaintDataScope.LIVE.id,
                    ))
                    TestActivationDeliveryRaceCut.LEASE_OWNER_AFTER_PUT -> assertEquals(1, f.rows.observer.update(
                        "UPDATE complaint_journal_control SET lease_owner = ? WHERE data_scope_id = ?", UUID.randomUUID(), ComplaintDataScope.LIVE.id,
                    ))
                    TestActivationDeliveryRaceCut.ROW_SCOPE_AFTER_PUT -> assertEquals(1, f.rows.observer.update(
                        "UPDATE complaint_catalog_mutations SET data_scope_id = ? WHERE operation_token = ?", UUID.randomUUID(), f.signed.token,
                    ))
                }
                negativeRow = f.rows.preparedRow()
            }
            try { assertThrows<CatalogTestRunActivationExceptionV1> { f.deliver(original) } } finally { f.http.afterPutAccepted = {} }
            assertTrue(reached)
            f.assertCleanFailure(original, selected)
            f.singlePrimaryPut()
            assertSame(budget, original.budget)
            assertEquals(negativeRow, f.rows.preparedRow(), "Recheck refuses the actual raced DB input before any COMPLETE update.")
            assertEquals(f.initialCounters, f.rows.counters.snapshot())
            f.rows.assertClosedAndHeadUnchanged()
            assertTrue(f.signed.probe(selected).steps.none { it == "test-complete" || it == "test-mark-pending" })
            val calls = f.signed.probe(selected).calls.size
            assertThrows<CatalogTestRunActivationExceptionV1> { f.deliver(original) }
            assertThrows<CatalogTestRunActivationExceptionV1> { f.recover(original) }
            assertEquals(calls, f.signed.probe(selected).calls.size)
            assertEquals(1, f.http.put.createdClients)
            assertEquals(1, f.signed.signing.createdClients)
        }
    }

    fun pendingReloadRefusesChangedExactPair(tls: VersionBoundPersistenceConnectedFixture, cut: TestActivationPendingReplayCut) =
        withCompletionActivationRows(tls) { f ->
            f.http.replicateOnPut = true
            f.signed.withFreshOwner { publishing ->
                val original = f.begin(publishing)
                f.assertPending(f.deliver(original))
                f.assertReleased(original, publishing)
                f.singlePrimaryPut()
            }
            f.signed.withFreshOwner { fresh ->
                when (cut) {
                    TestActivationPendingReplayCut.PENDING_TOKEN -> assertEquals(1, f.rows.observer.update(
                        "UPDATE complaint_journal_control SET pending_projection_token = " +
                            "(SELECT operation_token FROM complaint_catalog_mutations WHERE successor_generation = ?) WHERE data_scope_id = ?",
                        f.rows.evidence.generation - 1L, ComplaintDataScope.LIVE.id,
                    )) // Existing predecessor FK target, not a database constraint failure standing in for an owner check.
                    TestActivationPendingReplayCut.HEAD_HASH -> assertEquals(1, f.rows.observer.update(
                        "UPDATE complaint_journal_control SET accepted_catalog_hash = ? WHERE data_scope_id = ?",
                        ByteArray(32) { 0x6d }, ComplaintDataScope.LIVE.id,
                    ))
                    TestActivationPendingReplayCut.COMPLETED_VERSION -> assertEquals(1, f.rows.observer.update(
                        "UPDATE complaint_catalog_mutations SET object_version = ? WHERE operation_token = ?",
                        "different-completed-test-version", f.signed.token,
                    ))
                    TestActivationPendingReplayCut.MISSING_REPLICA -> f.http.replicaVersion = null
                }
                val row = f.rows.preparedRow()
                val head = checkNotNull(f.rows.observer.queryForObject(
                    "SELECT to_jsonb(c)::text FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, ComplaintDataScope.LIVE.id,
                ))
                val leaves = f.signed.leaves()
                val original = f.begin(fresh)
                assertThrows<CatalogTestRunActivationExceptionV1> { f.reloadPending(original) }
                f.assertCleanFailure(original, fresh)
                assertEquals(row, f.rows.preparedRow(), "Strict pending replay cannot rewrite the completed tuple or its xmin.")
                assertEquals(head, f.rows.observer.queryForObject(
                    "SELECT to_jsonb(c)::text FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, ComplaintDataScope.LIVE.id,
                ), "A refused exact head/token/raw copy cannot mint a fresh lease or repair the global pointer.")
                assertEquals(leaves, f.signed.leaves())
                assertEquals(f.initialCounters, f.rows.counters.snapshot())
                f.singlePrimaryPut()
                assertEquals(1, f.signed.signing.requests.size)
                assertTrue(f.signed.probe(fresh).steps.none { it == "test-complete" || it == "test-mark-pending" || it.startsWith("charge:") })
                assertEquals(true, f.head()["maintenance_closed"])
                assertEquals(true, f.head()["creation_closed"])
                assertNull(f.signed.row()["projected_at"])
                assertEquals(0L, f.rows.observer.queryForObject("SELECT count(*) FROM complaint_test_runs", Long::class.java))
                assertEquals(0L, f.rows.observer.queryForObject("SELECT count(*) FROM complaint_journal_control WHERE data_scope_id = ?",
                    Long::class.java, f.rows.evidence.journal.scope.id))
            }
        }
}
