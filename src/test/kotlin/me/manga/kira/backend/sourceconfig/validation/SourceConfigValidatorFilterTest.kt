package me.manga.kira.backend.sourceconfig.validation

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.domain.model.EndpointSpec
import me.manga.kira.backend.sourceconfig.domain.model.FilterConditionSpec
import me.manga.kira.backend.sourceconfig.domain.model.FilterDefinition
import me.manga.kira.backend.sourceconfig.domain.model.FilterOptionSpec
import me.manga.kira.backend.sourceconfig.domain.model.FilterRequestSpec
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 3 — the search-filter halves (rules 16–27). Ports the app's
 * `DefaultSourceConfigValidatorFilterTest`. Pure — no Spring context.
 */
class SourceConfigValidatorFilterTest {

    private val validator = SourceConfigValidator()

    private fun codes(source: SourceConfig): Set<String> =
        validator.validate(SourceConfigFixtures.document(source)).errors.map { it.code }.toSet()

    /** A generic source carrying [filters], optionally overriding the search endpoint. */
    private fun gen(
        vararg filters: FilterDefinition,
        search: EndpointSpec? = null,
    ): SourceConfig {
        val base = SourceConfigFixtures.validGenericSource()
        val endpoints = if (search != null) base.endpoints + ("search" to search) else base.endpoints
        return base.copy(filters = filters.toList(), endpoints = endpoints)
    }

    private fun filter(
        id: String,
        type: String,
        target: String = "query",
        param: String = "p",
        encode: String = "single",
        options: List<String> = emptyList(),
        default: String = "",
        defaults: List<String> = emptyList(),
        required: Boolean = false,
        excludeOf: String = "",
        visibleWhen: List<FilterConditionSpec> = emptyList(),
        appliesTo: List<String> = listOf("search"),
        label: String = "Label",
    ): FilterDefinition =
        FilterDefinition(
            id = id,
            label = label,
            type = type,
            options = options.map { FilterOptionSpec(it) },
            default = default,
            defaults = defaults,
            required = required,
            excludeOf = excludeOf,
            visibleWhen = visibleWhen,
            request = FilterRequestSpec(target = target, param = param, encode = encode),
            appliesTo = appliesTo,
        )

    @Test
    fun `a valid filter set is accepted`() {
        assertTrue(codes(gen(SourceConfigFixtures.validFilter())).isEmpty())
    }

    @Test
    fun `rule 16 - id regex and uniqueness`() {
        assertTrue(ValidationCodes.FILTER_ID_INVALID in codes(gen(filter(id = "Bad Id", type = "text"))))
        assertTrue(
            ValidationCodes.FILTER_ID_DUPLICATE in
                codes(gen(filter(id = "dup", type = "text", param = "a"), filter(id = "dup", type = "text", param = "b"))),
        )
    }

    @Test
    fun `rule 17 - blank label`() {
        assertTrue(ValidationCodes.FILTER_LABEL_BLANK in codes(gen(filter(id = "x", type = "text", label = " "))))
    }

    @Test
    fun `rule 18 - unknown type (range reserved-rejected)`() {
        assertTrue(ValidationCodes.FILTER_UNKNOWN_TYPE in codes(gen(filter(id = "x", type = "range"))))
    }

    @Test
    fun `rule 19 - type pinning for sort and genres`() {
        assertTrue(ValidationCodes.FILTER_TYPE_PINNING in codes(gen(filter(id = "sort", type = "multiselect", options = listOf("a"), defaults = listOf("a"), param = "s"))))
        assertTrue(ValidationCodes.FILTER_TYPE_PINNING in codes(gen(filter(id = "genres", type = "toggle", param = "g"))))
    }

