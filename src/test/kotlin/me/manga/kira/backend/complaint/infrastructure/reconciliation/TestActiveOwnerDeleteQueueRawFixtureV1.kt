package me.manga.kira.backend.complaint.infrastructure.reconciliation

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.NeverOwnerDeleteAllDataKeys
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.S3CatalogReply
import me.manga.kira.backend.complaint.catalog.TestOrdinarySealHttpFixtureV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveOwnerDeleteQueueInputV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveOwnerDeleteQueueStorageV1
import me.manga.kira.backend.complaint.journal.JournalPublisherHttpRequest
import me.manga.kira.backend.complaint.journal.JournalPublisherObject
import me.manga.kira.backend.complaint.journal.journalPublisherRawAssertSigned
import me.manga.kira.backend.complaint.journal.journalPublisherRawGetReply
import me.manga.kira.backend.complaint.journal.journalPublisherRawHttpClient
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalTupleV1
import me.manga.kira.backend.security.TestTerminalCryptoReferenceV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.JournalKmsHttpReply
import me.manga.kira.backend.security.aws.JournalKmsHttpRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpClient
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Immutable TEST raw recipes chosen before protected intake/full D. No SQL or admitted result seam. */
internal class TestActiveOwnerDeleteQueueHttpInputV1(
    val sts: (remainingMillis: () -> Int) -> SdkHttpClient,
    val kms: (remainingMillis: () -> Int) -> SdkHttpClient,
    val s3: (remainingMillis: () -> Int) -> SdkHttpClient,
    val sqs: (remainingMillis: () -> Int) -> SdkHttpClient,
) {
    val input = TestActiveOwnerDeleteQueueInputV1(1, TestActiveOwnerDeleteQueueStorageV1.PROFILE, 10_000, SESSION)
    val credentials: AwsSessionCredentials get() = CREDENTIALS
    companion object {
        const val SESSION = "synthetic-active-owner-delete-queue"
        val CREDENTIALS: AwsSessionCredentials = AwsSessionCredentials.create("SYNTHETICACTIVEQUEUEKEY",
            "synthetic-active-queue-secret-not-a-real-credential", "synthetic-active-queue-session")
    }
}

/**
 * Real SDK/SigV4 + bounded MAIN native transport + codec/decrypt, substituted public HTTP SPI only.
 * The synthetic external object has NO local N/P/L or authority handle. Its JCE wire framing and
 * KMS-context expectation are independent of MAIN encryption. No real AWS/two-process claim.
 */
internal class TestActiveOwnerDeleteQueueRawFixtureV1 {
    private var fixture: TestActiveOwnerDeleteQueueFixtureV1? = null
    private val assertion = AtomicReference<AssertionError?>()
    val sts = AwsJournalKmsFixture()
    val kms = AwsJournalKmsFixture()
    val sqs = AwsJournalKmsFixture()
    val requests = mutableListOf<JournalPublisherHttpRequest>()
    val order = mutableListOf<String>()
    val budgets = mutableListOf<Pair<String, Int>>()
    val ackRequests = mutableListOf<String>()
    private var primaryDeliveries = 0
    private var dlqDeliveries = 0
    var s3Created = 0
        private set
    var s3Closed = 0
        private set
    var s3CloseReturned = 0
        private set
    var primaryBody: String? = null
    var dlqBody: String? = null
    var beforeSqs: (JournalKmsHttpRequest) -> Unit = {}
    var changeSqs: (JournalKmsHttpRequest, JournalKmsHttpReply) -> Unit = { _, _ -> }
    var changeS3: (JournalPublisherHttpRequest, S3CatalogReply) -> Unit = { _, _ -> }
    var wrongPrincipal = false
    var onNativeClose: (String) -> Unit = {}
    lateinit var event: TestOwnerDeleteJournalEventV1
        private set
    lateinit var stored: JournalPublisherObject
        private set
    private var context = emptyMap<String, String>()
    private val wrapped = AwsJournalKmsFixture.wrappedBytes()
    private val mapper = ObjectMapper()
    val input = TestActiveOwnerDeleteQueueHttpInputV1(
        { remaining -> native("STS", remaining, sts::httpClient) },
        { remaining -> native("KMS", remaining, kms::httpClient) },
        { remaining -> native("S3", remaining, ::s3Client) },
        { remaining -> native("SQS", remaining, sqs::httpClient) },
    )

