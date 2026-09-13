package me.manga.kira.backend.common.infrastructure.persistence

import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.locks.ReentrantLock

/** Dormant composition of the existing ordinary permit budget, not another pool/physical owner. */
internal class PersistencePhaseOwnership(
    private val admission: OrdinaryPersistenceAdmission,
    internal val manager: GuardedJpaTransactionManager,
    internal val otherManager: GuardedJdbcTransactionManager? = null,
    internal val nanoClock: PersistenceNanoClock = SystemPersistenceNanoClock,
) {
    private val admissionCut = ReentrantLock()

    // Exactly the existing bounded permits. Resolved slots are removed, never kept as a history.
    private val phases = AtomicReferenceArray<PersistencePhaseContext?>(admission.ownerLimit)

    init {
        manager.bindPhaseOwner(this)
    }

    // Refusals precede their own side effects; catch every entry failure to settle only unused custody and retain bounded reasons.
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    internal fun enterSourceGrantCleanup(): PersistencePhaseContext {
        requireConnectionFree() // Before even a fail-fast permit attempt, including unbound loans.
        manager.requireResourcePair() // A changed/unprovable EMF/resource pair cannot spend a phase permit.
        val caller = PersistenceOwnedFactoryCaller.capture()
        if (caller.sampleOutsideLocks() != null) {
            caller.restoreAfterFailure()
            throw PersistencePhaseException(PersistencePhaseFailureCode.INTERRUPTED)
        }
        if (!admissionCut.tryLock()) throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
        var permit: LocalPersistencePermit? = null
        var phase: PersistencePhaseContext? = null
        try {
            if ((0 until phases.length()).any { phases.get(it)?.quarantined() == true }) {
                throw PersistencePhaseException(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED)
            }
            val slot = (0 until phases.length()).firstOrNull { phases.get(it) == null }
                ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            permit = admission.trySourceBoundary() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            val prepared = PersistencePhaseContext(this, permit, slot, caller)
            phase = prepared
            check(phases.compareAndSet(slot, null, prepared))
            current.set(prepared) // Custody is already retained by the exact permit slot if publication fails.
            return prepared
        } catch (failure: Throwable) {
            try {
                if (phase == null) check(permit?.releaseAfterQuiescence() != false) else phase.entryPublicationFailed()
            } catch (_: Throwable) {
                throw PersistencePhaseException(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED, cleanupProven = false)
            }
            // Only the genuinely unused entry was cleaned here; preserve an already bounded reason.
            throw failure as? PersistencePhaseException ?: PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
        } finally {
            admissionCut.unlock()
        }
    }

    internal fun forget(phase: PersistencePhaseContext, slot: Int) {
        check(phases.compareAndSet(slot, phase, null)) // A late old completion cannot erase a new permit.
        if (current.get() === phase) current.remove()
    }

    override fun toString(): String = "PersistencePhaseOwnership(redacted)"

    companion object {
        private val current = ThreadLocal<PersistencePhaseContext?>()
        private val loans = ThreadLocal<PersistenceLeaseCompletion?>()

        internal fun current(): PersistencePhaseContext? = current.get()

        internal fun prepareAcquisition(dataSource: GuardedDataSource, acquisition: PoolLifecycle.Acquisition): PersistenceLeaseCompletion {
            val phase = current.get()
            phase?.authorizeAcquisition(dataSource)
            val completion = PersistenceLeaseCompletion.prepare(dataSource, acquisition, phase)
            completion.nextOutstanding = loans.get()
            loans.set(completion) // Retained before acquisition.enter(), Hikari or any lazy-manager callback.
            phase?.retainAcquisition(completion)
            return completion
        }

        /** Prunes only exact ended identities. No resource lookup, counts, close retry or remote callback. */
        internal fun reconcileLoans() {
            var selected = loans.get()
            var previous: PersistenceLeaseCompletion? = null
            while (selected != null) {
                val next = selected.nextOutstanding
                if (selected.quiescent()) {
                    if (previous == null) {
                        if (next == null) loans.remove() else loans.set(next)
                    } else {
                        previous.nextOutstanding = next
                    }
                    selected.nextOutstanding = null
                } else {
                    previous = selected
                }
                selected = next
            }
        }

        internal fun connectionFree() {
            current.get()?.reconcileQuarantine()
            reconcileLoans()
            if (current.get() != null || loans.get() != null || !springConnectionFree()) {
                throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            }
        }

        internal fun springConnectionFree(): Boolean = !TransactionSynchronizationManager.isActualTransactionActive() &&
            !TransactionSynchronizationManager.isSynchronizationActive() && TransactionSynchronizationManager.getResourceMap().isEmpty()
    }
}

/** Necessary gate only; its existence does not certify any unwritten password/network caller. */
internal fun requireConnectionFree() = PersistencePhaseOwnership.connectionFree()

