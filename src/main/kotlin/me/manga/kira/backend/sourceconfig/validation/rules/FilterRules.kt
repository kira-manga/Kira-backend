package me.manga.kira.backend.sourceconfig.validation.rules

import me.manga.kira.backend.sourceconfig.domain.model.FilterDefinition
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.validation.Findings
import me.manga.kira.backend.sourceconfig.validation.RuleContext
import me.manga.kira.backend.sourceconfig.validation.ValidationCodes
import me.manga.kira.backend.sourceconfig.validation.sourcePath

/**
 * User-facing search-filter rules for **generic** sources (PLAN §8 rules 16–27): identity, type,
 * options, defaults, request spec, placeholder/reserved-var checks, appliesTo↔endpoint coherence,
 * `visibleWhen` and `excludeOf` cross-references, and `visibleWhen` dependency-cycle detection. Pure.
 */
object FilterRules {

    private val ID_REGEX = Regex("[a-z0-9_]{1,64}")
    private val PARAM_REGEX = Regex("[a-zA-Z0-9_]+")
    private val TYPES = setOf("select", "multiselect", "toggle", "text", "number")
    private val TARGETS = setOf("query", "path", "form", "header", "body-json")
    private val ENCODES = setOf("single", "csv", "repeat", "json-array")
    private val NON_SINGLE_ENCODES = setOf("csv", "repeat", "json-array")
    private val ALLOWED_VERBS = setOf("search")

    /** Reserved engine template vars a placeholder param must not shadow (PLAN §8 rule 23). */
    private val RESERVED_VARS =
        setOf(
            "baseUrl", "imageBase", "page", "pageOffset", "query",
            "queryEncoded", "queryJson", "itemUrl", "chapterUrl", "id",
        )

    /** Standard ids that must be select-or-multiselect (PLAN §8 rule 19). */
    private val SELECT_OR_MULTISELECT_IDS = setOf("genres", "status", "language", "type")

    fun check(source: SourceConfig, ctx: RuleContext, findings: Findings) {
        val filters = source.filters
        if (filters.isEmpty()) return
        val base = sourcePath(source.api)

        // Build id→filter (first wins) and report duplicate ids (rule 16).
        val byId = LinkedHashMap<String, FilterDefinition>()
        val seen = mutableSetOf<String>()
        for (f in filters) {
            if (!seen.add(f.id)) {
                findings.error(
                    ValidationCodes.FILTER_ID_DUPLICATE,
                    "$base.filters[${f.id}]",
                    "duplicate filter id '${f.id}'.",
                )
            }
            byId.putIfAbsent(f.id, f)
        }

        for (f in filters) {
            checkFilter(source, f, byId, findings, "$base.filters[${f.id}]")
        }

        detectCycle(byId, findings, base)
    }

    private fun checkFilter(source: SourceConfig, f: FilterDefinition, byId: Map<String, FilterDefinition>, findings: Findings, path: String) {
        // Rule 16 — id non-blank + regex.
        if (f.id.isBlank() || !ID_REGEX.matches(f.id)) {
            findings.error(ValidationCodes.FILTER_ID_INVALID, "$path.id", "filter id must match [a-z0-9_]{1,64}.")
        }
        // Rule 17 — label non-blank.
        if (f.label.isBlank()) {
            findings.error(ValidationCodes.FILTER_LABEL_BLANK, "$path.label", "filter label must be non-blank.")
        }
        // Rule 18 — type vocabulary.
        val knownType = f.type in TYPES
        if (!knownType) {
            findings.error(ValidationCodes.FILTER_UNKNOWN_TYPE, "$path.type", "unknown filter type '${f.type}'.")
        }
        // Rule 19 — standard-id type pinning.
        if (f.id == "sort" && f.type != "select") {
            findings.error(ValidationCodes.FILTER_TYPE_PINNING, "$path.type", "filter 'sort' must be type 'select'.")
        }
        if (f.id in SELECT_OR_MULTISELECT_IDS && f.type != "select" && f.type != "multiselect") {
            findings.error(
                ValidationCodes.FILTER_TYPE_PINNING,
                "$path.type",
                "filter '${f.id}' must be 'select' or 'multiselect'.",
            )
        }

        checkOptions(f, findings, path)
        checkDefaults(f, findings, path)
        checkRequest(f, findings, path)
        checkAppliesTo(source, f, findings, path)
        checkVisibleWhen(f, byId, findings, path)
        checkExcludeOf(f, byId, findings, path)
    }

