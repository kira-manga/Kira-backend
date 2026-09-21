package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.catalog.CAPTURE_EPOCH_ROTATION_CONTROL
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationCaptureOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochRotationRowV1
import me.manga.kira.backend.complaint.infrastructure.catalog.LOCK_EPOCH_ROTATION_CONTROL
import me.manga.kira.backend.complaint.infrastructure.catalog.READ_EPOCH_ROTATION_CONTROL
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutCaptureOperationV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutStateV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveFirstCutSqlV1
import java.util.UUID
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Wrapper
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** The distinct original first-delivery owner. Only these fixed operations expose detached rows, never JDBC or arbitrary SQL. */
internal class PersistenceEpochRotationSession private constructor(
    private val entry: PersistencePhysicalEntry,
    private val epoch: PersistenceProducerEpoch,
    private val resource: EpochRotationPersistence,
    private val attempt: PersistenceEpochRotationAttemptV1,
) {
    private val caller = checkNotNull(entry.control).caller
    private val total = attempt.budget
    private val problem = AtomicReference<PersistencePhaseFailureCode?>()
    private val retired = AtomicBoolean()
    private val context = PersistenceJdbcGuardContext.forEpochRotation(entry.jdbc, epoch, this, entry.driverCut)
    private val connection = EpochRotationConnectionCalls(entry, context, this).proxy

    @Volatile private var work: PersistenceTimeBudget? = null
    private var retained: CatalogEpochRotationCaptureOperation? = null
    private var retainedTest: TestActiveFirstCutCaptureOperationV1? = null
    private var stage = Stage.PREPARED
    private var clippingRead = false
    private var readCapKind: PersistenceJdbcGuardCallKind? = null
    private var maintenanceBudget: PersistenceComplaintMaintenanceFenceBudgetV1? = null
    private var maintenanceStage = MaintenanceStage.NEW
    private var observedMaintenanceLock: Boolean? = null

    internal fun begin() {
        requireCaller()
        check(stage === Stage.PREPARED && entry.jdbc.currentSession(this, epoch))
        attempt.requireCore(resource)
        work = total.capped(EpochRotationLimits.REQUEST_PHASE_MILLIS)
        stage = Stage.STARTING
        connection.autoCommit = false
        connection.isReadOnly = false
        connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        if (connection.transactionIsolation != Connection.TRANSACTION_READ_COMMITTED) refuseMaintenance(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        installLimits(EpochRotationLimits.STATEMENT_MILLIS)
        acquireMaintenanceFence()
        installLimits(EpochRotationLimits.STATEMENT_MILLIS) // Same remaining original work, before the existing exclusive E wait.
        connection.prepareStatement(EXCLUSIVE_EPOCH_FENCE).use { statement ->
            statement.executeQuery().use { row -> check(row.next() && !row.next()) }
        }
        requireWork()
        stage = Stage.EXCLUSIVE
    }

    internal fun retain(operation: CatalogEpochRotationCaptureOperation) {
        requireWork()
        check(stage === Stage.EXCLUSIVE && retained == null && retainedTest == null && attempt.owns(operation) && operation.belongsTo(this))
        retained = operation
    }

    internal fun lockControl(operation: CatalogEpochRotationCaptureOperation): CatalogEpochRotationRowV1 {
        requireOperation(operation)
        check(stage === Stage.EXCLUSIVE)
        installLimits(EpochRotationLimits.CONTROL_LOCK_MILLIS)
        val result = query(LOCK_EPOCH_ROTATION_CONTROL, operation.lockArguments())
        stage = Stage.LOCKED
        return result
    }

    internal fun captureControl(operation: CatalogEpochRotationCaptureOperation): CatalogEpochRotationRowV1 {
        requireOperation(operation)
        check(stage === Stage.SAMPLED)
        installLimits(EpochRotationLimits.CONTROL_LOCK_MILLIS)
        val result = query(CAPTURE_EPOCH_ROTATION_CONTROL, operation.captureArguments())
        stage = Stage.WRITTEN
        return result
    }

    internal fun readControl(operation: CatalogEpochRotationCaptureOperation): CatalogEpochRotationRowV1 {
        requireOperation(operation)
        val initial = stage === Stage.LOCKED
        check(initial || stage === Stage.WRITTEN || stage === Stage.SAMPLED)
        installLimits(EpochRotationLimits.CONTROL_LOCK_MILLIS)
        val result = query(READ_EPOCH_ROTATION_CONTROL, operation.readArguments())
        stage = if (initial) Stage.SAMPLED else Stage.REREAD
        return result
    }

    internal fun commit(operation: CatalogEpochRotationCaptureOperation) {
        requireOperation(operation)
        check(stage === Stage.REREAD && operation.completedFor(this))
        connection.commit()
        requireWork()
        check(context.transaction.databaseOutcome() === PersistenceDatabaseOutcome.COMMITTED && !context.transaction.uncertain())
        stage = Stage.COMMITTED
    }

    internal fun retain(operation: TestActiveFirstCutCaptureOperationV1) {
        requireWork()
        check(stage === Stage.EXCLUSIVE && retained == null && retainedTest == null && attempt.owns(operation) && operation.belongsTo(this))
        retainedTest = operation
    }

    /** Fixed ACTIVE authentication/row-lock order; no caller SQL, pooled connection or LIVE dispatch. */
    internal fun lockControl(operation: TestActiveFirstCutCaptureOperationV1) {
        requireOperation(operation)
        check(stage === Stage.EXCLUSIVE)
        installLimits(EpochRotationLimits.CONTROL_LOCK_MILLIS)
        connection.prepareStatement(TestActiveFirstCutSqlV1.authenticate).use { statement ->
            operation.authenticationArguments().forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { row -> check(row.next() && row.getBoolean("valid") && !row.wasNull() && !row.next()) }
        }
        lockTestRow(TestActiveFirstCutSqlV1.lockGlobal, emptyArray(), "data_scope_id", UUID(0L, 0L))
        lockTestRow(TestActiveFirstCutSqlV1.lockScope, operation.scopeArguments(), "data_scope_id", operation.expectedScope())
        lockTestRow(TestActiveFirstCutSqlV1.lockRun, operation.scopeArguments(), "data_scope_id", operation.expectedScope())
        lockTestRow(TestActiveFirstCutSqlV1.lockSlot, operation.scopeArguments(), "operation_token", operation.expectedSlot())
        stage = Stage.LOCKED
    }

    internal fun readControl(operation: TestActiveFirstCutCaptureOperationV1): TestActiveFirstCutStateV1 {
        requireOperation(operation)
        val initial = stage === Stage.LOCKED
        check(initial || stage === Stage.WRITTEN)
        installLimits(EpochRotationLimits.CONTROL_LOCK_MILLIS)
        val observed = connection.prepareStatement(TestActiveFirstCutSqlV1.read).use { statement ->
            operation.readArguments().forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { row ->
                check(row.next())
                val value = TestActiveFirstCutStateV1.copy(row)
                check(!row.next())
                value
            }
        }
        requireWork()
        stage = if (initial) Stage.SAMPLED else Stage.REREAD
        return observed
    }

    internal fun captureControl(operation: TestActiveFirstCutCaptureOperationV1) {
        requireOperation(operation)
        check(stage === Stage.SAMPLED)
        installLimits(EpochRotationLimits.CONTROL_LOCK_MILLIS)
        updateTest(TestActiveFirstCutSqlV1.capture, operation.captureArguments())
        updateTest(TestActiveFirstCutSqlV1.captureSlot, operation.captureSlotArguments())
        stage = Stage.WRITTEN
    }

    internal fun commit(operation: TestActiveFirstCutCaptureOperationV1) {
        requireOperation(operation)
        check(stage === Stage.REREAD && operation.completedFor(this))
        connection.commit()
        requireWork()
        check(context.transaction.databaseOutcome() === PersistenceDatabaseOutcome.COMMITTED && !context.transaction.uncertain())
        stage = Stage.COMMITTED
    }

    internal fun requireReleased(operation: TestActiveFirstCutCaptureOperationV1) {
        if (!caller.isCurrent() || retainedTest !== operation || retained != null || !attempt.owns(operation) || !operation.completedFor(this)) throw failure()
        if (stage !== Stage.RELEASED || problem.get() != null || !entry.jdbc.terminalCompletion().reclaimed()) throw failure()
        if (context.transaction.databaseOutcome() !== PersistenceDatabaseOutcome.COMMITTED) throw failure()
        requireWork()
    }

    private fun requireOperation(operation: TestActiveFirstCutCaptureOperationV1) {
        requireWork()
        check(retainedTest === operation && retained == null && attempt.owns(operation) && operation.belongsTo(this))
        attempt.requireCore(resource)
    }

    private fun lockTestRow(sql: String, values: Array<Any?>, column: String, expected: UUID) {
        requireWork()
        connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { row -> check(row.next() && row.getObject(column, UUID::class.java) == expected && !row.next()) }
        }
        requireWork()
    }

    private fun updateTest(sql: String, values: Array<Any?>) {
        requireWork()
        connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            check(statement.executeUpdate() == 1)
        }
        requireWork()
    }

    /** This is a retirement request only. The existing producer/native/Timer/first-close finalizer owns actual release. */
    internal fun finish() {
        if (retired.compareAndSet(false, true)) entry.jdbc.requestRetirement(epoch)
    }

    internal fun awaitRelease() {
        requireCaller()
        check(retired.get() && stage === Stage.COMMITTED)
        while (!entry.jdbc.terminalCompletion().reclaimed()) {
            requireWork()
            LockSupport.parkNanos(total.remainingMillis(10) * 1_000_000)
        }
        requireWork()
        stage = Stage.RELEASED
    }

    /** Early/between-commit-and-release probes refuse without upgrading or poisoning an otherwise active operation. */
    internal fun requireReleased(operation: CatalogEpochRotationCaptureOperation) {
        if (!caller.isCurrent() || retained !== operation || !operation.completedFor(this)) throw failure()
        if (stage !== Stage.RELEASED || problem.get() != null || !entry.jdbc.terminalCompletion().reclaimed()) throw failure()
        if (context.transaction.databaseOutcome() !== PersistenceDatabaseOutcome.COMMITTED) throw failure()
        requireWork() // A later receipt probe cannot turn a timed-out original attempt into on-time success.
    }

    internal fun failed() {
        problem.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
        finish()
    }

    internal fun failure(): PersistencePhaseException = PersistencePhaseException(
        problem.get() ?: PersistencePhaseFailureCode.COMPLETION_FAILED,
        context.transaction.databaseOutcome(),
        entry.jdbc.terminalCompletion().reclaimed(),
    )

    internal fun jdbcFailure() = failed()

    /** Same caller that may have consumed an overriding platform flag; no replacement caller is captured. */
    internal fun restoreAfterFailure() {
        if (problem.get() != null) caller.restoreAfterFailure()
    }

    /** Data-only deadline attachment survives the factory's TAKEN state; the existing scanner owns physical retirement. */
    internal fun deadlineExpired(): Boolean {
        val expired = persistenceFactoryRemainingMillis(total) == 0L || work?.let { persistenceFactoryRemainingMillis(it) == 0L } == true
        if (expired) problem.compareAndSet(null, PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
        return expired
    }

    internal fun callBudget(kind: PersistenceJdbcGuardCallKind): PersistenceTimeBudget {
        if (kind !== PersistenceJdbcGuardCallKind.CANCELLATION) requireCaller()
        if (kind === PersistenceJdbcGuardCallKind.BUSINESS) {
            requireWork()
            if (healthyMaintenancePrefix()) {
                requireMaintenanceOwner()
                return try {
                    checkNotNull(maintenanceBudget).dispatchBudget()
                } catch (_: PersistenceBoundaryException) {
                    refuseMaintenance(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
                }
            }
        }
        // No restarted emergency allowance: terminal cleanup continues under the original retained physical owner when this expires.
        return work ?: total
    }

    /** Acceptance only. Every lower cleanup admission/arm keeps the original work/total allowance. */
    internal fun maintenanceCleanupBudget(): PersistenceTimeBudget? {
        if (!healthyMaintenancePrefix()) return null
        return try {
            requireMaintenanceOwner()
            checkNotNull(maintenanceBudget).dispatchBudget()
        } catch (_: PersistenceBoundaryException) {
            problem.compareAndSet(null, PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
            null
        } catch (failure: PersistencePhaseException) {
            if (failure.code !== PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED) throw failure
            null // Prefix refusal does not replace or extend the original cleanup allowance.
        }
    }

    /** No replacement allowance: the exact call's acceptance cap includes its native/core return tails. */
    internal fun maintenanceDispatchReturned(kind: PersistenceJdbcGuardCallKind, admitted: PersistenceTimeBudget?) {
        if (kind === PersistenceJdbcGuardCallKind.CANCELLATION || !healthyMaintenancePrefix()) return
        try {
            checkNotNull(admitted).remainingMillis(PersistenceComplaintMaintenanceFenceV1.DISPATCH_MILLIS)
        } catch (_: PersistenceBoundaryException) {
            if (kind !== PersistenceJdbcGuardCallKind.CLEANUP) refuseMaintenance(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
            // Do not turn an actually returned native close into cleanup failure. The next mandatory
            // requireMaintenanceOwner/work check refuses ACCEPTED using this original sticky TIME.
            problem.compareAndSet(null, PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
        }
    }

    private fun healthyMaintenancePrefix(): Boolean = stage === Stage.STARTING && problem.get() == null && maintenanceBudget != null

    /** Only our private read-cap reclip may inherit cleanup admission after a prefix refusal. */
    internal fun connectionKind(method: Method): PersistenceJdbcGuardCallKind =
        if (clippingRead && method.name == "setNetworkTimeout") checkNotNull(readCapKind) else PersistenceJdbcGuardCallKind.BUSINESS

    /** Every guarded descendant dispatch reclips the actual native read cap, not only the initial query. */
    internal fun beforeJdbcCall(kind: PersistenceJdbcGuardCallKind) {
        if (kind === PersistenceJdbcGuardCallKind.CANCELLATION || clippingRead) return
        requireCaller()
        readCapKind = kind
        clippingRead = true
        try {
            val budget = callBudget(kind)
            val ceiling = if (maintenanceBudget == null) EpochRotationLimits.STATEMENT_MILLIS else PersistenceComplaintMaintenanceFenceV1.DISPATCH_MILLIS
            connection.setNetworkTimeout(INLINE, budget.remainingMillis(ceiling).toInt())
        } finally {
            clippingRead = false
            readCapKind = null
        }
    }

    private fun requireOperation(operation: CatalogEpochRotationCaptureOperation) {
        requireWork()
        check(retained === operation && retainedTest == null && attempt.owns(operation) && operation.belongsTo(this))
        attempt.requireCore(resource)
    }

    private fun requireCaller() {
        if (!caller.isCurrent()) throw failure()
        if (caller.sampleOutsideLocks() != null) {
            problem.compareAndSet(null, PersistencePhaseFailureCode.INTERRUPTED)
            finish()
            throw failure()
        }
    }

    private fun requireWork() {
        requireCaller()
        if (deadlineExpired() || problem.get() != null) throw failure()
    }

    /** Same concrete first-delivery owner and native guard; no pooled/alternate acquisition or SQL callback. */
    @Suppress("TooGenericExceptionCaught")
    private fun acquireMaintenanceFence() {
        try {
            requireMaintenanceOwner()
            if (maintenanceStage !== MaintenanceStage.NEW) refuseMaintenance(PersistencePhaseFailureCode.WORK_FAILED)
            maintenanceBudget = PersistenceComplaintMaintenanceFenceBudgetV1(checkNotNull(work))
            maintenanceStage = MaintenanceStage.INSTALLING
            installMaintenanceLimits()
            maintenanceStage = MaintenanceStage.SETTINGS_RETURNED
            requireMaintenanceRemaining()
            maintenanceStage = MaintenanceStage.TRYING
            observedMaintenanceLock = connection.prepareStatement(PersistenceComplaintMaintenanceFenceV1.TRY_SHARED_LOCK).use { statement ->
                requireMaintenanceRemaining()
                statement.executeQuery().use { row ->
                    requireMaintenanceRemaining()
                    if (!row.next()) refuseMaintenance(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                    requireMaintenanceRemaining()
                    val locked = row.getBoolean(1)
                    requireMaintenanceRemaining()
                    if (row.wasNull() || row.next()) refuseMaintenance(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                    requireMaintenanceRemaining()
                    locked
                }
            }
            maintenanceStage = MaintenanceStage.OBSERVED
            requireMaintenanceRemaining() // Includes boolean, descendant closes and guarded return before acceptance.
            if (observedMaintenanceLock != true) refuseMaintenance(PersistencePhaseFailureCode.ENTRY_REFUSED)
            maintenanceStage = MaintenanceStage.READING_GATE
            val gate = PersistenceComplaintMaintenanceGateV1.read(connection)
            requireMaintenanceRemaining()
            attempt.requireMaintenanceGate(resource, gate) // Closed actual LIVE/ACTIVE-TEST original; never a supplied continuation.
            maintenanceStage = MaintenanceStage.GATE_OBSERVED
            requireMaintenanceRemaining()
            maintenanceStage = MaintenanceStage.ACCEPTED
        } catch (cause: Throwable) {
            maintenanceStage = MaintenanceStage.FAILED
            val code = when (cause) {
                is PersistenceBoundaryException -> PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED
                is PersistencePhaseException -> cause.code
                is InterruptedException -> {
                    Thread.currentThread().interrupt()
                    PersistencePhaseFailureCode.INTERRUPTED
                }
                else -> PersistencePhaseFailureCode.WORK_FAILED
            }
            refuseMaintenance(code)
        } finally {
            maintenanceBudget = null // Retirement/physical cleanup keeps the original attempt's custody and deadline.
        }
    }

    private fun requireMaintenanceOwner() {
        requireWork()
        if (stage !== Stage.STARTING || !entry.jdbc.currentSession(this, epoch)) refuseMaintenance(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        attempt.requireCore(resource)
    }

    private fun requireMaintenanceRemaining(): Long {
        requireMaintenanceOwner()
        return try {
            checkNotNull(maintenanceBudget).remainingMillis()
        } catch (_: PersistenceBoundaryException) {
            refuseMaintenance(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
        }
    }

    private fun installMaintenanceLimits() {
        connection.prepareStatement(PersistenceComplaintMaintenanceFenceV1.LOCAL_LIMITS).use { statement ->
            statement.setString(1, requireMaintenanceRemaining().toString() + "ms")
            statement.setString(2, requireMaintenanceRemaining().toString() + "ms")
            requireMaintenanceRemaining()
            statement.executeQuery().use { row ->
                requireMaintenanceRemaining()
                if (!row.next() || row.next()) refuseMaintenance(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                requireMaintenanceRemaining()
            }
        }
    }

    private fun refuseMaintenance(code: PersistencePhaseFailureCode): Nothing {
        problem.compareAndSet(null, code)
        throw failure()
    }

    private fun query(sql: String, arguments: Array<Any?>): CatalogEpochRotationRowV1 {
        requireWork()
        val value = connection.prepareStatement(sql).use { statement ->
            arguments.forEachIndexed { index, argument -> statement.setObject(index + 1, argument) }
            statement.executeQuery().use { row ->
                check(row.next())
                val result = CatalogEpochRotationRowV1.copy(row)
                check(!row.next())
                result
            }
        }
        requireWork()
        return value
    }

    private fun installLimits(lockMillis: Long) {
        val budget = callBudget(PersistenceJdbcGuardCallKind.BUSINESS)
        connection.prepareStatement(LIMITS).use { statement ->
            statement.setString(1, budget.remainingMillis(EpochRotationLimits.REQUEST_PHASE_MILLIS).toString() + "ms")
            statement.setString(2, budget.remainingMillis(EpochRotationLimits.STATEMENT_MILLIS).toString() + "ms")
            statement.setString(3, budget.remainingMillis(lockMillis).toString() + "ms")
            statement.setString(4, budget.remainingMillis(EpochRotationLimits.STATEMENT_MILLIS).toString() + "ms")
            statement.executeQuery().use { row -> check(row.next() && !row.next()) }
        }
    }

    override fun toString(): String = "PersistenceEpochRotationSession(original-first-delivery,non-pooled,no-work-capability)"

    private enum class Stage { PREPARED, STARTING, EXCLUSIVE, LOCKED, SAMPLED, WRITTEN, REREAD, COMMITTED, RELEASED }
    private enum class MaintenanceStage { NEW, INSTALLING, SETTINGS_RETURNED, TRYING, OBSERVED, READING_GATE, GATE_OBSERVED, ACCEPTED, FAILED }

    companion object {
        private val INLINE = Executor { it.run() }
        private const val EXCLUSIVE_EPOCH_FENCE = "SELECT pg_advisory_xact_lock(hashtextextended('complaint-journal-epoch', 0))"
        private const val LIMITS = "SELECT set_config('transaction_timeout', ?, true), set_config('statement_timeout', ?, true), " +
            "set_config('lock_timeout', ?, true), set_config('idle_in_transaction_session_timeout', ?, true)"

        internal fun prepare(
            entry: PersistencePhysicalEntry,
            epoch: PersistenceProducerEpoch,
            resource: EpochRotationPersistence,
            attempt: PersistenceEpochRotationAttemptV1,
        ): PersistenceEpochRotationSession = PersistenceEpochRotationSession(entry, epoch, resource, attempt)
    }
}

/** Private JDBC reflection stays inside the owner. Native outputs use the existing child/invocation ledger before use. */
private class EpochRotationConnectionCalls(
    private val entry: PersistencePhysicalEntry,
    private val context: PersistenceJdbcGuardContext,
    private val session: PersistenceEpochRotationSession,
) : InvocationHandler {
    val proxy: Connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java), this) as Connection
    private val graph = PhysicalJdbcDescendants(context, proxy)

    @Suppress("TooGenericExceptionCaught")
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        if (method.declaringClass === Any::class.java) {
            return when (method.name) {
                "toString" -> "EpochRotationJdbcConnection(redacted)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                else -> error("Unsupported JDBC object method.")
            }
        }
        check(method.declaringClass === Connection::class.java || method.declaringClass === Wrapper::class.java)
        if (method.name == "unwrap" || method.name == "close" || method.name == "abort") PersistenceJdbcGuardContext.refuse()
        if (method.name == "isWrapperFor") return false
        val call = context.enterRoot(session.connectionKind(method))
        var invoked = false
        var returned = false
        var wrapping = false
        var completed = false
        try {
            try {
                val arguments: Array<Any?> = args?.let { original -> Array(original.size) { original[it] } } ?: emptyArray()
                val adapted = graph.connectionArguments(call, method, arguments)
                context.transaction.beforeConnection(method, adapted, returning = false)
                val raw = entry.raw.get() ?: PersistenceJdbcGuardContext.refuse()
                call.attachDriver(raw)
                call.prepareDriver(raw, method, adapted, PhysicalJdbcInputs.prepare(graph, call.identity, arguments, adapted))
                call.armDriver()
                invoked = true
                val result = method.invoke(raw, *adapted)
                returned = true
                context.transaction.connectionReturned(method, adapted, result)
                call.captureOutput(result)
                wrapping = true
                call.reconcileDriver()
                val guarded = graph.connectionResult(call, method, result)
                call.outputGuarded()
                completed = true
                return guarded
            } finally {
                if (!completed) {
                    if (invoked && !returned) context.transaction.connectionFailed(method)
                    context.phaseJdbcFailure(retireImmediately = true)
                    call.reconcileDriver()
                    call.failedBeforeBoxing(wrapping)
                }
            }
        } catch (problem: InvocationTargetException) {
            val actual = if (problem.javaClass === InvocationTargetException::class.java) problem.targetException else problem
            throw call.failure(actual, wrapping)
        } catch (problem: Throwable) {
            throw call.failure(problem, wrapping)
        } finally {
            call.finish()
        }
    }
}
