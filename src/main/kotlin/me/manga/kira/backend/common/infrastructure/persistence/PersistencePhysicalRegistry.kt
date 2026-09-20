package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.Connection
import kotlin.concurrent.withLock

/**
 * Unused physical-ownership interlock, not a pool, transport owner or cleanup executor.
 * Dispatched records deliberately cannot be reclaimed until a later real-receipt composition exists.
 */
internal class PersistencePhysicalRegistry internal constructor(private val ledger: PersistencePhysicalLedger) {
    constructor(capacity: Int) : this(PersistencePhysicalLedger(capacity, owned = false))

    private val capacity = ledger.capacity
    private val lock = ledger.lock
    private val entries = ledger.entries
    private var sealed: Boolean
        get() = ledger.sealed
        set(value) {
            ledger.sealed = value
        }

    fun tryReserve(): PersistencePhysicalReservation {
        check(!ledger.owned)
        if (!lock.tryLock()) return PersistencePhysicalReservation.Refused(PersistencePhysicalRefusal.CONTENDED)
        return try {
            if (sealed) return PersistencePhysicalReservation.Refused(PersistencePhysicalRefusal.SEALED)
            val slot = entries.indexOfFirst { it == null }
            if (slot < 0) return PersistencePhysicalReservation.Refused(PersistencePhysicalRefusal.FULL)
            val record = PersistencePhysicalRecord(slot)
            val entry = PersistencePhysicalEntry(record)
            val accepted = PersistencePhysicalReservation.Accepted(record)
            // Allocate all packaging before installing ownership.
            entries[slot] = entry
            accepted
        } finally {
            lock.unlock()
        }
    }

    /** Accounting before a future dispatch, not proof that a factory accepted or completed work. */
    fun claimDispatch(record: PersistencePhysicalRecord): Boolean {
        check(!ledger.owned)
        return lock.withLock {
            val entry = current(record) ?: return@withLock false
            if (sealed || entry.retiring || entry.dispatched) return@withLock false
            entry.dispatched = true
            true
        }
    }

    fun releaseUnused(record: PersistencePhysicalRecord): Boolean {
        check(!ledger.owned)
        return lock.withLock {
            val entry = current(record) ?: return@withLock false
            if (entry.dispatched || entry.opening != PersistencePhysicalOpeningPhase.UNCLAIMED) return@withLock false
            if (entry.raw.get() != null || entry.terminal != null) return@withLock false
            entries[record.slotHint] = null
            true
        }
    }

    /**
     * The future private callback must return Driver.connect's raw result directly, without wrapping it.
     * G1 supplies no nonterminal raw handoff; it must not be wired into Hikari on its own.
     */
    fun invokeOpening(record: PersistencePhysicalRecord, operation: () -> Connection?): PersistencePhysicalOpening {
        check(!ledger.owned)
        // This observation is not atomic with a later claim or invocation. No virtual Thread call under lock.
        if (Thread.currentThread().isInterrupted) return PersistencePhysicalOpening.INTERRUPTED
        val entry = claimOpening(record) ?: return PersistencePhysicalOpening.REFUSED
        val raw = runCatching { operation() }.getOrElse { failure ->
            // Only the actual operation is captured. Settlement/restoration/rethrow are outside that capture.
            return settleFailure(entry, failure)
        }
        // First action on a normal nullable raw return, before flags, classification or lock reacquisition.
        entry.raw.set(raw)
        val interrupted = Thread.currentThread().isInterrupted
        return settleReturn(entry, raw, interrupted)
    }

    fun requestRetirement(record: PersistencePhysicalRecord): Boolean {
        check(!ledger.owned)
        return lock.withLock {
            val entry = current(record) ?: return@withLock false
            entry.retiring = true
            true
        }
    }

    fun claimTerminal(record: PersistencePhysicalRecord): PersistencePhysicalTerminal {
        check(!ledger.owned)
        return lock.withLock {
            val entry = current(record) ?: return@withLock PersistencePhysicalTerminal.Refused
            if (!entry.retiring || entry.terminal != null) return@withLock PersistencePhysicalTerminal.Refused
            val claim = PersistencePhysicalTerminalClaim(record)
            val accepted = PersistencePhysicalTerminal.Claimed(claim)
            entry.terminal = claim
            accepted
        }
    }

