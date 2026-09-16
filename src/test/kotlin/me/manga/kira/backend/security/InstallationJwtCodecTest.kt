package me.manga.kira.backend.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.SignedJWT
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.support.JwtTestSupport
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.security.oauth2.jwt.JwtException
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

class InstallationJwtCodecTest {
    private val now = Instant.parse("2026-09-16T12:00:00.123456789Z")
    private val installation = ScopedInstallationId(UUID.fromString("a8f7ae56-7775-4cd0-8ca6-d8fa5aa86214"), ComplaintDataScope.LIVE)
    private val oldKey = ByteArray(32) { (it + 1).toByte() }
    private val newKey = ByteArray(32) { (it + 51).toByte() }
    private val mapper = ObjectMapper()
    private val codec = codec()

    @Test
    fun `real signing normalizes explicit issuance once and round trips immutable LIVE and TEST syntax`() {
        val issued = codec.issue(installation, Long.MAX_VALUE, now)
        val verified = codec.verify(issued.value)
        assertEquals(now.truncatedTo(ChronoUnit.SECONDS), issued.issuedAt)
        assertEquals(issued.issuedAt.plusSeconds(900), issued.expiresAt)
        assertEquals(900L, issued.expiresInSeconds)
        assertEquals(installation, verified.installation)
        assertEquals(Long.MAX_VALUE, verified.credentialVersion)
        assertEquals(issued.issuedAt, verified.issuedAt)
        assertEquals(issued.issuedAt, verified.notBefore)
        assertEquals(issued.expiresAt, verified.expiresAt)
        assertEquals(4, verified.jwtId.version())
        assertEquals(2, verified.jwtId.variant())
        val decoded = SignedJWT.parse(issued.value)
        assertEquals(JWSAlgorithm.HS256, decoded.header.algorithm)
        assertEquals(InstallationJwtCodec.TYPE, decoded.header.type.toString())
        assertEquals("install-old", decoded.header.keyID)
        assertEquals(InstallationJwtCodec.ROLE, decoded.jwtClaimsSet.getStringClaim("role"))

        val testScope = ComplaintDataScope.of(UUID.fromString("a8f7ae56-7775-4cd0-8ca6-d8fa5aa86215"))
        val testInstallation = ScopedInstallationId(installation.id, testScope)
        val second = codec.verify(codec.issue(testInstallation, 1, now).value)
        assertEquals(testInstallation, second.installation) // Syntax only; no run-ledger or current-mode authority exists here.
        assertNotEquals(verified.jwtId, second.jwtId)
        assertThrows(IllegalArgumentException::class.java) { codec.issue(installation, 0, now) }
        assertThrows(InstallationJwtRejectedException::class.java) { codec.issue(installation, 1, Instant.MAX) }
    }

    @Test
    fun `mandatory headers exact family and allowlisted key cannot be replaced or coerced`() {
        val invalidHeaders = mutableListOf<String>()
        listOf("alg", "typ", "kid").forEach { name -> invalidHeaders += header { it.remove(name) } }
        listOf(null, 1, true, emptyList<String>(), mapOf("value" to "install-old")).forEach { wrong ->
            listOf("typ", "kid", "alg").forEach { name -> invalidHeaders += header { it[name] = wrong } }
        }
        listOf("JWT", "jwt", "kira-user+jwt", "").forEach { wrong -> invalidHeaders += header { it["typ"] = wrong } }
        listOf("unknown", "x".repeat(65), "../key", "install-old\n").forEach { wrong -> invalidHeaders += header { it["kid"] = wrong } }
        listOf("none", "HS384", "HS512", "RS256").forEach { wrong -> invalidHeaders += header { it["alg"] = wrong } }
        invalidHeaders += header { it["crit"] = listOf("b64") }
        invalidHeaders += header { it["jku"] = "https://invalid.example/never-fetch" }
        invalidHeaders.forEach { rejected(compact(header = it)) }
        rejected(JwtTestSupport.tamperSignature(codec.issue(installation, 1, now).value))
        rejected(compact(key = newKey))
    }

    @Test
    fun `all claims are required and strictly typed before Nimbus can normalize them`() {
        claimsMap().keys.forEach { name -> rejected(compact(claims = claims { it.remove(name) })) }
        listOf("iss", "sub", "role", "dataScopeId", "jti").forEach { name ->
            listOf(null, 1, true, emptyList<String>(), mapOf("value" to "not-a-string")).forEach { wrong ->
                rejected(compact(claims = claims { it[name] = wrong }))
            }
        }
        listOf("credentialVersion", "iat", "nbf", "exp").forEach { name ->
            listOf(null, "1", true, 1.0, 1.5, BigInteger("9223372036854775808")).forEach { wrong ->
                rejected(compact(claims = claims { it[name] = wrong }))
            }
        }
        listOf(0L, -1L).forEach { wrong -> rejected(compact(claims = claims { it["credentialVersion"] = wrong })) }
        listOf(null, 1, true, emptyList<String>(), listOf(InstallationJwtCodec.AUDIENCE, "other"), listOf(1)).forEach { wrong ->
            rejected(compact(claims = claims { it["aud"] = wrong }))
        }
        rejected(compact(claims = claims { it["unknown"] = "not-accepted" }))
        assertEquals(installation, codec.verify(compact(claims = claims { it["aud"] = InstallationJwtCodec.AUDIENCE })).installation)
    }

