package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Concrete F→G handshake. External callers only use immediate tryLock; no callbacks run under F/G.
 * Lifecycle/driver/terminal composition supplies readiness and real resource facts, not this handshake.
 */
internal class PersistencePhysicalFactoryBinding(capacity: Int, private val shutdown: AtomicBoolean, private val managed: PersistenceJdbcParticipant? = null) {
    internal val ledger = PersistencePhysicalLedger(capacity, owned = true)
    internal val rendezvous = PersistenceFactoryRendezvous<PersistencePhysicalRecord, PersistenceJdbcCandidate>(this)
    internal val legacyRegistry = PersistencePhysicalRegistry(ledger)
    internal val admissionOpen = AtomicBoolean()
    internal val completion = PersistencePhysicalCompletion(this)
    private val stopRequested = AtomicBoolean()
    private var worker: PersistenceRetainedPlatformThread? = null

    fun request(allowanceMillis: Long): PersistenceFactoryResult<PersistenceJdbcCandidate> = PersistenceOwnedFactoryRequest(this, allowanceMillis).execute()

    internal fun request(allowanceMillis: Long, policy: PersistenceDriverAttemptPolicy): PersistenceFactoryResult<PersistenceJdbcCandidate> =
        PersistenceOwnedFactoryRequest(this, allowanceMillis, policy).execute()

    internal fun request(opening: PersistencePgDriverOpening): PersistenceFactoryResult<PersistenceJdbcCandidate> =
        PersistenceOwnedFactoryRequest(this, opening).execute()

    internal fun isClosed(): Boolean = shutdown.get() || stopRequested.get()

    /** Acquire/termination observation only outside F/G/T; no result/raw/reference is released by this fact. */
    internal fun actualFactoryThreadEnded(): Boolean = worker?.termination() === PersistenceThreadTermination.TERMINATED

    /** Stable constructor association; actual Thread identity is checked only outside ownership locks. */
    internal fun isOwnedWorkerThread(): Boolean =
        !ledger.lock.isHeldByCurrentThread && !rendezvous.lock.isHeldByCurrentThread && worker?.thread === Thread.currentThread()

    /** Only the retained managed F1 may wait for opening bookkeeping; external callers and legacy helpers stay fail-fast. */
    internal fun isManagedOpeningWorker(): Boolean = managed != null && isOwnedWorkerThread()

    /** Constructor-time association only; no start, readiness publication or extension callback. */
    internal fun retainOwnedWorker(retained: PersistenceRetainedPlatformThread) {
        check(worker == null && rendezvous.thread == null && rendezvous.generation == PersistenceFactoryGeneration.NEW)
        worker = retained
        rendezvous.thread = retained.thread
    }

    internal fun claimOwnedWorkerStart(claimed: AtomicBoolean): PersistenceOwnedFactoryStart? {
        if (!rendezvous.lock.tryLock()) return PersistenceOwnedFactoryStart.CONTENDED
        return try {
            if (!claimed.compareAndSet(false, true)) return PersistenceOwnedFactoryStart.ALREADY_CLAIMED
            if (isClosed()) {
                worker?.forbidStart()
                rendezvous.generation = PersistenceFactoryGeneration.SEALED
                return PersistenceOwnedFactoryStart.CLOSED
            }
            check(rendezvous.generation == PersistenceFactoryGeneration.NEW && worker != null)
            rendezvous.generation = PersistenceFactoryGeneration.STARTING
            rendezvous.startInProgress = true
            null
        } finally {
            rendezvous.lock.unlock()
        }
    }

    internal fun requestOwnedStop(): Boolean {
        val changed = stopRequested.compareAndSet(false, true)
        admissionOpen.set(false)
        worker?.forbidStart()
        return changed
    }

    internal fun isOwnedReceiverReady(): Boolean {
        if (!rendezvous.lock.tryLock()) return false
        return try {
            !isClosed() && worker?.startPhase() === PersistenceThreadStartPhase.RETURNED &&
                rendezvous.generation == PersistenceFactoryGeneration.WAITING && rendezvous.current == null
        } finally {
            rendezvous.lock.unlock()
        }
    }

