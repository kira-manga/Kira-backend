package me.manga.kira.backend.complaint.domain.catalog

import kotlinx.serialization.Serializable

/** Exact-byte descriptions only. The external backup manifest is NOT a kcj-1 document. */
@Serializable
internal data class CatalogBackupArtifactV1(val name: String, val bytes: Long, val sha256: String)

/** Named fields are the three fixed roles; diagnostic TOC/checksum sidecars are not selection authority. */
@Serializable
internal data class CatalogLogicalBundleV1(
    val schema: String,
    val manifest: CatalogBackupArtifactV1,
    val dump: CatalogBackupArtifactV1,
    val media: CatalogBackupArtifactV1,
)

/** ACCEPTED is an authenticated writer's claim, never local backup/restore acceptance or provider evidence. */
@Serializable
internal data class CatalogLogicalSourceV1(
    val sourceId: String,
    val kind: String,
    val databaseIdentity: String,
    val restoreIdentity: String,
    val restorePointEpochSecond: Long,
    val state: String,
    val bundleSha256: String,
    val bundle: CatalogLogicalBundleV1,
)

@Serializable
internal data class CatalogS3ObjectVersionV1(
    val accountId: String,
    val region: String,
    val bucket: String,
    val key: String,
    val versionId: String,
    val bytes: Long,
    val sha256: String,
)

@Serializable
internal data class CatalogLogicalCopyV1(
    val copyId: String,
    val sourceId: String,
    val locationClass: String,
    val state: String,
    val bundleSha256: String,
    val manifest: CatalogS3ObjectVersionV1,
    val dump: CatalogS3ObjectVersionV1,
    val media: CatalogS3ObjectVersionV1,
)

/** Full current inventory, never a list of only this generation's additions. */
@Serializable
internal data class CatalogRestoreInventoryV1(val sources: List<CatalogLogicalSourceV1>, val copies: List<CatalogLogicalCopyV1>)

/** IDs refer to the complete records in the signed successor inventory; no opaque records or implicit additions. */
@Serializable
internal data class CatalogInventoryDeltaV1(val addedSourceIds: List<String>, val addedCopyIds: List<String>)

internal data class CatalogLogicalInventoryContext(
    val databaseIdentity: String,
    val restoreIdentity: String,
    val oldestRestoreTimeEpochSecond: Long,
    val createdAtEpochSecond: Long,
    val maximumRecords: Int,
)

/** All elements contain only immutable scalar/value fields; snapshot the two caller-owned collections. */
internal fun CatalogRestoreInventoryV1.snapshot(): CatalogRestoreInventoryV1 = copy(sources = sources.toList(), copies = copies.toList())

internal object CatalogLogicalInventoryProtocol {
    const val SOURCE_KIND = "KIRA_BACKUP_BUNDLE_V1"
    const val BACKUP_SCHEMA = "kira.backup-bundle.v1"
    const val CLAIMED_STATE = "ACCEPTED"
    const val REGISTER_SOURCE = "REGISTER_SOURCE"
    const val ADD_COPY = "ADD_COPY"
    const val MAX_BACKUP_MANIFEST_BYTES = 4096L
    const val MAX_S3_KEY_BYTES = 1024
    const val MAX_S3_VERSION_BYTES = 1024
    const val LAST_EPOCH_SECOND = 253402300799L
}
