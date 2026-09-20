package me.manga.kira.backend.complaint.parsing.catalog

import com.fasterxml.jackson.core.JsonFactory
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
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapRegistryV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisManifestV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.requireOfflineTrustBundle
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDenialEnvelopeV1
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Closed typed entry points share bounded structural checks; no arbitrary serializer/JSON entry point is exposed. */
internal object OfflineTrustBundleParser {
    private const val MAX_STRING_BYTES = 4096
    private const val MAX_NAME_BYTES = 64
    private const val MAX_TOKENS = 4096
    private const val MAX_OBJECT_FIELDS = 32
    private const val MAX_INTEGER_LENGTH = 19

    private val factory =
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(16)
                    .maxStringLength(MAX_STRING_BYTES)
                    .maxNameLength(MAX_NAME_BYTES)
                    .maxNumberLength(MAX_INTEGER_LENGTH)
                    .build(),
            )
            .build()

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
    }

    fun parse(bytes: ByteArray): OfflineTrustBundleEnvelopeV1 = parseCanonical(bytes, OfflineTrustBundleEnvelopeV1.serializer())

    fun parseRegistry(bytes: ByteArray): OfflineBootstrapRegistryV1 = parseCanonical(bytes, OfflineBootstrapRegistryV1.serializer())

    /** Separate fixed-purpose approval grammar; this parser does not admit its signer or evidence. */
    fun parseOrdinaryDenial(bytes: ByteArray): TestOrdinaryDenialEnvelopeV1 =
        parseCanonical(bytes, TestOrdinaryDenialEnvelopeV1.serializer(), maximumBytes = 65_536)

    fun parseGenesis(bytes: ByteArray): OfflineCatalogGenesisEnvelopeV1 = parseCanonical(bytes, OfflineCatalogGenesisEnvelopeV1.serializer())

    fun parseGenesisManifest(bytes: ByteArray): OfflineCatalogGenesisManifestV1 = parseCanonical(bytes, OfflineCatalogGenesisManifestV1.serializer())

    fun parseChainGenesis(bytes: ByteArray, maximumManifestRecords: Int): OfflineCatalogGenesisEnvelopeV1 = parseCatalog(
        bytes,
        OfflineCatalogGenesisEnvelopeV1.serializer(),
        OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES,
        maximumManifestRecords,
    )

    fun parseRotation(bytes: ByteArray, maximumManifestRecords: Int): OfflineCatalogRotationEnvelopeV1 = parseCatalog(
        bytes,
        OfflineCatalogRotationEnvelopeV1.serializer(),
        OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES,
        maximumManifestRecords,
    )

    private fun <T> parseCatalog(bytes: ByteArray, serializer: KSerializer<T>, maximumBytes: Int, maximumManifestRecords: Int): T {
        requireOfflineTrustBundle(maximumManifestRecords in 1..OfflineCatalogChainProtocol.MAX_MANIFEST_RECORDS, OfflineTrustBundleFailure.INVALID_POLICY)
        return parseCanonical(bytes, serializer, maximumBytes, maximumManifestRecords)
    }

    private fun <T> parseCanonical(
        bytes: ByteArray,
        serializer: KSerializer<T>,
        maximumBytes: Int = OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES,
        maximumManifestRecords: Int = Int.MAX_VALUE,
    ): T {
        requireOfflineTrustBundle(bytes.size <= maximumBytes, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        try {
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            validateStructure(text, maximumManifestRecords)
            val document = json.decodeFromString(serializer, text)
            val canonical = CanonicalJson.canonicalize(serializer, document).toByteArray(Charsets.UTF_8)
            requireOfflineTrustBundle(canonical.contentEquals(bytes), OfflineTrustBundleFailure.NON_CANONICAL)
            return document
        } catch (_: CharacterCodingException) {
            throw OfflineTrustBundleException(OfflineTrustBundleFailure.MALFORMED_INPUT)
        } catch (_: StreamConstraintsException) {
            throw OfflineTrustBundleException(OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        } catch (_: JsonProcessingException) {
            throw OfflineTrustBundleException(OfflineTrustBundleFailure.MALFORMED_INPUT)
        } catch (_: SerializationException) {
            throw OfflineTrustBundleException(OfflineTrustBundleFailure.MALFORMED_INPUT)
        }
    }

    private fun validateStructure(text: String, maximumManifestRecords: Int) {
        factory.createParser(text).use { parser ->
            requireOfflineTrustBundle(parser.nextToken() == JsonToken.START_OBJECT, OfflineTrustBundleFailure.MALFORMED_INPUT)
            val containers = mutableListOf(Container(array = false))
            var tokens = 1
            var manifestRecords = 0
            while (containers.isNotEmpty()) {
                val token = parser.nextToken() ?: throw OfflineTrustBundleException(OfflineTrustBundleFailure.MALFORMED_INPUT)
                requireOfflineTrustBundle(++tokens <= MAX_TOKENS, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
                if (token.isScalarValue || token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    val parent = containers.last()
                    if (parent.array) {
                        requireOfflineTrustBundle(++parent.count <= OfflineTrustBundleProtocol.MAX_SIGNERS, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
                    }
                }
                when (token) {
                    JsonToken.START_OBJECT -> {
                        val inManifest = containers.last().inManifest || (containers.size == 1 && parser.currentName() == "manifest")
                        // Conservatively count EVERY object within manifest, including wrappers and empty heads.
                        if (inManifest) requireOfflineTrustBundle(++manifestRecords <= maximumManifestRecords, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
                        containers.add(Container(array = false, inManifest = inManifest))
                    }

                    JsonToken.START_ARRAY -> containers.add(Container(array = true, inManifest = containers.last().inManifest))

                    JsonToken.END_OBJECT, JsonToken.END_ARRAY -> containers.removeAt(containers.lastIndex)

                    JsonToken.FIELD_NAME -> {
                        requireOfflineTrustBundle(++containers.last().count <= MAX_OBJECT_FIELDS, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
                        requireUtf8Bound(parser.text, MAX_NAME_BYTES)
                    }

                    JsonToken.VALUE_STRING -> requireUtf8Bound(parser.text, MAX_STRING_BYTES)

                    JsonToken.VALUE_NUMBER_INT -> {
                        requireOfflineTrustBundle(parser.text.length <= MAX_INTEGER_LENGTH, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
                        requireOfflineTrustBundle(parser.text.toLongOrNull() != null, OfflineTrustBundleFailure.MALFORMED_INPUT)
                    }

                    else -> throw OfflineTrustBundleException(OfflineTrustBundleFailure.MALFORMED_INPUT)
                }
            }
            requireOfflineTrustBundle(parser.nextToken() == null, OfflineTrustBundleFailure.MALFORMED_INPUT)
        }
    }

    private fun requireUtf8Bound(value: String, maximum: Int) {
        // Also rejects escaped unpaired UTF-16 surrogates which a JSON tokenizer may otherwise retain.
        val encoded = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        requireOfflineTrustBundle(encoded.remaining() <= maximum, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    }

    private class Container(val array: Boolean, var count: Int = 0, val inManifest: Boolean = false)
}