    internal fun reserve(
        control: PersistenceOwnedCallerControl,
        policy: PersistenceDriverAttemptPolicy = PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER,
        opening: PersistencePgDriverOpening? = null,
    ): PersistencePhysicalEntry? {
        require(opening == null || opening.policy === policy) { "Persistence reservation policy must match its opening." }
        if (!ledger.lock.tryLock()) {
            control.fail(PersistenceFactoryFailure.BUSY)
            return null
        }
        return try {
            val refusal = when {
                isClosed() || ledger.sealed -> PersistenceFactoryFailure.CLOSED
                !admissionOpen.get() -> PersistenceFactoryFailure.NOT_READY
                !control.caller.isCurrent() -> PersistenceFactoryFailure.COORDINATION_FAILED
                else -> null
            }
            if (refusal != null) {
                control.fail(refusal)
                return null
            }
            val slot = ledger.entries.indexOfFirst { it == null }
            if (slot < 0) {
                control.fail(PersistenceFactoryFailure.BUSY)
                return null
            }
            val record = PersistencePhysicalRecord(slot)
            val entry = PersistencePhysicalEntry(record, control, policy, this, opening)
            entry.attempt = PersistenceFactoryAttempt(record, control.budget, control)
            if (!control.bindRecord(record)) {
                control.fail(PersistenceFactoryFailure.COORDINATION_FAILED)
                return null
            }
            // All fallible packaging precedes membership. The exact control is owned from this instant.
            ledger.entries[slot] = entry
            entry
        } finally {
            ledger.lock.unlock()
        }
    }

    internal fun admit(entry: PersistencePhysicalEntry): Boolean {
        val control = requireNotNull(entry.control)
        if (!rendezvous.lock.tryLock()) {
            control.fail(PersistenceFactoryFailure.BUSY)
            return false
        }
        return try {
            if (!ledger.lock.tryLock()) {
                control.fail(PersistenceFactoryFailure.BUSY)
                return false
            }
            try {
                val attempt = requireNotNull(entry.attempt)
                val reason = admissionFailure(entry) ?: finalCallerFailure(control)
                if (reason != null) {
                    control.fail(reason)
                    return false
                }
                // Only the authentic platform receiver can be queued on this owned Condition.
                rendezvous.changed.signalAll()
                if (!control.attach()) return false
                entry.dispatched = true
                rendezvous.current = attempt
                rendezvous.generation = PersistenceFactoryGeneration.ACTIVE
                true
            } finally {
                ledger.lock.unlock()
            }
        } finally {
            rendezvous.lock.unlock()
        }
    }

    /** One accepted-outcome observation, not a retry of admission. Packaging happens after F unlocks. */
    internal fun offered(entry: PersistencePhysicalEntry): PersistenceJdbcCandidate? {
        if (!rendezvous.lock.tryLock()) return null
        return try {
            val attempt = requireNotNull(entry.attempt)
            val control = requireNotNull(entry.control)
            val failure = when {
                rendezvous.current !== attempt -> PersistenceFactoryFailure.COORDINATION_FAILED
                isClosed() -> PersistenceFactoryFailure.CLOSED
                else -> rendezvous.closedFailure() ?: attempt.failure
            }
            if (failure != null) {
                control.fail(failure)
                null
            } else if (attempt.phase == PersistenceFactoryAttemptPhase.OFFERED && attempt.result === entry.candidate) {
                entry.candidate
            } else {
                null
            }
        } finally {
            rendezvous.lock.unlock()
        }
    }

    internal fun take(entry: PersistencePhysicalEntry, prepared: PersistenceFactoryResult.Success<PersistenceJdbcCandidate>): Boolean {
        if (!rendezvous.lock.tryLock()) return false
        return try {
            if (!ledger.lock.tryLock()) return false
            try {
                val control = requireNotNull(entry.control)
                val attempt = requireNotNull(entry.attempt)
                val reason = claimFailure(entry, prepared) ?: finalCallerFailure(control)
                if (reason != null) {
                    control.fail(reason)
                    return false
                }
                // Signal before the non-fallible commit. The receiver cannot reacquire F before unlock.
                rendezvous.changed.signalAll()
                if (!control.take()) return false
                attempt.commitOwnedTransfer()
                true
            } finally {
                ledger.lock.unlock()
            }
        } finally {
            rendezvous.lock.unlock()
        }
    }