    @Test
    fun `rule 20 - options presence, absence, blank, duplicate`() {
        assertTrue(ValidationCodes.FILTER_OPTIONS_REQUIRED in codes(gen(filter(id = "x", type = "select", param = "a"))))
        assertTrue(ValidationCodes.FILTER_OPTIONS_FORBIDDEN in codes(gen(filter(id = "x", type = "toggle", options = listOf("a"), param = "a"))))
        assertTrue(ValidationCodes.FILTER_OPTION_VALUE_BLANK in codes(gen(filter(id = "x", type = "select", options = listOf(""), param = "a"))))
        assertTrue(ValidationCodes.FILTER_OPTION_VALUE_DUPLICATE in codes(gen(filter(id = "x", type = "select", options = listOf("a", "a"), default = "a", param = "a"))))
    }

    @Test
    fun `rule 21 - defaults across every type`() {
        // multiselect must not set `default`
        assertTrue(ValidationCodes.FILTER_DEFAULT_NOT_ALLOWED in codes(gen(filter(id = "x", type = "multiselect", options = listOf("a"), default = "a", param = "a"))))
        // non-multiselect must not set `defaults`
        assertTrue(ValidationCodes.FILTER_DEFAULTS_NOT_ALLOWED in codes(gen(filter(id = "x", type = "select", options = listOf("a"), defaults = listOf("a"), param = "a"))))
        // select default must be an option
        assertTrue(ValidationCodes.FILTER_DEFAULT_NOT_OPTION in codes(gen(filter(id = "x", type = "select", options = listOf("a"), default = "b", param = "a"))))
        // multiselect defaults must be options
        assertTrue(ValidationCodes.FILTER_DEFAULT_NOT_OPTION in codes(gen(filter(id = "x", type = "multiselect", options = listOf("a"), defaults = listOf("z"), param = "a"))))
        // toggle default must be boolean-ish
        assertTrue(ValidationCodes.FILTER_TOGGLE_DEFAULT_INVALID in codes(gen(filter(id = "x", type = "toggle", default = "maybe", param = "a"))))
        // number default must parse
        assertTrue(ValidationCodes.FILTER_NUMBER_DEFAULT_INVALID in codes(gen(filter(id = "x", type = "number", default = "abc", param = "a"))))
        // required demands a usable default
        assertTrue(ValidationCodes.FILTER_REQUIRED_WITHOUT_DEFAULT in codes(gen(filter(id = "x", type = "select", options = listOf("a"), required = true, param = "a"))))
    }

    @Test
    fun `rule 22 - request target, param, encode, and compatibility`() {
        assertTrue(ValidationCodes.FILTER_REQUEST_UNKNOWN_TARGET in codes(gen(filter(id = "x", type = "toggle", target = "cookie", param = "a"))))
        assertTrue(ValidationCodes.FILTER_REQUEST_PARAM_BLANK in codes(gen(filter(id = "x", type = "toggle", param = " "))))
        assertTrue(ValidationCodes.FILTER_REQUEST_UNKNOWN_ENCODE in codes(gen(filter(id = "x", type = "toggle", encode = "weird", param = "a"))))
        // repeat only for query/form
        assertTrue(ValidationCodes.FILTER_ENCODE_INCOMPATIBLE in codes(gen(filter(id = "x", type = "multiselect", options = listOf("a"), target = "header", encode = "repeat", param = "a"))))
        // json-array only for body-json
        assertTrue(ValidationCodes.FILTER_ENCODE_INCOMPATIBLE in codes(gen(filter(id = "x", type = "multiselect", options = listOf("a"), target = "query", encode = "json-array", param = "a"))))
        // csv/repeat/json-array require multiselect
        assertTrue(ValidationCodes.FILTER_ENCODE_INCOMPATIBLE in codes(gen(filter(id = "x", type = "select", options = listOf("a"), default = "a", encode = "csv", param = "a"))))
    }

