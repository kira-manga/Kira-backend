package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean

/** Inert exact scope association. Opening exit is not a close, a call fence or native-disposal evidence. */
internal class PersistenceTransportExtentSource {
    val primaryOpeningEnded = AtomicBoolean()

    override fun toString(): String = "PersistenceTransportExtentSource(redacted)"
}

/** Fresh per allocation; an unbound legacy record cannot acquire this identity retroactively. */
internal class PersistenceTransportExtent(val source: PersistenceTransportExtentSource, val role: PersistenceTransportRole) {
    override fun toString(): String = "PersistenceTransportExtent(redacted)"
}

/** Preowned direct AUX binding-body receipt, distinct from the original first-close producer. */
internal class PersistenceTransportAuxiliaryCloseReceipt(val record: PersistenceTransportRecord, val extent: PersistenceTransportExtent) {
    override fun toString(): String = "PersistenceTransportAuxiliaryCloseReceipt(redacted)"
}
