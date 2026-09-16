package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.security.AcquiredVersionedSecret
import me.manga.kira.backend.security.ImmutableSecretVersion
import me.manga.kira.backend.security.SecretMaterialFamily
import me.manga.kira.backend.security.SecretMaterialPurpose
import me.manga.kira.backend.security.SecretVersionSnapshot
import me.manga.kira.backend.security.VersionedSecretBinding
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
import java.nio.file.Path

@EnabledOnOs(OS.LINUX, OS.MAC)
class VersionBoundPersistenceConfigurationTest {
    @Test
    fun `one acquisition supplies the actual password and the exact immutable binding`() {
        val password = " fixture-π-🔒\n "
        val raw = password.toByteArray(Charsets.UTF_8)
        var calls = 0
        val binding = VersionBoundPersistenceTestInputs.binding()
        val acquired = AcquiredVersionedSecret.acquire(binding) { version ->
            calls++
            SecretVersionSnapshot(version, raw)
        }
        val configuration = configured(acquired)
        val root = configuration.createRoot()
        raw.fill(0)
        acquired.useMaterial { it.fill(0) }

        assertEquals(1, calls)
        assertTrue(root.endpoint.credentialsMatch("fixture_user", password))
        assertFalse(root.endpoint.credentialsMatch("fixture_user", password.trim()))
        assertSame(acquired.descriptor, configuration.descriptor.authenticationPassword)
        assertEquals(2000L, root.endpoint.loginPolicy.durationMillis)
        assertEquals(root.endpoint.loginPolicy.durationMillis, configuration.descriptor.loginBudgetMillis)
        assertEquals(PersistenceLifecycleActivation.FAILED, root.start())
        assertEquals(PersistenceLifecycleActivation.CLOSED, root.prepareDeletion())
        assertEquals(PersistenceLifecycleActivation.CLOSED, root.prepareCatalogCoordinator())
        assertFalse(root.snapshot().ordinaryReady)
        root.requestShutdown()
        assertEquals(PersistencePublicTrustRelease.RELEASED, root.releasePublicTrustAfterShutdown())
    }

    @Test
    fun `strict UTF8 refuses malformed overlong surrogate truncated out of range and NUL passwords`() {
        val malformed = listOf(
            byteArrayOf(0),
            byteArrayOf(65, 0, 66),
            byteArrayOf(0x80.toByte()),
            byteArrayOf(0xc0.toByte(), 0x80.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xe2.toByte(), 0x82.toByte()),
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
        )
        malformed.forEach { material -> invalid { configured(VersionBoundPersistenceTestInputs.acquired(material)) } }
        listOf("x", "x".repeat(65_536), "\uFEFFliteral-bom").forEach { password ->
            val root = configured(VersionBoundPersistenceTestInputs.acquired(password.toByteArray(Charsets.UTF_8))).createRoot()
            assertTrue(root.endpoint.credentialsMatch("fixture_user", password))
            root.requestShutdown()
            assertEquals(PersistencePublicTrustRelease.RELEASED, root.releasePublicTrustAfterShutdown())
        }
    }

    @Test
    fun `other families and non-password database purposes cannot acquire fallback meanings`() {
        SecretMaterialFamily.entries.forEach { family ->
            val purpose = if (family === SecretMaterialFamily.DATABASE || family === SecretMaterialFamily.REDIS) {
                SecretMaterialPurpose.AUTHENTICATION_PASSWORD
            } else {
                SecretMaterialPurpose.HMAC_SHA256
            }
            if (family !== SecretMaterialFamily.DATABASE) {
                invalid { configured(VersionBoundPersistenceTestInputs.acquired(family = family, purpose = purpose)) }
            }
        }
        listOf(SecretMaterialPurpose.TLS_PRIVATE_KEY, SecretMaterialPurpose.TLS_PRIVATE_KEY_PASSWORD).forEach { purpose ->
            invalid { configured(VersionBoundPersistenceTestInputs.acquired(purpose = purpose)) }
        }
    }

