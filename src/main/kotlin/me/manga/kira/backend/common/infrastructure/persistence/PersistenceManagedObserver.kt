package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.locks.LockSupport

/** C6 observers never activate actors, enter a target monitor, join, or perform resource work. */
internal object PersistenceManagedObserver {
    fun observe(root: PersistenceJdbcDriverRoot, target: PersistenceManagedObservation): PersistenceLifecycleObservation {
        val budget = PersistenceTimeBudget.start(10_000) // Before any caller classification/metadata lookup.
        val caller = Thread.currentThread()
        if (!trusted(caller)) return PersistenceLifecycleObservation.UNSUPPORTED_OBSERVER
        var interrupted = false
        try {
            while (true) {
                if (Thread.interrupted()) interrupted = true
                if (persistenceFactoryRemainingMillis(budget) == 0L) return PersistenceLifecycleObservation.PENDING
                val observed = when (target) {
                    PersistenceManagedObservation.ORDINARY -> root.preparationObservation(deleting = false)
                    PersistenceManagedObservation.DELETION -> root.preparationObservation(deleting = true)
                    PersistenceManagedObservation.SHUTDOWN -> root.shutdownObservation()
                }
                if (persistenceFactoryRemainingMillis(budget) == 0L) return PersistenceLifecycleObservation.PENDING
                if (observed !== PersistenceLifecycleObservation.PENDING) return observed
                val remaining = persistenceFactoryRemainingMillis(budget)
                if (remaining == 0L) return PersistenceLifecycleObservation.PENDING
                LockSupport.parkNanos(minOf(remaining, 10) * 1_000_000)
            }
        } finally {
            if (interrupted) caller.interrupt() // Only the proved base platform method, outside every owner lock.
        }
    }

    private fun trusted(caller: Thread): Boolean = runCatching {
        !caller.isVirtual && caller.javaClass.getMethod("isInterrupted").declaringClass === Thread::class.java &&
            caller.javaClass.getMethod("interrupt").declaringClass === Thread::class.java
    }.getOrElse { failure ->
        if (failure is Error) throw failure
        false
    }
}

internal enum class PersistenceManagedObservation {
    ORDINARY,
    DELETION,
    SHUTDOWN,
}

/** These callers are exact owned platform actors, not arbitrary application Threads. */
internal fun persistenceLifecyclePark() {
    requirePersistenceFactoryWorkerNotInterrupted()
    LockSupport.parkNanos(1_000_000)
    requirePersistenceFactoryWorkerNotInterrupted()
}

internal fun PersistenceThreadTermination.ended(): Boolean = this === PersistenceThreadTermination.INERT || this === PersistenceThreadTermination.TERMINATED
