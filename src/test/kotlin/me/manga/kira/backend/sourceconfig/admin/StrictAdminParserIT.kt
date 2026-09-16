package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 25 — `StrictAdminParserIT`: *authoring typos cannot silently pass*. The STRICT authoring
 * endpoints (create source / create revision) reject an unknown field, a duplicate JSON key, trailing
 * garbage, and malformed JSON with a 400 that names the offending token (PLAN §7). The SAME payloads
 * pass the COMPATIBILITY import parser where the app would accept them — an unknown field imports fine
 * (with a `warnings[]` entry so it is visible, not silent), while genuinely malformed JSON fails
 * everywhere. (No prior strict-parser IT existed from Phase 6 — this is the full test.)
 */
class StrictAdminParserIT : AbstractAdminSourceIT() {

    private fun bodyOf(actions: org.springframework.test.web.servlet.ResultActionsDsl): String = actions.andReturn().response.contentAsString

    // --- STRICT authoring: create source ------------------------------------------------------------

    @Test
    fun `create rejects an unknown field with a 400 that names the offending key`() {
        val body =
            bodyOf(
                createSourceRaw(SourceConfigFixtures.loadFixture("invalid-unknown-key.json"))
                    .andExpect { status { isBadRequest() } },
            )
        assertTrue(body.contains("usesCaptureHeaders"), "the 400 must name the offending key, got: $body")
    }

    @Test
    fun `create rejects a duplicate json key with a 400`() {
        createSourceRaw(SourceConfigFixtures.loadFixture("invalid-duplicate-key.json"))
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `create rejects trailing garbage with a 400`() {
        createSourceRaw(SourceConfigFixtures.loadFixture("invalid-trailing-garbage.json"))
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `create rejects malformed json with a 400`() {
        createSourceRaw("{ not json").andExpect { status { isBadRequest() } }
    }

    // --- STRICT authoring: create revision ----------------------------------------------------------

    @Test
    fun `create revision rejects an unknown field with a 400`() {
        // A valid "Test" source exists; a revision body with the unknown-key fixture (api "Test") is a 400.
        createSource(SourceConfigFixtures.validGenericSource("Test")).andExpect { status { isCreated() } }
        val body =
            bodyOf(
                createRevisionRaw("Test", SourceConfigFixtures.loadFixture("invalid-unknown-key.json"))
                    .andExpect { status { isBadRequest() } },
            )
        assertTrue(body.contains("usesCaptureHeaders"), "the 400 must name the offending key, got: $body")
    }

    // --- COMPATIBILITY import: the same payloads the app would accept ------------------------------

    @Test
    fun `the compatibility import parser accepts an unknown field and surfaces a warning`() {
        // A complete, valid generic stanza carrying the unknown typo key — imports fine, but visibly.
        val docWithUnknownKey =
            """
            {"schemaVersion":1,"sources":[
              {"api":"UnknownKeyImport","language":"en","baseUrl":"https://example.com",
               "imageBase":"https://img.example.com","engine":"generic","usesCaptureHeaders":true,"endpoints":{
               "home":{"url":"{baseUrl}/home?page={page}","format":"json"},
               "search":{"url":"{baseUrl}/search?q={queryEncoded}&page={page}","format":"json"},
               "details":{"url":"{itemUrl}","format":"json"},
               "pages":{"url":"{chapterUrl}","format":"json"}}}
            ]}
            """.trimIndent()

        val body =
            objectMapper.readTree(
                importBundled(docWithUnknownKey).andExpect { status { isOk() } }.andReturn().response.contentAsString,
            )

        assertTrue(body.get("created").map { it.asText() }.contains("UnknownKeyImport"), "unknown field imports fine")
        val warningCodes = body.get("warnings").map { it.get("code").asText() }
        assertTrue(warningCodes.contains("NON_SCHEMA_KEYS"), "the unknown key must surface a warning, got: $warningCodes")
    }

    @Test
    fun `the compatibility import parser rejects genuinely malformed json`() {
        importBundled("{ not json").andExpect { status { isBadRequest() } }
    }
}
