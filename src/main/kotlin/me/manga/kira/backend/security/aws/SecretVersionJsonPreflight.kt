package me.manga.kira.backend.security.aws

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import me.manga.kira.backend.security.ImmutableSecretVersion
import me.manga.kira.backend.security.SecretVersionFailure
import me.manga.kira.backend.security.requireSecretVersion
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** SDK AWS-JSON decoding is permissive. This bounded streaming pass precedes its string/blob allocations. */
internal object SecretVersionJsonPreflight {
    const val MAX_MATERIAL_BYTES = 65_536
    const val MAX_ENCODED_CHARACTERS = 87_384
    const val MAX_REQUEST_BYTES = 2048
    private val factory = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
        .streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(2).maxNameLength(32)
                .maxStringLength(MAX_ENCODED_CHARACTERS).maxNumberLength(32).build(),
        )
        .build()

    fun request(bytes: ByteArray, expected: ImmutableSecretVersion, check: () -> Unit) = parse(bytes, check) { parser ->
        val fields = hashSetOf<String>()
        var arn: String? = null
        var version: String? = null
        while (next(parser, check) != JsonToken.END_OBJECT) {
            val name = field(parser, fields, setOf("SecretId", "VersionId"))
            next(parser, check)
            when (name) {
                "SecretId" -> arn = string(parser, 640)
                "VersionId" -> version = string(parser, 36)
            }
        }
        requireSecretVersion(arn == expected.resourceArn && version == expected.versionId, SecretVersionFailure.REFERENCE_MISMATCH)
    }

    fun response(bytes: ByteArray, expected: ImmutableSecretVersion, maximumMaterialBytes: Int, check: () -> Unit): SecretVersionWireReport =
        parse(bytes, check) { parser ->
            val fields = hashSetOf<String>()
            var arn: String? = null
            var version: String? = null
            var materialSize: Int? = null
            while (next(parser, check) != JsonToken.END_OBJECT) {
                val name = field(parser, fields, RESPONSE_FIELDS)
                next(parser, check)
                when (name) {
                    "ARN" -> arn = string(parser, 640)
                    "VersionId" -> version = string(parser, 36)
                    "SecretBinary" -> materialSize = binarySize(string(parser, MAX_ENCODED_CHARACTERS), maximumMaterialBytes, check)
                    "Name" -> string(parser, 256)
                    "VersionStages" -> stages(parser, check)
                    "CreatedDate" -> requireSecretVersion(
                        parser.currentToken()?.isNumeric == true && parser.text.length <= 32,
                        SecretVersionFailure.RESOLVER_FAILURE,
                    )
                }
            }
            requireSecretVersion(arn != null && version != null && materialSize != null, SecretVersionFailure.RESOLVER_FAILURE)
            val observed = ImmutableSecretVersion.awsSecretsManager(requireNotNull(arn), requireNotNull(version))
            requireSecretVersion(observed == expected, SecretVersionFailure.REFERENCE_MISMATCH)
            SecretVersionWireReport(observed, requireNotNull(materialSize))
        }

    private fun <T> parse(bytes: ByteArray, check: () -> Unit, consume: (JsonParser) -> T): T = secretProviderCall {
        check()
        // Do not let Jackson's charset autodetection accept a BOM, UTF-16 or UTF-32 instead of wire UTF-8 JSON.
        val first = bytes.firstOrNull { it.toInt() !in JSON_WHITESPACE }
        requireSecretVersion(first == '{'.code.toByte() && bytes.none { it == 0.toByte() }, SecretVersionFailure.RESOLVER_FAILURE)
        requireUtf8(bytes, check) // Jackson's byte parser is not our strict UTF-8 validation authority.
        val parser = factory.createParser(bytes)
        withSecretCleanup(
            {
                requireSecretVersion(next(parser, check) == JsonToken.START_OBJECT, SecretVersionFailure.RESOLVER_FAILURE)
                val result = consume(parser)
                requireSecretVersion(next(parser, check) == null, SecretVersionFailure.RESOLVER_FAILURE)
                result
            },
            { secretProviderCall { parser.close() } },
        )
    }

    private fun requireUtf8(bytes: ByteArray, check: () -> Unit) {
        val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
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
                requireSecretVersion(result.isOverflow, SecretVersionFailure.RESOLVER_FAILURE)
                output.clear()
            }
        } finally {
            characters.fill('\u0000')
        }
    }

    private fun next(parser: JsonParser, check: () -> Unit): JsonToken? {
        check()
        val token = parser.nextToken()
        check()
        return token
    }

    private fun field(parser: JsonParser, seen: MutableSet<String>, allowed: Set<String>): String {
        requireSecretVersion(parser.currentToken() == JsonToken.FIELD_NAME, SecretVersionFailure.RESOLVER_FAILURE)
        val name = parser.currentName()
        requireSecretVersion(name in allowed && seen.add(name), SecretVersionFailure.RESOLVER_FAILURE)
        return name
    }

    private fun string(parser: JsonParser, maximum: Int): String {
        requireSecretVersion(parser.currentToken() == JsonToken.VALUE_STRING, SecretVersionFailure.RESOLVER_FAILURE)
        return parser.text.also { requireSecretVersion(it.length in 1..maximum, SecretVersionFailure.RESOLVER_FAILURE) }
    }

    private fun stages(parser: JsonParser, check: () -> Unit) {
        requireSecretVersion(parser.currentToken() == JsonToken.START_ARRAY, SecretVersionFailure.RESOLVER_FAILURE)
        var count = 0
        while (next(parser, check) != JsonToken.END_ARRAY) {
            requireSecretVersion(++count <= 20, SecretVersionFailure.RESOLVER_FAILURE)
            string(parser, 256) // Metadata only: never used as a selector or an identity fallback.
        }
    }

    /** Exact decoded size and canonical unused bits, without invoking a Base64 decoder or allocating decoded bytes. */
    private fun binarySize(value: String, maximum: Int, check: () -> Unit): Int {
        requireSecretVersion(value.length in 4..MAX_ENCODED_CHARACTERS && value.length % 4 == 0, SecretVersionFailure.INVALID_MATERIAL)
        val padding = when {
            value.endsWith("==") -> 2
            value.endsWith("=") -> 1
            else -> 0
        }
        val size = value.length / 4 * 3 - padding
        requireSecretVersion(size in 1..maximum, SecretVersionFailure.INVALID_MATERIAL)
        var last = 0
        for (index in 0 until value.length - padding) {
            if (index % 8192 == 0) check()
            last = BASE64_ALPHABET.indexOf(value[index])
            requireSecretVersion(last >= 0, SecretVersionFailure.INVALID_MATERIAL)
        }
        requireSecretVersion(
            (padding != 1 || last and 3 == 0) && (padding != 2 || last and 15 == 0),
            SecretVersionFailure.INVALID_MATERIAL,
        )
        check()
        return size
    }

    private val JSON_WHITESPACE = setOf(9, 10, 13, 32)
    private val RESPONSE_FIELDS = setOf("ARN", "VersionId", "SecretBinary", "Name", "VersionStages", "CreatedDate")
    private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
}

internal class SecretVersionWireReport(val version: ImmutableSecretVersion, val materialSize: Int) {
    override fun toString(): String = "SecretVersionWireReport(redacted)"
}