    // Rule 20 — options presence/absence + value validity.
    private fun checkOptions(f: FilterDefinition, findings: Findings, path: String) {
        val isSelectLike = f.type == "select" || f.type == "multiselect"
        val isValueless = f.type == "toggle" || f.type == "text" || f.type == "number"
        if (isSelectLike && f.options.isEmpty()) {
            findings.error(ValidationCodes.FILTER_OPTIONS_REQUIRED, "$path.options", "select/multiselect requires ≥1 option.")
        }
        if (isValueless && f.options.isNotEmpty()) {
            findings.error(ValidationCodes.FILTER_OPTIONS_FORBIDDEN, "$path.options", "type '${f.type}' must not declare options.")
        }
        val seenValues = mutableSetOf<String>()
        f.options.forEachIndexed { i, opt ->
            if (opt.value.isBlank()) {
                findings.error(ValidationCodes.FILTER_OPTION_VALUE_BLANK, "$path.options[$i].value", "option value must be non-blank.")
            } else if (!seenValues.add(opt.value)) {
                findings.error(
                    ValidationCodes.FILTER_OPTION_VALUE_DUPLICATE,
                    "$path.options[$i].value",
                    "duplicate option value '${opt.value}'.",
                )
            }
        }
    }

    // Rule 21 — defaults.
    private fun checkDefaults(f: FilterDefinition, findings: Findings, path: String) {
        val optionValues = f.options.map { it.value }.toSet()
        if (f.type == "multiselect") {
            if (f.default.isNotEmpty()) {
                findings.error(ValidationCodes.FILTER_DEFAULT_NOT_ALLOWED, "$path.default", "multiselect uses 'defaults', not 'default'.")
            }
            f.defaults.forEach { d ->
                if (d !in optionValues) {
                    findings.error(ValidationCodes.FILTER_DEFAULT_NOT_OPTION, "$path.defaults", "default '$d' is not a declared option value.")
                }
            }
        } else {
            if (f.defaults.isNotEmpty()) {
                findings.error(ValidationCodes.FILTER_DEFAULTS_NOT_ALLOWED, "$path.defaults", "'defaults' is multiselect-only.")
            }
            when (f.type) {
                "select" ->
                    if (f.default.isNotEmpty() && f.default !in optionValues) {
                        findings.error(ValidationCodes.FILTER_DEFAULT_NOT_OPTION, "$path.default", "default '${f.default}' is not a declared option value.")
                    }

                "toggle" ->
                    if (f.default !in setOf("", "true", "false")) {
                        findings.error(ValidationCodes.FILTER_TOGGLE_DEFAULT_INVALID, "$path.default", "toggle default must be '', 'true', or 'false'.")
                    }

                "number" ->
                    if (f.default.isNotEmpty() && f.default.toDoubleOrNull()?.isFinite() != true) {
                        findings.error(ValidationCodes.FILTER_NUMBER_DEFAULT_INVALID, "$path.default", "number default must parse as a number.")
                    }
            }
        }
        if (f.required && !hasUsableDefault(f)) {
            findings.error(
                ValidationCodes.FILTER_REQUIRED_WITHOUT_DEFAULT,
                "$path.default",
                "required filter must declare a usable default.",
            )
        }
    }

    // Rules 22 & 23 — request spec + placeholder/reserved-var checks.
    private fun checkRequest(f: FilterDefinition, findings: Findings, path: String) {
        val r = f.request
        val rp = "$path.request"
        val knownTarget = r.target in TARGETS
        if (!knownTarget) {
            findings.error(ValidationCodes.FILTER_REQUEST_UNKNOWN_TARGET, "$rp.target", "unknown request target '${r.target}'.")
        }
        if (r.param.isBlank()) {
            findings.error(ValidationCodes.FILTER_REQUEST_PARAM_BLANK, "$rp.param", "request param must be non-blank.")
        }
        if (r.encode !in ENCODES) {
            findings.error(ValidationCodes.FILTER_REQUEST_UNKNOWN_ENCODE, "$rp.encode", "unknown encode '${r.encode}'.")
        }
        // Rule 22 compatibility.
        if (r.encode == "repeat" && r.target != "query" && r.target != "form") {
            findings.error(ValidationCodes.FILTER_ENCODE_INCOMPATIBLE, "$rp.encode", "'repeat' is only valid for query/form targets.")
        }
        if (r.encode == "json-array" && r.target != "body-json") {
            findings.error(ValidationCodes.FILTER_ENCODE_INCOMPATIBLE, "$rp.encode", "'json-array' is only valid for the body-json target.")
        }
        if (r.target == "body-json" && r.encode != "single" && r.encode != "json-array") {
            findings.error(ValidationCodes.FILTER_ENCODE_INCOMPATIBLE, "$rp.encode", "body-json allows only 'single' or 'json-array'.")
        }
        if (r.encode in NON_SINGLE_ENCODES && f.type != "multiselect") {
            findings.error(ValidationCodes.FILTER_ENCODE_INCOMPATIBLE, "$rp.encode", "encode '${r.encode}' requires type 'multiselect'.")
        }
        // Rule 23 — placeholder targets (path / body-json).
        if (r.target == "path" || r.target == "body-json") {
            if (!PARAM_REGEX.matches(r.param)) {
                findings.error(ValidationCodes.FILTER_PARAM_INVALID, "$rp.param", "placeholder param must match [a-zA-Z0-9_]+.")
            }
            if (r.param in RESERVED_VARS) {
                findings.error(ValidationCodes.FILTER_PARAM_RESERVED, "$rp.param", "param '${r.param}' shadows a reserved engine template var.")
            }
            if (r.target == "path" && !hasUsableDefault(f)) {
                findings.error(ValidationCodes.FILTER_PATH_REQUIRES_DEFAULT, "$rp.param", "a path-target filter requires a guaranteed non-empty default.")
            }
        }
    }

