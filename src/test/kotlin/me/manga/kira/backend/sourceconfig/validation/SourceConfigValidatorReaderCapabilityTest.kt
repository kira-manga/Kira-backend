package me.manga.kira.backend.sourceconfig.validation

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.domain.model.EndpointSpec
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Authoring mirror only: installed preview/package/native readiness is a separate delivery gate. */
class SourceConfigValidatorReaderCapabilityTest {
    private val validator = SourceConfigValidator()

    @Test
    fun `finite feeds survive canonical authoring and reject unsupported verbs`() {
        val original = SourceConfigFixtures.validGenericSource()
        assertFalse(SourceConfigParser.canonicalSource(original).contains("singlePage"))
        val endpoint = original.endpoints.getValue("search").copy(singlePage = true)
        val source = original.copy(endpoints = original.endpoints + ("search" to endpoint))
        assertTrue(validator.validateSource(source, emptySet()).isEmpty())
        assertEquals(source, SourceConfigParser.parseStrictSource(SourceConfigParser.canonicalSource(source)))
        for (verb in listOf("details", "chapters", "pages")) {
            val bad = source.copy(endpoints = source.endpoints + (verb to endpoint))
            assertTrue(validator.validateSource(bad, emptySet()).any { it.code == ValidationCodes.ENDPOINT_SINGLE_PAGE_UNSUPPORTED })
        }
    }

    @Test
    fun `encrypted format admits only the compiled credential free pages endpoint`() {
        val original = SourceConfigFixtures.validGenericSource()
        val endpoint = EndpointSpec("{baseUrl}/api/chapters/{chapterId}", format = "dilar-ecies-v16", root = "pages")
        val source = original.copy(baseUrl = "https://dilar.tube", headers = emptyMap(), usesCapturedHeaders = false,
            endpoints = original.endpoints + ("pages" to endpoint))
        assertTrue(validator.validateSource(source, emptySet()).isEmpty())
        val invalid = listOf(
            source.copy(usesCapturedHeaders = true), source.copy(headers = mapOf("X-Fixture" to "value")),
            source.copy(baseUrl = "https://other.example"),
            source.copy(endpoints = source.endpoints + ("home" to endpoint)),
            source.copy(endpoints = source.endpoints + ("pages" to endpoint.copy(url = "https://other.example/42"))),
            source.copy(endpoints = source.endpoints + ("pages" to endpoint.copy(method = "post-json", jsonBody = "{}"))),
        )
        assertTrue(invalid.all { candidate -> validator.validateSource(candidate, emptySet()).any {
            it.code == ValidationCodes.ENDPOINT_ENCRYPTED_READER_INVALID
        } })
    }
}
