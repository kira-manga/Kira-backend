package me.manga.kira.backend.security.aws

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.security.AcquiredVersionedSecret
import me.manga.kira.backend.security.InstallationJwtCodec
import me.manga.kira.backend.security.SecretMaterialFamily
import me.manga.kira.backend.security.SecretVersionException
import me.manga.kira.backend.security.VersionBoundInstallationJwtConfiguration
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture.Companion.binding
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture.Companion.material
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture.Companion.reply
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Actual SDK -> acquisition -> existing key rings -> actual JWT signature verification. No route or deployed user-family claim. */
class AwsVersionBoundJwtConsumerTest {
    @Test
    fun `actual SDK immutable acquisitions sign and verify current and retained installation JWT keys`() {
        val fixture = AwsSecretVersionFixture()
        val bindings = listOf(binding(1), binding(2), binding(91, SecretMaterialFamily.USER_ADMIN_JWT), binding(92, SecretMaterialFamily.USER_ADMIN_JWT))
        val inputs = bindings.zip(listOf(1, 2, 91, 92).map { material(it) })
        fixture.respond = { request ->
            val fields = request.fields()
            val (selected, bytes) = inputs.single { it.first.version.resourceArn == fields["SecretId"] && it.first.version.versionId == fields["VersionId"] }
            reply(selected.version, bytes)
        }
        val acquisitions = fixture.resolver().use { resolver -> bindings.map { AcquiredVersionedSecret.acquire(it, resolver) } }
        val installations = acquisitions.take(2)
        val users = acquisitions.drop(2)
        val current = configuration(installations, users, "installation-1")
        val previous = configuration(installations, users, "installation-2")
        val expectedDescriptors = users.map { it.descriptor }.sortedBy { it.logicalKeyId } + installations.map { it.descriptor }.sortedBy { it.logicalKeyId }
        expectedDescriptors.zip(current.descriptors()).forEach { (expected, actual) -> assertSame(expected, actual) }
        assertEquals(current.descriptors(), previous.descriptors())
        inputs.forEach { it.second.fill(0) }
        fixture.replies.forEach { it.bytes.fill(0) }
        acquisitions.forEach { acquired -> acquired.useMaterial { it.fill(0) } }

        val now = Instant.parse("2026-09-17T12:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val currentCodec = InstallationJwtCodec(current.installationKeyRing, clock)
        val previousCodec = InstallationJwtCodec(previous.installationKeyRing, clock)
        val installation = ScopedInstallationId(UUID.fromString("65000000-0000-4000-8000-000000000001"), ComplaintDataScope.LIVE)
        val signed = listOf(currentCodec.issue(installation, 7, now) to "installation-1", previousCodec.issue(installation, 7, now) to "installation-2")
        signed.forEach { (token, id) ->
            val verified = currentCodec.verify(token.value)
            assertEquals(id, verified.keyId)
            assertEquals(installation, verified.installation)
            assertEquals(7L, verified.credentialVersion)
        }
        assertArrayEquals(material(1), checkNotNull(current.installationKeyRing.key("installation-1")).encoded)
        assertArrayEquals(material(2), checkNotNull(current.installationKeyRing.key("installation-2")).encoded)
        assertEquals(4, fixture.requests.size)
        assertEquals(1, fixture.closedClients)
        fixture.replies.forEach { response ->
            assertEquals(1, response.aborts)
            assertEquals(1, response.closes)
        }
    }

    @Test
    fun `different fetched versions cannot bypass existing effective HMAC separation and failures retain both acquisitions`() {
        val fixture = AwsSecretVersionFixture()
        val installationBinding = binding(1)
        val userBinding = binding(91, SecretMaterialFamily.USER_ADMIN_JWT, "user-91")
        val shared = material(1)
        fixture.respond = { request ->
            if (request.fields()["VersionId"] == installationBinding.version.versionId) {
                reply(installationBinding.version, shared)
            } else {
                reply(userBinding.version, shared.copyOf(64)) // Different bytes, same effective HMAC key.
            }
        }
        val (installation, user) = fixture.resolver().use { resolver ->
            AcquiredVersionedSecret.acquire(installationBinding, resolver) to AcquiredVersionedSecret.acquire(userBinding, resolver)
        }
        val failure = assertThrows(IllegalArgumentException::class.java) { configuration(listOf(installation), listOf(user)) }
        assertSanitized(failure)
        assertEquals(installationBinding.version, installation.descriptor.version)
        assertEquals(userBinding.version, user.descriptor.version)
        installation.useMaterial { assertArrayEquals(shared, it) }
        user.useMaterial { assertArrayEquals(shared.copyOf(64), it) }
        assertEquals(2, fixture.requests.size)
        assertEquals(1, fixture.closedClients)
    }

    @Test
    fun `a later provider failure does not discard or replace a successfully retained key owner`() {
        val fixture = AwsSecretVersionFixture()
        val resolver = fixture.resolver()
        val retained = AcquiredVersionedSecret.acquire(binding(), resolver)
        try {
            fixture.respond = { reply().apply { status = 503 } }
            assertSanitized(assertThrows(SecretVersionException::class.java) { AcquiredVersionedSecret.acquire(binding(2), resolver) })
            assertEquals(binding().version, retained.descriptor.version)
            retained.useMaterial { assertArrayEquals(material(), it) }
            assertEquals(2, fixture.requests.size)
        } finally {
            resolver.close()
        }
        retained.useMaterial { assertArrayEquals(material(), it) }
        assertEquals(1, fixture.closedClients)
    }

    private fun configuration(
        installations: List<AcquiredVersionedSecret>,
        users: List<AcquiredVersionedSecret>,
        active: String = "installation-1",
    ): VersionBoundInstallationJwtConfiguration =
        VersionBoundInstallationJwtConfiguration.fromAcquired(active, installations, "fixture-user-issuer", "fixture-user-audience", users)
}
