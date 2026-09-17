package me.manga.kira.backend.security.aws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.InitialLiveJournalTestFixture
import me.manga.kira.backend.complaint.domain.JournalQueueV1
import me.manga.kira.backend.security.ImmutableSecretVersion
import me.manga.kira.backend.security.JournalDataKeyRequestV1
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.HttpExecuteResponse
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.SdkHttpRequest
import software.amazon.awssdk.http.SdkHttpResponse
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.util.Base64

/** Genuine KmsClient/signing/marshalling/unmarshalling, substituted public HTTP SPI only; no listeners or AWS. */
internal class AwsJournalKmsFixture(val journal: ComplaintJournalConfigurationV1 = journal()) {
    val requests = mutableListOf<JournalKmsHttpRequest>()
    val replies = mutableListOf<JournalKmsHttpReply>()
    var createdClients = 0
    var closedClients = 0
    var now = 0L
    var clockReads = 0
    var onClockRead: (Int) -> Unit = {}
    var beforePrepare: () -> Unit = {}
    var afterPrepare: () -> Unit = {}
    var onClientClose: () -> Unit = {}
    var respond: (JournalKmsHttpRequest) -> JournalKmsHttpReply = {
        JournalKmsHttpReply(if (it.target() == DECRYPT_TARGET) decryptDocument(journal) else generateDocument(journal))
    }

    fun adapter(credentials: AwsSessionCredentials = CREDENTIALS, httpFactory: () -> SdkHttpClient = ::httpClient): AwsJournalDataKeyAdapter =
        AwsJournalDataKeyAdapter.withHttpFixture(journal, credentials, httpFactory, ::clock)

    private fun clock(): Long {
        clockReads++
        onClockRead(clockReads)
        return now
    }

    fun httpClient(): SdkHttpClient {
        createdClients++
        return object : SdkHttpClient {
            override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest {
                beforePrepare()
                val bytes = request.contentStreamProvider().orElseThrow().newStream().use {
                    it.readNBytes(JournalKmsJsonPreflight.MAX_REQUEST_BYTES + 1)
                }
                check(bytes.size <= JournalKmsJsonPreflight.MAX_REQUEST_BYTES)
                val captured = JournalKmsHttpRequest(request.httpRequest(), bytes.toString(Charsets.UTF_8))
                bytes.fill(0)
                requests.add(captured)
                val reply = respond(captured)
                replies.add(reply)
                val prepared = object : ExecutableHttpRequest {
                    override fun call(): HttpExecuteResponse {
                        reply.calls++
                        reply.beforeCall()
                        return reply.response()
                    }

                    override fun abort() {
                        reply.aborts++
                        reply.onAbort()
                    }
                }
                afterPrepare()
                return prepared
            }

            override fun close() {
                closedClients++
                onClientClose()
            }

            override fun clientName(): String = "SyntheticJournalKmsSync"
        }
    }

    companion object {
        const val REGION = "us-east-1"
        const val ARN = "arn:aws:kms:us-east-1:123456789012:key/66666666-6666-4666-8666-666666666666"
        const val CONTEXT_KEY = "kira-complaint-journal-context-v1"
        const val GENERATE_TARGET = "TrentService.GenerateDataKey"
        const val DECRYPT_TARGET = "TrentService.Decrypt"
        const val PRIVATE_TEXT = "synthetic-private-journal-provider-text"
        val CREDENTIALS: AwsSessionCredentials = AwsSessionCredentials.create(
            "SYNTHETICKMSACCESSKEY",
            "synthetic-kms-secret-not-a-real-credential",
            "synthetic-kms-session",
        )

        fun journal(maximumWrappedBytes: Int = 6144, region: String = REGION): ComplaintJournalConfigurationV1 {
            val d = InitialLiveJournalTestFixture.declaration()
            fun queue(queue: JournalQueueV1): JournalQueueV1 = queue.copy(
                arn = queue.arn.replace(REGION, region),
                encryption = queue.encryption.copy(keyArn = queue.encryption.keyArn.replace(REGION, region)),
            )
            return ComplaintJournalConfigurationV1.of(
                d.copy(
                    journalLocation = d.journalLocation.copy(region = region),
                    encryption = d.encryption.copy(keyArn = d.encryption.keyArn.replace(REGION, region)),
                    routing = d.routing.copy(
                        keys = d.routing.keys.map {
                            val version = ImmutableSecretVersion.awsSecretsManager(it.secret.resourceArn.replace(REGION, region), it.secret.versionId)
                            it.copy(secret = version)
                        },
                    ),
                    recovery = d.recovery.copy(queue = queue(d.recovery.queue), deadLetterQueue = queue(d.recovery.deadLetterQueue)),
                    limits = d.limits.copy(decoder = d.limits.decoder.copy(maximumWrappedKeyBytes = maximumWrappedBytes)),
                ),
            )
        }

        fun request(
            journal: ComplaintJournalConfigurationV1 = journal(),
            context: Map<String, String> = context(journal),
            arn: String = journal.declaration().encryption.keyArn,
            maximum: Int = journal.declaration().limits.decoder.maximumWrappedKeyBytes,
            timeout: Int = journal.declaration().limits.deadlines.kmsCallMillis,
        ): JournalDataKeyRequestV1 = JournalDataKeyRequestV1(arn, context, maximum, timeout)

        /** Independent literal field order/framing, not the production codec/profile encoder. */
        fun fields(journal: ComplaintJournalConfigurationV1 = journal()): List<String> {
            val d = journal.declaration()
            val writer = d.writer.generationId
            val prefix = "complaints/journal/v1/$writer/live/00000000-0000-0000-0000-000000000000/ordinary/"
            val routingId = d.routing.activeKeyId
            return listOf(
                "kira-complaint-journal-kms-context-v1", "1", "1", "1", "kcj-1", "OWNER_DELETE_ALL", "AES-256-GCM",
                "FRESH_PER_OBJECT_KMS_WRAPPED", d.encryption.keyId, d.encryption.keyArn, d.journalLocation.bucket,
                "${prefix}writer/$writer/epoch/0000000000000000042/$routingId/${url(wrappedBytes(32))}",
                writer, prefix, "LIVE", "00000000-0000-0000-0000-000000000000", "42", routingId,
                url(keyBytes()), url(wrappedBytes(12)),
            )
        }

        fun frame(fields: List<String>): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { output ->
                fields.forEach { field ->
                    val encoded = field.toByteArray(Charsets.UTF_8)
                    output.writeInt(encoded.size)
                    output.write(encoded)
                }
            }
            return bytes.toByteArray()
        }