    @Test
    fun `signed wrong family UUID variants scope version and lifetime remain refusals`() {
        listOf("iss", "aud", "role").forEach { name -> rejected(compact(claims = claims { it[name] = "USER" })) }
        listOf("sub", "jti").forEach { name ->
            listOf(installation.id.toString().uppercase(), "1-1-1-1-1", "00000000-0000-0000-0000-000000000000", "not-an-id").forEach { wrong ->
                rejected(compact(claims = claims { it[name] = wrong }))
            }
        }
        listOf("1-1-1-1-1", "a8f7ae56-7775-1cd0-8ca6-d8fa5aa86215", "A8F7AE56-7775-4CD0-8CA6-D8FA5AA86215").forEach { wrong ->
            rejected(compact(claims = claims { it["dataScopeId"] = wrong }))
        }
        rejected(compact(claims = claims { it["nbf"] = now.epochSecond + 1 }))
        listOf(-1, 0, 899, 901).forEach { ttl -> rejected(compact(claims = claims { it["exp"] = now.epochSecond + ttl })) }
        rejected(compact(claims = claims { it["iat"] = Long.MAX_VALUE }))
    }

    @Test
    fun `explicit clock accepts exact future skew but rejects expired equality and one-over`() {
        val issued = codec.issue(installation, 1, now)
        val earlyBoundary = issued.issuedAt.minusSeconds(60)
        assertEquals(installation, codec(at = earlyBoundary).verify(issued.value).installation)
        rejected(issued.value, codec(at = earlyBoundary.minusNanos(1)))
        val expiryBoundary = issued.expiresAt.plusSeconds(60)
        assertEquals(installation, codec(at = expiryBoundary.minusNanos(1)).verify(issued.value).installation)
        rejected(issued.value, codec(at = expiryBoundary))
        rejected(issued.value, codec(at = expiryBoundary.plusNanos(1)))
        rejected(issued.value, codec(at = Instant.MAX))
        // Issuance uses its explicit supplied instant, not the verifier's clock.
        assertEquals(issued.issuedAt, codec(at = now.plusSeconds(1000)).issue(installation, 1, now).issuedAt)
    }

    @Test
    fun `strict shallow JSON compact encoding and exact four KiB cap reject bounded malformed tokens`() {
        rejected(compact(header = header().dropLast(1) + ",\"kid\":\"install-old\"}"))
        rejected(compact(header = header().dropLast(1) + ",\"ki\\u0064\":\"install-old\"}"))
        rejected(compact(claims = claims().dropLast(1) + ",\"credentialVersion\":1}"))
        rejected(compact(claims = claims() + " {}"))
        rejected(compact(header = header() + " {}"))
        rejected(compact(claims = "{\"iss\":{\"nested\":{\"tooDeep\":{\"secret\":\"never-echo\"}}}}"))
        rejected(compact(claims = claims().replace("\"credentialVersion\":7", "\"credentialVersion\":${"9".repeat(21)}")))
        rejected(compact(claims = "not-json-never-echo"))
        listOf("", "a.b", "a.b.c.d", "a..c", "a.b.c\n", "é.b.c").forEach { rejected(it) }
        val valid = compact()
        val signatureLast = URL_ALPHABET.indexOf(valid.last())
        rejected(valid.dropLast(1) + URL_ALPHABET[signatureLast xor 1]) // Same decoded MAC, nonzero unused base64 pad bits.
        rejected(valid + "=")
        val exact = compactOfLength(4096)
        assertEquals(4096, exact.length)
        assertEquals(installation, codec.verify(exact).installation)
        rejected(compactOfLength(4097))
    }

    @Test
    fun `real mixed replica signing predeployment activation rollback and verifier removal follow the ring`() {
        val before = codec()
        val predeploy = codec(includeNew = true)
        val activated = codec(active = "install-new", includeNew = true)
        val rollback = codec(includeNew = true)
        val removed = codec(active = "install-new", includeNew = true, includeOld = false)
        val old = before.issue(installation, 1, now).value
        val fresh = activated.issue(installation, 1, now).value
        listOf(predeploy, activated, rollback).forEach { replica ->
            assertEquals("install-old", replica.verify(old).keyId)
            assertEquals("install-new", replica.verify(fresh).keyId)
        }
        assertEquals("install-old", predeploy.verify(predeploy.issue(installation, 1, now).value).keyId)
        assertEquals("install-old", rollback.verify(rollback.issue(installation, 1, now).value).keyId)
        rejected(fresh, before)
        rejected(old, removed)
        assertEquals("install-new", removed.verify(fresh).keyId)
    }

