package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class PgLifecycleNegotiationIdentityTest {
    @Test
    fun `MODEL detached original primary rejects a change to only its retained raw cell`() {
        val owner = PersistenceTransportOwner<AutoCloseable>()
        val extent = PersistenceTransportExtent(PersistenceTransportExtentSource(), PersistenceTransportRole.PRIMARY)
        val entry = PersistenceTransportEntry(owner, PersistenceTransportRecord(PersistenceTransportRole.PRIMARY), extent)
        val original = object : AutoCloseable {
            override fun close() = Unit
        }
        val replacement = object : AutoCloseable {
            override fun close() = Unit
        }
        assertNotSame(original, replacement)
        entry.raw.set(original)
        val witness = PgPrimaryIdentityWitness()
        witness.capture(entry)
        witness.requireUnchanged(entry)
        entry.raw.set(replacement) // Detached MODEL only; no native socket, live ledger, driver or JDBC object is modified.
        assertSame(extent, entry.extent)
        assertSame(replacement, entry.raw.get())
        assertThrows(IllegalStateException::class.java) { witness.requireUnchanged(entry) }
        entry.raw.set(original)
        witness.requireUnchanged(entry)
    }
}
