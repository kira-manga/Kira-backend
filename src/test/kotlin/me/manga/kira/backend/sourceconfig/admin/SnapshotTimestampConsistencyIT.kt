package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * PLAN §11 test 43 — `SnapshotTimestampConsistencyIT`: one snapshot timestamp everywhere. After a
 * publish, the document's `generatedAt`, the `published_documents.created_at` row value, and the
 * publication audit detail all carry the identical instant (ISO-8601 UTC, seconds precision); the column
 * has no DB default (PLAN §9 steps 7–8).
 */
class SnapshotTimestampConsistencyIT : AbstractAdminSourceIT() {

    @Test
    fun `generatedAt equals the snapshot created_at and the audit detail instant`() {
        val api = "Ts"
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }
        val documentRevision = docRevisionOf(publish(api, 1).andExpect { status { isOk() } })

        // 1) The document's generatedAt (from the served bytes) — ISO-8601 UTC, seconds precision.
        val generatedAt = servedDocument(documentRevision).generatedAt!!
        assertTrue(generatedAt.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z"""))) {
            "generatedAt must be ISO-8601 UTC seconds, was '$generatedAt'"
        }
        val instant = OffsetDateTime.parse(generatedAt, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()

        // 2) published_documents.created_at == that instant.
        val createdAt =
            jdbcTemplate.queryForObject(
                "SELECT created_at FROM published_documents WHERE document_revision = ?",
                OffsetDateTime::class.java,
                documentRevision,
            )!!.toInstant()
        assertEquals(instant, createdAt, "the snapshot created_at must equal the document generatedAt instant")

        // 3) The DOCUMENT_PUBLISHED audit detail's generatedAt + the audit row's created_at == that instant.
        val auditDetail =
            jdbcTemplate.queryForObject(
                "SELECT detail::text FROM audit_log WHERE action = 'DOCUMENT_PUBLISHED' AND entity_id = ?",
                String::class.java,
                documentRevision.toString(),
            )!!
        assertEquals(generatedAt, objectMapper.readTree(auditDetail).get("generatedAt").asText())

        val auditCreatedAt =
            jdbcTemplate.queryForObject(
                "SELECT created_at FROM audit_log WHERE action = 'DOCUMENT_PUBLISHED' AND entity_id = ?",
                OffsetDateTime::class.java,
                documentRevision.toString(),
            )!!.toInstant()
        assertEquals(instant, auditCreatedAt, "the publication audit row must carry the same instant")
    }
}
