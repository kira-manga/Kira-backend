package me.manga.kira.backend.complaint.infrastructure.journal.aws

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.requireQueue
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

/** Closed SQS/S3-notification wire grammar only. No event, receipt or recovery authority is parsed. */
internal object TestActiveQueueJsonV1 {
    const val MAX_RESPONSE_BYTES = 131072
    const val MAX_NOTIFICATION_BYTES = 16384
    private val factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(65536)
            .maxNameLength(128).maxNumberLength(19).build()).build()
    private val mapper = ObjectMapper(factory)

    fun parse(bytes: ByteArray, maximum: Int = MAX_RESPONSE_BYTES): JsonNode {
        requireQueue(bytes.size in 1..maximum)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        factory.createParser(text).use { parser ->
            var tokens = 0
            var depth = 0
            while (true) {
                val token = parser.nextToken() ?: break
                requireQueue(++tokens <= 1024)
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) depth++
                if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--
                requireQueue(depth >= 0 && (depth > 0 || token == JsonToken.END_OBJECT && parser.nextToken() == null))
            }
            requireQueue(tokens > 1 && depth == 0)
        }
        return mapper.readTree(text).also { requireQueue(it.isObject) }
    }

    fun fields(node: JsonNode, required: Set<String>, optional: Set<String> = emptySet()) {
        requireQueue(node.isObject)
        val actual = node.fieldNames().asSequence().toSet()
        requireQueue(actual.containsAll(required) && (actual - required).all { it in optional })
    }
    fun string(node: JsonNode, field: String, maximum: Int = 4096): String {
        val value = node.get(field)
        requireQueue(value != null && value.isTextual)
        return checkNotNull(value).textValue().also { requireQueue(it.length in 1..maximum && it.toByteArray(Charsets.UTF_8).size <= maximum) }
    }
    fun number(node: JsonNode, field: String): Long {
        val value = node.get(field)
        requireQueue(value != null && value.isIntegralNumber && value.canConvertToLong())
        return checkNotNull(value).longValue()
    }

    fun notification(body: String, journal: TestOwnerDeleteJournalConfigurationV1): Locator {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val root = try { parse(bytes, MAX_NOTIFICATION_BYTES) } finally { bytes.fill(0) }
        fields(root, setOf("Records"))
        val records = root.get("Records")
        requireQueue(records.isArray && records.size() == 1) // Multi-record/SNS/test events are explicitly unsupported, never acked.
        val record = records.get(0)
        fields(record, setOf("eventVersion", "eventSource", "awsRegion", "eventTime", "eventName", "userIdentity", "requestParameters", "responseElements", "s3"))
        requireQueue(string(record, "eventVersion") in setOf("2.1", "2.2", "2.3") && string(record, "eventSource") == "aws:s3")
        val declaration = journal.declaration()
        requireQueue(string(record, "awsRegion") == declaration.journalLocation.region && string(record, "eventName") == "ObjectCreated:Put")
        Instant.parse(string(record, "eventTime")) // Syntax only; an event timestamp never authenticates the object.
        fields(record.get("userIdentity"), setOf("principalId")); string(record.get("userIdentity"), "principalId")
        fields(record.get("requestParameters"), setOf("sourceIPAddress")); string(record.get("requestParameters"), "sourceIPAddress")
        fields(record.get("responseElements"), setOf("x-amz-request-id"), setOf("x-amz-id-2"))
        record.get("responseElements").fields().forEachRemaining { requireQueue(it.value.isTextual && it.value.textValue().length in 1..4096) }
        val s3 = record.get("s3")
        fields(s3, setOf("s3SchemaVersion", "configurationId", "bucket", "object"))
        requireQueue(string(s3, "s3SchemaVersion") == "1.0"); string(s3, "configurationId")
        val bucket = s3.get("bucket")
        fields(bucket, setOf("name", "ownerIdentity", "arn"))
        requireQueue(string(bucket, "name") == declaration.journalLocation.bucket && string(bucket, "arn") == "arn:aws:s3:::${declaration.journalLocation.bucket}")
        fields(bucket.get("ownerIdentity"), setOf("principalId")); string(bucket.get("ownerIdentity"), "principalId")
        val item = s3.get("object")
        fields(item, setOf("key", "size", "eTag", "versionId", "sequencer"))
        val key = URLDecoder.decode(string(item, "key", 3072), Charsets.UTF_8)
        requireQueue(key.length in 1..1024 && key.all { it in '!'..'~' } && key.startsWith(journal.ordinaryPrefix))
        val size = number(item, "size")
        requireQueue(size in 1..declaration.limits.decoder.maximumEnvelopeBytes.toLong())
        string(item, "eTag", 128); string(item, "sequencer", 128) // Not checksum/ordering evidence.
        return Locator(key, requireJournalVersion(string(item, "versionId", 1024)), size)
    }
    class Locator(val key: String, val version: String, val size: Long) {
        override fun toString(): String = "QueueLocator(untrusted,redacted,no-event-authority)"
    }
}
