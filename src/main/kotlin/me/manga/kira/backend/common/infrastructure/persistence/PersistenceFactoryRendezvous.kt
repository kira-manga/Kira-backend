package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One already-idle receiver, never a pending-job queue. Nothing here invokes a resource operation. */
internal class PersistenceFactoryRendezvous<I : Any, R : Any>(internal val ownedBinding: PersistencePhysicalFactoryBinding? = null) {
    // Only the concrete owned binding shares F bookkeeping. It never accepts an under-lock callback.
    internal val lock = ReentrantLock()
    internal val changed = lock.newCondition()
    internal var generation = PersistenceFactoryGeneration.NEW
    internal var startInProgress = false
    internal var thread: Thread? = null
    internal var current: PersistenceFactoryAttempt<I, R>? = null

    fun beginStart(): Boolean {
        check(ownedBinding == null)
        return lock.withLock {
            if (generation != PersistenceFactoryGeneration.NEW) return@withLock false
            startInProgress = true
            generation = PersistenceFactoryGeneration.STARTING
            changed.signalAll()
            true
        }
    }

    fun retainThread(owned: Thread) {
        check(ownedBinding == null)
        lock.withLock {
            check(startInProgress && thread == null)
            thread = owned
        }
    }

    fun authorizeStart(): Boolean {
        check(ownedBinding == null)
        return lock.withLock { startInProgress && generation == PersistenceFactoryGeneration.STARTING }
    }

    fun finishStart() {
        check(ownedBinding == null)
        lock.withLock {
            startInProgress = false
            changed.signalAll()
        }
    }

    fun seal() {
        check(ownedBinding == null)
        lock.withLock {
            if (generation != PersistenceFactoryGeneration.BROKEN) generation = PersistenceFactoryGeneration.SEALED
            current?.abandon(PersistenceFactoryFailure.CLOSED)
            changed.signalAll()
        }
    }

    fun breakGeneration(reason: PersistenceFactoryFailure) {
        check(ownedBinding == null)
        lock.withLock { breakGenerationLocked(reason) }
    }

    fun workerFailed(reason: PersistenceFactoryFailure) {
        requireWorker()
        lock.withLock {
            breakGenerationLocked(reason)
            current?.let { attempt ->
                ownedBinding?.reconcileWorkerLocked(attempt)
                if (!attempt.workerSettled) attempt.settleWorker()
                finishIfBoth(attempt)
            }
            changed.signalAll()
        }
    }

    fun admit(input: I, budget: PersistenceTimeBudget): PersistenceFactoryAdmission<I, R> {
        check(ownedBinding == null)
        if (Thread.currentThread().isInterrupted) return PersistenceFactoryAdmission.refused(PersistenceFactoryFailure.INTERRUPTED)
        if (!lock.tryLock()) return PersistenceFactoryAdmission.refused(PersistenceFactoryFailure.BUSY)
        return try {
            val refusal = admissionFailure(budget)
            if (refusal != null) {
                PersistenceFactoryAdmission.refused(refusal)
            } else {
                val attempt = PersistenceFactoryAttempt<I, R>(input, budget)
                val admission = PersistenceFactoryAdmission.accepted(attempt)
                current = attempt
                generation = PersistenceFactoryGeneration.ACTIVE
                changed.signalAll()
                admission
            }
        } finally {
            lock.unlock()
        }
    }

    /** At most the one admitted caller can acquire/wait here. No caller-side probe or custom wait. */
    fun awaitResult(attempt: PersistenceFactoryAttempt<I, R>): PersistenceFactoryResult<R> {
        check(ownedBinding == null)
        var restoreInterruption = false
        lock.lock()
        try {
            return runCatching {
                check(current === attempt && !attempt.callerDetached)
                awaitCallerOutcome(attempt)
            }.getOrElse { failure ->
                val reason = persistenceFactoryUnexpectedReason(failure)
                if (failure is InterruptedException) {
                    restoreInterruption = true
                } else {
                    breakGenerationLocked(reason)
                }
                val outcome = detachFailure(attempt, reason)
                if (failure is Error) throw failure
                outcome
            }
        } finally {
            lock.unlock()
            // Even restoration is outside the coordination lock.
            if (restoreInterruption) Thread.currentThread().interrupt()
        }
    }

