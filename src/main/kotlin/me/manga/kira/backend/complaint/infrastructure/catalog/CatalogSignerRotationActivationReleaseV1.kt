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
import java.util.HexFormat
import java.util.UUID

/** Existing unsigned leaf/record schema in the protected fixed3 sibling; never an approval or old-lease receipt repair. */
@Suppress("TooManyFunctions", "LargeClass")
internal class CatalogSignerRotationActivationReleaseV1(
    private val original: CatalogSignerRotationActivationV1,
    private val inputs: CatalogSignerRotationActivationInputsV1,
    private val custody: CatalogSignerRotationReleaseCustodyV1,
    allocation: ByteArray,
    private val bindingBytes: ByteArray,
    created: Boolean,
) {
    private val allocationHash = Sha256.hex(allocation)
    private val binding = decode(bindingBytes, "binding", 14)
    private var frozen: CatalogFrozenMutation? = null
    private val envelope: ByteArray get() = checkNotNull(frozen?.signedEnvelopeBytes)
    private val envelopeHash: String get() = checkNotNull(frozen?.signedEnvelopeSha256)
    private var publicationArm: ByteArray? = null
    private var publicationLease: List<String>? = null
    private var completeArm: FinalizationRecord? = null
    private var completeOutcome: FinalizationRecord? = null
    private var projectArm: FinalizationRecord? = null
    private var projectOutcome: FinalizationRecord? = null
    private var newArm = false
    private var putSpent = false
    private var dual: CatalogDualLocationVerifier.Activation3Readback? = null

    init {
        requireConnectionFree()
        original.requireRunning()
        requireRecovery(inputs.allocation(bindingBytes).contentEquals(allocation))
        val desired = original.process.desiredSettings()
        val expected = listOf(desired.desiredGeneration.toString(), HexFormat.of().formatHex(original.process.configurationHashBytes()),
            desired.databaseIdentity.toString(), desired.restoreIdentity.toString(),
            original.process.consumers.journalConfiguration.declaration().writer.generationId,
            "2", inputs.manifest.previousEnvelopeSha256, Sha256.hex(inputs.currentBytes()), inputs.manifest.catalogWriterGenerationId,
            HexFormat.of().formatHex(original.process.consumers.capacityPolicy.digestBytes()))
        requireRecovery(binding.drop(2).take(10) == expected)
        val owner = UUID.fromString(binding[12])
        requireRecovery(owner.toString() == binding[12] && owner.version() == 4 && owner.variant() == 2)
        requireRecovery(binding[13].toLong() > 0 && binding[13].toLong().toString() == binding[13])
        if (created) inputLeaves().forEach { (leaf, bytes) -> requireCreated(leaf, bytes) }
        else inputLeaves().forEach { (leaf, bytes) -> requireExact(leaf, bytes) }
        if (!created) {
            requireExact(CatalogSignerRotationReleaseLeafV1.PREPARE_ARMED, record("prepare-armed"))
            custody.read(CatalogSignerRotationReleaseLeafV1.PREPARED)?.let { requireRecovery(it.contentEquals(record("prepared-unsigned-head2"))) }
            val returned = custody.read(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_RETURNED)
            val signature = custody.read(CatalogSignerRotationReleaseLeafV1.SIGNATURE_ONE)
            requireRecovery((returned == null) == (signature == null))
            val armed = custody.read(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_ARMED)
            if (signature != null) {
                requireExact(CatalogSignerRotationReleaseLeafV1.PREPARED, record("prepared-unsigned-head2"))
                requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_ARMED, record("sign-armed", "0", "-"))
                requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_RETURNED, record("sign-returned", "0", Sha256.hex(signature)))
                frozen = inputs.signed(signature)
                val sql = signatureRecord(signature)
                custody.read(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_ARMED)?.let { requireRecovery(it.contentEquals(sql)) }
                custody.read(CatalogSignerRotationReleaseLeafV1.ENVELOPE)?.let { requireRecovery(it.contentEquals(envelope)) }
                custody.read(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_PERSISTED)?.let {
                    requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_ARMED, sql)
                    requireExact(CatalogSignerRotationReleaseLeafV1.ENVELOPE, envelope)
                    requireRecovery(it.contentEquals(sql))
                }
                custody.read(CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME)?.let {
                    requireFrozenPrefix()
                    requireRecovery(it.contentEquals(record("signed-prepared3-head2", envelopeHash)))
                }
            } else {
                requireRecovery(armed == null) // Lost/unknown original Sign never gets another randomized signature.
                (SIGNATURE_TAIL + CatalogSignerRotationReleaseLeafV1.deliveryLeaves()).forEach { requireRecovery(custody.read(it) == null) }
            }
        }
        publicationArm = custody.read(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED)
        publicationArm?.let {
            requireFrozenPrefix()
            requireExact(CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME, record("signed-prepared3-head2", envelopeHash))
            publicationLease = validatePublicationArm(it)
            readFinalizationHistory()
        } ?: CatalogSignerRotationReleaseLeafV1.deliveryLeaves().forEach { requireRecovery(custody.read(it) == null) }
    }

    internal fun returnedMutation(): CatalogFrozenMutation? = frozen
    internal fun expectedFrozenMutation(): CatalogFrozenMutation = frozen ?: inputs.unsigned()
    internal fun envelopeBytes(): ByteArray = envelope.copyOf()

    internal fun armPrepare() {
        original.requirePrepareArm(this)
        requireCreated(CatalogSignerRotationReleaseLeafV1.PREPARE_ARMED, record("prepare-armed"))
    }

    internal fun prepared(value: CatalogSignerRotationActivationObservationV1) {
        original.requirePrepared(this, value)
        requireRecovery(sameSignerRotationMutation(checkNotNull(value.mutation), inputs.unsigned()))
        requireExact(CatalogSignerRotationReleaseLeafV1.PREPARE_ARMED, record("prepare-armed"))
        requireCreated(CatalogSignerRotationReleaseLeafV1.PREPARED, record("prepared-unsigned-head2"))
    }

    internal fun requireUnattemptedSignature(value: CatalogSignerRotationActivationObservationV1) {
        requireRecovery(sameSignerRotationMutation(checkNotNull(value.mutation), inputs.unsigned()))
        requireExact(CatalogSignerRotationReleaseLeafV1.PREPARE_ARMED, record("prepare-armed"))
        requireExact(CatalogSignerRotationReleaseLeafV1.PREPARED, record("prepared-unsigned-head2"))
        (listOf(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_ARMED, CatalogSignerRotationReleaseLeafV1.SIGN_ONE_RETURNED,
            CatalogSignerRotationReleaseLeafV1.SIGNATURE_ONE) + SIGNATURE_TAIL + CatalogSignerRotationReleaseLeafV1.deliveryLeaves())
            .forEach { requireRecovery(custody.read(it) == null) }
    }

    internal fun armSignature(value: CatalogSignerRotationActivationObservationV1) {
        original.requireSignArm(this, value)
        requireUnattemptedSignature(value)
        requireCreated(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_ARMED, record("sign-armed", "0", "-"))
    }

    internal fun preserveSignature(signature: ByteArray): CatalogFrozenMutation {
        original.requireSignatureReturn(this)
        requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_ARMED, record("sign-armed", "0", "-"))
        val value = inputs.signed(signature)
        requireCreated(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_RETURNED, record("sign-returned", "0", Sha256.hex(signature)))
        requireCreated(CatalogSignerRotationReleaseLeafV1.SIGNATURE_ONE, signature)
        frozen = value
        return value
    }

    internal fun armSignaturePersistence(value: CatalogFrozenMutation) {
        original.requireSignatureSqlArm(this, value)
        requireRecovery(sameSignerRotationMutation(value, checkNotNull(frozen)))
        val signature = checkNotNull(value.signatureSlots.single().signatureBytes)
        if (original.isRecovery()) {
            requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_ARMED, signatureRecord(signature))
            requireExact(CatalogSignerRotationReleaseLeafV1.ENVELOPE, envelope)
        } else {
            requireCreated(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_ARMED, signatureRecord(signature))
            requireCreated(CatalogSignerRotationReleaseLeafV1.ENVELOPE, envelope)
        }
    }

    internal fun signaturePersisted(value: CatalogSignerRotationActivationObservationV1) {
        original.requireSignaturePersisted(this, value)
        requireRecovery(sameSignerRotationMutation(checkNotNull(value.mutation), checkNotNull(frozen)))
        val sqlRecord = signatureRecord(checkNotNull(frozen?.signatureSlots?.single()?.signatureBytes))
        requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_ARMED, sqlRecord)
        requireExact(CatalogSignerRotationReleaseLeafV1.ENVELOPE, envelope)
        custody.putIfAbsent(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_PERSISTED, sqlRecord)
        requireFrozenPrefix()
        custody.putIfAbsent(CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME, record("signed-prepared3-head2", envelopeHash))
    }

    private fun requireFrozenPrefix() {
        val signature = checkNotNull(frozen?.signatureSlots?.single()?.signatureBytes)
        requireExact(CatalogSignerRotationReleaseLeafV1.PREPARED, record("prepared-unsigned-head2"))
        requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_RETURNED, record("sign-returned", "0", Sha256.hex(signature)))
        requireExact(CatalogSignerRotationReleaseLeafV1.SIGNATURE_ONE, signature)
        requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_ARMED, signatureRecord(signature))
        requireExact(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_PERSISTED, signatureRecord(signature))
        requireExact(CatalogSignerRotationReleaseLeafV1.ENVELOPE, envelope)
    }

    private fun signatureRecord(signature: ByteArray): ByteArray = record("signature-sql", "0", Sha256.hex(signature), "-", envelopeHash)

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
            is LocalCatalogSnapshot.Accepted -> requireRecovery(local.head.generation == 3L && completeArm != null && projectArm != null)
            else -> requireRecovery(false)
        }
    }

    /** Read-only historical comparison. An absent outcome never becomes an observed-success record. */
    internal fun requireObservedHistory(value: CatalogSignerRotationActivationObservationV1) {
        requireConnectionFree()
        original.requireRunning()
        finalizationHistory().forEach { (leaf, saved) -> requireRecovery(custody.read(leaf).contentEquals(saved?.bytes)) }
        val actual = checkNotNull(value.mutation)
        requireRecovery(actual.signedEnvelopeBytes == null || sameSignerRotationMutation(actual, checkNotNull(frozen)))
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
        requireFrozenPrefix()
        requireExact(CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME, record("signed-prepared3-head2", envelopeHash))
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

    internal fun awaitReplication(proof: CatalogDualLocationVerifier.Activation3Readback) {
        original.requireDeliveryProof(this, proof)
        requireArmed()
        requireRecovery(proof.state === CatalogDualLocationVerifier.Activation3Readback.State.PREPARED_AWAIT_REPLICATION)
        requireRecovery(proof.primaryEvidenceBytes() == null && proof.replicaEvidenceBytes() == null && proof.commonHeadEvidence() == null)
        requireFrozenProof(proof)
        requireHistoryMatches(proof)
        requireRecovery(custody.read(CatalogSignerRotationReleaseLeafV1.PRIMARY_COPY) == null)
        requireRecovery(custody.read(CatalogSignerRotationReleaseLeafV1.REPLICA_COPY) == null)
        requireRecovery(custody.read(CatalogSignerRotationReleaseLeafV1.PUBLICATION_DUAL_COPY) == null)
        custody.putIfAbsent(CatalogSignerRotationReleaseLeafV1.PUBLICATION_AWAIT_REPLICATION, awaitingRecord(proof))
    }

    internal fun preserveDual(proof: CatalogDualLocationVerifier.Activation3Readback) {
        original.requireDeliveryProof(this, proof)
        requireArmed()
        requireRecovery(proof.state === CatalogDualLocationVerifier.Activation3Readback.State.PREPARED_DUAL_COPY)
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
            completeArm = finalizationRecord(bytes, "complete-armed", 0, 2).also { requireLeaseAfter(it.lease, checkNotNull(publicationLease)) }
        } else {
            // The old arm remains spent and immutable. Only the new owner's released reconciliation grants this DB-only retry.
            requireRecovery(original.isRecovery() && completeOutcome == null && projectArm == null && projectOutcome == null)
            requireExact(CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED, checkNotNull(completeArm).bytes)
        }
    }

    internal fun completed(value: CatalogSignerRotationActivationObservationV1) {
        original.requireCompletedDelivery(this, value)
        requireRecovery(checkNotNull(value.mutation).signedEnvelopeBytes.contentEquals(envelope) && value.projectedAt == null)
        requireObservedHistory(value)
        val armed = checkNotNull(completeArm)
        requireExact(CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED, armed.bytes)
        if (armed.lease == original.currentAcquisitionRecordValues().toList()) {
            val bytes = record("completed3-pending3", envelopeHash, checkNotNull(value.completedAt).toString(), *pendingAcquisitionValues())
            requireCreated(CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME, bytes)
            completeOutcome = finalizationRecord(bytes, "completed3-pending3", 1, 3)
        } // A new lease must NOT fill an older arm's missing outcome, even after this new owner's known successful retry.
    }

    internal fun armProject(value: CatalogSignerRotationActivationObservationV1) {
        original.requireProjectionArm(this, value)
        requireObservedHistory(value)
        requireRecovery(value.completedAt != null && value.projectedAt == null && projectOutcome == null)
        if (projectArm == null) {
            val bytes = record("project-armed", envelopeHash, value.completedAt.toString(), *pendingAcquisitionValues())
            requireCreated(CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED, bytes)
            projectArm = finalizationRecord(bytes, "project-armed", 1, 3).also {
                requireLeaseAfter(it.lease, completeOutcome?.lease ?: pendingValues(checkNotNull(completeArm).lease))
            }
        } else {
            requireRecovery(original.isRecovery())
            requireExact(CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED, checkNotNull(projectArm).bytes)
        }
    }

    internal fun projected(value: CatalogSignerRotationActivationObservationV1) {
        original.requireProjectedDelivery(this, value)
        requireRecovery(checkNotNull(value.mutation).signedEnvelopeBytes.contentEquals(envelope))
        requireObservedHistory(value)
        val armed = checkNotNull(projectArm)
        requireExact(CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED, armed.bytes)
        if (armed.lease == pendingAcquisitionValues().toList()) {
            val bytes = record(
                "projected3",
                envelopeHash,
                value.completedAt.toString(),
                checkNotNull(value.projectedAt).toString(),
                *pendingAcquisitionValues(),
            )
            requireCreated(CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME, bytes)
            projectOutcome = finalizationRecord(bytes, "projected3", 2, 3)
        } // Otherwise the old arm/outcome gap remains historical, not a claim about the old transaction.
    }

    internal fun requireHistoricalReadback(proof: CatalogDualLocationVerifier.Activation3Readback) {
        requireConnectionFree()
        original.requireRunning()
        if (proof.state === CatalogDualLocationVerifier.Activation3Readback.State.HEAD2 ||
            proof.state === CatalogDualLocationVerifier.Activation3Readback.State.PREPARED_UNSIGNED) {
            requireRecovery(publicationArm == null && completeArm == null && projectArm == null)
            COPY_LEAVES.forEach { requireRecovery(custody.read(it) == null) }
            return
        }
        requireFrozenProof(proof)
        requireHistoryMatches(proof)
        when (proof.state) {
            CatalogDualLocationVerifier.Activation3Readback.State.PREPARED_DUAL_COPY,
            CatalogDualLocationVerifier.Activation3Readback.State.PROJECTION_PENDING_DUAL_COPY,
            CatalogDualLocationVerifier.Activation3Readback.State.PROJECTED3,
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

    private fun requireFrozenProof(proof: CatalogDualLocationVerifier.Activation3Readback) = requireRecovery(
        proof.operationToken == inputs.manifest.operationToken && proof.frozenEnvelopeBytes().contentEquals(envelope) &&
            (proof.state === CatalogDualLocationVerifier.Activation3Readback.State.PREPARED_UNPUBLISHED ||
                (proof.observedTail.generation == 3L && proof.observedTail.envelopeSha256 == envelopeHash)),
    )

    private fun requireHistoryMatches(proof: CatalogDualLocationVerifier.Activation3Readback) =
        requireHistoryMatches(proof.objectVersion, proof.retainUntilEpochSecond)

    private fun requireHistoryMatches(version: String, retainUntil: Long) {
        custody.read(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED)?.let {
            requireRecovery(it.contentEquals(acknowledgementRecord(version)))
        }
        custody.read(CatalogSignerRotationReleaseLeafV1.PUBLICATION_AWAIT_REPLICATION)?.let {
            requireRecovery(it.contentEquals(record("await-replication3", envelopeHash, version, retainUntil.toString())))
        }
    }

    private fun requireArmed() {
        original.requireRunning()
        requireExact(CatalogSignerRotationReleaseLeafV1.PUBLICATION_ARMED, checkNotNull(publicationArm))
    }

    private fun validatePublicationArm(bytes: ByteArray): List<String> {
        val values = decode(bytes, "publication-armed", 16)
        requireRecovery(values[2] == allocationHash && values[3] == envelopeHash)
        return validateLease(values.drop(4), 2)
    }

    private fun readFinalizationHistory() {
        completeArm = custody.read(CatalogSignerRotationReleaseLeafV1.COMPLETE_ARMED)?.let {
            finalizationRecord(it, "complete-armed", 0, 2).also { saved -> requireLeaseAfter(saved.lease, checkNotNull(publicationLease)) }
        }
        completeOutcome = custody.read(CatalogSignerRotationReleaseLeafV1.COMPLETE_OUTCOME)?.let {
            finalizationRecord(it, "completed3-pending3", 1, 3).also { saved ->
                requireRecovery(saved.lease == pendingValues(checkNotNull(completeArm).lease))
            }
        }
        projectArm = custody.read(CatalogSignerRotationReleaseLeafV1.PROJECT_ARMED)?.let {
            finalizationRecord(it, "project-armed", 1, 3).also { saved ->
                requireLeaseAfter(saved.lease, completeOutcome?.lease ?: pendingValues(checkNotNull(completeArm).lease))
                completeOutcome?.let { completed -> requireRecovery(saved.completedAt == completed.completedAt) }
            }
        }
        projectOutcome = custody.read(CatalogSignerRotationReleaseLeafV1.PROJECT_OUTCOME)?.let {
            finalizationRecord(it, "projected3", 2, 3).also { saved ->
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
        if (head == 3) {
            expected[5] = "3"
            expected[6] = envelopeHash
        }
        requireRecovery(lease.size == 12 && lease.take(9) == expected)
        val owner = UUID.fromString(lease[9])
        requireRecovery(owner.toString() == lease[9] && owner.version() == 4 && owner.variant() == 2)
        val token = lease[10].toLong()
        requireRecovery(token >= binding[13].toLong() && token.toString() == lease[10])
        requireRecovery(if (token == binding[13].toLong()) owner.toString() == binding[12] else owner.toString() != binding[12])
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
        it[5] = "3"
        it[6] = envelopeHash
    }

    private fun pendingAcquisitionValues(): Array<String> = pendingValues(original.currentAcquisitionRecordValues().toList()).toTypedArray()

    private fun acknowledgementRecord(version: String): ByteArray = record("publication-acknowledged", envelopeHash, version)
    private fun awaitingRecord(proof: CatalogDualLocationVerifier.Activation3Readback): ByteArray =
        record("await-replication3", envelopeHash, proof.objectVersion, proof.retainUntilEpochSecond.toString())

    private fun dualRecord(proof: CatalogDualLocationVerifier.Activation3Readback): ByteArray =
        dualRecord(proof.objectVersion, proof.retainUntilEpochSecond, checkNotNull(proof.primaryEvidenceBytes()), checkNotNull(proof.replicaEvidenceBytes()))

    private fun dualRecord(version: String, retainUntil: Long, primary: ByteArray, replica: ByteArray): ByteArray = record(
        "dual-copy3",
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
        CatalogSignerRotationReleaseLeafV1.BINDING to bindingBytes,
    )

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
    override fun toString(): String = "CatalogSignerRotationActivationReleaseV1(complete-freeze-prefix,fixed-write-once-delivery,no-Sign,redacted)"

    private class FinalizationRecord(val bytes: ByteArray, val lease: List<String>, val completedAt: Instant?, val projectedAt: Instant?)

    private companion object {
        val SIGNATURE_TAIL = listOf(CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_ARMED, CatalogSignerRotationReleaseLeafV1.SIGN_ONE_SQL_PERSISTED,
            CatalogSignerRotationReleaseLeafV1.ENVELOPE, CatalogSignerRotationReleaseLeafV1.FREEZE_OUTCOME)
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
