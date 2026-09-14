package me.manga.kira.backend.security

import me.manga.kira.backend.user.domain.UserRepository
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import java.util.UUID

/**
 * The DB-backed `jwtAuthenticationConverter` registered on `oauth2ResourceServer { jwt {} }` (PLAN
 * §6, Appendix B #8). Spring invokes it for **every** request that presents a bearer token, on every
 * protected endpoint — this is the enforcement point (a controller argument resolver would run only
 * when a handler injects it, so it is NOT sufficient).
 *
 * After standard JWT verification (signature/exp/nbf/issuer/audience by the decoder) it:
 *  1. loads the user by `sub` (indexed PK read);
 *  2. rejects a missing OR `enabled = false` user with [InvalidBearerTokenException] → the entry
 *     point returns **401** (disabling a user is effective on their very next authentication check);
 *  3. requires a raw canonical string `credential_version` equal to the current DB generation,
 *     rejecting old/legacy tokens after password reset with the same generic **401**;
 *  4. derives authorities from the **DB role**, not the token claim (a stale role claim can never
 *     grant outdated access; a server-side role change takes effect on the next request);
 *  5. exposes the loaded [AuthenticatedUser] as the principal (so [CurrentUser] is a context read).
 */
class DbUserJwtAuthenticationConverter(private val users: UserRepository) : Converter<Jwt, AbstractAuthenticationToken> {

    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val subject = jwt.subject ?: throw InvalidBearerTokenException("Token is missing a subject.")
        val userId =
            try {
                UUID.fromString(subject)
            } catch (ex: IllegalArgumentException) {
                throw InvalidBearerTokenException("Token subject is not a valid user id.")
            }
        // Unknown/disabled users and revoked or malformed credentials share a generic failure.
        val user = users.findById(userId) ?: throw InvalidBearerTokenException(REJECTED_MESSAGE)
        if (!user.enabled) throw InvalidBearerTokenException(REJECTED_MESSAGE)

        // Raw access is deliberate: getClaimAsString coerces numbers and other wrong-typed claims.
        // Equality to the nonnegative Long's canonical string also rejects signs, padding, overflow,
        // non-ASCII digits and legacy absence without a parser, truncation or a missing->zero fallback.
        val claimedVersion = jwt.claims[JwtService.CLAIM_CREDENTIAL_VERSION] as? String
        if (user.credentialVersion < 0 || claimedVersion != user.credentialVersion.toString()) {
            throw InvalidBearerTokenException(REJECTED_MESSAGE)
        }

        val authorities = listOf(SimpleGrantedAuthority(Roles.authority(user.role)))
        return BearerUserAuthenticationToken(jwt, AuthenticatedUser.from(user), authorities)
    }

    private companion object {
        const val REJECTED_MESSAGE = "The token is not valid for this account."
    }
}