    /** WAITING is published only by the actual receiver, immediately before its Condition releases the lock. */
    fun awaitWork(): PersistenceFactoryAttempt<I, R>? {
        requireWorker()
        lock.lock()
        try {
            while (true) {
                requirePersistenceFactoryWorkerNotInterrupted()
                current?.let { ownedBinding?.reconcileWorkerLocked(it) }
                val attempt = current
                if (attempt != null && !attempt.workerSettled) return attempt
                if (closedFailure() != null) return null
                if (attempt == null) {
                    generation = PersistenceFactoryGeneration.WAITING
                    changed.signalAll()
                }
                // An already-settled job with an attached caller remains ACTIVE, never receiver readiness.
                // This occupied acknowledgement park does not retry an expired acceptance budget.
                changed.await()
            }
        } finally {
            lock.unlock()
        }
    }

    fun beginWork(attempt: PersistenceFactoryAttempt<I, R>): Boolean {
        requireWorker()
        return lock.withLock {
            requireCurrent(attempt)
            requirePersistenceFactoryWorkerNotInterrupted()
            abandonIfIneligible(attempt)
            if (attempt.failure != null) return@withLock false
            attempt.beginWork()
            true
        }
    }

    fun retainResult(attempt: PersistenceFactoryAttempt<I, R>, result: R) {
        requireWorker()
        lock.withLock {
            requireCurrent(attempt)
            // Retain first, including when the callback returned normally with interruption set.
            attempt.retain(result)
            ownedBinding?.reconcileWorkerLocked(attempt)
        }
    }

    fun creationFailed(attempt: PersistenceFactoryAttempt<I, R>) {
        requireWorker()
        lock.withLock {
            requireCurrent(attempt)
            attempt.abandon(PersistenceFactoryFailure.CREATE_FAILED)
            ownedBinding?.reconcileWorkerLocked(attempt)
            changed.signalAll()
        }
    }

    fun discardReturned(attempt: PersistenceFactoryAttempt<I, R>) {
        requireWorker()
        lock.withLock {
            requireCurrent(attempt)
            attempt.recordDiscardReturn()
            ownedBinding?.reconcileWorkerLocked(attempt)
        }
    }

    fun offer(attempt: PersistenceFactoryAttempt<I, R>): Boolean {
        requireWorker()
        return lock.withLock {
            requireCurrent(attempt)
            requirePersistenceFactoryWorkerNotInterrupted()
            abandonIfIneligible(attempt)
            if (attempt.failure != null) return@withLock false
            attempt.offer()
            changed.signalAll()
            true
        }
    }

