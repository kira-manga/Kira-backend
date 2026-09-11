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
        if (phase.get() !== Phase.OPEN || !ownership.currentPoolState(state) || !ownership.permits(state.epoch)) PersistenceJdbcGuardContext.refuse()
        if (!cancellation && Thread.currentThread() !== original) PersistenceJdbcGuardContext.refuse()
        if (!owner.businessReady()) PersistenceJdbcGuardContext.refuse()
    }

    internal fun select(expected: PhysicalJdbcFacade, expectedOwner: PersistenceOwnership, returning: Boolean): PersistenceJdbcPoolEpoch {
        if (lower !== expected || ownership !== expectedOwner || !ownership.currentPoolState(state)) PersistenceJdbcGuardContext.refuse()
        if (Thread.currentThread() !== original) PersistenceJdbcGuardContext.refuse()
        if (returning) {
            if (phase.get() !== Phase.RETURNING || transfer?.consented() == true) PersistenceJdbcGuardContext.refuse()
        } else {
            requireBusiness()
        }
        return state
    }

    internal fun enterDispatch(cancellation: Boolean = false): PersistenceJdbcDispatch.Frame {
        requireBusiness(cancellation)
        return PersistenceJdbcDispatch.enter(this, returning = false)
    }

    internal fun closed(): Boolean = phase.get() !== Phase.OPEN || ownership.retirementRequested()

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
        if (ownership.ownershipLockHeld()) PersistenceJdbcGuardContext.refuse()
        val budget = PersistenceTimeBudget.start(1_000)
        var entered = false
        val operation = try {
            returnCaller = PersistenceOwnedFactoryCaller.capture() // Metadata only, outside F/G/T.
            val prepared = entitlement.prepareReturn(budget) ?: PersistenceJdbcGuardContext.refuse()
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
        var dispatch: PersistenceJdbcDispatch.Frame? = null
        var failure: Throwable? = null
        var bookkeepingFailure: SQLException? = null
        try {
            check(phase.compareAndSet(Phase.OPEN, Phase.RETURNING))
            val attempt = PersistenceJdbcPoolTransfer.prepare(ownership, state, PersistenceJdbcPoolTransfer.Kind.RETURN, budget, requireNotNull(returnCaller))
            transfer = attempt
            if (!attempt.claim()) PersistenceJdbcGuardContext.refuse()
            if (!state.epoch.stopBusiness(cleanup)) PersistenceJdbcGuardContext.refuse()
            if (state.epoch.poisoned() || state.context.graphFailed() || state.context.transaction.uncertain()) {
                attempt.reject()
                PersistenceJdbcGuardContext.refuse()
            }
            dispatch = PersistenceJdbcDispatch.enter(this, returning = true)
            handle.close() // No epoch foreground ancestor around this complete Hikari/return extent.
            if (!attempt.consented()) PersistenceJdbcGuardContext.refuse()
        } catch (problem: Throwable) {
            if (transfer?.consented() == true || transfer?.callerSamplingFailed() == true) operation.failBeforeEnd()
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
                    try {
                        // Exact source ownership is claimed before the private Hikari eviction.
                        // This is still inside the consumed RETURN frame, never a second ingress.
                        owner.evictOwned(this, handle, budget)
                    } catch (problem: Throwable) {
                        operation.failBeforeEnd()
                        if (failure == null) failure = problem
                    }
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
                    val originalFailure = failure
                    if (originalFailure !is InterruptedException) {
                        try {
                            requireNotNull(returnCaller).restoreAfterFailure()
                        } catch (problem: Throwable) {
                            operation.failBeforeEnd()
                            if (originalFailure == null) throw problem
                        }
                    }
                    // For an original InterruptedException this adapter IS the sole restoration.
                    // Both its callback and its own failure stay inside this genuine RETURN.
                    failure = originalFailure?.let { state.context.adaptFailure(it) }
                } catch (problem: Throwable) {
                    operation.failBeforeEnd()
                    failure = if (problem is Error) problem else bookkeepingFailure ?: problem
                } finally {
                    var holderEnded = transfer?.actualEnded() != false
                    try {
                        val attempt = transfer
                        if (attempt != null && !attempt.actualEnded()) {
                            attempt.end()
                            holderEnded = true
                        }
                    } catch (problem: Throwable) {
                        operation.failBeforeEnd()
                        if (failure == null) failure = if (problem is Error) problem else bookkeepingFailure ?: problem
                    }
                    var dispatchEnded = dispatch == null
                    try {
                        dispatch?.end()
                        dispatchEnded = true
                    } catch (problem: Throwable) {
                        operation.failBeforeEnd()
                        if (failure == null) failure = if (problem is Error) problem else bookkeepingFailure ?: problem
                    } finally {
                        phase.set(Phase.CLOSED)
                        // Never publish an ended actor frame while its holder/TL tail is unresolved.
                        // A consented old tail records only actor uncertainty, not successor eviction.
                        if (holderEnded && dispatchEnded) {
                            try {
                                if (!operation.end() || !operation.actualFrameEnded()) {
                                    operation.failBeforeEnd()
                                    if (failure == null) failure = requireNotNull(bookkeepingFailure)
                                }
                            } catch (problem: Throwable) {
                                if (failure == null) failure = if (problem is Error) problem else bookkeepingFailure ?: problem
                            }
                        } else {
                            operation.failBeforeEnd()
                        }
                    }
                }
            }
        }
        failure?.let { throw it } // Already adapted, or an adapter/Error outcome; never another callback.
    }

    internal fun returnTransfer(expected: PhysicalJdbcFacade): PersistenceJdbcPoolTransfer? = transfer?.takeIf {
        lower === expected && phase.get() === Phase.RETURNING && Thread.currentThread() === original &&
            !it.actualEnded() && ownership.ownsTransfer(it)
    }

    internal fun afterClearWarnings(expected: PhysicalJdbcFacade, call: PersistenceJdbcGuardCall) {
        if (returnTransfer(expected) == null) PersistenceJdbcGuardContext.refuse()
        lower.finishReturn(this, call)
    }

    internal fun claimEviction(expectedOwner: GuardedDataSource, candidate: Connection, budget: PersistenceTimeBudget): Boolean {
        if (owner !== expectedOwner || handle !== candidate || Thread.currentThread() !== original || phase.get() !== Phase.RETURNING ||
            transfer?.consented() == true || !ownership.currentPoolState(state) || !evictionClaimed.compareAndSet(false, true)
        ) return false
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
        ): PersistenceJdbcLease = PersistenceJdbcLease(owner, lower, ownership, state, handle, entitlement)
    }
}

/** Positive, exact original-thread/epoch dispatch. Absence is never authority for composed lower calls. */
internal object PersistenceJdbcDispatch {
    private val frames = ThreadLocal<Frame?>()

    internal fun current(): Frame? = frames.get()

    internal fun enter(lease: PersistenceJdbcLease, returning: Boolean): Frame = Frame(lease, returning, frames.get()).also { frames.set(it) }

    internal class Frame internal constructor(
        internal val lease: PersistenceJdbcLease,
        private val returning: Boolean,
        private val parent: Frame?,
    ) {
        private val caller = Thread.currentThread()
        private var ended = false
        internal val identity: PersistenceJdbcGuardIdentity get() = lease.identity

        internal fun returning(): Boolean = !ended && returning

        internal fun select(lower: PhysicalJdbcFacade, ownership: PersistenceOwnership): PersistenceJdbcPoolEpoch {
            if (ended || caller !== Thread.currentThread() || frames.get() !== this) PersistenceJdbcGuardContext.refuse()
            return lease.select(lower, ownership, returning)
        }

        internal fun end() {
            check(!ended && caller === Thread.currentThread() && frames.get() === this)
            if (parent == null) frames.remove() else frames.set(parent)
            ended = true
        }
    }
}