        fun context(journal: ComplaintJournalConfigurationV1 = journal(), change: (MutableList<String>) -> Unit = {}): Map<String, String> =
            mapOf(CONTEXT_KEY to url(frame(fields(journal).toMutableList().also(change))))

        fun keyBytes(): ByteArray = "QUJD".repeat(8).toByteArray(Charsets.US_ASCII) // A second Base64 decoding changes its size/content.
        fun wrappedBytes(size: Int = 64): ByteArray = ByteArray(size) { (it * 13 + 7).toByte() }
        fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        fun url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun generateDocument(
            journal: ComplaintJournalConfigurationV1 = journal(),
            plaintext: ByteArray = keyBytes(),
            wrapped: ByteArray = wrappedBytes(),
        ): String = """{"KeyId":"${journal.declaration().encryption.keyArn}","Plaintext":"${base64(plaintext)}",""" +
            """"CiphertextBlob":"${base64(wrapped)}"}"""

        fun decryptDocument(journal: ComplaintJournalConfigurationV1 = journal(), plaintext: ByteArray = keyBytes()): String =
            """{"KeyId":"${journal.declaration().encryption.keyArn}","Plaintext":"${base64(plaintext)}",""" +
                """"EncryptionAlgorithm":"SYMMETRIC_DEFAULT"}"""
    }
}

internal class JournalKmsHttpRequest(val http: SdkHttpRequest, val json: String) {
    fun fields(): JsonNode = ObjectMapper().readTree(json)
    fun target(): String = http.firstMatchingHeader("X-Amz-Target").orElseThrow()
    override fun toString(): String = "JournalKmsHttpRequest(synthetic,redacted)"
}

/** Deliberately separate executable abort and body-close counters, as with URLConnection. */
internal class JournalKmsHttpReply(val bytes: ByteArray) {
    constructor(json: String) : this(json.toByteArray(Charsets.UTF_8))

    var status = 200
    var headers: Map<String, List<String>> = mapOf(
        "Content-Length" to listOf(bytes.size.toString()),
        "Content-Type" to listOf("application/x-amz-json-1.1"),
    )
    var bodyPresent = true
    var chunkSize = Int.MAX_VALUE
    var calls = 0
    var reads = 0
    var eofProbes = 0
    var bytesRead = 0
    var closes = 0
    var aborts = 0
    var beforeCall: () -> Unit = {}
    var beforeRead: () -> Unit = {}
    var onClose: () -> Unit = {}
    var onAbort: () -> Unit = {}

    fun response(): HttpExecuteResponse {
        val response = HttpExecuteResponse.builder().response(SdkHttpResponse.builder().statusCode(status).headers(headers).build())
        if (bodyPresent) {
            val body = object : InputStream() {
                private var position = 0

                override fun read(): Int {
                    val single = ByteArray(1)
                    return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xff
                }

                override fun read(destination: ByteArray, offset: Int, length: Int): Int {
                    reads++
                    beforeRead()
                    if (position == bytes.size) {
                        eofProbes++
                        return -1
                    }
                    val count = minOf(chunkSize, length, bytes.size - position)
                    bytes.copyInto(destination, offset, position, position + count)
                    position += count
                    bytesRead += count
                    return count
                }

                override fun close() {
                    closes++
                    onClose()
                }
            }
            response.responseBody(AbortableInputStream.create(body))
        }
        return response.build()
    }
}
