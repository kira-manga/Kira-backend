package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicReference

/** Prepared without allocation authority. Only its exact owner can publish this one-use ticket. */
internal class PersistenceTransportConstructionTicket<T : AutoCloseable>(
    internal val owner: PersistenceTransportOwner<T>,
    internal val entry: PersistenceTransportEntry<T>,
) {
    val record: PersistenceTransportRecord get() = entry.record

    // Only an identity, never the predecessor Entry/raw/ticket graph. ROTATION_READY publishes this selection.
    internal var predecessor: PersistenceTransportRecord? = null

    override fun toString(): String = "PersistenceTransportConstructionTicket(redacted)"
}

internal enum class PersistenceTransportInvocation {
    PREPARED,
    RESERVING,
    ROTATION_READY,
    REFUSED,
    RESERVED,
    INVOKING,
    RETURNED,
    THREW,
}

/** All cells and first-close/result identities exist before either ledger publication or native construction. */
internal class PersistenceTransportEntry<T : AutoCloseable>(
    owner: PersistenceTransportOwner<T>,
    val record: PersistenceTransportRecord,
    val extent: PersistenceTransportExtent? = null,
) {
    val raw = AtomicReference<T?>()
    val invocation = AtomicReference(PersistenceTransportInvocation.PREPARED)
    val business = arrayOfNulls<PersistenceTransportCall>(32)
    val observations = arrayOfNulls<PersistenceTransportCall>(8)
    val closeClaim = PersistenceTransportCloseClaim(record)
    val retained = PersistenceTransportCreation.Retained(record)
    val auxiliaryCloseReceipt = if (extent?.role === PersistenceTransportRole.AUX_CANCEL) PersistenceTransportAuxiliaryCloseReceipt(record, extent) else null
    var extentEnded = false
    var construction = PersistenceTransportConstruction.ACTIVE
    var businessSealed = false
    var allCallsSealed = false
    var firstClose = PersistenceTransportClosePhase.NOT_STARTED
    val ticket = PersistenceTransportConstructionTicket(owner, this)

    fun cells(kind: PersistenceTransportCallKind): Array<PersistenceTransportCall?> =
        if (kind == PersistenceTransportCallKind.BUSINESS) business else observations

    fun snapshot(exhausted: Boolean): PersistenceTransportRecordSnapshot = PersistenceTransportRecordSnapshot(
        record,
        construction,
        raw.get() != null,
        businessSealed,
        business.count { it != null },
        observations.count { it != null },
        firstClose,
        exhausted || construction == PersistenceTransportConstruction.FAILED || firstClose == PersistenceTransportClosePhase.FAILED,
        allCallsSealed,
    )

    override fun toString(): String = "PersistenceTransportEntry(redacted)"
}
