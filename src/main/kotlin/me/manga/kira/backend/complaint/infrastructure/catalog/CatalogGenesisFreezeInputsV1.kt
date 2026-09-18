package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisSignatureProposal
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundlePolicy
import me.manga.kira.backend.complaint.domain.catalog.UnverifiedGenesisPreparation
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogSigningKeyV1
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import me.manga.kira.backend.security.SecretMaterialFamily
import me.manga.kira.backend.security.SecretMaterialPurpose
import me.manga.kira.backend.security.VersionedSecretBinding
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/** Typed acquisition requests only. Neither paths nor an immutable secret reference claim that acquisition already occurred. */
internal class CatalogGenesisAuthorDatabaseV1(
    val authenticationPassword: VersionedSecretBinding,
    val host: String,
    val port: Int,
    val database: String,
    val publicTrustPem: Path,
    val protectedTrustParent: Path,
) {
    override fun toString(): String = "CatalogGenesisAuthorDatabaseV1(acquisition-input,redacted)"
}

internal class CatalogGenesisFreezeFilesV1(val approvedIntent: Path, val initialBundle: Path, val currentBundle: Path, val approvalInputs: Path) {
    override fun toString(): String = "CatalogGenesisFreezeFilesV1(acquisition-input,redacted)"
}

internal class CatalogGenesisFreezeRequestV1(
    val database: CatalogGenesisAuthorDatabaseV1,
    val files: CatalogGenesisFreezeFilesV1,
    val chainPolicy: OfflineCatalogChainReaderPolicy,
    capacityPolicyDigest: ByteArray,
    val signingKey: CatalogSigningKeyV1,
    val releaseRoot: Path,
    val independentPin: Path? = null,
) {
    private val capacity = capacityPolicyDigest.also { requireCatalogFreeze(it.size == 32) }.copyOf()
    internal fun capacityDigest(): ByteArray = capacity.copyOf()
    override fun toString(): String = "CatalogGenesisFreezeRequestV1(independent-inputs,redacted,no-authority)"
}

