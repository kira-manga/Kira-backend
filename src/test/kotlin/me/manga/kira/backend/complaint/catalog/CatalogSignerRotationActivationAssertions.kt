package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerRotationActivationCapacityV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationEnvelopeV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest
import java.nio.file.Files
import java.security.Signature
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.HexFormat

/** Independent observations only: no returned signed wrapper, SQL seed, fabricated history or capacity authority is supplied to production. */
internal class CatalogSignerRotationActivationAssertions(private val f: CatalogSignerRotationActivationFixture) {
    private val originalControl = f.delivery.invariantControl()
    private val originalGenesis = f.core.genesisJson()
    private val originalGenesisVersion = genesisVersion()
    private val originalOverlap = overlapJson()
    private val originalOverlapVersion = f.core.mutationRowVersion()
    private val originalCounters = f.core.counterBalances()

    init {
        assertEquals(2L, originalCounters.getValue("catalog_mutations")[1])
        assertEquals(33, f.freeze.d7.genesisRow().size)
        assertEquals(33, f.freeze.row().size)
    }

    fun preserved(allocated: Boolean = true, extraRows: Int = 0) {
        assertEquals(originalControl, f.delivery.invariantControl(), "D, identities, writers, trust, gates and non-head controls are unchanged.")
        assertEquals(originalGenesis, f.core.genesisJson(), "All 33 actual G1 columns remain immutable.")
        assertEquals(originalGenesisVersion, genesisVersion(), "Do not even rewrite an identical G1 row.")
        assertEquals(
            originalOverlap,
            overlapJson(),
            "All 33 projected overlap2 columns, including both copies and lifecycle times, remain immutable.",
        )
        assertEquals(originalOverlapVersion, f.core.mutationRowVersion(), "Do not even rewrite an identical overlap2 row.")
        assertEquals(
            (if (allocated) 3L else 2L) + extraRows,
            f.observer.queryForObject("SELECT count(*) FROM complaint_catalog_mutations", Long::class.java),
        )
        assertEquals(
            extraRows.toLong(),
            f.observer.queryForObject("SELECT count(*) FROM complaint_catalog_mutations WHERE successor_generation >= 4", Long::class.java),
        )
        val after = f.core.counterBalances()
        assertEquals(originalCounters.keys, after.keys)
        originalCounters.forEach { (name, amounts) ->
            val charge = if (!allocated) {
                0L
            } else {
                when (name) {
                    "catalog_mutations" -> 1L
                    "storage_bytes" -> CatalogSignerRotationActivationCapacityV1.storageBytes
                    else -> 0L
                }
            }
            assertEquals(listOf(amounts[0] - charge, amounts[1] + charge, amounts[2], amounts[3]), after[name], name)
        }
        f.freeze.assertLeavesUnchanged(f.projectedLeaves)
        f.delivery.assertNoFurtherSign()
        secondSignerAbsent()
    }

    fun head2(allocated: Boolean = true) {
        val head = f.delivery.head()
        assertEquals(2L, head["accepted_catalog_generation"])
        assertArrayEquals(hash(f.delivery.envelope), head["accepted_catalog_hash"] as ByteArray)
        assertNull(head["pending_projection_token"])
        assertEquals(true, head["maintenance_closed"])
        assertEquals(true, head["creation_closed"])
        preserved(allocated)
    }

