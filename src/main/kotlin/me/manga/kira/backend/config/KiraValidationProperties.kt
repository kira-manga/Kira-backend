package me.manga.kira.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `kira.validation.*` — inputs to the source-config validator (PLAN §8 rule 32b).
 *
 * [publicHeaderPlaceholderValues] is the allowlist of NON-secret values that a sensitive-name header
 * (`authorization`, `x-api-key`, `token`/`secret`/`password` substrings, …) may carry in a published
 * config. Default is exactly `["Bearer null"]` — the literal placeholder the real bundled document's
 * Mangamello / Mangamello Plus stanzas legitimately require (verified 2026-07-11), so a blanket
 * Authorization denylist would wrongly reject the production document. Any OTHER value on such a header
 * is a `SECRET_LIKE_HEADER` rejection. Extending this list is a deliberate, reviewed server-config change.
 */
@ConfigurationProperties(prefix = "kira.validation")
data class KiraValidationProperties(
    val publicHeaderPlaceholderValues: List<String> = listOf("Bearer null"),
)
