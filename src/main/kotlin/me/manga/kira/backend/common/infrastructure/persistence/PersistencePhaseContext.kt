package me.manga.kira.backend.common.infrastructure.persistence

import jakarta.persistence.EntityManager
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.lang.reflect.Method
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * One synchronous named source-cleanup owner. No application lambda, foreign thread or resource switch.
 * All guarded transitions share this retained phase/permit/lease rather than splitting their custody.
 */
@Suppress("TooManyFunctions")
internal class PersistencePhaseContext(
    private val ownership: PersistencePhaseOwnership,
    private val permit: LocalPersistencePermit,
    private val slot: Int,
    private val caller: PersistenceOwnedFactoryCaller,
) {
    private val manager = ownership.manager
    private val failure = AtomicReference<PersistencePhaseFailureCode?>()
    private val refunded = AtomicBoolean()

    @Volatile private var stage = Stage.PREPARED

    @Volatile private var work: PersistenceTimeBudget? = null

    @Volatile private var emergency: PersistenceTimeBudget? = null
    private var acquisition: PersistenceLeaseCompletion? = null
    private var lease: PersistenceJdbcLease? = null
    private var connection: LeaseJdbcFacade? = null
    private var holder: ConnectionHolder? = null
    private var entityHolder: EntityManagerHolder? = null
    private var createdEntityManager: EntityManager? = null
    private var rootStatus: PersistenceManagedStatus? = null
    private var beginDispatched = false
    private var beginEnded = false
    private var acquisitionAuthority = false
    private var acquisitionSpent = false
    private var completionActive = false
    private var completionEnded = false
    private var springSettled = false
    private var sourceOperationIssued = false
    private var changingReadCap = false
    private var readCapKind: PersistenceJdbcGuardCallKind? = null
    private var restoringReadCap = false
    private var originalReadCap: Int? = null
    private var restoreInterrupt = false
    private var finalizerEnded = false

    internal fun begin() {
        requireCaller()
        if (stage !== Stage.PREPARED) refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
        stage = Stage.STARTING
        val definition = DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRED).apply {
            setName("SOURCE_GRANT_CLEANUP")
            timeout = 2
        }
        manager.getTransaction(definition)
        // The wrapper retained TransactionStatus before this validation. A failure here still has rollback custody.
        val status = rootStatus ?: refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
        if (!status.hasReturnedStatus() || !status.isNewTransaction || !beginEnded) refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive()) {
            refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        holder = TransactionSynchronizationManager.getResource(manager.dataSource) as? ConnectionHolder
            ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        entityHolder = TransactionSynchronizationManager.getResource(manager.entityManagerFactory) as? EntityManagerHolder
            ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        // Do not use DataSourceUtils or bind a replacement. JPA's existing handle may acquire lazily here.
        val selected = requireNotNull(holder).connection
        val accepted = lease ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        if (selected !is LeaseJdbcFacade || !selected.matches(accepted) || acquisition?.matches(accepted) != true) {
            refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        connection = selected
        acquisitionAuthority = false // Spent is never reset, even if Hibernate later releases its handle.
        requireWork()
        stage = Stage.SETTING_UP
        installLimits()
        requireWork()
        stage = Stage.WORK
    }

    internal fun beforeGetTransaction(candidate: PlatformTransactionManager, definition: TransactionDefinition?): Boolean {
        requireCaller()
        if (candidate !== manager || definition?.propagationBehavior?.let { it != TransactionDefinition.PROPAGATION_REQUIRED } == true) {
            refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
        }
        if (stage === Stage.STARTING && !beginDispatched) {
            beginDispatched = true
            acquisitionAuthority = true
            return true
        }
        requireParticipation() // Before even Spring's synchronization-suspension callbacks.
        return false
    }

    internal fun prepareStatus(status: PersistenceManagedStatus) {
        requireCaller()
        if (status.root) {
            if (rootStatus != null) refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
            rootStatus = status
        }
    }

    internal fun retainEntityManager(candidate: GuardedJpaTransactionManager, entityManager: EntityManager) {
        requireCaller()
        check(candidate === manager && stage === Stage.STARTING && createdEntityManager == null)
        createdEntityManager = entityManager // Before the real dialect/transaction begin, including the no-status path.
    }

    internal fun statusReturned(status: PersistenceManagedStatus) {
        requireCaller()
        if (status.root) {
            if (rootStatus !== status) refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
        } else if (status.isNewTransaction || status.hasSavepoint()) {
            refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
        }
    }

    internal fun getTransactionEnded(status: PersistenceManagedStatus) {
        if (status === rootStatus) beginEnded = true
    }

    // Selected resource/start stage and unspent single-borrow authority are one pre-checkout gate.
    @Suppress("ComplexCondition")
    internal fun authorizeAcquisition(dataSource: GuardedDataSource) {
        requireCaller()
        if (dataSource !== manager.dataSource || stage !== Stage.STARTING || !acquisitionAuthority || acquisitionSpent) {
            refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        acquisitionSpent = true // Consumed before either DataSource overload reaches Hikari.
    }

    internal fun retainAcquisition(completion: PersistenceLeaseCompletion) {
        requireCaller()
        check(acquisition == null && completion.phase === this)
        acquisition = completion
    }

    internal fun bindLease(completion: PersistenceLeaseCompletion, prepared: PersistenceJdbcLease) {
        requireCaller()
        check(acquisition === completion && lease == null)
        lease = prepared
        prepared.state.epoch.attachPhase(this)
        prepared.state.context.attachPhase(this)
    }

    internal fun bindFacade(prepared: PersistenceJdbcLease, facade: LeaseJdbcFacade) {
        requireCaller()
        check(lease === prepared && connection == null)
        connection = facade
    }

    internal fun leaseAccepted(completion: PersistenceLeaseCompletion) {
        requireCaller()
        check(acquisition === completion && work == null)
        // Immediately after the real CHECKOUT consent, before its old tail and all remaining JPA begin work.
        work = PersistenceTimeBudget.start(WORK_MILLIS, ownership.nanoClock)
    }

    internal fun requireAcceptedLease() = requireWork()

    internal fun requireParticipation() {
        requireCaller()
        if (stage !== Stage.WORK || !beginEnded || completionActive) refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
        requireWork()
        requireSelectedHolder()
    }

    internal fun requireSourceCleanup(jdbc: JdbcTemplate) {
        requireParticipation()
        if (jdbc.dataSource !== manager.dataSource || sourceOperationIssued) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        sourceOperationIssued = true // One fixed batch, not repeated cleanup until exhaustion.
        installLimits()
        requireWork()
    }

    // Short-circuit Spring-state/holder identity checks before consulting the retained holder's connection.
    @Suppress("ComplexCondition")
    private fun requireSelectedHolder() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isSynchronizationActive() ||
            TransactionSynchronizationManager.getResource(manager.dataSource) !== holder ||
            TransactionSynchronizationManager.getResource(manager.entityManagerFactory) !== entityHolder ||
            holder == null || entityHolder == null || holder?.connection !== connection
        ) {
            refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if (entityHolder?.entityManager !== createdEntityManager) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        val selected = lease ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        if (connection?.matches(selected) != true || acquisition?.matches(selected) != true) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        selected.requireBusiness()
    }

    internal fun checkWorkReturned(count: Int) {
        requireParticipation()
        if (!sourceOperationIssued || count !in 0..50) refuse(PersistencePhaseFailureCode.WORK_FAILED)
    }

    internal fun commit() {
        requireParticipation()
        stage = Stage.COMMITTING
        manager.commit(requireNotNull(rootStatus))
        if (databaseOutcome() !== PersistenceDatabaseOutcome.COMMITTED) refuse(PersistencePhaseFailureCode.COMPLETION_FAILED)
        requireWork()
    }

    // Root completion must match its commit/rollback stage with no overlapping completion dispatch.
    @Suppress("ComplexCondition")
    internal fun beforeCompletion(candidate: PlatformTransactionManager, status: PersistenceManagedStatus, commit: Boolean) {
        requireCaller()
        if (candidate !== manager || !status.hasReturnedStatus()) refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
        if (status !== rootStatus) {
            requireParticipation()
            return
        }
        if (completionActive || (commit && stage !== Stage.COMMITTING) || (!commit && stage !== Stage.ROLLING_BACK)) {
            refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
        }
        if (commit) {
            requireWork()
            requireSelectedHolder()
        } else {
            cleanupBudget()
        }
        completionActive = true
    }

    internal fun completionDispatchEnded(status: PersistenceManagedStatus) {
        if (status === rootStatus) {
            completionActive = false
            completionEnded = true
        }
    }

    internal fun managerFailure(problem: Throwable) = recordFailure(problem)

    internal fun recordFailure(problem: Throwable) {
        val reason = when (problem) {
            is InterruptedException -> {
                restoreInterrupt = true
                PersistencePhaseFailureCode.INTERRUPTED
            }

            is PersistencePhaseException -> problem.code

            else -> PersistencePhaseFailureCode.WORK_FAILED
        }
        failure.compareAndSet(null, reason) // Preserve our refusal code; DB/cleanup facts still come from this exact owner.
    }

    internal fun jdbcFailure() {
        failure.compareAndSet(null, PersistencePhaseFailureCode.WORK_FAILED)
    }

    /** Lower/upper dispatch uses this budget; no business worker or copied Spring context exists. */
    internal fun callBudget(kind: PersistenceJdbcGuardCallKind): PersistenceTimeBudget {
        if (kind === PersistenceJdbcGuardCallKind.CANCELLATION) {
            return emergency ?: work ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        }
        if (kind === PersistenceJdbcGuardCallKind.BUSINESS) {
            requireWork()
            if (stage !in BUSINESS_STAGES || databaseOutcome() !== PersistenceDatabaseOutcome.NONE) {
                refuse(PersistencePhaseFailureCode.COMPLETION_FAILED)
            }
            return requireNotNull(work)
        }
        return cleanupBudget()
    }

    // Keep the closed JDBC capability table and exact rollback-stage conjunction together; no fallback grants cleanup authority.
    @Suppress("CyclomaticComplexMethod", "ComplexCondition")
    internal fun connectionKind(method: Method, arguments: Array<out Any?>?): PersistenceJdbcGuardCallKind {
        requireCaller()
        if (changingReadCap && method.name in READ_CAP_METHODS) return requireNotNull(readCapKind)
        val completion = stage === Stage.ROLLING_BACK || stage === Stage.FINALIZING || stage === Stage.COMMITTING ||
            (stage === Stage.STARTING && databaseOutcome() in KNOWN_OUTCOMES)
        return when (method.name) {
            "commit" -> {
                if (stage !== Stage.COMMITTING || !completionActive) refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
                PersistenceJdbcGuardCallKind.BUSINESS
            }

            "rollback" -> {
                if (arguments?.isNotEmpty() == true ||
                    !((completionActive && stage in setOf(Stage.COMMITTING, Stage.ROLLING_BACK)) || (stage === Stage.STARTING && !beginEnded))
                ) {
                    refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
                }
                PersistenceJdbcGuardCallKind.CLEANUP
            }

            "setSavepoint", "releaseSavepoint" -> refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)

            "setNetworkTimeout" -> {
                if (!changingReadCap && !restoringReadCap) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                if (completion) PersistenceJdbcGuardCallKind.CLEANUP else PersistenceJdbcGuardCallKind.BUSINESS
            }

            "setAutoCommit" -> {
                if (arguments?.singleOrNull() == false) {
                    if (stage !== Stage.STARTING || !acquisitionAuthority) refuse(PersistencePhaseFailureCode.MANAGER_REFUSED)
                    PersistenceJdbcGuardCallKind.BUSINESS
                } else {
                    if (!completion || databaseOutcome() !in KNOWN_OUTCOMES) refuse(PersistencePhaseFailureCode.COMPLETION_FAILED)
                    PersistenceJdbcGuardCallKind.CLEANUP
                }
            }

            in COMPLETION_LOCAL_METHODS -> if (completion) PersistenceJdbcGuardCallKind.CLEANUP else PersistenceJdbcGuardCallKind.BUSINESS

            else -> PersistenceJdbcGuardCallKind.BUSINESS
        }
    }

    /** Reclips the actual JDBC read cap before every upper call, without recursion or a new time budget. */
    internal fun beforeJdbcCall(kind: PersistenceJdbcGuardCallKind) {
        if (kind === PersistenceJdbcGuardCallKind.CANCELLATION || changingReadCap || restoringReadCap) return
        val budget = callBudget(kind)
        val selected = connection ?: return
        readCapKind = kind
        changingReadCap = true
        try {
            if (originalReadCap == null) originalReadCap = selected.networkTimeout
            val ceiling = if (budget === emergency) EMERGENCY_READ_MILLIS else NORMAL_READ_MILLIS
            selected.setNetworkTimeout(INLINE, budget.remainingMillis(ceiling).toInt())
        } finally {
            changingReadCap = false
            readCapKind = null
        }
    }

    internal fun afterJdbcCall(kind: PersistenceJdbcGuardCallKind) {
        if (kind === PersistenceJdbcGuardCallKind.BUSINESS) requireWork()
    }

    private fun installLimits() {
        requireWork()
        val selected = connection ?: refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        selected.prepareStatement(LOCAL_LIMITS).use { statement ->
            statement.setString(1, requireNotNull(work).remainingMillis(WORK_MILLIS).toString() + "ms")
            statement.setString(2, requireNotNull(work).remainingMillis(1_000).toString() + "ms")
            statement.setString(3, requireNotNull(work).remainingMillis(1_000).toString() + "ms")
            statement.setString(4, requireNotNull(work).remainingMillis(100).toString() + "ms")
            statement.executeQuery().use { result ->
                if (!result.next() || result.next()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
        }
        requireWork()
    }

    internal fun logicalRelease(completion: PersistenceLeaseCompletion) {
        requireCaller()
        if (completion !== acquisition) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        if (stage === Stage.WORK || stage === Stage.SETTING_UP) failure.compareAndSet(null, PersistencePhaseFailureCode.COMPLETION_FAILED)
    }

    internal fun requireFinalizer(candidate: PersistenceJdbcLease) {
        requireCaller()
        if (stage !== Stage.FINALIZING || candidate !== lease || !springSettled) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }

    /**
     * Caller finally owns rollback, restoration, the real return, and proof observation in that order.
     * Broad catches retain bounded failure/interrupt state through cleanup; nested restoration always resets its read-cap flag.
     */
    @Suppress("TooGenericExceptionCaught", "NestedBlockDepth", "ComplexCondition")
    internal fun finish() {
        requireCaller()
        acquisitionAuthority = false
        try {
            val status = rootStatus
            // Roll back only a returned, incomplete status after begin ends and outside any completion dispatch.
            if (status?.hasReturnedStatus() == true && !status.isCompleted && beginEnded && !completionActive) {
                stage = Stage.ROLLING_BACK
                try {
                    manager.rollback(status)
                } catch (problem: Throwable) {
                    recordFailure(problem)
                }
            }
            springSettled = springCompletionProven()
            stage = Stage.FINALIZING
            val selected = lease
            if (selected != null) {
                if (springSettled && databaseOutcome() in KNOWN_OUTCOMES) {
                    try {
                        // Manager reset may have swallowed failure; lower poison/transaction evidence still gates return.
                        restoringReadCap = true
                        try {
                            originalReadCap?.let { requireNotNull(connection).setNetworkTimeout(INLINE, it) }
                        } finally {
                            restoringReadCap = false
                        }
                        selected.finishScoped(this, cleanupBudget())
                    } catch (problem: Throwable) {
                        recordFailure(problem)
                        selected.retireScoped(this)
                    }
                } else {
                    selected.retireScoped(this)
                }
            }
            if (acquisition?.quiescent() == false) awaitCleanup()
            deadlineExpired() // A successful but late return is still an overrun, not an on-time commit.
        } catch (problem: Throwable) {
            recordFailure(problem)
            lease?.retireScoped(this)
        } finally {
            try {
                caller.restoreAfterFailure()
                if (restoreInterrupt) Thread.currentThread().interrupt()
            } catch (_: Throwable) {
                // Discard raw restoration details, but retain unresolved custody instead of claiming settlement/refund.
                failure.set(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED)
                springSettled = false
            }
            finalizerEnded = true
            if (!releaseIfProven()) {
                failure.set(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED)
                stage = Stage.QUARANTINED // Retain exact permit/holder/lease; this is an incident, not a lifetime PASS.
            }
        }
    }

    // Empty Spring state alone cannot settle an unfinished dispatch or a no-status begin without exact EM custody.
    @Suppress("ComplexCondition")
    private fun springCompletionProven(): Boolean {
        if (!PersistencePhaseOwnership.springConnectionFree() || completionActive || (beginDispatched && !beginEnded)) return false
        val status = rootStatus
        if (status?.hasReturnedStatus() == true && (!status.isCompleted || !completionEnded)) return false
        // The private delegate retained the exact created EM before doBegin. Only actual close,
        // ended dispatch and empty Spring state settle a no-status begin; no rollback is invented.
        if (status != null && !status.hasReturnedStatus() && acquisition != null && createdEntityManager == null) return false
        return createdEntityManager?.isOpen != true && entityHolder?.entityManager?.isOpen != true
    }

    private fun awaitCleanup() {
        val budget = emergencyBudget() // One measured allowance for all failed return/terminal observation, never per close.
        while (acquisition?.quiescent() == false && persistenceFactoryRemainingMillis(budget) > 0L) {
            if (Thread.interrupted()) restoreInterrupt = true // Cleanup only; never restores business authority.
            LockSupport.parkNanos(1_000_000)
        }
    }

    internal fun reconcileQuarantine() {
        requireCaller()
        if (stage !== Stage.QUARANTINED || !finalizerEnded) return
        springSettled = springCompletionProven()
        releaseIfProven()
    }

    private fun releaseIfProven(): Boolean {
        if (!finalizerEnded || !springSettled || acquisition?.quiescent() == false) return false
        if (refunded.compareAndSet(false, true)) {
            ownership.forget(this, slot)
            check(permit.releaseAfterQuiescence())
            PersistencePhaseOwnership.reconcileLoans()
            stage = Stage.CLOSED
        }
        return true
    }

    internal fun entryPublicationFailed() {
        // No manager/checkout was entered. This is genuinely unused, not an invented lease receipt.
        springSettled = true
        finalizerEnded = true
        releaseIfProven()
    }

    internal fun quarantined(): Boolean = stage === Stage.QUARANTINED

    internal fun result(count: Int): Int {
        val reason = failure.get()
        if (reason != null || !refunded.get() || databaseOutcome() !== PersistenceDatabaseOutcome.COMMITTED) {
            throw failureException(reason ?: PersistencePhaseFailureCode.COMPLETION_FAILED)
        }
        return count
    }

    internal fun failureException(default: PersistencePhaseFailureCode): PersistencePhaseException =
        PersistencePhaseException(failure.get() ?: default, databaseOutcome(), refunded.get())

    internal fun databaseOutcome(): PersistenceDatabaseOutcome = acquisition?.databaseOutcome() ?: PersistenceDatabaseOutcome.NONE

    /** Called outside F/G/T by the existing scanner; a later exact-epoch cut performs the retirement. */
    internal fun deadlineExpired(): Boolean {
        val selected = work ?: return false
        val expired = persistenceFactoryRemainingMillis(selected) == 0L
        if (expired) failure.compareAndSet(null, PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
        return expired
    }

    private fun requireWork() {
        requireCaller()
        if (caller.sampleOutsideLocks() != null) {
            failure.compareAndSet(null, PersistencePhaseFailureCode.INTERRUPTED)
            refuse(PersistencePhaseFailureCode.INTERRUPTED)
        }
        if (work == null || deadlineExpired()) refuse(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
        if (failure.get() != null) refuse(failure.get()!!)
    }

    internal fun cleanupBudget(): PersistenceTimeBudget {
        requireCaller()
        emergency?.let { return it }
        val normal = work
        return if (normal != null && persistenceFactoryRemainingMillis(normal) > 0L) normal else emergencyBudget()
    }

    private fun emergencyBudget(): PersistenceTimeBudget {
        emergency?.let { return it }
        requireCaller()
        return PersistenceTimeBudget.start(EMERGENCY_MILLIS, ownership.nanoClock).also { emergency = it }
    }

    private fun requireCaller() {
        if (!caller.isCurrent() || PersistencePhaseOwnership.current() !== this) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
    }

    private fun refuse(code: PersistencePhaseFailureCode): Nothing = throw failureException(code)

    override fun toString(): String = "PersistencePhaseContext(SOURCE_GRANT_CLEANUP)"

    private enum class Stage { PREPARED, STARTING, SETTING_UP, WORK, COMMITTING, ROLLING_BACK, FINALIZING, QUARANTINED, CLOSED }

    private companion object {
        const val WORK_MILLIS = 2_000L
        const val EMERGENCY_MILLIS = 1_000L
        const val NORMAL_READ_MILLIS = 1_000L
        const val EMERGENCY_READ_MILLIS = 250L
        val INLINE = Executor { command -> command.run() }
        val BUSINESS_STAGES = setOf(Stage.STARTING, Stage.SETTING_UP, Stage.WORK, Stage.COMMITTING)
        val KNOWN_OUTCOMES = setOf(PersistenceDatabaseOutcome.COMMITTED, PersistenceDatabaseOutcome.ROLLED_BACK)
        val READ_CAP_METHODS = setOf("getNetworkTimeout", "setNetworkTimeout")
        val COMPLETION_LOCAL_METHODS = setOf(
            "getAutoCommit", "getNetworkTimeout", "setNetworkTimeout", "isClosed", "getWarnings", "clearWarnings",
            "getTransactionIsolation", "setTransactionIsolation", "isReadOnly", "setReadOnly", "getHoldability", "setHoldability",
            "getCatalog", "setCatalog", "getSchema", "setSchema", "getTypeMap", "setTypeMap",
        )
        const val LOCAL_LIMITS = "SELECT set_config('transaction_timeout', ?, true), set_config('statement_timeout', ?, true), " +
            "set_config('idle_in_transaction_session_timeout', ?, true), set_config('lock_timeout', ?, true)"
    }
}

/** Exact phase/path/one-operation/resource/holder/lease guard, before the fixed store's SQL. */
internal fun requireSourceGrantCleanup(jdbc: JdbcTemplate) {
    val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
    phase.requireSourceCleanup(jdbc)
}
