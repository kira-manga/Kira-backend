package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Immutable witness captured before peer release, not a post-retirement empty-ledger disposal inference. */
internal class PgLifecycleTrackedWeakNoRawTransport(val physical: PersistencePhysicalFactoryBinding, val entry: PersistencePhysicalEntry) {
    val transports = requireNotNull(entry.transports)
    val owner = lifecycleField(transports, "owner") as PersistenceTransportOwner<*>
    val lock = lifecycleField(owner, "lock") as ReentrantLock
    val slots = lifecycleField(owner, "entries") as Array<*>
    val source = requireNotNull(entry.driverScope).extentSource
    val primary = physical.ledger.lock.withLock {
        lock.withLock {
            check(physical.ledger.current(entry.record) === entry)
            requireNotNull(slots[PersistenceTransportRole.PRIMARY.ordinal]) as PersistenceTransportEntry<*>
        }
    }
    val record = primary.record
    val extent = requireNotNull(primary.extent)
    val socket = primary.raw.get() as TrackedPersistenceSocket

    // Resolve fixed own-project fields outside locks; no public snapshot becomes disposal authority.
    private val sealedField = owner.javaClass.getDeclaredField("sealed").also { it.isAccessible = true }
    private val exhaustedField = owner.javaClass.getDeclaredField("exhausted").also { it.isAccessible = true }

    fun beforeRefusal() {
        lock.withLock {
            requireIdentity()
            check(slots[PersistenceTransportRole.AUX_CANCEL.ordinal] == null)
            check(primary.invocation.get() === PersistenceTransportInvocation.RETURNED)
            check(primary.construction === PersistenceTransportConstruction.RETURNED)
            check(primary.firstClose === PersistenceTransportClosePhase.NOT_STARTED)
            check(!primary.extentEnded && !source.primaryOpeningEnded.get())
            check(!primary.businessSealed && !primary.allCallsSealed)
        }
        check(socket.isConnected && !socket.isClosed) // Inherited Socket state only; no tracked call is started here.
    }

    /** Read under T after real producer drain. A MODEL token, if requested, is the only permitted remaining call. */
    fun requirePrimaryEnded(pending: PersistenceTransportCall? = null) {
        lock.withLock {
            requireIdentity()
            check(primary.invocation.get() === PersistenceTransportInvocation.RETURNED)
            check(primary.construction === PersistenceTransportConstruction.RETURNED)
            check(primary.firstClose === PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED)
            check(primary.businessSealed && primary.allCallsSealed && primary.business.all { it == null })
            check(primary.observations.filterNotNull() == if (pending == null) emptyList() else listOf(pending))
            // Weak policy has no record boundary: this exact source's real opening exit supplies the PRIMARY outer fact.
            check(extent.source === source && source.primaryOpeningEnded.get())
        }
        check(socket.isClosed) // Supplementary local API state, never a peer/session/native-child certificate.
    }

    fun requireNoAuxiliary() {
        lock.withLock { check(slots[PersistenceTransportRole.AUX_CANCEL.ordinal] == null) }
    }

    /** Exactly one inert MODEL token is the only remaining known-transport prerequisite; no native observation here. */
    fun requireSolePendingLocked(token: PersistenceTransportCall) {
        check(physical.ledger.lock.isHeldByCurrentThread && lock.isHeldByCurrentThread)
        requireIdentity()
        check(sealedField.getBoolean(owner) && !exhaustedField.getBoolean(owner))
        check(slots.size == 2 && slots[PersistenceTransportRole.AUX_CANCEL.ordinal] == null)
        check(primary.invocation.get() === PersistenceTransportInvocation.RETURNED)
        check(primary.construction === PersistenceTransportConstruction.RETURNED && primary.raw.get() === socket)
        check(primary.firstClose === PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED)
        check(primary.businessSealed && primary.allCallsSealed && primary.business.all { it == null })
        check(!primary.extentEnded && extent.source === source && source.primaryOpeningEnded.get())
        check(requireNotNull(entry.driverScope).extentSource === source)
        check(token.record === record && token.kind === PersistenceTransportCallKind.OBSERVATION)
        check(token.slotHint in primary.observations.indices && primary.observations[token.slotHint] === token)
        check(primary.observations.indices.all { slot -> slot == token.slotHint || primary.observations[slot] == null })
    }

    fun firstCloseAcknowledged(): Boolean = lock.withLock {
        requireIdentity()
        primary.firstClose === PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED
    }

    fun requireTerminalState(expected: PersistenceTerminalTransportState) {
        check(expected !== PersistenceTerminalTransportState.PENDING)
        awaitLifecycleFact {
            when (val observed = owner.terminalState(source, null, failedTimerWorkEnded = false)) {
                PersistenceTerminalTransportState.PENDING -> false

                else -> {
                    check(observed === expected)
                    true
                }
            }
        }
    }

    fun requireIdentity() {
        check(lock.isHeldByCurrentThread)
        check(slots[PersistenceTransportRole.PRIMARY.ordinal] === primary && primary.record === record)
        check(record.role === PersistenceTransportRole.PRIMARY && primary.raw.get() === socket)
        check(primary.extent === extent && extent.role === PersistenceTransportRole.PRIMARY && extent.source === source)
    }
}
