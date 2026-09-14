package me.manga.kira.backend.common.infrastructure.persistence

import com.zaxxer.hikari.HikariDataSource
import java.util.concurrent.ThreadFactory

/**
 * The private observing factory, not an executor or a physical-connection registry. The fixed
 * cells retain every emitted unresolved generation. Only observed actual termination frees one.
 * All admissions/publications/seals use the lifecycle's same monitor; Thread observation is outside it and F/G/T.
 */
internal class PoolActorCustody(private val owner: PoolLifecycle, private val gate: Any) {
    private val cells = arrayOfNulls<Generation>(CAPACITY)
    private var constructing = 0
    private var retired = 0L
    private var factorySealed = false
    private var firstFailure: PoolActorFault? = null
    private val factory = object : ThreadFactory {
        override fun newThread(runnable: Runnable?): Thread? = create(runnable)
    }

    internal fun installOn(pool: HikariDataSource) {
        pool.threadFactory = factory
    }

    /** These are current-value checks only. Immutable-from-launch provenance is an independent qualification prerequisite. */
    internal fun profileSupported(pool: HikariDataSource, installed: Boolean): Boolean = pool.javaClass === HikariDataSource::class.java &&
        (if (installed) pool.threadFactory === factory else pool.threadFactory == null) &&
        pool.scheduledExecutor == null && pool.metricsTrackerFactory == null && pool.metricRegistry == null &&
        pool.healthCheckRegistry == null && !pool.isRegisterMbeans && !pool.isAllowPoolSuspension &&
        pool.exceptionOverride == null && pool.exceptionOverrideClassName == null && pool.initializationFailTimeout == -1L &&
        absentOrFalse("com.zaxxer.hikari.blockUntilFilled") && absentOrFalse("com.zaxxer.hikari.enableRequestBoundaries")

    internal fun failLocked(fault: PoolActorFault) {
        check(Thread.holdsLock(gate))
        if (firstFailure == null) firstFailure = fault
        factorySealed = true // Containment, never a normal-shutdown receipt.
        owner.sealBusinessForActorFaultLocked()
    }

    /** Accounted RETURN failure denies business without revoking the existing authenticated creator closure. */
    internal fun recordCallerIncidentLocked() {
        check(Thread.holdsLock(gate))
        if (firstFailure == null) firstFailure = PoolActorFault.BOOKKEEPING_FAILED
        owner.sealBusinessForActorFaultLocked() // Never reopens an earlier hard factory seal.
    }

    internal fun failedLocked(): Boolean {
        check(Thread.holdsLock(gate))
        return firstFailure != null
    }

    internal fun actualCreatorLocked(): PoolCreatorCompletion? {
        check(Thread.holdsLock(gate))
        val actual = actorFrame.get() ?: return null
        if (actual.owner !== this || actual.thread !== Thread.currentThread() || !actual.entered) return null
        if (actual.completion.hasEnded()) return null
        return actual.completion.takeIf { cells[actual.index] === actual }
    }

    /** No join, target monitor, start, cancellation, executor query or time-based retirement. */
    internal fun observeTerminations() {
        if (owner.ownershipLockHeld()) return
        for (index in cells.indices) {
            val generation = synchronized(gate) { cells[index] } ?: continue
            val thread = generation.thread ?: continue
            if (thread === Thread.currentThread() || thread.state !== Thread.State.TERMINATED || thread.isAlive) continue
            synchronized(gate) {
                if (cells[index] === generation && generation.constructionEnded && generation.published) {
                    if (!generation.entered || !generation.completion.hasEnded()) failLocked(PoolActorFault.WORKER_FAILED)
                    // Keep monotone completion/failure summaries, but no chain of old Worker/creator objects.
                    if (retired < Long.MAX_VALUE) retired++
                    cells[index] = null
                }
            }
        }
    }

    internal fun observationLocked(): PoolActorObservation {
        check(Thread.holdsLock(gate))
        if (constructing != 0 || !owner.closedPopulationReadyLocked()) return PoolActorObservation.PENDING
        var unproved = false
        for (generation in cells) {
            if (generation == null) continue
            if (!generation.constructionEnded || generation.entered || !generation.creator.hasEnded()) return PoolActorObservation.PENDING
            // Published NEW with an ended creator is not inert, even after shutdown or an outer throw.
            unproved = true
        }
        if (unproved) return PoolActorObservation.UNPROVEN
        factorySealed = true // No current or future authentic creator remains under this same protocol.
        if (firstFailure != null) return PoolActorObservation.UNKNOWN
        return PoolActorObservation.ENDED
    }

