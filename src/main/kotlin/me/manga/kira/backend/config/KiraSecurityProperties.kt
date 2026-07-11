package me.manga.kira.backend.config

import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * `kira.security.*` — cross-cutting security configuration (PLAN §3 config/, §6). Phase 2 defines
 * and validates the typed binding; the consumers wire it in Phase 3 (`JwtService`,
 * `AuthThrottleService`, the SecurityFilterChain).
 *
 * [jwtSecret] is intentionally nullable and un-gated **here**: the real fail-fast (Base64 that
 * decodes to ≥ 256 bits, with a documented insecure dev default) is a Phase-3 `JwtService`
 * responsibility (PLAN §6) — so a Phase-2 context and the dev profile load without secrets. Do
 * NOT hardcode a signing key here.
 */
@Validated
@ConfigurationProperties(prefix = "kira.security")
data class KiraSecurityProperties(
    /** Base64 JWT signing key, bound from `KIRA_JWT_SECRET`. Validated by the Phase-3 JwtService. */
    val jwtSecret: String? = null,
    /** Access-token lifetime (PLAN §6 / Open Q3 default PT60M). */
    @field:NotNull
    val accessTokenTtl: Duration = Duration.ofMinutes(60),
    /**
     * Honor `X-Forwarded-For` / `Forwarded` for throttle client-IP resolution ONLY when true AND
     * the direct peer is in [trustedProxies] (PLAN §6). Default false = forwarding headers ignored.
     */
    val trustForwardedHeaders: Boolean = false,
    /** Trusted reverse-proxy CIDRs/addresses (PLAN §6). Empty = no proxy trusted. */
    val trustedProxies: List<String> = emptyList(),
    /** In-memory auth-throttle store bounds (PLAN §6). */
    @field:Valid
    @field:NotNull
    val throttle: Throttle = Throttle(),
) {
    data class Throttle(
        /** Maximum tracked throttle entries before deterministic eviction (PLAN §6). */
        @field:Positive
        val maxEntries: Int = 100_000,
    )
}
