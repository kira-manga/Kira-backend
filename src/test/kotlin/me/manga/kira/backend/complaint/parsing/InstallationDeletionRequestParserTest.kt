package me.manga.kira.backend.complaint.parsing

import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import java.util.UUID

class InstallationDeletionRequestParserTest {
    @Test
    fun `exact mobile LIVE and TEST deletion tuples retain version key and verifier without retaining caller bytes`() {
        listOf(LIVE, TEST_SCOPE).forEach { scope ->
            listOf(1L, Long.MAX_VALUE).forEach { version ->
                val body = request(scope = scope, version = version.toString()).toByteArray()
                val candidate = InstallationDeletionRequestParser.parse(body, KEY)
                val installation = ScopedInstallationId(ComplaintIdentifiers.installationId(ID), ComplaintIdentifiers.dataScope(scope))
                val expected = InstallationEnrollmentCredentials.prepareSession(installation, SECRET_BYTES)
                body.fill(0)
                assertEquals(installation, candidate.installation)
                assertEquals(version, candidate.credentialVersion)
                assertEquals(UUID.fromString(KEY), candidate.operationKey)
                assertArrayEquals(expected.verifierBytes(), candidate.credential.verifierBytes())
                candidate.credential.verifierBytes().fill(0)
                assertArrayEquals(expected.verifierBytes(), candidate.credential.verifierBytes())
                assertEquals("InstallationDeletionCandidate(redacted,no-authority)", candidate.toString())
            }
        }
    }

    @Test
    fun `submitted version is a positive exact Long and the separate key is canonical UUIDv4`() {
        listOf("0", "-1", "9223372036854775808", "1.0", "1e0", "\"1\"", "null", "true", "[]", "{}").forEach {
            malformed(request(version = it))
        }
        listOf("", KEY.uppercase(), " $KEY", "$KEY ", "$KEY,$KEY", LIVE, KEY.replace("-42d3-", "-12d3-")).forEach {
            rejected(request().toByteArray(), InstallationDeletionRequestFailure.MALFORMED_REQUEST, it)
        }
    }

    @Test
    fun `closed fields reject session aliases duplicate and unknown fields and malformed secret scope or JSON`() {
        val body = request()
        listOf(
            body.replace("dataScopeId", "expectedDataScopeId"),
            body.replace("\"credentialVersion\":1", "\"unknown\":1"),
            body.dropLast(1) + ",\"credentialVersion\":1}",
            body.dropLast(1) + ",\"credential\\u0056ersion\":1}",
            body.dropLast(1) + ",\"idempotencyKey\":\"$KEY\"}",
            body.replace(SECRET, "$SECRET="),
            body.replace(LIVE, "not-a-scope"),
            "$body {}", "[]", "null", "",
        ).forEach(::malformed)
        rejected(body.toByteArray() + byteArrayOf(0xc3.toByte(), 0x28), InstallationDeletionRequestFailure.MALFORMED_REQUEST)
        rejected(body.toByteArray(Charsets.UTF_16LE), InstallationDeletionRequestFailure.MALFORMED_REQUEST)
    }

    @Test
    fun `four KiB cap rejects before decoding or key parsing and all failures expose only bounded diagnostics`() {
        val body = request().toByteArray()
        val atLimit = body + ByteArray(InstallationDeletionRequestParser.MAX_BODY_BYTES - body.size) { 32 }
        assertEquals(1L, InstallationDeletionRequestParser.parse(atLimit, KEY).credentialVersion)
        rejected(atLimit + byteArrayOf(32), InstallationDeletionRequestFailure.PAYLOAD_TOO_LARGE)
        rejected(ByteArray(InstallationDeletionRequestParser.MAX_BODY_BYTES + 1) { -1 }, InstallationDeletionRequestFailure.PAYLOAD_TOO_LARGE, "")
    }

    private fun request(scope: String = LIVE, version: String = "1"): String =
        "{\"installationId\":\"$ID\",\"secret\":\"$SECRET\",\"credentialVersion\":$version,\"dataScopeId\":\"$scope\"}"

    private fun malformed(body: String) = rejected(body.toByteArray(), InstallationDeletionRequestFailure.MALFORMED_REQUEST)

    private fun rejected(body: ByteArray, expected: InstallationDeletionRequestFailure, key: String = KEY) {
        val failure = assertThrows<InstallationDeletionRequestRejected> { InstallationDeletionRequestParser.parse(body, key) }
        assertEquals(expected, failure.failure)
        assertEquals("Installation deletion request refused.", failure.message)
        assertNull(failure.cause)
        assertEquals(0, failure.suppressed.size)
        assertEquals(0, failure.stackTrace.size)
    }

    private companion object {
        const val ID = "123e4567-e89b-42d3-a456-426614174000"
        const val KEY = "123e4567-e89b-42d3-a456-426614174002"
        const val LIVE = "00000000-0000-0000-0000-000000000000"
        const val TEST_SCOPE = "123e4567-e89b-42d3-a456-426614174001"
        val SECRET_BYTES = ByteArray(32) { it.toByte() }
        val SECRET = Base64.getUrlEncoder().withoutPadding().encodeToString(SECRET_BYTES)
    }
}
