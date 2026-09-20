package me.manga.kira.backend.common.infrastructure.persistence

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.SQLException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** Genuine cold shells/root shutdown plus explicitly negative caller/profile cases. No JDBC or TLS operation. */
@EnabledOnOs(OS.LINUX, OS.MAC)
class VersionBoundPersistencePoolCustodyTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `sealed driver root and local role closes cannot release trust until every owned pool close actually ends`() = preparedFixture().use { fixture ->
        val pools = fixture.bind()
        val file = trustPath(fixture)
        fixture.owner.requestShutdown()
        assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, fixture.root.shutdownObservation())
        assertEquals(PersistencePublicTrustRelease.RETAINED, fixture.owner.releasePublicTrustAfterShutdown())
        pools.ordinary.requestShutdown()
        pools.deletion.close()
        pools.catalogCoordinator.close()
        assertEquals(PoolActorObservation.ENDED, lifecycle(pools.deletion).localShutdownForTrust())
        assertEquals(PoolActorObservation.ENDED, lifecycle(pools.catalogCoordinator.dataSource).localShutdownForTrust())
        assertEquals(PoolActorObservation.PENDING, lifecycle(pools.ordinary).localShutdownForTrust())
        assertEquals(PersistencePublicTrustRelease.RETAINED, fixture.owner.releasePublicTrustAfterShutdown())
        assertTrue(Files.exists(file), "Observers and another role's local drain never remove material.")
        pools.ordinary.close()
        assertEquals(PoolActorObservation.ENDED, lifecycle(pools.ordinary).localShutdownForTrust())
        assertEquals(PersistencePublicTrustRelease.RELEASED, fixture.owner.releasePublicTrustAfterShutdown())
        assertFalse(Files.exists(file.parent))
    }

    @Test
    fun `a directly closed raw Hikari shell is not an owned close or actor population certificate`() = preparedFixture().use { fixture ->
        val pools = fixture.bind()
        val ordinary = lifecycle(pools.ordinary)
        actualPool(pools.ordinary).close() // Test-only bypass through our own field; never a product cleanup path.
        assertTrue(actualPool(pools.ordinary).isClosed)
        assertEquals(PersistenceTerminalCall.NOT_INVOKED, ordinary.firstCloseOutcome())
        fixture.owner.requestShutdown()
        pools.deletion.close()
        pools.catalogCoordinator.close()
        assertEquals(PoolActorObservation.PENDING, ordinary.localShutdownForTrust())
        assertEquals(PersistencePublicTrustRelease.RETAINED, fixture.owner.releasePublicTrustAfterShutdown())
        assertTrue(Files.exists(trustPath(fixture)))
        pools.ordinary.close() // Genuine owner frame/tail/closed-population checks, even for this already-cold shell.
        assertEquals(PoolActorObservation.ENDED, ordinary.localShutdownForTrust())
        assertEquals(PersistencePublicTrustRelease.RELEASED, fixture.owner.releasePublicTrustAfterShutdown())
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    fun `failed catalog installation retains its undisclosed shell before the enclosing resource tuple can return`() =
        VersionBoundPoolTestFixture(retained = true).use { fixture ->
            val custody = checkNotNull(fixture.owner.versionBoundPools)
            val binding = poolTestField<VersionBoundPersistencePoolBinding>(custody, "catalogBinding")
            val gate = poolTestField<Any>(binding, "custody")
            OwnedCallerTestScope().use { scope ->
                withPoolSystemProperty("com.zaxxer.hikari.blockUntilFilled", "false") {
                    val call = synchronized(gate) {
                        val constructor = scope.launch { fixture.bind() }
                        awaitBlockedConstructor(custody, constructor.thread)
                        System.setProperty("com.zaxxer.hikari.blockUntilFilled", "true")
                        constructor
                    }
                    assertTrue(call.problem() is SQLException)
                }
            }
            val pool = poolTestField<HikariDataSource>(binding, "pool")
            val retainedLifecycle = poolTestField<PoolLifecycle>(binding, "lifecycle")
            assertFalse(pool.isRunning)
            assertFalse(fixture.owner.ownsCatalogLifecycle(retainedLifecycle), "The enclosing catalog tuple was never published.")
            assertEquals(PoolActorFault.UNSUPPORTED_PROFILE, retainedLifecycle.actorSnapshot().firstFailure)
            assertThrows(PersistenceBoundaryException::class.java) { custody.descriptors() }
            assertThrows(PersistenceBoundaryException::class.java) { fixture.bind() }
            custody.close() // Partial lifecycle is reachable through its original owner despite no returned composition.
            assertTrue(pool.isClosed)
            assertEquals(PersistenceTerminalCall.RETURNED, retainedLifecycle.firstCloseOutcome())
            assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, fixture.root.shutdownObservation())
            assertEquals(PersistencePublicTrustRelease.RETAINED, fixture.owner.releasePublicTrustAfterShutdown())
        }

    @Test
    fun `a genuine in progress cold constructor remains outstanding across permanent root shutdown`() =
        VersionBoundPoolTestFixture(retained = true).use { fixture ->
            val custody = checkNotNull(fixture.owner.versionBoundPools)
            val binding = poolTestField<VersionBoundPersistencePoolBinding>(custody, "ordinaryBinding")
            val bindingGate = poolTestField<Any>(binding, "custody")
            OwnedCallerTestScope().use { scope ->
                val call = synchronized(bindingGate) {
                    val constructor = scope.launch { fixture.bind() }
                    awaitBlockedConstructor(custody, constructor.thread)
                    fixture.owner.requestShutdown()
                    assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, fixture.root.shutdownObservation())
                    assertEquals(PersistencePublicTrustRelease.RETAINED, fixture.owner.releasePublicTrustAfterShutdown())
                    assertNull(poolTestField<HikariDataSource?>(binding, "pool"))
                    constructor
                }
                assertTrue(call.problem() is PersistenceBoundaryException, "The sealed root must refuse the not-yet-entered shell constructor.")
            }
            assertThrows(PersistenceBoundaryException::class.java) { fixture.bind() }
            assertEquals(PersistencePublicTrustRelease.RETAINED, fixture.owner.releasePublicTrustAfterShutdown())
        }

    @Test
    fun `an authentic pool caller extent cannot be bypassed by a native root drain or a close request`() =
        VersionBoundPoolTestFixture(retained = true).use { fixture ->
            val pools = fixture.bind()
            val ordinary = lifecycle(pools.ordinary)
            val caller = ordinary.prepareAcquisition(PersistenceTimeBudget.start(30_000))
            assertTrue(caller.enter())
            try {
                fixture.owner.requestShutdown()
                assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, fixture.root.shutdownObservation())
                assertEquals(PoolShutdownInvocation.ACTIVE_ACQUISITION, pools.ordinary.shutdownInvocation())
                assertEquals(PoolActorObservation.PENDING, ordinary.localShutdownForTrust())
                assertEquals(PersistencePublicTrustRelease.RETAINED, fixture.owner.releasePublicTrustAfterShutdown())
            } finally {
                assertTrue(caller.end())
            }
            // The real frame ended but this MODEL acquisition did not initialize Hikari. It is a sticky negative, never native success.
            assertEquals(PoolActorFault.INITIALIZATION_FAILED, ordinary.actorSnapshot().firstFailure)
            pools.close()
            assertEquals(PoolActorObservation.UNKNOWN, ordinary.localShutdownForTrust())
            assertEquals(PersistencePublicTrustRelease.RETAINED, fixture.owner.releasePublicTrustAfterShutdown())
        }

    private fun preparedFixture(): VersionBoundPoolTestFixture {
        val parent = Files.createDirectory(temporary.resolve("private"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val fixture = VersionBoundPoolTestFixture(parent)
        return runCatching {
            assertEquals(PersistencePublicTrustPreparation.READY, fixture.owner.preparePublicTrust())
            fixture
        }.onFailure { fixture.close() }.getOrThrow()
    }

    private fun awaitBlockedConstructor(custody: VersionBoundPersistencePools, thread: Thread) {
        val compositionGate = poolTestField<Any>(custody, "custody")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (true) {
            val entered = synchronized(compositionGate) { poolTestField<Thread?>(custody, "constructor") === thread }
            if (entered && thread.state === Thread.State.BLOCKED) return
            assertTrue(System.nanoTime() < deadline, "The actual constructor did not reach its retained role claim.")
            LockSupport.parkNanos(1_000_000)
        }
    }

    private fun trustPath(fixture: VersionBoundPoolTestFixture): Path = Path.of(fixture.root.endpoint.driverProperties().getProperty("sslrootcert"))

    private fun lifecycle(source: GuardedDataSource): PoolLifecycle = poolTestField(source, "lifecycle")
}
