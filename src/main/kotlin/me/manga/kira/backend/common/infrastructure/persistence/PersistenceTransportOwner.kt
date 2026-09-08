package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Private two-role transport ledger. Only source-bound, independently disposed entries can rotate.
 * The trusted constructor must return its raw result directly; this is not containment of arbitrary callbacks.
 */
internal class PersistenceTransportOwner<T : AutoCloseable> {
    private val lock = ReentrantLock()
    private val entries = arrayOfNulls<PersistenceTransportEntry<T>>(2)
    private var sealed = false
    private var revision = 0L
    private var exhausted = false

    fun tryCreate(role: PersistenceTransportRole, constructor: (PersistenceTransportRecord) -> T): PersistenceTransportCreation<T> {
        val ticket = prepareConstruction(role)
        val refusal = reserveConstruction(ticket)
        if (refusal != null) return PersistenceTransportCreation.Refused(refusal)
        return constructReserved(ticket, constructor)
    }

    /** No lock, publication, callback or Socket constructor. The composed bridge also precreates its binding here. */
    fun prepareConstruction(role: PersistenceTransportRole): PersistenceTransportConstructionTicket<T> =
        PersistenceTransportEntry(this, PersistenceTransportRecord(role)).ticket

    /** The concrete bridge authenticates the source. This method only prepares inert bookkeeping. */
    fun prepareBoundConstruction(extent: PersistenceTransportExtent): PersistenceTransportConstructionTicket<T> =
        PersistenceTransportEntry(this, PersistenceTransportRecord(extent.role), extent).ticket

    fun ownershipLockHeld(): Boolean = lock.isHeldByCurrentThread

    /** One immediate bookkeeping transition; safe beneath G, with no constructor callback beneath G/T. */
    fun reserveConstruction(ticket: PersistenceTransportConstructionTicket<T>): PersistenceTransportRefusal? {
        if (ticket.owner !== this || ticket.entry.ticket !== ticket || ticket.entry.extent != null) return PersistenceTransportRefusal.INVALID_CONSTRUCTION
        val entry = ticket.entry
        if (!entry.invocation.compareAndSet(PersistenceTransportInvocation.PREPARED, PersistenceTransportInvocation.RESERVING)) {
            return PersistenceTransportRefusal.INVALID_CONSTRUCTION
        }
        if (!lock.tryLock()) {
            entry.invocation.set(PersistenceTransportInvocation.REFUSED)
            return PersistenceTransportRefusal.CONTENDED
        }
        return try {
            val refusal = when {
                exhausted -> PersistenceTransportRefusal.EXHAUSTED
                sealed -> PersistenceTransportRefusal.SEALED
                entries[entry.record.role.ordinal] != null -> PersistenceTransportRefusal.FULL
                else -> null
            }
            if (refusal != null) {
                entry.invocation.set(PersistenceTransportInvocation.REFUSED)
                return refusal
            }
            // All cells/binding packaging precede this installation; RESERVED is published last.
            entries[entry.record.role.ordinal] = entry
            advance()
            entry.invocation.set(PersistenceTransportInvocation.RESERVED)
            null
        } finally {
            lock.unlock()
        }
    }

    /** First G→T cut. Source-authenticated PRIMARY succession ends/fences its predecessor, never an active AUX. */
    fun prepareBoundReservation(ticket: PersistenceTransportConstructionTicket<T>): PersistenceTransportRefusal? {
        val refusal = beginBoundReservation(ticket, PersistenceTransportInvocation.PREPARED)
        if (refusal != null) return refusal
        val entry = ticket.entry
        return try {
            val unavailable = boundAvailability()
            if (unavailable != null) return refuseBound(entry, unavailable)
            val predecessor = entries[entry.record.role.ordinal]
            if (predecessor != null) {
                if (!sameExtentSource(entry, predecessor)) return refuseBound(entry, PersistenceTransportRefusal.FULL)
                if (entry.record.role === PersistenceTransportRole.PRIMARY) {
                    if (!predecessor.extentEnded || !predecessor.allCallsSealed || !predecessor.businessSealed) {
                        predecessor.extentEnded = true
                        predecessor.allCallsSealed = true
                        predecessor.businessSealed = true
                        advance()
                    }
                } else if (!predecessor.extentEnded) {
                    return refuseBound(entry, PersistenceTransportRefusal.FULL)
                }
            }
            ticket.predecessor = predecessor?.record
            entry.invocation.set(PersistenceTransportInvocation.ROTATION_READY)
            null
        } finally {
            lock.unlock()
        }
    }

