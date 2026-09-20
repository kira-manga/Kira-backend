package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock

class PersistencePhysicalTerminalTest {
    @Test
    fun `one terminal identity waits for late raw and grants it exactly once`() = FactoryWorkerTestScope().use { scope ->
        val registry = PersistencePhysicalRegistry(1)
        val record = dispatchPhysical(registry)
        val resource = PhysicalTestConnection()
        val returning = scope.gate()
        val caller = scope.launch {
            registry.invokeOpening(record) {
                returning.hold()
                resource.raw
            }
        }
        returning.awaitEntered()
        assertTrue(registry.requestRetirement(record))
        val claim = terminalPhysical(registry, record)
        repeat(3) {
            assertSame(PersistencePhysicalRawDecision.WaitingForOpening, registry.takeTerminalRaw(claim))
            assertSame(PersistencePhysicalTerminal.Refused, registry.claimTerminal(record))
            assertFalse(registry.releaseUnused(record))
            assertPhysicalRefused(registry, PersistencePhysicalRefusal.FULL)
        }
        assertNull(physicalRetainedRaw(registry, record))
        assertEquals(1, physicalSnapshot(registry).activeOpening)
        returning.release()
        assertEquals(PersistencePhysicalOpening.RETAINED_FOR_RETIREMENT, caller.join())
        assertPhysicalRaw(registry, claim, resource.raw)
        assertEquals(0, physicalSnapshot(registry).activeOpening)
        assertEquals(1, physicalSnapshot(registry).occupied)
        assertEquals(1, physicalSnapshot(registry).terminalClaimed)
        assertEquals(0, resource.calls.get())
    }

