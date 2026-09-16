package me.manga.kira.backend.complaint.domain.catalog

import kotlinx.serialization.Serializable

/** Separate closed profile. Existing schema-1 genesis/rotation envelopes keep their exact DTOs and bytes. */
@Serializable
internal data class OfflineCatalogInventoryEnvelopeV2(
    val schemaVersion: Int,
    val manifest: OfflineCatalogInventoryManifestV2,
    val signatures: List<OfflineCatalogGenesisSignatureV1>,
)

@Serializable
internal data class OfflineCatalogInventoryManifestV2(
    val schemaVersion: Int,
    val profile: String,
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
    val restoreInventory: CatalogRestoreInventoryV1,
    val inventoryDelta: CatalogInventoryDeltaV1,
    val history: GenesisEmptyHistoryV1,
)

/** Signature-checked metadata only, not a verified provider inventory, accepted head or permission to restore. */
internal class CheckedOfflineCatalogInventoryChain(
    val tail: CatalogTailEvidence,
    val trust: CatalogChainTrustEvidence,
    val rotation: CatalogRotationState,
    val encodedBytes: Long,
    inventory: CatalogRestoreInventoryV1,
) {
    private val storedInventory = inventory.snapshot()

    val inventory: CatalogRestoreInventoryV1 get() = storedInventory.snapshot()

    override fun toString(): String = "CheckedOfflineCatalogInventoryChain(no-accepted-head,no-provider-or-restore-authority)"
}

internal object OfflineCatalogInventoryProtocol {
    const val SCHEMA_VERSION = 2
    const val PROFILE = "NEW_BACKEND_LOGICAL_BUNDLE_V1"
}
