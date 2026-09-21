package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.complaint.domain.ComplaintCapacityBalance
import me.manga.kira.backend.complaint.domain.ComplaintCapacityConfiguration
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDailyAdmission
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveFirstSealStorageV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalAccountingPlanV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationProjectionCountersV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainRowsV1
import java.sql.ResultSet

/** Bounded full current P/run/identity comparisons only. No ledger mutation, admission or recovered issuer. */
internal class TestActiveFirstCutSuccessorAccountingV1 private constructor(
    private val counters: CatalogTestRunActivationProjectionCountersV1,
    private val run: TestActiveFirstCutSuccessorRunV1,
    private val ids: Long,
    private val credentials: Long,
) {
    init {
        run.requireAccounting(counters, ids, credentials)
        // Aggregate sanity, not a per-row payment receipt. The fixed writer/provenance authenticates the paid slot.
        requireFirstCutSuccessor(counters.balance.actual[ComplaintCapacityCounter.STORAGE_BYTES] >= TestActiveFirstSealStorageV1.STORAGE_BYTES)
    }
    fun requireSame(other: TestActiveFirstCutSuccessorAccountingV1) {
        counters.requireSame(other.counters)
        run.requireSame(other.run)
        requireFirstCutSuccessor(ids == other.ids && credentials == other.credentials)
    }

    override fun toString(): String = "TestActiveFirstCutSuccessorAccountingV1(current-P22-run-identities,no-authority)"

    companion object {
        /** Cursor begins before the first row. Exactly the same22 typed names/order/ordinals and one daily bucket as P. */
        internal fun readCounters(rows: ResultSet, policy: ComplaintCapacityPolicyV1): CatalogTestRunActivationProjectionCountersV1 {
            val order = ComplaintCapacityEncoding.lockOrder()
            val vectors = List(6) { LongArray(ComplaintCapacityEncoding.WIDTH) }
            val fingerprints = ArrayList<ByteArray>(order.size)
            var configuration: ComplaintCapacityConfiguration? = null
            var daily: ComplaintDailyAdmission? = null
            var count = 0
            while (rows.next()) {
                requireFirstCutSuccessor(count < order.size)
                val counter = ComplaintCapacityEncoding.counter(rows.requiredInt("accounting_version"), rows.requiredInt("ordinal"), checkNotNull(rows.getString("name")))
                requireFirstCutSuccessor(counter === order[count] && rows.requiredBoolean("finite_times"))
                val selected = ComplaintCapacityConfiguration.of(rows.getBytes("configuration_hash"), rows.requiredBoolean("configuration_closed"))
                selected.requireCreationAllowed(policy.digestBytes())
                if (configuration == null) configuration = selected else requireFirstCutSuccessor(configuration == selected)
                listOf("hard_limit", "creation_limit", "free_units", "actual_units", "recovery_reserved_units", "test_reserved_units")
                    .forEachIndexed { index, column -> vectors[index][counter.storedOrdinal - 1] = rows.requiredLong(column) }
                val date = rows.getDate("admission_utc_date")
                val used = rows.nullableLong("admission_count")
                val limit = rows.nullableLong("admission_daily_limit")
                if (counter === ComplaintCapacityCounter.INSTALLATION_IDS) {
                    daily = ComplaintDailyAdmission(date?.toLocalDate()?.toEpochDay(), checkNotNull(used), checkNotNull(limit))
                } else requireFirstCutSuccessor(date == null && used == null && limit == null)
                fingerprints += rows.digest("row_digest")
                count++
            }
            requireFirstCutSuccessor(count == order.size)
            val balance = ComplaintCapacityBalance(
                ComplaintCapacityVector.of(vectors[0]), ComplaintCapacityVector.of(vectors[1]), ComplaintCapacityVector.of(vectors[2]),
                ComplaintCapacityVector.of(vectors[3]), ComplaintCapacityVector.of(vectors[4]), ComplaintCapacityVector.of(vectors[5]),
            )
            requireFirstCutSuccessor(balance.hardLimit == policy.hardLimit && balance.creationLimit == policy.creationLimit &&
                checkNotNull(daily).dailyLimit == policy.dailyEnrollmentLimit)
            return CatalogTestRunActivationProjectionCountersV1(checkNotNull(configuration), balance, checkNotNull(daily), fingerprints)
        }

        internal fun bind(counters: CatalogTestRunActivationProjectionCountersV1, run: TestActiveFirstCutSuccessorRunV1,
            ids: Long, credentials: Long): TestActiveFirstCutSuccessorAccountingV1 =
            TestActiveFirstCutSuccessorAccountingV1(counters, run, ids, credentials)
    }
}

