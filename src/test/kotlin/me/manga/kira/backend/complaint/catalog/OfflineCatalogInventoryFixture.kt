package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogBackupArtifactV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogInventoryDeltaV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalBundleV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalCopyV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalSourceV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogRestoreInventoryV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogS3ObjectVersionV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerPolicyV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisSignatureV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogInventoryEnvelopeV2
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogInventoryManifestV2
import java.security.Signature
import java.util.Base64

/** Small synthetic metadata/signatures, not valid dump/archive bytes or observations of real provider copies. */
internal object OfflineCatalogInventoryFixture {
    private val bundles = OfflineTrustBundleFixture
    private val rotations = OfflineCatalogRotationFixture
    const val SOURCE_ID = "66666666-6666-4666-8666-666666666666"

    fun copyId(index: Int): String = "${index.toString(16).padStart(8, '0')}-7777-4777-8777-777777777777"

    fun source(genesis: OfflineCatalogGenesisEnvelopeV1): CatalogLogicalSourceV1 {
        val dump = CatalogBackupArtifactV1("backup-1.dump", 123, Sha256.hexUtf8("synthetic dump bytes"))
        val media = CatalogBackupArtifactV1("backup-1.media.tar.gz", 456, Sha256.hexUtf8("synthetic media bytes"))
        // Backend29 producer spelling has a terminal LF; these exact bytes are NOT kcj-1.
        val manifest = "{\"dump\":{\"bytes\":${dump.bytes},\"name\":\"${dump.name}\",\"sha256\":\"${dump.sha256}\"}," +
            "\"media\":{\"bytes\":${media.bytes},\"name\":\"${media.name}\",\"sha256\":\"${media.sha256}\"}," +
            "\"schema\":\"kira.backup-bundle.v1\"}\n"
        val bundle = CatalogLogicalBundleV1(
            "kira.backup-bundle.v1",
            CatalogBackupArtifactV1("backup-1.bundle.json", manifest.toByteArray().size.toLong(), Sha256.hexUtf8(manifest)),
            dump,
            media,
        )
        return CatalogLogicalSourceV1(
            SOURCE_ID,
            "KIRA_BACKUP_BUNDLE_V1",
            genesis.manifest.initialWriterRegistry.databaseIdentity,
            genesis.manifest.initialWriterRegistry.restoreIdentity,
            1720000100,
            "ACCEPTED",
            bundleHash(bundle),
            bundle,
        )
    }

    fun bundleHash(bundle: CatalogLogicalBundleV1): String = Sha256.hexUtf8(CanonicalJson.canonicalize(CatalogLogicalBundleV1.serializer(), bundle))

    fun copy(source: CatalogLogicalSourceV1, index: Int): CatalogLogicalCopyV1 {
        val replica = index == 2
        fun location(artifact: CatalogBackupArtifactV1, role: String): CatalogS3ObjectVersionV1 = CatalogS3ObjectVersionV1(
            if (replica) "222222222222" else "111111111111",
            if (replica) "eu-west-1" else "us-east-1",
            if (replica) "backup-replica" else "backup-primary",
            "logical/copy-$index/${artifact.name}",
            "$role-version-1",
            artifact.bytes,
            artifact.sha256,
        )
        return CatalogLogicalCopyV1(
            copyId(index),
            source.sourceId,
            if (replica) "REPLICA" else "PRIMARY",
            "ACCEPTED",
            source.bundleSha256,
            location(source.bundle.manifest, "manifest"),
            location(source.bundle.dump, "dump"),
            location(source.bundle.media, "media"),
        )
    }