    @Test
    fun `raw is already retained before actual settlement lock reacquisition while terminal still waits`() = FactoryWorkerTestScope().use { scope ->
        val registry = PersistencePhysicalRegistry(1)
        val record = dispatchPhysical(registry)
        val resource = PhysicalTestConnection()
        val returning = scope.gate()
        val caller = scope.launch {
            registry.invokeOpening(record) {
                returning.hold()
                resource.raw
            }
        }
        returning.awaitEntered()
        assertTrue(registry.requestRetirement(record))
        val claim = terminalPhysical(registry, record)
        val lock = physicalTestLock(registry)
        lock.withLock {
            returning.release()
            awaitFactoryTestFact { physicalRetainedRaw(registry, record) === resource.raw && lock.hasQueuedThread(caller.thread) }
            assertTrue(caller.thread.isAlive)
            assertSame(resource.raw, physicalRetainedRaw(registry, record))
            assertEquals(1, physicalSnapshot(registry).activeOpening)
            // This test thread holds the real lock: the opener cannot have published settlement.
            assertSame(PersistencePhysicalRawDecision.WaitingForOpening, registry.takeTerminalRaw(claim))
            assertFalse(registry.releaseUnused(record))
            assertEquals(1, physicalSnapshot(registry).occupied)
        }
        assertEquals(PersistencePhysicalOpening.RETAINED_FOR_RETIREMENT, caller.join())
        assertPhysicalRaw(registry, claim, resource.raw)
        assertEquals(0, physicalSnapshot(registry).activeOpening)
        assertEquals(0, resource.calls.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["NULL", "RUNTIME", "DIRECT", "ERROR"])
    fun `early terminal owner waits through actual late no raw and fatal callback unwinding`(mode: String) = FactoryWorkerTestScope().use { scope ->
        val registry = PersistencePhysicalRegistry(1)
        val record = dispatchPhysical(registry)
        val failures = PhysicalTestFailures()
        val returning = scope.gate()
        val unwound = AtomicBoolean()
        val caller = scope.launch {
            runCatching {
                registry.invokeOpening(record) {
                    try {
                        returning.hold()
                        failures.produce(mode)
                    } finally {
                        unwound.set(true)
                    }
                }
            }
        }
        returning.awaitEntered()
        assertTrue(registry.requestRetirement(record))
        val claim = terminalPhysical(registry, record)
        assertFalse(unwound.get())
        assertSame(PersistencePhysicalRawDecision.WaitingForOpening, registry.takeTerminalRaw(claim))
        assertEquals(1, physicalSnapshot(registry).activeOpening)
        assertPhysicalRefused(registry, PersistencePhysicalRefusal.FULL)
        returning.release()
        val result = caller.join()
        when (mode) {
            "ERROR" -> assertSame(failures.fatal, result.exceptionOrNull())
            "NULL" -> assertEquals(PersistencePhysicalOpening.NO_RAW_RETURN, result.getOrThrow())
            else -> assertEquals(PersistencePhysicalOpening.FAILED, result.getOrThrow())
        }
        assertTrue(unwound.get())
        assertEquals(0, physicalSnapshot(registry).activeOpening)
        assertEquals(if (mode == "NULL") 0 else 1, physicalSnapshot(registry).unknown)
        assertSame(PersistencePhysicalRawDecision.NoRawReturned, registry.takeTerminalRaw(claim))
        assertSame(PersistencePhysicalRawDecision.AlreadyTaken, registry.takeTerminalRaw(claim))
        assertSame(PersistencePhysicalTerminal.Refused, registry.claimTerminal(record))
        assertFalse(registry.releaseUnused(record))
        assertPhysicalRefused(registry, PersistencePhysicalRefusal.FULL)
        assertEquals(0, failures.runtime.reads.get())
        assertEquals(0, failures.fatal.reads.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["RETIRE", "SEAL"])
    fun `a raw retained before retirement is granted once without being cleared`(mode: String) = FactoryWorkerTestScope().use {
        val registry = PersistencePhysicalRegistry(1)
        val record = dispatchPhysical(registry)
        val resource = PhysicalTestConnection()
        assertEquals(PersistencePhysicalOpening.RETAINED, registry.invokeOpening(record) { resource.raw })
        assertEquals(1, physicalSnapshot(registry).live)
        fencePhysical(registry, record, mode)
        val claim = terminalPhysical(registry, record)
        assertPhysicalRaw(registry, claim, resource.raw)
        assertFalse(registry.releaseUnused(record))
        assertEquals(1, physicalSnapshot(registry).retiring)
        assertEquals(0, resource.calls.get())
    }

    @Test
    fun `a later caller wrapper failure cannot conceal the already retained normal return`() = FactoryWorkerTestScope().use { scope ->
        val registry = PersistencePhysicalRegistry(1)
        val record = dispatchPhysical(registry)
        val resource = PhysicalTestConnection()
        val wrapperFailure = PhysicalHostileFailure()
        val caller = scope.launch {
            runCatching {
                assertEquals(PersistencePhysicalOpening.RETAINED, registry.invokeOpening(record) { resource.raw })
                // Deliberately AFTER the raw-return boundary, not hidden inside the opening callback.
                throw wrapperFailure
            }
        }
        assertSame(wrapperFailure, caller.join().exceptionOrNull())
        assertSame(resource.raw, physicalRetainedRaw(registry, record))
        assertTrue(registry.requestRetirement(record))
        assertPhysicalRaw(registry, terminalPhysical(registry, record), resource.raw)
        assertFalse(registry.releaseUnused(record))
        assertEquals(0, wrapperFailure.reads.get())
        assertEquals(0, resource.calls.get())
    }

    @Test
    fun `a failed interrupted terminal consumer cannot erase ownership or request a second grant`() = FactoryWorkerTestScope().use { scope ->
        val registry = PersistencePhysicalRegistry(1)
        val record = dispatchPhysical(registry)
        val resource = PhysicalTestConnection()
        assertEquals(PersistencePhysicalOpening.RETAINED, registry.invokeOpening(record) { resource.raw })
        assertTrue(registry.requestRetirement(record))
        val claim = terminalPhysical(registry, record)
        val consumerFailure = PhysicalHostileFailure()
        val consumer = scope.launch {
            try {
                runCatching {
                    val decision = registry.takeTerminalRaw(claim) as PersistencePhysicalRawDecision.Granted
                    assertSame(resource.raw, decision.raw)
                    Thread.currentThread().interrupt()
                    throw consumerFailure
                }.also { assertTrue(Thread.currentThread().isInterrupted) }
            } finally {
                Thread.interrupted()
            }
        }
        assertSame(consumerFailure, consumer.join().exceptionOrNull())
        assertSame(resource.raw, physicalRetainedRaw(registry, record))
        assertSame(PersistencePhysicalRawDecision.AlreadyTaken, registry.takeTerminalRaw(claim))
        assertSame(PersistencePhysicalTerminal.Refused, registry.claimTerminal(record))
        assertFalse(registry.releaseUnused(record))
        assertPhysicalRefused(registry, PersistencePhysicalRefusal.FULL)
        assertEquals(0, resource.calls.get())
        assertEquals(0, consumerFailure.reads.get())
    }

    @Test
    fun `two actual queued terminal contenders receive one claim and one raw grant`() = FactoryWorkerTestScope().use { scope ->
        val registry = PersistencePhysicalRegistry(1)
        val record = dispatchPhysical(registry)
        val resource = PhysicalTestConnection()
        assertEquals(PersistencePhysicalOpening.RETAINED, registry.invokeOpening(record) { resource.raw })
        assertTrue(registry.requestRetirement(record))
        val lock = physicalTestLock(registry)
        val claimants = lock.withLock {
            val first = scope.launch { registry.claimTerminal(record) }
            val second = scope.launch { registry.claimTerminal(record) }
            awaitFactoryTestFact { lock.hasQueuedThread(first.thread) && lock.hasQueuedThread(second.thread) }
            listOf(first, second)
        }
        val claims = claimants.map { it.join() }
        assertEquals(1, claims.count { it === PersistencePhysicalTerminal.Refused })
        val claim = claims.filterIsInstance<PersistencePhysicalTerminal.Claimed>().single().claim
        val takers = lock.withLock {
            val first = scope.launch { registry.takeTerminalRaw(claim) }
            val second = scope.launch { registry.takeTerminalRaw(claim) }
            awaitFactoryTestFact { lock.hasQueuedThread(first.thread) && lock.hasQueuedThread(second.thread) }
            listOf(first, second)
        }
        val decisions = takers.map { it.join() }
        assertEquals(1, decisions.count { it === PersistencePhysicalRawDecision.AlreadyTaken })
        assertSame(resource.raw, decisions.filterIsInstance<PersistencePhysicalRawDecision.Granted>().single().raw)
        assertSame(resource.raw, physicalRetainedRaw(registry, record))
        assertSame(PersistencePhysicalTerminal.Refused, registry.claimTerminal(record))
        assertFalse(registry.releaseUnused(record))
        assertEquals(1, physicalSnapshot(registry).terminalClaimed)
        assertEquals(0, resource.calls.get())
    }

    @Test
    fun `forged foreign and stale terminal identities cannot consume the real decision`() = FactoryWorkerTestScope().use {
        val registry = PersistencePhysicalRegistry(1)
        val stale = reservePhysical(registry)
        assertTrue(registry.releaseUnused(stale))
        val record = dispatchPhysical(registry)
        val resource = PhysicalTestConnection()
        assertEquals(PersistencePhysicalOpening.RETAINED, registry.invokeOpening(record) { resource.raw })
        assertTrue(registry.requestRetirement(record))
        val claim = terminalPhysical(registry, record)
        val other = PersistencePhysicalRegistry(1)
        val foreign = dispatchPhysical(other)
        assertEquals(PersistencePhysicalOpening.NO_RAW_RETURN, other.invokeOpening(foreign) { null })
        val foreignClaim = terminalPhysical(other, foreign)
        val invalid = listOf(
            PersistencePhysicalTerminalClaim(record),
            PersistencePhysicalTerminalClaim(PersistencePhysicalRecord(record.slotHint)),
            PersistencePhysicalTerminalClaim(stale),
            foreignClaim,
        )
        for (candidate in invalid) {
            assertSame(PersistencePhysicalRawDecision.Refused, registry.takeTerminalRaw(candidate))
        }
        assertSame(PersistencePhysicalRawDecision.Refused, other.takeTerminalRaw(claim))
        assertPhysicalRaw(registry, claim, resource.raw)
        assertSame(PersistencePhysicalRawDecision.NoRawReturned, other.takeTerminalRaw(foreignClaim))
        assertEquals(0, resource.calls.get())
    }
}