    @Test
    fun `rule 23 - placeholder param regex, reserved-var shadow, path-without-default`() {
        assertTrue(ValidationCodes.FILTER_PARAM_INVALID in codes(gen(filter(id = "x", type = "select", options = listOf("a"), default = "a", target = "path", param = "bad-param"))))
        assertTrue(ValidationCodes.FILTER_PARAM_RESERVED in codes(gen(filter(id = "x", type = "select", options = listOf("a"), default = "a", target = "path", param = "page"))))
        assertTrue(ValidationCodes.FILTER_PATH_REQUIRES_DEFAULT in codes(gen(filter(id = "x", type = "select", options = listOf("a"), target = "path", param = "p"))))
    }

    @Test
    fun `rule 24 - appliesTo verbs and per-target endpoint coherence`() {
        assertTrue(ValidationCodes.FILTER_APPLIES_TO_EMPTY in codes(gen(SourceConfigFixtures.validFilter().copy(appliesTo = emptyList()))))
        assertTrue(ValidationCodes.FILTER_APPLIES_TO_UNKNOWN_VERB in codes(gen(SourceConfigFixtures.validFilter().copy(appliesTo = listOf("home")))))

        // form target requires a post-form endpoint and no static-key collision.
        assertTrue(
            ValidationCodes.FILTER_FORM_METHOD_MISMATCH in
                codes(gen(filter(id = "genres", type = "multiselect", options = listOf("a"), defaults = listOf("a"), target = "form", encode = "csv", param = "g"))),
        )
        assertTrue(
            ValidationCodes.FILTER_FORM_PARAM_COLLISION in
                codes(
                    gen(
                        filter(id = "genres", type = "multiselect", options = listOf("a"), defaults = listOf("a"), target = "form", encode = "csv", param = "g"),
                        search = EndpointSpec(url = "{baseUrl}/s", method = "post-form", format = "json", formBody = mapOf("g" to "1")),
                    ),
                ),
        )
        // body-json target requires a post-json endpoint whose jsonBody carries the placeholder.
        assertTrue(
            ValidationCodes.FILTER_BODYJSON_METHOD_MISMATCH in
                codes(gen(filter(id = "genres", type = "multiselect", options = listOf("a"), defaults = listOf("a"), target = "body-json", encode = "json-array", param = "g"))),
        )
        assertTrue(
            ValidationCodes.FILTER_BODYJSON_MISSING_PLACEHOLDER in
                codes(
                    gen(
                        filter(id = "genres", type = "multiselect", options = listOf("a"), defaults = listOf("a"), target = "body-json", encode = "json-array", param = "g"),
                        search = EndpointSpec(url = "{baseUrl}/s", method = "post-json", format = "json", jsonBody = "{}"),
                    ),
                ),
        )
        // path target requires {param} in the endpoint url.
        assertTrue(
            ValidationCodes.FILTER_PATH_PLACEHOLDER_MISSING in
                codes(gen(filter(id = "x", type = "select", options = listOf("a"), default = "a", target = "path", param = "g"))),
        )
        // query target: param must not already be hardcoded in the endpoint url (search has ?q=).
        assertTrue(
            ValidationCodes.FILTER_QUERY_PARAM_HARDCODED in
                codes(gen(filter(id = "x", type = "text", target = "query", param = "q"))),
        )
        // A verb whose endpoint is absent is flagged.
        val noSearch = SourceConfigFixtures.validGenericSource().let { it.copy(endpoints = it.endpoints - "search", filters = listOf(SourceConfigFixtures.validFilter())) }
        assertTrue(ValidationCodes.FILTER_ENDPOINT_MISSING in codes(noSearch))
    }

