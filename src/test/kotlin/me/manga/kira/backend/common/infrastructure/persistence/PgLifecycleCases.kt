package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal object PgLifecycleCases {
    fun verify(mode: PgLifecycleCase) {
        when (mode) {
            PgLifecycleCase.INERT_SHUTDOWN -> inert()

            PgLifecycleCase.ORDINARY, PgLifecycleCase.DELETION, PgLifecycleCase.BOTH_PARTICIPANTS,
            PgLifecycleCase.SLOT_REUSE, PgLifecycleCase.ORIGINAL_PROVIDER, PgLifecycleCase.VIRTUAL_CANDIDATE,
            -> successful(mode)

            PgLifecycleCase.ORIGINAL_FAILURE_RETRY, PgLifecycleCase.TRACKED_FAILURE_RETRY, PgLifecycleCase.PARTIAL_PROTOCOL,
            PgLifecycleCase.LATE_ORIGINAL, PgLifecycleCase.TRACKED_TIMEOUT,
            -> failure(mode)

            PgLifecycleCase.DELETION_UNSUPPORTED -> unavailableDeletion()

            PgLifecycleCase.ORDINARY_DURING_TIMER_CAPTURE, PgLifecycleCase.HELD_TIMER_BOUNDARIES,
            PgLifecycleCase.QUEUED_AND_CANCELED_BOUNDARIES, PgLifecycleCase.OBSERVER_TIMER_MONITOR_TIMEOUT,
            PgLifecycleCase.MODEL_TIMER_THREAD_FAILURE,
            -> PgLifecycleTimerCases.verify(mode)

            PgLifecycleCase.OBSERVER_REFUSALS, PgLifecycleCase.OBSERVER_INTERRUPTED -> PgLifecycleObserverCases.verify(mode)

            PgLifecycleCase.MODEL_OBSERVER_ACTOR_MONITOR_INTERRUPTED -> PgLifecycleManagedObserverCases.verify()

            PgLifecycleCase.MODEL_WEAK_ABORT_FAILURE, PgLifecycleCase.MODEL_WEAK_CLOSE_FAILURE,
            PgLifecycleCase.MODEL_STRONG_ABORT_FAILURE, PgLifecycleCase.MODEL_STRONG_CLOSE_FAILURE,
            PgLifecycleCase.MODEL_STRONG_ABORT_FATAL, PgLifecycleCase.MODEL_STRONG_CLOSE_FATAL,
            -> PgLifecycleTerminalFaultCases.verify(mode)

            PgLifecycleCase.MODEL_STRONG_AUX_CLOSE_FAILURE -> PgLifecycleAuxiliaryFaultCases.verify()

            PgLifecycleCase.BENIGN_PROVIDER_SUCCESS, PgLifecycleCase.BENIGN_PROVIDER_NO_RAW -> PgLifecycleProviderCases.verify(mode)

            PgLifecycleCase.MODEL_CLAIM_CONTENTION, PgLifecycleCase.MODEL_PRIMARY_PREPARE_CONTENTION,
            PgLifecycleCase.MODEL_PRIMARY_INSTALL_CONTENTION,
            -> PgLifecycleContentionCases.verify(mode)

            PgLifecycleCase.MODEL_CLAIM_EXPIRED, PgLifecycleCase.MODEL_CLAIM_SEALED, PgLifecycleCase.MODEL_CLAIM_INTERRUPTED,
            PgLifecycleCase.MODEL_PRIMARY_PREPARE_EXPIRED, PgLifecycleCase.MODEL_PRIMARY_PREPARE_SEALED, PgLifecycleCase.MODEL_PRIMARY_PREPARE_INTERRUPTED,
            PgLifecycleCase.MODEL_PRIMARY_INSTALL_EXPIRED, PgLifecycleCase.MODEL_PRIMARY_INSTALL_SEALED, PgLifecycleCase.MODEL_PRIMARY_INSTALL_INTERRUPTED,
            -> PgLifecycleInvalidatedOpeningCases.verify(mode)

            PgLifecycleCase.DELETION_PROGRESS_DEADLINE, PgLifecycleCase.ORDINARY_PROGRESS_CONTROL -> PgLifecycleDeadlineCases.verify(mode)

            PgLifecycleCase.MODEL_FINAL_RECLAIM_CONTENTION, PgLifecycleCase.MODEL_FAILED_SCOPE_FINAL_SCAN,
            PgLifecycleCase.MODEL_EMPTY_FINAL_SCAN,
            -> PgLifecycleShutdownScanCases.verify(mode)

            PgLifecycleCase.TLS_VERIFY_FULL_ORDINARY, PgLifecycleCase.TLS_VERIFY_FULL_DELETION,
            PgLifecycleCase.TLS_WRONG_CA_ORDINARY, PgLifecycleCase.TLS_WRONG_CA_DELETION,
            PgLifecycleCase.TLS_WRONG_HOST_ORDINARY, PgLifecycleCase.TLS_WRONG_HOST_DELETION,
            PgLifecycleCase.TLS_PARTIAL_HANDSHAKE_ORDINARY, PgLifecycleCase.TLS_PARTIAL_HANDSHAKE_DELETION,
            -> PgLifecycleTlsCases.verify(mode)

            else -> PgLifecycleModelCases.verify(mode)
        }
    }

    private fun inert() {
        PgLifecycleTestScope(pgProbeEndpoint(1)).use { scope ->
            check(scope.actors().all { it.startPhase() === PersistenceThreadStartPhase.NEW && !it.thread.isAlive })
            check(!scope.root.retainedDriver.isFinished())
            check(scope.owner.observeOrdinaryPreparation() === PersistenceLifecycleObservation.NOT_REQUESTED)
            check(scope.owner.observeDeletionPreparation() === PersistenceLifecycleObservation.NOT_REQUESTED)
            check(scope.owner.observeShutdown() === PersistenceLifecycleObservation.NOT_REQUESTED)
            check(scope.owner.requestOrdinary() is PersistenceFactoryResult.Refused)
            check(scope.owner.requestDeletion() is PersistenceFactoryResult.Refused)
            check(scope.owner.requestShutdown())
            check(!scope.owner.requestShutdown())
            check(scope.owner.start() === PersistenceLifecycleActivation.CLOSED)
            check(scope.owner.prepareDeletion() === PersistenceLifecycleActivation.CLOSED)
            check(!scope.root.retainedDriver.isFinished())
            check(scope.actors().all { it.termination() === PersistenceThreadTermination.INERT })
        }
    }

    private fun successful(mode: PgLifecycleCase) {
        val count = when (mode) {
            PgLifecycleCase.SLOT_REUSE -> 3
            PgLifecycleCase.BOTH_PARTICIPANTS -> 2
            else -> 1
        }
        PgLifecyclePeer(count).use { peer ->
            peer.start()
            val extras = if (mode === PgLifecycleCase.ORIGINAL_PROVIDER) mapOf("socketFactoryArg" to "synthetic-ignored") else emptyMap()
            PgLifecycleTestScope(pgProbeEndpoint(peer.port, extras)).use { scope ->
                scope.start()
                check(scope.owner.start() === PersistenceLifecycleActivation.ALREADY_CLAIMED)
                check(scope.owner.requestDeletion() is PersistenceFactoryResult.Refused)
                check(scope.owner.observeDeletionPreparation() === PersistenceLifecycleObservation.NOT_REQUESTED)
                check(!scope.owner.snapshot().deletionRequested)
                val deleting = mode === PgLifecycleCase.DELETION
                if (deleting || mode === PgLifecycleCase.BOTH_PARTICIPANTS) scope.prepareDeletion()
                if (mode === PgLifecycleCase.VIRTUAL_CANDIDATE) {
                    val task = FutureTask { scope.request() }
                    val thread = Thread.ofVirtual().unstarted(task)
                    try {
                        thread.start()
                        scope.retire(task.get(8, TimeUnit.SECONDS))
                    } finally {
                        awaitLifecycleFact { !thread.isAlive }
                    }
                } else {
                    repeat(count) { index ->
                        val deletion = deleting || (mode === PgLifecycleCase.BOTH_PARTICIPANTS && index == 1)
                        val result = scope.request(deletion)
                        val entry = scope.retire(result, deletion)
                        check(entry.driverOpening != null && entry.openingFacts.driverEntered.get() && entry.openingFacts.driverEnded.get())
                        check(entry.raw.get() != null && entry.terminalWork?.closeState() === PersistenceTerminalCall.RETURNED)
                        val driver = lifecycleField(requireNotNull(entry.driverOpening), "driver")
                        check(driver === scope.root.retainedDriver.forOpening()) { "Participants did not share the retained guarded Driver." }
                        val expected =
                            if (extras.isEmpty()) PersistenceTerminalDisposition.TRACKED_DISPOSED else PersistenceTerminalDisposition.DRIVER_CLOSE_RETURNED
                        check(entry.terminalWork?.disposition() === expected)
                        check(
                            entry.policy.route === if (deletion) PersistenceDriverTransportRoute.APPROVED_DIRECT else PersistenceDriverTransportRoute.ORDINARY,
                        )
                        // Old aliases remain monotone and cannot retire a successor occupying the same slot.
                        check(!result.value.requestRetirement())
                    }
                }
                peer.verify()
            }
        }
    }

    private fun failure(mode: PgLifecycleCase) {
        val peerMode = when (mode) {
            PgLifecycleCase.PARTIAL_PROTOCOL -> PgLifecyclePeerMode.PARTIAL
            PgLifecycleCase.LATE_ORIGINAL -> PgLifecyclePeerMode.HOLD_ORIGINAL
            PgLifecycleCase.TRACKED_TIMEOUT -> PgLifecyclePeerMode.BLOCK_TRACKED
            else -> PgLifecyclePeerMode.REFUSE
        }
        val repeats = if (peerMode === PgLifecyclePeerMode.REFUSE) 2 else 1
        PgLifecyclePeer(repeats, peerMode).use { peer ->
            peer.start()
            val original = mode === PgLifecycleCase.ORIGINAL_FAILURE_RETRY || mode === PgLifecycleCase.LATE_ORIGINAL
            val extras = if (original) mapOf("socketFactoryArg" to "synthetic-ignored") else emptyMap()
            val endpoint = pgProbeEndpoint(peer.port, extras)
            val bounded = if (mode === PgLifecycleCase.LATE_ORIGINAL || mode === PgLifecycleCase.TRACKED_TIMEOUT) {
                ResolvedPersistenceEndpoint(
                    endpoint.driverProperties().stringPropertyNames().associateWith { endpoint.driverProperties().getProperty(it) },
                    PersistenceLoginPolicy.resolve(null, 750),
                )
            } else {
                endpoint
            }
            PgLifecycleTestScope(bounded).use { scope ->
                scope.start()
                repeat(repeats) {
                    val result = scope.owner.requestOrdinary()
                    check(result is PersistenceFactoryResult.Failed) { "Expected accepted failed managed creation." }
                    if (mode === PgLifecycleCase.LATE_ORIGINAL) {
                        peer.awaitStartup()
                        val retained = scope.entries().single()
                        check(retained.raw.get() == null && !retained.openingFacts.driverEnded.get())
                        check(result.receipt.state() === PersistenceFactoryProcessing.PENDING)
                        peer.releaseReady()
                        awaitLifecycleFact { retained.terminalWork?.bodyExited() == true }
                        check(retained.raw.get() != null && retained.openingFacts.driverEnded.get())
                        check(retained.terminalWork?.disposition() === PersistenceTerminalDisposition.DRIVER_CLOSE_RETURNED)
                    }
                    awaitLifecycleFact { scope.entries().isEmpty() && result.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED }
                }
                peer.verify()
                check(scope.owner.snapshot().weakEvidenceUsed == original)
            }
        }
    }

    private fun unavailableDeletion() {
        PgLifecyclePeer().use { peer ->
            peer.start()
            PgLifecycleTestScope(pgProbeEndpoint(peer.port, mapOf("socketFactoryArg" to "synthetic-original-only"))).use { scope ->
                scope.start()
                check(scope.owner.prepareDeletion() === PersistenceLifecycleActivation.STARTED)
                check(scope.owner.observeDeletionPreparation() === PersistenceLifecycleObservation.UNAVAILABLE)
                check(scope.owner.requestDeletion() is PersistenceFactoryResult.Refused)
                check(scope.owner.prepareDeletion() === PersistenceLifecycleActivation.ALREADY_CLAIMED)
                check(scope.owner.snapshot().ordinaryReady)
                scope.retire(scope.request())
                peer.verify()
            }
        }
    }
}
