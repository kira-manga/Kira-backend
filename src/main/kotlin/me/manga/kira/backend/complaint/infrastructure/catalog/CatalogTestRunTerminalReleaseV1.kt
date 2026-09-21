package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityBalance
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogPrimaryPutAcknowledgementV1
import java.time.Instant
import java.util.UUID

/**
 * Terminal-only interpretation of the existing secure file mechanism, on its separate provisioned
 * root/lock. Markers are durable arm history, NOT denial, raw readback, database commit or cleanup
 * evidence. Only an original-produced fresh arm authorizes one irreversible native dispatch.
 */
internal class CatalogTestRunTerminalReleaseV1 private constructor(
    private val original: CatalogTestRunTerminalV1,
    private val input: CatalogTestRunTerminalFrozenV1,
    private val custody: CatalogTestRunActivationReleaseCustodyV1,
    allocation: ByteArray,
    private val binding: ByteArray,
    private val created: Boolean,
) {
    private val allocationHash = Sha256.hex(allocation)
    private val fields = decode(binding, "binding", 15)
    private val arms = LinkedHashMap<CatalogTestRunActivationReleaseLeafV1, Arm>()
    private var signNew = false
    private var signConsumed = false
    private var putNew = false
    private var putConsumed = false
    private var signed: CatalogTestRunTerminalSignedV1? = null
    private var dual: CatalogTestRunTerminalDeliveryReadbackV1? = null

    init {
        requireConnectionFree(); original.requireReleaseCapture(this, custody, input)
        requireTestTerminalCatalog(allocation(input, original, binding).contentEquals(allocation))
        val snapshot = original.custodySnapshot()
        requireBinding(snapshot)
        leaves().forEach { (leaf, bytes) ->
            if (created) create(leaf, bytes) else exact(leaf, bytes)
        }
        // This profile deliberately does not synthesize legacy activation outcome receipts.
        UNUSED.forEach { requireTestTerminalCatalog(custody.read(it) == null) }
        if (created) {
            (listOf(PREPARE, SIGN, SIGN_SQL, PUT, COMPLETE, PROJECT) + EFFECTS).forEach { requireTestTerminalCatalog(custody.read(it) == null) }
        } else {
            requireTestTerminalCatalog(snapshot.terminal != null)
            listOf(PREPARE, SIGN, SIGN_SQL, PUT, COMPLETE, PROJECT).forEach { leaf ->
                custody.read(leaf)?.let { arms[leaf] = parseArm(leaf, it) }
            }
            requireTestTerminalCatalog(PREPARE in arms)
            val signature = custody.read(CatalogTestRunActivationReleaseLeafV1.SIGNATURE)
            val envelope = custody.read(CatalogTestRunActivationReleaseLeafV1.ENVELOPE)
            if (signature != null) {
                requireTestTerminalCatalog(SIGN in arms)
                signed = CatalogTestRunTerminalSignedV1.verify(original.process, input, signature, envelope)
            } else requireTestTerminalCatalog(envelope == null && !snapshot.terminal.signed)
            custody.read(CatalogTestRunActivationReleaseLeafV1.SIGN_RETURNED)?.let {
                requireTestTerminalCatalog(envelope != null && it.contentEquals(returnedRecord(checkNotNull(signed))))
            }
            requireTestTerminalCatalog(SIGN_SQL !in arms || signed != null)
            requireTestTerminalCatalog(PUT !in arms || SIGN_SQL in arms && signed != null && envelope != null)
            requireTestTerminalCatalog(COMPLETE !in arms || PUT in arms)
            requireTestTerminalCatalog(PROJECT !in arms || COMPLETE in arms)
            arms.values.zipWithNext().forEach { (before, after) ->
                requireTestTerminalCatalog(if (before.lease.token == after.lease.token) before.lease == after.lease
                    else before.lease.token < after.lease.token && before.lease.owner != after.lease.owner && before.lease.expiresAt < after.lease.expiresAt)
            }
            val ack = custody.read(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED)
            val primary = custody.read(CatalogTestRunActivationReleaseLeafV1.PRIMARY_COPY)
            val replica = custody.read(CatalogTestRunActivationReleaseLeafV1.REPLICA_COPY)
            val delivered = custody.read(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_DUAL_COPY)
            requireTestTerminalCatalog((ack == null && primary == null && replica == null && delivered == null) || PUT in arms)
            requireTestTerminalCatalog(replica == null || primary != null)
            requireTestTerminalCatalog(delivered == null || primary != null && replica != null)
            requireTestTerminalCatalog(COMPLETE !in arms || delivered != null)
            ack?.let { requireAcknowledgedRecord(it) }
            delivered?.let { requireStoredDual() }
            if (snapshot.terminal.signed) {
                requireTestTerminalCatalog(SIGN_SQL in arms)
                snapshot.terminal.requireSigned(checkNotNull(signed))
            }
            if (snapshot.terminal.completed != null) {
                requireTestTerminalCatalog(COMPLETE in arms)
                requireStoredDual()
            }
            if (snapshot.terminal.projectedAt != null) requireTestTerminalCatalog(PROJECT in arms)
        }
        requireSnapshot(snapshot)
    }

    fun availableSignature(): CatalogTestRunTerminalSignedV1? = signed
    fun publicationWasArmed(): Boolean = PUT in arms

    /** Any partial historical copy leaves must agree with a fresh actual DUAL read, never source-only. */
    fun requireObservation(proof: CatalogTestRunTerminalDeliveryReadbackV1) {
        requireConnectionFree(); original.requireReleaseObservation(this, proof)
        val primary = custody.read(CatalogTestRunActivationReleaseLeafV1.PRIMARY_COPY)
        val replica = custody.read(CatalogTestRunActivationReleaseLeafV1.REPLICA_COPY)
        val complete = custody.read(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_DUAL_COPY)
        custody.read(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED)?.let { bytes ->
            val version = requireAcknowledgedRecord(bytes)
            requireTestTerminalCatalog(proof.objectVersion == version) // An acknowledged copy may not disappear.
        }
        if (proof.state !== CatalogTestRunTerminalDeliveryReadbackV1.State.DUAL_COPY) {
            requireTestTerminalCatalog(primary == null && replica == null && complete == null)
        } else {
            requireTestTerminalCatalog(PUT in arms)
            primary?.let { requireTestTerminalCatalog(it.contentEquals(proof.primaryEvidenceBytes())) }
            replica?.let { requireTestTerminalCatalog(it.contentEquals(proof.replicaEvidenceBytes())) }
            complete?.let { requireTestTerminalCatalog(it.contentEquals(record("dual-copy", checkNotNull(proof.objectVersion),
                checkNotNull(proof.retainUntilEpochSecond).toString(), Sha256.hex(checkNotNull(proof.primaryEvidenceBytes())), Sha256.hex(checkNotNull(proof.replicaEvidenceBytes()))))) }
        }
    }

    /** Absence of SIGN_ARMED can permit the first Sign only after fresh original admission. */
    fun armSign(): Boolean {
        requireConnectionFree(); original.requireSignArm(this)
        requireSnapshot(original.custodySnapshot())
        requireTestTerminalCatalog(original.custodySnapshot().terminal != null)
        if (SIGN in arms) {
            requireTestTerminalCatalog(signed != null) // Unknown Sign without any durably returned bytes is never repeated.
            return false
        }
        requireTestTerminalCatalog(signed == null && !checkNotNull(original.custodySnapshot().terminal).signed)
        arm(SIGN); signNew = true; return true
    }
    fun consumeSign() {
        requireConnectionFree(); original.requireReleaseUse(this)
        requireTestTerminalCatalog(signNew && !signConsumed && signed == null)
        signConsumed = true; requireCurrentArm(SIGN)
    }
    fun preserveSignature(signature: ByteArray): CatalogTestRunTerminalSignedV1 {
        requireConnectionFree(); original.requireSignatureReturn(this)
        requireTestTerminalCatalog(signNew && signConsumed && signed == null)
        val value = CatalogTestRunTerminalSignedV1.verify(original.process, input, signature)
        signed = value; preserveReturned(value)
        return value
    }
    /** A genuine durable signature may complete a missing wire/return leaf without ever re-Signing. */
    fun preserveRecoveredSignature(value: CatalogTestRunTerminalSignedV1) {
        requireConnectionFree(); original.requireRecoveredSignature(this, value)
        requireTestTerminalCatalog(value === signed && SIGN in arms); preserveReturned(value)
    }
    private fun preserveReturned(value: CatalogTestRunTerminalSignedV1) {
        exact(SIGN, checkNotNull(arms[SIGN]).bytes)
        keep(CatalogTestRunActivationReleaseLeafV1.SIGNATURE, value.signatureBytes())
        keep(CatalogTestRunActivationReleaseLeafV1.ENVELOPE, value.envelopeBytes())
        keep(CatalogTestRunActivationReleaseLeafV1.SIGN_RETURNED, returnedRecord(value))
    }
    fun armPrepare() {
        requireConnectionFree(); original.requirePrepareArm(this)
        requireTestTerminalCatalog(created && original.custodySnapshot().terminal == null && PREPARE !in arms)
        arm(PREPARE)
    }
    fun armSignaturePersistence() {
        requireConnectionFree(); original.requireSignaturePersistenceArm(this)
        requireReturned(); requireSnapshot(original.custodySnapshot())
        if (SIGN_SQL !in arms) requireTestTerminalCatalog(!checkNotNull(original.custodySnapshot().terminal).signed)
        arm(SIGN_SQL)
    }
    fun armPublication(proof: CatalogTestRunTerminalDeliveryReadbackV1): Boolean {
        requireConnectionFree(); original.requirePublicationArm(this, proof)
        requireReturned(); requireSnapshot(original.custodySnapshot())
        exact(SIGN_SQL, checkNotNull(arms[SIGN_SQL]).bytes)
        if (PUT in arms) return false // An old arm forbids a second PUT, even if BOTH keys currently appear absent.
        requireTestTerminalCatalog(proof.state === CatalogTestRunTerminalDeliveryReadbackV1.State.UNPUBLISHED)
        arm(PUT); putNew = true; return true
    }
    fun consumePublication() {
        requireConnectionFree(); original.requireReleaseUse(this)
        requireTestTerminalCatalog(putNew && !putConsumed)
        putConsumed = true; requireCurrentArm(PUT)
    }
    fun acknowledge(value: CatalogPrimaryPutAcknowledgementV1) {
        requireConnectionFree(); original.requirePublicationAcknowledgement(this, value)
        requireTestTerminalCatalog(putNew && putConsumed && value.envelopeSha256 == checkNotNull(signed).envelopeSha256)
        keep(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED, record("publication-acknowledged", value.versionId))
    }
    fun preserveDual(proof: CatalogTestRunTerminalDeliveryReadbackV1) {
        requireConnectionFree(); original.requireDualPreservation(this, proof)
        requireTestTerminalCatalog(PUT in arms && proof.state === CatalogTestRunTerminalDeliveryReadbackV1.State.DUAL_COPY)
        val version = checkNotNull(proof.objectVersion)
        custody.read(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED)?.let {
            requireTestTerminalCatalog(it.contentEquals(record("publication-acknowledged", version)))
        }
        keep(CatalogTestRunActivationReleaseLeafV1.PRIMARY_COPY, checkNotNull(proof.primaryEvidenceBytes()))
        keep(CatalogTestRunActivationReleaseLeafV1.REPLICA_COPY, checkNotNull(proof.replicaEvidenceBytes()))
        keep(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_DUAL_COPY, record("dual-copy", version,
            checkNotNull(proof.retainUntilEpochSecond).toString(), Sha256.hex(checkNotNull(proof.primaryEvidenceBytes())), Sha256.hex(checkNotNull(proof.replicaEvidenceBytes()))))
        requireStoredDual(); dual = proof
    }
    fun armComplete(proof: CatalogTestRunTerminalDeliveryReadbackV1) {
        requireConnectionFree(); original.requireCompleteArm(this, proof)
        requireTestTerminalCatalog(dual === proof); requireStoredDual()
        if (COMPLETE !in arms) requireTestTerminalCatalog(original.custodySnapshot().terminal?.completed == null)
        arm(COMPLETE)
    }
    fun armProject(proof: CatalogTestRunTerminalDeliveryReadbackV1) {
        requireConnectionFree(); original.requireProjectArm(this, proof)
        requireTestTerminalCatalog(dual === proof && COMPLETE in arms && original.custodySnapshot().terminal?.completed != null)
        requireSnapshot(original.custodySnapshot()); requireStoredDual()
        if (PROJECT !in arms) requireTestTerminalCatalog(original.custodySnapshot().terminal?.projectedAt == null)
        arm(PROJECT)
    }

    fun requireSnapshot(snapshot: CatalogTestRunTerminalSnapshotV1) {
        requireConnectionFree(); requireBinding(snapshot)
        input.requireOwner(original); snapshot.run.requireFrozen(input); snapshot.terminal?.requireFrozen(input)
        val expected = fields[if (snapshot.terminal == null) 12 else if (snapshot.terminal.projectedAt == null) 13 else 14]
        requireTestTerminalCatalog(expected == capacityHash(snapshot.counters, snapshot.counters.balance))
    }
    fun requireAcquisition(value: CatalogTestRunActivationLeaseV1) {
        requireConnectionFree(); original.requireReleaseUse(this)
        val historical = arms.values.map { it.lease }
        if (!created) requireTestTerminalCatalog(historical.all { value.token > it.token && value.owner != it.owner && value.expiresAt > it.expiresAt })
        else requireTestTerminalCatalog(historical.all { value.token == it.token && value.owner == it.owner && value.expiresAt == it.expiresAt })
    }
    private fun requireBinding(snapshot: CatalogTestRunTerminalSnapshotV1) {
        requireTestTerminalCatalog(fields.drop(2).take(10) == listOf(input.token.toString(), input.scope.toString(), input.generation.toString(),
            input.predecessorHash, Sha256.hex(original.process.canonicalBytes()),
            java.util.HexFormat.of().formatHex(snapshot.global.coreHashBytes()), java.util.HexFormat.of().formatHex(snapshot.scoped.coreHashBytes()),
            snapshot.run.coreSha256(), snapshot.history.custodyPrefixHash(), original.custodyPreconditionSha256()))
    }
    private fun requireReturned() {
        val value = checkNotNull(signed)
        requireTestTerminalCatalog(SIGN in arms)
        exact(CatalogTestRunActivationReleaseLeafV1.SIGNATURE, value.signatureBytes())
        exact(CatalogTestRunActivationReleaseLeafV1.ENVELOPE, value.envelopeBytes())
        exact(CatalogTestRunActivationReleaseLeafV1.SIGN_RETURNED, returnedRecord(value))
    }
    private fun requireStoredDual() {
        requireReturned()
        val bytes = checkNotNull(custody.read(CatalogTestRunActivationReleaseLeafV1.PUBLICATION_DUAL_COPY))
        val values = decode(bytes, "dual-copy", 7)
        requireTestTerminalCatalog(values[2] == allocationHash && values[3].isNotEmpty() && values[3] != "null" &&
            values[4].toLong().toString() == values[4] && values[4].toLong() in 1..253_402_300_799L &&
            values[5] == Sha256.hex(checkNotNull(custody.read(CatalogTestRunActivationReleaseLeafV1.PRIMARY_COPY))) &&
            values[6] == Sha256.hex(checkNotNull(custody.read(CatalogTestRunActivationReleaseLeafV1.REPLICA_COPY))))
    }
    private fun requireAcknowledgedRecord(bytes: ByteArray): String {
        val fields = decode(bytes, "publication-acknowledged", 4)
        requireTestTerminalCatalog(PUT in arms && fields[2] == allocationHash && fields[3].toByteArray(Charsets.UTF_8).size in 1..1024 &&
            fields[3] != "null" && fields[3].none { it.code < 32 || it.code == 127 })
        return fields[3]
    }
    private fun arm(leaf: CatalogTestRunActivationReleaseLeafV1) {
        original.requireReleaseUse(this)
        val old = arms[leaf]
        if (old != null) { exact(leaf, old.bytes); return }
        val lease = original.custodyLease()
        val bytes = record(kind(leaf), lease.owner.toString(), lease.token.toString(), lease.expiresAt.toString())
        create(leaf, bytes); arms[leaf] = parseArm(leaf, bytes)
    }
    private fun requireCurrentArm(leaf: CatalogTestRunActivationReleaseLeafV1) {
        val arm = checkNotNull(arms[leaf]); val current = original.custodyLease()
        exact(leaf, arm.bytes)
        requireTestTerminalCatalog(arm.lease.owner == current.owner && arm.lease.token == current.token && arm.lease.expiresAt == current.expiresAt)
    }
    private fun parseArm(leaf: CatalogTestRunActivationReleaseLeafV1, bytes: ByteArray): Arm {
        val fields = decode(bytes, kind(leaf), 6)
        val owner = UUID.fromString(fields[3]); val token = fields[4].toLong(); val expires = Instant.parse(fields[5])
        requireTestTerminalCatalog(fields[2] == allocationHash && owner.toString() == fields[3] && owner.version() == 4 && owner.variant() == 2 &&
            token > 0 && token.toString() == fields[4] && expires.epochSecond in 0..253_402_300_799L && expires.toString() == fields[5])
        return Arm(bytes.copyOf(), Lease(owner, token, expires))
    }
    private fun returnedRecord(value: CatalogTestRunTerminalSignedV1): ByteArray = record("sign-returned", value.signatureSha256, value.envelopeSha256)
    private fun record(kind: String, vararg values: String): ByteArray = encode(kind, allocationHash, *values)
    private fun exact(leaf: CatalogTestRunActivationReleaseLeafV1, bytes: ByteArray) = requireTestTerminalCatalog(custody.read(leaf).contentEquals(bytes))
    private fun create(leaf: CatalogTestRunActivationReleaseLeafV1, bytes: ByteArray) {
        requireTestTerminalCatalog(custody.putIfAbsent(leaf, bytes) === CatalogTestRunActivationCustodyObservationV1.CREATED); exact(leaf, bytes)
    }
    private fun keep(leaf: CatalogTestRunActivationReleaseLeafV1, bytes: ByteArray) { custody.putIfAbsent(leaf, bytes); exact(leaf, bytes) }
    private fun leaves(): List<Pair<CatalogTestRunActivationReleaseLeafV1, ByteArray>> = listOf(
        CatalogTestRunActivationReleaseLeafV1.APPROVED_INTENT to input.unsignedBytes(),
        CatalogTestRunActivationReleaseLeafV1.FULL_CONFIGURATION to original.process.canonicalBytes(),
        CatalogTestRunActivationReleaseLeafV1.TRUST_T0 to original.process.catalogReadback.initialBundleBytes(),
        CatalogTestRunActivationReleaseLeafV1.TRUST_TN to original.process.catalogReadback.currentBundleBytes(),
        CatalogTestRunActivationReleaseLeafV1.INITIAL_REGISTRY to original.process.catalogActivation.initialWriterRegistryBytes(),
        CatalogTestRunActivationReleaseLeafV1.APPROVAL_INPUTS to input.approvalBytes(),
        CatalogTestRunActivationReleaseLeafV1.GLOBAL_PREIMAGE to fields[7].toByteArray(Charsets.US_ASCII),
        CatalogTestRunActivationReleaseLeafV1.BINDING to binding.copyOf(),
    )
    private data class Lease(val owner: UUID, val token: Long, val expiresAt: Instant)
    private class Arm(val bytes: ByteArray, val lease: Lease)
    override fun toString(): String = "CatalogTestRunTerminalReleaseV1(exact-original-custody,no-denial-or-commit-authority)"

    companion object {
        private const val DOMAIN = "catalog-test-run-terminal-release-v1"
        private val PREPARE = CatalogTestRunActivationReleaseLeafV1.PREPARE_ARMED
        private val SIGN = CatalogTestRunActivationReleaseLeafV1.SIGN_ARMED
        private val SIGN_SQL = CatalogTestRunActivationReleaseLeafV1.SIGNATURE_SQL_ARMED
        private val PUT = CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ARMED
        private val COMPLETE = CatalogTestRunActivationReleaseLeafV1.COMPLETE_ARMED
        private val PROJECT = CatalogTestRunActivationReleaseLeafV1.PROJECT_ARMED
        private val UNUSED = listOf(CatalogTestRunActivationReleaseLeafV1.PREPARED, CatalogTestRunActivationReleaseLeafV1.SIGNATURE_SQL_PERSISTED,
            CatalogTestRunActivationReleaseLeafV1.FREEZE_OUTCOME, CatalogTestRunActivationReleaseLeafV1.PUBLICATION_AWAIT_REPLICATION,
            CatalogTestRunActivationReleaseLeafV1.COMPLETE_OUTCOME, CatalogTestRunActivationReleaseLeafV1.PENDING_RELOAD_OUTCOME, CatalogTestRunActivationReleaseLeafV1.PROJECT_OUTCOME)
        private val EFFECTS = listOf(CatalogTestRunActivationReleaseLeafV1.SIGNATURE, CatalogTestRunActivationReleaseLeafV1.ENVELOPE,
            CatalogTestRunActivationReleaseLeafV1.SIGN_RETURNED, CatalogTestRunActivationReleaseLeafV1.PUBLICATION_ACKNOWLEDGED,
            CatalogTestRunActivationReleaseLeafV1.PRIMARY_COPY, CatalogTestRunActivationReleaseLeafV1.REPLICA_COPY, CatalogTestRunActivationReleaseLeafV1.PUBLICATION_DUAL_COPY)
        private fun kind(leaf: CatalogTestRunActivationReleaseLeafV1): String = when (leaf) {
            PREPARE -> "prepare-armed"; SIGN -> "sign-armed"; SIGN_SQL -> "signature-sql-armed"; PUT -> "publication-armed"
            COMPLETE -> "complete-armed"; PROJECT -> "project-armed"; else -> throw CatalogTestRunTerminalExceptionV1()
        }
        internal fun binding(original: CatalogTestRunTerminalV1, input: CatalogTestRunTerminalFrozenV1): ByteArray {
            requireConnectionFree(); val snapshot = original.custodySnapshot()
            requireTestTerminalCatalog(snapshot.terminal == null)
            val counters = snapshot.counters
            val prepared = ComplaintCapacityLedger(counters.configuration, counters.balance).spendTestReserve(input.capacityDigest(), input.prepareCharge, ComplaintCapacityVector.ZERO)
            val projected = prepared.spendTestReserve(input.capacityDigest(), input.projectionCharge, ComplaintCapacityVector.ZERO)
            return encode("binding", input.token.toString(), input.scope.toString(), input.generation.toString(), input.predecessorHash,
                Sha256.hex(original.process.canonicalBytes()), java.util.HexFormat.of().formatHex(snapshot.global.coreHashBytes()),
                java.util.HexFormat.of().formatHex(snapshot.scoped.coreHashBytes()), snapshot.run.coreSha256(), snapshot.history.custodyPrefixHash(), original.custodyPreconditionSha256(),
                capacityHash(counters, counters.balance), capacityHash(counters, prepared.balance), capacityHash(counters, projected.balance))
        }
        internal fun allocation(input: CatalogTestRunTerminalFrozenV1, original: CatalogTestRunTerminalV1, binding: ByteArray): ByteArray =
            encode("allocation", input.token.toString(), input.scope.toString(), input.generation.toString(),
                Sha256.hex(original.process.canonicalBytes()), java.util.HexFormat.of().formatHex(input.unsignedHash()), Sha256.hex(binding))
        internal fun open(original: CatalogTestRunTerminalV1, input: CatalogTestRunTerminalFrozenV1, custody: CatalogTestRunActivationReleaseCustodyV1,
            allocation: ByteArray, binding: ByteArray, created: Boolean): CatalogTestRunTerminalReleaseV1 =
            CatalogTestRunTerminalReleaseV1(original, input, custody, allocation, binding, created)
        private fun capacityHash(counters: CatalogTestRunActivationProjectionCountersV1, balance: ComplaintCapacityBalance): String {
            val fields = buildList {
                add(java.util.HexFormat.of().formatHex(checkNotNull(counters.configuration.digestBytes())))
                add(counters.configuration.creationClosed.toString())
                ComplaintCapacityEncoding.lockOrder().forEach { counter ->
                    add(counter.storedOrdinal.toString()); add(counter.storedName)
                    add(balance.hardLimit[counter].toString()); add(balance.creationLimit[counter].toString()); add(balance.free[counter].toString())
                    add(balance.actual[counter].toString()); add(balance.recoveryReserved[counter].toString()); add(balance.testReserved[counter].toString())
                }
                add(counters.daily.utcEpochDay?.toString() ?: "NULL"); add(counters.daily.count.toString()); add(counters.daily.dailyLimit.toString())
            }
            return Sha256.hex(encode("counter-preimage", *fields.toTypedArray()))
        }
        private fun encode(kind: String, vararg fields: String): ByteArray = CanonicalJson.canonicalize(ListSerializer(String.serializer()),
            listOf(DOMAIN, kind) + fields.toList()).toByteArray(Charsets.UTF_8)
        private fun decode(bytes: ByteArray, kind: String, count: Int): List<String> {
            requireTestTerminalCatalog(bytes.size in 1..4096)
            val fields = CanonicalJson.json.decodeFromString(ListSerializer(String.serializer()), bytes.toString(Charsets.UTF_8))
            requireTestTerminalCatalog(fields.size == count && fields[0] == DOMAIN && fields[1] == kind && encode(kind, *fields.drop(2).toTypedArray()).contentEquals(bytes))
            return fields
        }
    }
}