    @Test
    fun `endpoint has a closed secure property set with no arbitrary extensions or client identity`() {
        val configuration = configured()
        val root = configuration.createRoot()
        val properties = root.endpoint.driverProperties()
        assertEquals("jdbc:postgresql://", root.endpoint.driverUrl)
        assertEquals("verify-full", properties.getProperty("sslmode"))
        assertEquals("org.postgresql.ssl.LibPQFactory", properties.getProperty("sslfactory"))
        assertEquals("org.postgresql.ssl.PGjdbcHostnameVerifier", properties.getProperty("sslhostnameverifier"))
        assertEquals("password,scram-sha-256", properties.getProperty("requireAuth"))
        assertEquals("disable", properties.getProperty("gssEncMode"))
        assertEquals("100000", properties.getProperty("scramMaxIterations"))
        assertEquals("0", properties.getProperty("loginTimeout"))
        assertEquals("1", properties.getProperty("connectTimeout"))
        assertEquals("2", properties.getProperty("socketTimeout"))
        assertEquals("1", properties.getProperty("cancelSignalTimeout"))
        assertEquals("", properties.getProperty("sslcert"))
        assertEquals("", properties.getProperty("sslkey"))
        listOf("sslpassword", "sslpasswordcallback", "socketFactory", "authenticationPluginClassName", "options").forEach { assertNull(properties[it]) }
        val actualPublic = properties.stringPropertyNames().filterNot { it == "password" || it == "sslrootcert" }
            .associateWith { properties.getProperty(it) }
        assertEquals(actualPublic, configuration.descriptor.publicDriverProperties())
        properties.setProperty("password", "changed-copy")
        properties.setProperty("sslmode", "disable")
        (configuration.descriptor.publicDriverProperties() as MutableMap<String, String>).clear()
        assertEquals(actualPublic, configuration.descriptor.publicDriverProperties())
        assertTrue(root.endpoint.credentialsMatch("fixture_user", VersionBoundPersistenceTestInputs.PASSWORD))
        root.requestShutdown()
        assertEquals(PersistencePublicTrustRelease.RELEASED, root.releasePublicTrustAfterShutdown())
    }

    @Test
    fun `public descriptor binds bytes rather than pod-local filename and never discloses password`() {
        val first = configured()
        val second = configured()
        val firstRoot = first.createRoot()
        val secondRoot = second.createRoot()
        val firstPath = firstRoot.endpoint.driverProperties().getProperty("sslrootcert")
        val secondPath = secondRoot.endpoint.driverProperties().getProperty("sslrootcert")
        assertNotEquals(firstPath, secondPath)
        assertEquals(first.descriptor.publicDriverProperties(), second.descriptor.publicDriverProperties())
        assertEquals(first.descriptor.publicTrustSha256, second.descriptor.publicTrustSha256)
        assertEquals(1, first.descriptor.publicTrustCertificateCount)
        assertEquals(VersionBoundPersistenceTestInputs.pem().size, first.descriptor.publicTrustByteCount)
        val printable = listOf(first, first.descriptor, first.descriptor.authenticationPassword, firstRoot).joinToString()
        assertFalse(printable.contains(VersionBoundPersistenceTestInputs.PASSWORD))
        assertFalse(printable.contains(firstPath))
        assertFalse(first.descriptor.publicDriverProperties().containsKey("sslrootcert"))
        listOf(firstRoot, secondRoot).forEach { root ->
            root.requestShutdown()
            assertEquals(PersistencePublicTrustRelease.RELEASED, root.releasePublicTrustAfterShutdown())
        }
    }

