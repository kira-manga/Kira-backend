package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One pinned borrower. Its retained Hikari handle never leaves this private composition. */
internal class PersistenceJdbcLease private constructor(
    private val owner: GuardedDataSource,
    private val lower: PhysicalJdbcFacade,
    private val ownership: PersistenceOwnership,
    internal val state: PersistenceJdbcPoolEpoch,
    private val handle: Connection,
    private val entitlement: PoolLifecycle.LeaseEntitlement,
    internal val completion: PersistenceLeaseCompletion,
) {
    private val original = Thread.currentThread()
    internal val cleanup = state.epoch.preparedCleanup()
    internal val identity = PersistenceJdbcGuardIdentity.prepare(state.context, state.epoch, cleanup)
    private val phase = AtomicReference(Phase.OPEN)
    private val evictionClaimed = AtomicBoolean()
    private var transfer: PersistenceJdbcPoolTransfer? = null
    private var returnCaller: PersistenceOwnedFactoryCaller? = null

    init {
        state.retainLease(this)
    }

    /** Failed acquisition bookkeeping cannot expose this committed but inaccessible borrower. */
    internal fun deliveryFailed() {
        check(Thread.currentThread() === original)
        if (phase.compareAndSet(Phase.OPEN, Phase.CLOSED)) {
            ownership.requestRetirement(state.epoch)
            state.epoch.sealForTerminal()
        }
    }

    internal fun requireBusiness(cancellation: Boolean = false) {
        if (completion.phase !== PersistencePhaseOwnership.current() && !cancellation) PersistenceJdbcGuardContext.refuse()
        if (completion.logicallyReleased()) PersistenceJdbcGuardContext.refuse()
        if (phase.get() !== Phase.OPEN || !ownership.currentPoolState(state) || !ownership.permits(state.epoch)) PersistenceJdbcGuardContext.refuse()
        if (!cancellation && Thread.currentThread() !== original) PersistenceJdbcGuardContext.refuse()
        if (!owner.businessReady()) PersistenceJdbcGuardContext.refuse()
    }

    internal fun select(
        expected: PhysicalJdbcFacade,
        expectedOwner: PersistenceOwnership,
        returning: Boolean,
        kind: PersistenceJdbcGuardCallKind,
    ): PersistenceJdbcPoolEpoch {
        if (lower !== expected || ownership !== expectedOwner || !ownership.currentPoolState(state)) PersistenceJdbcGuardContext.refuse()
        if (Thread.currentThread() !== original) PersistenceJdbcGuardContext.refuse()
        if (returning) {
            if (phase.get() !== Phase.RETURNING || transfer?.consented() == true) PersistenceJdbcGuardContext.refuse()
        } else {
            requireDispatch(kind)
        }
        return state
    }

    internal fun enterDispatch(kind: PersistenceJdbcGuardCallKind = PersistenceJdbcGuardCallKind.BUSINESS): PersistenceJdbcDispatch.Frame {
        requireDispatch(kind)
        return PersistenceJdbcDispatch.enter(this, returning = false, kind = kind)
    }

    private fun requireDispatch(kind: PersistenceJdbcGuardCallKind) {
        if (kind === PersistenceJdbcGuardCallKind.BUSINESS || completion.phase == null) {
            requireBusiness(kind === PersistenceJdbcGuardCallKind.CANCELLATION)
        } else {
            if (phase.get() !== Phase.OPEN || !ownership.currentPoolState(state) || !ownership.permitsCleanup(state.epoch)) {
                PersistenceJdbcGuardContext.refuse()
            }
            if (kind !== PersistenceJdbcGuardCallKind.CANCELLATION &&
                (Thread.currentThread() !== original || completion.phase !== PersistencePhaseOwnership.current())
            ) {
                PersistenceJdbcGuardContext.refuse()
            }
        }
    }

    internal fun closed(): Boolean = phase.get() !== Phase.OPEN || completion.logicallyReleased() || ownership.retirementRequested()

    internal fun abort(executor: Executor) {
        if (closed()) return
        requireBusiness(cancellation = true)
        val call = state.context.enter(identity, PersistenceJdbcGuardCallKind.CANCELLATION)
        try {
            state.context.dispatchAbort(call, executor)
        } finally {
            call.finish()
        }
    }

    /** One real close; duplicate close is always facade-only, even on the post-consent tail. */
    @Suppress("TooGenericExceptionCaught")
    internal fun close() {
        if (phase.get() !== Phase.OPEN) return
        if (Thread.currentThread() !== original) PersistenceJdbcGuardContext.refuse()
        if (completion.phase != null) {
            completion.logicalRelease() // Spring release is not Hikari/native return or permit proof.
            return
        }
        closeActual(PersistenceTimeBudget.start(1_000))
    }

    internal fun finishScoped(scope: PersistencePhaseContext, budget: PersistenceTimeBudget) {
        if (completion.phase !== scope) PersistenceJdbcGuardContext.refuse()
        scope.requireFinalizer(this)
        if (!completion.logicallyReleased()) PersistenceJdbcGuardContext.refuse()
        closeActual(budget)
    }

    internal fun retireScoped(scope: PersistencePhaseContext) {
        if (completion.phase !== scope || Thread.currentThread() !== original) PersistenceJdbcGuardContext.refuse()
        // In particular, a consented old return tail has no authority over its successor.
        if (phase.get() !== Phase.OPEN || transfer?.consented() == true) return
        try {
            closeActual(scope.cleanupBudget(), retire = true)
        } catch (_: Throwable) {
            scope.jdbcFailure()
            // The original operation/entitlement and terminal receipt, not this catch, decide completion.
        }
    }

    // Every reuse veto stays inside the original return attempt, before Hikari close and its retained finally tail.
    @Suppress("TooGenericExceptionCaught", "ComplexCondition")
    private fun closeActual(budget: PersistenceTimeBudget, retire: Boolean = false) {
        if (phase.get() !== Phase.OPEN) return
        if (Thread.currentThread() !== original) PersistenceJdbcGuardContext.refuse()
        if (ownership.ownershipLockHeld()) PersistenceJdbcGuardContext.refuse()
        val operation = enterReturn(budget)
        var dispatch: PersistenceJdbcDispatch.Frame? = null
        var failure: Throwable? = null
        var bookkeepingFailure: SQLException? = null
        try {
            check(phase.compareAndSet(Phase.OPEN, Phase.RETURNING))
            val attempt = PersistenceJdbcPoolTransfer.prepare(ownership, state, PersistenceJdbcPoolTransfer.Kind.RETURN, budget, requireNotNull(returnCaller))
            transfer = attempt
            completion.retainTransfer(attempt)
            if (!attempt.claim()) PersistenceJdbcGuardContext.refuse()
            if (!state.epoch.stopBusiness(cleanup)) PersistenceJdbcGuardContext.refuse()
            if (retire || state.epoch.poisoned() || state.context.graphFailed() || state.context.transaction.uncertain()) {
                attempt.reject()
                PersistenceJdbcGuardContext.refuse()
            }
            val frame = PersistenceJdbcDispatch.enter(this, returning = true)
            dispatch = frame
            completion.retainDispatch(frame)
            handle.close() // No epoch foreground ancestor around this complete Hikari/return extent.
            if (!attempt.consented()) PersistenceJdbcGuardContext.refuse()
        } catch (problem: Throwable) {
            if (transfer?.consented() == true || transfer?.callerSamplingFailed() == true) operation.recordReturnIncidentBeforeEnd()
            failure = problem
        } finally {
            try {
                val attempt = transfer
                if (attempt?.consented() != true) {
                    // This original-thread, unconsented return still owns the only possible
                    // transfer. Retire even when its original allowance has already expired.
                    ownership.requestRetirement(state.epoch)
                    state.epoch.sealForTerminal()
                    if (attempt != null && !attempt.actualEnded()) attempt.reject()
                    failure = evictReturningLease(operation, budget, failure)
                }
            } finally {
                try {
                    // No overriding self-interrupt precedes the authoritative source retirement
                    // (or consented-tail actor failure). Retain this enclosing RETURN throughout
                    // restoration, using the SAME C5 caller that may have consumed the real flag.
                    check(!ownership.ownershipLockHeld())
                    // Prepare a detached fallback while RETURN is still retained. A later adapter
                    // or end failure must never send an already-restored InterruptedException
                    // through another interrupting adapter, especially after the actor has ended.
                    bookkeepingFailure = state.context.adaptFailure(IllegalStateException("Persistence return bookkeeping refused."))
                    failure = restoreReturnFailure(operation, failure)
                } catch (problem: Error) {
                    operation.failBeforeEnd()
                    failure = problem
                } catch (problem: Throwable) {
                    operation.failBeforeEnd()
                    failure = bookkeepingFailure ?: problem
                } finally {
                    failure = endReturnTail(operation, dispatch, failure, bookkeepingFailure)
                    PersistencePhaseOwnership.reconcileLoans()
                }
            }
        }
        failure?.let { throw it } // Already adapted, or an adapter/Error outcome; never another callback.
    }

    private fun enterReturn(budget: PersistenceTimeBudget): PoolLifecycle.Operation {
        var entered = false
        return try {
            returnCaller = PersistenceOwnedFactoryCaller.capture() // Metadata only, outside F/G/T.
            val prepared = entitlement.prepareReturn(budget) ?: PersistenceJdbcGuardContext.refuse()
            completion.retainReturn(prepared)
            if (!prepared.enter()) PersistenceJdbcGuardContext.refuse()
            entered = true
            prepared
        } finally {
            if (!entered) {
                try {
                    deliveryFailed()
                } finally {
                    // Only AVAILABLE can be revoked. A partially admitted authentic frame keeps
                    // its consumed right/count; never invent its end or issue a replacement budget.
                    entitlement.revoke()
                }
            }
        }
    }

    private fun evictReturningLease(operation: PoolLifecycle.Operation, budget: PersistenceTimeBudget, originalFailure: Throwable?): Throwable? {
        var failure = originalFailure
        runCatching {
            // Exact source ownership is claimed before the private Hikari eviction.
            // This is still inside the consumed RETURN frame, never a second ingress.
            val retirement = owner.evictOwned(this, handle, budget)
            if (retirement is PersistenceLeaseRetirementClaim.CallerSampleFailed) {
                operation.recordReturnIncidentBeforeEnd()
                if (failure == null) failure = retirement.failure
            }
        }.onFailure { problem ->
            operation.failBeforeEnd()
            if (failure == null) failure = problem
        }
        return failure
    }

    private fun restoreReturnFailure(operation: PoolLifecycle.Operation, originalFailure: Throwable?): Throwable? {
        if (originalFailure !is InterruptedException) {
            runCatching {
                requireNotNull(returnCaller).restoreAfterFailure()
            }.onFailure { problem ->
                operation.failBeforeEnd()
                if (originalFailure == null) throw problem
            }
        }
        // For an original InterruptedException this adapter IS the sole restoration.
        // Both its callback and its own failure stay inside this genuine RETURN.
        return originalFailure?.let { state.context.adaptFailure(it) }
    }

    private fun endReturnTail(
        operation: PoolLifecycle.Operation,
        dispatch: PersistenceJdbcDispatch.Frame?,
        originalFailure: Throwable?,
        bookkeepingFailure: SQLException?,
    ): Throwable? {
        var failure = originalFailure
        var holderEnded = transfer?.actualEnded() != false
        runCatching {
            val attempt = transfer
            if (attempt != null && !attempt.actualEnded()) {
                attempt.end()
                holderEnded = true
            }
        }.onFailure { problem ->
            operation.failBeforeEnd()
            if (failure == null) {
                failure = when (problem) {
                    is Error -> problem
                    else -> bookkeepingFailure ?: problem
                }
            }
        }
        var dispatchEnded = dispatch == null
        try {
            runCatching {
                dispatch?.end()
                dispatchEnded = true
            }.onFailure { problem ->
                operation.failBeforeEnd()
                if (failure == null) {
                    failure = when (problem) {
                        is Error -> problem
                        else -> bookkeepingFailure ?: problem
                    }
                }
            }
        } finally {
            phase.set(Phase.CLOSED)
            // Never publish an ended actor frame while its holder/TL tail is unresolved.
            // A consented old tail records only actor uncertainty, not successor eviction.
            if (holderEnded && dispatchEnded) {
                failure = endReturnedFrame(operation, failure, bookkeepingFailure)
            } else {
                operation.failBeforeEnd()
            }
        }
        return failure
    }

    private fun endReturnedFrame(operation: PoolLifecycle.Operation, originalFailure: Throwable?, bookkeepingFailure: SQLException?): Throwable? {
        var failure = originalFailure
        runCatching {
            if (!operation.end() || !operation.actualFrameEnded()) {
                operation.failBeforeEnd()
                if (failure == null) failure = requireNotNull(bookkeepingFailure)
            }
        }.onFailure { problem ->
            if (failure == null) {
                failure = when (problem) {
                    is Error -> problem
                    else -> bookkeepingFailure ?: problem
                }
            }
        }
        return failure
    }

    internal fun returnTransfer(expected: PhysicalJdbcFacade): PersistenceJdbcPoolTransfer? = transfer?.takeIf {
        lower === expected && phase.get() === Phase.RETURNING && Thread.currentThread() === original &&
            !it.actualEnded() && ownership.ownsTransfer(it)
    }

    internal fun afterClearWarnings(expected: PhysicalJdbcFacade, call: PersistenceJdbcGuardCall) {
        if (returnTransfer(expected) == null) PersistenceJdbcGuardContext.refuse()
        lower.finishReturn(this, call)
    }

    internal fun claimEviction(expectedOwner: GuardedDataSource, candidate: Connection, budget: PersistenceTimeBudget): PersistenceLeaseRetirementClaim {
        if (owner !== expectedOwner || handle !== candidate || Thread.currentThread() !== original) return PersistenceLeaseRetirementClaim.Refused
        if (phase.get() !== Phase.RETURNING || transfer?.consented() == true || !ownership.currentPoolState(state)) {
            return PersistenceLeaseRetirementClaim.Refused
        }
        if (!evictionClaimed.compareAndSet(false, true)) return PersistenceLeaseRetirementClaim.Refused
        return ownership.retireLeasedState(state, budget, requireNotNull(returnCaller))
    }

    override fun toString(): String = "PersistenceJdbcLease(redacted)"

    private enum class Phase { OPEN, RETURNING, CLOSED }

    companion object {
        internal fun prepare(
            owner: GuardedDataSource,
            lower: PhysicalJdbcFacade,
            ownership: PersistenceOwnership,
            state: PersistenceJdbcPoolEpoch,
            handle: Connection,
            entitlement: PoolLifecycle.LeaseEntitlement,
            completion: PersistenceLeaseCompletion,
        ): PersistenceJdbcLease = PersistenceJdbcLease(owner, lower, ownership, state, handle, entitlement, completion)
    }
}

