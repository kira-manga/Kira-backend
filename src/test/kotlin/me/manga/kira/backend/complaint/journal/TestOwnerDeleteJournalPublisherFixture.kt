package me.manga.kira.backend.complaint.journal

import com.fasterxml.jackson.databind.JsonNode
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.S3CatalogReply
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllStore
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteAllJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.errorReply
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.hash
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture.Companion.xmlReply
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.AwsTestOwnerDeleteDataKeyAdapterV1
import me.manga.kira.backend.security.aws.JournalKmsHttpReply
import me.manga.kira.backend.security.aws.JournalKmsHttpRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TEST raw replies on the existing S3/KMS HTTP SPI fixtures, not another transport/pool harness.
 * The event is expected content only. This class cannot issue committed work, readback or VERIFIED;
 * those must come from the real SQL phase and the separate real TEST SDK publisher respectively.
 * Synthetic control/activation rows are deliberately not represented as provider authority here.
 */
internal class TestOwnerDeleteJournalPublisherFixture(
    val routing: TestOwnerDeleteJournalRoutingV1,
    val event: TestOwnerDeleteJournalEventV1,
) {
    val journal = routing.journalConfiguration
    // Only the existing raw HTTP client is reused. Its LIVE adapter/profile is never called for TEST.
    val kms = AwsJournalKmsFixture()
    val requests = CopyOnWriteArrayList<JournalPublisherHttpRequest>()
    val objects = CopyOnWriteArrayList<JournalPublisherObject>()
    private val keys = HashMap<String, SyntheticKey>()
    private var keySerial = 0
    var nanos = 0L
    var wall: Instant = Instant.parse("2030-01-02T03:04:05Z")
    var s3ClientsCreated = 0
    var s3ClientsClosed = 0
    var beforeOpen: () -> Unit = {}
    var beforePrepare: () -> Unit = {}
    var afterPrepare: () -> Unit = {}
    var onClientClose: () -> Unit = {}
    var respond: (JournalPublisherHttpRequest) -> S3CatalogReply = ::statefulReply
    val clock: Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this.also { require(zone == ZoneOffset.UTC) }
        override fun instant(): Instant = wall
    }

    var stored: JournalPublisherObject?
        get() = objects.singleOrNull { it.key == event.route.objectKey }
        set(value) {
            objects.removeIf { it.key == event.route.objectKey }
            value?.let(objects::add)
        }

    init {
        require(event.belongsTo(routing))
        kms.respond = ::keyReply
        kms.beforePrepare = {
            requireConnectionFree()
            assertS3ExchangesClosed()
            beforePrepare()
        }
    }

    fun factory(store: JdbcComplaintOwnerDeleteStore, lanes: JournalPublicationLanesV1): TestOwnerDeleteJournalPublisherFactoryV1 =
        TestOwnerDeleteJournalPublisherFactoryV1.withHttpFixture(
            lanes, store, routing, CREDENTIALS, { beforeOpen(); httpClient() }, kms::httpClient, clock, { nanos },
        )

    fun factory(store: JdbcComplaintOwnerDeleteAllStore, lanes: JournalPublicationLanesV1): TestOwnerDeleteAllJournalPublisherFactoryV1 =
        TestOwnerDeleteAllJournalPublisherFactoryV1.withHttpFixture(
            lanes, store, routing, CREDENTIALS, { beforeOpen(); httpClient() }, kms::httpClient, clock, { nanos },
        )

    fun httpClient(): SdkHttpClient {
        s3ClientsCreated++
        return journalPublisherRawHttpClient(
            requests,
            { beforePrepare() },
            { afterPrepare() },
            {
                s3ClientsClosed++
                onClientClose()
            },
            { respond(it) },
        )
    }

    fun statefulReply(request: JournalPublisherHttpRequest): S3CatalogReply = when (request.kind) {
        "LIST" -> {
            val key = request.http.rawQueryParameters().getValue("prefix").single()
            listReply(objects.filter { it.key == key }, key)
        }
        "GET" -> {
            val key = objectKey(request)
            val version = request.http.rawQueryParameters().getValue("versionId").single()
            objects.singleOrNull { it.key == key && it.version == version }?.let(::getReply) ?: errorReply(404)
        }
        else -> {
            val key = objectKey(request)
            if (objects.any { it.key == key }) {
                errorReply(412)
            } else {
                val value = JournalPublisherObject(
                    key,
                    VERSION,
                    request.body.copyOf(),
                    wall.truncatedTo(ChronoUnit.SECONDS),
                    Instant.parse(request.header("x-amz-object-lock-retain-until-date")),
                    request.http.headers().entries.filter { it.key.startsWith("x-amz-meta-", ignoreCase = true) }
                        .associate { it.key.lowercase().removePrefix("x-amz-meta-") to it.value.single() },
                )
                objects.add(value)
                journalPublisherRawPutReply(value)
            }
        }
    }

    fun listReply(versions: List<JournalPublisherObject> = listOfNotNull(stored), exactKey: String = event.route.objectKey): S3CatalogReply =
        xmlReply(listDocument(versions, exactKey))

    fun listDocument(versions: List<JournalPublisherObject> = listOfNotNull(stored), exactKey: String = event.route.objectKey): String =
        journalPublisherRawListDocument(journal.declaration().journalLocation.bucket, exactKey, versions)

    fun getReply(value: JournalPublisherObject = checkNotNull(stored)): S3CatalogReply =
        journalPublisherRawGetReply(journal.declaration().journalLocation.region, value)

    fun objectFor(
        bytes: ByteArray,
        selected: TestOwnerDeleteJournalEventV1 = event,
        version: String = VERSION,
    ): JournalPublisherObject {
        require(selected.belongsTo(routing))
        val created = wall.truncatedTo(ChronoUnit.SECONDS)
        val retention = created.plusSeconds(journal.declaration().limits.retention.ordinaryRetentionSeconds + 30)
        return JournalPublisherObject(
            selected.route.objectKey,
            version,
            bytes.copyOf(),
            created,
            retention,
            mapOf(
                "kira-journal-schema" to "1",
                "kira-journal-event-id" to selected.route.eventId,
                "kira-journal-ciphertext-sha256" to hash(bytes),
                "kira-journal-retain-until" to retention.toString(),
            ),
        )
    }

    /** Real codec/KMS SDK bytes for hostile provider replies; sealing itself is explicitly not readback custody. */
    fun envelope(selected: TestOwnerDeleteJournalEventV1 = event): ByteArray =
        AwsTestOwnerDeleteDataKeyAdapterV1.withHttpFixture(journal, CREDENTIALS, kms::httpClient) { nanos }.use { dataKeys ->
            val codec = TestOwnerDeleteJournalCodecV1(routing, dataKeys, nanoTime = { nanos })
            codec.seal(selected, codec.startAttempt()).wireBytes()
        }

    fun generated(): Int = kms.requests.count { it.target() == AwsJournalKmsFixture.GENERATE_TARGET }
    fun decrypted(): Int = kms.requests.count { it.target() == AwsJournalKmsFixture.DECRYPT_TARGET }

    fun assertS3ExchangesClosed() {
        requests.forEach { request ->
            assertEquals(1, request.calls)
            assertEquals(1, request.aborts)
            request.reply?.let { assertEquals(if (request.responseReturned && it.bodyPresent) 1 else 0, it.closes) }
        }
    }

    fun assertClientsClosed() {
        assertS3ExchangesClosed()
        assertEquals(s3ClientsCreated, s3ClientsClosed)
        assertEquals(kms.createdClients, kms.closedClients)
        assertEquals(kms.createdClients, kms.returnedClientCloses)
        kms.replies.forEach {
            assertEquals(1, it.calls)
            assertEquals(1, it.aborts)
            assertEquals(if (it.bodyPresent) 1 else 0, it.closes)
        }
    }

    fun assertSigned(request: JournalPublisherHttpRequest) {
        val location = journal.declaration().journalLocation
        journalPublisherRawAssertSigned(request, location.region, location.accountId, CREDENTIALS)
        assertFalse(request.http.encodedPath().contains("/live/"))
        assertFalse(request.http.rawQueryParameters().containsKey("delete"))
    }

    /** Raw independent LP field order; product encoder/profile output is not used for the expectation. */
    fun assertKmsContext(request: JournalKmsHttpRequest, selected: TestOwnerDeleteJournalEventV1 = event) {
        val body = request.fields()
        assertEquals(journal.declaration().encryption.keyArn, body["KeyId"].asText())
        val context = body["EncryptionContext"]
        assertEquals(setOf(AwsJournalKmsFixture.CONTEXT_KEY), context.fieldNames().asSequence().toSet())
        val encoded = context[AwsJournalKmsFixture.CONTEXT_KEY].asText()
        val fields = DataInputStream(ByteArrayInputStream(Base64.getUrlDecoder().decode(encoded))).use { input ->
            buildList {
                while (input.available() != 0) {
                    val size = input.readInt()
                    assertTrue(size in 1..4096)
                    val bytes = input.readNBytes(size)
                    assertEquals(size, bytes.size)
                    add(bytes.toString(Charsets.UTF_8))
                }
            }
        }
        assertEquals(20, fields.size)
        assertEquals(16, fields.last().length)
        assertEquals(
            AwsJournalKmsFixture.testOwnerDeleteFields(
                journal, selected.route.objectKey, selected.route.eventId, selected.comparison.epoch, selected.route.routingKeyId, fields.last(),
            ).toMutableList().also { it[5] = selected.comparison.eventKind.name },
            fields,
        )
        assertEquals(AwsJournalKmsFixture.url(AwsJournalKmsFixture.frame(fields)), encoded)
        if (request.target() == AwsJournalKmsFixture.GENERATE_TARGET) {
            assertEquals("AES_256", body["KeySpec"].asText())
            assertEquals(setOf("KeyId", "KeySpec", "EncryptionContext"), body.fieldNames().asSequence().toSet())
        } else {
            assertEquals("SYMMETRIC_DEFAULT", body["EncryptionAlgorithm"].asText())
            assertEquals(setOf("KeyId", "CiphertextBlob", "EncryptionAlgorithm", "EncryptionContext"), body.fieldNames().asSequence().toSet())
        }
    }

    private fun objectKey(request: JournalPublisherHttpRequest): String {
        val prefix = "/${journal.declaration().journalLocation.bucket}/"
        check(request.http.encodedPath().startsWith(prefix))
        return URLDecoder.decode(request.http.encodedPath().removePrefix(prefix), StandardCharsets.UTF_8)
    }

    private fun keyReply(request: JournalKmsHttpRequest): JournalKmsHttpReply {
        val fields = request.fields()
        val context = fields["EncryptionContext"]
        val arn = journal.declaration().encryption.keyArn
        return if (request.target() == AwsJournalKmsFixture.GENERATE_TARGET) {
            val serial = ++keySerial
            val key = ByteArray(32) { (it * 17 + serial).toByte() }
            val wrapped = ByteArray(64) { (it * 11 + serial).toByte() }
            keys[Base64.getEncoder().encodeToString(wrapped)] = SyntheticKey(key, context)
            JournalKmsHttpReply(AwsJournalKmsFixture.generateDocument(arn, key, wrapped))
        } else {
            val saved = checkNotNull(keys[fields["CiphertextBlob"].asText()])
            assertEquals(saved.context, context)
            JournalKmsHttpReply(AwsJournalKmsFixture.decryptDocument(arn, saved.bytes))
        }
    }

    private class SyntheticKey(val bytes: ByteArray, val context: JsonNode)

    companion object {
        const val VERSION = "test-ordinary-%2F+&=version-1"
        const val PRIVATE_TEXT = "synthetic-private-test-owner-delete-provider-text"
        val CREDENTIALS: AwsSessionCredentials = AwsSessionCredentials.create(
            "SYNTHETICTESTJOURNAL", "synthetic-test-journal-not-a-real-secret", "synthetic-test-journal-session",
        )
    }
}
