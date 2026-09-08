package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.withLock

class PersistencePhysicalOpeningTest {
    @Test
    fun `dispatch and opening are each one shot including an active duplicate`() = FactoryWorkerTestScope().use { scope ->
        val registry = PersistencePhysicalRegistry(1)
        val record = reservePhysical(registry)
        val calls = AtomicInteger()
        assertEquals(
            PersistencePhysicalOpening.REFUSED,
            registry.invokeOpening(record) {
                calls.incrementAndGet()
                null
            },
        )
        assertTrue(registry.claimDispatch(record))
        assertFalse(registry.claimDispatch(record))
        val returning = scope.gate()
        val resource = PhysicalTestConnection()
        val caller = scope.launch {
            registry.invokeOpening(record) {
                calls.incrementAndGet()
                returning.hold()
                resource.raw
            }
        }
        returning.awaitEntered()
        assertEquals(1, physicalSnapshot(registry).activeOpening)
        assertFalse(registry.releaseUnused(record))
        assertEquals(
            PersistencePhysicalOpening.REFUSED,
            registry.invokeOpening(record) {
                calls.incrementAndGet()
                null
            },
        )
        returning.release()
        assertEquals(PersistencePhysicalOpening.RETAINED, caller.join())
        assertEquals(
            PersistencePhysicalOpening.REFUSED,
            registry.invokeOpening(record) {
                calls.incrementAndGet()
                null
            },
        )
        assertFalse(registry.claimDispatch(record))
        assertEquals(1, calls.get())
        assertEquals(1, physicalSnapshot(registry).live)
        assertEquals(0, physicalSnapshot(registry).activeOpening)
        assertEquals(0, resource.calls.get())
    }

