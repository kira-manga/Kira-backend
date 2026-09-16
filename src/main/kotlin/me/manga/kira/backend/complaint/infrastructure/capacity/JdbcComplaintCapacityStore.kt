package me.manga.kira.backend.complaint.infrastructure.capacity

import jakarta.persistence.EntityManager
import me.manga.kira.backend.audit.domain.ComplaintAuditAllocation
import me.manga.kira.backend.audit.domain.ComplaintAuditMutation
import me.manga.kira.backend.audit.domain.CountedComplaintAuditEntry
import me.manga.kira.backend.audit.domain.CountedInstallationEnrollmentAuditEntry
import me.manga.kira.backend.audit.infrastructure.ComplaintAuditInsertion
import me.manga.kira.backend.audit.infrastructure.ComplaintAuditSelectedHolder
import me.manga.kira.backend.audit.infrastructure.ComplaintInstallationEnrollmentAuditInsertion
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.complaint.domain.ComplaintCapacityBalance
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityConfiguration
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDailyAdmission
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentRejection
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentResult
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationOperation
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintDeletionOperation
import me.manga.kira.backend.security.ComplaintGrantCleanupBatch
import me.manga.kira.backend.security.ComplaintGrantConsumption
import me.manga.kira.backend.security.StepUpGrantIssuance
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Date
import java.sql.ResultSet
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Dormant, non-bean composition requires a separately trusted expected policy digest. Row agreement
 * does not authenticate configuration. No production policy producer or seed-opening initializer
 * is supplied here; focused fixtures must explicitly label their binding as synthetic.
 */
internal class JdbcComplaintCapacityStore(private val jdbc: JdbcTemplate, expectedPolicyDigest: ByteArray?) {
    private val expectedPolicyDigest = expectedPolicyDigest?.copyOf()

    internal fun lockForComplaintGrantCleanup(batch: ComplaintGrantCleanupBatch): LockedCleanupCounters = LockedCleanupCounters.lock(this, batch)

    internal fun chargeStepUpGrant(issuance: StepUpGrantIssuance): ChargedStepUpGrant = ChargedStepUpGrant.charge(this, issuance)

    internal fun chargeComplaintAudit(consumption: ComplaintGrantConsumption, mutation: ComplaintAuditMutation): ChargedComplaintAudit =
        ChargedComplaintAudit.charge(this, consumption, mutation)

    internal fun chargeDeletionAudit(operation: ComplaintDeletionOperation): ChargedComplaintAudit = ChargedComplaintAudit.chargeDeletion(this, operation)

    internal fun lockForRecoverySettlement(operation: ComplaintRecoverySettlementOperation): LockedRecoveryTransfer =
        LockedRecoveryTransfer.lock(this, operation)

    internal fun lockForTestReserveSpend(operation: ComplaintTestReserveSpendOperation): LockedTestReserveSpend = LockedTestReserveSpend.lock(this, operation)

    internal fun lockForInstallationEnrollment(operation: ComplaintInstallationEnrollmentOperation): LockedInstallationEnrollment =
        LockedInstallationEnrollment.lock(this, operation)

    internal fun lockForCatalogGenesis(operation: CatalogGenesisMutationOperation): LockedCatalogGenesis = LockedCatalogGenesis.lock(this, operation)

    private fun readLockedLedger(): ComplaintCapacityLedger = readLockedCounters().ledger

    private fun readLockedCounters(): LockedCounters {
        val rows = jdbc.query(LOCK_COUNTERS, { result, _ -> readCounter(result) })
        check(rows.map { it.counter } == ComplaintCapacityEncoding.lockOrder()) // Cardinality, order and exact unique catalogue together.
        val expected = checkNotNull(expectedPolicyDigest)
        val configuration = rows.first().configuration
        rows.forEach { row ->
            check(row.configuration == configuration)
            row.configuration.requireMatching(expected)
        }
        val ledger = ComplaintCapacityLedger(
            configuration,
            ComplaintCapacityBalance(
                hardLimit = rows.vector { it.hardLimit },
                creationLimit = rows.vector { it.creationLimit },
                free = rows.vector { it.free },
                actual = rows.vector { it.actual },
                recoveryReserved = rows.vector { it.recoveryReserved },
                testReserved = rows.vector { it.testReserved },
            ),
        )
        return LockedCounters(ledger, checkNotNull(rows.single { it.counter === ComplaintCapacityCounter.INSTALLATION_IDS }.daily))
    }

