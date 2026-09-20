package me.manga.kira.backend.complaint.domain.terminal

import kotlinx.serialization.Serializable
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleSignatureV1

/** Independently provisioned purpose pin, not an authority nominated by an approval artifact. */
@Serializable
internal data class TestOrdinaryDenialAuthorityInputV1(
    val profile: String,
    val purpose: String,
    val keyId: String,
    val algorithmId: String,
    val publicKeySpkiBase64: String,
    val publicKeySha256: String,
    val environment: String,
    val dataScopeId: String,
    val writerGeneration: String,
    val databaseIdentity: String,
    val restoreIdentity: String,
    val bucket: String,
    val accountId: String,
    val region: String,
    val minimumApprovalVersion: Long,
    val authorityGrant: InitialPolicyReferenceV1,
    val implementationAcceptance: InitialPolicyReferenceV1,
    val evidenceRetentionPolicy: InitialPolicyReferenceV1,
    val runEnvelopeAccounting: String,
)

/** One purpose only, no general signature registry or new journal event. Every field is required. */
@Serializable
internal data class TestOrdinaryDenialEnvelopeV1(
    val schemaVersion: Int,
    val body: TestOrdinaryDenialStatementV1,
    val signature: OfflineTrustBundleSignatureV1,
)

@Serializable
internal data class TestOrdinaryDenialStatementV1(
    val schemaVersion: Int,
    val purpose: String,
    val approvalVersion: Long,
    val authorityGrant: InitialPolicyReferenceV1,
    val implementationAcceptance: InitialPolicyReferenceV1,
    val evidenceRetentionPolicy: InitialPolicyReferenceV1,
    val environment: String,
    val dataScopeId: String,
    val activationCatalogGeneration: Long,
    val activationCatalogSha256: String,
    val configurationSha256: String,
    val terminalEncodingSha256: String,
    val initialWriterRegistrySha256: String,
    val writerGeneration: String,
    val databaseIdentity: String,
    val restoreIdentity: String,
    val epochStartInclusive: Long,
    val epochEndInclusive: Long,
    val bucket: String,
    val accountId: String,
    val region: String,
    val ordinaryPrefix: String,
    val roleId: String,
    val policy: TestTerminalPolicyRefV1,
    val denialEffectiveAtEpochSecond: Long,
    val lastSessionExpiryEpochSecond: Long,
    val acceptedRequestBoundSeconds: Long,
    val utcUncertaintySeconds: Long,
    val evidenceRetainUntilEpochSecond: Long,
    val boundEvidence: TestTerminalEvidenceDigestV1,
    val effectivePaths: List<TestOrdinaryDeniedPathV1>,
)

/** A complete signed path inventory, not a collection of caller-asserted `denied=true` flags. */
@Serializable
internal data class TestOrdinaryDeniedPathV1(
    val pathId: String,
    val roleId: String,
    val denialEffectiveAtEpochSecond: Long,
    val lastSessionExpiryEpochSecond: Long,
    val rawEvidence: TestTerminalEvidenceDigestV1,
)
