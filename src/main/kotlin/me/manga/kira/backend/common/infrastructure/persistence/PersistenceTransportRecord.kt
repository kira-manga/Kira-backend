package me.manga.kira.backend.common.infrastructure.persistence

/** A role is an index hint only. Every transition also checks this exact identity. */
internal class PersistenceTransportRecord(internal val role: PersistenceTransportRole) {
    override fun toString(): String = "PersistenceTransportRecord(redacted)"
}

internal class PersistenceTransportCall(
    internal val record: PersistenceTransportRecord,
    internal val kind: PersistenceTransportCallKind,
    internal val slotHint: Int,
) {
    override fun toString(): String = "PersistenceTransportCall(redacted)"
}

/** The pre-owned first-close producer identity, never a disposal capability. */
internal class PersistenceTransportCloseClaim(internal val record: PersistenceTransportRecord) {
    override fun toString(): String = "PersistenceTransportCloseClaim(redacted)"
}
