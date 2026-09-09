package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.TimeUnit

internal object PgLifecycleTimerCases {
    fun verify(mode: PgLifecycleCase) {
        val pending = mode === PgLifecycleCase.ORDINARY_DURING_TIMER_CAPTURE
        val monitor = mode === PgLifecycleCase.OBSERVER_TIMER_MONITOR_TIMEOUT
        PgLifecyclePeer(if (monitor) 1 else 2).use { peer ->
            // Monitor-only scope creates no connection; bind no unused listener for that case.
            if (!monitor) peer.start()
            val foreign = PgLifecycleTimerFixture()
            val scope = PgLifecycleTestScope(pgProbeEndpoint(if (monitor) 1 else peer.port))
            var admission: PgLifecycleAdmissionScheduling? = null
            var original: Throwable? = null
            try {
                if (!monitor) {
                    val retained = PgLifecycleAdmissionScheduling(scope)
                    admission = retained // Own before fallible installation/start, including a failed pause wait.
                    installLifecycleModelLock(scope, PgLifecycleAdmissionLock(retained))
                }
                foreign.acquire()
                val earlyHold = if (pending) foreign.hold() else null
                scope.start(waitTimer = !pending)
                when {
                    pending -> pendingCapture(scope, peer, requireNotNull(earlyHold), requireNotNull(admission))
                    monitor -> monitorTimeout(scope, foreign)
                    mode === PgLifecycleCase.MODEL_TIMER_THREAD_FAILURE -> deadTimer(scope, peer, foreign, requireNotNull(admission))
                    else -> boundaries(scope, peer, foreign, mode === PgLifecycleCase.QUEUED_AND_CANCELED_BOUNDARIES, requireNotNull(admission))
                }
            } catch (failure: Throwable) {
                original = failure
                throw failure
            } finally {
                closeFixture(scope, foreign, admission, original)
            }
        }
    }

    private fun closeFixture(scope: PgLifecycleTestScope, foreign: PgLifecycleTimerFixture, admission: PgLifecycleAdmissionScheduling?, original: Throwable?) {
        val returned = runCatching { admission?.close() } // Release/acknowledge before root waits, without skipping independent cleanup.
        val holds = runCatching { foreign.releaseHolds() }
        val shutdown = runCatching { scope.owner.requestShutdown() }
        val released = runCatching { foreign.releaseReference() }
        val closed = runCatching { scope.close() }
        val timer = runCatching { foreign.close() }
        val failures = listOf(returned, holds, shutdown, released, closed, timer).mapNotNull { it.exceptionOrNull() }
        (original ?: failures.firstOrNull())?.let { failure ->
            failures.forEach { if (it !== failure) failure.addSuppressed(it) }
            if (original == null) throw failure // A body failure remains primary; cleanup-only failure is still fatal.
        }
    }

    private fun pendingCapture(
        scope: PgLifecycleTestScope,
        peer: PgLifecyclePeer,
        hold: PgLifecycleTimerFixture.Hold,
        admission: PgLifecycleAdmissionScheduling,
    ) {
        check(!scope.owner.snapshot().timerReady)
        val weak = admission.during("mode=ORDINARY_DURING_TIMER_CAPTURE ordinal=0") { scope.request() }
        val entry = scope.entries().single()
        check(entry.policy === PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONTRACT)
        check(scope.owner.prepareDeletion() === PersistenceLifecycleActivation.STARTED)
        check(scope.owner.requestDeletion() is PersistenceFactoryResult.Refused)
        val retired = scope.retire(weak)
        check(retired.terminalWork?.disposition() === PersistenceTerminalDisposition.DRIVER_CLOSE_RETURNED)
        hold.unblock.countDown()
        awaitLifecycleFact { scope.owner.snapshot().timerReady && scope.owner.snapshot().deletionReady }
        check(retired.policy === PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONTRACT)
        val deletion = admission.during("mode=ORDINARY_DURING_TIMER_CAPTURE ordinal=1") { scope.request(deletion = true) }
        scope.retire(deletion, deletion = true)
        check(scope.owner.snapshot().weakEvidenceUsed)
        peer.verify()
    }