    fun prepared(signed: Boolean) {
        val row = f.row()
        assertEquals(33, row.size)
        assertEquals(f.token, row["operation_token"])
        assertEquals("SIGNER_ROTATION_ACTIVATION", row["operation_type"])
        assertEquals(2L, row["predecessor_generation"])
        assertArrayEquals(hash(f.delivery.envelope), row["predecessor_hash"] as ByteArray)
        assertEquals(3L, row["successor_generation"])
        assertEquals(f.freeze.row()["catalog_writer_generation"], row["catalog_writer_generation"])
        assertEquals("kcj-1", row["canonicalizer"])
        assertEquals("SINGLE", row["signer_policy"])
        assertEquals("catalog-new", row["signer_one_id"])
        assertEquals("RSASSA_PSS_SHA_256", row["signer_one_algorithm"])
        assertEquals("PREPARED", row["state"])
        assertEquals(CatalogReadbackProtocol.key(3), row["object_key"])
        assertEquals(Instant.ofEpochSecond(f.manifest.creation.createdAtEpochSecond), (row["created_at"] as Timestamp).toInstant())
        assertArrayEquals(f.intent, row["unsigned_bytes"] as ByteArray)
        assertArrayEquals(hash(f.intent), row["unsigned_hash"] as ByteArray)
        assertArrayEquals(f.read(CatalogSignerRotationReleaseLeafV1.APPROVAL_INPUTS), row["approval_bytes"] as ByteArray)
        assertArrayEquals(hash(row["approval_bytes"] as ByteArray), row["approval_hash"] as ByteArray)
        val absent = listOf(
            "data_scope_id", "test_only", "signer_two_id", "signer_two_algorithm", "signer_two_signature",
            "object_version", "retain_until", "primary_evidence_bytes", "primary_evidence_hash", "replica_evidence_bytes", "replica_evidence_hash",
            "completed_at", "projected_at",
        )
        absent.forEach { column -> assertNull(row[column], column) }
        if (signed) {
            signedBytes()
        } else {
            listOf("signer_one_signature", "envelope_bytes", "envelope_hash").forEach { assertNull(row[it], it) }
        }
        head2()
    }

    fun signedBytes(): ByteArray {
        val row = f.row()
        val bytes = row["envelope_bytes"] as ByteArray
        assertArrayEquals(f.read(CatalogSignerRotationReleaseLeafV1.ENVELOPE), bytes)
        assertArrayEquals(hash(bytes), row["envelope_hash"] as ByteArray)
        assertArrayEquals(f.signatures.single(), row["signer_one_signature"] as ByteArray)
        assertArrayEquals(f.signatures.single(), f.read(CatalogSignerRotationReleaseLeafV1.SIGNATURE_ONE))
        val envelope = Json.decodeFromString(OfflineCatalogRotationEnvelopeV1.serializer(), bytes.decodeToString())
        assertEquals(1, envelope.schemaVersion)
        assertEquals(f.manifest, envelope.manifest)
        val signature = envelope.signatures.single()
        assertEquals("catalog-new", signature.keyId)
        assertEquals("RSASSA_PSS_SHA_256", signature.algorithmId)
        assertArrayEquals(f.signatures.single(), Base64.getDecoder().decode(signature.signatureBase64))
        val verifies = Signature.getInstance("RSASSA-PSS").run {
            setParameter(OfflineTrustBundleFixture.parameters)
            initVerify(OfflineTrustBundleFixture.secondSigner.public)
            update(OfflineCatalogGenesisFixture.independentFrame("catalog-new", f.intent))
            verify(f.signatures.single())
        }
        assertTrue(verifies)
        secondSignerAbsent()
        return bytes
    }

    fun pendingHead3(): Timestamp {
        val row = f.row()
        assertEquals("COMPLETED", row["state"])
        assertNull(row["projected_at"])
        head3(pending = true)
        copies(row)
        preserved()
        return row["completed_at"] as Timestamp
    }

    fun projectedHead3(completed: Timestamp) {
        val row = f.row()
        assertEquals("COMPLETED", row["state"])
        assertEquals(completed, row["completed_at"])
        assertFalse((row["projected_at"] as Timestamp).before(completed))
        head3(pending = false)
        copies(row)
        preserved()
    }

    private fun head3(pending: Boolean) {
        val head = f.delivery.head()
        assertEquals(3L, head["accepted_catalog_generation"])
        assertArrayEquals(hash(signedBytes()), head["accepted_catalog_hash"] as ByteArray)
        assertEquals(if (pending) f.token else null, head["pending_projection_token"])
        assertEquals(true, head["maintenance_closed"])
        assertEquals(true, head["creation_closed"])
    }

