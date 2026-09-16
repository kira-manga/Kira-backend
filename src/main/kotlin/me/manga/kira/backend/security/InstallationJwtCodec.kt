package me.manga.kira.backend.security

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.OctetSequenceKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * Dormant crypto only. A verified token is NOT an authenticated principal, database-state check,
 * current-mode decision or admission result. No bean, route, session bridge or remote lookup.
 */
internal class InstallationJwtCodec(private val keys: InstallationJwtKeyRing, private val clock: Clock) {
    private val encoder =
        NimbusJwtEncoder(
            ImmutableJWKSet<SecurityContext>(
                JWKSet(
                    OctetSequenceKey
                        .Builder(checkNotNull(keys.key(keys.activeKeyId)).encoded)
                        .keyID(keys.activeKeyId)
                        .algorithm(JWSAlgorithm.HS256)
                        .build(),
                ),
            ),
        )
    private val decoders = keys.verificationKeyIds().associateWith { keyId ->
        NimbusJwtDecoder
            .withSecretKey(checkNotNull(keys.key(keyId)))
            .macAlgorithm(MacAlgorithm.HS256)
            .validateType(false)
            .build()
            .also { decoder ->
                // Disabling Nimbus's JWT/null type default is safe ONLY with this mandatory,
                // exact replacement. These private decoders cannot bypass the bounded parser.
                decoder.setJwtValidator { jwt ->
                    val validHeader = jwt.headers["typ"] == TYPE && jwt.headers["alg"] == "HS256" && jwt.headers["kid"] == keyId
                    if (validHeader && jwt.getClaimAsString("iss") == ISSUER && jwt.audience == listOf(AUDIENCE)) {
                        OAuth2TokenValidatorResult.success()
                    } else {
                        OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "Invalid installation token", null))
                    }
                }
            }
    }

    /**
     * The supplied time is normalized once for this cryptographic result, never sampled from the
     * verifier clock. This does NOT decide a future HTTP response's database-issuedAt mapping.
     */
    fun issue(installation: ScopedInstallationId, credentialVersion: Long, issuedAt: Instant): IssuedInstallationJwt {
        require(credentialVersion > 0) { "Invalid installation JWT issuance" }
        val normalized = issuedAt.truncatedTo(ChronoUnit.SECONDS)
        val expires = validExpiry(normalized)
        val claims =
            JwtClaimsSet
                .builder()
                .issuer(ISSUER)
                .audience(listOf(AUDIENCE))
                .subject(installation.id.toString())
                .claim("role", ROLE)
                .claim("credentialVersion", credentialVersion)
                .claim("dataScopeId", installation.scope.id.toString())
                .issuedAt(normalized)
                .notBefore(normalized)
                .expiresAt(expires)
                .id(UUID.randomUUID().toString())
                .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).type(TYPE).keyId(keys.activeKeyId).build()
        val encoded = encode(JwtEncoderParameters.from(header, claims))
        check(encoded.length <= MAX_COMPACT_BYTES) { "Invalid installation JWT issuance" }
        return IssuedInstallationJwt(encoded, normalized, expires)
    }

    fun verify(compact: String): VerifiedInstallationJwt {
        val parsed = InstallationJwtParser.parse(compact)
        val decoder = decoders[parsed.keyId] ?: rejectInstallationToken()
        verifySignature(decoder, compact)
        validateTime(parsed.claims)
        return parsed.claims
    }

    @Suppress("SwallowedException") // No underlying JOSE exception or token enters the public failure.
    private fun encode(parameters: JwtEncoderParameters): String = try {
        encoder.encode(parameters).tokenValue
    } catch (ex: JwtException) {
        error("Installation JWT signing failed")
    }

    @Suppress("SwallowedException") // Discard library exceptions that may include untrusted token text.
    private fun verifySignature(decoder: NimbusJwtDecoder, compact: String) {
        try {
            decoder.decode(compact)
        } catch (ex: JwtException) {
            rejectInstallationToken()
        }
    }

    @Suppress("SwallowedException") // Overflow is a bounded refusal, never a host-clock substitution.
    private fun validateTime(claims: VerifiedInstallationJwt) {
        try {
            val now = clock.instant()
            if (claims.issuedAt.isAfter(now.plusSeconds(SKEW_SECONDS)) || !claims.expiresAt.isAfter(now.minusSeconds(SKEW_SECONDS))) {
                rejectInstallationToken()
            }
        } catch (ex: DateTimeException) {
            rejectInstallationToken()
        } catch (ex: ArithmeticException) {
            rejectInstallationToken()
        }
    }

    companion object {
        const val TYPE = "kira-installation+jwt"
        const val ISSUER = "kira-installation"
        const val AUDIENCE = "kira-complaints"
        const val ROLE = "ROLE_INSTALLATION"
        const val TTL_SECONDS = 900L
        const val SKEW_SECONDS = 60L
        const val MAX_COMPACT_BYTES = 4096
    }
}

/** Normalized cryptographic issuance only; not the unimplemented session-response DTO. */
internal class IssuedInstallationJwt(val value: String, val issuedAt: Instant, val expiresAt: Instant) {
    val expiresInSeconds: Long get() = InstallationJwtCodec.TTL_SECONDS

    override fun toString(): String = "IssuedInstallationJwt(redacted)"
}

/** Immutable signed facts only. Even LIVE syntax does not prove that an installation is ACTIVE. */
internal class VerifiedInstallationJwt(
    val installation: ScopedInstallationId,
    val credentialVersion: Long,
    val issuedAt: Instant,
    val notBefore: Instant,
    val expiresAt: Instant,
    val jwtId: UUID,
    val keyId: String,
) {
    override fun toString(): String = "VerifiedInstallationJwt(redacted)"
}

