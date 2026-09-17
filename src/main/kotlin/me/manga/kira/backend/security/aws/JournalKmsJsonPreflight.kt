package me.manga.kira.backend.security.aws

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import me.manga.kira.backend.security.journalKeyCall
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Dedicated KMS AWS-JSON grammar before the permissive SDK string/blob unmarshallers. */
internal object JournalKmsJsonPreflight {
    // Fixed profile: <=8192 context bytes + <=8192 encoded wrapped bytes + ARN/JSON overhead.
    const val MAX_REQUEST_BYTES = 24 * 1024
    const val MAX_RESPONSE_BYTES = 16 * 1024
    private const val MAX_BLOB_CHARACTERS = 8192
    private val factory = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
        .streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(2).maxNameLength(64)
                .maxStringLength(MAX_BLOB_CHARACTERS).maxNumberLength(20).build(),
        ).build()

    fun request(bytes: ByteArray, expected: JournalKmsCall, check: () -> Unit) = parse(bytes, check) { parser ->
        val allowed = if (expected.operation == JournalKmsOperation.GENERATE) GENERATE_REQUEST else DECRYPT_REQUEST
        val seen = hashSetOf<String>()
        while (next(parser, check) != JsonToken.END_OBJECT) {
            val name = field(parser, seen, allowed)
            next(parser, check)
            when (name) {
                "KeyId" -> requireJournalKms(string(parser, 2048) == expected.keyArn)

                "KeySpec" -> requireJournalKms(string(parser, 32) == "AES_256")

                "EncryptionAlgorithm" -> requireJournalKms(string(parser, 32) == "SYMMETRIC_DEFAULT")

                "EncryptionContext" -> context(parser, expected, check)

                "CiphertextBlob" -> {
                    val value = string(parser, MAX_BLOB_CHARACTERS)
                    binarySize(value, expected.maximumWrappedBytes, check)
                    requireJournalKms(value == expected.encodedWrapped)
                }
            }
        }
        requireJournalKms(seen == allowed)
    }

    fun response(bytes: ByteArray, expected: JournalKmsCall, check: () -> Unit): JournalKmsWireReport = parse(bytes, check) { parser ->
        val required = if (expected.operation == JournalKmsOperation.GENERATE) GENERATE_RESPONSE else DECRYPT_RESPONSE
        val allowed = required + "KeyMaterialId"
        val seen = hashSetOf<String>()
        var arn: String? = null
        var algorithm: String? = null
        var keySize: Int? = null
        var wrappedSize: Int? = null
        while (next(parser, check) != JsonToken.END_OBJECT) {
            val name = field(parser, seen, allowed)
            next(parser, check)
            when (name) {
                "KeyId" -> arn = string(parser, 2048)

                "EncryptionAlgorithm" -> algorithm = string(parser, 32)

                "Plaintext" -> keySize = binarySize(string(parser, 44), 32, check)

                "CiphertextBlob" -> wrappedSize = binarySize(string(parser, MAX_BLOB_CHARACTERS), expected.maximumWrappedBytes, check)

                "KeyMaterialId" -> {
                    val value = string(parser, 64)
                    requireJournalKms(value.length == 64 && value.all { it in LOWER_HEX })
                }
            }
        }
        requireJournalKms(seen.containsAll(required) && arn == expected.keyArn && keySize == 32)
        if (expected.operation == JournalKmsOperation.DECRYPT) requireJournalKms(algorithm == "SYMMETRIC_DEFAULT")
        JournalKmsWireReport(checkNotNull(arn), checkNotNull(keySize), wrappedSize, algorithm)
    }

    private fun context(parser: JsonParser, expected: JournalKmsCall, check: () -> Unit) {
        requireJournalKms(parser.currentToken() == JsonToken.START_OBJECT)
        val seen = hashSetOf<String>()
        requireJournalKms(next(parser, check) == JsonToken.FIELD_NAME)
        val name = field(parser, seen, setOf(JournalKmsRequestProfile.CONTEXT_KEY))
        next(parser, check)
        requireJournalKms(string(parser, JournalKmsRequestProfile.MAX_CONTEXT_BYTES) == expected.context[name])
        requireJournalKms(next(parser, check) == JsonToken.END_OBJECT)
    }

    private fun <T> parse(bytes: ByteArray, check: () -> Unit, consume: (JsonParser) -> T): T = journalKeyCall {
        check()
        val first = bytes.firstOrNull { it.toInt() !in JSON_WHITESPACE }
        requireJournalKms(first == '{'.code.toByte() && bytes.none { it == 0.toByte() })
        requireUtf8(bytes, check)
        val parser = factory.createParser(bytes)
        withJournalKmsCleanup(
            {
                requireJournalKms(next(parser, check) == JsonToken.START_OBJECT)
                val result = consume(parser)
                requireJournalKms(next(parser, check) == null)
                result
            },
            { journalKmsClose { parser.close() } },
        )
    }

    private fun requireUtf8(bytes: ByteArray, check: () -> Unit) {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes)
        val characters = CharArray(2048)
        val output = CharBuffer.wrap(characters)
        try {
            while (true) {
                check()
                val result = decoder.decode(input, output, true)
                check()
                if (result.isError) result.throwException()
                if (result.isUnderflow) break
                requireJournalKms(result.isOverflow)
                output.clear()
            }
        } finally {
            characters.fill('\u0000')
        }
    }

    private fun next(parser: JsonParser, check: () -> Unit): JsonToken? {
        check()
        val result = parser.nextToken()
        check()
        return result
    }

    private fun field(parser: JsonParser, seen: MutableSet<String>, allowed: Set<String>): String {
        requireJournalKms(parser.currentToken() == JsonToken.FIELD_NAME)
        val name = parser.currentName()
        requireJournalKms(name in allowed && seen.add(name))
        return name
    }

    private fun string(parser: JsonParser, maximum: Int): String {
        requireJournalKms(parser.currentToken() == JsonToken.VALUE_STRING)
        return parser.text.also { requireJournalKms(it.length in 1..maximum) }
    }

    /** Canonical padded standard Base64 and its exact decoded size, without decoded-blob allocation. */
    private fun binarySize(value: String, maximum: Int, check: () -> Unit): Int {
        requireJournalKms(value.length in 4..MAX_BLOB_CHARACTERS && value.length % 4 == 0)
        val padding = when {
            value.endsWith("==") -> 2
            value.endsWith("=") -> 1
            else -> 0
        }
        val size = value.length / 4 * 3 - padding
        requireJournalKms(size in 1..maximum)
        var last = 0
        for (index in 0 until value.length - padding) {
            if (index % 1024 == 0) check()
            last = BASE64.indexOf(value[index])
            requireJournalKms(last >= 0)
        }
        requireJournalKms((padding != 1 || last and 3 == 0) && (padding != 2 || last and 15 == 0))
        check()
        return size
    }

    private val JSON_WHITESPACE = setOf(9, 10, 13, 32)
    private val GENERATE_REQUEST = setOf("KeyId", "KeySpec", "EncryptionContext")
    private val DECRYPT_REQUEST = setOf("KeyId", "CiphertextBlob", "EncryptionContext", "EncryptionAlgorithm")
    private val GENERATE_RESPONSE = setOf("KeyId", "Plaintext", "CiphertextBlob")
    private val DECRYPT_RESPONSE = setOf("KeyId", "Plaintext", "EncryptionAlgorithm")
    private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val LOWER_HEX = "0123456789abcdef"
}

internal class JournalKmsWireReport(val keyArn: String, val keySize: Int, val wrappedSize: Int?, val algorithm: String?) {
    override fun toString(): String = "JournalKmsWireReport(redacted)"
}
