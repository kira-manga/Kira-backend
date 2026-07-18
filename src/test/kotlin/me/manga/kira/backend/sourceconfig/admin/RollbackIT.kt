package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 15 — `RollbackIT`: publish r1, publish r2 (changed baseUrl), rollback to 1 → a NEW r3
 * with r1's payload is published; the document serves r1's baseUrl again; revision numbers only grow;
 * the document revision grows; rolling back a *disabled* source leaves it disabled (no lifecycle
 * restoration) (PLAN §9 forward-roll).
 */
class RollbackIT : AbstractAdminSourceIT() {

    private val urlA = "https://a.example.com"
    private val urlB = "https://b.example.com"

    private fun servedBaseUrl(documentRevision: Long, api: String): String = servedDocument(documentRevision).sources.first { it.api == api }.baseUrl

    private fun latestRevisionNumber(api: String): Int = jdbcTemplate.queryForObject(
        "SELECT max(r.revision_number) FROM source_config_revisions r " +
            "JOIN source_configs s ON s.id = r.source_config_id WHERE s.api = ?",
        Int::class.java,
        api,
    )!!

    @Test
    fun `rollback copies old content into a new published revision`() {
        val api = "Rollme"
        createSource(SourceConfigFixtures.validGenericSource(api).copy(baseUrl = urlA)).andExpect { status { isCreated() } }
        val doc1 = docRevisionOf(publish(api, 1).andExpect { status { isOk() } })
        assertEquals(urlA, servedBaseUrl(doc1, api))

        createRevision(api, SourceConfigFixtures.validGenericSource(api).copy(baseUrl = urlB)).andExpect { status { isCreated() } }
        val doc2 = docRevisionOf(publish(api, 2).andExpect { status { isOk() } })
        assertEquals(urlB, servedBaseUrl(doc2, api))
        assertTrue(doc2 > doc1, "the document revision must grow")

        // Roll back to revision 1 → a NEW r3 with r1's content, published.
        val rollbackBody = rollback(api, 1).andExpect { status { isOk() } }.andReturn().response.contentAsString
        val rollback = objectMapper.readTree(rollbackBody)
        assertEquals(3, rollback.get("newRevisionNumber").asInt(), "rollback creates a new highest revision")
        val doc3 = rollback.get("documentRevision").asLong()
        assertTrue(doc3 > doc2, "the document revision must grow again")

        assertEquals(urlA, servedBaseUrl(doc3, api), "the document now serves r1's baseUrl")
        assertEquals(3, latestRevisionNumber(api), "revision numbers only grow (r1,r2,r3)")
    }

    @Test
    fun `rollback of a disabled source leaves it disabled`() {
        val api = "RollDisabled"
        createSource(SourceConfigFixtures.validGenericSource(api).copy(baseUrl = urlA)).andExpect { status { isCreated() } }
        publish(api, 1).andExpect { status { isOk() } }
        createRevision(api, SourceConfigFixtures.validGenericSource(api).copy(baseUrl = urlB)).andExpect { status { isCreated() } }
        publish(api, 2).andExpect { status { isOk() } }
        disable(api).andExpect { status { isOk() } }

        rollback(api, 1).andExpect { status { isOk() } }

        val status = jdbcTemplate.queryForObject("SELECT status FROM source_configs WHERE api = ?", String::class.java, api)
        assertEquals("disabled", status, "rollback must not re-enable a disabled source")
    }
}
