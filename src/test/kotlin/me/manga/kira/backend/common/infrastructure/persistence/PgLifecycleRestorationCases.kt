package me.manga.kira.backend.common.infrastructure.persistence

/** Actual managed retirement while the same hostile caller's restoration remains entered, unreleased and unreturned. */
internal object PgLifecycleRestorationCases {
    fun verify(deletion: Boolean) {
        PgLifecycleRestorationFixture(deletion).use { fixture ->
            val timer = fixture.start()
            val caller = fixture.caller
            caller.launch()
            fixture.peer.awaitStartup()
            val exact = PgLifecycleRestorationAttempt.capture(fixture.scope, deletion)
            caller.holdNextSample(exact)
            awaitLifecycleFact {
                caller.requireSampleHeld()
                exact.attachedAndActive().also { caller.requireSampleHeld() }
            }
            timer.requireRunning()
            println(
                "PG_LIFECYCLE_RESTORATION_SAMPLE lane=${fixture.lane} caller=ATTACHED processing=PENDING " +
                    "driver_active=true caller_held=true outside_fgt=true proof=REAL_MANAGED",
            )
            caller.injectActualFlagAndReleaseSample()
            caller.awaitRestorationHeld()
            awaitHeld(caller) { exact.abandonedAndPending() }
            timer.requireRunning()
            val originalFailure = exact.originalInterruption()
            caller.requireRestorationHeld()
            println(
                "PG_LIFECYCLE_RESTORATION_PENDING lane=${fixture.lane} caller=ABANDONED_INTERRUPTED resource=PENDING processing=PENDING " +
                    "caller_held=true outside_fgt=true timer_work=REAL_SAME_TIMER_FIXTURE_HOLD proof=REAL_MANAGED",
            )
            awaitHeld(caller) { exact.producersDrained() }
            awaitHeld(caller) { exact.primaryDisposed() }
            awaitHeld(caller) { timer.retainScheduledBoundary(exact) }
            awaitHeld(caller) { exact.abandonedAndPending() }
            exact.requireNoJdbcCalls()
            caller.requireRestorationHeld()
            println(
                "PG_LIFECYCLE_RESTORATION_PRODUCERS lane=${fixture.lane} driver_factory_scope_ended=true primary_disposed=true " +
                    "boundary_scheduled=true boundary_ack=false resource=PENDING caller_held=true proof=REAL_MANAGED",
            )
            timer.releaseTask()
            awaitHeld(caller) { timer.returned() }
            requireRetirementWhileHeld(fixture, exact, timer)
            fixture.peer.verifyBeforeCleanup()
            caller.requireRestorationHeld()
            check(exact.originalInterruption() === originalFailure)
            caller.releaseRestorationAndRequireResult(originalFailure)
            exact.requireOriginalIdentity()
            println(
                "PG_LIFECYCLE_RESTORATION_RESULT lane=${fixture.lane} reason=INTERRUPTED original_receipt=true " +
                    "actual_flag_restored=true returned_after_release=true proof=REAL_MANAGED",
            )
        }
    }

    private fun requireRetirementWhileHeld(fixture: PgLifecycleRestorationFixture, exact: PgLifecycleRestorationAttempt, timer: PgLifecycleRestorationTimer) {
        val caller = fixture.caller
        awaitHeld(caller) {
            val state = exact.work.disposition()
            check(state === PersistenceTerminalDisposition.PENDING || state === PersistenceTerminalDisposition.TRACKED_DISPOSED)
            state === PersistenceTerminalDisposition.TRACKED_DISPOSED
        }
        awaitHeld(caller) {
            val state = exact.receipt.state()
            check(state === PersistenceFactoryProcessing.PENDING || state === PersistenceFactoryProcessing.PROCESSING_ENDED)
            state === PersistenceFactoryProcessing.PROCESSING_ENDED
        }
        awaitHeld(caller) { exact.work.bodyExited() }
        awaitHeld(caller) { exact.reclaimed() }
        awaitHeld(caller) { exact.primaryDisposed() }
        timer.requireAcknowledged(exact)
        exact.requireNoJdbcCalls()
        fixture.requireUnchangedActiveRoot()
        caller.requireRestorationHeld()
        println(
            "PG_LIFECYCLE_RESTORATION_RETIRED lane=${fixture.lane} resource=TRACKED_DISPOSED processing=PROCESSING_ENDED " +
                "body_exited=true exact_reclaimed=true caller_held=true proof=REAL_MANAGED",
        )
    }

    private inline fun awaitHeld(caller: PgLifecycleRestorationCaller, crossinline observed: () -> Boolean) {
        awaitLifecycleFact {
            caller.requireRestorationHeld()
            observed().also { caller.requireRestorationHeld() }
        }
    }
}