/** Positive, exact original-thread/epoch dispatch. Absence is never authority for composed lower calls. */
internal object PersistenceJdbcDispatch {
    private val frames = ThreadLocal<Frame?>()

    internal fun current(): Frame? = frames.get()

    internal fun enter(
        lease: PersistenceJdbcLease,
        returning: Boolean,
        kind: PersistenceJdbcGuardCallKind = if (returning) PersistenceJdbcGuardCallKind.CLEANUP else PersistenceJdbcGuardCallKind.BUSINESS,
    ): Frame = Frame(lease, returning, kind, frames.get()).also { frames.set(it) }

    internal class Frame internal constructor(
        internal val lease: PersistenceJdbcLease,
        private val returning: Boolean,
        internal val kind: PersistenceJdbcGuardCallKind,
        private val parent: Frame?,
    ) {
        private val caller = Thread.currentThread()
        private var ended = false
        internal val identity: PersistenceJdbcGuardIdentity get() = lease.identity

        internal fun returning(): Boolean = !ended && returning

        internal fun actualEnded(): Boolean = ended

        internal fun select(lower: PhysicalJdbcFacade, ownership: PersistenceOwnership): PersistenceJdbcPoolEpoch {
            if (ended || caller !== Thread.currentThread() || frames.get() !== this) PersistenceJdbcGuardContext.refuse()
            return lease.select(lower, ownership, returning, kind)
        }

        internal fun end() {
            check(!ended && caller === Thread.currentThread() && frames.get() === this)
            if (parent == null) frames.remove() else frames.set(parent)
            ended = true
        }
    }
}
