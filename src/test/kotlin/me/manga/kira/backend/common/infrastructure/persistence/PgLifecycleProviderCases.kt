package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Executes the real configured provider; settings rejection alone cannot satisfy this compatibility check. */
internal object PgLifecycleProviderCases {
    fun verify(mode: PgLifecycleCase) {
        val refusing = mode === PgLifecycleCase.BENIGN_PROVIDER_NO_RAW
        val peerMode = if (refusing) PgLifecyclePeerMode.HOLD_REFUSE else PgLifecyclePeerMode.SUCCESS
        try {
            PgLifecyclePeer(2, peerMode).use { peer ->
                peer.start()
                val extras = mapOf("socketFactory" to PgLifecycleBenignSocketFactory::class.java.name)
                PgLifecycleTestScope(pgProbeEndpoint(peer.port, extras)).use { scope ->
                    scope.start()
                    check(scope.owner.prepareDeletion() === PersistenceLifecycleActivation.STARTED)
                    check(scope.owner.observeDeletionPreparation() === PersistenceLifecycleObservation.UNAVAILABLE)
                    check(scope.owner.requestDeletion() is PersistenceFactoryResult.Refused)
                    repeat(2) { ordinal ->
                        if (refusing) refused(scope, peer, ordinal) else successful(scope)
                        PgLifecycleBenignSocketFactory.verifyDisposed(ordinal + 1)
                        check(scope.binding().completion.weakEvidence.get())
                        check(scope.binding().completion.unprovedProvider.get() == refusing)
                    }
                    peer.verify()
                    check(scope.owner.snapshot().weakEvidenceUsed && !scope.owner.snapshot().cleanupFailureObserved)
                    println("PG_LIFECYCLE_CUSTOM_PROVIDER refusal=$refusing calls=2 sticky_weak=true unproved=$refusing proof=REAL_DRIVER_REAL_PROVIDER")
                }
            }
        } finally {
            PgLifecycleBenignSocketFactory.cleanup() // Never used as evidence that the terminal/driver disposed a socket.
        }
    }

    private fun successful(scope: PgLifecycleTestScope) {
        val result = scope.request()
        val entry = scope.entries().single()
        check(entry.policy === PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER && entry.transports == null)
        scope.retire(result)
        check(entry.terminalWork?.disposition() === PersistenceTerminalDisposition.DRIVER_CLOSE_RETURNED)
    }

    private fun refused(scope: PgLifecycleTestScope, peer: PgLifecyclePeer, ordinal: Int) {
        awaitLifecycleFact { scope.binding().isOwnedReceiverReady() }
        val request = FutureTask { scope.owner.requestOrdinary() }
        val caller = Thread.ofPlatform().name("synthetic-custom-provider-caller").inheritInheritableThreadLocals(false).unstarted(request)
        try {
            caller.start()
            peer.awaitRefusal(ordinal)
            val entry = scope.entries().single()
            check(entry.policy === PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER && entry.transports == null)
            check(entry.raw.get() == null && entry.openingFacts.driverEntered.get() && !entry.openingFacts.driverEnded.get())
            peer.releaseRefusal(ordinal)
            val result = request.get(8, TimeUnit.SECONDS)
            check(result is PersistenceFactoryResult.Failed && result.reason === PersistenceFactoryFailure.CREATE_FAILED)
            awaitLifecycleFact { scope.entries().isEmpty() && entry.terminalWork?.bodyExited() == true }
            check(entry.raw.get() == null && entry.scopeEnded && entry.openingFacts.driverEnded.get())
            check(entry.terminalWork?.disposition() === PersistenceTerminalDisposition.NO_RAW_DRIVER_RETURN_ONLY)
            check(entry.terminalWork?.closeState() === PersistenceTerminalCall.NOT_INVOKED)
            check(result.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED)
        } finally {
            peer.releaseRefusal(ordinal)
            if (caller.isAlive) scope.owner.requestShutdown()
            awaitLifecycleFact { !caller.isAlive }
        }
    }
}