    /** Second G→T cut. No snapshot or requested/coalesced close can authorize replacement. */
    fun reserveBoundConstruction(ticket: PersistenceTransportConstructionTicket<T>): PersistenceTransportRefusal? {
        val refusal = beginBoundReservation(ticket, PersistenceTransportInvocation.ROTATION_READY)
        if (refusal != null) return refusal
        val entry = ticket.entry
        return try {
            val unavailable = boundAvailability()
            if (unavailable != null) return refuseBound(entry, unavailable)
            val predecessor = entries[entry.record.role.ordinal]
            if (predecessor?.record !== ticket.predecessor) return refuseBound(entry, PersistenceTransportRefusal.INVALID_CONSTRUCTION)
            if (predecessor != null && (!sameExtentSource(entry, predecessor) || !disposed(predecessor))) {
                return refuseBound(entry, PersistenceTransportRefusal.FULL)
            }
            // The revision check precedes replacement. No absent-slot interval or retained predecessor graph.
            entries[entry.record.role.ordinal] = entry
            advance()
            entry.invocation.set(PersistenceTransportInvocation.RESERVED)
            null
        } finally {
            lock.unlock()
        }
    }

    /** Physical refusal, including a failed outside-lock close, cannot leave a reusable prepared grant. */
    fun abandonBoundConstruction(ticket: PersistenceTransportConstructionTicket<T>) {
        if (ticket.owner !== this || ticket.entry.ticket !== ticket || ticket.entry.extent == null) return
        ticket.entry.invocation.compareAndSet(PersistenceTransportInvocation.PREPARED, PersistenceTransportInvocation.REFUSED)
        ticket.entry.invocation.compareAndSet(PersistenceTransportInvocation.ROTATION_READY, PersistenceTransportInvocation.REFUSED)
    }

    /** Called only after the concrete adapter authenticates the exact current-stack/raw binding. */
    fun captureAuxiliaryClose(record: PersistenceTransportRecord, extent: PersistenceTransportExtent, raw: T): PersistenceTransportAuxiliaryCloseReceipt? =
        lock.withLock {
            val entry = current(record) ?: return@withLock null
            if (extent.role !== PersistenceTransportRole.AUX_CANCEL || entry.extent !== extent || entry.raw.get() !== raw) return@withLock null
            entry.auxiliaryCloseReceipt
        }

    /** Final trusted bookkeeping after normal binding-body return; never repairs a failed/running first close. */
    fun completeAuxiliaryClose(receipt: PersistenceTransportAuxiliaryCloseReceipt): Boolean = lock.withLock {
        val entry = current(receipt.record) ?: return@withLock false
        if (entry.extent !== receipt.extent || entry.auxiliaryCloseReceipt !== receipt || entry.extentEnded) return@withLock false
        entry.extentEnded = true
        entry.allCallsSealed = true
        entry.businessSealed = true
        advance()
        true
    }

    /** Run only after the enclosing physical lock is released. A pre-fence reservation remains charged. */
    fun constructReserved(ticket: PersistenceTransportConstructionTicket<T>, constructor: (PersistenceTransportRecord) -> T): PersistenceTransportCreation<T> {
        if (ticket.owner !== this || ticket.entry.ticket !== ticket || lock.isHeldByCurrentThread) {
            return PersistenceTransportCreation.Refused(PersistenceTransportRefusal.INVALID_CONSTRUCTION)
        }
        val entry = ticket.entry
        if (!entry.invocation.compareAndSet(PersistenceTransportInvocation.RESERVED, PersistenceTransportInvocation.INVOKING)) {
            return PersistenceTransportCreation.Refused(PersistenceTransportRefusal.INVALID_CONSTRUCTION)
        }
        val raw = runCatching { constructor(entry.record) }.getOrElse { failure ->
            entry.invocation.set(PersistenceTransportInvocation.THREW)
            constructionFailed(entry)
            throw failure
        }
        // The first action after the direct normal return, before settlement or result packaging.
        entry.raw.set(raw)
        entry.invocation.set(PersistenceTransportInvocation.RETURNED)
        return settleConstruction(entry, raw)
    }

