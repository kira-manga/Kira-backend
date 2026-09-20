package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.GenesisEmptyHeadV1
import me.manga.kira.backend.complaint.domain.catalog.GenesisEmptyHistoryV1
import me.manga.kira.backend.complaint.domain.catalog.InitialSignerPolicyV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapRegistryV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisCreationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisManifestV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisSignatureV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleBodyV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleEnvelopeV1
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

/** Reuses the existing synthetic in-memory keys; the original head42 and public golden fixtures stay unchanged. */
internal object OfflineCatalogGenesisFixture {
    private val bundleFixture = OfflineTrustBundleFixture

    fun bundleBody(registry: OfflineBootstrapRegistryV1 = bundleFixture.registry()): OfflineTrustBundleBodyV1 {
        val original = bundleFixture.body()
        return original.copy(
            minimumCatalogHeadGeneration = 1,
            bootstrapAuthority = original.bootstrapAuthority.copy(initialWriterRegistrySha256 = Sha256.hex(bundleFixture.registryBytes(registry))),
        )
    }

    fun bundle(): OfflineTrustBundleEnvelopeV1 = bundleFixture.signed(bundleBody())

    fun manifest(bundle: OfflineTrustBundleEnvelopeV1, registry: OfflineBootstrapRegistryV1 = bundleFixture.registry()): OfflineCatalogGenesisManifestV1 {
        val empty = GenesisEmptyHeadV1(0, Sha256.hexUtf8("[]"))
        return OfflineCatalogGenesisManifestV1(
            schemaVersion = 1,
            canonicalizerId = "kcj-1",
            operation = "GENESIS",
            operationToken = "55555555-5555-4555-8555-555555555555",
            generation = 1,
            previousEnvelopeSha256 = "0".repeat(64),
            initialTrustBundleEnvelopeSha256 = Sha256.hex(bundleFixture.bytes(bundle)),
            catalogWriterGenerationId = bundle.body.bootstrapAuthority.catalogWriterGenerationId,
            requiredSignerPolicy = InitialSignerPolicyV1("SINGLE", "ALL_MEMBERS", listOf(bundle.body.bootstrapAuthority.requiredSigner)),
            creation = OfflineCatalogGenesisCreationV1("catalog-approver-a", 1720000060),
            approvals = listOf(
                OfflineCatalogGenesisApprovalV1("catalog-approver-a", 1720000070),
                OfflineCatalogGenesisApprovalV1("catalog-approver-b", 1720000080),
            ),
            oldestRestoreTimeEpochSecond = 1720000060,
            initialWriterRegistry = registry,
            restoreInventory = empty,
            history = GenesisEmptyHistoryV1(empty, empty, empty, empty, empty, empty, empty),
        )
    }

    fun manifestBytes(manifest: OfflineCatalogGenesisManifestV1): ByteArray =
        CanonicalJson.canonicalize(OfflineCatalogGenesisManifestV1.serializer(), manifest).toByteArray(Charsets.UTF_8)

    fun bytes(envelope: OfflineCatalogGenesisEnvelopeV1): ByteArray =
        CanonicalJson.canonicalize(OfflineCatalogGenesisEnvelopeV1.serializer(), envelope).toByteArray(Charsets.UTF_8)

    fun signed(
        manifest: OfflineCatalogGenesisManifestV1,
        key: KeyPair = bundleFixture.firstSigner,
        keyId: String = "catalog-old",
        transformFrame: (ByteArray) -> ByteArray = { it },
    ): OfflineCatalogGenesisEnvelopeV1 {
        val signer = Signature.getInstance("RSASSA-PSS")
        signer.setParameter(bundleFixture.parameters)
        signer.initSign(key.private)
        signer.update(transformFrame(independentFrame(keyId, manifestBytes(manifest))))
        val signature = OfflineCatalogGenesisSignatureV1(keyId, "RSASSA_PSS_SHA_256", Base64.getEncoder().encodeToString(signer.sign()))
        return OfflineCatalogGenesisEnvelopeV1(1, manifest, listOf(signature))
    }

    /** Independent DataOutputStream framing, never a call to the production catalog or bundle frame helper. */
    fun independentFrame(keyId: String, manifestBytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { framed ->
            listOf(
                "kira.complaints.catalog-generation.v1".toByteArray(Charsets.UTF_8),
                "kcj-1".toByteArray(Charsets.UTF_8),
                keyId.toByteArray(Charsets.UTF_8),
                "RSASSA_PSS_SHA_256".toByteArray(Charsets.UTF_8),
                MessageDigest.getInstance("SHA-256").digest(manifestBytes),
            ).forEach {
                framed.writeInt(it.size)
                framed.write(it)
            }
        }
        return output.toByteArray()
    }
}
