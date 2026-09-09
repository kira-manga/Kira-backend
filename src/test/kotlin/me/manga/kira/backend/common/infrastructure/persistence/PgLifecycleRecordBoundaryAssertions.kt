package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicReference

/** Closed read-only oracles. A test-local counterfactual claim is never supplied to the real lifecycle implementation. */
internal object PgLifecycleRecordBoundaryAssertions {
    fun boundaryBlindClaim(record: PgLifecycleRecordBoundaryRecord): PgLifecycleRecordBoundaryBlindClaim? = record.cut {
        val entry = record.entry
        val work = record.work
        PgLifecycleRecordBoundaryBlindClaim(
            exactStrongIdentity = record.exactCurrentLocked() && record.fixedStrongLocked() && !entry.unknown,
            retiredAndClaimed = entry.retirementRequested.get() && entry.retiring && entry.terminal === work.claim && entry.decisionDelivered,
            realOpeningEnded = record.openingEndedLocked() && !entry.openingFacts.fatal.get(),
            producerDrain = work.producerDrainProven(),
            originalProcessingEnded = record.processingEndedLocked(),
            actualJdbcEnded = jdbcEndedLocked(record),
            knownPrimaryDisposed = primaryDisposedLocked(record),
        )
    }

    fun jdbcEndedLocked(record: PgLifecycleRecordBoundaryRecord): Boolean {
        val abort = record.abortReference.get()
        return record.work.closeState() === PersistenceTerminalCall.RETURNED &&
            (abort === PersistenceTerminalCall.RETURNED || abort === PersistenceTerminalCall.THREW) && !record.work.hasFatalFailure()
    }

    fun primaryDisposedLocked(record: PgLifecycleRecordBoundaryRecord): Boolean {
        val primary = record.primary
        return record.exactPrimaryLocked() && primary.invocation.get() === PersistenceTransportInvocation.RETURNED &&
            primary.construction === PersistenceTransportConstruction.RETURNED &&
            primary.firstClose === PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED &&
            primary.businessSealed && primary.allCallsSealed && primary.business.all { it == null } && primary.observations.all { it == null } &&
            record.transports.terminalState(record.source, boundary = null, failedTimerWorkEnded = false) === PersistenceTerminalTransportState.DISPOSED
    }

    fun requirePending(record: PgLifecycleRecordBoundaryRecord, access: PgLifecycleRecordBoundaryAccess) {
        awaitLifecycleFact { boundaryBlindClaim(record)?.accepted == true } // Exclude other known pending resource prerequisites.
        check(access.expectedThread.isAlive && !record.scope.root.timer.capturedThreadEnded()) // No native observation under F/G/T.
        check(record.boundaryReference.get() === access.boundary && record.workRunnerReference.get() === access.runner)
        check(!access.boundary.acknowledged() && record.work.acknowledgedBoundary() == null && !record.work.failedTimerWorkEnded())
        // One existing observation budget; unavailable G/T or post-cut samples never satisfy this negative.
        awaitLifecycleFact { record.queryWitness.samplePending(record, access) }
        awaitLifecycleFact {
            record.cut {
                check(record.exactCurrentLocked() && record.exactPrimaryLocked() && record.fixedStrongLocked())
                check(record.entry.retiring && record.entry.terminal === record.work.claim && record.work.producerDrainProven())
                check(record.work.disposition() === PersistenceTerminalDisposition.PENDING && !record.work.bodyExited())
                true
            } == true
        }
    }

    fun requireUnfencedClaimWhileRunning(
        record: PgLifecycleRecordBoundaryRecord,
        access: PgLifecycleRecordBoundaryAccess,
        task: PgLifecycleRecordBoundaryTask,
    ) {
        awaitLifecycleFact { boundaryBlindClaim(record)?.accepted == true }
        check(task.isRunning() && task.actualThread() === access.expectedThread)
        access.requireGenuineScheduledPending()
        requirePending(record, access)
        check(task.isRunning())
        task.checkHealthy()
        println(
            "PG_LIFECYCLE_RECORD_BOUNDARY_BLIND_CLAIM accepted=true task_running=true fenced=PENDING " +
                "retained_exact=true proof=MODEL_CLAIM_REAL_PREREQUISITES",
        )
    }