    init {
        listOf("STS" to sts, "KMS" to kms, "SQS" to sqs).forEach { (kind, raw) ->
            raw.beforePrepare = { checked { boundary() } }
            raw.onClientClose = { checked { closeBoundary(kind) } }
        }
        sts.respond = { request -> checked {
            boundary(); signed(request, "sts"); order.add("STS")
            assertEquals(mapOf("Action" to "GetCallerIdentity", "Version" to "2011-06-15"), TestOrdinarySealHttpFixtureV1.query(request))
            val account = TestOrdinarySealHttpFixtureV1.ACCOUNT
            val session = TestActiveOwnerDeleteQueueHttpInputV1.SESSION
            val role = if (wrongPrincipal) "test-ordinary" else "test-recovery"
            JournalKmsHttpReply("""<GetCallerIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/"><GetCallerIdentityResult>""" +
                "<Arn>arn:aws:sts::$account:assumed-role/$role/$session</Arn><UserId>AROA${"C".repeat(17)}:$session</UserId><Account>$account</Account></GetCallerIdentityResult>" +
                "<ResponseMetadata><RequestId>active-queue-identity</RequestId></ResponseMetadata></GetCallerIdentityResponse>").apply {
                headers = mapOf("Content-Type" to listOf("text/xml; charset=utf-8"), "Content-Length" to listOf(bytes.size.toString()))
            }
        } }
        kms.respond = { request -> checked {
            boundary(); signed(request, "kms"); order.add("DECRYPT")
            assertEquals(AwsJournalKmsFixture.DECRYPT_TARGET, request.target(), "Queue recovery cannot generate a key or PUT a journal.")
            val fields = request.fields()
            assertEquals(context, fields["EncryptionContext"].fields().asSequence().associate { it.key to it.value.textValue() })
            assertEquals(Base64.getEncoder().encodeToString(wrapped), fields["CiphertextBlob"].textValue())
            val key = checkNotNull(fixture).process.consumers.journalConfiguration.declaration().encryption.keyArn
            assertEquals(key, fields["KeyId"].textValue())
            JournalKmsHttpReply(AwsJournalKmsFixture.decryptDocument(key, AwsJournalKmsFixture.keyBytes()))
        } }
        sqs.respond = { request -> checked { sqsReply(request) } }
    }

    fun attach(f: TestActiveOwnerDeleteQueueFixtureV1) { check(fixture == null); fixture = f; externalObject() }
    fun detach(f: TestActiveOwnerDeleteQueueFixtureV1) { check(fixture === f); fixture = null }
    fun resetFaults() { beforeSqs = {}; changeSqs = { _, _ -> }; changeS3 = { _, _ -> }; wrongPrincipal = false; onNativeClose = {} }