    @Test
    fun `rule 25 - visibleWhen unknown, self, empty-anyOf, out-of-vocabulary`() {
        assertTrue(
            ValidationCodes.FILTER_VISIBLEWHEN_UNKNOWN in
                codes(gen(filter(id = "a", type = "text", visibleWhen = listOf(FilterConditionSpec("ghost", listOf("x")))))),
        )
        assertTrue(
            ValidationCodes.FILTER_VISIBLEWHEN_SELF in
                codes(gen(SourceConfigFixtures.validFilter().copy(visibleWhen = listOf(FilterConditionSpec("genres", listOf("action")))))),
        )
        assertTrue(
            ValidationCodes.FILTER_VISIBLEWHEN_EMPTY_ANYOF in
                codes(gen(SourceConfigFixtures.validFilter(), filter(id = "b2", type = "toggle", param = "b", visibleWhen = listOf(FilterConditionSpec("genres", emptyList()))))),
        )
        assertTrue(
            ValidationCodes.FILTER_VISIBLEWHEN_OUT_OF_VOCABULARY in
                codes(gen(SourceConfigFixtures.validFilter(), filter(id = "b2", type = "toggle", param = "b", visibleWhen = listOf(FilterConditionSpec("genres", listOf("nonexistent")))))),
        )
    }

    @Test
    fun `rule 26 - excludeOf non-multiselect, unknown, target-not-multiselect, chained, overlap`() {
        // excludeOf only on multiselect
        assertTrue(
            ValidationCodes.FILTER_EXCLUDEOF_NOT_MULTISELECT in
                codes(gen(SourceConfigFixtures.validFilter(), filter(id = "s", type = "select", options = listOf("a"), default = "a", param = "s", excludeOf = "genres"))),
        )
        // unknown reference
        assertTrue(
            ValidationCodes.FILTER_EXCLUDEOF_UNKNOWN in
                codes(gen(filter(id = "m", type = "multiselect", options = listOf("a"), param = "m", excludeOf = "ghost"))),
        )
        // reference must be multiselect
        assertTrue(
            ValidationCodes.FILTER_EXCLUDEOF_TARGET_NOT_MULTISELECT in
                codes(
                    gen(
                        filter(id = "a", type = "multiselect", options = listOf("x"), param = "a", excludeOf = "s"),
                        filter(id = "s", type = "select", options = listOf("y"), default = "y", param = "sp"),
                    ),
                ),
        )
        // chained exclusion
        assertTrue(
            ValidationCodes.FILTER_EXCLUDEOF_CHAINED in
                codes(
                    gen(
                        filter(id = "a", type = "multiselect", options = listOf("x"), param = "a", excludeOf = "b"),
                        filter(id = "b", type = "multiselect", options = listOf("y"), param = "b", excludeOf = "c"),
                        filter(id = "c", type = "multiselect", options = listOf("z"), param = "c"),
                    ),
                ),
        )
        // overlapping defaults
        assertTrue(
            ValidationCodes.FILTER_EXCLUDEOF_DEFAULTS_OVERLAP in
                codes(
                    gen(
                        filter(id = "a", type = "multiselect", options = listOf("x"), defaults = listOf("x"), param = "a", excludeOf = "b"),
                        filter(id = "b", type = "multiselect", options = listOf("x"), defaults = listOf("x"), param = "b"),
                    ),
                ),
        )
    }

    @Test
    fun `rule 27 - visibleWhen dependency cycle`() {
        val a = filter(id = "a", type = "multiselect", options = listOf("x"), param = "fa", visibleWhen = listOf(FilterConditionSpec("b", listOf("x"))))
        val b = filter(id = "b", type = "multiselect", options = listOf("x"), param = "fb", visibleWhen = listOf(FilterConditionSpec("a", listOf("x"))))
        assertTrue(ValidationCodes.FILTER_CYCLE in codes(gen(a, b)))
    }

    @Test
    fun `a linear visibleWhen chain is not a cycle`() {
        val a = filter(id = "a", type = "multiselect", options = listOf("x"), param = "fa")
        val b = filter(id = "b", type = "multiselect", options = listOf("x"), param = "fb", visibleWhen = listOf(FilterConditionSpec("a", listOf("x"))))
        assertFalse(ValidationCodes.FILTER_CYCLE in codes(gen(a, b)))
    }
}