    fun tryBeginCall(record: PersistenceTransportRecord, kind: PersistenceTransportCallKind): PersistenceTransportCall? {
        if (!lock.tryLock()) return null
        return try {
            val entry = current(record) ?: return null
            if (exhausted || entry.allCallsSealed) return null
            if (kind == PersistenceTransportCallKind.BUSINESS && entry.businessSealed) return null
            val cells = entry.cells(kind)
            val slot = cells.indexOfFirst { it == null }
            if (slot < 0) return null
            val call = PersistenceTransportCall(record, kind, slot)
            // The ticket is fully allocated before its cell becomes active.
            cells[slot] = call
            advance()
            call
        } finally {
            lock.unlock()
        }
    }

    /** Reliable completion, not a second tryLock that could drop a real exit. */
    fun completeCall(call: PersistenceTransportCall): Boolean = lock.withLock {
        val entry = current(call.record) ?: return@withLock false
        val cells = entry.cells(call.kind)
        if (call.slotHint !in cells.indices) return@withLock false
        if (cells[call.slotHint] !== call) return@withLock false
        cells[call.slotHint] = null
        advance()
        true
    }

    /** Independent first-close capacity. Only the retained exact resource can become its producer. */
    fun claimClose(record: PersistenceTransportRecord, raw: T): PersistenceTransportCloseClaim? = lock.withLock {
        val entry = current(record) ?: return@withLock null
        if (entry.raw.get() !== raw) return@withLock null
        if (entry.firstClose != PersistenceTransportClosePhase.NOT_STARTED) return@withLock null
        entry.businessSealed = true
        entry.firstClose = PersistenceTransportClosePhase.RUNNING
        advance()
        entry.closeClaim
    }

    fun completeClose(claim: PersistenceTransportCloseClaim, acknowledged: Boolean): Boolean = lock.withLock {
        val entry = current(claim.record) ?: return@withLock false
        if (entry.closeClaim !== claim) return@withLock false
        if (entry.firstClose != PersistenceTransportClosePhase.RUNNING) return@withLock false
        entry.firstClose = if (acknowledged) PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED else PersistenceTransportClosePhase.FAILED
        advance()
        true
    }

    fun requestClose(record: PersistenceTransportRecord): PersistenceTransportCloseRequest {
        val request = lock.withLock {
            val entry = current(record) ?: return@withLock CloseRequest<T>(null, PersistenceTransportCloseRequest.REFUSED)
            val raw = entry.raw.get()
            val status = when {
                raw != null -> PersistenceTransportCloseRequest.REQUESTED
                entry.construction == PersistenceTransportConstruction.ACTIVE -> PersistenceTransportCloseRequest.WAITING_FOR_RAW
                else -> PersistenceTransportCloseRequest.NO_RAW_RETURNED
            }
            CloseRequest(raw, status)
        }
        // Never invoke the resource while holding the ledger lock. This return is not a close receipt.
        request.raw?.close()
        return request.status
    }

    fun seal(record: PersistenceTransportRecord): Boolean = lock.withLock {
        val entry = current(record) ?: return@withLock false
        if (entry.businessSealed) return@withLock false
        entry.businessSealed = true
        advance()
        true
    }

    fun seal(): Boolean = lock.withLock {
        if (sealed) return@withLock false
        sealed = true
        entries.forEach { it?.businessSealed = true }
        advance()
        true
    }

    /** Concrete G→T retirement uses this immediate fence, never the legacy blocking seal. Not a disposal receipt. */
    fun trySealForRetirement(): Boolean {
        if (!lock.tryLock()) return false
        return try {
            if (!sealed) {
                sealed = true
                entries.forEach { it?.businessSealed = true }
                advance()
            }
            true
        } finally {
            lock.unlock()
        }
    }

    /** Permanent fence, including OBSERVATION. Previously admitted tokens/first close remain independently owned. */
    fun tryFenceCalls(record: PersistenceTransportRecord): Boolean {
        if (!lock.tryLock()) return false
        return try {
            val entry = current(record) ?: return false
            if (!entry.allCallsSealed) {
                entry.allCallsSealed = true
                entry.businessSealed = true
                advance()
            }
            true
        } finally {
            lock.unlock()
        }
    }

    /** Final retirement fence, unlike the public BUSINESS-only seal. No raw operation runs here. */
    fun tryFenceTerminal(): Boolean {
        if (!lock.tryLock()) return false
        return try {
            val changed = !sealed || entries.any { it != null && (!it.businessSealed || !it.allCallsSealed) }
            sealed = true
            entries.forEach { entry ->
                entry?.businessSealed = true
                entry?.allCallsSealed = true
            }
            if (changed) advance()
            true
        } finally {
            lock.unlock()
        }
    }