    /** Caller-side unused release is one nonblocking attempt. Failure leaves the exact Entry to the scanner. */
    internal fun releaseRefused(entry: PersistencePhysicalEntry): Boolean {
        if (!ledger.lock.tryLock()) return false
        return try {
            releaseRefusedLocked(entry)
        } finally {
            ledger.lock.unlock()
        }
    }

    /** Called only by the actual worker while F is already held. Never acquire G and then F. */
    internal fun reconcileWorkerLocked(attempt: PersistenceFactoryAttempt<*, *>) {
        check(rendezvous.lock.isHeldByCurrentThread && Thread.currentThread() === rendezvous.thread)
        if (!ledger.lock.tryLock()) return
        try {
            val record = attempt.input as? PersistencePhysicalRecord ?: return
            val entry = ledger.current(record) ?: return
            if (entry.attempt !== attempt) return
            reconcileLocked(entry)
        } finally {
            ledger.lock.unlock()
        }
    }

    /** Fixed scanner pass, bounded by physical capacity; contention never discards a pending projection. */
    internal fun reconcileCallers(): Boolean {
        if (!rendezvous.lock.tryLock()) return false
        return try {
            if (!ledger.lock.tryLock()) return false
            try {
                reconcileLifecycleLocked()
                for (entry in ledger.entries) {
                    if (entry == null) continue
                    if (entry.control?.state()?.phase == PersistenceOwnedCallerPhase.REFUSED) {
                        releaseRefusedLocked(entry)
                    } else {
                        reconcileLocked(entry)
                    }
                }
                true
            } finally {
                ledger.lock.unlock()
            }
        } finally {
            rendezvous.lock.unlock()
        }
    }

    /** F→G only. Start/body facts are observations, never invented worker entry or termination. */
    private fun reconcileLifecycleLocked() {
        val previousGeneration = rendezvous.generation
        val previousStart = rendezvous.startInProgress
        val phase = worker?.startPhase()
        if (phase != null && phase !== PersistenceThreadStartPhase.NEW) {
            // NEW can be the pre-invocation gap after the enclosing F start claim. Do not erase it.
            rendezvous.startInProgress = phase === PersistenceThreadStartPhase.CLAIMED || phase === PersistenceThreadStartPhase.INVOKING
        }
        if (phase === PersistenceThreadStartPhase.THREW) {
            rendezvous.generation = PersistenceFactoryGeneration.BROKEN
            rendezvous.current?.breakProcessing(PersistenceFactoryFailure.COORDINATION_FAILED)
            admissionOpen.set(false)
        }
        if (isClosed()) {
            worker?.forbidStart()
            ledger.sealed = true
            admissionOpen.set(false)
            if (rendezvous.generation != PersistenceFactoryGeneration.BROKEN) rendezvous.generation = PersistenceFactoryGeneration.SEALED
            rendezvous.current?.abandon(PersistenceFactoryFailure.CLOSED)
            for (entry in ledger.entries) {
                if (entry == null) continue
                entry.retirementRequested.set(true)
            }
        }
        if (previousGeneration != rendezvous.generation || previousStart != rendezvous.startInProgress) rendezvous.changed.signalAll()
    }

    private fun reconcileLocked(entry: PersistencePhysicalEntry) {
        val attempt = entry.attempt ?: return
        val control = entry.control ?: return
        if (ledger.current(entry.record) !== entry || !control.matchesRecord(entry.record)) return
        if (attempt.ownedControl !== control || attempt.budget !== control.budget) return
        if (rendezvous.current === attempt && control.state().phase == PersistenceOwnedCallerPhase.ABANDONED) {
            entry.retirementRequested.set(true)
            attempt.projectOwnedAbandonment()
            // This projection can finish even while T is contended. A logical detach is not a physical fence.
            rendezvous.finishIfBoth(attempt)
            rendezvous.changed.signalAll()
        }
        // Also covers an already-TAKEN candidate and a pending T fence after F has cleared current.
        if (entry.retirementRequested.get()) {
            val transports = entry.transports
            if (transports == null) entry.retiring = true else transports.fenceRetirementLocked()
        }
    }

