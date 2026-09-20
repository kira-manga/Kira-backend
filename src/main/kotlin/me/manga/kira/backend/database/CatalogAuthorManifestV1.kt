package me.manga.kira.backend.database

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainLimits
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogLocationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundlePolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisAuthorDatabaseV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeFilesV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeRequestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogSigningKeyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.boundedCatalogFreezeFailure
import me.manga.kira.backend.complaint.infrastructure.catalog.preferCatalogFreezeCleanup
import me.manga.kira.backend.complaint.infrastructure.catalog.requireCatalogFreeze
import me.manga.kira.backend.security.ImmutableSecretVersion
import me.manga.kira.backend.security.SecretMaterialFamily
import me.manga.kira.backend.security.SecretMaterialPurpose
import me.manga.kira.backend.security.VersionedSecretBinding
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.FileSystems
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Base64

/** Closed acquisition input only; never TARGET P/D, effective grants, released pin or completed custody. */
internal object CatalogAuthorManifestV1 {
    const val MAX_BYTES = 65_536
    private val factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(4096).maxNameLength(64).maxNumberLength(19).build())
        .build()
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    @Suppress("TooGenericExceptionCaught") // No parser/path/value diagnostics cross this fixed input boundary; signals keep accepted precedence.
    fun parse(bytes: ByteArray, independentPin: Path?): CatalogGenesisFreezeRequestV1 = try {
        requireCatalogFreeze(bytes.size in 1..MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        factory.createParser(text).use { parser ->
            requireCatalogFreeze(parser.nextToken() == JsonToken.START_OBJECT)
            var depth = 1
            var tokens = 1
            while (depth > 0) {
                val token = parser.nextToken()
                requireCatalogFreeze(token != null && ++tokens <= 4096)
                when (token) {
                    JsonToken.START_ARRAY, JsonToken.START_OBJECT -> depth++
                    JsonToken.END_ARRAY, JsonToken.END_OBJECT -> depth--
                    JsonToken.VALUE_NUMBER_FLOAT -> requireCatalogFreeze(false)
                    else -> Unit
                }
            }
            requireCatalogFreeze(parser.nextToken() == null)
        }
        decode(json.decodeFromString(CatalogAuthorDocumentV1.serializer(), text), independentPin)
    } catch (failure: Exception) {
        throw boundedCatalogFreezeFailure(
            preferCatalogFreezeCleanup(failure, CatalogGenesisFreezeExceptionV1(CatalogGenesisFreezeFailureV1.INPUT_REFUSED)),
        )
    }

    private fun decode(document: CatalogAuthorDocumentV1, pin: Path?): CatalogGenesisFreezeRequestV1 {
        requireCatalogFreeze(document.schemaVersion == 1 && OfflineBootstrapGrammar.sha256(document.capacityPolicySha256))
        val database = document.database
        val secret = database.password
        val trust = document.trust
        val policy = OfflineTrustBundlePolicy(
            spki(trust.rootPublicKeySpkiBase64),
            trust.rootPublicKeySha256,
            trust.rootKeyId,
            trust.rootAlgorithmId,
            trust.expectedEnvironment,
            trust.expectedCatalogLocations,
            trust.minimumBundleVersion,
        )
        val chain = OfflineCatalogChainReaderPolicy(
            policy,
            trust.currentWriterGenerationIds,
            trust.currentApproverIds,
            trust.limits.let { OfflineCatalogChainLimits(it.maximumEnvelopeBytes, it.maximumManifestRecords, it.maximumGenerations, it.maximumEncodedBytes) },
        )
        val signing = document.signing
        val key = CatalogSigningKeyV1(signing.keyId, signing.keyArn, signing.algorithmId, spki(signing.publicKeySpkiBase64), signing.publicKeySha256)
        return CatalogGenesisFreezeRequestV1(
            CatalogGenesisAuthorDatabaseV1(
                VersionedSecretBinding.of(
                    SecretMaterialFamily.DATABASE,
                    SecretMaterialPurpose.AUTHENTICATION_PASSWORD,
                    secret.keyId,
                    ImmutableSecretVersion.awsSecretsManager(secret.resourceArn, secret.versionId),
                ),
                database.host,
                database.port,
                database.name,
                catalogAuthorPath(database.publicTrustPem),
                catalogAuthorPath(database.protectedTrustParent),
            ),
            document.files.let {
                CatalogGenesisFreezeFilesV1(
                    catalogAuthorPath(it.approvedIntent),
                    catalogAuthorPath(it.initialBundle),
                    catalogAuthorPath(it.currentBundle),
                    catalogAuthorPath(it.approvalInputs),
                )
            },
            chain,
            document.capacityPolicySha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            key,
            catalogAuthorPath(document.releaseRoot),
            pin,
        )
    }

    private fun spki(value: String): ByteArray {
        requireCatalogFreeze(value.length == 4 * ((OfflineTrustBundleProtocol.PUBLIC_KEY_BYTES + 2) / 3))
        val bytes = Base64.getDecoder().decode(value)
        requireCatalogFreeze(bytes.size == OfflineTrustBundleProtocol.PUBLIC_KEY_BYTES && Base64.getEncoder().encodeToString(bytes) == value)
        return bytes
    }
}

internal fun catalogAuthorPath(raw: String): Path {
    requireCatalogFreeze(raw.length in 1..4096)
    val path = try {
        Path.of(raw)
    } catch (_: InvalidPathException) {
        throw CatalogGenesisFreezeExceptionV1(CatalogGenesisFreezeFailureV1.INPUT_REFUSED)
    }
    requireCatalogFreeze(path.fileSystem === FileSystems.getDefault() && path.isAbsolute && path.normalize() == path)
    return path
}

@Serializable
internal data class CatalogAuthorDocumentV1(
    val schemaVersion: Int,
    val database: CatalogAuthorDatabaseDocumentV1,
    val files: CatalogAuthorFilesDocumentV1,
    val trust: CatalogAuthorTrustDocumentV1,
    val capacityPolicySha256: String,
    val signing: CatalogAuthorSigningDocumentV1,
    val releaseRoot: String,
)

@Serializable
internal data class CatalogAuthorSecretDocumentV1(val keyId: String, val resourceArn: String, val versionId: String)

@Serializable
internal data class CatalogAuthorDatabaseDocumentV1(
    val host: String,
    val port: Int,
    val name: String,
    val password: CatalogAuthorSecretDocumentV1,
    val publicTrustPem: String,
    val protectedTrustParent: String,
)

@Serializable
internal data class CatalogAuthorFilesDocumentV1(val approvedIntent: String, val initialBundle: String, val currentBundle: String, val approvalInputs: String)

@Serializable
internal data class CatalogAuthorTrustDocumentV1(
    val rootPublicKeySpkiBase64: String,
    val rootPublicKeySha256: String,
    val rootKeyId: String,
    val rootAlgorithmId: String,
    val expectedEnvironment: String,
    val expectedCatalogLocations: List<OfflineCatalogLocationV1>,
    val minimumBundleVersion: Long,
    val currentWriterGenerationIds: List<String>,
    val currentApproverIds: List<String>,
    val limits: CatalogAuthorChainLimitsV1,
)

@Serializable
internal data class CatalogAuthorChainLimitsV1(
    val maximumEnvelopeBytes: Int,
    val maximumManifestRecords: Int,
    val maximumGenerations: Int,
    val maximumEncodedBytes: Long,
)

@Serializable
internal data class CatalogAuthorSigningDocumentV1(
    val keyId: String,
    val keyArn: String,
    val algorithmId: String,
    val publicKeySpkiBase64: String,
    val publicKeySha256: String,
)
