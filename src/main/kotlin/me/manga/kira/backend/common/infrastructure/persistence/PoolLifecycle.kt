package me.manga.kira.backend.common.infrastructure.persistence

import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One private pool, caller-owned acquisition frames, and one shared shutdown obligation; no waiter/unknown-handle registry. */
internal class PoolLifecycle(private val pool: HikariDataSource, private val owner: PersistenceJdbcLifecycleOwner) {
    private val issuance = Any()
    private val acquisitions = AtomicReference(Acquisitions())
    private val receipt = ShutdownReceipt.prepare(this)
    private val accepted = AtomicBoolean()
    private val firstClose = AtomicReference<CloseAttempt?>()

    /** Caller retains the ticket BEFORE enter/Hikari. This original budget is never replaced or restarted. */
    fun prepareAcquisition(budget: PersistenceTimeBudget): Acquisition {
        check(!owner.ownershipLockHeld())
        return Acquisition.prepare(this, issuance, Thread.currentThread(), budget)
    }

    /** Nonwaiting handoff only. The ticket/strict obligation stays caller-owned if authentication or root acceptance fails. */
    fun handoff(ticket: Acquisition): ShutdownReceipt? {
        if (!ticket.ownedBy(this, issuance) || !ticket.isActualCaller() || !ticket.hasEntered()) return null
        val shared = requestShutdown() ?: return null
        ticket.accept(issuance, shared)
        return shared
    }

    /** Closing admission/requesting the authentic owner is not native shutdown or a disposal receipt. Safe inside an acquisition frame. */
    fun requestShutdown(): ShutdownReceipt? {
        if (owner.ownershipLockHeld()) return null
        while (true) {
            val before = acquisitions.get()
            if (before.sealed || acquisitions.compareAndSet(before, before.copy(sealed = true))) break
        }
        owner.requestShutdown()
        accepted.set(true)
        return receipt
    }

    /** One synchronous, caller-owned actual close. No new executor/thread and no shutdown wait from a still-counted acquisition. */
    fun closePool(): PoolShutdownInvocation {
        if (frames.get() != null) return PoolShutdownInvocation.ACTIVE_ACQUISITION
        if (owner.ownershipLockHeld()) return PoolShutdownInvocation.OWNERSHIP_LOCK_HELD
        requestShutdown()
        val attempt = CloseAttempt(PersistenceOwnedFactoryCaller.capture(), Thread.currentThread())
        if (!firstClose.compareAndSet(null, attempt)) return PoolShutdownInvocation.ALREADY_CLAIMED
        var failure: Throwable? = null
        try {
            var returned = false
            runCatching {
                try {
                    attempt.outcome.set(PersistenceTerminalCall.RUNNING)
                    pool.close()
                    attempt.outcome.set(PersistenceTerminalCall.RETURNED)
                    returned = true
                } finally {
                    // Retain the native failure fact before Result can box any thrown Throwable.
                    if (!returned) attempt.outcome.set(PersistenceTerminalCall.THREW)
                }
            }.onFailure { problem ->
                failure = problem
                if (problem is InterruptedException) attempt.interrupted.set(true)
            }
        } finally {
            // Result packaging or suppression bookkeeping must not bypass caller cleanup.
            try {
                captureCloseBookkeepingFailure(attempt) {
                    if (failure is InterruptedException) Thread.currentThread().interrupt()
                    if (attempt.caller.sampleOutsideLocks() != null) attempt.interrupted.set(true)
                }?.let { failure = combine(failure, it) }
            } finally {
                try {
                    captureCloseBookkeepingFailure(attempt) {
                        attempt.caller.restoreAfterFailure()
                    }?.let { failure = combine(failure, it) }
                } finally {
                    attempt.ended.set(true) // Includes flag sampling/restoration, never just Hikari's native stack return.
                }
            }
        }
        failure?.let { throw it }
        return PoolShutdownInvocation.RETURNED
    }

    private inline fun captureCloseBookkeepingFailure(attempt: CloseAttempt, operation: () -> Unit): Throwable? {
        var returned = false
        return runCatching {
            try {
                operation()
                returned = true
            } finally {
                // Preserve uncertainty even if Result failure boxing itself cannot complete.
                if (!returned) attempt.bookkeepingFailed.set(true)
            }
        }.exceptionOrNull()
    }

    fun acquisitionsEnded(): Boolean = acquisitions.get().let { it.sealed && it.active == 0L }

    fun activeAcquisitions(): Long = acquisitions.get().active

    fun firstCloseOutcome(): PersistenceTerminalCall = firstClose.get()?.outcome?.get() ?: PersistenceTerminalCall.NOT_INVOKED