/** Bounded, value-free result. A DB fact is deliberately separate from local cleanup/refund. */
internal class PersistencePhaseException(
    val code: PersistencePhaseFailureCode,
    val databaseOutcome: PersistenceDatabaseOutcome = PersistenceDatabaseOutcome.NONE,
    val cleanupProven: Boolean = true,
) : RuntimeException("Persistence phase refused.", null, false, false)

internal enum class PersistencePhaseFailureCode {
    ENTRY_REFUSED,
    MANAGER_REFUSED,
    RESOURCE_REFUSED,
    TIME_BUDGET_EXHAUSTED,
    INTERRUPTED,
    WORK_FAILED,
    COMPLETION_FAILED,
    CLEANUP_UNRESOLVED,
}

/** Public Spring dispatch is guarded by composition: APTM's final methods cannot be overridden. */
internal class PersistenceManagerDispatch(private val identity: PlatformTransactionManager, private val delegate: PlatformTransactionManager) {
    // Any scoped begin/validation failure belongs to this retained status; unscoped failures are rethrown unchanged.
    @Suppress("TooGenericExceptionCaught")
    fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
        val phase = PersistencePhaseOwnership.current()
        val root = phase?.beforeGetTransaction(identity, definition) == true
        val status = PersistenceManagedStatus(identity, phase, root)
        phase?.prepareStatus(status)
        try {
            status.attach(delegate.getTransaction(definition)) // Retain the returned status before validation/allocation.
            phase?.statusReturned(status)
            return status
        } catch (failure: Throwable) {
            if (phase == null) throw failure
            phase.managerFailure(failure)
            throw phase.failureException(PersistencePhaseFailureCode.COMPLETION_FAILED)
        } finally {
            phase?.getTransactionEnded(status)
        }
    }

    // Retain scoped failure/interrupt state before the dispatch finally; never replace the separately observed DB outcome.
    @Suppress("TooGenericExceptionCaught")
    fun complete(candidate: TransactionStatus, commit: Boolean) {
        val status = candidate as? PersistenceManagedStatus ?: throw PersistencePhaseException(PersistencePhaseFailureCode.MANAGER_REFUSED)
        status.requireOwner(identity)
        val phase = status.phase
        phase?.beforeCompletion(identity, status, commit)
        try {
            if (commit) delegate.commit(status.native()) else delegate.rollback(status.native())
        } catch (failure: Throwable) {
            if (phase == null) throw failure
            phase.managerFailure(failure)
            throw phase.failureException(PersistencePhaseFailureCode.COMPLETION_FAILED)
        } finally {
            phase?.completionDispatchEnded(status)
        }
    }
}

/** A status cannot carry another manager's delegate, escape its original owner, or mint scoped savepoints. */
internal class PersistenceManagedStatus(
    private val manager: PlatformTransactionManager,
    internal val phase: PersistencePhaseContext?,
    internal val root: Boolean,
) : TransactionStatus {
    private val caller = Thread.currentThread()
    private var value: TransactionStatus? = null

    internal fun attach(status: TransactionStatus) {
        check(value == null)
        value = status
    }

    internal fun hasReturnedStatus(): Boolean = value != null
    internal fun native(): TransactionStatus = requireNotNull(value)

    internal fun requireOwner(expected: PlatformTransactionManager = manager) {
        if (expected !== manager || caller !== Thread.currentThread() || phase !== PersistencePhaseOwnership.current()) {
            throw PersistencePhaseException(PersistencePhaseFailureCode.MANAGER_REFUSED)
        }
    }

    override fun isNewTransaction(): Boolean = native().isNewTransaction
    override fun getTransactionName(): String = native().transactionName
    override fun hasTransaction(): Boolean = native().hasTransaction()
    override fun isNested(): Boolean = native().isNested
    override fun isReadOnly(): Boolean = native().isReadOnly
    override fun hasSavepoint(): Boolean = native().hasSavepoint()
    override fun isRollbackOnly(): Boolean = native().isRollbackOnly
    override fun isCompleted(): Boolean = native().isCompleted

    override fun setRollbackOnly() {
        requireOwner()
        phase?.requireParticipation()
        native().setRollbackOnly()
    }

    override fun flush() {
        requireOwner()
        phase?.requireParticipation()
        native().flush()
    }

    override fun createSavepoint(): Any {
        requireUnscopedSavepoint()
        return native().createSavepoint()
    }

    override fun rollbackToSavepoint(savepoint: Any) {
        requireUnscopedSavepoint()
        native().rollbackToSavepoint(savepoint)
    }

    override fun releaseSavepoint(savepoint: Any) {
        requireUnscopedSavepoint()
        native().releaseSavepoint(savepoint)
    }

    private fun requireUnscopedSavepoint() {
        requireOwner()
        if (phase != null) throw phase.failureException(PersistencePhaseFailureCode.MANAGER_REFUSED)
    }

    override fun toString(): String = "PersistenceManagedStatus(redacted)"
}
