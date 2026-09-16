package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the exact platform caller so the test can retain the Entry at SSLRequest before any fast TLS rejection. */
internal class PgLifecycleTlsRequest(private val scope: PgLifecycleTestScope, deletion: Boolean) : AutoCloseable {
    private val task = FutureTask { if (deletion) scope.owner.requestDeletion() else scope.owner.requestOrdinary() }
    private val thread = Thread.ofPlatform().name("synthetic-lifecycle-tls-caller").inheritInheritableThreadLocals(false).unstarted(task)
    private val started = AtomicBoolean()
    private var resultObserved = false

    fun start() {
        check(started.compareAndSet(false, true))
        thread.start()
    }

    fun await(): PersistenceFactoryResult<PersistenceJdbcCandidate> {
        val result = task.get(8, TimeUnit.SECONDS)
        resultObserved = true
        return result
    }

    fun diagnostic(): String = pgLifecycleCallerDiagnostic(task, thread)

    override fun close() {
        if (!resultObserved) scope.owner.requestShutdown()
        pgLifecycleTlsJoin(listOf(thread))
        check(!thread.isAlive)
        println("PG_LIFECYCLE_TLS_CALLER_CLEANUP joined=true")
    }
}

/** Only actual, fixture-owned platform threads; the one original cleanup budget never supplies join(0). */
internal fun pgLifecycleTlsJoin(threads: List<Thread>) {
    val budget = PersistenceTimeBudget.start(6_000)
    var interrupted = Thread.interrupted()
    try {
        val attempts = threads.map { thread ->
            runCatching {
                while (thread.isAlive) {
                    try {
                        thread.join(budget.remainingMillis(100))
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            }
        }
        attempts.forEach { it.getOrThrow() }
        check(threads.none { it.isAlive })
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}