/** Actual bounded file snapshots owned by one invocation. All raw verification is repeated on resume. */
internal class CatalogGenesisFreezeInputsV1(
    val request: CatalogGenesisFreezeRequestV1,
    private val intent: ByteArray,
    private val initial: ByteArray,
    private val current: ByteArray,
    val approvals: ByteArray,
    val publicTrust: ByteArray,
) {
    val chain = copyPolicy(request.chainPolicy)
    private val capacity = request.capacityDigest()
    private val manifest = OfflineTrustBundleParser.parseGenesisManifest(intent)
    private val currentTrust = OfflineTrustBundleVerifier.verify(current, chain.trustBundlePolicy)
    private val unsignedInput = CatalogGenesisMutationInput.prepare(intent, initial, current, chain)
    val publicTarget: ByteArray
    val retention: ByteArray
    val allocation: ByteArray

    init {
        val binding = request.database.authenticationPassword
        requireCatalogFreeze(binding.family === SecretMaterialFamily.DATABASE && binding.purpose === SecretMaterialPurpose.AUTHENTICATION_PASSWORD)
        requireCatalogFreeze(capacity.size == 32 && currentTrust.body.minimumCatalogHeadGeneration == 1L)
        requireCatalogFreeze(request.database.host.length in 1..253 && request.database.database.length in 1..63 && request.database.port in 1..65_535)
        val expectedApprovals = CanonicalJson.canonicalize(ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), manifest.approvals).toByteArray()
        requireCatalogFreeze(approvals.contentEquals(expectedApprovals)) // Existing approval wire bytes, not authentication of humans.
        val key = request.signingKey
        val signer = currentTrust.body.signers.singleOrNull { it.keyId == key.keyId }
            ?: throw CatalogGenesisFreezeExceptionV1(CatalogGenesisFreezeFailureV1.INPUT_REFUSED)
        requireCatalogFreeze(unsignedInput.before.signatureSlots.single().keyId == key.keyId && signer.algorithmId == key.algorithmId)
        requireCatalogFreeze(key.publicKey().encoded.contentEquals(Base64.getDecoder().decode(signer.publicKeySpkiBase64)))
        val trust = chain.trustBundlePolicy
        publicTarget = catalogFreezeRecord(
            "public-target", request.database.host, request.database.port.toString(), request.database.database, Sha256.hex(publicTrust),
            binding.logicalKeyId, binding.version.resourceArn, binding.version.versionId, Sha256.hex(capacity),
            key.keyId, key.keyArn, key.algorithmId, signer.publicKeySha256, trust.rootKeyId, trust.rootPublicKeySha256,
            trust.expectedEnvironment, trust.minimumBundleVersion.toString(),
            chain.currentWriterGenerationIds.joinToString(","), chain.currentApproverIds.joinToString(","),
            chain.limits.maximumEnvelopeBytes.toString(), chain.limits.maximumManifestRecords.toString(),
            chain.limits.maximumGenerations.toString(), chain.limits.maximumEncodedBytes.toString(),
        )
        val created = manifest.creation.createdAtEpochSecond
        val retainUntil = Instant.ofEpochSecond(created).atOffset(ZoneOffset.UTC).plusYears(10).toEpochSecond()
        retention = catalogFreezeRecord("retention", created.toString(), retainUntil.toString(), "COMPLIANCE", "SHA256")
        allocation = catalogFreezeRecord(
            "allocation",
            manifest.operationToken,
            Sha256.hex(intent),
            Sha256.hex(initial),
            Sha256.hex(current),
            Sha256.hex(approvals),
            Sha256.hex(publicTarget),
            Sha256.hex(retention),
        )
    }

    fun intentBytes(): ByteArray = intent.copyOf()
    fun initialBytes(): ByteArray = initial.copyOf()
    fun currentBytes(): ByteArray = current.copyOf()
    fun capacityDigest(): ByteArray = capacity.copyOf()
    fun preparationInput(): CatalogGenesisMutationInput = CatalogGenesisMutationInput.prepare(intent, initial, current, chain)

    fun requireUnsigned(local: UnverifiedGenesisPreparation) {
        val signing = CatalogGenesisPreparationVerifier.signingInput(local, intent, initial, current, chain)
        requireCatalogFreeze(signing.keyId == request.signingKey.keyId && signing.algorithmId == request.signingKey.algorithmId)
    }

    fun proposal(local: UnverifiedGenesisPreparation, signature: ByteArray?): CatalogGenesisSignatureProposal =
        CatalogGenesisPreparationVerifier.proposeSignature(local, intent, initial, current, chain, signature)

    fun signatureInput(local: UnverifiedGenesisPreparation, signature: ByteArray): CatalogGenesisMutationInput =
        CatalogGenesisMutationInput.signature(local, intent, initial, current, chain, signature)

    fun verifyPinned(proposal: CatalogGenesisSignatureProposal, local: UnverifiedGenesisPreparation, pin: String) {
        val now = Instant.now().epochSecond
        // No storage observation is claimed: this policy is used ONLY for raw local preparation verification.
        val policy = CatalogReadbackPolicy(chain, pin, now, Math.addExact(now, 1L), 1, 1)
        CatalogGenesisPreparationVerifier.verifyPinnedReadback(proposal, local, initial, current, policy)
    }

    override fun toString(): String = "CatalogGenesisFreezeInputsV1(actual-file-snapshots,redacted,no-authority)"

    companion object {
        private fun copyPolicy(policy: OfflineCatalogChainReaderPolicy): OfflineCatalogChainReaderPolicy {
            val trust = policy.trustBundlePolicy
            return OfflineCatalogChainReaderPolicy(
                OfflineTrustBundlePolicy(
                    trust.rootPublicKeySpki,
                    trust.rootPublicKeySha256,
                    trust.rootKeyId,
                    trust.rootAlgorithmId,
                    trust.expectedEnvironment,
                    trust.expectedCatalogLocations,
                    trust.minimumBundleVersion,
                ),
                policy.currentWriterGenerationIds,
                policy.currentApproverIds,
                policy.limits.copy(),
            )
        }
    }
}

/** Fixed unsigned local history/completeness bytes only; not a signed wire format or an approval capability. */
internal fun catalogFreezeRecord(kind: String, vararg values: String): ByteArray =
    CanonicalJson.canonicalize(ListSerializer(String.serializer()), listOf("catalog-genesis-freeze-v1", kind) + values).toByteArray()
