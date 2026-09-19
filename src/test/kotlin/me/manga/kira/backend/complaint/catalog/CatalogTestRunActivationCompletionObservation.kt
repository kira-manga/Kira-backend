package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReleaseLeafV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunCompletedPendingV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat

/** Existing ConnectedIT/signed-custody/raw-HTTP composition only; no synthetic signed TEST SQL or pre-published TEST object. */
internal fun withCompletionActivationRows(
    tls: VersionBoundPersistenceConnectedFixture,
    prefix: ActivationEvidencePrefix = ActivationEvidencePrefix.INVENTORY_ROTATED,
    action: (CompletionActivationObservation) -> Unit,
) = withSignedActivationRows(tls, prefix) { signed ->
    val before = signed.rows.counters.snapshot()
    val original = signed.begin()
    signed.assertSigned(signed.freeze(original))
    signed.assertReleased(original)
    signed.rows.assertPrepareCharge(before)
    action(CompletionActivationObservation(signed))
}

/** Observes actual retained bytes, real SQL and real SDK calls; none of these test values is submitted as authority. */
internal class CompletionActivationObservation(val signed: SignedActivationObservation) {
    val rows get() = signed.rows
    val envelope = signed.read(CatalogTestRunActivationReleaseLeafV1.ENVELOPE)
    val http = CatalogGenesisPublishHttpFixture(
        envelope, rows.evidence.creation.createdAtEpochSecond,
        prefixBytes = rows.evidence.prefix, prefixRetainUntil = rows.evidence.retainedUntil,
    )
    val initialPrepared = rows.preparedRow()
    val initialCounters = rows.counters.snapshot()
    val signedLeaves = signed.leaves()
    private val originalControl = invariantControl()
    private val originalFrozen = frozenTuple()
    private val originalHistory = rows.history().take(rows.evidence.prefix.size)

    init {
        assertNull(http.primaryVersion)
        assertNull(http.replicaVersion)
        assertTrue(http.primaryBytes.isEmpty() && http.replicaBytes.isEmpty())
        http.beforePut = {
            signed.releasedSql()
            assertTrue(signed.complete(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ARMED))
        }
        http.beforeRead = signed::releasedSql
        signed.additionalCleanupObservation = {
            // Physical fixture disposal is not a returned native-cleanup receipt for a failed original owner.
            assertEquals(http.put.createdClients, http.put.closedClients)
            assertEquals(http.read.createdClients, http.read.closedClients)
            http.assertNoLostAssertions()
        }
    }

    fun begin(
        selected: VersionBoundPersistenceConnectedFixture,
        putFactory: () -> SdkHttpClient = ::openPut,
        process: VersionBoundTestNamespaceProcessV1 = if (selected === signed.tls) rows.evidence.process else rows.evidence.processOn(selected.pools),
    ): CatalogTestRunActivationV1 = signed.beginDelivery(selected, putFactory, http::readClient, process)

    fun deliver(original: CatalogTestRunActivationV1, bytes: ByteArray = rows.intent): CatalogTestRunCompletedPendingV1 =
        original.deliverAndComplete(signed.root, bytes, CatalogGenesisPublishHttpFixture.PUT_CREDENTIALS,
            S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)

    fun recover(original: CatalogTestRunActivationV1, bytes: ByteArray = rows.intent): CatalogTestRunCompletedPendingV1 =
        original.recoverCompletion(signed.root, bytes, S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)

    fun reloadPending(original: CatalogTestRunActivationV1, bytes: ByteArray = rows.intent): CatalogTestRunCompletedPendingV1 =
        original.reloadPending(signed.root, bytes, S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)

    fun assertPrepared() {
        assertEquals(initialPrepared, rows.preparedRow(), "No failed or waiting delivery may rewrite even the signed PREPARED xmin.")
        signed.assertSigned()
        assertPreserved()
    }

    fun assertPending(receipt: CatalogTestRunCompletedPendingV1? = null) {
        signed.releasedSql()
        val row = signed.row()
        val head = head()
        assertEquals("COMPLETED", row["state"])
        assertNull(row["projected_at"], "COMPLETE is not PROJECT or a run issuer.")
        val completedAt = (row["completed_at"] as Timestamp).toInstant()
        assertEquals(rows.evidence.generation, head["accepted_catalog_generation"])
        assertArrayEquals(hash(envelope), head["accepted_catalog_hash"] as ByteArray)
        assertEquals(signed.token, head["pending_projection_token"])
        assertEquals(http.publishedVersion, row["object_version"])
        assertEquals(Instant.ofEpochSecond(http.retainUntil), (row["retain_until"] as Timestamp).toInstant())
        assertCopies(row)
        receipt?.let {
            assertEquals(signed.token, it.operationToken)
            assertEquals(rows.evidence.journal.scope.id, it.dataScopeId)
            assertEquals(rows.evidence.generation, it.generation)
            assertEquals(Sha256.hex(rows.intent), it.unsignedSha256)
            assertEquals(Sha256.hex(envelope), it.envelopeSha256)
            assertEquals(http.publishedVersion, it.objectVersion)
            assertEquals(completedAt, it.completedAt)
        }
        assertPreserved()
    }

