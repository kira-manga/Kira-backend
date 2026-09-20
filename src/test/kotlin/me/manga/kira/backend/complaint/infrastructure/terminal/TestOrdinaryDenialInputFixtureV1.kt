package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.catalog.OfflineTrustBundleFixture
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDeniedPathV1
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDenialAuthorityInputV1
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDenialEnvelopeV1
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDenialStatementV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEvidenceDigestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPolicyRefV1
import me.manga.kira.backend.security.fullTestJournal
import java.util.Base64

/**
 * Synthetic syntax inputs only. Reuses existing in-memory crypto material, never an admitted
 * denial/original factory. The parser envelope deliberately carries an unrelated trust-bundle
 * signature: parsing a complete canonical DTO does not authenticate its purpose, context or proof.
 */
internal object TestOrdinaryDenialInputFixtureV1 {
    private val unrelatedSignature by lazy { OfflineTrustBundleFixture.signed().signature }

    fun input(
        journal: TestOwnerDeleteJournalConfigurationV1 = fullTestJournal(),
        spki: ByteArray = OfflineTrustBundleFixture.firstSigner.public.encoded,
    ): TestOrdinaryDenialAuthorityInputV1 {
        val declaration = journal.declaration()
        return TestOrdinaryDenialAuthorityInputV1(
            profile = TestOrdinaryDenialAuthorityPolicyV1.PROFILE,
            purpose = TestOrdinaryDenialAuthorityPolicyV1.PURPOSE,
            keyId = "synthetic-ordinary-denial-1",
            algorithmId = OfflineTrustBundleFixture.ALGORITHM,
            publicKeySpkiBase64 = Base64.getEncoder().encodeToString(spki),
            publicKeySha256 = Sha256.hex(spki),
            environment = "synthetic-test",
            dataScopeId = journal.scope.id.toString(),
            writerGeneration = declaration.writer.generationId,
            databaseIdentity = declaration.writer.databaseIdentity,
            restoreIdentity = declaration.writer.restoreIdentity,
            bucket = declaration.journalLocation.bucket,
            accountId = declaration.journalLocation.accountId,
            region = declaration.journalLocation.region,
            minimumApprovalVersion = 1,
            authorityGrant = reference("synthetic-ordinary-denial-grant"),
            implementationAcceptance = reference("synthetic-ordinary-drain-implementation"),
            evidenceRetentionPolicy = reference("synthetic-denial-evidence-retention"),
            runEnvelopeAccounting = TestOrdinaryDenialAuthorityPolicyV1.RUN_ENVELOPE_ACCOUNTING,
        )
    }

    fun statement(pathCount: Int = 1): TestOrdinaryDenialStatementV1 {
        val journal = fullTestJournal()
        val input = input(journal)
        return TestOrdinaryDenialStatementV1(
            schemaVersion = 1,
            purpose = input.purpose,
            approvalVersion = 1,
            authorityGrant = input.authorityGrant,
            implementationAcceptance = input.implementationAcceptance,
            evidenceRetentionPolicy = input.evidenceRetentionPolicy,
            environment = input.environment,
            dataScopeId = input.dataScopeId,
            activationCatalogGeneration = 4,
            activationCatalogSha256 = "a".repeat(64),
            configurationSha256 = "b".repeat(64),
            terminalEncodingSha256 = "c".repeat(64),
            initialWriterRegistrySha256 = "d".repeat(64),
            writerGeneration = input.writerGeneration,
            databaseIdentity = input.databaseIdentity,
            restoreIdentity = input.restoreIdentity,
            epochStartInclusive = 1,
            epochEndInclusive = 2,
            bucket = input.bucket,
            accountId = input.accountId,
            region = input.region,
            ordinaryPrefix = journal.ordinaryPrefix,
            roleId = "synthetic-ordinary-role",
            policy = TestTerminalPolicyRefV1("synthetic-ordinary-denial-policy", 1, "e".repeat(64)),
            denialEffectiveAtEpochSecond = 100,
            lastSessionExpiryEpochSecond = 101,
            acceptedRequestBoundSeconds = 5,
            utcUncertaintySeconds = 1,
            evidenceRetainUntilEpochSecond = 1_000,
            boundEvidence = evidence("synthetic-provider-bound"),
            effectivePaths = List(pathCount) { index ->
                TestOrdinaryDeniedPathV1(
                    "synthetic-path-${index.toString().padStart(2, '0')}", "synthetic-ordinary-role", 100, 101,
                    evidence("synthetic-path-proof-$index"),
                )
            },
        )
    }

    fun syntaxEnvelope(statement: TestOrdinaryDenialStatementV1 = statement()): TestOrdinaryDenialEnvelopeV1 =
        TestOrdinaryDenialEnvelopeV1(1, statement, unrelatedSignature)

    fun bytes(envelope: TestOrdinaryDenialEnvelopeV1 = syntaxEnvelope()): ByteArray =
        CanonicalJson.canonicalize(TestOrdinaryDenialEnvelopeV1.serializer(), envelope).toByteArray(Charsets.UTF_8)

    private fun reference(id: String): InitialPolicyReferenceV1 = InitialPolicyReferenceV1(id, 1, Sha256.hexUtf8("synthetic-declaration:$id"))

    private fun evidence(text: String): TestTerminalEvidenceDigestV1 {
        val raw = text.toByteArray(Charsets.UTF_8)
        return TestTerminalEvidenceDigestV1(Sha256.hex(raw), raw.size.toLong())
    }
}
