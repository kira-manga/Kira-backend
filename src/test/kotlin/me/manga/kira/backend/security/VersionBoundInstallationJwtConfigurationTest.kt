package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

class VersionBoundInstallationJwtConfigurationTest {
    @Test
    fun `both actual families and all descriptors derive from the same acquisitions without another lookup`() {
        var lookups = 0
        fun input(family: SecretMaterialFamily, id: String, seed: Int): AcquiredVersionedSecret {
            val binding = VersionedSecretBinding.of(family, SecretMaterialPurpose.HMAC_SHA256, id, reference(seed))
            return AcquiredVersionedSecret.acquire(binding) { requested ->
                lookups += 1
                SecretVersionSnapshot(reference(seed), bytes(seed)).also { assertEquals(reference(seed), requested) }
            }
        }
        val current = input(SecretMaterialFamily.INSTALLATION_JWT, "installation-z", 1)
        val previous = input(SecretMaterialFamily.INSTALLATION_JWT, "installation-a", 2)
        val userCurrent = input(SecretMaterialFamily.USER_ADMIN_JWT, "user-z", 91)
        val userPrevious = input(SecretMaterialFamily.USER_ADMIN_JWT, "user-a", 92)
        val configuration = configured(listOf(current, previous), listOf(userCurrent, userPrevious), "installation-z")

        assertEquals(4, lookups)
        assertEquals("installation-z", configuration.installationKeyRing.activeKeyId)
        assertEquals(setOf("installation-a", "installation-z"), configuration.installationKeyRing.verificationKeyIds())
        assertArrayEquals(bytes(1), checkNotNull(configuration.installationKeyRing.key("installation-z")).encoded)
        assertArrayEquals(bytes(2), checkNotNull(configuration.installationKeyRing.key("installation-a")).encoded)
        assertNull(configuration.installationKeyRing.key("user-z"))
        assertEquals("user-issuer", configuration.userAdminFamily.issuer)
        assertEquals("user-audience", configuration.userAdminFamily.audience)
        val userKeys = configuration.userAdminFamily.keys()
        assertEquals(listOf("user-a", "user-z"), userKeys.map { it.id })
        assertArrayEquals(bytes(92), userKeys[0].secretKey().encoded)
        assertArrayEquals(bytes(91), userKeys[1].secretKey().encoded)
        val expected = listOf(userPrevious, userCurrent, previous, current).map { it.descriptor }
        assertEquals(expected.size, configuration.descriptors().size)
        expected.zip(configuration.descriptors()).forEach { (acquired, actual) -> assertSame(acquired, actual) }
    }

