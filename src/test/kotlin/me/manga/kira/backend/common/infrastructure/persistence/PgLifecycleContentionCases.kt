package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.TimeUnit

/** Explicit own-project lock interception; real F1, Driver and root. No fabricated readiness or resource completion. */
internal object PgLifecycleContentionCases {
    fun verify(mode: PgLifecycleCase) {
        val stage = when (mode) {
            PgLifecycleCase.MODEL_CLAIM_CONTENTION -> 0
            PgLifecycleCase.MODEL_PRIMARY_PREPARE_CONTENTION -> 1
            PgLifecycleCase.MODEL_PRIMARY_INSTALL_CONTENTION -> 2
            else -> error("Unknown managed contention gate.")
        }
        PgLifecyclePeer().use { peer ->
            peer.start()
            PgLifecycleTestScope(pgProbeEndpoint(peer.port)).use { scope ->
                val binding = scope.binding()
                val gate = PgLifecycleContentionLock.install(scope, stage)
                scope.start()
                val control = PersistenceOwnedCallerControl.prepare(6_000)
                val opening = requireNotNull(scope.root.ordinary.selectOpening())
                val entry = requireNotNull(binding.reserve(control, opening.policy, opening))
                gate.entry.set(entry)
                try {
                    check(binding.admit(entry))
                    check(gate.entered.await(4, TimeUnit.SECONDS))
                    gate.lock()
                    try {
                        gate.proceed.countDown()
                        awaitLifecycleFact { gate.hasQueuedThread(gate.target) || gate.attemptEnded.get() }
                        check(gate.hasQueuedThread(gate.target) && !gate.attemptEnded.get()) {
                            "Managed opening incorrectly abandoned an admitted invocation on transient G contention at stage=$stage."
                        }
                        check(entry.raw.get() == null && control.receipt.state() === PersistenceFactoryProcessing.PENDING)
                    } finally {
                        gate.unlock()
                    }
                    awaitLifecycleFact { binding.offered(entry) != null || control.failureResult() != null }
                    check(control.failureResult() == null)
                    val result = PersistenceFactoryResult.Success(entry.candidate, control.receipt)
                    awaitLifecycleFact { binding.take(entry, result) || control.failureResult() != null }
                    check(control.state() === PersistenceOwnedCallerDisposition.TAKEN)
                    awaitLifecycleFact { control.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED }
                    scope.retire(result)
                    peer.verify()
                    println("PG_LIFECYCLE_G_CONTENTION stage=$stage same_attempt=true proof=OWN_PROJECT_MODEL_REAL_ACTORS_DRIVER")
                } finally {
                    gate.proceed.countDown()
                    control.fail(PersistenceFactoryFailure.CLOSED)
                }
            }
        }
    }
}
