package me.manga.kira.backend.support

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * Mints HS256 tokens for tests using the SAME throwaway key as `application-test.yml`
 * ([TEST_JWT_SECRET_BASE64]). Lets `RealBearerTokenIT` (test 36) craft tokens with a wrong
 * issuer/audience/expiry — variants the production [me.manga.kira.backend.security.JwtService]
 * would never emit — and prove the REAL `NimbusJwtDecoder` rejects them (not the mocked `jwt()`
 * post-processor). This is test-only material; it is never used by production code.
 */
object JwtTestSupport {

    /** Byte-identical to `application-test.yml`'s `kira.security.jwt-secret`. */
    const val TEST_JWT_SECRET_BASE64 = "a2lyYS1iYWNrZW5kLVRFU1QtT05MWS1qd3Qtc2VjcmV0LWRvLW5vdC11c2UtaW4tcHJvZCE="

    const val ISSUER = "kira-backend"
    const val AUDIENCE = "kira-api"
    const val KEY_ID = "kira-hs256-1"

    private val secretBytes: ByteArray = Base64.getDecoder().decode(TEST_JWT_SECRET_BASE64)

    /** Mint a signed token, overriding any claim to produce valid or deliberately-invalid tokens. */
    fun mint(
        subject: UUID,
        email: String = "user@example.com",
        role: String = "USER",
        issuer: String = ISSUER,
        audience: String = AUDIENCE,
        issuedAt: Instant = Instant.now(),
        expiresAt: Instant = Instant.now().plus(Duration.ofMinutes(60)),
        keyId: String = KEY_ID,
    ): String {
        val header = JWSHeader.Builder(JWSAlgorithm.HS256).keyID(keyId).build()
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(subject.toString())
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim("email", email)
                .claim("role", role)
                .build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(MACSigner(secretBytes))
        return jwt.serialize()
    }

    /** Return [token] with its signature segment corrupted so signature verification must fail. */
    fun tamperSignature(token: String): String {
        val parts = token.split(".")
        require(parts.size == 3) { "not a compact JWS" }
        val sig = parts[2]
        val flipped = if (sig.first() == 'A') "B" + sig.substring(1) else "A" + sig.substring(1)
        return "${parts[0]}.${parts[1]}.$flipped"
    }
}
