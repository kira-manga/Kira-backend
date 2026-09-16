package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.withLock

class PersistenceTransportBindingTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["BUSINESS", "OBSERVATION"])
    fun `MODEL lexical helper stays active across seal close and the full gated callback`(kind: String) = TransportTestScope("MODEL").use { scope ->
        val fixture = scope.socket()
        val binding = fixture.modelBinding()
        val gate = scope.gate()
        val worker = scope.launch {
            invokeTransportBinding(binding, kind) {
                gate.hold()
                42
            }
        }
        gate.awaitEntered()
        assertTrue(Thread.currentThread() !== worker.thread)
        val entry = transportRecordSnapshot(fixture.owner, fixture.record)
        assertEquals(if (kind == "BUSINESS") 1 else 0, entry.activeBusiness)
        assertEquals(if (kind == "OBSERVATION") 1 else 0, entry.activeObservations)
        // Different actual Thread progresses through these transitions while the delegate is held.
        assertTrue(fixture.owner.seal(fixture.record))
        fixture.socket.close()
        assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, transportRecordSnapshot(fixture.owner, fixture.record).firstClose)
        assertEquals(entry.activeBusiness, transportRecordSnapshot(fixture.owner, fixture.record).activeBusiness)
        assertEquals(entry.activeObservations, transportRecordSnapshot(fixture.owner, fixture.record).activeObservations)
        assertTransportBusinessRefused { binding.business { error("Must not delegate.") } }
        assertEquals(7, binding.observation { 7 })
        gate.release()
        assertEquals(42, worker.join())
        assertNoTransportCalls(fixture)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["TIMEOUT", "IO", "RUNTIME", "ERROR", "INTERRUPTED_IO", "HOSTILE"])
    fun `MODEL lexical failures preserve original identity and do not poison the first close`(kind: String) = TransportTestScope("MODEL").use { scope ->
        val fixture = scope.socket()
        val binding = fixture.modelBinding()
        val failure = when (kind) {
            "TIMEOUT" -> SocketTimeoutException("Synthetic call failure.")
            "IO" -> IOException("Synthetic call failure.")
            "RUNTIME" -> IllegalStateException("Synthetic call failure.")
            "ERROR" -> AssertionError("Synthetic call failure.")
            "INTERRUPTED_IO" -> InterruptedIOException("Synthetic call failure.")
            else -> TransportTestFailure()
        }
        for (partition in listOf("BUSINESS", "OBSERVATION")) {
            assertSame(failure, runCatching { invokeTransportBinding(binding, partition) { throw failure } }.exceptionOrNull())
            assertNoTransportCalls(fixture)
            val snapshot = transportRecordSnapshot(fixture.owner, fixture.record)
            assertEquals(PersistenceTransportClosePhase.NOT_STARTED, snapshot.firstClose)
            assertFalse(snapshot.unknown)
        }
        if (failure is TransportTestFailure) assertEquals(0, failure.renders.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["BUSINESS", "OBSERVATION"])
    fun `MODEL overloaded helper refuses with no delegate invocation`(kind: String) = TransportTestScope("MODEL").use { scope ->
        val fixture = scope.socket()
        val binding = fixture.modelBinding()
        val partition = PersistenceTransportCallKind.valueOf(kind)
        val calls = holdTransportCalls(fixture, partition, if (kind == "BUSINESS") 32 else 8)
        val delegated = AtomicInteger()
        val action = {
            invokeTransportBinding(binding, kind) { delegated.incrementAndGet() }
            Unit
        }
        if (kind == "BUSINESS") assertTransportBusinessRefused(action) else assertTransportObservationRefused(action)
        assertEquals(0, delegated.get())
        releaseTransportCalls(fixture, calls)
        assertNoTransportCalls(fixture)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["BUSINESS", "OBSERVATION"])
    fun `MODEL contended helper refuses without queueing or invoking a callback`(kind: String) = TransportTestScope("MODEL").use { scope ->
        val fixture = scope.socket()
        val binding = fixture.modelBinding()
        val delegated = AtomicInteger()
        val lock = transportTestLock(fixture.owner)
        lock.withLock {
            val worker = scope.launch {
                val action = {
                    invokeTransportBinding(binding, kind) { delegated.incrementAndGet() }
                    Unit
                }
                if (kind == "BUSINESS") assertTransportBusinessRefused(action) else assertTransportObservationRefused(action)
                true
            }
            assertTrue(worker.join())
            assertFalse(lock.hasQueuedThread(worker.thread))
        }
        assertEquals(0, delegated.get())
        assertNoTransportCalls(fixture)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["BUSINESS", "OBSERVATION"])
    fun `MODEL foreign binding never inherits the matching role permission`(kind: String) = TransportTestScope("MODEL").use { scope ->
        val fixture = scope.socket()
        val binding = PersistenceTransportBinding(fixture.owner, PersistenceTransportRecord(fixture.record.role))
        val delegated = AtomicInteger()
        val action = {
            invokeTransportBinding(binding, kind) { delegated.incrementAndGet() }
            Unit
        }
        if (kind == "BUSINESS") assertTransportBusinessRefused(action) else assertTransportObservationRefused(action)
        assertEquals(0, delegated.get())
        assertNoTransportCalls(fixture)
    }
}

private fun <T> invokeTransportBinding(binding: PersistenceTransportBinding, kind: String, action: () -> T): T =
    if (kind == "BUSINESS") binding.business(action) else binding.observation(action)
