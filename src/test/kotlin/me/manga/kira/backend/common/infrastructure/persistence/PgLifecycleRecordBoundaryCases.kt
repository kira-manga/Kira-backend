package me.manga.kira.backend.common.infrastructure.persistence

/** Genuine record proofs plus explicitly separate bookkeeping MODEL and non-record ADAPTER controls. */
internal object PgLifecycleRecordBoundaryCases {
    fun isRecordBoundaryCase(mode: PgLifecycleCase): Boolean = PgLifecycleRecordBoundaryMode.from(mode) != null

    fun verify(mode: PgLifecycleCase) {
        val selected = requireNotNull(PgLifecycleRecordBoundaryMode.from(mode))
        PgLifecycleRecordBoundaryFixture(selected).use { fixture ->
            fixture.start()
            when (selected) {
                PgLifecycleRecordBoundaryMode.RECORD_BOUNDARY_REUSE_ORDINARY,
                PgLifecycleRecordBoundaryMode.RECORD_BOUNDARY_REUSE_DELETION,
                -> realReuseAndUnfencedControls(fixture)

                PgLifecycleRecordBoundaryMode.MODEL_RECORD_BOUNDARY_RECEIPTS_ORDINARY,
                PgLifecycleRecordBoundaryMode.MODEL_RECORD_BOUNDARY_RECEIPTS_DELETION,
                -> recordReceiptModels(fixture)

                PgLifecycleRecordBoundaryMode.MODEL_RECORD_BOUNDARY_ADAPTER_AUTHORIZATION -> adapterAuthorization(fixture)
            }
            fixture.verify()
        }
    }

    private fun realReuseAndUnfencedControls(fixture: PgLifecycleRecordBoundaryFixture) {
        val producer = fixture.ownProducer()
        val first = fixture.request()
        var liveClaim: PgLifecycleRecordBoundaryBlindClaim? = null
        awaitLifecycleFact {
            liveClaim = PgLifecycleRecordBoundaryAssertions.boundaryBlindClaim(first)
            liveClaim != null
        }
        val before = requireNotNull(liveClaim)
        check(before.exactStrongIdentity && before.realOpeningEnded && before.originalProcessingEnded)
        check(!before.retiredAndClaimed && !before.producerDrain && !before.actualJdbcEnded && !before.knownPrimaryDisposed && !before.accepted)
        println("PG_LIFECYCLE_RECORD_BOUNDARY_BLIND_CLAIM accepted=false phase=LIVE proof=MODEL_CLAIM_REAL_PREREQUISITES")
        fixture.timer.holdRunning()
        check(first.result.value.requestRetirement())
        val firstBoundary = first.awaitBoundary(fixture.timer)
        fixture.observeDisconnect(0)
        PgLifecycleRecordBoundaryAssertions.requireUnfencedClaimWhileRunning(first, firstBoundary, fixture.timer.hold)
        fixture.timer.hold.release()
        PgLifecycleRecordBoundaryAssertions.disposed(first, firstBoundary, ordinal = 1)

        // Latch release is AFTER this exact actual ACK/body exit/removal, not a timestamp or a manually written ACK.
        producer.permitAfter(firstBoundary.boundary)
        producer.awaitLaterRunning(fixture.timer.actualThread())
        val second = fixture.request()
        PgLifecycleRecordBoundaryAssertions.freshRecord(first, second)
        check(second.result.value.requestRetirement())
        val secondBoundary = second.awaitBoundary(fixture.timer)
        fixture.observeDisconnect(1)
        PgLifecycleRecordBoundaryAssertions.staleAck(firstBoundary, secondBoundary, second, producer.laterTask)
        producer.laterTask.release()
        PgLifecycleRecordBoundaryAssertions.disposed(second, secondBoundary, ordinal = 2)
    }

    private fun recordReceiptModels(fixture: PgLifecycleRecordBoundaryFixture) {
        val record = fixture.request()
        fixture.timer.holdRunning()
        check(record.result.value.requestRetirement())
        val access = record.awaitBoundary(fixture.timer)
        fixture.observeDisconnect(0)
        access.requireGenuineScheduledPending()
        access.wrongCallerSchedule()
        access.wrongThreadTask()
        PgLifecycleRecordBoundaryAssertions.requirePending(record, access)
        println("PG_LIFECYCLE_RECORD_BOUNDARY_WRONG_THREAD record_task=true cell_ack=false wrong_schedule_refused=true proof=OWN_PROJECT_MODEL_ACCESS")

        val model = fixture.ownReceiptModel(record, access)
        model.suppressEndedReceipt()
        fixture.timer.hold.release()
        awaitLifecycleFact { access.cellAcknowledged() }
        model.requireAckWithoutExit()
        PgLifecycleRecordBoundaryAssertions.requirePending(record, access)
        println("PG_LIFECYCLE_RECORD_BOUNDARY_RECEIPT_MODEL stage=exit_withheld real_schedule_returned=true real_cell_ack=true fenced=PENDING")
        model.modelFailedEndedReceipt()
        PgLifecycleRecordBoundaryAssertions.requirePending(record, access)
        println("PG_LIFECYCLE_RECORD_BOUNDARY_RECEIPT_MODEL stage=failed_ended outcome=MODEL_THREW real_cell_ack=true timer_alive=true fenced=PENDING")
        model.restore()
        PgLifecycleRecordBoundaryAssertions.disposed(record, access, ordinal = 1)
    }

    private fun adapterAuthorization(fixture: PgLifecycleRecordBoundaryFixture) {
        fixture.timer.holdRunning()
        val adapter = fixture.ownAdapter()
        adapter.startHeld()
        adapter.exerciseWrongCallerThenOneShot()
        check(fixture.timer.hold.isRunning() && !adapter.access.cellAcknowledged())
        fixture.timer.hold.release()
        awaitLifecycleFact { adapter.access.cellAcknowledged() && adapter.access.boundary.acknowledged() }
        check(adapter.runner.hasEntered() && !adapter.runner.hasBodyEnded() && adapter.runner.thread.isAlive)
        println(
            "PG_LIFECYCLE_RECORD_BOUNDARY_ADAPTER wrong_caller_virgin_refused=true same_active_runner_one_shot=true " +
                "real_ack=true proof=ADAPTER_NOT_RECORD_DISPOSAL",
        )
    }
}
