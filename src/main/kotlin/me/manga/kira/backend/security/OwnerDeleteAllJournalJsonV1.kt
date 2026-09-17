package me.manga.kira.backend.security

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.complaint.domain.JournalDecoderLimitsV1
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Strict, bounded, closed journal-only JSON; no changes to the catalog/AWS parsers or global kcj-1. */
internal class OwnerDeleteAllJournalJsonV1(private val limits: JournalDecoderLimitsV1) {
    private val factory = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(limits.maximumJsonDepth)
                .maxStringLength(limits.maximumStringUtf8Bytes)
                .maxNameLength(limits.maximumStringUtf8Bytes)
                .maxNumberLength(19)
                .build(),
        ).build()

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
    }

    fun header(bytes: ByteArray): OwnerDeleteAllJournalHeaderV1 = parse(
        bytes,
        minOf(4096, limits.maximumPlaintextBytes),
        HEADER_FIELDS,
        HEADER_NUMBERS,
        emptyMap(),
        OwnerDeleteAllJournalHeaderV1.serializer(),
    )

    fun payload(bytes: ByteArray): OwnerDeleteAllJournalPayloadV1 = parse(
        bytes,
        limits.maximumPlaintextBytes,
        PAYLOAD_FIELDS,
        PAYLOAD_NUMBERS,
        mapOf("ownerInstallationIds" to 1, "complaintIds" to 100),
        OwnerDeleteAllJournalPayloadV1.serializer(),
    )

    fun encodeHeader(value: OwnerDeleteAllJournalHeaderV1): ByteArray = encode(OwnerDeleteAllJournalHeaderV1.serializer(), value) { header(it) }

    fun encodePayload(value: OwnerDeleteAllJournalPayloadV1): ByteArray = encode(OwnerDeleteAllJournalPayloadV1.serializer(), value) { payload(it) }

    @Suppress("TooGenericExceptionCaught")
    private fun <T> encode(serializer: SerializationStrategy<T>, value: T, check: (ByteArray) -> Unit): ByteArray {
        val bytes = CanonicalJson.canonicalize(serializer, value).toByteArray(Charsets.UTF_8)
        try {
            check(bytes)
            return bytes
        } catch (failure: Throwable) {
            bytes.fill(0)
            throw failure
        }
    }

    private fun <T> parse(
        bytes: ByteArray,
        maximumBytes: Int,
        fields: Set<String>,
        numbers: Set<String>,
        arrays: Map<String, Int>,
        serializer: DeserializationStrategy<T>,
    ): T {
        requireJournalCodec(bytes.size in 1..maximumBytes, OwnerDeleteAllJournalFailure.LIMIT_EXCEEDED)
        try {
            val decoded = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            val text = try {
                decoded.toString()
            } finally {
                if (decoded.hasArray()) decoded.array().fill('\u0000')
            }
            structure(text, fields, numbers, arrays)
            val value = json.decodeFromString(serializer, text)
            val canonical = when (value) {
                is OwnerDeleteAllJournalHeaderV1 -> CanonicalJson.canonicalize(OwnerDeleteAllJournalHeaderV1.serializer(), value)
                is OwnerDeleteAllJournalPayloadV1 -> CanonicalJson.canonicalize(OwnerDeleteAllJournalPayloadV1.serializer(), value)
                else -> throw OwnerDeleteAllJournalException(OwnerDeleteAllJournalFailure.INVALID_INPUT)
            }.toByteArray(Charsets.UTF_8)
            try {
                requireJournalCodec(canonical.contentEquals(bytes))
            } finally {
                canonical.fill(0)
            }
            return value
        } catch (_: CharacterCodingException) {
            throw OwnerDeleteAllJournalException(OwnerDeleteAllJournalFailure.INVALID_INPUT)
        } catch (_: StreamConstraintsException) {
            throw OwnerDeleteAllJournalException(OwnerDeleteAllJournalFailure.LIMIT_EXCEEDED)
        } catch (_: JsonProcessingException) {
            throw OwnerDeleteAllJournalException(OwnerDeleteAllJournalFailure.INVALID_INPUT)
        } catch (_: SerializationException) {
            throw OwnerDeleteAllJournalException(OwnerDeleteAllJournalFailure.INVALID_INPUT)
        }
    }

    private fun structure(text: String, fields: Set<String>, numbers: Set<String>, arrays: Map<String, Int>) {
        factory.createParser(text).use { parser ->
            val cursor = Cursor(parser)
            requireJournalCodec(cursor.next() == JsonToken.START_OBJECT)
            val seen = HashSet<String>()
            while (cursor.next() != JsonToken.END_OBJECT) {
                requireJournalCodec(parser.currentToken() == JsonToken.FIELD_NAME)
                requireJournalCodec(seen.size < limits.maximumObjectFields, OwnerDeleteAllJournalFailure.LIMIT_EXCEEDED)
                val name = parser.text
                utf8Bound(name)
                requireJournalCodec(name in fields && seen.add(name))
                val token = cursor.next()
                when {
                    name in numbers -> {
                        requireJournalCodec(token == JsonToken.VALUE_NUMBER_INT)
                        requireJournalCodec(DECIMAL.matches(parser.text) && parser.text.toLongOrNull() != null)
                    }

                    name in arrays -> {
                        requireJournalCodec(token == JsonToken.START_ARRAY)
                        var count = 0
                        while (cursor.next() != JsonToken.END_ARRAY) {
                            requireJournalCodec(++count <= arrays.getValue(name), OwnerDeleteAllJournalFailure.LIMIT_EXCEEDED)
                            requireJournalCodec(parser.currentToken() == JsonToken.VALUE_STRING)
                            utf8Bound(parser.text)
                        }
                    }

                    else -> {
                        requireJournalCodec(token == JsonToken.VALUE_STRING)
                        utf8Bound(parser.text)
                    }
                }
            }
            requireJournalCodec(seen == fields && cursor.next() == null)
        }
    }

    private fun utf8Bound(value: String) {
        val encoded = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        try {
            requireJournalCodec(encoded.remaining() <= limits.maximumStringUtf8Bytes, OwnerDeleteAllJournalFailure.LIMIT_EXCEEDED)
        } finally {
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }

    private inner class Cursor(private val parser: JsonParser) {
        private var tokens = 0

        fun next(): JsonToken? = parser.nextToken().also { token ->
            if (token != null) requireJournalCodec(++tokens <= limits.maximumJsonTokens, OwnerDeleteAllJournalFailure.LIMIT_EXCEEDED)
        }
    }

    private companion object {
        val DECIMAL = Regex("0|[1-9][0-9]{0,18}")
        val HEADER_NUMBERS = setOf("envelopeSchemaVersion", "payloadSchemaVersion", "publicationEpoch")
        val HEADER_FIELDS = setOf(
            "envelopeSchemaVersion", "payloadSchemaVersion", "canonicalizerId", "objectKind", "encryptionAlgorithm", "dataKeyMode",
            "kmsKeyId", "kmsKeyArn", "bucket", "objectKey", "writerGeneration", "ordinaryPrefix", "dataScopeKind", "dataScopeId",
            "publicationEpoch", "routingKeyId", "eventId", "nonce",
        )
        val PAYLOAD_NUMBERS = setOf("schemaVersion", "publicationEpoch", "credentialVersion")
        val PAYLOAD_FIELDS = setOf(
            "schemaVersion", "eventKind", "eventId", "publicationEpoch", "writerGeneration", "actorKind", "actorId", "credentialVersion",
            "operationKey", "requestFingerprint", "ownerInstallationIds", "dataScopeKind", "dataScopeId", "complaintIds",
        )
    }
}

