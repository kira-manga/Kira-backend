package me.manga.kira.backend.common.infrastructure.persistence

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
    fun `many ambiguous callers coalesce one receipt and one authentic held acquisition tail keeps it pending`() = InertPoolFixture().use { fixture ->
        OwnedCallerTestScope().use { scope ->
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
            assertEquals(
                PoolShutdownInvocation.INITIALIZATION_PENDING,
                fixture.lifecycle.closePool(),
                "An admitted lazy constructor must finish before the only close.",
            )
            assertEquals(PersistenceTerminalCall.NOT_INVOKED, fixture.lifecycle.firstCloseOutcome())
            tails.dropLast(1).forEach(OwnedCallerTestGate::release)
            callers.dropLast(1).forEach { assertSame(shared, it.value()) }
            assertEquals(1L, fixture.lifecycle.activeAcquisitions())
            assertFalse(fixture.lifecycle.acquisitionsEnded())
            assertEquals(PoolShutdownObservation.PENDING, shared.observe())
            tails.last().release()
            assertSame(shared, callers.last().value())
            assertTrue(fixture.lifecycle.acquisitionsEnded())
            assertEquals(PoolShutdownInvocation.RETURNED, fixture.lifecycle.closePool())
            assertEquals(PoolShutdownObservation.POOL_ACTORS_UNPROVEN, shared.observe())
        }
    }

    @Test
    fun `future return entitlement prevents a closed-population claim until consumed and the actual outer tail ends`() = InertPoolFixture().use { fixture ->
        val acquisition = fixture.lifecycle.prepareAcquisition(PersistenceTimeBudget.start(30_000))
        assertTrue(acquisition.enter())
        var retained: PoolLifecycle.LeaseEntitlement? = null
        try {
            try {
                assertNull(acquisition.prepareLeaseEntitlement(), "No future return right precedes capture of an actual handle.")
                assertTrue(acquisition.capture(PhysicalTestConnection().raw)) // MODEL handle, no native Hikari lease.
                retained = requireNotNull(acquisition.prepareLeaseEntitlement())
                assertNull(acquisition.prepareLeaseEntitlement())
            } finally {
                assertTrue(acquisition.end())
            }
            val entitlement = requireNotNull(retained)
            val receipt = requireNotNull(fixture.lifecycle.requestShutdown())
            assertEquals(PoolShutdownInvocation.RETURNED, fixture.lifecycle.closePool())
            assertEquals(PoolShutdownObservation.PENDING, receipt.observe())
            assertEquals(1L, fixture.lifecycle.actorSnapshot().futureLeaseEntries)
            val returning = requireNotNull(entitlement.prepareReturn(PersistenceTimeBudget.start(30_000)))
            assertTrue(returning.enter(), "Previously issued cleanup remains counted after business admission is sealed.")
            try {
                assertEquals(0L, fixture.lifecycle.actorSnapshot().futureLeaseEntries)
                assertEquals(1L, fixture.lifecycle.actorSnapshot().activeOperations)
                assertNull(entitlement.prepareEviction(PersistenceTimeBudget.start(30_000)))
                assertFalse(entitlement.revoke(), "The future right was already consumed; this cannot finish the active return tail.")
                assertEquals(PoolShutdownObservation.ACTIVE_POOL_FRAME, receipt.observe())
            } finally {
                assertTrue(returning.end())
            }
            assertTrue(returning.actualFrameEnded())
            assertFalse(returning.end())
            assertEquals(PoolShutdownObservation.POOL_ACTORS_UNPROVEN, receipt.observe())
        } finally {
            retained?.revoke() // Only an unused MODEL future right; never substitutes for ending an admitted operation.
        }
    }

    @Test
    fun `unused entitlement revocation and original operation budget exclude later eviction without a replacement budget`() =
        InertPoolFixture().use { fixture ->
            val acquisition = fixture.lifecycle.prepareAcquisition(PersistenceTimeBudget.start(30_000))
            assertTrue(acquisition.enter())
            var retained: PoolLifecycle.LeaseEntitlement? = null
            try {
                try {
                    assertTrue(acquisition.capture(PhysicalTestConnection().raw))
                    retained = requireNotNull(acquisition.prepareLeaseEntitlement())
                } finally {
                    assertTrue(acquisition.end())
                }
                val entitlement = requireNotNull(retained)
                val clock = AtomicLong()
                val budget = PersistenceTimeBudget.start(10, clock::get)
                val eviction = requireNotNull(entitlement.prepareEviction(budget))
                clock.set(10_000_000)
                assertFalse(eviction.enter())
                assertEquals(1L, fixture.lifecycle.actorSnapshot().futureLeaseEntries)
                assertNull(entitlement.prepareReturn(PersistenceTimeBudget.start(30_000)))
                assertTrue(entitlement.revoke())
                assertFalse(entitlement.revoke())
                assertFalse(eviction.enter())
                assertEquals(0L, fixture.lifecycle.actorSnapshot().futureLeaseEntries)
            } finally {
                retained?.revoke()
            }
        }

    @Test
    fun `foreign and fabricated return entitlements never enter or consume the authentic future right`() = InertPoolFixture().use { fixture ->
        OwnedCallerTestScope().use { scope ->
            val acquisition = fixture.lifecycle.prepareAcquisition(PersistenceTimeBudget.start(30_000))
            assertTrue(acquisition.enter())
            var retained: PoolLifecycle.LeaseEntitlement? = null
            try {
                try {
                    assertTrue(acquisition.capture(PhysicalTestConnection().raw))
                    retained = requireNotNull(acquisition.prepareLeaseEntitlement())
                } finally {
                    assertTrue(acquisition.end())
                }
                val entitlement = requireNotNull(retained)
                val fake = PoolLifecycle.LeaseEntitlement.prepare(fixture.lifecycle, Any(), Thread.currentThread())
                assertNull(fake.prepareReturn(PersistenceTimeBudget.start(30_000)))
                assertFalse(fake.revoke())
                assertTrue(
                    scope.launch {
                        assertNull(entitlement.prepareReturn(PersistenceTimeBudget.start(30_000)))
                        assertFalse(entitlement.revoke())
                        true
                    }.value(),
                )
                assertEquals(1L, fixture.lifecycle.actorSnapshot().futureLeaseEntries)
                assertTrue(entitlement.revoke())
            } finally {
                retained?.revoke()
            }
        }
    }

    @Test
    fun `MODEL exact RETURN incident retains custody and seals only the empty UNKNOWN cut`() = InertPoolFixture().use { fixture ->
        InertPoolFixture().use { other ->
            OwnedCallerTestScope().use { callers ->
                assertModelReturnIncident(fixture, other, callers)
            }
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["request", "handoff"])
    fun `the first shutdown budget is retained across duplicate requests and cannot be replenished by observation`(origin: String) =
        InertPoolFixture().use { fixture ->
            val clock = AtomicLong()
            val original = PersistenceTimeBudget.start(10, clock::get)
            val receipt = if (origin == "handoff") {
                val acquisition = fixture.lifecycle.prepareAcquisition(original)
                assertTrue(acquisition.enter())
                try {
                    requireNotNull(acquisition.handoff())
                } finally {
                    assertTrue(acquisition.end())
                }
            } else {
                requireNotNull(fixture.lifecycle.requestShutdown(original))
            }
            clock.set(10_000_000)
            assertSame(receipt, fixture.lifecycle.requestShutdown(PersistenceTimeBudget.start(30_000)))
            assertEquals(PoolShutdownInvocation.RETURNED, fixture.lifecycle.closePool())
            assertEquals(PoolShutdownObservation.PENDING, receipt.observe())
            assertEquals(PoolShutdownObservation.PENDING, receipt.observe())
        }

    @Test
    fun `MODEL entry bookkeeping failure remains sticky and restores the authentic enclosing caller frame`() = InertPoolFixture().use { outer ->
        InertPoolFixture().use { inner ->
            val enclosing = outer.lifecycle.prepareAcquisition(PersistenceTimeBudget.start(30_000))
            assertTrue(enclosing.enter())
            try {
                val failure = IllegalStateException("MODEL acquisition clock observation failure.")
                var failObservation = false
                val budget = PersistenceTimeBudget.start(10) { if (failObservation) throw failure else 0L }
                val acquisition = inner.lifecycle.prepareAcquisition(budget)
                failObservation = true
                assertSame(failure, assertThrows<IllegalStateException> { acquisition.enter() })
                assertEquals(PoolAcquisitionDisposition.NOT_ENTERED, acquisition.disposition())
                assertFalse(acquisition.actualFrameEnded())
                assertSame(enclosing.frame, PoolCallFrames.current())
                assertEquals(0L, inner.lifecycle.activeAcquisitions())
                assertEquals(PoolActorFault.BOOKKEEPING_FAILED, inner.lifecycle.actorSnapshot().firstFailure)
                assertTrue(inner.lifecycle.actorSnapshot().factorySealed)
                assertFalse(inner.lifecycle.prepareAcquisition(PersistenceTimeBudget.start(30_000)).enter())
            } finally {
                assertTrue(enclosing.end())
            }
            val receipt = requireNotNull(inner.lifecycle.requestShutdown())
            assertEquals(PoolShutdownInvocation.RETURNED, inner.lifecycle.closePool())
            assertEquals(PoolShutdownObservation.UNKNOWN, receipt.observe())
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["sample", "restore"])
    fun `MODEL close caller bookkeeping failure restores its pool frame but never upgrades the first close outcome`(failureAt: String) =
        InertPoolFixture().use { fixture ->
            OwnedCallerTestScope().use { scope ->
                val failure = IllegalStateException("MODEL close caller bookkeeping failure.")
                val behavior = OwnedCallerTestBehavior().apply {
                    if (failureAt == "sample") sampleFailure = failure else restoreFailure = failure
                }
                val receipt = requireNotNull(fixture.lifecycle.requestShutdown())
                val closer = scope.launch(OwnedCallerTestKind.OVERRIDING, behavior) {
                    try {
                        if (failureAt == "restore") (Thread.currentThread() as OverridingCallerThread).setActualFlag()
                        val result = runCatching { fixture.lifecycle.closePool() }
                        assertNull(PoolCallFrames.current(), "A flag failure must not bypass restoration of the pool caller lineage.")
                        result
                    } finally {
                        Thread.interrupted() // Fixture cleanup only, after retaining the authentic close bookkeeping outcome.
                    }
                }
                assertSame(failure, closer.value().exceptionOrNull())
                assertEquals(PersistenceTerminalCall.RETURNED, fixture.lifecycle.firstCloseOutcome())
                assertEquals(PoolActorFault.BOOKKEEPING_FAILED, fixture.lifecycle.actorSnapshot().firstFailure)
                assertEquals(failureAt == "restore", fixture.lifecycle.closeInterruptionObserved())
                assertEquals(PoolShutdownObservation.UNKNOWN, receipt.observe())
                assertEquals(PoolShutdownInvocation.ALREADY_CLAIMED, fixture.lifecycle.closePool())
                assertEquals(PoolShutdownObservation.UNKNOWN, receipt.observe())
            }
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

private fun assertModelReturnIncident(fixture: InertPoolFixture, other: InertPoolFixture, callers: OwnedCallerTestScope) {
    val entitlement = modelLeaseEntitlement(fixture.lifecycle)
    val evictionEntitlement = modelLeaseEntitlement(fixture.lifecycle)
    val budget = PersistenceTimeBudget.start(30_000)
    val returning = requireNotNull(entitlement.prepareReturn(budget))
    val otherBefore = other.lifecycle.actorSnapshot()
    val prepared = fixture.lifecycle.actorSnapshot()
    assertNull(prepared.firstFailure)
    assertFalse(prepared.factorySealed, "No MODEL initialization fault may mask the final-seal branch.")
    assertFalse(returning.recordReturnIncidentBeforeEnd())
    assertEquals(prepared, fixture.lifecycle.actorSnapshot())
    assertTrue(returning.enter())
    try {
        val active = fixture.lifecycle.actorSnapshot()
        val forged = PoolLifecycle.Operation.prepare(fixture.lifecycle, returning.frame, entitlement)
        val wrongPool = PoolLifecycle.Operation.prepare(other.lifecycle, returning.frame, entitlement)
        assertFalse(
            forged.recordReturnIncidentBeforeEnd(),
            "Sharing the exact active frame and consumed right is not prepared-Operation identity.",
        )
        assertFalse(wrongPool.recordReturnIncidentBeforeEnd())
        assertTrue(callers.launch { !returning.recordReturnIncidentBeforeEnd() }.value())
        assertEquals(active, fixture.lifecycle.actorSnapshot())
        assertEquals(otherBefore, other.lifecycle.actorSnapshot())
        val eviction = requireNotNull(evictionEntitlement.prepareEviction(budget))
        assertTrue(eviction.enter())
        try {
            val nested = fixture.lifecycle.actorSnapshot()
            assertFalse(eviction.recordReturnIncidentBeforeEnd(), "An authentic EVICTION is not a RETURN incident grant.")
            assertFalse(returning.recordReturnIncidentBeforeEnd(), "Even the authentic RETURN must be the current top frame.")
            assertSame(eviction.frame, PoolCallFrames.current())
            assertEquals(nested, fixture.lifecycle.actorSnapshot())
        } finally {
            assertTrue(eviction.end())
        }
        assertFalse(eviction.recordReturnIncidentBeforeEnd())
        assertTrue(returning.recordReturnIncidentBeforeEnd())
        val incident = fixture.lifecycle.actorSnapshot()
        assertEquals(PoolActorFault.BOOKKEEPING_FAILED, incident.firstFailure)
        assertFalse(incident.factorySealed)
        assertEquals(0L, incident.futureLeaseEntries)
        assertEquals(1L, incident.activeOperations)
        assertSame(returning.frame, PoolCallFrames.current())
        assertSame(budget, returning.frame.budget)
        assertFalse(returning.actualFrameEnded() || returning.frame.completion.hasEnded())
        assertFalse(entitlement.revoke())
        assertNull(entitlement.prepareReturn(PersistenceTimeBudget.start(30_000)))
        assertFalse(fixture.lifecycle.prepareAcquisition(PersistenceTimeBudget.start(30_000)).enter())
        assertTrue(returning.recordReturnIncidentBeforeEnd())
        assertFalse(
            forged.recordReturnIncidentBeforeEnd(),
            "An earlier incident must not give the forged operation hard-failure authority either.",
        )
        assertEquals(incident, fixture.lifecycle.actorSnapshot())
    } finally {
        assertTrue(returning.end())
        evictionEntitlement.revoke() // Only an unused MODEL right if an earlier assertion failed.
    }
    assertFalse(returning.recordReturnIncidentBeforeEnd())
    assertTrue(returning.actualFrameEnded() && returning.frame.completion.hasEnded())
    assertNull(PoolCallFrames.current())
    val receipt = requireNotNull(fixture.lifecycle.requestShutdown())
    assertEquals(PoolShutdownInvocation.RETURNED, fixture.lifecycle.closePool())
    assertFalse(
        fixture.lifecycle.actorSnapshot().factorySealed,
        "No earlier hard failure or shutdown invocation seals this MODEL population.",
    )
    assertEquals(PoolShutdownObservation.UNKNOWN, receipt.observe())
    val ended = fixture.lifecycle.actorSnapshot()
    assertEquals(PoolActorFault.BOOKKEEPING_FAILED, ended.firstFailure)
    assertEquals(0L, ended.futureLeaseEntries)
    assertEquals(0L, ended.activeOperations)
    assertEquals(0, ended.constructing)
    assertEquals(0, ended.retainedGenerations)
    assertTrue(ended.factorySealed)
    assertEquals(PoolShutdownObservation.UNKNOWN, receipt.observe())
    assertEquals(ended, fixture.lifecycle.actorSnapshot())
    assertEquals(otherBefore, other.lifecycle.actorSnapshot())
}

/** MODEL captured handles only; the uninstalled inert pool cannot inject a startup fault into the incident oracle. */
private fun modelLeaseEntitlement(lifecycle: PoolLifecycle): PoolLifecycle.LeaseEntitlement {
    val acquisition = lifecycle.prepareAcquisition(PersistenceTimeBudget.start(30_000))
    assertTrue(acquisition.enter())
    return try {
        assertTrue(acquisition.capture(PhysicalTestConnection().raw))
        requireNotNull(acquisition.prepareLeaseEntitlement())
    } finally {
        assertTrue(acquisition.end())
    }
}

/** All native shells remain unstarted. Only the existing owner proves its inert actors ended; Hikari actor drain is not asserted. */
private class InertPoolFixture(pool: HikariDataSource = HikariDataSource()) : AutoCloseable {
    val owner = PersistenceJdbcLifecycleOwner(pgProbeEndpoint(1), 1, PersistencePathStyle.POSIX)
    val lifecycle = PoolLifecycle(pool, owner)

    override fun close() {
        val poolResult = runCatching {
            val invocation = lifecycle.closePool()
            check(invocation === PoolShutdownInvocation.RETURNED || invocation === PoolShutdownInvocation.ALREADY_CLAIMED)
        }
        val ownerResult = runCatching {
            owner.requestShutdown()
            check(owner.observeShutdown() === PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED)
        }
        poolResult.getOrThrow()
        ownerResult.getOrThrow()
    }
}

/** MODEL close callback/failure only; this override supplies no authentic Hikari disposal evidence. */
private class ModelPool(private val onClose: () -> Unit) : HikariDataSource() {
    override fun close() = onClose()
}