    fun assertPreserved() {
        assertEquals(originalControl, invariantControl(), "Global D/trust/writers/identities/flags and every non-head control remain exact.")
        assertEquals(originalFrozen, frozenTuple(), "Unsigned/signed TEST tuple is immutable across COMPLETE.")
        assertEquals(originalHistory, rows.history().take(rows.evidence.prefix.size))
        assertEquals(initialCounters, rows.counters.snapshot(), "No second PREPARE charge, reserve, refund, daily rewrite or projection charge.")
        assertEquals(1, signed.signing.requests.size, "Delivery/recovery has no Sign credentials or randomized PSS retry.")
        assertArrayEquals(envelope, signed.read(CatalogTestRunActivationReleaseLeafV1.ENVELOPE))
        val leaves = signed.leaves()
        signedLeaves.forEach { (path, image) -> assertEquals(image, leaves[path], "Original signed custody cannot be replaced by delivery.") }
        val control = head()
        assertEquals(true, control["maintenance_closed"])
        assertEquals(true, control["creation_closed"])
        assertEquals(0L, rows.observer.queryForObject("SELECT count(*) FROM complaint_test_runs", Long::class.java))
        for (table in listOf("complaint_journal_control", "complaint_resource_ids", "complaints", "app_installations",
            "complaint_installation_ids", "complaint_journal_publications")) {
            assertEquals(0L, rows.observer.queryForObject("SELECT count(*) FROM $table WHERE data_scope_id = ?",
                Long::class.java, rows.evidence.journal.scope.id), table)
        }
        assertNoLostAssertions()
    }

    fun singlePrimaryPut() {
        val request = http.put.requests.single()
        val credentials = CatalogGenesisPublishHttpFixture.PUT_CREDENTIALS
        assertEquals(1, http.put.createdClients)
        assertEquals(1, http.put.closedClients)
        assertEquals(1, http.put.replies.single().calls)
        assertEquals(1, http.put.replies.single().aborts)
        assertEquals(SdkHttpMethod.PUT, request.method())
        assertEquals("https", request.protocol())
        assertEquals("s3.${S3CatalogReadbackFixture.primary.region}.amazonaws.com", request.host())
        assertEquals("/${S3CatalogReadbackFixture.primary.bucket}/${CatalogReadbackProtocol.key(rows.evidence.generation)}", request.encodedPath())
        assertEquals("*", request.header("If-None-Match"))
        assertEquals(S3CatalogReadbackFixture.primary.accountId, request.header("x-amz-expected-bucket-owner"))
        assertEquals(credentials.sessionToken(), request.header("x-amz-security-token"))
        assertTrue(request.header("Authorization").contains("Credential=${credentials.accessKeyId()}/"))
        assertTrue(request.header("Authorization").contains("/${S3CatalogReadbackFixture.primary.region}/s3/aws4_request"))
        assertEquals(envelope.size.toString(), request.header("Content-Length"))
        assertEquals(Sha256.hex(envelope), request.header("x-amz-content-sha256"))
        assertEquals(CatalogGenesisPublishHttpFixture.checksum(envelope), request.header("x-amz-checksum-sha256"))
        assertEquals("SHA256", request.header("x-amz-sdk-checksum-algorithm"))
        assertEquals("COMPLIANCE", request.header("x-amz-object-lock-mode"))
        assertEquals(Instant.ofEpochSecond(http.retainUntil).toString(), request.header("x-amz-object-lock-retain-until-date"))
        assertEquals("1", request.header("amz-sdk-request").substringAfter("attempt=").substringBefore(';'))
        assertArrayEquals(envelope, http.bodies.single())
        assertArrayEquals(envelope, http.primaryBytes)
        assertTrue(http.read.requests.all { it.method() == SdkHttpMethod.GET }, "No replica PUT, overwrite, delete, or replacement publication route.")
        assertNoLostAssertions()
    }

    fun assertReleased(original: CatalogTestRunActivationV1, selected: VersionBoundPersistenceConnectedFixture) {
        signed.assertReleased(original, selected)
        assertEquals(http.put.createdClients, http.put.closedClients)
        assertEquals(http.read.createdClients, http.read.closedClients)
        assertTrue(http.read.replies.all { it.calls == 1 && it.closes > 0 })
        assertNoLostAssertions()
    }