    fun closeInterruptionObserved(): Boolean = firstClose.get()?.interrupted?.get() == true

    private fun enter(ticket: Acquisition): Boolean {
        if (!ticket.ownedBy(this, issuance) || !ticket.isActualCaller() || owner.ownershipLockHeld()) return false
        val previous = frames.get()
        if (!ticket.claimEntry(issuance, previous)) return false
        var entered = false
        try {
            frames.set(ticket) // All fallible ThreadLocal preparation precedes counted/native admission.
            while (true) {
                val before = acquisitions.get()
                if (before.sealed || ticket.remainingMillis(issuance) == 0L) return false
                if (acquisitions.compareAndSet(before, before.copy(active = Math.addExact(before.active, 1L)))) {
                    entered = true
                    ticket.entered(issuance)
                    return true
                }
            }
        } finally {
            if (!entered) {
                frames.set(previous)
                ticket.refused(issuance)
            }
        }
    }

    private fun end(ticket: Acquisition): Boolean {
        if (!ticket.ownedBy(this, issuance) || !ticket.isActualCaller() || frames.get() !== ticket) return false
        if (!ticket.claimEnd(issuance)) return false
        val previous = ticket.previous(issuance)
        while (true) {
            val before = acquisitions.get()
            check(before.active > 0L)
            val after = before.copy(active = before.active - 1L)
            // All allocation precedes removal of the counted-frame guard. Restore the existing
            // caller-local link before count release; failed CAS reinstates it before retry work.
            frames.set(previous)
            if (acquisitions.compareAndSet(before, after)) {
                ticket.ended(issuance)
                return true
            }
            frames.set(ticket)
        }
    }

    private fun observe(candidate: ShutdownReceipt): PoolShutdownObservation {
        if (candidate !== receipt || !accepted.get()) return PoolShutdownObservation.UNACCEPTED
        if (frames.get() != null) return PoolShutdownObservation.ACTIVE_ACQUISITION
        if (owner.ownershipLockHeld()) return PoolShutdownObservation.OWNERSHIP_LOCK_HELD
        val attempt = firstClose.get() ?: return PoolShutdownObservation.PENDING
        return when {
            !attempt.ended.get() && attempt.thread === Thread.currentThread() -> PoolShutdownObservation.ACTIVE_SHUTDOWN_FRAME
            !attempt.ended.get() || !acquisitionsEnded() -> PoolShutdownObservation.PENDING
            else -> observeEndedPool(attempt)
        }
    }

    private fun observeEndedPool(attempt: CloseAttempt): PoolShutdownObservation {
        // This existing authentic observer may wait; never invoke it under F/G/T or either still-owned frame.
        val native = owner.observeShutdown()
        if (native === PersistenceLifecycleObservation.PENDING) return PoolShutdownObservation.PENDING
        if (native !== PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED && native !== PersistenceLifecycleObservation.DRIVER_CONTRACT_ONLY_ENDED) {
            return PoolShutdownObservation.UNKNOWN
        }
        if (attempt.outcome.get() !== PersistenceTerminalCall.RETURNED || attempt.interrupted.get() || attempt.bookkeepingFailed.get()) {
            return PoolShutdownObservation.UNKNOWN
        }
        // Hikari.close catches interruption and sets isClosed before shutdown. Even a normal first
        // return is NOT an authenticated end of every Hikari-owned actor/extension callback tail.
        // The real facade/root integration must supply that concrete ownership hook; no Boolean setter exists here.
        return PoolShutdownObservation.POOL_ACTORS_UNPROVEN
    }

    private fun combine(original: Throwable?, failure: Throwable): Throwable {
        if (original == null) return failure
        if (original !== failure) original.addSuppressed(failure)
        return original
    }

    override fun toString(): String = "PoolLifecycle(redacted)"

