package me.manga.kira.backend.security

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

class SecretVersionAcquisitionTest {
    @Test
    fun `one exact lookup constructs material and descriptor from the same reported snapshot`() {
        val expected = binding()
        val reported = version()
        val raw = bytes()
        var calls = 0
        val acquired = AcquiredVersionedSecret.acquire(expected) { requested ->
            calls += 1
            assertSame(expected.version, requested)
            SecretVersionSnapshot(reported, raw)
        }
        assertEquals(1, calls)
        assertSame(reported, acquired.descriptor.version)
        assertEquals(expected.family, acquired.descriptor.family)
        assertEquals(expected.purpose, acquired.descriptor.purpose)
        assertEquals(expected.logicalKeyId, acquired.descriptor.logicalKeyId)
        acquired.useMaterial { assertArrayEquals(raw, it) }
        assertEquals("AcquiredVersionedSecret(redacted)", acquired.toString())
        assertEquals("SecretVersionSnapshot(redacted)", SecretVersionSnapshot(reported, raw).toString())
    }

    @Test
    fun `resolver input and all consumer copies cannot mutate retained material and temporary copies are cleared`() {
        val raw = bytes()
        val expected = raw.copyOf()
        val snapshot = SecretVersionSnapshot(version(), raw)
        raw.fill(0)
        val acquired = AcquiredVersionedSecret.acquire(binding()) { snapshot }
        snapshot.copyMaterial().fill(91)
        val retainedTemporary = acquired.useMaterial { copy ->
            assertArrayEquals(expected, copy)
            copy.fill(72)
            copy
        }
        assertArrayEquals(ByteArray(expected.size), retainedTemporary)
        var exceptionalTemporary: ByteArray? = null
        assertThrows(IllegalStateException::class.java) {
            acquired.useMaterial { copy ->
                exceptionalTemporary = copy
                error("Consumer failure")
            }
        }
        assertArrayEquals(ByteArray(expected.size), exceptionalTemporary)
        acquired.useMaterial { assertArrayEquals(expected, it) }
    }