    /** A grant/NO_RAW decision is not cleanup. One future terminal task must span WAITING and late raw. */
    fun takeTerminalRaw(claim: PersistencePhysicalTerminalClaim): PersistencePhysicalRawDecision {
        check(!ledger.owned)
        return lock.withLock {
            val entry = current(claim.record) ?: return@withLock PersistencePhysicalRawDecision.Refused
            if (entry.terminal !== claim) return@withLock PersistencePhysicalRawDecision.Refused
            if (entry.decisionDelivered) return@withLock PersistencePhysicalRawDecision.AlreadyTaken
            if (entry.opening == PersistencePhysicalOpeningPhase.ACTIVE) return@withLock PersistencePhysicalRawDecision.WaitingForOpening
            val raw = entry.raw.get()
            val decision = if (raw == null) PersistencePhysicalRawDecision.NoRawReturned else PersistencePhysicalRawDecision.Granted(raw)
            // Do not consume the one decision before successful packaging. Never clear the retained raw.
            entry.decisionDelivered = true
            decision
        }
    }

    fun seal(): Boolean {
        check(!ledger.owned)
        return lock.withLock {
            if (sealed) return@withLock false
            sealed = true
            entries.forEach { entry -> entry?.retiring = true }
            true
        }
    }

    fun snapshot(): PersistencePhysicalSnapshot {
        if (ledger.owned) return PersistencePhysicalSnapshot.Unavailable
        if (!lock.tryLock()) return PersistencePhysicalSnapshot.Unavailable
        return try {
            var occupied = 0
            var opening = 0
            var live = 0
            var retiring = 0
            var unknown = 0
            var activeOpening = 0
            var terminalClaimed = 0
            for (entry in entries) {
                if (entry == null) continue
                occupied++
                when {
                    entry.unknown -> unknown++
                    entry.retiring -> retiring++
                    entry.opening == PersistencePhysicalOpeningPhase.SETTLED -> live++
                    else -> opening++
                }
                if (entry.opening == PersistencePhysicalOpeningPhase.ACTIVE) activeOpening++
                if (entry.terminal != null) terminalClaimed++
            }
            PersistencePhysicalSnapshot.Available(capacity, sealed, occupied, opening, live, retiring, unknown, activeOpening, terminalClaimed)
        } finally {
            lock.unlock()
        }
    }

    private fun current(record: PersistencePhysicalRecord): PersistencePhysicalEntry? = ledger.current(record)

    private fun claimOpening(record: PersistencePhysicalRecord): PersistencePhysicalEntry? = lock.withLock {
        val entry = current(record) ?: return@withLock null
        if (sealed || entry.retiring) return@withLock null
        if (!entry.dispatched || entry.opening != PersistencePhysicalOpeningPhase.UNCLAIMED) return@withLock null
        entry.opening = PersistencePhysicalOpeningPhase.ACTIVE
        entry
    }

    private fun settleReturn(entry: PersistencePhysicalEntry, raw: Connection?, interrupted: Boolean): PersistencePhysicalOpening = lock.withLock {
        entry.opening = PersistencePhysicalOpeningPhase.SETTLED
        when {
            interrupted -> {
                entry.retiring = true
                entry.unknown = true
                PersistencePhysicalOpening.INTERRUPTED
            }

            raw == null -> {
                entry.retiring = true
                PersistencePhysicalOpening.NO_RAW_RETURN
            }

            entry.retiring -> PersistencePhysicalOpening.RETAINED_FOR_RETIREMENT

            else -> PersistencePhysicalOpening.RETAINED
        }
    }

    private fun settleFailure(entry: PersistencePhysicalEntry, failure: Throwable): PersistencePhysicalOpening {
        lock.withLock {
            entry.opening = PersistencePhysicalOpeningPhase.SETTLED
            entry.retiring = true
            entry.unknown = true
        }
        if (failure is InterruptedException) {
            Thread.currentThread().interrupt()
            return PersistencePhysicalOpening.INTERRUPTED
        }
        if (failure is Error) throw failure
        return PersistencePhysicalOpening.FAILED
    }

    override fun toString(): String = "PersistencePhysicalRegistry(redacted)"
}
