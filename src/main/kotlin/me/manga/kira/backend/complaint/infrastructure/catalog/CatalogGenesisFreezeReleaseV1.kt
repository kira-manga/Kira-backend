package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.UnverifiedGenesisPreparation
import java.nio.charset.StandardCharsets

/**
 * Fixed effect ordering under one independently provisioned no-rollback root and the existing writer-fencing assumptions.
 * Stable records are history, not approval or deserialized verifier handles. No repair, mutable ledger or new signature protocol.
 */
internal class CatalogGenesisFreezeReleaseV1(private val inputs: CatalogGenesisFreezeInputsV1, budget: PersistenceTimeBudget) : AutoCloseable {
    private val custody = CatalogGenesisReleaseCustodyV1.retain(inputs.request.releaseRoot, inputs.allocation, budget)
    private val releaseHash = Sha256.hex(inputs.allocation)

    fun openNew() {
        requireRecovery(custody.open() === CatalogGenesisCustodyObservationV1.CREATED)
        inputLeaves().forEach { (leaf, bytes) -> custody.putIfAbsent(leaf, bytes) }
        custody.putIfAbsent(CatalogGenesisReleaseLeafV1.FREEZE_ARMED, record("prepare-armed"))
    }

    fun openExisting() {
        requireRecovery(custody.open() === CatalogGenesisCustodyObservationV1.IDENTICAL_OBSERVED)
        inputLeaves().forEach { (leaf, bytes) -> requireExact(leaf, bytes) }
        requireExact(CatalogGenesisReleaseLeafV1.FREEZE_ARMED, record("prepare-armed"))
        // A crash before this positive, committed-and-released unsigned receipt is an explicit recovery case, not replay permission.
        requireExact(CatalogGenesisReleaseLeafV1.FREEZE_PREPARED_NO_SIGNATURE, record("prepared-no-signature"))
    }

    fun preparedUnsigned(local: UnverifiedGenesisPreparation) {
        inputs.requireUnsigned(local)
        custody.putIfAbsent(CatalogGenesisReleaseLeafV1.FREEZE_PREPARED_NO_SIGNATURE, record("prepared-no-signature"))
    }

    /** Fresh raw SQL is essential: neither an absent restored row nor absence of a Sign outcome establishes this predicate. */
    fun signatureOrRequirePreFreeze(local: UnverifiedGenesisPreparation): ByteArray? {
        requireExact(CatalogGenesisReleaseLeafV1.FREEZE_PREPARED_NO_SIGNATURE, record("prepared-no-signature"))
        val signature = custody.read(CatalogGenesisReleaseLeafV1.SIGNATURE)
        val armed = custody.read(CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTENCE_ARMED)
        if (signature != null) {
            requireRecovery(signature.size == OfflineTrustBundleProtocol.SIGNATURE_BYTES)
            requireRecovery(armed.contentEquals(record("signature-persistence-armed", Sha256.hex(signature))))
            requireExact(CatalogGenesisReleaseLeafV1.FREEZE_SIGN_ARMED, record("sign-armed"))
            val proposal = inputs.proposal(local, signature) // Raw intent/trust/signature verification before returning any reusable bytes.
            checkFrozenHistory(signature, checkNotNull(proposal.after.signedEnvelopeBytes))
            return signature
        }
        requireRecovery(armed == null && inputs.request.independentPin == null)
        val mustBeAbsent = listOf(
            CatalogGenesisReleaseLeafV1.ENVELOPE, CatalogGenesisReleaseLeafV1.PIN,
            CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTED, CatalogGenesisReleaseLeafV1.FREEZE_PIN_COMMITMENT_ARMED,
            CatalogGenesisReleaseLeafV1.FREEZE_OUTCOME,
            CatalogGenesisReleaseLeafV1.FIRST_D_ARMED, CatalogGenesisReleaseLeafV1.FIRST_D_OUTCOME,
            CatalogGenesisReleaseLeafV1.PUBLISH_ARMED, CatalogGenesisReleaseLeafV1.PUBLISH_OUTCOME,
            CatalogGenesisReleaseLeafV1.FINALIZE_ARMED, CatalogGenesisReleaseLeafV1.FINALIZE_OUTCOME,
        )
        requireRecovery(mustBeAbsent.all { custody.read(it) == null })
        custody.read(CatalogGenesisReleaseLeafV1.FREEZE_SIGN_ARMED)?.let { requireRecovery(it.contentEquals(record("sign-armed"))) }
        // This exact positive receipt + mandatory pre-persistence arms + complete original custody is the narrow proof,
        // under the documented independent no-rollback/fencing assumptions. Fresh namespace probing still precedes every Sign.
        inputs.requireUnsigned(local)
        return null
    }

    fun armSign() = custody.putIfAbsent(CatalogGenesisReleaseLeafV1.FREEZE_SIGN_ARMED, record("sign-armed"))

