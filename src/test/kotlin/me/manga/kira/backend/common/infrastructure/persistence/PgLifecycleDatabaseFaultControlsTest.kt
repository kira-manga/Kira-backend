package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.UUID

/** Deterministic fixture protocol/state controls only. These are not real driver, socket, deadline or server-disposal evidence. */
internal class PgLifecycleDatabaseFaultControlsTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `late byte requires exact one use arm deadline and final permits`() {
        val case = scenarioCase(PgLifecycleDatabaseMode.LATE_RETURN)
        val fault = PgLifecycleDatabaseReadyFault(case)
        fault.bind(0)
        fault.observeReady(System.nanoTime())
        assertThrows(IllegalStateException::class.java) { fault.beginDelivery() }
        assertThrows(IllegalStateException::class.java) { fault.arm(case, 1) }
        assertThrows(IllegalStateException::class.java) { fault.arm(scenarioCase(PgLifecycleDatabaseMode.PROGRESS_DEADLINE), 0) }
        check(!fault.armed.get())
        fault.arm(case, 0)
        assertThrows(IllegalStateException::class.java) { fault.arm(case, 0) }
        fault.beginDelivery()
        fault.wrote(0, 5)
        assertThrows(IllegalStateException::class.java) { fault.wrote(5, 1) }
        assertThrows(IllegalStateException::class.java) { fault.releaseFinal(case, 0) }
        fault.confirmDeadline(case, 0)
        assertThrows(IllegalStateException::class.java) { fault.confirmDeadline(case, 0) }
        assertThrows(IllegalStateException::class.java) { fault.releaseFinal(case, 1) }
        fault.releaseFinal(case, 0)
        check(fault.awaitFinal())
        assertThrows(IllegalStateException::class.java) { fault.releaseFinal(case, 0) }
        fault.wrote(5, 1)
        fault.requireEvidence(System.nanoTime())
    }

    @Test
    fun `progress never grants the final byte and failure cleanup never grants late completion`() {
        val progress = scenarioCase(PgLifecycleDatabaseMode.PROGRESS_DEADLINE)
        val fault = armed(progress)
        fault.wrote(0, 5)
        fault.confirmDeadline(progress, 0)
        assertThrows(IllegalStateException::class.java) { fault.releaseFinal(progress, 0) }
        assertThrows(IllegalStateException::class.java) { fault.wrote(5, 1) }
        check(!fault.finalReleased.get())
        val late = scenarioCase(PgLifecycleDatabaseMode.LATE_RETURN)
        val abandoned = armed(late)
        abandoned.wrote(0, 5)
        abandoned.confirmDeadline(late, 0)
        abandoned.stop()
        assertThrows(IllegalStateException::class.java) { abandoned.releaseFinal(late, 0) }
        check(!abandoned.awaitFinal() && !abandoned.finalReleased.get() && abandoned.bytesWritten.get() == 5)
    }

    @Test
    fun `idle and strict truncation cannot acquire complete Ready evidence`() {
        val idle = armed(scenarioCase(PgLifecycleDatabaseMode.SOCKET_TIMEOUT))
        assertThrows(IllegalStateException::class.java) { idle.wrote(0, 1) }
        check(idle.bytesWritten.get() == 0)
        val truncated = armed(scenarioCase(PgLifecycleDatabaseMode.TRUNCATED_STARTUP))
        truncated.wrote(0, 3)
        assertThrows(IllegalStateException::class.java) { truncated.wrote(3, 3) }
        assertThrows(IllegalStateException::class.java) { truncated.requireEvidence(System.nanoTime()) }
        check(!truncated.outputHalfCloseEntered.get() && !truncated.outputHalfCloseEnded.get())
    }

    @Test
    fun `fixture input close wins without fabricating candidate EOF or upstream disposal order`() {
        val state = PgLifecycleDatabaseRelayState(scenarioCase(PgLifecycleDatabaseMode.ORIGINAL_MATRIX))
        state.cleanupRelease()
        state.clientOriginatedEnd(PgLifecycleDatabaseClientEnd.EOF)
        check(state.fixtureClosing.get() && state.clientEnd.get() == null)
        check(state.clientEndNanos.get() == 0L && state.originOrder.get() == 0L && state.upstreamCloseOrder.get() == 0L)
        assertThrows(IllegalStateException::class.java) { state.propagatingUpstreamClose() }
        assertThrows(IllegalStateException::class.java) { state.requireClientDisposal(primary = true) }
    }

    @Test
    fun `MODEL cleanup before accepted session start permanently prevents a later actor start`() {
        val session = PgLifecycleDatabaseRelaySession(
            Socket(),
            "127.0.0.1",
            1,
            scenarioCase(PgLifecycleDatabaseMode.ORIGINAL_MATRIX),
        ) { _, _ -> error("A fixture-closed session must not reach registration or connect.") }
        session.use {
            check(!it.actorsEnded()) // NEW is not actual no-future-start evidence.
            it.close()
            it.start()
            check(it.actorsEnded() && it.state.fixtureClosing.get())
            check(it.state.clientEnd.get() == null && it.state.originOrder.get() == 0L)
        }
    }

    @Test
    fun `MODEL only exact stock read reset mapping is eligible and unknown input exceptions stay failures`() {
        check(PgLifecycleDatabaseDisconnectEvidence.reset(SocketException("Connection reset")))
        val unknown = listOf(
            SocketException(),
            SocketException("Socket closed"),
            SocketException("Socket is closed"),
            SocketException("Connection reset by peer"),
            SocketException("Broken pipe"),
            SocketException("Connection aborted"),
            object : SocketException("Connection reset") {},
        )
        check(unknown.none(PgLifecycleDatabaseDisconnectEvidence::reset))
    }

    @Test
    fun `MODEL complete Ready forbids output half close and fault evidence requires a real ending timestamp`() {
        val complete = PgLifecycleDatabaseReadyFault(scenarioCase(PgLifecycleDatabaseMode.MATRIX))
        complete.bind(0)
        complete.observeReady(System.nanoTime())
        complete.beginDelivery()
        complete.wrote(0, 6)
        complete.requireEvidence(0) // A weak no-raw provider may remain open; COMPLETE is not client-disposal evidence.
        complete.outputHalfCloseEntered.set(true)
        assertThrows(IllegalStateException::class.java) { complete.requireEvidence(System.nanoTime()) }
        val truncated = armed(scenarioCase(PgLifecycleDatabaseMode.TRUNCATED_STARTUP))
        truncated.wrote(0, 3)
        truncated.outputHalfCloseEntered.set(true)
        truncated.outputHalfCloseEnded.set(true)
        assertThrows(IllegalStateException::class.java) { truncated.requireEvidence(0) }
    }

    @Test
    fun `case phase identity disallows LIVE for late timeout and strong absence for weak no raw`() {
        val directory = pgLifecycleDatabasePrivateDirectory(root.resolve("phase-controls"))
        val nonce = UUID.randomUUID().toString()
        val late = PgLifecycleDatabaseHandshake(directory, nonce, PgLifecycleDatabaseParty.CHILD, scenarioCase(PgLifecycleDatabaseMode.LATE_RETURN))
        assertThrows(IllegalStateException::class.java) { late.publish(PgLifecycleDatabasePhase.LIVE) }
        assertThrows(IllegalStateException::class.java) { late.publish(PgLifecycleDatabasePhase.DEADLINE_OBSERVED) }
        val progress = PgLifecycleDatabaseHandshake(directory, nonce, PgLifecycleDatabaseParty.CHILD, scenarioCase(PgLifecycleDatabaseMode.PROGRESS_DEADLINE))
        assertThrows(IllegalStateException::class.java) { progress.publish(PgLifecycleDatabasePhase.LATE_RAW_RETAINED) }
        val weak = PgLifecycleDatabaseCase(
            PgLifecycleDatabaseRecipe.READ_ONLY_TRUE_ALWAYS,
            1,
            PgLifecycleDatabaseLane.ORDINARY,
            PgLifecycleDatabaseMode.ORIGINAL_MATRIX,
        )
        val weakParent = PgLifecycleDatabaseHandshake(directory, nonce, PgLifecycleDatabaseParty.PARENT, weak)
        assertThrows(IllegalStateException::class.java) { weakParent.publish(PgLifecycleDatabasePhase.ABSENCE_CONFIRMED) }
        weakParent.publish(PgLifecycleDatabasePhase.WEAK_CLEANUP_CONFIRMED)
    }

    @Test
    fun `same nonce and phase with wrong fault case cannot arm the relay`() {
        val directory = pgLifecycleDatabasePrivateDirectory(root.resolve("wrong-fault"))
        val nonce = UUID.randomUUID().toString()
        val writer = PgLifecycleDatabaseHandshake(directory, nonce, PgLifecycleDatabaseParty.CHILD, scenarioCase(PgLifecycleDatabaseMode.LATE_RETURN))
        val reader = PgLifecycleDatabaseHandshake(directory, nonce, PgLifecycleDatabaseParty.PARENT, scenarioCase(PgLifecycleDatabaseMode.PROGRESS_DEADLINE))
        writer.publish(PgLifecycleDatabasePhase.FAULT_ARMED)
        assertThrows(IllegalStateException::class.java) { reader.await(PgLifecycleDatabasePhase.FAULT_ARMED) }
    }

    @Test
    fun `only the authentic complete Ready and exact primary role response satisfy the wire controls`() {
        val ready = PgLifecycleDatabaseWire.Message('Z'.code, byteArrayOf('I'.code.toByte()))
        check(PgLifecycleDatabaseWire.readyFrame(ready).contentEquals(byteArrayOf(90, 0, 0, 0, 5, 73)))
        assertThrows(IllegalStateException::class.java) { PgLifecycleDatabaseWire.readyFrame(PgLifecycleDatabaseWire.Message('Z'.code, byteArrayOf())) }
        PgLifecycleDatabaseWire.requirePrimaryRoleRow(row())
        val invalid = listOf(row(columns = 2), row(value = "OFF"), row(value = "on"), row(length = -1), row(trailing = true))
        invalid.forEach { message -> assertThrows(IllegalStateException::class.java) { PgLifecycleDatabaseWire.requirePrimaryRoleRow(message) } }
        val query = PgLifecycleDatabaseWire.Message('Q'.code, "show transaction_read_only\u0000".toByteArray(Charsets.US_ASCII))
        check(PgLifecycleDatabaseWire.sqlFingerprint(query) === PgLifecycleDatabaseSql.ROLE)
        val other = PgLifecycleDatabaseWire.Message('Q'.code, "show transaction_read_only; select 1\u0000".toByteArray(Charsets.US_ASCII))
        check(PgLifecycleDatabaseWire.sqlFingerprint(other) === PgLifecycleDatabaseSql.UNEXPECTED)
    }

    @Test
    fun `role witness rejects duplicate rows and requires a full real response shape`() {
        val role = PgLifecycleDatabaseRoleWitness(scenarioCase(PgLifecycleDatabaseMode.PRIMARY_ROLE))
        val query = PgLifecycleDatabaseWire.Message('Q'.code, "show transaction_read_only\u0000".toByteArray(Charsets.US_ASCII))
        role.request(query)
        role.response(row())
        assertThrows(IllegalStateException::class.java) { role.response(row()) }
        assertThrows(IllegalStateException::class.java) { role.requireEvidence() }
        role.response(PgLifecycleDatabaseWire.Message('C'.code, "SHOW\u0000".toByteArray(Charsets.US_ASCII)))
        role.response(PgLifecycleDatabaseWire.Message('Z'.code, byteArrayOf('I'.code.toByte())))
        role.requireEvidence()
    }

    private fun scenarioCase(mode: PgLifecycleDatabaseMode): PgLifecycleDatabaseCase =
        PgLifecycleDatabaseCase(PgLifecycleDatabaseRecipe.DEFAULT, 0, PgLifecycleDatabaseLane.ORDINARY, mode)

    private fun armed(case: PgLifecycleDatabaseCase): PgLifecycleDatabaseReadyFault = PgLifecycleDatabaseReadyFault(case).also {
        it.bind(0)
        it.observeReady(System.nanoTime())
        it.arm(case, 0)
        it.beginDelivery()
    }

    private fun row(columns: Int = 1, value: String = "off", length: Int = value.length, trailing: Boolean = false): PgLifecycleDatabaseWire.Message {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        val buffer = ByteBuffer.allocate(6 + bytes.size + if (trailing) 1 else 0).putShort(columns.toShort()).putInt(length).put(bytes)
        return PgLifecycleDatabaseWire.Message('D'.code, buffer.array())
    }
}
