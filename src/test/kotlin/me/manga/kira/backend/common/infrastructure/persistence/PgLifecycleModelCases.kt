package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock

/** Explicit own-project MODEL cuts. These do not claim private JVM/AQS/native fault injection. */
internal object PgLifecycleModelCases {
    fun verify(mode: PgLifecycleCase) {
        when (mode) {
            PgLifecycleCase.MODEL_SCANNER_FAILURE_EMPTY, PgLifecycleCase.MODEL_CONTROLLER_FAILURE_EMPTY -> emptyActorFailure(mode)
            PgLifecycleCase.MODEL_FAILED_BEFORE_CREATE -> beforeCreate()
            PgLifecycleCase.MODEL_STRONG_FINAL_GATE -> strongFinalGate()
            else -> error("Missing integrated lifecycle scenario.")
        }
        println("PG_LIFECYCLE_MODEL mode=$mode proof=OWN_PROJECT_MODEL_REAL_ACTORS")
    }

    private fun emptyActorFailure(mode: PgLifecycleCase) {
        PgLifecycleTestScope(pgProbeEndpoint(1)).use { scope ->
            scope.expectedUnknown = true
            scope.start()
            val actor = if (mode === PgLifecycleCase.MODEL_SCANNER_FAILURE_EMPTY) {
                lifecycleField(scope.root, "scanner")
            } else {
                lifecycleField(scope.root.ordinary, "controller")
            } as PersistenceRetainedPlatformThread
            actor.thread.interrupt()
            awaitLifecycleFact { actor.termination() === PersistenceThreadTermination.TERMINATED }
            check(!scope.owner.snapshot().ordinaryReady)
            scope.owner.requestShutdown()
            awaitLifecycleFact { scope.actors().all { it.termination().ended() } }
        }
    }

    private fun beforeCreate() {
        PgLifecycleTestScope(pgProbeEndpoint(1)).use { scope ->
            scope.expectedUnknown = true
            scope.start()
            val binding = scope.binding()
            val control = PersistenceOwnedCallerControl.prepare(5_000)
            val opening = requireNotNull(scope.root.ordinary.selectOpening())
            val factory = lifecycleField(lifecycleField(scope.root.ordinary, "worker") as PersistenceFactoryWorker<*, *>, "ownedThread")
                as PersistenceRetainedPlatformThread
            val entry = binding.rendezvous.lock.withLock {
                val reserved = requireNotNull(binding.reserve(control, opening.policy, opening))
                check(binding.admit(reserved))
                factory.thread.interrupt() // F is held by this explicit model cut; create has not entered.
                check(control.fail(PersistenceFactoryFailure.INTERRUPTED))
                reserved
            }
            awaitLifecycleFact { factory.termination() === PersistenceThreadTermination.TERMINATED && entry.terminalWork?.bodyExited() == true }
            check(!entry.openingFacts.factoryEntered.get() && !entry.openingFacts.driverEntered.get())
            check(entry.terminalWork?.disposition() === PersistenceTerminalDisposition.BEFORE_DRIVER)
            check(control.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED_UNRESOLVED && scope.entries().single() === entry)
        }
    }

    private fun strongFinalGate() {
        PgLifecyclePeer().use { peer ->
            peer.start()
            PgLifecycleTestScope(pgProbeEndpoint(peer.port)).use { scope ->
                scope.expectedUnknown = true
                scope.start()
                val binding = scope.binding()
                val control = PersistenceOwnedCallerControl.prepare(6_000)
                val opening = requireNotNull(scope.root.ordinary.selectOpening())
                check(opening.policy === PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION)
                val entry = requireNotNull(binding.reserve(control, opening.policy, opening))
                try {
                    check(binding.admit(entry))
                    awaitLifecycleFact { binding.offered(entry) != null }
                    val result = PersistenceFactoryResult.Success(entry.candidate, control.receipt)
                    (lifecycleField(scope.root.timer, "failed") as AtomicBoolean).set(true) // MODEL readiness loss before final G claim.
                    check(!binding.take(entry, result))
                    check(control.state().phase === PersistenceOwnedCallerPhase.ABANDONED)
                    awaitLifecycleFact { scope.entries().isEmpty() }
                    check(entry.policy === PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION)
                    check(entry.terminalWork?.disposition() === PersistenceTerminalDisposition.TRACKED_DISPOSED)
                    peer.verify()
                } finally {
                    control.fail(PersistenceFactoryFailure.CLOSED)
                }
            }
        }
    }
}
