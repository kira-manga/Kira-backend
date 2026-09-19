package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/**
 * Closed TEST freeze custody, not a portable approval or SQL receipt. The original allocation and PREPARE
 * arm precede PREPARE dispatch; only that same successful owner can create/spend its one Sign arm.
 * Cold recovery requires the complete original returned bytes. Absence never creates Sign eligibility.
 */
@Suppress("TooManyFunctions", "LargeClass")
internal class CatalogTestRunActivationReleaseV1(
    private val original: CatalogTestRunActivationV1,
    private val input: CatalogTestRunActivationFrozenV1,
    private val custody: CatalogTestRunActivationReleaseCustodyV1,
    allocationBytes: ByteArray,
    private val bindingBytes: ByteArray,
    private val created: Boolean,
) {
    init {
        requireConnectionFree()
        original.requireReleaseCapture(custody, input, created) // Before parsing or hashing even a bounded custody document.
    }

    private val allocationHash = Sha256.hex(allocationBytes)
    private val binding = decode(bindingBytes, "binding", 20)
    private val global = if (created) original.custodySnapshot().control.custodyBytes()
    else checkNotNull(custody.read(CatalogTestRunActivationReleaseLeafV1.GLOBAL_PREIMAGE))
    private val originalLease = lease(binding.drop(17))
    private var returned: CatalogTestRunActivationSignedV1? = null
    private var signatureSqlArm: SignatureSqlArm? = null
    private var persisted: ByteArray? = null
    private var outcome: ByteArray? = null

    init {
        requireTestActivation(allocation(input, original, bindingBytes).contentEquals(allocationBytes))
        val expected = bindingPrefix(input, original, Sha256.hex(global), binding[8])
        requireTestActivation(binding.drop(2).take(12) == expected && OfflineBootstrapGrammar.sha256(binding[8]))
        requireTestActivation(binding[14] in BOOLEANS && binding[15] in BOOLEANS)
        val beforeToken = canonicalLong(binding[16], zeroAllowed = true)
        requireTestActivation(beforeToken < Long.MAX_VALUE && originalLease.token == beforeToken + 1L)
        if (created) {
            requireSnapshot(original.custodySnapshot())
            requireTestActivation(originalLease.same(original.custodyLease()))
            inputLeaves().forEach { (leaf, bytes) -> requireCreated(leaf, bytes) }
        } else {
            inputLeaves().forEach { (leaf, bytes) -> requireExact(leaf, bytes) }
            // No recovery bootstrap from an old diagnostic PREPARED, even when every Sign leaf is absent.
            requireExact(CatalogTestRunActivationReleaseLeafV1.PREPARE_ARMED, record("prepare-armed"))
            requireExact(CatalogTestRunActivationReleaseLeafV1.PREPARED, record("prepared-unsigned"))
            requireExact(CatalogTestRunActivationReleaseLeafV1.SIGN_ARMED, record("sign-armed"))
            val signature = checkNotNull(custody.read(CatalogTestRunActivationReleaseLeafV1.SIGNATURE))
            val envelope = checkNotNull(custody.read(CatalogTestRunActivationReleaseLeafV1.ENVELOPE))
            returned = CatalogTestRunActivationSignedV1.verify(original.process, input, signature, envelope)
            requireReturnedPrefix()
            signatureSqlArm = custody.read(CatalogTestRunActivationReleaseLeafV1.SIGNATURE_SQL_ARMED)?.let(::parseSqlArm)
            persisted = custody.read(CatalogTestRunActivationReleaseLeafV1.SIGNATURE_SQL_PERSISTED)?.also {
                requireTestActivation(it.contentEquals(persistedRecord(checkNotNull(signatureSqlArm))))
            }
            outcome = custody.read(CatalogTestRunActivationReleaseLeafV1.FREEZE_OUTCOME)?.also {
                requireTestActivation(persisted != null && it.contentEquals(outcomeRecord(checkNotNull(signatureSqlArm))))
            }
        }
    }

    internal fun returnedSignature(): CatalogTestRunActivationSignedV1 = checkNotNull(returned)

    /** Exact global SQL preimage and full history prefix, never the not-yet-created target D substituted for global D. */
    internal fun requireSnapshot(snapshot: CatalogTestRunActivationSnapshotV1) {
        requireConnectionFree()
        requireTestActivation(snapshot.control.custodyBytes().contentEquals(global) && snapshot.history.custodyPrefixHash() == binding[8])
        val prepared = snapshot.history.size.toLong() == input.generation
        snapshot.requireExpected(input, prepared, if (snapshot.signedTail == null) null else checkNotNull(returned))
        if (!prepared) {
            requireTestActivation(created && snapshot.control.maintenanceClosed.toString() == binding[14] && snapshot.control.creationClosed.toString() == binding[15])
        } else if (snapshot.signedTail != null) {
            requireTestActivation(signatureSqlArm != null) // No missing-arm adoption of an already signed SQL row.
        }
        if (persisted != null || outcome != null) requireTestActivation(snapshot.signedTail != null)
        if (!created) requireTestActivation(prepared && snapshot.control.leaseToken >= historicalLease().token)
    }

    internal fun requireAcquisition(value: CatalogTestRunActivationLeaseV1) {
        requireConnectionFree()
        if (created) {
            requireTestActivation(originalLease.same(value))
        } else {
            val historical = historicalLease()
            requireTestActivation(value.token > historical.token && value.owner != originalLease.owner && value.owner != historical.owner &&
                value.expiresAt.isAfter(historical.expiresAt))
        }
    }

    internal fun armPrepare() {
        original.requirePrepareArm(this)
        requireTestActivation(created)
        requireCreated(CatalogTestRunActivationReleaseLeafV1.PREPARE_ARMED, record("prepare-armed"))
    }

    internal fun prepared(snapshot: CatalogTestRunActivationSnapshotV1) {
        original.requirePrepared(this, snapshot)
        requireSnapshot(snapshot)
        snapshot.requireExpected(input, prepared = true)
        requireExact(CatalogTestRunActivationReleaseLeafV1.PREPARE_ARMED, record("prepare-armed"))
        requireCreated(CatalogTestRunActivationReleaseLeafV1.PREPARED, record("prepared-unsigned"))
    }

    internal fun armSignature(snapshot: CatalogTestRunActivationSnapshotV1) {
        original.requireSignArm(this, snapshot)
        requireTestActivation(created && returned == null)
        requireSnapshot(snapshot)
        snapshot.requireExpected(input, prepared = true)
        requireExact(CatalogTestRunActivationReleaseLeafV1.PREPARE_ARMED, record("prepare-armed"))
        requireExact(CatalogTestRunActivationReleaseLeafV1.PREPARED, record("prepared-unsigned"))
        CatalogTestRunActivationReleaseLeafV1.entries.drop(CatalogTestRunActivationReleaseLeafV1.SIGN_ARMED.ordinal).forEach {
            requireTestActivation(custody.read(it) == null)
        }
        requireCreated(CatalogTestRunActivationReleaseLeafV1.SIGN_ARMED, record("sign-armed"))
    }

    internal fun preserveSignature(signature: ByteArray): CatalogTestRunActivationSignedV1 {
        original.requireSignatureReturn(this)
        requireTestActivation(created && returned == null)
        requireExact(CatalogTestRunActivationReleaseLeafV1.SIGN_ARMED, record("sign-armed"))
        val value = CatalogTestRunActivationSignedV1.verify(original.process, input, signature)
        requireCreated(CatalogTestRunActivationReleaseLeafV1.SIGNATURE, value.signatureBytes())
        requireCreated(CatalogTestRunActivationReleaseLeafV1.ENVELOPE, value.envelopeBytes())
        requireCreated(CatalogTestRunActivationReleaseLeafV1.SIGN_RETURNED, returnedRecord(value))
        returned = value
        return value
    }

    internal fun armSignaturePersistence(value: CatalogTestRunActivationSignedV1) {
        original.requireSignatureSqlArm(this, value)
        requireTestActivation(returned === value)
        requireReturnedPrefix()
        val current = original.custodyLease()
        requireAcquisition(current)
        val old = signatureSqlArm
        if (old == null) {
            // A complete returned-byte prefix can arm DB-only recovery after a fresh released unsigned recheck.
            // This cannot retrofit a missing allocation/PREPARE/Sign/return leaf, nor adopt a signed row.
            requireTestActivation(original.custodySnapshot().signedTail == null)
            val bytes = record("signature-sql-armed", value.signatureSha256, value.envelopeSha256, *leaseValues(current))
            requireCreated(CatalogTestRunActivationReleaseLeafV1.SIGNATURE_SQL_ARMED, bytes)
            signatureSqlArm = parseSqlArm(bytes)
        } else {
            requireExact(CatalogTestRunActivationReleaseLeafV1.SIGNATURE_SQL_ARMED, old.bytes)
        }
    }

    internal fun signaturePersisted(snapshot: CatalogTestRunActivationSnapshotV1) {
        original.requireSignaturePersisted(this, snapshot)
        requireSnapshot(snapshot)
        snapshot.requireExpected(input, prepared = true, signed = checkNotNull(returned))
        requireReturnedPrefix()
        val arm = checkNotNull(signatureSqlArm)
        requireExact(CatalogTestRunActivationReleaseLeafV1.SIGNATURE_SQL_ARMED, arm.bytes)
        if (arm.lease.same(original.custodyLease())) {
            val bytes = persistedRecord(arm)
            requireCreated(CatalogTestRunActivationReleaseLeafV1.SIGNATURE_SQL_PERSISTED, bytes)
            persisted = bytes
        } // A fresh owner never fills the old acquisition's missing outcome, even after its own known commit.
    }

    internal fun frozen(snapshot: CatalogTestRunActivationSnapshotV1) {
        original.requireSignedReload(this, snapshot)
        requireSnapshot(snapshot)
        snapshot.requireExpected(input, prepared = true, signed = checkNotNull(returned))
        requireReturnedPrefix()
        val arm = checkNotNull(signatureSqlArm)
        requireExact(CatalogTestRunActivationReleaseLeafV1.SIGNATURE_SQL_ARMED, arm.bytes)
        if (arm.lease.same(original.custodyLease())) {
            requireExact(CatalogTestRunActivationReleaseLeafV1.SIGNATURE_SQL_PERSISTED, persistedRecord(arm))
            val bytes = outcomeRecord(arm)
            requireCreated(CatalogTestRunActivationReleaseLeafV1.FREEZE_OUTCOME, bytes)
            outcome = bytes
        }
    }

    private fun requireReturnedPrefix() {
        val value = checkNotNull(returned)
        requireExact(CatalogTestRunActivationReleaseLeafV1.PREPARE_ARMED, record("prepare-armed"))
        requireExact(CatalogTestRunActivationReleaseLeafV1.PREPARED, record("prepared-unsigned"))
        requireExact(CatalogTestRunActivationReleaseLeafV1.SIGN_ARMED, record("sign-armed"))
        requireExact(CatalogTestRunActivationReleaseLeafV1.SIGNATURE, value.signatureBytes())
        requireExact(CatalogTestRunActivationReleaseLeafV1.ENVELOPE, value.envelopeBytes())
        requireExact(CatalogTestRunActivationReleaseLeafV1.SIGN_RETURNED, returnedRecord(value))
    }

    private fun parseSqlArm(bytes: ByteArray): SignatureSqlArm {
        val values = decode(bytes, "signature-sql-armed", 8)
        val value = checkNotNull(returned)
        requireTestActivation(values[2] == allocationHash && values[3] == value.signatureSha256 && values[4] == value.envelopeSha256)
        val selected = lease(values.drop(5))
        requireTestActivation(selected.token >= originalLease.token)
        if (selected.token == originalLease.token) requireTestActivation(selected == originalLease)
        else requireTestActivation(selected.owner != originalLease.owner && selected.expiresAt.isAfter(originalLease.expiresAt))
        return SignatureSqlArm(bytes.copyOf(), selected)
    }

    private fun historicalLease(): Lease = signatureSqlArm?.lease ?: originalLease
    private fun returnedRecord(value: CatalogTestRunActivationSignedV1): ByteArray = record("sign-returned", value.signatureSha256, value.envelopeSha256)
    private fun persistedRecord(arm: SignatureSqlArm): ByteArray = record(
        "signature-sql-persisted", checkNotNull(returned).signatureSha256, checkNotNull(returned).envelopeSha256, *arm.lease.recordValues(),
    )
    private fun outcomeRecord(arm: SignatureSqlArm): ByteArray = record("signed-prepared", checkNotNull(returned).envelopeSha256, *arm.lease.recordValues())

    private fun inputLeaves(): List<Pair<CatalogTestRunActivationReleaseLeafV1, ByteArray>> = listOf(
        CatalogTestRunActivationReleaseLeafV1.APPROVED_INTENT to input.unsignedBytes(),
        CatalogTestRunActivationReleaseLeafV1.FULL_CONFIGURATION to original.process.canonicalBytes(),
        CatalogTestRunActivationReleaseLeafV1.TRUST_T0 to original.process.catalogReadback.initialBundleBytes(),
        CatalogTestRunActivationReleaseLeafV1.TRUST_TN to original.process.catalogReadback.currentBundleBytes(),
        CatalogTestRunActivationReleaseLeafV1.INITIAL_REGISTRY to original.process.catalogActivation.initialWriterRegistryBytes(),
        CatalogTestRunActivationReleaseLeafV1.APPROVAL_INPUTS to input.approvalBytes(),
        CatalogTestRunActivationReleaseLeafV1.GLOBAL_PREIMAGE to global.copyOf(),
        CatalogTestRunActivationReleaseLeafV1.BINDING to bindingBytes.copyOf(),
    )

    private fun record(kind: String, vararg values: String): ByteArray = testActivationRecord(kind, allocationHash, *values)
    private fun requireExact(leaf: CatalogTestRunActivationReleaseLeafV1, bytes: ByteArray) = requireTestActivation(custody.read(leaf).contentEquals(bytes))
    private fun requireCreated(leaf: CatalogTestRunActivationReleaseLeafV1, bytes: ByteArray) =
        requireTestActivation(custody.putIfAbsent(leaf, bytes) === CatalogTestRunActivationCustodyObservationV1.CREATED)

    override fun toString(): String = "CatalogTestRunActivationReleaseV1(original-TEST-freeze-custody,no-PUT-or-issuer,redacted)"

    private class SignatureSqlArm(val bytes: ByteArray, val lease: Lease)
    private data class Lease(val owner: UUID, val token: Long, val expiresAt: Instant) {
        fun same(value: CatalogTestRunActivationLeaseV1): Boolean = owner == value.owner && token == value.token && expiresAt == value.expiresAt
        fun recordValues(): Array<String> = arrayOf(owner.toString(), token.toString(), expiresAt.toString())
    }

    companion object {
        private const val DOMAIN = "catalog-test-run-activation-freeze-v1"
        private val BOOLEANS = setOf("true", "false")

        internal fun binding(original: CatalogTestRunActivationV1, input: CatalogTestRunActivationFrozenV1): ByteArray {
            requireConnectionFree()
            val snapshot = original.custodySnapshot()
            val lease = original.custodyLease()
            snapshot.requireExpected(input, prepared = false)
            return testActivationRecord(
                "binding", *bindingPrefix(input, original, Sha256.hex(snapshot.control.custodyBytes()), snapshot.history.custodyPrefixHash()).toTypedArray(),
                snapshot.control.maintenanceClosed.toString(), snapshot.control.creationClosed.toString(), snapshot.control.leaseToken.toString(),
                *leaseValues(lease),
            )
        }

        internal fun allocation(input: CatalogTestRunActivationFrozenV1, original: CatalogTestRunActivationV1, binding: ByteArray): ByteArray {
            requireConnectionFree()
            return testActivationRecord("allocation", input.token.toString(), input.scope.toString(), input.generation.toString(),
                HexFormat.of().formatHex(original.process.configurationHashBytes()), HexFormat.of().formatHex(input.unsignedHash()), Sha256.hex(binding))
        }

        private fun bindingPrefix(input: CatalogTestRunActivationFrozenV1, original: CatalogTestRunActivationV1, global: String, prefix: String): List<String> = listOf(
            input.token.toString(), input.scope.toString(), input.generation.toString(), input.predecessorHash,
            Sha256.hex(original.process.canonicalBytes()), global, prefix, original.process.catalogReadback.initialTrustBundleSha256,
            input.currentTrustHash, original.process.catalogActivation.initialWriterRegistrySha256,
            Sha256.hex(input.approvalBytes()), HexFormat.of().formatHex(input.unsignedHash()),
        )

        private fun testActivationRecord(kind: String, vararg values: String): ByteArray = CanonicalJson.canonicalize(
            ListSerializer(String.serializer()), listOf(DOMAIN, kind) + values.toList(),
        ).toByteArray(Charsets.UTF_8)

        private fun decode(bytes: ByteArray, kind: String, count: Int): List<String> {
            requireTestActivation(bytes.size in 1..4096)
            val values = CanonicalJson.json.decodeFromString(ListSerializer(String.serializer()), bytes.toString(Charsets.UTF_8))
            requireTestActivation(values.size == count && values[0] == DOMAIN && values[1] == kind)
            requireTestActivation(testActivationRecord(kind, *values.drop(2).toTypedArray()).contentEquals(bytes))
            return values
        }

        private fun lease(values: List<String>): Lease {
            requireTestActivation(values.size == 3)
            val owner = UUID.fromString(values[0])
            val expiresAt = Instant.parse(values[2])
            requireTestActivation(owner.toString() == values[0] && owner.version() == 4 && owner.variant() == 2 &&
                expiresAt.toString() == values[2] && expiresAt.epochSecond in 0..253402300799L)
            return Lease(owner, canonicalLong(values[1], zeroAllowed = false), expiresAt)
        }

        private fun canonicalLong(value: String, zeroAllowed: Boolean): Long = value.toLong().also {
            requireTestActivation(it >= (if (zeroAllowed) 0L else 1L) && it.toString() == value)
        }

        private fun leaseValues(value: CatalogTestRunActivationLeaseV1): Array<String> =
            arrayOf(value.owner.toString(), value.token.toString(), value.expiresAt.toString())
    }
}