    @Test
    fun `real user service and production user decoder reject cross-family tokens without changing their own contract`() {
        val properties = KiraSecurityProperties(jwtSecret = JwtTestSupport.TEST_JWT_SECRET_BASE64)
        val provider = JwtKeyProvider(properties)
        val liveClock = Clock.systemUTC()
        val service = JwtService(provider, properties, liveClock)
        val user = User(UUID.randomUUID(), "test@example.com", "{bcrypt}unused", Role.USER, true, liveClock.instant(), liveClock.instant())
        val userToken = service.issue(user)
        val userDecoder = SecurityConfig(MockEnvironment()).jwtDecoder(provider, properties)
        assertEquals(user.id.toString(), userDecoder.decode(userToken.value).subject)
        val installationCodec = codec(at = liveClock.instant())
        rejected(userToken.value, installationCodec)
        val installationToken = installationCodec.issue(installation, 1, liveClock.instant())
        assertEquals(installation, installationCodec.verify(installationToken.value).installation)
        assertThrows(JwtException::class.java) { userDecoder.decode(installationToken.value) }
    }

    @Test
    fun `crypto results and all malformed-token exceptions contain neither tokens nor input fragments`() {
        val issued = codec.issue(installation, 1, now)
        val verified = codec.verify(issued.value)
        val rendered = listOf(issued, verified, codec).joinToString()
        listOf(issued.value, installation.id.toString(), Base64.getEncoder().encodeToString(oldKey)).forEach { sensitive ->
            assertFalse(rendered.contains(sensitive))
        }
        val poisoned = compact(claims = "{\"secret\":\"private-input-never-echo\"")
        val failure = rejected(poisoned)
        assertFalse(failure.toString().contains(poisoned))
        assertFalse(failure.toString().contains("private-input-never-echo"))
    }

    private fun codec(at: Instant = now, active: String = "install-old", includeNew: Boolean = false, includeOld: Boolean = true): InstallationJwtCodec {
        val keys = buildList {
            if (includeOld) add(InstallationJwtKeyMaterial("install-old", oldKey))
            if (includeNew) add(InstallationJwtKeyMaterial("install-new", newKey))
        }
        val forbidden = InstallationJwtForbiddenFamily(
            JwtTestSupport.ISSUER,
            JwtTestSupport.AUDIENCE,
            listOf(InstallationJwtKeyMaterial(JwtTestSupport.KEY_ID, Base64.getDecoder().decode(JwtTestSupport.TEST_JWT_SECRET_BASE64))),
        )
        return InstallationJwtCodec(InstallationJwtKeyRing(active, keys, forbidden), Clock.fixed(at, ZoneOffset.UTC))
    }

    private fun header(change: (MutableMap<String, Any?>) -> Unit = {}): String = mapper.writeValueAsString(
        linkedMapOf<String, Any?>("alg" to "HS256", "typ" to InstallationJwtCodec.TYPE, "kid" to "install-old").also(change),
    )

    private fun claims(change: (MutableMap<String, Any?>) -> Unit = {}): String = mapper.writeValueAsString(claimsMap().also(change))

    private fun claimsMap(): MutableMap<String, Any?> = linkedMapOf(
        "iss" to InstallationJwtCodec.ISSUER,
        "aud" to listOf(InstallationJwtCodec.AUDIENCE),
        "sub" to installation.id.toString(),
        "role" to InstallationJwtCodec.ROLE,
        "credentialVersion" to 7L,
        "dataScopeId" to installation.scope.id.toString(),
        "iat" to now.epochSecond,
        "nbf" to now.epochSecond,
        "exp" to now.epochSecond + 900,
        "jti" to "a8f7ae56-7775-4cd0-8ca6-d8fa5aa86216",
    )

    /** Nimbus signs the exact raw JSON bytes, including intentionally invalid/duplicate structures. */
    private fun compact(header: String = header(), claims: String = claims(), key: ByteArray = oldKey): String {
        val input = "${base64(header)}.${base64(claims)}"
        val signature = MACSigner(key).sign(JWSHeader.Builder(JWSAlgorithm.HS256).build(), input.toByteArray(Charsets.US_ASCII))
        return "$input.$signature"
    }

    private fun compactOfLength(target: Int): String {
        val payload = claims()
        for (headerPadding in 0..2) {
            val paddedHeader = header() + " ".repeat(headerPadding)
            val encodedPayloadLength = target - base64(paddedHeader).length - 45
            val decodedPayloadLength = encodedPayloadLength * 3 / 4
            if ((decodedPayloadLength * 4 + 2) / 3 != encodedPayloadLength) continue
            return compact(paddedHeader, payload + " ".repeat(decodedPayloadLength - payload.toByteArray().size))
        }
        error("No exact compact fixture length")
    }

    private fun base64(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun rejected(value: String, verifier: InstallationJwtCodec = codec): InstallationJwtRejectedException {
        val failure = assertThrows(InstallationJwtRejectedException::class.java) { verifier.verify(value) }
        assertEquals("Invalid installation token", failure.message)
        assertNull(failure.cause)
        return failure
    }

    companion object {
        private const val URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    }
}