    fun preserveSignature(signature: ByteArray) {
        requireRecovery(signature.size == OfflineTrustBundleProtocol.SIGNATURE_BYTES)
        custody.putIfAbsent(CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTENCE_ARMED, record("signature-persistence-armed", Sha256.hex(signature)))
        custody.putIfAbsent(CatalogGenesisReleaseLeafV1.SIGNATURE, signature) // Arm is durable BEFORE the first local or SQL signature persistence.
    }

    fun signaturePersisted(local: UnverifiedGenesisPreparation, signature: ByteArray): ByteArray {
        val proposal = inputs.proposal(local, null)
        requireRecovery(proposal.after.signatureSlots.single().signatureBytes.contentEquals(signature))
        val envelope = checkNotNull(proposal.after.signedEnvelopeBytes)
        requireRecovery(local.mutation.signedEnvelopeBytes.contentEquals(envelope))
        custody.putIfAbsent(CatalogGenesisReleaseLeafV1.ENVELOPE, envelope)
        custody.putIfAbsent(CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTED, record("signature-persisted", Sha256.hex(signature), Sha256.hex(envelope)))
        checkExistingPin(envelope)
        return envelope
    }

    fun commitIndependentPin(pin: ByteArray, envelope: ByteArray) {
        requireRecovery(pin.contentEquals(Sha256.hex(envelope).toByteArray(StandardCharsets.US_ASCII)))
        val value = String(pin, StandardCharsets.US_ASCII)
        custody.putIfAbsent(CatalogGenesisReleaseLeafV1.FREEZE_PIN_COMMITMENT_ARMED, record("pin-commitment-armed", value))
        custody.putIfAbsent(CatalogGenesisReleaseLeafV1.PIN, pin)
        custody.putIfAbsent(CatalogGenesisReleaseLeafV1.FREEZE_OUTCOME, record("frozen", value))
    }

    private fun checkExistingPin(envelope: ByteArray) {
        val hash = Sha256.hex(envelope)
        val armed = custody.read(CatalogGenesisReleaseLeafV1.FREEZE_PIN_COMMITMENT_ARMED)
        val pin = custody.read(CatalogGenesisReleaseLeafV1.PIN)
        val outcome = custody.read(CatalogGenesisReleaseLeafV1.FREEZE_OUTCOME)
        if (armed != null || pin != null || outcome != null) {
            requireExact(CatalogGenesisReleaseLeafV1.ENVELOPE, envelope)
            requireRecovery(custody.read(CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTED) != null)
        }
        if (armed != null) requireRecovery(armed.contentEquals(record("pin-commitment-armed", hash)))
        if (pin != null) requireRecovery(armed != null && pin.contentEquals(hash.toByteArray(StandardCharsets.US_ASCII)))
        if (outcome != null) requireRecovery(pin != null && outcome.contentEquals(record("frozen", hash)))
    }

    private fun checkFrozenHistory(signature: ByteArray, envelope: ByteArray) {
        custody.read(CatalogGenesisReleaseLeafV1.ENVELOPE)?.let { requireRecovery(it.contentEquals(envelope)) }
        custody.read(CatalogGenesisReleaseLeafV1.FREEZE_SIGNATURE_PERSISTED)?.let {
            requireExact(CatalogGenesisReleaseLeafV1.ENVELOPE, envelope)
            requireRecovery(it.contentEquals(record("signature-persisted", Sha256.hex(signature), Sha256.hex(envelope))))
        }
        checkExistingPin(envelope) // Conflicting/partial frozen history refuses BEFORE even an exact signature CAS/no-op.
    }

    private fun inputLeaves(): List<Pair<CatalogGenesisReleaseLeafV1, ByteArray>> = listOf(
        CatalogGenesisReleaseLeafV1.APPROVED_INTENT to inputs.intentBytes(),
        CatalogGenesisReleaseLeafV1.TRUST_T0 to inputs.initialBytes(),
        CatalogGenesisReleaseLeafV1.TRUST_TN to inputs.currentBytes(),
        CatalogGenesisReleaseLeafV1.APPROVAL_INPUTS to inputs.approvals,
        CatalogGenesisReleaseLeafV1.PUBLIC_TARGET_BINDINGS to inputs.publicTarget,
        CatalogGenesisReleaseLeafV1.RETENTION_BINDINGS to inputs.retention,
    )

    private fun record(kind: String, vararg values: String): ByteArray = catalogFreezeRecord(kind, releaseHash, *values)

    private fun requireExact(leaf: CatalogGenesisReleaseLeafV1, expected: ByteArray) = requireRecovery(custody.read(leaf).contentEquals(expected))

    private fun requireRecovery(condition: Boolean) = requireCatalogFreeze(condition, CatalogGenesisFreezeFailureV1.RECOVERY_REQUIRED)

    override fun close() = custody.close()
    override fun toString(): String = "CatalogGenesisFreezeReleaseV1(fixed-history,redacted,no-approval-authority)"
}