    fun disposed(record: PgLifecycleRecordBoundaryRecord, access: PgLifecycleRecordBoundaryAccess, ordinal: Int) {
        awaitLifecycleFact {
            access.cellAcknowledged() && access.boundary.acknowledged() && record.work.bodyExited() &&
                record.work.disposition() === PersistenceTerminalDisposition.TRACKED_DISPOSED
        }
        check(record.work.acknowledgedBoundary() === access.boundary && !record.work.failedTimerWorkEnded())
        check(record.boundaryReference.get() === access.boundary && record.workRunnerReference.get() === access.runner)
        awaitLifecycleFact {
            record.cut {
                val removed = record.binding.ledger.current(record.record) == null && record.binding.ledger.entries[record.record.slotHint] == null
                removed && record.processingEndedLocked() && record.openingEndedLocked() && record.exactPrimaryLocked() &&
                    record.transports.terminalState(record.source, access.boundary, false) === PersistenceTerminalTransportState.DISPOSED
            } == true
        }
        // BodyExit's release publication follows these clears. No successor has been requested yet.
        check((lifecycleField(record.slotRunner, "mailbox") as AtomicReference<*>).get() == null)
        check(lifecycleField(record.slotRunner, "current") == null)
        println("PG_LIFECYCLE_RECORD_BOUNDARY_DISPOSED ordinal=$ordinal exact_ack=true body_exited=true exact_slot_removed=true proof=REAL_COMPOSITION")
    }

    fun freshRecord(previous: PgLifecycleRecordBoundaryRecord, current: PgLifecycleRecordBoundaryRecord) {
        check(previous.work.bodyExited() && previous.work.disposition() === PersistenceTerminalDisposition.TRACKED_DISPOSED)
        check(current.entry !== previous.entry && current.record !== previous.record && current.work !== previous.work)
        check(current.bodyExit !== previous.bodyExit)
        check(current.control !== previous.control && current.budget !== previous.budget && current.receipt !== previous.receipt)
        check(current.attempt !== previous.attempt && current.primaryRecord !== previous.primaryRecord && current.primaryRaw !== previous.primaryRaw)
        check(current.record.slotHint == previous.record.slotHint && current.slotRunner === previous.slotRunner && current.runner === previous.runner)
        check(!previous.result.value.requestRetirement())
        awaitLifecycleFact {
            current.cut {
                check(current.exactCurrentLocked() && !current.entry.retiring && !current.entry.retirementRequested.get() && current.entry.terminal == null)
                check(previous.binding.ledger.current(previous.record) == null)
                true
            } == true
        }
    }

    fun staleAck(
        previous: PgLifecycleRecordBoundaryAccess,
        current: PgLifecycleRecordBoundaryAccess,
        record: PgLifecycleRecordBoundaryRecord,
        running: PgLifecycleRecordBoundaryTask,
    ) {
        check(current.boundary !== previous.boundary && current.call !== previous.call && current.cell !== previous.cell && current.task !== previous.task)
        check(current.runner === previous.runner && current.timer === previous.timer && current.expectedThread === previous.expectedThread)
        check(previous.boundary.acknowledged() && previous.cellAcknowledged())
        check(running.isRunning() && running.actualThread() === current.expectedThread)
        current.requireGenuineScheduledPending()
        requirePending(record, current)
        println("PG_LIFECYCLE_RECORD_BOUNDARY_FRESH old_ack=true fresh_record_work_call_cell_task=true same_slot_runner_timer_thread=true fenced=PENDING")
    }
}

/** Intentionally WRONG boundary-blind resource-completion claim; no body-exit/reclamation claim and no production state writer. */
internal data class PgLifecycleRecordBoundaryBlindClaim(
    val exactStrongIdentity: Boolean,
    val retiredAndClaimed: Boolean,
    val realOpeningEnded: Boolean,
    val producerDrain: Boolean,
    val originalProcessingEnded: Boolean,
    val actualJdbcEnded: Boolean,
    val knownPrimaryDisposed: Boolean,
) {
    val accepted: Boolean get() = exactStrongIdentity && retiredAndClaimed && realOpeningEnded && producerDrain &&
        originalProcessingEnded && actualJdbcEnded && knownPrimaryDisposed
}
