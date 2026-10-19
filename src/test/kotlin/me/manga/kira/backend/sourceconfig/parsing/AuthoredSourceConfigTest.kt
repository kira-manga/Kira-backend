package me.manga.kira.backend.sourceconfig.parsing

import me.manga.kira.backend.sourceconfig.application.SharedSourceDeclarationValidator
import me.manga.kira.backend.sourceconfig.validation.SourceConfigValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Authoring/serialization safety, not provider operations or publication acceptance. */
class AuthoredSourceConfigTest {
    @Test
    fun `authored sources strictly parse validate and preserve their model through canonicalization`() {
        val validator = SourceConfigValidator(declarations = SharedSourceDeclarationValidator())
        for (api in listOf("Lavatoons", "Manhwatop", "Mangatuk", "Dilar")) {
            val source = SourceConfigParser.parseStrictSource(Files.readString(Path.of("config", "sources", "$api.json")))
            assertEquals(api, source.api)
            assertEquals("generic", source.engine)
            assertFalse(source.enabled, "Authoring input must not imply publication authority")
            assertTrue(validator.validateSource(source, emptySet()).isEmpty(), api)
            assertEquals(source, SourceConfigParser.parseStrictSource(SourceConfigParser.canonicalSource(source)))
        }
    }
}