    @Test
    fun `the existing codec signs with the selected acquisition and verifies every retained installation key`() {
        val installations = listOf(acquired(id = "installation-current"), acquired(id = "installation-old", seed = 2))
        val current = configured(installations, active = "installation-current")
        val previous = configured(installations, active = "installation-old")
        val now = Instant.parse("2026-09-16T12:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val currentCodec = InstallationJwtCodec(current.installationKeyRing, clock)
        val previousCodec = InstallationJwtCodec(previous.installationKeyRing, clock)
        val installation = ScopedInstallationId(UUID.fromString("65000000-0000-4000-8000-000000000001"), ComplaintDataScope.LIVE)
        val currentToken = currentCodec.issue(installation, 7, now)
        val previousToken = previousCodec.issue(installation, 7, now)

        listOf(currentToken to "installation-current", previousToken to "installation-old").forEach { (token, id) ->
            val verified = currentCodec.verify(token.value)
            assertEquals(id, verified.keyId)
            assertEquals(installation, verified.installation)
            assertEquals(7L, verified.credentialVersion)
        }
        assertEquals(current.descriptors().map { it.version }, previous.descriptors().map { it.version })
    }

    @Test
    fun `ordering input mutation and returned copies cannot change descriptors or actual retained material`() {
        val original = bytes(1)
        val expected = original.copyOf()
        val userRaw = bytes(91)
        val installation = acquired(material = original)
        val installations = mutableListOf(installation, acquired(id = "installation-old", seed = 2))
        val users = mutableListOf(acquired(SecretMaterialFamily.USER_ADMIN_JWT, "user-key", 91, userRaw))
        val configuration = configured(installations, users)
        val reversed = configured(installations.reversed(), users.reversed())
        val descriptors = configuration.descriptors()
        assertEquals(descriptors, reversed.descriptors())
        original.fill(0)
        userRaw.fill(0)
        installations.clear()
        users.clear()
        (configuration.descriptors() as MutableList<VersionedSecretBinding>).clear()
        checkNotNull(configuration.installationKeyRing.key("installation-key")).encoded.fill(0)
        configuration.userAdminFamily.keys().first().secretKey().encoded.fill(0)
        val temporary = installation.useMaterial { it.apply { fill(0) } }
        assertArrayEquals(ByteArray(32), temporary)

        assertEquals(descriptors, configuration.descriptors())
        assertArrayEquals(expected, checkNotNull(configuration.installationKeyRing.key("installation-key")).encoded)
        assertArrayEquals(bytes(91), configuration.userAdminFamily.keys().first().secretKey().encoded)
        assertArrayEquals(expected, installation.useMaterial { it.copyOf() })
    }

    @Test
    fun `one through eight keys in each family retain exact ID and material size boundaries`() {
        val ids = listOf("a") + (2..7).map { "installation-$it" } + "A._-".repeat(16)
        val installations = ids.mapIndexed { index, id -> acquired(id = id, seed = index + 1, material = bytes(index + 1, if (index == 7) 128 else 32)) }
        val users = (1..8).map { acquired(SecretMaterialFamily.USER_ADMIN_JWT, "user-$it", 90 + it) }
        val configuration = configured(installations, users, ids.last(), "u".repeat(256), "a".repeat(256))
        assertEquals(16, configuration.descriptors().size)
        assertEquals(ids.toSet(), configuration.installationKeyRing.verificationKeyIds())
        assertEquals(8, configuration.userAdminFamily.keys().size)
        assertArrayEquals(bytes(8, 128), checkNotNull(configuration.installationKeyRing.key(ids.last())).encoded)
        assertEquals(2, configured().descriptors().size)
    }

    @Test
    fun `both family lists are mandatory and bounded before allocation and throughout traversal`() {
        invalid { configured(installations = emptyList()) }
        invalid { configured(users = emptyList()) }
        listOf(SecretMaterialFamily.INSTALLATION_JWT, SecretMaterialFamily.USER_ADMIN_JWT).forEach { family ->
            val values = (1..9).map { acquired(family, "key-$it", if (family == SecretMaterialFamily.USER_ADMIN_JWT) 100 + it else it) }
            listOf(Triple(9, 9, 0), Triple(8, 9, 8), Triple(2, 1, 1), Triple(1, 2, 1)).forEach { (declared, actual, reads) ->
                val changing = CountedAcquisitions(declared, values.take(actual))
                invalid {
                    if (family == SecretMaterialFamily.INSTALLATION_JWT) {
                        configured(installations = changing, active = "key-1")
                    } else {
                        configured(users = changing)
                    }
                }
                assertEquals(reads, changing.reads)
            }
        }
    }

    @Test
    fun `foreign purposes families absent active IDs and invalid user families cannot acquire defaults`() {
        SecretMaterialFamily.entries.forEach { family ->
            val purpose = if (family == SecretMaterialFamily.DATABASE || family == SecretMaterialFamily.REDIS) {
                SecretMaterialPurpose.AUTHENTICATION_PASSWORD
            } else {
                SecretMaterialPurpose.HMAC_SHA256
            }
            val descriptor = VersionedSecretBinding.of(family, purpose, "foreign-key", reference(20))
            val foreign = AcquiredVersionedSecret.acquire(descriptor) { SecretVersionSnapshot(it, bytes(20)) }
            if (family != SecretMaterialFamily.INSTALLATION_JWT) invalid { configured(installations = listOf(acquired(), foreign)) }
            if (family != SecretMaterialFamily.USER_ADMIN_JWT) invalid { configured(users = listOf(foreign)) }
        }
        listOf("", " ", "missing", "installation-key\n", "clé", "x".repeat(65)).forEach { active -> invalid { configured(active = active) } }
        listOf("", " ", "x".repeat(257), InstallationJwtCodec.ISSUER, InstallationJwtCodec.AUDIENCE).forEach { family ->
            invalid { configured(issuer = family) }
            invalid { configured(audience = family) }
        }
        invalid { configured(issuer = "same", audience = "same") }
    }

    @Test
    fun `duplicate logical IDs or full immutable references cannot nominate keys within or across families`() {
        invalid { configured(installations = listOf(acquired(), acquired(seed = 2))) }
        invalid {
            configured(
                users = listOf(
                    acquired(SecretMaterialFamily.USER_ADMIN_JWT, "user-key", 91),
                    acquired(SecretMaterialFamily.USER_ADMIN_JWT, "user-key", 92),
                ),
            )
        }
        invalid { configured(users = listOf(acquired(SecretMaterialFamily.USER_ADMIN_JWT, "installation-key", 91))) }

        val shared = reference(50)
        val installation = acquired(reference = shared)
        val anotherInstallation = acquired(id = "other-installation", seed = 2, reference = shared)
        val user = acquired(SecretMaterialFamily.USER_ADMIN_JWT, "user-key", 91, reference = shared)
        val anotherUser = acquired(SecretMaterialFamily.USER_ADMIN_JWT, "other-user", 92, reference = shared)
        invalid { configured(installations = listOf(installation, anotherInstallation)) }
        invalid { configured(users = listOf(user, anotherUser)) }
        invalid { configured(installations = listOf(installation), users = listOf(user)) }
    }

    @Test
    fun `distinct versions in one resource and the same version token in different resources stay distinct`() {
        val first = acquired(reference = reference(1, "shared"))
        val second = acquired(id = "installation-next", seed = 2, reference = reference(2, "shared"))
        val user = acquired(SecretMaterialFamily.USER_ADMIN_JWT, "user-key", 91, reference = reference(1, "different"))
        val configuration = configured(listOf(second, first), listOf(user))
        assertEquals(3, configuration.descriptors().map { it.version }.distinct().size)
        assertArrayEquals(bytes(1), checkNotNull(configuration.installationKeyRing.key("installation-key")).encoded)
        assertArrayEquals(bytes(2), checkNotNull(configuration.installationKeyRing.key("installation-next")).encoded)
    }

    @Test
    fun `distinct acquired versions never bypass effective HMAC separation including retained user keys`() {
        val short = bytes(1)
        val long = bytes(2, 128)
        val aliases = listOf(short to short, short to short.copyOf(64), long to MessageDigest.getInstance("SHA-256").digest(long))
        aliases.forEach { (first, second) ->
            invalid {
                configured(installations = listOf(acquired(material = first), acquired(id = "other", seed = 2, material = second)))
            }
            val retainedUsers = listOf(
                acquired(SecretMaterialFamily.USER_ADMIN_JWT, "user-current", 91, bytes(91)),
                acquired(SecretMaterialFamily.USER_ADMIN_JWT, "user-old", 92, second),
            )
            invalid { configured(installations = listOf(acquired(material = first)), users = retainedUsers) }
            invalid {
                configured(
                    installations = listOf(acquired(material = bytes(50))),
                    users = listOf(
                        acquired(SecretMaterialFamily.USER_ADMIN_JWT, "user-current", 91, first),
                        acquired(SecretMaterialFamily.USER_ADMIN_JWT, "user-old", 92, second),
                    ),
                )
            }
        }
    }

    @Test
    fun `acquisition valid material still passes the existing key strength bounds for both families`() {
        listOf(1, 31, 129, 65_536).forEach { size ->
            invalid { configured(installations = listOf(acquired(material = bytes(1, size)))) }
            invalid { configured(users = listOf(acquired(SecretMaterialFamily.USER_ADMIN_JWT, "user-key", 91, bytes(91, size)))) }
        }
    }

    @Test
    fun `diagnostics disclose neither acquired material nor IDs versions and submitted family names`() {
        val raw = "synthetic-private-material-not-a-live-secret".toByteArray()
        val configuration = configured(installations = listOf(acquired(material = raw)))
        assertEquals(
            "VersionBoundInstallationJwtConfiguration(redacted,no-deployed-user-binding,no-authority)",
            configuration.toString(),
        )
        val rendered = listOf(configuration, configuration.installationKeyRing, configuration.userAdminFamily, configuration.descriptors()).joinToString()
        val inputs = listOf(
            String(raw),
            Base64.getEncoder().encodeToString(raw),
            "installation-key",
            "user-key",
            "user-issuer",
            reference(1).resourceArn,
            reference(1).versionId,
        )
        inputs.forEach { input -> assertFalse(rendered.contains(input)) }
        invalid { configured(active = "private-submitted-key-id") }
    }

    private fun configured(
        installations: List<AcquiredVersionedSecret> = listOf(acquired()),
        users: List<AcquiredVersionedSecret> = listOf(acquired(SecretMaterialFamily.USER_ADMIN_JWT, "user-key", 91)),
        active: String = "installation-key",
        issuer: String = "user-issuer",
        audience: String = "user-audience",
    ): VersionBoundInstallationJwtConfiguration = VersionBoundInstallationJwtConfiguration.fromAcquired(active, installations, issuer, audience, users)

    private fun acquired(
        family: SecretMaterialFamily = SecretMaterialFamily.INSTALLATION_JWT,
        id: String = "installation-key",
        seed: Int = 1,
        material: ByteArray = bytes(seed),
        reference: ImmutableSecretVersion = reference(seed),
    ): AcquiredVersionedSecret {
        val binding = VersionedSecretBinding.of(family, SecretMaterialPurpose.HMAC_SHA256, id, reference)
        return AcquiredVersionedSecret.acquire(binding) { SecretVersionSnapshot(reference, material) }
    }

    private fun reference(number: Int, resource: String = "key"): ImmutableSecretVersion = ImmutableSecretVersion.awsSecretsManager(
        "arn:aws:secretsmanager:eu-west-1:123456789012:secret:kira/$resource-AbC123",
        "64000000-0000-4000-8000-${number.toString(16).padStart(12, '0')}",
    )

    private fun bytes(seed: Int, count: Int = 32): ByteArray = ByteArray(count) { (seed + it * 7).toByte() }

    private fun invalid(operation: () -> Unit) {
        val failure = assertThrows(IllegalArgumentException::class.java) { operation() }
        assertEquals("Invalid installation JWT key configuration", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private class CountedAcquisitions(private val declaredSize: Int, private val actual: List<AcquiredVersionedSecret>) :
        AbstractList<AcquiredVersionedSecret>() {
        var reads = 0
            private set

        override val size: Int get() = declaredSize

        override fun get(index: Int): AcquiredVersionedSecret = actual[index]

        override fun iterator(): Iterator<AcquiredVersionedSecret> = actual.asSequence().map { acquired ->
            reads += 1
            acquired
        }.iterator()
    }
}
