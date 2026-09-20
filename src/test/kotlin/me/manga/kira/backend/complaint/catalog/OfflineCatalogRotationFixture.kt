package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerPolicyV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapRegistryV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainLimits
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisSignatureV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationManifestV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineRequiredSignerV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleEnvelopeV1
import java.security.KeyPair
import java.security.Signature
import java.util.Base64

/** Small synthetic chains only; existing in-memory keys and independent catalog framing are reused unchanged. */
internal object OfflineCatalogRotationFixture {
    private val bundles = OfflineTrustBundleFixture
    private val genesisFixture = OfflineCatalogGenesisFixture
    private val thirdSigner: KeyPair by lazy { bundles.newKey() }

    fun member(id: String): OfflineRequiredSignerV1 = OfflineRequiredSignerV1(id, "RSASSA_PSS_SHA_256")

    fun limits(): OfflineCatalogChainLimits = OfflineCatalogChainLimits(8 * 1024 * 1024, 4096, 65536, 2L * 1024 * 1024 * 1024)

    fun policy(
        limits: OfflineCatalogChainLimits = limits(),
        writers: List<String> = listOf(bundles.CATALOG_WRITER),
        approvers: List<String> = listOf("catalog-approver-a", "catalog-approver-b"),
    ): OfflineCatalogChainReaderPolicy = OfflineCatalogChainReaderPolicy(bundles.policy(minimumVersion = 9), writers, approvers, limits)

    fun chain(twoRotations: Boolean = false, registry: OfflineBootstrapRegistryV1 = bundles.registry()): RotationChainFixture {
        val initial = bundles.signed(genesisFixture.bundleBody(registry))
        val genesis = genesisFixture.signed(genesisFixture.manifest(initial, registry))
        val overlap = signed(manifest(genesis, 2, genesisFixture.bytes(genesis), "ROTATION_OVERLAP", listOf("catalog-old", "catalog-new")))
        val activation = signed(manifest(genesis, 3, bytes(overlap), "SINGLE", listOf("catalog-new")))
        val rotations = mutableListOf(overlap, activation)
        val signers = initial.body.signers.toMutableList()
        if (twoRotations) {
            signers.add(bundles.signer(thirdSigner, "catalog-third"))
            val nextOverlap = signed(manifest(genesis, 4, bytes(activation), "ROTATION_OVERLAP", listOf("catalog-new", "catalog-third")))
            rotations.add(nextOverlap)
            rotations.add(signed(manifest(genesis, 5, bytes(nextOverlap), "SINGLE", listOf("catalog-third"))))
        }
        val current = bundles.signed(initial.body.copy(version = 9, issuedAtEpochSecond = initial.body.issuedAtEpochSecond + 3600, signers = signers))
        return RotationChainFixture(initial, current, genesis, rotations.toList())
    }

    fun manifest(
        genesis: OfflineCatalogGenesisEnvelopeV1,
        generation: Long,
        previousBytes: ByteArray,
        mode: String,
        keyIds: List<String>,
    ): OfflineCatalogRotationManifestV1 {
        val initial = genesis.manifest
        val created = 1720000000 + generation * 100
        return OfflineCatalogRotationManifestV1(
            schemaVersion = 1,
            canonicalizerId = "kcj-1",
            operation = if (mode == "ROTATION_OVERLAP") "ROTATION_OVERLAP" else "ROTATION_ACTIVATE",
            operationToken = "${generation.toString(16).padStart(8, '0')}-5555-4555-8555-555555555555",
            generation = generation,
            previousEnvelopeSha256 = Sha256.hex(previousBytes),
            initialTrustBundleEnvelopeSha256 = initial.initialTrustBundleEnvelopeSha256,
            catalogWriterGenerationId = initial.catalogWriterGenerationId,
            requiredSignerPolicy = CatalogSignerPolicyV1(mode, "ALL_MEMBERS", keyIds.map(::member)),
            creation = initial.creation.copy(createdAtEpochSecond = created),
            approvals = initial.approvals.mapIndexed { index, approval -> approval.copy(approvedAtEpochSecond = created + (index + 1) * 10) },
            oldestRestoreTimeEpochSecond = initial.oldestRestoreTimeEpochSecond,
            initialWriterRegistry = initial.initialWriterRegistry,
            restoreInventory = initial.restoreInventory,
            history = initial.history,
        )
    }

    fun manifestBytes(manifest: OfflineCatalogRotationManifestV1): ByteArray =
        CanonicalJson.canonicalize(OfflineCatalogRotationManifestV1.serializer(), manifest).toByteArray(Charsets.UTF_8)

    fun bytes(envelope: OfflineCatalogRotationEnvelopeV1): ByteArray =
        CanonicalJson.canonicalize(OfflineCatalogRotationEnvelopeV1.serializer(), envelope).toByteArray(Charsets.UTF_8)

    fun signed(manifest: OfflineCatalogRotationManifestV1): OfflineCatalogRotationEnvelopeV1 {
        val bytes = manifestBytes(manifest)
        val signatures = manifest.requiredSignerPolicy.members.map { member ->
            val signer = Signature.getInstance("RSASSA-PSS")
            signer.setParameter(bundles.parameters)
            signer.initSign(key(member.keyId).private)
            signer.update(genesisFixture.independentFrame(member.keyId, bytes))
            OfflineCatalogGenesisSignatureV1(member.keyId, member.algorithmId, Base64.getEncoder().encodeToString(signer.sign()))
        }
        return OfflineCatalogRotationEnvelopeV1(1, manifest, signatures)
    }

    /** An independent small-fixture tree count, not a call to the production streaming counter. */
    fun manifestRecords(bytes: ByteArray): Int = objectCount(Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("manifest"))

    private fun objectCount(value: JsonElement): Int = when (value) {
        is JsonObject -> 1 + value.values.sumOf(::objectCount)
        is JsonArray -> value.sumOf(::objectCount)
        else -> 0
    }

    private fun key(id: String): KeyPair = when (id) {
        "catalog-old" -> bundles.firstSigner
        "catalog-new" -> bundles.secondSigner
        "catalog-third" -> thirdSigner
        else -> error("Unknown synthetic key")
    }
}

internal data class RotationChainFixture(
    val initial: OfflineTrustBundleEnvelopeV1,
    val current: OfflineTrustBundleEnvelopeV1,
    val genesis: OfflineCatalogGenesisEnvelopeV1,
    val rotations: List<OfflineCatalogRotationEnvelopeV1>,
) {
    fun bytes(): List<ByteArray> = listOf(OfflineCatalogGenesisFixture.bytes(genesis)) + rotations.map(OfflineCatalogRotationFixture::bytes)
}
