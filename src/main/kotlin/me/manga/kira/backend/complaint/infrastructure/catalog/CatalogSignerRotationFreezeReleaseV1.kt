package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol

/** Fixed write-once effects and positive outcomes. Absence alone never authorizes Sign; there is no pin/reapproval protocol. */
internal class CatalogSignerRotationFreezeReleaseV1 private constructor(
    private val inputs: CatalogSignerRotationInputsV1,
    private val custody: CatalogSignerRotationReleaseCustodyV1,
    private val recovery: CatalogSignerRotationPreparedRecoveryV1?,
) : AutoCloseable {
    constructor(inputs: CatalogSignerRotationInputsV1, budget: PersistenceTimeBudget) :
        this(inputs, CatalogSignerRotationReleaseCustodyV1.retain(inputs.request.releaseRoot, inputs.allocation, budget), null)

    internal constructor(
        original: CatalogSignerRotationPreparedRecoveryV1,
        inputs: CatalogSignerRotationInputsV1,
        custody: CatalogSignerRotationReleaseCustodyV1,
    ) : this(inputs, custody, original) {
        original.requireRecoveryReleaseInputs(inputs, custody)
        requirePreparedInputs() // The original lock is still held. Never reopen/reallocate with the fresh lease token.
    }
    private val allocationHash = Sha256.hex(inputs.allocation)

    fun openNew() {
        requireRecovery(recovery == null)
        requireRecovery(custody.open() === CatalogSignerRotationCustodyObservationV1.CREATED)
        inputLeaves().forEach { (leaf, bytes) -> requireCreated(leaf, bytes) }
    }

    fun openExisting() {
        requireRecovery(recovery == null)
        requireRecovery(custody.openExisting() === CatalogSignerRotationCustodyObservationV1.IDENTICAL_OBSERVED)
        requirePreparedInputs()
    }

    private fun requirePreparedInputs() {
        requireNoDeliveryHistory()
        inputLeaves().forEach { (leaf, bytes) -> requireExact(leaf, bytes) }
        requireExact(CatalogSignerRotationReleaseLeafV1.PREPARE_ARMED, record("prepare-armed"))
        requireExact(CatalogSignerRotationReleaseLeafV1.PREPARED, record("prepared-unsigned-head1"))
    }

    fun armPrepare() {
        requireRecovery(recovery == null)
        requireCreated(CatalogSignerRotationReleaseLeafV1.PREPARE_ARMED, record("prepare-armed"))
    }

    fun prepared(local: CatalogSignerRotationObservationV1) {
        requireRecovery(recovery == null)
        inputs.requireObservation(local)
        requireRecovery(sameSignerRotationMutation(checkNotNull(local.mutation), inputs.unsigned()))
        requireExact(CatalogSignerRotationReleaseLeafV1.PREPARE_ARMED, record("prepare-armed"))
        requireCreated(CatalogSignerRotationReleaseLeafV1.PREPARED, record("prepared-unsigned-head1"))
    }

    fun armSign(slot: Int, local: CatalogSignerRotationObservationV1) {
        requireRecovery(recovery == null)
        requireRecovery(slot in 0..1)
        inputs.requireObservation(local)
        val mutation = checkNotNull(local.mutation)
        requireExact(CatalogSignerRotationReleaseLeafV1.PREPARED, record("prepared-unsigned-head1"))
        val signatures = mutation.signatureSlots.map { it.signatureBytes }
        requireRecovery(signatures[slot] == null && signatures.drop(slot).all { it == null } && mutation.signedEnvelopeBytes == null)
        if (slot == 0) {
            requireRecovery(signatures[0] == null)
        } else {
            val first = requireReturned(0)
            requireRecovery(first.contentEquals(signatures[0]))
            requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_PERSISTED, persistenceRecord(0, mutation))
        }
        requireCreated(armedLeaf(slot), signRecord(slot, signatures[0]))
    }

    /** Written only after the real fixed SDK adapter returns a verified signature AND proves its own cleanup. */
    fun preserveSignature(slot: Int, signature: ByteArray) {
        requireRecovery(recovery == null)
        requireRecovery(slot in 0..1 && signature.size == OfflineTrustBundleProtocol.SIGNATURE_BYTES)
        val first = if (slot == 1) requireReturned(0) else null
        requireExact(armedLeaf(slot), signRecord(slot, first))
        requireCreated(returnedLeaf(slot), record("sign-returned", slot.toString(), Sha256.hex(signature)))
        requireCreated(signatureLeaf(slot), signature)
    }

    fun armSignaturePersistence(slot: Int, after: CatalogFrozenMutation) {
        requireRecovery(recovery == null)
        inputs.requireMutation(after)
        requireRecovery(slot in 0..1)
        for (index in 0..slot) requireRecovery(requireReturned(index).contentEquals(after.signatureSlots[index].signatureBytes))
        requireCreated(sqlArmedLeaf(slot), persistenceRecord(slot, after))
        if (slot == 1) requireCreated(CatalogSignerRotationReleaseLeafV1.ENVELOPE, checkNotNull(after.signedEnvelopeBytes))
    }

    fun signaturePersisted(slot: Int, local: CatalogSignerRotationObservationV1) {
        recovery?.let {
            requireRecovery(slot == 1)
            it.requirePersistedReplay(local)
        }
        inputs.requireObservation(local)
        val mutation = checkNotNull(local.mutation)
        val expected = persistenceRecord(slot, mutation)
        requireExact(sqlArmedLeaf(slot), expected)
        for (index in 0..slot) requireRecovery(requireReturned(index).contentEquals(mutation.signatureSlots[index].signatureBytes))
        if (slot == 1) requireFrozenEnvelope(mutation)
        custody.putIfAbsent(sqlPersistedLeaf(slot), expected) // Exact replay may observe the same already-committed receipt.
    }

    /** Both actual returned outcomes, exact signatures, prior signature1 SQL and envelope are mandatory even on a no-op resume. */
    fun requireBothReturnedSignatures(): List<ByteArray> {
        requireNoDeliveryHistory()
        requireExact(CatalogSignerRotationReleaseLeafV1.PREPARED, record("prepared-unsigned-head1"))
        val first = requireReturned(0)
        val second = requireReturned(1)
        val signatures = listOf(first, second)
        val envelope = inputs.signedBytes(signatures)
        requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_ARMED, persistenceRecord(0, first, null, null))
        requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_PERSISTED, persistenceRecord(0, first, null, null))
        requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_ARMED, persistenceRecord(1, first, second, envelope))
        requireExact(CatalogSignerRotationReleaseLeafV1.ENVELOPE, envelope)
        val persisted = custody.read(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_PERSISTED)
        persisted?.let { requireRecovery(it.contentEquals(persistenceRecord(1, first, second, envelope))) }
        custody.read(CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME)?.let {
            requireRecovery(persisted != null && it.contentEquals(record("signed-prepared2-head1", Sha256.hex(envelope))))
        }
        return signatures
    }

    /** Positive complete prefix AND absent every second-slot/downstream record under the original exclusive lock. */
    fun requireUnattemptedSecondSign(local: CatalogSignerRotationObservationV1) {
        requirePreparedInputs()
        inputs.requireObservation(local)
        val mutation = checkNotNull(local.mutation)
        val first = requireReturned(0)
        val persistedFirst = persistenceRecord(0, first, null, null)
        requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_ARMED, persistedFirst)
        requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_PERSISTED, persistedFirst)
        requireRecovery(first.contentEquals(mutation.signatureSlots[0].signatureBytes))
        requireRecovery(
            mutation.signatureSlots[1].signatureBytes == null && mutation.signedEnvelopeBytes == null && mutation.signedEnvelopeSha256 == null,
        )
        // Each read scans the entire bounded allowed inventory and rejects every partial content/completeness pair.
        (SECOND_SIGN_LEAVES + CatalogSignerRotationReleaseLeafV1.deliveryLeaves()).forEach { requireRecovery(custody.read(it) == null) }
    }

    fun requireFrozenEnvelope(after: CatalogFrozenMutation) {
        inputs.requireMutation(after)
        requireExact(CatalogSignerRotationReleaseLeafV1.ENVELOPE, checkNotNull(after.signedEnvelopeBytes))
    }

    fun signedPrepared(local: CatalogSignerRotationObservationV1): CatalogSignerRotationFrozenProductV1 {
        recovery?.requirePersistedReplay(local)
        inputs.requireObservation(local)
        val mutation = checkNotNull(local.mutation)
        val signatures = requireBothReturnedSignatures()
        signatures.forEachIndexed { index, bytes -> requireRecovery(bytes.contentEquals(mutation.signatureSlots[index].signatureBytes)) }
        requireFrozenEnvelope(mutation)
        requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_PERSISTED, persistenceRecord(1, mutation))
        val hash = checkNotNull(mutation.signedEnvelopeSha256)
        custody.putIfAbsent(CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME, record("signed-prepared2-head1", hash))
        return CatalogSignerRotationFrozenProductV1(mutation.operationToken, hash) // Overall owner still must prove original cleanup before Result.
    }

    private fun requireNoDeliveryHistory() {
        // Every read validates the complete bounded inventory, including content/marker partial pairs.
        CatalogSignerRotationReleaseLeafV1.deliveryLeaves().forEach { requireRecovery(custody.read(it) == null) }
    }

    private fun requireReturned(slot: Int): ByteArray {
        val bytes = custody.read(signatureLeaf(slot)) ?: throw CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        requireRecovery(bytes.size == OfflineTrustBundleProtocol.SIGNATURE_BYTES)
        requireExact(returnedLeaf(slot), record("sign-returned", slot.toString(), Sha256.hex(bytes)))
        requireExact(armedLeaf(slot), signRecord(slot, if (slot == 1) requireReturned(0) else null))
        return bytes
    }

    private fun inputLeaves(): List<Pair<CatalogSignerRotationReleaseLeafV1, ByteArray>> = listOf(
        CatalogSignerRotationReleaseLeafV1.APPROVED_INTENT to inputs.intentBytes(),
        CatalogSignerRotationReleaseLeafV1.TRUST_T0 to inputs.initialBytes(),
        CatalogSignerRotationReleaseLeafV1.TRUST_TN to inputs.currentBytes(),
        CatalogSignerRotationReleaseLeafV1.APPROVAL_INPUTS to inputs.approvalBytes(),
        CatalogSignerRotationReleaseLeafV1.BINDING to inputs.bindingRecord,
    )

    private fun persistenceRecord(slot: Int, mutation: CatalogFrozenMutation): ByteArray = persistenceRecord(
        slot,
        checkNotNull(mutation.signatureSlots[0].signatureBytes),
        mutation.signatureSlots[1].signatureBytes,
        mutation.signedEnvelopeBytes,
    )

    private fun persistenceRecord(slot: Int, first: ByteArray, second: ByteArray?, envelope: ByteArray?): ByteArray =
        record("signature-sql", slot.toString(), Sha256.hex(first), second?.let(Sha256::hex) ?: "-", envelope?.let(Sha256::hex) ?: "-")

    private fun signRecord(slot: Int, first: ByteArray?): ByteArray = record("sign-armed", slot.toString(), first?.let(Sha256::hex) ?: "-")
    private fun record(kind: String, vararg values: String): ByteArray = signerRotationRecord(kind, allocationHash, *values)
    private fun requireExact(leaf: CatalogSignerRotationReleaseLeafV1, bytes: ByteArray) = requireRecovery(custody.read(leaf).contentEquals(bytes))
    private fun requireCreated(leaf: CatalogSignerRotationReleaseLeafV1, bytes: ByteArray) =
        requireRecovery(custody.putIfAbsent(leaf, bytes) === CatalogSignerRotationCustodyObservationV1.CREATED)

    private fun armedLeaf(slot: Int) = if (slot == 0) CatalogSignerRotationReleaseLeafV1.SIGN_ONE_ARMED else CatalogSignerRotationReleaseLeafV1.SIGN_TWO_ARMED
    private fun returnedLeaf(slot: Int) = if (slot ==
        0
    ) {
        CatalogSignerRotationReleaseLeafV1.SIGN_ONE_RETURNED
    } else {
        CatalogSignerRotationReleaseLeafV1.SIGN_TWO_RETURNED
    }
    private fun signatureLeaf(slot: Int) = if (slot == 0) CatalogSignerRotationReleaseLeafV1.SIGNATURE_ONE else CatalogSignerRotationReleaseLeafV1.SIGNATURE_TWO
    private fun sqlArmedLeaf(slot: Int) = if (slot ==
        0
    ) {
        CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_ARMED
    } else {
        CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_ARMED
    }
    private fun sqlPersistedLeaf(slot: Int) = if (slot ==
        0
    ) {
        CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_PERSISTED
    } else {
        CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_PERSISTED
    }
    private fun requireRecovery(condition: Boolean) = requireSignerRotation(condition, CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)

    override fun close() {
        if (recovery == null) custody.close() // The concrete recovery owner retains/closes its same discovery custody.
    }
    override fun toString(): String = "CatalogSignerRotationFreezeReleaseV1(fixed-write-once-history,redacted,no-human-approval-authority)"

    private companion object {
        val SECOND_SIGN_LEAVES = listOf(
            CatalogSignerRotationReleaseLeafV1.SIGN_TWO_ARMED,
            CatalogSignerRotationReleaseLeafV1.SIGN_TWO_RETURNED,
            CatalogSignerRotationReleaseLeafV1.SIGNATURE_TWO,
            CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_ARMED,
            CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_PERSISTED,
            CatalogSignerRotationReleaseLeafV1.ENVELOPE,
            CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME,
        )
    }
}
