package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.withLock

class PersistencePhysicalRegistryTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(ints = [-1, 0])
    fun `nonpositive capacity rejects with a fixed value free error`(capacity: Int) = FactoryWorkerTestScope().use {
        val failure = assertThrows(IllegalArgumentException::class.java) { PersistencePhysicalRegistry(capacity) }
        assertEquals("Physical capacity must be positive.", failure.message)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(ints = [1, 4, 17])
    fun `cold fixed capacity admits exactly its slots without starting operations`(capacity: Int) = FactoryWorkerTestScope().use {
        val registry = PersistencePhysicalRegistry(capacity)
        assertEquals(0, physicalSnapshot(registry).occupied)
        repeat(capacity) { expectedSlot ->
            assertEquals(expectedSlot, reservePhysical(registry).slotHint)
        }
        assertPhysicalRefused(registry, PersistencePhysicalRefusal.FULL)
        val snapshot = physicalSnapshot(registry)
        assertEquals(capacity, snapshot.occupied)
        assertEquals(capacity, snapshot.opening)
        assertEquals(0, snapshot.activeOpening)
        assertEquals(0, snapshot.terminalClaimed)
        assertFalse(snapshot.sealed)
    }

    @Test
    fun `all four lifecycles remain charged and unknown survives sealing and terminal decisions`() = FactoryWorkerTestScope().use {
        val registry = PersistencePhysicalRegistry(4)
        val records = List(4) { dispatchPhysical(registry) }
        val resource = PhysicalTestConnection()
        assertEquals(PersistencePhysicalOpening.RETAINED, registry.invokeOpening(records[1]) { resource.raw })
        assertTrue(registry.requestRetirement(records[2]))
        assertEquals(PersistencePhysicalOpening.FAILED, registry.invokeOpening(records[3]) { error("Synthetic opening failure.") })
        val before = physicalSnapshot(registry)
        assertEquals(4, before.occupied)
        assertEquals(1, before.opening)
        assertEquals(1, before.live)
        assertEquals(1, before.retiring)
        assertEquals(1, before.unknown)
        assertTrue(registry.seal())
        for (record in records) {
            val claim = terminalPhysical(registry, record)
            if (record === records[1]) {
                assertPhysicalRaw(registry, claim, resource.raw)
            } else {
                assertSame(PersistencePhysicalRawDecision.NoRawReturned, registry.takeTerminalRaw(claim))
            }
            assertFalse(registry.releaseUnused(record))
        }
        val after = physicalSnapshot(registry)
        assertEquals(4, after.occupied)
        assertEquals(3, after.retiring)
        assertEquals(1, after.unknown)
        assertEquals(0, after.activeOpening)
        assertEquals(4, after.terminalClaimed)
        assertPhysicalRefused(registry, PersistencePhysicalRefusal.SEALED)
        assertEquals(0, resource.calls.get())
    }

    @Test
    fun `unused release is one shot and a stale record cannot affect its replacement`() = FactoryWorkerTestScope().use {
        val registry = PersistencePhysicalRegistry(1)
        val old = reservePhysical(registry)
        assertTrue(registry.releaseUnused(old))
        assertFalse(registry.releaseUnused(old))
        val current = reservePhysical(registry)
        assertEquals(old.slotHint, current.slotHint)
        assertNotSame(old, current)
        val calls = AtomicInteger()
        assertFalse(registry.claimDispatch(old))
        assertFalse(registry.requestRetirement(old))
        assertSame(PersistencePhysicalTerminal.Refused, registry.claimTerminal(old))
        assertEquals(
            PersistencePhysicalOpening.REFUSED,
            registry.invokeOpening(old) {
                calls.incrementAndGet()
                null
            },
        )
        assertFalse(registry.releaseUnused(old))
        assertTrue(registry.claimDispatch(current))
        assertEquals(0, calls.get())
        assertEquals(1, physicalSnapshot(registry).occupied)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(ints = [-2147483648, -1, 0, 1, 2147483647])
    fun `forged and foreign record hints never confer membership`(hint: Int) = FactoryWorkerTestScope().use {
        val registry = PersistencePhysicalRegistry(1)
        val real = reservePhysical(registry)
        val other = PersistencePhysicalRegistry(1)
        val foreign = reservePhysical(other)
        val forged = PersistencePhysicalRecord(hint)
        val calls = AtomicInteger()
        for (record in listOf(foreign, forged)) {
            assertFalse(registry.claimDispatch(record))
            assertFalse(registry.releaseUnused(record))
            assertFalse(registry.requestRetirement(record))
            assertSame(PersistencePhysicalTerminal.Refused, registry.claimTerminal(record))
            assertEquals(
                PersistencePhysicalOpening.REFUSED,
                registry.invokeOpening(record) {
                    calls.incrementAndGet()
                    null
                },
            )
            assertSame(PersistencePhysicalRawDecision.Refused, registry.takeTerminalRaw(PersistencePhysicalTerminalClaim(record)))
        }
        assertEquals(0, calls.get())
        assertTrue(registry.claimDispatch(real))
        assertTrue(other.releaseUnused(foreign))
        assertEquals(1, physicalSnapshot(registry).occupied)
    }

    @Test
    fun `actual other thread lock contention refuses admission and snapshot without queueing`() = FactoryWorkerTestScope().use { scope ->
        val registry = PersistencePhysicalRegistry(1)
        val lock = physicalTestLock(registry)
        lock.withLock {
            val contender = scope.launch {
                assertSame(PersistencePhysicalSnapshot.Unavailable, registry.snapshot())
                assertPhysicalRefused(registry, PersistencePhysicalRefusal.CONTENDED)
                true
            }
            assertTrue(contender.join())
            assertFalse(contender.thread.isAlive)
            assertFalse(lock.hasQueuedThread(contender.thread))
            assertFalse(lock.hasQueuedThreads())
        }
        assertEquals(0, physicalSnapshot(registry).occupied)
        assertTrue(registry.releaseUnused(reservePhysical(registry)))
        assertEquals(0, physicalSnapshot(registry).occupied)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["DISPATCH", "RELEASE"])
    fun `dispatch versus unused release has one winner in both controlled orders`(first: String) = FactoryWorkerTestScope().use { scope ->
        val registry = PersistencePhysicalRegistry(1)
        val record = reservePhysical(registry)
        val later = scope.gate()
        val loser = scope.launch {
            later.hold()
            if (first == "DISPATCH") registry.releaseUnused(record) else registry.claimDispatch(record)
        }
        later.awaitEntered()
        val winner = scope.launch {
            if (first == "DISPATCH") registry.claimDispatch(record) else registry.releaseUnused(record)
        }
        assertTrue(winner.join())
        later.release()
        assertFalse(loser.join())
        val calls = AtomicInteger()
        val result = registry.invokeOpening(record) {
            calls.incrementAndGet()
            null
        }
        assertEquals(if (first == "DISPATCH") PersistencePhysicalOpening.NO_RAW_RETURN else PersistencePhysicalOpening.REFUSED, result)
        assertEquals(if (first == "DISPATCH") 1 else 0, calls.get())
        assertEquals(if (first == "DISPATCH") 1 else 0, physicalSnapshot(registry).occupied)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["RESERVE", "SEAL"])
    fun `seal versus reservation preserves only an earlier accepted identity`(first: String) = FactoryWorkerTestScope().use { scope ->
        val registry = PersistencePhysicalRegistry(1)
        val later = scope.gate()
        val second = scope.launch {
            later.hold()
            if (first == "RESERVE") registry.seal() else registry.tryReserve()
        }
        later.awaitEntered()
        val initial = scope.launch { if (first == "RESERVE") registry.tryReserve() else registry.seal() }
        val result = initial.join()
        later.release()
        val following = second.join()
        if (first == "RESERVE") {
            val record = (result as PersistencePhysicalReservation.Accepted).record
            assertEquals(true, following)
            assertFalse(registry.claimDispatch(record))
            assertEquals(1, physicalSnapshot(registry).retiring)
            assertTrue(registry.releaseUnused(record))
        } else {
            assertEquals(true, result)
            assertEquals(PersistencePhysicalRefusal.SEALED, (following as PersistencePhysicalReservation.Refused).reason)
        }
        assertFalse(registry.seal())
        assertPhysicalRefused(registry, PersistencePhysicalRefusal.SEALED)
        assertEquals(0, physicalSnapshot(registry).occupied)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["DISPATCH", "SEAL"])
    fun `seal versus dispatch never reopens a generation or invokes a fenced callback`(first: String) = FactoryWorkerTestScope().use { scope ->
        val registry = PersistencePhysicalRegistry(1)
        val record = reservePhysical(registry)
        val later = scope.gate()
        val second = scope.launch {
            later.hold()
            if (first == "DISPATCH") registry.seal() else registry.claimDispatch(record)
        }
        later.awaitEntered()
        assertTrue(scope.launch { if (first == "DISPATCH") registry.claimDispatch(record) else registry.seal() }.join())
        later.release()
        assertEquals(first == "DISPATCH", second.join())
        val calls = AtomicInteger()
        assertEquals(
            PersistencePhysicalOpening.REFUSED,
            registry.invokeOpening(record) {
                calls.incrementAndGet()
                null
            },
        )
        assertEquals(0, calls.get())
        assertFalse(registry.claimDispatch(record))
        assertFalse(registry.seal())
        assertPhysicalRefused(registry, PersistencePhysicalRefusal.SEALED)
        assertEquals(first != "DISPATCH", registry.releaseUnused(record))
    }

    @Test
    fun `unused slot rotation keeps one fixed array and no retained history`() = FactoryWorkerTestScope().use {
        val registry = PersistencePhysicalRegistry(1)
        val slots = physicalTestSlots(registry)
        var previous: PersistencePhysicalRecord? = null
        repeat(1_000) {
            val record = reservePhysical(registry)
            assertNotSame(previous, record)
            previous?.let { stale -> assertFalse(registry.releaseUnused(stale)) }
            assertTrue(registry.releaseUnused(record))
            assertSame(slots, physicalTestSlots(registry))
            assertEquals(1, slots.size)
            assertTrue(slots.all { entry -> entry == null })
            previous = record
        }
        val fields = PersistencePhysicalRegistry::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(1, fields.count { it.type.isArray })
        assertFalse(fields.any { Collection::class.java.isAssignableFrom(it.type) || Map::class.java.isAssignableFrom(it.type) })
        assertEquals(0, physicalSnapshot(registry).occupied)
    }

    @Test
    fun `unused terminal claimed identity cannot escape through unused release`() = FactoryWorkerTestScope().use {
        val registry = PersistencePhysicalRegistry(1)
        val record = reservePhysical(registry)
        assertSame(PersistencePhysicalTerminal.Refused, registry.claimTerminal(record))
        assertTrue(registry.requestRetirement(record))
        assertTrue(registry.requestRetirement(record))
        val claim = terminalPhysical(registry, record)
        assertSame(PersistencePhysicalRawDecision.NoRawReturned, registry.takeTerminalRaw(claim))
        assertFalse(registry.releaseUnused(record))
        assertFalse(registry.claimDispatch(record))
        assertPhysicalRefused(registry, PersistencePhysicalRefusal.FULL)
        assertEquals(1, physicalSnapshot(registry).terminalClaimed)
    }

    @Test
    fun `core API exposes neither reclamation nor cleanup nor a live raw escape`() = FactoryWorkerTestScope().use {
        val registry = PersistencePhysicalRegistry(1)
        val methods = PersistencePhysicalRegistry::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name }.toSet()
        assertEquals(
            setOf(
                "tryReserve",
                "claimDispatch",
                "releaseUnused",
                "invokeOpening",
                "requestRetirement",
                "claimTerminal",
                "takeTerminalRaw",
                "seal",
                "snapshot",
                "toString",
            ),
            methods,
        )
        assertFalse(AutoCloseable::class.java.isInstance(registry))
        assertFalse(javax.sql.DataSource::class.java.isInstance(registry))
        assertFalse(PersistencePhysicalRecord::class.java.declaredFields.any { it.type == java.sql.Connection::class.java })
        assertEquals("PersistencePhysicalRegistry(redacted)", registry.toString())
        assertEquals("PersistencePhysicalRecord(redacted)", reservePhysical(registry).toString())
        assertEquals("PersistencePhysicalSnapshot.Available", physicalSnapshot(registry).toString())
    }
}
