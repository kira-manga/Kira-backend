package me.manga.kira.backend.common.infrastructure.persistence

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock

/** Inert Hikari/owner shells and explicitly MODEL close faults. No pool starts, connections, services or native-drain proof. */
class PoolLifecycleTest {
    @Test
    fun `original acquisition budget is never restarted and a sealed zero still needs genuine shutdown conjunctions`() = InertPoolFixture().use { fixture ->
        val lifecycle = fixture.lifecycle
        val clock = AtomicLong()
        val budget = PersistenceTimeBudget.start(10, clock::get)
        val ticket = lifecycle.prepareAcquisition(budget)
        clock.set(10_000_000)
        assertFalse(ticket.enter())
        assertEquals(PoolAcquisitionDisposition.NOT_ENTERED, ticket.disposition())
        assertFalse(lifecycle.acquisitionsEnded(), "An unsealed zero cannot certify no future acquisitions.")
        val receipt = requireNotNull(lifecycle.requestShutdown())
        assertTrue(lifecycle.acquisitionsEnded())
        assertFalse(lifecycle.prepareAcquisition(PersistenceTimeBudget.start(30_000)).enter())
        assertEquals(PoolShutdownObservation.PENDING, receipt.observe())
        assertEquals(PersistenceTerminalCall.NOT_INVOKED, lifecycle.firstCloseOutcome())
        assertEquals(PoolShutdownInvocation.RETURNED, lifecycle.closePool())
        assertEquals(PoolShutdownObservation.POOL_ACTORS_UNPROVEN, receipt.observe())
        assertEquals(PoolShutdownObservation.UNACCEPTED, PoolLifecycle.ShutdownReceipt.prepare(lifecycle).observe())
    }

    @Test
    fun `nested caller-owned acquisitions have no pool-capacity cap and no cross-pool shutdown wait cycle`() = InertPoolFixture().use { first ->
        InertPoolFixture().use { second ->
            val tickets = List(16) { first.lifecycle.prepareAcquisition(PersistenceTimeBudget.start(30_000)) }
            val crossPool = second.lifecycle.prepareAcquisition(PersistenceTimeBudget.start(30_000))
            try {
                tickets.forEach { assertTrue(it.enter()) }
                assertTrue(crossPool.enter())
                assertEquals(16L, first.lifecycle.activeAcquisitions())
                assertFalse(tickets.first().end(), "Acquisition frames must end in the authentic caller's nesting order.")
                val receipt = requireNotNull(tickets.first().handoff())
                assertSame(receipt, tickets.last().handoff())
                assertEquals(PoolShutdownInvocation.ACTIVE_ACQUISITION, first.lifecycle.closePool())
                assertEquals(PoolShutdownInvocation.ACTIVE_ACQUISITION, second.lifecycle.closePool())
                assertEquals(PoolShutdownObservation.ACTIVE_ACQUISITION, receipt.observe())
                assertFalse(first.lifecycle.acquisitionsEnded())
                assertEquals(PersistenceTerminalCall.NOT_INVOKED, first.lifecycle.firstCloseOutcome())
                assertEquals(PoolAcquisitionDisposition.ROOT_PENDING, tickets.first().disposition())
            } finally {
                crossPool.end()
                tickets.asReversed().forEach { it.end() }
            }
            assertTrue(tickets.all { it.actualFrameEnded() })
            assertTrue(first.lifecycle.acquisitionsEnded())
            assertFalse(tickets.last().end())
            assertEquals(0L, first.lifecycle.activeAcquisitions())
            assertEquals(0L, second.lifecycle.activeAcquisitions())
        }
    }