    /** The concrete terminal runner repeats this for late constructor returns; first-close facts never reset. */
    fun closeTerminalTransports() {
        check(!lock.isHeldByCurrentThread)
        var fatal: Error? = null
        for (slot in entries.indices) {
            val raw = lock.withLock {
                val entry = entries[slot]
                if (entry?.firstClose === PersistenceTransportClosePhase.NOT_STARTED) entry.raw.get() else null
            }
            runCatching { raw?.close() }.onFailure { failure ->
                // TrackedPersistenceSocket recorded its first actual close outcome before throwing.
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                if (failure is Error) fatal = failure
            }
        }
        fatal?.let { throw it }
    }

    /** Managed LIVE can only accept known settled constructor/call facts; no raw call or liveness probe here. */
    fun liveFailure(source: PersistenceTransportExtentSource?): PersistenceFactoryFailure? {
        if (!lock.tryLock()) return PersistenceFactoryFailure.BUSY
        return try {
            if (sealed || exhausted) return PersistenceFactoryFailure.NOT_READY
            if (source == null || !source.primaryOpeningEnded.get()) return PersistenceFactoryFailure.NOT_READY
            if (entries.any { it != null && !liveEntrySettled(it, source) }) PersistenceFactoryFailure.NOT_READY else null
        } finally {
            lock.unlock()
        }
    }

    private fun liveEntrySettled(entry: PersistenceTransportEntry<T>, source: PersistenceTransportExtentSource): Boolean {
        val constructed = entry.invocation.get() === PersistenceTransportInvocation.RETURNED &&
            entry.construction === PersistenceTransportConstruction.RETURNED && entry.raw.get() != null
        val drained = entry.business.all { it == null } && entry.observations.all { it == null }
        val role = if (entry.record.role === PersistenceTransportRole.PRIMARY) {
            !entry.businessSealed && !entry.allCallsSealed && entry.firstClose === PersistenceTransportClosePhase.NOT_STARTED
        } else {
            disposed(entry)
        }
        return constructed && drained && role && entry.extent?.source === source
    }

    /** Called by the exact G-owned binding after its real producer/scope drain, never with a public snapshot. */
    fun terminalState(
        source: PersistenceTransportExtentSource?,
        boundary: PersistenceTimerBoundary?,
        failedTimerWorkEnded: Boolean,
    ): PersistenceTerminalTransportState {
        if (!lock.tryLock()) return PersistenceTerminalTransportState.PENDING
        return try {
            if (!sealed) return PersistenceTerminalTransportState.PENDING
            var failed = exhausted
            for (entry in entries) {
                if (entry == null) continue
                when (terminalEntryState(entry, source, boundary, failedTimerWorkEnded)) {
                    PersistenceTerminalTransportState.PENDING -> return PersistenceTerminalTransportState.PENDING
                    PersistenceTerminalTransportState.FAILED_ENDED -> failed = true
                    PersistenceTerminalTransportState.DISPOSED -> Unit
                }
            }
            if (failed) PersistenceTerminalTransportState.FAILED_ENDED else PersistenceTerminalTransportState.DISPOSED
        } finally {
            lock.unlock()
        }
    }

    private fun terminalEntryState(
        entry: PersistenceTransportEntry<T>,
        source: PersistenceTransportExtentSource?,
        boundary: PersistenceTimerBoundary?,
        failedTimerWorkEnded: Boolean,
    ): PersistenceTerminalTransportState {
        val invocation = entry.invocation.get()
        val constructionEnded = invocation === PersistenceTransportInvocation.RETURNED || invocation === PersistenceTransportInvocation.THREW
        val fenced = entry.allCallsSealed && entry.businessSealed
        val drained = entry.business.all { it == null } && entry.observations.all { it == null }
        val constructionSettled = constructionEnded && entry.construction !== PersistenceTransportConstruction.ACTIVE
        if (!fenced || !drained || !constructionSettled) {
            return PersistenceTerminalTransportState.PENDING
        }
        val outerEnded = entry.extentEnded || (
            source != null && entry.extent?.source === source &&
                (
                    (source.primaryOpeningEnded.get() && entry.record.role === PersistenceTransportRole.PRIMARY) ||
                        boundary?.acknowledged() == true || failedTimerWorkEnded
                    )
            )
        if (!outerEnded) return PersistenceTerminalTransportState.PENDING
        // Even a failed no-raw constructor must first prove its enclosing work ended. Native disposal is still unknown.
        if (entry.raw.get() == null) return PersistenceTerminalTransportState.FAILED_ENDED
        val closeEnded = entry.firstClose === PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED ||
            entry.firstClose === PersistenceTransportClosePhase.FAILED
        return when {
            !closeEnded -> PersistenceTerminalTransportState.PENDING
            entry.firstClose === PersistenceTransportClosePhase.FAILED -> PersistenceTerminalTransportState.FAILED_ENDED
            else -> PersistenceTerminalTransportState.DISPOSED
        }
    }

