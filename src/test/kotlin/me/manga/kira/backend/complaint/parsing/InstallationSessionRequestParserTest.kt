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

/** Parsing facts only: a syntactically valid TEST UUID is not proof of an ACTIVE run or mode. */
class InstallationSessionRequestParserTest {
    @Test
    fun `closed canonical LIVE and TEST bodies prepare the existing comparison candidate without retaining body bytes`() {
        listOf(LIVE, TEST_SCOPE).forEach { scope ->
            val body = request(scope = scope).toByteArray()
            val candidate = InstallationSessionRequestParser.parse(body)
            val installation = ScopedInstallationId(ComplaintIdentifiers.installationId(ID), ComplaintIdentifiers.dataScope(scope))
            val expected = InstallationEnrollmentCredentials.prepareSession(installation, SECRET_BYTES)
            body.fill(0)
            assertEquals(installation, candidate.installation)
            assertArrayEquals(expected.verifierBytes(), candidate.verifierBytes())
            assertEquals("InstallationSessionCandidate(redacted)", candidate.toString())
        }
    }

    @Test
    fun `all three named fields are required closed strings with duplicate and escaped duplicate keys rejected`() {
        val normal = request()
        listOf(
            "{}", "[]", "null", "true", "42", "\"body\"",
            normal.replace("\"expectedDataScopeId\":\"$LIVE\"", "\"dataScopeId\":\"$LIVE\""),
            normal.dropLast(1) + ",\"platform\":\"ANDROID\"}",
            normal.replace("\"installationId\":", "\"installationId\":\"$ID\",\"installationId\":"),
            normal.replace("\"installationId\":", "\"install\\u0061tionId\":\"$ID\",\"installationId\":"),
        ).forEach(::malformed)
        mapOf("installationId" to ID, "secret" to SECRET, "expectedDataScopeId" to LIVE).forEach { (name, value) ->
            val field = "\"$name\":\"$value\""
            malformed(normal.replace(field, "\"unknown\":\"$value\""))
            listOf("null", "false", "1", "1.0", "[]", "{}").forEach { replacement ->
                malformed(normal.replace(field, "\"$name\":$replacement"))
            }
        }
    }

    @Test
    fun `UUID and scope syntax follows the existing canonical variant version rules without normalization`() {
        listOf(ID.uppercase(), ID.drop(1), LIVE, ID.replace("-42d3-", "-12d3-"), ID.replace("-a456-", "-7456-"), " $ID", "$ID ").forEach {
            malformed(request(id = it))
        }
        listOf("", TEST_SCOPE.uppercase(), TEST_SCOPE.replace("-42d3-", "-12d3-"), " $LIVE", LIVE.drop(1)).forEach {
            malformed(request(scope = it))
        }
    }

    @Test
    fun `secret is exactly canonical unpadded Base64url for 32 bytes including unused pad bit rejection`() {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val alias = SECRET.dropLast(1) + alphabet[alphabet.indexOf(SECRET.last()) + 1]
        assertArrayEquals(Base64.getUrlDecoder().decode(SECRET), Base64.getUrlDecoder().decode(alias))
        listOf("", SECRET.dropLast(1), "$SECRET=", "+${SECRET.drop(1)}", "/${SECRET.drop(1)}", " ${SECRET.drop(1)}", alias).forEach {
            malformed(request(secret = it))
        }
        malformed(request(secret = "a".repeat(65)))
        malformed(request(secret = "\\ud800"))
    }

    @Test
    fun `malformed UTF-8 foreign JSON encodings comments trailing values and excessive structure are redacted refusals`() {
        val normal = request()
        listOf(
            "",
            " ",
            "$normal {}",
            "$normal true",
            "/*comment*/$normal",
            normal.replace('"', '\''),
            "{\"installationId\":[[[\"$ID\"]]],\"secret\":\"$SECRET\",\"expectedDataScopeId\":\"$LIVE\"}",
            "{\"${"x".repeat(100)}\":\"value\"}",
        ).forEach(::malformed)
        listOf(
            normal.toByteArray() + byteArrayOf(0xc3.toByte(), 0x28),
            normal.toByteArray(Charsets.UTF_16LE),
            normal.toByteArray(Charsets.UTF_16BE),
        ).forEach { rejected(it, InstallationSessionRequestFailure.MALFORMED_BODY) }
    }

    @Test
    fun `exact four KiB byte boundary permits JSON whitespace and one byte over is refused before decoding`() {
        val body = request().toByteArray()
        val atLimit = body + ByteArray(InstallationSessionRequestParser.MAX_BODY_BYTES - body.size) { 32 }
        assertEquals(ID, InstallationSessionRequestParser.parse(atLimit).installation.id.toString())
        rejected(atLimit + byteArrayOf(32), InstallationSessionRequestFailure.PAYLOAD_TOO_LARGE)
        rejected(ByteArray(InstallationSessionRequestParser.MAX_BODY_BYTES + 1) { 0xff.toByte() }, InstallationSessionRequestFailure.PAYLOAD_TOO_LARGE)
    }

    private fun request(id: String = ID, secret: String = SECRET, scope: String = LIVE): String =
        "{\"installationId\":\"$id\",\"secret\":\"$secret\",\"expectedDataScopeId\":\"$scope\"}"

    private fun malformed(body: String) = rejected(body.toByteArray(), InstallationSessionRequestFailure.MALFORMED_BODY)

    private fun rejected(body: ByteArray, expected: InstallationSessionRequestFailure) {
        val failure = assertThrows<InstallationSessionRequestRejected> { InstallationSessionRequestParser.parse(body) }
        assertEquals(expected, failure.failure)
        assertEquals("Installation session request refused.", failure.message)
        assertNull(failure.cause)
    }

    private companion object {
        const val ID = "123e4567-e89b-42d3-a456-426614174000"
        const val LIVE = "00000000-0000-0000-0000-000000000000"
        const val TEST_SCOPE = "123e4567-e89b-42d3-a456-426614174001"
        val SECRET_BYTES = ByteArray(32) { it.toByte() }
        val SECRET = Base64.getUrlEncoder().withoutPadding().encodeToString(SECRET_BYTES)
    }
}
