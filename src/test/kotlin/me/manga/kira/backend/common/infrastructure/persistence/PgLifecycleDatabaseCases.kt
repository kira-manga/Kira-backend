package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Actual retained owner and factory. The parent, never this JVM, witnesses PostgreSQL session presence/removal. */
internal object PgLifecycleDatabaseCases {
    fun verify(case: PgLifecycleDatabaseCase, port: Int, application: String, handshake: PgLifecycleDatabaseHandshake) {
        PgLifecycleTestScope(PgLifecycleDatabaseSettings.endpoint(case, port, application), capacity = 1).use { scope ->
            scope.start()
            if (case.lane.deleting) scope.prepareDeletion()
            check(scope.owner.snapshot().timerReady && !scope.owner.snapshot().weakEvidenceUsed)
            val actors = scope.actors()
            val driver = scope.root.retainedDriver.forOpening()
            handshake.publish(PgLifecycleDatabasePhase.PREPARED)
            var previous: PgLifecycleDatabaseWitness? = null
            repeat(case.attempts) { ordinal ->
                handshake.await(PgLifecycleDatabasePhase.START, ordinal)
                awaitLifecycleFact { scope.binding(case.lane.deleting).isOwnedReceiverReady() }
                val request = PgLifecycleDatabaseRequest(scope, case.lane)
                try {
                    request.start()
                    val witness = PgLifecycleDatabaseAssertions.retain(scope, case, application)
                    // This strong Entry reference survives automatic reclamation of short-lived constructor failures.
                    handshake.publish(PgLifecycleDatabasePhase.RETAINED, ordinal)
                    val result = request.result()
                    val receipt = resultReceipt(case, result)
                    if (case.returnsRaw) {
                        PgLifecycleDatabaseAssertions.live(scope, case, witness, result)
                        handshake.publish(PgLifecycleDatabasePhase.LIVE, ordinal)
                        if (previous != null) {
                            requireReuse(scope, case, requireNotNull(previous), witness)
                            handshake.publish(PgLifecycleDatabasePhase.STALE_REJECTED, ordinal)
                        }
                        handshake.await(PgLifecycleDatabasePhase.RETIRE, ordinal, PgLifecycleDatabaseDeadline(20_000))
                        PgLifecycleDatabaseAssertions.live(scope, case, witness, result)
                        check(witness.entry.candidate.requestRetirement())
                    }
                    PgLifecycleDatabaseAssertions.retired(scope, case, witness, receipt)
                    check(scope.root.retainedDriver.forOpening() === driver && scope.actors() == actors)
                    handshake.publish(PgLifecycleDatabasePhase.RETIRED, ordinal)
                    handshake.await(PgLifecycleDatabasePhase.ABSENCE_CONFIRMED, ordinal)
                    previous = witness
                } finally {
                    request.close()
                }
            }
            PgLifecycleDatabaseAssertions.shutdown(scope)
            handshake.publish(PgLifecycleDatabasePhase.OWNER_DRAINED)
            handshake.await(PgLifecycleDatabasePhase.EXIT)
        }
    }

    private fun resultReceipt(case: PgLifecycleDatabaseCase, result: PersistenceFactoryResult<PersistenceJdbcCandidate>): PersistenceFactoryReceipt {
        if (case.returnsRaw) {
            check(result is PersistenceFactoryResult.Success) { "Expected the matrix's accepted S outcome, not timeout/refusal/failure." }
            return result.receipt
        }
        check(result is PersistenceFactoryResult.Failed && result.reason === PersistenceFactoryFailure.CREATE_FAILED) {
            "Expected the matrix's accepted nonfatal F outcome, not timeout/refusal/success."
        }
        return result.receipt
    }

    private fun requireReuse(
        scope: PgLifecycleTestScope,
        case: PgLifecycleDatabaseCase,
        previous: PgLifecycleDatabaseWitness,
        current: PgLifecycleDatabaseWitness,
    ) {
        check(previous.entry !== current.entry && previous.entry.record !== current.entry.record)
        check(previous.entry.record.slotHint == current.entry.record.slotHint)
        check(previous.entry.terminalWork !== current.entry.terminalWork)
        check(!previous.entry.candidate.requestRetirement()) // Issue the stale alias only after the successor is actually LIVE.
        check(!current.entry.retirementRequested.get() && scope.entries(case.lane.deleting).single() === current.entry)
    }
}

/** Owned real platform caller; no Future.cancel, callback replacement, or test-written lifecycle fact. */
private class PgLifecycleDatabaseRequest(private val scope: PgLifecycleTestScope, lane: PgLifecycleDatabaseLane) : AutoCloseable {
    private val task = FutureTask { if (lane.deleting) scope.owner.requestDeletion() else scope.owner.requestOrdinary() }
    private val thread = Thread.ofPlatform().name("w03-database-candidate-caller").unstarted(task)

    fun start() = thread.start()

    fun result(): PersistenceFactoryResult<PersistenceJdbcCandidate> = task.get(8, TimeUnit.SECONDS)

    override fun close() {
        if (!task.isDone) scope.owner.requestShutdown()
        awaitLifecycleFact { !thread.isAlive }
        check(task.isDone)
    }
}