    private fun readCounter(result: ResultSet): CounterRow {
        val counter = ComplaintCapacityEncoding.counter(
            requiredInt(result, "accounting_version"),
            requiredInt(result, "ordinal"),
            requireNotNull(result.getString("name")),
        )
        check(requiredBoolean(result, "finite_times"))
        val date = result.getDate("admission_utc_date")
        val count = nullableLong(result, "admission_count")
        val limit = nullableLong(result, "admission_daily_limit")
        val daily = if (counter === ComplaintCapacityCounter.INSTALLATION_IDS) {
            ComplaintDailyAdmission(date?.toLocalDate()?.toEpochDay(), checkNotNull(count), checkNotNull(limit))
        } else {
            check(date == null && count == null && limit == null)
            null
        }
        return CounterRow(
            counter,
            ComplaintCapacityConfiguration.of(result.getBytes("configuration_hash"), requiredBoolean(result, "configuration_closed")),
            requiredLong(result, "hard_limit"),
            requiredLong(result, "creation_limit"),
            requiredLong(result, "free_units"),
            requiredLong(result, "actual_units"),
            requiredLong(result, "recovery_reserved_units"),
            requiredLong(result, "test_reserved_units"),
            daily,
        )
    }

    private fun persistRefund(
        locked: LockedCleanupCounters,
        batch: ComplaintGrantCleanupBatch,
        before: ComplaintCapacityBalance,
        after: ComplaintCapacityBalance,
    ) {
        for (counter in REFUND_COUNTERS) {
            batch.requireCounterRefund(locked, jdbc)
            val updated = jdbc.update(
                REFUND_COUNTER,
                after.free[counter],
                after.actual[counter],
                counter.storedName,
                before.free[counter],
                before.actual[counter],
            )
            check(updated == 1) // A missing or changed locked row poisons the same phase, including after the first update.
        }
        batch.requireCounterRefund(locked, jdbc)
    }

    private fun persistGrantCharge(
        charged: ChargedStepUpGrant,
        issuance: StepUpGrantIssuance,
        before: ComplaintCapacityBalance,
        after: ComplaintCapacityBalance,
    ) {
        for (counter in REFUND_COUNTERS) {
            issuance.requireCounterCharge(charged, jdbc)
            val updated = jdbc.update(
                REFUND_COUNTER,
                after.free[counter],
                after.actual[counter],
                counter.storedName,
                before.free[counter],
                before.actual[counter],
            )
            check(updated == 1)
        }
        issuance.requireCounterCharge(charged, jdbc)
    }

    private fun persistAuditCharge(charged: ChargedComplaintAudit, before: ComplaintCapacityBalance, after: ComplaintCapacityBalance) {
        for (counter in AUDIT_COUNTERS) {
            charged.requireCharge(jdbc)
            val updated = jdbc.update(
                REFUND_COUNTER,
                after.free[counter],
                after.actual[counter],
                counter.storedName,
                before.free[counter],
                before.actual[counter],
            )
            check(updated == 1)
        }
        charged.requireCharge(jdbc)
    }

    private fun List<CounterRow>.vector(amount: (CounterRow) -> Long): ComplaintCapacityVector {
        val units = LongArray(ComplaintCapacityEncoding.WIDTH)
        forEach { units[it.counter.storedOrdinal - 1] = amount(it) }
        return ComplaintCapacityVector.of(units)
    }

    override fun toString(): String = "JdbcComplaintCapacityStore(redacted)"

