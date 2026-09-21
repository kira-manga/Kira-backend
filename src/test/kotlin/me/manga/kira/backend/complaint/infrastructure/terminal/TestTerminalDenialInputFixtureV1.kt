package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.complaint.catalog.OfflineTrustBundleFixture
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDeniedPathV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialAuthorityInputV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialCandidateV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialEnvelopeV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialStatementV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRoleV1
import me.manga.kira.backend.security.fullTestJournal

/** Syntax declarations only. The envelope signature is deliberately unrelated, never an admission factory. */
internal object TestTerminalDenialInputFixtureV1 {
    fun input(journal: TestOwnerDeleteJournalConfigurationV1 = fullTestJournal(),
        spki: ByteArray = OfflineTrustBundleFixture.secondSigner.public.encoded): TestTerminalDenialAuthorityInputV1 {
        val ordinary = TestOrdinaryDenialInputFixtureV1.input(journal, spki)
        return TestTerminalDenialAuthorityInputV1(TestTerminalDenialAuthorityPolicyV1.PROFILE,
            TestTerminalDenialAuthorityPolicyV1.PURPOSE, "synthetic-terminal-denial-1", ordinary.algorithmId,
            ordinary.publicKeySpkiBase64, ordinary.publicKeySha256, ordinary.environment, ordinary.dataScopeId,
            ordinary.writerGeneration, ordinary.databaseIdentity, ordinary.restoreIdentity, ordinary.bucket, ordinary.accountId,
            ordinary.region, ordinary.minimumApprovalVersion, ordinary.authorityGrant.copy(policyId = "synthetic-terminal-denial-grant"),
            ordinary.implementationAcceptance.copy(policyId = "synthetic-terminal-quiescence-implementation"), ordinary.evidenceRetentionPolicy,
            TestTerminalDenialAuthorityPolicyV1.RUN_ENVELOPE_ACCOUNTING)
    }

    fun statement(pathCount: Int = 1): TestTerminalDenialStatementV1 {
        val journal = fullTestJournal()
        val pin = input(journal)
        val ordinary = TestOrdinaryDenialInputFixtureV1.statement(pathCount)
        val id = "A".repeat(43)
        fun objectRef(kind: String) = TestTerminalObjectRefV1(
            "${journal.sealTerminalPrefix}2/${journal.declaration().routing.activeKeyId}/$kind/$id.kjev", "synthetic-version-1", "a".repeat(64), "b".repeat(64))
        val seal = TestTerminalSealRefV1(TestTerminalSealRoleV1.TERMINAL, pin.writerGeneration, 2, 2, id, "c".repeat(64), objectRef("epoch-seal"))
        return TestTerminalDenialStatementV1(1, pin.purpose, pin.minimumApprovalVersion, pin.authorityGrant, pin.implementationAcceptance,
            pin.evidenceRetentionPolicy, pin.environment, pin.dataScopeId, ordinary.activationCatalogGeneration,
            ordinary.activationCatalogSha256, ordinary.configurationSha256, ordinary.terminalEncodingSha256,
            ordinary.initialWriterRegistrySha256, pin.writerGeneration, pin.databaseIdentity, pin.restoreIdentity, 1, 2,
            pin.bucket, pin.accountId, pin.region, journal.sealTerminalPrefix,
            TestTerminalDenialCandidateV1(seal, "d".repeat(64), objectRef("test-run-purge")), "synthetic-terminal-role",
            ordinary.policy.copy(policyId = "synthetic-terminal-policy"), ordinary.denialEffectiveAtEpochSecond,
            ordinary.lastSessionExpiryEpochSecond, ordinary.acceptedRequestBoundSeconds, ordinary.utcUncertaintySeconds,
            ordinary.evidenceRetainUntilEpochSecond, ordinary.boundEvidence,
            ordinary.effectivePaths.map { TestTerminalDeniedPathV1(it.pathId, "synthetic-terminal-role", it.denialEffectiveAtEpochSecond,
                it.lastSessionExpiryEpochSecond, it.rawEvidence) })
    }

    fun envelope(statement: TestTerminalDenialStatementV1 = statement()) =
        TestTerminalDenialEnvelopeV1(1, statement, OfflineTrustBundleFixture.signed().signature)

    fun bytes(value: TestTerminalDenialEnvelopeV1 = envelope()): ByteArray =
        CanonicalJson.canonicalize(TestTerminalDenialEnvelopeV1.serializer(), value).toByteArray(Charsets.UTF_8)
}
