package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CheckedHistoricalGenesisEvidence
import me.manga.kira.backend.complaint.domain.catalog.CheckedOfflineCatalogGenesis
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisManifestV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogLocationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleBodyV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundlePolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.SignatureCheckedOfflineTrustBundle
import me.manga.kira.backend.complaint.domain.catalog.requireOfflineTrustBundle
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import java.security.interfaces.RSAPublicKey

/** Local cryptographic checking only. Intentionally not a Spring bean or any complaint-admission boundary. */
internal object OfflineTrustBundleVerifier {
    private val keyId = Regex("[A-Za-z0-9._-]{1,64}")
    private val environment = Regex("[a-z][a-z0-9-]{0,63}")
    private val fingerprint = Regex("[0-9a-f]{64}")
    private const val LAST_EPOCH_SECOND = 253402300799L

    fun verify(bytes: ByteArray, policy: OfflineTrustBundlePolicy): SignatureCheckedOfflineTrustBundle {
        val input = boundedSnapshot(bytes)
        val root = checkedRoot(policy)
        val envelope = parseBundle(input)
        requireOfflineTrustBundle(envelope.body.version >= policy.minimumBundleVersion, OfflineTrustBundleFailure.VERSION_ROLLBACK)
        return authenticate(envelope, input, root, policy)
    }

    /** Raw-input historical evidence only; a current independently floored release is always required. */
    fun verifyBootstrapEvidence(
        genesisEnvelopeBytes: ByteArray,
        initialBundleBytes: ByteArray,
        currentBundleBytes: ByteArray,
        policy: OfflineTrustBundlePolicy,
    ): CheckedHistoricalGenesisEvidence {
        val genesisInput = boundedSnapshot(genesisEnvelopeBytes)
        val current = verify(currentBundleBytes, policy)
        val initialInput = boundedSnapshot(initialBundleBytes)
        val root = checkedRoot(policy)
        val initial = authenticate(parseBundle(initialInput), initialInput, root, policy)
        validateBootstrapContinuity(initial, current)
        val envelope = OfflineTrustBundleParser.parseGenesis(genesisInput)
        OfflineCatalogGenesisVerifier.validateClaims(envelope, initial.body, initial.envelopeSha256)
        val manifest = envelope.manifest
        val manifestBytes = CanonicalJson.canonicalize(OfflineCatalogGenesisManifestV1.serializer(), manifest).toByteArray(Charsets.UTF_8)
        val genesis = CheckedOfflineCatalogGenesis(manifest, manifestBytes, genesisInput, Sha256.hex(manifestBytes), Sha256.hex(genesisInput))
        return CheckedHistoricalGenesisEvidence(genesis, initial, current)
    }

    /** Raw unsigned-G1 check only, before an exact signed envelope can exist. No historical trust handle escapes. */
    fun validateGenesisPreparation(
        genesisManifestBytes: ByteArray,
        initialBundleBytes: ByteArray,
        currentBundleBytes: ByteArray,
        policy: OfflineTrustBundlePolicy,
    ) {
        val manifestInput = boundedSnapshot(genesisManifestBytes)
        val initialInput = boundedSnapshot(initialBundleBytes)
        val current = verify(currentBundleBytes, policy)
        val root = checkedRoot(policy)
        val initial = authenticate(parseBundle(initialInput), initialInput, root, policy)
        validateBootstrapContinuity(initial, current)
        requireOfflineTrustBundle(current.body.minimumCatalogHeadGeneration == 1L, OfflineTrustBundleFailure.POLICY_MISMATCH)
        OfflineCatalogGenesisVerifier.validateManifestClaims(
            OfflineTrustBundleParser.parseGenesisManifest(manifestInput),
            initial.body,
            initial.envelopeSha256,
        )
    }

