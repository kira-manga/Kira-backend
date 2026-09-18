package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutAcknowledgementV1
import java.time.Instant
import java.util.UUID

/**
 * Read the COMPLETE original freeze prefix, then append only fixed delivery/finalization records.
 * No author or Sign mutator is reachable from this type. The original discovery custody remains held
 * by the concrete delivery owner through separate PROJECT and its actual cleanup.
 */
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
    private var newArm = false
    private var putSpent = false
    private var dual: CatalogDualLocationVerifier.Overlap2Readback? = null

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
        requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_ARMED, signatureRecord(0, first, null, null))
        requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_PERSISTED, signatureRecord(0, first, null, null))
        val finalSignature = signatureRecord(1, first, second, envelope)
        requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_ARMED, finalSignature)
        requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_TWO_SQL_PERSISTED, finalSignature)
        requireExact(CatalogSignerRotationReleaseLeafV1.ENVELOPE, envelope)
        requireExact(CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME, record("signed-prepared2-head1", envelopeHash))
        // Cold pending/unknown-COMPLETE/PROJECT resolution is deliberately not implemented in this slice.
        FINALIZATION_LEAVES.forEach { requireRecovery(custody.read(it) == null) }
        publicationArm = custody.read(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED)
        publicationArm?.let { publicationLease = validatePublicationArm(it) }
        if (publicationArm == null) {
            CatalogSignerRotationReleaseLeafV1.deliveryLeaves().forEach { requireRecovery(custody.read(it) == null) }
        }
    }

    internal fun signatures(): List<ByteArray> = listOf(first.copyOf(), second.copyOf())
    internal fun envelopeBytes(): ByteArray = envelope.copyOf()
    internal fun isArmed(): Boolean {
        original.requireRunning()
        return publicationArm != null
    }

    /** Stronger historical floor from the actual earlier publication acquisition, never current leadership. */
    internal fun historicalLeaseFloor(): Long = maxOf(binding[13].toLong(), publicationLease?.get(10)?.toLong() ?: 0L)

    internal fun historicalOwners(): Set<UUID> = setOfNotNull(UUID.fromString(binding[12]), publicationLease?.get(9)?.let(UUID::fromString))

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
        requireCreated(
            CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED,
            record("complete-armed", envelopeHash, *original.currentAcquisitionRecordValues()),
        )
    }

    internal fun completed(value: CatalogSignerRotationFinalizationObservationV1) {
        original.requireCompletedDelivery(this, value)
        requireRecovery(value.mutation.signedEnvelopeBytes.contentEquals(envelope) && value.projectedAt == null)
        requireExact(
            CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED,
            record("complete-armed", envelopeHash, *original.currentAcquisitionRecordValues()),
        )
        requireCreated(
            CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME,
            record("completed2-pending2", envelopeHash, checkNotNull(value.completedAt).toString(), *pendingAcquisitionValues()),
        )
    }

    internal fun armProject(value: CatalogSignerRotationFinalizationObservationV1) {
        original.requireProjectionArm(this, value)
        requireExact(
            CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME,
            record("completed2-pending2", envelopeHash, checkNotNull(value.completedAt).toString(), *pendingAcquisitionValues()),
        )
        requireCreated(
            CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED,
            record("project-armed", envelopeHash, value.completedAt.toString(), *pendingAcquisitionValues()),
        )
    }

    internal fun projected(value: CatalogSignerRotationFinalizationObservationV1) {
        original.requireProjectedDelivery(this, value)
        requireRecovery(value.mutation.signedEnvelopeBytes.contentEquals(envelope))
        requireExact(
            CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED,
            record("project-armed", envelopeHash, checkNotNull(value.completedAt).toString(), *pendingAcquisitionValues()),
        )
        requireCreated(
            CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME,
            record(
                "projected2",
                envelopeHash,
                value.completedAt.toString(),
                checkNotNull(value.projectedAt).toString(),
                *pendingAcquisitionValues(),
            ),
        )
    }

    private fun requireFrozenProof(proof: CatalogDualLocationVerifier.Overlap2Readback) = requireRecovery(
        proof.operationToken == inputs.manifest.operationToken && proof.frozenEnvelopeBytes().contentEquals(envelope) &&
            proof.observedTail.generation == 2L && proof.observedTail.envelopeSha256 == envelopeHash,
    )

    private fun requireHistoryMatches(proof: CatalogDualLocationVerifier.Overlap2Readback) {
        custody.read(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED)?.let {
            requireRecovery(it.contentEquals(acknowledgementRecord(proof.objectVersion)))
        }
        custody.read(CatalogSignerRotationReleaseLeafV1.PUBLICATION_AWAIT_REPLICATION)?.let { requireRecovery(it.contentEquals(awaitingRecord(proof))) }
    }

    private fun requireArmed() {
        original.requireRunning()
        requireExact(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED, checkNotNull(publicationArm))
    }

    private fun validatePublicationArm(bytes: ByteArray): List<String> {
        val values = decode(bytes, "publication-armed", 16)
        requireRecovery(values[2] == allocationHash && values[3] == envelopeHash)
        val lease = values.drop(4)
        requireRecovery(lease.take(9) == binding.drop(2).take(9)) // Same full B1; only actual acquisition identity/time changed.
        val owner = UUID.fromString(lease[9])
        requireRecovery(owner.toString() == lease[9] && owner.version() == 4 && owner.variant() == 2 && owner.toString() != binding[12])
        val token = lease[10].toLong()
        requireRecovery(token > binding[13].toLong() && token.toString() == lease[10])
        val expiry = Instant.parse(lease[11])
        requireRecovery(expiry.toString() == lease[11] && expiry.epochSecond in 0..253402300799L)
        return lease
    }

    private fun pendingAcquisitionValues(): Array<String> = original.currentAcquisitionRecordValues().also {
        it[5] = "2"
        it[6] = envelopeHash
    }

    private fun acknowledgementRecord(version: String): ByteArray = record("publication-acknowledged", envelopeHash, version)
    private fun awaitingRecord(proof: CatalogDualLocationVerifier.Overlap2Readback): ByteArray =
        record("await-replication2", envelopeHash, proof.objectVersion, proof.retainUntilEpochSecond.toString())

    private fun dualRecord(proof: CatalogDualLocationVerifier.Overlap2Readback): ByteArray = record(
        "dual-copy2",
        envelopeHash,
        proof.objectVersion,
        proof.retainUntilEpochSecond.toString(),
        Sha256.hex(checkNotNull(proof.primaryEvidenceBytes())),
        Sha256.hex(checkNotNull(proof.replicaEvidenceBytes())),
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

    private companion object {
        val FINALIZATION_LEAVES = listOf(
            CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED,
            CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME,
            CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED,
            CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME,
        )
    }
}
