package me.manga.kira.backend.complaint.parsing

import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import java.util.HexFormat

/** Syntax and comparison preparation only; a TEST UUID does not establish an ACTIVE run or mode. */
class InstallationEnrollmentRequestParserTest {
    @Test
    fun `canonical platform and scope bindings use the real verifier without retaining caller bytes`() {
        listOf(ComplaintPlatform.ANDROID, ComplaintPlatform.IOS).forEach { platform ->
            listOf(LIVE, TEST_SCOPE).forEach { scope ->
                val body = request(platform = platform.name, scope = scope).toByteArray()
                val candidate = InstallationEnrollmentRequestParser.parse(body)
                val installation = ScopedInstallationId(ComplaintIdentifiers.installationId(ID), ComplaintIdentifiers.dataScope(scope))
                val expected = InstallationEnrollmentCredentials.prepare(installation, platform, SECRET_BYTES)
                body.fill(0)
                candidate.verifierBytes().fill(0)
                assertEquals(installation, candidate.installation)
                assertEquals(platform, candidate.platform)
                assertArrayEquals(expected.verifierBytes(), candidate.verifierBytes())
                assertEquals("InstallationEnrollmentCandidate(redacted)", candidate.toString())
            }
        }
        val vector = InstallationEnrollmentRequestParser.parse(request(id = VERIFIER_ID).toByteArray())
        assertEquals(VERIFIER_HEX, HexFormat.of().formatHex(vector.verifierBytes()))
    }

    @Test
    fun `exactly four named string fields are required with raw and escaped duplicates rejected`() {
        val normal = request()
        mapOf("installationId" to ID, "secret" to SECRET, "platform" to "ANDROID", "expectedDataScopeId" to LIVE).forEach { (name, value) ->
            val field = "\"$name\":\"$value\""
            malformed(normal.replace("$field,", "").replace(",$field", ""))
            malformed(normal.replace(field, "\"unknown\":\"$value\""))
            malformed(normal.dropLast(1) + ",$field}")
            val escaped = "\\u" + name.first().code.toString(16).padStart(4, '0') + name.drop(1)
            malformed(normal.dropLast(1) + ",\"$escaped\":\"$value\"}")
            listOf("null", "false", "1", "1.0", "[]", "{}").forEach { replacement ->
                malformed(normal.replace(field, "\"$name\":$replacement"))
            }
        }
        malformed(normal.replace("\"expectedDataScopeId\":", "\"dataScopeId\":"))
        malformed(normal.dropLast(1) + ",\"credentialVersion\":1}")
        malformed(normal.dropLast(1) + ",\"isReplay\":true}")
    }

    @Test
    fun `canonical UUID Base64url and platform spelling are never normalized or coerced`() {
        listOf(ID.uppercase(), ID.drop(1), LIVE, ID.replace("-42d3-", "-12d3-"), ID.replace("-a456-", "-7456-"), " $ID", "$ID ").forEach {
            malformed(request(id = it))
        }
        listOf("", TEST_SCOPE.uppercase(), TEST_SCOPE.replace("-42d3-", "-12d3-"), TEST_SCOPE.replace("-a456-", "-7456-"), " $LIVE", LIVE.drop(1)).forEach {
            malformed(request(scope = it))
        }
        listOf("", "android", "ios", "Android", "DESKTOP", " ANDROID", "IOS ", "ANDR\\u00d6ID").forEach {
            malformed(request(platform = it))
        }
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
    fun `four KiB cap precedes UTF-8 decoding and exact boundary whitespace remains valid`() {
        val body = request().toByteArray()
        val atLimit = body + ByteArray(InstallationEnrollmentRequestParser.MAX_BODY_BYTES - body.size) { 32 }
        assertEquals(ID, InstallationEnrollmentRequestParser.parse(atLimit).installation.id.toString())
        rejected(atLimit + byteArrayOf(32), InstallationEnrollmentRequestFailure.PAYLOAD_TOO_LARGE)
        rejected(ByteArray(InstallationEnrollmentRequestParser.MAX_BODY_BYTES + 1) { 0xff.toByte() }, InstallationEnrollmentRequestFailure.PAYLOAD_TOO_LARGE)
        listOf(
            body + byteArrayOf(0xc3.toByte(), 0x28),
            body + byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            request().toByteArray(Charsets.UTF_16LE),
            request().toByteArray(Charsets.UTF_16BE),
        ).forEach { rejected(it, InstallationEnrollmentRequestFailure.MALFORMED_BODY) }
    }

    @Test
    fun `malformed or excessive JSON never leaks input or a parser cause`() {
        val normal = request()
        listOf(
            "", " ", "{}", "[]", "null", "true", "42", "\"body\"",
            "$normal {}", "$normal true", "/*comment*/$normal", normal.replace('"', '\''),
            normal.dropLast(1) + ",}", normal.replace("\"installationId\":", "installationId:"),
            normal.replace("\"installationId\":\"$ID\"", "\"installationId\":[[[\"$ID\"]]]"),
            "{\"${"x".repeat(100)}\":\"value\"}",
            request(platform = "ANDR\nOID"),
        ).forEach(::malformed)
    }

    private fun request(id: String = ID, secret: String = SECRET, platform: String = "ANDROID", scope: String = LIVE): String =
        "{\"installationId\":\"$id\",\"secret\":\"$secret\",\"platform\":\"$platform\",\"expectedDataScopeId\":\"$scope\"}"

    private fun malformed(body: String) = rejected(body.toByteArray(), InstallationEnrollmentRequestFailure.MALFORMED_BODY)

    private fun rejected(body: ByteArray, expected: InstallationEnrollmentRequestFailure) {
        val failure = assertThrows<InstallationEnrollmentRequestRejected> { InstallationEnrollmentRequestParser.parse(body) }
        assertEquals(expected, failure.failure)
        assertEquals("Installation enrollment request refused.", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.stackTrace.isEmpty())
    }

    private companion object {
        const val ID = "123e4567-e89b-42d3-a456-426614174000"
        const val LIVE = "00000000-0000-0000-0000-000000000000"
        const val TEST_SCOPE = "123e4567-e89b-42d3-a456-426614174001"
        const val VERIFIER_ID = "10000000-0000-4000-8000-000000000001"
        const val VERIFIER_HEX = "515d5b404fcf3ec1d0e2a8a22b9335dbb7e10d7285b2d14f8695accc96eea473"
        val SECRET_BYTES = ByteArray(32) { it.toByte() }
        val SECRET = Base64.getUrlEncoder().withoutPadding().encodeToString(SECRET_BYTES)
    }
}