internal class InstallationJwtRejectedException : RuntimeException("Invalid installation token")

private class ParsedInstallationJwt(val keyId: String, val claims: VerifiedInstallationJwt)

/** Strict structural pass before Nimbus can coerce NumericDates, duplicate keys or header values. */
private object InstallationJwtParser {
    private val mapper =
        ObjectMapper(
            JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(
                    StreamReadConstraints.builder().maxNestingDepth(3).maxStringLength(4096).maxNumberLength(20).build(),
                )
                .build(),
        ).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val base64 = Base64.getUrlDecoder()
    private val canonicalBase64 = Base64.getUrlEncoder().withoutPadding()
    private val headers = setOf("alg", "typ", "kid")
    private val claimNames = setOf("iss", "aud", "sub", "role", "credentialVersion", "dataScopeId", "iat", "nbf", "exp", "jti")

    fun parse(compact: String): ParsedInstallationJwt {
        if (compact.length !in 1..InstallationJwtCodec.MAX_COMPACT_BYTES || compact.any { it.code !in 33..126 }) rejectInstallationToken()
        val parts = compact.split('.')
        if (parts.size != 3 || parts.any { it.isEmpty() }) rejectInstallationToken()
        val header = objectNode(decode(parts[0]), headers)
        val payload = objectNode(decode(parts[1]), claimNames)
        if (decode(parts[2]).size != 32) rejectInstallationToken()
        val keyId = string(header, "kid")
        if (!validInstallationKeyId(keyId) || string(header, "alg") != "HS256" || string(header, "typ") != InstallationJwtCodec.TYPE) {
            rejectInstallationToken()
        }
        return ParsedInstallationJwt(keyId, claims(payload, keyId))
    }

    private fun claims(node: JsonNode, keyId: String): VerifiedInstallationJwt {
        if (string(node, "iss") != InstallationJwtCodec.ISSUER || string(node, "role") != InstallationJwtCodec.ROLE || !validAudience(node["aud"])) {
            rejectInstallationToken()
        }
        val version = integer(node, "credentialVersion")
        if (version <= 0) rejectInstallationToken()
        val issuedAt = instant(node, "iat")
        val notBefore = instant(node, "nbf")
        val expiresAt = instant(node, "exp")
        if (notBefore != issuedAt || expiresAt != validExpiry(issuedAt)) rejectInstallationToken()
        return VerifiedInstallationJwt(installation(node), version, issuedAt, notBefore, expiresAt, jwtId(node), keyId)
    }

    private fun validAudience(node: JsonNode): Boolean = (node.isTextual && node.textValue() == InstallationJwtCodec.AUDIENCE) ||
        (node.isArray && node.size() == 1 && node[0].isTextual && node[0].textValue() == InstallationJwtCodec.AUDIENCE)

    @Suppress("SwallowedException")
    private fun installation(node: JsonNode): ScopedInstallationId = try {
        ScopedInstallationId(ComplaintIdentifiers.installationId(string(node, "sub")), ComplaintIdentifiers.dataScope(string(node, "dataScopeId")))
    } catch (ex: ComplaintValidationException) {
        rejectInstallationToken()
    }

    @Suppress("SwallowedException")
    private fun jwtId(node: JsonNode): UUID = try {
        ComplaintIdentifiers.installationId(string(node, "jti"))
    } catch (ex: ComplaintValidationException) {
        rejectInstallationToken()
    }

    private fun decode(part: String): ByteArray = try {
        val decoded = base64.decode(part)
        if (canonicalBase64.encodeToString(decoded) != part) rejectInstallationToken()
        decoded
    } catch (ex: IllegalArgumentException) {
        rejectInstallationToken()
    }

    @Suppress("SwallowedException") // Includes depth/number constraints as well as malformed JSON.
    private fun objectNode(bytes: ByteArray, fields: Set<String>): JsonNode = try {
        val node = mapper.readTree(bytes)
        if (node == null || !node.isObject || node.fieldNames().asSequence().toSet() != fields) rejectInstallationToken()
        node
    } catch (ex: JsonProcessingException) {
        rejectInstallationToken()
    }

    private fun string(node: JsonNode, name: String): String {
        val field = node[name]
        if (!field.isTextual) rejectInstallationToken()
        return field.textValue()
    }

    private fun integer(node: JsonNode, name: String): Long {
        val field = node[name]
        if (!field.isIntegralNumber || !field.canConvertToLong()) rejectInstallationToken()
        return field.longValue()
    }

    @Suppress("SwallowedException")
    private fun instant(node: JsonNode, name: String): Instant = try {
        Instant.ofEpochSecond(integer(node, name)).also { it.toEpochMilli() }
    } catch (ex: DateTimeException) {
        rejectInstallationToken()
    } catch (ex: ArithmeticException) {
        rejectInstallationToken()
    }
}

@Suppress("SwallowedException")
private fun validExpiry(issuedAt: Instant): Instant = try {
    issuedAt.toEpochMilli()
    issuedAt.plusSeconds(InstallationJwtCodec.TTL_SECONDS).also { it.toEpochMilli() }
} catch (ex: DateTimeException) {
    rejectInstallationToken()
} catch (ex: ArithmeticException) {
    rejectInstallationToken()
}

private fun rejectInstallationToken(): Nothing = throw InstallationJwtRejectedException()
