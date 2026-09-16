package me.manga.kira.backend.complaint.domain.catalog

/**
 * Pure append-only inventory semantics. Inputs and ACCEPTED values are claims, not authenticated or provider-verified evidence.
 * The raw-chain owner verifies signatures and recomputes bundle commitments; this reducer grants no restore or catalog authority.
 */
internal object CatalogLogicalInventoryReducer {
    private val stem = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,95}")
    private val key = Regex("[A-Za-z0-9._/-]+")
    private val locationClasses = setOf("PRIMARY", "REPLICA", "OPERATOR", "OFFSITE")

    fun reduce(
        previous: CatalogRestoreInventoryV1,
        proposed: CatalogRestoreInventoryV1,
        operation: String,
        delta: CatalogInventoryDeltaV1,
        context: CatalogLogicalInventoryContext,
    ): CatalogRestoreInventoryV1 {
        validateContext(context)
        boundInventory(previous, context.maximumRecords)
        boundInventory(proposed, context.maximumRecords)
        val cardinality = when (operation) {
            CatalogLogicalInventoryProtocol.REGISTER_SOURCE -> 1 to 1
            CatalogLogicalInventoryProtocol.ADD_COPY -> 0 to 1
            OfflineCatalogChainProtocol.ROTATION_OVERLAP, OfflineCatalogChainProtocol.ROTATION_ACTIVATE -> 0 to 0
            else -> throw OfflineTrustBundleException(OfflineTrustBundleFailure.INVALID_DOCUMENT)
        }
        requireOfflineTrustBundle(delta.addedSourceIds.size == cardinality.first && delta.addedCopyIds.size == cardinality.second)
        val addition = delta.copy(addedSourceIds = delta.addedSourceIds.toList(), addedCopyIds = delta.addedCopyIds.toList())
        requireOfflineTrustBundle(addition.addedSourceIds.size == cardinality.first && addition.addedCopyIds.size == cardinality.second)
        validateIds(addition.addedSourceIds)
        validateIds(addition.addedCopyIds)
        val before = previous.snapshot()
        val after = proposed.snapshot()
        // Recheck the copied collections before constructing lookup tables or comparing records.
        boundInventory(before, context.maximumRecords)
        boundInventory(after, context.maximumRecords)
        validateInventory(before, context)
        validateInventory(after, context)
        requireExactAdditions(before, after, addition)
        when (operation) {
            CatalogLogicalInventoryProtocol.REGISTER_SOURCE -> {
                val copy = after.copies.single { it.copyId == addition.addedCopyIds.single() }
                requireOfflineTrustBundle(copy.sourceId == addition.addedSourceIds.single())
            }

            CatalogLogicalInventoryProtocol.ADD_COPY -> {
                val copy = after.copies.single { it.copyId == addition.addedCopyIds.single() }
                requireOfflineTrustBundle(before.sources.any { it.sourceId == copy.sourceId })
            }

            else -> requireOfflineTrustBundle(after == before)
        }
        return after
    }

    private fun validateContext(context: CatalogLogicalInventoryContext) {
        requireOfflineTrustBundle(
            OfflineBootstrapGrammar.uuidV4(context.databaseIdentity) && OfflineBootstrapGrammar.uuidV4(context.restoreIdentity) &&
                context.oldestRestoreTimeEpochSecond in 0..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND &&
                context.createdAtEpochSecond in context.oldestRestoreTimeEpochSecond..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND &&
                context.maximumRecords in 1..OfflineCatalogChainProtocol.MAX_MANIFEST_RECORDS,
            OfflineTrustBundleFailure.INVALID_POLICY,
        )
    }

    private fun boundInventory(inventory: CatalogRestoreInventoryV1, maximum: Int) {
        // Every inventory object: wrapper + (source, bundle, three artifacts) + (copy, three object-version references).
        val records = 1L + 5L * inventory.sources.size.toLong() + 4L * inventory.copies.size.toLong()
        requireOfflineTrustBundle(records <= maximum.toLong(), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    }

    private fun validateIds(values: List<String>) {
        requireOfflineTrustBundle(values.all(OfflineBootstrapGrammar::uuidV4))
        for (index in 1 until values.size) requireOfflineTrustBundle(values[index - 1] < values[index])
    }

    private fun validateInventory(inventory: CatalogRestoreInventoryV1, context: CatalogLogicalInventoryContext) {
        validateIds(inventory.sources.map { it.sourceId })
        validateIds(inventory.copies.map { it.copyId })
        inventory.sources.forEach { validateSource(it, context) }
        val sources = inventory.sources.associateBy { it.sourceId }
        val coordinates = mutableSetOf<ObjectCoordinate>()
        inventory.copies.forEach { copy ->
            val source = sources[copy.sourceId] ?: throw OfflineTrustBundleException(OfflineTrustBundleFailure.INVALID_DOCUMENT)
            validateCopy(copy, source, coordinates)
        }
        requireOfflineTrustBundle(inventory.copies.map { it.sourceId }.toSet() == sources.keys)
    }

    private fun validateSource(source: CatalogLogicalSourceV1, context: CatalogLogicalInventoryContext) {
        requireOfflineTrustBundle(
            source.kind == CatalogLogicalInventoryProtocol.SOURCE_KIND && source.state == CatalogLogicalInventoryProtocol.CLAIMED_STATE &&
                OfflineBootstrapGrammar.uuidV4(source.databaseIdentity) && OfflineBootstrapGrammar.uuidV4(source.restoreIdentity) &&
                OfflineBootstrapGrammar.sha256(source.bundleSha256),
        )
        requireOfflineTrustBundle(
            source.databaseIdentity == context.databaseIdentity && source.restoreIdentity == context.restoreIdentity &&
                source.restorePointEpochSecond in context.oldestRestoreTimeEpochSecond..context.createdAtEpochSecond,
            OfflineTrustBundleFailure.POLICY_MISMATCH,
        )
        val bundle = source.bundle
        requireOfflineTrustBundle(bundle.schema == CatalogLogicalInventoryProtocol.BACKUP_SCHEMA)
        requireOfflineTrustBundle(bundle.dump.name.length in 6..101 && bundle.dump.name.endsWith(".dump"))
        val name = bundle.dump.name.removeSuffix(".dump")
        requireOfflineTrustBundle(stem.matches(name))
        requireOfflineTrustBundle(bundle.manifest.name == "$name.bundle.json" && bundle.media.name == "$name.media.tar.gz")
        listOf(bundle.manifest, bundle.dump, bundle.media).forEach {
            requireOfflineTrustBundle(it.bytes > 0 && OfflineBootstrapGrammar.sha256(it.sha256))
        }
        requireOfflineTrustBundle(bundle.manifest.bytes <= CatalogLogicalInventoryProtocol.MAX_BACKUP_MANIFEST_BYTES)
    }

    private fun validateCopy(copy: CatalogLogicalCopyV1, source: CatalogLogicalSourceV1, coordinates: MutableSet<ObjectCoordinate>) {
        requireOfflineTrustBundle(
            copy.locationClass in locationClasses && copy.state == CatalogLogicalInventoryProtocol.CLAIMED_STATE &&
                copy.bundleSha256 == source.bundleSha256,
        )
        val roles = listOf(copy.manifest to source.bundle.manifest, copy.dump to source.bundle.dump, copy.media to source.bundle.media)
        var prefix: String? = null
        roles.forEach { (objectVersion, artifact) ->
            val currentPrefix = validateObject(objectVersion, artifact)
            requireOfflineTrustBundle(
                objectVersion.accountId == copy.manifest.accountId && objectVersion.region == copy.manifest.region &&
                    objectVersion.bucket == copy.manifest.bucket && (prefix == null || prefix == currentPrefix),
            )
            prefix = currentPrefix
            requireOfflineTrustBundle(
                coordinates.add(
                    ObjectCoordinate(objectVersion.accountId, objectVersion.region, objectVersion.bucket, objectVersion.key, objectVersion.versionId),
                ),
            )
        }
    }

    private fun validateObject(objectVersion: CatalogS3ObjectVersionV1, artifact: CatalogBackupArtifactV1): String {
        requireOfflineTrustBundle(
            OfflineBootstrapGrammar.account(objectVersion.accountId) && OfflineBootstrapGrammar.region(objectVersion.region) &&
                OfflineBootstrapGrammar.bucket(objectVersion.bucket) &&
                objectVersion.bytes == artifact.bytes && objectVersion.sha256 == artifact.sha256,
        )
        val name = objectVersion.key
        requireOfflineTrustBundle(name.length in 1..CatalogLogicalInventoryProtocol.MAX_S3_KEY_BYTES && key.matches(name))
        requireOfflineTrustBundle(name.split('/').none { it.isEmpty() || it == "." || it == ".." })
        val suffix = "/${artifact.name}"
        requireOfflineTrustBundle(name.endsWith(suffix) && name.length > suffix.length)
        val version = objectVersion.versionId
        requireOfflineTrustBundle(
            version.length in 1..CatalogLogicalInventoryProtocol.MAX_S3_VERSION_BYTES && version != "null" && version.all { it in '!'..'~' },
        )
        return name.dropLast(suffix.length)
    }

    private fun requireExactAdditions(previous: CatalogRestoreInventoryV1, proposed: CatalogRestoreInventoryV1, delta: CatalogInventoryDeltaV1) {
        val previousSources = previous.sources.associateBy { it.sourceId }
        val previousCopies = previous.copies.associateBy { it.copyId }
        val nextSources = proposed.sources.associateBy { it.sourceId }
        val nextCopies = proposed.copies.associateBy { it.copyId }
        requireOfflineTrustBundle(previous.sources.all { nextSources[it.sourceId] == it } && previous.copies.all { nextCopies[it.copyId] == it })
        requireOfflineTrustBundle(proposed.sources.filter { it.sourceId !in previousSources }.map { it.sourceId } == delta.addedSourceIds)
        requireOfflineTrustBundle(proposed.copies.filter { it.copyId !in previousCopies }.map { it.copyId } == delta.addedCopyIds)
    }

    private data class ObjectCoordinate(val accountId: String, val region: String, val bucket: String, val key: String, val versionId: String)
}
