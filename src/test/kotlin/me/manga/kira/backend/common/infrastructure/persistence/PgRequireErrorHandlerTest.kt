package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.ResourceBundle
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.LogRecord

internal class PgRequireErrorHandlerTest {
    @Test
    fun `MODEL only an exact event on the actual owned thread captures successfully`() {
        val owned = Thread.currentThread()
        val captures = AtomicInteger()
        val handler = PgRequireErrorHandler(owned) {
            assertSame(owned, Thread.currentThread())
            captures.incrementAndGet()
        }
        assertThrows(IllegalStateException::class.java) { handler.requireObserved() }
        assertDoesNotThrow { handler.publish(event()) }
        handler.requireObserved()
        assertEquals(1, captures.get())
        handler.flush()
        handler.close()
        handler.requireObserved()
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["NULL", "NO_LOGGER", "WRONG_LOGGER", "LEVEL", "EQUIVALENT_LEVEL", "NO_MESSAGE", "WRONG_MESSAGE"])
    fun `MODEL null and nonmatching records do not count or poison a later exact event`(invalid: String) {
        val captures = AtomicInteger()
        val handler = PgRequireErrorHandler(Thread.currentThread()) { captures.incrementAndGet() }
        val record = if (invalid == "NULL") {
            null
        } else {
            event().apply {
                when (invalid) {
                    "NO_LOGGER" -> loggerName = null
                    "WRONG_LOGGER" -> loggerName = "org.postgresql.core.v3.Other"
                    "LEVEL" -> level = Level.FINE
                    "EQUIVALENT_LEVEL" -> level = object : Level("FINEST", Level.FINEST.intValue()) {}
                    "NO_MESSAGE" -> message = null
                    "WRONG_MESSAGE" -> message = " <=BE SSLRefused"
                }
            }
        }
        assertDoesNotThrow { handler.publish(record) }
        assertEquals(0, captures.get())
        assertThrows(IllegalStateException::class.java) { handler.requireObserved() }
        handler.publish(event())
        handler.requireObserved()
        assertEquals(1, captures.get())
    }

    @Test
    fun `MODEL duplicate exact event permanently invalidates an earlier capture`() {
        val captures = AtomicInteger()
        val handler = PgRequireErrorHandler(Thread.currentThread()) { captures.incrementAndGet() }
        handler.publish(event())
        handler.requireObserved()
        repeat(2) {
            assertDoesNotThrow { handler.publish(event()) }
            assertThrows(IllegalStateException::class.java) { handler.requireObserved() }
        }
        assertEquals(1, captures.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(booleans = [false, true])
    fun `MODEL actual foreign thread is rejected despite spoofed owned name and record ID`(virtual: Boolean) = TransportTestScope("MODEL").use { scope ->
        val owned = Thread.currentThread()
        val captures = AtomicInteger()
        val handler = PgRequireErrorHandler(owned) { captures.incrementAndGet() }
        val call = scope.launch(virtual) {
            Thread.currentThread().name = owned.name
            val spoofed = event().apply { setLongThreadID(owned.threadId()) }
            assertDoesNotThrow { handler.publish(spoofed) }
            Unit
        }
        call.join()
        assertThrows(IllegalStateException::class.java) { handler.requireObserved() }
        assertDoesNotThrow { handler.publish(event()) }
        assertThrows(IllegalStateException::class.java) { handler.requireObserved() }
        assertEquals(0, captures.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["EXCEPTION", "ERROR"])
    fun `MODEL capture exception or Error never escapes publish or becomes valid evidence`(kind: String) {
        val failure = if (kind == "ERROR") AssertionError("Synthetic capture failure.") else TransportTestFailure()
        val captures = AtomicInteger()
        val handler = PgRequireErrorHandler(Thread.currentThread()) {
            captures.incrementAndGet()
            throw failure
        }
        repeat(2) {
            assertDoesNotThrow { handler.publish(event()) }
            assertThrows(IllegalStateException::class.java) { handler.requireObserved() }
        }
        assertEquals(1, captures.get())
        if (failure is TransportTestFailure) assertEquals(0, failure.renders.get())
    }

    @Test
    fun `MODEL concurrent duplicate during capture cannot be overwritten by callback success`() {
        TransportTestScope("MODEL").use { scope ->
            val start = scope.gate()
            val capturing = scope.gate()
            val captures = AtomicInteger()
            val reference = AtomicReference<PgRequireErrorHandler>()
            val call = scope.launch {
                start.hold()
                requireNotNull(reference.get()).publish(event())
                Unit
            }
            start.awaitEntered()
            val handler = PgRequireErrorHandler(call.thread) {
                capturing.hold()
                captures.incrementAndGet()
            }
            reference.set(handler)
            start.release()
            capturing.awaitEntered()
            assertThrows(IllegalStateException::class.java) { handler.requireObserved() }
            assertEquals(0, captures.get())
            // A genuine second event races the first callback; mutable record metadata grants no authority.
            assertDoesNotThrow { handler.publish(event().apply { setLongThreadID(call.thread.threadId()) }) }
            capturing.release()
            call.join()
            assertEquals(1, captures.get())
            assertThrows(IllegalStateException::class.java) { handler.requireObserved() }
        }
    }

    @Test
    fun `MODEL ignored payloads are never formatted localized or rendered`() {
        val touches = AtomicInteger()
        val payload = object {
            override fun toString(): String {
                touches.incrementAndGet()
                error("Synthetic payload must not be rendered.")
            }
        }
        val bundle = object : ResourceBundle() {
            override fun handleGetObject(key: String): Any {
                touches.incrementAndGet()
                error("Synthetic bundle must not be consulted.")
            }

            override fun getKeys(): java.util.Enumeration<String> {
                touches.incrementAndGet()
                error("Synthetic bundle keys must not be consulted.")
            }
        }
        val failure = TransportTestFailure()
        val record = LogRecord(Level.INFO, "synthetic {0}").apply {
            loggerName = PgRequireErrorHandler.LOGGER_NAME
            parameters = arrayOf(payload)
            thrown = failure
            resourceBundle = bundle
        }
        val captures = AtomicInteger()
        val handler = PgRequireErrorHandler(Thread.currentThread()) { captures.incrementAndGet() }
        assertDoesNotThrow { handler.publish(record) }
        assertEquals(0, captures.get())
        assertThrows(IllegalStateException::class.java) { handler.requireObserved() }
        handler.publish(event())
        handler.requireObserved()
        assertEquals(0, touches.get())
        assertEquals(0, failure.renders.get())
    }

    private fun event(): LogRecord = LogRecord(Level.FINEST, PgRequireErrorHandler.MESSAGE).apply {
        loggerName = PgRequireErrorHandler.LOGGER_NAME
    }
}
