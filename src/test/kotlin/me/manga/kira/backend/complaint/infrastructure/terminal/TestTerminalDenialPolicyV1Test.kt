package me.manga.kira.backend.complaint.infrastructure.terminal

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.catalog.OfflineTrustBundleFixture
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialAuthorityInputV1
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
import java.security.MessageDigest

/** Independent syntax and commitment only. Neither a pin nor this test is installed-denial evidence. */
internal class TestTerminalDenialPolicyV1Test {
    @Test
    fun `terminal pin commits independent key context grant implementation retention and existing envelope reuse`() {
        val input = TestTerminalDenialInputFixtureV1.input()
        val policy = TestTerminalDenialAuthorityPolicyV1.fromIndependentInput(input)
        policy.requireJournal(fullTestJournal()); policy.requireEnvironment(input.environment)
        assertEquals(buildJsonObject {
            put("profile", "TEST_TERMINAL_DENIAL_AUTHORITY_V1")
            put("purpose", "TEST_SEAL_TERMINAL_PUT_DENIAL_AND_REQUEST_BOUND_V1")
            put("sha256", Sha256.hex(CanonicalJson.canonicalize(TestTerminalDenialAuthorityInputV1.serializer(), input).toByteArray()))
            put("runEnvelopeAccounting", "REUSE_FIRST_CUT_LIFETIME_ENVELOPE_V1")
            put("recoverySessionProfile", "TEST_TERMINAL_READ_ONLY_RECOVERY_V1")
            put("inventoryProfile", "TEST_POST_TERMINAL_ALL_VERSIONS_V1")
        }, policy.inventory())
        val changed = listOf(input.copy(keyId = "synthetic-terminal-denial-2"),
            TestTerminalDenialInputFixtureV1.input(spki = OfflineTrustBundleFixture.firstSigner.public.encoded),
            input.copy(minimumApprovalVersion = 2), input.copy(authorityGrant = input.authorityGrant.copy(version = 2)),
            input.copy(implementationAcceptance = input.implementationAcceptance.copy(version = 2)),
            input.copy(evidenceRetentionPolicy = input.evidenceRetentionPolicy.copy(version = 2)), input.copy(environment = "other-synthetic"))
        changed.forEach { assertNotEquals(policy.inventory(), TestTerminalDenialAuthorityPolicyV1.fromIndependentInput(it).inventory()) }
        assertFalse(policy.toString().contains(input.publicKeySpkiBase64))
        assertFalse(policy.toString().contains(input.authorityGrant.policyId))
    }

    @Test
    fun `ordinary purpose profile or first payment accounting cannot stand in for the terminal grant`() {
        val input = TestTerminalDenialInputFixtureV1.input()
        listOf(input.copy(profile = TestOrdinaryDenialAuthorityPolicyV1.PROFILE), input.copy(purpose = TestOrdinaryDenialAuthorityPolicyV1.PURPOSE),
            input.copy(runEnvelopeAccounting = TestOrdinaryDenialAuthorityPolicyV1.RUN_ENVELOPE_ACCOUNTING), input.copy(algorithmId = "RS256"),
            input.copy(keyId = "unbounded/key"), input.copy(minimumApprovalVersion = 0), input.copy(publicKeySha256 = "0".repeat(64)),
            input.copy(authorityGrant = input.authorityGrant.copy(version = 0)),
            input.copy(implementationAcceptance = input.implementationAcceptance.copy(sha256 = "A".repeat(64))),
            input.copy(evidenceRetentionPolicy = input.evidenceRetentionPolicy.copy(policyId = "has spaces"))).forEach {
            val problem = assertThrows<IllegalArgumentException> { TestTerminalDenialAuthorityPolicyV1.fromIndependentInput(it) }
            assertEquals("TEST terminal denial authority refused.", problem.message); assertNull(problem.cause)
        }
        val malformed = assertThrows<OfflineTrustBundleException> {
            TestTerminalDenialAuthorityPolicyV1.fromIndependentInput(input.copy(publicKeySpkiBase64 = " " + input.publicKeySpkiBase64))
        }
        assertEquals(OfflineTrustBundleFailure.INVALID_PUBLIC_KEY, malformed.code)
    }

    @Test
    fun `independent pin still refuses a foreign journal or environment`() {
        val input = TestTerminalDenialInputFixtureV1.input()
        val id = "99000000-0000-4000-8000-000000000001"
        listOf(input.copy(dataScopeId = id), input.copy(writerGeneration = id), input.copy(databaseIdentity = id), input.copy(restoreIdentity = id),
            input.copy(bucket = "other-synthetic-journal"), input.copy(accountId = "987654321012"), input.copy(region = "us-west-2")).forEach {
            val policy = TestTerminalDenialAuthorityPolicyV1.fromIndependentInput(it)
            assertThrows<IllegalArgumentException> { policy.requireJournal(fullTestJournal()) }
        }
        assertThrows<IllegalArgumentException> { TestTerminalDenialAuthorityPolicyV1.fromIndependentInput(input).requireEnvironment("other-synthetic") }
    }

    @Test
    fun `signature frame has independent terminal LP32 domain and hashes raw canonical bytes`() {
        val body = "{\"synthetic\":\"é\"}".toByteArray(Charsets.UTF_8)
        val keyId = "synthetic-terminal-denial-1"
        val expected = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            listOf("kira.complaints.test-terminal-denial.v1".toByteArray(), "kcj-1".toByteArray(), keyId.toByteArray(),
                "RSASSA_PSS_SHA_256".toByteArray(), MessageDigest.getInstance("SHA-256").digest(body)).forEach {
                data.writeInt(it.size); data.write(it)
            }
        } }.toByteArray()
        val actual = TestTerminalDenialAuthorityPolicyV1.frame(keyId, body)
        assertArrayEquals(expected, actual)
        assertFalse(actual.contentEquals(TestOrdinaryDenialAuthorityPolicyV1.frame(keyId, body)))
        assertFalse(actual.contentEquals(TestTerminalDenialAuthorityPolicyV1.frame(keyId, body + ' '.code.toByte())))
    }
}
