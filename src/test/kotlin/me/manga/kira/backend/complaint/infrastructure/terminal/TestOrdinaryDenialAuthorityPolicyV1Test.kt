package me.manga.kira.backend.complaint.infrastructure.terminal

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.catalog.OfflineTrustBundleFixture
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDenialAuthorityInputV1
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineTrustBundleCrypto
import me.manga.kira.backend.security.fullTestJournal
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.MessageDigest

/** Independent-input syntax/commitment and primitive framing only; not signer/evidence admission. */
internal class TestOrdinaryDenialAuthorityPolicyV1Test {
    @Test
    fun `retained policy inventory commits the complete independent declaration and no supplied approval`() {
        val input = TestOrdinaryDenialInputFixtureV1.input()
        val policy = TestOrdinaryDenialAuthorityPolicyV1.fromIndependentInput(input)
        policy.requireEnvironment(input.environment)
        policy.requireJournal(fullTestJournal())
        val expected = buildJsonObject {
            put("profile", "TEST_ORDINARY_DENIAL_AUTHORITY_V1")
            put("purpose", "TEST_ORDINARY_PUT_DENIAL_AND_REQUEST_BOUND_V1")
            put("sha256", Sha256.hex(CanonicalJson.canonicalize(TestOrdinaryDenialAuthorityInputV1.serializer(), input).toByteArray(Charsets.UTF_8)))
            put("runEnvelopeAccounting", "RUN_LIFETIME_ENVELOPE_AT_FIRST_CUT_V1")
        }
        assertEquals(expected, policy.inventory())
        assertEquals(expected, TestOrdinaryDenialAuthorityPolicyV1.fromIndependentInput(input.copy()).inventory())
        assertFalse(policy.toString().contains(input.publicKeySpkiBase64))
        assertFalse(policy.toString().contains(input.authorityGrant.policyId))
    }

    @Test
    fun `every mutable independent issuer context and approval pin changes the retained commitment`() {
        val input = TestOrdinaryDenialInputFixtureV1.input()
        val original = TestOrdinaryDenialAuthorityPolicyV1.fromIndependentInput(input).inventory()
        val anotherId = "99000000-0000-4000-8000-000000000001"
        val replacementKey = TestOrdinaryDenialInputFixtureV1.input(spki = OfflineTrustBundleFixture.secondSigner.public.encoded)
        val changes = listOf(
            input.copy(keyId = "synthetic-ordinary-denial-2"), replacementKey,
            input.copy(environment = "other-synthetic-test"), input.copy(dataScopeId = anotherId),
            input.copy(writerGeneration = anotherId), input.copy(databaseIdentity = anotherId), input.copy(restoreIdentity = anotherId),
            input.copy(bucket = "other-synthetic-journal"), input.copy(accountId = "987654321012"), input.copy(region = "us-west-2"),
            input.copy(minimumApprovalVersion = 2),
            input.copy(authorityGrant = input.authorityGrant.copy(policyId = "synthetic:grant/another")),
            input.copy(authorityGrant = input.authorityGrant.copy(version = 2)),
            input.copy(authorityGrant = input.authorityGrant.copy(sha256 = "a".repeat(64))),
            input.copy(implementationAcceptance = input.implementationAcceptance.copy(version = 2)),
            input.copy(evidenceRetentionPolicy = input.evidenceRetentionPolicy.copy(version = 2)),
        )
        changes.forEachIndexed { index, changed ->
            assertNotEquals(original, TestOrdinaryDenialAuthorityPolicyV1.fromIndependentInput(changed).inventory(), "independent pin $index")
        }
    }

    @Test
    fun `unsupported purpose suite accounting profile and malformed identity declarations are refused`() {
        val input = TestOrdinaryDenialInputFixtureV1.input()
        val invalid = listOf(
            input.copy(profile = "PRE_CUTOVER_TEST_ORDINARY_SEAL_V1"), input.copy(purpose = "CATALOG_SIGNING"),
            input.copy(algorithmId = "RS256"), input.copy(runEnvelopeAccounting = "PAY_AT_PURGED"),
            input.copy(keyId = ""), input.copy(keyId = "a".repeat(65)), input.copy(keyId = "key/alias"),
            input.copy(environment = "Synthetic-Test"), input.copy(environment = "synthetic test"),
            input.copy(minimumApprovalVersion = 0), input.copy(minimumApprovalVersion = -1),
            input.copy(dataScopeId = "00000000-0000-0000-0000-000000000000"),
            input.copy(writerGeneration = "99000000-0000-5000-8000-000000000001"),
            input.copy(databaseIdentity = "database-alias"), input.copy(restoreIdentity = "restore-alias"),
            input.copy(bucket = "Uppercase-Bucket"), input.copy(accountId = "1234"), input.copy(region = "us-east-0"),
            input.copy(publicKeySha256 = "A".repeat(64)), input.copy(publicKeySha256 = "a".repeat(63)),
            input.copy(publicKeySha256 = "0".repeat(64)),
        )
        invalid.forEach { changed ->
            val failure = assertThrows<IllegalArgumentException> { TestOrdinaryDenialAuthorityPolicyV1.fromIndependentInput(changed) }
            assertEquals("TEST ordinary denial authority refused.", failure.message)
            assertNull(failure.cause)
        }
    }

