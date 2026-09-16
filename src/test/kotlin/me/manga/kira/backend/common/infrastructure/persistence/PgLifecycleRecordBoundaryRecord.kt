package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Exact live identities captured before retirement. Readback never authorizes admission, mutation or reclamation. */
internal class PgLifecycleRecordBoundaryRecord private constructor(
    val scope: PgLifecycleTestScope,
    val deletion: Boolean,
    val result: PersistenceFactoryResult.Success<PersistenceJdbcCandidate>,
    val entry: PersistencePhysicalEntry,
    val transports: PersistenceTransportOwner<*>,
) {
    val binding = scope.binding(deletion)
    val record = entry.record
    val work = requireNotNull(entry.terminalWork)
    val control = requireNotNull(entry.control)
    val budget = control.budget
    val receipt = result.receipt
    val attempt = requireNotNull(entry.attempt)
    val raw = requireNotNull(entry.raw.get())
    val source = requireNotNull(entry.driverScope).extentSource
    val transportEntries = lifecycleField(transports, "entries") as Array<*>
    val primary = transportEntries[PersistenceTransportRole.PRIMARY.ordinal] as PersistenceTransportEntry<*>
    val primaryRecord = primary.record
    val primaryExtent = requireNotNull(primary.extent)
    val primaryRaw = requireNotNull(primary.raw.get())
    val bodyExit = requireNotNull(lifecycleField(work, "bodyExit"))
    val transportLock = lifecycleField(transports, "lock") as ReentrantLock
    val queryWitness = binding.ledger.lock as PgLifecycleRecordBoundaryQueryWitness
    private val locks = listOf(binding.rendezvous.lock, queryWitness, transportLock)
    private val participant = if (deletion) scope.root.deletion else scope.root.ordinary
    val slotRunner = (lifecycleField(participant, "runners") as Array<*>)[record.slotHint] as PersistenceTerminalRunner
    val runner = lifecycleField(slotRunner, "actor") as PersistenceRetainedPlatformThread
    val boundaryReference = lifecycleField(work, "boundary") as AtomicReference<*>
    val workRunnerReference = lifecycleField(work, "runner") as AtomicReference<*>
    val abortReference = lifecycleField(work, "abort") as AtomicReference<*>

    fun <T : Any> cut(read: () -> T): T? = pgLifecycleRecordBoundaryCut(locks, read)

    fun exactCurrentLocked(): Boolean = binding.ledger.current(record) === entry && entry.record === record && entry.terminalWork === work &&
        work.claim.record === record && entry.control === control && entry.attempt === attempt && control.matchesRecord(record) &&
        control.budget === budget && attempt.budget === budget && attempt.input === record && attempt.ownedControl === control &&
        receipt === control.receipt && attempt.receipt === receipt && result.value === entry.candidate && entry.raw.get() === raw

    fun exactPrimaryLocked(): Boolean = transportEntries.size == 2 && transportEntries[PersistenceTransportRole.PRIMARY.ordinal] === primary &&
        transportEntries[PersistenceTransportRole.AUX_CANCEL.ordinal] == null && primary.record === primaryRecord && primary.extent === primaryExtent &&
        primary.raw.get() === primaryRaw && primaryRecord.role === PersistenceTransportRole.PRIMARY && primaryExtent.source === source &&
        primaryExtent.role === PersistenceTransportRole.PRIMARY

    fun fixedStrongLocked(): Boolean = entry.policy === if (deletion) {
        PersistenceDriverAttemptPolicy.TRACKED_DELETION_CONJUNCTION
    } else {
        PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION
    }

    fun processingEndedLocked(): Boolean = receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED && attempt.workerSettled &&
        !attempt.unresolved && binding.rendezvous.current !== attempt && control.state().phase === PersistenceOwnedCallerPhase.TAKEN

    fun openingEndedLocked(): Boolean = entry.dispatched && entry.opening === PersistencePhysicalOpeningPhase.SETTLED && entry.scopeEnded &&
        entry.openingFacts.driverEntered.get() && entry.openingFacts.driverEnded.get() && entry.openingFacts.factoryEntered.get() &&
        entry.openingFacts.factoryEnded.get() && entry.openingFacts.scopeCallEnded.get() && source.primaryOpeningEnded.get()

    /** G/T-only stable identity and genuine resource facts; no mutable rendezvous/F1 fields or native Thread observation. */
    fun queryFactsLocked(access: PgLifecycleRecordBoundaryAccess): Boolean =
        exactCurrentLocked() && exactPrimaryLocked() && fixedStrongLocked() && !entry.unknown &&
            entry.retirementRequested.get() && entry.retiring && entry.terminal === work.claim && entry.decisionDelivered &&
            openingEndedLocked() && !entry.openingFacts.fatal.get() && work.producerDrainProven() &&
            PgLifecycleRecordBoundaryAssertions.jdbcEndedLocked(this) && PgLifecycleRecordBoundaryAssertions.primaryDisposedLocked(this) &&
            boundaryReference.get() === access.boundary && workRunnerReference.get() === access.runner &&
            !access.boundary.acknowledged() && work.acknowledgedBoundary() == null && !work.failedTimerWorkEnded() &&
            work.disposition() === PersistenceTerminalDisposition.PENDING && !work.bodyExited()

    /** Immediate exact F→G→T cut; the caller owns observation retry and must not arm a query while these locks are held. */
    fun pendingCut(access: PgLifecycleRecordBoundaryAccess): Boolean? = cut {
        check(queryFactsLocked(access) && processingEndedLocked())
        true
    }

    fun awaitBoundary(timer: PgLifecycleRecordBoundaryTimer): PgLifecycleRecordBoundaryAccess {
        awaitLifecycleFact { boundaryReference.get() != null && work.closeState() === PersistenceTerminalCall.RETURNED }
        check(workRunnerReference.get() === runner && runner.hasEntered() && !runner.hasBodyEnded() && runner.thread.isAlive)
        check((lifecycleField(bodyExit, "thread") as AtomicReference<*>).get() === runner.thread)
        return PgLifecycleRecordBoundaryAccess(boundaryReference.get() as PersistenceTimerBoundary, scope.root, timer, runner)
    }

    companion object {
        fun capture(
            scope: PgLifecycleTestScope,
            deletion: Boolean,
            result: PersistenceFactoryResult.Success<PersistenceJdbcCandidate>,
        ): PgLifecycleRecordBoundaryRecord {
            val binding = scope.binding(deletion)
            var entry: PersistencePhysicalEntry? = null
            awaitLifecycleFact {
                entry = pgLifecycleRecordBoundaryCut(listOf(binding.rendezvous.lock, binding.ledger.lock)) {
                    binding.ledger.entries.filterNotNull().single().also { check(it.candidate === result.value) }
                }
                entry != null
            }
            val found = requireNotNull(entry)
            val transports = lifecycleField(requireNotNull(found.transports), "owner") as PersistenceTransportOwner<*>
            val locks = listOf(binding.rendezvous.lock, binding.ledger.lock, lifecycleField(transports, "lock") as ReentrantLock)
            var record: PgLifecycleRecordBoundaryRecord? = null
            awaitLifecycleFact {
                record = pgLifecycleRecordBoundaryCut(locks) {
                    check(binding.ledger.current(found.record) === found)
                    PgLifecycleRecordBoundaryRecord(scope, deletion, result, found, transports).also { captured ->
                        check(captured.exactCurrentLocked() && captured.exactPrimaryLocked() && captured.fixedStrongLocked())
                        check(captured.processingEndedLocked() && captured.openingEndedLocked())
                        check(!found.retiring && !found.retirementRequested.get() && found.terminal == null && !found.unknown)
                    }
                }
                record != null
            }
            val captured = requireNotNull(record)
            check(found.driverOpening?.timer === scope.root.timer)
            check(lifecycleField(requireNotNull(found.driverOpening), "driver") === scope.root.retainedDriver.forOpening())
            return captured
        }
    }
}

/** Immediate fixed-order F→G→T observation only. Contention is null/unavailable, never a successful empty snapshot. */
internal fun <T : Any> pgLifecycleRecordBoundaryCut(locks: List<ReentrantLock>, read: () -> T): T? {
    var acquired = 0
    try {
        for (lock in locks) {
            check(!lock.isHeldByCurrentThread)
            if (!lock.tryLock()) return null
            acquired++
        }
        return read()
    } finally {
        for (index in acquired - 1 downTo 0) locks[index].unlock()
    }
}
