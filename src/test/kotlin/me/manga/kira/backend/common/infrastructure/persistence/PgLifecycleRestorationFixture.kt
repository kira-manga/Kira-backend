package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.locks.LockSupport

/** All caller/peer/foreign-pin ownership survives partial setup. Close attempts do not stop at the first failure. */
internal class PgLifecycleRestorationFixture(private val deletion: Boolean) : AutoCloseable {
    val lane = if (deletion) "DELETION" else "ORDINARY"
    val peer = PgLifecycleRestorationPeer()
    private val foreign = PgLifecycleTimerFixture()
    private var acquired = false
    private var retainedScope: PgLifecycleTestScope? = null
    private var retainedCaller: PgLifecycleRestorationCaller? = null
    private var actors = emptyList<PersistenceRetainedPlatformThread>()
    private val cleanupProblems = mutableListOf<Throwable>()
    val scope: PgLifecycleTestScope get() = requireNotNull(retainedScope)
    val caller: PgLifecycleRestorationCaller get() = requireNotNull(retainedCaller)

    fun start(): PgLifecycleRestorationTimer {
        foreign.acquire() // Explicit independent pin, acquired before the managed root can obtain its shared Timer.
        acquired = true
        peer.start()
        retainedScope = PgLifecycleTestScope(pgProbeEndpoint(peer.port))
        actors = scope.actors() // Exact root wrappers and Threads retained before ANY root actor starts.
        retainedCaller = PgLifecycleRestorationCaller(scope.owner, deletion)
        check(actors.all { it.startPhase() === PersistenceThreadStartPhase.NEW && !it.thread.isAlive })
        scope.start()
        if (deletion) scope.prepareDeletion()
        awaitLifecycleFact { scope.binding(deletion).isOwnedReceiverReady() }
        return PgLifecycleRestorationTimer(foreign, scope) // Real foreign task starts only AFTER genuine capture/readiness.
    }

    fun requireUnchangedActiveRoot() {
        val now = scope.actors()
        check(now.size == actors.size && now.zip(actors).all { (observed, original) -> observed === original })
        check(!scope.root.shutdown.get() && !scope.owner.snapshot().weakEvidenceUsed && !scope.owner.snapshot().cleanupFailureObserved)
        check(actors.filter { it.startPhase() === PersistenceThreadStartPhase.RETURNED }.all { it.thread.isAlive && !it.hasBodyEnded() })
    }

    override fun close() {
        cleanup { retainedCaller?.releaseGates() }
        cleanup { foreign.releaseHolds() }
        cleanup { retainedScope?.owner?.requestShutdown() }
        cleanup { if (acquired) foreign.releaseReference() } // Before any root/Timer shutdown wait, never a foreign release by the root.
        cleanup { peer.close() }
        cleanup { retainedCaller?.close() }
        cleanup { retainedScope?.close() }
        cleanup { closeActorsIndependently() } // Also attempted if scope.close rejected its earlier observation.
        cleanup { foreign.close() }
        cleanup { retainedScope?.let { check(it.root.shutdownObservation() === PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED) } }
        val failure = cleanupProblems.firstOrNull()
        if (failure != null) {
            cleanupProblems.drop(1).forEach { if (it !== failure) failure.addSuppressed(it) }
            throw failure
        }
        println("PG_LIFECYCLE_RESTORATION_FIXTURE_CLEANUP lane=$lane all_terminated=true forced=false")
    }

    private fun closeActorsIndependently() {
        val allowance = PersistenceTimeBudget.start(8_000) // One fixture cleanup allowance, not an acceptance-budget replacement.
        actors.forEach { actor ->
            cleanup {
                while (!actor.termination().ended() || actor.thread.isAlive) {
                    LockSupport.parkNanos(allowance.remainingMillis(1) * 1_000_000)
                }
            }
        }
        cleanup {
            check(actors.all { it.termination().ended() && !it.thread.isAlive })
            val terminated = actors.count { it.termination() === PersistenceThreadTermination.TERMINATED }
            val inert = actors.count { it.termination() === PersistenceThreadTermination.INERT }
            println("PG_LIFECYCLE_RESTORATION_ACTORS_CLEANUP retained=${actors.size} terminated=$terminated inert=$inert all_ended=true")
        }
    }

    private inline fun cleanup(action: () -> Unit) {
        runCatching(action).exceptionOrNull()?.let { cleanupProblems.add(it) }
    }
}
