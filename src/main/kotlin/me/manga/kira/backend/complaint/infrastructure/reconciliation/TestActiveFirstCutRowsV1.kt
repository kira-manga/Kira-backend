package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveFirstSealStorageV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Detached private comparison bytes. This class is deliberately not a registration, lease or successor issuer. */
internal class TestActiveFirstCutIdentityV1 private constructor(registration: ComplaintTestNamespaceRegistrationV1) {
    private val values = copyFirstCutArguments(registration.identityAdmissionArguments())
    val scope = values[0] as UUID
    val installationLimit = values[2] as Long
    val generation = values[3] as Long
    val runCreatedAt = (values[5] as Timestamp).toInstant()
    val head = CatalogLocalHead(generation, HexFormat.of().formatHex(values[4] as ByteArray))
    val writer = values[13] as UUID
    val desiredGeneration = values[8] as Long
    val implementationSchema = values[9] as Int
    val databaseIdentity = values[11] as UUID
    val restoreIdentity = values[12] as UUID
    val catalogWriter = values[14] as UUID
    val acceptedCatalogGeneration = values[16] as Long
    val globalDesiredGeneration = values[18] as Long
    val originalReserveSql = values[6] as String
    private val journal = HexFormat.of().parseHex(registration.process.consumers.journalConfiguration.sha256)
    val sealEncodingSha256 = TestActiveFirstSealStorageV1.sealEncodingSha256

    init {
        requireFirstCut(values.size == 20 && journal.size == 32 && scope.version() == 4 && installationLimit > 0 && generation in 2..65536)
        requireFirstCut(values[0] == values[7] && (values[1] as ByteArray).contentEquals(values[10] as ByteArray) &&
            values[3] == values[16] && (values[4] as ByteArray).contentEquals(values[17] as ByteArray))
    }

    fun arguments(): Array<Any?> = arrayOf(*copyFirstCutArguments(values), journal.copyOf())
    fun runArguments(): Array<Any?> = copyFirstCutArguments(values.copyOfRange(0, 7))
    fun configurationHash(): ByteArray = (values[1] as ByteArray).copyOf()
    fun journalHash(): ByteArray = journal.copyOf()
    fun activationCatalogHash(): ByteArray = (values[4] as ByteArray).copyOf()
    fun acceptedCatalogHash(): ByteArray = (values[17] as ByteArray).copyOf()
    fun trustBundleHash(): ByteArray = (values[15] as ByteArray).copyOf()
    fun globalConfigurationHash(): ByteArray? = (values[19] as ByteArray?)?.copyOf()
    fun tailArguments(): Array<Any?> = arrayOf(scope, generation, activationCatalogHash(), catalogWriter)

    companion object {
        internal fun fromRegistration(registration: ComplaintTestNamespaceRegistrationV1): TestActiveFirstCutIdentityV1 =
            TestActiveFirstCutIdentityV1(registration)
    }
    override fun toString(): String = "TestActiveFirstCutIdentityV1(detached-full-D-and-birth,redacted,no-authority)"
}

/** DB-time observation only. Actual acquisition and every use still belong to the original typed holder. */
internal class TestActiveFirstCutLeaseV1(val owner: UUID, val token: Long, val expiresAt: Instant) {
    fun arguments(): Array<Any?> = arrayOf(owner, token, Timestamp.from(expiresAt))
    fun same(other: TestActiveFirstCutLeaseV1): Boolean = owner == other.owner && token == other.token && expiresAt == other.expiresAt
    override fun toString(): String = "TestActiveFirstCutLeaseV1(detached,no-current-use-authority)"
}

internal class TestActiveFirstCutStateV1 private constructor(row: ResultSet) {
    val sampledAt: Instant = checkNotNull(row.getTimestamp("sampled_at")).toInstant()
    val epoch = row.requiredLong("publication_epoch")
    val scanRequested = row.requiredBoolean("scan_requested")
    val leaseToken = row.requiredLong("lease_token")
    val lease: TestActiveFirstCutLeaseV1? = row.getObject("lease_owner", UUID::class.java)?.let {
        TestActiveFirstCutLeaseV1(it, leaseToken, checkNotNull(row.getTimestamp("lease_expires_at")).toInstant())
    }
    val sequence = row.requiredLong("rotation_sequence")
    val id: UUID? = row.getObject("rotation_id", UUID::class.java)
    val state: String? = row.getString("rotation_state")
    val epochBefore: Long? = row.nullableLong("rotation_epoch_before")
    val requestOwner: UUID? = row.getObject("rotation_request_owner", UUID::class.java)
    val requestToken: Long? = row.nullableLong("rotation_request_token")
    val requestedAt: Instant? = row.getTimestamp("rotation_requested_at")?.toInstant()
    val captureOwner: UUID? = row.getObject("rotation_capture_owner", UUID::class.java)
    val captureToken: Long? = row.nullableLong("rotation_capture_token")
    val capturedAt: Instant? = row.getTimestamp("rotation_captured_at")?.toInstant()
    val epochAfter: Long? = row.nullableLong("rotation_epoch_after")
    private val global = row.digest("global_fingerprint")
    private val control = row.digest("control_fingerprint")
    private val run = row.digest("run_fingerprint")
    private val paid = row.getBytes("slot_fingerprint")?.also { requireFirstCut(it.size == 32) }?.copyOf()

