package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutAcknowledgementV1
import java.time.Instant
import java.util.UUID

/**
 * Read the COMPLETE original freeze prefix, then append only fixed delivery/finalization records.
 * No author or Sign mutator is reachable from this type. The original discovery custody remains held
 * by the concrete delivery owner through separate PROJECT and its actual cleanup.
 */
@Suppress("TooManyFunctions") // The fixed historical record/custody checks stay with the one original release owner.
internal class CatalogSignerRotationDeliveryReleaseV1(
    private val original: CatalogSignerRotationDeliveryV1,
    private val inputs: CatalogSignerRotationInputsV1,
    private val custody: CatalogSignerRotationReleaseCustodyV1,
) {
    private val allocationHash = Sha256.hex(inputs.allocation)
    private val binding = decode(inputs.bindingRecord, "binding", 14)
    private val first: ByteArray
    private val second: ByteArray
    private val envelope: ByteArray
    private val envelopeHash: String
    private var publicationArm: ByteArray? = null
    private var publicationLease: List<String>? = null
    private var completeArm: FinalizationRecord? = null
    private var completeOutcome: FinalizationRecord? = null
    private var projectArm: FinalizationRecord? = null
    private var projectOutcome: FinalizationRecord? = null
    private var newArm = false
    private var putSpent = false
    private var dual: CatalogDualLocationVerifier.Overlap2Readback? = null
    private val expectedMutation: CatalogFrozenMutation

    init {
        requireConnectionFree()
        original.requireDeliveryReleaseInputs(inputs, custody)
        inputLeaves().forEach { (leaf, bytes) -> requireExact(leaf, bytes) }
        requireExact(CatalogSignerRotationReleaseLeafV1.PREPARE_ARMED, record("prepare-armed"))
        requireExact(CatalogSignerRotationReleaseLeafV1.PREPARED, record("prepared-unsigned-head1"))
        first = requireReturned(0, null)
        second = requireReturned(1, first)
        envelope = inputs.signedBytes(listOf(first, second))
        envelopeHash = Sha256.hex(envelope)
        expectedMutation = inputs.expectedSignedDeliveryMutation(listOf(first, second))
        requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_ARMED, signatureRecord(0, first, null, null))
        requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_PERSISTED, signatureRecord(0, first, null, null))
        val finalSignature = signatureRecord(1, first, second, envelope)
        requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_ARMED, finalSignature)
        requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_PERSISTED, finalSignature)
        requireExact(CatalogSignerRotationReleaseLeafV1.ENVELOPE, envelope)
        requireExact(CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME, record("signed-prepared2-head1", envelopeHash))
        if (!original.isRecovery()) FINALIZATION_LEAVES.forEach { requireRecovery(custody.read(it) == null) }
        publicationArm = custody.read(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED)
        publicationArm?.let { publicationLease = validatePublicationArm(it) }
        if (publicationArm == null) {
            CatalogSignerRotationReleaseLeafV1.deliveryLeaves().forEach { requireRecovery(custody.read(it) == null) }
        } else {
            readFinalizationHistory()
        }
    }

    internal fun signatures(): List<ByteArray> = listOf(first.copyOf(), second.copyOf())
    internal fun envelopeBytes(): ByteArray = envelope.copyOf()

    /** Expected R17 only. Actual G1+2/full33 history must still be acquired under the new lease and row locks. */
    internal fun expectedFrozenMutation(): CatalogFrozenMutation = expectedMutation

    internal fun isArmed(): Boolean {
        original.requireRunning()
        return publicationArm != null
    }

    /** Every present historical acquisition contributes its floor; none confers current leadership. */
    internal fun historicalLeaseFloor(): Long = maxOf(binding[13].toLong(), historicalLeases().maxOfOrNull { it[10].toLong() } ?: 0L)

    internal fun historicalOwners(): Set<UUID> = setOf(UUID.fromString(binding[12])) + historicalLeases().map { UUID.fromString(it[9]) }

    internal fun requireSnapshot(local: LocalCatalogSnapshot) {
        original.requireRunning()
        when (local) {
            is LocalCatalogSnapshot.Prepared -> requireRecovery(completeOutcome == null && projectArm == null && projectOutcome == null)
            is LocalCatalogSnapshot.ProjectionPending -> requireRecovery(completeArm != null && projectOutcome == null)
            is LocalCatalogSnapshot.Accepted -> requireRecovery(completeArm != null && projectArm != null)
            else -> requireRecovery(false)
        }
    }

    /** Read-only historical comparison. An absent outcome never becomes an observed-success record. */
    internal fun requireObservedHistory(value: CatalogSignerRotationFinalizationObservationV1) {
        requireConnectionFree()
        original.requireRunning()
        finalizationHistory().forEach { (leaf, saved) -> requireRecovery(custody.read(leaf).contentEquals(saved?.bytes)) }
        requireRecovery(sameSignerRotationMutation(value.mutation, expectedMutation))
        val completedAt = value.completedAt
        if (completedAt == null) {
            requireRecovery(value.projectedAt == null && completeOutcome == null && projectArm == null && projectOutcome == null)
            return
        }
        requireRecovery(completeArm != null)
        completeOutcome?.let { requireRecovery(it.completedAt == completedAt) }
        projectArm?.let { requireRecovery(it.completedAt == completedAt) }
        projectOutcome?.let { requireRecovery(it.completedAt == completedAt && it.projectedAt == value.projectedAt) }
        if (value.projectedAt == null) requireRecovery(projectOutcome == null) else requireRecovery(projectArm != null)
    }

    internal fun armPublication() {
        original.requirePublicationArm(this)
        requireRecovery(publicationArm == null && !newArm && !putSpent)
        val bytes = record("publication-armed", envelopeHash, *original.currentAcquisitionRecordValues())
        requireRecovery(bytes.size <= CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED.maximumBytes)
        requireCreated(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED, bytes)
        publicationArm = bytes
        publicationLease = validatePublicationArm(bytes)
        newArm = true // Identical pre-existing bytes never reach this assignment or grant another claim.
    }

    internal fun claimPut() {
        requireArmed()
        requireRecovery(newArm && !putSpent)
        original.requirePublicationClaim(this)
        putSpent = true // Spent before either native/SDK construction, even if the next line cannot run.
    }

    internal fun acknowledged(value: CatalogPrimaryPutAcknowledgementV1) {
        requireRecovery(newArm && putSpent)
        original.requireAcknowledgement(this, value)
        requireArmed()
        requireRecovery(value.envelopeSha256 == envelopeHash)
        requireCreated(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED, acknowledgementRecord(value.versionId))
    }

    internal fun awaitReplication(proof: CatalogDualLocationVerifier.Overlap2Readback) {
        original.requireDeliveryProof(this, proof)
        requireArmed()
        requireRecovery(proof.state === CatalogDualLocationVerifier.Overlap2Readback.State.PREPARED_AWAIT_REPLICATION)
        requireRecovery(proof.primaryEvidenceBytes() == null && proof.replicaEvidenceBytes() == null && proof.commonHeadEvidence() == null)
        requireFrozenProof(proof)
        requireHistoryMatches(proof)
        requireRecovery(custody.read(CatalogSignerRotationReleaseLeafV1.PRIMARY_COPY) == null)
        requireRecovery(custody.read(CatalogSignerRotationReleaseLeafV1.REPLICA_COPY) == null)
        requireRecovery(custody.read(CatalogSignerRotationReleaseLeafV1.PUBLICATION_DUAL_COPY) == null)
        custody.putIfAbsent(CatalogSignerRotationReleaseLeafV1.PUBLICATION_AWAIT_REPLICATION, awaitingRecord(proof))
    }

    internal fun preserveDual(proof: CatalogDualLocationVerifier.Overlap2Readback) {
        original.requireDeliveryProof(this, proof)
        requireArmed()
        requireRecovery(proof.state === CatalogDualLocationVerifier.Overlap2Readback.State.PREPARED_DUAL_COPY)
        requireFrozenProof(proof)
        requireHistoryMatches(proof)
        val primary = checkNotNull(proof.primaryEvidenceBytes())
        val replica = checkNotNull(proof.replicaEvidenceBytes())
        custody.putIfAbsent(CatalogSignerRotationReleaseLeafV1.PRIMARY_COPY, primary)
        custody.putIfAbsent(CatalogSignerRotationReleaseLeafV1.REPLICA_COPY, replica)
        custody.putIfAbsent(CatalogSignerRotationReleaseLeafV1.PUBLICATION_DUAL_COPY, dualRecord(proof))
        requireExact(CatalogSignerRotationReleaseLeafV1.PRIMARY_COPY, primary)
        requireExact(CatalogSignerRotationReleaseLeafV1.REPLICA_COPY, replica)
        requireExact(CatalogSignerRotationReleaseLeafV1.PUBLICATION_DUAL_COPY, dualRecord(proof))
        dual = proof // Both actual independent canonical evidence blobs, durably fsynced/reread under original custody.
    }

    internal fun armComplete() {
        original.requireCompletionArm(this, checkNotNull(dual))
        requireArmed()
        val proof = checkNotNull(dual)
        requireExact(CatalogSignerRotationReleaseLeafV1.PRIMARY_COPY, checkNotNull(proof.primaryEvidenceBytes()))
        requireExact(CatalogSignerRotationReleaseLeafV1.REPLICA_COPY, checkNotNull(proof.replicaEvidenceBytes()))
        requireExact(CatalogSignerRotationReleaseLeafV1.PUBLICATION_DUAL_COPY, dualRecord(proof))
        if (completeArm == null) {
            val bytes = record("complete-armed", envelopeHash, *original.currentAcquisitionRecordValues())
            requireCreated(CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED, bytes)
            completeArm = finalizationRecord(bytes, "complete-armed", 0, 1).also { requireLeaseAfter(it.lease, checkNotNull(publicationLease)) }
        } else {
            // The old arm remains spent and immutable. Only the new owner's released reconciliation grants this DB-only retry.
            requireRecovery(original.isRecovery() && completeOutcome == null && projectArm == null && projectOutcome == null)
            requireExact(CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED, checkNotNull(completeArm).bytes)
        }
    }

    internal fun completed(value: CatalogSignerRotationFinalizationObservationV1) {
        original.requireCompletedDelivery(this, value)
        requireRecovery(value.mutation.signedEnvelopeBytes.contentEquals(envelope) && value.projectedAt == null)
        requireObservedHistory(value)
        val armed = checkNotNull(completeArm)
        requireExact(CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED, armed.bytes)
        if (armed.lease == original.currentAcquisitionRecordValues().toList()) {
            val bytes = record("completed2-pending2", envelopeHash, checkNotNull(value.completedAt).toString(), *pendingAcquisitionValues())
            requireCreated(CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME, bytes)
            completeOutcome = finalizationRecord(bytes, "completed2-pending2", 1, 2)
        } // A new lease must NOT fill an older arm's missing outcome, even after this new owner's known successful retry.
    }

    internal fun armProject(value: CatalogSignerRotationFinalizationObservationV1) {
        original.requireProjectionArm(this, value)
        requireObservedHistory(value)
        requireRecovery(value.completedAt != null && value.projectedAt == null && projectOutcome == null)
        if (projectArm == null) {
            val bytes = record("project-armed", envelopeHash, value.completedAt.toString(), *pendingAcquisitionValues())
            requireCreated(CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED, bytes)
            projectArm = finalizationRecord(bytes, "project-armed", 1, 2).also {
                requireLeaseAfter(it.lease, completeOutcome?.lease ?: pendingValues(checkNotNull(completeArm).lease))
            }
        } else {
            requireRecovery(original.isRecovery())
            requireExact(CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED, checkNotNull(projectArm).bytes)
        }
    }

    internal fun projected(value: CatalogSignerRotationFinalizationObservationV1) {
        original.requireProjectedDelivery(this, value)
        requireRecovery(value.mutation.signedEnvelopeBytes.contentEquals(envelope))
        requireObservedHistory(value)
        val armed = checkNotNull(projectArm)
        requireExact(CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED, armed.bytes)
        if (armed.lease == pendingAcquisitionValues().toList()) {
            val bytes = record(
                "projected2",
                envelopeHash,
                value.completedAt.toString(),
                checkNotNull(value.projectedAt).toString(),
                *pendingAcquisitionValues(),
            )
            requireCreated(CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME, bytes)
            projectOutcome = finalizationRecord(bytes, "projected2", 2, 2)
        } // Otherwise the old arm/outcome gap remains historical, not a claim about the old transaction.
    }

    internal fun requireHistoricalReadback(proof: CatalogDualLocationVerifier.Overlap2Readback) {
        requireConnectionFree()
        original.requireRunning()
        requireFrozenProof(proof)
        requireHistoryMatches(proof)
        when (proof.state) {
            CatalogDualLocationVerifier.Overlap2Readback.State.PREPARED_DUAL_COPY,
            CatalogDualLocationVerifier.Overlap2Readback.State.PROJECTION_PENDING_DUAL_COPY,
            -> requireExistingCopies(
                proof.objectVersion,
                proof.retainUntilEpochSecond,
                checkNotNull(proof.primaryEvidenceBytes()),
                checkNotNull(proof.replicaEvidenceBytes()),
                completeArm != null,
            )

            else -> {
                requireRecovery(completeArm == null)
                COPY_LEAVES.forEach { requireRecovery(custody.read(it) == null) }
            }
        }
    }

    internal fun requireHistoricalProjectedReadback(proof: CatalogDualLocationVerifier.ProjectedHeadReadback) {
        requireConnectionFree()
        original.requireRunning()
        val generation = proof.generation()
        requireRecovery(
            completeArm != null && projectArm != null && generation.claims.operationToken == inputs.manifest.operationToken &&
                generation.envelopeBytes.contentEquals(envelope) && proof.envelopeSha256 == envelopeHash,
        )
        requireHistoryMatches(proof.objectVersion, proof.retainUntilEpochSecond)
        requireExistingCopies(proof.objectVersion, proof.retainUntilEpochSecond, proof.primaryEvidenceBytes(), proof.replicaEvidenceBytes(), true)
    }

    private fun requireExistingCopies(version: String, retainUntil: Long, primary: ByteArray, replica: ByteArray, required: Boolean) {
        listOf(
            CatalogSignerRotationReleaseLeafV1.PRIMARY_COPY to primary,
            CatalogSignerRotationReleaseLeafV1.REPLICA_COPY to replica,
            CatalogSignerRotationReleaseLeafV1.PUBLICATION_DUAL_COPY to dualRecord(version, retainUntil, primary, replica),
        ).forEach { (leaf, expected) ->
            val bytes = custody.read(leaf)
            requireRecovery((bytes != null || !required) && (bytes == null || bytes.contentEquals(expected)))
        }
    }

    private fun requireFrozenProof(proof: CatalogDualLocationVerifier.Overlap2Readback) = requireRecovery(
        proof.operationToken == inputs.manifest.operationToken && proof.frozenEnvelopeBytes().contentEquals(envelope) &&
            (proof.state === CatalogDualLocationVerifier.Overlap2Readback.State.PREPARED_UNPUBLISHED ||
                (proof.observedTail.generation == 2L && proof.observedTail.envelopeSha256 == envelopeHash)),
    )

    private fun requireHistoryMatches(proof: CatalogDualLocationVerifier.Overlap2Readback) =
        requireHistoryMatches(proof.objectVersion, proof.retainUntilEpochSecond)

    private fun requireHistoryMatches(version: String, retainUntil: Long) {
        custody.read(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED)?.let {
            requireRecovery(it.contentEquals(acknowledgementRecord(version)))
        }
        custody.read(CatalogSignerRotationReleaseLeafV1.PUBLICATION_AWAIT_REPLICATION)?.let {
            requireRecovery(it.contentEquals(record("await-replication2", envelopeHash, version, retainUntil.toString())))
        }
    }

    private fun requireArmed() {
        original.requireRunning()
        requireExact(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED, checkNotNull(publicationArm))
    }

    private fun validatePublicationArm(bytes: ByteArray): List<String> {
        val values = decode(bytes, "publication-armed", 16)
        requireRecovery(values[2] == allocationHash && values[3] == envelopeHash)
        return validateLease(values.drop(4), 1)
    }

    private fun readFinalizationHistory() {
        completeArm = custody.read(CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED)?.let {
            finalizationRecord(it, "complete-armed", 0, 1).also { saved -> requireLeaseAfter(saved.lease, checkNotNull(publicationLease)) }
        }
        completeOutcome = custody.read(CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME)?.let {
            finalizationRecord(it, "completed2-pending2", 1, 2).also { saved ->
                requireRecovery(saved.lease == pendingValues(checkNotNull(completeArm).lease))
            }
        }
        projectArm = custody.read(CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED)?.let {
            finalizationRecord(it, "project-armed", 1, 2).also { saved ->
                requireLeaseAfter(saved.lease, completeOutcome?.lease ?: pendingValues(checkNotNull(completeArm).lease))
                completeOutcome?.let { completed -> requireRecovery(saved.completedAt == completed.completedAt) }
            }
        }
        projectOutcome = custody.read(CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME)?.let {
            finalizationRecord(it, "projected2", 2, 2).also { saved ->
                val armed = checkNotNull(projectArm)
                requireRecovery(saved.lease == armed.lease && saved.completedAt == armed.completedAt)
            }
        }
        if (completeArm != null) COPY_LEAVES.forEach { requireRecovery(custody.read(it) != null) }
    }

    private fun finalizationRecord(bytes: ByteArray, kind: String, timeCount: Int, head: Int): FinalizationRecord {
        val values = decode(bytes, kind, 16 + timeCount)
        requireRecovery(values[2] == allocationHash && values[3] == envelopeHash)
        val times = values.drop(4).take(timeCount).map(::canonicalInstant)
        if (timeCount == 2) requireRecovery(!times[1].isBefore(times[0]))
        return FinalizationRecord(bytes.copyOf(), validateLease(values.drop(4 + timeCount), head), times.firstOrNull(), times.getOrNull(1))
    }

    private fun validateLease(lease: List<String>, head: Int): List<String> {
        val expected = binding.drop(2).take(9).toMutableList()
        if (head == 2) {
            expected[5] = "2"
            expected[6] = envelopeHash
        }
        requireRecovery(lease.size == 12 && lease.take(9) == expected)
        val owner = UUID.fromString(lease[9])
        requireRecovery(owner.toString() == lease[9] && owner.version() == 4 && owner.variant() == 2 && owner.toString() != binding[12])
        val token = lease[10].toLong()
        requireRecovery(token > binding[13].toLong() && token.toString() == lease[10])
        canonicalInstant(lease[11])
        return lease
    }

    private fun requireLeaseAfter(lease: List<String>, before: List<String>) {
        requireRecovery(lease[10].toLong() >= before[10].toLong())
        if (lease[10] == before[10]) {
            requireRecovery(lease.drop(9) == before.drop(9))
        } else {
            requireRecovery(UUID.fromString(lease[9]) !in historicalOwners())
        }
    }

    private fun canonicalInstant(value: String): Instant = Instant.parse(value).also {
        requireRecovery(it.toString() == value && it.epochSecond in 0..253402300799L)
    }

    private fun finalizationHistory(): List<Pair<CatalogSignerRotationReleaseLeafV1, FinalizationRecord?>> = listOf(
        CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED to completeArm,
        CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME to completeOutcome,
        CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED to projectArm,
        CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME to projectOutcome,
    )

    private fun historicalLeases(): List<List<String>> = listOfNotNull(publicationLease) + finalizationHistory().mapNotNull { it.second?.lease }

    private fun pendingValues(lease: List<String>): List<String> = lease.toMutableList().also {
        it[5] = "2"
        it[6] = envelopeHash
    }

    private fun pendingAcquisitionValues(): Array<String> = pendingValues(original.currentAcquisitionRecordValues().toList()).toTypedArray()

    private fun acknowledgementRecord(version: String): ByteArray = record("publication-acknowledged", envelopeHash, version)
    private fun awaitingRecord(proof: CatalogDualLocationVerifier.Overlap2Readback): ByteArray =
        record("await-replication2", envelopeHash, proof.objectVersion, proof.retainUntilEpochSecond.toString())

    private fun dualRecord(proof: CatalogDualLocationVerifier.Overlap2Readback): ByteArray =
        dualRecord(proof.objectVersion, proof.retainUntilEpochSecond, checkNotNull(proof.primaryEvidenceBytes()), checkNotNull(proof.replicaEvidenceBytes()))

    private fun dualRecord(version: String, retainUntil: Long, primary: ByteArray, replica: ByteArray): ByteArray = record(
        "dual-copy2",
        envelopeHash,
        version,
        retainUntil.toString(),
        Sha256.hex(primary),
        Sha256.hex(replica),
    )

    private fun inputLeaves(): List<Pair<CatalogSignerRotationReleaseLeafV1, ByteArray>> = listOf(
        CatalogSignerRotationReleaseLeafV1.APPROVED_INTENT to inputs.intentBytes(),
        CatalogSignerRotationReleaseLeafV1.TRUST_T0 to inputs.initialBytes(),
        CatalogSignerRotationReleaseLeafV1.TRUST_TN to inputs.currentBytes(),
        CatalogSignerRotationReleaseLeafV1.APPROVAL_INPUTS to inputs.approvalBytes(),
        CatalogSignerRotationReleaseLeafV1.BINDING to inputs.bindingRecord,
    )

    private fun requireReturned(slot: Int, first: ByteArray?): ByteArray {
        val signatureLeaf = if (slot == 0) CatalogSignerRotationReleaseLeafV1.SIGNATURE_ONE else CatalogSignerRotationReleaseLeafV1.SIGNATURE_TWO
        val armed = if (slot == 0) CatalogSignerRotationReleaseLeafV1.SIGN_ONE_ARMED else CatalogSignerRotationReleaseLeafV1.SIGN_TWO_ARMED
        val returned = if (slot == 0) CatalogSignerRotationReleaseLeafV1.SIGN_ONE_RETURNED else CatalogSignerRotationReleaseLeafV1.SIGN_TWO_RETURNED
        val signature = custody.read(signatureLeaf) ?: throw CatalogSignerRotationFreezeExceptionV1(CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        requireRecovery(signature.size == OfflineTrustBundleProtocol.SIGNATURE_BYTES)
        requireExact(armed, record("sign-armed", slot.toString(), first?.let(Sha256::hex) ?: "-"))
        requireExact(returned, record("sign-returned", slot.toString(), Sha256.hex(signature)))
        return signature
    }

    private fun signatureRecord(slot: Int, first: ByteArray, second: ByteArray?, envelope: ByteArray?): ByteArray =
        record("signature-sql", slot.toString(), Sha256.hex(first), second?.let(Sha256::hex) ?: "-", envelope?.let(Sha256::hex) ?: "-")

    private fun record(kind: String, vararg values: String): ByteArray = signerRotationRecord(kind, allocationHash, *values)
    private fun requireExact(leaf: CatalogSignerRotationReleaseLeafV1, bytes: ByteArray) = requireRecovery(custody.read(leaf).contentEquals(bytes))
    private fun requireCreated(leaf: CatalogSignerRotationReleaseLeafV1, bytes: ByteArray) =
        requireRecovery(custody.putIfAbsent(leaf, bytes) === CatalogSignerRotationCustodyObservationV1.CREATED)

    private fun decode(bytes: ByteArray, kind: String, count: Int): List<String> {
        val values = CanonicalJson.json.decodeFromString(ListSerializer(String.serializer()), bytes.toString(Charsets.UTF_8))
        requireRecovery(values.size == count && values[0] == "catalog-signer-rotation-freeze-v1" && values[1] == kind)
        requireRecovery(signerRotationRecord(kind, *values.drop(2).toTypedArray()).contentEquals(bytes))
        return values
    }

    private fun requireRecovery(condition: Boolean) = requireSignerRotation(condition, CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
    override fun toString(): String = "CatalogSignerRotationDeliveryReleaseV1(complete-freeze-prefix,fixed-write-once-delivery,no-Sign,redacted)"

    private class FinalizationRecord(val bytes: ByteArray, val lease: List<String>, val completedAt: Instant?, val projectedAt: Instant?)

    private companion object {
        val COPY_LEAVES = listOf(
            CatalogSignerRotationReleaseLeafV1.PRIMARY_COPY,
            CatalogSignerRotationReleaseLeafV1.REPLICA_COPY,
            CatalogSignerRotationReleaseLeafV1.PUBLICATION_DUAL_COPY,
        )
        val FINALIZATION_LEAVES = listOf(
            CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED,
            CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME,
            CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED,
            CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME,
        )
    }
}