    fun oneSignAndPut(puts: Int = 1) {
        assertEquals(1, f.signing.createdClients)
        assertEquals(1, f.signing.closedClients)
        assertEquals(1, f.signing.requests.size)
        assertEquals(1, f.signatures.size)
        assertTrue(f.signing.replies.single().eofProbes > 0)
        assertEquals(puts, f.http.put.createdClients)
        assertEquals(puts, f.http.put.closedClients)
        assertEquals(puts, f.http.put.requests.size)
        assertEquals(puts, f.http.bodies.size)
        assertTrue(f.http.read.requests.all { it.method() == SdkHttpMethod.GET })
        if (puts == 0) {
            assertNull(f.http.primaryVersion)
            assertNull(f.http.replicaVersion)
            assertTrue(f.http.primaryBytes.isEmpty() && f.http.replicaBytes.isEmpty())
            return
        }
        val bytes = signedBytes()
        val request = f.http.put.requests.single()
        val credentials = CatalogGenesisPublishHttpFixture.PUT_CREDENTIALS
        assertEquals(SdkHttpMethod.PUT, request.method())
        assertEquals("https", request.protocol())
        assertEquals("s3.${S3CatalogReadbackFixture.primary.region}.amazonaws.com", request.host())
        assertEquals("/${S3CatalogReadbackFixture.primary.bucket}/${CatalogReadbackProtocol.key(3)}", request.encodedPath())
        assertEquals("*", request.header("If-None-Match"))
        assertEquals(S3CatalogReadbackFixture.primary.accountId, request.header("x-amz-expected-bucket-owner"))
        assertEquals(credentials.sessionToken(), request.header("x-amz-security-token"))
        assertTrue(request.header("Authorization").contains("Credential=${credentials.accessKeyId()}/"))
        assertEquals(bytes.size.toString(), request.header("Content-Length"))
        assertEquals(Sha256.hex(bytes), request.header("x-amz-content-sha256"))
        assertEquals(CatalogGenesisPublishHttpFixture.checksum(bytes), request.header("x-amz-checksum-sha256"))
        assertEquals("SHA256", request.header("x-amz-sdk-checksum-algorithm"))
        assertEquals("COMPLIANCE", request.header("x-amz-object-lock-mode"))
        assertEquals(Instant.ofEpochSecond(f.http.retainUntil).toString(), request.header("x-amz-object-lock-retain-until-date"))
        assertEquals("1", request.header("amz-sdk-request").substringAfter("attempt=").substringBefore(';'))
        assertArrayEquals(bytes, f.http.bodies.single())
        assertArrayEquals(bytes, f.http.primaryBytes)
        assertEquals(1, f.http.put.replies.single().calls)
        assertEquals(1, f.http.put.replies.single().aborts)
    }

    /** Independently decode acquisition-bound write-once records against the actual DB lease, never a record helper. */
    fun acquisitionRecord(
        leaf: CatalogSignerRotationReleaseLeafV1,
        kind: String,
        head3: Boolean = false,
        times: List<Timestamp> = emptyList(),
    ) {
        assertTrue(f.complete(leaf), leaf.name)
        val allocation = Files.readAllBytes(f.allocation.resolve("allocation"))
        val current = f.delivery.leaseRow()
        val binding = f.delivery.historicalBindingArguments.map {
            if (it is ByteArray) HexFormat.of().formatHex(it) else it.toString()
        }.toMutableList().apply {
            this[5] = if (head3) "3" else "2"
            this[6] = Sha256.hex(if (head3) f.read(CatalogSignerRotationReleaseLeafV1.ENVELOPE) else f.delivery.envelope)
        }
        val expected = listOf("catalog-signer-rotation-freeze-v1", kind, Sha256.hex(allocation), Sha256.hex(signedBytes())) +
            times.map { it.toInstant().toString() } + binding + listOf(
                current["lease_owner"].toString(), current["lease_token"].toString(), (current["lease_expires_at"] as Timestamp).toInstant().toString(),
            )
        val actual = Json.parseToJsonElement(f.read(leaf).decodeToString()).jsonArray.map { it.jsonPrimitive.content }
        assertEquals(expected, actual, leaf.name)
    }

