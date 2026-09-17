package me.manga.kira.backend.complaint.journal

import com.fasterxml.jackson.databind.JsonNode
import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteAllAuthorizationFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.S3CatalogReply
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllStore
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllJournalPublisherV1
import me.manga.kira.backend.security.ComplaintJournalDeletionTupleV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalCodecV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalEventV1
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import me.manga.kira.backend.security.aws.AwsJournalDataKeyAdapter
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.JournalKmsHttpReply
import me.manga.kira.backend.security.aws.JournalKmsHttpRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.HttpExecuteResponse
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Existing genuine PG producer + genuine S3/KMS SDKs and codec. Only raw HTTP replies/key material
 * are synthetic. The expected canonical event below is fixture data, never a Prepared substitute.
 * No listener, AWS call, alternate launcher or new persistence harness is involved.
 */
internal class OwnerDeleteAllJournalPublisherFixture private constructor(
    private val authorization: OwnerDeleteAllAuthorizationFixture?,
    private val authorizedCandidate: InstallationDeletionCandidate?,
    val targets: List<UUID>,
    val routing: VersionBoundComplaintJournalRouting,
    val event: OwnerDeleteAllJournalEventV1,
) {
    constructor(
        auth: OwnerDeleteAllAuthorizationFixture,
        candidate: InstallationDeletionCandidate,
        targets: List<UUID>,
        selectedRoutingKeyId: String? = null,
    ) : this(auth, candidate, targets, auth.routing, auth.codec.canonicalize(auth.journalTuple(candidate), targets, selectedRoutingKeyId))

    val auth: OwnerDeleteAllAuthorizationFixture get() = checkNotNull(authorization)
    val candidate: InstallationDeletionCandidate get() = checkNotNull(authorizedCandidate)
    val journal = routing.journalConfiguration
    val kms = AwsJournalKmsFixture(journal)
    val requests = mutableListOf<JournalPublisherHttpRequest>()
    val keys = HashMap<String, SyntheticKey>()
    var stored: JournalPublisherObject? = null
    var nanos = 0L
    var wall: Instant = Instant.parse("2030-01-02T03:04:05.123456789Z")
    var s3ClientsCreated = 0
    var s3ClientsClosed = 0
    var beforePrepare: () -> Unit = {}
    var afterPrepare: () -> Unit = {}
    var onClientClose: () -> Unit = {}
    var respond: (JournalPublisherHttpRequest) -> S3CatalogReply = ::statefulReply
    private var keySerial = 0
    val clock: Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this.also { require(zone == ZoneOffset.UTC) }
        override fun instant(): Instant = wall
    }

    init {
        kms.respond = ::keyReply
        kms.beforePrepare = {
            requireConnectionFree()
            assertClosedExchanges()
        }
        kms.afterPrepare = {}
        kms.onClientClose = {}
    }

    fun publisher(
        store: JdbcComplaintOwnerDeleteAllStore = auth.store,
        selected: VersionBoundComplaintJournalRouting = routing,
        credentials: AwsSessionCredentials = CREDENTIALS,
    ): OwnerDeleteAllJournalPublisherV1 = OwnerDeleteAllJournalPublisherV1.withHttpFixture(
        store,
        selected,
        credentials,
        ::httpClient,
        kms::httpClient,
        clock,
        { nanos },
    )

    fun httpClient(): SdkHttpClient {
        s3ClientsCreated++
        return object : SdkHttpClient {
            override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest {
                requireConnectionFree()
                beforePrepare()
                val bytes = request.contentStreamProvider().orElse(null)?.newStream()?.use { it.readNBytes(98_305) } ?: ByteArray(0)
                check(bytes.size <= 98_304)
                val captured = JournalPublisherHttpRequest(request.httpRequest(), bytes).also { requests.add(it) }
                val result = object : ExecutableHttpRequest {
                    override fun call(): HttpExecuteResponse {
                        captured.calls++
                        val reply = respond(captured).also { captured.reply = it }
                        reply.calls++
                        reply.beforeCall()
                        return reply.response().also { captured.responseReturned = true }
                    }

                    override fun abort() {
                        captured.aborts++
                        captured.reply?.let {
                            it.aborts++
                            it.onAbort()
                        }
                    }
                }
                afterPrepare()
                return result
            }

            override fun close() {
                s3ClientsClosed++
                onClientClose()
            }

            override fun clientName(): String = "SyntheticOrdinaryJournalSync"
        }
    }

    fun statefulReply(request: JournalPublisherHttpRequest): S3CatalogReply = when (request.kind) {
        "LIST" -> listReply(listOfNotNull(stored))

        "GET" -> getReply(checkNotNull(stored))

        else -> {
            if (stored != null) {
                errorReply(412)
            } else {
                val value = JournalPublisherObject(
                    event.route.objectKey,
                    VERSION,
                    request.body.copyOf(),
                    wall.truncatedTo(ChronoUnit.SECONDS),
                    Instant.parse(request.header("x-amz-object-lock-retain-until-date")),
                    request.http.headers().entries.filter { it.key.startsWith("x-amz-meta-", ignoreCase = true) }
                        .associate { it.key.lowercase().removePrefix("x-amz-meta-") to it.value.single() },
                )
                stored = value
                putReply(value)
            }
        }
    }

    fun listReply(versions: List<JournalPublisherObject> = listOfNotNull(stored), exactKey: String = event.route.objectKey): S3CatalogReply =
        xmlReply(listDocument(versions, exactKey))

    fun listDocument(versions: List<JournalPublisherObject> = listOfNotNull(stored), exactKey: String = event.route.objectKey): String = buildString {
        append("<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">")
        append("<Name>${journal.declaration().journalLocation.bucket}</Name><Prefix>${encoded(exactKey)}</Prefix>")
        append("<KeyMarker></KeyMarker><VersionIdMarker></VersionIdMarker><MaxKeys>2</MaxKeys><IsTruncated>false</IsTruncated><EncodingType>url</EncodingType>")
        versions.forEach {
            append("<Version><Key>${encoded(it.key)}</Key><VersionId>${xml(it.version)}</VersionId><IsLatest>true</IsLatest>")
            append("<LastModified>${it.lastModified}</LastModified><Size>${it.bytes.size}</Size><StorageClass>STANDARD</StorageClass></Version>")
        }
        append("</ListVersionsResult>")
    }

    fun getReply(value: JournalPublisherObject = checkNotNull(stored)): S3CatalogReply = S3CatalogReply(value.bytes).apply {
        headers = headers + mapOf(
            "Content-Type" to listOf("application/octet-stream"),
            "x-amz-version-id" to listOf(value.version),
            "x-amz-bucket-region" to listOf(journal.declaration().journalLocation.region),
            "x-amz-checksum-sha256" to listOf(checksum(value.bytes)),
            "x-amz-checksum-type" to listOf("FULL_OBJECT"),
            "Last-Modified" to listOf(DateTimeFormatter.RFC_1123_DATE_TIME.format(value.lastModified.atZone(ZoneOffset.UTC))),
            "x-amz-object-lock-mode" to listOf("COMPLIANCE"),
            "x-amz-object-lock-retain-until-date" to listOf(value.retainUntil.toString()),
        ) + value.metadata.mapKeys { "x-amz-meta-${it.key}" }.mapValues { listOf(it.value) }
    }

    fun putReply(value: JournalPublisherObject = checkNotNull(stored)): S3CatalogReply = S3CatalogReply(ByteArray(0)).apply {
        headers = headers + mapOf(
            "x-amz-version-id" to listOf(value.version),
            "x-amz-checksum-sha256" to listOf(checksum(value.bytes)),
            "x-amz-checksum-type" to listOf("FULL_OBJECT"),
        )
    }

    fun objectFor(bytes: ByteArray, version: String = VERSION): JournalPublisherObject {
        val created = wall.truncatedTo(ChronoUnit.SECONDS)
        val retention = created.plusSeconds(journal.declaration().limits.retention.ordinaryRetentionSeconds + 30)
        return JournalPublisherObject(
            event.route.objectKey,
            version,
            bytes.copyOf(),
            created,
            retention,
            mapOf(
                "kira-journal-schema" to "1",
                "kira-journal-event-id" to event.route.eventId,
                "kira-journal-ciphertext-sha256" to hash(bytes),
                "kira-journal-retain-until" to retention.toString(),
            ),
        )
    }

    /** Actual codec/AES-GCM output for adversarial provider replies, not publication permission. */
    fun envelope(ids: List<UUID> = targets, tuple: ComplaintJournalDeletionTupleV1 = event.tuple): ByteArray =
        AwsJournalDataKeyAdapter.withHttpFixture(journal, CREDENTIALS, kms::httpClient) { nanos }.use { keys ->
            val codec = OwnerDeleteAllJournalCodecV1(routing, keys, nanoTime = { nanos })
            codec.seal(codec.canonicalize(tuple, ids, event.route.routingKeyId), codec.startAttempt()).wireBytes()
        }

    fun reset() {
        assertClosedExchanges()
        requests.clear()
        nanos = 0
        stored = null
        beforePrepare = {}
        afterPrepare = {}
        onClientClose = {}
        respond = ::statefulReply
        kms.respond = ::keyReply
        kms.beforePrepare = {
            requireConnectionFree()
            assertClosedExchanges()
        }
        kms.afterPrepare = {}
        kms.onClientClose = {}
    }

    fun generated(): Int = kms.requests.count { it.target() == AwsJournalKmsFixture.GENERATE_TARGET }
    fun decrypted(): Int = kms.requests.count { it.target() == AwsJournalKmsFixture.DECRYPT_TARGET }

    fun assertClosedExchanges() {
        requests.forEach { request ->
            assertEquals(1, request.calls)
            assertEquals(1, request.aborts)
            request.reply?.let {
                assertEquals(if (request.responseReturned && it.bodyPresent) 1 else 0, it.closes)
            }
        }
    }

    fun assertSigned(request: JournalPublisherHttpRequest) {
        val http = request.http
        val location = journal.declaration().journalLocation
        assertEquals("https", http.protocol())
        assertEquals("s3.${location.region}.amazonaws.com", http.host())
        assertEquals(443, http.port())
        assertEquals(location.accountId, request.header("x-amz-expected-bucket-owner"))
        assertEquals(CREDENTIALS.sessionToken(), request.header("x-amz-security-token"))
        assertEquals(hash(request.body), request.header("x-amz-content-sha256"))
        val authorization = request.header("Authorization")
        val scope = authorization.substringAfter("Credential=${CREDENTIALS.accessKeyId()}/").substringBefore(',')
        val signed = authorization.substringAfter("SignedHeaders=").substringBefore(',').split(';')
        val query = http.rawQueryParameters().flatMap { (key, values) ->
            (if (values.isEmpty()) listOf("") else values).map { encodedQuery(key) + "=" + encodedQuery(it.orEmpty()) }
        }.sorted().joinToString("&")
        val headers = signed.joinToString("") { "$it:${request.header(it).trim().replace(Regex("[ \\t]+"), " ")}\n" }
        val canonical = "${http.method()}\n${http.encodedPath()}\n$query\n$headers\n${signed.joinToString(";")}\n${hash(request.body)}"
        val date = request.header("x-amz-date")
        val signingDate = hmac(("AWS4" + CREDENTIALS.secretAccessKey()).toByteArray(), date.take(8))
        val signingRegion = hmac(signingDate, location.region)
        val signingService = hmac(signingRegion, "s3")
        val signingKey = hmac(signingService, "aws4_request")
        val signature = hmac(signingKey, "AWS4-HMAC-SHA256\n$date\n$scope\n${hash(canonical.toByteArray())}")
        assertEquals(HexFormat.of().formatHex(signature), authorization.substringAfter("Signature="))
        assertTrue(signed.containsAll(listOf("host", "x-amz-date", "x-amz-security-token", "x-amz-expected-bucket-owner")))
        listOf("Range", "Transfer-Encoding", "Content-Encoding", "x-amz-trailer", "x-amz-decoded-content-length").forEach {
            assertFalse(http.firstMatchingHeader(it).isPresent)
        }
        assertEquals("1", request.header("amz-sdk-request").substringAfter("attempt=").substringBefore(';'))
    }

    private fun keyReply(request: JournalKmsHttpRequest): JournalKmsHttpReply {
        val fields = request.fields()
        val context = fields.get("EncryptionContext")
        return if (request.target() == AwsJournalKmsFixture.GENERATE_TARGET) {
            val serial = ++keySerial
            val key = ByteArray(32) { (it * 17 + serial).toByte() }
            val wrapped = ByteArray(64) { (it * 11 + serial).toByte() }
            keys[Base64.getEncoder().encodeToString(wrapped)] = SyntheticKey(key, context)
            JournalKmsHttpReply(AwsJournalKmsFixture.generateDocument(journal, key, wrapped))
        } else {
            val value = checkNotNull(keys[fields.get("CiphertextBlob").asText()])
            assertEquals(value.context, context)
            JournalKmsHttpReply(AwsJournalKmsFixture.decryptDocument(journal, value.bytes))
        }
    }

    internal class SyntheticKey(val bytes: ByteArray, val context: JsonNode)

    companion object {
        /** Raw HTTP/key material only. This path cannot manufacture API work, a SQL issuer or readback evidence. */
        fun historical(routing: VersionBoundComplaintJournalRouting, event: OwnerDeleteAllJournalEventV1): OwnerDeleteAllJournalPublisherFixture {
            require(event.belongsTo(routing))
            return OwnerDeleteAllJournalPublisherFixture(null, null, event.complaintIds(), routing, event)
        }

        const val VERSION = "ordinary-%2F+&=version-1"
        const val PRIVATE_TEXT = "synthetic-private-journal-publication-provider-text"
        val CREDENTIALS: AwsSessionCredentials = AwsSessionCredentials.create(
            "SYNTHETICJOURNALACCESS",
            "synthetic-journal-not-a-real-secret",
            "synthetic-journal-session",
        )

        fun checksum(bytes: ByteArray): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        fun encoded(value: String): String = xml(encodedQuery(value))
        fun encodedQuery(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%7E", "~")
        fun xmlReply(document: String): S3CatalogReply = S3CatalogReply(document.toByteArray()).apply {
            headers =
                headers + ("Content-Type" to listOf("application/xml"))
        }
        fun errorReply(statusCode: Int): S3CatalogReply = xmlReply("<Error><Code>ConditionalRequestConflict</Code><Message>synthetic</Message></Error>")
            .apply { status = statusCode }

        fun padXml(document: String, size: Int): String {
            val padding = size - document.toByteArray().size - 7
            require(padding >= 0)
            val position = document.lastIndexOf("</")
            return document.substring(0, position) + "<!--" + " ".repeat(padding) + "-->" + document.substring(position)
        }

        fun assertRedacted(failure: Throwable) {
            assertFalse(failure.toString().contains(PRIVATE_TEXT))
            assertNull(failure.cause)
            assertTrue(failure.suppressed.isEmpty())
        }

        private fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value.toByteArray())
        }
    }
}

internal class JournalPublisherHttpRequest(val http: SdkHttpRequest, val body: ByteArray) {
    val kind: String = when {
        http.method() == SdkHttpMethod.PUT -> "PUT"
        http.rawQueryParameters().containsKey("versions") -> "LIST"
        else -> "GET"
    }
    var calls = 0
    var aborts = 0
    var reply: S3CatalogReply? = null
    var responseReturned = false
    fun header(name: String): String = http.firstMatchingHeader(name).orElseThrow()
    override fun toString(): String = "JournalPublisherHttpRequest(synthetic,redacted)"
}

internal data class JournalPublisherObject(
    val key: String,
    val version: String,
    val bytes: ByteArray,
    val lastModified: Instant,
    val retainUntil: Instant,
    val metadata: Map<String, String>,
) {
    override fun toString(): String = "JournalPublisherObject(synthetic,redacted)"
}
