package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

/** Real retained identities and ordered observations; no resource/F1/body completion setter or alternate opening recipe. */
internal class PgLifecycleTrackedWeakNoRawAttempt(private val scope: PgLifecycleTestScope, private val caller: PgLifecycleTrackedWeakNoRawCaller) {
    val binding = scope.binding()
    val entry = scope.entries().single()
    val control = requireNotNull(entry.control)
    val receipt = control.receipt
    val attempt = requireNotNull(entry.attempt)
    val work = requireNotNull(entry.terminalWork)
    val transport = PgLifecycleTrackedWeakNoRawTransport(binding, entry)
    private val opening = requireNotNull(entry.driverOpening)
    private val factory = lifecycleField(binding, "worker") as PersistenceRetainedPlatformThread
    private val scopePhase = lifecycleField(requireNotNull(entry.driverScope), "phase") as AtomicReference<*>
    private val abort = lifecycleField(work, "abort") as AtomicReference<*>
    private val boundary = lifecycleField(work, "boundary") as AtomicReference<*>

    fun beforeRefusal() {
        requireFixedWeak()
        binding.rendezvous.lock.withLock {
            binding.ledger.lock.withLock {
                requireAssociation()
                check(binding.ledger.current(entry.record) === entry && binding.rendezvous.current === attempt)
                check(entry.dispatched && entry.opening === PersistencePhysicalOpeningPhase.ACTIVE)
                check(!entry.retiring && !entry.retirementRequested.get() && !entry.unknown && !entry.decisionDelivered)
                check(control.state() === PersistenceOwnedCallerDisposition.ATTACHED && receipt.state() === PersistenceFactoryProcessing.PENDING)
                check(attempt.phase === PersistenceFactoryAttemptPhase.RUNNING)
                check(!attempt.workerSettled && !attempt.transferred && attempt.result == null)
            }
        }
        val facts = entry.openingFacts
        check(facts.factoryEntered.get() && !facts.factoryEnded.get() && facts.driverEntered.get() && !facts.driverEnded.get())
        check(!facts.scopeCallEnded.get() && entry.raw.get() == null)
        check(work.disposition() === PersistenceTerminalDisposition.PENDING && !work.bodyExited() && !work.isAssigned())
        check(factory.hasEntered() && !factory.hasBodyEnded() && factory.thread.isAlive)
        transport.beforeRefusal()
        requireNoJdbcOrBoundary()
        println(
            "PG_LIFECYCLE_TRACKED_WEAK_NO_RAW_CAPTURED ordinal=${caller.ordinal} recipe=TRACKED_STANDARD evidence=DRIVER_CONTRACT_ONLY " +
                "known_primary=true driver_pending=true proof=REAL_DRIVER_PROTOCOL_PEER",
        )
    }

    fun requireOriginalFailure() {
        val result = caller.failure()
        check(result === control.failureResult() && result.receipt === receipt)
        check(control.state() === PersistenceOwnedCallerDisposition.ABANDONED_CREATE_FAILED)
        binding.rendezvous.lock.withLock {
            check(attempt.failure === PersistenceFactoryFailure.CREATE_FAILED && !attempt.transferred && attempt.result == null)
        }
        check(entry.raw.get() == null && !entry.openingFacts.fatal.get())
    }

    fun awaitActualOpeningAndPrimary(pending: PersistenceTransportCall? = null) {
        awaitLifecycleFact { work.producerDrainProven() && entry.openingFacts.awaitingResourcePhase.get() && transport.firstCloseAcknowledged() }
        requireStableNoRaw()
        transport.requirePrimaryEnded(pending)
    }

    fun requirePendingCall(token: PersistenceTransportCall, witness: PgLifecycleTrackedWeakNoRawWitness) {
        // The single original observation allowance now includes conclusive actual queries, not initial-phase sampling alone.
        witness.requirePending(this, token)
        requireStableNoRaw()
        transport.requirePrimaryEnded(token) // Supplementary Socket state is observed only after the query releases every lock.
        transport.requireNoAuxiliary()
        check(token.record === transport.record)
        check(work.disposition() === PersistenceTerminalDisposition.PENDING && !work.bodyExited())
        check(receipt.state() === PersistenceFactoryProcessing.PENDING)
        binding.rendezvous.lock.withLock {
            check(!attempt.workerSettled && !attempt.transferred)
        }
        requireMembership(retained = true)
        println(
            "PG_LIFECYCLE_TRACKED_WEAK_NO_RAW_PENDING_CALL resource=PENDING processing=PENDING body_exited=false retained=true " +
                "known_primary=true model_call_active=true proof=MODEL_TOKEN_AND_LOCK_WITNESSED_REAL_QUERY",
        )
    }

