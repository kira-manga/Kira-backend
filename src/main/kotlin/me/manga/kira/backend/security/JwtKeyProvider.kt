package me.manga.kira.backend.security

import me.manga.kira.backend.config.KiraSecurityProperties
import org.springframework.stereotype.Component
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Owns the JWT signing key and its **fail-fast validation** (PLAN §6): `kira.security.jwt-secret`
 * must be present, valid Base64, and decode to **≥ 256 bits (32 bytes)** — the HS256 minimum. A
 * missing/short/malformed secret throws at bean construction, so the application refuses to start
 * with an insecure key (the dev profile supplies a documented, clearly-insecure default; tests
 * supply a throwaway; prod supplies `KIRA_JWT_SECRET` from the environment).
 *
 * The single [SecretKey] instance is shared by both the [JwtService] encoder and the resource-server
 * decoder so issuing and verifying always use the same material.
 */
@Component
class JwtKeyProvider(properties: KiraSecurityProperties) {

    val secretKey: SecretKey = deriveKey(properties.jwtSecret)

    companion object {
        /** HS256 requires a key of at least 256 bits. */
        const val MIN_KEY_BYTES = 32

        /** JCA algorithm name for the HMAC-SHA256 key backing HS256. */
        const val MAC_ALGORITHM = "HmacSHA256"

        private fun deriveKey(configured: String?): SecretKey {
            val secret =
                configured?.takeIf { it.isNotBlank() }
                    ?: error(
                        "kira.security.jwt-secret is not set. Provide KIRA_JWT_SECRET as a Base64 value " +
                            "that decodes to >= 256 bits (generate: openssl rand -base64 32). The dev " +
                            "profile ships a clearly-insecure default; production must set the env var.",
                    )
            val decoded =
                try {
                    Base64.getDecoder().decode(secret.trim())
                } catch (ex: IllegalArgumentException) {
                    error("kira.security.jwt-secret is not valid Base64 (openssl rand -base64 32): ${ex.message}")
                }
            require(decoded.size >= MIN_KEY_BYTES) {
                "kira.security.jwt-secret decodes to ${decoded.size * 8} bits; HS256 requires >= 256 bits " +
                    "(>= $MIN_KEY_BYTES bytes). Generate one with: openssl rand -base64 32."
            }
            return SecretKeySpec(decoded, MAC_ALGORITHM)
        }
    }
}
