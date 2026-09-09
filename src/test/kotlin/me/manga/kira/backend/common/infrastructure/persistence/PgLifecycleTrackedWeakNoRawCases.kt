package me.manga.kira.backend.common.infrastructure.persistence

/** D09/§8.1's tracked-weak actual no-raw row, plus separately labeled known-T MODEL conjunct controls. */
internal object PgLifecycleTrackedWeakNoRawCases {
    fun verify(mode: PgLifecycleCase) {
        val count = if (mode === PgLifecycleCase.TRACKED_WEAK_NO_RAW_REUSE) 2 else 1
        PgLifecycleTrackedWeakNoRawFixture(mode, count).use { fixture ->
            fixture.start()
            when (mode) {
                PgLifecycleCase.TRACKED_WEAK_NO_RAW_REUSE -> reuse(fixture)
                PgLifecycleCase.MODEL_TRACKED_WEAK_NO_RAW_PENDING_CALL -> pending(fixture)
                PgLifecycleCase.MODEL_TRACKED_WEAK_NO_RAW_FAILED_CONSTRUCTION -> failedEnded(fixture)
                else -> error("Unexpected tracked-weak no-raw mode.")
            }
            fixture.peer.verify()
        }
    }

    private fun reuse(fixture: PgLifecycleTrackedWeakNoRawFixture) {
        val first = fixture.startAttempt(0)
        fixture.peer.releaseRefusal(0)
        first.requireOriginalFailure()
        first.awaitReclaimed()
        fixture.capture.requirePending(fixture.scope)
        first.emit("REAL_DRIVER_PROTOCOL_PEER", retained = false)

        val second = fixture.startAttempt(1)
        second.requireFreshAfter(first)
        fixture.peer.releaseRefusal(1)
        second.requireOriginalFailure()
        second.awaitReclaimed()
        fixture.capture.requirePending(fixture.scope)
        second.requireSameRunner(first)
        second.emit("REAL_DRIVER_PROTOCOL_PEER", retained = false)
        println(
            "PG_LIFECYCLE_TRACKED_WEAK_NO_RAW_REUSE attempts=2 fresh_record_control_receipt_primary=true fixed_runner=true " +
                "sticky_weak=true unproved_provider=true proof=REAL_COMPOSITION",
        )

        fixture.capture.releaseAndRequireReady(fixture.scope)
        listOf(first, second).forEach { retained ->
            retained.requireOriginalFailure()
            retained.requireWeakEvidence()
            retained.requireMembership(retained = false)
        }
        println("PG_LIFECYCLE_TRACKED_WEAK_NO_RAW_LATER_READY attempts=2 fixed_weak=true no_upgrade=true sticky_weak=true unproved_provider=true")
    }

    private fun pending(fixture: PgLifecycleTrackedWeakNoRawFixture) {
        val retained = fixture.startAttempt(0)
        val model = fixture.pendingCall(retained)
        fixture.peer.releaseRefusal(0)
        retained.requireOriginalFailure()
        retained.requirePendingCall(model.call, fixture.pendingWitness)
        fixture.capture.requirePending(fixture.scope)
        // Completes this exact inert token once. The real PRIMARY's constructor/first close/outer receipts are untouched.
        model.release()
        retained.awaitReclaimed()
        retained.emit("MODEL_CALL_REAL_TERMINAL", retained = false)
        fixture.capture.releaseAndRequireReady(fixture.scope)
        retained.requireWeakEvidence()
        retained.requireMembership(retained = false)
        println("PG_LIFECYCLE_TRACKED_WEAK_NO_RAW_LATER_READY attempts=1 fixed_weak=true no_upgrade=true sticky_weak=true unproved_provider=true")
    }

    private fun failedEnded(fixture: PgLifecycleTrackedWeakNoRawFixture) {
        val retained = fixture.startAttempt(0)
        val model = fixture.failedConstruction(retained)
        fixture.peer.releaseRefusal(0)
        retained.requireOriginalFailure()
        retained.awaitFailedRetained()
        model.requireKnownFailure(fenced = true)
        fixture.capture.requirePending(fixture.scope)
        retained.emit("MODEL_CONSTRUCTOR_REAL_TERMINAL", retained = true)
        println(
            "PG_LIFECYCLE_TRACKED_WEAK_NO_RAW_FAILED_CONSTRUCTION aux=MODEL_NO_NATIVE_ALLOCATION constructor=THREW outer_ended=MODEL " +
                "first_close=NOT_STARTED resource=UNKNOWN_ENDED processing=PROCESSING_ENDED body_exited=true retained=true",
        )

        fixture.capture.releaseAndRequireReady(fixture.scope)
        retained.awaitFailedRetained()
        model.requireKnownFailure(fenced = true)
        retained.requireStableNoRaw()
        println("PG_LIFECYCLE_TRACKED_WEAK_NO_RAW_LATER_READY attempts=1 fixed_weak=true no_upgrade=true retained=true resource=UNKNOWN_ENDED")
    }
}
