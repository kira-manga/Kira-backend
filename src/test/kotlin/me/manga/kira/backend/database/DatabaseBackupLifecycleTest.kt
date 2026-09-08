package me.manga.kira.backend.database

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.io.ByteArrayInputStream

class DatabaseBackupLifecycleTest {
    @Test
    fun `dump reader retains control and multibyte bytes without log decoding`() {
        val bytes = "\u001b[31mRAW\u001b[0m\u0001\u009fعربي😀\n".toByteArray(Charsets.UTF_8)
        assertArrayEquals(bytes, readBoundedDump(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `dump reader permits exact bound and refuses one extra byte`() {
        val exact = ByteArray(MAX_SYNTHETIC_DUMP_BYTES) { 65 }
        assertArrayEquals(exact, readBoundedDump(ByteArrayInputStream(exact)))
        assertThrows(IllegalArgumentException::class.java) {
            readBoundedDump(ByteArrayInputStream(exact + 66.toByte()))
        }
    }

    @Test
    fun `failure during target startup closes target and already-started source`() {
        val calls = mutableListOf<String>()
        val failure = IllegalStateException("synthetic second start failure")
        val source = RecordingPostgres("source", calls)
        val target = RecordingPostgres("target", calls, startFailure = failure)
        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) {
                withStartedBackupContainers(source, target) { _, _ -> error("Must not reach the backup") }
            },
        )
        assertEquals(listOf("source.start", "target.start", "target.stop", "source.stop"), calls)
    }

    @Test
    fun `failure during target stop still closes source without masking the primary failure`() {
        val calls = mutableListOf<String>()
        val primary = IllegalStateException("synthetic restore failure")
        val cleanup = IllegalArgumentException("synthetic stop failure")
        val failure = assertThrows(IllegalStateException::class.java) {
            withStartedBackupContainers(RecordingPostgres("source", calls), RecordingPostgres("target", calls, stopFailure = cleanup)) { _, _ ->
                throw primary
            }
        }
        assertSame(primary, failure)
        assertEquals(listOf(cleanup), failure.suppressed.toList())
        assertEquals(listOf("source.start", "target.start", "target.stop", "source.stop"), calls)
    }
}

/** Override only lifecycle: no Docker start/connection occurs in these deterministic failure tests. */
private class RecordingPostgres(
    private val label: String,
    private val calls: MutableList<String>,
    private val startFailure: RuntimeException? = null,
    private val stopFailure: RuntimeException? = null,
) : PostgreSQLContainer<RecordingPostgres>("postgres:17.6-alpine") {
    override fun start() {
        calls += "$label.start"
        startFailure?.let { throw it }
    }

    override fun stop() {
        calls += "$label.stop"
        stopFailure?.let { throw it }
    }
}
