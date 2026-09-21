package me.manga.kira.backend.complaint.infrastructure.terminal

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalV1
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDenialAuthorityInputV1
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDenialStatementV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEvidenceDigestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineTrustBundleCrypto
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.time.Instant

/**
 * Required independent deployment trust input. This checks its syntax/key, NOT its provenance or
 * membership. The separately approved grant is committed into full D before activation/registration.
 * Neither a catalog key nor a candidate approval can construct or substitute this retained pin.
 */
internal class TestOrdinaryDenialAuthorityPolicyV1 private constructor(
    private val declaration: TestOrdinaryDenialAuthorityInputV1,
    private val key: RSAPublicKey,
) {
    private val canonical = CanonicalJson.canonicalize(TestOrdinaryDenialAuthorityInputV1.serializer(), declaration).toByteArray(Charsets.UTF_8)

    fun inventory(): JsonObject = buildJsonObject {
        put("profile", PROFILE)
        put("purpose", PURPOSE)
        put("sha256", Sha256.hex(canonical))
        put("runEnvelopeAccounting", RUN_ENVELOPE_ACCOUNTING)
    }

    fun requireEnvironment(environment: String) { require(declaration.environment == environment) { REFUSAL } }

    fun requireJournal(journal: TestOwnerDeleteJournalConfigurationV1) {
        val writer = journal.declaration().writer
        val location = journal.declaration().journalLocation
        require(declaration.dataScopeId == journal.scope.id.toString() && declaration.writerGeneration == writer.generationId &&
            declaration.databaseIdentity == writer.databaseIdentity && declaration.restoreIdentity == writer.restoreIdentity &&
            declaration.bucket == location.bucket && declaration.accountId == location.accountId && declaration.region == location.region) { REFUSAL }
    }

    /** Called only by the retained original, after actual scope/fence capture; no supplied inventory. */
    internal fun admit(
        original: TestRunOrdinaryDrainV1,
        approval: ByteArray,
        rawEvidence: List<ByteArray>,
    ): AdmittedOrdinaryDenialV1 {
        requireConnectionFree()
        original.requireAuthority(this)
        val (body, evidence) = verify(approval, rawEvidence)
        original.requireDenialContext(body)
        original.requireAuthority(this)
        return Admitted(original, this, body, evidence)
    }

    /** Exact externally re-supplied closure for the separate catalog original; never fabricates a drain/quiescence admission. */
    internal fun readmitCatalog(
        original: CatalogTestRunTerminalV1, approval: ByteArray, rawEvidence: List<ByteArray>,
    ): AdmittedCatalogOrdinaryDenialV1 {
        requireConnectionFree()
        original.requireOrdinaryAuthority(this)
        val (body, evidence) = verify(approval, rawEvidence)
        original.requireOrdinaryDenialContext(body, evidence)
        original.requireOrdinaryAuthority(this)
        return CatalogAdmitted(original, this, body, evidence)
    }

    /** Fixed same-lineage eraser admission; historical row bytes are never denial authority. */
    internal fun readmitErasure(
        original: TestRunErasureV1, approval: ByteArray, rawEvidence: List<ByteArray>,
    ): AdmittedErasureOrdinaryDenialV1 {
        requireConnectionFree()
        original.requireOrdinaryAuthority(this)
        val (body, evidence) = verify(approval, rawEvidence)
        original.requireOrdinaryDenialContext(body, evidence)
        original.requireOrdinaryAuthority(this)
        return ErasureAdmitted(original, this, body, evidence)
    }

    /** Cryptographic/closed-body validation only; the two fixed callers separately bind their actual original. */
    private fun verify(approval: ByteArray, rawEvidence: List<ByteArray>): Pair<TestOrdinaryDenialStatementV1, TestTerminalEvidenceDigestV1> {
        val count = rawEvidence.size
        require(approval.size in 1..MAX_ARTIFACT_BYTES && count in 2..17) { REFUSAL }
        val input = approval.copyOf()
        val raw = ArrayList<ByteArray>(count)
        try {
            var total = input.size.toLong()
            val iterator = rawEvidence.iterator()
            repeat(count) {
                require(iterator.hasNext()) { REFUSAL }
                val bytes = iterator.next()
                require(bytes.size in 1..MAX_ARTIFACT_BYTES) { REFUSAL }
                total = Math.addExact(total, bytes.size.toLong())
                require(total <= MAX_CLOSURE_BYTES) { REFUSAL }
                raw.add(bytes.copyOf())
            }
            require(!iterator.hasNext()) { REFUSAL }
            val envelope = OfflineTrustBundleParser.parseOrdinaryDenial(input)
            val body = envelope.body
            require(envelope.schemaVersion == 1 && body.schemaVersion == 1 && body.purpose == PURPOSE &&
                body.approvalVersion >= declaration.minimumApprovalVersion && body.authorityGrant == declaration.authorityGrant &&
                body.implementationAcceptance == declaration.implementationAcceptance && body.evidenceRetentionPolicy == declaration.evidenceRetentionPolicy &&
                body.environment == declaration.environment && body.dataScopeId == declaration.dataScopeId &&
                body.writerGeneration == declaration.writerGeneration && body.databaseIdentity == declaration.databaseIdentity &&
                body.restoreIdentity == declaration.restoreIdentity && body.bucket == declaration.bucket &&
                body.accountId == declaration.accountId && body.region == declaration.region) { REFUSAL }
            require(envelope.signature.keyId == declaration.keyId && envelope.signature.algorithmId == OfflineTrustBundleProtocol.ALGORITHM_ID) { REFUSAL }
            val canonicalBody = CanonicalJson.canonicalize(TestOrdinaryDenialStatementV1.serializer(), body).toByteArray(Charsets.UTF_8)
            val signature = OfflineTrustBundleCrypto.decodeBase64(envelope.signature.signatureBase64,
                OfflineTrustBundleProtocol.SIGNATURE_BYTES, OfflineTrustBundleFailure.INVALID_SIGNATURE)
            val frame = frame(declaration.keyId, canonicalBody)
            try { OfflineTrustBundleCrypto.verify(key, frame, signature) }
            finally { signature.fill(0); frame.fill(0); canonicalBody.fill(0) }
            validateTimes(body)
            require(body.effectivePaths.size in 1..MAX_PATHS && body.effectivePaths.zipWithNext().all { (a, b) -> a.pathId < b.pathId }) { REFUSAL }
            body.effectivePaths.forEach {
                require(REFERENCE.matches(it.pathId) && REFERENCE.matches(it.roleId) &&
                    it.denialEffectiveAtEpochSecond in 0..body.denialEffectiveAtEpochSecond &&
                    it.lastSessionExpiryEpochSecond in 0..body.lastSessionExpiryEpochSecond) { REFUSAL }
            }
            val references = body.effectivePaths.map { it.rawEvidence } + body.boundEvidence
            require(references.size == raw.size && references.map { it.sha256 }.distinct().size == references.size) { REFUSAL }
            val actual = raw.map { TestTerminalEvidenceDigestV1(Sha256.hex(it), it.size.toLong()) }
            require(actual.distinct().size == actual.size && actual.toSet() == references.toSet()) { REFUSAL }
            return body to TestTerminalEvidenceDigestV1(Sha256.hex(input), input.size.toLong())
        } finally { input.fill(0); raw.forEach { it.fill(0) } }
    }

    override fun toString(): String = "TestOrdinaryDenialAuthorityPolicyV1(independent-purpose-pin,no-provenance-claim)"

    /** Only successful admission can create this implementation; deserializing a DTO cannot. */
    private class Admitted(
        private val original: TestRunOrdinaryDrainV1,
        private val policy: TestOrdinaryDenialAuthorityPolicyV1,
        override val statement: TestOrdinaryDenialStatementV1,
        override val policyEvidence: TestTerminalEvidenceDigestV1,
    ) : AdmittedOrdinaryDenialV1 {
        override fun requireOriginal(candidate: TestRunOrdinaryDrainV1) {
            require(candidate === original) { REFUSAL }
            original.requireAuthority(policy)
        }
        override fun firstStartAfter(): Instant = Instant.ofEpochSecond(Math.addExact(Math.addExact(
            maxOf(statement.denialEffectiveAtEpochSecond, statement.lastSessionExpiryEpochSecond), statement.acceptedRequestBoundSeconds),
            Math.multiplyExact(2L, statement.utcUncertaintySeconds)))
        override fun toString(): String = "AdmittedOrdinaryDenialV1(original-only,redacted)"
    }

    private class CatalogAdmitted(
        private val original: CatalogTestRunTerminalV1,
        private val policy: TestOrdinaryDenialAuthorityPolicyV1,
        override val statement: TestOrdinaryDenialStatementV1,
        override val policyEvidence: TestTerminalEvidenceDigestV1,
    ) : AdmittedCatalogOrdinaryDenialV1 {
        override fun requireOriginal(candidate: CatalogTestRunTerminalV1) {
            require(candidate === original) { REFUSAL }
            original.requireOrdinaryAuthority(policy)
        }
        override fun firstStartAfter(): Instant = Instant.ofEpochSecond(Math.addExact(Math.addExact(
            maxOf(statement.denialEffectiveAtEpochSecond, statement.lastSessionExpiryEpochSecond), statement.acceptedRequestBoundSeconds),
            Math.multiplyExact(2L, statement.utcUncertaintySeconds)))
        override fun toString(): String = "AdmittedCatalogOrdinaryDenialV1(original-only,redacted)"
    }

    private class ErasureAdmitted(
        private val original: TestRunErasureV1,
        private val policy: TestOrdinaryDenialAuthorityPolicyV1,
        override val statement: TestOrdinaryDenialStatementV1,
        override val policyEvidence: TestTerminalEvidenceDigestV1,
    ) : AdmittedErasureOrdinaryDenialV1 {
        override fun requireOriginal(candidate: TestRunErasureV1) {
            require(candidate === original) { REFUSAL }
            original.requireOrdinaryAuthority(policy)
        }
        override fun firstStartAfter(): Instant = Instant.ofEpochSecond(Math.addExact(Math.addExact(
            maxOf(statement.denialEffectiveAtEpochSecond, statement.lastSessionExpiryEpochSecond), statement.acceptedRequestBoundSeconds),
            Math.multiplyExact(2L, statement.utcUncertaintySeconds)))
        override fun toString(): String = "AdmittedErasureOrdinaryDenialV1(original-only,redacted)"
    }

    companion object {
        const val PROFILE = "TEST_ORDINARY_DENIAL_AUTHORITY_V1"
        const val PURPOSE = "TEST_ORDINARY_PUT_DENIAL_AND_REQUEST_BOUND_V1"
        const val RUN_ENVELOPE_ACCOUNTING = "RUN_LIFETIME_ENVELOPE_AT_FIRST_CUT_V1"
        const val MAX_ARTIFACT_BYTES = 65_536
        const val MAX_PATHS = 16
        const val MAX_CLOSURE_BYTES = 18L * MAX_ARTIFACT_BYTES
        private const val LAST_SECOND = 253_402_300_799L
        private const val REFUSAL = "TEST ordinary denial authority refused."
        private val REFERENCE = Regex("[A-Za-z0-9._:/-]{1,128}")

        fun fromIndependentInput(input: TestOrdinaryDenialAuthorityInputV1): TestOrdinaryDenialAuthorityPolicyV1 {
            requireConnectionFree()
            require(input.profile == PROFILE && input.purpose == PURPOSE && input.runEnvelopeAccounting == RUN_ENVELOPE_ACCOUNTING &&
                input.algorithmId == OfflineTrustBundleProtocol.ALGORITHM_ID && input.keyId.matches(Regex("[A-Za-z0-9._-]{1,64}")) &&
                input.environment.matches(Regex("[a-z][a-z0-9-]{0,63}")) && input.minimumApprovalVersion > 0) { REFUSAL }
            require(listOf(input.dataScopeId, input.writerGeneration, input.databaseIdentity, input.restoreIdentity).all(OfflineBootstrapGrammar::uuidV4) &&
                input.bucket.matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) &&
                input.accountId.matches(Regex("[0-9]{12}")) && input.region.matches(Regex("[a-z]{2}-[a-z]+-[1-9][0-9]?"))) { REFUSAL }
            listOf(input.authorityGrant, input.implementationAcceptance, input.evidenceRetentionPolicy).forEach(::requirePolicyReference)
            val spki = OfflineTrustBundleCrypto.decodeBase64(input.publicKeySpkiBase64, OfflineTrustBundleProtocol.PUBLIC_KEY_BYTES,
                OfflineTrustBundleFailure.INVALID_PUBLIC_KEY)
            return try {
                require(Sha256.hex(spki) == input.publicKeySha256) { REFUSAL }
                TestOrdinaryDenialAuthorityPolicyV1(input.copy(), OfflineTrustBundleCrypto.publicKey(spki))
            } finally { spki.fill(0) }
        }

        internal fun frame(keyId: String, canonicalBody: ByteArray): ByteArray {
            val fields = listOf("kira.complaints.test-ordinary-denial.v1".toByteArray(), "kcj-1".toByteArray(), keyId.toByteArray(),
                OfflineTrustBundleProtocol.ALGORITHM_ID.toByteArray(), MessageDigest.getInstance("SHA-256").digest(canonicalBody))
            return ByteBuffer.allocate(fields.sumOf { 4 + it.size }).apply { fields.forEach { putInt(it.size).put(it) } }.array()
        }

        private fun validateTimes(body: TestOrdinaryDenialStatementV1) {
            require(REFERENCE.matches(body.roleId) && body.denialEffectiveAtEpochSecond in 0..LAST_SECOND &&
                body.lastSessionExpiryEpochSecond in 0..LAST_SECOND && body.acceptedRequestBoundSeconds in 1..LAST_SECOND &&
                body.utcUncertaintySeconds in 0..LAST_SECOND && body.evidenceRetainUntilEpochSecond in 1..LAST_SECOND) { REFUSAL }
            val end = Math.addExact(Math.addExact(maxOf(body.denialEffectiveAtEpochSecond, body.lastSessionExpiryEpochSecond),
                body.acceptedRequestBoundSeconds), Math.multiplyExact(2L, body.utcUncertaintySeconds))
            require(end <= LAST_SECOND && body.evidenceRetainUntilEpochSecond > end) { REFUSAL }
        }

        private fun requirePolicyReference(reference: InitialPolicyReferenceV1) {
            require(REFERENCE.matches(reference.policyId) && reference.version > 0 && reference.sha256.matches(Regex("[0-9a-f]{64}"))) { REFUSAL }
        }
    }
}

/** Private-produced admission, never a serializable authority or a caller-selected signer. */
internal sealed interface AdmittedOrdinaryDenialV1 {
    val statement: TestOrdinaryDenialStatementV1
    val policyEvidence: TestTerminalEvidenceDigestV1
    fun requireOriginal(candidate: TestRunOrdinaryDrainV1)
    fun firstStartAfter(): Instant
}

/** Fresh genuine catalog re-admission only. No SQL digest, old admitted wrapper or copied cut implements it. */
internal sealed interface AdmittedCatalogOrdinaryDenialV1 {
    val statement: TestOrdinaryDenialStatementV1
    val policyEvidence: TestTerminalEvidenceDigestV1
    fun requireOriginal(candidate: CatalogTestRunTerminalV1)
    fun firstStartAfter(): Instant
}

/** Private admitted wrapper for the exact eraser original only. */
internal sealed interface AdmittedErasureOrdinaryDenialV1 {
    val statement: TestOrdinaryDenialStatementV1
    val policyEvidence: TestTerminalEvidenceDigestV1
    fun requireOriginal(candidate: TestRunErasureV1)
    fun firstStartAfter(): Instant
}
