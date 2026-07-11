package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 46 — `EmptyDocumentPublishIT`: removing the last source yields a valid, served, empty
 * document. Walk the only published source through disable→retire→remove → a new snapshot publishes with
 * zero sources; the canonical bytes omit the default-empty `sources`; re-parsing yields an empty list
 * (PLAN §9 empty-document rule). (The public `/document` + `/document/meta` serving is Phase 7 — asserted
 * here through the admin raw-bytes endpoint.)
 */
class EmptyDocumentPublishIT : AbstractAdminSourceIT() {

    @Test
    fun `removing the last source publishes a valid empty document`() {
        val api = "Only"
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }
        publish(api, 1).andExpect { status { isOk() } }
        disable(api).andExpect { status { isOk() } }
        retire(api).andExpect { status { isOk() } }
        val emptyDoc = docRevisionOf(remove(api).andExpect { status { isOk() } })

        // The snapshot has zero sources.
        val sourceCount =
            jdbcTemplate.queryForObject(
                "SELECT source_count FROM published_documents WHERE document_revision = ?",
                Int::class.java,
                emptyDoc,
            )
        assertEquals(0, sourceCount)

        // Canonical bytes omit the default-empty `sources` (kcj-1 default-omission).
        val json =
            jdbcTemplate.queryForObject(
                "SELECT document_json FROM published_documents WHERE document_revision = ?",
                String::class.java,
                emptyDoc,
            )!!
        assertFalse(json.contains("\"sources\""), "an empty sources list must be omitted from the canonical bytes")

        // Served + re-parsed → empty list; the pointer + ETag are fresh.
        assertEquals(emptyDoc, latestPointer())
        assertTrue(servedDocument(emptyDoc).sources.isEmpty())
    }
}