    @Test
    fun `material has a finite acquisition bound but key suitability remains a consumer responsibility`() {
        listOf(0, 65_537).forEach { size ->
            rejected(SecretVersionFailure.INVALID_MATERIAL) { SecretVersionSnapshot(version(), ByteArray(size)) }
        }
        listOf(1, 65_536).forEach { size ->
            val acquired = AcquiredVersionedSecret.acquire(binding()) { SecretVersionSnapshot(version(), ByteArray(size)) }
            acquired.useMaterial { assertEquals(size, it.size) }
        }
        val weak = AcquiredVersionedSecret.acquire(binding()) { SecretVersionSnapshot(version(), byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { weak.useMaterial { InstallationJwtKeyMaterial("installation", it) } }
    }

    @Test
    fun `a different resource or version is refused without a retry or usable result`() {
        listOf(version(arn = ARN.replace("123456789012", "123456789013")), version(id = OTHER_VERSION)).forEach { reported ->
            var calls = 0
            rejected(SecretVersionFailure.REFERENCE_MISMATCH) {
                AcquiredVersionedSecret.acquire(binding()) {
                    calls += 1
                    SecretVersionSnapshot(reported, bytes())
                }
            }
            assertEquals(1, calls)
        }
    }

    @Test
    fun `ordinary resolver failures are rebuilt without provider messages causes or suppressed data`() {
        val privateMessage = "private-provider-material-$ARN-$VERSION"
        val failures = listOf(
            IllegalStateException(privateMessage, IllegalArgumentException(privateMessage)),
            SecretVersionException(SecretVersionFailure.INVALID_MATERIAL).apply { initCause(IllegalStateException(privateMessage)) },
        )
        failures.forEach { original ->
            original.addSuppressed(IllegalStateException(privateMessage))
            var calls = 0
            val failure = rejected(SecretVersionFailure.RESOLVER_FAILURE) {
                AcquiredVersionedSecret.acquire(binding()) {
                    calls += 1
                    throw original
                }
            }
            assertEquals(1, calls)
            assertFalse(failure.toString().contains(privateMessage))
        }
    }

    @Test
    fun `preexisting direct and return-time interruption refuse acquisition and preserve the interrupt flag`() {
        try {
            Thread.currentThread().interrupt()
            rejected(SecretVersionFailure.INTERRUPTED) {
                AcquiredVersionedSecret.acquire(binding()) { error("Must not resolve an interrupted request") }
            }
            assertTrue(Thread.interrupted())
            rejected(SecretVersionFailure.INTERRUPTED) {
                AcquiredVersionedSecret.acquire(binding()) { throw InterruptedException("private resolver detail") }
            }
            assertTrue(Thread.interrupted())
            rejected(SecretVersionFailure.INTERRUPTED) {
                AcquiredVersionedSecret.acquire(binding()) {
                    Thread.currentThread().interrupt()
                    SecretVersionSnapshot(version(), bytes())
                }
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `cancellation is sanitized without becoming success and fatal errors are not swallowed`() {
        val original = CancellationException("private cancellation").apply { addSuppressed(IllegalStateException("private cause")) }
        val cancelled = assertThrows(CancellationException::class.java) { AcquiredVersionedSecret.acquire(binding()) { throw original } }
        assertEquals("Secret-version acquisition cancelled.", cancelled.message)
        assertNull(cancelled.cause)
        assertTrue(cancelled.suppressed.isEmpty())
        val fatal = AssertionError("fatal sentinel")
        assertSame(fatal, assertThrows(AssertionError::class.java) { AcquiredVersionedSecret.acquire(binding()) { throw fatal } })
    }

    @Test
    fun `distinct secret versions and family labels do not bypass existing effective key separation`() {
        val raw = bytes()
        val userInput = AcquiredVersionedSecret.acquire(binding(SecretMaterialFamily.USER_ADMIN_JWT, "user-key")) {
            SecretVersionSnapshot(version(), raw)
        }
        val installationInput = AcquiredVersionedSecret.acquire(binding(id = "installation-key", reference = version(id = OTHER_VERSION))) {
            SecretVersionSnapshot(version(id = OTHER_VERSION), raw.copyOf(64))
        }
        val userKey = userInput.useMaterial { InstallationJwtKeyMaterial(userInput.descriptor.logicalKeyId, it) }
        val installationKey = installationInput.useMaterial { InstallationJwtKeyMaterial(installationInput.descriptor.logicalKeyId, it) }
        val forbidden = InstallationJwtForbiddenFamily("user-issuer", "user-audience", listOf(userKey))
        assertThrows(IllegalArgumentException::class.java) { InstallationJwtKeyRing("installation-key", listOf(installationKey), forbidden) }
    }

    private fun binding(
        family: SecretMaterialFamily = SecretMaterialFamily.INSTALLATION_JWT,
        id: String = "installation-key",
        reference: ImmutableSecretVersion = version(),
    ): VersionedSecretBinding = VersionedSecretBinding.of(family, SecretMaterialPurpose.HMAC_SHA256, id, reference)

    private fun version(arn: String = ARN, id: String = VERSION): ImmutableSecretVersion = ImmutableSecretVersion.awsSecretsManager(arn, id)

    private fun bytes(): ByteArray = ByteArray(32) { (it * 7 + 3).toByte() }

    private fun rejected(code: SecretVersionFailure, operation: () -> Unit): SecretVersionException {
        val failure = assertThrows(SecretVersionException::class.java) { operation() }
        assertEquals(code, failure.code)
        assertEquals("Secret-version input rejected: ${code.name}.", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        return failure
    }

    private companion object {
        const val ARN = "arn:aws:secretsmanager:eu-west-1:123456789012:secret:kira/installations/key-AbC123"
        const val VERSION = "64000000-0000-4000-8000-0000000000ab"
        const val OTHER_VERSION = "64000000-0000-4000-8000-0000000000ac"
    }
}
