package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.TimeUnit

internal object PgLifecycleShutdownScanCases {
    fun verify(mode: PgLifecycleCase) {
        when (mode) {
            PgLifecycleCase.MODEL_FINAL_RECLAIM_CONTENTION -> contendedFinalReclaim()
            PgLifecycleCase.MODEL_FAILED_SCOPE_FINAL_SCAN -> failedEndedScope()
            PgLifecycleCase.MODEL_EMPTY_FINAL_SCAN -> emptyFinalPass()
            else -> error("Unknown final-scanner scenario.")
        }
    }

    private fun contendedFinalReclaim() {
        PgLifecyclePeer().use { peer ->
            peer.start()
            PgLifecycleTestScope(pgProbeEndpoint(peer.port), capacity = 2).use { scope ->
                val scanner = lifecycleField(scope.root, "scanner") as PersistenceRetainedPlatformThread
                val gate = PgLifecycleReclaimLock(scope.binding(), scanner.thread)
                installLifecycleModelLock(scope, gate)
                try {
                    scope.start()
                    val result = scope.request()
                    val entry = scope.entries().single()
                    gate.entry.set(entry)
                    check(scope.owner.requestShutdown())
                    check(gate.beforeReclaim.await(8, TimeUnit.SECONDS))
                    gate.lock()
                    try {
                        gate.allowReclaim.countDown()
                        check(gate.reclaimAttemptEnded.await(8, TimeUnit.SECONDS))
                        check(gate.realReclaimMiss.get())
                    } finally {
                        gate.unlock()
                    }
                    check(gate.beforeRecords.await(8, TimeUnit.SECONDS))
                    // G is free, scanner is paused outside F/G/T; real controllers can drain every participant actor.
                    awaitLifecycleFact { scope.root.ordinary.threadsEnded() && scope.root.deletion.threadsEnded() }
                    check(entry.terminalWork?.bodyExited() == true && result.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED)
                    check(scope.entries().single() === entry)
                    gate.allowRecords.countDown()
                    check(scope.owner.observeShutdown() === PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED)
                    check(scanner.termination() === PersistenceThreadTermination.TERMINATED && scope.entries().isEmpty())
                    check(gate.finalPassAcquisitions.get() == 2)
                    peer.verify()
                    println("PG_LIFECYCLE_FINAL_SCAN real_reclaim_miss=true actors_ended_before_final_pass=true proof=OWN_PROJECT_MODEL_REAL_ACTORS")
                } finally {
                    gate.releaseGates()
                }
            }
        }
    }

    private fun failedEndedScope() {
        PgLifecyclePeer().use { peer ->
            peer.start()
            PgLifecycleTestScope(pgProbeEndpoint(peer.port)).use { scope ->
                scope.expectedUnknown = true
                val binding = scope.binding()
                val factory = lifecycleField(lifecycleField(scope.root.ordinary, "worker") as PersistenceFactoryWorker<*, *>, "ownedThread")
                    as PersistenceRetainedPlatformThread
                val gate = PgLifecycleScopeFailureLock(binding, factory.thread)
                installLifecycleModelLock(scope, gate)
                scope.start()
                val result = scope.owner.requestOrdinary()
                check(result is PersistenceFactoryResult.Failed)
                val entry = scope.entries().single()
                awaitLifecycleFact {
                    entry.terminalWork?.bodyExited() == true && factory.termination() === PersistenceThreadTermination.TERMINATED &&
                        result.receipt.state() !== PersistenceFactoryProcessing.PENDING
                }
                check(gate.associationRemoved.get() && gate.factoryInterrupted.get())
                check(entry.raw.get() != null && entry.openingFacts.scopeCallEnded.get() && !entry.scopeEnded)
                check(pgScopePhase(requireNotNull(entry.driverScope)).get() === PersistencePgScopePhase.REMOVAL_FAILED)
                check(result.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED_UNRESOLVED)
                check(entry.attempt?.workerSettled == true && entry.attempt?.unresolved == true)
                val work = requireNotNull(entry.terminalWork)
                check(work.disposition() === PersistenceTerminalDisposition.UNKNOWN_ENDED && work.closeState() === PersistenceTerminalCall.RETURNED)
                check(work.acknowledgedBoundary() != null)
                check(scope.owner.requestShutdown())
                check(scope.owner.observeShutdown() === PersistenceLifecycleObservation.UNKNOWN)
                check(scope.actors().all { it.termination().ended() && !it.thread.isAlive })
                check(scope.entries().single() === entry && binding.rendezvous.current === entry.attempt)
                peer.verify()
                println("PG_LIFECYCLE_FAILED_SCOPE retained_unknown=true actual_actors_ended=true proof=MODEL_ASSOCIATION_LOSS_REAL_LEAVE_AND_INTERRUPT")
            }
        }
    }

    private fun emptyFinalPass() {
        PgLifecycleTestScope(pgProbeEndpoint(1), capacity = 3).use { scope ->
            val scanner = lifecycleField(scope.root, "scanner") as PersistenceRetainedPlatformThread
            val ordinary = PgLifecycleEmptyScanLock(scanner.thread)
            val deletion = PgLifecycleEmptyScanLock(scanner.thread)
            installLifecycleModelLock(scope, ordinary)
            installLifecycleModelLock(scope, deletion, deletion = true)
            scope.start()
            check(!scope.owner.snapshot().deletionRequested && scope.entries().isEmpty() && scope.entries(deletion = true).isEmpty())
            check(scope.owner.requestShutdown())
            check(scope.owner.observeShutdown() === PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED)
            check(scope.root.ordinary.threadsEnded() && scope.root.deletion.threadsEnded())
            check(scanner.termination() === PersistenceThreadTermination.TERMINATED)
            check(ordinary.finalPassAcquisitions.get() == 3 && deletion.finalPassAcquisitions.get() == 4)
            println("PG_LIFECYCLE_EMPTY_FINAL_SCAN ordinary_slots=3 cold_deletion_slots=4 proof=REAL_ACQUISITIONS_MODEL_OBSERVER")
        }
    }
}