    private fun boundaries(
        scope: PgLifecycleTestScope,
        peer: PgLifecyclePeer,
        foreign: PgLifecycleTimerFixture,
        queued: Boolean,
        admission: PgLifecycleAdmissionScheduling,
    ) {
        scope.prepareDeletion()
        val ordinary = admission.during("fixture=TIMER_BOUNDARIES queued=$queued ordinal=0") { scope.request() }
        val deletion = admission.during("fixture=TIMER_BOUNDARIES queued=$queued ordinal=1") { scope.request(deletion = true) }
        val ordinaryEntry = scope.entries().single()
        val deletionEntry = scope.entries(deletion = true).single()
        val entries = listOf(ordinaryEntry, deletionEntry)
        val hold = foreign.hold()
        val run = if (queued) foreign.queued(cancel = false) else null
        val canceled = if (queued) foreign.queued(cancel = true) else null
        check(ordinary.value.requestRetirement() && deletion.value.requestRetirement())
        awaitLifecycleFact { entries.all { it.terminalWork?.closeState() === PersistenceTerminalCall.RETURNED } }
        check(entries.all { it.terminalWork?.disposition() === PersistenceTerminalDisposition.PENDING && !it.terminalWork.bodyExited() })
        awaitLifecycleFact {
            retainedExactly(scope.binding(), ordinaryEntry) && retainedExactly(scope.binding(deletion = true), deletionEntry)
        }
        check(!hold.returned.get())
        hold.unblock.countDown()
        awaitLifecycleFact { scope.entries().isEmpty() && scope.entries(deletion = true).isEmpty() }
        if (run != null) awaitLifecycleFact { run.get() }
        check(canceled?.get() != true)
        check(
            entries.all {
                it.terminalWork?.acknowledgedBoundary() != null && it.terminalWork.disposition() === PersistenceTerminalDisposition.TRACKED_DISPOSED
            },
        )
        peer.verify()
    }

    /** Contention is pending; a conclusive count/identity mismatch fails immediately, never retries. */
    private fun retainedExactly(binding: PersistencePhysicalFactoryBinding, expected: PersistencePhysicalEntry): Boolean {
        if (!binding.ledger.lock.tryLock()) return false
        return try {
            check(binding.ledger.entries.filterNotNull().singleOrNull() === expected) { "Expected the exact entry to remain retained while the Timer is held." }
            true
        } finally {
            binding.ledger.lock.unlock()
        }
    }

    private fun deadTimer(scope: PgLifecycleTestScope, peer: PgLifecyclePeer, foreign: PgLifecycleTimerFixture, admission: PgLifecycleAdmissionScheduling) {
        val first = admission.during("mode=MODEL_TIMER_THREAD_FAILURE ordinal=0") { scope.request() }
        scope.prepareDeletion()
        val second = admission.during("mode=MODEL_TIMER_THREAD_FAILURE ordinal=1") { scope.request(deletion = true) }
        val entries = scope.entries() + scope.entries(deletion = true)
        val hold = foreign.hold()
        val dying = foreign.killThreadForModel() // Explicit MODEL interference; earliest deadline, ahead of later record boundaries.
        first.value.requestRetirement()
        second.value.requestRetirement()
        awaitLifecycleFact { entries.all { it.terminalWork?.closeState() === PersistenceTerminalCall.RETURNED } }
        check(entries.all { it.terminalWork?.acknowledgedBoundary() == null })
        hold.unblock.countDown()
        check(dying.await(5, TimeUnit.SECONDS)) // Task entry alone is not completed Timer-thread death.
        awaitLifecycleFact { !foreign.capturedThread().isAlive && entries.all { it.terminalWork?.bodyExited() == true } }
        check(entries.all { it.terminalWork?.disposition() === PersistenceTerminalDisposition.UNKNOWN_ENDED && it.terminalWork.acknowledgedBoundary() == null })
        scope.expectedUnknown = true // Only after actual Timer death and the unchanged strict terminal evidence.
        check(scope.entries().size == 1 && scope.entries(deletion = true).size == 1)
        peer.verify()
    }

    private fun monitorTimeout(scope: PgLifecycleTestScope, foreign: PgLifecycleTimerFixture) {
        val hold = foreign.hold(ownMonitor = true)
        scope.owner.requestShutdown()
        val began = System.nanoTime()
        check(scope.owner.observeShutdown() === PersistenceLifecycleObservation.PENDING)
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
        check(elapsed in 9_000..15_000) { "Observer did not use its one original bounded allowance." }
        check(!hold.returned.get() && foreign.capturedThread().isAlive)
        println("PG_LIFECYCLE_OBSERVER_MONITOR original_budget_ms=10000 elapsed_ms=$elapsed target_still_held=true proof=REAL_THREAD_MONITOR")
    }
}
