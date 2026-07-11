package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 45 (non-import halves) — `LifecycleNeutralStorageIT`: stored revision content NEVER
 * carries server lifecycle. The stored canonical revision bytes contain no `lifecycle` key; the assembled
 * document injects `disabled`/retired-as-`removed` at materialization only (PLAN §9). (Importing a
 * `disabled` stanza + no-op re-import are the Phase-8 import halves.)
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
}