/** Exact ACTIVE D enrollment/reserve equations, not old PROJECT balances or terminal spending authority. */
internal class TestActiveFirstCutSuccessorRunV1(row: ResultSet, identity: TestActiveFirstCutIdentityV1, maximumRetainedVersions: Long) {
    private val enrolled = row.requiredLong("enrolled_count")
    private val reserve = TestOrdinaryDrainRowsV1.vector(row, "original_reserve")
    private val unused = TestOrdinaryDrainRowsV1.vector(row, "unused_reserve")
    private val fingerprint = row.digest("fingerprint")
    init {
        val plan = TestTerminalAccountingPlanV1(identity.installationLimit, maximumRetainedVersions)
        requireFirstCutSuccessor(row.requiredLong("installation_limit") == identity.installationLimit && enrolled in 0..identity.installationLimit &&
            reserve == plan.originalUnusedReserve)
        val spent = TestTerminalCapacityChargesV1.INSTALLATION_SHARE.scaled(enrolled)
        requireFirstCutSuccessor(spent.fitsWithin(reserve) && unused == reserve - spent)
    }
    fun requireAccounting(counters: CatalogTestRunActivationProjectionCountersV1, ids: Long, credentials: Long) {
        requireFirstCutSuccessor(ids == enrolled && credentials in 0..ids && counters.balance.actual[ComplaintCapacityCounter.TEST_RUNS] == 1L &&
            counters.balance.testReserved == unused && TestTerminalCapacityChargesV1.ACTIVE_RUN.fitsWithin(counters.balance.actual) &&
            counters.balance.actual[ComplaintCapacityCounter.INSTALLATION_IDS] >= ids &&
            counters.balance.actual[ComplaintCapacityCounter.APP_INSTALLATIONS] >= credentials)
    }
    fun requireSame(other: TestActiveFirstCutSuccessorRunV1) = requireFirstCutSuccessor(
        enrolled == other.enrolled && reserve == other.reserve && unused == other.unused && fingerprint.contentEquals(other.fingerprint),
    )
    override fun toString(): String = "TestActiveFirstCutSuccessorRunV1(detached-current-enrollment-reserve,no-authority)"
}

internal fun requireFirstCutReserved(state: TestActiveFirstCutStateV1) {
    val requestToken = checkNotNull(state.requestToken)
    val requestedAt = checkNotNull(state.requestedAt)
    requireFirstCutSuccessor(state.sequence == 1L && state.id != null && state.epochBefore == 1L &&
        state.requestOwner != null && requestToken > 0 && state.leaseToken >= requestToken && requestedAt <= state.sampledAt)
    when (state.state) {
        "REQUESTED" -> requireFirstCutSuccessor(state.epoch == 1L && state.scanRequested && state.captureOwner == null &&
            state.captureToken == null && state.capturedAt == null && state.epochAfter == null)
        "CAPTURED" -> {
            val captureToken = checkNotNull(state.captureToken)
            val capturedAt = checkNotNull(state.capturedAt)
            requireFirstCutSuccessor(state.epoch == 2L && !state.scanRequested && state.captureOwner != null &&
                captureToken >= requestToken && state.leaseToken >= captureToken && capturedAt >= requestedAt &&
                capturedAt <= state.sampledAt && state.epochAfter == 2L)
        }
        else -> throw TestActiveFirstCutSuccessorExceptionV1()
    }
    state.slotFingerprint() // The unchanged C SQL verified every RESERVED V26 identity/provenance/null field.
}

internal fun requireSameFirstCutReserved(before: TestActiveFirstCutStateV1, after: TestActiveFirstCutStateV1) {
    before.requireSameGlobal(after); before.requireSameRun(after); before.requireSameRequest(after)
    requireFirstCutSuccessor(before.state == after.state && before.epoch == after.epoch && before.scanRequested == after.scanRequested &&
        before.captureOwner == after.captureOwner && before.captureToken == after.captureToken && before.capturedAt == after.capturedAt &&
        before.epochAfter == after.epochAfter && before.slotFingerprint().contentEquals(after.slotFingerprint()))
}

private fun ResultSet.requiredLong(name: String): Long = getLong(name).also { requireFirstCutSuccessor(!wasNull()) }
private fun ResultSet.nullableLong(name: String): Long? = getLong(name).let { if (wasNull()) null else it }
private fun ResultSet.requiredInt(name: String): Int = getInt(name).also { requireFirstCutSuccessor(!wasNull()) }
private fun ResultSet.requiredBoolean(name: String): Boolean = getBoolean(name).also { requireFirstCutSuccessor(!wasNull()) }
private fun ResultSet.digest(name: String): ByteArray = checkNotNull(getBytes(name)).also { requireFirstCutSuccessor(it.size == 32) }.copyOf()