    fun assertCleanFailure(original: CatalogTestRunActivationV1, selected: VersionBoundPersistenceConnectedFixture) {
        original.requireActualCleanup()
        signed.releasedSql()
        assertNull(SignedActivationObservation.active(selected.pools.catalogCoordinator))
        assertTrue(poolTestField<Boolean>(original, "released"))
        assertTrue(poolTestField<Boolean>(original, "cleanupProven"))
        assertFalse(poolTestField<Boolean>(original, "allowedCompletedResult"))
        signed.probe(selected).observations.keys.forEach {
            assertTrue(it.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
        }
        assertEquals(http.put.createdClients, http.put.closedClients)
        assertEquals(http.read.createdClients, http.read.closedClients)
        assertNoLostAssertions()
    }

    fun assertSticky(original: CatalogTestRunActivationV1, selected: VersionBoundPersistenceConnectedFixture) {
        assertSame(original, SignedActivationObservation.active(selected.pools.catalogCoordinator))
        for (field in listOf("released", "cleanupProven", "allowedResult", "allowedSignedResult", "allowedCompletedResult")) {
            assertFalse(poolTestField<Boolean>(original, field), field)
        }
    }

    fun head(): Map<String, Any?> = rows.observer.queryForMap(
        "SELECT accepted_catalog_generation, accepted_catalog_hash, pending_projection_token, maintenance_closed, creation_closed " +
            "FROM complaint_journal_control WHERE data_scope_id = ?", ComplaintDataScope.LIVE.id,
    )

    fun assertNoLostAssertions() {
        signed.assertNoLostAssertions()
        http.assertNoLostAssertions()
    }

    private fun openPut(): SdkHttpClient {
        signed.releasedSql()
        assertTrue(signed.complete(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ARMED), "Durable arm precedes the actual raw PUT constructor.")
        return http.putClient()
    }

    private fun assertCopies(row: Map<String, Any?>) {
        for ((leaf, prefix) in listOf(CatalogTestRunActivationReleaseLeafV1.PRIMARY_COPY to "primary", CatalogTestRunActivationReleaseLeafV1.REPLICA_COPY to "replica")) {
            assertTrue(signed.complete(leaf))
            val bytes = signed.read(leaf)
            assertArrayEquals(bytes, row["${prefix}_evidence_bytes"] as ByteArray)
            assertArrayEquals(hash(bytes), row["${prefix}_evidence_hash"] as ByteArray)
            val copy = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            assertEquals(CatalogReadbackProtocol.key(rows.evidence.generation), copy.getValue("objectKey").jsonPrimitive.content)
            assertEquals(http.publishedVersion, copy.getValue("objectVersion").jsonPrimitive.content)
            assertEquals(Sha256.hex(envelope), copy.getValue("envelopeSha256").jsonPrimitive.content)
            assertEquals(envelope.size.toLong(), copy.getValue("contentLength").jsonPrimitive.long)
            assertEquals(http.retainUntil, copy.getValue("retainUntilEpochSecond").jsonPrimitive.long)
            assertEquals("COMPLIANCE", copy.getValue("objectLockMode").jsonPrimitive.content)
            assertEquals(if (prefix == "primary") "COMPLETED" else "REPLICA", copy.getValue("replicationStatus").jsonPrimitive.content)
            val location = if (prefix == "primary") S3CatalogReadbackFixture.primary else S3CatalogReadbackFixture.replica
            val actual = copy.getValue("location").jsonObject
            for ((name, value) in listOf("role" to location.role, "accountId" to location.accountId, "region" to location.region, "bucket" to location.bucket)) {
                assertEquals(value, actual.getValue(name).jsonPrimitive.content)
            }
        }
    }

    private fun invariantControl(): String = checkNotNull(rows.observer.queryForObject(
        "SELECT (to_jsonb(c) - ARRAY['lease_owner','lease_token','lease_expires_at','updated_at'," +
            "'accepted_catalog_generation','accepted_catalog_hash','pending_projection_token'])::text " +
            "FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, ComplaintDataScope.LIVE.id,
    ))

    private fun frozenTuple(): String = checkNotNull(rows.observer.queryForObject(
        "SELECT (to_jsonb(m) - ARRAY['state','completed_at','projected_at','object_version','retain_until'," +
            "'primary_evidence_bytes','primary_evidence_hash','replica_evidence_bytes','replica_evidence_hash'])::text " +
            "FROM complaint_catalog_mutations m WHERE operation_token = ?", String::class.java, signed.token,
    ))

    private fun SdkHttpRequest.header(name: String): String = firstMatchingHeader(name).orElseThrow()
    private fun hash(bytes: ByteArray): ByteArray = HexFormat.of().parseHex(Sha256.hex(bytes))
}
