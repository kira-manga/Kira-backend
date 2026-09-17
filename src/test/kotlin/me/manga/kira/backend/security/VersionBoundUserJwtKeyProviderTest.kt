package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.support.JwtTestSupport
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.mock.env.MockEnvironment
import org.springframework.security.oauth2.jwt.JwtException
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

class VersionBoundUserJwtKeyProviderTest {
    private val properties = KiraSecurityProperties()
    private val security = SecurityConfig(MockEnvironment())

    @Test
    fun `one acquired singleton drives actual user wire and installation family from the same owner without relookup`() {
        var lookups = 0
        val original = Base64.getDecoder().decode(JwtTestSupport.TEST_JWT_SECRET_BASE64)
        val userAcquired = acquired(material = original) { lookups++ }
        val owner = JwtKeyProvider.fromAcquired(userAcquired, properties)
        val installation = acquired(SecretMaterialFamily.INSTALLATION_JWT, "installation-key", 2, bytes(2)) { lookups++ }
        val configuration = VersionBoundInstallationJwtConfiguration.fromAcquired("installation-key", listOf(installation), owner)
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val service = JwtService(owner, properties, clock)
        val decoder = security.jwtDecoder(owner, properties)
        val legacyProperties = properties.copy(jwtSecret = JwtTestSupport.TEST_JWT_SECRET_BASE64)
        val legacy = JwtService(JwtKeyProvider(legacyProperties), legacyProperties, clock)
        original.fill(0)
        owner.secretKey.encoded.fill(0)
        configuration.userAdminFamily.keys().single().secretKey().encoded.fill(0)
        val user = user()
        val token = service.issue(user)
        assertEquals(legacy.issue(user).value, token.value)
        val decoded = decoder.decode(token.value)
        assertEquals("HS256", decoded.headers["alg"])
        assertEquals(JwtService.KEY_ID, decoded.headers["kid"])
        assertEquals(setOf("sub", "iss", "aud", "iat", "exp", "email", "role", "credential_version"), decoded.claims.keys)
        assertEquals(user.id.toString(), decoded.subject)
        assertEquals(user.email, decoded.getClaimAsString("email"))
        assertEquals("ADMIN", decoded.getClaimAsString("role"))
        assertEquals("7", decoded.getClaimAsString("credential_version"))
        assertEquals(properties.issuer, decoded.getClaimAsString("iss"))
        assertEquals(listOf(properties.audience), decoded.audience)
        assertEquals(now, decoded.issuedAt)
        assertEquals(now.plus(properties.accessTokenTtl), decoded.expiresAt)
        assertEquals(properties.accessTokenTtl.seconds, token.expiresInSeconds)
        assertSame(userAcquired.descriptor, owner.immutableVersionBinding())
        assertSame(owner, configuration.boundUserKeyProvider)
        assertEquals(listOf(userAcquired.descriptor, installation.descriptor), configuration.descriptors())
        assertEquals(listOf(JwtService.KEY_ID), configuration.userAdminFamily.keys().map { it.id })
        assertArrayEquals(owner.secretKey.encoded, configuration.userAdminFamily.keys().single().secretKey().encoded)
        val codec = InstallationJwtCodec(configuration.installationKeyRing, clock)
        val identity = ScopedInstallationId(UUID.fromString("67000000-0000-4000-8000-000000000001"), ComplaintDataScope.LIVE)
        val installationToken = codec.issue(identity, 1, now)
        assertEquals(identity, codec.verify(installationToken.value).installation)
        assertThrows(JwtException::class.java) { decoder.decode(installationToken.value) }
        assertThrows(InstallationJwtRejectedException::class.java) { codec.verify(token.value) }
        assertEquals(2, lookups)
    }

    @Test
    fun `the actual signer and decoder refuse replaced JWT scalars or a parallel property secret but not unrelated settings`() {
        val owner = JwtKeyProvider.fromAcquired(acquired(), properties)
        val alternatives =
            listOf(
                properties.copy(issuer = "other-issuer"),
                properties.copy(audience = "other-audience"),
                properties.copy(accessTokenTtl = Duration.ofMinutes(30)),
                properties.copy(clockSkew = Duration.ofSeconds(30)),
                properties.copy(jwtSecret = JwtTestSupport.TEST_JWT_SECRET_BASE64),
            )
        alternatives.forEach { changed ->
            invalid { JwtService(owner, changed, Clock.systemUTC()) }
            invalid { security.jwtDecoder(owner, changed) }
        }
        val unrelated = properties.copy(trustForwardedHeaders = true, trustedProxies = listOf("192.0.2.0/24"))
        val token = JwtService(owner, unrelated, Clock.systemUTC()).issue(user())
        assertEquals(user().id.toString(), security.jwtDecoder(owner, unrelated).decode(token.value).subject)
        val family = owner.installationUserFamily()
        assertEquals(properties.issuer, family.issuer)
        assertEquals(properties.audience, family.audience)
    }

