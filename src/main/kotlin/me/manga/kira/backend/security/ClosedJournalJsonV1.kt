package me.manga.kira.backend.security

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.complaint.domain.JournalDecoderLimitsV1
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Shared strict scalar/array journal parser; each family retains its own closed field set and serializer. */
internal class ClosedJournalJsonV1(private val limits: JournalDecoderLimitsV1) {
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

    @Suppress("TooGenericExceptionCaught")
    fun <T> encode(serializer: KSerializer<T>, value: T, check: (ByteArray) -> Unit): ByteArray {
        val bytes = CanonicalJson.canonicalize(serializer, value).toByteArray(Charsets.UTF_8)
        try {
            check(bytes)
            return bytes
        } catch (failure: Throwable) {
            bytes.fill(0)
            throw failure
        }
    }

    fun <T> parse(bytes: ByteArray, maximumBytes: Int, fields: Set<String>, numbers: Set<String>, arrays: Map<String, Int>, serializer: KSerializer<T>): T {
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
            val canonical = CanonicalJson.canonicalize(serializer, value).toByteArray(Charsets.UTF_8)
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
    }
}