    fun chain(emptyRotationPrefix: Boolean = false): InventoryChainFixture {
        val base = rotations.chain()
        val prefix = if (emptyRotationPrefix) base.bytes() else base.bytes().take(1)
        val source = source(base.genesis)
        val firstCopy = copy(source, 1)
        val signer = if (emptyRotationPrefix) "catalog-new" else "catalog-old"
        val register = signed(
            manifest(
                base.genesis,
                prefix.size.toLong() + 1,
                prefix.last(),
                "REGISTER_SOURCE",
                CatalogRestoreInventoryV1(listOf(source), listOf(firstCopy)),
                CatalogInventoryDeltaV1(listOf(source.sourceId), listOf(firstCopy.copyId)),
                signer,
            ),
        )
        val second = copy(source, 2)
        val add = signed(
            manifest(
                base.genesis,
                register.manifest.generation + 1,
                bytes(register),
                "ADD_COPY",
                register.manifest.restoreInventory.copy(copies = listOf(firstCopy, second)),
                CatalogInventoryDeltaV1(emptyList(), listOf(second.copyId)),
                signer,
            ),
        )
        return InventoryChainFixture(base, prefix, listOf(register, add))
    }

    fun manifest(
        genesis: OfflineCatalogGenesisEnvelopeV1,
        generation: Long,
        previous: ByteArray,
        operation: String,
        inventory: CatalogRestoreInventoryV1,
        delta: CatalogInventoryDeltaV1,
        signer: String = "catalog-old",
    ): OfflineCatalogInventoryManifestV2 {
        val initial = genesis.manifest
        val created = 1720000000 + generation * 100
        return OfflineCatalogInventoryManifestV2(
            schemaVersion = 2,
            profile = "NEW_BACKEND_LOGICAL_BUNDLE_V1",
            canonicalizerId = "kcj-1",
            operation = operation,
            operationToken = "${generation.toString(16).padStart(8, '0')}-5555-4555-8555-555555555555",
            generation = generation,
            previousEnvelopeSha256 = Sha256.hex(previous),
            initialTrustBundleEnvelopeSha256 = initial.initialTrustBundleEnvelopeSha256,
            catalogWriterGenerationId = initial.catalogWriterGenerationId,
            requiredSignerPolicy = CatalogSignerPolicyV1("SINGLE", "ALL_MEMBERS", listOf(rotations.member(signer))),
            creation = initial.creation.copy(createdAtEpochSecond = created),
            approvals = initial.approvals.mapIndexed { index, approval -> approval.copy(approvedAtEpochSecond = created + (index + 1) * 10) },
            oldestRestoreTimeEpochSecond = initial.oldestRestoreTimeEpochSecond,
            initialWriterRegistry = initial.initialWriterRegistry,
            restoreInventory = inventory,
            inventoryDelta = delta,
            history = initial.history,
        )
    }

    fun manifestBytes(manifest: OfflineCatalogInventoryManifestV2): ByteArray =
        CanonicalJson.canonicalize(OfflineCatalogInventoryManifestV2.serializer(), manifest).toByteArray(Charsets.UTF_8)

    fun bytes(envelope: OfflineCatalogInventoryEnvelopeV2): ByteArray =
        CanonicalJson.canonicalize(OfflineCatalogInventoryEnvelopeV2.serializer(), envelope).toByteArray(Charsets.UTF_8)

    fun signed(manifest: OfflineCatalogInventoryManifestV2): OfflineCatalogInventoryEnvelopeV2 {
        val bytes = manifestBytes(manifest)
        val signatures = manifest.requiredSignerPolicy.members.map { member ->
            val signer = Signature.getInstance("RSASSA-PSS")
            signer.setParameter(bundles.parameters)
            val key = when (member.keyId) {
                "catalog-old" -> bundles.firstSigner
                "catalog-new" -> bundles.secondSigner
                else -> error("Unknown synthetic signer")
            }
            signer.initSign(key.private)
            signer.update(OfflineCatalogGenesisFixture.independentFrame(member.keyId, bytes))
            OfflineCatalogGenesisSignatureV1(member.keyId, member.algorithmId, Base64.getEncoder().encodeToString(signer.sign()))
        }
        return OfflineCatalogInventoryEnvelopeV2(2, manifest, signatures)
    }
}

internal data class InventoryChainFixture(
    val base: RotationChainFixture,
    val prefix: List<ByteArray>,
    val generations: List<OfflineCatalogInventoryEnvelopeV2>,
) {
    fun bytes(): List<ByteArray> = prefix + generations.map(OfflineCatalogInventoryFixture::bytes)
}
