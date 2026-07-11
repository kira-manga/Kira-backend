package me.manga.kira.backend.sourceconfig.parsing

import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit coverage for the Phase-4 deliverable `SourceConfigParser` (PLAN §7) — the STRICT vs
 * COMPATIBILITY split and canonical outbound. (The full authoring-endpoint IT, PLAN §11 test 25
 * `StrictAdminParserIT`, lands in Phase 8; this pure test pins the parser behavior it relies on.)
 */
class SourceConfigParserTest {

    @Test
    fun `strict parser rejects an unknown key naming the token`() {
        val json = SourceConfigFixtures.loadFixture("invalid-unknown-key.json")
        val ex = assertThrows(BadRequestException::class.java) { SourceConfigParser.parseStrictSource(json) }
        assertEquals("MALFORMED_CONFIG_JSON", ex.code)
        assertTrue(ex.detail.contains("usesCaptureHeaders"), "should name the offending key, got: ${ex.detail}")
    }

    @Test
    fun `strict parser rejects duplicate object keys`() {
        val json = SourceConfigFixtures.loadFixture("invalid-duplicate-key.json")
        val ex = assertThrows(BadRequestException::class.java) { SourceConfigParser.parseStrictSource(json) }
        assertEquals("MALFORMED_CONFIG_JSON", ex.code)
    }

    @Test
    fun `strict parser rejects trailing garbage`() {
        val json = SourceConfigFixtures.loadFixture("invalid-trailing-garbage.json")
        val ex = assertThrows(BadRequestException::class.java) { SourceConfigParser.parseStrictSource(json) }
        assertEquals("MALFORMED_CONFIG_JSON", ex.code)
    }

    @Test
    fun `strict parser rejects malformed JSON`() {
        assertThrows(BadRequestException::class.java) { SourceConfigParser.parseStrictSource("{ not json") }
    }

    @Test
    fun `compatibility parser accepts an unknown key the strict parser rejects`() {
        val json = SourceConfigFixtures.loadFixture("invalid-unknown-key.json")
        // Strict rejects; compatibility silently ignores the typo (app parity).
        assertThrows(BadRequestException::class.java) { SourceConfigParser.parseStrictSource(json) }
        val source = SourceConfigParser.parseCompatibleSource(json)
        assertEquals("Test", source.api)
        assertEquals("generic", source.engine)
    }

    @Test
    fun `both parsers accept a valid document`() {
        val json = SourceConfigFixtures.loadFixture("valid-document.json")
        assertEquals(1, SourceConfigParser.parseStrictDocument(json).sources.size)
        assertEquals(1, SourceConfigParser.parseCompatibleDocument(json).sources.size)
    }

    @Test
    fun `the full real bundled document parses STRICTLY - proving the model mirrors the app field-for-field`() {
        val json = SourceConfigFixtures.loadFixture("bundled-full.json")
        val doc = SourceConfigParser.parseStrictDocument(json)
        assertEquals(1, doc.schemaVersion)
        assertEquals(4L, doc.revision)
        assertEquals(45, doc.sources.size)
        assertEquals(12, doc.sources.count { it.engine == "generic" })
        assertEquals(33, doc.sources.count { it.engine == "legacy" })
    }

    @Test
    fun `canonical outbound round-trips the full bundled document (shape parity)`() {
        val json = SourceConfigFixtures.loadFixture("bundled-full.json")
        val doc = SourceConfigParser.parseCompatibleDocument(json)
        val canonical = SourceConfigParser.canonicalDocument(doc)
        val reparsed = SourceConfigParser.parseCompatibleDocument(canonical)
        assertEquals(doc, reparsed, "re-parsed canonical document must be semantically equal")
        assertEquals(doc.sources.map { it.api }, reparsed.sources.map { it.api }, "api identities + order preserved")
        assertFalse(canonical.endsWith("\n"), "no trailing newline")
    }
}
