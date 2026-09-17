package me.manga.kira.backend.complaint.journal

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.complaint.catalog.S3CatalogReply
import me.manga.kira.backend.complaint.infrastructure.journal.EpochSealS3ReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.EpochSealVersionReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.EpochSealS3BindingV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.S3EpochSealClientV1
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.CREDENTIALS
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.checksum
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.errorReply
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.hash
import me.manga.kira.backend.security.EpochSealCodecV1
import me.manga.kira.backend.security.EpochSealTestFixtureV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalCodecV1
import me.manga.kira.backend.security.aws.AwsJournalDataKeyAdapter
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import software.amazon.awssdk.http.SdkHttpMethod
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Reuses existing raw S3/KMS fixtures only. No ordinary publisher, SQL work, STS session or wire-ready authority is constructed. */
internal class EpochSealS3FixtureV1(outerMillis: Long? = null, retentionSeconds: Long? = null) : AutoCloseable {
    val local = EpochSealTestFixtureV1()
    private val ordinary = OwnerDeleteAllJournalCodecV1(local.owner, local.keys).canonicalize(EpochSealTestFixtureV1.tuple(), emptyList())
    val http = OwnerDeleteAllJournalPublisherFixture.historical(local.owner, ordinary)
    private val total = outerMillis?.let { PersistenceTimeBudget.start(it, PersistenceNanoClock { http.nanos }) }
    private val keys = AwsJournalDataKeyAdapter.withEpochSealHttpFixture(http.journal, CREDENTIALS, http.kms::httpClient, total) { http.nanos }
    val codec = EpochSealCodecV1(local.owner, keys, local.nonces) { http.nanos }
    val attempt = codec.startAttempt(total)
    val content = local.content(attempt)
    val envelope = codec.seal(content, attempt)
    val created: Instant = http.wall.truncatedTo(ChronoUnit.SECONDS)
    val requested: Instant = created.plusSeconds(retentionSeconds ?: (http.journal.declaration().limits.retention.ordinaryRetentionSeconds + 60))
    val binding = EpochSealS3BindingV1.encoded(local.owner, content, envelope, requested, attempt)

    init {
        http.respond = ::reply
    }

    fun client(): S3EpochSealClientV1 = S3EpochSealClientV1.withHttpFixture(binding, CREDENTIALS, http::httpClient) { http.nanos }

    fun readback(client: S3EpochSealClientV1): EpochSealVersionReadbackV1 = EpochSealVersionReadbackV1(client, codec, http.clock)

    fun metadata(bytes: ByteArray = envelope.wireBytes()): Map<String, String> = mapOf(
        "kira-journal-schema" to "1",
        "kira-journal-event-id" to content.route.sealId,
        "kira-journal-ciphertext-sha256" to hash(bytes),
        "kira-journal-retain-until" to requested.toString(),
    )

    fun objectFor(bytes: ByteArray = envelope.wireBytes()): JournalPublisherObject = JournalPublisherObject(
        content.route.objectKey,
        VERSION,
        bytes.copyOf(),
        created,
        requested,
        metadata(bytes),
    )

    fun listDocument(versions: List<JournalPublisherObject> = listOfNotNull(http.stored)): String = http.listDocument(versions, content.route.objectKey)

    fun reply(request: JournalPublisherHttpRequest): S3CatalogReply = when (request.kind) {
        "LIST" -> http.listReply(listOfNotNull(http.stored), content.route.objectKey)

        "GET" -> http.getReply(checkNotNull(http.stored))

        else -> if (http.stored != null) {
            errorReply(412)
        } else {
            val value = objectFor(request.body).copy(
                retainUntil = Instant.parse(request.header("x-amz-object-lock-retain-until-date")),
                metadata = request.http.headers().filterKeys { it.startsWith("x-amz-meta-", ignoreCase = true) }
                    .mapKeys { it.key.lowercase().removePrefix("x-amz-meta-") }.mapValues { it.value.single() },
            )
            http.stored = value
            http.putReply(value)
        }
    }

    fun assertObserved(observed: EpochSealS3ReadbackV1) {
        val stored = checkNotNull(http.stored)
        assertSame(content, observed.content)
        assertArrayEquals(content.canonicalBytes(), observed.content.canonicalBytes())
        assertEquals(stored.version, observed.versionId)
        assertEquals(envelope.wireSha256, observed.wireSha256)
        assertEquals(created, observed.lastModified)
        assertEquals(stored.retainUntil, observed.retainUntil)
        assertEquals(http.wall, observed.verifiedAt)
        http.assertClosedExchanges()
    }

