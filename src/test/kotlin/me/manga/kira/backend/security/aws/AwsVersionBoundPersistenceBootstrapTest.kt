package me.manga.kira.backend.security.aws

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcDriverRoot
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLifecycleOwner
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePublicTrustRelease
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceTestInputs
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.withPoolSystemProperty
import me.manga.kira.backend.security.SecretVersionException
import me.manga.kira.backend.security.SecretVersionFailure
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture.Companion.PRIVATE_TEXT
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture.Companion.reply
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.nio.file.Path

/** Reuses the existing public-trust fixture and cold pool/root custody; no PostgreSQL, trust preparation or actor start. */
@EnabledOnOs(OS.LINUX, OS.MAC)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AwsVersionBoundPersistenceBootstrapTest {
    @Test
    fun `production bootstrap retains one real SDK acquisition then returns the same inert persistence owner`() {
        val fixture = fixture()
        val resolver = fixture.resolver()
        val bootstrap = AwsVersionBoundPersistenceBootstrap(VersionBoundPersistenceTestInputs.binding(), resolver)
        assertNull(bootstrap.acquired)
        assertNull(bootstrap.configuration)
        assertNull(bootstrap.owner)
        assertTrue(fixture.requests.isEmpty())
        val owner = bind(bootstrap)
        try {
            resolver.close() // Consumer material is the retained acquisition, not a live resolver handle.
            val acquired = checkNotNull(bootstrap.acquired)
            val configuration = checkNotNull(bootstrap.configuration)
            val root = poolTestField<PersistenceJdbcDriverRoot>(owner, "root")
            assertSame(owner, bootstrap.owner)
            assertSame(acquired.descriptor, configuration.descriptor.authenticationPassword)
            assertTrue(root.endpoint.credentialsMatch("fixture_user", VersionBoundPersistenceTestInputs.PASSWORD))
            assertFalse(owner.snapshot().ordinaryReady)
            acquired.useMaterial { it.fill(0) }
            fixture.replies.single().bytes.fill(0)
            assertTrue(root.endpoint.credentialsMatch("fixture_user", VersionBoundPersistenceTestInputs.PASSWORD))
            val pools = owner.bindVersionBoundPools()
            assertSame(pools, owner.versionBoundPools)
            assertEquals(listOf(2, 4, 1), pools.descriptors().map { it.hikari.sizing.maximumPoolSize })
            pools.descriptors().forEach { descriptor -> assertSame(acquired.descriptor, descriptor.authenticationPassword) }
            assertFalse(pools.ordinary.businessReady())
            assertFalse(pools.deletion.businessReady())
            assertFalse(pools.catalogCoordinator.dataSource.businessReady())
            val failure = assertThrows(SecretVersionException::class.java) { bind(bootstrap) }
            assertEquals(SecretVersionFailure.INVALID_BINDING, failure.code)
            assertEquals(1, fixture.requests.size)
        } finally {
            resolver.close()
            closeOwner(owner)
        }
        assertEquals(1, fixture.closedClients)
        checkNotNull(bootstrap.acquired).useMaterial { assertArrayEquals(VersionBoundPersistenceTestInputs.PASSWORD.toByteArray(), it) }
    }

    @Test
    fun `configuration failure after acquisition retains exact material and permanently prevents relookup or rebind`() {
        val invalidUtf8 = byteArrayOf(0xff.toByte())
        val fixture = fixture(invalidUtf8)
        fixture.resolver().use { resolver ->
            val bootstrap = AwsVersionBoundPersistenceBootstrap(VersionBoundPersistenceTestInputs.binding(), resolver)
            assertSanitized(assertThrows(PersistenceBoundaryException::class.java) { bind(bootstrap) })
            val retained = checkNotNull(bootstrap.acquired)
            assertEquals(VersionBoundPersistenceTestInputs.binding().version, retained.descriptor.version)
            retained.useMaterial { assertArrayEquals(invalidUtf8, it) }
            assertNull(bootstrap.configuration)
            assertNull(bootstrap.owner)
            assertSanitized(assertThrows(SecretVersionException::class.java) { bind(bootstrap) })
            assertSame(retained, bootstrap.acquired)
            assertEquals(1, fixture.requests.size)
        }
        assertEquals(1, fixture.closedClients)
    }

    @Test
    fun `later resolver cleanup failure cannot replace the already returned acquisition configuration or owner`() {
        val fixture = fixture()
        fixture.onClientClose = { throw IOException(PRIVATE_TEXT) }
        val resolver = fixture.resolver()
        val bootstrap = AwsVersionBoundPersistenceBootstrap(VersionBoundPersistenceTestInputs.binding(), resolver)
        val owner = bind(bootstrap)
        val acquired = checkNotNull(bootstrap.acquired)
        val configuration = checkNotNull(bootstrap.configuration)
        try {
            repeat(2) { assertSanitized(assertThrows(SecretVersionException::class.java) { resolver.close() }) }
            assertSame(acquired, bootstrap.acquired)
            assertSame(configuration, bootstrap.configuration)
            assertSame(owner, bootstrap.owner)
            acquired.useMaterial { assertArrayEquals(VersionBoundPersistenceTestInputs.PASSWORD.toByteArray(), it) }
            assertSame(acquired.descriptor, configuration.descriptor.authenticationPassword)
            assertEquals(1, fixture.requests.size)
            assertEquals(1, fixture.closedClients)
        } finally {
            closeOwner(owner)
        }
    }

    @Test
    fun `failed cold pool construction keeps bootstrap acquisition and the original owner custody with no implicit replacement`() {
        val fixture = fixture()
        fixture.resolver().use { resolver ->
            val bootstrap = AwsVersionBoundPersistenceBootstrap(VersionBoundPersistenceTestInputs.binding(), resolver)
            val owner = bind(bootstrap)
            val acquired = checkNotNull(bootstrap.acquired)
            val custody = checkNotNull(owner.versionBoundPools)
            try {
                withPoolSystemProperty("hikaricp.configurationFile", "/synthetic-must-not-read/hikari.properties") {
                    assertSanitized(assertThrows(PersistenceBoundaryException::class.java) { owner.bindVersionBoundPools() })
                }
                assertSanitized(assertThrows(PersistenceBoundaryException::class.java) { custody.descriptors() })
                assertSanitized(assertThrows(PersistenceBoundaryException::class.java) { owner.bindVersionBoundPools() })
                assertSame(acquired, bootstrap.acquired)
                assertSame(owner, bootstrap.owner)
                assertSame(custody, owner.versionBoundPools)
                acquired.useMaterial { assertArrayEquals(VersionBoundPersistenceTestInputs.PASSWORD.toByteArray(), it) }
                assertEquals(1, fixture.requests.size)
            } finally {
                closeOwner(owner, retained = true)
            }
        }
    }

    @Test
    fun `inert construction does not bypass the connection-free bind gate or acquire under a transaction`() {
        val fixture = fixture()
        fixture.resolver().use { resolver ->
            TransactionSynchronizationManager.setActualTransactionActive(true)
            val bootstrap: AwsVersionBoundPersistenceBootstrap
            try {
                bootstrap = AwsVersionBoundPersistenceBootstrap(VersionBoundPersistenceTestInputs.binding(), resolver)
                assertThrows(PersistencePhaseException::class.java) { bind(bootstrap) }
                assertNull(bootstrap.acquired)
                assertTrue(fixture.requests.isEmpty())
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }
            val owner = bind(bootstrap) // Refusal before entry did not initiate a lookup or consume the bootstrap.
            closeOwner(owner)
            assertEquals(1, fixture.requests.size)
        }
    }

    private fun fixture(material: ByteArray = VersionBoundPersistenceTestInputs.PASSWORD.toByteArray()): AwsSecretVersionFixture =
        AwsSecretVersionFixture().apply { respond = { reply(VersionBoundPersistenceTestInputs.binding().version, material) } }

    private fun bind(bootstrap: AwsVersionBoundPersistenceBootstrap): PersistenceJdbcLifecycleOwner = bootstrap.bind(
        "db.invalid", 5432, "fixture_db", "fixture_user", 2, VersionBoundPersistenceTestInputs.pem(), Path.of("/deliberately-not-created/secret-bootstrap"),
    )

    private fun closeOwner(owner: PersistenceJdbcLifecycleOwner, retained: Boolean = false) {
        checkNotNull(owner.versionBoundPools).close()
        owner.requestShutdown()
        assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, owner.observeShutdown())
        assertEquals(if (retained) PersistencePublicTrustRelease.RETAINED else PersistencePublicTrustRelease.RELEASED, owner.releasePublicTrustAfterShutdown())
    }
}
