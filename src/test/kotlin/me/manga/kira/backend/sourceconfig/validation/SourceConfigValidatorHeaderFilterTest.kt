package me.manga.kira.backend.sourceconfig.validation

import me.manga.kira.backend.sourceconfig.HeaderFilterSafetyFixtures
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.domain.model.FilterConditionSpec
import me.manga.kira.backend.sourceconfig.domain.model.FilterDefinition
import me.manga.kira.backend.sourceconfig.domain.model.FilterOptionSpec
import me.manga.kira.backend.sourceconfig.domain.model.FilterRequestSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class SourceConfigValidatorHeaderFilterTest {
    private val validator = SourceConfigValidator()

    @ParameterizedTest(name = "header name case {index}")
    @MethodSource("headerNames")
    fun `both entrypoints apply exact token and credential name policy`(name: String, expectedCode: String?) {
        val source = SourceConfigFixtures.validGenericSource().copy(
            filters = listOf(HeaderFilterSafetyFixtures.headerFilter(name).copy(default = HeaderFilterSafetyFixtures.OPTION)),
        )
        val result = validator.validate(SourceConfigFixtures.document(source))
        val expected = listOfNotNull(expectedCode) + if (name.isBlank()) listOf(ValidationCodes.FILTER_REQUEST_PARAM_BLANK) else emptyList()

        assertEquals(expected.sorted(), result.errors.map { it.code }.sorted())
        assertEquals(expected.isEmpty(), result.isValid)
        assertEquals(result.errors, validator.validateSource(source, emptySet()))
        assertTrue(result.errors.all { it.path == "sources[GenSrc].filters[choice].request.param" })
        assertTrue(result.errors.all { it.message.length <= 160 })
        assertFalse(result.toString().contains(HeaderFilterSafetyFixtures.OPTION))
    }

    @ParameterizedTest(name = "dynamic value case {index}")
    @MethodSource("dynamicFilters")
    fun `otherwise valid sensitive filters are rejected independently of values and visibility`(filter: FilterDefinition) {
        val gate = HeaderFilterSafetyFixtures.headerFilter("X-Gate").copy(id = "gate", type = "toggle", default = "false")
        val source = SourceConfigFixtures.validGenericSource().copy(filters = listOf(gate, filter))
        val result = validator.validate(SourceConfigFixtures.document(source))

        assertEquals(listOf(ValidationCodes.SECRET_LIKE_HEADER), result.errors.map { it.code })
        assertEquals("sources[GenSrc].filters[choice].request.param", result.errors.single().path)
        assertEquals(result.errors, validator.validateSource(source, emptySet()))
        HeaderFilterSafetyFixtures.sentinels.forEach { assertFalse(result.toString().contains(it)) }

        // The same values, encoding, defaults and conditions are valid with a non-sensitive name.
        val safe = source.copy(filters = listOf(gate, filter.copy(request = filter.request.copy(param = "X-Content-Lang"))))
        assertTrue(validator.validate(SourceConfigFixtures.document(safe)).isValid)
        assertTrue(validator.validateSource(safe, emptySet()).isEmpty())
    }

    @Test
    fun `all diagnostic findings survive without any submitted value in the complete result`() {
        val source = HeaderFilterSafetyFixtures.diagnosticSource("Diagnostics")
        val result = validator.validate(SourceConfigFixtures.document(source))
        assertFalse(result.isValid)
        assertEquals(HeaderFilterSafetyFixtures.diagnosticErrorCounts, result.errors.groupingBy { it.code }.eachCount())
        assertEquals(result.errors, validator.validateSource(source, emptySet()))
        assertEquals(
            setOf(
                "selection.options[1].value", "selection.default", "selection.request.param",
                "multiple.defaults", "multiple.request.param", "toggle.default", "toggle.request.param",
                "number.default", "number.request.param", "dependent.visibleWhen[0].anyOf",
            ).map { "sources[Diagnostics].filters[${it.substringBefore('.')}].${it.substringAfter('.')}" }.toSet(),
            result.errors.map { it.path }.toSet(),
        )
        HeaderFilterSafetyFixtures.sentinels.forEach { assertFalse(result.toString().contains(it), "diagnostic value must not be echoed") }
    }

    @Test
    fun `static placeholder allowlist remains exact configurable and static only`() {
        val source = SourceConfigFixtures.validGenericSource().copy(headers = mapOf("aUtHoRiZaTiOn" to "Bearer null"))
        assertTrue(validator.validate(SourceConfigFixtures.document(source)).isValid)

        val configured = SourceConfigValidator(publicHeaderPlaceholderValues = setOf("PUBLIC_ONLY"))
        fun codes(name: String, value: String): List<String> = configured.validate(
            SourceConfigFixtures.document(source.copy(headers = mapOf(name to value))),
        ).errors.map { it.code }

        assertTrue(codes("aUtHoRiZaTiOn", "PUBLIC_ONLY").isEmpty())
        assertEquals(listOf(ValidationCodes.SECRET_LIKE_HEADER), codes("Authorization", "Bearer null"))
        assertEquals(listOf(ValidationCodes.SECRET_LIKE_HEADER), codes("Authorization", " PUBLIC_ONLY"))
        assertEquals(listOf(ValidationCodes.FORBIDDEN_HEADER), codes("Cookie", "PUBLIC_ONLY"))
        assertEquals(listOf(ValidationCodes.HEADER_NAME_INVALID), codes(" Authorization", "PUBLIC_ONLY"))
        val dynamic = source.copy(headers = emptyMap(), filters = listOf(HeaderFilterSafetyFixtures.headerFilter().copy(default = "PUBLIC_ONLY")))
        assertEquals(listOf(ValidationCodes.SECRET_LIKE_HEADER), configured.validateSource(dynamic, emptySet()).map { it.code })
    }

    companion object {
        @JvmStatic
        fun headerNames(): List<Arguments> =
            listOf("", " ", " Authorization", "Authorization ", "Cookie\t", "X\r\nName", "X\u0000Name", "X-É", "X:Lang", "genre[]", "X/Lang", "X Lang")
                .map { Arguments.of(it, ValidationCodes.HEADER_NAME_INVALID) } +
                listOf("cOoKiE", "SET-cookie", "Proxy-AUTHorization").map { Arguments.of(it, ValidationCodes.FORBIDDEN_HEADER) } +
                listOf("aUtHoRiZaTiOn", "x-API-key", "Api-Key", "X-AUTH-TOKEN", "X-Session-ToKeN", "X-SeCrEt-Id", "PASSWORD-Hint")
                    .map { Arguments.of(it, ValidationCodes.SECRET_LIKE_HEADER) } +
                listOf("X-Lang", "Accept-Language", "X-Content-Lang", "X!#\$%&'*+-.^_`|~09").map { Arguments.of(it, null) }

        @JvmStatic
        fun dynamicFilters(): List<FilterDefinition> {
            val base = HeaderFilterSafetyFixtures.headerFilter()
            return listOf(
                base.copy(type = "select", options = listOf(FilterOptionSpec(HeaderFilterSafetyFixtures.OPTION))),
                base.copy(
                    type = "multiselect",
                    options = listOf(FilterOptionSpec("public")),
                    defaults = listOf("public"),
                    request = FilterRequestSpec(target = "header", param = "X-API-Key", encode = "csv", delimiter = HeaderFilterSafetyFixtures.DELIMITER),
                ),
                base.copy(
                    type = "toggle",
                    default = "false",
                    request = FilterRequestSpec(
                        target = "header",
                        param = "X-Auth-Token",
                        trueValue = HeaderFilterSafetyFixtures.TRUE_VALUE,
                        falseValue = HeaderFilterSafetyFixtures.FALSE_VALUE,
                    ),
                ),
                base.copy(request = FilterRequestSpec(target = "header", param = "X-Secret", omitIfEmpty = false)),
                base.copy(type = "number", request = FilterRequestSpec(target = "header", param = "X-Password")),
                base.copy(
                    default = "Bearer null",
                    required = true,
                    visibleWhen = listOf(FilterConditionSpec(filter = "gate", anyOf = listOf("true"))),
                ),
            )
        }
    }
}
