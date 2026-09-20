package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CheckedOfflineCatalogGenesis
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisManifestV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineRequiredSignerV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleBodyV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundlePolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.requireOfflineTrustBundle
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Current-T0 raw verification and shared genesis validation; historical orchestration stays in OfflineTrustBundleVerifier. */
internal object OfflineCatalogGenesisVerifier {
    private const val LAST_EPOCH_SECOND = 253402300799L
    private val emptyHeadSha256 = Sha256.hexUtf8("[]")

    fun verify(genesisEnvelopeBytes: ByteArray, currentInitialBundleBytes: ByteArray, policy: OfflineTrustBundlePolicy): CheckedOfflineCatalogGenesis {
        requireOfflineTrustBundle(genesisEnvelopeBytes.size <= OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        val input = genesisEnvelopeBytes.copyOf()
        val bundle = OfflineTrustBundleVerifier.verify(currentInitialBundleBytes, policy)
        val body = bundle.body
        requireOfflineTrustBundle(body.minimumCatalogHeadGeneration == 1L, OfflineTrustBundleFailure.POLICY_MISMATCH)
        val envelope = OfflineTrustBundleParser.parseGenesis(input)
        validateClaims(envelope, body, bundle.envelopeSha256)
        val manifest = envelope.manifest
        val manifestBytes = CanonicalJson.canonicalize(OfflineCatalogGenesisManifestV1.serializer(), manifest).toByteArray(Charsets.UTF_8)
        return CheckedOfflineCatalogGenesis(manifest, manifestBytes, input, Sha256.hex(manifestBytes), Sha256.hex(input))
    }

    /** Shared semantic/signature validation only; raw entry points authenticate their own bundles and return evidence. */
    fun validateClaims(envelope: OfflineCatalogGenesisEnvelopeV1, body: OfflineTrustBundleBodyV1, initialBundleEnvelopeSha256: String) {
        val manifest = envelope.manifest
        requireOfflineTrustBundle(envelope.schemaVersion == 1, OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
        validateManifestClaims(manifest, body, initialBundleEnvelopeSha256)
        val members = manifest.requiredSignerPolicy.members
        requireOfflineTrustBundle(envelope.signatures.map { OfflineRequiredSignerV1(it.keyId, it.algorithmId) } == members)
        val record = envelope.signatures.single()
        val signer = body.signers.single { it.keyId == record.keyId && it.algorithmId == record.algorithmId }
        val publicBytes = OfflineTrustBundleCrypto.decodeBase64(
            signer.publicKeySpkiBase64,
            OfflineTrustBundleProtocol.PUBLIC_KEY_BYTES,
            OfflineTrustBundleFailure.INVALID_PUBLIC_KEY,
        )
        val signatureBytes = OfflineTrustBundleCrypto.decodeBase64(
            record.signatureBase64,
            OfflineTrustBundleProtocol.SIGNATURE_BYTES,
            OfflineTrustBundleFailure.INVALID_SIGNATURE,
        )
        val manifestBytes = CanonicalJson.canonicalize(OfflineCatalogGenesisManifestV1.serializer(), manifest).toByteArray(Charsets.UTF_8)
        val frame = OfflineCatalogGenesisCrypto.signatureFrame(record.keyId, manifestBytes)
        OfflineTrustBundleCrypto.verify(OfflineTrustBundleCrypto.publicKey(publicBytes), frame, signatureBytes)
    }

    /** Check-only shared semantics. Neither a caller-constructed body nor an unsigned manifest becomes trusted evidence. */
    fun validateManifestClaims(manifest: OfflineCatalogGenesisManifestV1, body: OfflineTrustBundleBodyV1, initialBundleEnvelopeSha256: String) {
        requireOfflineTrustBundle(
            manifest.schemaVersion == 1 && manifest.canonicalizerId == CanonicalJson.CANON_VERSION,
            OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA,
        )
        requireOfflineTrustBundle(
            manifest.initialTrustBundleEnvelopeSha256 == initialBundleEnvelopeSha256,
            OfflineTrustBundleFailure.BUNDLE_HASH_MISMATCH,
        )
        validateManifest(manifest, body)
    }

    private fun validateManifest(manifest: OfflineCatalogGenesisManifestV1, body: OfflineTrustBundleBodyV1) {
        requireOfflineTrustBundle(manifest.operation == "GENESIS" && OfflineBootstrapGrammar.uuidV4(manifest.operationToken))
        requireOfflineTrustBundle(manifest.generation == 1L && manifest.previousEnvelopeSha256 == OfflineCatalogGenesisProtocol.ZERO_PREDECESSOR_SHA256)
        requireOfflineTrustBundle(manifest.catalogWriterGenerationId == body.bootstrapAuthority.catalogWriterGenerationId)
        OfflineBootstrapRegistryVerifier.validateClaims(manifest.initialWriterRegistry, body.bootstrapAuthority)
        requireOfflineTrustBundle(manifest.requiredSignerPolicy == manifest.initialWriterRegistry.catalogWriter.requiredSignerPolicy)
        validateApprovals(manifest, body)
        val history = manifest.history
        val heads = listOf(
            manifest.restoreInventory,
            history.expiredRestoreSources,
            history.testRunActivations,
            history.testRunTerminals,
            history.installationManifests,
            history.epochSeals,
            history.retirementAuthorizations,
            history.retirementCompletions,
        )
        requireOfflineTrustBundle(heads.all { it.count == 0L && it.sha256 == emptyHeadSha256 })
    }

    private fun validateApprovals(manifest: OfflineCatalogGenesisManifestV1, body: OfflineTrustBundleBodyV1) {
        val created = manifest.creation.createdAtEpochSecond
        requireOfflineTrustBundle(created in body.issuedAtEpochSecond..LAST_EPOCH_SECOND)
        requireOfflineTrustBundle(manifest.oldestRestoreTimeEpochSecond == created)
        val ids = manifest.approvals.map { it.approverId }
        requireOfflineTrustBundle(ids.size == 2 && ids.distinct().size == 2 && ids == ids.sorted())
        requireOfflineTrustBundle(ids.all { it in body.bootstrapAuthority.catalogApproverIds })
        requireOfflineTrustBundle(manifest.creation.creatorId in ids)
        requireOfflineTrustBundle(manifest.approvals.all { it.approvedAtEpochSecond in created..LAST_EPOCH_SECOND })
    }
}

/** Fixed catalog domain and algorithm; intentionally cannot select arbitrary provider algorithms or the bundle domain. */
internal object OfflineCatalogGenesisCrypto {
    fun signatureFrame(keyId: String, canonicalManifestBytes: ByteArray): ByteArray {
        val parts = listOf(
            OfflineCatalogGenesisProtocol.DOMAIN.toByteArray(Charsets.UTF_8),
            CanonicalJson.CANON_VERSION.toByteArray(Charsets.UTF_8),
            keyId.toByteArray(Charsets.UTF_8),
            OfflineTrustBundleProtocol.ALGORITHM_ID.toByteArray(Charsets.UTF_8),
            MessageDigest.getInstance("SHA-256").digest(canonicalManifestBytes),
        )
        val frame = ByteBuffer.allocate(parts.sumOf { Integer.BYTES + it.size })
        parts.forEach { frame.putInt(it.size).put(it) }
        return frame.array()
    }
}
