package me.manga.kira.backend.complaint.infrastructure

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.rejectAdminRead
import me.manga.kira.backend.security.JwtService
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import java.io.IOException
import java.time.DateTimeException
import java.time.Duration
import java.util.Base64
import java.util.UUID

/** Shared bounded normal-JWT decoding only. No read handoff, DB role, ingress or write authority escapes. */
internal class ComplaintAdminJwtIdentityDecoder(
    private val testScope: ComplaintDataScope,
    private val userDecoder: JwtDecoder,
    private val userClockSkew: Duration,
) {
    init {
        require(testScope.testOnly && !userClockSkew.isNegative && userClockSkew <= Duration.ofSeconds(60)) { "Invalid TEST Admin identity composition." }
    }

    @Suppress("SwallowedException")
    fun decode(bearer: String): ComplaintAdminReadIdentity = try {
        requireConnectionFree()
        boundedToken(bearer)
        val jwt = userDecoder.decode(bearer)
        val subject = jwt.claims["sub"] as? String ?: unauthorized()
        if (!UUID_TEXT.matches(subject)) unauthorized()
        val actor = UUID.fromString(subject)
        val version = jwt.claims[JwtService.CLAIM_CREDENTIAL_VERSION] as? String ?: unauthorized()
        val generation = version.toLongOrNull() ?: unauthorized()
        if (generation < 0 || version != generation.toString()) unauthorized()
        // Composition supplies the same skew used by the qualified normal decoder; no token role grants anything.
        val until = (jwt.expiresAt ?: unauthorized()).plus(userClockSkew)
        ComplaintAdminReadIdentity(actor, testScope, version, jwt.notBefore?.minus(userClockSkew), until)
    } catch (failure: JwtException) {
        unauthorized()
    } catch (failure: IOException) {
        unauthorized()
    } catch (failure: IllegalArgumentException) {
        unauthorized()
    } catch (failure: DateTimeException) {
        unauthorized()
    } catch (failure: ArithmeticException) {
        unauthorized()
    }

    private fun boundedToken(value: String) {
        if (value.length !in 1..4096 || !TOKEN.matches(value)) unauthorized()
        val parts = value.split('.')
        val header = decodePart(parts[0], 512)
        try {
            TOKEN_JSON.createParser(header).use { json ->
                if (json.nextToken() != JsonToken.START_OBJECT) unauthorized()
                val fields = mutableMapOf<String, String>()
                while (json.nextToken() != JsonToken.END_OBJECT) {
                    if (json.currentToken != JsonToken.FIELD_NAME || fields.size == 3) unauthorized()
                    val name = json.currentName()
                    if (name !in setOf("alg", "kid", "typ") || json.nextToken() != JsonToken.VALUE_STRING) unauthorized()
                    fields[checkNotNull(name)] = json.text
                }
                if (json.nextToken() != null || fields["alg"] != "HS256" || fields["kid"] != JwtService.KEY_ID ||
                    fields["typ"]?.let { it != "JWT" } == true
                ) unauthorized()
            }
        } finally {
            header.fill(0)
        }
        val claims = decodePart(parts[1], 3072)
        try {
            // Bound malformed unauthenticated claim nesting/strings before the normal crypto decoder sees it.
            TOKEN_JSON.createParser(claims).use { json ->
                if (json.nextToken() != JsonToken.START_OBJECT) unauthorized()
                var count = 1
                var depth = 1
                while (depth > 0) {
                    val token = json.nextToken() ?: unauthorized()
                    if (++count > 128) unauthorized()
                    if (token.isStructStart) depth++
                    if (token.isStructEnd) depth--
                    if (token == JsonToken.VALUE_STRING && json.text.length > 2048) unauthorized()
                }
                if (json.nextToken() != null) unauthorized()
            }
        } finally {
            claims.fill(0)
        }
        val signature = decodePart(parts[2], 32)
        try {
            if (signature.size != 32) unauthorized()
        } finally {
            signature.fill(0)
        }
    }

    private fun decodePart(value: String, maximum: Int): ByteArray {
        if (value.length > (maximum * 4 + 2) / 3) unauthorized()
        val result = Base64.getUrlDecoder().decode(value)
        if (result.size !in 1..maximum || Base64.getUrlEncoder().withoutPadding().encodeToString(result) != value) {
            result.fill(0)
            unauthorized()
        }
        return result
    }

    override fun toString(): String = "ComplaintAdminJwtIdentityDecoder(redacted)"

    private companion object {
        val TOKEN = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
        val UUID_TEXT = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val TOKEN_JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(2048).maxNameLength(64).maxNumberLength(20).build())
            .build()

        fun unauthorized(): Nothing = rejectAdminRead(ComplaintAdminReadFailure.UNAUTHORIZED)
    }
}
