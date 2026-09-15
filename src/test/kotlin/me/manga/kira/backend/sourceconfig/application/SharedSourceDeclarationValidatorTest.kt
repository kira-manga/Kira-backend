package me.manga.kira.backend.sourceconfig.application

import me.manga.kira.backend.sourceconfig.DeclarationValidationFixtures
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.domain.model.EndpointSpec
import me.manga.kira.backend.sourceconfig.domain.model.FieldSpec
import me.manga.kira.backend.sourceconfig.domain.model.IconSpec
import me.manga.kira.backend.sourceconfig.domain.model.TransformSpec
import me.manga.kira.backend.sourceconfig.validation.ServerStrategyCatalog
import me.manga.kira.backend.sourceconfig.validation.SourceConfigValidator
import me.manga.kira.backend.sourceconfig.validation.ValidationCodes
import me.manga.kira.backend.sourceconfig.validation.ValidationError
import me.manga.kira.backend.sourceconfig.validation.rules.ComplexityRules
import me.manga.kira.source.engine.SourceDeclarationCapabilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class SharedSourceDeclarationValidatorTest {
    private val adapter = SharedSourceDeclarationValidator()
    private val strategies = ServerStrategyCatalog()
    private val validator = SourceConfigValidator(declarations = adapter)

    @ParameterizedTest(name = "shared declaration {0}")
    @MethodSource("representativeIds")
    fun `representative shared findings retain machine code relative path and static message`(id: String) {
        val fixture = DeclarationValidationFixtures.fixture(id)
        val source = DeclarationValidationFixtures.backendSource(fixture)
        val findings = adapter.validate(source, strategies)
        assertEquals(
            fixture.expectedFindings.map { it.code to "sources[${source.api}].${it.path}" },
            findings.map { it.code to it.path },
        )
        assertEquals(
            SourceDeclarationCapabilities().validate(fixture.source).map {
                ValidationError(it.code, "sources[${source.api}].${it.path}", it.message)
            },
            findings,
            "the adapter must not parse or replace the checker's static messages",
        )
    }

    @Test
    fun `model conversion preserves ordinal map paths without exposing submitted names or values`() {
        val base = SourceConfigFixtures.validGenericSource()
        val source = base.copy(
            endpoints = base.endpoints + mapOf(
                "search" to EndpointSpec(
                    url = "{baseUrl}/search",
                    method = "post-form",
                    formBody = linkedMapOf("z_private_form_name" to "{baseUrl}", "a_private_form_name" to "{unknown_form_value}"),
                ),
            ),
            fields = mapOf(
                "item.url" to FieldSpec(
                    template = "{z_private_var_name}/{a_private_var_name}",
                    vars = linkedMapOf("z_private_var_name" to "slug", "a_private_var_name" to "id|unknown_pipe_value"),
                ),
            ),
        )
        val findings = adapter.validate(source, strategies)
        assertEquals(2, findings.size)
        assertEquals(
            setOf(
                "template.variable.unsupported" to "sources[GenSrc].endpoints[search].formBody[1].value",
                "field.variable.transform" to "sources[GenSrc].fields[item.url].vars[1]",
            ),
            findings.map { it.code to it.path }.toSet(),
            "canonical key sorting would incorrectly move both offending map entries to index zero",
        )
        listOf("private_form_name", "private_var_name", "unknown_form_value", "unknown_pipe_value").forEach {
            assertFalse(findings.toString().contains(it), "structured findings must not echo author-controlled map entries")
        }
    }

    @Test
    fun `variable pipes honor the same caller catalog as existing transform rules`() {
        val source = SourceConfigFixtures.validGenericSource().copy(
            fields = mapOf(
                "item.url" to FieldSpec(
                    template = "{id}",
                    vars = mapOf("id" to "slug|trim"),
                    transform = listOf(TransformSpec("trim")),
                ),
            ),
        )
        assertTrue(validator.validate(SourceConfigFixtures.document(source)).isValid)
        val restricted = SourceConfigValidator(
            declarations = adapter,
            strategies = ServerStrategyCatalog(transforms = ServerStrategyCatalog.TRANSFORMS - "trim"),
        )
        val result = restricted.validate(SourceConfigFixtures.document(source))
        assertEquals(setOf(ValidationCodes.UNKNOWN_TRANSFORM, "field.variable.transform"), result.errors.map { it.code }.toSet())
        assertEquals(result.errors, restricted.validateSource(source, emptySet()))
    }

    @Test
    fun `capability findings augment server errors and advisory warnings at both entrypoints`() {
        val source = DeclarationValidationFixtures.completeSource("unknown_url_variable", "Declarations").copy(
            headers = mapOf("Cookie" to "fixture-only-public-value"),
            icon = IconSpec(resourceKey = "unbundled_icon"),
        )
        val result = validator.validate(SourceConfigFixtures.document(source))
        assertFalse(result.isValid)
        assertEquals(setOf(ValidationCodes.FORBIDDEN_HEADER, "template.variable.unsupported"), result.errors.map { it.code }.toSet())
        assertEquals(listOf(ValidationCodes.UNKNOWN_ICON_KEY), result.warnings.map { it.code })
        assertEquals(result.errors, validator.validateSource(source, emptySet()))
    }

    @Test
    fun `schema and collection guards still precede capability inspection and legacy remains outside it`() {
        val unsupported = DeclarationValidationFixtures.completeSource("unknown_url_variable", "Guarded")
        val document = SourceConfigFixtures.document(unsupported)
        assertEquals(
            listOf(ValidationCodes.UNSUPPORTED_SCHEMA_VERSION),
            validator.validate(document.copy(schemaVersion = 2)).errors.map { it.code },
        )
        assertEquals(
            listOf(ValidationCodes.DOCUMENT_COMPLEXITY_EXCEEDED),
            validator.validate(document.copy(sources = List(ComplexityRules.MAX_SOURCES + 1) { unsupported })).errors.map { it.code },
        )
        val oversized = unsupported.copy(fields = (0..ComplexityRules.MAX_COLLECTION_ENTRIES).associate { "unused.$it" to FieldSpec() })
        val sourceErrors = validator.validateSource(oversized, emptySet())
        assertTrue(sourceErrors.isNotEmpty())
        assertTrue(sourceErrors.all { it.code == ValidationCodes.SOURCE_COMPLEXITY_EXCEEDED })
        assertTrue(validator.validateSource(unsupported.copy(engine = "legacy"), emptySet()).isEmpty())
    }

    companion object {
        @JvmStatic
        fun representativeIds(): List<String> = DeclarationValidationFixtures.representativeIds
    }
}
