package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

internal fun <T : AutoCloseable> transportSnapshot(owner: PersistenceTransportOwner<T>): PersistenceTransportSnapshot.Available {
    var snapshot = owner.snapshot()
    awaitTransportTestFact {
        snapshot = owner.snapshot()
        snapshot is PersistenceTransportSnapshot.Available
    }
    return snapshot as PersistenceTransportSnapshot.Available
}

internal fun <T : AutoCloseable> transportRecordSnapshot(
    owner: PersistenceTransportOwner<T>,
    record: PersistenceTransportRecord,
): PersistenceTransportRecordSnapshot {
    val snapshot = transportSnapshot(owner)
    val entry = requireNotNull(if (record.role == PersistenceTransportRole.PRIMARY) snapshot.primary else snapshot.auxiliary)
    assertSame(record, entry.record)
    return entry
}

/** Reads only this project's own ledger; no private JDK state or native instrumentation. */
internal fun transportTestLock(owner: PersistenceTransportOwner<*>): ReentrantLock {
    val field = PersistenceTransportOwner::class.java.getDeclaredField("lock")
    check(field.trySetAccessible())
    return field.get(owner) as ReentrantLock
}

internal fun setTransportTestRevision(owner: PersistenceTransportOwner<*>, revision: Long) {
    val field = PersistenceTransportOwner::class.java.getDeclaredField("revision")
    check(field.trySetAccessible())
    field.setLong(owner, revision)
}

internal fun retainedTransportTestRaw(owner: PersistenceTransportOwner<*>, record: PersistenceTransportRecord): Any? {
    val entries = PersistenceTransportOwner::class.java.getDeclaredField("entries")
    check(entries.trySetAccessible())
    val entry = requireNotNull((entries.get(owner) as Array<*>)[record.role.ordinal])
    val raw = entry.javaClass.getDeclaredField("raw")
    check(raw.trySetAccessible())
    return (raw.get(entry) as AtomicReference<*>).get()
}

internal class TransportSocketFixture(
    val owner: PersistenceTransportOwner<TrackedPersistenceSocket>,
    val record: PersistenceTransportRecord,
    val socket: TrackedPersistenceSocket,
) {
    // A separate binding is only for explicitly MODEL raw-stream fixtures; real acquisitions use the Socket's own binding.
    fun modelBinding(): PersistenceTransportBinding = PersistenceTransportBinding(owner, record)
}

internal fun TransportTestScope.socket(direct: Boolean = false, role: PersistenceTransportRole = PersistenceTransportRole.PRIMARY): TransportSocketFixture {
    val owner = PersistenceTransportOwner<TrackedPersistenceSocket>()
    val result = if (direct) TrackedPersistenceSocket.tryCreateApprovedDirect(owner, role) else TrackedPersistenceSocket.tryCreate(owner, role)
    val created = result as PersistenceTransportCreation.Created<TrackedPersistenceSocket>
    return TransportSocketFixture(owner, created.record, own(created.resource))
}

internal fun TransportTestScope.listener(): ServerSocket = own(ServerSocket()).also {
    it.soTimeout = 3_000
    it.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
}

internal fun TransportTestScope.connect(fixture: TransportSocketFixture, oneArgument: Boolean = false): Socket {
    val listener = listener()
    val endpoint = listener.localSocketAddress
    if (oneArgument) fixture.socket.connect(endpoint) else fixture.socket.connect(endpoint, 3_000)
    fixture.socket.soTimeout = 3_000
    return own(listener.accept()).also { it.soTimeout = 3_000 }
}

internal fun holdTransportCalls(fixture: TransportSocketFixture, kind: PersistenceTransportCallKind, count: Int): List<PersistenceTransportCall> = List(count) {
    requireNotNull(fixture.owner.tryBeginCall(fixture.record, kind))
}

internal fun releaseTransportCalls(fixture: TransportSocketFixture, calls: List<PersistenceTransportCall>) {
    calls.forEach { assertEquals(true, fixture.owner.completeCall(it)) }
}

internal fun assertTransportBusinessRefused(action: () -> Unit) {
    val failure = assertThrows(SocketException::class.java, action)
    assertEquals("Persistence transport is unavailable.", failure.message)
}

internal fun assertTransportObservationRefused(action: () -> Unit) {
    val failure = assertThrows(IllegalStateException::class.java, action)
    assertEquals("Persistence transport observation is unavailable.", failure.message)
}

internal fun assertNoTransportCalls(fixture: TransportSocketFixture) {
    val entry = transportRecordSnapshot(fixture.owner, fixture.record)
    assertEquals(0, entry.activeBusiness)
    assertEquals(0, entry.activeObservations)
    assertNotNull(retainedTransportTestRaw(fixture.owner, fixture.record))
}

/** Public stack metadata of our own Thread: establishes raw read entry, not just adapter admission. */
internal fun awaitTransportSocketRead(thread: Thread) {
    awaitTransportTestFact {
        val stack = thread.stackTrace
        stack.any { it.className == "java.net.Socket\$SocketInputStream" && it.methodName == "read" } &&
            stack.any { it.className == "sun.nio.ch.NioSocketImpl" && it.methodName == "implRead" }
    }
}
