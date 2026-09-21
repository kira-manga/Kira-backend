package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleSignatureV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDeniedPathV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialCandidateV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialEnvelopeV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialStatementV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEvidenceDigestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPolicyRefV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunTerminalQuiescenceV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalDenialAuthorityPolicyV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalDenialInputFixtureV1
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

/** Independent synthetic terminal signer/pin fixed before actual protected intake, full D and activation. */
internal class TestTerminalQuiescenceFixtureInputsV1 {
    private val signer by lazy { OfflineTrustBundleFixture.newKey() }
    private val raw = listOf("synthetic-terminal-path-denial-evidence".toByteArray(), "synthetic-terminal-one-second-request-bound".toByteArray())
    val rawEvidence: List<ByteArray> get() = raw.map(ByteArray::copyOf)

    fun authorityInput(journal: TestOwnerDeleteJournalConfigurationV1, environment: String) =
        TestTerminalDenialInputFixtureV1.input(journal, signer.public.encoded).copy(environment = environment)
    fun authority(journal: TestOwnerDeleteJournalConfigurationV1, environment: String) =
        TestTerminalDenialAuthorityPolicyV1.fromIndependentInput(authorityInput(journal, environment))

    fun statement(original: TestRunTerminalQuiescenceV1): TestTerminalDenialStatementV1 {
        val journal = original.routing.journalConfiguration
        val pin = authorityInput(journal, original.registration.process.catalogReadback.chainPolicy.trustBundlePolicy.expectedEnvironment)
        val context = original.runContext
        val role = journal.declaration().authorities.sealTerminal
        val deniedAt = original.acquisition.sampleUtc().minusSeconds(2).epochSecond
        return TestTerminalDenialStatementV1(1, pin.purpose, pin.minimumApprovalVersion, pin.authorityGrant, pin.implementationAcceptance,
            pin.evidenceRetentionPolicy, pin.environment, context.dataScopeId, context.activationCatalogGeneration, context.activationCatalogSha256,
            context.configurationSha256, context.terminalEncodingSha256, original.registration.process.catalogActivation.initialWriterRegistrySha256,
            pin.writerGeneration, pin.databaseIdentity, pin.restoreIdentity, 1, original.epoch, pin.bucket, pin.accountId, pin.region,
            journal.sealTerminalPrefix, TestTerminalDenialCandidateV1(original.sealedReference, original.fullSealSetSha256, original.purge.authenticatedPurge()),
            role.roleId, TestTerminalPolicyRefV1(role.policy.policyId, role.policy.version, role.policy.sha256), deniedAt, deniedAt, 1, 0,
            original.acquisition.sampleUtc().plusSeconds(86_400).epochSecond, digest(raw[1]),
            listOf(TestTerminalDeniedPathV1("synthetic-terminal-path", role.roleId, deniedAt, deniedAt, digest(raw[0]))))
    }

    fun approval(statement: TestTerminalDenialStatementV1, keyId: String = "synthetic-terminal-denial-1",
        domain: String = "kira.complaints.test-terminal-denial.v1"): ByteArray {
        val canonical = CanonicalJson.canonicalize(TestTerminalDenialStatementV1.serializer(), statement).toByteArray(Charsets.UTF_8)
        val fields = listOf(domain.toByteArray(), "kcj-1".toByteArray(), keyId.toByteArray(), "RSASSA_PSS_SHA_256".toByteArray(),
            MessageDigest.getInstance("SHA-256").digest(canonical))
        val frame = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            fields.forEach { out.writeInt(it.size); out.write(it) }
        } }.toByteArray()
        return try {
            val signature = Signature.getInstance("RSASSA-PSS").apply {
                setParameter(OfflineTrustBundleFixture.parameters); initSign(signer.private); update(frame)
            }.sign()
            val envelope = TestTerminalDenialEnvelopeV1(1, statement,
                OfflineTrustBundleSignatureV1(keyId, "RSASSA_PSS_SHA_256", Base64.getEncoder().encodeToString(signature)))
            CanonicalJson.canonicalize(TestTerminalDenialEnvelopeV1.serializer(), envelope).toByteArray(Charsets.UTF_8)
        } finally { canonical.fill(0); frame.fill(0); fields.forEach { it.fill(0) } }
    }

    private fun digest(bytes: ByteArray) = TestTerminalEvidenceDigestV1(Sha256.hex(bytes), bytes.size.toLong())
}
