package me.manga.kira.backend.security

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.OctetSequenceKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.user.domain.User
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Issues HS256 access tokens via Nimbus (PLAN §6). Claims: `sub` = user UUID, `email`, `role`,
 * `iss = kira.security.issuer`, `aud = [kira.security.audience]`, `iat`, `exp = iat + access-token-ttl`.
 * A static `kid` header is emitted from day one as the rotation seam (single active key in v1) — the
 * signing JWK carries the same `kid` so Nimbus's key selection matches the header.
 *
 * The `role` claim is diagnostic/client-convenience ONLY — authorization is always derived from the
 * DB role by [DbUserJwtAuthenticationConverter] (PLAN §6), never trusted from the token.
 */
@Service
class JwtService(
    keyProvider: JwtKeyProvider,
    private val properties: KiraSecurityProperties,
) {
    // The signing JWK carries the kid, so a JwsHeader specifying that kid selects it deterministically.
    private val encoder: NimbusJwtEncoder =
        NimbusJwtEncoder(
            ImmutableJWKSet<SecurityContext>(
                JWKSet(
                    OctetSequenceKey
                        .Builder(keyProvider.secretKey.encoded)
                        .keyID(KEY_ID)
                        .algorithm(JWSAlgorithm.HS256)
                        .build(),
                ),
            ),
        )

    /** Mint a signed access token for [user]. Returns the compact token plus its lifetime. */
    fun issue(
        user: User,
        issuedAt: Instant = Instant.now(),
    ): IssuedToken {
        val expiresAt = issuedAt.plus(properties.accessTokenTtl)
        val header = JwsHeader.with(MacAlgorithm.HS256).keyId(KEY_ID).build()
        val claims =
            JwtClaimsSet
                .builder()
                .issuer(properties.issuer)
                .audience(listOf(properties.audience))
                .subject(user.id.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(CLAIM_EMAIL, user.email)
                .claim(CLAIM_ROLE, user.role.name)
                .build()
        val token = encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
        return IssuedToken(value = token, expiresInSeconds = properties.accessTokenTtl.seconds)
    }

    companion object {
        const val KEY_ID = "kira-hs256-1"
        const val CLAIM_EMAIL = "email"
        const val CLAIM_ROLE = "role"
    }
}

/** A minted access token and its lifetime in seconds (PLAN §4.2 login response). */
data class IssuedToken(
    val value: String,
    val expiresInSeconds: Long,
)
