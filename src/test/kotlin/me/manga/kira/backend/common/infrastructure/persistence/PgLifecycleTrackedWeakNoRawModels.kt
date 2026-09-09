package me.manga.kira.backend.common.infrastructure.persistence

import java.io.IOException
import java.lang.reflect.InvocationTargetException
import kotlin.concurrent.withLock

/** One extra MODEL observation token on the actual PRIMARY; no socket/native call is claimed to be executing. */
internal class PgLifecycleTrackedWeakNoRawPendingCall(private val transport: PgLifecycleTrackedWeakNoRawTransport) : AutoCloseable {
    private var retained: PersistenceTransportCall? = null
    private var released = false
    val call: PersistenceTransportCall get() = requireNotNull(retained)

    fun admit() {
        check(retained == null)
        transport.physical.ledger.lock.withLock {
            check(transport.physical.ledger.current(transport.entry.record) === transport.entry)
            transport.lock.withLock {
                transport.requireIdentity()
                retained = requireNotNull(transport.owner.tryBeginCall(transport.record, PersistenceTransportCallKind.OBSERVATION))
            }
        }
        check(call.record === transport.record && call.kind === PersistenceTransportCallKind.OBSERVATION)
    }

    fun release() {
        val token = retained ?: return
        if (!released) {
            check(transport.owner.completeCall(token))
            released = true
        }
    }

    override fun close() {
        release()
        check(retained == null || released)
        println("PG_LIFECYCLE_TRACKED_WEAK_NO_RAW_MODEL_CALL_CLEANUP token_released=true proof=MODEL_BOOKKEEPING")
    }
}

/**
 * MODEL-only bound AUX constructor throws before allocating any native/raw resource. Its explicit MODEL
 * outer-extent end is necessary: a weak record has no Timer boundary that could end this AUX implicitly.
 * The real PRIMARY, its close/outer facts, Driver result, resource phase, F1 and terminal body are never rewritten.
 */
internal class PgLifecycleTrackedWeakNoRawFailedConstruction(private val transport: PgLifecycleTrackedWeakNoRawTransport) : AutoCloseable {
    private val extent = PersistenceTransportExtent(transport.source, PersistenceTransportRole.AUX_CANCEL)
    private val ticket = transport.owner.prepareBoundConstruction(extent)
    private val expectedFailure = IOException("Synthetic inert MODEL AUX constructor has no raw result.")
    private var callbackRecord: PersistenceTransportRecord? = null
    private var callbackCalls = 0
    private var callbackUnlocked = false
    private var callbackExited = false
    private var modelOuterEnded = false
    private val constructor: (PersistenceTransportRecord) -> AutoCloseable = { record ->
        callbackRecord = record
        callbackCalls++
        callbackUnlocked = !transport.physical.ledger.lock.isHeldByCurrentThread &&
            !transport.physical.rendezvous.lock.isHeldByCurrentThread && !transport.owner.ownershipLockHeld()
        try {
            throw expectedFailure
        } finally {
            callbackExited = true
        }
    }

    fun installAndFail() {
        check(callbackCalls == 0)
        transport.physical.ledger.lock.withLock {
            check(transport.physical.ledger.current(transport.entry.record) === transport.entry)
            check(!transport.entry.retiring && !transport.entry.retirementRequested.get())
            // Explicit MODEL owner-level association, not authenticated pgjdbc AUX provenance.
            check(invokeOwner("prepareBoundReservation", ticket) == null)
            check(invokeOwner("reserveBoundConstruction", ticket) == null)
        }
        val outcome = constructOnce()
        // An arbitrary assertion, wrong exception or missed constructor cannot satisfy this negative oracle.
        check(outcome.exceptionOrNull() === expectedFailure && callbackCalls == 1 && callbackRecord === ticket.record && callbackUnlocked)
        requireKnownFailure(fenced = false)
    }

    private fun constructOnce(): Result<Any?> {
        check(callbackCalls == 0 && ticket.entry.invocation.get() === PersistenceTransportInvocation.RESERVED)
        return try {
            runCatching { invokeOwner("constructReserved", ticket, constructor) }
        } finally {
            finishModelOuterIfEnded()
        }
    }

    fun requireKnownFailure(fenced: Boolean) {
        transport.lock.withLock {
            transport.requireIdentity()
            check(transport.slots[PersistenceTransportRole.AUX_CANCEL.ordinal] === ticket.entry)
            check(ticket.record !== transport.record && ticket.entry.extent === extent && extent.source === transport.source)
            check(ticket.record.role === PersistenceTransportRole.AUX_CANCEL)
            check(ticket.entry.invocation.get() === PersistenceTransportInvocation.THREW)
            check(ticket.entry.construction === PersistenceTransportConstruction.FAILED && ticket.entry.raw.get() == null)
            check(callbackExited && modelOuterEnded && ticket.entry.extentEnded)
            check(ticket.entry.firstClose === PersistenceTransportClosePhase.NOT_STARTED)
            check(ticket.entry.business.all { it == null } && ticket.entry.observations.all { it == null })
            check(ticket.entry.businessSealed && ticket.entry.allCallsSealed == fenced)
        }
    }

    private fun finishModelOuterIfEnded() {
        if (!callbackExited || modelOuterEnded) return
        transport.lock.withLock {
            check(ticket.entry.invocation.get() === PersistenceTransportInvocation.THREW)
            check(ticket.entry.construction === PersistenceTransportConstruction.FAILED)
            // The only synthetic receipt write. This is explicitly MODEL, NOT a driver/native AUX exit or disposal certificate.
            ticket.entry.extentEnded = true
            modelOuterEnded = true
        }
    }

    override fun close() {
        // A charged but never-invoked MODEL reservation still owns its precreated callback. This is its first use, not a retry.
        if (ticket.entry.invocation.get() === PersistenceTransportInvocation.RESERVED) {
            check(constructOnce().exceptionOrNull() === expectedFailure)
        }
        finishModelOuterIfEnded()
        val known = transport.lock.withLock { transport.slots[PersistenceTransportRole.AUX_CANCEL.ordinal] === ticket.entry }
        check(!known || (callbackExited && modelOuterEnded))
        println(
            "PG_LIFECYCLE_TRACKED_WEAK_NO_RAW_MODEL_CONSTRUCTION_CLEANUP known=$known constructor_calls=$callbackCalls " +
                "model_outer_ended=$modelOuterEnded proof=MODEL_NO_NATIVE_ALLOCATION",
        )
    }

    /** Erased own-project generic calls only; reflection never targets pgjdbc/JDK private members. */
    private fun invokeOwner(name: String, vararg arguments: Any): Any? = try {
        transport.owner.javaClass.declaredMethods.single { it.name == name }.invoke(transport.owner, *arguments)
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }
}