    @Test
    fun `same configuration cannot be paired with another endpoint or adopted by a second root`() {
        val configuration = configured()
        val foreign = ResolvedPersistenceEndpoint(mapOf("PGHOST" to "elsewhere.invalid"), PersistenceLoginPolicy.resolve("2", 2000))
        invalid { PersistenceJdbcDriverRoot(foreign, 2, PersistencePathStyle.POSIX, versionBound = configuration) }
        val root = configuration.createRoot()
        assertThrows(IllegalStateException::class.java) { configuration.createRoot() }
        assertThrows(IllegalStateException::class.java) { configuration.bindLifecycleOwner() }
        invalid { PersistenceJdbcDriverRoot(root.endpoint, 3, PersistencePathStyle.POSIX, versionBound = configuration) }
        invalid { PersistenceJdbcDriverRoot(root.endpoint, 2, PersistencePathStyle.POSIX, sourceOnly = true, versionBound = configuration) }
        root.requestShutdown()
        assertEquals(PersistencePublicTrustRelease.RELEASED, root.releasePublicTrustAfterShutdown())
        assertThrows(IllegalStateException::class.java) { configuration.bindLifecycleOwner() }
    }

    @Test
    fun `host identifiers ports capacities and caller path are deliberately narrow and not normalized`() {
        listOf("", "db.invalid,second.invalid", "db.invalid/path", "user@db.invalid", "DB.invalid", "db.invalid.", "bad\nname", "[::1]").forEach { host ->
            invalid { configured(host = host) }
        }
        listOf(0, 65_536).forEach { port -> invalid { configured(port = port) } }
        listOf("", " ", "db;options", "db/name", "d".repeat(64)).forEach { name ->
            invalid { configured(database = name) }
            invalid { configured(username = name) }
        }
        listOf(0, 65).forEach { capacity -> invalid { configured(capacity = capacity) } }
        invalid { configured(parent = Path.of("relative")) }
        invalid { configured(parent = Path.of("/absolute/../not-normalized")) }
        val failed = assertThrows(PersistenceBoundaryException::class.java) { configured(host = "private-value\n") }
        assertNull(failed.cause)
        assertTrue(failed.suppressed.isEmpty())
        assertFalse(failed.toString().contains("private-value"))
    }

    private fun configured(
        acquired: AcquiredVersionedSecret = VersionBoundPersistenceTestInputs.acquired(),
        host: String = "db.invalid",
        port: Int = 5432,
        database: String = "fixture_db",
        username: String = "fixture_user",
        capacity: Int = 2,
        parent: Path = Path.of("/deliberately-not-created/persistence-test"),
    ): VersionBoundPersistenceConfiguration = VersionBoundPersistenceConfiguration.fromAcquired(
        acquired,
        host,
        port,
        database,
        username,
        capacity,
        VersionBoundPersistenceTestInputs.pem(),
        parent,
    )

    private fun invalid(operation: () -> Unit) {
        val failure = assertThrows(PersistenceBoundaryException::class.java) { operation() }
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }
}

internal object VersionBoundPersistenceTestInputs {
    const val PASSWORD = "fixture-only-not-a-deployed-password"

    fun binding(
        family: SecretMaterialFamily = SecretMaterialFamily.DATABASE,
        purpose: SecretMaterialPurpose = SecretMaterialPurpose.AUTHENTICATION_PASSWORD,
    ): VersionedSecretBinding = VersionedSecretBinding.of(
        family,
        purpose,
        "fixture-db-password",
        ImmutableSecretVersion.awsSecretsManager(
            "arn:aws:secretsmanager:eu-west-1:123456789012:secret:fixture-db-Ab12Cd",
            "550e8400-e29b-41d4-a716-446655440000",
        ),
    )

    fun acquired(
        material: ByteArray = PASSWORD.toByteArray(Charsets.UTF_8),
        family: SecretMaterialFamily = SecretMaterialFamily.DATABASE,
        purpose: SecretMaterialPurpose = SecretMaterialPurpose.AUTHENTICATION_PASSWORD,
    ): AcquiredVersionedSecret = AcquiredVersionedSecret.acquire(binding(family, purpose)) { SecretVersionSnapshot(it, material) }

    // Public ISRG Root X1 certificate, distributed by the system CA package. No private key or network fixture.
    fun pem(): ByteArray =
        """
        -----BEGIN CERTIFICATE-----
        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
        -----END CERTIFICATE-----
        """.trimIndent().plus("\n").toByteArray(Charsets.US_ASCII)
}
