package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame

/** Synthetic extent/source/receipt identities prove ledger transitions, not authenticated pgjdbc contacts. */
internal class TransportBoundModel(
    val owner: PersistenceTransportOwner<TransportModelResource>,
    val ticket: PersistenceTransportConstructionTicket<TransportModelResource>,
    val resource: TransportModelResource,
) {
    val record: PersistenceTransportRecord get() = ticket.record

    fun completeBody(): PersistenceTransportAuxiliaryCloseReceipt {
        val receipt = requireNotNull(owner.captureAuxiliaryClose(record, requireNotNull(ticket.entry.extent), resource))
        check(owner.completeAuxiliaryClose(receipt))
        return receipt
    }
}

internal fun modelBoundTransport(
    scope: TransportTestScope,
    owner: PersistenceTransportOwner<TransportModelResource>,
    source: PersistenceTransportExtentSource,
    role: PersistenceTransportRole = PersistenceTransportRole.AUX_CANCEL,
    closeAction: () -> Unit = {},
): TransportBoundModel {
    val ticket = owner.prepareBoundConstruction(PersistenceTransportExtent(source, role))
    scope.own(AutoCloseable { owner.requestClose(ticket.record) })
    assertNull(owner.prepareBoundReservation(ticket))
    if (role === PersistenceTransportRole.PRIMARY) ticket.predecessor?.let { owner.requestClose(it) }
    assertNull(owner.reserveBoundConstruction(ticket))
    val created = owner.constructReserved(ticket) { TransportModelResource(owner, it, closeAction) }
        as PersistenceTransportCreation.Created<TransportModelResource>
    assertSame(ticket.record, created.record)
    return TransportBoundModel(owner, ticket, created.resource)
}

internal fun <T : AutoCloseable> assertBoundConstructorRefused(owner: PersistenceTransportOwner<T>, ticket: PersistenceTransportConstructionTicket<T>) {
    val result = owner.constructReserved(ticket) { error("A refused bound attempt must not reach a constructor.") }
    check(result is PersistenceTransportCreation.Refused && result.reason === PersistenceTransportRefusal.INVALID_CONSTRUCTION)
}