    @Test
    fun `normal raw identity is retained without any resource method or diagnostic rendering`() = FactoryWorkerTestScope().use {
        val registry = PersistencePhysicalRegistry(1)
        val record = dispatchPhysical(registry)
        val resource = PhysicalTestConnection()
        assertEquals(PersistencePhysicalOpening.RETAINED, registry.invokeOpening(record) { resource.raw })
        assertSame(resource.raw, physicalRetainedRaw(registry, record))
        assertSame(PersistencePhysicalTerminal.Refused, registry.claimTerminal(record))
        assertTrue(registry.requestRetirement(record))
        val claim = terminalPhysical(registry, record)
        assertEquals("PersistencePhysicalTerminalClaim(redacted)", claim.toString())
        assertPhysicalRaw(registry, claim, resource.raw)
        assertPhysicalRefused(registry, PersistencePhysicalRefusal.FULL)
        assertFalse(registry.releaseUnused(record))
        assertEquals(0, resource.calls.get())
        assertThrows(IllegalStateException::class.java) { resource.raw.toString() }
        assertEquals(1, resource.calls.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["NULL", "RUNTIME", "DIRECT", "ERROR"])
    fun `no raw and thrown outcomes settle without graph inspection or physical release`(mode: String) = FactoryWorkerTestScope().use {
        val registry = PersistencePhysicalRegistry(1)
        val record = dispatchPhysical(registry)
        val failures = PhysicalTestFailures()
        val calls = AtomicInteger()
        val result = runCatching {
            registry.invokeOpening(record) {
                calls.incrementAndGet()
                failures.produce(mode)
            }
        }
        when (mode) {
            "ERROR" -> assertSame(failures.fatal, result.exceptionOrNull())
            "NULL" -> assertEquals(PersistencePhysicalOpening.NO_RAW_RETURN, result.getOrThrow())
            else -> assertEquals(PersistencePhysicalOpening.FAILED, result.getOrThrow())
        }
        val snapshot = physicalSnapshot(registry)
        assertEquals(1, snapshot.occupied)
        assertEquals(0, snapshot.activeOpening)
        assertEquals(0, snapshot.live)
        assertEquals(if (mode == "NULL") 1 else 0, snapshot.retiring)
        assertEquals(if (mode == "NULL") 0 else 1, snapshot.unknown)
        val claim = terminalPhysical(registry, record)
        assertSame(PersistencePhysicalRawDecision.NoRawReturned, registry.takeTerminalRaw(claim))
        assertSame(PersistencePhysicalRawDecision.AlreadyTaken, registry.takeTerminalRaw(claim))
        assertFalse(registry.releaseUnused(record))
        assertEquals(
            PersistencePhysicalOpening.REFUSED,
            registry.invokeOpening(record) {
                calls.incrementAndGet()
                null
            },
        )
        assertEquals(1, calls.get())
        assertNull(physicalRetainedRaw(registry, record))
        assertEquals(0, failures.runtime.reads.get())
        assertEquals(0, failures.fatal.reads.get())
        assertEquals("synthetic-physical-failure", failures.runtime.message)
        assertNull(failures.runtime.cause)
        failures.runtime.toString()
        failures.runtime.hashCode()
        failures.runtime.equals(Any())
        assertEquals(5, failures.runtime.reads.get())
        assertEquals("synthetic-physical-error", failures.fatal.toString())
        assertEquals(1, failures.fatal.reads.get())
    }

    @Test
    fun `already observed interruption preserves the flag without claiming or calling opening`() = FactoryWorkerTestScope().use { scope ->
        val registry = PersistencePhysicalRegistry(1)
        val record = dispatchPhysical(registry)
        val calls = AtomicInteger()
        val caller = scope.launch {
            Thread.currentThread().interrupt()
            try {
                val result = registry.invokeOpening(record) {
                    calls.incrementAndGet()
                    null
                }
                assertTrue(Thread.currentThread().isInterrupted)
                result
            } finally {
                Thread.interrupted()
            }
        }
        assertEquals(PersistencePhysicalOpening.INTERRUPTED, caller.join())
        assertEquals(0, calls.get())
        assertEquals(0, physicalSnapshot(registry).activeOpening)
        assertEquals(1, physicalSnapshot(registry).opening)
        assertFalse(registry.releaseUnused(record))
        assertTrue(registry.requestRetirement(record))
        assertSame(PersistencePhysicalRawDecision.NoRawReturned, registry.takeTerminalRaw(terminalPhysical(registry, record)))
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["THROW_INTERRUPTED", "RAW_INTERRUPTED", "NULL_INTERRUPTED", "FAILURE_INTERRUPTED"])
    fun `actual interrupted invocation settles unknown and preserves or restores its flag`(mode: String) = FactoryWorkerTestScope().use { scope ->
        val registry = PersistencePhysicalRegistry(1)
        val record = dispatchPhysical(registry)
        val resource = PhysicalTestConnection()
        val failure = PhysicalHostileFailure()
        val calls = AtomicInteger()
        val caller = scope.launch {
            try {
                val result = registry.invokeOpening(record) {
                    calls.incrementAndGet()
                    if (mode == "THROW_INTERRUPTED") throw InterruptedException("Synthetic opening interruption.")
                    Thread.currentThread().interrupt()
                    when (mode) {
                        "RAW_INTERRUPTED" -> resource.raw
                        "NULL_INTERRUPTED" -> null
                        else -> throw failure
                    }
                }
                assertTrue(Thread.currentThread().isInterrupted)
                result
            } finally {
                Thread.interrupted()
            }
        }
        assertEquals(if (mode == "FAILURE_INTERRUPTED") PersistencePhysicalOpening.FAILED else PersistencePhysicalOpening.INTERRUPTED, caller.join())
        assertEquals(1, calls.get())
        assertEquals(1, physicalSnapshot(registry).unknown)
        assertEquals(0, physicalSnapshot(registry).activeOpening)
        val claim = terminalPhysical(registry, record)
        if (mode == "RAW_INTERRUPTED") {
            assertPhysicalRaw(registry, claim, resource.raw)
        } else {
            assertSame(PersistencePhysicalRawDecision.NoRawReturned, registry.takeTerminalRaw(claim))
        }
        assertFalse(registry.releaseUnused(record))
        assertEquals(0, resource.calls.get())
        assertEquals(0, failure.reads.get())
    }

    @Test
    fun `interrupt after the flag check while queued on the actual lock still owns its returned raw`() = FactoryWorkerTestScope().use { scope ->
        val registry = PersistencePhysicalRegistry(1)
        val record = dispatchPhysical(registry)
        val resource = PhysicalTestConnection()
        val calls = AtomicInteger()
        val lock = physicalTestLock(registry)
        val caller = lock.withLock {
            val queued = scope.launch {
                try {
                    val result = registry.invokeOpening(record) {
                        calls.incrementAndGet()
                        resource.raw
                    }
                    assertTrue(Thread.currentThread().isInterrupted)
                    result
                } finally {
                    Thread.interrupted()
                }
            }
            awaitFactoryTestFact { lock.hasQueuedThread(queued.thread) }
            assertEquals(0, calls.get())
            queued.thread.interrupt()
            assertTrue(lock.hasQueuedThread(queued.thread))
            queued
        }
        assertEquals(PersistencePhysicalOpening.INTERRUPTED, caller.join())
        assertEquals(1, calls.get())
        assertEquals(1, physicalSnapshot(registry).unknown)
        assertEquals(0, physicalSnapshot(registry).activeOpening)
        assertPhysicalRaw(registry, terminalPhysical(registry, record), resource.raw)
        assertFalse(registry.releaseUnused(record))
        assertEquals(0, resource.calls.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["RETIRE", "SEAL"])
    fun `retirement before opening claim permanently prevents callback invocation`(mode: String) = FactoryWorkerTestScope().use {
        val registry = PersistencePhysicalRegistry(1)
        val record = dispatchPhysical(registry)
        fencePhysical(registry, record, mode)
        val claim = terminalPhysical(registry, record)
        assertSame(PersistencePhysicalRawDecision.NoRawReturned, registry.takeTerminalRaw(claim))
        val calls = AtomicInteger()
        repeat(2) {
            assertEquals(
                PersistencePhysicalOpening.REFUSED,
                registry.invokeOpening(record) {
                    calls.incrementAndGet()
                    null
                },
            )
        }
        assertEquals(0, calls.get())
        assertFalse(registry.releaseUnused(record))
        assertEquals(1, physicalSnapshot(registry).retiring)
        assertEquals(0, physicalSnapshot(registry).activeOpening)
        assertSame(PersistencePhysicalRawDecision.AlreadyTaken, registry.takeTerminalRaw(claim))
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["RETIRE", "SEAL"])
    fun `another actual thread completes retirement and terminal claims while callback stays active`(mode: String) = FactoryWorkerTestScope().use { scope ->
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
        val transition = scope.launch {
            fencePhysical(registry, record, mode)
            val claim = terminalPhysical(registry, record)
            assertSame(PersistencePhysicalRawDecision.WaitingForOpening, registry.takeTerminalRaw(claim))
            assertEquals(1, physicalSnapshot(registry).activeOpening)
            claim
        }
        // A different thread actually finishes these lock-taking methods before the callback is released.
        val claim = transition.join()
        assertTrue(caller.thread.isAlive)
        assertFalse(transition.thread.isAlive)
        assertFalse(registry.releaseUnused(record))
        assertPhysicalRefused(registry, if (mode == "SEAL") PersistencePhysicalRefusal.SEALED else PersistencePhysicalRefusal.FULL)
        returning.release()
        assertEquals(PersistencePhysicalOpening.RETAINED_FOR_RETIREMENT, caller.join())
        assertPhysicalRaw(registry, claim, resource.raw)
        assertEquals(1, physicalSnapshot(registry).occupied)
        assertEquals(0, physicalSnapshot(registry).activeOpening)
        assertEquals(0, resource.calls.get())
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["RETIRE", "SEAL"])
    fun `reentrant opening cannot invoke twice release its owner or bypass retirement`(mode: String) = FactoryWorkerTestScope().use {
        val registry = PersistencePhysicalRegistry(1)
        val record = dispatchPhysical(registry)
        val resource = PhysicalTestConnection()
        val duplicateCalls = AtomicInteger()
        val result = registry.invokeOpening(record) {
            assertFalse(physicalTestLock(registry).isHeldByCurrentThread)
            assertEquals(
                PersistencePhysicalOpening.REFUSED,
                registry.invokeOpening(record) {
                    duplicateCalls.incrementAndGet()
                    null
                },
            )
            assertFalse(registry.releaseUnused(record))
            fencePhysical(registry, record, mode)
            resource.raw
        }
        assertEquals(PersistencePhysicalOpening.RETAINED_FOR_RETIREMENT, result)
        assertEquals(0, duplicateCalls.get())
        assertPhysicalRaw(registry, terminalPhysical(registry, record), resource.raw)
        assertEquals(0, resource.calls.get())
    }
}
