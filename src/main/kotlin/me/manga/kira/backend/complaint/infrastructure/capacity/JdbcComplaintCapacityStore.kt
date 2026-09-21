package me.manga.kira.backend.complaint.infrastructure.capacity

import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveInitialCheckpointOperationV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointStorageV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutOperationV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveFirstSealStorageV1

import me.manga.kira.backend.complaint.domain.ComplaintDataScope

import me.manga.kira.backend.audit.domain.CountedAdminDeleteAuditEntry
import me.manga.kira.backend.audit.infrastructure.ComplaintAdminDeleteAuditInsertion
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteAuthorizationOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteApplyOperation
import me.manga.kira.backend.audit.domain.CountedOwnerDeleteAuditEntry
import me.manga.kira.backend.audit.infrastructure.ComplaintOwnerDeleteAuditInsertion
import me.manga.kira.backend.complaint.domain.OwnerDeleteCapacityCharges
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAuthorizationOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteRegisteredReloadOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteRegisteredSelectionOperation
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRegistrationOperationV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestInitialAdmissionOperationV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationOperationV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceActiveRegistrationOperationV1
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteApplyOperation
import jakarta.persistence.EntityManager
import me.manga.kira.backend.audit.domain.ComplaintAuditAllocation
import me.manga.kira.backend.audit.domain.ComplaintAuditMutation
import me.manga.kira.backend.audit.domain.CountedComplaintAuditEntry
import me.manga.kira.backend.audit.domain.CountedInstallationDeleteAuthorizationAuditEntry
import me.manga.kira.backend.audit.domain.CountedInstallationEnrollmentAuditEntry
import me.manga.kira.backend.audit.domain.CountedOwnerDeleteAllAuditEntry
import me.manga.kira.backend.audit.infrastructure.ComplaintAuditInsertion
import me.manga.kira.backend.audit.infrastructure.ComplaintAuditSelectedHolder
import me.manga.kira.backend.audit.infrastructure.ComplaintInstallationDeleteAuthorizationAuditInsertion
import me.manga.kira.backend.audit.infrastructure.ComplaintInstallationEnrollmentAuditInsertion
import me.manga.kira.backend.audit.infrastructure.ComplaintOwnerDeleteAllAuditInsertion
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusReceipt
import me.manga.kira.backend.complaint.domain.ComplaintCapacityBalance
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityConfiguration
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDailyAdmission
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReceipt
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentRejection
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentResult
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerRotationActivationCapacityV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerRotationCapacityV1
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminContentOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminStatusMutation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminBatchStatusMutation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllApplyOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerEditOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationActivationOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFinalizationOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationKindV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationOperationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationProjectionCountersV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintDeletionOperation
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestPublicationOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgeOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalEpochSealOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainOperationV1
import me.manga.kira.backend.security.ComplaintGrantCleanupBatch
import me.manga.kira.backend.security.ComplaintGrantConsumption
import me.manga.kira.backend.security.StepUpGrantIssuance
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Connection
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

    internal fun lockForTestRunSealedAudit(operation: TestRunSealingOperationV1): LockedTestRunSealedAudit = LockedTestRunSealedAudit.lock(this, operation)
    internal fun lockForTestInstallationManifest(operation: TestInstallationManifestOperationV1): LockedTestInstallationManifest = LockedTestInstallationManifest.lock(this, operation)
    internal fun lockForTestInstallationManifestPublication(operation: TestInstallationManifestPublicationOperationV1): LockedTestInstallationManifestPublication = LockedTestInstallationManifestPublication.lock(this, operation)
    internal fun lockForTestRunPurge(operation: TestRunPurgeOperationV1): LockedTestRunPurge = LockedTestRunPurge.lock(this, operation)
    internal fun lockForTestActiveFirstCut(operation: TestActiveFirstCutOperationV1): LockedTestActiveFirstCut = LockedTestActiveFirstCut.lock(this, operation)
    internal fun lockForTestActiveInitialCheckpoint(operation: TestActiveInitialCheckpointOperationV1): LockedTestActiveInitialCheckpoint = LockedTestActiveInitialCheckpoint.lock(this, operation)

    internal fun lockForTestTerminalEpochSeal(operation: TestTerminalEpochSealOperationV1): LockedTestTerminalEpochSeal = LockedTestTerminalEpochSeal.lock(this, operation)
    internal fun lockForTestOrdinarySeal(operation: TestOrdinarySealOperationV1): LockedTestOrdinarySeal = LockedTestOrdinarySeal.lock(this, operation)
    internal fun lockForTestOrdinaryDrain(operation: TestOrdinaryDrainOperationV1): LockedTestOrdinaryDrain = LockedTestOrdinaryDrain.lock(this, operation)

    internal fun lockForInstallationEnrollment(operation: ComplaintInstallationEnrollmentOperation): LockedInstallationEnrollment =
        LockedInstallationEnrollment.lock(this, operation)

    internal fun lockForCatalogGenesis(operation: CatalogGenesisMutationOperation): LockedCatalogGenesis = LockedCatalogGenesis.lock(this, operation)

    internal fun lockForCatalogSignerRotation(operation: CatalogSignerRotationOperationV1): LockedCatalogSignerRotation =
        LockedCatalogSignerRotation.lock(this, operation)

    internal fun lockForCatalogSignerRotationFinalization(operation: CatalogSignerRotationFinalizationOperationV1): LockedCatalogSignerRotationFinalization =
        LockedCatalogSignerRotationFinalization.lock(this, operation)

    internal fun lockForCatalogSignerRotationActivation(operation: CatalogSignerRotationActivationOperationV1): LockedCatalogSignerRotationActivation =
        LockedCatalogSignerRotationActivation.lock(this, operation)

    internal fun lockForCatalogTestRunActivation(operation: CatalogTestRunActivationOperationV1): LockedCatalogTestRunActivation =
        LockedCatalogTestRunActivation.lock(this, operation)

    /** Fixed registration read only. No settlement/update capability is returned. */
    internal fun lockForTestNamespaceRegistration(operation: TestNamespaceRegistrationOperationV1): CatalogTestRunActivationProjectionCountersV1 {
        operation.beginCounterLock(jdbc)
        val result = projectionCounters(readLockedCounters())
        operation.requireCounterRead(jdbc)
        return result
    }

    /** Fixed initial-admission comparison only. No settlement/update capability is returned. */
    internal fun lockForTestInitialAdmission(operation: TestInitialAdmissionOperationV1): CatalogTestRunActivationProjectionCountersV1 {
        operation.beginCounterLock(jdbc)
        val result = projectionCounters(readLockedCounters())
        operation.requireCounterRead(jdbc)
        return result
    }

    /** Fixed cold recovery registration read only. No settlement/update capability is returned. */
    internal fun lockForTestNamespaceRecoveryRegistration(operation: TestNamespaceRecoveryRegistrationOperationV1): CatalogTestRunActivationProjectionCountersV1 {
        operation.beginCounterLock(jdbc)
        val result = projectionCounters(readLockedCounters())
        operation.requireCounterRead(jdbc)
        return result
    }

    /** Fixed cold ACTIVE accounting read only. No settlement/update capability is returned. */
    internal fun lockForTestNamespaceActiveRegistration(operation: TestNamespaceActiveRegistrationOperationV1): CatalogTestRunActivationProjectionCountersV1 {
        operation.beginCounterLock(jdbc)
        val result = projectionCounters(readLockedCounters())
        operation.requireCounterRead(jdbc)
        return result
    }

    internal fun lockForOwnerCreate(operation: ComplaintOwnerCreateOperation): LockedOwnerCreate = LockedOwnerCreate.lock(this, operation)

    internal fun lockForOwnerEdit(operation: ComplaintOwnerEditOperation): LockedOwnerEdit = LockedOwnerEdit.lock(this, operation)

    internal fun lockForAdminContent(operation: ComplaintAdminContentOperation): LockedAdminContent = LockedAdminContent.lock(this, operation)

    internal fun lockForAdminStatus(operation: ComplaintAdminStatusMutation): LockedAdminStatus = LockedAdminStatus.lock(this, operation)

    internal fun lockForAdminBatchStatus(operation: ComplaintAdminBatchStatusMutation): LockedAdminBatchStatus = LockedAdminBatchStatus.lock(this, operation)

    internal fun lockForAdminDelete(operation: ComplaintAdminDeleteAuthorizationOperation): LockedAdminDelete = LockedAdminDelete.lock(this, operation)
    internal fun lockForAdminDeleteApply(operation: ComplaintAdminDeleteApplyOperation): LockedAdminDeleteApply = LockedAdminDeleteApply.lock(this, operation)

    internal fun lockForOwnerDeleteAll(operation: ComplaintOwnerDeleteAllOperation): LockedOwnerDeleteAll = LockedOwnerDeleteAll.lock(this, operation)

    internal fun lockForOwnerDeleteAllApply(operation: ComplaintOwnerDeleteAllApplyOperation): LockedOwnerDeleteAllApply =
        LockedOwnerDeleteAllApply.lock(this, operation)

    internal fun lockForOwnerDelete(operation: ComplaintOwnerDeleteAuthorizationOperation): LockedOwnerDelete = LockedOwnerDelete.lock(this, operation)
    /** Only an original registered reload reads these counters; it receives no spending/settlement owner. */
    internal fun lockForRegisteredOwnerDeleteReload(operation: ComplaintOwnerDeleteRegisteredReloadOperation) {
        operation.beginCounterLock(jdbc)
        operation.requireCapacityPolicy(readLockedLedger(), jdbc)
    }
    /** Existing Admin primary only: no charge, allocation or authorization can be issued here. */
    internal fun lockForRegisteredAdminDeleteReload(operation: me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteRegisteredReloadOperation) {
        operation.beginCounterLock(jdbc)
        operation.requireCapacityPolicy(readLockedLedger(), jdbc)
    }
    /** The bounded selection variant has the same read-only counter prefix; no allocation is issued. */
    internal fun lockForRegisteredOwnerDeleteSelection(operation: ComplaintOwnerDeleteRegisteredSelectionOperation) {
        operation.beginCounterLock(jdbc)
        operation.requireCapacityPolicy(readLockedLedger(), jdbc)
    }
    internal fun lockForOwnerDeleteApply(operation: ComplaintOwnerDeleteApplyOperation): LockedOwnerDeleteApply = LockedOwnerDeleteApply.lock(this, operation)

    private fun readLockedLedger(): ComplaintCapacityLedger = readLockedCounters().ledger

    private fun readLockedCounters(): LockedCounters = readCounters(LOCK_COUNTERS)

    private fun readCounters(sql: String): LockedCounters {
        val rows = jdbc.query(sql, { result, _ -> readCounter(result) })
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

    /** Only after the same22 counter rows are already locked; no backward lock acquisition during effect rereads. */
    private fun projectionCounters(counters: LockedCounters): CatalogTestRunActivationProjectionCountersV1 {
        val rows = jdbc.query(TEST_PROJECTION_COUNTER_FINGERPRINTS, { result, _ ->
            val counter = ComplaintCapacityEncoding.counter(requiredInt(result, "accounting_version"), requiredInt(result, "ordinal"), checkNotNull(result.getString("name")))
            counter to checkNotNull(result.getBytes("row_digest")).also { check(it.size == 32) }
        })
        check(rows.map { it.first } == ComplaintCapacityEncoding.lockOrder())
        return CatalogTestRunActivationProjectionCountersV1(counters.ledger.configuration, counters.ledger.balance, counters.daily, rows.map { it.second })
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

    /** Only these concrete counted allocations can enter the qualified single-delete JDBC audit insertion. */
    internal sealed interface OwnerDeleteAuditAllocation : ComplaintAuditAllocation {
        fun beginAuditInsert(insertion: ComplaintOwnerDeleteAuditInsertion, entry: CountedOwnerDeleteAuditEntry): Connection
        fun requireAuditInsert(insertion: ComplaintOwnerDeleteAuditInsertion)
        fun failed(problem: Throwable): Nothing
    }

    internal class LockedOwnerDelete private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: ComplaintOwnerDeleteAuthorizationOperation,
        private val before: ComplaintCapacityLedger,
        private val charged: ComplaintCapacityLedger,
        private val fresh: Boolean,
    ) : OwnerDeleteAuditAllocation {
        private var settled = false
        private var rejected = false
        private var audit: ComplaintOwnerDeleteAuditInsertion? = null
        fun belongsTo(candidate: ComplaintOwnerDeleteAuthorizationOperation): Boolean = operation === candidate
        fun settledFor(candidate: ComplaintOwnerDeleteAuthorizationOperation): Boolean = belongsTo(candidate) && settled
        fun completedFor(candidate: ComplaintOwnerDeleteAuthorizationOperation): Boolean = settledFor(candidate) &&
            (if (fresh && !rejected) audit?.completedFor(this) == true else audit == null)
        fun keepReceiptOnly(candidate: ComplaintOwnerDeleteAuthorizationOperation) {
            try {
                check(candidate === operation && settled && fresh && !rejected && audit == null)
                val final = before.chargePrivacyActual(checkNotNull(store.expectedPolicyDigest), OwnerDeleteCapacityCharges.RECEIPT)
                persist(charged.balance, final.balance, true)
                rejected = true // The provisional obligation never commits; no promised future work is released here.
            } catch (problem: Throwable) { operation.failed(problem) }
        }
        private fun persist(old: ComplaintCapacityBalance, after: ComplaintCapacityBalance, rejecting: Boolean) {
            for (counter in ComplaintCapacityEncoding.lockOrder()) {
                operation.requireCapacityWrite(this, store.jdbc, rejecting)
                if (old.free[counter] == after.free[counter] && old.actual[counter] == after.actual[counter] && old.recoveryReserved[counter] == after.recoveryReserved[counter]) continue
                check(store.jdbc.update(RECOVERY_COUNTER, after.free[counter], after.actual[counter], after.recoveryReserved[counter], counter.storedName,
                    old.free[counter], old.actual[counter], old.recoveryReserved[counter], old.testReserved[counter]) == 1)
            }
            operation.requireCapacityWrite(this, store.jdbc, rejecting)
        }
        override fun beginAuditInsert(insertion: ComplaintOwnerDeleteAuditInsertion, entry: CountedOwnerDeleteAuditEntry): Connection {
            check(fresh && settled && !rejected && audit == null && insertion.belongsTo(this))
            val connection = operation.auditConnection(this, store.jdbc, entry)
            audit = insertion; return connection
        }
        override fun requireAuditInsert(insertion: ComplaintOwnerDeleteAuditInsertion) { check(audit === insertion); operation.requireAuditWrite(this, store.jdbc) }
        override fun failed(problem: Throwable): Nothing = operation.failed(problem)
        companion object {
            @Suppress("TooGenericExceptionCaught")
            fun lock(store: JdbcComplaintCapacityStore, operation: ComplaintOwnerDeleteAuthorizationOperation): LockedOwnerDelete {
                try {
                    val fresh = operation.beginCounterLock(store.jdbc)
                    val before = store.readLockedLedger()
                    operation.requireCapacityPolicy(before, store.jdbc)
                    val after = if (fresh) before.chargePrivacyActual(checkNotNull(store.expectedPolicyDigest), OwnerDeleteCapacityCharges.AUTHORIZATION)
                        .reserveRecovery(checkNotNull(store.expectedPolicyDigest), OwnerDeleteCapacityCharges.RECOVERY) else {
                        check((OwnerDeleteCapacityCharges.AUTHORIZATION - ComplaintCapacityCharges.AUDIT).fitsWithin(before.balance.actual))
                        check(operation.requiredRecovery().fitsWithin(before.balance.recoveryReserved))
                        before
                    }
                    return LockedOwnerDelete(store, operation, before, after, fresh).also {
                        operation.retainCapacity(it, store.jdbc)
                        if (fresh) it.persist(before.balance, after.balance, false)
                        it.settled = true
                    }
                } catch (problem: Throwable) { operation.failed(problem) }
            }
        }
    }

    /** Missing bookkeeping is paid before reconstruction, then only privately measured new rows spend Y-U. */
    internal class LockedOwnerDeleteApply private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: ComplaintOwnerDeleteApplyOperation,
        private var ledger: ComplaintCapacityLedger,
    ) : OwnerDeleteAuditAllocation {
        private var toppedUp = false
        private var settled = false
        private var expectedAudits = 0
        private val audits = ArrayList<ComplaintOwnerDeleteAuditInsertion>(2)
        fun belongsTo(candidate: ComplaintOwnerDeleteApplyOperation): Boolean = operation === candidate
        fun settledFor(candidate: ComplaintOwnerDeleteApplyOperation): Boolean = belongsTo(candidate) && settled
        fun completedFor(candidate: ComplaintOwnerDeleteApplyOperation): Boolean = settledFor(candidate) && audits.size == expectedAudits && audits.all { it.completedFor(this) }
        private fun topUp() {
            check(!toppedUp)
            val missing = operation.missingBookkeeping(this, store.jdbc)
            val expected = checkNotNull(store.expectedPolicyDigest)
            val after = ledger.chargePrivacyActual(expected, missing.first).reserveRecovery(expected, missing.second)
            persist(ledger.balance, after.balance)
            ledger = after; toppedUp = true
        }
        @Suppress("TooGenericExceptionCaught")
        fun settle(candidate: ComplaintOwnerDeleteApplyOperation) {
            try {
                check(candidate === operation && toppedUp && !settled)
                val counts = operation.materializedCounts(this, store.jdbc)
                expectedAudits = counts.removed + counts.summary
                val use = ComplaintCapacityCharges.INSTALLATION_ID.scaled(counts.installation.toLong()) + ComplaintCapacityCharges.RESOURCE_ID.scaled(counts.resource.toLong()) +
                    ComplaintCapacityCharges.AUDIT.scaled(expectedAudits.toLong()) + OwnerDeleteCapacityCharges.APPLIED.scaled(counts.applied.toLong())
                val refund = ComplaintCapacityCharges.INSTALLATION_CONTENT_V1.scaled(counts.removed.toLong())
                val expected = checkNotNull(store.expectedPolicyDigest)
                val after = ledger.spendRecovery(expected, operation.remainingReserve(this, store.jdbc), use).refundActual(expected, refund)
                persist(ledger.balance, after.balance)
                if (!use.isZero()) operation.recordProgress(this, store.jdbc, use)
                ledger = after; settled = true
            } catch (problem: Throwable) { operation.failed(problem) }
        }
        private fun persist(old: ComplaintCapacityBalance, after: ComplaintCapacityBalance) {
            for (counter in ComplaintCapacityEncoding.lockOrder()) {
                operation.requireCapacityWrite(this, store.jdbc)
                if (old.free[counter] == after.free[counter] && old.actual[counter] == after.actual[counter] && old.recoveryReserved[counter] == after.recoveryReserved[counter]) continue
                check(store.jdbc.update(RECOVERY_COUNTER, after.free[counter], after.actual[counter], after.recoveryReserved[counter], counter.storedName,
                    old.free[counter], old.actual[counter], old.recoveryReserved[counter], old.testReserved[counter]) == 1)
            }
            operation.requireCapacityWrite(this, store.jdbc)
        }
        override fun beginAuditInsert(insertion: ComplaintOwnerDeleteAuditInsertion, entry: CountedOwnerDeleteAuditEntry): Connection {
            check(settled && audits.size < expectedAudits && audits.all { it.completedFor(this) } && insertion.belongsTo(this))
            val connection = operation.auditConnection(this, store.jdbc, entry, audits.size)
            audits.add(insertion); return connection
        }
        override fun requireAuditInsert(insertion: ComplaintOwnerDeleteAuditInsertion) { check(audits.lastOrNull() === insertion); operation.requireAuditWrite(this, store.jdbc) }
        override fun failed(problem: Throwable): Nothing = operation.failed(problem)
        companion object {
            @Suppress("TooGenericExceptionCaught")
            fun lock(store: JdbcComplaintCapacityStore, operation: ComplaintOwnerDeleteApplyOperation): LockedOwnerDeleteApply {
                try {
                    operation.beginCounterLock(store.jdbc)
                    val ledger = store.readLockedLedger()
                    operation.requireCapacityPolicy(ledger, store.jdbc)
                    return LockedOwnerDeleteApply(store, operation, ledger).also { operation.retainCapacity(it, store.jdbc); it.topUp() }
                } catch (problem: Throwable) { operation.failed(problem) }
            }
        }
    }

    /** Only these concrete counted allocations can enter the qualified single-delete JDBC audit insertion. */
    internal sealed interface AdminDeleteAuditAllocation : ComplaintAuditAllocation {
        fun beginAuditInsert(insertion: ComplaintAdminDeleteAuditInsertion, entry: CountedAdminDeleteAuditEntry): Connection
        fun requireAuditInsert(insertion: ComplaintAdminDeleteAuditInsertion)
        fun failed(problem: Throwable): Nothing
    }

    internal class LockedAdminDelete private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: ComplaintAdminDeleteAuthorizationOperation,
        private val before: ComplaintCapacityLedger,
        private val charged: ComplaintCapacityLedger,
        private val fresh: Boolean,
    ) : AdminDeleteAuditAllocation {
        private var settled = false
        private var rejected = false
        private val audits = ArrayList<ComplaintAdminDeleteAuditInsertion>(operation.authorizationAuditCount())
        fun belongsTo(candidate: ComplaintAdminDeleteAuthorizationOperation): Boolean = operation === candidate
        fun settledFor(candidate: ComplaintAdminDeleteAuthorizationOperation): Boolean = belongsTo(candidate) && settled
        fun completedFor(candidate: ComplaintAdminDeleteAuthorizationOperation): Boolean = settledFor(candidate) &&
            (if (fresh && !rejected) audits.size == operation.authorizationAuditCount() && audits.all { it.completedFor(this) } else audits.isEmpty())
        fun keepReceiptOnly(candidate: ComplaintAdminDeleteAuthorizationOperation) {
            try {
                check(candidate === operation && settled && fresh && !rejected && audits.isEmpty())
                val final = before.chargePrivacyActual(checkNotNull(store.expectedPolicyDigest), OwnerDeleteCapacityCharges.RECEIPT)
                persist(charged.balance, final.balance, true)
                rejected = true // The provisional obligation never commits; no promised future work is released here.
            } catch (problem: Throwable) { operation.failed(problem) }
        }
        private fun persist(old: ComplaintCapacityBalance, after: ComplaintCapacityBalance, rejecting: Boolean) {
            for (counter in ComplaintCapacityEncoding.lockOrder()) {
                operation.requireCapacityWrite(this, store.jdbc, rejecting)
                if (old.free[counter] == after.free[counter] && old.actual[counter] == after.actual[counter] && old.recoveryReserved[counter] == after.recoveryReserved[counter]) continue
                check(store.jdbc.update(RECOVERY_COUNTER, after.free[counter], after.actual[counter], after.recoveryReserved[counter], counter.storedName,
                    old.free[counter], old.actual[counter], old.recoveryReserved[counter], old.testReserved[counter]) == 1)
            }
            operation.requireCapacityWrite(this, store.jdbc, rejecting)
        }
        override fun beginAuditInsert(insertion: ComplaintAdminDeleteAuditInsertion, entry: CountedAdminDeleteAuditEntry): Connection {
            check(fresh && settled && !rejected && audits.size < operation.authorizationAuditCount() && audits.all { it.completedFor(this) } && insertion.belongsTo(this))
            val connection = operation.auditConnection(this, store.jdbc, entry, audits.size)
            audits.add(insertion); return connection
        }
        override fun requireAuditInsert(insertion: ComplaintAdminDeleteAuditInsertion) { check(audits.lastOrNull() === insertion); operation.requireAuditWrite(this, store.jdbc) }
        override fun failed(problem: Throwable): Nothing = operation.failed(problem)
        companion object {
            @Suppress("TooGenericExceptionCaught")
            fun lock(store: JdbcComplaintCapacityStore, operation: ComplaintAdminDeleteAuthorizationOperation): LockedAdminDelete {
                try {
                    val fresh = operation.beginCounterLock(store.jdbc)
                    val before = store.readLockedLedger()
                    operation.requireCapacityPolicy(before, store.jdbc)
                    val after = if (fresh) before.chargePrivacyActual(checkNotNull(store.expectedPolicyDigest), operation.authorizationCharge())
                        .reserveRecovery(checkNotNull(store.expectedPolicyDigest), operation.requiredRecovery()) else {
                        check((OwnerDeleteCapacityCharges.AUTHORIZATION - ComplaintCapacityCharges.AUDIT).fitsWithin(before.balance.actual))
                        check(operation.requiredRecovery().fitsWithin(before.balance.recoveryReserved))
                        before
                    }
                    return LockedAdminDelete(store, operation, before, after, fresh).also {
                        operation.retainCapacity(it, store.jdbc)
                        if (fresh) it.persist(before.balance, after.balance, false)
                        it.settled = true
                    }
                } catch (problem: Throwable) { operation.failed(problem) }
            }
        }
    }

    /** Missing bookkeeping is paid before reconstruction, then only privately measured new rows spend Y-U. */
    internal class LockedAdminDeleteApply private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: ComplaintAdminDeleteApplyOperation,
        private var ledger: ComplaintCapacityLedger,
    ) : AdminDeleteAuditAllocation {
        private var toppedUp = false
        private var settled = false
        private var expectedAudits = 0
        private val audits = ArrayList<ComplaintAdminDeleteAuditInsertion>(2)
        fun belongsTo(candidate: ComplaintAdminDeleteApplyOperation): Boolean = operation === candidate
        fun settledFor(candidate: ComplaintAdminDeleteApplyOperation): Boolean = belongsTo(candidate) && settled
        fun completedFor(candidate: ComplaintAdminDeleteApplyOperation): Boolean = settledFor(candidate) && audits.size == expectedAudits && audits.all { it.completedFor(this) }
        private fun topUp() {
            check(!toppedUp)
            val missing = operation.missingBookkeeping(this, store.jdbc)
            val expected = checkNotNull(store.expectedPolicyDigest)
            val after = ledger.chargePrivacyActual(expected, missing.first).reserveRecovery(expected, missing.second)
            persist(ledger.balance, after.balance)
            ledger = after; toppedUp = true
        }
        @Suppress("TooGenericExceptionCaught")
        fun settle(candidate: ComplaintAdminDeleteApplyOperation) {
            try {
                check(candidate === operation && toppedUp && !settled)
                val counts = operation.materializedCounts(this, store.jdbc)
                expectedAudits = counts.removed + counts.summary
                val use = ComplaintCapacityCharges.INSTALLATION_ID.scaled(counts.installation.toLong()) + ComplaintCapacityCharges.RESOURCE_ID.scaled(counts.resource.toLong()) +
                    ComplaintCapacityCharges.AUDIT.scaled(expectedAudits.toLong()) + OwnerDeleteCapacityCharges.APPLIED.scaled(counts.applied.toLong())
                val refund = ComplaintCapacityCharges.INSTALLATION_CONTENT_V1.scaled(counts.removed.toLong())
                val expected = checkNotNull(store.expectedPolicyDigest)
                val after = ledger.spendRecovery(expected, operation.remainingReserve(this, store.jdbc), use).refundActual(expected, refund)
                persist(ledger.balance, after.balance)
                if (!use.isZero()) operation.recordProgress(this, store.jdbc, use)
                ledger = after; settled = true
            } catch (problem: Throwable) { operation.failed(problem) }
        }
        private fun persist(old: ComplaintCapacityBalance, after: ComplaintCapacityBalance) {
            for (counter in ComplaintCapacityEncoding.lockOrder()) {
                operation.requireCapacityWrite(this, store.jdbc)
                if (old.free[counter] == after.free[counter] && old.actual[counter] == after.actual[counter] && old.recoveryReserved[counter] == after.recoveryReserved[counter]) continue
                check(store.jdbc.update(RECOVERY_COUNTER, after.free[counter], after.actual[counter], after.recoveryReserved[counter], counter.storedName,
                    old.free[counter], old.actual[counter], old.recoveryReserved[counter], old.testReserved[counter]) == 1)
            }
            operation.requireCapacityWrite(this, store.jdbc)
        }
        override fun beginAuditInsert(insertion: ComplaintAdminDeleteAuditInsertion, entry: CountedAdminDeleteAuditEntry): Connection {
            check(settled && audits.size < expectedAudits && audits.all { it.completedFor(this) } && insertion.belongsTo(this))
            val connection = operation.auditConnection(this, store.jdbc, entry, audits.size)
            audits.add(insertion); return connection
        }
        override fun requireAuditInsert(insertion: ComplaintAdminDeleteAuditInsertion) { check(audits.lastOrNull() === insertion); operation.requireAuditWrite(this, store.jdbc) }
        override fun failed(problem: Throwable): Nothing = operation.failed(problem)
        companion object {
            @Suppress("TooGenericExceptionCaught")
            fun lock(store: JdbcComplaintCapacityStore, operation: ComplaintAdminDeleteApplyOperation): LockedAdminDeleteApply {
                try {
                    operation.beginCounterLock(store.jdbc)
                    val ledger = store.readLockedLedger()
                    operation.requireCapacityPolicy(ledger, store.jdbc)
                    return LockedAdminDeleteApply(store, operation, ledger).also { operation.retainCapacity(it, store.jdbc); it.topUp() }
                } catch (problem: Throwable) { operation.failed(problem) }
            }
        }
    }

    /** Immediate deletion bookkeeping plus one immutable future promise; reload validates without charging again. */
    internal class LockedOwnerDeleteAll private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: ComplaintOwnerDeleteAllOperation,
        private val newAuthorization: Boolean,
    ) : ComplaintAuditAllocation {
        private var settled = false
        private var audit: ComplaintInstallationDeleteAuthorizationAuditInsertion? = null

        internal fun belongsTo(candidate: ComplaintOwnerDeleteAllOperation): Boolean = operation === candidate
        internal fun settledFor(candidate: ComplaintOwnerDeleteAllOperation): Boolean = belongsTo(candidate) && settled
        internal fun completedFor(candidate: ComplaintOwnerDeleteAllOperation): Boolean = settledFor(candidate) &&
            if (newAuthorization) audit?.completedFor(this) == true else audit == null

        private fun persist(before: ComplaintCapacityBalance, after: ComplaintCapacityBalance) {
            for (counter in ComplaintCapacityEncoding.lockOrder()) {
                operation.requireCapacityWrite(this, store.jdbc)
                if (before.free[counter] == after.free[counter] && before.actual[counter] == after.actual[counter] &&
                    before.recoveryReserved[counter] == after.recoveryReserved[counter]
                ) {
                    continue
                }
                check(
                    store.jdbc.update(
                        RECOVERY_COUNTER,
                        after.free[counter], after.actual[counter], after.recoveryReserved[counter], counter.storedName,
                        before.free[counter], before.actual[counter], before.recoveryReserved[counter], before.testReserved[counter],
                    ) == 1,
                )
            }
            operation.requireCapacityWrite(this, store.jdbc)
        }

        internal fun beginAuditInsert(
            insertion: ComplaintInstallationDeleteAuthorizationAuditInsertion,
            entry: CountedInstallationDeleteAuthorizationAuditEntry,
        ): Connection {
            check(newAuthorization && settled && audit == null && insertion.belongsTo(this))
            val connection = operation.auditConnection(this, store.jdbc, entry)
            audit = insertion
            return connection
        }

        internal fun requireAuditInsert(insertion: ComplaintInstallationDeleteAuthorizationAuditInsertion) {
            check(audit === insertion)
            operation.requireAuditWrite(this, store.jdbc)
        }

        internal fun auditScope(insertion: ComplaintInstallationDeleteAuthorizationAuditInsertion): ComplaintDataScope {
            requireAuditInsert(insertion)
            return operation.auditScope(this, store.jdbc)
        }

        internal fun failed(problem: Throwable): Nothing = operation.failed(problem)

        override fun toString(): String = "LockedOwnerDeleteAll(redacted)"

        companion object {
            @Suppress("TooGenericExceptionCaught")
            internal fun lock(store: JdbcComplaintCapacityStore, operation: ComplaintOwnerDeleteAllOperation): LockedOwnerDeleteAll {
                try {
                    val fresh = operation.beginCounterLock(store.jdbc)
                    val before = store.readLockedLedger()
                    operation.requireCapacityPolicy(before, store.jdbc)
                    val after = if (fresh) {
                        before.chargePrivacyActual(checkNotNull(store.expectedPolicyDigest), OwnerDeleteAllCapacityCharges.AUTHORIZATION)
                            .reserveRecovery(checkNotNull(store.expectedPolicyDigest), OwnerDeleteAllCapacityCharges.RECOVERY)
                    } else {
                        // Necessary aggregate bounds only, not a substitute for drained reconciliation.
                        // A scope-only authorization audit may have legitimately expired independently.
                        check((OwnerDeleteAllCapacityCharges.AUTHORIZATION - ComplaintCapacityCharges.AUDIT).fitsWithin(before.balance.actual))
                        check(operation.requiredReloadRecovery(store.jdbc).fitsWithin(before.balance.recoveryReserved))
                        before
                    }
                    val allocation = LockedOwnerDeleteAll(store, operation, fresh)
                    operation.retainCapacity(allocation, store.jdbc)
                    if (fresh) allocation.persist(before.balance, after.balance)
                    allocation.settled = true
                    return allocation
                } catch (problem: Throwable) {
                    operation.failed(problem)
                }
            }
        }
    }

    /**
     * The concrete APPLY alone supplies actual removal/reconstruction counts after its locked
     * mutations. Spend just those new rows, refund only the paid content envelope, keep all later
     * promises reserved. Replay verifies the same full ledger but writes no counters or audit.
     */
    internal class LockedOwnerDeleteAllApply private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: ComplaintOwnerDeleteAllApplyOperation,
        private val before: ComplaintCapacityLedger,
    ) : ComplaintAuditAllocation {
        private var issued = false
        private var settled = false
        private var expectedAudits = 0
        private val audits = ArrayList<ComplaintOwnerDeleteAllAuditInsertion>()

        internal fun belongsTo(candidate: ComplaintOwnerDeleteAllApplyOperation): Boolean = operation === candidate
        internal fun settledFor(candidate: ComplaintOwnerDeleteAllApplyOperation): Boolean = belongsTo(candidate) && settled
        internal fun completedFor(candidate: ComplaintOwnerDeleteAllApplyOperation): Boolean = settledFor(candidate) &&
            audits.size == expectedAudits && audits.all { it.completedFor(this) }

        @Suppress("TooGenericExceptionCaught")
        internal fun settle(candidate: ComplaintOwnerDeleteAllApplyOperation) {
            try {
                check(candidate === operation && !issued)
                val counts = operation.materializedCounts(this, store.jdbc)
                issued = true
                if (counts != null) {
                    expectedAudits = counts.audits
                    val use = counts.use
                    val refund = ComplaintCapacityCharges.INSTALLATION_CONTENT_V1.scaled(counts.removed.toLong())
                    val expected = checkNotNull(store.expectedPolicyDigest)
                    val after = before.spendRecovery(expected, operation.remainingReserve(this, store.jdbc), use).refundActual(expected, refund)
                    persist(before.balance, after.balance)
                    operation.recordProgress(this, store.jdbc, use)
                }
                operation.requireCapacityWrite(this, store.jdbc)
                settled = true
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        private fun persist(old: ComplaintCapacityBalance, after: ComplaintCapacityBalance) {
            for (counter in ComplaintCapacityEncoding.lockOrder()) {
                operation.requireCapacityWrite(this, store.jdbc)
                if (old.free[counter] == after.free[counter] && old.actual[counter] == after.actual[counter] &&
                    old.recoveryReserved[counter] == after.recoveryReserved[counter]
                ) {
                    continue
                }
                check(
                    store.jdbc.update(
                        RECOVERY_COUNTER,
                        after.free[counter], after.actual[counter], after.recoveryReserved[counter], counter.storedName,
                        old.free[counter], old.actual[counter], old.recoveryReserved[counter], old.testReserved[counter],
                    ) == 1,
                )
            }
            operation.requireCapacityWrite(this, store.jdbc)
        }

        internal fun beginAuditInsert(insertion: ComplaintOwnerDeleteAllAuditInsertion, entry: CountedOwnerDeleteAllAuditEntry): Connection {
            check(settled && audits.size < expectedAudits && insertion.belongsTo(this))
            check(audits.all { it.completedFor(this) } && audits.none { it === insertion })
            val connection = operation.auditConnection(this, store.jdbc, entry, audits.size)
            audits.add(insertion)
            return connection
        }

        internal fun requireAuditInsert(insertion: ComplaintOwnerDeleteAllAuditInsertion) {
            check(audits.lastOrNull() === insertion)
            operation.requireAuditWrite(this, store.jdbc)
        }

        internal fun auditScope(insertion: ComplaintOwnerDeleteAllAuditInsertion): ComplaintDataScope {
            requireAuditInsert(insertion)
            return operation.auditScope(this, store.jdbc)
        }

        internal fun auditActorKind(insertion: ComplaintOwnerDeleteAllAuditInsertion): String {
            requireAuditInsert(insertion)
            return operation.auditActorKind(this, store.jdbc)
        }

        internal fun failed(problem: Throwable): Nothing = operation.failed(problem)

        override fun toString(): String = "LockedOwnerDeleteAllApply(redacted)"

        companion object {
            @Suppress("TooGenericExceptionCaught")
            internal fun lock(store: JdbcComplaintCapacityStore, operation: ComplaintOwnerDeleteAllApplyOperation): LockedOwnerDeleteAllApply {
                try {
                    operation.beginCounterLock(store.jdbc)
                    val ledger = store.readLockedLedger()
                    operation.requireCapacityPolicy(ledger, store.jdbc)
                    return LockedOwnerDeleteAllApply(store, operation, ledger).also { operation.retainCapacity(it, store.jdbc) }
                } catch (problem: Throwable) {
                    operation.failed(problem)
                }
            }
        }
    }

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
                                after.free[counter], after.actual[counter], after.testReserved[counter], counter.storedName, counter.storedOrdinal, expected,
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

    /** Separate actual1/2 first-overlap profile. G1's actual0/1 guard above is deliberately not widened. */
    internal class LockedCatalogSignerRotation private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: CatalogSignerRotationOperationV1,
        private val before: ComplaintCapacityLedger,
    ) {
        private var issued = false
        private var settled = false
        internal fun belongsTo(candidate: CatalogSignerRotationOperationV1): Boolean = operation === candidate
        internal fun settledFor(candidate: CatalogSignerRotationOperationV1): Boolean = belongsTo(candidate) && settled

        @Suppress("TooGenericExceptionCaught")
        internal fun settle(candidate: CatalogSignerRotationOperationV1) {
            try {
                check(candidate === operation && !issued)
                val (rows, creating) = operation.requireCounterSettlement(this, store.jdbc)
                issued = true
                check(rows in 1..2 && (!creating || rows == 1))
                val expected = checkNotNull(store.expectedPolicyDigest)
                val balance = before.balance
                check(balance.actual[ComplaintCapacityCounter.CATALOG_MUTATIONS] == rows.toLong())
                val minimumStorage = CatalogGenesisCapacity.storageBytes + if (rows == 2) CatalogSignerRotationCapacityV1.storageBytes else 0L
                check(balance.actual[ComplaintCapacityCounter.STORAGE_BYTES] >= minimumStorage)
                if (creating) {
                    val after = before.chargeCreation(expected, CatalogSignerRotationCapacityV1.charge).balance
                    for (counter in CATALOG_SIGNER_ROTATION_COUNTERS) {
                        operation.requireCounterSettlement(this, store.jdbc)
                        check(
                            store.jdbc.update(
                                CHARGE_ENROLLMENT_COUNTER,
                                after.free[counter], after.actual[counter], after.testReserved[counter], counter.storedName, counter.storedOrdinal, expected,
                                balance.hardLimit[counter], balance.creationLimit[counter], balance.free[counter], balance.actual[counter],
                                balance.recoveryReserved[counter], balance.testReserved[counter],
                            ) == 1,
                        )
                    }
                }
                operation.requireCounterSettlement(this, store.jdbc)
                settled = true // READ/signature/replay verify existing charge, never spend free capacity again.
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        companion object {
            @Suppress("TooGenericExceptionCaught")
            internal fun lock(store: JdbcComplaintCapacityStore, operation: CatalogSignerRotationOperationV1): LockedCatalogSignerRotation {
                try {
                    operation.beginCounterLock(store.jdbc)
                    return LockedCatalogSignerRotation(store, operation, store.readLockedLedger())
                } catch (problem: Throwable) {
                    operation.failed(problem)
                }
            }
        }
    }

    /** Actual2 prepaid first-overlap only. Never charge/refund and never widen G1's actual0/1 profile. */
    internal class LockedCatalogSignerRotationFinalization private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: CatalogSignerRotationFinalizationOperationV1,
        private val before: ComplaintCapacityLedger,
    ) {
        private var issued = false
        private var verified = false
        internal fun belongsTo(candidate: CatalogSignerRotationFinalizationOperationV1): Boolean = operation === candidate
        internal fun verifiedFor(candidate: CatalogSignerRotationFinalizationOperationV1): Boolean = belongsTo(candidate) && verified

        @Suppress("TooGenericExceptionCaught")
        internal fun verify(candidate: CatalogSignerRotationFinalizationOperationV1) {
            try {
                check(candidate === operation && !issued)
                operation.requireCounterVerification(this, store.jdbc) // Concrete history is already exactly two locked rows.
                issued = true
                checkNotNull(store.expectedPolicyDigest) // readLockedLedger matched every locked row to this original P.
                val balance = before.balance
                check(balance.actual[ComplaintCapacityCounter.CATALOG_MUTATIONS] == 2L)
                check(
                    balance.actual[ComplaintCapacityCounter.STORAGE_BYTES] >=
                        CatalogGenesisCapacity.storageBytes + CatalogSignerRotationCapacityV1.storageBytes,
                )
                operation.requireCounterVerification(this, store.jdbc)
                verified = true
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        companion object {
            @Suppress("TooGenericExceptionCaught")
            internal fun lock(
                store: JdbcComplaintCapacityStore,
                operation: CatalogSignerRotationFinalizationOperationV1,
            ): LockedCatalogSignerRotationFinalization {
                try {
                    operation.beginCounterLock(store.jdbc)
                    return LockedCatalogSignerRotationFinalization(store, operation, store.readLockedLedger())
                } catch (problem: Throwable) {
                    operation.failed(problem)
                }
            }
        }
    }

    /** Separate fixed actual2/3 activation profile. Existing G1 and overlap2 actual-count guards stay unchanged. */
    internal class LockedCatalogSignerRotationActivation private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: CatalogSignerRotationActivationOperationV1,
        private val before: ComplaintCapacityLedger,
    ) {
        private var issued = false
        private var settled = false
        internal fun belongsTo(candidate: CatalogSignerRotationActivationOperationV1): Boolean = operation === candidate
        internal fun settledFor(candidate: CatalogSignerRotationActivationOperationV1): Boolean = belongsTo(candidate) && settled

        @Suppress("TooGenericExceptionCaught")
        internal fun settle(candidate: CatalogSignerRotationActivationOperationV1) {
            try {
                check(candidate === operation && !issued)
                val (rows, creating) = operation.requireCounterSettlement(this, store.jdbc)
                issued = true
                check(rows in 2..3 && (!creating || rows == 2))
                val expected = checkNotNull(store.expectedPolicyDigest)
                val balance = before.balance
                check(balance.actual[ComplaintCapacityCounter.CATALOG_MUTATIONS] == rows.toLong())
                val minimumStorage = CatalogGenesisCapacity.storageBytes + CatalogSignerRotationCapacityV1.storageBytes +
                    if (rows == 3) CatalogSignerRotationActivationCapacityV1.storageBytes else 0L
                check(balance.actual[ComplaintCapacityCounter.STORAGE_BYTES] >= minimumStorage)
                if (creating) {
                    val after = before.chargeCreation(expected, CatalogSignerRotationActivationCapacityV1.charge).balance
                    for (counter in CATALOG_SIGNER_ROTATION_COUNTERS) {
                        operation.requireCounterSettlement(this, store.jdbc)
                        check(
                            store.jdbc.update(
                                CHARGE_ENROLLMENT_COUNTER,
                                after.free[counter], after.actual[counter], after.testReserved[counter], counter.storedName, counter.storedOrdinal, expected,
                                balance.hardLimit[counter], balance.creationLimit[counter], balance.free[counter], balance.actual[counter],
                                balance.recoveryReserved[counter], balance.testReserved[counter],
                            ) == 1,
                        )
                    }
                }
                operation.requireCounterSettlement(this, store.jdbc)
                settled = true // Only PREPARE with actual2 creates/charges. Reads/signature/COMPLETE/PROJECT never charge/refund.
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        companion object {
            @Suppress("TooGenericExceptionCaught")
            internal fun lock(store: JdbcComplaintCapacityStore, operation: CatalogSignerRotationActivationOperationV1): LockedCatalogSignerRotationActivation {
                try {
                    operation.beginCounterLock(store.jdbc)
                    return LockedCatalogSignerRotationActivation(store, operation, store.readLockedLedger())
                } catch (problem: Throwable) {
                    operation.failed(problem)
                }
            }
        }
    }

    /** First TEST only. Original PREPARE pays C; only the named atomic PROJECT pays its exact effect and reserve. */
    internal class LockedCatalogTestRunActivation private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: CatalogTestRunActivationOperationV1,
        private val before: ComplaintCapacityLedger,
        private val daily: ComplaintDailyAdmission,
        private val projectionBefore: CatalogTestRunActivationProjectionCountersV1?,
    ) {
        private var issued = false
        private var settled = false
        private var projectionSettled: CatalogTestRunActivationProjectionCountersV1? = null
        internal fun belongsTo(candidate: CatalogTestRunActivationOperationV1): Boolean = operation === candidate
        internal fun settledFor(candidate: CatalogTestRunActivationOperationV1): Boolean = belongsTo(candidate) && settled

        internal fun projectionBefore(candidate: CatalogTestRunActivationOperationV1): CatalogTestRunActivationProjectionCountersV1 {
            check(candidate === operation)
            operation.requireProjectionCounterRead(this, store.jdbc)
            return checkNotNull(projectionBefore)
        }

        /** Reread only already-held counters. Row bytes/xmin must still equal the original settlement, including daily state. */
        @Suppress("TooGenericExceptionCaught")
        internal fun rereadProjection(candidate: CatalogTestRunActivationOperationV1): CatalogTestRunActivationProjectionCountersV1 {
            try {
                check(candidate === operation && settled)
                operation.requireProjectionCounterRead(this, store.jdbc)
                val observed = store.projectionCounters(store.readCounters(READ_COUNTERS))
                observed.requireSame(checkNotNull(projectionSettled))
                operation.requireProjectionCounterRead(this, store.jdbc)
                return observed
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        internal fun settle(candidate: CatalogTestRunActivationOperationV1) {
            try {
                check(candidate === operation && !issued)
                val (rows, creating) = operation.requireCounterSettlement(this, store.jdbc)
                issued = true
                val frozen = operation.input.frozen
                val expected = checkNotNull(store.expectedPolicyDigest)
                check(expected.contentEquals(frozen.capacityDigest()))
                val balance = before.balance
                // The P digest alone does not authenticate different declared hard/creation/daily limits.
                check(balance.hardLimit == frozen.policy.hardLimit && balance.creationLimit == frozen.policy.creationLimit && daily.dailyLimit == frozen.policy.dailyEnrollmentLimit)
                check(rows.toLong() == frozen.generation - if (creating) 1L else 0L)
                check(balance.actual[ComplaintCapacityCounter.CATALOG_MUTATIONS] == rows.toLong())
                if (!creating) check(balance.actual[ComplaintCapacityCounter.STORAGE_BYTES] >= frozen.prepareCharge[ComplaintCapacityCounter.STORAGE_BYTES])
                if (operation.input.projecting) {
                    check(!creating && projectionBefore != null)
                    settleProjection()
                    operation.requireCounterSettlement(this, store.jdbc)
                    settled = true
                    return
                }
                check(projectionBefore == null && balance.actual[ComplaintCapacityCounter.TEST_RUNS] == 0L && balance.testReserved == ComplaintCapacityVector.ZERO)
                val prepared = if (creating) before.chargeCreation(expected, frozen.prepareCharge) else before
                prepared.chargeCreation(expected, frozen.projectionCharge).reserveTest(expected, frozen.reserve)
                if (creating) {
                    val after = prepared.balance
                    for (counter in ComplaintCapacityEncoding.lockOrder().filter { frozen.prepareCharge[it] != 0L }) {
                        operation.requireCounterSettlement(this, store.jdbc)
                        check(
                            store.jdbc.update(
                                CHARGE_ENROLLMENT_COUNTER,
                                after.free[counter], after.actual[counter], after.testReserved[counter], counter.storedName, counter.storedOrdinal, expected,
                                balance.hardLimit[counter], balance.creationLimit[counter], balance.free[counter], balance.actual[counter],
                                balance.recoveryReserved[counter], balance.testReserved[counter],
                            ) == 1,
                        )
                    }
                }
                operation.requireCounterSettlement(this, store.jdbc)
                settled = true // Reload neither charges, reserves, refunds nor reopens anything.
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        private fun settleProjection() {
            val input = operation.input
            val frozen = input.frozen
            val expected = checkNotNull(store.expectedPolicyDigest)
            val physicalBefore = checkNotNull(projectionBefore)
            val projected = checkNotNull(checkNotNull(input.expected).completedTail).projectedAt != null
            val writing = input.kind === CatalogTestRunActivationKindV1.PROJECT
            check(!writing || !projected)
            input.expected.projectionRows?.counters?.requireSame(physicalBefore)
            before.configuration.requireCreationAllowed(expected)
            val after = if (projected) {
                check(before.balance.actual[ComplaintCapacityCounter.TEST_RUNS] == 1L && before.balance.testReserved == frozen.reserve)
                check((frozen.prepareCharge + frozen.projectionCharge).fitsWithin(before.balance.actual))
                before
            } else {
                check(before.balance.actual[ComplaintCapacityCounter.TEST_RUNS] == 0L && before.balance.testReserved == ComplaintCapacityVector.ZERO)
                val prospective = before.chargeCreation(expected, frozen.projectionCharge).reserveTest(expected, frozen.reserve)
                if (writing) prospective else before // A reload proves future fit but never materializes the promise.
            }
            if (writing) {
                for (counter in ComplaintCapacityEncoding.lockOrder()) {
                    if (frozen.projectionCharge[counter] == 0L && frozen.reserve[counter] == 0L) continue
                    operation.requireCounterSettlement(this, store.jdbc)
                    check(store.jdbc.update(CHARGE_ENROLLMENT_COUNTER,
                        after.balance.free[counter], after.balance.actual[counter], after.balance.testReserved[counter],
                        counter.storedName, counter.storedOrdinal, expected,
                        before.balance.hardLimit[counter], before.balance.creationLimit[counter], before.balance.free[counter],
                        before.balance.actual[counter], before.balance.recoveryReserved[counter], before.balance.testReserved[counter]) == 1)
                }
            }
            operation.requireCounterSettlement(this, store.jdbc)
            val observed = store.projectionCounters(store.readCounters(READ_COUNTERS))
            observed.requireSettled(physicalBefore, after.balance)
            if (!writing) observed.requireSame(physicalBefore)
            operation.requireCounterSettlement(this, store.jdbc)
            projectionSettled = observed
        }

        companion object {
            @Suppress("TooGenericExceptionCaught")
            internal fun lock(store: JdbcComplaintCapacityStore, operation: CatalogTestRunActivationOperationV1): LockedCatalogTestRunActivation {
                try {
                    operation.beginCounterLock(store.jdbc)
                    val counters = store.readLockedCounters()
                    val projection = if (operation.input.projecting) store.projectionCounters(counters) else null
                    return LockedCatalogTestRunActivation(store, operation, counters.ledger, counters.daily, projection)
                } catch (problem: Throwable) {
                    operation.failed(problem)
                }
            }
        }
    }

    /** Precharge the maximum result before domain locks. Rejecting releases only this transaction's unused slice. */
    internal class LockedOwnerCreate private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: ComplaintOwnerCreateOperation,
        private val after: ComplaintCapacityLedger,
    ) {
        private var charged = false
        private var receiptOnly = false
        private var audit: ChargedComplaintAudit? = null

        internal fun belongsTo(candidate: ComplaintOwnerCreateOperation): Boolean = operation === candidate
        internal fun chargedFor(candidate: ComplaintOwnerCreateOperation): Boolean = belongsTo(candidate) && charged

        internal fun completedFor(candidate: ComplaintOwnerCreateOperation, receipt: ComplaintOwnerReceipt?): Boolean =
            chargedFor(candidate) && when (receipt) {
                is ComplaintOwnerReceipt.Applied -> !receiptOnly && audit?.completedFor(candidate) == true
                is ComplaintOwnerReceipt.Rejected -> receiptOnly && audit == null
                null -> false
            }

        @Suppress("TooGenericExceptionCaught")
        internal fun keepReceiptOnly(candidate: ComplaintOwnerCreateOperation) {
            try {
                check(candidate === operation && charged && !receiptOnly && audit == null)
                val unused = ComplaintCapacityCharges.OWNER_CREATE - ComplaintCapacityCharges.NORMAL_RECEIPT
                val retained = after.refundActual(checkNotNull(store.expectedPolicyDigest), unused)
                persist(after.balance, retained.balance, rejection = true)
                receiptOnly = true
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        internal fun prepaidAudit(candidate: ComplaintOwnerCreateOperation, mutation: ComplaintAuditMutation.Created): ChargedComplaintAudit {
            try {
                check(candidate === operation && charged && !receiptOnly && audit == null)
                // No counter query/acquisition here: that charge was included before run/owner/domain locks.
                val result = ChargedComplaintAudit.prepaidOwnerCreate(this, operation, mutation)
                audit = result
                return result
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        private fun persist(old: ComplaintCapacityBalance, next: ComplaintCapacityBalance, rejection: Boolean) {
            for (counter in OWNER_CREATE_COUNTERS) {
                operation.requireCapacityWrite(this, store.jdbc, rejection)
                if (old.free[counter] == next.free[counter] && old.actual[counter] == next.actual[counter]) continue
                check(
                    store.jdbc.update(
                        REFUND_COUNTER,
                        next.free[counter],
                        next.actual[counter],
                        counter.storedName,
                        old.free[counter],
                        old.actual[counter],
                    ) == 1,
                )
            }
            operation.requireCapacityWrite(this, store.jdbc, rejection)
        }

        override fun toString(): String = "LockedOwnerCreate(redacted)"

        companion object {
            @Suppress("TooGenericExceptionCaught")
            internal fun lock(store: JdbcComplaintCapacityStore, operation: ComplaintOwnerCreateOperation): LockedOwnerCreate {
                try {
                    operation.beginCounterLock(store.jdbc)
                    val before = store.readLockedLedger()
                    val after = before.chargeCreation(checkNotNull(store.expectedPolicyDigest), ComplaintCapacityCharges.OWNER_CREATE)
                    val allocation = LockedOwnerCreate(store, operation, after)
                    operation.retainCapacity(allocation, store.jdbc, before)
                    allocation.persist(before.balance, after.balance, rejection = false)
                    allocation.charged = true
                    return allocation
                } catch (problem: Throwable) {
                    operation.failed(problem)
                }
            }
        }
    }

    /** Only receipt and audit are new: the existing content row already paid its full legal edit lifecycle. */
    internal class LockedOwnerEdit private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: ComplaintOwnerEditOperation,
        private val after: ComplaintCapacityLedger,
    ) {
        private var charged = false
        private var receiptOnly = false
        private var audit: ChargedComplaintAudit? = null

        internal fun belongsTo(candidate: ComplaintOwnerEditOperation): Boolean = operation === candidate
        internal fun chargedFor(candidate: ComplaintOwnerEditOperation): Boolean = belongsTo(candidate) && charged

        internal fun completedFor(candidate: ComplaintOwnerEditOperation, receipt: ComplaintOwnerEditReceipt?): Boolean =
            chargedFor(candidate) && when (receipt) {
                is ComplaintOwnerEditReceipt.Applied -> !receiptOnly && audit?.completedFor(candidate) == true
                is ComplaintOwnerEditReceipt.Rejected -> receiptOnly && audit == null
                null -> false
            }

        @Suppress("TooGenericExceptionCaught")
        internal fun keepReceiptOnly(candidate: ComplaintOwnerEditOperation) {
            try {
                check(candidate === operation && charged && !receiptOnly && audit == null)
                val retained = after.refundActual(checkNotNull(store.expectedPolicyDigest), ComplaintCapacityCharges.AUDIT)
                persist(after.balance, retained.balance, rejection = true)
                receiptOnly = true
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        internal fun prepaidAudit(candidate: ComplaintOwnerEditOperation, mutation: ComplaintAuditMutation.ContentEdited): ChargedComplaintAudit {
            try {
                check(candidate === operation && charged && !receiptOnly && audit == null)
                val result = ChargedComplaintAudit.prepaidOwnerEdit(this, operation, mutation)
                audit = result
                return result
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        private fun persist(old: ComplaintCapacityBalance, next: ComplaintCapacityBalance, rejection: Boolean) {
            for (counter in OWNER_EDIT_COUNTERS) {
                operation.requireCapacityWrite(this, store.jdbc, rejection)
                if (old.free[counter] == next.free[counter] && old.actual[counter] == next.actual[counter]) continue
                check(
                    store.jdbc.update(
                        REFUND_COUNTER,
                        next.free[counter],
                        next.actual[counter],
                        counter.storedName,
                        old.free[counter],
                        old.actual[counter],
                    ) == 1,
                )
            }
            operation.requireCapacityWrite(this, store.jdbc, rejection)
        }

        override fun toString(): String = "LockedOwnerEdit(redacted)"

        companion object {
            @Suppress("TooGenericExceptionCaught")
            internal fun lock(store: JdbcComplaintCapacityStore, operation: ComplaintOwnerEditOperation): LockedOwnerEdit {
                try {
                    operation.beginCounterLock(store.jdbc)
                    val before = store.readLockedLedger()
                    val after = before.chargeCreation(checkNotNull(store.expectedPolicyDigest), ComplaintCapacityCharges.OWNER_EDIT)
                    val allocation = LockedOwnerEdit(store, operation, after)
                    operation.retainCapacity(allocation, store.jdbc, before)
                    allocation.persist(before.balance, after.balance, rejection = false)
                    allocation.charged = true
                    return allocation
                } catch (problem: Throwable) {
                    operation.failed(problem)
                }
            }
        }
    }

    /** Only receipt and audit are new: the existing content row already paid its full legal edit lifecycle. */
    internal class LockedAdminContent private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: ComplaintAdminContentOperation,
        private val after: ComplaintCapacityLedger,
    ) {
        private var charged = false
        private var receiptOnly = false
        private var audit: ChargedComplaintAudit? = null

        internal fun belongsTo(candidate: ComplaintAdminContentOperation): Boolean = operation === candidate
        internal fun chargedFor(candidate: ComplaintAdminContentOperation): Boolean = belongsTo(candidate) && charged

        internal fun completedFor(candidate: ComplaintAdminContentOperation, receipt: ComplaintAdminContentReceipt?): Boolean =
            chargedFor(candidate) && when (receipt) {
                is ComplaintAdminContentReceipt.Applied -> !receiptOnly && audit?.completedFor(candidate) == true
                is ComplaintAdminContentReceipt.Rejected -> receiptOnly && audit == null
                null -> false
            }

        @Suppress("TooGenericExceptionCaught")
        internal fun keepReceiptOnly(candidate: ComplaintAdminContentOperation) {
            try {
                check(candidate === operation && charged && !receiptOnly && audit == null)
                val retained = after.refundActual(checkNotNull(store.expectedPolicyDigest), ComplaintCapacityCharges.AUDIT)
                persist(after.balance, retained.balance, rejection = true)
                receiptOnly = true
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        internal fun prepaidAudit(candidate: ComplaintAdminContentOperation, mutation: ComplaintAuditMutation.ContentEdited): ChargedComplaintAudit {
            try {
                check(candidate === operation && charged && !receiptOnly && audit == null)
                val result = ChargedComplaintAudit.prepaidAdminContent(this, operation, mutation)
                audit = result
                return result
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        private fun persist(old: ComplaintCapacityBalance, next: ComplaintCapacityBalance, rejection: Boolean) {
            for (counter in ADMIN_EDIT_COUNTERS) {
                operation.requireCapacityWrite(this, store.jdbc, rejection)
                if (old.free[counter] == next.free[counter] && old.actual[counter] == next.actual[counter]) continue
                check(
                    store.jdbc.update(
                        REFUND_COUNTER,
                        next.free[counter],
                        next.actual[counter],
                        counter.storedName,
                        old.free[counter],
                        old.actual[counter],
                    ) == 1,
                )
            }
            operation.requireCapacityWrite(this, store.jdbc, rejection)
        }

        override fun toString(): String = "LockedAdminContent(redacted)"

        companion object {
            @Suppress("TooGenericExceptionCaught")
            internal fun lock(store: JdbcComplaintCapacityStore, operation: ComplaintAdminContentOperation): LockedAdminContent {
                try {
                    operation.beginCounterLock(store.jdbc)
                    val before = store.readLockedLedger()
                    val after = before.chargeCreation(checkNotNull(store.expectedPolicyDigest), ComplaintCapacityCharges.ADMIN_EDIT)
                    val allocation = LockedAdminContent(store, operation, after)
                    operation.retainCapacity(allocation, store.jdbc, before)
                    allocation.persist(before.balance, after.balance, rejection = false)
                    allocation.charged = true
                    return allocation
                } catch (problem: Throwable) {
                    operation.failed(problem)
                }
            }
        }
    }

    /** Status and closure share this receipt/audit charge; closure bytes belong to the prepaid content lifecycle. */
    internal class LockedAdminStatus private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: ComplaintAdminStatusMutation,
        private val after: ComplaintCapacityLedger,
    ) {
        private var charged = false
        private var receiptOnly = false
        private var audit: ChargedComplaintAudit? = null

        internal fun belongsTo(candidate: ComplaintAdminStatusMutation): Boolean = operation === candidate
        internal fun chargedFor(candidate: ComplaintAdminStatusMutation): Boolean = belongsTo(candidate) && charged

        internal fun completedFor(candidate: ComplaintAdminStatusMutation, receipt: ComplaintAdminStatusReceipt?): Boolean =
            chargedFor(candidate) && when (receipt) {
                is ComplaintAdminStatusReceipt.Applied -> !receiptOnly && audit?.completedFor(candidate) == true
                is ComplaintAdminStatusReceipt.Rejected -> receiptOnly && audit == null
                null -> false
            }

        @Suppress("TooGenericExceptionCaught")
        internal fun keepReceiptOnly(candidate: ComplaintAdminStatusMutation) {
            try {
                check(candidate === operation && charged && !receiptOnly && audit == null)
                val retained = after.refundActual(checkNotNull(store.expectedPolicyDigest), ComplaintCapacityCharges.AUDIT)
                persist(after.balance, retained.balance, rejection = true)
                receiptOnly = true
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        internal fun prepaidAudit(candidate: ComplaintAdminStatusMutation): ChargedComplaintAudit {
            try {
                check(candidate === operation && charged && !receiptOnly && audit == null)
                val result = ChargedComplaintAudit.prepaidAdminStatus(this, operation)
                audit = result
                return result
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        private fun persist(old: ComplaintCapacityBalance, next: ComplaintCapacityBalance, rejection: Boolean) {
            for (counter in ADMIN_STATUS_COUNTERS) {
                operation.requireCapacityWrite(this, store.jdbc, rejection)
                if (old.free[counter] == next.free[counter] && old.actual[counter] == next.actual[counter]) continue
                check(
                    store.jdbc.update(
                        REFUND_COUNTER,
                        next.free[counter],
                        next.actual[counter],
                        counter.storedName,
                        old.free[counter],
                        old.actual[counter],
                    ) == 1,
                )
            }
            operation.requireCapacityWrite(this, store.jdbc, rejection)
        }

        override fun toString(): String = "LockedAdminStatus(redacted)"

        companion object {
            @Suppress("TooGenericExceptionCaught")
            internal fun lock(store: JdbcComplaintCapacityStore, operation: ComplaintAdminStatusMutation): LockedAdminStatus {
                try {
                    operation.beginCounterLock(store.jdbc)
                    val before = store.readLockedLedger()
                    val after = before.chargeCreation(checkNotNull(store.expectedPolicyDigest), ComplaintCapacityCharges.ADMIN_STATUS)
                    val allocation = LockedAdminStatus(store, operation, after)
                    operation.retainCapacity(allocation, store.jdbc, before)
                    allocation.persist(before.balance, after.balance, rejection = false)
                    allocation.charged = true
                    return allocation
                } catch (problem: Throwable) {
                    operation.failed(problem)
                }
            }
        }
    }

    /** One concrete batch pays one receipt and N audits. Every counted insertion remains identity-bound and one-use. */
    internal class LockedAdminBatchStatus private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: ComplaintAdminBatchStatusMutation,
        private val after: ComplaintCapacityLedger,
    ) {
        private var charged = false
        private var receiptOnly = false
        private val targetCount = operation.targetCount
        private val audits = ArrayList<ChargedComplaintAudit>(targetCount)

        internal fun belongsTo(candidate: ComplaintAdminBatchStatusMutation): Boolean = operation === candidate
        internal fun chargedFor(candidate: ComplaintAdminBatchStatusMutation): Boolean = belongsTo(candidate) && charged

        internal fun completedFor(candidate: ComplaintAdminBatchStatusMutation, receipt: ComplaintAdminBatchStatusReceipt?): Boolean =
            chargedFor(candidate) && when (receipt) {
                is ComplaintAdminBatchStatusReceipt.Applied -> !receiptOnly && receipt.items.size == targetCount &&
                    audits.size == targetCount && audits.all { it.completedFor(candidate) }
                is ComplaintAdminBatchStatusReceipt.Rejected -> receiptOnly && audits.isEmpty()
                null -> false
            }

        @Suppress("TooGenericExceptionCaught")
        internal fun keepReceiptOnly(candidate: ComplaintAdminBatchStatusMutation) {
            try {
                check(candidate === operation && charged && !receiptOnly && audits.isEmpty())
                val retained = after.refundActual(checkNotNull(store.expectedPolicyDigest), ComplaintCapacityCharges.AUDIT.scaled(targetCount.toLong()))
                persist(after.balance, retained.balance, rejection = true)
                receiptOnly = true
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        @Suppress("TooGenericExceptionCaught")
        internal fun prepaidAudit(candidate: ComplaintAdminBatchStatusMutation): ChargedComplaintAudit {
            try {
                check(candidate === operation && charged && !receiptOnly && audits.size < targetCount && audits.all { it.completedFor(operation) })
                val result = ChargedComplaintAudit.prepaidAdminBatchStatus(this, operation)
                audits.add(result)
                return result
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        private fun persist(old: ComplaintCapacityBalance, next: ComplaintCapacityBalance, rejection: Boolean) {
            for (counter in ADMIN_BATCH_STATUS_COUNTERS) {
                operation.requireCapacityWrite(this, store.jdbc, rejection)
                if (old.free[counter] == next.free[counter] && old.actual[counter] == next.actual[counter]) continue
                check(
                    store.jdbc.update(
                        REFUND_COUNTER,
                        next.free[counter],
                        next.actual[counter],
                        counter.storedName,
                        old.free[counter],
                        old.actual[counter],
                    ) == 1,
                )
            }
            operation.requireCapacityWrite(this, store.jdbc, rejection)
        }

        override fun toString(): String = "LockedAdminBatchStatus(redacted)"

        companion object {
            @Suppress("TooGenericExceptionCaught")
            internal fun lock(store: JdbcComplaintCapacityStore, operation: ComplaintAdminBatchStatusMutation): LockedAdminBatchStatus {
                try {
                    operation.beginCounterLock(store.jdbc)
                    val before = store.readLockedLedger()
                    val after = before.chargeCreation(checkNotNull(store.expectedPolicyDigest), ComplaintCapacityCharges.adminBatchStatus(operation.targetCount))
                    val allocation = LockedAdminBatchStatus(store, operation, after)
                    operation.retainCapacity(allocation, store.jdbc, before)
                    allocation.persist(before.balance, after.balance, rejection = false)
                    allocation.charged = true
                    return allocation
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

    /** Existing22 locked counters; only the original SEALED run/audit operation selects this one fixed transfer. */
    internal class LockedTestRunSealedAudit private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: TestRunSealingOperationV1,
        private val counters: LockedCounters,
    ) {
        private var issued = false
        private var completed = false

        internal fun completedFor(candidate: TestRunSealingOperationV1): Boolean = operation === candidate && completed

        internal fun settle(candidate: TestRunSealingOperationV1) {
            try {
                check(candidate === operation && !issued)
                operation.requireCounterTransfer(this, store.jdbc)
                issued = true
                val before = counters.ledger.balance
                val after = operation.settleLockedLedger(store.jdbc, counters.ledger, counters.daily, checkNotNull(store.expectedPolicyDigest)).balance
                for (counter in ComplaintCapacityEncoding.lockOrder()) {
                    operation.requireCounterTransfer(this, store.jdbc)
                    if (before.actual[counter] == after.actual[counter] && before.recoveryReserved[counter] == after.recoveryReserved[counter] &&
                        before.testReserved[counter] == after.testReserved[counter]) continue
                    check(store.jdbc.update(TEST_RESERVE_COUNTER,
                        after.actual[counter], after.recoveryReserved[counter], after.testReserved[counter], counter.storedName,
                        before.free[counter], before.actual[counter], before.recoveryReserved[counter], before.testReserved[counter]) == 1)
                }
                operation.requireCounterTransfer(this, store.jdbc)
                completed = true
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        override fun toString(): String = "LockedTestRunSealedAudit(original-run-paid-audit,redacted)"

        companion object {
            internal fun lock(store: JdbcComplaintCapacityStore, operation: TestRunSealingOperationV1): LockedTestRunSealedAudit {
                try {
                    operation.beginCounterLock(store.jdbc)
                    return LockedTestRunSealedAudit(store, operation, store.readLockedCounters())
                } catch (problem: Throwable) {
                    operation.failed(problem)
                }
            }
        }
    }

    /** Ordinary actual only. The typed operation proves ALL row ownership/removals before an exact refund. */
    internal class LockedTestActiveInitialCheckpoint private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: TestActiveInitialCheckpointOperationV1,
        private val counters: LockedCounters,
    ) {
        private var issued = false
        private var completed = false
        internal fun completedFor(candidate: TestActiveInitialCheckpointOperationV1): Boolean = operation === candidate && completed
        internal fun charge(candidate: TestActiveInitialCheckpointOperationV1) {
            try {
                check(candidate === operation && !issued)
                operation.requireCharge(this, store.jdbc); issued = true
                val expected = checkNotNull(store.expectedPolicyDigest)
                persist(counters.ledger.chargeCreation(expected, TestActiveInitialCheckpointStorageV1.ROW).balance)
                operation.requireCharge(this, store.jdbc); completed = true
            } catch (problem: Throwable) { operation.failed(problem) }
        }
        internal fun refund(candidate: TestActiveInitialCheckpointOperationV1) {
            try {
                check(candidate === operation && !issued)
                val count = operation.refundCount(this, store.jdbc); issued = true
                val expected = checkNotNull(store.expectedPolicyDigest)
                persist(counters.ledger.refundActual(expected, TestActiveInitialCheckpointStorageV1.rows(count)).balance)
                check(operation.refundCount(this, store.jdbc) == count); completed = true
            } catch (problem: Throwable) { operation.failed(problem) }
        }
        private fun persist(after: ComplaintCapacityBalance) {
            val expected = checkNotNull(store.expectedPolicyDigest)
            val before = counters.ledger.balance
            check(after.testReserved == before.testReserved && after.recoveryReserved == before.recoveryReserved &&
                after.hardLimit == before.hardLimit && after.creationLimit == before.creationLimit)
            for (counter in ComplaintCapacityEncoding.lockOrder()) {
                if (before.free[counter] == after.free[counter] && before.actual[counter] == after.actual[counter]) continue
                check(counter === ComplaintCapacityCounter.SCAN_RUNS || counter === ComplaintCapacityCounter.STORAGE_BYTES)
                // Same reserved values are CAS predicates, not mutable settlement columns.
                check(store.jdbc.update(INITIAL_CHECKPOINT_COUNTER, after.free[counter], after.actual[counter], counter.storedName, counter.storedOrdinal, expected,
                    before.hardLimit[counter], before.creationLimit[counter], before.free[counter], before.actual[counter],
                    before.recoveryReserved[counter], before.testReserved[counter]) == 1)
            }
        }
        override fun toString(): String = "LockedInitialCheckpoint(ordinary-actual4416-per-row,no-reserve-spend)"
        companion object {
            internal fun lock(store: JdbcComplaintCapacityStore, operation: TestActiveInitialCheckpointOperationV1): LockedTestActiveInitialCheckpoint {
                try {
                    operation.beginCounterLock(store.jdbc)
                    val locked = LockedTestActiveInitialCheckpoint(store, operation, store.readLockedCounters())
                    operation.requireCounterRead(store.jdbc)
                    return locked
                } catch (problem: Throwable) { operation.failed(problem) }
            }
        }
    }

    /** Independently paid ordinary STORAGE charge. No test/terminal reserve is spent or refunded. */
    internal class LockedTestActiveFirstCut private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: TestActiveFirstCutOperationV1,
        private val counters: LockedCounters,
    ) {
        private var issued = false
        private var completed = false

        internal fun completedFor(candidate: TestActiveFirstCutOperationV1): Boolean = operation === candidate && completed

        internal fun charge(candidate: TestActiveFirstCutOperationV1) {
            try {
                check(candidate === operation && !issued)
                operation.requireCounterCharge(this, store.jdbc)
                issued = true
                val expected = checkNotNull(store.expectedPolicyDigest)
                val before = counters.ledger.balance
                val after = counters.ledger.chargeCreation(expected, TestActiveFirstSealStorageV1.ROW).balance
                check(after.recoveryReserved == before.recoveryReserved && after.testReserved == before.testReserved &&
                    after.hardLimit == before.hardLimit && after.creationLimit == before.creationLimit)
                val counter = ComplaintCapacityCounter.STORAGE_BYTES
                check(store.jdbc.update(CHARGE_ENROLLMENT_COUNTER,
                    after.free[counter], after.actual[counter], after.testReserved[counter], counter.storedName, counter.storedOrdinal, expected,
                    before.hardLimit[counter], before.creationLimit[counter], before.free[counter], before.actual[counter],
                    before.recoveryReserved[counter], before.testReserved[counter]) == 1)
                operation.requireCounterCharge(this, store.jdbc)
                completed = true
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        override fun toString(): String = "LockedTestActiveFirstCut(original-independent-ordinary-2MiB-charge,redacted)"

        companion object {
            internal fun lock(store: JdbcComplaintCapacityStore, operation: TestActiveFirstCutOperationV1): LockedTestActiveFirstCut {
                try {
                    operation.beginCounterLock(store.jdbc)
                    val locked = LockedTestActiveFirstCut(store, operation, store.readLockedCounters())
                    operation.requireCounterRead(store.jdbc)
                    return locked
                } catch (problem: Throwable) {
                    operation.failed(problem)
                }
            }
        }
    }

    internal class LockedTestOrdinarySeal private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: TestOrdinarySealOperationV1,
        private val counters: LockedCounters,
    ) {
        private var issued = false
        private var completed = false

        internal fun completedFor(candidate: TestOrdinarySealOperationV1): Boolean = operation === candidate && completed

        internal fun settle(candidate: TestOrdinarySealOperationV1) {
            try {
                check(candidate === operation && !issued)
                operation.requireCounterTransfer(this, store.jdbc)
                issued = true
                val before = counters.ledger.balance
                val after = operation.settleLockedLedger(store.jdbc, counters.ledger, counters.daily, checkNotNull(store.expectedPolicyDigest)).balance
                for (counter in ComplaintCapacityEncoding.lockOrder()) {
                    operation.requireCounterTransfer(this, store.jdbc)
                    if (before.actual[counter] == after.actual[counter] && before.recoveryReserved[counter] == after.recoveryReserved[counter] &&
                        before.testReserved[counter] == after.testReserved[counter]) continue
                    check(store.jdbc.update(TEST_RESERVE_COUNTER,
                        after.actual[counter], after.recoveryReserved[counter], after.testReserved[counter], counter.storedName,
                        before.free[counter], before.actual[counter], before.recoveryReserved[counter], before.testReserved[counter]) == 1)
                }
                operation.requireCounterTransfer(this, store.jdbc)
                completed = true
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        override fun toString(): String = "LockedTestOrdinarySeal(original-run-paid-ordinary-seal,redacted)"

        companion object {
            internal fun lock(store: JdbcComplaintCapacityStore, operation: TestOrdinarySealOperationV1): LockedTestOrdinarySeal {
                try {
                    operation.beginCounterLock(store.jdbc)
                    return LockedTestOrdinarySeal(store, operation, store.readLockedCounters())
                } catch (problem: Throwable) {
                    operation.failed(problem)
                }
            }
        }
    }

    internal class LockedTestInstallationManifest private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: TestInstallationManifestOperationV1,
        private val counters: LockedCounters,
    ) {
        private var issued = false
        private var completed = false

        internal fun completedFor(candidate: TestInstallationManifestOperationV1): Boolean = operation === candidate && completed

        internal fun settle(candidate: TestInstallationManifestOperationV1) {
            try {
                check(candidate === operation && !issued)
                operation.requireCounterTransfer(this, store.jdbc)
                issued = true
                val before = counters.ledger.balance
                val after = operation.settleLockedLedger(store.jdbc, counters.ledger, counters.daily, checkNotNull(store.expectedPolicyDigest)).balance
                for (counter in ComplaintCapacityEncoding.lockOrder()) {
                    operation.requireCounterTransfer(this, store.jdbc)
                    if (before.actual[counter] == after.actual[counter] && before.recoveryReserved[counter] == after.recoveryReserved[counter] &&
                        before.testReserved[counter] == after.testReserved[counter]) continue
                    check(store.jdbc.update(TEST_RESERVE_COUNTER,
                        after.actual[counter], after.recoveryReserved[counter], after.testReserved[counter], counter.storedName,
                        before.free[counter], before.actual[counter], before.recoveryReserved[counter], before.testReserved[counter]) == 1)
                }
                operation.requireCounterTransfer(this, store.jdbc)
                completed = true
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        override fun toString(): String = "LockedTestInstallationManifest(original-run-paid-manifest-prepare,redacted)"

        companion object {
            internal fun lock(store: JdbcComplaintCapacityStore, operation: TestInstallationManifestOperationV1): LockedTestInstallationManifest {
                try {
                    operation.beginCounterLock(store.jdbc)
                    return LockedTestInstallationManifest(store, operation, store.readLockedCounters())
                } catch (problem: Throwable) {
                    operation.failed(problem)
                }
            }
        }
    }

    internal class LockedTestRunPurge private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: TestRunPurgeOperationV1,
        private val counters: LockedCounters,
    ) {
        private var issued = false
        private var completed = false

        internal fun completedFor(candidate: TestRunPurgeOperationV1): Boolean = operation === candidate && completed

        internal fun settle(candidate: TestRunPurgeOperationV1) {
            try {
                check(candidate === operation && !issued)
                operation.requireCounterTransfer(this, store.jdbc)
                issued = true
                val before = counters.ledger.balance
                val after = operation.settleLockedLedger(store.jdbc, counters.ledger, counters.daily, checkNotNull(store.expectedPolicyDigest)).balance
                for (counter in ComplaintCapacityEncoding.lockOrder()) {
                    operation.requireCounterTransfer(this, store.jdbc)
                    if (before.actual[counter] == after.actual[counter] && before.recoveryReserved[counter] == after.recoveryReserved[counter] &&
                        before.testReserved[counter] == after.testReserved[counter]) continue
                    check(store.jdbc.update(TEST_RESERVE_COUNTER,
                        after.actual[counter], after.recoveryReserved[counter], after.testReserved[counter], counter.storedName,
                        before.free[counter], before.actual[counter], before.recoveryReserved[counter], before.testReserved[counter]) == 1)
                }
                operation.requireCounterTransfer(this, store.jdbc)
                completed = true
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        override fun toString(): String = "LockedTestRunPurge(original-run-purge-physical-and-audit-promise,redacted)"

        companion object {
            internal fun lock(store: JdbcComplaintCapacityStore, operation: TestRunPurgeOperationV1): LockedTestRunPurge {
                try {
                    operation.beginCounterLock(store.jdbc)
                    return LockedTestRunPurge(store, operation, store.readLockedCounters())
                } catch (problem: Throwable) {
                    operation.failed(problem)
                }
            }
        }
    }

    internal class LockedTestTerminalEpochSeal private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: TestTerminalEpochSealOperationV1,
        private val counters: LockedCounters,
    ) {
        private var issued = false
        private var completed = false

        internal fun completedFor(candidate: TestTerminalEpochSealOperationV1): Boolean = operation === candidate && completed

        internal fun settle(candidate: TestTerminalEpochSealOperationV1) {
            try {
                check(candidate === operation && !issued)
                operation.requireCounterTransfer(this, store.jdbc)
                issued = true
                val before = counters.ledger.balance
                val after = operation.settleLockedLedger(store.jdbc, counters.ledger, counters.daily, checkNotNull(store.expectedPolicyDigest)).balance
                for (counter in ComplaintCapacityEncoding.lockOrder()) {
                    operation.requireCounterTransfer(this, store.jdbc)
                    if (before.actual[counter] == after.actual[counter] && before.recoveryReserved[counter] == after.recoveryReserved[counter] &&
                        before.testReserved[counter] == after.testReserved[counter]) continue
                    check(store.jdbc.update(TEST_RESERVE_COUNTER,
                        after.actual[counter], after.recoveryReserved[counter], after.testReserved[counter], counter.storedName,
                        before.free[counter], before.actual[counter], before.recoveryReserved[counter], before.testReserved[counter]) == 1)
                }
                operation.requireCounterTransfer(this, store.jdbc)
                completed = true
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        override fun toString(): String = "LockedTestTerminalEpochSeal(original-terminal-epoch-seal-one-existing-sidecar,redacted)"

        companion object {
            internal fun lock(store: JdbcComplaintCapacityStore, operation: TestTerminalEpochSealOperationV1): LockedTestTerminalEpochSeal {
                try {
                    operation.beginCounterLock(store.jdbc)
                    return LockedTestTerminalEpochSeal(store, operation, store.readLockedCounters())
                } catch (problem: Throwable) {
                    operation.failed(problem)
                }
            }
        }
    }

    /** Read-only original-owned paid closure. This type deliberately has no settlement/update method. */
    internal class LockedTestInstallationManifestPublication private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: TestInstallationManifestPublicationOperationV1,
        private val counters: LockedCounters,
    ) {
        private var issued = false
        private var completed = false
        internal fun completedFor(candidate: TestInstallationManifestPublicationOperationV1): Boolean = operation === candidate && completed
        internal fun checkPaid(candidate: TestInstallationManifestPublicationOperationV1) {
            try {
                check(candidate === operation && !issued)
                operation.requireCounterCheck(this, store.jdbc)
                issued = true
                operation.requirePaidLedger(store.jdbc, counters.ledger, counters.daily, checkNotNull(store.expectedPolicyDigest))
                operation.requireCounterCheck(this, store.jdbc)
                completed = true
            } catch (problem: Throwable) { operation.failed(problem) }
        }
        override fun toString(): String = "LockedTestInstallationManifestPublication(read-only-paid-closure,redacted)"
        companion object {
            internal fun lock(store: JdbcComplaintCapacityStore, operation: TestInstallationManifestPublicationOperationV1): LockedTestInstallationManifestPublication {
                try {
                    operation.beginCounterLock(store.jdbc)
                    return LockedTestInstallationManifestPublication(store, operation, store.readLockedCounters())
                } catch (problem: Throwable) { operation.failed(problem) }
            }
        }
    }

    internal class LockedTestOrdinaryDrain private constructor(
        private val store: JdbcComplaintCapacityStore,
        private val operation: TestOrdinaryDrainOperationV1,
        private val counters: LockedCounters,
    ) {
        private var issued = false
        private var completed = false

        internal fun completedFor(candidate: TestOrdinaryDrainOperationV1): Boolean = operation === candidate && completed

        internal fun settle(candidate: TestOrdinaryDrainOperationV1) {
            try {
                check(candidate === operation && !issued)
                operation.requireCounterTransfer(this, store.jdbc)
                issued = true
                val before = counters.ledger.balance
                val after = operation.settleLockedLedger(store.jdbc, counters.ledger, counters.daily, checkNotNull(store.expectedPolicyDigest)).balance
                for (counter in ComplaintCapacityEncoding.lockOrder()) {
                    operation.requireCounterTransfer(this, store.jdbc)
                    if (before.free[counter] == after.free[counter] && before.actual[counter] == after.actual[counter] && before.recoveryReserved[counter] == after.recoveryReserved[counter] &&
                        before.testReserved[counter] == after.testReserved[counter]) continue
                    check(store.jdbc.update(TEST_ORDINARY_DRAIN_COUNTER,
                        after.free[counter], after.actual[counter], after.recoveryReserved[counter], after.testReserved[counter], counter.storedName,
                        before.free[counter], before.actual[counter], before.recoveryReserved[counter], before.testReserved[counter]) == 1)
                }
                operation.requireCounterTransfer(this, store.jdbc)
                completed = true
            } catch (problem: Throwable) {
                operation.failed(problem)
            }
        }

        override fun toString(): String = "LockedTestOrdinaryDrain(original-run-paid-ordinary-drain,redacted)"

        companion object {
            internal fun lock(store: JdbcComplaintCapacityStore, operation: TestOrdinaryDrainOperationV1): LockedTestOrdinaryDrain {
                try {
                    operation.beginCounterLock(store.jdbc)
                    return LockedTestOrdinaryDrain(store, operation, store.readLockedCounters())
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

        internal fun belongsTo(candidate: ComplaintOwnerCreateOperation): Boolean = source is Source.OwnerCreate && source.operation === candidate

        internal fun belongsTo(candidate: ComplaintOwnerEditOperation): Boolean = source is Source.OwnerEdit && source.operation === candidate

        internal fun belongsTo(candidate: ComplaintAdminContentOperation): Boolean = source is Source.AdminContent && source.operation === candidate

        internal fun belongsTo(candidate: ComplaintAdminStatusMutation): Boolean = source is Source.AdminStatus && source.operation === candidate
        internal fun belongsTo(candidate: ComplaintAdminBatchStatusMutation): Boolean = source is Source.AdminBatchStatus && source.operation === candidate

        internal fun chargedFor(candidate: ComplaintGrantConsumption): Boolean = belongsTo(candidate) && charged

        internal fun chargedFor(candidate: ComplaintDeletionOperation): Boolean = belongsTo(candidate) && charged

        internal fun chargedFor(candidate: ComplaintOwnerCreateOperation): Boolean = belongsTo(candidate) && charged

        internal fun chargedFor(candidate: ComplaintOwnerEditOperation): Boolean = belongsTo(candidate) && charged

        internal fun chargedFor(candidate: ComplaintAdminContentOperation): Boolean = belongsTo(candidate) && charged

        internal fun chargedFor(candidate: ComplaintAdminStatusMutation): Boolean = belongsTo(candidate) && charged
        internal fun chargedFor(candidate: ComplaintAdminBatchStatusMutation): Boolean = belongsTo(candidate) && charged

        internal fun completedFor(candidate: ComplaintGrantConsumption): Boolean = chargedFor(candidate) && insertion?.completedFor(this) == true

        internal fun completedFor(candidate: ComplaintDeletionOperation): Boolean = chargedFor(candidate) && insertion?.completedFor(this) == true

        internal fun completedFor(candidate: ComplaintOwnerCreateOperation): Boolean = chargedFor(candidate) && insertion?.completedFor(this) == true

        internal fun completedFor(candidate: ComplaintOwnerEditOperation): Boolean = chargedFor(candidate) && insertion?.completedFor(this) == true

        internal fun completedFor(candidate: ComplaintAdminContentOperation): Boolean = chargedFor(candidate) && insertion?.completedFor(this) == true

        internal fun completedFor(candidate: ComplaintAdminStatusMutation): Boolean = chargedFor(candidate) && insertion?.completedFor(this) == true
        internal fun completedFor(candidate: ComplaintAdminBatchStatusMutation): Boolean = chargedFor(candidate) && insertion?.completedFor(this) == true

        internal fun beginInsert(candidate: ComplaintAuditInsertion, entry: CountedComplaintAuditEntry): ComplaintAuditSelectedHolder {
            val holder = source.holder(this)
            check(insertion == null && candidate.belongsTo(this) && entry.mutation === mutation)
            if (source is Source.Deletion) source.operation.requireAuditEntry(entry)
            if (source is Source.OwnerCreate) source.operation.requireAuditEntry(entry)
            if (source is Source.OwnerEdit) source.operation.requireAuditEntry(entry)
            if (source is Source.AdminContent) source.operation.requireAuditEntry(entry)
            if (source is Source.AdminStatus) source.operation.requireAuditEntry(entry)
            if (source is Source.AdminBatchStatus) source.operation.requireAuditEntry(entry)
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

        /** Only fixed existing-phase producers select a holder; none accepts a resource flag or connection. */
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

            /** The ordinary create allocation already paid. Generic charge entry is deliberately forbidden. */
            class OwnerCreate(val operation: ComplaintOwnerCreateOperation) : Source {
                override fun beginCharge(jdbc: JdbcTemplate): Nothing = failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
                override fun retainCharge(charged: ChargedComplaintAudit, jdbc: JdbcTemplate): Nothing =
                    failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
                override fun requireCharge(charged: ChargedComplaintAudit, jdbc: JdbcTemplate): Nothing =
                    failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
                override fun holder(charged: ChargedComplaintAudit): ComplaintAuditSelectedHolder =
                    ComplaintAuditSelectedHolder.Ordinary(operation.auditEntityManager(charged))
                override fun failed(problem: Throwable): Nothing = operation.failed(problem)
            }

            /** Edit audit was prepaid before all domain locks. Generic charge entry stays forbidden. */
            class OwnerEdit(val operation: ComplaintOwnerEditOperation) : Source {
                override fun beginCharge(jdbc: JdbcTemplate): Nothing = failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
                override fun retainCharge(charged: ChargedComplaintAudit, jdbc: JdbcTemplate): Nothing =
                    failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
                override fun requireCharge(charged: ChargedComplaintAudit, jdbc: JdbcTemplate): Nothing =
                    failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
                override fun holder(charged: ChargedComplaintAudit): ComplaintAuditSelectedHolder =
                    ComplaintAuditSelectedHolder.Ordinary(operation.auditEntityManager(charged))
                override fun failed(problem: Throwable): Nothing = operation.failed(problem)
            }

            /** Edit audit was prepaid before all domain locks. Generic charge entry stays forbidden. */
            class AdminContent(val operation: ComplaintAdminContentOperation) : Source {
                override fun beginCharge(jdbc: JdbcTemplate): Nothing = failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
                override fun retainCharge(charged: ChargedComplaintAudit, jdbc: JdbcTemplate): Nothing =
                    failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
                override fun requireCharge(charged: ChargedComplaintAudit, jdbc: JdbcTemplate): Nothing =
                    failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
                override fun holder(charged: ChargedComplaintAudit): ComplaintAuditSelectedHolder =
                    ComplaintAuditSelectedHolder.Ordinary(operation.auditEntityManager(charged))
                override fun failed(problem: Throwable): Nothing = operation.failed(problem)
            }

            /** The concrete status/closure audit was prepaid before all domain locks. Generic charge entry stays forbidden. */
            class AdminStatus(val operation: ComplaintAdminStatusMutation) : Source {
                override fun beginCharge(jdbc: JdbcTemplate): Nothing = failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
                override fun retainCharge(charged: ChargedComplaintAudit, jdbc: JdbcTemplate): Nothing =
                    failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
                override fun requireCharge(charged: ChargedComplaintAudit, jdbc: JdbcTemplate): Nothing =
                    failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
                override fun holder(charged: ChargedComplaintAudit): ComplaintAuditSelectedHolder =
                    ComplaintAuditSelectedHolder.Ordinary(operation.auditEntityManager(charged))
                override fun failed(problem: Throwable): Nothing = operation.failed(problem)
            }

            /** The concrete batch row audit was prepaid before all domain locks. Generic charge entry stays forbidden. */
            class AdminBatchStatus(val operation: ComplaintAdminBatchStatusMutation) : Source {
                override fun beginCharge(jdbc: JdbcTemplate): Nothing = failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
                override fun retainCharge(charged: ChargedComplaintAudit, jdbc: JdbcTemplate): Nothing =
                    failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
                override fun requireCharge(charged: ChargedComplaintAudit, jdbc: JdbcTemplate): Nothing =
                    failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
                override fun holder(charged: ChargedComplaintAudit): ComplaintAuditSelectedHolder =
                    ComplaintAuditSelectedHolder.Ordinary(operation.auditEntityManager(charged))
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

            internal fun prepaidOwnerCreate(
                allocation: LockedOwnerCreate,
                operation: ComplaintOwnerCreateOperation,
                mutation: ComplaintAuditMutation.Created,
            ): ChargedComplaintAudit {
                check(allocation.chargedFor(operation))
                val charged = ChargedComplaintAudit(Source.OwnerCreate(operation), mutation)
                charged.charged = true
                operation.retainPrepaidAudit(allocation, charged)
                return charged
            }

            internal fun prepaidOwnerEdit(
                allocation: LockedOwnerEdit,
                operation: ComplaintOwnerEditOperation,
                mutation: ComplaintAuditMutation.ContentEdited,
            ): ChargedComplaintAudit {
                check(allocation.chargedFor(operation))
                val charged = ChargedComplaintAudit(Source.OwnerEdit(operation), mutation)
                charged.charged = true
                operation.retainPrepaidAudit(allocation, charged)
                return charged
            }

            internal fun prepaidAdminContent(
                allocation: LockedAdminContent,
                operation: ComplaintAdminContentOperation,
                mutation: ComplaintAuditMutation.ContentEdited,
            ): ChargedComplaintAudit {
                check(allocation.chargedFor(operation))
                val charged = ChargedComplaintAudit(Source.AdminContent(operation), mutation)
                charged.charged = true
                operation.retainPrepaidAudit(allocation, charged)
                return charged
            }

            internal fun prepaidAdminStatus(
                allocation: LockedAdminStatus,
                operation: ComplaintAdminStatusMutation,
            ): ChargedComplaintAudit {
                check(allocation.chargedFor(operation))
                val charged = ChargedComplaintAudit(Source.AdminStatus(operation), operation.prepaidAuditMutation(allocation))
                charged.charged = true
                operation.retainPrepaidAudit(allocation, charged)
                return charged
            }

            internal fun prepaidAdminBatchStatus(
                allocation: LockedAdminBatchStatus,
                operation: ComplaintAdminBatchStatusMutation,
            ): ChargedComplaintAudit {
                check(allocation.chargedFor(operation))
                val charged = ChargedComplaintAudit(Source.AdminBatchStatus(operation), operation.prepaidAuditMutation(allocation))
                charged.charged = true
                operation.retainPrepaidAudit(allocation, charged)
                return charged
            }

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
        val CATALOG_SIGNER_ROTATION_COUNTERS = listOf(ComplaintCapacityCounter.CATALOG_MUTATIONS, ComplaintCapacityCounter.STORAGE_BYTES)
        val OWNER_CREATE_COUNTERS = ComplaintCapacityEncoding.lockOrder().filter { ComplaintCapacityCharges.OWNER_CREATE[it] > 0 }
        val OWNER_EDIT_COUNTERS = ComplaintCapacityEncoding.lockOrder().filter { ComplaintCapacityCharges.OWNER_EDIT[it] > 0 }
        val ADMIN_EDIT_COUNTERS = ComplaintCapacityEncoding.lockOrder().filter { ComplaintCapacityCharges.ADMIN_EDIT[it] > 0 }
        val ADMIN_STATUS_COUNTERS = ComplaintCapacityEncoding.lockOrder().filter { ComplaintCapacityCharges.ADMIN_STATUS[it] > 0 }
        val ADMIN_BATCH_STATUS_COUNTERS = ComplaintCapacityEncoding.lockOrder().filter { ComplaintCapacityCharges.adminBatchStatus(1)[it] > 0 }
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
        val READ_COUNTERS = LOCK_COUNTERS.removeSuffix("\nFOR UPDATE")
        val TEST_PROJECTION_COUNTER_FINGERPRINTS = """
            SELECT c.name, c.ordinal, c.accounting_version,
                CASE WHEN octet_length((to_jsonb(c))::text) BETWEEN 1 AND 4096
                    THEN sha256(convert_to((to_jsonb(c) || jsonb_build_object('row_xmin', c.xmin::text))::text, 'UTF8')) END AS row_digest
            FROM complaint_capacity_counters c ORDER BY c.name COLLATE "C" LIMIT 23
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
        val TEST_ORDINARY_DRAIN_COUNTER = """
            UPDATE complaint_capacity_counters
            SET free_units = ?, actual_units = ?, recovery_reserved_units = ?, test_reserved_units = ?, updated_at = clock_timestamp()
            WHERE name = ? AND free_units = ? AND actual_units = ? AND recovery_reserved_units = ? AND test_reserved_units = ?
        """.trimIndent()
        val ENROLLMENT_COUNTER_MATCH = """
            WHERE name = ? AND ordinal = ? AND accounting_version = 1 AND configuration_hash = ? AND NOT configuration_closed
                AND hard_limit = ? AND creation_limit = ? AND free_units = ? AND actual_units = ?
                AND recovery_reserved_units = ? AND test_reserved_units = ?
        """.trimIndent()
        val INITIAL_CHECKPOINT_COUNTER = """
            UPDATE complaint_capacity_counters SET free_units = ?, actual_units = ?, updated_at = clock_timestamp()
        """.trimIndent() + "\n" + ENROLLMENT_COUNTER_MATCH
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
