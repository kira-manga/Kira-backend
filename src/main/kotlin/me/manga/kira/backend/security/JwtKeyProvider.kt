package me.manga.kira.backend.security

import me.manga.kira.backend.config.KiraSecurityProperties
import org.springframework.beans.factory.annotation.Autowired
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
 * The explicit dormant [fromAcquired] factory does not change the Spring/env constructor selection.
 */
@Component
class JwtKeyProvider private constructor(val secretKey: SecretKey, private val versionBound: VersionBoundInputs?) {

    @Autowired
    constructor(properties: KiraSecurityProperties) : this(deriveKey(properties.jwtSecret), null)

    /** The legacy path is unchanged. An acquired owner cannot be paired with different effective JWT settings. */
    internal fun requireMatchingConfiguration(properties: KiraSecurityProperties) {
        require(versionBound == null || versionBound.matches(properties)) { INVALID_VERSION_BOUND_CONFIGURATION }
    }

    internal fun immutableVersionBinding(): VersionedSecretBinding = requireVersionBound().binding

    /** The actual signer/verifier owns exactly one key. No caller supplies another retained-key list or key ID. */
    internal fun installationUserFamily(): InstallationJwtForbiddenFamily {
        val inputs = requireVersionBound()
        val bytes = secretKey.encoded
        return try {
            InstallationJwtForbiddenFamily(inputs.issuer, inputs.audience, listOf(InstallationJwtKeyMaterial(JwtService.KEY_ID, bytes)))
        } finally {
            bytes.fill(0)
        }
    }

    private fun requireVersionBound(): VersionBoundInputs = requireNotNull(versionBound) { INVALID_VERSION_BOUND_CONFIGURATION }

    override fun toString(): String = "JwtKeyProvider(redacted)"

    companion object {
        /** HS256 requires a key of at least 256 bits. */
        const val MIN_KEY_BYTES = 32

        /** JCA algorithm name for the HMAC-SHA256 key backing HS256. */
        const val MAC_ALGORITHM = "HmacSHA256"

        /**
         * One copied acquisition, not a lookup or bean. This bound profile uses the existing 32–128-byte
         * installation-separation range; the legacy environment constructor keeps its >=32-byte rule.
         * The existing fixed wire kid is also the logical ID. No independent property secret is accepted.
         */
        internal fun fromAcquired(secret: AcquiredVersionedSecret, properties: KiraSecurityProperties): JwtKeyProvider {
            val binding = secret.descriptor
            require(
                binding.family == SecretMaterialFamily.USER_ADMIN_JWT && binding.purpose == SecretMaterialPurpose.HMAC_SHA256 &&
                    binding.logicalKeyId == JwtService.KEY_ID && properties.jwtSecret == null,
            ) { INVALID_VERSION_BOUND_CONFIGURATION }
            val inputs = VersionBoundInputs(binding, properties)
            return secret.useMaterial { material ->
                require(material.size in MIN_KEY_BYTES..128) { INVALID_VERSION_BOUND_CONFIGURATION }
                JwtKeyProvider(SecretKeySpec(material, MAC_ALGORITHM), inputs)
            }
        }

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

        private const val INVALID_VERSION_BOUND_CONFIGURATION = "Invalid version-bound user JWT configuration"
    }

    /** Only the four immutable scalars actually consumed by JwtService / SecurityConfig.jwtDecoder. */
    private class VersionBoundInputs(val binding: VersionedSecretBinding, properties: KiraSecurityProperties) {
        val issuer = properties.issuer
        val audience = properties.audience
        private val ttl = properties.accessTokenTtl
        private val skew = properties.clockSkew

        fun matches(properties: KiraSecurityProperties): Boolean = issuer == properties.issuer && audience == properties.audience &&
            ttl == properties.accessTokenTtl && skew == properties.clockSkew
    }
}
