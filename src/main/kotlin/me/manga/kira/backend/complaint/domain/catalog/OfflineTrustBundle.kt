package me.manga.kira.backend.complaint.domain.catalog

import kotlinx.serialization.Serializable

/** Closed wire types: no defaults, optional fields, arbitrary JSON, or authority-bearing tokens. */
@Serializable
internal data class OfflineTrustBundleEnvelopeV1(val schemaVersion: Int, val body: OfflineTrustBundleBodyV1, val signature: OfflineTrustBundleSignatureV1)

@Serializable
internal data class OfflineTrustBundleBodyV1(
    val schemaVersion: Int,
    val version: Long,
    val environment: String,
    val catalogLocations: List<OfflineCatalogLocationV1>,
    val canonicalizerId: String,
    val minimumCatalogHeadGeneration: Long,
    val issuedAtEpochSecond: Long,
    val approvals: List<OfflineTrustBundleApprovalV1>,
    val signers: List<OfflineCatalogSignerV1>,
    val bootstrapAuthority: OfflineBootstrapAuthorityV1,
)

@Serializable
internal data class OfflineCatalogLocationV1(val role: String, val bucket: String, val accountId: String, val region: String)

@Serializable
internal data class OfflineTrustBundleApprovalV1(val approverId: String, val approvedAtEpochSecond: Long)

@Serializable
internal data class OfflineCatalogSignerV1(val keyId: String, val algorithmId: String, val publicKeySpkiBase64: String, val publicKeySha256: String)

@Serializable
internal data class OfflineTrustBundleSignatureV1(val keyId: String, val algorithmId: String, val signatureBase64: String)

/** Immutable INITIAL authority across related releases, never a grant to the current writer or approvers. */
@Serializable
internal data class OfflineBootstrapAuthorityV1(
    val catalogWriterGenerationId: String,
    val requiredSigner: OfflineRequiredSignerV1,
    val initialWriterRegistrySha256: String,
    val catalogApproverIds: List<String>,
)

@Serializable
internal data class OfflineRequiredSignerV1(val keyId: String, val algorithmId: String)

/** Required independent deployment/recovery input. This neither proves its provenance nor loads it from a database. */
internal class OfflineTrustBundlePolicy(
    rootPublicKeySpki: ByteArray,
    val rootPublicKeySha256: String,
    val rootKeyId: String,
    val rootAlgorithmId: String,
    val expectedEnvironment: String,
    expectedCatalogLocations: List<OfflineCatalogLocationV1>,
    val minimumBundleVersion: Long,
) {
    init {
        requireOfflineTrustBundle(rootPublicKeySpki.size == OfflineTrustBundleProtocol.PUBLIC_KEY_BYTES, OfflineTrustBundleFailure.INVALID_POLICY)
        requireOfflineTrustBundle(expectedCatalogLocations.size == 2, OfflineTrustBundleFailure.INVALID_POLICY)
    }

    private val storedRoot = rootPublicKeySpki.copyOf()
    private val storedLocations = expectedCatalogLocations.toList()

    val rootPublicKeySpki: ByteArray get() = storedRoot.copyOf()
    val expectedCatalogLocations: List<OfflineCatalogLocationV1> get() = storedLocations.toList()

    override fun toString(): String = "OfflineTrustBundlePolicy(out-of-band)"
}

/**
 * Signature-checked claims and bytes only. Not an accepted catalog head, authenticated membership,
 * two-person approval proof, or a capability. In particular, the catalog-head floor is not applied to historical objects here.
 */
internal class SignatureCheckedOfflineTrustBundle(
    body: OfflineTrustBundleBodyV1,
    canonicalBodyBytes: ByteArray,
    canonicalEnvelopeBytes: ByteArray,
    val bodySha256: String,
    val envelopeSha256: String,
) {
    private val storedBody = body.snapshot()
    private val storedBodyBytes = canonicalBodyBytes.copyOf()
    private val storedEnvelopeBytes = canonicalEnvelopeBytes.copyOf()

    val body: OfflineTrustBundleBodyV1 get() = storedBody.snapshot()
    val canonicalBodyBytes: ByteArray get() = storedBodyBytes.copyOf()
    val canonicalEnvelopeBytes: ByteArray get() = storedEnvelopeBytes.copyOf()

    override fun toString(): String = "SignatureCheckedOfflineTrustBundle(no-catalog-authority)"
}

private fun OfflineTrustBundleBodyV1.snapshot(): OfflineTrustBundleBodyV1 = copy(
    catalogLocations = catalogLocations.toList(),
    approvals = approvals.toList(),
    signers = signers.toList(),
    bootstrapAuthority = bootstrapAuthority.copy(catalogApproverIds = bootstrapAuthority.catalogApproverIds.toList()),
)

internal object OfflineTrustBundleProtocol {
    const val SCHEMA_VERSION = 1
    const val MAX_ENVELOPE_BYTES = 128 * 1024
    const val MAX_SIGNERS = 16
    const val PUBLIC_KEY_BYTES = 422
    const val SIGNATURE_BYTES = 384
    const val ALGORITHM_ID = "RSASSA_PSS_SHA_256"
    const val DOMAIN = "kira.complaints.offline-trust-bundle.v1"
}

internal enum class OfflineTrustBundleFailure {
    LIMIT_EXCEEDED,
    MALFORMED_INPUT,
    NON_CANONICAL,
    UNSUPPORTED_SCHEMA,
    INVALID_DOCUMENT,
    INVALID_POLICY,
    POLICY_MISMATCH,
    VERSION_ROLLBACK,
    UNSUPPORTED_ALGORITHM,
    INVALID_PUBLIC_KEY,
    FINGERPRINT_MISMATCH,
    INVALID_SIGNATURE,
    REGISTRY_HASH_MISMATCH,
    BUNDLE_HASH_MISMATCH,
    SUPPLIER_FAILURE,
}

/** Deliberately no submitted values, parser/provider messages, or underlying exception causes. */
internal class OfflineTrustBundleException(val code: OfflineTrustBundleFailure) : RuntimeException("Offline trust bundle rejected: ${code.name}.")

internal fun requireOfflineTrustBundle(condition: Boolean, code: OfflineTrustBundleFailure = OfflineTrustBundleFailure.INVALID_DOCUMENT) {
    if (!condition) throw OfflineTrustBundleException(code)
}
