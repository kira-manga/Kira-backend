package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.SocketException

class TrackedPersistenceInterruptionTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["VIRTUAL", "PLATFORM"])
    fun `REAL API interruption distinguishes virtual parent close from a classic platform read`(threadKind: String) =
        TransportTestScope("REAL_SOCKET_API").use { scope ->
            val fixture = scope.socket()
            scope.connect(fixture)
            fixture.socket.soTimeout = 0
            val input = fixture.socket.inputStream
            val virtual = threadKind == "VIRTUAL"
            val reader = scope.launch(virtual = virtual) {
                val failure = runCatching { input.read() }.exceptionOrNull()
                TransportReadOutcome(failure, Thread.currentThread().isInterrupted)
            }
            assertEquals(virtual, reader.thread.isVirtual)
            awaitTransportSocketRead(reader.thread)
            assertEquals(1, transportRecordSnapshot(fixture.owner, fixture.record).activeBusiness)
            reader.thread.interrupt()
            if (!virtual) {
                awaitTransportTestFact { reader.thread.isInterrupted }
                awaitTransportSocketRead(reader.thread)
                assertFalse(fixture.socket.isClosed)
                assertEquals(PersistenceTransportClosePhase.NOT_STARTED, transportRecordSnapshot(fixture.owner, fixture.record).firstClose)
                assertEquals(1, transportRecordSnapshot(fixture.owner, fixture.record).activeBusiness)
                fixture.socket.close()
            }
            val result = reader.join()
            assertTrue(result.failure is SocketException)
            assertTrue(result.interrupted)
            assertTrue(fixture.socket.isClosed)
            assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, transportRecordSnapshot(fixture.owner, fixture.record).firstClose)
            assertNoTransportCalls(fixture)
            // No virtual connect/write/native-fd or driver/TLS completion evidence is inferred.
        }
}

private class TransportReadOutcome(val failure: Throwable?, val interrupted: Boolean)