    private fun copies(row: Map<String, Any?>) {
        val bytes = signedBytes()
        assertEquals(CatalogGenesisPublishHttpFixture.ACTIVATION3_VERSION, row["object_version"])
        assertEquals(Instant.ofEpochSecond(f.http.retainUntil), (row["retain_until"] as Timestamp).toInstant())
        val copies = listOf(CatalogSignerRotationReleaseLeafV1.PRIMARY_COPY to "primary", CatalogSignerRotationReleaseLeafV1.REPLICA_COPY to "replica")
        for ((leaf, prefix) in copies) {
            assertTrue(f.complete(leaf))
            val evidence = f.read(leaf)
            assertArrayEquals(evidence, row["${prefix}_evidence_bytes"] as ByteArray)
            assertArrayEquals(hash(evidence), row["${prefix}_evidence_hash"] as ByteArray)
            val json = Json.parseToJsonElement(evidence.decodeToString()).jsonObject
            assertEquals(CatalogReadbackProtocol.key(3), json.getValue("objectKey").jsonPrimitive.content)
            assertEquals(CatalogGenesisPublishHttpFixture.ACTIVATION3_VERSION, json.getValue("objectVersion").jsonPrimitive.content)
            assertEquals(Sha256.hex(bytes), json.getValue("envelopeSha256").jsonPrimitive.content)
            assertEquals(bytes.size.toLong(), json.getValue("contentLength").jsonPrimitive.long)
            assertEquals(f.http.retainUntil, json.getValue("retainUntilEpochSecond").jsonPrimitive.long)
            assertEquals("COMPLIANCE", json.getValue("objectLockMode").jsonPrimitive.content)
            assertEquals(if (prefix == "primary") "COMPLETED" else "REPLICA", json.getValue("replicationStatus").jsonPrimitive.content)
            val location = if (prefix == "primary") S3CatalogReadbackFixture.primary else S3CatalogReadbackFixture.replica
            val where = json.getValue("location").jsonObject
            assertEquals(location.role, where.getValue("role").jsonPrimitive.content)
            assertEquals(location.accountId, where.getValue("accountId").jsonPrimitive.content)
            assertEquals(location.region, where.getValue("region").jsonPrimitive.content)
            assertEquals(location.bucket, where.getValue("bucket").jsonPrimitive.content)
        }
    }

    private fun secondSignerAbsent() {
        listOf(
            CatalogSignerRotationReleaseLeafV1.SIGN_TWO_ARMED,
            CatalogSignerRotationReleaseLeafV1.SIGN_TWO_RETURNED,
            CatalogSignerRotationReleaseLeafV1.SIGNATURE_TWO,
            CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_ARMED,
            CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_PERSISTED,
        ).forEach { assertFalse(f.exists(it), it.name) }
    }

    private fun overlapJson(): String = checkNotNull(
        f.observer.queryForObject(
            "SELECT to_jsonb(m)::text FROM complaint_catalog_mutations m WHERE operation_token = ?", String::class.java, f.freeze.token,
        ),
    )

    private fun genesisVersion(): String = checkNotNull(
        f.observer.queryForObject(
            "SELECT xmin::text FROM complaint_catalog_mutations WHERE operation_token = ?",
            String::class.java,
            f.freeze.d7.genesisRow()["operation_token"],
        ),
    )

    private fun SdkHttpRequest.header(name: String): String = firstMatchingHeader(name).orElseThrow()

    companion object {
        fun hash(bytes: ByteArray): ByteArray = HexFormat.of().parseHex(Sha256.hex(bytes))
    }
}
