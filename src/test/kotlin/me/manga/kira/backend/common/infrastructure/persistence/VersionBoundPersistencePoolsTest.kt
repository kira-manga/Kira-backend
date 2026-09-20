package me.manga.kira.backend.common.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import java.nio.file.Path

/** Actual cold Hikari/configuration custody only. No driver construction, connection, pool start or TLS qualification. */
@EnabledOnOs(OS.LINUX, OS.MAC)
class VersionBoundPersistencePoolsTest {
    @Test
    fun `fixed composition binds actual Hikari settings and physical role material without starting anything`() = VersionBoundPoolTestFixture().use { fixture ->
        val pools = fixture.bind()
        val sources = listOf(pools.ordinary, pools.deletion, pools.catalogCoordinator.dataSource)
        val participants = listOf(fixture.root.ordinary, fixture.root.deletion, fixture.root.catalogCoordinator)
        val descriptors = pools.descriptors()
        assertEquals(PersistenceJdbcParticipantRole.entries, descriptors.map { it.role })
        assertEquals(listOf(2, 4, 1), descriptors.map { it.hikari.sizing.maximumPoolSize })
        assertEquals(listOf(0, 4, 1), descriptors.map { it.hikari.sizing.minimumIdle })
        assertEquals(listOf(2000L, 500L, 250L), descriptors.map { it.hikari.timing.connectionTimeoutMillis })
        assertEquals(listOf(2000L, 250L, 250L), descriptors.map { it.hikari.timing.validationTimeoutMillis })
        sources.indices.forEach { index ->
            val pool = actualPool(sources[index])
            val descriptor = descriptors[index]
            val binding = poolTestField<PersistencePhysicalFactoryBinding>(participants[index], "binding")
            val material = poolTestField<VersionBoundPersistencePoolMaterial>(participants[index], "versionBoundMaterial")
            assertSame(pools.material(descriptor.role), material)
            assertEquals(binding.ledger.entries.size, descriptor.hikari.sizing.maximumPoolSize)
            assertEquals(PersistenceHikariDescriptor.capture(pool), descriptor.hikari)
            assertEquals(-1L, pool.initializationFailTimeout)
            assertSame(poolTestField<PrivateJdbcDataSource>(sources[index], "lower"), pool.dataSource)
            assertTrue(pool.threadFactory != null)
            assertNull(pool.scheduledExecutor)
            assertFalse(pool.isRunning)
            assertFalse(sources[index].businessReady(), "UNKNOWN launch profile stays closed despite owned factory installation.")
            assertEquals(PersistenceLifecycleActivation.FAILED, sources[index].start())
        }
        assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, pools.deletion.prepareDeletion())
        assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, pools.catalogCoordinator.prepare())
        assertEquals(PersistenceLifecycleActivation.FAILED, fixture.owner.start(), "Public trust was not prepared.")
        assertFalse(fixture.owner.snapshot().ordinaryReady)
    }

    @Test
    fun `descriptor recipes use the exact retained effective inputs including strict roles and tracked socket selection`() =
        VersionBoundPoolTestFixture().use { fixture ->
            val pools = fixture.bind()
            pools.descriptors().forEach { descriptor ->
                assertSame(fixture.configuration.descriptor.authenticationPassword, descriptor.authenticationPassword)
                val material = pools.material(descriptor.role)
                descriptor.openings().forEach { recipe ->
                    val actual = material.opening(recipe.policy)
                    val properties = poolTestField<ResolvedPersistenceEndpoint>(actual, "endpoint").driverProperties()
                    if (recipe.policy.recipe === PersistenceDriverExecutionRecipe.TRACKED_STANDARD) {
                        properties.setProperty("socketFactory", TrackedPgSocketFactory::class.java.name)
                    }
                    val public = properties.stringPropertyNames().filterNot { it == "password" || it == "sslrootcert" }
                        .associateWith { properties.getProperty(it) }
                    assertEquals(public, recipe.publicDriverProperties())
                    assertEquals(actual.loginPolicy.durationMillis, recipe.loginBudgetMillis)
                    assertEquals(VersionBoundPersistenceTestInputs.PASSWORD, properties.getProperty("password"))
                    assertEquals("verify-full", public["sslmode"])
                    val factory = if (recipe.policy.recipe === PersistenceDriverExecutionRecipe.TRACKED_STANDARD) {
                        TrackedPgSocketFactory::class.java.name
                    } else {
                        null
                    }
                    assertEquals(factory, public["socketFactory"])
                    properties.setProperty("sslmode", "disable")
                    (recipe.publicDriverProperties() as MutableMap<String, String>).clear()
                    assertEquals(public, recipe.publicDriverProperties())
                    assertEquals(public, actual.publicDriverProperties())
                }
            }
            val deletion = pools.material(PersistenceJdbcParticipantRole.DELETION)
            rejected { deletion.opening(PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER) }
            rejected { deletion.opening(PersistenceDriverAttemptPolicy.TRACKED_CATALOG_CONJUNCTION) }
        }

    @Test
    fun `identical public configuration stays path independent and redacted across actual pool generations`() = VersionBoundPoolTestFixture().use { first ->
        VersionBoundPoolTestFixture().use { second ->
            val firstPools = first.bind()
            val secondPools = second.bind()
            val firstPath = first.root.endpoint.driverProperties().getProperty("sslrootcert")
            val secondPath = second.root.endpoint.driverProperties().getProperty("sslrootcert")
            assertNotEquals(firstPath, secondPath)
            firstPools.descriptors().zip(secondPools.descriptors()).forEach { (left, right) ->
                assertEquals(left.hikari, right.hikari)
                assertEquals(left.publicTrustSha256, right.publicTrustSha256)
                assertEquals(left.openings().map { it.publicDriverProperties() }, right.openings().map { it.publicDriverProperties() })
                left.openings().forEach { recipe ->
                    assertFalse(recipe.publicDriverProperties().containsKey("password"))
                    assertFalse(recipe.publicDriverProperties().containsKey("sslrootcert"))
                }
            }
            val diagnostics = listOf(first.configuration, first.owner, firstPools, firstPools.descriptors()).joinToString()
            assertFalse(diagnostics.contains(firstPath))
            assertFalse(diagnostics.contains(VersionBoundPersistenceTestInputs.PASSWORD))
        }
    }

    @Test
    fun `legacy endpoint capacity and settings paths cannot replace a version bound role or consume its sole composition`() =
        VersionBoundPoolTestFixture().use { fixture ->
            val foreign = pgProbeEndpoint(1)
            val exact = fixture.root.endpoint
            rejected { GuardedDataSource(fixture.owner, foreign, 2) }
            rejected { GuardedDataSource(fixture.owner, exact, 3) }
            rejected { GuardedDataSource(fixture.owner, exact, 2) }
            rejected { GuardedDataSource.deletion(fixture.owner, foreign) }
            rejected { GuardedDataSource.catalogCoordinator(fixture.owner, exact, PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY) }
            rejected { GuardedDataSource.sourceOnly(fixture.owner, exact, HikariConfig().apply { maximumPoolSize = 7 }) }
            HikariDataSource().use { pool ->
                rejected { PoolLifecycle(pool, fixture.owner) }
                rejected { PoolLifecycle.deletion(pool, fixture.owner) }
                rejected { PoolLifecycle.catalogCoordinator(pool, fixture.owner) }
                rejected { PoolLifecycle.sourceOnly(pool, fixture.owner) }
            }
            rejected { fixture.owner.bindCatalogCoordinator() }
            val pools = fixture.bind()
            rejected { fixture.owner.bindVersionBoundPools() }
            assertSame(pools.ordinary, pools.ordinary)
            assertEquals(listOf(2, 4, 1), pools.descriptors().map { it.hikari.sizing.maximumPoolSize })
        }

    @Test
    fun `independently supplied material and role bindings are not authentic pool construction inputs`() = VersionBoundPoolTestFixture().use { fixture ->
        VersionBoundPoolTestFixture().use { other ->
            val actual = checkNotNull(fixture.owner.versionBoundPools)
            val foreignMaterial = VersionBoundPersistencePoolMaterial.create(
                other.root.endpoint,
                PersistenceJdbcParticipantRole.DELETION,
                29,
                other.configuration.descriptor,
            )
            val counterfeit = VersionBoundPersistencePoolBinding.create(actual, foreignMaterial)
            rejected { counterfeit.construct(fixture.owner, PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY) }
            rejected { counterfeit.construct(other.owner, PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY) }
            fixture.bind()
            other.bind()
            rejected { counterfeit.construct(fixture.owner, PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY) }
            assertNull(poolTestField<HikariDataSource?>(counterfeit, "pool"), "No counterfeit shell was ever constructed.")
        }
    }

    @Test
    fun `actual capacity and lower source drift fail the bound descriptor and authentic pool admission`() {
        listOf<(HikariDataSource, GuardedDataSource) -> Unit>(
            { pool, _ -> pool.maximumPoolSize = 3 },
            { pool, replacement -> pool.dataSource = replacement },
        ).forEach { mutate ->
            VersionBoundPoolTestFixture(retained = true).use { fixture ->
                val pools = fixture.bind()
                mutate(actualPool(pools.ordinary), pools.deletion)
                rejected { pools.descriptors() }
                val lifecycle = poolTestField<PoolLifecycle>(pools.ordinary, "lifecycle")
                assertFalse(lifecycle.businessReady())
                assertEquals(PoolActorFault.UNSUPPORTED_PROFILE, lifecycle.actorSnapshot().firstFailure)
                assertFalse(actualPool(pools.ordinary).isRunning)
            }
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    fun `ambient Hikari file configuration is rejected before its constructor can read any file`() =
        VersionBoundPoolTestFixture(retained = true).use { fixture ->
            withPoolSystemProperty("hikaricp.configurationFile", "/deliberately-not-created/foreign-hikari.properties") {
                rejected { fixture.bind() }
            }
            val binding = poolTestField<VersionBoundPersistencePoolBinding>(checkNotNull(fixture.owner.versionBoundPools), "ordinaryBinding")
            assertNull(poolTestField<HikariDataSource?>(binding, "pool"))
            rejected { fixture.bind() }
        }

    @Test
    fun `ordinary Boot settings and legacy controlled test defaults keep their original selection`() {
        val endpoint = pgProbeEndpoint(1)
        val owner = PersistenceJdbcLifecycleOwner.sourceOnly(endpoint, 3, PersistencePathStyle.POSIX)
        val settings = HikariConfig().apply {
            maximumPoolSize = 3
            minimumIdle = 1
            initializationFailTimeout = 17
            connectionTimeout = 1750
            validationTimeout = 750
            isAutoCommit = false
            isReadOnly = true
            connectionTestQuery = "SELECT 1"
        }
        GuardedDataSource.sourceOnly(owner, endpoint, settings).use { source ->
            assertNull(owner.versionBoundPools)
            rejected { owner.bindVersionBoundPools() }
            val pool = actualPool(source)
            assertEquals(3, pool.maximumPoolSize)
            assertEquals(1, pool.minimumIdle)
            assertEquals(17L, pool.initializationFailTimeout)
            assertEquals(1750L, pool.connectionTimeout)
            assertEquals(750L, pool.validationTimeout)
            assertEquals("SELECT 1", source.ordinaryValidationQuery())
            assertFalse(pool.isAutoCommit)
            assertTrue(pool.isReadOnly)
            assertFalse(pool.isRunning)
        }
        assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, owner.observeShutdown())
        assertEquals(PersistencePublicTrustRelease.NOT_REQUIRED, owner.releasePublicTrustAfterShutdown())
        val legacy = PersistenceJdbcLifecycleOwner(endpoint, 2, PersistencePathStyle.POSIX)
        GuardedDataSource(legacy, endpoint, 2, PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY).use { source ->
            val pool = actualPool(source)
            assertEquals(2, pool.maximumPoolSize)
            assertEquals(0, pool.minimumIdle)
            assertEquals(-1L, pool.initializationFailTimeout)
            assertEquals(endpoint.loginPolicy.durationMillis, pool.connectionTimeout)
            assertNull(legacy.versionBoundPools)
            assertFalse(pool.isRunning)
        }
        assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, legacy.observeShutdown())
    }

    private fun rejected(operation: () -> Unit) {
        val failure = assertThrows(PersistenceBoundaryException::class.java) { operation() }
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }
}

