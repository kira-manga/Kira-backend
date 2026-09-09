package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Exact retained observations only. No model-ready binding, producer setter, settlement call or disposal authority. */
internal class PgLifecycleRestorationAttempt private constructor(
    private val scope: PgLifecycleTestScope,
    private val deletion: Boolean,
    val entry: PersistencePhysicalEntry,
) {
    private val binding = scope.binding(deletion)
    val control = requireNotNull(entry.control)
    val receipt = control.receipt
    val work = requireNotNull(entry.terminalWork)
    private val attempt = requireNotNull(entry.attempt)
    private val record = entry.record
    private val budget = control.budget
    private val startedAtNanos = lifecycleField(budget, "startedAtNanos") as Long
    private val allowanceMillis = if (deletion) 2_000L else 6_000L
    private val opening = requireNotNull(entry.driverOpening)
    private val driver = scope.root.retainedDriver.forOpening()
    private val driverScope = requireNotNull(entry.driverScope)
    private val transports = requireNotNull(entry.transports)
    private val transportOwner = requireNotNull(lifecycleField(transports, "owner"))
    private val transportLock = lifecycleField(transportOwner, "lock") as ReentrantLock
    private val transportEntries = lifecycleField(transportOwner, "entries") as Array<*>
    private var primary: PersistenceTransportEntry<*>? = null
    private var primaryRaw: Any? = null
    private val participant = if (deletion) scope.root.deletion else scope.root.ordinary
    private val factory = lifecycleField(binding, "worker") as PersistenceRetainedPlatformThread
    private val runners = lifecycleField(participant, "runners") as Array<*>
    val runner = lifecycleField(requireNotNull(runners[record.slotHint]), "actor") as PersistenceRetainedPlatformThread

    fun requireOriginalIdentity() {
        check(entry.control === control && entry.attempt === attempt && entry.record === record && entry.terminalWork === work)
        check(work.claim.record === record)
        check(attempt.input === record && attempt.ownedControl === control && control.matchesRecord(record))
        check(attempt.budget === budget && control.budget === budget && attempt.receipt === receipt && control.receipt === receipt)
        check(lifecycleField(budget, "clock") === SystemPersistenceNanoClock)
        check(lifecycleField(budget, "startedAtNanos") == startedAtNanos && lifecycleField(budget, "allowanceNanos") == allowanceMillis * 1_000_000)
        val policy = if (deletion) {
            PersistenceDriverAttemptPolicy.TRACKED_DELETION_CONJUNCTION
        } else {
            PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION
        }
        check(entry.policy === policy && opening.policy === policy && entry.driverOpening === opening && opening.image != null)
        check(opening.timer === scope.root.timer && opening.loginPolicy.durationMillis == allowanceMillis)
        check(lifecycleField(opening, "driver") === driver && scope.root.retainedDriver.forOpening() === driver)
        check(entry.driverScope === driverScope && entry.transports === transports)
        requireAuthenticFactory()
        requireSettings()
    }

    private fun requireAuthenticFactory() {
        check(lifecycleField(driverScope, "image") === opening.image)
        check((lifecycleField(driverScope, "caller") as AtomicReference<*>).get() === factory.thread)
        check((lifecycleField(driverScope, "captured") as AtomicReference<*>).get() is TrackedPgSocketFactory)
        check(factory.hasEntered() && !factory.hasBodyEnded() && factory.thread.isAlive)
    }

    private fun requireSettings() {
        val endpoint = lifecycleField(opening, "endpoint") as ResolvedPersistenceEndpoint
        val properties = endpoint.driverProperties()
        check(properties.getProperty("loginTimeout") == "0" && !properties.containsKey("queryTimeout"))
        check(properties.getProperty("requireAuth") == "password" && properties.getProperty("gssEncMode") == "disable")
        check(properties.getProperty("sslmode") == "disable" && !properties.containsKey("socketFactoryArg"))
        check(properties.getProperty("connectTimeout") == if (deletion) "1" else "2")
        check(properties.getProperty("socketTimeout") == if (deletion) "2" else "3")
        check(properties.getProperty("cancelSignalTimeout") == if (deletion) "1" else "2")
    }

    fun requireCallerOutsideLocks(caller: Thread) {
        check(Thread.currentThread() === caller && control.caller.isCurrent())
        check(!transports.ownershipLockHeld()) { "Hostile caller override entered while owning F/G/T." }
    }

    fun attachedAndActive(): Boolean {
        requireOriginalIdentity()
        return readFg {
            requireMember()
            check(control.state() === PersistenceOwnedCallerDisposition.ATTACHED && control.failureResult() == null)
            check(entry.dispatched && entry.opening === PersistencePhysicalOpeningPhase.ACTIVE && !entry.scopeEnded)
            check(!entry.retiring && !entry.retirementRequested.get() && !entry.unknown && entry.terminal == null)
            check(attempt.phase === PersistenceFactoryAttemptPhase.RUNNING && !attempt.callerDetached && !attempt.workerSettled)
            check(!attempt.transferred && !attempt.unresolved && attempt.failure == null && attempt.result == null)
            check(!attempt.cancellation.isRequested() && persistenceFactoryRemainingMillis(budget) > 0)
            check(entry.openingFacts.factoryEntered.get() && !entry.openingFacts.factoryEnded.get())
            check(entry.openingFacts.driverEntered.get() && !entry.openingFacts.driverEnded.get() && entry.raw.get() == null)
            requirePendingFacts()
            true
        }
    }

    fun abandonedAndPending(): Boolean {
        requireOriginalIdentity()
        return readFg {
            requireMember()
            check(control.state() === PersistenceOwnedCallerDisposition.ABANDONED_INTERRUPTED)
            check(entry.dispatched && entry.raw.get() == null && !attempt.transferred && !attempt.workerSettled)
            requirePendingFacts()
            true
        }
    }

    fun originalInterruption(): PersistenceFactoryResult.Failed {
        val failure = control.failureResult()
        check(failure is PersistenceFactoryResult.Failed && failure.reason === PersistenceFactoryFailure.INTERRUPTED)
        check(failure.receipt === receipt)
        return failure
    }

    fun producersDrained(): Boolean {
        val facts = entry.openingFacts
        val openingCallsEnded = facts.driverEnded.get() && facts.factoryEnded.get() && facts.scopeCallEnded.get()
        if (!openingCallsEnded || !facts.awaitingResourcePhase.get()) return false
        if (!work.producerDrainProven()) return false
        return readFg {
            requireMember()
            check(facts.driverEntered.get() && facts.factoryEntered.get() && !facts.fatal.get())
            check(entry.opening === PersistencePhysicalOpeningPhase.SETTLED && entry.scopeEnded && entry.raw.get() == null)
            check(facts.outcome.get() === PersistencePhysicalOpening.FAILED)
            check(entry.retiring && entry.retirementRequested.get() && entry.terminal === work.claim && entry.decisionDelivered)
            check(attempt.callerDetached && !attempt.workerSettled && !attempt.transferred && !attempt.unresolved && attempt.result == null)
            requirePendingFacts()
            true
        }
    }

    /** First T observation is AFTER actual opening/scope exit; it cannot contend with an opening BUSINESS admission. */
    fun primaryDisposed(): Boolean {
        check(entry.openingFacts.driverEnded.get() && entry.openingFacts.scopeCallEnded.get() && work.producerDrainProven())
        if (!transportLock.tryLock()) return false
        return try {
            val observed = transportEntries[PersistenceTransportRole.PRIMARY.ordinal] as? PersistenceTransportEntry<*>
            check(observed != null && transportEntries[PersistenceTransportRole.AUX_CANCEL.ordinal] == null)
            check(observed.record.role === PersistenceTransportRole.PRIMARY && observed.extent?.source === driverScope.extentSource)
            check(observed.ticket.entry === observed && observed.ticket.record === observed.record && observed.ticket.owner === transportOwner)
            check(observed.invocation.get() === PersistenceTransportInvocation.RETURNED && observed.construction === PersistenceTransportConstruction.RETURNED)
            check(observed.raw.get() is TrackedPersistenceSocket && observed.firstClose === PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED)
            check(observed.businessSealed && observed.allCallsSealed && observed.business.all { it == null } && observed.observations.all { it == null })
            check(driverScope.extentSource.primaryOpeningEnded.get())
            check(primary == null || (primary === observed && primaryRaw === observed.raw.get()))
            primary = observed
            primaryRaw = observed.raw.get()
            true
        } finally {
            transportLock.unlock()
        }
    }

    fun requireNoJdbcCalls() {
        check(entry.raw.get() == null && work.closeState() === PersistenceTerminalCall.NOT_INVOKED)
        check((lifecycleField(work, "abort") as AtomicReference<*>).get() === PersistenceTerminalCall.NOT_INVOKED)
        check(!work.hasCleanupFailure() && !work.hasFatalFailure() && !work.failedTimerWorkEnded())
        check((lifecycleField(work, "runner") as AtomicReference<*>).get() === runner)
    }

    fun reclaimed(): Boolean {
        requireOriginalIdentity()
        check(work.disposition() === PersistenceTerminalDisposition.TRACKED_DISPOSED && work.bodyExited())
        return readFg {
            val retained = binding.ledger.entries[record.slotHint]
            if (retained === entry) return@readFg false
            check(retained == null && binding.ledger.current(record) == null && binding.ledger.entries.none { it === entry })
            check(binding.rendezvous.current !== attempt && attempt.callerDetached && attempt.workerSettled && !attempt.unresolved)
            check(!attempt.transferred && attempt.result == null && attempt.phase === PersistenceFactoryAttemptPhase.FINISHED)
            check(control.state() === PersistenceOwnedCallerDisposition.ABANDONED_INTERRUPTED)
            check(receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED)
            check(entry.scopeEnded && entry.terminal === work.claim && primary != null && primaryRaw != null)
            true
        }
    }

    private fun requireMember() {
        check(binding.ledger.current(record) === entry && binding.ledger.entries[record.slotHint] === entry)
        check(binding.rendezvous.current === attempt && entry.attempt === attempt)
        check(binding.rendezvous.thread === factory.thread)
        check(!binding.isClosed() && !binding.ledger.sealed && !scope.root.shutdown.get())
    }

    private fun requirePendingFacts() {
        check(receipt.state() === PersistenceFactoryProcessing.PENDING && work.disposition() === PersistenceTerminalDisposition.PENDING)
        check(!work.bodyExited() && work.acknowledgedBoundary() == null && !work.failedTimerWorkEnded())
    }

    /** Private read-only test bodies. Contention alone is pending; a conclusive mismatch fails, never repairs/retries work. */
    private inline fun readFg(observe: () -> Boolean): Boolean {
        if (!binding.rendezvous.lock.tryLock()) return false
        return try {
            if (!binding.ledger.lock.tryLock()) return false
            try {
                observe()
            } finally {
                binding.ledger.lock.unlock()
            }
        } finally {
            binding.rendezvous.lock.unlock()
        }
    }

    companion object {
        fun capture(scope: PgLifecycleTestScope, deletion: Boolean): PgLifecycleRestorationAttempt {
            val binding = scope.binding(deletion)
            var retained: PersistencePhysicalEntry? = null
            awaitLifecycleFact {
                if (!binding.ledger.lock.tryLock()) return@awaitLifecycleFact false
                try {
                    retained = binding.ledger.entries.filterNotNull().single()
                    true
                } finally {
                    binding.ledger.lock.unlock()
                }
            }
            return PgLifecycleRestorationAttempt(scope, deletion, requireNotNull(retained))
        }
    }
}
