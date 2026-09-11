package me.manga.kira.backend.sourceconfig

import me.manga.kira.backend.sourceconfig.domain.model.FilterConditionSpec
import me.manga.kira.backend.sourceconfig.domain.model.FilterDefinition
import me.manga.kira.backend.sourceconfig.domain.model.FilterOptionSpec
import me.manga.kira.backend.sourceconfig.domain.model.FilterRequestSpec
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.validation.ValidationCodes

/** Synthetic values only. Reused across pure diagnostics and real persistence/publication tests. */
object HeaderFilterSafetyFixtures {
    const val OPTION = "fixture-only-duplicate-option-sentinel"
    const val SELECT_DEFAULT = "fixture-only-select-default-sentinel"
    const val MULTI_DEFAULT = "fixture-only-multiselect-default-sentinel"
    const val TOGGLE_DEFAULT = "fixture-only-toggle-default-sentinel"
    const val NUMBER_DEFAULT = "fixture-only-number-default-sentinel"
    const val ANY_OF = "fixture-only-condition-value-sentinel"
    const val TRUE_VALUE = "fixture-only-toggle-true-wire-sentinel"
    const val FALSE_VALUE = "fixture-only-toggle-false-wire-sentinel"
    const val DELIMITER = "fixture-only-csv-delimiter-sentinel"

    val sentinels = listOf(OPTION, SELECT_DEFAULT, MULTI_DEFAULT, TOGGLE_DEFAULT, NUMBER_DEFAULT, ANY_OF, TRUE_VALUE, FALSE_VALUE, DELIMITER)

    val diagnosticErrorCounts = mapOf(
        ValidationCodes.FILTER_OPTION_VALUE_DUPLICATE to 1,
        ValidationCodes.FILTER_DEFAULT_NOT_OPTION to 2,
        ValidationCodes.FILTER_TOGGLE_DEFAULT_INVALID to 1,
        ValidationCodes.FILTER_NUMBER_DEFAULT_INVALID to 1,
        ValidationCodes.FILTER_VISIBLEWHEN_OUT_OF_VOCABULARY to 1,
        ValidationCodes.SECRET_LIKE_HEADER to 4,
    )

    /** Otherwise valid: rejection must come from the header name, not an invalid default or option. */
    fun unsafeSource(api: String): SourceConfig = SourceConfigFixtures.validGenericSource(api).copy(
        filters = listOf(headerFilter().copy(default = OPTION)),
    )

    fun headerFilter(param: String = "Authorization"): FilterDefinition = FilterDefinition(
        id = "choice",
        label = "Choice",
        type = "text",
        request = FilterRequestSpec(target = "header", param = param),
    )

    /** All old value-bearing findings must survive alongside four new name-policy findings. */
    fun diagnosticSource(api: String): SourceConfig = SourceConfigFixtures.validGenericSource(api).copy(
        filters = listOf(
            headerFilter().copy(
                id = "selection",
                type = "select",
                options = listOf(FilterOptionSpec(OPTION), FilterOptionSpec(OPTION)),
                default = SELECT_DEFAULT,
            ),
            headerFilter("X-Session-Token").copy(
                id = "multiple",
                type = "multiselect",
                options = listOf(FilterOptionSpec("public")),
                defaults = listOf(MULTI_DEFAULT),
                request = FilterRequestSpec(target = "header", param = "X-Session-Token", encode = "csv", delimiter = DELIMITER),
            ),
            headerFilter("X-Secret").copy(
                id = "toggle",
                type = "toggle",
                default = TOGGLE_DEFAULT,
                request = FilterRequestSpec(target = "header", param = "X-Secret", trueValue = TRUE_VALUE, falseValue = FALSE_VALUE),
            ),
            headerFilter("X-Password").copy(id = "number", type = "number", default = NUMBER_DEFAULT),
            headerFilter("X-Lang").copy(
                id = "dependent",
                visibleWhen = listOf(FilterConditionSpec(filter = "selection", anyOf = listOf(ANY_OF))),
            ),
        ),
    )
}
