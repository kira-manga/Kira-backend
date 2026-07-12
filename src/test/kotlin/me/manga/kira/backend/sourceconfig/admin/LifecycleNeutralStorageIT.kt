package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 45 — `LifecycleNeutralStorageIT`: stored revision content NEVER carries server lifecycle.
 * The stored canonical revision bytes contain no `lifecycle` key; the assembled document injects
 * `disabled`/retired-as-`removed` at materialization only (PLAN §9). The import halves (Phase 8) prove
 * that importing a `disabled` stanza stores neutral content + initial status `disabled`, and re-importing
 * it is a no-op (the §9 neutral normalization is what makes that idempotency hold — §12.2).
 */
class LifecycleNeutralStorageIT : AbstractAdminSourceIT() {

    private fun publishedRevisionJson(api: String): String =
        jdbcTemplate.queryForObject(
            "SELECT r.config_canonical_json FROM source_config_revisions r " +
                "JOIN source_configs s ON s.id = r.source_config_id " +
                "WHERE s.api = ? AND r.status = 'published'",
            String::class.java,
            api,
        )!!

    @Test
    fun `stored content is lifecycle-neutral while the assembled document injects the served value`() {
        val api = "Neutral"
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }
        publish(api, 1).andExpect { status { isOk() } }

        // Stored canonical bytes: no lifecycle key (default "active" is omitted by kcj-1).
        assertFalse(publishedRevisionJson(api).contains("lifecycle"), "stored revision bytes must omit lifecycle")

        // Disabling injects lifecycle:"disabled" into the SERVED document, but the stored bytes are untouched.
        val disabledDoc = docRevisionOf(disable(api).andExpect { status { isOk() } })
        assertEquals("disabled", servedDocument(disabledDoc).sources.first { it.api == api }.lifecycle)
        assertFalse(
            publishedRevisionJson(api).contains("lifecycle"),
            "the stored published revision bytes must STILL omit lifecycle after disable",
        )
    }

    @Test
    fun `importing a disabled stanza stores neutral content with initial status disabled, and re-import is a no-op`() {
        val api = "ImportNeutral"
        val document = SourceConfigFixtures.document(SourceConfigFixtures.validGenericSource(api).copy(lifecycle = "disabled"))

        val created =
            objectMapper.readTree(importBundled(document).andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertTrue(created.get("created").map { it.asText() }.contains(api), "the disabled stanza is created")

        // Stored content is lifecycle-NEUTRAL (no lifecycle key), and the payload lifecycle set the status.
        assertFalse(publishedRevisionJson(api).contains("lifecycle"), "imported content must be stored lifecycle-neutral")
        assertEquals("disabled", sourceStatus(api), "the payload lifecycle sets the initial status")
        // The assembled document injects lifecycle:"disabled" at materialization only.
        assertEquals("disabled", publicServedDocument().sources.first { it.api == api }.lifecycle)

        // Re-import is a no-op — the neutralized content compares equal to its stored twin (PLAN §9/§12.2).
        val snapshotsBefore = snapshotCount()
        val reimport =
            objectMapper.readTree(importBundled(document).andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertTrue(reimport.get("unchanged").map { it.asText() }.contains(api), "re-import reports unchanged")
        assertEquals(snapshotsBefore, snapshotCount(), "re-import materializes no new snapshot")
    }
}