    /** G1's sole row prepays its complete lifecycle once. Replay/signature never spend free capacity again. */
    internal class LockedCatalogGenesis private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: CatalogGenesisMutationOperation,
        private val before: ComplaintCapacityLedger,
    ) {
        private var issued = false
        private var settled = false

        internal fun belongsTo(candidate: CatalogGenesisMutationOperation): Boolean = operation === candidate

        internal fun settledFor(candidate: CatalogGenesisMutationOperation): Boolean = belongsTo(candidate) && settled

        @Suppress("TooGenericExceptionCaught")
        internal fun settle(candidate: CatalogGenesisMutationOperation) {
            try {
                check(candidate === operation && !issued)
                val existing = operation.requireCounterSettlement(this, store.jdbc)
                issued = true
                val expected = checkNotNull(store.expectedPolicyDigest)
                val balance = before.balance
                check(balance.actual[ComplaintCapacityCounter.CATALOG_MUTATIONS] == if (existing) 1L else 0L)
                if (existing) {
                    // Sanity only, not a per-row accounting receipt: storage includes other classes.
                    // Legitimate ownership comes from atomic PREPARED+charge; restored aggregate drift
                    // still requires the separately drained reconciliation, never this lower bound.
                    check(balance.actual[ComplaintCapacityCounter.STORAGE_BYTES] >= CatalogGenesisCapacity.storageBytes)
                } else {
                    val after = before.chargeCreation(expected, CatalogGenesisCapacity.charge).balance
                    for (counter in CATALOG_GENESIS_COUNTERS) {
                        operation.requireCounterSettlement(this, store.jdbc)
                        check(
                            store.jdbc.update(
                                CHARGE_ENROLLMENT_COUNTER,
                                after.free[counter], after.actual[counter], counter.storedName, counter.storedOrdinal, expected,
                                balance.hardLimit[counter], balance.creationLimit[counter], balance.free[counter], balance.actual[counter],
                                balance.recoveryReserved[counter], balance.testReserved[counter],
                            ) == 1,
                        )
                    }
                }
                operation.requireCounterSettlement(this, store.jdbc)
                settled = true
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        companion object {
            @Suppress("TooGenericExceptionCaught")
            internal fun lock(store: JdbcComplaintCapacityStore, operation: CatalogGenesisMutationOperation): LockedCatalogGenesis {
                try {
                    operation.beginCounterLock(store.jdbc)
                    return LockedCatalogGenesis(store, operation, store.readLockedLedger())
                } catch (problem: Throwable) {
                    operation.failed(problem)
                }
            }
        }
    }

    /** A real new pair, its daily admission and scope-only audit share this one-use locked allocation. */
    internal class LockedInstallationEnrollment private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: ComplaintInstallationEnrollmentOperation,
        private val before: LockedCounters,
    ) : ComplaintAuditAllocation {
        private var issued = false
        private var charged = false
        private var audit: ComplaintInstallationEnrollmentAuditInsertion? = null

        internal fun belongsTo(candidate: ComplaintInstallationEnrollmentOperation): Boolean = operation === candidate

        internal fun chargedFor(candidate: ComplaintInstallationEnrollmentOperation): Boolean = belongsTo(candidate) && charged

        internal fun completedFor(candidate: ComplaintInstallationEnrollmentOperation): Boolean = chargedFor(candidate) && audit?.completedFor(this) == true

        @Suppress("TooGenericExceptionCaught")
        internal fun chargeNewIdentity(candidate: ComplaintInstallationEnrollmentOperation): InstallationEnrollmentResult.Rejected? {
            try {
                check(candidate === operation)
                operation.requireNewIdentityCharge(this, store.jdbc)
                check(!issued)
                issued = true
                val expected = checkNotNull(store.expectedPolicyDigest)
                // Read/validated ledger first; subtraction-style headroom avoids overflowing a valid full Long ceiling.
                if (!creationAvailable(operation.creationCharge(store.jdbc)) || before.daily.dailyLimit == 0L || !operation.testSlotAvailable(store.jdbc)) {
                    return InstallationEnrollmentResult.Rejected(InstallationEnrollmentRejection.CAPACITY_UNAVAILABLE)
                }
                val after = operation.chargeLedger(store.jdbc, before.ledger, expected).balance
                val day = operation.databaseUtcEpochDay(store.jdbc)
                val retainedDay = before.daily.utcEpochDay
                if (retainedDay != null && day <= retainedDay && before.daily.count >= before.daily.dailyLimit) {
                    val reset = LocalDate.ofEpochDay(retainedDay).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
                    val remaining = Duration.between(operation.databaseInstant(store.jdbc), reset)
                    val retryAfter = Math.addExact(remaining.seconds, if (remaining.nano == 0) 0L else 1L)
                    check(retryAfter > 0)
                    return InstallationEnrollmentResult.Rejected(InstallationEnrollmentRejection.DAILY_LIMIT_REACHED, retryAfter)
                }
                val daily = before.daily.admitNewIdentity(day)
                // All equations, OPEN configuration, lifetime ceiling and daily limit precede the first update.
                for (counter in ENROLLMENT_COUNTERS) {
                    operation.requireNewIdentityCharge(this, store.jdbc)
                    persistCharge(counter, after, daily, expected)
                }
                operation.requireNewIdentityCharge(this, store.jdbc)
                charged = true
                return null
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        private fun creationAvailable(charge: ComplaintCapacityVector): Boolean {
            if (before.ledger.configuration.creationClosed) return false
            val balance = before.ledger.balance
            val committed = balance.committedUnits
            return ComplaintCapacityCounter.entries.all { counter ->
                val used = committed[counter]
                val limit = balance.creationLimit[counter]
                used <= limit && charge[counter] <= limit - used
            }
        }

        private fun persistCharge(counter: ComplaintCapacityCounter, after: ComplaintCapacityBalance, daily: ComplaintDailyAdmission, expected: ByteArray) {
            val old = before.ledger.balance
            val arguments = arrayOf(
                after.free[counter], after.actual[counter], after.testReserved[counter], counter.storedName, counter.storedOrdinal, expected,
                old.hardLimit[counter], old.creationLimit[counter], old.free[counter], old.actual[counter],
                old.recoveryReserved[counter], old.testReserved[counter],
            )
            val updated = if (counter === ComplaintCapacityCounter.INSTALLATION_IDS) {
                store.jdbc.update(
                    ADMIT_INSTALLATION_COUNTER,
                    Date.valueOf(LocalDate.ofEpochDay(checkNotNull(daily.utcEpochDay))),
                    daily.count,
                    *arguments,
                    before.daily.utcEpochDay?.let { Date.valueOf(LocalDate.ofEpochDay(it)) },
                    before.daily.count,
                    before.daily.dailyLimit,
                )
            } else {
                store.jdbc.update(CHARGE_ENROLLMENT_COUNTER, *arguments)
            }
            check(updated == 1)
        }

        internal fun beginAuditInsert(insertion: ComplaintInstallationEnrollmentAuditInsertion, entry: CountedInstallationEnrollmentAuditEntry): EntityManager {
            val entityManager = operation.auditEntityManager(this, entry, store.jdbc)
            if (!charged || audit != null || !insertion.belongsTo(this)) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
            audit = insertion // Retain before the shared adapter's persist/flush; no retry after a partial insertion.
            return entityManager
        }

        internal fun requireAuditInsert(insertion: ComplaintInstallationEnrollmentAuditInsertion) {
            operation.requireAuditWrite(this, store.jdbc)
            if (audit !== insertion) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
        }

        internal fun failed(problem: Throwable): Nothing = operation.failed(problem)

        override fun toString(): String = "LockedInstallationEnrollment(redacted)"

        companion object {
            @Suppress("TooGenericExceptionCaught")
            internal fun lock(store: JdbcComplaintCapacityStore, operation: ComplaintInstallationEnrollmentOperation): LockedInstallationEnrollment {
                try {
                    operation.beginCounterLock(store.jdbc)
                    val before = store.readLockedCounters()
                    val locked = LockedInstallationEnrollment(store, operation, before)
                    operation.checkAdmissionBounds(locked, store.jdbc, before.ledger, before.daily)
                    return locked
                } catch (problem: Throwable) {
                    operation.failed(problem)
                }
            }
        }
    }

    /** Only the retained original reservation can spend this checked, one-use full-ledger transfer. */
    internal class LockedRecoveryTransfer private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: ComplaintRecoverySettlementOperation,
        private val before: ComplaintCapacityBalance,
        private val after: ComplaintCapacityBalance,
    ) {
        private var issued = false
        private var completed = false

        internal fun belongsTo(candidate: ComplaintRecoverySettlementOperation): Boolean = operation === candidate

        internal fun completedFor(candidate: ComplaintRecoverySettlementOperation): Boolean = belongsTo(candidate) && completed

        @Suppress("TooGenericExceptionCaught")
        internal fun transfer(candidate: ComplaintRecoverySettlementOperation) {
            try {
                check(candidate === operation)
                operation.requireCounterTransfer(this, store.jdbc)
                check(!issued)
                issued = true
                for (counter in ComplaintCapacityEncoding.lockOrder()) {
                    operation.requireCounterTransfer(this, store.jdbc)
                    if (before.free[counter] == after.free[counter] && before.actual[counter] == after.actual[counter] &&
                        before.recoveryReserved[counter] == after.recoveryReserved[counter]
                    ) {
                        continue
                    }
                    val updated = store.jdbc.update(
                        RECOVERY_COUNTER,
                        after.free[counter], after.actual[counter], after.recoveryReserved[counter], counter.storedName,
                        before.free[counter], before.actual[counter], before.recoveryReserved[counter], before.testReserved[counter],
                    )
                    check(updated == 1)
                }
                operation.requireCounterTransfer(this, store.jdbc)
                completed = true // Replay reaches this only after the same full-ledger/configuration validation, without UPDATEs.
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        override fun toString(): String = "LockedRecoveryTransfer(redacted)"

        companion object {
            @Suppress("TooGenericExceptionCaught")
            internal fun lock(store: JdbcComplaintCapacityStore, operation: ComplaintRecoverySettlementOperation): LockedRecoveryTransfer {
                try {
                    operation.beginCounterLock(store.jdbc)
                    val ledger = store.readLockedLedger()
                    val after = operation.convertLockedLedger(store.jdbc, ledger, checkNotNull(store.expectedPolicyDigest))
                    return LockedRecoveryTransfer(store, operation, ledger.balance, after.balance)
                } catch (problem: Throwable) {
                    operation.failed(problem)
                }
            }
        }
    }

    /** Counters are retained before run locking; only that operation may spend its subsequently validated unused slice. */
    internal class LockedTestReserveSpend private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: ComplaintTestReserveSpendOperation,
        private val ledger: ComplaintCapacityLedger,
    ) {
        private var issued = false
        private var completed = false

        internal fun belongsTo(candidate: ComplaintTestReserveSpendOperation): Boolean = operation === candidate

        internal fun completedFor(candidate: ComplaintTestReserveSpendOperation): Boolean = belongsTo(candidate) && completed

        @Suppress("TooGenericExceptionCaught")
        internal fun transfer(candidate: ComplaintTestReserveSpendOperation) {
            try {
                check(candidate === operation)
                operation.requireCounterTransfer(this, store.jdbc)
                check(!issued)
                issued = true
                val before = ledger.balance
                val after = operation.spendLockedLedger(store.jdbc, ledger, checkNotNull(store.expectedPolicyDigest)).balance
                for (counter in ComplaintCapacityEncoding.lockOrder()) {
                    operation.requireCounterTransfer(this, store.jdbc)
                    if (before.actual[counter] == after.actual[counter] && before.recoveryReserved[counter] == after.recoveryReserved[counter] &&
                        before.testReserved[counter] == after.testReserved[counter]
                    ) {
                        continue
                    }
                    val updated = store.jdbc.update(
                        TEST_RESERVE_COUNTER,
                        after.actual[counter], after.recoveryReserved[counter], after.testReserved[counter], counter.storedName,
                        before.free[counter], before.actual[counter], before.recoveryReserved[counter], before.testReserved[counter],
                    )
                    check(updated == 1)
                }
                operation.requireCounterTransfer(this, store.jdbc)
                completed = true
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        override fun toString(): String = "LockedTestReserveSpend(redacted)"

        companion object {
            @Suppress("TooGenericExceptionCaught")
            internal fun lock(store: JdbcComplaintCapacityStore, operation: ComplaintTestReserveSpendOperation): LockedTestReserveSpend {
                try {
                    operation.beginCounterLock(store.jdbc)
                    return LockedTestReserveSpend(store, operation, store.readLockedLedger())
                } catch (problem: Throwable) {
                    operation.failed(problem)
                }
            }
        }
    }

    /** Immutable checked rows plus one private consumption bit; never a caller-minted count/vector update endpoint. */
    internal class LockedCleanupCounters private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val batch: ComplaintGrantCleanupBatch,
        private val before: ComplaintCapacityBalance,
        private val after: ComplaintCapacityBalance,
    ) {
        private var refundIssued = false

        internal fun belongsTo(candidate: ComplaintGrantCleanupBatch): Boolean = batch === candidate

        // All late failures, not just SQLExceptions, poison the retained batch before they can be caught outside this adapter.
        @Suppress("TooGenericExceptionCaught")
        internal fun refundDeletedBatch(candidate: ComplaintGrantCleanupBatch) {
            try {
                check(candidate === batch)
                batch.requireCounterRefund(this, store.jdbc)
                check(!refundIssued)
                refundIssued = true
                store.persistRefund(this, batch, before, after)
            } catch (failure: Throwable) {
                batch.failed(failure)
            }
        }

        override fun toString(): String = "LockedCleanupCounters(redacted)"

        companion object {
            @Suppress("TooGenericExceptionCaught")
            internal fun lock(store: JdbcComplaintCapacityStore, batch: ComplaintGrantCleanupBatch): LockedCleanupCounters {
                try {
                    batch.beginCounterLock(store.jdbc)
                    val ledger = store.readLockedLedger()
                    val refunded = ledger.refundActual(checkNotNull(store.expectedPolicyDigest), batch.selectedCharge(store.jdbc))
                    // Checked arithmetic/underflow is refused before the first deletion. Only actual per-row DELETE == 1 can consume this result.
                    return LockedCleanupCounters(store, batch, ledger.balance, refunded.balance)
                } catch (failure: Throwable) {
                    batch.failed(failure)
                }
            }
        }
    }

    /** Only real locked, checked and fully updated counters can mint this operation-specific handle. */
    internal class ChargedStepUpGrant private constructor(private val issuance: StepUpGrantIssuance) {
        private var completed = false

        internal fun belongsTo(candidate: StepUpGrantIssuance): Boolean = issuance === candidate

        internal fun completedFor(candidate: StepUpGrantIssuance): Boolean = issuance === candidate && completed

        override fun toString(): String = "ChargedStepUpGrant(redacted)"

        companion object {
            @Suppress("TooGenericExceptionCaught")
            internal fun charge(store: JdbcComplaintCapacityStore, issuance: StepUpGrantIssuance): ChargedStepUpGrant {
                try {
                    issuance.beginCounterCharge(store.jdbc)
                    val ledger = store.readLockedLedger()
                    val after = ledger.chargeCreation(checkNotNull(store.expectedPolicyDigest), ComplaintCapacityCharges.MODERATION_GRANT)
                    val charged = ChargedStepUpGrant(issuance)
                    issuance.retainCounterCharge(charged, store.jdbc)
                    store.persistGrantCharge(charged, issuance, ledger.balance, after.balance)
                    charged.completed = true
                    return charged
                } catch (problem: Throwable) {
                    issuance.failed(problem)
                }
            }
        }
    }

    /** One real charge bound to the exact typed mutation and selected operation/phase. No caller can mint or reuse it. */
    internal class ChargedComplaintAudit private constructor(private val source: Source, private val mutation: ComplaintAuditMutation) :
        ComplaintAuditAllocation {
        private var charged = false
        private var insertion: ComplaintAuditInsertion? = null

        internal fun belongsTo(candidate: ComplaintGrantConsumption): Boolean = source is Source.Grant && source.consumption === candidate

        internal fun belongsTo(candidate: ComplaintDeletionOperation): Boolean = source is Source.Deletion && source.operation === candidate

        internal fun chargedFor(candidate: ComplaintGrantConsumption): Boolean = belongsTo(candidate) && charged

        internal fun chargedFor(candidate: ComplaintDeletionOperation): Boolean = belongsTo(candidate) && charged

        internal fun completedFor(candidate: ComplaintGrantConsumption): Boolean = chargedFor(candidate) && insertion?.completedFor(this) == true

        internal fun completedFor(candidate: ComplaintDeletionOperation): Boolean = chargedFor(candidate) && insertion?.completedFor(this) == true

        internal fun beginInsert(candidate: ComplaintAuditInsertion, entry: CountedComplaintAuditEntry): ComplaintAuditSelectedHolder {
            val holder = source.holder(this)
            check(insertion == null && candidate.belongsTo(this) && entry.mutation === mutation)
            if (source is Source.Deletion) source.operation.requireAuditEntry(entry)
            insertion = candidate // Spent before either insert; a failed insert can never reuse its allocation.
            return holder
        }

        internal fun requireInsert(candidate: ComplaintAuditInsertion) {
            source.holder(this)
            check(insertion === candidate)
        }

        internal fun requireCharge(jdbc: JdbcTemplate) = source.requireCharge(this, jdbc)

        internal fun failed(problem: Throwable): Nothing = source.failed(problem)

        override fun toString(): String = "ChargedComplaintAudit(redacted)"

        /** Only the two existing-phase producers select a holder; neither accepts a resource flag or connection. */
        private sealed interface Source {
            fun beginCharge(jdbc: JdbcTemplate)
            fun retainCharge(charged: ChargedComplaintAudit, jdbc: JdbcTemplate)
            fun requireCharge(charged: ChargedComplaintAudit, jdbc: JdbcTemplate)
            fun holder(charged: ChargedComplaintAudit): ComplaintAuditSelectedHolder
            fun failed(problem: Throwable): Nothing

            class Grant(val consumption: ComplaintGrantConsumption) : Source {
                override fun beginCharge(jdbc: JdbcTemplate) = consumption.beginAuditCharge(jdbc)
                override fun retainCharge(charged: ChargedComplaintAudit, jdbc: JdbcTemplate) = consumption.retainAuditCharge(charged, jdbc)
                override fun requireCharge(charged: ChargedComplaintAudit, jdbc: JdbcTemplate) = consumption.requireAuditCharge(charged, jdbc)
                override fun holder(charged: ChargedComplaintAudit): ComplaintAuditSelectedHolder = consumption.auditHolder(charged)

                override fun failed(problem: Throwable): Nothing = consumption.failed(problem)
            }

            class Deletion(val operation: ComplaintDeletionOperation) : Source {
                override fun beginCharge(jdbc: JdbcTemplate) = operation.beginAuditCharge(jdbc)
                override fun retainCharge(charged: ChargedComplaintAudit, jdbc: JdbcTemplate) = operation.retainAuditCharge(charged, jdbc)
                override fun requireCharge(charged: ChargedComplaintAudit, jdbc: JdbcTemplate) = operation.requireAuditCharge(charged, jdbc)
                override fun holder(charged: ChargedComplaintAudit): ComplaintAuditSelectedHolder =
                    ComplaintAuditSelectedHolder.Deletion(operation.auditConnection(charged))

                override fun failed(problem: Throwable): Nothing = operation.failed(problem)
            }
        }

        companion object {
            internal fun charge(
                store: JdbcComplaintCapacityStore,
                consumption: ComplaintGrantConsumption,
                mutation: ComplaintAuditMutation,
            ): ChargedComplaintAudit = charge(store, Source.Grant(consumption), mutation)

            internal fun chargeDeletion(store: JdbcComplaintCapacityStore, operation: ComplaintDeletionOperation): ChargedComplaintAudit =
                charge(store, Source.Deletion(operation), operation.mutation)

            @Suppress("TooGenericExceptionCaught")
            private fun charge(store: JdbcComplaintCapacityStore, source: Source, mutation: ComplaintAuditMutation): ChargedComplaintAudit {
                try {
                    source.beginCharge(store.jdbc)
                    val ledger = store.readLockedLedger()
                    val after = ledger.chargeCreation(checkNotNull(store.expectedPolicyDigest), ComplaintCapacityCharges.AUDIT)
                    val allocation = ChargedComplaintAudit(source, mutation)
                    source.retainCharge(allocation, store.jdbc)
                    store.persistAuditCharge(allocation, ledger.balance, after.balance)
                    allocation.charged = true
                    return allocation
                } catch (problem: Throwable) {
                    source.failed(problem)
                }
            }
        }
    }

    private class CounterRow(
        val counter: ComplaintCapacityCounter,
        val configuration: ComplaintCapacityConfiguration,
        val hardLimit: Long,
        val creationLimit: Long,
        val free: Long,
        val actual: Long,
        val recoveryReserved: Long,
        val testReserved: Long,
        val daily: ComplaintDailyAdmission?,
    )

    private class LockedCounters(val ledger: ComplaintCapacityLedger, val daily: ComplaintDailyAdmission)

    private companion object {
        val REFUND_COUNTERS = listOf(ComplaintCapacityCounter.MODERATION_GRANTS, ComplaintCapacityCounter.STORAGE_BYTES)
        val AUDIT_COUNTERS = listOf(ComplaintCapacityCounter.AUDIT_ROWS, ComplaintCapacityCounter.STORAGE_BYTES)
        val CATALOG_GENESIS_COUNTERS = listOf(ComplaintCapacityCounter.CATALOG_MUTATIONS, ComplaintCapacityCounter.STORAGE_BYTES)
        val ENROLLMENT_COUNTERS = listOf(
            ComplaintCapacityCounter.APP_INSTALLATIONS,
            ComplaintCapacityCounter.AUDIT_ROWS,
            ComplaintCapacityCounter.INSTALLATION_IDS,
            ComplaintCapacityCounter.STORAGE_BYTES,
        )
        val LOCK_COUNTERS = """
            SELECT name, ordinal, accounting_version, configuration_hash, configuration_closed,
                hard_limit, creation_limit, free_units, actual_units, recovery_reserved_units, test_reserved_units,
                admission_utc_date, admission_count, admission_daily_limit,
                isfinite(updated_at) AND (admission_utc_date IS NULL OR isfinite(admission_utc_date)) AS finite_times
            FROM complaint_capacity_counters
            ORDER BY name COLLATE "C"
            FOR UPDATE
        """.trimIndent()
        val REFUND_COUNTER = """
            UPDATE complaint_capacity_counters
            SET free_units = ?, actual_units = ?, updated_at = now()
            WHERE name = ? AND free_units = ? AND actual_units = ?
        """.trimIndent()
        val RECOVERY_COUNTER = """
            UPDATE complaint_capacity_counters
            SET free_units = ?, actual_units = ?, recovery_reserved_units = ?, updated_at = now()
            WHERE name = ? AND free_units = ? AND actual_units = ? AND recovery_reserved_units = ? AND test_reserved_units = ?
        """.trimIndent()
        val TEST_RESERVE_COUNTER = """
            UPDATE complaint_capacity_counters
            SET actual_units = ?, recovery_reserved_units = ?, test_reserved_units = ?, updated_at = now()
            WHERE name = ? AND free_units = ? AND actual_units = ? AND recovery_reserved_units = ? AND test_reserved_units = ?
        """.trimIndent()
        val ENROLLMENT_COUNTER_MATCH = """
            WHERE name = ? AND ordinal = ? AND accounting_version = 1 AND configuration_hash = ? AND NOT configuration_closed
                AND hard_limit = ? AND creation_limit = ? AND free_units = ? AND actual_units = ?
                AND recovery_reserved_units = ? AND test_reserved_units = ?
        """.trimIndent()
        val CHARGE_ENROLLMENT_COUNTER = """
            UPDATE complaint_capacity_counters
            SET free_units = ?, actual_units = ?, test_reserved_units = ?, updated_at = clock_timestamp()
        """.trimIndent() + "\n" + ENROLLMENT_COUNTER_MATCH
        val ADMIT_INSTALLATION_COUNTER = """
            UPDATE complaint_capacity_counters
            SET admission_utc_date = ?, admission_count = ?, free_units = ?, actual_units = ?, test_reserved_units = ?, updated_at = clock_timestamp()
        """.trimIndent() + "\n" + ENROLLMENT_COUNTER_MATCH +
            " AND admission_utc_date IS NOT DISTINCT FROM ?::date AND admission_count = ? AND admission_daily_limit = ?"

        fun requiredInt(result: ResultSet, column: String): Int = result.getInt(column).also { check(!result.wasNull()) }

        fun requiredBoolean(result: ResultSet, column: String): Boolean = result.getBoolean(column).also { check(!result.wasNull()) }

        fun nullableLong(result: ResultSet, column: String): Long? = result.getLong(column).let { if (result.wasNull()) null else it }

        fun requiredLong(result: ResultSet, column: String): Long = checkNotNull(nullableLong(result, column))
    }
}
