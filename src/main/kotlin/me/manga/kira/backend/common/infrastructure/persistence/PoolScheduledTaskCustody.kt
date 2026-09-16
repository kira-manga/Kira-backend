package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Private adapter for the two Runnable scheduling methods used by pinned Hikari. The supplied
 * executor, its Workers/afterExecute, policies and unrelated tasks remain entirely externally owned.
 * Only our registrations, actual callback extents and scheduling/cancellation calls are retained.
 * All state uses the pool admission monitor; no external method runs under that monitor or F/G/T.
 */
internal class PoolScheduledTaskCustody(
    private val owner: PoolLifecycle,
    private val actors: PoolActorCustody,
    private val gate: Any,
    private val executor: ScheduledExecutorService,
    maximumPoolSize: Int,
) : AbstractExecutorService(),
    ScheduledExecutorService {
    private val capacity = retentionCapacity(maximumPoolSize)
    private val tasks = LinkedHashSet<Registration>()
    private var sealed = false
    private var publications = 0L
    private var invocations = 0L
    private var cancellations = 0L
    private var retired = 0L

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
        submitOwned(command, periodic = false) { executor.schedule(it, delay, unit) }

    override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
        submitOwned(command, periodic = true) { executor.scheduleWithFixedDelay(it, initialDelay, delay, unit) }

    private inline fun submitOwned(command: Runnable, periodic: Boolean, submit: (Runnable) -> ScheduledFuture<*>): ScheduledFuture<*> {
        requireOutsideOwnershipLocks()
        var prepared = false
        val registration = try {
            synchronized(gate) {
                val creator = owner.creatorCompletionLocked()
                if (creator == null) refuseLocked(PoolActorFault.UNAUTHENTICATED_CREATION)
                if (sealed) refuseLocked(PoolActorFault.CREATION_AFTER_SEAL)
                if (tasks.size.toLong() >= capacity) refuseLocked(PoolActorFault.CAPACITY_EXHAUSTED)
                val next = Math.addExact(publications, 1L)
                Registration(this, command, periodic, creator).also {
                    tasks.add(it) // Reserve BEFORE the external scheduler can publish, run, retain or throw.
                    publications = next
                }
            }.also { prepared = true }
        } finally {
            // Errors after partial preparation must seal custody too; propagate the original failure.
            if (!prepared) synchronized(gate) { failLocked(PoolActorFault.BOOKKEEPING_FAILED) }
        }
        var published = false
        try {
            val future = submit(registration)
            // Retain the returned handle before allocating/exposing the caller's wrapper.
            synchronized(gate) { registration.future = future }
            val result = OwnedFuture(registration, future)
            published = true
            return result
        } finally {
            synchronized(gate) {
                if (!published) {
                    registration.uncertain = true // A throwing scheduler may already have retained/run it.
                    failLocked(PoolActorFault.SCHEDULING_FAILED)
                }
                registration.publicationEnded = true
                check(publications > 0L)
                publications--
                retireLocked(registration)
            }
        }
    }

    internal fun sealLocked() {
        check(Thread.holdsLock(gate))
        sealed = true // Not the observing ThreadFactory seal: admitted tasks/close still need closers.
    }

    internal fun publicationPendingLocked(): Boolean {
        check(Thread.holdsLock(gate))
        return publications != 0L
    }

    internal fun closedPopulationReadyLocked(): Boolean {
        check(Thread.holdsLock(gate))
        return sealed && publications == 0L && invocations == 0L && cancellations == 0L
    }

    internal fun actualCreatorLocked(): PoolCreatorCompletion? {
        check(Thread.holdsLock(gate))
        val actual = taskFrame.get() ?: return null
        if (actual.owner !== this || actual.thread !== Thread.currentThread() || actual.completion.hasEnded()) return null
        return actual.completion.takeIf { actual.registration.active === actual && tasks.contains(actual.registration) }
    }

    internal fun snapshotLocked(): PoolScheduledTaskSnapshot {
        check(Thread.holdsLock(gate))
        return PoolScheduledTaskSnapshot(capacity, tasks.size, publications, invocations, cancellations, retired, sealed)
    }

    /**
     * Invoked only by the ONE authentic pool-close frame, including after partial Hikari construction.
     * Close admission first seals submissions and waits nonblockingly for admitted publications to
     * end, so no late future can escape this pass. An earlier ambiguous cancel is never retried.
     */
    internal fun cancelOwnedTasks() {
        requireOutsideOwnershipLocks()
        val retained = synchronized(gate) {
            check(sealed && publications == 0L)
            check(PoolCallFrames.current()?.kind === PoolCallKind.SHUTDOWN && owner.creatorCompletionLocked() != null)
            tasks.toList()
        }
        var failure: Throwable? = null
        for (registration in retained) {
            val future = synchronized(gate) {
                if (registration.custody !== this || registration.cancelAttempted) {
                    null
                } else {
                    registration.future?.also {
                        beginCancelLocked(registration)
                    }
                }
            } ?: continue
            runCatching { invokeCancel(registration, future, mayInterruptIfRunning = false) }.onFailure { problem ->
                if (failure == null) {
                    failure = problem
                } else if (failure !== problem) {
                    failure!!.addSuppressed(problem)
                }
            }
        }
        failure?.let { throw it }
    }

    private fun cancel(registration: Registration, future: ScheduledFuture<*>, mayInterruptIfRunning: Boolean): Boolean {
        requireOutsideOwnershipLocks()
        val counted = synchronized(gate) {
            if (registration.custody !== this) {
                false // Already detached after actual exit/disposal; this wrapper carries no pool graph.
            } else {
                if (registration.cancelUncertain) refuseLocked(PoolActorFault.CANCELLATION_FAILED)
                beginCancelLocked(registration)
                true
            }
        }
        return if (counted) invokeCancel(registration, future, mayInterruptIfRunning) else future.cancel(mayInterruptIfRunning)
    }

    private fun beginCancelLocked(registration: Registration) {
        check(Thread.holdsLock(gate))
        val all = Math.addExact(cancellations, 1L)
        val local = Math.addExact(registration.cancelling, 1L)
        registration.cancelAttempted = true
        registration.cancelling = local
        cancellations = all
    }

    private fun invokeCancel(registration: Registration, future: ScheduledFuture<*>, mayInterruptIfRunning: Boolean): Boolean {
        var returned = false
        var cancelled = false
        try {
            cancelled = future.cancel(mayInterruptIfRunning)
            returned = true
            return cancelled
        } finally {
            synchronized(gate) {
                if (cancelled) {
                    registration.entrySealed = true
                }
                if (!returned) {
                    registration.cancelUncertain = true
                    registration.uncertain = true
                    failLocked(PoolActorFault.CANCELLATION_FAILED)
                }
                check(registration.cancelling > 0L && cancellations > 0L)
                registration.cancelling--
                cancellations--
                // Concurrent cancel(false) may lose to a successful cancel, or to actual one-shot
                // entry/exit. A false result by itself proves neither future-entry closure nor exit.
                if (registration.cancelling == 0L && !registration.entrySealed) {
                    registration.cancelUncertain = true
                    registration.uncertain = true
                    failLocked(PoolActorFault.CANCELLATION_FAILED)
                }
                retireLocked(registration)
            }
        }
    }

    private fun runTask(registration: Registration) {
        requireOutsideOwnershipLocks()
        // Do not clear, copy or reinterpret an existing lease/phase/foreign pool credential.
        val conflicting = PoolCallFrames.current() != null || PoolActorCustody.currentThreadOwnsActorFrame() ||
            PersistenceJdbcDispatch.current() != null || PersistencePhaseOwnership.current() != null
        var prepared = false
        val invocation = try {
            synchronized(gate) { prepareInvocationLocked(registration, conflicting) }.also { prepared = true }
        } finally {
            // A detached/sealed no-op returns normally; every abnormal preparation exit retains the failure.
            if (!prepared) {
                synchronized(gate) {
                    registration.uncertain = true
                    registration.entrySealed = true
                    failLocked(PoolActorFault.BOOKKEEPING_FAILED)
                }
            }
        } ?: return
        var installed = false
        var returned = false
        var restored = false
        try {
            check(taskFrame.get() == null)
            taskFrame.set(invocation)
            installed = true
            invocation.command.run() // Including the supplied callback's entire finally, not Future state.
            returned = true
        } finally {
            try {
                if (!returned) {
                    synchronized(gate) {
                        registration.uncertain = true
                        registration.entrySealed = true
                        failLocked(if (installed) PoolActorFault.TASK_FAILED else PoolActorFault.BOOKKEEPING_FAILED)
                    }
                }
            } finally {
                try {
                    val actual = taskFrame.get()
                    if (actual === invocation) taskFrame.remove() else check(!installed && actual == null)
                    restored = true
                } finally {
                    synchronized(gate) {
                        if (!restored) {
                            registration.uncertain = true
                            failLocked(PoolActorFault.BOOKKEEPING_FAILED)
                            // The exact tail remains retained/counted, never completed by cancellation.
                        } else {
                            check(registration.active === invocation && invocations > 0L)
                            invocation.completion.finish(invocation.issuance)
                            registration.active = null
                            invocations--
                            retireLocked(registration)
                        }
                    }
                }
            }
        }
    }

    private fun prepareInvocationLocked(registration: Registration, conflicting: Boolean): Invocation? {
        check(Thread.holdsLock(gate))
        if (registration.custody !== this || sealed || registration.entrySealed) return null
        if (conflicting) refuseLocked(PoolActorFault.UNAUTHENTICATED_ENTRY)
        if (registration.active != null) refuseLocked(PoolActorFault.DUPLICATE_ENTRY)
        val next = Math.addExact(invocations, 1L)
        return Invocation(this, registration, checkNotNull(registration.command)).also {
            registration.active = it
            if (!registration.periodic) registration.entrySealed = true
            invocations = next
        }
    }

    private fun retireLocked(registration: Registration) {
        check(Thread.holdsLock(gate))
        if (!registration.publicationEnded || registration.active != null || registration.cancelling != 0L) return
        if (!registration.entrySealed || registration.uncertain) return
        if (!tasks.remove(registration)) return
        registration.command = null
        registration.creator = null
        registration.future = null
        registration.custody = null // Queued cancelled Runnable wrappers no longer retain the old pool.
        if (retired < Long.MAX_VALUE) retired++
    }

    private fun requireOutsideOwnershipLocks() {
        if (owner.ownershipLockHeld()) synchronized(gate) { refuseLocked(PoolActorFault.UNAUTHENTICATED_ENTRY) }
        check(!Thread.holdsLock(gate))
    }

    private fun failLocked(fault: PoolActorFault) {
        check(Thread.holdsLock(gate))
        sealed = true
        actors.recordScheduledIncidentLocked(fault)
    }

    private fun refuseLocked(fault: PoolActorFault): Nothing {
        failLocked(fault)
        throw RejectedExecutionException("Private persistence scheduled task refused.")
    }

    private fun unsupported(): Nothing {
        synchronized(gate) { failLocked(PoolActorFault.UNSUPPORTED_PROFILE) }
        throw UnsupportedOperationException("Private persistence scheduler operation unavailable.")
    }

    // This private adapter is not a second executor service. In particular, none of these may stop,
    // await or query the shared executor, or silently submit work without registration custody.
    override fun execute(command: Runnable): Unit = unsupported()
    override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> = unsupported()
    override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> = unsupported()
    override fun shutdown(): Unit = unsupported()
    override fun shutdownNow(): MutableList<Runnable> = unsupported()
    override fun isShutdown(): Boolean = unsupported()
    override fun isTerminated(): Boolean = unsupported()
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = unsupported()

    private class Registration(
        @Volatile var custody: PoolScheduledTaskCustody?,
        var command: Runnable?,
        val periodic: Boolean,
        var creator: PoolCreatorCompletion?,
    ) : Runnable {
        var future: ScheduledFuture<*>? = null
        var publicationEnded = false
        var active: Invocation? = null
        var entrySealed = false
        var cancelAttempted = false
        var cancelUncertain = false
        var cancelling = 0L
        var uncertain = false

        override fun run() {
            custody?.runTask(this)
        }
        override fun toString(): String = "PoolScheduledRegistration(redacted)"
    }

    private class Invocation(val owner: PoolScheduledTaskCustody, val registration: Registration, val command: Runnable) {
        val thread = Thread.currentThread()
        val issuance = Any()
        val completion = PoolCreatorCompletion.prepare(issuance)
    }

    private class OwnedFuture(private val registration: Registration, private val delegate: ScheduledFuture<*>) : ScheduledFuture<Any?> {
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean =
            registration.custody?.cancel(registration, delegate, mayInterruptIfRunning) ?: delegate.cancel(mayInterruptIfRunning)

        override fun isCancelled(): Boolean = delegate.isCancelled
        override fun isDone(): Boolean = delegate.isDone
        override fun get(): Any? = delegate.get()
        override fun get(timeout: Long, unit: TimeUnit): Any? = delegate.get(timeout, unit)
        override fun getDelay(unit: TimeUnit): Long = delegate.getDelay(unit)
        override fun compareTo(other: Delayed): Int = delegate.compareTo(if (other is OwnedFuture) other.delegate else other)
        override fun toString(): String = "PoolScheduledFuture(redacted)"
    }

    companion object {
        private val taskFrame = ThreadLocal<Invocation?>()

        internal fun currentThreadOwnsTaskFrame(): Boolean = taskFrame.get() != null

        private fun retentionCapacity(maximumPoolSize: Int): Long {
            require(maximumPoolSize > 0)
            // Hikari normally retains HouseKeeper + at most EOL/keepalive/leak per entry (1+3P).
            // Four such populations allow replacement, publication/cancel, and actual-exit overlap.
            // This bounds *unretired custody*, not total task throughput or arbitrary blocked-tail
            // progress. Long arithmetic and lazy cells impose no extra maximumPoolSize product cap.
            return Math.multiplyExact(4L, Math.addExact(1L, Math.multiplyExact(3L, maximumPoolSize.toLong())))
        }
    }
}

/** Scalars only; no external executor, future, Runnable or authority escape. */
internal data class PoolScheduledTaskSnapshot(
    val capacity: Long,
    val retained: Int,
    val publishing: Long,
    val activeInvocations: Long,
    val activeCancellations: Long,
    val retired: Long,
    val sealed: Boolean,
)
