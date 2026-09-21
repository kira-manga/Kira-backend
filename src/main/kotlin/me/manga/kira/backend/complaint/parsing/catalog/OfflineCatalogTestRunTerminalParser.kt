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
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunTerminalEnvelopeV4
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunTerminalManifestV4
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunTerminalProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunTerminalSyntaxV4
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.requireOfflineTrustBundle
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalExceptionV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Distinct first-terminal schema only. No signature, denial, current run or native-provider capability. */
internal object OfflineCatalogTestRunTerminalParser {
    private const val MAX_STRING_BYTES = 4096
    private const val MAX_NAME_BYTES = 64
    private const val MAX_TOKENS = 262144
    private const val MAX_OBJECT_FIELDS = 32
    private const val MAX_INTEGER_LENGTH = 19
    private const val RECORD_PATH = "manifest.terminalRecord"

    private val factory = JsonFactory.builder()
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

    fun parse(
        bytes: ByteArray,
        maximumManifestRecords: Int,
        maximumDocumentBytes: Int = OfflineCatalogTestRunTerminalProtocol.MAX_DOCUMENT_BYTES,
    ): OfflineCatalogTestRunTerminalEnvelopeV4 = checked {
        val input = snapshotDocument(bytes, maximumManifestRecords, maximumDocumentBytes)
        val text = decodeUtf8(input)
        validateStructure(text, maximumManifestRecords, rootPath = "")
        val envelope = json.decodeFromString(OfflineCatalogTestRunTerminalEnvelopeV4.serializer(), text)
        requireOfflineTrustBundle(envelope.schemaVersion == OfflineCatalogTestRunTerminalProtocol.SCHEMA_VERSION, OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
        OfflineCatalogTestRunTerminalSyntaxV4.validateManifest(envelope.manifest)
        requireOfflineTrustBundle(envelope.signatures.size == 1)
        val canonical = CanonicalJson.canonicalize(OfflineCatalogTestRunTerminalEnvelopeV4.serializer(), envelope).toByteArray(Charsets.UTF_8)
        requireOfflineTrustBundle(canonical.contentEquals(input), OfflineTrustBundleFailure.NON_CANONICAL)
        envelope
    }

    fun parseManifest(
        bytes: ByteArray,
        maximumManifestRecords: Int,
        maximumDocumentBytes: Int = OfflineCatalogTestRunTerminalProtocol.MAX_DOCUMENT_BYTES,
    ): OfflineCatalogTestRunTerminalManifestV4 = checked {
        val input = snapshotDocument(bytes, maximumManifestRecords, maximumDocumentBytes)
        val text = decodeUtf8(input)
        validateStructure(text, maximumManifestRecords, rootPath = "manifest")
        val manifest = json.decodeFromString(OfflineCatalogTestRunTerminalManifestV4.serializer(), text)
        OfflineCatalogTestRunTerminalSyntaxV4.validateManifest(manifest)
        val canonical = CanonicalJson.canonicalize(OfflineCatalogTestRunTerminalManifestV4.serializer(), manifest).toByteArray(Charsets.UTF_8)
        requireOfflineTrustBundle(canonical.contentEquals(input), OfflineTrustBundleFailure.NON_CANONICAL)
        manifest
    }

    /** Old schemas retain their exact old readers; only this entry can dispatch the final V4. */
    fun parseGeneration(
        bytes: ByteArray,
        maximumManifestRecords: Int,
        maximumEnvelopeBytes: Int = OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES,
    ): ParsedOfflineTerminalCatalogGeneration = checked {
        requireOfflineTrustBundle(maximumManifestRecords in 1..OfflineCatalogChainProtocol.MAX_MANIFEST_RECORDS, OfflineTrustBundleFailure.INVALID_POLICY)
        requireOfflineTrustBundle(maximumEnvelopeBytes in 1..OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES, OfflineTrustBundleFailure.INVALID_POLICY)
        requireOfflineTrustBundle(bytes.size in 1..maximumEnvelopeBytes, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        val input = bytes.copyOf()
        when (schemaVersion(decodeUtf8(input))) {
            1L, 2L, 3L -> ParsedOfflineTerminalCatalogGeneration.Predecessor(
                OfflineCatalogTestRunActivationParser.parseGeneration(input, maximumManifestRecords, maximumEnvelopeBytes),
            )
            4L -> ParsedOfflineTerminalCatalogGeneration.TerminalV4(
                parse(input, maximumManifestRecords, minOf(maximumEnvelopeBytes, OfflineCatalogTestRunTerminalProtocol.MAX_DOCUMENT_BYTES)),
            )
            else -> throw OfflineTrustBundleException(OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
        }
    }

    private fun snapshotDocument(bytes: ByteArray, maximumManifestRecords: Int, maximumDocumentBytes: Int): ByteArray {
        requireOfflineTrustBundle(maximumManifestRecords in 1..OfflineCatalogChainProtocol.MAX_MANIFEST_RECORDS, OfflineTrustBundleFailure.INVALID_POLICY)
        requireOfflineTrustBundle(maximumDocumentBytes in 1..OfflineCatalogTestRunTerminalProtocol.MAX_DOCUMENT_BYTES, OfflineTrustBundleFailure.INVALID_POLICY)
        requireOfflineTrustBundle(bytes.size in 1..maximumDocumentBytes, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        return bytes.copyOf()
    }

    private fun schemaVersion(text: String): Long {
        factory.createParser(text).use { parser ->
            requireOfflineTrustBundle(parser.nextToken() == JsonToken.START_OBJECT, OfflineTrustBundleFailure.MALFORMED_INPUT)
            var version: Long? = null
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                requireOfflineTrustBundle(parser.currentToken() == JsonToken.FIELD_NAME, OfflineTrustBundleFailure.MALFORMED_INPUT)
                val name = parser.currentName()
                val token = parser.nextToken() ?: throw OfflineTrustBundleException(OfflineTrustBundleFailure.MALFORMED_INPUT)
                if (name == "schemaVersion") {
                    requireOfflineTrustBundle(token == JsonToken.VALUE_NUMBER_INT, OfflineTrustBundleFailure.MALFORMED_INPUT)
                    version = parser.text.toLongOrNull() ?: throw OfflineTrustBundleException(OfflineTrustBundleFailure.MALFORMED_INPUT)
                } else {
                    parser.skipChildren()
                }
            }
            requireOfflineTrustBundle(parser.nextToken() == null, OfflineTrustBundleFailure.MALFORMED_INPUT)
            return version ?: throw OfflineTrustBundleException(OfflineTrustBundleFailure.MALFORMED_INPUT)
        }
    }

    /** Every manifest object, including all contextual history/source witnesses, consumes the record cap. */
    private fun validateStructure(text: String, maximumManifestRecords: Int, rootPath: String) {
        factory.createParser(text).use { parser ->
            requireOfflineTrustBundle(parser.nextToken() == JsonToken.START_OBJECT, OfflineTrustBundleFailure.MALFORMED_INPUT)
            val containers = mutableListOf(Container(rootPath, rootPath == "manifest", null))
            var tokens = 1
            var records = if (rootPath == "manifest") 1 else 0
            while (containers.isNotEmpty()) {
                val token = parser.nextToken() ?: throw OfflineTrustBundleException(OfflineTrustBundleFailure.MALFORMED_INPUT)
                requireOfflineTrustBundle(++tokens <= MAX_TOKENS, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
                val parent = containers.last()
                countArrayItem(parent, token)
                when (token) {
                    JsonToken.START_OBJECT, JsonToken.START_ARRAY -> {
                        val path = childPath(parent, parser)
                        val inManifest = parent.inManifest || path == "manifest"
                        if (inManifest && token == JsonToken.START_OBJECT) {
                            requireOfflineTrustBundle(++records <= maximumManifestRecords, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
                        }
                        val array = if (token == JsonToken.START_ARRAY) arrayRule(path, maximumManifestRecords) else null
                        containers.add(Container(path, inManifest, array))
                    }

                    JsonToken.END_OBJECT, JsonToken.END_ARRAY -> {
                        parent.array?.let { requireOfflineTrustBundle(parent.count >= it.minimum, OfflineTrustBundleFailure.MALFORMED_INPUT) }
                        containers.removeAt(containers.lastIndex)
                    }

                    JsonToken.FIELD_NAME -> {
                        requireOfflineTrustBundle(++parent.count <= MAX_OBJECT_FIELDS, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
                        requireUtf8Bound(parser.text, MAX_NAME_BYTES)
                    }

                    JsonToken.VALUE_STRING -> requireUtf8Bound(parser.text, MAX_STRING_BYTES)

                    JsonToken.VALUE_NUMBER_INT -> {
                        requireOfflineTrustBundle(parser.text.length <= MAX_INTEGER_LENGTH, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
                        val number = parser.text.toLongOrNull() ?: throw OfflineTrustBundleException(OfflineTrustBundleFailure.MALFORMED_INPUT)
                        if (parent.array?.item == JsonToken.VALUE_NUMBER_INT) requireOfflineTrustBundle(number >= 0L)
                    }

                    else -> throw OfflineTrustBundleException(OfflineTrustBundleFailure.MALFORMED_INPUT)
                }
            }
            requireOfflineTrustBundle(parser.nextToken() == null, OfflineTrustBundleFailure.MALFORMED_INPUT)
        }
    }

    private fun arrayRule(path: String, maximumManifestRecords: Int): ArrayRule = when (path) {
        "signatures", "manifest.requiredSignerPolicy.members", "manifest.initialWriterRegistry.catalogWriter.requiredSignerPolicy.members" ->
            ArrayRule(1, 1, JsonToken.START_OBJECT)

        "manifest.approvals" -> ArrayRule(2, 2, JsonToken.START_OBJECT)
        "manifest.initialWriterRegistry.catalogWriter.catalogApproverIds" -> ArrayRule(2, OfflineTrustBundleProtocol.MAX_SIGNERS, JsonToken.VALUE_STRING)
        "manifest.restoreInventory.sources", "manifest.restoreInventory.copies" -> ArrayRule(0, maximumManifestRecords, JsonToken.START_OBJECT)
        "manifest.inventoryDelta.addedSourceIds", "manifest.inventoryDelta.addedCopyIds" -> ArrayRule(0, 0, JsonToken.VALUE_STRING)
        "$RECORD_PATH.sealSet.records" -> ArrayRule(2, TestTerminalProfileV1.MAX_SEALS, JsonToken.START_OBJECT)
        "$RECORD_PATH.progress.completedCuts", "$RECORD_PATH.progress.installationReads" ->
            ArrayRule(2, 2, JsonToken.START_OBJECT)
        "$RECORD_PATH.installationManifest.chunks" -> ArrayRule(0, minOf(maximumManifestRecords, TestTerminalProfileV1.MAX_MANIFEST_CHUNKS), JsonToken.START_OBJECT)

        else -> throw OfflineTrustBundleException(OfflineTrustBundleFailure.MALFORMED_INPUT)
    }

    private fun countArrayItem(parent: Container, token: JsonToken) {
        val rule = parent.array ?: return
        if (token.isScalarValue || token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
            requireOfflineTrustBundle(++parent.count <= rule.maximum, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
            requireOfflineTrustBundle(token == rule.item, OfflineTrustBundleFailure.MALFORMED_INPUT)
        }
    }

    private fun childPath(parent: Container, parser: JsonParser): String {
        if (parent.array != null) return parent.path
        val field = parser.currentName() ?: throw OfflineTrustBundleException(OfflineTrustBundleFailure.MALFORMED_INPUT)
        return if (parent.path.isEmpty()) field else "${parent.path}.$field"
    }

    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun requireUtf8Bound(value: String, maximum: Int) {
        val encoded = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        requireOfflineTrustBundle(encoded.remaining() <= maximum, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    }

    private inline fun <T> checked(action: () -> T): T = try {
        action()
    } catch (_: CharacterCodingException) {
        throw OfflineTrustBundleException(OfflineTrustBundleFailure.MALFORMED_INPUT)
    } catch (_: StreamConstraintsException) {
        throw OfflineTrustBundleException(OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    } catch (_: JsonProcessingException) {
        throw OfflineTrustBundleException(OfflineTrustBundleFailure.MALFORMED_INPUT)
    } catch (_: SerializationException) {
        throw OfflineTrustBundleException(OfflineTrustBundleFailure.MALFORMED_INPUT)
    } catch (_: TestTerminalExceptionV1) {
        throw OfflineTrustBundleException(OfflineTrustBundleFailure.INVALID_DOCUMENT)
    } catch (_: ArithmeticException) {
        throw OfflineTrustBundleException(OfflineTrustBundleFailure.INVALID_DOCUMENT)
    }

    private class Container(val path: String, val inManifest: Boolean, val array: ArrayRule?, var count: Int = 0)
    private data class ArrayRule(val minimum: Int, val maximum: Int, val item: JsonToken)
}

internal sealed interface ParsedOfflineTerminalCatalogGeneration {
    data class Predecessor(val generation: ParsedOfflineTestRunCatalogGeneration) : ParsedOfflineTerminalCatalogGeneration
    data class TerminalV4(val envelope: OfflineCatalogTestRunTerminalEnvelopeV4) : ParsedOfflineTerminalCatalogGeneration
}