    /** Synthetic provider input, not a current-state seed or a verification/APPLY issuer. */
    fun externalObject(kind: ComplaintJournalDeletionKindV1 = ComplaintJournalDeletionKindV1.OWNER_DELETE) {
        val f = checkNotNull(fixture)
        val routing = f.process.consumers.journalRouting
        val j = routing.journalConfiguration
        val tuple = TestOwnerDeleteJournalTupleV1(1, UUID.randomUUID(), 1, UUID.randomUUID(), ByteArray(32) { (it + 1).toByte() }, j.scope, kind)
        event = TestOwnerDeleteJournalCodecV1(routing, NeverOwnerDeleteAllDataKeys()).canonicalize(tuple, listOf(UUID.randomUUID()))
        val d = j.declaration()
        val nonce = ByteArray(12) { (it + 11).toByte() }
        val fields = AwsJournalKmsFixture.testOwnerDeleteFields(j, event.route.objectKey, event.route.eventId,
            tuple.epoch, event.route.routingKeyId, AwsJournalKmsFixture.url(nonce)).toMutableList().apply { this[5] = kind.name }
        context = mapOf(AwsJournalKmsFixture.CONTEXT_KEY to AwsJournalKmsFixture.url(AwsJournalKmsFixture.frame(fields)))
        val names = listOf("envelopeSchemaVersion", "payloadSchemaVersion", "canonicalizerId", "objectKind", "encryptionAlgorithm", "dataKeyMode",
            "kmsKeyId", "kmsKeyArn", "bucket", "objectKey", "writerGeneration", "ordinaryPrefix", "dataScopeKind", "dataScopeId",
            "publicationEpoch", "routingKeyId", "eventId", "nonce")
        val values = fields.drop(2)
        val header = CanonicalJson.canonicalize(JsonObject(names.zip(values).associate { (name, value) ->
            name to if (name in setOf("envelopeSchemaVersion", "payloadSchemaVersion", "publicationEpoch")) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
        })).toByteArray()
        val plaintext = event.canonicalBytes()
        val aad = AwsJournalKmsFixture.frame(listOf("kira-complaint-journal-aad-v1", "1", "KJEV", "1", header.size.toString()) + values +
            listOf(wrapped.size.toString(), AwsJournalKmsFixture.url(wrapped), (plaintext.size + 16).toString()))
        val key = AwsJournalKmsFixture.keyBytes()
        val wire = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); cipher.updateAAD(aad)
            TestTerminalCryptoReferenceV1.pack(header, wrapped, cipher.doFinal(plaintext))
        } finally { plaintext.fill(0); key.fill(0); nonce.fill(0); aad.fill(0); header.fill(0) }
        val at = f.initial.native.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS)
        val until = at.plusSeconds(d.limits.retention.ordinaryRetentionSeconds + 60)
        stored = JournalPublisherObject(event.route.objectKey, "synthetic-active-queue-opaque-version", wire, at, until,
            mapOf("kira-journal-schema" to "1", "kira-journal-event-id" to event.route.eventId,
                "kira-journal-ciphertext-sha256" to Sha256.hex(wire), "kira-journal-retain-until" to until.toString()))
        primaryBody = notification(); dlqBody = null
    }

    fun notification(): String {
        val d = checkNotNull(fixture).process.consumers.journalConfiguration.declaration()
        return mapper.writeValueAsString(mapOf("Records" to listOf(mapOf(
            "eventVersion" to "2.1", "eventSource" to "aws:s3", "awsRegion" to d.journalLocation.region,
            "eventTime" to stored.lastModified.toString(), "eventName" to "ObjectCreated:Put",
            "userIdentity" to mapOf("principalId" to "synthetic-not-authority"),
            "requestParameters" to mapOf("sourceIPAddress" to "192.0.2.15"),
            "responseElements" to mapOf("x-amz-request-id" to "synthetic-not-checksum"),
            "s3" to mapOf("s3SchemaVersion" to "1.0", "configurationId" to "synthetic-journal-notification",
                "bucket" to mapOf("name" to d.journalLocation.bucket, "ownerIdentity" to mapOf("principalId" to "synthetic-owner"),
                    "arn" to "arn:aws:s3:::${d.journalLocation.bucket}"),
                "object" to mapOf("key" to URLEncoder.encode(stored.key, Charsets.UTF_8), "size" to stored.bytes.size,
                    "eTag" to "not-checksum-evidence", "versionId" to stored.version, "sequencer" to "not-ordering-evidence")),
        ))))
    }

    fun differentOpaqueVersion() {
        stored = stored.copy(version = "synthetic-active-queue-other-opaque-version")
        primaryBody = notification()
    }

    private fun sqsReply(request: JournalKmsHttpRequest): JournalKmsHttpReply {
        boundary(); signed(request, "sqs"); beforeSqs(request)
        val action = request.target().removePrefix("AmazonSQS.")
        val fields = request.fields()
        val d = checkNotNull(fixture).process.consumers.journalConfiguration.declaration()
        val primary = d.recovery.queue; val dlq = d.recovery.deadLetterQueue
        fun url(arn: String) = "https://sqs.${d.journalLocation.region}.amazonaws.com/${arn.split(':')[4]}/${arn.split(':')[5]}"
        val isDlq = if (action == "GetQueueUrl") fields["QueueName"].textValue() == dlq.arn.split(':')[5]
            else fields["QueueUrl"].textValue() == url(dlq.arn)
        val selected = if (isDlq) dlq else primary
        order.add("$action:${if (isDlq) "DLQ" else "PRIMARY"}")
        val body: Map<String, Any> = when (action) {
            "GetQueueUrl" -> {
                assertEquals(setOf("QueueName", "QueueOwnerAWSAccountId"), fields.fieldNames().asSequence().toSet())
                assertEquals(selected.arn.split(':')[5], fields["QueueName"].textValue())
                assertEquals(d.journalLocation.accountId, fields["QueueOwnerAWSAccountId"].textValue())
                mapOf("QueueUrl" to url(selected.arn))
            }
            "GetQueueAttributes" -> {
                assertEquals(url(selected.arn), fields["QueueUrl"].textValue())
                assertEquals(listOf("QueueArn", "KmsMasterKeyId", "RedrivePolicy"), fields["AttributeNames"].map { it.textValue() })
                mapOf("Attributes" to (mapOf("QueueArn" to selected.arn, "KmsMasterKeyId" to selected.encryption.keyArn) +
                    if (isDlq) emptyMap() else mapOf("RedrivePolicy" to mapper.writeValueAsString(mapOf("deadLetterTargetArn" to dlq.arn, "maxReceiveCount" to "3")))))
            }
            "ReceiveMessage" -> {
                assertEquals(setOf("QueueUrl", "MaxNumberOfMessages", "WaitTimeSeconds", "VisibilityTimeout", "MessageSystemAttributeNames"), fields.fieldNames().asSequence().toSet())
                assertEquals(url(selected.arn), fields["QueueUrl"].textValue()); assertEquals(1, fields["MaxNumberOfMessages"].intValue())
                assertEquals(1, fields["WaitTimeSeconds"].intValue()); assertEquals(30, fields["VisibilityTimeout"].intValue())
                assertEquals(listOf("ApproximateReceiveCount"), fields["MessageSystemAttributeNames"].map { it.textValue() })
                val text = if (isDlq) dlqBody else primaryBody
                if (text != null) { if (isDlq) dlqDeliveries++ else primaryDeliveries++ }
                if (text == null) emptyMap() else mapOf("Messages" to listOf(mapOf("MessageId" to "synthetic-message-${if (isDlq) "dlq" else "primary"}",
                    "ReceiptHandle" to handle(isDlq), "Body" to text,
                    "MD5OfBody" to HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(text.toByteArray())),
                    "Attributes" to mapOf("ApproximateReceiveCount" to "2"))))
            }
            "DeleteMessage" -> {
                assertEquals(setOf("QueueUrl", "ReceiptHandle"), fields.fieldNames().asSequence().toSet())
                assertEquals(url(selected.arn), fields["QueueUrl"].textValue()); assertEquals(handle(isDlq), fields["ReceiptHandle"].textValue())
                checkNotNull(fixture).assertAckBoundary(isDlq)
                ackRequests.add(handle(isDlq)); emptyMap()
            }
            else -> error("Unexpected queue native request")
        }
        return JournalKmsHttpReply(mapper.writeValueAsBytes(body)).apply {
            headers = mapOf("Content-Type" to listOf("application/x-amz-json-1.0"), "Content-Length" to listOf(bytes.size.toString()))
            changeSqs(request, this)
        }
    }
    private fun handle(dlq: Boolean) = if (dlq) "synthetic-dlq-receipt-$dlqDeliveries" else "synthetic-primary-receipt-$primaryDeliveries"
    private fun s3Client(): SdkHttpClient {
        boundary(); s3Created++
        return journalPublisherRawHttpClient(requests, { checked { boundary() } }, {}, {
            s3Closed++; checked { closeBoundary("S3") }; s3CloseReturned++
        }) { request -> checked {
            boundary(); order.add("GET")
            val d = checkNotNull(fixture).process.consumers.journalConfiguration.declaration()
            journalPublisherRawAssertSigned(request, d.journalLocation.region, d.journalLocation.accountId, input.credentials)
            assertEquals("GET", request.kind, "The queue graph never LISTs or PUTs.")
            assertEquals("/${d.journalLocation.bucket}/${stored.key}", request.http.encodedPath())
            assertEquals(stored.version, request.http.rawQueryParameters().getValue("versionId").single())
            journalPublisherRawGetReply(d.journalLocation.region, stored.copy(bytes = stored.bytes.copyOf())).also { changeS3(request, it) }
        } }
    }
    private fun signed(request: JournalKmsHttpRequest, service: String) {
        val d = checkNotNull(fixture).process.consumers.journalConfiguration.declaration()
        val http = request.http
        assertEquals("https", http.protocol()); assertEquals("$service.${d.journalLocation.region}.amazonaws.com", http.host())
        assertEquals(input.credentials.sessionToken(), http.firstMatchingHeader("x-amz-security-token").orElseThrow())
        val authorization = http.firstMatchingHeader("Authorization").orElseThrow()
        val scope = authorization.substringAfter("Credential=${input.credentials.accessKeyId()}/").substringBefore(',')
        val signed = authorization.substringAfter("SignedHeaders=").substringBefore(',').split(';')
        val headers = signed.joinToString("") { name -> "$name:${http.firstMatchingHeader(name).orElseThrow().trim().replace(Regex("[ \\t]+"), " ")}\n" }
        assertTrue(http.rawQueryParameters().isEmpty())
        val canonical = "${http.method()}\n${http.encodedPath().ifEmpty { "/" }}\n\n$headers\n${signed.joinToString(";")}\n${Sha256.hex(request.json.toByteArray())}"
        val date = http.firstMatchingHeader("x-amz-date").orElseThrow()
        assertEquals("${date.take(8)}/${d.journalLocation.region}/$service/aws4_request", scope)
        fun hmac(key: ByteArray, value: String) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(value.toByteArray()) }
        val day = hmac(("AWS4" + input.credentials.secretAccessKey()).toByteArray(), date.take(8))
        val region = hmac(day, d.journalLocation.region); val serviceKey = hmac(region, service); val signing = hmac(serviceKey, "aws4_request")
        try { assertEquals(HexFormat.of().formatHex(hmac(signing, "AWS4-HMAC-SHA256\n$date\n$scope\n${Sha256.hex(canonical.toByteArray())}")), authorization.substringAfter("Signature=")) }
        finally { day.fill(0); region.fill(0); serviceKey.fill(0); signing.fill(0) }
    }
    private fun native(kind: String, remaining: () -> Int, factory: () -> SdkHttpClient): SdkHttpClient {
        boundary(); val raw = factory()
        return object : SdkHttpClient {
            override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest = checked {
                boundary(); val millis = remaining(); assertTrue(millis in 1..10_000); budgets.add(kind to millis); raw.prepareRequest(request)
            }
            override fun close() = raw.close()
            override fun clientName(): String = "SyntheticActiveOwnerDeleteQueue$kind"
        }
    }
    private fun boundary() { requireConnectionFree(); checkNotNull(fixture).assertProviderBoundary() }
    private fun closeBoundary(kind: String) { requireConnectionFree(); checkNotNull(fixture).assertSqlReleased(); onNativeClose(kind) }
    private fun <T> checked(action: () -> T): T = try { action() } catch (problem: AssertionError) { assertion.compareAndSet(null, problem); throw problem }
    fun assertNoLostAssertions() { assertion.get()?.let { throw it } }
    fun assertDisposed(returned: Boolean = true) {
        assertEquals(s3Created, s3Closed); if (returned) assertEquals(s3Created, s3CloseReturned)
        listOf(sts, kms, sqs).forEach { raw ->
            assertEquals(raw.createdClients, raw.closedClients); if (returned) assertEquals(raw.createdClients, raw.returnedClientCloses)
            raw.replies.forEach { assertEquals(1, it.calls); assertEquals(1, it.aborts); assertEquals(if (it.bodyPresent) 1 else 0, it.closes) }
        }
        requests.forEach { assertEquals(1, it.calls); assertEquals(1, it.aborts); assertEquals(1, checkNotNull(it.reply).closes) }
        assertFalse(requests.any { it.kind != "GET" }); assertNoLostAssertions()
    }
}