    init { requireFirstCut(row.requiredBoolean("valid")) }
    fun requireNoSlot() = requireFirstCut(sequence == 0L && id == null && state == null && epoch == 1L && paid == null)
    fun requireCurrent(selected: TestActiveFirstCutLeaseV1) {
        requireFirstCut(lease?.same(selected) == true && selected.expiresAt.isAfter(sampledAt))
    }
    fun requireSameGlobal(other: TestActiveFirstCutStateV1) = requireFirstCut(global.contentEquals(other.global))
    fun requireSameRun(other: TestActiveFirstCutStateV1) = requireFirstCut(run.contentEquals(other.run))
    fun requireSamePhysical(other: TestActiveFirstCutStateV1) {
        requireSameGlobal(other); requireSameRun(other)
        requireFirstCut(control.contentEquals(other.control) && paid.contentEquals(other.paid))
    }
    fun requireSameRequest(other: TestActiveFirstCutStateV1) = requireFirstCut(sequence == other.sequence && id == other.id &&
        epochBefore == other.epochBefore && requestOwner == other.requestOwner && requestToken == other.requestToken && requestedAt == other.requestedAt)
    fun controlFingerprint(): ByteArray = control.copyOf()
    fun slotFingerprint(): ByteArray = checkNotNull(paid).copyOf()
    override fun toString(): String = "TestActiveFirstCutStateV1(bounded-physical-comparisons,no-authority)"

    companion object {
        fun copy(row: ResultSet): TestActiveFirstCutStateV1 = TestActiveFirstCutStateV1(row)
    }
}

/** Immutable first-range identity only. It cannot be supplied to a constructor that issues Captured. */
internal class TestActiveFirstCutSlotV1 private constructor(val identity: TestActiveFirstCutIdentityV1, state: TestActiveFirstCutStateV1) {
    val operationToken: UUID = checkNotNull(state.id)
    val requestOwner: UUID = checkNotNull(state.requestOwner)
    val requestToken: Long = checkNotNull(state.requestToken)
    val requestedAt: Instant = checkNotNull(state.requestedAt)
    val captureOwner: UUID = checkNotNull(state.captureOwner)
    val captureToken: Long = checkNotNull(state.captureToken)
    val capturedAt: Instant = checkNotNull(state.capturedAt)
    val rotationSequence = 1L
    val chargedStorageBytes = TestActiveFirstSealStorageV1.STORAGE_BYTES
    val epochStart = 1L
    val epochEnd: Long = checkNotNull(state.epochBefore)
    val epochAfter: Long = checkNotNull(state.epochAfter)
    private val paid = state.slotFingerprint()
    init { requireFirstCut(state.state == "CAPTURED" && state.sequence == 1L && epochEnd == 1L && epochAfter == 2L && state.lease == null) }
    fun fingerprint(): ByteArray = paid.copyOf()
    companion object {
        /** Detached historical comparisons only; the private Captured issuer never accepts this as authority. */
        internal fun copy(identity: TestActiveFirstCutIdentityV1, state: TestActiveFirstCutStateV1): TestActiveFirstCutSlotV1 =
            TestActiveFirstCutSlotV1(identity, state)
    }
    override fun toString(): String = "TestActiveFirstCutSlotV1(historical-first-range,no-seal-or-lease-authority)"
}

internal fun copyFirstCutArguments(values: Array<Any?>): Array<Any?> = values.map { when (it) {
    is ByteArray -> it.copyOf()
    is Timestamp -> Timestamp.from(it.toInstant())
    is LongArray -> it.copyOf()
    else -> it
} }.toTypedArray()
private fun ResultSet.requiredLong(name: String): Long = getLong(name).also { requireFirstCut(!wasNull()) }
private fun ResultSet.nullableLong(name: String): Long? = getLong(name).let { if (wasNull()) null else it }
private fun ResultSet.requiredBoolean(name: String): Boolean = getBoolean(name).also { requireFirstCut(!wasNull()) }
private fun ResultSet.digest(name: String): ByteArray = checkNotNull(getBytes(name)).also { requireFirstCut(it.size == 32) }.copyOf()

internal fun requireFirstCut(value: Boolean) { if (!value) throw TestActiveFirstCutExceptionV1() }
internal class TestActiveFirstCutExceptionV1 : RuntimeException("ACTIVE TEST first cut refused.", null, false, false)