    // Rule 24 — appliesTo verbs + per-verb endpoint coherence.
    private fun checkAppliesTo(source: SourceConfig, f: FilterDefinition, findings: Findings, path: String) {
        if (f.appliesTo.isEmpty()) {
            findings.error(ValidationCodes.FILTER_APPLIES_TO_EMPTY, "$path.appliesTo", "appliesTo must be non-empty.")
        }
        f.appliesTo.forEach { verb ->
            if (verb !in ALLOWED_VERBS) {
                findings.error(ValidationCodes.FILTER_APPLIES_TO_UNKNOWN_VERB, "$path.appliesTo", "unsupported appliesTo verb '$verb' (v1: 'search').")
            }
        }
        val r = f.request
        // Endpoint coherence only for the valid verbs referenced.
        f.appliesTo.filter { it in ALLOWED_VERBS }.forEach { verb ->
            val endpoint = source.endpoints[verb]
            if (endpoint == null) {
                findings.error(ValidationCodes.FILTER_ENDPOINT_MISSING, "$path.appliesTo", "appliesTo verb '$verb' has no matching endpoint.")
                return@forEach
            }
            when (r.target) {
                "form" -> {
                    if (!HttpMethods.isPostForm(endpoint.method)) {
                        findings.error(
                            ValidationCodes.FILTER_FORM_METHOD_MISMATCH,
                            "$path.request.target",
                            "form target requires a post-form endpoint '$verb'.",
                        )
                    }
                    if (r.param in endpoint.formBody.keys) {
                        findings.error(
                            ValidationCodes.FILTER_FORM_PARAM_COLLISION,
                            "$path.request.param",
                            "param '${r.param}' collides with a static formBody key on '$verb'.",
                        )
                    }
                }

                "body-json" -> {
                    if (!HttpMethods.isPostJson(endpoint.method)) {
                        findings.error(
                            ValidationCodes.FILTER_BODYJSON_METHOD_MISMATCH,
                            "$path.request.target",
                            "body-json target requires a post-json endpoint '$verb'.",
                        )
                    }
                    if (!endpoint.jsonBody.contains("{${r.param}}")) {
                        findings.error(
                            ValidationCodes.FILTER_BODYJSON_MISSING_PLACEHOLDER,
                            "$path.request.param",
                            "endpoint '$verb' jsonBody must contain {${r.param}}.",
                        )
                    }
                }

                "path" -> {
                    if (!endpoint.url.contains("{${r.param}}")) {
                        findings.error(
                            ValidationCodes.FILTER_PATH_PLACEHOLDER_MISSING,
                            "$path.request.param",
                            "endpoint '$verb' url must contain {${r.param}}.",
                        )
                    }
                }

                "query" -> {
                    if (endpoint.url.contains("?${r.param}=") || endpoint.url.contains("&${r.param}=")) {
                        findings.error(
                            ValidationCodes.FILTER_QUERY_PARAM_HARDCODED,
                            "$path.request.param",
                            "query param '${r.param}' is already hardcoded in endpoint '$verb' url.",
                        )
                    }
                }
            }
        }
    }