internal class VersionBoundPoolTestFixture(parent: Path = Path.of("/deliberately-not-created/persistence-pool-test"), private val retained: Boolean = false) :
    AutoCloseable {
    val configuration = VersionBoundPersistenceConfiguration.fromAcquired(
        VersionBoundPersistenceTestInputs.acquired(),
        "db.invalid",
        5432,
        "fixture_db",
        "fixture_user",
        2,
        VersionBoundPersistenceTestInputs.pem(),
        parent,
    )
    val owner = configuration.bindLifecycleOwner()
    val root: PersistenceJdbcDriverRoot = poolTestField(owner, "root")

    fun bind(): VersionBoundPersistencePools = owner.bindVersionBoundPools()

    override fun close() {
        checkNotNull(owner.versionBoundPools).close()
        owner.requestShutdown()
        assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, owner.observeShutdown())
        val expected = if (retained) PersistencePublicTrustRelease.RETAINED else PersistencePublicTrustRelease.RELEASED
        assertEquals(expected, owner.releasePublicTrustAfterShutdown())
    }
}

internal fun actualPool(source: GuardedDataSource): HikariDataSource = poolTestField(source, "pool")

/** Test-only access to our own private fields. Hikari state is read/mutated exclusively through its public API. */
@Suppress("UNCHECKED_CAST")
internal fun <T> poolTestField(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name).apply { check(trySetAccessible()) }.get(owner) as T

internal fun <T> withPoolSystemProperty(name: String, value: String, operation: () -> T): T {
    val previous = System.getProperty(name)
    System.setProperty(name, value)
    return try {
        operation()
    } finally {
        if (previous == null) System.clearProperty(name) else System.setProperty(name, previous)
    }
}
