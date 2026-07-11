package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * PLAN §11 test 19 — `AuditLogIT`: publish + disable write audit rows carrying the actor, action, and
 * entity; the audit `detail` contains NO config bodies, header values, or prompts (PLAN §5/§6 log-hygiene).
 */
class AuditLogIT : AbstractAdminSourceIT() {

    @Test
    fun `publish and disable write hygienic audit rows`() {
        val api = "Audited"
        createSource(
            SourceConfigFixtures.validGenericSource(api).copy(headers = mapOf("x-custom" to "should-not-leak")),
        ).andExpect { status { isCreated() } }
        publish(api, 1).andExpect { status { isOk() } }
        disable(api).andExpect { status { isOk() } }

        val rows =
            jdbcTemplate.queryForList(
                "SELECT action, actor_user_id, entity_type, entity_id, detail::text AS detail FROM audit_log",
            )
        val actions = rows.map { it["action"] as String }.toSet()
        assertTrue(actions.containsAll(setOf("SOURCE_CREATED", "REVISION_CREATED", "REVISION_PUBLISHED", "DOCUMENT_PUBLISHED", "SOURCE_DISABLED"))) {
            "expected the source/publish/disable actions, got $actions"
        }

        // Actor + entity are recorded (the acting admin) on a publish/disable row.
        val disabledRow = rows.first { it["action"] == "SOURCE_DISABLED" }
        assertEquals(admin.id, disabledRow["actor_user_id"] as UUID)
        assertEquals("source", disabledRow["entity_type"])
        assertEquals(api, disabledRow["entity_id"])

        // Log-hygiene: no detail may carry a config body / header value / URL.
        val forbidden = listOf("endpoints", "should-not-leak", "http", "baseUrl", "\"url\"")
        for (row in rows) {
            val detail = (row["detail"] as String)
            for (needle in forbidden) {
                assertTrue(!detail.contains(needle)) {
                    "audit detail for ${row["action"]} leaked '$needle': $detail"
                }
            }
        }
    }
}
