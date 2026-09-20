package me.manga.kira.backend.complaint.parsing.catalog

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogInventoryEnvelopeV2
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.requireOfflineTrustBundle
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Separate closed inventory profile; never widens the original trust/genesis/rotation parser entry points. */
internal object OfflineCatalogInventoryParser {
    private const val MAX_STRING_BYTES = 4096
    private const val MAX_NAME_BYTES = 64
    private const val MAX_TOKENS = 262144
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

    fun parse(bytes: ByteArray, maximumManifestRecords: Int): ParsedOfflineCatalogGeneration {
        requireOfflineTrustBundle(maximumManifestRecords in 1..OfflineCatalogChainProtocol.MAX_MANIFEST_RECORDS, OfflineTrustBundleFailure.INVALID_POLICY)
        requireOfflineTrustBundle(bytes.size <= OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        try {
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            return when (validateStructure(text, maximumManifestRecords)) {
                1L -> ParsedOfflineCatalogGeneration.RotationV1(OfflineTrustBundleParser.parseRotation(bytes, maximumManifestRecords))

                OfflineCatalogInventoryProtocol.SCHEMA_VERSION.toLong() -> {
                    val envelope = json.decodeFromString(OfflineCatalogInventoryEnvelopeV2.serializer(), text)
                    val canonical = CanonicalJson.canonicalize(OfflineCatalogInventoryEnvelopeV2.serializer(), envelope).toByteArray(Charsets.UTF_8)
                    requireOfflineTrustBundle(canonical.contentEquals(bytes), OfflineTrustBundleFailure.NON_CANONICAL)
                    ParsedOfflineCatalogGeneration.InventoryV2(envelope)
                }

                else -> throw OfflineTrustBundleException(OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
            }
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

    private fun validateStructure(text: String, maximumManifestRecords: Int): Long {
        factory.createParser(text).use { parser ->
            requireOfflineTrustBundle(parser.nextToken() == JsonToken.START_OBJECT, OfflineTrustBundleFailure.MALFORMED_INPUT)
            val containers = mutableListOf(Container(array = false, path = "", inManifest = false))
            var tokens = 1
            var records = 0
            var schemaVersion = 0L
            while (containers.isNotEmpty()) {
                val token = parser.nextToken() ?: throw OfflineTrustBundleException(OfflineTrustBundleFailure.MALFORMED_INPUT)
                requireOfflineTrustBundle(++tokens <= MAX_TOKENS, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
                val parent = containers.last()
                countArrayItem(parent, token, maximumManifestRecords)
                when (token) {
                    JsonToken.START_OBJECT, JsonToken.START_ARRAY -> {
                        val path = childPath(parent, parser)
                        val inManifest = parent.inManifest || path == "manifest"
                        if (inManifest && token == JsonToken.START_OBJECT) {
                            requireOfflineTrustBundle(++records <= maximumManifestRecords, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
                        }
                        containers.add(Container(token == JsonToken.START_ARRAY, path, inManifest))
                    }

                    JsonToken.END_OBJECT, JsonToken.END_ARRAY -> containers.removeAt(containers.lastIndex)

                    JsonToken.FIELD_NAME -> {
                        requireOfflineTrustBundle(++parent.count <= MAX_OBJECT_FIELDS, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
                        requireUtf8Bound(parser.text, MAX_NAME_BYTES)
                    }

                    JsonToken.VALUE_STRING -> requireUtf8Bound(parser.text, MAX_STRING_BYTES)

                    JsonToken.VALUE_NUMBER_INT -> {
                        requireOfflineTrustBundle(parser.text.length <= MAX_INTEGER_LENGTH, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
                        val number = parser.text.toLongOrNull() ?: throw OfflineTrustBundleException(OfflineTrustBundleFailure.MALFORMED_INPUT)
                        if (containers.size == 1 && parser.currentName() == "schemaVersion") schemaVersion = number
                    }

                    else -> throw OfflineTrustBundleException(OfflineTrustBundleFailure.MALFORMED_INPUT)
                }
            }
            requireOfflineTrustBundle(parser.nextToken() == null, OfflineTrustBundleFailure.MALFORMED_INPUT)
            return schemaVersion
        }
    }

    private fun countArrayItem(parent: Container, token: JsonToken, maximumManifestRecords: Int) {
        if (!parent.array) return
        if (token.isScalarValue || token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
            val maximumItems = if (parent.path == "manifest.restoreInventory.sources" || parent.path == "manifest.restoreInventory.copies") {
                maximumManifestRecords
            } else {
                OfflineTrustBundleProtocol.MAX_SIGNERS
            }
            requireOfflineTrustBundle(++parent.count <= maximumItems, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        }
    }

    private fun childPath(parent: Container, parser: JsonParser): String {
        if (parent.array) return parent.path
        val field = parser.currentName() ?: throw OfflineTrustBundleException(OfflineTrustBundleFailure.MALFORMED_INPUT)
        return if (parent.path.isEmpty()) field else "${parent.path}.$field"
    }

    private fun requireUtf8Bound(value: String, maximum: Int) {
        val encoded = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        requireOfflineTrustBundle(encoded.remaining() <= maximum, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    }

    private class Container(val array: Boolean, val path: String, val inManifest: Boolean, var count: Int = 0)
}

internal sealed interface ParsedOfflineCatalogGeneration {
    data class RotationV1(val envelope: OfflineCatalogRotationEnvelopeV1) : ParsedOfflineCatalogGeneration
    data class InventoryV2(val envelope: OfflineCatalogInventoryEnvelopeV2) : ParsedOfflineCatalogGeneration
}