    @Test
    fun `every retained installation key is checked against the actual user key for material ID and reference aliasing`() {
        val short = bytes(10)
        val long = bytes(11, 128)
        val aliases = listOf(short to short, short to short.copyOf(64), long to MessageDigest.getInstance("SHA-256").digest(long))
        val current = acquired(SecretMaterialFamily.INSTALLATION_JWT, "installation-current", 2, bytes(90))
        aliases.forEach { (userBytes, installationBytes) ->
            val userAcquired = acquired(material = userBytes)
            val owner = JwtKeyProvider.fromAcquired(userAcquired, properties)
            val retained = acquired(SecretMaterialFamily.INSTALLATION_JWT, "installation-retained", 3, installationBytes)
            invalid { VersionBoundInstallationJwtConfiguration.fromAcquired("installation-current", listOf(current, retained), owner) }
            assertArrayEquals(userBytes, owner.secretKey.encoded)
            assertSame(userAcquired.descriptor, owner.immutableVersionBinding())
        }
        val owner = JwtKeyProvider.fromAcquired(acquired(), properties)
        val wrongId = acquired(SecretMaterialFamily.INSTALLATION_JWT, JwtService.KEY_ID, 3, bytes(91))
        val wrongVersion = acquired(SecretMaterialFamily.INSTALLATION_JWT, "installation-retained", 1, bytes(91))
        listOf(wrongId, wrongVersion).forEach { retained ->
            invalid { VersionBoundInstallationJwtConfiguration.fromAcquired("installation-current", listOf(current, retained), owner) }
        }
    }

    @Test
    fun `the bound factory refuses wrong family kid blank scalars mixed weak oversized keys and legacy owner fallback`() {
        SecretMaterialFamily.entries.filterNot { it == SecretMaterialFamily.USER_ADMIN_JWT }.forEach { family ->
            invalid { JwtKeyProvider.fromAcquired(acquired(family = family), properties) }
        }
        invalid { JwtKeyProvider.fromAcquired(acquired(id = "other-user-key"), properties) }
        invalid { JwtKeyProvider.fromAcquired(acquired(), properties.copy(jwtSecret = JwtTestSupport.TEST_JWT_SECRET_BASE64)) }
        listOf(properties.copy(issuer = ""), properties.copy(audience = " \t")).forEach { invalidProperties ->
            invalid { JwtKeyProvider.fromAcquired(acquired(), invalidProperties) }
        }
        listOf(1, 31, 129, 65_536).forEach { size ->
            val original = bytes(15, size)
            val input = acquired(material = original)
            invalid { JwtKeyProvider.fromAcquired(input, properties) }
            assertArrayEquals(original, input.useMaterial { it.copyOf() })
        }
        val legacy = JwtKeyProvider(properties.copy(jwtSecret = JwtTestSupport.TEST_JWT_SECRET_BASE64))
        val installation = acquired(SecretMaterialFamily.INSTALLATION_JWT, "installation-current", 2, bytes(2))
        invalid { VersionBoundInstallationJwtConfiguration.fromAcquired("installation-current", listOf(installation), legacy) }
        val owner = JwtKeyProvider.fromAcquired(acquired(), properties)
        val rendered = listOf(owner, owner.immutableVersionBinding(), owner.installationUserFamily()).joinToString()
        listOf(JwtTestSupport.TEST_JWT_SECRET_BASE64, JwtService.KEY_ID, reference(1).resourceArn, reference(1).versionId).forEach {
            assertFalse(rendered.contains(it))
        }
    }

    @Test
    fun `Spring still selects the environment constructor with its unchanged long-key and whitespace rules`() {
        val material = bytes(17, 129)
        val properties = properties.copy(jwtSecret = "  ${Base64.getEncoder().encodeToString(material)}  ")
        AnnotationConfigApplicationContext().use { context ->
            context.beanFactory.registerSingleton("securityProperties", properties)
            context.register(JwtKeyProvider::class.java)
            context.refresh()
            val owner = context.getBean(JwtKeyProvider::class.java)
            assertArrayEquals(material, owner.secretKey.encoded)
            invalid { owner.immutableVersionBinding() }
            val service = JwtService(owner, properties, Clock.systemUTC())
            val decoded = security.jwtDecoder(owner, properties).decode(service.issue(user()).value)
            assertEquals(JwtService.KEY_ID, decoded.headers["kid"])
            assertEquals("7", decoded.getClaimAsString("credential_version"))
            assertEquals(properties.issuer, decoded.getClaimAsString("iss"))
        }
    }

    private fun acquired(
        family: SecretMaterialFamily = SecretMaterialFamily.USER_ADMIN_JWT,
        id: String = JwtService.KEY_ID,
        version: Int = 1,
        material: ByteArray = Base64.getDecoder().decode(JwtTestSupport.TEST_JWT_SECRET_BASE64),
        onLookup: () -> Unit = {},
    ): AcquiredVersionedSecret {
        val purpose =
            if (family == SecretMaterialFamily.DATABASE || family == SecretMaterialFamily.REDIS) {
                SecretMaterialPurpose.AUTHENTICATION_PASSWORD
            } else {
                SecretMaterialPurpose.HMAC_SHA256
            }
        val binding = VersionedSecretBinding.of(family, purpose, id, reference(version))
        return AcquiredVersionedSecret.acquire(binding) { requested ->
            onLookup()
            assertEquals(binding.version, requested)
            SecretVersionSnapshot(reference(version), material)
        }
    }

    private fun reference(version: Int): ImmutableSecretVersion = ImmutableSecretVersion.awsSecretsManager(
        "arn:aws:secretsmanager:eu-west-1:123456789012:secret:kira/user-jwt-fixture-AbC123",
        "66000000-0000-4000-8000-${version.toString().padStart(12, '0')}",
    )

    private fun bytes(seed: Int, size: Int = 32): ByteArray = ByteArray(size) { (it * 7 + seed).toByte() }

    private fun user(): User = User(
        id = UUID.fromString("67000000-0000-4000-8000-000000000002"),
        email = "fixture@example.com",
        passwordHash = "{bcrypt}irrelevant",
        role = Role.ADMIN,
        enabled = true,
        credentialVersion = 7,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun invalid(action: () -> Unit) {
        val failure = assertThrows(IllegalArgumentException::class.java) { action() }
        assertTrue(failure.message in setOf("Invalid version-bound user JWT configuration", "Invalid installation JWT key configuration"))
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }
}
