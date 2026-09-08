package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal object PgLifecycleObserverCases {
    fun verify(mode: PgLifecycleCase) {
        PgLifecycleTestScope(pgProbeEndpoint(1)).use { scope ->
            if (mode === PgLifecycleCase.OBSERVER_INTERRUPTED) {
                try {
                    Thread.currentThread().interrupt()
                    check(scope.owner.observeOrdinaryPreparation() === PersistenceLifecycleObservation.NOT_REQUESTED)
                    check(Thread.interrupted()) { "Trusted observer failed to restore actual interruption." }
                } finally {
                    Thread.interrupted()
                }
            } else {
                virtual(scope)
                untrusted(scope)
            }
            check(scope.actors().all { it.startPhase() === PersistenceThreadStartPhase.NEW && !it.thread.isAlive })
        }
    }

    private fun virtual(scope: PgLifecycleTestScope) {
        val result = FutureTask {
            Thread.currentThread().interrupt()
            val observation = scope.owner.observeShutdown()
            check(Thread.currentThread().isInterrupted)
            observation
        }
        val thread = Thread.ofVirtual().unstarted(result)
        try {
            thread.start()
            check(result.get(3, TimeUnit.SECONDS) === PersistenceLifecycleObservation.UNSUPPORTED_OBSERVER)
        } finally {
            awaitLifecycleFact { !thread.isAlive }
        }
    }

    private fun untrusted(scope: PgLifecycleTestScope) {
        val thread = UntrustedObserver(scope.owner)
        try {
            thread.start()
            awaitLifecycleFact { !thread.isAlive }
            thread.problem.get()?.let { throw it }
            check(thread.overrideCalls.get() == 0 && thread.finished)
        } finally {
            awaitLifecycleFact { !thread.isAlive }
        }
    }

    private class UntrustedObserver(private val owner: PersistenceJdbcLifecycleOwner) : Thread(null, null, "synthetic-untrusted-observer", 0, false) {
        val overrideCalls = AtomicInteger()
        val problem = AtomicReference<Throwable?>()

        @Volatile var finished = false

        override fun run() {
            runCatching {
                super.interrupt()
                check(owner.observeShutdown() === PersistenceLifecycleObservation.UNSUPPORTED_OBSERVER)
                check(interrupted()) { "Untrusted observer's actual flag was cleared." }
                finished = true
            }.onFailure(problem::set)
        }

        override fun isInterrupted(): Boolean {
            overrideCalls.incrementAndGet()
            error("Untrusted isInterrupted must not be invoked by management observation.")
        }

        override fun interrupt() {
            overrideCalls.incrementAndGet()
            error("Untrusted interrupt must not be invoked by management observation.")
        }
    }
}