    /** Returns the one retained late result for discard, or null after transfer/permanent uncertainty. */
    fun awaitDisposition(attempt: PersistenceFactoryAttempt<I, R>): R? {
        requireWorker()
        lock.lock()
        try {
            requireCurrent(attempt)
            while (true) {
                requirePersistenceFactoryWorkerNotInterrupted()
                if (attempt.transferred || generation == PersistenceFactoryGeneration.BROKEN) return null
                abandonIfIneligible(attempt)
                if (attempt.failure != null) return if (attempt.result != null) attempt.beginDiscard() else null
                val remaining = persistenceFactoryRemainingMillis(attempt.budget)
                if (remaining == 0L) {
                    attempt.abandon(PersistenceFactoryFailure.TIMEOUT)
                    changed.signalAll()
                } else {
                    changed.await(remaining, TimeUnit.MILLISECONDS)
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /** Must be the worker's last action for this job: no later callback or scheduling probe. */
    fun settleWorker(attempt: PersistenceFactoryAttempt<I, R>) {
        requireWorker()
        lock.withLock {
            requireCurrent(attempt)
            requirePersistenceFactoryWorkerNotInterrupted()
            if (generation == PersistenceFactoryGeneration.BROKEN) attempt.breakProcessing(PersistenceFactoryFailure.BROKEN)
            ownedBinding?.reconcileWorkerLocked(attempt)
            attempt.settleWorker()
            finishIfBoth(attempt)
            changed.signalAll()
        }
    }

    fun snapshot(): PersistenceFactorySnapshot {
        if (ownedBinding != null) return PersistenceFactorySnapshot.Unavailable
        if (!lock.tryLock()) return PersistenceFactorySnapshot.Unavailable
        return try {
            val attempt = current
            PersistenceFactorySnapshot.Available(
                generation,
                startInProgress,
                attempt != null,
                attempt?.result != null,
                attempt?.cancellation?.isRequested() == true,
                attempt != null && !attempt.callerDetached,
                attempt?.workerSettled == true,
            )
        } finally {
            lock.unlock()
        }
    }

    fun awaitReady(budget: PersistenceTimeBudget): PersistenceFactoryObservation {
        check(ownedBinding == null)
        if (!observationLock(budget)) return PersistenceFactoryObservation.TIMEOUT
        return try {
            while (generation != PersistenceFactoryGeneration.WAITING && closedFailure() == null) {
                if (!observationWait(budget)) return PersistenceFactoryObservation.TIMEOUT
            }
            when {
                persistenceFactoryRemainingMillis(budget) == 0L -> PersistenceFactoryObservation.TIMEOUT
                closedFailure() != null -> PersistenceFactoryObservation.CLOSED
                else -> PersistenceFactoryObservation.READY
            }
        } finally {
            lock.unlock()
        }
    }

    /** Internal target only. The public kernel observer joins this actual Thread outside the lock. */
    fun awaitJoinTarget(budget: PersistenceTimeBudget): PersistenceFactoryJoinTarget {
        check(ownedBinding == null)
        if (!observationLock(budget)) return PersistenceFactoryJoinTarget(null, PersistenceFactoryObservation.TIMEOUT)
        return try {
            if (closedFailure() == null) return PersistenceFactoryJoinTarget(null, PersistenceFactoryObservation.NOT_SEALED)
            while (startInProgress) {
                if (!observationWait(budget)) return PersistenceFactoryJoinTarget(null, PersistenceFactoryObservation.TIMEOUT)
            }
            if (persistenceFactoryRemainingMillis(budget) == 0L) {
                PersistenceFactoryJoinTarget(null, PersistenceFactoryObservation.TIMEOUT)
            } else {
                // No caller can start this retained Thread after finishStart; NEW is now truly inert.
                PersistenceFactoryJoinTarget(thread, null)
            }
        } finally {
            lock.unlock()
        }
    }

    /** Ordinary notification only; useful to test predicate loops without replacing Condition waits. */
    fun signalWaiters() {
        check(ownedBinding == null)
        lock.withLock { changed.signalAll() }
    }

    private fun awaitCallerOutcome(attempt: PersistenceFactoryAttempt<I, R>): PersistenceFactoryResult<R> {
        while (true) {
            val ineligible = callerFailure(attempt)
            if (ineligible != null) return detachFailure(attempt, ineligible)
            if (attempt.phase == PersistenceFactoryAttemptPhase.OFFERED) {
                val success = PersistenceFactoryResult.Success(requireNotNull(attempt.result), attempt.receipt)
                // Packaging may have taken time. The actual claim rechecks the original deadline and current flag.
                val afterPackaging = callerFailure(attempt)
                if (afterPackaging != null) return detachFailure(attempt, afterPackaging)
                attempt.transferAndDetach()
                finishIfBoth(attempt)
                changed.signalAll()
                return success
            }
            val remaining = persistenceFactoryRemainingMillis(attempt.budget)
            if (remaining == 0L) return detachFailure(attempt, PersistenceFactoryFailure.TIMEOUT)
            changed.await(remaining, TimeUnit.MILLISECONDS)
        }
    }

    private fun detachFailure(attempt: PersistenceFactoryAttempt<I, R>, reason: PersistenceFactoryFailure): PersistenceFactoryResult.Failed {
        val outcome = PersistenceFactoryResult.Failed(reason, attempt.receipt)
        attempt.detachFailure(reason)
        finishIfBoth(attempt)
        changed.signalAll()
        return outcome
    }

    private fun callerFailure(attempt: PersistenceFactoryAttempt<I, R>): PersistenceFactoryFailure? = when {
        Thread.currentThread().isInterrupted -> PersistenceFactoryFailure.INTERRUPTED
        closedFailure() != null -> closedFailure()
        persistenceFactoryRemainingMillis(attempt.budget) == 0L -> PersistenceFactoryFailure.TIMEOUT
        else -> attempt.failure
    }

    private fun admissionFailure(budget: PersistenceTimeBudget): PersistenceFactoryFailure? = when {
        Thread.currentThread().isInterrupted -> PersistenceFactoryFailure.INTERRUPTED
        closedFailure() != null -> closedFailure()
        persistenceFactoryRemainingMillis(budget) == 0L -> PersistenceFactoryFailure.TIMEOUT
        current != null || generation == PersistenceFactoryGeneration.ACTIVE -> PersistenceFactoryFailure.BUSY
        generation != PersistenceFactoryGeneration.WAITING -> PersistenceFactoryFailure.NOT_READY
        else -> null
    }

    private fun abandonIfIneligible(attempt: PersistenceFactoryAttempt<I, R>) {
        ownedBinding?.reconcileWorkerLocked(attempt)
        if (attempt.ownedControl?.isAbandoned() == true) {
            attempt.abandon(requireNotNull(attempt.ownedControl.state().reason))
        }
        val reason = closedFailure() ?: if (persistenceFactoryRemainingMillis(attempt.budget) == 0L) PersistenceFactoryFailure.TIMEOUT else null
        if (reason != null) {
            attempt.abandon(reason)
            changed.signalAll()
        }
    }

    internal fun closedFailure(): PersistenceFactoryFailure? = when (generation) {
        PersistenceFactoryGeneration.SEALED -> PersistenceFactoryFailure.CLOSED
        PersistenceFactoryGeneration.BROKEN -> PersistenceFactoryFailure.BROKEN
        else -> null
    }

    private fun breakGenerationLocked(reason: PersistenceFactoryFailure) {
        generation = PersistenceFactoryGeneration.BROKEN
        current?.breakProcessing(reason)
        changed.signalAll()
    }

    private fun requireCurrent(attempt: PersistenceFactoryAttempt<I, R>) {
        check(current === attempt)
    }

    internal fun finishIfBoth(attempt: PersistenceFactoryAttempt<I, R>) {
        if (attempt.finishIfBoth() && !attempt.unresolved) current = null
        // Only awaitWork may publish WAITING. Caller finalization cannot invent an idle receiver.
    }

    private fun requireWorker() {
        check(ownedBinding == null || Thread.currentThread() === thread)
    }

    private fun observationLock(budget: PersistenceTimeBudget): Boolean {
        val remaining = persistenceFactoryRemainingMillis(budget)
        return remaining > 0 && lock.tryLock(remaining, TimeUnit.MILLISECONDS)
    }

    private fun observationWait(budget: PersistenceTimeBudget): Boolean {
        val remaining = persistenceFactoryRemainingMillis(budget)
        if (remaining == 0L) return false
        changed.await(remaining, TimeUnit.MILLISECONDS)
        return true
    }

    override fun toString(): String = "PersistenceFactoryRendezvous(redacted)"
}

internal class PersistenceFactoryJoinTarget(val thread: Thread?, val refusal: PersistenceFactoryObservation?) {
    override fun toString(): String = "PersistenceFactoryJoinTarget"
}