    @Test
    fun `authority implementation and retention references each require bounded IDs positive versions and lowercase hashes`() {
        val input = TestOrdinaryDenialInputFixtureV1.input()
        val valid = input.authorityGrant
        val invalid = listOf(
            valid.copy(policyId = ""), valid.copy(policyId = "x".repeat(129)), valid.copy(policyId = "grant has spaces"),
            valid.copy(version = 0), valid.copy(version = -1), valid.copy(sha256 = "A".repeat(64)), valid.copy(sha256 = "a".repeat(63)),
        )
        val replace: List<(InitialPolicyReferenceV1) -> TestOrdinaryDenialAuthorityInputV1> = listOf(
            { input.copy(authorityGrant = it) }, { input.copy(implementationAcceptance = it) }, { input.copy(evidenceRetentionPolicy = it) },
        )
        replace.forEach { withReference ->
            invalid.forEach { reference ->
                assertThrows<IllegalArgumentException> { TestOrdinaryDenialAuthorityPolicyV1.fromIndependentInput(withReference(reference)) }
            }
            TestOrdinaryDenialAuthorityPolicyV1.fromIndependentInput(withReference(valid.copy(policyId = "x".repeat(128), version = Long.MAX_VALUE)))
        }
    }

    @Test
    fun `independently pinned RSA material still requires canonical base64 DER 3072 bits and exponent 65537`() {
        val input = TestOrdinaryDenialInputFixtureV1.input()
        val noncanonical = OfflineTrustBundleFixture.firstSigner.public.encoded.copyOf().also { it[17] = 0x04 }
        val invalid = listOf(
            input.copy(publicKeySpkiBase64 = input.publicKeySpkiBase64.dropLast(1)),
            input.copy(publicKeySpkiBase64 = " " + input.publicKeySpkiBase64),
            input.copy(publicKeySpkiBase64 = input.publicKeySpkiBase64 + "="),
            TestOrdinaryDenialInputFixtureV1.input(spki = noncanonical),
            TestOrdinaryDenialInputFixtureV1.input(spki = OfflineTrustBundleFixture.newKey(bits = 2048).public.encoded),
            TestOrdinaryDenialInputFixtureV1.input(spki = OfflineTrustBundleFixture.newKey(exponent = BigInteger.valueOf(3)).public.encoded),
        )
        invalid.forEach { changed ->
            val failure = assertThrows<OfflineTrustBundleException> { TestOrdinaryDenialAuthorityPolicyV1.fromIndependentInput(changed) }
            assertEquals(OfflineTrustBundleFailure.INVALID_PUBLIC_KEY, failure.code)
            assertNull(failure.cause)
        }
    }

    @Test
    fun `syntax-valid foreign scope writer restore location and environment pins cannot match the retained journal`() {
        val input = TestOrdinaryDenialInputFixtureV1.input()
        val anotherId = "99000000-0000-4000-8000-000000000001"
        val mismatches = listOf(
            input.copy(dataScopeId = anotherId), input.copy(writerGeneration = anotherId),
            input.copy(databaseIdentity = anotherId), input.copy(restoreIdentity = anotherId),
            input.copy(bucket = "other-synthetic-journal"), input.copy(accountId = "987654321012"), input.copy(region = "us-west-2"),
        )
        mismatches.forEach { changed ->
            val policy = TestOrdinaryDenialAuthorityPolicyV1.fromIndependentInput(changed)
            assertThrows<IllegalArgumentException> { policy.requireJournal(fullTestJournal()) }
        }
        val policy = TestOrdinaryDenialAuthorityPolicyV1.fromIndependentInput(input)
        assertThrows<IllegalArgumentException> { policy.requireEnvironment("other-synthetic-test") }
    }

    @Test
    fun `purpose frame is exact LP32 and hashes raw body bytes rather than a catalog domain or hex digest`() {
        val body = "{\"synthetic\":\"é\"}".toByteArray(Charsets.UTF_8)
        val keyId = "synthetic-ordinary-denial-1"
        val expected = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { framed ->
                listOf(
                    "kira.complaints.test-ordinary-denial.v1".toByteArray(Charsets.UTF_8), "kcj-1".toByteArray(Charsets.UTF_8),
                    keyId.toByteArray(Charsets.UTF_8), "RSASSA_PSS_SHA_256".toByteArray(Charsets.UTF_8),
                    MessageDigest.getInstance("SHA-256").digest(body),
                ).forEach { part -> framed.writeInt(part.size); framed.write(part) }
            }
        }.toByteArray()
        val actual = TestOrdinaryDenialAuthorityPolicyV1.frame(keyId, body)
        assertArrayEquals(expected, actual)
        assertFalse(actual.contentEquals(OfflineTrustBundleCrypto.signatureFrame(keyId, body)))
        assertFalse(actual.contentEquals(TestOrdinaryDenialAuthorityPolicyV1.frame("other-key", body)))
        assertFalse(actual.contentEquals(TestOrdinaryDenialAuthorityPolicyV1.frame(keyId, body + ' '.code.toByte())))
    }
}
