package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutAcknowledgementV1
import java.time.Instant
import java.util.UUID

/**
 * Append-only TEST delivery custody on the ORIGINAL freeze allocation. There is no Sign method,
 * replacement root, absent-arm recovery PUT, or retroactive original-outcome repair. PROJECT has
 * only two additional bounded leaves on that same root, not a portable continuation capability.
 */
@Suppress("TooManyFunctions", "LargeClass")
internal class CatalogTestRunActivationDeliveryReleaseV1(
    private val original: CatalogTestRunActivationV1,
    private val input: CatalogTestRunActivationFrozenV1,
    private val signed: CatalogTestRunActivationSignedV1,
    private val freeze: CatalogTestRunActivationReleaseV1,
    private val custody: CatalogTestRunActivationReleaseCustodyV1,
    allocation: ByteArray,
    publishing: Boolean,
    private val projecting: Boolean = false,
) {
    init {
        requireConnectionFree()
        original.requireDeliveryReleaseCapture(freeze, custody, input, signed, publishing, projecting)
        freeze.requireDeliveryPrefix()
    }

    private val allocationHash = Sha256.hex(allocation)
    private var publicationArm: Arm? = null
    private var acknowledgement: ByteArray? = null
    private var awaiting: ByteArray? = null
    private var primaryCopy: ByteArray? = null
    private var replicaCopy: ByteArray? = null
    private var dualRecord: ByteArray? = null
    private var completeArm: Arm? = null
    private var completeOutcome: Outcome? = null
    private var pendingOutcome: Outcome? = null
    private var projectArm: ProjectArm? = null
    private var projectOutcome: ProjectOutcome? = null
    private var newProjectArm = false
    private var newPublicationArm = false
    private var publicationArmConsumed = false
    private var dual: CatalogTestRunActivationDeliveryReadbackV1? = null

    init {
        if (!projecting) {
            requireTestActivation(custody.read(CatalogTestRunActivationReleaseLeafV1.PROJECT_ARMED) == null &&
                custody.read(CatalogTestRunActivationReleaseLeafV1.PROJECT_OUTCOME) == null)
        }
        publicationArm = custody.read(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ARMED)?.let {
            val values = decode(it, "publication-armed", 7)
            val lease = lease(values.drop(4))
            requireTestActivation(lease.token > freeze.deliveryLeaseFloor() && lease.owner !in freeze.deliveryHistoricalOwners())
            Arm(it.copyOf(), lease)
        }
        if (publicationArm == null) {
            DELIVERY_LEAVES.forEach { requireTestActivation(custody.read(it) == null) }
        } else {
            acknowledgement = custody.read(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED)?.also {
                version(decode(it, "publication-acknowledged", 5)[4])
            }
            awaiting = custody.read(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_AWAIT_REPLICATION)?.also {
                val values = decode(it, "await-replication", 6)
                version(values[4]); epoch(values[5])
            }
            primaryCopy = custody.read(CatalogTestRunActivationReleaseLeafV1.PRIMARY_COPY)
            replicaCopy = custody.read(CatalogTestRunActivationReleaseLeafV1.REPLICA_COPY)
            dualRecord = custody.read(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_DUAL_COPY)?.also {
                val values = decode(it, "dual-copy", 8)
                version(values[4]); epoch(values[5])
                requireTestActivation(values[6] == Sha256.hex(checkNotNull(primaryCopy)) && values[7] == Sha256.hex(checkNotNull(replicaCopy)))
            }
            requireHistoryAgreement()
            completeArm = custody.read(CatalogTestRunActivationReleaseLeafV1.COMPLETE_ARMED)?.let {
                val values = decode(it, "complete-armed", 8)
                requireTestActivation(values[4] == Sha256.hex(checkNotNull(dualRecord)))
                val lease = lease(values.drop(5))
                requireAfterOrSame(lease, checkNotNull(publicationArm).lease)
                Arm(it.copyOf(), lease)
            }
            completeOutcome = custody.read(CatalogTestRunActivationReleaseLeafV1.COMPLETE_OUTCOME)?.let { outcome(it, "completed-pending") }
            pendingOutcome = custody.read(CatalogTestRunActivationReleaseLeafV1.PENDING_RELOAD_OUTCOME)?.let {
                outcome(it, "pending-reloaded").also { value ->
                    if (!projecting) requireTestActivation(completeOutcome != null)
                    completeOutcome?.let { completed -> requireTestActivation(value.completedAt == completed.completedAt) }
                }
            }
            projectArm = custody.read(CatalogTestRunActivationReleaseLeafV1.PROJECT_ARMED)?.let(::parseProjectArm)
            projectOutcome = custody.read(CatalogTestRunActivationReleaseLeafV1.PROJECT_OUTCOME)?.let(::parseProjectOutcome)
        }
        // A separate recovery entry never interprets missing custody as permission to create a new PUT.
        requireTestActivation(if (publishing) publicationArm == null else publicationArm != null, CatalogTestRunActivationFailureV1.STATE_REFUSED)
    }

    internal fun requireSnapshot(snapshot: CatalogTestRunActivationSnapshotV1) {
        if (projecting) freeze.requireProjectionSnapshot(snapshot) else freeze.requireDeliverySnapshot(snapshot)
        requireTestActivation(snapshot.control.leaseToken >= historicalLeaseFloor())
        val completed = snapshot.completedTail
        if (completed == null) {
            requireTestActivation(!projecting && completeOutcome == null && pendingOutcome == null && projectArm == null && projectOutcome == null)
        } else {
            requireTestActivation(completeArm != null)
            val values = decode(checkNotNull(dualRecord), "dual-copy", 8)
            completed.requireCustody(values[4], epoch(values[5]), checkNotNull(primaryCopy), checkNotNull(replicaCopy))
            completeOutcome?.let { requireTestActivation(completed.completedAt == it.completedAt) }
            pendingOutcome?.let { requireTestActivation(completed.completedAt == it.completedAt) }
            val arm = projectArm
            if (completed.projectedAt == null) {
                requireTestActivation(projectOutcome == null)
                arm?.let {
                    requireTestActivation(completed.completedAt == it.completedAt)
                    snapshot.projectionRows?.let { rows ->
                        requireTestActivation(rows.counters.semanticHash == it.beforeCapacityHash && rows.projectedCapacityHash == it.afterCapacityHash)
                    }
                }
            } else {
                requireTestActivation(projecting && arm != null)
                requireTestActivation(completed.completedAt == checkNotNull(arm).completedAt)
                snapshot.projectionRows?.let { requireTestActivation(it.counters.semanticHash == arm.afterCapacityHash) }
                projectOutcome?.let { requireTestActivation(completed.completedAt == it.completedAt && completed.projectedAt == it.projectedAt) }
            }
        }
    }

    internal fun requireAcquisition(value: CatalogTestRunActivationLeaseV1) {
        freeze.requireAcquisition(value)
        requireTestActivation(value.token > historicalLeaseFloor() && value.owner !in historicalOwners())
        listOfNotNull(publicationArm?.lease, completeArm?.lease, projectArm?.lease).forEach { requireTestActivation(value.expiresAt.isAfter(it.expiresAt)) }
    }

    internal fun requireReadback(proof: CatalogTestRunActivationDeliveryReadbackV1) {
        original.requireDeliveryProof(this, proof)
        if (projecting) requireTestActivation(proof.state === CatalogTestRunActivationDeliveryReadbackV1.State.DUAL_COPY)
        if (proof.state === CatalogTestRunActivationDeliveryReadbackV1.State.UNPUBLISHED) {
            requireTestActivation(acknowledgement == null && awaiting == null && primaryCopy == null && replicaCopy == null && dualRecord == null && completeArm == null)
            return
        }
        val observedVersion = checkNotNull(proof.objectVersion)
        val retainUntil = checkNotNull(proof.retainUntilEpochSecond)
        acknowledgement?.let { requireTestActivation(it.contentEquals(record("publication-acknowledged", observedVersion))) }
        awaiting?.let { requireTestActivation(it.contentEquals(record("await-replication", observedVersion, retainUntil.toString()))) }
        if (proof.state === CatalogTestRunActivationDeliveryReadbackV1.State.AWAIT_REPLICATION) {
            requireTestActivation(primaryCopy == null && replicaCopy == null && dualRecord == null && completeArm == null)
        } else {
            primaryCopy?.let { requireTestActivation(it.contentEquals(proof.primaryEvidenceBytes())) }
            replicaCopy?.let { requireTestActivation(it.contentEquals(proof.replicaEvidenceBytes())) }
            dualRecord?.let { requireTestActivation(it.contentEquals(dualBytes(proof))) }
        }
    }

    internal fun armPublication(proof: CatalogTestRunActivationDeliveryReadbackV1) {
        original.requirePublicationArm(this, proof)
        requireReadback(proof)
        requireTestActivation(publicationArm == null && !newPublicationArm && proof.state === CatalogTestRunActivationDeliveryReadbackV1.State.UNPUBLISHED)
        val current = original.custodyLease()
        requireAcquisition(current)
        val bytes = record("publication-armed", *leaseValues(current))
        requireCreated(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ARMED, bytes)
        publicationArm = Arm(bytes, lease(leaseValues(current).toList()))
        newPublicationArm = true
    }

    internal fun acknowledged(value: CatalogPrimaryPutAcknowledgementV1) {
        original.requirePublicationAcknowledgement(this, value)
        requirePublicationArm()
        requireTestActivation(newPublicationArm && publicationArmConsumed && acknowledgement == null && value.envelopeSha256 == signed.envelopeSha256)
        val bytes = record("publication-acknowledged", version(value.versionId))
        requireCreated(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED, bytes)
        acknowledgement = bytes
    }

    internal fun consumePublicationArm() {
        requireConnectionFree()
        requireTestActivation(newPublicationArm && !publicationArmConsumed && acknowledgement == null)
        publicationArmConsumed = true // This original invocation can never reconstruct its one-use claim.
        requirePublicationArm()
        requireTestActivation(checkNotNull(publicationArm).lease.same(original.custodyLease()))
    }

    internal fun awaitReplication(proof: CatalogTestRunActivationDeliveryReadbackV1) {
        original.requireDeliveryEvidence(this, proof)
        requirePublicationArm()
        requireReadback(proof)
        requireTestActivation(proof.state === CatalogTestRunActivationDeliveryReadbackV1.State.AWAIT_REPLICATION)
        val bytes = record("await-replication", checkNotNull(proof.objectVersion), checkNotNull(proof.retainUntilEpochSecond).toString())
        custody.putIfAbsent(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_AWAIT_REPLICATION, bytes)
        awaiting = bytes
    }

    internal fun preserveDual(proof: CatalogTestRunActivationDeliveryReadbackV1) {
        original.requireDeliveryEvidence(this, proof)
        requirePublicationArm()
        requireReadback(proof)
        requireTestActivation(proof.state === CatalogTestRunActivationDeliveryReadbackV1.State.DUAL_COPY)
        val primary = checkNotNull(proof.primaryEvidenceBytes())
        val replica = checkNotNull(proof.replicaEvidenceBytes())
        val bytes = dualBytes(proof)
        custody.putIfAbsent(CatalogTestRunActivationReleaseLeafV1.PRIMARY_COPY, primary)
        custody.putIfAbsent(CatalogTestRunActivationReleaseLeafV1.REPLICA_COPY, replica)
        custody.putIfAbsent(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_DUAL_COPY, bytes)
        primaryCopy = primary
        replicaCopy = replica
        dualRecord = bytes
        requireDualRecords()
        dual = proof
    }

    internal fun armComplete(proof: CatalogTestRunActivationDeliveryReadbackV1) {
        original.requireCompletionArm(this, proof)
        requireTestActivation(dual === proof)
        requirePublicationArm()
        requireDualRecords()
        val old = completeArm
        if (old == null) {
            requireTestActivation(original.custodySnapshot().completedTail == null)
            val current = original.custodyLease()
            val selected = lease(leaseValues(current).toList())
            requireAfterOrSame(selected, checkNotNull(publicationArm).lease)
            val bytes = record("complete-armed", Sha256.hex(checkNotNull(dualRecord)), *leaseValues(current))
            requireCreated(CatalogTestRunActivationReleaseLeafV1.COMPLETE_ARMED, bytes)
            completeArm = Arm(bytes, selected)
        } else {
            // Original marker stays spent. Fresh exact reconciliation grants this owner's DB-only attempt.
            requireExact(CatalogTestRunActivationReleaseLeafV1.COMPLETE_ARMED, old.bytes)
        }
    }

    internal fun completed(snapshot: CatalogTestRunActivationSnapshotV1) {
        original.requireCompletedDelivery(this, snapshot)
        requireSnapshot(snapshot)
        checkNotNull(snapshot.completedTail).requireExact(checkNotNull(dual))
        requireDualRecords()
        val arm = checkNotNull(completeArm)
        requireExact(CatalogTestRunActivationReleaseLeafV1.COMPLETE_ARMED, arm.bytes)
        if (arm.lease.same(original.custodyLease())) {
            val bytes = outcomeBytes("completed-pending", snapshot, arm)
            requireCreated(CatalogTestRunActivationReleaseLeafV1.COMPLETE_OUTCOME, bytes)
            completeOutcome = outcome(bytes, "completed-pending")
        } // Never fill an older acquisition's missing known-commit/original-release result.
    }

    internal fun pendingReloaded(snapshot: CatalogTestRunActivationSnapshotV1) {
        original.requirePendingReload(this, snapshot)
        requireSnapshot(snapshot)
        checkNotNull(snapshot.completedTail).requireExact(checkNotNull(dual))
        requireDualRecords()
        val arm = checkNotNull(completeArm)
        requireExact(CatalogTestRunActivationReleaseLeafV1.COMPLETE_ARMED, arm.bytes)
        if (arm.lease.same(original.custodyLease())) {
            requireExact(CatalogTestRunActivationReleaseLeafV1.COMPLETE_OUTCOME, checkNotNull(completeOutcome).bytes)
            val bytes = outcomeBytes("pending-reloaded", snapshot, arm)
            requireCreated(CatalogTestRunActivationReleaseLeafV1.PENDING_RELOAD_OUTCOME, bytes)
            pendingOutcome = outcome(bytes, "pending-reloaded")
        }
    }

    /** Eleven bounded strings: allocation/envelope/dual/complete time, exact before+after capacity hashes and original lease. */
    internal fun armProject(proof: CatalogTestRunActivationDeliveryReadbackV1) {
        original.requireProjectArm(this, proof)
        requireTestActivation(projecting && !newProjectArm)
        requireReadback(proof)
        requirePublicationArm()
        requireDualRecords()
        requireExact(CatalogTestRunActivationReleaseLeafV1.COMPLETE_ARMED, checkNotNull(completeArm).bytes)
        val snapshot = original.custodySnapshot()
        requireSnapshot(snapshot)
        val completed = checkNotNull(snapshot.completedTail)
        val rows = checkNotNull(snapshot.projectionRows)
        requireTestActivation(completed.projectedAt == null && rows.projectedAt == null)
        val old = projectArm
        if (old == null) {
            val current = original.custodyLease()
            requireAcquisition(current)
            val bytes = record("project-armed", Sha256.hex(checkNotNull(dualRecord)), completed.completedAt.toString(),
                rows.counters.semanticHash, rows.projectedCapacityHash, *leaseValues(current))
            requireCreated(CatalogTestRunActivationReleaseLeafV1.PROJECT_ARMED, bytes)
            projectArm = parseProjectArm(bytes)
            newProjectArm = true
        } else {
            // Never replace an old arm or rebase its capacity preimage. The fresh grant is DB-only and same-owner.
            requireExact(CatalogTestRunActivationReleaseLeafV1.PROJECT_ARMED, old.bytes)
        }
    }

    /** Only this arm's own known-committed, originally released PROJECT can append its outcome. */
    internal fun projected(snapshot: CatalogTestRunActivationSnapshotV1) {
        original.requireProjected(this, snapshot)
        requireSnapshot(snapshot)
        requireDualRecords()
        val arm = checkNotNull(projectArm)
        requireExact(CatalogTestRunActivationReleaseLeafV1.PROJECT_ARMED, arm.bytes)
        val completed = checkNotNull(snapshot.completedTail)
        requireTestActivation(completed.projectedAt != null)
        if (arm.lease.same(original.custodyLease())) {
            requireTestActivation(newProjectArm && projectOutcome == null)
            val bytes = record("project-outcome", Sha256.hex(arm.bytes), completed.completedAt.toString(), checkNotNull(completed.projectedAt).toString(), *arm.lease.values())
            requireCreated(CatalogTestRunActivationReleaseLeafV1.PROJECT_OUTCOME, bytes)
            projectOutcome = parseProjectOutcome(bytes)
        }
    }

    internal fun projectedReloaded(snapshot: CatalogTestRunActivationSnapshotV1) {
        original.requireProjectedReload(this, snapshot)
        requireSnapshot(snapshot)
        requireDualRecords()
        val arm = checkNotNull(projectArm)
        requireExact(CatalogTestRunActivationReleaseLeafV1.PROJECT_ARMED, arm.bytes)
        if (arm.lease.same(original.custodyLease())) {
            requireTestActivation(newProjectArm)
            requireExact(CatalogTestRunActivationReleaseLeafV1.PROJECT_OUTCOME, checkNotNull(projectOutcome).bytes)
        } // Cold exact replay never fills an old missing outcome, nor claims that old cleanup was repaired.
    }

    private fun parseProjectArm(bytes: ByteArray): ProjectArm {
        requireTestActivation(projecting && bytes.size <= CatalogTestRunActivationReleaseLeafV1.PROJECT_ARMED.maximumBytes)
        val values = decode(bytes, "project-armed", 11)
        requireTestActivation(values[4] == Sha256.hex(checkNotNull(dualRecord)))
        val completedAt = canonicalInstant(values[5])
        completeOutcome?.let { requireTestActivation(it.completedAt == completedAt) }
        pendingOutcome?.let { requireTestActivation(it.completedAt == completedAt) }
        requireTestActivation(OfflineBootstrapGrammar.sha256(values[6]) && OfflineBootstrapGrammar.sha256(values[7]) && values[6] != values[7])
        val selected = lease(values.drop(8))
        requireTestActivation(selected.token > checkNotNull(completeArm).lease.token && selected.owner !in historicalOwners())
        listOfNotNull(publicationArm?.lease, completeArm?.lease).forEach { requireTestActivation(selected.expiresAt.isAfter(it.expiresAt)) }
        return ProjectArm(bytes.copyOf(), selected, completedAt, values[6], values[7])
    }

    /** Ten strings, including the exact arm hash; no missing COMPLETE/PENDING receipt is synthesized. */
    private fun parseProjectOutcome(bytes: ByteArray): ProjectOutcome {
        requireTestActivation(projecting && bytes.size <= CatalogTestRunActivationReleaseLeafV1.PROJECT_OUTCOME.maximumBytes)
        val values = decode(bytes, "project-outcome", 10)
        val arm = checkNotNull(projectArm)
        val completedAt = canonicalInstant(values[5])
        val projectedAt = canonicalInstant(values[6])
        requireTestActivation(values[4] == Sha256.hex(arm.bytes) && lease(values.drop(7)) == arm.lease && completedAt == arm.completedAt && !projectedAt.isBefore(completedAt))
        return ProjectOutcome(bytes.copyOf(), completedAt, projectedAt)
    }

    private fun requirePublicationArm() = requireExact(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ARMED, checkNotNull(publicationArm).bytes)

    private fun requireDualRecords() {
        requireExact(CatalogTestRunActivationReleaseLeafV1.PRIMARY_COPY, checkNotNull(primaryCopy))
        requireExact(CatalogTestRunActivationReleaseLeafV1.REPLICA_COPY, checkNotNull(replicaCopy))
        requireExact(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_DUAL_COPY, checkNotNull(dualRecord))
    }

    private fun requireHistoryAgreement() {
        val versions = listOfNotNull(acknowledgement?.let { decode(it, "publication-acknowledged", 5)[4] },
            awaiting?.let { decode(it, "await-replication", 6)[4] }, dualRecord?.let { decode(it, "dual-copy", 8)[4] })
        requireTestActivation(versions.distinct().size <= 1)
        if (awaiting != null && dualRecord != null) {
            requireTestActivation(decode(checkNotNull(awaiting), "await-replication", 6)[5] == decode(checkNotNull(dualRecord), "dual-copy", 8)[5])
        }
    }

    private fun historicalLeaseFloor(): Long = maxOf(freeze.deliveryLeaseFloor(), publicationArm?.lease?.token ?: 0L, completeArm?.lease?.token ?: 0L, projectArm?.lease?.token ?: 0L)
    private fun historicalOwners(): Set<UUID> = freeze.deliveryHistoricalOwners() + listOfNotNull(publicationArm?.lease?.owner, completeArm?.lease?.owner, projectArm?.lease?.owner)
    private fun requireAfterOrSame(value: Lease, before: Lease) {
        requireTestActivation(value.token >= before.token)
        if (value.token == before.token) requireTestActivation(value == before)
        else requireTestActivation(value.owner !in freeze.deliveryHistoricalOwners() && value.owner != before.owner && value.expiresAt.isAfter(before.expiresAt))
    }

    private fun dualBytes(proof: CatalogTestRunActivationDeliveryReadbackV1): ByteArray = record("dual-copy", checkNotNull(proof.objectVersion),
        checkNotNull(proof.retainUntilEpochSecond).toString(), Sha256.hex(checkNotNull(proof.primaryEvidenceBytes())), Sha256.hex(checkNotNull(proof.replicaEvidenceBytes())))

    private fun outcomeBytes(kind: String, snapshot: CatalogTestRunActivationSnapshotV1, arm: Arm): ByteArray = record(kind,
        Sha256.hex(checkNotNull(dualRecord)), checkNotNull(snapshot.completedTail).completedAt.toString(), *arm.lease.values())

    private fun outcome(bytes: ByteArray, kind: String): Outcome {
        val values = decode(bytes, kind, 9)
        requireTestActivation(values[4] == Sha256.hex(checkNotNull(dualRecord)) && lease(values.drop(6)) == checkNotNull(completeArm).lease)
        return Outcome(bytes.copyOf(), canonicalInstant(values[5]))
    }

    private fun record(kind: String, vararg values: String): ByteArray = encode(kind, allocationHash, signed.envelopeSha256, *values)
    private fun decode(bytes: ByteArray, kind: String, count: Int): List<String> {
        requireTestActivation(bytes.size in 1..4096)
        val values = CanonicalJson.json.decodeFromString(ListSerializer(String.serializer()), bytes.toString(Charsets.UTF_8))
        requireTestActivation(values.size == count && values[0] == DOMAIN && values[1] == kind && values[2] == allocationHash && values[3] == signed.envelopeSha256)
        requireTestActivation(encode(kind, *values.drop(2).toTypedArray()).contentEquals(bytes))
        return values
    }

    private fun requireExact(leaf: CatalogTestRunActivationReleaseLeafV1, bytes: ByteArray) = requireTestActivation(custody.read(leaf).contentEquals(bytes))
    private fun requireCreated(leaf: CatalogTestRunActivationReleaseLeafV1, bytes: ByteArray) =
        requireTestActivation(custody.putIfAbsent(leaf, bytes) === CatalogTestRunActivationCustodyObservationV1.CREATED)

    private class Arm(val bytes: ByteArray, val lease: Lease)
    private class Outcome(val bytes: ByteArray, val completedAt: Instant)
    private class ProjectArm(val bytes: ByteArray, val lease: Lease, val completedAt: Instant, val beforeCapacityHash: String, val afterCapacityHash: String)
    private class ProjectOutcome(val bytes: ByteArray, val completedAt: Instant, val projectedAt: Instant)
    private data class Lease(val owner: UUID, val token: Long, val expiresAt: Instant) {
        fun same(value: CatalogTestRunActivationLeaseV1): Boolean = owner == value.owner && token == value.token && expiresAt == value.expiresAt
        fun values(): Array<String> = arrayOf(owner.toString(), token.toString(), expiresAt.toString())
    }

    override fun toString(): String = "CatalogTestRunActivationDeliveryReleaseV1(original-freeze-custody,closed-first-PROJECT,no-issuer)"

    companion object {
        private const val DOMAIN = "catalog-test-run-activation-freeze-v1"
        private val DELIVERY_LEAVES = CatalogTestRunActivationReleaseLeafV1.entries.drop(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ARMED.ordinal)
        private fun encode(kind: String, vararg values: String): ByteArray = CanonicalJson.canonicalize(
            ListSerializer(String.serializer()), listOf(DOMAIN, kind) + values.toList(),
        ).toByteArray(Charsets.UTF_8)
        private fun version(value: String): String = value.also { requireTestActivation(CatalogReadbackProtocol.validVersion(it)) }
        private fun epoch(value: String): Long = value.toLong().also { requireTestActivation(it in 0L..253402300799L && it.toString() == value) }
        private fun canonicalInstant(value: String): Instant = Instant.parse(value).also {
            requireTestActivation(it.epochSecond in 0L..253402300799L && it.toString() == value)
        }
        private fun lease(values: List<String>): Lease {
            requireTestActivation(values.size == 3)
            val owner = UUID.fromString(values[0])
            val token = values[1].toLong()
            requireTestActivation(owner.toString() == values[0] && owner.version() == 4 && owner.variant() == 2 && token > 0L && token.toString() == values[1])
            return Lease(owner, token, canonicalInstant(values[2]))
        }
        private fun leaseValues(value: CatalogTestRunActivationLeaseV1): Array<String> = arrayOf(value.owner.toString(), value.token.toString(), value.expiresAt.toString())
    }
}
