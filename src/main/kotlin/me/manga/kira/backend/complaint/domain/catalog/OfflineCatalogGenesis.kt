package me.manga.kira.backend.complaint.domain.catalog

import kotlinx.serialization.Serializable

/** Closed genesis candidate only, not a general catalog generation or history representation. */
@Serializable
internal data class OfflineCatalogGenesisEnvelopeV1(
    val schemaVersion: Int,
    val manifest: OfflineCatalogGenesisManifestV1,
    val signatures: List<OfflineCatalogGenesisSignatureV1>,
)

@Serializable
internal data class OfflineCatalogGenesisSignatureV1(val keyId: String, val algorithmId: String, val signatureBase64: String)

@Serializable
internal data class OfflineCatalogGenesisManifestV1(
    val schemaVersion: Int,
    val canonicalizerId: String,
    val operation: String,
    val operationToken: String,
    val generation: Long,
    val previousEnvelopeSha256: String,
    val initialTrustBundleEnvelopeSha256: String,
    val catalogWriterGenerationId: String,
    val requiredSignerPolicy: InitialSignerPolicyV1,
    val creation: OfflineCatalogGenesisCreationV1,
    val approvals: List<OfflineCatalogGenesisApprovalV1>,
    val oldestRestoreTimeEpochSecond: Long,
    val initialWriterRegistry: OfflineBootstrapRegistryV1,
    val restoreInventory: GenesisEmptyHeadV1,
    val history: GenesisEmptyHistoryV1,
)

@Serializable
internal data class OfflineCatalogGenesisCreationV1(val creatorId: String, val createdAtEpochSecond: Long)

@Serializable
internal data class OfflineCatalogGenesisApprovalV1(val approverId: String, val approvedAtEpochSecond: Long)

/** Only count zero and SHA256(canonical []) are permitted; this is not a general cumulative-history head. */
@Serializable
internal data class GenesisEmptyHeadV1(val count: Long, val sha256: String)

@Serializable
internal data class GenesisEmptyHistoryV1(
    val expiredRestoreSources: GenesisEmptyHeadV1,
    val testRunActivations: GenesisEmptyHeadV1,
    val testRunTerminals: GenesisEmptyHeadV1,
    val installationManifests: GenesisEmptyHeadV1,
    val epochSeals: GenesisEmptyHeadV1,
    val retirementAuthorizations: GenesisEmptyHeadV1,
    val retirementCompletions: GenesisEmptyHeadV1,
)

/** Defensive evidence claims, never an accepted head, registration, restore decision, or credential capability. */
internal class CheckedOfflineCatalogGenesis(
    manifest: OfflineCatalogGenesisManifestV1,
    canonicalManifestBytes: ByteArray,
    canonicalEnvelopeBytes: ByteArray,
    val manifestSha256: String,
    val envelopeSha256: String,
) {
    private val storedManifest = manifest.snapshot()
    private val storedManifestBytes = canonicalManifestBytes.copyOf()
    private val storedEnvelopeBytes = canonicalEnvelopeBytes.copyOf()

    val manifest: OfflineCatalogGenesisManifestV1 get() = storedManifest.snapshot()
    val canonicalManifestBytes: ByteArray get() = storedManifestBytes.copyOf()
    val canonicalEnvelopeBytes: ByteArray get() = storedEnvelopeBytes.copyOf()

    override fun toString(): String = "CheckedOfflineCatalogGenesis(no-accepted-head)"
}

/**
 * Composition of defensive evidence holders only. Does not accept a head or prove namespace uniqueness/no reset.
 * The current bundle's head floor still applies to the eventual complete chain, never to this historical G1 alone.
 */
internal class CheckedHistoricalGenesisEvidence(
    val genesis: CheckedOfflineCatalogGenesis,
    val initialTrustBundle: SignatureCheckedOfflineTrustBundle,
    val currentTrustBundle: SignatureCheckedOfflineTrustBundle,
) {
    override fun toString(): String = "CheckedHistoricalGenesisEvidence(historical-only,no-accepted-head)"
}

private fun OfflineCatalogGenesisManifestV1.snapshot(): OfflineCatalogGenesisManifestV1 = copy(
    requiredSignerPolicy = requiredSignerPolicy.copy(members = requiredSignerPolicy.members.toList()),
    approvals = approvals.toList(),
    initialWriterRegistry = initialWriterRegistry.snapshot(),
)

internal object OfflineCatalogGenesisProtocol {
    const val DOMAIN = "kira.complaints.catalog-generation.v1"
    const val ZERO_PREDECESSOR_SHA256 = "0000000000000000000000000000000000000000000000000000000000000000"
}