    internal fun snapshotLocked(futureEntries: Long, activeOperations: Long): PoolActorSnapshot {
        check(Thread.holdsLock(gate))
        return PoolActorSnapshot(CAPACITY, cells.count { it != null }, constructing, retired, factorySealed, firstFailure, futureEntries, activeOperations)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun create(runnable: Runnable?): Thread? {
        if (owner.ownershipLockHeld()) return refuse(PoolActorFault.UNAUTHENTICATED_CREATION)
        val generation = try {
            observeTerminations()
            synchronized(gate) { reserveLocked(runnable) }
        } catch (failure: Throwable) {
            refuse(PoolActorFault.CONSTRUCTION_FAILED)
            throw failure
        } ?: return null
        var published = false
        try {
            val thread = Thread.ofPlatform()
                .name("kira-private-pool-actor")
                .daemon(true)
                .inheritInheritableThreadLocals(false)
                .uncaughtExceptionHandler { actual, _ ->
                    if (actual === generation.thread) refuse(PoolActorFault.WORKER_FAILED)
                }
                .unstarted { runWorker(generation) }
            synchronized(gate) {
                // A seal does not erase an already admitted construction. Retain before any return/escape.
                generation.thread = thread
                generation.published = true
                published = true
            }
            return thread
        } finally {
            synchronized(gate) {
                if (!published) {
                    failLocked(PoolActorFault.CONSTRUCTION_FAILED)
                    // Authentic completed construction, no publication and no external start entitlement.
                    check(cells[generation.index] === generation)
                    cells[generation.index] = null
                }
                generation.constructionEnded = true
                constructing--
            }
        }
    }

    private fun reserveLocked(runnable: Runnable?): Generation? {
        check(Thread.holdsLock(gate))
        val creator = owner.creatorCompletionLocked()
        if (runnable == null || creator == null) {
            failLocked(PoolActorFault.UNAUTHENTICATED_CREATION)
            return null
        }
        if (factorySealed) {
            failLocked(PoolActorFault.CREATION_AFTER_SEAL)
            return null
        }
        val index = cells.indexOfFirst { it == null }
        if (index < 0) {
            failLocked(PoolActorFault.CAPACITY_EXHAUSTED)
            return null
        }
        val generation = Generation(this, index, runnable, creator)
        cells[index] = generation // Reserve the finite cell BEFORE constructing a Thread.
        constructing++
        return generation
    }

    private fun runWorker(generation: Generation) {
        val entered = synchronized(gate) {
            when {
                Thread.currentThread() !== generation.thread -> {
                    failLocked(PoolActorFault.UNAUTHENTICATED_ENTRY)
                    false
                }

                generation.entered || cells[generation.index] !== generation -> {
                    failLocked(PoolActorFault.DUPLICATE_ENTRY)
                    false
                }

                else -> {
                    generation.entered = true
                    true
                }
            }
        }
        if (!entered) return
        var contextInstalled = false
        var returned = false
        var bookkeepingEnded = false
        try {
            check(actorFrame.get() == null) // Ordinary fresh emitted Threads have no inherited actor lineage.
            actorFrame.set(generation)
            contextInstalled = true
            // This is the COMPLETE supplied executor Worker, including processWorkerExit/replacement in its finally.
            generation.delegate.run()
            returned = true
        } finally {
            try {
                if (!contextInstalled) {
                    refuse(PoolActorFault.BOOKKEEPING_FAILED)
                } else if (!returned) {
                    refuse(PoolActorFault.WORKER_FAILED)
                }
            } finally {
                try {
                    val actual = actorFrame.get()
                    if (actual === generation) actorFrame.remove() else check(!contextInstalled && actual == null)
                    bookkeepingEnded = true
                } finally {
                    synchronized(gate) {
                        if (!bookkeepingEnded) failLocked(PoolActorFault.BOOKKEEPING_FAILED)
                        generation.completion.finish(generation.issuance)
                        // Even failed setup/restore is sticky; handler/JVM exit tails still need TERMINATED plus !isAlive.
                    }
                }
            }
        }
    }

    private fun refuse(fault: PoolActorFault): Thread? {
        synchronized(gate) { failLocked(fault) }
        return null
    }

    private fun absentOrFalse(name: String): Boolean = System.getProperty(name).let { it == null || it.equals("false", ignoreCase = true) }

    private class Generation(val owner: PoolActorCustody, val index: Int, val delegate: Runnable, val creator: PoolCreatorCompletion) {
        val issuance = Any()
        val completion = PoolCreatorCompletion.prepare(issuance)

        @Volatile
        var thread: Thread? = null
        var entered = false
        var published = false
        var constructionEnded = false
    }

    companion object {
        // Explicit finite retained-unretired custody allocation, NOT a Hikari/JDBC population or throughput theorem.
        private const val CAPACITY = 64
        private val actorFrame = ThreadLocal<Generation?>()

        internal fun currentThreadOwnsActorFrame(): Boolean = actorFrame.get() != null
    }
}

internal enum class PoolActorFault {
    UNSUPPORTED_PROFILE,
    UNAUTHENTICATED_CREATION,
    UNAUTHENTICATED_ENTRY,
    DUPLICATE_ENTRY,
    CREATION_AFTER_SEAL,
    CAPACITY_EXHAUSTED,
    CONSTRUCTION_FAILED,
    INITIALIZATION_FAILED,
    WORKER_FAILED,
    BOOKKEEPING_FAILED,
}

internal enum class PoolActorObservation {
    PENDING,
    UNPROVEN,
    UNKNOWN,
    ENDED,
}

/** Fixed diagnostics only: no Thread/Worker, lease, raw handle, authority or writable cell escape. */
internal data class PoolActorSnapshot(
    val capacity: Int,
    val retainedGenerations: Int,
    val constructing: Int,
    val retiredGenerations: Long,
    val factorySealed: Boolean,
    val firstFailure: PoolActorFault?,
    val futureLeaseEntries: Long,
    val activeOperations: Long,
)
