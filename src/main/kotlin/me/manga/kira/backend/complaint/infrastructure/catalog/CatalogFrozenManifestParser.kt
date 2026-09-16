package me.manga.kira.backend.complaint.infrastructure.catalog

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogInventoryDeltaV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainLimits
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisSignatureV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogInventoryManifestV2
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationManifestV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogInventoryParser
import me.manga.kira.backend.complaint.parsing.catalog.ParsedOfflineCatalogGeneration

/** Private local parsing result. Empty signatures in the unsigned wrapper are parsing only, never authentication. */
internal data class FrozenCatalogGeneration(
    val schemaVersion: Int,
    val claims: CatalogGenerationAuthenticationClaims,
    val manifestBytes: ByteArray,
    val signatures: List<OfflineCatalogGenesisSignatureV1>,
    val envelopeBytes: ByteArray?,
    val inventoryDelta: CatalogInventoryDeltaV1? = null,
) {
    val manifestSha256: String get() = Sha256.hex(manifestBytes)
    val envelopeSha256: String? get() = envelopeBytes?.let(Sha256::hex)
}

internal object CatalogFrozenManifestParser {
    private val selectorFactory = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(16)
                .maxStringLength(4096)
                .maxNameLength(64)
                .maxNumberLength(19)
                .build(),
        )
        .build()

    /** V14 stores no envelope/schema selector. Read the real root field; the closed parser still checks ALL bytes below. */
    fun schemaVersion(bytes: ByteArray, limits: OfflineCatalogChainLimits): Int {
        requireCatalogReadback(bytes.size in 1..limits.maximumEnvelopeBytes, CatalogReadbackFailure.LIMIT_EXCEEDED)
        try {
            return selectorFactory.createParser(bytes).use { parser ->
                requireCatalogReadback(parser.nextToken() == JsonToken.START_OBJECT, CatalogReadbackFailure.INVALID_LOCAL_STATE)
                var selected: Int? = null
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    requireCatalogReadback(parser.currentToken() == JsonToken.FIELD_NAME, CatalogReadbackFailure.INVALID_LOCAL_STATE)
                    val field = parser.currentName()
                    val value = parser.nextToken()
                    if (field == "schemaVersion") {
                        requireCatalogReadback(
                            value == JsonToken.VALUE_NUMBER_INT && (parser.text == "1" || parser.text == "2"),
                            CatalogReadbackFailure.INVALID_LOCAL_STATE,
                        )
                        selected = parser.text.toInt()
                    } else {
                        parser.skipChildren()
                    }
                }
                requireCatalogReadback(parser.nextToken() == null, CatalogReadbackFailure.INVALID_LOCAL_STATE)
                selected ?: throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LOCAL_STATE)
            }
        } catch (_: StreamConstraintsException) {
            throw CatalogReadbackException(CatalogReadbackFailure.LIMIT_EXCEEDED)
        } catch (_: JsonProcessingException) {
            throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LOCAL_STATE)
        }
    }

    fun unsigned(schemaVersion: Int, bytes: ByteArray, limits: OfflineCatalogChainLimits): FrozenCatalogGeneration {
        requireCatalogReadback(schemaVersion == 1 || schemaVersion == 2, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        val prefix = "{\"manifest\":".toByteArray(Charsets.UTF_8)
        val suffix = ",\"schemaVersion\":$schemaVersion,\"signatures\":[]}".toByteArray(Charsets.UTF_8)
        val length = prefix.size.toLong() + bytes.size + suffix.size
        requireCatalogReadback(length <= limits.maximumEnvelopeBytes, CatalogReadbackFailure.LIMIT_EXCEEDED)
        val envelope = ByteArray(length.toInt())
        prefix.copyInto(envelope)
        bytes.copyInto(envelope, prefix.size)
        suffix.copyInto(envelope, prefix.size + bytes.size)
        val parsed = signed(envelope, limits)
        requireCatalogReadback(parsed.manifestBytes.contentEquals(bytes), CatalogReadbackFailure.INVALID_LOCAL_STATE)
        return parsed.copy(envelopeBytes = null)
    }

    fun signed(bytes: ByteArray, limits: OfflineCatalogChainLimits): FrozenCatalogGeneration {
        requireCatalogReadback(bytes.size in 1..limits.maximumEnvelopeBytes, CatalogReadbackFailure.LIMIT_EXCEEDED)
        val parsed = try {
            OfflineCatalogInventoryParser.parse(bytes, limits.maximumManifestRecords)
        } catch (failure: OfflineTrustBundleException) {
            val code = if (failure.code == OfflineTrustBundleFailure.LIMIT_EXCEEDED) {
                CatalogReadbackFailure.LIMIT_EXCEEDED
            } else {
                CatalogReadbackFailure.INVALID_LOCAL_STATE
            }
            throw CatalogReadbackException(code)
        }
        return when (parsed) {
            is ParsedOfflineCatalogGeneration.RotationV1 -> {
                val manifest = parsed.envelope.manifest
                requireCatalogReadback(
                    manifest.schemaVersion == 1 && manifest.canonicalizerId == CanonicalJson.CANON_VERSION,
                    CatalogReadbackFailure.INVALID_LOCAL_STATE,
                )
                FrozenCatalogGeneration(
                    1,
                    manifest.authenticationClaims(),
                    CanonicalJson.canonicalize(OfflineCatalogRotationManifestV1.serializer(), manifest).toByteArray(Charsets.UTF_8),
                    parsed.envelope.signatures,
                    bytes,
                )
            }

            is ParsedOfflineCatalogGeneration.InventoryV2 -> {
                val manifest = parsed.envelope.manifest
                requireCatalogReadback(
                    manifest.schemaVersion == 2 && manifest.canonicalizerId == CanonicalJson.CANON_VERSION &&
                        manifest.profile == OfflineCatalogInventoryProtocol.PROFILE,
                    CatalogReadbackFailure.INVALID_LOCAL_STATE,
                )
                FrozenCatalogGeneration(
                    2,
                    manifest.authenticationClaims(),
                    CanonicalJson.canonicalize(OfflineCatalogInventoryManifestV2.serializer(), manifest).toByteArray(Charsets.UTF_8),
                    parsed.envelope.signatures,
                    bytes,
                    manifest.inventoryDelta,
                )
            }
        }
    }
}
