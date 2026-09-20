package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException

class TrackedPersistenceStreamTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["READ", "READ_ARRAY", "READ_SLICE", "READ_ALL", "READ_N", "READ_N_SLICE", "SKIP", "SKIP_N", "AVAILABLE", "TRANSFER"])
    fun `MODEL input methods delegate the complete corresponding raw operation under one cell`(method: String) = TransportTestScope("MODEL").use { scope ->
        val fixture = scope.socket()
        val raw = TransportInputProbe { assertEquals(32, transportRecordSnapshot(fixture.owner, fixture.record).activeBusiness) }
        val input = TrackedPersistenceInputStream(raw, fixture.modelBinding(), fixture.socket)
        val held = holdTransportCalls(fixture, PersistenceTransportCallKind.BUSINESS, 31)
        invokeTransportInput(input, method)
        assertEquals(listOf(method), raw.calls)
        assertEquals(31, transportRecordSnapshot(fixture.owner, fixture.record).activeBusiness)
        fixture.owner.seal(fixture.record)
        assertTransportBusinessRefused { invokeTransportInput(input, method) }
        assertEquals(listOf(method), raw.calls)
        releaseTransportCalls(fixture, held)
        assertNoTransportCalls(fixture)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["WRITE", "WRITE_ARRAY", "WRITE_SLICE"])
    fun `MODEL output methods preserve whole dispatch bytes and full call accounting`(method: String) = TransportTestScope("MODEL").use { scope ->
        val fixture = scope.socket()
        val raw = TransportOutputProbe { assertEquals(32, transportRecordSnapshot(fixture.owner, fixture.record).activeBusiness) }
        val output = TrackedPersistenceOutputStream(raw, fixture.modelBinding(), fixture.socket)
        val held = holdTransportCalls(fixture, PersistenceTransportCallKind.BUSINESS, 31)
        invokeTransportOutput(output, method)
        assertEquals(listOf(method), raw.calls)
        val expected = when (method) {
            "WRITE" -> byteArrayOf(65)
            "WRITE_ARRAY" -> byteArrayOf(66, 67)
            else -> byteArrayOf(68, 69)
        }
        assertArrayEquals(expected, raw.bytes.toByteArray())
        fixture.owner.seal(fixture.record)
        assertTransportBusinessRefused { invokeTransportOutput(output, method) }
        assertEquals(listOf(method), raw.calls)
        releaseTransportCalls(fixture, held)
        assertNoTransportCalls(fixture)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["READ", "READ_ARRAY", "READ_SLICE", "READ_ALL", "READ_N", "READ_N_SLICE", "SKIP", "SKIP_N", "AVAILABLE", "TRANSFER"])
    fun `MODEL each input preserves raw timeout IO runtime and error identities on actual unwind`(method: String) = TransportTestScope("MODEL").use { scope ->
        val fixture = scope.socket()
        val hostile = TransportTestFailure()
        val failures = listOf(
            SocketTimeoutException("Synthetic timeout."),
            hostile,
            IllegalStateException("Synthetic failure."),
            AssertionError("Synthetic error."),
        )
        for (failure in failures) {
            val raw = TransportInputProbe { throw failure }
            val input = TrackedPersistenceInputStream(raw, fixture.modelBinding(), fixture.socket)
            assertSame(failure, runCatching { invokeTransportInput(input, method) }.exceptionOrNull())
            assertEquals(listOf(method), raw.calls)
            assertNoTransportCalls(fixture)
            assertEquals(PersistenceTransportClosePhase.NOT_STARTED, transportRecordSnapshot(fixture.owner, fixture.record).firstClose)
        }
        assertEquals(0, hostile.renders.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["WRITE", "WRITE_ARRAY", "WRITE_SLICE"])
    fun `MODEL each output preserves raw timeout IO runtime and error identities on actual unwind`(method: String) = TransportTestScope("MODEL").use { scope ->
        val fixture = scope.socket()
        val hostile = TransportTestFailure()
        val failures = listOf(
            SocketTimeoutException("Synthetic timeout."),
            hostile,
            IllegalStateException("Synthetic failure."),
            AssertionError("Synthetic error."),
        )
        for (failure in failures) {
            val raw = TransportOutputProbe { throw failure }
            val output = TrackedPersistenceOutputStream(raw, fixture.modelBinding(), fixture.socket)
            assertSame(failure, runCatching { invokeTransportOutput(output, method) }.exceptionOrNull())
            assertEquals(listOf(method), raw.calls)
            assertNoTransportCalls(fixture)
        }
        assertEquals(0, hostile.renders.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(
        strings = [
            "ZERO_ARRAY", "READ_NULL", "SLICE_NULL", "SLICE_NEGATIVE", "SLICE_OVERFLOW", "N_ZERO", "N_NEGATIVE",
            "N_NULL", "N_BOUND", "N_PARTIAL", "SKIP_NEGATIVE", "SKIP_N_NEGATIVE", "SKIP_N_EOF", "TRANSFER_NULL",
        ],
    )
    fun `MODEL input zero invalid partial and EOF behavior follows the chosen raw method after admission`(vector: String) =
        TransportTestScope("MODEL").use { scope ->
            val fixture = scope.socket()
            val stock = ByteArrayInputStream(byteArrayOf(1, 2, 3))
            val raw = ByteArrayInputStream(byteArrayOf(1, 2, 3))
            val input = TrackedPersistenceInputStream(raw, fixture.modelBinding(), fixture.socket)
            val expected = runCatching { invokeTransportInputArgument(stock, vector) }
            val actual = runCatching { invokeTransportInputArgument(input, vector) }
            assertEquals(expected.exceptionOrNull()?.javaClass, actual.exceptionOrNull()?.javaClass)
            assertEquals(expected.exceptionOrNull()?.message, actual.exceptionOrNull()?.message)
            assertEquals(expected.isSuccess, actual.isSuccess)
            val value = expected.getOrNull()
            if (value is ByteArray) assertArrayEquals(value, actual.getOrNull() as ByteArray) else assertEquals(value, actual.getOrNull())
            fixture.owner.seal(fixture.record)
            assertTransportBusinessRefused { invokeTransportInputArgument(input, vector) }
            assertNoTransportCalls(fixture)
        }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["ZERO_ARRAY", "WRITE_NULL", "SLICE_NULL", "SLICE_NEGATIVE", "SLICE_OVERFLOW"])
    fun `MODEL output zero and invalid arguments preserve raw validation only after admission`(vector: String) = TransportTestScope("MODEL").use { scope ->
        val fixture = scope.socket()
        val stock = ByteArrayOutputStream()
        val raw = ByteArrayOutputStream()
        val output = TrackedPersistenceOutputStream(raw, fixture.modelBinding(), fixture.socket)
        val expected = runCatching { invokeTransportOutputArgument(stock, vector) }
        val actual = runCatching { invokeTransportOutputArgument(output, vector) }
        assertEquals(expected.exceptionOrNull()?.javaClass, actual.exceptionOrNull()?.javaClass)
        assertEquals(expected.exceptionOrNull()?.message, actual.exceptionOrNull()?.message)
        assertEquals(expected.isSuccess, actual.isSuccess)
        assertArrayEquals(stock.toByteArray(), raw.toByteArray())
        fixture.owner.seal(fixture.record)
        assertTransportBusinessRefused { invokeTransportOutputArgument(output, vector) }
        assertNoTransportCalls(fixture)
    }

    @Test
    fun `MODEL pinned mark reset flush and close do not inherit Filter delegation`() = TransportTestScope("MODEL").use { scope ->
        val fixture = scope.socket()
        val rawInput = TransportInputProbe()
        val rawOutput = TransportOutputProbe()
        val input = TrackedPersistenceInputStream(rawInput, fixture.modelBinding(), fixture.socket)
        val output = TrackedPersistenceOutputStream(rawOutput, fixture.modelBinding(), fixture.socket)
        val revision = transportSnapshot(fixture.owner).revision
        input.mark(3)
        assertFalse(input.markSupported())
        assertEquals("mark/reset not supported", assertThrows(IOException::class.java) { input.reset() }.message)
        output.flush()
        assertEquals(revision, transportSnapshot(fixture.owner).revision)
        fixture.owner.seal(fixture.record)
        input.mark(4)
        output.flush()
        input.close()
        output.close()
        assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, transportRecordSnapshot(fixture.owner, fixture.record).firstClose)
        assertTrue(rawInput.calls.isEmpty())
        assertTrue(rawOutput.calls.isEmpty())
        assertEquals("TrackedPersistenceInputStream(redacted)", input.toString())
        assertEquals("TrackedPersistenceOutputStream(redacted)", output.toString())
        assertNoTransportCalls(fixture)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["INPUT", "OUTPUT"])
    fun `MODEL competing wrapper publication retains one identity without exposing the raw stream`(direction: String) =
        TransportTestScope("MODEL").use { scope ->
            val fixture = scope.socket()
            val binding = fixture.modelBinding()
            val input = TransportInputProbe()
            val output = TransportOutputProbe()
            val gates = listOf(scope.gate(), scope.gate())
            val tickets = holdTransportCalls(fixture, PersistenceTransportCallKind.BUSINESS, 2)
            val calls = gates.mapIndexed { index, gate ->
                scope.launch {
                    gate.hold()
                    val result = if (direction == "INPUT") binding.input(input, fixture.socket) else binding.output(output, fixture.socket)
                    assertTrue(fixture.owner.completeCall(tickets[index]))
                    result
                }
            }
            gates.forEach(TransportTestGate::awaitEntered)
            gates.forEach(TransportTestGate::release)
            val first = calls[0].join()
            assertSame(first, calls[1].join())
            assertFalse(first === input || first === output)
            assertNoTransportCalls(fixture)
        }
}
