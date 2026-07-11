package me.manga.kira.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * `kira.auth.*` — authentication feature toggles (PLAN §4.2, §16 Open Q5).
 *
 * [registrationEnabled] gates `POST /api/v1/auth/register`. The plan's default is **true in dev,
 * false in prod** — so the field defaults to `true` (dev/base) and `application-prod.yml` overrides
 * it to `false`. Prod onboarding is via the admin user API (§4.4), not open registration.
 */
@Validated
@ConfigurationProperties(prefix = "kira.auth")
data class KiraAuthProperties(
    val registrationEnabled: Boolean = true,
)
