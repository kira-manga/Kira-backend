package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** One real native physical-close hold; no substituted raw connection, driver body, or completion receipt. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PersistencePgNativePhysicalCloseIT {
    private val database = lazy { PgLifecycleDatabaseFixture(PersistencePgNativePhysicalCloseIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `real native physical close retains its entry after session disappearance and child drain`() = withOwnedCutConnection(database.value) { fixture ->
        database.value.observer().use { observer ->
            val application = checkNotNull(fixture.connection.getClientInfo("ApplicationName"))
            val pid = fixture.scalar("SELECT pg_backend_pid()")
            val session = observer.requireNew(emptySet(), observer.sample(application))
            assertEquals(pid, session.pid)
            val childLife = hiddenChild(fixture)
            val lock = ownedCutField(fixture.raw, "lock") as ReentrantLock
            val action = checkNotNull(ownedCutField(fixture.raw, "finalizeAction"))
            assertSame(lock, ownedCutField(action, "lock"))
            assertEquals("org.postgresql.jdbc.PgConnection", fixture.raw.javaClass.name)
            val deadline = PgLifecycleDatabaseDeadline(2_000)
            assertTrue(lock.tryLock(), "The idle native connection lock must be available before this test holds it.")
            try {
                fixture.connection.close()
                val terminal = awaitNativeClose(fixture, lock, deadline)
                observer.awaitAbsent(application, session, deadline) { requireHeldClose(fixture, lock, terminal, childLife) }
                fixture.connection.close() // A duplicate facade request cannot end the first actual native close.
                requireHeldClose(fixture, lock, terminal, childLife)
                deadline.checkRemaining()
            } finally {
                lock.unlock() // Always release the actual native lock before fixture retirement can wait.
            }
            fixture.retire()
            assertEquals(PersistencePgOwnedCutAccess.FIRST_RETURNED, ownedCutField(childLife, "first"))
            assertEquals(PersistenceTerminalCall.RETURNED, fixture.work.closeState())
            assertTrue(fixture.work.bodyExited() && fixture.work.producerDrainProven())
            assertEquals(PersistenceTerminalDisposition.TRACKED_DISPOSED, fixture.work.disposition())
            assertTrue(fixture.entry.driverCut.canReclaim() && fixture.scope.entries().isEmpty())
            assertSame(fixture.raw, fixture.entry.raw.get())
        }
    }

    private fun hiddenChild(fixture: OwnedCutConnection): Any {
        fixture.connection.metaData.typeInfo.use { result -> assertTrue(result.next()) }
        val cache = checkNotNull(ownedCutField(fixture.raw, "typeCache"))
        val statement = checkNotNull(ownedCutField(cache, "getAllTypeInfoStatement"))
        val native = checkNotNull(ownedCutField(statement, "kira"))
        val life = checkNotNull(ownedCutField(native, "life"))
        assertEquals(true, ownedCutField(native, "factoryReturned"))
        assertEquals(PersistencePgOwnedCutAccess.FIRST_NEVER, ownedCutField(life, "first"))
        assertTrue(fixture.root.access.liveNativeChildren(fixture.root) > 0L)
        return life
    }

    private fun awaitNativeClose(fixture: OwnedCutConnection, lock: ReentrantLock, deadline: PgLifecycleDatabaseDeadline): Thread {
        while (fixture.work.closeState() !== PersistenceTerminalCall.RUNNING) deadline.pause()
        val retained = checkNotNull((ownedCutField(fixture.work, "runner") as AtomicReference<*>).get()) as PersistenceRetainedPlatformThread
        while (!lock.hasQueuedThread(retained.thread)) deadline.pause()
        deadline.checkRemaining()
        return retained.thread
    }

    private fun requireHeldClose(fixture: OwnedCutConnection, lock: ReentrantLock, terminal: Thread, childLife: Any) {
        assertTrue(lock.isHeldByCurrentThread && lock.hasQueuedThread(terminal))
        val frames = terminal.stackTrace
        assertTrue(frames.any { it.className == "org.postgresql.jdbc.PgConnection" && it.methodName == "close" })
        assertTrue(frames.any { it.className == "org.postgresql.jdbc.PgConnectionCleaningAction" && it.methodName == "onClean" })
        assertTrue(frames.any { it.className == "org.postgresql.jdbc.PgConnectionCleaningAction" && it.methodName == "releaseTimer" })
        assertSame(fixture.raw, fixture.entry.raw.get())
        assertEquals(PersistencePgOwnedCutAccess.FIRST_RETURNED, ownedCutField(childLife, "first"))
        assertEquals(0L, fixture.entry.driverCut.fixedNativeChildren())
        assertTrue(fixture.entry.jdbc.postOpeningCallsEnded() && fixture.work.producerDrainProven())
        assertEquals(PersistenceTerminalCall.RETURNED, (ownedCutField(fixture.work, "abort") as AtomicReference<*>).get())
        assertEquals(PersistenceTerminalCall.RUNNING, fixture.work.closeState())
        assertEquals(PersistenceTerminalDisposition.PENDING, fixture.work.disposition())
        assertFalse(fixture.work.bodyExited())
        assertSame(fixture.entry, fixture.scope.entries().single())
        assertFalse(fixture.scope.binding().completion.scanReclamation(fixture.entry.record.slotHint))
        assertSame(fixture.entry, fixture.scope.entries().single())
    }
}
