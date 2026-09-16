package me.manga.kira.backend.common.infrastructure.persistence

import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** One explicit preparation of the retained deletion pool, not another lifecycle, phase, lease or physical registry. */
internal class DeletionPoolPreparation(
    private val owner: PersistenceJdbcLifecycleOwner,
    private val pool: HikariDataSource,
    private val lifecycle: PoolLifecycle,
) {
    private val state = AtomicReference(PersistenceLifecycleObservation.NOT_REQUESTED)

    @Volatile private var initialization: PoolLifecycle.Acquisition? = null

    fun prepared(): Boolean = state.get() === PersistenceLifecycleObservation.READY && initialization?.actualFrameEnded() == true

    /** The preparation result is not a guarantee that all four connections remain idle during later application use. */
    fun observation(): PersistenceLifecycleObservation = state.get().let {
        if (it === PersistenceLifecycleObservation.READY && (!prepared() || !lifecycle.businessReady())) PersistenceLifecycleObservation.UNAVAILABLE else it
    }

    fun prepare(): PersistenceLifecycleObservation {
        val budget = PersistenceTimeBudget.start(10_000)
        requireConnectionFree()
        if (owner.ownershipLockHeld() || PoolCallFrames.current() != null || PoolActorCustody.currentThreadOwnsActorFrame()) {
            return PersistenceLifecycleObservation.UNAVAILABLE
        }
        if (!state.compareAndSet(PersistenceLifecycleObservation.NOT_REQUESTED, PersistenceLifecycleObservation.PENDING)) return observation()
        var ownsPreparation = false
        var ready = false
        try {
            // ALREADY_CLAIMED belongs to an earlier preparation, never a second pool generation or a reason to stop it.
            if (owner.prepareDeletion() !== PersistenceLifecycleActivation.STARTED) return PersistenceLifecycleObservation.UNAVAILABLE
            ownsPreparation = true
            ready = owner.observeDeletionPreparation(budget) === PersistenceLifecycleObservation.READY &&
                !Thread.currentThread().isInterrupted && initialize(budget) && awaitFourIdle(budget)
            val result = if (ready) PersistenceLifecycleObservation.READY else PersistenceLifecycleObservation.UNAVAILABLE
            state.set(result)
            return result
        } finally {
            if (!ready) {
                state.set(PersistenceLifecycleObservation.UNAVAILABLE)
                // Request only. The retained wrapper still owns its one actual close and independent observation.
                if (ownsPreparation) lifecycle.requestShutdown(budget)
            }
        }
    }

    @Suppress("SwallowedException") // An unreturned checkout may time out while the same bounded warm-up continues, never a success receipt.
    private fun initialize(budget: PersistenceTimeBudget): Boolean {
        val acquisition = lifecycle.prepareAcquisition(budget)
        initialization = acquisition // Retain even an inaccessible failed handle before entering stock lazy initialization.
        var obtained = false
        var endAttempted = false
        var initialized = false
        try {
            if (!acquisition.enter()) return false
            val handle = try {
                pool.connection
            } catch (_: SQLException) {
                // An initial 500ms checkout may expire while the retained 2s factory attempt is still active.
                // No returned handle exists. The same pool may finish filling within this preparation allowance.
                null
            }
            if (handle != null) {
                obtained = true
                if (!acquisition.capture(handle)) PersistenceJdbcGuardContext.refuse()
                // Authentic pool-frame return only: no application LeaseJdbcFacade, entitlement or phase loan.
                handle.close()
            }
            endAttempted = true
            if (!acquisition.end() || !acquisition.actualFrameEnded()) return false
            initialized = pool.isRunning && lifecycle.businessReady()
            return initialized
        } finally {
            try {
                if (!initialized) {
                    if (obtained || endAttempted) acquisition.failBeforeEnd()
                    acquisition.handoff() // Exact ticket survives; never guess raw close/eviction or retry initialization.
                }
            } finally {
                if (!endAttempted && acquisition.entered() && !acquisition.end()) lifecycle.requestShutdown(budget)
            }
        }
    }

    private fun awaitFourIdle(budget: PersistenceTimeBudget): Boolean {
        while (persistenceFactoryRemainingMillis(budget) > 0L && !Thread.currentThread().isInterrupted) {
            if (!lifecycle.businessReady()) return false
            val counts = pool.hikariPoolMXBean ?: return false
            if (owner.deletionPoolIdle(lifecycle) && counts.idleConnections == 4 && counts.totalConnections == 4) {
                if (counts.activeConnections == 0 && persistenceFactoryRemainingMillis(budget) > 0L) return true
            }
            LockSupport.parkNanos(minOf(persistenceFactoryRemainingMillis(budget), 50L) * 1_000_000)
        }
        return false
    }

    override fun toString(): String = "DeletionPoolPreparation(redacted)"
}
