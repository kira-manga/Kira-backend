package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.logging.Filter
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

internal class PgRequireErrorObservationTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @CsvSource("INHERITED,false", "INHERITED,true", "EXPLICIT,false", "EXPLICIT,true")
    fun `MODEL close restores nullable level and original parent propagation`(mode: String, parents: Boolean) {
        val logger = Logger.getAnonymousLogger()
        val previous = if (mode == "INHERITED") null else Level.WARNING
        logger.level = previous
        logger.useParentHandlers = parents
        val thread = inertThread()
        PgRequireErrorObservation.model(logger, thread) { error("No synthetic event was sent.") }.use { observation ->
            assertSame(Level.FINEST, logger.level)
            assertFalse(logger.useParentHandlers)
            assertEquals(1, logger.handlers.size)
            assertNull(logger.filter)
            assertThrows(IllegalStateException::class.java) { observation.requireObserved() }
        }
        assertSame(previous, logger.level)
        assertEquals(parents, logger.useParentHandlers)
        assertTrue(logger.handlers.isEmpty())
        assertSame(Thread.State.NEW, thread.state)
    }

    @Test
    fun `MODEL restoration preserves independently added handler and successful repeated close is inert`() {
        val logger = Logger.getAnonymousLogger().apply { level = Level.INFO }
        val foreign = inertHandler()
        val observation = PgRequireErrorObservation.model(logger, inertThread()) {}
        try {
            logger.addHandler(foreign)
            observation.close()
            assertSame(foreign, logger.handlers.single())
            assertSame(Level.INFO, logger.level)
            assertTrue(logger.useParentHandlers)
            logger.level = Level.SEVERE
            logger.useParentHandlers = false
            observation.close()
            assertSame(foreign, logger.handlers.single())
            assertSame(Level.SEVERE, logger.level)
            assertFalse(logger.useParentHandlers)
        } finally {
            try {
                observation.close()
            } finally {
                logger.removeHandler(foreign)
            }
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["FILTER", "HANDLER", "BOTH"])
    fun `MODEL install rejects existing filter or handler without changing logger state`(existing: String) {
        val logger = Logger.getAnonymousLogger().apply {
            level = Level.CONFIG
            useParentHandlers = false
        }
        val filter = Filter { true }
        val handler = inertHandler()
        if (existing != "HANDLER") logger.filter = filter
        if (existing != "FILTER") logger.addHandler(handler)
        val previousFilter = logger.filter
        val previousHandlers = logger.handlers
        try {
            assertThrows(IllegalStateException::class.java) { PgRequireErrorObservation.model(logger, inertThread()) {} }
            assertSame(Level.CONFIG, logger.level)
            assertFalse(logger.useParentHandlers)
            assertSame(previousFilter, logger.filter)
            assertEquals(previousHandlers.size, logger.handlers.size)
            previousHandlers.forEachIndexed { index, previous -> assertSame(previous, logger.handlers[index]) }
        } finally {
            logger.removeHandler(handler)
        }
    }

    @Test
    fun `MODEL observation never accepts a named logger instead of its isolated anonymous leaf`() {
        val logger = object : Logger("synthetic-not-registered", null) {}
        val previous = logger.level
        val parents = logger.useParentHandlers
        assertThrows(IllegalStateException::class.java) { PgRequireErrorObservation.model(logger, inertThread()) {} }
        assertSame(previous, logger.level)
        assertEquals(parents, logger.useParentHandlers)
        assertTrue(logger.handlers.isEmpty())
    }

    @Test
    fun `MODEL early close cannot restore until actual worker exit and remains retryable`() {
        val logger = Logger.getAnonymousLogger().apply { level = Level.WARNING }
        var observation: PgRequireErrorObservation? = null
        try {
            TransportTestScope("MODEL").use { scope ->
                val gate = scope.gate()
                val call = scope.launch { gate.hold() }
                gate.awaitEntered()
                val installed = PgRequireErrorObservation.model(logger, call.thread) {}
                observation = installed
                val ownHandler = logger.handlers.single()
                assertTrue(call.thread.isAlive)
                assertThrows(IllegalStateException::class.java) { installed.close() }
                assertSame(Level.FINEST, logger.level)
                assertFalse(logger.useParentHandlers)
                assertSame(ownHandler, logger.handlers.single())
                gate.release()
                call.join()
                assertFalse(call.thread.isAlive)
                installed.close()
                assertSame(Level.WARNING, logger.level)
                assertTrue(logger.useParentHandlers)
                assertTrue(logger.handlers.isEmpty())
            }
        } finally {
            // Scope releases and joins even after an assertion fails; restoration must remain outside it.
            observation?.close()
        }
    }

    @Test
    fun `MODEL body failure first terminates its scoped worker then restores logging without masking failure`() {
        val logger = Logger.getAnonymousLogger().apply { level = null }
        val sentinel = IllegalStateException("Synthetic observer body failure.")
        var observation: PgRequireErrorObservation? = null
        var ownedThread: Thread? = null
        val failure = assertThrows(IllegalStateException::class.java) {
            try {
                TransportTestScope("MODEL").use { scope ->
                    val gate = scope.gate()
                    val call = scope.launch { gate.hold() }
                    ownedThread = call.thread
                    gate.awaitEntered()
                    observation = PgRequireErrorObservation.model(logger, call.thread) {}
                    throw sentinel
                }
            } finally {
                observation?.close()
            }
        }
        assertSame(sentinel, failure)
        assertFalse(requireNotNull(ownedThread).isAlive)
        assertNull(logger.level)
        assertTrue(logger.useParentHandlers)
        assertTrue(logger.handlers.isEmpty())
    }

    @Test
    fun `MODEL partial install rolls back even when setting finest changes state before throwing`() {
        val sentinel = IllegalStateException("Synthetic partial installation failure.")
        val logger = object : Logger(null, null) {
            private var failOnce = true

            override fun setLevel(newLevel: Level?) {
                super.setLevel(newLevel)
                if (newLevel === Level.FINEST && failOnce) {
                    failOnce = false
                    throw sentinel
                }
            }
        }
        logger.level = null
        logger.useParentHandlers = true
        val failure = assertThrows(IllegalStateException::class.java) { PgRequireErrorObservation.model(logger, inertThread()) {} }
        assertSame(sentinel, failure)
        assertNull(logger.level)
        assertTrue(logger.useParentHandlers)
        assertTrue(logger.handlers.isEmpty())
        PgRequireErrorObservation.model(logger, inertThread()) {}.close()
        assertNull(logger.level)
        assertTrue(logger.useParentHandlers)
        assertTrue(logger.handlers.isEmpty())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["REMOVE", "LEVEL", "PARENTS"])
    fun `MODEL restore failure attempts all remaining actions and a later close can retry`(operation: String) {
        val sentinel = IllegalStateException("Synthetic restoration failure.")
        var failOnce = false
        val logger = object : Logger(null, null) {
            private fun failIfSelected(selected: String) {
                if (failOnce && operation == selected) {
                    failOnce = false
                    throw sentinel
                }
            }

            override fun removeHandler(handler: Handler?) {
                failIfSelected("REMOVE")
                super.removeHandler(handler)
            }

            override fun setLevel(newLevel: Level?) {
                failIfSelected("LEVEL")
                super.setLevel(newLevel)
            }

            override fun setUseParentHandlers(useParents: Boolean) {
                failIfSelected("PARENTS")
                super.setUseParentHandlers(useParents)
            }
        }
        logger.level = Level.INFO
        logger.useParentHandlers = true
        val observation = PgRequireErrorObservation.model(logger, inertThread()) {}
        try {
            failOnce = true
            val failure = assertThrows(IllegalStateException::class.java) { observation.close() }
            assertSame(sentinel, failure)
            assertEquals(if (operation == "REMOVE") 1 else 0, logger.handlers.size)
            assertSame(if (operation == "LEVEL") Level.FINEST else Level.INFO, logger.level)
            assertEquals(operation != "PARENTS", logger.useParentHandlers)
            observation.close()
            assertTrue(logger.handlers.isEmpty())
            assertSame(Level.INFO, logger.level)
            assertTrue(logger.useParentHandlers)
        } finally {
            observation.close()
        }
    }

    private fun inertThread(): Thread = Thread.ofPlatform().inheritInheritableThreadLocals(false).unstarted {}

    private fun inertHandler(): Handler = object : Handler() {
        override fun publish(record: LogRecord?) = Unit

        override fun flush() = Unit

        override fun close() = Unit
    }
}
