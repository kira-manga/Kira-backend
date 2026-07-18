package me.manga.kira.backend.sourceconfig.validation.rules

import me.manga.kira.backend.sourceconfig.domain.model.EndpointSpec
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.validation.Findings
import me.manga.kira.backend.sourceconfig.validation.RuleContext
import me.manga.kira.backend.sourceconfig.validation.ValidationCodes
import me.manga.kira.backend.sourceconfig.validation.sourcePath

/**
 * Endpoint rules for **generic** sources: presence (PLAN §8 rule 13 + server rule 31) and per-verb
 * shape (rule 14). Pure. `chapters` is deliberately NOT required — the chapter list parses inline
 * from the details response; a declared `chapters` endpoint is the optional two-request pattern
 * (rule 31 evidence: 4 of 12 real generic stanzas ship without it).
 */
object EndpointRules {

    private val FORMATS = setOf("json", "html", "script-json")
    private val LIST_FILTER_OPS = setOf("equals", "notEquals", "contains", "notNull", "isNull")
    private val LIST_FILTER_MODES = setOf("include", "exclude")

    fun check(source: SourceConfig, ctx: RuleContext, findings: Findings) {
        val base = sourcePath(source.api)
        val endpoints = source.endpoints

        // Rule 13 / rule 31 — at least one of home|featured.
        if ("home" !in endpoints && "featured" !in endpoints) {
            findings.error(
                ValidationCodes.GENERIC_MISSING_HOME_OR_FEATURED,
                "$base.endpoints",
                "a generic source must declare at least one of the 'home' or 'featured' endpoints.",
            )
        }
        // Rule 31 — search / details / pages required (chapters is optional).
        requireVerb(endpoints, "search", ValidationCodes.GENERIC_MISSING_SEARCH, base, findings)
        requireVerb(endpoints, "details", ValidationCodes.GENERIC_MISSING_DETAILS, base, findings)
        requireVerb(endpoints, "pages", ValidationCodes.GENERIC_MISSING_PAGES, base, findings)

        // Rule 14 — per-endpoint shape for every declared verb.
        for ((verb, endpoint) in endpoints) {
            checkEndpoint(verb, endpoint, "$base.endpoints[$verb]", findings)
        }
    }

    private fun requireVerb(endpoints: Map<String, EndpointSpec>, verb: String, code: String, base: String, findings: Findings) {
        if (verb !in endpoints) {
            findings.error(code, "$base.endpoints", "a generic source must declare the '$verb' endpoint.")
        }
    }

    private fun checkEndpoint(verb: String, endpoint: EndpointSpec, path: String, findings: Findings) {
        if (endpoint.url.isBlank()) {
            findings.error(ValidationCodes.ENDPOINT_URL_BLANK, "$path.url", "endpoint '$verb' url must be non-blank.")
        }
        // Raw {query} is forbidden — the engine only substitutes {queryEncoded} / {queryJson}.
        if (endpoint.url.contains(RAW_QUERY)) {
            findings.error(
                ValidationCodes.ENDPOINT_URL_RAW_QUERY,
                "$path.url",
                "endpoint '$verb' url must use {queryEncoded}, not raw {query}.",
            )
        }
        if (endpoint.jsonBody.contains(RAW_QUERY)) {
            findings.error(
                ValidationCodes.ENDPOINT_JSONBODY_RAW_QUERY,
                "$path.jsonBody",
                "endpoint '$verb' jsonBody must use {queryJson}, not raw {query}.",
            )
        }
        if (endpoint.method.isNotEmpty() && !HttpMethods.isKnown(endpoint.method)) {
            findings.error(
                ValidationCodes.ENDPOINT_UNKNOWN_METHOD,
                "$path.method",
                "endpoint '$verb' has unknown method '${endpoint.method}'.",
            )
        }
        if (endpoint.format.isNotEmpty() && endpoint.format !in FORMATS) {
            findings.error(
                ValidationCodes.ENDPOINT_UNKNOWN_FORMAT,
                "$path.format",
                "endpoint '$verb' has unknown format '${endpoint.format}'.",
            )
        }
        endpoint.listFilters.forEachIndexed { i, lf ->
            if (lf.op !in LIST_FILTER_OPS) {
                findings.error(
                    ValidationCodes.LISTFILTER_UNKNOWN_OP,
                    "$path.listFilters[$i].op",
                    "unknown listFilter op '${lf.op}'.",
                )
            }
            if (lf.mode !in LIST_FILTER_MODES) {
                findings.error(
                    ValidationCodes.LISTFILTER_UNKNOWN_MODE,
                    "$path.listFilters[$i].mode",
                    "unknown listFilter mode '${lf.mode}'.",
                )
            }
        }
    }

    /** The literal raw-query token forbidden in url/jsonBody (`{queryEncoded}`/`{queryJson}` are OK). */
    private const val RAW_QUERY = "{query}"
}

/** HTTP method-variant helpers shared by rule 14 (endpoints) and rule 24 (filters). PLAN §8. */
internal object HttpMethods {
    private val POST_FORM = setOf("post-form", "post_form", "postform")
    private val POST_JSON = setOf("post-json", "post_json", "postjson")
    private val ALL = setOf("get") + POST_FORM + POST_JSON

    fun isKnown(method: String): Boolean = method.lowercase() in ALL

    fun isPostForm(method: String): Boolean = method.lowercase() in POST_FORM

    fun isPostJson(method: String): Boolean = method.lowercase() in POST_JSON
}
