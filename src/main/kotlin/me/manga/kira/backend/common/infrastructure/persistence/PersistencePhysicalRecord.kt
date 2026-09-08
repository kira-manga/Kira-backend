package me.manga.kira.backend.common.infrastructure.persistence

/** An index hint is not authority. The registry must also match this exact identity. */
internal class PersistencePhysicalRecord(internal val slotHint: Int) {
    override fun toString(): String = "PersistencePhysicalRecord(redacted)"
}

/** One terminal decision identity, not a task-completion or physical-disposal capability. */
internal class PersistencePhysicalTerminalClaim(internal val record: PersistencePhysicalRecord) {
    override fun toString(): String = "PersistencePhysicalTerminalClaim(redacted)"
}