    private fun boundedSnapshot(bytes: ByteArray): ByteArray {
        requireOfflineTrustBundle(bytes.size <= OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        return bytes.copyOf()
    }

    private fun checkedRoot(policy: OfflineTrustBundlePolicy): RSAPublicKey {
        validatePolicy(policy)
        val rootBytes = policy.rootPublicKeySpki
        val root = OfflineTrustBundleCrypto.publicKey(rootBytes)
        requireOfflineTrustBundle(Sha256.hex(rootBytes) == policy.rootPublicKeySha256, OfflineTrustBundleFailure.INVALID_POLICY)
        return root
    }

    private fun parseBundle(input: ByteArray): OfflineTrustBundleEnvelopeV1 {
        val envelope = OfflineTrustBundleParser.parse(input)
        val body = envelope.body
        requireOfflineTrustBundle(
            envelope.schemaVersion == OfflineTrustBundleProtocol.SCHEMA_VERSION && body.schemaVersion == OfflineTrustBundleProtocol.SCHEMA_VERSION,
            OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA,
        )
        validateBody(body)
        return envelope
    }

    // Never exposed as a historical API: only verify() or the closed raw current-plus-initial entry points above may call this.
    private fun authenticate(
        envelope: OfflineTrustBundleEnvelopeV1,
        input: ByteArray,
        root: RSAPublicKey,
        policy: OfflineTrustBundlePolicy,
    ): SignatureCheckedOfflineTrustBundle {
        val body = envelope.body
        requireOfflineTrustBundle(
            body.environment == policy.expectedEnvironment && body.catalogLocations == policy.expectedCatalogLocations,
            OfflineTrustBundleFailure.POLICY_MISMATCH,
        )
        requireAlgorithm(envelope.signature.algorithmId)
        requireOfflineTrustBundle(envelope.signature.keyId == policy.rootKeyId, OfflineTrustBundleFailure.POLICY_MISMATCH)
        val signatureBytes = OfflineTrustBundleCrypto.decodeBase64(
            envelope.signature.signatureBase64,
            OfflineTrustBundleProtocol.SIGNATURE_BYTES,
            OfflineTrustBundleFailure.INVALID_SIGNATURE,
        )
        val bodyBytes = CanonicalJson.canonicalize(OfflineTrustBundleBodyV1.serializer(), body).toByteArray(Charsets.UTF_8)
        OfflineTrustBundleCrypto.verify(root, OfflineTrustBundleCrypto.signatureFrame(policy.rootKeyId, bodyBytes), signatureBytes)

        // Public keys become usable material only after the independent root signature has been checked.
        body.signers.forEach { signer ->
            requireAlgorithm(signer.algorithmId)
            val publicBytes = OfflineTrustBundleCrypto.decodeBase64(
                signer.publicKeySpkiBase64,
                OfflineTrustBundleProtocol.PUBLIC_KEY_BYTES,
                OfflineTrustBundleFailure.INVALID_PUBLIC_KEY,
            )
            OfflineTrustBundleCrypto.publicKey(publicBytes)
            requireOfflineTrustBundle(Sha256.hex(publicBytes) == signer.publicKeySha256, OfflineTrustBundleFailure.FINGERPRINT_MISMATCH)
            requireOfflineTrustBundle(signer.publicKeySha256 != policy.rootPublicKeySha256, OfflineTrustBundleFailure.INVALID_PUBLIC_KEY)
        }
        val required = body.bootstrapAuthority.requiredSigner
        requireAlgorithm(required.algorithmId)
        requireOfflineTrustBundle(body.signers.any { it.keyId == required.keyId && it.algorithmId == required.algorithmId })
        return SignatureCheckedOfflineTrustBundle(body, bodyBytes, input, Sha256.hex(bodyBytes), Sha256.hex(input))
    }

    private fun validateBootstrapContinuity(initial: SignatureCheckedOfflineTrustBundle, current: SignatureCheckedOfflineTrustBundle) {
        val initialBody = initial.body
        val currentBody = current.body
        requireOfflineTrustBundle(initialBody.minimumCatalogHeadGeneration == 1L, OfflineTrustBundleFailure.POLICY_MISMATCH)
        requireOfflineTrustBundle(initialBody.version <= currentBody.version, OfflineTrustBundleFailure.POLICY_MISMATCH)
        requireOfflineTrustBundle(initialBody.issuedAtEpochSecond <= currentBody.issuedAtEpochSecond, OfflineTrustBundleFailure.POLICY_MISMATCH)
        requireOfflineTrustBundle(
            initialBody.version != currentBody.version || initial.canonicalEnvelopeBytes.contentEquals(current.canonicalEnvelopeBytes),
            OfflineTrustBundleFailure.POLICY_MISMATCH,
        )
        requireOfflineTrustBundle(initialBody.bootstrapAuthority == currentBody.bootstrapAuthority, OfflineTrustBundleFailure.POLICY_MISMATCH)
        val required = initialBody.bootstrapAuthority.requiredSigner
        val signer = initialBody.signers.single { it.keyId == required.keyId && it.algorithmId == required.algorithmId }
        // Both canonical SPKIs/fingerprints were root-authenticated; matching only the key ID would permit retargeting.
        requireOfflineTrustBundle(signer in currentBody.signers, OfflineTrustBundleFailure.POLICY_MISMATCH)
    }

    private fun validatePolicy(policy: OfflineTrustBundlePolicy) {
        requireOfflineTrustBundle(
            keyId.matches(policy.rootKeyId) && fingerprint.matches(policy.rootPublicKeySha256) &&
                environment.matches(policy.expectedEnvironment) && policy.minimumBundleVersion > 0,
            OfflineTrustBundleFailure.INVALID_POLICY,
        )
        requireAlgorithm(policy.rootAlgorithmId)
        validateLocations(policy.expectedCatalogLocations, OfflineTrustBundleFailure.INVALID_POLICY)
    }

    private fun validateBody(body: OfflineTrustBundleBodyV1) {
        requireOfflineTrustBundle(body.canonicalizerId == CanonicalJson.CANON_VERSION, OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
        requireOfflineTrustBundle(body.version > 0 && body.minimumCatalogHeadGeneration > 0)
        requireOfflineTrustBundle(environment.matches(body.environment))
        validateLocations(body.catalogLocations, OfflineTrustBundleFailure.INVALID_DOCUMENT)
        requireOfflineTrustBundle(body.issuedAtEpochSecond in 0..LAST_EPOCH_SECOND)
        requireOfflineTrustBundle(body.approvals.size == 2 && body.approvals.map { it.approverId }.distinct().size == 2)
        body.approvals.forEach {
            requireOfflineTrustBundle(it.approverId.length in 1..256 && it.approverId.all { character -> character in '!'..'~' })
            requireOfflineTrustBundle(it.approvedAtEpochSecond in 0..body.issuedAtEpochSecond)
        }
        requireOfflineTrustBundle(body.signers.size in 1..OfflineTrustBundleProtocol.MAX_SIGNERS)
        requireOfflineTrustBundle(body.signers.map { it.keyId }.distinct().size == body.signers.size)
        requireOfflineTrustBundle(body.signers.map { it.publicKeySha256 }.distinct().size == body.signers.size)
        body.signers.forEach { requireOfflineTrustBundle(keyId.matches(it.keyId) && fingerprint.matches(it.publicKeySha256)) }
        val authority = body.bootstrapAuthority
        requireOfflineTrustBundle(OfflineBootstrapGrammar.uuidV4(authority.catalogWriterGenerationId))
        requireOfflineTrustBundle(keyId.matches(authority.requiredSigner.keyId) && fingerprint.matches(authority.initialWriterRegistrySha256))
        requireOfflineTrustBundle(authority.catalogApproverIds.size in 2..16)
        requireOfflineTrustBundle(authority.catalogApproverIds.distinct().size == authority.catalogApproverIds.size)
        requireOfflineTrustBundle(authority.catalogApproverIds.all(OfflineBootstrapGrammar::approverId))
    }

    private fun validateLocations(locations: List<OfflineCatalogLocationV1>, failure: OfflineTrustBundleFailure) {
        requireOfflineTrustBundle(locations.size == 2, failure)
        requireOfflineTrustBundle(locations[0].role == "PRIMARY" && locations[1].role == "REPLICA", failure)
        requireOfflineTrustBundle(
            locations.map { it.bucket }.distinct().size == 2 && locations.map { it.accountId }.distinct().size == 2 &&
                locations.map { it.region }.distinct().size == 2,
            failure,
        )
        locations.forEach {
            requireOfflineTrustBundle(
                OfflineBootstrapGrammar.bucket(it.bucket) && OfflineBootstrapGrammar.account(it.accountId) && OfflineBootstrapGrammar.region(it.region),
                failure,
            )
        }
    }

    private fun requireAlgorithm(value: String) =
        requireOfflineTrustBundle(value == OfflineTrustBundleProtocol.ALGORITHM_ID, OfflineTrustBundleFailure.UNSUPPORTED_ALGORITHM)
}
