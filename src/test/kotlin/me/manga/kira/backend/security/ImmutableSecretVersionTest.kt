package me.manga.kira.backend.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImmutableSecretVersionTest {
    @Test
    fun `full supported ARNs and canonical UUID versions retain their exact scalar identities`() {
        val reference = version()
        assertEquals(ARN, reference.resourceArn)
        assertEquals(VERSION, reference.versionId)
        assertEquals(reference, version())
        assertEquals(reference.hashCode(), version().hashCode())
        assertNotEquals(reference, version(versionId = OTHER_VERSION))
        assertNotEquals(reference, version(arn = ARN.replace("123456789012", "123456789013")))
        listOf("a", "folder/key_+=.@-", "a".repeat(512)).forEach { name ->
            val arn = "arn:aws:secretsmanager:eu-west-1:123456789012:secret:$name-AbC123"
            assertEquals(arn, version(arn = arn).resourceArn)
        }
    }

    @Test
    fun `stages aliases missing versions and alternate UUID spellings fail without coercion`() {
        listOf(
            "", "latest", "LATEST", "AWSCURRENT", "AWSPREVIOUS", "version-1", "1",
            " $VERSION", "$VERSION ", "$VERSION\n", VERSION.uppercase(), VERSION.replace("-", ""),
            "00000000-0000-0000-0000-000000000000", VERSION.replace("-4000-", "-1000-"),
            VERSION.replace("-8000-", "-7000-"), "$VERSION?stage=AWSCURRENT", "x".repeat(4096),
        ).forEach { rejected -> invalid(SecretVersionFailure.INVALID_REFERENCE) { version(versionId = rejected) } }
    }

    @Test
    fun `mutable paths partial ARNs other providers and malformed resource fields are unsupported`() {
        listOf(
            "", "/run/secrets/key", "file:///run/secrets/key", "env:KIRA_KEY", "kira-key",
            "https://secrets.example/key", "arn:aws:kms:eu-west-1:123456789012:alias/key",
            ARN.replace("arn:aws:", "arn:aws-cn:"), ARN.replace("arn:aws:", "arn:aws-us-gov:"),
            ARN.replace("eu-west-1", "EU-WEST-1"), ARN.replace("eu-west-1", "eu-west"),
            ARN.replace("123456789012", "12345678901"), ARN.replace("123456789012", "1234567890123"),
            ARN.removeSuffix("-AbC123"), ARN.replace("secret:", "secret: "), ARN.replace("/key", "/clé"),
            ARN.replace("key-AbC123", "a".repeat(513) + "-AbC123"),
            ARN.replace("eu-west-1", "eu-" + "a".repeat(65) + "-1"),
            "$ARN?versionId=$VERSION", "$ARN#version", "$ARN\n", " $ARN", "x".repeat(4096),
        ).forEach { rejected -> invalid(SecretVersionFailure.INVALID_REFERENCE) { version(arn = rejected) } }
    }

    @Test
    fun `bindings preserve the declared family purpose key and reference without accepting incompatible purposes`() {
        val reference = version()
        SecretMaterialFamily.entries.forEach { family ->
            SecretMaterialPurpose.entries.forEach { purpose ->
                val credentials = family == SecretMaterialFamily.DATABASE || family == SecretMaterialFamily.REDIS
                if (credentials != (purpose == SecretMaterialPurpose.HMAC_SHA256)) {
                    val binding = VersionedSecretBinding.of(family, purpose, "key-1", reference)
                    assertEquals(family, binding.family)
                    assertEquals(purpose, binding.purpose)
                    assertEquals("key-1", binding.logicalKeyId)
                    assertEquals(reference, binding.version)
                } else {
                    invalid(SecretVersionFailure.INVALID_BINDING) { VersionedSecretBinding.of(family, purpose, "key-1", reference) }
                }
            }
        }
        listOf("a", "A._-".repeat(16)).forEach { id -> assertEquals(id, binding(id).logicalKeyId) }
        listOf("", " ", " key", "key ", "key\n", "key/id", "clé", "a".repeat(65)).forEach { id ->
            invalid(SecretVersionFailure.INVALID_BINDING) { binding(id) }
        }
    }

    @Test
    fun `reference binding and refusal diagnostics redact submitted identities`() {
        assertEquals("ImmutableSecretVersion(aws-secrets-manager,redacted)", version().toString())
        assertEquals("VersionedSecretBinding(redacted)", binding("private-logical-key").toString())
        listOf(version().toString(), binding("private-logical-key").toString()).forEach { rendered ->
            listOf(ARN, VERSION, "private-logical-key").forEach { privateValue -> assertFalse(rendered.contains(privateValue)) }
        }
    }

    private fun binding(id: String): VersionedSecretBinding =
        VersionedSecretBinding.of(SecretMaterialFamily.INSTALLATION_JWT, SecretMaterialPurpose.HMAC_SHA256, id, version())

    private fun version(arn: String = ARN, versionId: String = VERSION): ImmutableSecretVersion = ImmutableSecretVersion.awsSecretsManager(arn, versionId)

    private fun invalid(code: SecretVersionFailure, operation: () -> Unit) {
        val failure = assertThrows(SecretVersionException::class.java) { operation() }
        assertEquals(code, failure.code)
        assertEquals("Secret-version input rejected: ${code.name}.", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private companion object {
        const val ARN = "arn:aws:secretsmanager:eu-west-1:123456789012:secret:kira/installations/key-AbC123"
        const val VERSION = "64000000-0000-4000-8000-0000000000ab"
        const val OTHER_VERSION = "64000000-0000-4000-8000-0000000000ac"
    }
}
