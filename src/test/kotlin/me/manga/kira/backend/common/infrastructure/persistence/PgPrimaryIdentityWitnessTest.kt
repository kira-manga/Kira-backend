package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.concurrent.withLock

internal class PgPrimaryIdentityWitnessTest {
    @Test
    fun `MODEL same captured primary remains valid after its legitimate close`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val first = modelBoundTransport(scope, owner, PersistenceTransportExtentSource(), PersistenceTransportRole.PRIMARY)
        val witness = PgPrimaryIdentityWitness()
        withPgCurrentPrimary(owner, witness::capture)
        first.resource.close()
        assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, transportSnapshot(owner).primary?.firstClose)
        withPgCurrentPrimary(owner) { current ->
            assertSame(first.ticket.entry, current)
            witness.requireUnchanged(current)
        }
        assertEquals(1, first.resource.closes.get())
        assertEquals(0, first.resource.renders.get())
        assertEquals(0, first.resource.comparisons.get())
    }

    @Test
    fun `MODEL missing capture cannot verify an otherwise valid current primary`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        modelBoundTransport(scope, owner, PersistenceTransportExtentSource(), PersistenceTransportRole.PRIMARY)
        val witness = PgPrimaryIdentityWitness()
        withPgCurrentPrimary(owner) { current ->
            assertThrows(IllegalStateException::class.java) { witness.requireUnchanged(current) }
        }
    }

    @Test
    fun `MODEL duplicate capture is rejected instead of overwriting original evidence`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val first = modelBoundTransport(scope, owner, PersistenceTransportExtentSource(), PersistenceTransportRole.PRIMARY)
        val witness = PgPrimaryIdentityWitness()
        witness.capture(first.ticket.entry)
        assertThrows(IllegalStateException::class.java) { witness.capture(first.ticket.entry) }
        witness.requireUnchanged(first.ticket.entry)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["AUX", "NO_RAW", "NO_EXTENT", "WRONG_EXTENT_ROLE"])
    fun `MODEL invalid identity tuple cannot become primary evidence`(invalid: String) {
        val owner = PersistenceTransportOwner<AutoCloseable>()
        val role = if (invalid == "AUX") PersistenceTransportRole.AUX_CANCEL else PersistenceTransportRole.PRIMARY
        val extentRole = if (invalid == "WRONG_EXTENT_ROLE") PersistenceTransportRole.AUX_CANCEL else role
        val extent = if (invalid == "NO_EXTENT") null else PersistenceTransportExtent(PersistenceTransportExtentSource(), extentRole)
        val entry = PersistenceTransportEntry(owner, PersistenceTransportRecord(role), extent)
        if (invalid != "NO_RAW") entry.raw.set(AutoCloseable {})
        val witness = PgPrimaryIdentityWitness()
        assertThrows(IllegalStateException::class.java) { witness.capture(entry) }
        assertThrows(IllegalStateException::class.java) { witness.requireUnchanged(entry) }
    }

    @Test
    fun `MODEL witness saves the raw object rather than rereading its mutable cell`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val first = modelBoundTransport(scope, owner, PersistenceTransportExtentSource(), PersistenceTransportRole.PRIMARY)
        val witness = PgPrimaryIdentityWitness()
        witness.capture(first.ticket.entry)
        val otherRaw = TransportModelResource(owner, first.record)
        try {
            first.ticket.entry.raw.set(otherRaw)
            withPgCurrentPrimary(owner) { current ->
                assertSame(first.ticket.entry, current)
                assertThrows(IllegalStateException::class.java) { witness.requireUnchanged(current) }
            }
        } finally {
            // Restore the real MODEL resource before the scope asks its exact ledger owner to close it.
            first.ticket.entry.raw.set(first.resource)
        }
        withPgCurrentPrimary(owner, witness::requireUnchanged)
        assertEquals(0, first.resource.comparisons.get() + otherRaw.comparisons.get())
    }

    @Test
    fun `MODEL copied entry with identical record extent and raw is not original identity`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val first = modelBoundTransport(scope, owner, PersistenceTransportExtentSource(), PersistenceTransportRole.PRIMARY)
        val witness = PgPrimaryIdentityWitness()
        witness.capture(first.ticket.entry)
        val copy = PersistenceTransportEntry(owner, first.record, first.ticket.entry.extent)
        copy.raw.set(first.resource)
        assertNotSame(first.ticket.entry, copy)
        assertSame(first.record, copy.record)
        assertSame(first.ticket.entry.extent, copy.extent)
        assertSame(first.resource, copy.raw.get())
        assertThrows(IllegalStateException::class.java) { witness.requireUnchanged(copy) }
        withPgCurrentPrimary(owner, witness::requireUnchanged)
    }

    @Test
    fun `MODEL real replacement satisfies weak closed primary checks but fails original identity`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        val source = PersistenceTransportExtentSource()
        val first = modelBoundTransport(scope, owner, source, PersistenceTransportRole.PRIMARY)
        val witness = PgPrimaryIdentityWitness()
        withPgCurrentPrimary(owner, witness::capture)
        // This is the actual bound-ledger transition, not an assignment into the owner's private slot.
        val replacement = modelBoundTransport(scope, owner, source, PersistenceTransportRole.PRIMARY)
        replacement.resource.close()
        val state = transportSnapshot(owner)
        assertSame(replacement.record, requireNotNull(state.primary).record)
        assertEquals(PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED, state.primary.firstClose)
        assertNull(state.auxiliary)
        assertEquals(1, first.resource.closes.get())
        assertEquals(1, replacement.resource.closes.get())
        assertSame(first.record, replacement.ticket.predecessor)
        withPgCurrentPrimary(owner) { current ->
            assertSame(replacement.ticket.entry, current)
            assertNotSame(first.ticket.entry, current)
            // All setup and the old weak terminal conditions above must succeed outside this assertion.
            assertThrows(IllegalStateException::class.java) { witness.requireUnchanged(current) }
        }
    }

    @Test
    fun `MODEL current slot helper unlocks after callback failure and rejects preheld ownership`() = TransportTestScope("MODEL").use { scope ->
        val owner = PersistenceTransportOwner<TransportModelResource>()
        modelBoundTransport(scope, owner, PersistenceTransportExtentSource(), PersistenceTransportRole.PRIMARY)
        val lock = transportTestLock(owner)
        val sentinel = IllegalStateException("Synthetic witness callback failure.")
        val failure = assertThrows(IllegalStateException::class.java) {
            withPgCurrentPrimary(owner) {
                assertTrue(lock.isHeldByCurrentThread)
                throw sentinel
            }
        }
        assertSame(sentinel, failure)
        assertFalse(lock.isHeldByCurrentThread)
        var invoked = false
        lock.withLock {
            assertThrows(IllegalStateException::class.java) { withPgCurrentPrimary(owner) { invoked = true } }
            assertEquals(1, lock.holdCount)
        }
        assertFalse(invoked)
        assertFalse(lock.isLocked)
    }

    @Test
    fun `MODEL absent current primary fails without calling the observer or retaining the lock`() {
        val owner = PersistenceTransportOwner<AutoCloseable>()
        var invoked = false
        assertThrows(IllegalStateException::class.java) { withPgCurrentPrimary(owner) { invoked = true } }
        assertFalse(invoked)
        assertFalse(transportTestLock(owner).isLocked)
    }
}