// No defaults: required versions and empty arrays survive global kcj-1 default omission.
@Serializable
internal data class OwnerDeleteAllJournalPayloadV1(
    val schemaVersion: Int,
    val eventKind: String,
    val eventId: String,
    val publicationEpoch: Long,
    val writerGeneration: String,
    val actorKind: String,
    val actorId: String,
    val credentialVersion: Long,
    val operationKey: String,
    val requestFingerprint: String,
    val ownerInstallationIds: List<String>,
    val dataScopeKind: String,
    val dataScopeId: String,
    val complaintIds: List<String>,
) {
    override fun toString(): String = "OwnerDeleteAllJournalPayloadV1(redacted,no-authority)"
}

@Serializable
internal data class OwnerDeleteAllJournalHeaderV1(
    val envelopeSchemaVersion: Int,
    val payloadSchemaVersion: Int,
    val canonicalizerId: String,
    val objectKind: String,
    val encryptionAlgorithm: String,
    val dataKeyMode: String,
    val kmsKeyId: String,
    val kmsKeyArn: String,
    val bucket: String,
    val objectKey: String,
    val writerGeneration: String,
    val ordinaryPrefix: String,
    val dataScopeKind: String,
    val dataScopeId: String,
    val publicationEpoch: Long,
    val routingKeyId: String,
    val eventId: String,
    val nonce: String,
) {
    fun framedValues(): List<String> = listOf(
        envelopeSchemaVersion.toString(), payloadSchemaVersion.toString(), canonicalizerId, objectKind, encryptionAlgorithm, dataKeyMode,
        kmsKeyId, kmsKeyArn, bucket, objectKey, writerGeneration, ordinaryPrefix, dataScopeKind, dataScopeId, publicationEpoch.toString(),
        routingKeyId, eventId, nonce,
    )

    override fun toString(): String = "OwnerDeleteAllJournalHeaderV1(redacted,no-authority)"
}
