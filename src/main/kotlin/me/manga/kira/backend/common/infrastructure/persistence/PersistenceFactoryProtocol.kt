package me.manga.kira.backend.common.infrastructure.persistence

/** Unused factory seam. Input must eventually be an independently registered physical owner. */
internal interface PersistenceFactoryOperations<I : Any, R : Any> {
    fun create(input: I, cancellation: PersistenceFactoryCancellation): R

    fun discard(input: I, result: R)
}

/**
 * Only the private physical composition uses this path; clocks/probes and generic inputs cannot
 * enter it. The callback must leave its capture scope before waiting for the terminal resource phase.
 */
internal interface PersistenceOwnedFactoryOperations : PersistenceFactoryOperations<PersistencePhysicalRecord, PersistenceJdbcCandidate> {
    fun awaitFailedCreationRetirement(input: PersistencePhysicalRecord)
}

internal enum class PersistenceOwnedFactoryStart {
    STARTED,
    CONTENDED,
    ALREADY_CLAIMED,
    CLOSED,
    FAILED,
}

/** A request, not Thread interruption, native cancellation or permission to release capacity. */
internal sealed interface PersistenceFactoryCancellation {
    fun isRequested(): Boolean
}

/** Detached job observation only; deliberately no wait, listener, resource or completion API. */
internal sealed interface PersistenceFactoryReceipt {
    fun state(): PersistenceFactoryProcessing
}

internal enum class PersistenceFactoryProcessing {
    PENDING,
    PROCESSING_ENDED,
    PROCESSING_ENDED_UNRESOLVED,
}

internal sealed interface PersistenceFactoryResult<out R : Any> {
    class Refused(val reason: PersistenceFactoryFailure) : PersistenceFactoryResult<Nothing> {
        override fun toString(): String = "PersistenceFactoryResult.Refused(${reason.name})"
    }

    class Failed(val reason: PersistenceFactoryFailure, val receipt: PersistenceFactoryReceipt) : PersistenceFactoryResult<Nothing> {
        override fun toString(): String = "PersistenceFactoryResult.Failed(${reason.name})"
    }

    class Success<R : Any>(val value: R, val receipt: PersistenceFactoryReceipt) : PersistenceFactoryResult<R> {
        override fun toString(): String = "PersistenceFactoryResult.Success(redacted)"
    }
}

internal enum class PersistenceFactoryFailure {
    NOT_READY,
    BUSY,
    CLOSED,
    BROKEN,
    TIMEOUT,
    INTERRUPTED,
    CREATE_FAILED,
    COORDINATION_FAILED,
}

internal enum class PersistenceFactoryGeneration {
    NEW,
    STARTING,
    WAITING,
    ACTIVE,
    SEALED,
    BROKEN,
}

internal enum class PersistenceFactoryStart {
    STARTED,
    ALREADY_CLAIMED,
    CLOSED,
    FAILED,
}

internal enum class PersistenceFactoryObservation {
    READY,
    TERMINATED,
    TIMEOUT,
    BUSY,
    CLOSED,
    NOT_SEALED,
    FAILED,
}

internal sealed interface PersistenceFactorySnapshot {
    data object Unavailable : PersistenceFactorySnapshot

    class Available(
        val generation: PersistenceFactoryGeneration,
        val startInProgress: Boolean,
        val attemptRetained: Boolean,
        val resultRetained: Boolean,
        val cancellationRequested: Boolean,
        val callerAttached: Boolean,
        val workerSettled: Boolean,
    ) : PersistenceFactorySnapshot {
        override fun toString(): String = "PersistenceFactorySnapshot.Available(${generation.name})"
    }
}

/** Scheduling only, outside the lock. No setting, plugin, replacement Thread or completion authority. */
internal fun interface PersistenceFactorySchedulingProbe {
    fun reached(point: PersistenceFactoryProbePoint)
}

internal enum class PersistenceFactoryProbePoint {
    START_CLAIMED,
    THREAD_RETAINED,
    SEAL_CLAIMED,
    WORKER_ENTERED,
    BEFORE_WORK,
    RESULT_RETAINED,
    OFFER_PUBLISHED,
    SETTLING,
    OBSERVER_ENTERED,
}

internal object NoPersistenceFactoryProbe : PersistenceFactorySchedulingProbe {
    override fun reached(point: PersistenceFactoryProbePoint) = Unit
}

/** A zero here is a rejected observation, never an argument to wait/join. */
internal fun persistenceFactoryRemainingMillis(budget: PersistenceTimeBudget): Long = try {
    budget.remainingMillis(Long.MAX_VALUE)
} catch (failure: PersistenceBoundaryException) {
    if (failure.code != PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED) throw failure
    0
}

internal fun requirePersistenceFactoryWorkerNotInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Factory worker interrupted.")
}

internal fun persistenceFactoryUnexpectedReason(failure: Throwable): PersistenceFactoryFailure =
    if (failure is InterruptedException || Thread.currentThread().isInterrupted) {
        PersistenceFactoryFailure.INTERRUPTED
    } else {
        PersistenceFactoryFailure.COORDINATION_FAILED
    }

/** Outside the rendezvous lock; never examines or renders a supplied Throwable graph. */
internal fun finishPersistenceFactoryFailure(failure: Throwable) {
    if (failure is InterruptedException) Thread.currentThread().interrupt()
    if (failure is Error) throw failure
}