    fun snapshot(): PersistenceTransportSnapshot {
        if (!lock.tryLock()) return PersistenceTransportSnapshot.Unavailable
        return try {
            PersistenceTransportSnapshot.Available(
                sealed,
                revision,
                exhausted,
                entries[0]?.snapshot(exhausted),
                entries[1]?.snapshot(exhausted),
            )
        } finally {
            lock.unlock()
        }
    }

    private fun current(record: PersistenceTransportRecord): PersistenceTransportEntry<T>? = entries[record.role.ordinal]?.takeIf { it.record === record }

    /** Success retains T for the caller's closed bookkeeping body. No reentrant T may escape to a raw close. */
    private fun beginBoundReservation(ticket: PersistenceTransportConstructionTicket<T>, phase: PersistenceTransportInvocation): PersistenceTransportRefusal? {
        val entry = ticket.entry
        if (ticket.owner !== this || entry.ticket !== ticket || entry.extent?.role !== entry.record.role) {
            return PersistenceTransportRefusal.INVALID_CONSTRUCTION
        }
        if (!entry.invocation.compareAndSet(phase, PersistenceTransportInvocation.RESERVING)) return PersistenceTransportRefusal.INVALID_CONSTRUCTION
        if (lock.isHeldByCurrentThread) return refuseBound(entry, PersistenceTransportRefusal.INVALID_CONSTRUCTION)
        if (!lock.tryLock()) return refuseBound(entry, PersistenceTransportRefusal.CONTENDED)
        return null
    }

    private fun refuseBound(entry: PersistenceTransportEntry<T>, reason: PersistenceTransportRefusal): PersistenceTransportRefusal {
        entry.invocation.set(PersistenceTransportInvocation.REFUSED)
        return reason
    }

    private fun boundAvailability(): PersistenceTransportRefusal? = when {
        exhausted -> PersistenceTransportRefusal.EXHAUSTED

        sealed -> PersistenceTransportRefusal.SEALED

        revision >= Long.MAX_VALUE - 1 -> {
            advance()
            PersistenceTransportRefusal.EXHAUSTED
        }

        else -> null
    }

    private fun sameExtentSource(entry: PersistenceTransportEntry<T>, predecessor: PersistenceTransportEntry<T>): Boolean =
        predecessor.extent != null && predecessor.extent.source === entry.extent?.source && predecessor.extent.role === entry.record.role

    private fun disposed(entry: PersistenceTransportEntry<T>): Boolean =
        entry.invocation.get() === PersistenceTransportInvocation.RETURNED && entry.construction === PersistenceTransportConstruction.RETURNED &&
            entry.raw.get() != null && entry.firstClose === PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED &&
            entry.business.all { it == null } && entry.observations.all { it == null } && entry.allCallsSealed && entry.businessSealed &&
            (entry.extentEnded || (entry.extent?.role === PersistenceTransportRole.PRIMARY && entry.extent.source.primaryOpeningEnded.get()))

    private fun settleConstruction(entry: PersistenceTransportEntry<T>, raw: T): PersistenceTransportCreation<T> = lock.withLock {
        val open = !sealed && !entry.businessSealed && !exhausted
        // If settlement itself exhausts the revision, do not deliver a newly fenced resource.
        val result = if (open && revision < Long.MAX_VALUE - 1) {
            PersistenceTransportCreation.Created(entry.record, raw)
        } else {
            entry.retained
        }
        entry.construction = PersistenceTransportConstruction.RETURNED
        advance()
        result
    }

    private fun constructionFailed(entry: PersistenceTransportEntry<T>) = lock.withLock {
        entry.construction = PersistenceTransportConstruction.FAILED
        entry.businessSealed = true
        advance()
    }

    private fun advance() {
        if (revision < Long.MAX_VALUE) revision++
        if (revision == Long.MAX_VALUE) {
            exhausted = true
            sealed = true
            entries.forEach { it?.businessSealed = true }
        }
    }

    override fun toString(): String = "PersistenceTransportOwner(redacted)"

    private class CloseRequest<T : AutoCloseable>(val raw: T?, val status: PersistenceTransportCloseRequest)
}
