package me.manga.kira.backend.sourceconfig.validation

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.domain.model.FilterConditionSpec
import me.manga.kira.backend.sourceconfig.domain.model.FilterDefinition
import me.manga.kira.backend.sourceconfig.domain.model.FilterRequestSpec
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument
import me.manga.kira.backend.sourceconfig.validation.rules.ComplexityRules
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceConfigValidatorComplexityTest {
    private val validator = SourceConfigValidator()

    @Test
    fun `oversized documents fail before semantic traversal`() {
        val source = SourceConfigFixtures.validGenericSource("bounded")
        val result =
            validator.validate(
                SourceConfigDocument(
                    schemaVersion = 1,
                    sources = List(ComplexityRules.MAX_SOURCES + 1) { source.copy(api = "source-$it") },
                ),
            )

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == ValidationCodes.DOCUMENT_COMPLEXITY_EXCEEDED })
    }

    @Test
    fun `deep visibility chains are rejected by limits without recursive traversal`() {
        val filters =
            List(ComplexityRules.MAX_FILTERS + 1) { index ->
                FilterDefinition(
                    id = "f$index",
                    label = "Filter $index",
                    type = "text",
                    request = FilterRequestSpec(target = "query", param = "f$index"),
                    visibleWhen =
                    if (index == 0) {
                        emptyList()
                    } else {
                        listOf(FilterConditionSpec(filter = "f${index - 1}", anyOf = listOf("x")))
                    },
                )
            }
        val source = SourceConfigFixtures.validGenericSource("bounded").copy(filters = filters)
        val result = validator.validate(SourceConfigFixtures.document(source))

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == ValidationCodes.SOURCE_COMPLEXITY_EXCEEDED })
    }
}
