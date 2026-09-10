package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean

/** Published by the lifecycle owner before activation. Every participant and actor exists before any start. */
internal class PersistenceJdbcDriverRoot(val endpoint: ResolvedPersistenceEndpoint, capacity: Int, val pathStyle: PersistencePathStyle) {
    val shutdown = AtomicBoolean()
    val retainedDriver = PersistenceRetainedPgDriver()
    val ordinary = PersistenceJdbcParticipant(this, capacity, deletion = false)
    val deletion = PersistenceJdbcParticipant(this, 4, deletion = true)
    val timer = PersistenceDriverTimer(this)
    private val startClaimed = AtomicBoolean()
    private val deletionClaimed = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val scanner = PersistenceRetainedPlatformThread("kira-persistence-scanner", ::scan)

    fun start(): PersistenceLifecycleActivation {
        if (shutdown.get()) return PersistenceLifecycleActivation.CLOSED
        if (!startClaimed.compareAndSet(false, true)) return PersistenceLifecycleActivation.ALREADY_CLAIMED
        return runCatching { startActors() }.getOrElse { failure ->
            failed.set(true)
            requestShutdown()
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (failure is Error) throw failure
            PersistenceLifecycleActivation.FAILED
        }
    }

    private fun startActors(): PersistenceLifecycleActivation {
        val scannerStart = scanner.start()
        if (shutdown.get() && scannerStart === PersistenceFactoryStart.CLOSED) return PersistenceLifecycleActivation.CLOSED
        check(scannerStart === PersistenceFactoryStart.STARTED)
        if (shutdown.get()) return PersistenceLifecycleActivation.CLOSED
        // Timer-only failure never changes the ordinary endpoint or installs a second controller.
        runCatching { timer.start() }.onFailure { failure ->
            if (failure is Error) throw failure
            if (failure is InterruptedException) Thread.currentThread().interrupt()
        }
        if (shutdown.get()) return PersistenceLifecycleActivation.CLOSED
        val ordinaryStart = ordinary.start()
        if (shutdown.get() && ordinaryStart === PersistenceFactoryStart.CLOSED) return PersistenceLifecycleActivation.CLOSED
        check(ordinaryStart === PersistenceFactoryStart.STARTED)
        return PersistenceLifecycleActivation.STARTED
    }

    fun prepareDeletion(): PersistenceLifecycleActivation {
        if (shutdown.get() || !startClaimed.get()) return PersistenceLifecycleActivation.CLOSED
        if (!deletionClaimed.compareAndSet(false, true)) return PersistenceLifecycleActivation.ALREADY_CLAIMED
        return runCatching {
            when (deletion.start()) {
                PersistenceFactoryStart.STARTED -> PersistenceLifecycleActivation.STARTED
                PersistenceFactoryStart.CLOSED -> PersistenceLifecycleActivation.CLOSED
                else -> PersistenceLifecycleActivation.FAILED
            }
        }.getOrElse { failure ->
            deletion.forbidStarts()
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (failure is Error) throw failure
            PersistenceLifecycleActivation.FAILED
        }
    }

    fun requestShutdown(): Boolean {
        val first = shutdown.compareAndSet(false, true) // Permanent before any other lifecycle action.
        ordinary.forbidStarts()
        deletion.forbidStarts()
        timer.forbidStart()
        scanner.forbidStart()
        return first
    }

    fun scannerReady(): Boolean = scanner.startPhase() === PersistenceThreadStartPhase.RETURNED && scanner.hasEntered() && !scanner.hasBodyEnded()

    internal fun ownershipLockHeld(): Boolean = ordinary.ownershipLockHeld() || deletion.ownershipLockHeld()

    fun canReleaseTimer(): Boolean = shutdown.get() && participantsEnded() && scanner.termination().ended()

    private fun participantsEnded(): Boolean = ordinary.recordsEnded() && deletion.recordsEnded() && ordinary.threadsEnded() && deletion.threadsEnded()

    fun preparationObservation(deleting: Boolean): PersistenceLifecycleObservation {
        if (shutdown.get()) return PersistenceLifecycleObservation.UNAVAILABLE
        if (!startClaimed.get() || (deleting && !deletionClaimed.get())) return PersistenceLifecycleObservation.NOT_REQUESTED
        val participant = if (deleting) deletion else ordinary
        return when {
            participant.isReady() -> PersistenceLifecycleObservation.READY
            participant.preparationFinished() -> PersistenceLifecycleObservation.UNAVAILABLE
            else -> PersistenceLifecycleObservation.PENDING
        }
    }

    fun shutdownObservation(): PersistenceLifecycleObservation {
        if (!shutdown.get()) return PersistenceLifecycleObservation.NOT_REQUESTED
        if (!canReleaseTimer() || !timer.threadEnded()) return PersistenceLifecycleObservation.PENDING
        val timerState = timer.shutdownObservation()
        if (timerState !== PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED) return timerState
        val retained = ordinary.retainedCount() ?: return PersistenceLifecycleObservation.PENDING
        val retainedDeletion = deletion.retainedCount() ?: return PersistenceLifecycleObservation.PENDING
        return when {
            failed.get() || ordinary.cleanupFailed() || deletion.cleanupFailed() || retained != 0 || retainedDeletion != 0 ->
                PersistenceLifecycleObservation.UNKNOWN

            ordinary.usedWeakEvidence() || deletion.usedWeakEvidence() -> PersistenceLifecycleObservation.DRIVER_CONTRACT_ONLY_ENDED

            else -> PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED
        }
    }

    fun snapshot(): PersistenceLifecycleSnapshot = PersistenceLifecycleSnapshot(
        shutdown.get(), ordinary.isReady(), deletionClaimed.get(), deletion.isReady(), timer.canAcceptStrong(),
        ordinary.retainedCount(), deletion.retainedCount(), ordinary.usedWeakEvidence() || deletion.usedWeakEvidence(),
        failed.get() || ordinary.cleanupFailed() || deletion.cleanupFailed(),
    )

    private fun scan() {
        runCatching {
            while (true) {
                ordinary.scan()
                deletion.scan()
                if (shutdown.get() && participantsEnded() && shutdownScanFinished()) return
                persistenceLifecyclePark()
            }
        }.onFailure { failure ->
            failed.set(true)
            requestShutdown()
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (failure is Error) throw failure
        }
    }

    private fun shutdownScanFinished(): Boolean = ordinary.finishShutdownScan() && deletion.finishShutdownScan()

    override fun toString(): String = "PersistenceJdbcDriverRoot(redacted)"
}