    // Rule 25 — visibleWhen references.
    private fun checkVisibleWhen(f: FilterDefinition, byId: Map<String, FilterDefinition>, findings: Findings, path: String) {
        f.visibleWhen.forEachIndexed { i, cond ->
            val cp = "$path.visibleWhen[$i]"
            if (cond.filter == f.id) {
                findings.error(ValidationCodes.FILTER_VISIBLEWHEN_SELF, "$cp.filter", "visibleWhen must not self-reference.")
            }
            val ref = byId[cond.filter]
            if (ref == null) {
                findings.error(ValidationCodes.FILTER_VISIBLEWHEN_UNKNOWN, "$cp.filter", "visibleWhen references unknown filter '${cond.filter}'.")
            }
            if (cond.anyOf.isEmpty()) {
                findings.error(ValidationCodes.FILTER_VISIBLEWHEN_EMPTY_ANYOF, "$cp.anyOf", "visibleWhen anyOf must be non-empty.")
            }
            if (ref != null) {
                val vocab = vocabulary(ref)
                if (vocab != null) {
                    cond.anyOf.forEach { v ->
                        if (v !in vocab) {
                            findings.error(
                                ValidationCodes.FILTER_VISIBLEWHEN_OUT_OF_VOCABULARY,
                                "$cp.anyOf",
                                "value '$v' is not in filter '${cond.filter}'s value vocabulary.",
                            )
                        }
                    }
                }
            }
        }
    }

    // Rule 26 — excludeOf.
    private fun checkExcludeOf(f: FilterDefinition, byId: Map<String, FilterDefinition>, findings: Findings, path: String) {
        if (f.excludeOf.isEmpty()) return
        val ep = "$path.excludeOf"
        if (f.type != "multiselect") {
            findings.error(ValidationCodes.FILTER_EXCLUDEOF_NOT_MULTISELECT, ep, "excludeOf is only valid on a multiselect filter.")
        }
        if (f.excludeOf == f.id) {
            findings.error(ValidationCodes.FILTER_EXCLUDEOF_SELF, ep, "excludeOf must not self-reference.")
        }
        val ref = byId[f.excludeOf]
        if (ref == null) {
            findings.error(ValidationCodes.FILTER_EXCLUDEOF_UNKNOWN, ep, "excludeOf references unknown filter '${f.excludeOf}'.")
            return
        }
        if (ref.type != "multiselect") {
            findings.error(ValidationCodes.FILTER_EXCLUDEOF_TARGET_NOT_MULTISELECT, ep, "excludeOf must reference a multiselect filter.")
        }
        if (ref.excludeOf.isNotEmpty()) {
            findings.error(ValidationCodes.FILTER_EXCLUDEOF_CHAINED, ep, "chained exclusion is not allowed (referenced filter also sets excludeOf).")
        }
        val overlap = f.defaults.toSet().intersect(ref.defaults.toSet())
        if (overlap.isNotEmpty()) {
            findings.error(ValidationCodes.FILTER_EXCLUDEOF_DEFAULTS_OVERLAP, ep, "excluding filters must not share default values.")
        }
    }

    // Rule 27 — bounded, iterative visibleWhen dependency-cycle detection (one report per source).
    private fun detectCycle(byId: Map<String, FilterDefinition>, findings: Findings, base: String) {
        val adjacency = LinkedHashMap<String, Set<String>>(byId.size)
        val indegree = byId.keys.associateWith { 0 }.toMutableMap()
        byId.forEach { (id, filter) ->
            val dependencies =
                filter.visibleWhen
                    .asSequence()
                    .map { it.filter }
                    .filter { it in byId }
                    .toCollection(linkedSetOf())
            adjacency[id] = dependencies
            dependencies.forEach { dependency -> indegree[dependency] = indegree.getValue(dependency) + 1 }
        }

        val ready = ArrayDeque(indegree.filterValues { it == 0 }.keys)
        var visited = 0
        while (ready.isNotEmpty()) {
            val node = ready.removeFirst()
            visited++
            adjacency[node].orEmpty().forEach { dependency ->
                val remaining = indegree.getValue(dependency) - 1
                indegree[dependency] = remaining
                if (remaining == 0) ready.addLast(dependency)
            }
        }

        if (visited != byId.size) {
            findings.error(ValidationCodes.FILTER_CYCLE, "$base.filters", "visibleWhen dependency cycle detected among filters.")
        }
    }

    private fun hasUsableDefault(f: FilterDefinition): Boolean = if (f.type == "multiselect") f.defaults.isNotEmpty() else f.default.isNotEmpty()

    private fun vocabulary(f: FilterDefinition): Set<String>? = when (f.type) {
        "select", "multiselect" -> f.options.map { it.value }.toSet()
        "toggle" -> setOf("true", "false")
        else -> null
    }
}
