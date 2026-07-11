package me.manga.kira.backend.config

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * `kira.security.*` — cross-cutting security configuration (PLAN §3 config/, §6).
 *
 * [jwtSecret] is intentionally nullable and un-gated **here**: the real fail-fast (Base64 that
 * decodes to ≥ 256 bits, with a documented insecure dev default) is
 * [me.manga.kira.backend.security.JwtKeyProvider]'s responsibility (PLAN §6) — so a context that
 * does not touch JWTs and the dev profile load without secrets. Do NOT hardcode a signing key here.
 *
 * [issuer]/[audience]/[clockSkew] are the Nimbus decoder-validation inputs (PLAN §6: `iss`,
 * `aud`, 60s skew). [trustForwardedHeaders]/[trustedProxies] drive the throttle's trusted
 * client-IP resolution; [throttle] bounds the in-memory throttle store (all PLAN §6).
 */
@Validated
@ConfigurationProperties(prefix = "kira.security")
data class KiraSecurityProperties(
    /** Base64 JWT signing key, bound from `KIRA_JWT_SECRET`. Validated by `JwtKeyProvider`. */
    val jwtSecret: String? = null,
    /** Access-token lifetime (PLAN §6 / Open Q3 default PT60M). */
    @field:NotNull
    val accessTokenTtl: Duration = Duration.ofMinutes(60),
    /** JWT `iss` claim, issued and validated (PLAN §6). */
    @field:NotBlank
    val issuer: String = "kira-backend",
    /** JWT `aud` claim, issued and validated (PLAN §6). */
    @field:NotBlank
    val audience: String = "kira-api",
    /** Allowed clock skew for `exp`/`nbf` validation (PLAN §6: 60s). */
    @field:NotNull
    val clockSkew: Duration = Duration.ofSeconds(60),
    /**
     * Honor `X-Forwarded-For` / `Forwarded` for throttle client-IP resolution ONLY when true AND
     * the direct peer is in [trustedProxies] (PLAN §6). Default false = forwarding headers ignored.
     */
    val trustForwardedHeaders: Boolean = false,
    /** Trusted reverse-proxy CIDRs/addresses (PLAN §6). Empty = no proxy trusted. */
    val trustedProxies: List<String> = emptyList(),
    /** In-memory auth-throttle store bounds + tuning (PLAN §6). */
    @field:Valid
    @field:NotNull
    val throttle: Throttle = Throttle(),
) {
    /**
     * Auth-throttle tuning (PLAN §6). Login is keyed by normalized-email AND client IP with a
     * progressive block; registration is a per-IP rate limit. Everything is TTL-bounded — there is
     * no permanent lockout an attacker could weaponize against a victim account.
     */
    data class Throttle(
        /** Maximum tracked throttle entries before deterministic eviction (PLAN §6). */
        @field:Positive
        val maxEntries: Int = 100_000,
        /** Consecutive login failures (per email+IP) that trigger a temporary block (PLAN §6: ≥5). */
        @field:Positive
        val loginFailureThreshold: Int = 5,
        /** First temporary-block duration once the threshold is hit (PLAN §6: 1 minute). */
        @field:NotNull
        val loginInitialBlock: Duration = Duration.ofMinutes(1),
        /** Cap for the doubling temporary-block duration (PLAN §6: 15 minutes). */
        @field:NotNull
        val loginMaxBlock: Duration = Duration.ofMinutes(15),
        /** Idle window after which a login failure counter is considered stale and reset (PLAN §6). */
        @field:NotNull
        val loginFailureWindow: Duration = Duration.ofMinutes(15),
        /** Per-IP registration attempts allowed within [registrationWindow] (PLAN §6). */
        @field:Positive
        val registrationMaxPerWindow: Int = 20,
        /** Registration rate-limit window (PLAN §6). */
        @field:NotNull
        val registrationWindow: Duration = Duration.ofHours(1),
    )
}
