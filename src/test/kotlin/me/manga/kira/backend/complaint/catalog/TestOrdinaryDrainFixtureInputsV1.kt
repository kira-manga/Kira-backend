package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.complaint.domain.JournalLimitsV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleSignatureV1
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDenialEnvelopeV1
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDenialStatementV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDenialAuthorityPolicyV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDenialInputFixtureV1
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

/**
 * Explicit TEST fixture inputs BEFORE J, consumers, full D and signed activation. The fresh
 * in-memory signer is separate from catalog keys. None of these synthetic policy declarations,
 * signed provider-bound statements or raw evidence is real installed-denial/acceptance evidence.
 */
internal class TestOrdinaryDrainFixtureInputsV1(
    val maximumRetainedVersions: Long = 10_000,
    val maximumFramedBytes: Long? = null,
    val scanMillis: Int? = null,
    val terminalQuiescence: TestTerminalQuiescenceFixtureInputsV1? = null,
) {
    private val signer by lazy { OfflineTrustBundleFixture.newKey() }

    init {
        require(maximumRetainedVersions in 1..10_000)
        require(maximumFramedBytes == null || maximumFramedBytes > 0)
        require(scanMillis == null || scanMillis in 1..600_000)
    }

    fun limits(original: JournalLimitsV1): JournalLimitsV1 = original.copy(
        capacity = original.capacity.copy(
            maximumRetainedVersions = maximumRetainedVersions,
            maximumScanStagingBytes = maximumFramedBytes ?: original.capacity.maximumScanStagingBytes,
        ),
        // Before consumers/full D/signing only; the real TEST configuration validates all deadline inequalities.
        deadlines = scanMillis?.let { original.deadlines.copy(scanMillis = it) } ?: original.deadlines,
    )

    fun authorityInput(journal: TestOwnerDeleteJournalConfigurationV1, environment: String) =
        TestOrdinaryDenialInputFixtureV1.input(journal, signer.public.encoded).copy(environment = environment)

    fun authority(journal: TestOwnerDeleteJournalConfigurationV1, environment: String): TestOrdinaryDenialAuthorityPolicyV1 =
        TestOrdinaryDenialAuthorityPolicyV1.fromIndependentInput(authorityInput(journal, environment))

    /** Independent LP32/JCE signing, not the production authority's framing or admitted-result factory. */
    fun approval(statement: TestOrdinaryDenialStatementV1, keyId: String): ByteArray {
        val canonical = CanonicalJson.canonicalize(TestOrdinaryDenialStatementV1.serializer(), statement).toByteArray(Charsets.UTF_8)
        val fields = listOf(
            "kira.complaints.test-ordinary-denial.v1".toByteArray(Charsets.UTF_8),
            "kcj-1".toByteArray(Charsets.UTF_8), keyId.toByteArray(Charsets.UTF_8),
            "RSASSA_PSS_SHA_256".toByteArray(Charsets.UTF_8), MessageDigest.getInstance("SHA-256").digest(canonical),
        )
        val frame = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output -> fields.forEach { output.writeInt(it.size); output.write(it) } }
            buffer.toByteArray()
        }
        return try {
            val signature = Signature.getInstance("RSASSA-PSS").apply {
                setParameter(OfflineTrustBundleFixture.parameters)
                initSign(signer.private)
                update(frame)
            }.sign()
            val envelope = TestOrdinaryDenialEnvelopeV1(1, statement,
                OfflineTrustBundleSignatureV1(keyId, "RSASSA_PSS_SHA_256", Base64.getEncoder().encodeToString(signature)))
            CanonicalJson.canonicalize(TestOrdinaryDenialEnvelopeV1.serializer(), envelope).toByteArray(Charsets.UTF_8)
        } finally {
            canonical.fill(0); frame.fill(0); fields.forEach { it.fill(0) }
        }
    }
}
