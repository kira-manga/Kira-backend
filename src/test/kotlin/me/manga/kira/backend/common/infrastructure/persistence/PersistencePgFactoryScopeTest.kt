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
import java.net.SocketException
import kotlin.concurrent.withLock

internal class PersistencePgFactoryScopeTest {
    @Test
    fun `REAL_STACK direct configured bridge construction cannot grant its own scope`() {
        assertThrows(SocketException::class.java) { TrackedPgSocketFactory() }
        assertNull(pgCurrentScope().get())
    }

    @Test
    fun `MODEL scope entry and removal refuse a foreign caller without changing prepared state`() = PgScopeTestSupport().use { fixture ->
        assertThrows(SocketException::class.java) { fixture.scope.enter() }
        assertFalse(fixture.scope.leave())
        assertEquals(PersistencePgScopePhase.PREPARED, pgScopePhase(fixture.scope).get())
    }

    @Test
    fun `REAL_THREAD one-use scope preserves its original active installation on repeated entry`() = PgScopeTestSupport().use { fixture ->
        fixture.start {
            fixture.scope.enter()
            try {
                assertSame(fixture.scope, pgCurrentScope().get())
                assertThrows(SocketException::class.java) { fixture.scope.enter() }
                assertEquals(PersistencePgScopePhase.ACTIVE, pgScopePhase(fixture.scope).get())
                assertThrows(SocketException::class.java) { TrackedPgSocketFactory() }
            } finally {
                assertTrue(fixture.scope.leave())
            }
            assertTrue(fixture.scope.leave(), "Reobserving a completed removal is not a second scope installation.")
            assertThrows(SocketException::class.java) { fixture.scope.enter() }
            assertNull(pgCurrentScope().get())
        }
        fixture.await()
    }

    @Test
    fun `REAL_THREAD rejected nested scope cannot remove the outer installation`() = PgScopeTestSupport().use { fixture ->
        val nested = fixture.newScope()
        fixture.start {
            fixture.scope.enter()
            try {
                assertThrows(SocketException::class.java) { nested.enter() }
                assertEquals(PersistencePgScopePhase.REJECTED, pgScopePhase(nested).get())
                assertTrue(nested.leave())
                assertSame(fixture.scope, pgCurrentScope().get())
                assertEquals(PersistencePgScopePhase.ACTIVE, pgScopePhase(fixture.scope).get())
            } finally {
                assertTrue(fixture.scope.leave())
            }
        }
        fixture.await()
    }

    @Test
    fun `REAL_THREAD foreign removal does not clear another worker active scope`() = PgScopeTestSupport().use { fixture ->
        val held = fixture.calls.gate()
        fixture.start {
            fixture.scope.enter()
            try {
                held.hold()
                assertSame(fixture.scope, pgCurrentScope().get())
            } finally {
                assertTrue(fixture.scope.leave())
            }
        }
        held.awaitEntered()
        assertFalse(fixture.scope.leave())
        assertEquals(PersistencePgScopePhase.ACTIVE, pgScopePhase(fixture.scope).get())
        held.release()
        fixture.await()
    }

    @Test
    fun `REAL_THREAD capture ThreadLocal is not inherited by a normal inheriting child`() = PgScopeTestSupport().use { fixture ->
        fixture.start {
            fixture.scope.enter()
            try {
                fixture.runInheritingChild {
                    assertNull(pgCurrentScope().get())
                    assertThrows(SocketException::class.java) { TrackedPgSocketFactory() }
                }
            } finally {
                assertTrue(fixture.scope.leave())
            }
        }
        fixture.await()
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["F", "G"])
    fun `REAL_THREAD held ownership lock refuses scope operations without an installation`(lock: String) = PgScopeTestSupport().use { fixture ->
        fixture.start {
            val owned = if (lock == "F") fixture.binding.rendezvous.lock else fixture.binding.ledger.lock
            owned.withLock {
                assertThrows(SocketException::class.java) { fixture.scope.enter() }
                assertFalse(fixture.scope.leave())
                assertEquals(PersistencePgScopePhase.PREPARED, pgScopePhase(fixture.scope).get())
            }
            assertTrue(fixture.scope.leave(), "Positively never-entered scope has no outstanding capture cleanup.")
        }
        fixture.await()
    }

    @Test
    fun `MODEL failed active removal stays failed on a later call rather than becoming rejected success`() = PgScopeTestSupport().use { fixture ->
        fixture.start {
            fixture.scope.enter()
            // Synthetic own-project state cut: simulate loss of this ThreadLocal installation, not a JDK remove failure.
            pgCurrentScope().remove()
            assertFalse(fixture.scope.leave())
            assertEquals(PersistencePgScopePhase.REMOVAL_FAILED, pgScopePhase(fixture.scope).get())
            assertFalse(fixture.scope.leave())
            assertEquals(PersistencePgScopePhase.REMOVAL_FAILED, pgScopePhase(fixture.scope).get())
            assertThrows(SocketException::class.java) { fixture.scope.enter() }
            assertNull(pgCurrentScope().get())
        }
        fixture.await()
    }
}
