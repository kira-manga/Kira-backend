package me.manga.kira.backend.sourceconfig.validation.rules

import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.validation.Findings
import me.manga.kira.backend.sourceconfig.validation.RuleContext
import me.manga.kira.backend.sourceconfig.validation.ValidationCodes
import me.manga.kira.backend.sourceconfig.validation.sourcePath

/**
 * Core per-source rules that apply to **every engine**: identity (PLAN §8 rules 3–6), the
 * generic-only pagination-type check (rule 12), and the public-config secret-safety rules — headers
 * (rule 32a/b) + top-level URL values (rule 32c). Pure.
 */
object SourceRules {

    /** Hard-denied header names, case-insensitive (PLAN §8 rule 32a). */
    private val FORBIDDEN_HEADER_NAMES = setOf("cookie", "set-cookie", "proxy-authorization")

    /** Exact sensitive header names (PLAN §8 rule 32b). */
    private val SENSITIVE_HEADER_NAMES = setOf("authorization", "x-api-key", "api-key", "x-auth-token")

    /** Substrings that make ANY header name sensitive (PLAN §8 rule 32b). */
    private val SENSITIVE_HEADER_SUBSTRINGS = listOf("token", "secret", "password")

    fun check(source: SourceConfig, ctx: RuleContext, findings: Findings) {
        val base = sourcePath(source.api)

        // Rule 3 — api non-blank.
        if (source.api.isBlank()) {
            findings.error(ValidationCodes.API_BLANK, "$base.api", "api must be non-blank.")
        }
        // Rule 4 — language non-blank.
        if (source.language.isBlank()) {
            findings.error(ValidationCodes.LANGUAGE_BLANK, "$base.language", "language must be non-blank.")
        }
        // Rule 5 — baseUrl starts with "http" (absolute http(s) URL).
        if (!source.baseUrl.startsWith("http")) {
            findings.error(ValidationCodes.BASE_URL_NOT_HTTP, "$base.baseUrl", "baseUrl must be an absolute http(s) URL.")
        }
        // Rule 6 — engine ∈ {generic, legacy, kotlin:<id> prefix}.
        if (!isKnownEngine(source.engine)) {
            findings.error(
                ValidationCodes.UNKNOWN_ENGINE,
                "$base.engine",
                "engine must be 'generic', 'legacy', or 'kotlin:<id>' (got '${source.engine}').",
            )
        }

        // Rule 12 — pagination.type known to the catalog (generic engine only; skipped otherwise
        // per rule 11). The default 'page-number' is in the catalog.
        if (source.engine == "generic" && !ctx.strategies.hasPagination(source.pagination.type)) {
            findings.error(
                ValidationCodes.UNKNOWN_PAGINATION_TYPE,
                "$base.pagination.type",
                "unknown pagination type '${source.pagination.type}'.",
            )
        }

        // Rule 32a/b — header safety (all engines: headers are served publicly for any engine).
        checkHeaders(source, ctx, findings, base)

        // Rule 32c — top-level URL values (all engines). Endpoint URL templates are exempt (rule 14).
        UrlSafety.check(source.baseUrl, "$base.baseUrl", findings)
        if (source.imageBase.isNotEmpty()) {
            UrlSafety.check(source.imageBase, "$base.imageBase", findings)
        }
    }

    private fun isKnownEngine(engine: String): Boolean = engine == "generic" || engine == "legacy" || engine.startsWith("kotlin:")

    private fun checkHeaders(source: SourceConfig, ctx: RuleContext, findings: Findings, base: String) {
        for ((name, value) in source.headers) {
            val normalized = name.trim()
            if (name != normalized || !isHttpFieldName(normalized)) {
                findings.error(
                    ValidationCodes.HEADER_NAME_INVALID,
                    "$base.headers",
                    "header names must be non-blank RFC HTTP field-name tokens without surrounding whitespace.",
                )
                continue
            }

            val lower = normalized.lowercase()
            if (lower in FORBIDDEN_HEADER_NAMES) {
                findings.error(
                    ValidationCodes.FORBIDDEN_HEADER,
                    "$base.headers[$normalized]",
                    "header '$normalized' is never allowed in a published config.",
                )
                continue
            }
            if (isSensitiveName(lower) && value !in ctx.publicHeaderPlaceholderValues) {
                // Never echo the value — only the (structural) header name (PLAN §6 log-hygiene).
                findings.error(
                    ValidationCodes.SECRET_LIKE_HEADER,
                    "$base.headers[$normalized]",
                    "header '$normalized' looks credential-like; its value is not on the public-placeholder allowlist.",
                )
            }
        }
    }

    /** RFC 9110 field-name = `token`; tokens are non-empty and ASCII-only. */
    private fun isHttpFieldName(name: String): Boolean = name.isNotEmpty() &&
        name.all { char ->
            char in 'a'..'z' ||
                char in 'A'..'Z' ||
                char in '0'..'9' ||
                char in HTTP_TOKEN_PUNCTUATION
        }

    private fun isSensitiveName(lowerName: String): Boolean = lowerName in SENSITIVE_HEADER_NAMES || SENSITIVE_HEADER_SUBSTRINGS.any { it in lowerName }

    private const val HTTP_TOKEN_PUNCTUATION = "!#\$%&'*+-.^_`|~"
}
