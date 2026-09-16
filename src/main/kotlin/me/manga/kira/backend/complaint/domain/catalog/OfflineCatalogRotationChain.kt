package me.manga.kira.backend.complaint.domain.catalog

import kotlinx.serialization.Serializable

/** Only the two declared rotation operations; not a generic catalog/history envelope or an acceptance record. */
@Serializable
internal data class OfflineCatalogRotationEnvelopeV1(
    val schemaVersion: Int,
    val manifest: OfflineCatalogRotationManifestV1,
    val signatures: List<OfflineCatalogGenesisSignatureV1>,
)

@Serializable
internal data class OfflineCatalogRotationManifestV1(
    val schemaVersion: Int,
    val canonicalizerId: String,
    val operation: String,
    val operationToken: String,
    val generation: Long,
    val previousEnvelopeSha256: String,
    val initialTrustBundleEnvelopeSha256: String,
    val catalogWriterGenerationId: String,
    val requiredSignerPolicy: CatalogSignerPolicyV1,
    val creation: OfflineCatalogGenesisCreationV1,
    val approvals: List<OfflineCatalogGenesisApprovalV1>,
    val oldestRestoreTimeEpochSecond: Long,
    val initialWriterRegistry: OfflineBootstrapRegistryV1,
    val restoreInventory: GenesisEmptyHeadV1,
    val history: GenesisEmptyHistoryV1,
)

/** Current signing policy is folded separately; the unchanged bootstrap registry still records its INITIAL policy. */
@Serializable
internal data class CatalogSignerPolicyV1(val mode: String, val threshold: String, val members: List<OfflineRequiredSignerV1>)

/** Immutable metadata only. No payload history, accepted head, namespace/no-reset proof, or signer-disable permission. */
internal class CheckedOfflineCatalogRotationChain(
    val tail: CatalogTailEvidence,
    val trust: CatalogChainTrustEvidence,
    val rotation: CatalogRotationState,
    val encodedBytes: Long,
) {
    override fun toString(): String = "CheckedOfflineCatalogRotationChain(no-accepted-head,no-signer-disable-permission)"
}

internal data class CatalogTailEvidence(val generation: Long, val manifestSha256: String, val envelopeSha256: String, val catalogWriterGenerationId: String)

internal data class CatalogChainTrustEvidence(
    val initialBundleEnvelopeSha256: String,
    val currentBundleEnvelopeSha256: String,
    val currentBundleVersion: Long,
    val genesisEnvelopeSha256: String,
    val minimumHeadGeneration: Long,
)

internal sealed interface CatalogRotationState {
    data class Stable(val active: OfflineRequiredSignerV1) : CatalogRotationState

    /** The next admitted chain operation can only activate next; this evidence grants no mutation privilege. */
    data class AwaitingActivation(val previous: OfflineRequiredSignerV1, val next: OfflineRequiredSignerV1) : CatalogRotationState
}
