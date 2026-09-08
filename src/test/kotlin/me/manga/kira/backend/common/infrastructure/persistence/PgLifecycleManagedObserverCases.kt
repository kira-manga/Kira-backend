package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** A real owned actor holds its own public Thread monitor; only the scheduling hook is MODEL. */
internal object PgLifecycleManagedObserverCases {
    fun verify() {
        PgLifecycleTestScope(pgProbeEndpoint(1)).use { scope ->
            val actor = lifecycleField(scope.root, "scanner") as PersistenceRetainedPlatformThread
            PgLifecycleScannerGate(scope.binding(), actor.thread, ownMonitor = true).use { gate ->
                installLifecycleModelLock(scope, gate)
                scope.start()
                gate.arm()
                check(actor.thread.isAlive && !actor.hasBodyEnded())
                check(scope.owner.requestShutdown())
                val began = CountDownLatch(1)
                val task = FutureTask {
                    val started = System.nanoTime()
                    began.countDown()
                    val result = scope.owner.observeShutdown()
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    check(result === PersistenceLifecycleObservation.PENDING && elapsed in 9_000..15_000)
                    check(Thread.interrupted()) { "Managed wait did not restore the trusted observer's real interruption." }
                    elapsed
                }
                val observer = Thread.ofPlatform().name("synthetic-managed-observer").inheritInheritableThreadLocals(false).unstarted(task)
                try {
                    observer.start()
                    check(began.await(5, TimeUnit.SECONDS))
                    awaitLifecycleFact { observer.state === Thread.State.TIMED_WAITING }
                    observer.interrupt()
                    val elapsed = task.get(16, TimeUnit.SECONDS)
                    check(!gate.returned.get() && actor.thread.isAlive && !actor.hasBodyEnded())
                    println("PG_LIFECYCLE_OWNED_MONITOR elapsed_ms=$elapsed interrupted_restored=true proof=REAL_THREAD_MONITOR_MODEL_SCHEDULING")
                } finally {
                    gate.proceed.countDown()
                    awaitLifecycleFact { !observer.isAlive }
                }
            }
        }
    }
}