    @Test
    fun `many ambiguous callers coalesce one receipt and one authentic held acquisition tail keeps it pending`() = OwnedCallerTestScope().use { scope ->
        val fixture = InertPoolFixture()
        scope.beforeClose(fixture::close)
        val entered = List(8) { scope.gate() }
        val tails = List(8) { scope.gate() }
        val receipts = List(8) { AtomicReference<PoolLifecycle.ShutdownReceipt>() }
        val callers = entered.indices.map { index ->
            scope.launch {
                val ticket = fixture.lifecycle.prepareAcquisition(PersistenceTimeBudget.start(30_000))
                check(ticket.enter())
                try {
                    entered[index].hold()
                    val receipt = requireNotNull(ticket.handoff()) // MODEL ambiguous outcome; no native handle was manufactured.
                    receipts[index].set(receipt)
                    tails[index].hold()
                    receipt
                } finally {
                    check(ticket.end())
                }
            }
        }
        entered.forEach(OwnedCallerTestGate::awaitEntered)
        assertEquals(8L, fixture.lifecycle.activeAcquisitions())
        entered.forEach(OwnedCallerTestGate::release)
        tails.forEach(OwnedCallerTestGate::awaitEntered)
        val shared = requireNotNull(receipts.first().get())
        receipts.forEach { assertSame(shared, it.get()) }
        assertSame(shared, fixture.lifecycle.requestShutdown())
        assertEquals(PoolShutdownInvocation.RETURNED, fixture.lifecycle.closePool(), "A different shutdown caller may unblock active acquisitions.")
        tails.dropLast(1).forEach(OwnedCallerTestGate::release)
        callers.dropLast(1).forEach { assertSame(shared, it.value()) }
        assertEquals(1L, fixture.lifecycle.activeAcquisitions())
        assertFalse(fixture.lifecycle.acquisitionsEnded())
        assertEquals(PoolShutdownObservation.PENDING, shared.observe())
        tails.last().release()
        assertSame(shared, callers.last().value())
        assertTrue(fixture.lifecycle.acquisitionsEnded())
        assertEquals(PoolShutdownObservation.POOL_ACTORS_UNPROVEN, shared.observe())
    }

    @Test
    fun `wrong pool foreign caller and fabricated tickets cannot hand off or forget a privately captured handle`() = OwnedCallerTestScope().use { scope ->
        val fixture = InertPoolFixture()
        val other = InertPoolFixture()
        scope.beforeClose(fixture::close)
        scope.beforeClose(other::close)
        val ticket = fixture.lifecycle.prepareAcquisition(PersistenceTimeBudget.start(30_000))
        val raw = PhysicalTestConnection()
        assertTrue(ticket.enter())
        try {
            assertTrue(ticket.capture(raw.raw))
            assertFalse(ticket.capture(raw.raw))
            assertNull(other.lifecycle.handoff(ticket))
            assertEquals(PoolAcquisitionDisposition.CALLER_OWNED, ticket.disposition())
            val fabricated = PoolLifecycle.Acquisition.prepare(fixture.lifecycle, Any(), Thread.currentThread(), PersistenceTimeBudget.start(30_000))
            assertFalse(fabricated.enter())
            assertNull(fabricated.handoff())
            assertFalse(fabricated.end())
            assertTrue(
                scope.launch {
                    assertFalse(ticket.capture(raw.raw))
                    assertNull(ticket.handoff())
                    assertFalse(ticket.end())
                    true
                }.value(),
            )
            assertEquals(PoolAcquisitionDisposition.CALLER_OWNED, ticket.disposition())
            assertEquals(1L, fixture.lifecycle.activeAcquisitions())
            assertFalse(other.lifecycle.acquisitionsEnded(), "A wrong-pool handoff must not even seal that unrelated pool.")
            assertEquals(0, raw.calls.get())
            val accepted = requireNotNull(ticket.handoff())
            assertEquals(PoolAcquisitionDisposition.ROOT_PENDING, ticket.disposition())
            assertEquals(PoolShutdownObservation.ACTIVE_ACQUISITION, accepted.observe())
        } finally {
            assertTrue(ticket.end())
        }
        assertEquals(0, raw.calls.get(), "No guessed close or eviction is authorized by an uncertain handoff.")
    }

