package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLClientInfoException
import java.sql.SQLException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

/** Genuine lower facade against an inert/model-opened JDBC delegate. No Hikari or database proof. */
class PhysicalJdbcFacadeTest {
    @Test
    fun `lower self unwrap is guarded and vendor or private delegate escape never delegates`() {
        val fixture = LowerCoreFixture()
        assertSame(fixture.lower, fixture.lower.unwrap(Connection::class.java))
        assertSame(fixture.lower, fixture.lower.unwrap(PhysicalJdbcFacade::class.java))
        assertTrue(fixture.lower.isWrapperFor(Connection::class.java))
        assertFalse(fixture.lower.isWrapperFor(Runnable::class.java))
        assertThrows<SQLException> { fixture.lower.unwrap(Runnable::class.java) }
        assertEquals("PhysicalJdbcFacade(redacted)", fixture.lower.toString())
        assertEquals(0, fixture.nativeCalls.get())
    }

    @Test
    fun `pool epoch adopts successive actual foreground callers rather than the factory caller forever`() = OwnedCallerTestScope().use { scope ->
        val fixture = LowerCoreFixture()
        repeat(4) { assertTrue(scope.launch { fixture.lower.autoCommit }.value()) }
        assertTrue(fixture.lower.autoCommit)
        assertEquals(5, fixture.nativeCalls.get())
        assertFalse(fixture.prepared.epoch.foregroundActive())
        assertFalse(fixture.prepared.epoch.poisoned())
    }

    @Test
    fun `ordinary root reentry has no small depth cap and genuine calls end while G is held elsewhere`() = OwnedCallerTestScope().use { scope ->
        val depth = AtomicInteger()
        lateinit var fixture: LowerCoreFixture
        fixture = LowerCoreFixture { method ->
            assertEquals("getAutoCommit", method.name)
            val level = depth.incrementAndGet()
            try {
                if (level < 96) fixture.lower.autoCommit else true
            } finally {
                depth.decrementAndGet()
            }
        }
        fixture.model.binding.ledger.lock.withLock {
            assertTrue(scope.launch { fixture.lower.autoCommit }.value())
            assertFalse(fixture.prepared.epoch.foregroundActive())
        }
        assertEquals(96, fixture.nativeCalls.get())
        assertEquals(0, depth.get())
    }

    @Test
    fun `current-thread ownership lock denies native invocation rather than calling JDBC under G`() {
        val fixture = LowerCoreFixture()
        fixture.model.binding.ledger.lock.withLock { assertThrows<SQLException> { fixture.lower.autoCommit } }
        assertEquals(0, fixture.nativeCalls.get())
        assertTrue(fixture.lower.autoCommit)
    }

    @Test
    fun `ordinary checked business failure preserves exact exception and does not poison the physical owner`() {
        val business = SQLException("ordinary model failure", "23505", 7)
        val fixture = LowerCoreFixture { throw business }
        assertSame(business, assertThrows<SQLException> { fixture.lower.autoCommit })
        assertFalse(fixture.prepared.epoch.poisoned())
        assertFalse(fixture.model.entry.retirementRequested.get())
        assertFalse(fixture.prepared.epoch.foregroundActive())
    }

    @Test
    fun `setClientInfo refusal preserves its checked declaration without raw work`() {
        val fixture = LowerCoreFixture()
        fixture.lower.close()
        val failure = assertThrows<SQLClientInfoException> { fixture.lower.setClientInfo("ApplicationName", "model") }
        assertNull(failure.cause)
        assertEquals(0, fixture.nativeCalls.get())
    }

    @Test
    fun `physical close requests existing exact retirement and duplicate close performs no native action`() {
        val fixture = LowerCoreFixture()
        fixture.lower.close()
        fixture.lower.close()
        assertTrue(fixture.model.entry.retirementRequested.get())
        assertTrue(fixture.lower.isClosed)
        assertFalse(fixture.model.entry.retiring, "A request is not the G/T physical terminal fence.")
        assertEquals(0, fixture.nativeCalls.get())
        assertEquals(PersistenceFactoryProcessing.PENDING, fixture.prepared.result.receipt.state())
        fixture.model.retire()
        assertTrue(fixture.model.entry.jdbc.postOpeningCallsEnded())
        assertEquals(PersistenceTerminalCall.NOT_INVOKED, fixture.model.work.closeState())
    }

    @Test
    fun `accepted but unstarted supplied-executor abort remains counted after permanent terminal seal`() = OwnedCallerTestScope().use { scope ->
        val fixture = LowerCoreFixture()
        val command = AtomicReference<Runnable>()
        fixture.lower.abort(Executor { assertTrue(command.compareAndSet(null, it)) })
        assertEquals(1L, fixture.prepared.epoch.activeCancellations())
        fixture.model.retire()
        assertFalse(fixture.model.entry.jdbc.postOpeningCallsEnded())
        assertEquals(0, fixture.nativeCalls.get())
        assertTrue(
            scope.launch {
                command.get().run()
                true
            }.value(),
        )
        assertTrue(fixture.model.entry.jdbc.postOpeningCallsEnded())
        command.get().run()
        fixture.lower.abort(Executor { error("A duplicate abort must not redispatch.") })
        assertEquals(0L, fixture.prepared.epoch.activeCancellations())
        assertEquals(PersistenceTerminalCall.NOT_INVOKED, fixture.model.work.closeState())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(booleans = [false, true])
    fun `supplied-executor rejection before or after command entry never invents disposal or double-decrements`(runFirst: Boolean) {
        val fixture = LowerCoreFixture()
        val failure = assertThrows<SQLException> {
            fixture.lower.abort(
                Executor { command ->
                    if (runFirst) command.run()
                    throw RejectedExecutionException("model rejection")
                },
            )
        }
        assertNull(failure.cause)
        assertTrue(fixture.prepared.epoch.poisoned())
        assertEquals(0L, fixture.prepared.epoch.activeCancellations())
        fixture.model.retire()
        assertTrue(fixture.model.entry.jdbc.postOpeningCallsEnded())
        assertEquals(0, fixture.nativeCalls.get())
        assertEquals(PersistenceTerminalCall.NOT_INVOKED, fixture.model.work.closeState())
    }

    @Test
    fun `foreign terminal request does not wait for active native foreground and actual tail still blocks drain`() = OwnedCallerTestScope().use { scope ->
        val held = scope.gate()
        val fixture = LowerCoreFixture {
            held.hold()
            true
        }
        val caller = scope.launch { fixture.lower.autoCommit }
        held.awaitEntered()
        fixture.lower.abort(Executor(Runnable::run))
        fixture.model.retire()
        assertFalse(fixture.model.entry.jdbc.postOpeningCallsEnded())
        held.release()
        assertTrue(caller.value())
        assertTrue(fixture.model.entry.jdbc.postOpeningCallsEnded())
        assertEquals(1, fixture.nativeCalls.get())
    }
}

private class LowerCoreFixture(operation: (Method) -> Any? = { true }) {
    val nativeCalls = AtomicInteger()
    private val raw = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
        nativeCalls.incrementAndGet()
        operation(method)
    } as Connection
    val model = PersistenceOwnershipTestFixture(raw = raw)
    val prepared = PreparedPoolConnection.prepare(model.entry, model.binding)
    val lower: PhysicalJdbcFacade = prepared.result.value

    init {
        check(model.binding.takePoolConnection(model.entry, prepared))
    }
}
