package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class PgLifecycleNegotiationWitness(
    val entry: PersistencePhysicalEntry,
    val transports: PersistenceTransportOwner<*>,
    val first: PersistenceTransportEntry<*>,
    val firstIdentity: PgPrimaryIdentityWitness,
    val worker: Thread,
    val factory: TrackedPgSocketFactory,
)

/** Read-only own-project identities under their owning locks. Neither a snapshot nor a peer EOF grants a replacement. */
internal object PgLifecycleNegotiationAssertions {
    fun admitted(scope: PgLifecycleTestScope, deletion: Boolean, endpoint: ResolvedPersistenceEndpoint): PgLifecycleNegotiationWitness {
        val binding = scope.binding(deletion)
        val entry = binding.ledger.lock.withLock {
            binding.ledger.entries.filterNotNull().single().also { active(scope, it, deletion, endpoint) }
        }
        val owner = lifecycleField(requireNotNull(entry.transports), "owner") as PersistenceTransportOwner<*>
        val firstIdentity = PgPrimaryIdentityWitness()
        val first = primary(owner) { current ->
            live(current, entry)
            check(current.ticket.predecessor == null)
            firstIdentity.capture(current)
            current
        }
        val participant = if (deletion) scope.root.deletion else scope.root.ordinary
        val worker = lifecycleField(participant, "worker") as PersistenceFactoryWorker<*, *>
        val actor = lifecycleField(worker, "ownedThread") as PersistenceRetainedPlatformThread
        val driverScope = requireNotNull(entry.driverScope)
        check(actor.thread.isAlive && actor.hasEntered() && !actor.hasBodyEnded())
        check((lifecycleField(driverScope, "caller") as AtomicReference<*>).get() === actor.thread)
        val factory = (lifecycleField(driverScope, "captured") as AtomicReference<*>).get() as TrackedPgSocketFactory
        return PgLifecycleNegotiationWitness(entry, owner, first, firstIdentity, actor.thread, factory)
    }

    /** Called at final StartupMessage, before this peer can authenticate or complete the opening. */
    fun finalPrimary(
        scope: PgLifecycleTestScope,
        deletion: Boolean,
        endpoint: ResolvedPersistenceEndpoint,
        witness: PgLifecycleNegotiationWitness,
        rotated: Boolean,
    ): PgLifecycleTlsWitness {
        val binding = scope.binding(deletion)
        binding.ledger.lock.withLock {
            check(binding.ledger.current(witness.entry.record) === witness.entry)
            active(scope, witness.entry, deletion, endpoint)
        }
        val driverScope = requireNotNull(witness.entry.driverScope)
        check((lifecycleField(driverScope, "caller") as AtomicReference<*>).get() === witness.worker)
        check((lifecycleField(driverScope, "captured") as AtomicReference<*>).get() === witness.factory)
        return primary(witness.transports) { current ->
            live(current, witness.entry)
            witness.firstIdentity.requireUnchanged(witness.first)
            if (rotated) {
                check(current !== witness.first && current.record !== witness.first.record && current.raw.get() !== witness.first.raw.get())
                check(current.extent !== witness.first.extent && current.extent?.source === witness.first.extent?.source)
                check(current.ticket.predecessor === witness.first.record)
                disposedPredecessor(witness.first)
            } else {
                witness.firstIdentity.requireUnchanged(current)
                check(current === witness.first && current.record === witness.first.record && current.ticket.predecessor == null)
            }
            PgLifecycleTlsWitness(witness.entry, current.record)
        }
    }

    fun staleAlias(scope: PgLifecycleTestScope, deletion: Boolean, old: PgLifecycleNegotiationWitness, current: PgLifecycleNegotiationWitness) {
        check(current.entry.record !== old.entry.record && current.entry.record.slotHint == old.entry.record.slotHint)
        check(current.entry.driverScope !== old.entry.driverScope && current.first.record !== old.first.record)
        check(current.worker === old.worker && current.worker.isAlive) { "Negotiation did not reuse the same authentic managed worker." }
        check(!old.entry.candidate.requestRetirement())
        scope.binding(deletion).ledger.lock.withLock {
            check(scope.binding(deletion).ledger.current(current.entry.record) === current.entry)
            check(!current.entry.retiring && !current.entry.retirementRequested.get() && current.entry.terminal == null)
        }
    }