    /** F-only mutable facts are checked in the witness's valid F→G→T cuts before AND after the sampled query. */
    fun requirePendingFactoryLocked() {
        check(binding.rendezvous.lock.isHeldByCurrentThread && binding.ledger.lock.isHeldByCurrentThread)
        check(binding.rendezvous.current === attempt && !attempt.workerSettled && !attempt.transferred && !attempt.unresolved)
        check(attempt.failure === PersistenceFactoryFailure.CREATE_FAILED && attempt.result == null)
        check(control.state() === PersistenceOwnedCallerDisposition.ABANDONED_CREATE_FAILED)
        check(receipt.state() === PersistenceFactoryProcessing.PENDING)
    }

    /** Closed G→T bookkeeping only. In particular this does not call the native factory-Thread precheck or acquire F. */
    fun requirePendingQueryLocked(token: PersistenceTransportCall) {
        check(binding.ledger.lock.isHeldByCurrentThread && transport.lock.isHeldByCurrentThread)
        requireAssociation()
        check(binding.ledger.current(entry.record) === entry && binding.ledger.entries[entry.record.slotHint] === entry)
        check(entry.transports === transport.transports && transport.physical === binding && transport.entry === entry)
        check(entry.dispatched && entry.opening === PersistencePhysicalOpeningPhase.SETTLED && entry.scopeEnded)
        check(entry.retiring && entry.retirementRequested.get() && entry.unknown && entry.decisionDelivered)
        check(entry.terminal === work.claim && work.claim.record === entry.record && work.isAssigned())
        check(entry.policy === PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONTRACT && opening.policy === entry.policy)
        check(entry.policy.recipe === PersistenceDriverExecutionRecipe.TRACKED_STANDARD)
        check(entry.policy.evidence === PersistenceDriverEvidencePolicy.DRIVER_CONTRACT_ONLY && opening.timer == null)
        val facts = entry.openingFacts
        check(facts.factoryEntered.get() && facts.factoryEnded.get() && facts.awaitingResourcePhase.get())
        check(facts.driverEntered.get() && facts.driverEnded.get() && facts.outcome.get() === PersistencePhysicalOpening.FAILED)
        check(facts.scopeCallEnded.get() && scopePhase.get() === PersistencePgScopePhase.ENDED && work.producerDrainProven())
        check(!facts.fatal.get() && !work.hasFatalFailure() && !work.hasCleanupFailure() && entry.raw.get() == null)
        requireNoJdbcOrBoundary()
        check(work.disposition() === PersistenceTerminalDisposition.PENDING && !work.bodyExited())
        check(receipt.state() === PersistenceFactoryProcessing.PENDING)
        transport.requireSolePendingLocked(token)
    }

    fun awaitReclaimed() {
        awaitEnded(PersistenceTerminalDisposition.NO_RAW_DRIVER_RETURN_ONLY)
        transport.requireNoAuxiliary()
        transport.requireTerminalState(PersistenceTerminalTransportState.DISPOSED)
        awaitLifecycleFact { binding.ledger.lock.withLock { binding.ledger.current(entry.record) == null } }
        requireMembership(retained = false)
        requireWeakEvidence()
    }

    fun awaitFailedRetained() {
        awaitEnded(PersistenceTerminalDisposition.UNKNOWN_ENDED)
        transport.requireTerminalState(PersistenceTerminalTransportState.FAILED_ENDED)
        // A conclusive bounded reclaimer pass may end scanning but cannot remove failed-ended ownership.
        awaitLifecycleFact { binding.completion.scanReclamation(entry.record.slotHint) }
        requireMembership(retained = true)
    }

    private fun awaitEnded(expected: PersistenceTerminalDisposition) {
        awaitLifecycleFact { work.disposition() !== PersistenceTerminalDisposition.PENDING }
        check(work.disposition() === expected)
        awaitActualOpeningAndPrimary()
        awaitLifecycleFact { receipt.state() !== PersistenceFactoryProcessing.PENDING }
        check(receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED && work.disposition() === expected)
        awaitLifecycleFact { work.bodyExited() }
        check(work.disposition() === expected && receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED)
        binding.rendezvous.lock.withLock {
            check(attempt.workerSettled && attempt.callerDetached && !attempt.unresolved && !attempt.transferred)
            check(attempt.result == null && binding.rendezvous.current !== attempt)
        }
        check(runner().hasEntered() && !runner().hasBodyEnded() && runner().thread.isAlive)
        check(factory.hasEntered() && !factory.hasBodyEnded() && factory.thread.isAlive)
        requireOriginalFailure()
    }

