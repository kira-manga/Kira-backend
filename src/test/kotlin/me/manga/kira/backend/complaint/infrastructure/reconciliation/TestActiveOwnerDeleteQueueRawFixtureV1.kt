package me.manga.kira.backend.complaint.infrastructure.reconciliation

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.Sha256
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
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.JournalKmsHttpReply
import me.manga.kira.backend.security.aws.JournalKmsHttpRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpClient
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
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
 * Default positive input is A's actual native PUT and its original wrapped-key map. Explicitly
 * selected protocol-history inputs instead use separate reference bytes and synthetic key maps;
 * they never claim another AUTH/PUT. Both traverse the same real SDK/GET/decrypt/APPLY consumers.
 * No copied primary plaintext key, supplied proof, real AWS or two-producer/process claim.
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
    private lateinit var originalRecord: TestRegisteredInitialDeletionNativeRecordV1
    private var historical: TestActiveQueueHistoricalAllObjectV1? = null
    private var historicalSerial = 0
    val deliveredStored: JournalPublisherObject get() = historical?.stored ?: stored
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
            assertEquals(historical?.kmsContext ?: originalRecord.kmsContext,
                fields["EncryptionContext"].fields().asSequence().associate { it.key to it.value.textValue() })
            val key = checkNotNull(fixture).process.consumers.journalConfiguration.declaration().encryption.keyArn
            assertEquals(key, fields["KeyId"].textValue())
            historical?.decrypt(request) ?: originalRecord.decrypt(request)
        } }
        sqs.respond = { request -> checked { sqsReply(request) } }
    }

    fun attach(f: TestActiveOwnerDeleteQueueFixtureV1, record: TestRegisteredInitialDeletionNativeRecordV1) {
        check(fixture == null); fixture = f
        originalRecord = record; event = record.event; stored = record.stored
        assertSame(checkNotNull(f.precursor.event), event)
        assertSame(checkNotNull(f.precursor.record).stored, stored)
        primaryBody = notification(); dlqBody = null
    }
    fun detach(f: TestActiveOwnerDeleteQueueFixtureV1) { check(fixture === f); fixture = null }
    fun resetFaults() { beforeSqs = {}; changeSqs = { _, _ -> }; changeS3 = { _, _ -> }; wrongPrincipal = false; onNativeClose = {} }

    /** Reference protocol data only; original AUTH/work/PUT/key mapping and full D stay unchanged. */
    fun protocolHistoricalAllObject(targets: List<UUID> = event.complaintIds(), routingKeyId: String? = null): TestActiveQueueHistoricalAllObjectV1 {
        val f = checkNotNull(fixture); f.assertReleased()
        assertEquals(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, f.family)
        val routing = f.process.consumers.journalRouting
        val selected = routingKeyId ?: routing.derive(event.tuple).candidates().first { it.routingKeyId != event.route.routingKeyId }.routingKeyId
        check(selected != event.route.routingKeyId)
        val comparison = f.precursor.codec.canonicalize(event.tuple, targets, selected)
        return TestActiveQueueHistoricalAllObjectV1.reference(routing, comparison, originalRecord.stored,
            "synthetic-protocol-history-%2F+&=version-${++historicalSerial}")
    }
    fun selectHistorical(value: TestActiveQueueHistoricalAllObjectV1) {
        val f = checkNotNull(fixture); f.assertReleased()
        assertEquals(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, f.family)
        assertTrue(value.event.belongsTo(f.process.consumers.journalRouting))
        historical = value; primaryBody = notification(); dlqBody = null
    }
    fun selectOriginal() {
        checkNotNull(fixture).assertReleased()
        historical = null; primaryBody = notification(); dlqBody = null
    }

    fun notification(): String {
        val stored = deliveredStored
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

    /** Adversarial raw metadata only, never a claim that another native PUT occurred. */
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
            val stored = deliveredStored
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

/**
 * Passive, explicitly synthetic protocol/history data, NOT A's actual-PUT record or any proof.
 * Independent restricted JSON/JCE framing reuses existing TEST LP/base64 primitives only. Routes
 * are retained product comparisons; the separate literal tests own independent HMAC coverage.
 */
internal class TestActiveQueueHistoricalAllObjectV1 private constructor(
    val event: TestOwnerDeleteJournalEventV1,
    private val value: JournalPublisherObject,
    private val context: Map<String, String>,
    private val key: ByteArray,
    private val wrapped: ByteArray,
    private val keyArn: String,
) {
    val stored: JournalPublisherObject get() = value.copy(bytes = value.bytes.copyOf(), metadata = value.metadata.toMap())
    val kmsContext: Map<String, String> get() = context.toMap()
    fun decrypt(request: JournalKmsHttpRequest): JournalKmsHttpReply {
        requireConnectionFree()
        assertEquals(AwsJournalKmsFixture.DECRYPT_TARGET, request.target())
        val fields = request.fields()
        assertEquals(setOf("KeyId", "CiphertextBlob", "EncryptionAlgorithm", "EncryptionContext"), fields.fieldNames().asSequence().toSet())
        assertEquals(keyArn, fields["KeyId"].textValue())
        assertEquals("SYMMETRIC_DEFAULT", fields["EncryptionAlgorithm"].textValue())
        assertEquals(AwsJournalKmsFixture.base64(wrapped), fields["CiphertextBlob"].textValue())
        assertEquals(context, fields["EncryptionContext"].fields().asSequence().associate { it.key to it.value.textValue() })
        return JournalKmsHttpReply(AwsJournalKmsFixture.decryptDocument(keyArn, key))
    }
    /** Hostile provider version metadata, explicitly not a second actual PUT or a new authority. */
    fun withAdversarialOpaqueVersion(version: String): TestActiveQueueHistoricalAllObjectV1 {
        require(version.isNotBlank() && version != value.version)
        return TestActiveQueueHistoricalAllObjectV1(event, value.copy(version = version), context, key, wrapped, keyArn)
    }
    override fun toString() = "HistoricalAllObject(protocol-reference-only,redacted,no-auth-put-or-proof)"

    companion object {
        internal fun reference(routing: TestOwnerDeleteJournalRoutingV1, event: TestOwnerDeleteJournalEventV1,
            original: JournalPublisherObject, version: String): TestActiveQueueHistoricalAllObjectV1 {
            requireConnectionFree()
            assertTrue(event.belongsTo(routing))
            assertEquals(ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL, event.comparison.eventKind)
            val tuple = event.tuple; val d = routing.journalConfiguration.declaration()
            val random = SecureRandom()
            val key = ByteArray(32).also(random::nextBytes)
            val wrapped = ByteArray(64).also(random::nextBytes)
            val nonce = ByteArray(12).also(random::nextBytes)
            val payload = canonical(mapOf(
                "schemaVersion" to JsonPrimitive(1), "eventKind" to JsonPrimitive("OWNER_DELETE_ALL"),
                "eventId" to JsonPrimitive(event.route.eventId), "publicationEpoch" to JsonPrimitive(tuple.epoch),
                "writerGeneration" to JsonPrimitive(d.writer.generationId.toString()), "actorKind" to JsonPrimitive("INSTALLATION"),
                "actorId" to JsonPrimitive(tuple.actorId.toString()), "credentialVersion" to JsonPrimitive(tuple.credentialVersion),
                "operationKey" to JsonPrimitive(tuple.operationKey.toString()), "requestFingerprint" to JsonPrimitive(tuple.encodedFingerprint()),
                "ownerInstallationIds" to JsonArray(listOf(JsonPrimitive(tuple.actorId.toString()))),
                "dataScopeKind" to JsonPrimitive("TEST"), "dataScopeId" to JsonPrimitive(tuple.scope.id.toString()),
                "complaintIds" to JsonArray(event.complaintIds().map { JsonPrimitive(it.toString()) }),
            ))
            assertArrayEquals(event.canonicalBytes(), payload, "Independent protocol-history payload matches only comparison semantics.")
            val header = mapOf(
                "envelopeSchemaVersion" to JsonPrimitive(1), "payloadSchemaVersion" to JsonPrimitive(1),
                "canonicalizerId" to JsonPrimitive("kcj-1"), "objectKind" to JsonPrimitive("OWNER_DELETE_ALL"),
                "encryptionAlgorithm" to JsonPrimitive("AES-256-GCM"), "dataKeyMode" to JsonPrimitive("FRESH_PER_OBJECT_KMS_WRAPPED"),
                "kmsKeyId" to JsonPrimitive(d.encryption.keyId), "kmsKeyArn" to JsonPrimitive(d.encryption.keyArn),
                "bucket" to JsonPrimitive(d.journalLocation.bucket), "objectKey" to JsonPrimitive(event.route.objectKey),
                "writerGeneration" to JsonPrimitive(d.writer.generationId.toString()), "ordinaryPrefix" to JsonPrimitive(routing.journalConfiguration.ordinaryPrefix),
                "dataScopeKind" to JsonPrimitive("TEST"), "dataScopeId" to JsonPrimitive(tuple.scope.id.toString()),
                "publicationEpoch" to JsonPrimitive(tuple.epoch), "routingKeyId" to JsonPrimitive(event.route.routingKeyId),
                "eventId" to JsonPrimitive(event.route.eventId), "nonce" to JsonPrimitive(AwsJournalKmsFixture.url(nonce)),
            )
            val values = HEADER_ORDER.map { header.getValue(it).jsonPrimitive.content }
            val headerBytes = canonical(header)
            val aad = AwsJournalKmsFixture.frame(listOf("kira-complaint-journal-aad-v1", "1", "KJEV", "1", headerBytes.size.toString()) +
                values + listOf(wrapped.size.toString(), AwsJournalKmsFixture.url(wrapped), (payload.size + 16).toString()))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(payload)
            val wire = ByteBuffer.allocate(20 + headerBytes.size + wrapped.size + encrypted.size).apply {
                putInt(0x4b4a4556).putInt(1)
                listOf(headerBytes, wrapped, encrypted).forEach { putInt(it.size).put(it) }
            }.array()
            val context = mapOf(AwsJournalKmsFixture.CONTEXT_KEY to AwsJournalKmsFixture.url(AwsJournalKmsFixture.frame(
                listOf("kira-complaint-journal-kms-context-v1", "1") + values)))
            val stored = JournalPublisherObject(event.route.objectKey, version, wire, original.lastModified, original.retainUntil, mapOf(
                "kira-journal-schema" to "1", "kira-journal-event-id" to event.route.eventId,
                "kira-journal-ciphertext-sha256" to Sha256.hex(wire),
                "kira-journal-retain-until" to original.metadata.getValue("kira-journal-retain-until"),
            ))
            return TestActiveQueueHistoricalAllObjectV1(event, stored, context, key, wrapped, d.encryption.keyArn)
        }
        private fun canonical(fields: Map<String, JsonElement>): ByteArray = JsonObject(fields.toSortedMap()).toString().toByteArray(Charsets.UTF_8)
        private val HEADER_ORDER = listOf("envelopeSchemaVersion", "payloadSchemaVersion", "canonicalizerId", "objectKind", "encryptionAlgorithm", "dataKeyMode",
            "kmsKeyId", "kmsKeyArn", "bucket", "objectKey", "writerGeneration", "ordinaryPrefix", "dataScopeKind", "dataScopeId", "publicationEpoch", "routingKeyId", "eventId", "nonce")
    }
}
