package me.manga.kira.backend.sourceconfig.application

import me.manga.kira.backend.common.ApiFieldError
import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig

/**
 * The **Tier-1 structural 400 gate** (PLAN §8 two-tier model). Runs at the service boundary BEFORE any
 * row is created; a violation is a `400` with the named code and **nothing persisted** — distinct from
 * a Tier-2 semantic finding, which is stored on an inspectable draft and reported (never a 400).
 *
 * These are DB-identity / routing requirements, not ordinary validation findings: a draft row
 * physically cannot hold an over-length identity field, so it must be a controlled 400, never a
 * persistence exception surfacing as a 500. (Malformed / strict-parse-rejected JSON — check (a) — is
 * already a `400 MALFORMED_CONFIG_JSON` from `SourceConfigParser`, before this gate runs.)
 *
 * The api format is deliberately permissive beyond the structural bans: production apis include
 * embedded spaces (`"Team X"`, `"Mangamello Plus"`) and non-ASCII script (`"مانجا بارك"`), so an
 * ASCII-slug regex would reject live identifiers — instead the gate rejects only blank / over-128-chars
 * / control-chars / `/`+`\` (path-routing breakers) / edge-whitespace (PLAN §8, Appendix B Finding 9).
 */
object StructuralAuthoringGate {

    const val API_ID_MISMATCH = "API_ID_MISMATCH" // check (b)
    const val LIFECYCLE_NOT_AUTHORABLE = "LIFECYCLE_NOT_AUTHORABLE" // check (c)
    const val API_IDENTIFIER_INVALID = "API_IDENTIFIER_INVALID" // check (d)
    const val FIELD_TOO_LONG = "FIELD_TOO_LONG" // check (e)

    // DB column limits from V2 (PLAN §5). Postgres counts characters, so non-ASCII is safe.
    private const val API_MAX = 128
    private const val DISPLAY_NAME_MAX = 256
    private const val LANGUAGE_MAX = 32
    private const val ENGINE_MAX = 64
    private const val BASE_URL_MAX = 512

    private const val NEUTRAL_LIFECYCLE = "active"

    /**
     * Enforce the gate on an authoring [source]. [pathApi] is the `{api}` for a revision (identity is
     * immutable and path-bound → check (b)); null for a create. Throws [BadRequestException] on the
     * first violation; nothing has been persisted at this point.
     */
    fun check(source: SourceConfig, pathApi: String?) {
        // (b) API_ID_MISMATCH — body.api must equal the path {api} on later revisions.
        if (pathApi != null && source.api != pathApi) {
            throw BadRequestException(
                "body api does not match the path api; the api identity is immutable after creation.",
                code = API_ID_MISMATCH,
            )
        }

        // (c) LIFECYCLE_NOT_AUTHORABLE — lifecycle is server-managed (§9); the payload must be the
        // neutral default (omitted or explicit "active" — both canonicalize identically).
        if (source.lifecycle != NEUTRAL_LIFECYCLE) {
            throw BadRequestException(
                "lifecycle is server-managed and must be the neutral default; it cannot be authored.",
                code = LIFECYCLE_NOT_AUTHORABLE,
            )
        }

        // (d) API_IDENTIFIER_INVALID — blank / over-128 / control chars / '/' or '\' / edge whitespace.
        if (apiIdentifierInvalid(source.api)) {
            // Never echo the raw value verbatim (PLAN §6) — describe the constraint only.
            throw BadRequestException(apiIdentifierMessage(), code = API_IDENTIFIER_INVALID)
        }

        // (e) FIELD_TOO_LONG — identity/denormalized values over their DB column limits.
        fieldTooLongErrors(source, path = null).firstOrNull()?.let {
            throw BadRequestException(it.message, code = FIELD_TOO_LONG)
        }
    }

    /**
     * The Tier-1 structural findings that ALSO apply to a bundled import (PLAN §8 "Import" note + §12.2):
     * ONLY checks (d) `API_IDENTIFIER_INVALID` and (e) `FIELD_TOO_LONG` — the DB-identity / column-limit
     * guards that would otherwise surface as a 500 on insert. Import deliberately does NOT run (b)
     * `API_ID_MISMATCH` (there is no path api) or (c) `LIFECYCLE_NOT_AUTHORABLE` (import READS the incoming
     * lifecycle — §9/§12.2). Returns findings (never throws) so the import folds them into its
     * whole-document 422 all-or-nothing list. [path] is a stanza-position prefix (e.g. `sources[3]`) the
     * caller supplies so no potentially-invalid api value is echoed into the finding path (PLAN §6).
     */
    fun importStructuralErrors(source: SourceConfig, path: String): List<ApiFieldError> {
        val errors = mutableListOf<ApiFieldError>()
        if (apiIdentifierInvalid(source.api)) {
            errors += ApiFieldError(code = API_IDENTIFIER_INVALID, path = "$path.api", message = apiIdentifierMessage())
        }
        errors += fieldTooLongErrors(source, path)
        return errors
    }

    /** True when [api] violates check (d) — blank / over-128 / control chars / `/` or `\` / edge whitespace. */
    private fun apiIdentifierInvalid(api: String): Boolean = api.isBlank() ||
        api.length > API_MAX ||
        api != api.trim() ||
        api.any { it.isControlChar() } ||
        api.contains('/') ||
        api.contains('\\')

    private fun apiIdentifierMessage(): String = "api identifier is invalid: it must be non-blank, at most $API_MAX characters, free of " +
        "control characters, without '/' or '\\', and without leading/trailing whitespace."

    /** Check (e) as findings: every identity/denormalized value over its DB column limit. */
    private fun fieldTooLongErrors(source: SourceConfig, path: String?): List<ApiFieldError> = buildList {
        fieldTooLong(path, "displayName", source.displayName.length, DISPLAY_NAME_MAX)?.let { add(it) }
        fieldTooLong(path, "language", source.language.length, LANGUAGE_MAX)?.let { add(it) }
        fieldTooLong(path, "engine", source.engine.length, ENGINE_MAX)?.let { add(it) }
        fieldTooLong(path, "baseUrl", source.baseUrl.length, BASE_URL_MAX)?.let { add(it) }
    }

    private fun fieldTooLong(path: String?, field: String, length: Int, max: Int): ApiFieldError? = if (length > max) {
        ApiFieldError(
            code = FIELD_TOO_LONG,
            path = path?.let { "$it.$field" } ?: field,
            message = "$field exceeds its maximum length of $max characters.",
        )
    } else {
        null
    }

    // Control characters: U+0000–U+001F and U+007F (PLAN §8 check (d)).
    private fun Char.isControlChar(): Boolean = this.code in 0x00..0x1F || this.code == 0x7F
}