    fun assertWire() {
        val d = http.journal.declaration()
        val writer = d.writer.generationId
        val prefix = "complaints/journal/v1/$writer/live/00000000-0000-0000-0000-000000000000/seal-terminal/"
        val fields = listOf(
            "kira-complaint-journal-epoch-seal-v1", "1", "key", writer, prefix, "LIVE", "00000000-0000-0000-0000-000000000000",
            "1", "42", "0", content.payload.eventManifestSha256, "", content.route.routingKeyId,
        )
        val opaqueKey = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(EpochSealTestFixtureV1.material(content.route.routingKeyId), "HmacSHA256"))
            AwsJournalKmsFixture.url(doFinal(AwsJournalKmsFixture.frame(fields)))
        }
        assertEquals("${prefix}writer/$writer/epoch/0000000000000000042/${content.route.routingKeyId}/$opaqueKey", content.route.objectKey)
        http.requests.forEach { request ->
            http.assertSigned(request) // Recomputes actual SigV4, including the signed payload and metadata.
            val wire = request.http
            if (request.kind == "LIST") {
                assertEquals(SdkHttpMethod.GET, wire.method())
                assertTrue(wire.encodedPath() in listOf("/${d.journalLocation.bucket}", "/${d.journalLocation.bucket}/"))
                assertEquals(content.route.objectKey, wire.firstMatchingRawQueryParameter("prefix").orElseThrow())
                assertEquals("2", wire.firstMatchingRawQueryParameter("max-keys").orElseThrow())
                assertEquals("url", wire.firstMatchingRawQueryParameter("encoding-type").orElseThrow())
                assertTrue(wire.rawQueryParameters().keys.all { it in setOf("versions", "prefix", "max-keys", "encoding-type", "x-id") })
            } else {
                assertEquals("/${d.journalLocation.bucket}/${content.route.objectKey}", wire.encodedPath())
                if (request.kind == "GET") {
                    assertEquals(SdkHttpMethod.GET, wire.method())
                    assertEquals(VERSION, wire.firstMatchingRawQueryParameter("versionId").orElseThrow())
                    assertEquals("ENABLED", request.header("x-amz-checksum-mode"))
                    assertTrue(wire.rawQueryParameters().keys.all { it in setOf("versionId", "x-id") })
                } else {
                    assertEquals(SdkHttpMethod.PUT, wire.method())
                    assertArrayEquals(envelope.wireBytes(), request.body)
                    assertEquals("*", request.header("If-None-Match"))
                    assertEquals(checksum(request.body), request.header("x-amz-checksum-sha256"))
                    assertEquals("SHA256", request.header("x-amz-sdk-checksum-algorithm"))
                    assertEquals(request.body.size.toString(), request.header("Content-Length"))
                    assertEquals("application/octet-stream", request.header("Content-Type"))
                    assertEquals("COMPLIANCE", request.header("x-amz-object-lock-mode"))
                    assertEquals(requested.toString(), request.header("x-amz-object-lock-retain-until-date"))
                    val observedMetadata = wire.headers().filterKeys { it.startsWith("x-amz-meta-", ignoreCase = true) }
                        .mapKeys { it.key.lowercase() }.mapValues { it.value.single() }
                    assertEquals(metadata().mapKeys { "x-amz-meta-${it.key}" }, observedMetadata)
                    assertTrue(wire.rawQueryParameters().keys.all { it == "x-id" })
                }
            }
        }
    }

    fun assertSealKmsContext() {
        val d = http.journal.declaration()
        val writer = d.writer.generationId
        val fields = listOf(
            "kira-complaint-journal-kms-context-v1", "1", "1", "1", "kcj-1", "EPOCH_SEAL", "AES-256-GCM", "FRESH_PER_OBJECT_KMS_WRAPPED",
            d.encryption.keyId, d.encryption.keyArn, d.journalLocation.bucket, content.route.objectKey, writer,
            "complaints/journal/v1/$writer/live/00000000-0000-0000-0000-000000000000/seal-terminal/",
            "LIVE", "00000000-0000-0000-0000-000000000000", "1", "42", content.route.routingKeyId, content.route.sealId,
            "", AwsJournalKmsFixture.url(ByteArray(12) { it.toByte() }),
        )
        assertEquals(22, fields.size)
        val encoded = AwsJournalKmsFixture.url(AwsJournalKmsFixture.frame(fields))
        assertEquals(listOf(AwsJournalKmsFixture.GENERATE_TARGET, AwsJournalKmsFixture.DECRYPT_TARGET), http.kms.requests.map { it.target() })
        http.kms.requests.forEach {
            assertEquals(1, it.fields()["EncryptionContext"].size())
            assertEquals(encoded, it.fields()["EncryptionContext"][AwsJournalKmsFixture.CONTEXT_KEY].textValue())
            assertEquals(d.encryption.keyArn, it.fields()["KeyId"].textValue())
            assertTrue(it.http.firstMatchingHeader("Authorization").orElseThrow().startsWith("AWS4-HMAC-SHA256 "))
        }
    }

    override fun close() {
        try {
            binding.close()
        } finally {
            keys.close()
        }
        http.assertClosedExchanges()
        assertEquals(http.s3ClientsCreated, http.s3ClientsClosed)
        assertEquals(http.kms.createdClients, http.kms.closedClients)
        http.kms.replies.forEach { assertEquals(1, it.closes) }
    }

    companion object {
        const val VERSION = "seal-%2F+&=version-1"
    }
}