    fun predecessorRemainsDisposed(witness: PgLifecycleNegotiationWitness) {
        primary(witness.transports) { current ->
            witness.firstIdentity.requireUnchanged(witness.first)
            check(current !== witness.first && current.ticket.predecessor === witness.first.record)
            disposedPredecessor(witness.first)
        }
    }

    private fun active(scope: PgLifecycleTestScope, entry: PersistencePhysicalEntry, deletion: Boolean, endpoint: ResolvedPersistenceEndpoint) {
        val expected = if (deletion) {
            PersistenceDriverAttemptPolicy.TRACKED_DELETION_CONJUNCTION
        } else {
            PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION
        }
        check(entry.policy === expected && entry.dispatched && entry.opening === PersistencePhysicalOpeningPhase.ACTIVE)
        check(entry.policy.route === if (deletion) PersistenceDriverTransportRoute.APPROVED_DIRECT else PersistenceDriverTransportRoute.ORDINARY)
        check(entry.raw.get() == null && entry.openingFacts.driverEntered.get() && !entry.openingFacts.driverEnded.get())
        check(entry.openingFacts.factoryEntered.get() && !entry.openingFacts.factoryEnded.get())
        check(!entry.retirementRequested.get() && !entry.retiring && !entry.unknown && entry.control?.state() === PersistenceOwnedCallerDisposition.ATTACHED)
        val opening = requireNotNull(entry.driverOpening)
        check(opening.policy === expected && opening.image != null && opening.timer === scope.root.timer)
        check(lifecycleField(opening, "driver") === scope.root.retainedDriver.forOpening())
        check((lifecycleField(requireNotNull(entry.driverScope), "phase") as AtomicReference<*>).get() === PersistencePgScopePhase.ACTIVE)
        PgLifecycleNegotiationSettings.verify(opening, requireNotNull(entry.control), deletion, endpoint)
    }

    private fun live(current: PersistenceTransportEntry<*>, entry: PersistencePhysicalEntry) {
        check(current.record.role === PersistenceTransportRole.PRIMARY && current.extent?.role === PersistenceTransportRole.PRIMARY)
        check(current.extent?.source === entry.driverScope?.extentSource && !requireNotNull(current.extent).source.primaryOpeningEnded.get())
        check(current.invocation.get() === PersistenceTransportInvocation.RETURNED && current.construction === PersistenceTransportConstruction.RETURNED)
        check(current.raw.get() != null && current.firstClose === PersistenceTransportClosePhase.NOT_STARTED)
        check(!current.extentEnded && !current.allCallsSealed && !current.businessSealed)
    }

    private fun disposedPredecessor(first: PersistenceTransportEntry<*>) {
        check(first.invocation.get() === PersistenceTransportInvocation.RETURNED && first.construction === PersistenceTransportConstruction.RETURNED)
        check(first.raw.get() != null && first.firstClose === PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED)
        check(first.extentEnded && first.allCallsSealed && first.businessSealed)
        check(first.business.all { it == null } && first.observations.all { it == null })
    }

    /** No F/G is held here, no raw operations, and no unbounded acquisition of the production T lock. */
    private fun <T : Any> primary(owner: PersistenceTransportOwner<*>, read: (PersistenceTransportEntry<*>) -> T): T {
        val lock = lifecycleField(owner, "lock") as ReentrantLock
        check(!lock.isHeldByCurrentThread)
        var result: T? = null
        awaitLifecycleFact {
            if (!lock.tryLock()) {
                false
            } else {
                try {
                    val entries = lifecycleField(owner, "entries") as Array<*>
                    check(entries.size == 2 && entries[PersistenceTransportRole.AUX_CANCEL.ordinal] == null)
                    result = read(entries[PersistenceTransportRole.PRIMARY.ordinal] as PersistenceTransportEntry<*>)
                    true
                } finally {
                    lock.unlock()
                }
            }
        }
        return requireNotNull(result)
    }
}