    fun requireStableNoRaw() {
        requireFixedWeak()
        val facts = entry.openingFacts
        check(facts.driverEntered.get() && facts.driverEnded.get() && facts.outcome.get() === PersistencePhysicalOpening.FAILED)
        check(facts.factoryEntered.get() && facts.factoryEnded.get() && facts.awaitingResourcePhase.get())
        check(facts.scopeCallEnded.get() && entry.scopeEnded && work.producerDrainProven())
        check(!facts.fatal.get() && !work.hasFatalFailure() && !work.hasCleanupFailure() && entry.raw.get() == null)
        check(scopePhase.get() === PersistencePgScopePhase.ENDED)
        binding.ledger.lock.withLock {
            requireAssociation()
            check(entry.opening === PersistencePhysicalOpeningPhase.SETTLED && entry.retiring && entry.unknown && entry.decisionDelivered)
            check(entry.terminal === work.claim && work.claim.record === entry.record)
        }
        requireNoJdbcOrBoundary()
    }

    fun requireWeakEvidence() {
        check(binding.completion.weakEvidence.get() && binding.completion.unprovedProvider.get())
        check(scope.owner.snapshot().weakEvidenceUsed && !scope.owner.snapshot().cleanupFailureObserved)
        check(work.disposition() === PersistenceTerminalDisposition.NO_RAW_DRIVER_RETURN_ONLY && work.bodyExited())
        requireStableNoRaw()
    }

    fun requireFreshAfter(previous: PgLifecycleTrackedWeakNoRawAttempt) {
        check(entry !== previous.entry && entry.record !== previous.entry.record && entry.record.slotHint == previous.entry.record.slotHint)
        check(control !== previous.control && control.budget !== previous.control.budget && receipt !== previous.receipt && attempt !== previous.attempt)
        check(entry.driverScope !== previous.entry.driverScope && transport.owner !== previous.transport.owner)
        check(transport.record !== previous.transport.record && transport.extent !== previous.transport.extent)
        check(transport.socket !== previous.transport.socket)
        check(work !== previous.work && factory === previous.factory && opening === previous.opening)
        check(previous.work.bodyExited() && previous.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED)
        binding.ledger.lock.withLock {
            check(binding.ledger.current(previous.entry.record) == null && binding.ledger.current(entry.record) === entry)
        }
        binding.rendezvous.lock.withLock { check(binding.rendezvous.current !== previous.attempt) }
        previous.requireWeakEvidence()
    }

    fun requireSameRunner(previous: PgLifecycleTrackedWeakNoRawAttempt) {
        check(work.bodyExited() && previous.work.bodyExited() && runner() === previous.runner())
    }

    fun requireMembership(retained: Boolean) {
        binding.ledger.lock.withLock {
            check((binding.ledger.current(entry.record) === entry) == retained)
            check(binding.ledger.entries[entry.record.slotHint] === if (retained) entry else null)
        }
        if (!retained) {
            check(work.bodyExited() && work.disposition() === PersistenceTerminalDisposition.NO_RAW_DRIVER_RETURN_ONLY)
            check(receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED)
        }
    }

    fun emit(proof: String, retained: Boolean) {
        requireMembership(retained)
        println(
            "PG_LIFECYCLE_TRACKED_WEAK_NO_RAW_ATTEMPT ordinal=${caller.ordinal} disposition=${work.disposition()} " +
                "processing=${receipt.state()} body_exited=${work.bodyExited()} retained=$retained primary_disposed=true boundary=ABSENT proof=$proof",
        )
    }

    private fun requireFixedWeak() {
        check(entry.policy === PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONTRACT && opening.policy === entry.policy)
        check(entry.policy.recipe === PersistenceDriverExecutionRecipe.TRACKED_STANDARD)
        check(entry.policy.evidence === PersistenceDriverEvidencePolicy.DRIVER_CONTRACT_ONLY && entry.policy.route === PersistenceDriverTransportRoute.ORDINARY)
        check(opening.image != null && opening.timer == null && opening.loginPolicy.durationMillis == 6_000L)
        check(lifecycleField(opening, "driver") === scope.root.retainedDriver.forOpening())
        check(scope.root.retainedDriver.forOpening().javaClass.name == "org.postgresql.Driver")
        check(lifecycleField(control.budget, "clock") === SystemPersistenceNanoClock)
    }

    private fun requireAssociation() {
        check(binding.ledger.lock.isHeldByCurrentThread)
        check(entry.attempt === attempt && entry.control === control && control.matchesRecord(entry.record))
        check(attempt.input === entry.record && attempt.ownedControl === control && attempt.budget === control.budget && attempt.receipt === receipt)
        check(entry.driverOpening === opening && entry.terminalWork === work)
        check(work.claim.record === entry.record)
    }

    private fun requireNoJdbcOrBoundary() {
        check(abort.get() === PersistenceTerminalCall.NOT_INVOKED)
        check(work.closeState() === PersistenceTerminalCall.NOT_INVOKED)
        check(boundary.get() == null)
        check(work.acknowledgedBoundary() == null && !work.failedTimerWorkEnded())
    }

    private fun runner(): PersistenceRetainedPlatformThread = (lifecycleField(work, "runner") as AtomicReference<*>).get() as PersistenceRetainedPlatformThread
}
