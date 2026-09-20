package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Every method, including Object methods, is observable and forbidden at this raw identity seam. */
internal class PhysicalTestConnection {
    val calls = AtomicInteger()
    val raw: Connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, _, _ ->
        calls.incrementAndGet()
        error("Synthetic raw connection method invoked.")
    } as Connection
}

internal class PhysicalTestFailures {
    val runtime = PhysicalHostileFailure()
    val direct = PhysicalDirectFailure()
    val fatal = PhysicalHostileError()

    fun produce(mode: String): Connection? = when (mode) {
        "NULL" -> null
        "RUNTIME" -> throw runtime
        "DIRECT" -> throw direct
        "ERROR" -> throw fatal
        else -> error("Unknown synthetic physical opening vector.")
    }
}

internal class PhysicalHostileFailure : RuntimeException(null, null, false, false) {
    val reads = AtomicInteger()
    override val message: String
        get() {
            reads.incrementAndGet()
            return "synthetic-physical-failure"
        }
    override val cause: Throwable?
        get() {
            reads.incrementAndGet()
            return null
        }

    override fun toString(): String {
        reads.incrementAndGet()
        return "synthetic-physical-failure"
    }

    override fun equals(other: Any?): Boolean {
        reads.incrementAndGet()
        return this === other
    }

    override fun hashCode(): Int {
        reads.incrementAndGet()
        return 1
    }
}

internal class PhysicalDirectFailure : Throwable(null, null, false, false)

internal class PhysicalHostileError : Error(null, null, false, false) {
    val reads = AtomicInteger()
    override val message: String
        get() {
            reads.incrementAndGet()
            return "synthetic-physical-error"
        }
    override val cause: Throwable?
        get() {
            reads.incrementAndGet()
            return null
        }

    override fun toString(): String {
        reads.incrementAndGet()
        return "synthetic-physical-error"
    }
}

internal fun reservePhysical(registry: PersistencePhysicalRegistry): PersistencePhysicalRecord =
    (registry.tryReserve() as PersistencePhysicalReservation.Accepted).record

internal fun dispatchPhysical(registry: PersistencePhysicalRegistry): PersistencePhysicalRecord = reservePhysical(registry).also {
    assertTrue(registry.claimDispatch(it))
}

internal fun terminalPhysical(registry: PersistencePhysicalRegistry, record: PersistencePhysicalRecord): PersistencePhysicalTerminalClaim =
    (registry.claimTerminal(record) as PersistencePhysicalTerminal.Claimed).claim

internal fun physicalSnapshot(registry: PersistencePhysicalRegistry): PersistencePhysicalSnapshot.Available {
    var observed: PersistencePhysicalSnapshot.Available? = null
    awaitFactoryTestFact {
        observed = registry.snapshot() as? PersistencePhysicalSnapshot.Available
        observed != null
    }
    return requireNotNull(observed).also {
        assertEquals(it.occupied, it.opening + it.live + it.retiring + it.unknown)
        assertTrue(it.occupied <= it.capacity)
        assertTrue(it.activeOpening <= it.occupied)
        assertTrue(it.terminalClaimed <= it.occupied)
    }
}

internal fun assertPhysicalRefused(registry: PersistencePhysicalRegistry, reason: PersistencePhysicalRefusal) {
    assertEquals(reason, (registry.tryReserve() as PersistencePhysicalReservation.Refused).reason)
}

internal fun assertPhysicalRaw(registry: PersistencePhysicalRegistry, claim: PersistencePhysicalTerminalClaim, raw: Connection) {
    val decision = registry.takeTerminalRaw(claim) as PersistencePhysicalRawDecision.Granted
    assertSame(raw, decision.raw)
    assertEquals("PersistencePhysicalRawDecision.Granted(redacted)", decision.toString())
    assertSame(PersistencePhysicalRawDecision.AlreadyTaken, registry.takeTerminalRaw(claim))
    assertSame(raw, physicalRetainedRaw(registry, claim.record))
}

internal fun fencePhysical(registry: PersistencePhysicalRegistry, record: PersistencePhysicalRecord, mode: String) {
    when (mode) {
        "RETIRE" -> assertTrue(registry.requestRetirement(record))
        "SEAL" -> assertTrue(registry.seal())
        else -> error("Unknown synthetic physical fence vector.")
    }
}

/** Read only this project's actual lock, never replace it or inspect private JDK implementation. */
internal fun physicalTestLock(registry: PersistencePhysicalRegistry): ReentrantLock {
    val field = PersistencePhysicalRegistry::class.java.getDeclaredField("lock")
    check(field.trySetAccessible())
    return field.get(registry) as ReentrantLock
}

internal fun physicalTestSlots(registry: PersistencePhysicalRegistry): Array<*> {
    val field = PersistencePhysicalRegistry::class.java.getDeclaredField("entries")
    check(field.trySetAccessible())
    return field.get(registry) as Array<*>
}

/** Test-only observation of pre-settlement retention. Never a production LIVE resource getter. */
internal fun physicalRetainedRaw(registry: PersistencePhysicalRegistry, record: PersistencePhysicalRecord): Connection? {
    val entry = requireNotNull(physicalTestSlots(registry)[record.slotHint])
    val owner = entry.javaClass.getDeclaredField("record")
    check(owner.trySetAccessible())
    assertSame(record, owner.get(entry))
    val cell = entry.javaClass.getDeclaredField("raw")
    check(cell.trySetAccessible())
    return (cell.get(entry) as AtomicReference<*>).get() as Connection?
}
