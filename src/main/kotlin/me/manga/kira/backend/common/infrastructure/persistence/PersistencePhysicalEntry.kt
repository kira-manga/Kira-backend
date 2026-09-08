package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** One G ledger, shared only by its legacy facade or its concrete private owned binding. */
internal class PersistencePhysicalLedger(val capacity: Int, val owned: Boolean) {
    init {
        require(capacity > 0) { "Physical capacity must be positive." }
    }

    val lock = ReentrantLock()
    val entries = arrayOfNulls<PersistencePhysicalEntry>(capacity)
    var sealed = false

    fun current(record: PersistencePhysicalRecord): PersistencePhysicalEntry? {
        if (record.slotHint !in entries.indices) return null
        val entry = entries[record.slotHint]
        return if (entry?.record === record) entry else null
    }

    override fun toString(): String = "PersistencePhysicalLedger(redacted)"
}

internal enum class PersistencePhysicalOpeningPhase {
    UNCLAIMED,
    ACTIVE,
    SETTLED,
}

/** Kept in the exact fixed slot. Disposal facts are never supplied by an outside snapshot. */
internal class PersistencePhysicalEntry(
    val record: PersistencePhysicalRecord,
    val control: PersistenceOwnedCallerControl? = null,
    val policy: PersistenceDriverAttemptPolicy = PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER,
    physical: PersistencePhysicalFactoryBinding? = null,
    val driverOpening: PersistencePgDriverOpening? = null,
) {
    init {
        require(driverOpening == null || driverOpening.policy === policy) { "Persistence opening policy must match its Entry." }
    }

    val raw = AtomicReference<Connection?>()
    val openingFacts = PersistenceOpeningFacts()
    val retirementRequested = AtomicBoolean()
    var dispatched = false
    var opening = PersistencePhysicalOpeningPhase.UNCLAIMED
    var retiring = false
    var unknown = false
    var terminal: PersistencePhysicalTerminalClaim? = null
    var decisionDelivered = false
    var attempt: PersistenceFactoryAttempt<PersistencePhysicalRecord, PersistenceJdbcCandidate>? = null
    val candidate = PersistenceJdbcCandidate(retirementRequested)
    var scopeEnded = false
    val transports = if (policy.recipe == PersistenceDriverExecutionRecipe.TRACKED_STANDARD) {
        PersistencePhysicalTransportBinding(requireNotNull(physical), this)
    } else {
        null
    }
    val driverScope = driverOpening?.image?.let { image ->
        // Image/class/walker preparation already happened outside G; this constructor is inert.
        PersistencePgFactoryScope(image, requireNotNull(physical), requireNotNull(transports))
    }
    val terminalWork = physical?.let { PersistenceTerminalWork(it, this) }

    override fun toString(): String = "PersistencePhysicalEntry(redacted)"
}
