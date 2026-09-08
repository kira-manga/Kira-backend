package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Real Driver progress differentiates the deletion caller deadline from socket-idle expiry and ordinary policy. */
internal object PgLifecycleDeadlineCases {
    fun verify(mode: PgLifecycleCase) {
        val deletion = mode === PgLifecycleCase.DELETION_PROGRESS_DEADLINE
        check(deletion || mode === PgLifecycleCase.ORDINARY_PROGRESS_CONTROL)
        val peerMode = if (deletion) PgLifecyclePeerMode.PROGRESS_TIMEOUT else PgLifecyclePeerMode.PROGRESS_READY
        PgLifecyclePeer(mode = peerMode).use { peer ->
            peer.start()
            PgLifecycleTestScope(pgProbeEndpoint(peer.port)).use { scope ->
                check(scope.root.endpoint.loginPolicy.durationMillis == 6_000L)
                scope.start()
                if (deletion) scope.prepareDeletion()
                val started = System.nanoTime()
                val request = FutureTask { if (deletion) scope.owner.requestDeletion() else scope.owner.requestOrdinary() }
                val caller = Thread.ofPlatform().name("synthetic-progress-caller").inheritInheritableThreadLocals(false).unstarted(request)
                try {
                    caller.start()
                    try {
                        peer.awaitStartup()
                    } catch (failure: IllegalStateException) {
                        // Safe sealed result text only; do not infer a startup/admission cause from a missing peer receipt.
                        if (request.isDone) println("PG_LIFECYCLE_PROGRESS_EARLY_RESULT deletion=$deletion result=${request.get(1, TimeUnit.SECONDS)}")
                        throw failure
                    }
                    val entry = scope.entries(deletion).single()
                    val control = requireNotNull(entry.control)
                    val expectedPolicy = if (deletion) {
                        PersistenceDriverAttemptPolicy.TRACKED_DELETION_CONJUNCTION
                    } else {
                        PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION
                    }
                    check(entry.policy === expectedPolicy && entry.raw.get() == null && !entry.openingFacts.driverEnded.get())
                    val expectedMillis = if (deletion) 2_000L else 6_000L
                    check(requireNotNull(entry.driverOpening).loginPolicy.durationMillis == expectedMillis)
                    check(lifecycleField(control.budget, "allowanceNanos") == expectedMillis * 1_000_000L)
                    check(entry.attempt?.budget === control.budget)
                    val result = request.get(8, TimeUnit.SECONDS)
                    val elapsedMillis = (System.nanoTime() - started) / 1_000_000L
                    if (deletion) verifyDeadline(scope, peer, result, entry, elapsedMillis) else verifyOrdinary(scope, peer, result, elapsedMillis)
                    peer.verify()
                    println("PG_LIFECYCLE_PROGRESS deletion=$deletion original_allowance_ms=$expectedMillis elapsed_ms=$elapsedMillis")
                } finally {
                    if (caller.isAlive) scope.owner.requestShutdown()
                    awaitLifecycleFact { !caller.isAlive }
                }
            }
        }
    }

    private fun verifyDeadline(
        scope: PgLifecycleTestScope,
        peer: PgLifecyclePeer,
        result: PersistenceFactoryResult<PersistenceJdbcCandidate>,
        entry: PersistencePhysicalEntry,
        elapsedMillis: Long,
    ) {
        check(result is PersistenceFactoryResult.Failed && result.reason === PersistenceFactoryFailure.TIMEOUT)
        check(elapsedMillis in 1_900..4_000)
        check(peer.progressWrites.get() >= 3)
        check(System.nanoTime() - peer.lastProgressNanos.get() < 1_500_000_000L)
        check(peer.maximumProgressGapNanos.get() in 1..1_500_000_000L)
        awaitLifecycleFact { scope.entries(deletion = true).isEmpty() }
        check(entry.raw.get() == null && entry.openingFacts.driverEnded.get() && entry.scopeEnded)
        check(result.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED)
        check(entry.terminalWork?.disposition() === PersistenceTerminalDisposition.TRACKED_DISPOSED)
        check(entry.terminalWork?.closeState() === PersistenceTerminalCall.NOT_INVOKED)
    }

    private fun verifyOrdinary(
        scope: PgLifecycleTestScope,
        peer: PgLifecyclePeer,
        result: PersistenceFactoryResult<PersistenceJdbcCandidate>,
        elapsedMillis: Long,
    ) {
        check(result is PersistenceFactoryResult.Success)
        check(elapsedMillis in 2_400..5_500 && peer.progressWrites.get() == 6)
        check(peer.maximumProgressGapNanos.get() in 1..1_500_000_000L)
        val entry = scope.retire(result)
        check(entry.raw.get() != null && entry.terminalWork?.disposition() === PersistenceTerminalDisposition.TRACKED_DISPOSED)
    }
}
