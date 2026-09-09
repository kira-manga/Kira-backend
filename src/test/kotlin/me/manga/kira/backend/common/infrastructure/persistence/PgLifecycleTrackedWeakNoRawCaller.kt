package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Child-safe, non-overriding caller. The fixture retains this entire object before starting its sole request. */
internal class PgLifecycleTrackedWeakNoRawCaller(private val scope: PgLifecycleTestScope, val ordinal: Int) : AutoCloseable {
    private val result = FutureTask { scope.owner.requestOrdinary() }
    private val thread = Thread.ofPlatform().name("synthetic-tracked-weak-no-raw-$ordinal").inheritInheritableThreadLocals(false).unstarted(result)
    private var startClaimed = false
    private var startReturned = false
    private var closed = false

    fun start() {
        check(!startClaimed)
        startClaimed = true
        thread.start()
        startReturned = true
    }

    fun failure(): PersistenceFactoryResult.Failed {
        check(startReturned)
        val observed = result.get(8, TimeUnit.SECONDS)
        check(observed is PersistenceFactoryResult.Failed && observed.reason === PersistenceFactoryFailure.CREATE_FAILED)
        return observed
    }

    fun diagnostic(): String = pgLifecycleCallerDiagnostic(result, thread)

    override fun close() {
        if (closed) return
        val budget = PersistenceTimeBudget.start(8_000)
        while (thread.isAlive) thread.join(budget.remainingMillis(100))
        check(!thread.isAlive)
        check(!startClaimed || (startReturned && result.isDone))
        closed = true
        println("PG_LIFECYCLE_TRACKED_WEAK_NO_RAW_CALLER_CLEANUP ordinal=$ordinal start_returned=$startReturned all_terminated=true")
    }
}
