package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import me.manga.kira.backend.sourceconfig.validation.SourceConfigValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * PLAN §11 test 32 — `FullBundledParityIT`: *the real production document survives the whole pipeline*.
 * Parse the FULL `bundled-full.json` (45 sources: 12 generic + 33 legacy, schemaVersion 1, revision 4)
 * with the compatibility parser → validate whole (zero errors) → canonicalize → re-parse → semantic
 * equality (source count + every api identity, incl. the non-ASCII `"مانجا بارك"`) → import it
 * transactionally via the endpoint. Public artifacts intentionally contain only the 12 converted
 * generic sources; the 33 legacy rows remain admin-side migration input and are never exposed.
 */
class FullBundledParityIT : AbstractAdminSourceIT() {

    @Autowired
    private lateinit var validator: SourceConfigValidator

    @Test
    fun `the full bundled document parses, validates, canonicalizes, imports, serves and re-checksums`() {
        val raw = SourceConfigFixtures.loadFixture("bundled-full.json")

        // Parse (compatibility) → 45 sources, apis preserved incl. non-ASCII.
        val document = SourceConfigParser.parseCompatibleDocument(raw)
        assertEquals(45, document.sources.size)
        assertEquals(12, document.sources.count { it.engine == "generic" })
        assertTrue(document.sources.any { it.api == "مانجا بارك" }, "non-ASCII api identity preserved")
        val payloadApis = document.sources.map { it.api }

        // Validate the whole document → zero errors (rules 31–33 must never reject the real document).
        val validation = validator.validate(document)
        assertTrue(validation.isValid, "full bundled document must validate clean; errors: ${validation.errors}")

        // Canonicalize → re-parse canonical → semantic equality (source count + every api identity/order).
        val canonical = SourceConfigParser.canonicalDocument(document)
        val reparsed = SourceConfigParser.parseCompatibleDocument(canonical)
        assertEquals(document.sources.size, reparsed.sources.size)
        assertEquals(payloadApis, reparsed.sources.map { it.api }, "api identities + order preserved through canonical")

        // Import transactionally via the endpoint → all 45 created, one snapshot.
        val importBody =
            objectMapper.readTree(importBundled(raw).andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals(45, importBody.get("created").size(), "all 45 sources created")
        assertEquals(1L, snapshotCount(), "one snapshot for the whole import")

        // Serve it via the PUBLIC endpoint → only the 12 approved generic sources, in payload order.
        val servedResponse = getPublicDocument().andExpect { status { isOk() } }.andReturn().response
        val servedBytes = servedResponse.contentAsByteArray
        val served = servedJson.decodeFromString(
            me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument.serializer(),
            servedBytes.toString(Charsets.UTF_8),
        )
        val approvedApis = document.sources.filter { it.engine == "generic" }.map { it.api }
        assertEquals(12, served.sources.size)
        assertEquals(approvedApis, served.sources.map { it.api }, "public document contains only converted generic sources")
        assertTrue(served.sources.none { it.engine != "generic" })

        // The served raw bytes re-checksum to the ETag + X-Config-Checksum (no re-serialization drift).
        val hash = Sha256.hexUtf8(servedBytes.toString(Charsets.UTF_8))
        assertEquals(hash, servedResponse.getHeader("X-Config-Checksum"), "X-Config-Checksum == hash of served bytes")
        assertEquals("\"$hash\"", servedResponse.getHeader("ETag"), "ETag == quoted hash of served bytes")
    }
}
