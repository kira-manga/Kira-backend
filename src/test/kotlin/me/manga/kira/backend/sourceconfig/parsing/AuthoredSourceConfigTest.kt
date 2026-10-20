package me.manga.kira.backend.sourceconfig.parsing

import kotlinx.coroutines.runBlocking
import me.manga.kira.backend.sourceconfig.application.SharedSourceDeclarationValidator
import me.manga.kira.backend.sourceconfig.application.StructuralAuthoringGate
import me.manga.kira.backend.sourceconfig.validation.SourceConfigValidator
import me.manga.kira.source.contracts.SourceChapter
import me.manga.kira.source.contracts.SourceEngineError
import me.manga.kira.source.contracts.SourceEngineResult
import me.manga.kira.source.contracts.SourceHeaderProvider
import me.manga.kira.source.contracts.SourceMangaRef
import me.manga.kira.source.contracts.SourceRequest
import me.manga.kira.source.contracts.SourceResponse
import me.manga.kira.source.contracts.SourceTransport
import me.manga.kira.source.engine.GenericSourceEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import me.manga.kira.source.contracts.SourceConfigParser as SharedSourceConfigParser

/** Authoring/serialization safety, not provider operations or publication acceptance. */
class AuthoredSourceConfigTest {
    @Test
    fun `authored sources strictly parse validate and preserve their model through canonicalization`() {
        val validator = SourceConfigValidator(declarations = SharedSourceDeclarationValidator())
        for (api in listOf("Lavatoons", "Manhwatop", "Mangatuk", "Dilar", "Manhwaweb", "Mangahub")) {
            val source = SourceConfigParser.parseStrictSource(Files.readString(Path.of("config", "sources", "$api.json")))
            assertEquals(api, source.api)
            assertEquals("generic", source.engine)
            StructuralAuthoringGate.check(source, api)
            assertFalse(source.enabled, "Authoring input must not imply publication authority")
            assertTrue(validator.validateSource(source, emptySet()).isEmpty(), api)
            assertEquals(source, SourceConfigParser.parseStrictSource(SourceConfigParser.canonicalSource(source)))
        }
    }

    @Test
    fun `optional authored sources retain empty featured and invalid reader boundaries through shared parsing`() = runBlocking {
        for (api in listOf("Manhwaweb", "Mangahub")) {
            val source = SourceConfigParser.parseStrictSource(Files.readString(Path.of("config", "sources", "$api.json")))
            val canonical = SourceConfigParser.canonicalSource(source)
            val shared = when (val parsed = SharedSourceConfigParser.parseSource(canonical)) {
                is SourceEngineResult.Success -> parsed.value
                is SourceEngineResult.Failure -> error("Canonical optional source was refused by the shared parser")
            }
            assertEquals(api, shared.api)
            assertFalse(shared.enabled)
            assertFalse(shared.endpoints.containsKey("featured"))
            val requests = mutableListOf<SourceRequest>()
            val transport = SourceTransport { request ->
                requests += request
                SourceResponse(200, "<html><body>Age confirmation required</body></html>")
            }
            val headers = SourceHeaderProvider { error("Optional sources must not read captured credentials") }
            val engine = GenericSourceEngine(shared, transport, headers)
            val featured = engine.featured(1)
            assertTrue(featured is SourceEngineResult.Success && featured.value.isEmpty(), api)
            assertTrue(requests.isEmpty(), "An omitted optional endpoint must not issue HTTP")

            if (api == "Mangahub") {
                val manga = SourceMangaRef(api, shared.language, "Fixture", "https://mangahub.ru/manga/fixture", "")
                val chapter = SourceChapter("1", "Fixture chapter", "https://mangahub.ru/read/fixture/1", null)
                assertEquals(SourceEngineResult.Failure(SourceEngineError.InvalidResponse), engine.pages(manga, chapter))
                assertEquals(listOf(chapter.url), requests.map { it.url })
            }
        }
    }
}