    /** Handle retention is private and caller-proportional. No raw getter or guessed close/evict route. */
    internal class Acquisition private constructor(
        private val pool: PoolLifecycle,
        private val issuance: Any,
        private val caller: Thread,
        private val budget: PersistenceTimeBudget,
    ) {
        private val phase = AtomicReference(AcquisitionPhase.PREPARED)
        private var parent: Acquisition? = null
        private var handle: Connection? = null
        private var shared: ShutdownReceipt? = null

        fun enter(): Boolean = pool.enter(this)

        fun capture(returned: Connection): Boolean {
            if (!isActualCaller() || phase.get() !== AcquisitionPhase.ACTIVE || frames.get() !== this) return false
            if (handle != null) return false
            handle = returned
            return true
        }

        fun handoff(): ShutdownReceipt? = pool.handoff(this)

        /** Call only from the actual acquisition finally, after all attach/failure/output bookkeeping, never after waiting for disposal. */
        fun end(): Boolean = pool.end(this)

        fun actualFrameEnded(): Boolean = phase.get() === AcquisitionPhase.ENDED

        fun disposition(): PoolAcquisitionDisposition = when {
            !hasEntered() -> PoolAcquisitionDisposition.NOT_ENTERED
            shared != null -> PoolAcquisitionDisposition.ROOT_PENDING
            else -> PoolAcquisitionDisposition.CALLER_OWNED
        }

        internal fun ownedBy(owner: PoolLifecycle, authority: Any): Boolean = pool === owner && issuance === authority

        internal fun isActualCaller(): Boolean = Thread.currentThread() === caller

        internal fun hasEntered(): Boolean = phase.get() in ENTERED_PHASES

        internal fun remainingMillis(authority: Any): Long {
            check(issuance === authority && isActualCaller())
            return persistenceFactoryRemainingMillis(budget)
        }

        internal fun claimEntry(authority: Any, previous: Acquisition?): Boolean {
            if (issuance !== authority || !isActualCaller() || !phase.compareAndSet(AcquisitionPhase.PREPARED, AcquisitionPhase.ENTERING)) return false
            parent = previous
            return true
        }

        internal fun entered(authority: Any) {
            check(issuance === authority && isActualCaller())
            phase.set(AcquisitionPhase.ACTIVE)
        }

        internal fun refused(authority: Any) {
            check(issuance === authority && isActualCaller())
            phase.set(AcquisitionPhase.REFUSED)
        }

        internal fun claimEnd(authority: Any): Boolean = issuance === authority && isActualCaller() &&
            phase.compareAndSet(AcquisitionPhase.ACTIVE, AcquisitionPhase.ENDING)

        internal fun previous(authority: Any): Acquisition? {
            check(issuance === authority && isActualCaller())
            return parent
        }

        internal fun ended(authority: Any) {
            check(issuance === authority && isActualCaller())
            phase.set(AcquisitionPhase.ENDED)
        }

        internal fun accept(authority: Any, receipt: ShutdownReceipt) {
            check(issuance === authority && isActualCaller() && hasEntered())
            shared = receipt
        }

        override fun toString(): String = "PoolAcquisition(redacted)"

        companion object {
            private val ENTERED_PHASES = setOf(AcquisitionPhase.ACTIVE, AcquisitionPhase.ENDING, AcquisitionPhase.ENDED)

            internal fun prepare(pool: PoolLifecycle, issuance: Any, caller: Thread, budget: PersistenceTimeBudget): Acquisition =
                Acquisition(pool, issuance, caller, budget)
        }
    }

    /** Exact one-per-pool observation, not a caller completion/disposal publication API. */
    internal class ShutdownReceipt private constructor(private val pool: PoolLifecycle) {
        fun observe(): PoolShutdownObservation = pool.observe(this)

        override fun toString(): String = "PoolShutdownReceipt(redacted)"

        companion object {
            internal fun prepare(pool: PoolLifecycle): ShutdownReceipt = ShutdownReceipt(pool)
        }
    }

    private data class Acquisitions(val sealed: Boolean = false, val active: Long = 0)

    private class CloseAttempt(val caller: PersistenceOwnedFactoryCaller, val thread: Thread) {
        val outcome = AtomicReference(PersistenceTerminalCall.NOT_INVOKED)
        val interrupted = AtomicBoolean()
        val bookkeepingFailed = AtomicBoolean()
        val ended = AtomicBoolean()
    }

    private enum class AcquisitionPhase {
        PREPARED,
        ENTERING,
        ACTIVE,
        ENDING,
        ENDED,
        REFUSED,
    }

    companion object {
        // One caller-local lineage across pools prevents a cross-pool/root shutdown wait cycle too.
        private val frames = ThreadLocal<Acquisition?>()
    }
}

internal enum class PoolAcquisitionDisposition {
    NOT_ENTERED,
    CALLER_OWNED,
    ROOT_PENDING,
}

internal enum class PoolShutdownInvocation {
    RETURNED,
    ALREADY_CLAIMED,
    ACTIVE_ACQUISITION,
    OWNERSHIP_LOCK_HELD,
}

internal enum class PoolShutdownObservation {
    UNACCEPTED,
    PENDING,
    UNKNOWN,
    POOL_ACTORS_UNPROVEN,
    ACTIVE_ACQUISITION,
    ACTIVE_SHUTDOWN_FRAME,
    OWNERSHIP_LOCK_HELD,
}