    private fun releaseRefusedLocked(entry: PersistencePhysicalEntry): Boolean {
        if (ledger.current(entry.record) !== entry || entry.control?.state()?.phase != PersistenceOwnedCallerPhase.REFUSED) return false
        if (entry.dispatched || entry.opening != PersistencePhysicalOpeningPhase.UNCLAIMED) return false
        if (entry.raw.get() != null || entry.terminal != null) return false
        ledger.entries[entry.record.slotHint] = null
        return true
    }

    private fun admissionFailure(entry: PersistencePhysicalEntry): PersistenceFactoryFailure? = when {
        ledger.current(entry.record) !== entry -> PersistenceFactoryFailure.COORDINATION_FAILED
        entry.control?.matchesRecord(entry.record) != true -> PersistenceFactoryFailure.COORDINATION_FAILED
        isClosed() || ledger.sealed || entry.retiring -> PersistenceFactoryFailure.CLOSED
        !admissionOpen.get() -> PersistenceFactoryFailure.NOT_READY
        managed?.permits(entry) == false -> PersistenceFactoryFailure.NOT_READY
        worker != null && worker?.startPhase() !== PersistenceThreadStartPhase.RETURNED -> PersistenceFactoryFailure.NOT_READY
        rendezvous.closedFailure() != null -> rendezvous.closedFailure()
        rendezvous.current != null || rendezvous.generation == PersistenceFactoryGeneration.ACTIVE -> PersistenceFactoryFailure.BUSY
        rendezvous.generation != PersistenceFactoryGeneration.WAITING -> PersistenceFactoryFailure.NOT_READY
        entry.dispatched || entry.control?.state() != PersistenceOwnedCallerDisposition.PREPARED -> PersistenceFactoryFailure.COORDINATION_FAILED
        else -> null
    }

    private fun claimFailure(
        entry: PersistencePhysicalEntry,
        prepared: PersistenceFactoryResult.Success<PersistenceJdbcCandidate>,
    ): PersistenceFactoryFailure? {
        val attempt = requireNotNull(entry.attempt)
        val control = requireNotNull(entry.control)
        if (!claimIdentityMatches(entry, prepared)) return PersistenceFactoryFailure.COORDINATION_FAILED
        return when {
            isClosed() || ledger.sealed || entry.retiring || entry.retirementRequested.get() -> PersistenceFactoryFailure.CLOSED
            managed?.permits(entry) == false -> PersistenceFactoryFailure.NOT_READY
            rendezvous.closedFailure() != null -> rendezvous.closedFailure()
            attempt.failure != null -> attempt.failure
            control.state() != PersistenceOwnedCallerDisposition.ATTACHED -> PersistenceFactoryFailure.COORDINATION_FAILED
            attempt.phase != PersistenceFactoryAttemptPhase.OFFERED || attempt.result !== prepared.value -> PersistenceFactoryFailure.COORDINATION_FAILED
            attempt.callerDetached || attempt.workerSettled || attempt.unresolved -> PersistenceFactoryFailure.BROKEN
            !entry.dispatched || entry.opening != PersistencePhysicalOpeningPhase.SETTLED || !entry.scopeEnded -> PersistenceFactoryFailure.NOT_READY
            entry.raw.get() == null || entry.unknown -> PersistenceFactoryFailure.CREATE_FAILED
            managed != null -> entry.transports?.liveFailureLocked()
            else -> null
        }
    }

    private fun claimIdentityMatches(entry: PersistencePhysicalEntry, prepared: PersistenceFactoryResult.Success<PersistenceJdbcCandidate>): Boolean {
        val attempt = requireNotNull(entry.attempt)
        val control = requireNotNull(entry.control)
        return ledger.current(entry.record) === entry && rendezvous.current === attempt && control.matchesRecord(entry.record) &&
            attempt.ownedControl === control && attempt.budget === control.budget &&
            prepared.value === entry.candidate && prepared.receipt === control.receipt
    }

    private fun finalCallerFailure(control: PersistenceOwnedCallerControl): PersistenceFactoryFailure? =
        control.caller.sampleActualFlag() ?: if (persistenceFactoryRemainingMillis(control.budget) == 0L) PersistenceFactoryFailure.TIMEOUT else null

    override fun toString(): String = "PersistencePhysicalFactoryBinding(redacted)"
}