    @Test
    fun `MODEL held first close frame cannot observe its own completion and duplicate close never replaces its outcome`() =
        OwnedCallerTestScope().use { scope ->
            val held = scope.gate()
            val closes = AtomicInteger()
            val nestedObservation = AtomicReference<PoolShutdownObservation>()
            lateinit var lifecycle: PoolLifecycle
            val pool = ModelPool {
                closes.incrementAndGet()
                nestedObservation.set(requireNotNull(lifecycle.requestShutdown()).observe())
                held.hold()
            }
            val fixture = InertPoolFixture(pool)
            lifecycle = fixture.lifecycle
            scope.beforeClose(fixture::close)
            val shared = requireNotNull(lifecycle.requestShutdown())
            val closer = scope.launch { lifecycle.closePool() }
            held.awaitEntered()
            assertEquals(PoolShutdownObservation.ACTIVE_SHUTDOWN_FRAME, nestedObservation.get())
            assertEquals(PoolShutdownObservation.PENDING, shared.observe())
            assertEquals(PoolShutdownInvocation.ALREADY_CLAIMED, lifecycle.closePool())
            assertEquals(1, closes.get())
            assertEquals(PersistenceTerminalCall.RUNNING, lifecycle.firstCloseOutcome())
            held.release()
            assertEquals(PoolShutdownInvocation.RETURNED, closer.value())
            assertEquals(PersistenceTerminalCall.RETURNED, lifecycle.firstCloseOutcome())
            assertEquals(PoolShutdownObservation.POOL_ACTORS_UNPROVEN, shared.observe())
            assertEquals(1, closes.get())
        }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["THREW", "THREW_INTERRUPT", "CAUGHT_INTERRUPT"])
    fun `MODEL first failed or interrupted close stays unknown without a successful retry`(mode: String) = OwnedCallerTestScope().use { scope ->
        val closes = AtomicInteger()
        val failure = if (mode == "THREW_INTERRUPT") InterruptedException("MODEL close interruption.") else IllegalStateException("MODEL close failure.")
        val pool = ModelPool {
            closes.incrementAndGet()
            if (mode == "CAUGHT_INTERRUPT") Thread.currentThread().interrupt() else throw failure
        }
        val fixture = InertPoolFixture(pool)
        scope.beforeClose(fixture::close)
        val shared = requireNotNull(fixture.lifecycle.requestShutdown())
        val closer = scope.launch {
            try {
                runCatching { fixture.lifecycle.closePool() } to Thread.currentThread().isInterrupted
            } finally {
                Thread.interrupted() // Fixture-thread cleanup, after retaining the real flag observation.
            }
        }
        val (result, flag) = closer.value()
        if (mode == "CAUGHT_INTERRUPT") {
            assertEquals(PoolShutdownInvocation.RETURNED, result.getOrThrow())
            assertEquals(PersistenceTerminalCall.RETURNED, fixture.lifecycle.firstCloseOutcome())
        } else {
            assertSame(failure, result.exceptionOrNull())
            assertEquals(PersistenceTerminalCall.THREW, fixture.lifecycle.firstCloseOutcome())
        }
        assertEquals(mode != "THREW", flag)
        assertEquals(mode != "THREW", fixture.lifecycle.closeInterruptionObserved())
        assertEquals(PoolShutdownObservation.UNKNOWN, shared.observe())
        assertEquals(PoolShutdownInvocation.ALREADY_CLAIMED, fixture.lifecycle.closePool())
        assertEquals(PoolShutdownObservation.UNKNOWN, shared.observe())
        assertEquals(1, closes.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["F", "G"])
    fun `native close and possibly waiting observation are refused beneath actual ownership locks`(held: String) = InertPoolFixture().use { fixture ->
        val root = lifecycleField(fixture.owner, "root") as PersistenceJdbcDriverRoot
        val binding = lifecycleField(root.ordinary, "binding") as PersistencePhysicalFactoryBinding
        val lock = if (held == "F") binding.rendezvous.lock else binding.ledger.lock
        val ticket = fixture.lifecycle.prepareAcquisition(PersistenceTimeBudget.start(30_000))
        lock.withLock {
            assertFalse(ticket.enter())
            assertNull(fixture.lifecycle.requestShutdown())
            assertEquals(PoolShutdownInvocation.OWNERSHIP_LOCK_HELD, fixture.lifecycle.closePool())
            assertEquals(PersistenceTerminalCall.NOT_INVOKED, fixture.lifecycle.firstCloseOutcome())
        }
        assertTrue(ticket.enter(), "Lock refusal must neither enter nor consume the prepared acquisition.")
        assertTrue(ticket.end())
        val shared = requireNotNull(fixture.lifecycle.requestShutdown())
        lock.withLock { assertEquals(PoolShutdownObservation.OWNERSHIP_LOCK_HELD, shared.observe()) }
        assertEquals(PoolShutdownInvocation.RETURNED, fixture.lifecycle.closePool())
        assertEquals(PoolShutdownObservation.POOL_ACTORS_UNPROVEN, shared.observe())
    }
}

/** All native shells remain unstarted. Only the existing owner proves its inert actors ended; Hikari actor drain is not asserted. */
private class InertPoolFixture(pool: HikariDataSource = HikariDataSource()) : AutoCloseable {
    val owner = PersistenceJdbcLifecycleOwner(pgProbeEndpoint(1), 1, PersistencePathStyle.POSIX)
    val lifecycle = PoolLifecycle(pool, owner)

    override fun close() {
        val invocation = lifecycle.closePool()
        check(invocation === PoolShutdownInvocation.RETURNED || invocation === PoolShutdownInvocation.ALREADY_CLAIMED)
        check(owner.observeShutdown() === PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED)
    }
}

/** MODEL close callback/failure only; this override supplies no authentic Hikari disposal evidence. */
private class ModelPool(private val onClose: () -> Unit) : HikariDataSource() {
    override fun close() = onClose()
}
