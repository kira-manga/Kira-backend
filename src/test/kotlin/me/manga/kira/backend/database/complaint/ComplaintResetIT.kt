package me.manga.kira.backend.database.complaint

import me.manga.kira.backend.support.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.test.annotation.DirtiesContext

// Retire this Spring pool before standalone owned-root fixtures observe the shared pgjdbc Timer.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ComplaintResetIT : AbstractIntegrationTest() {
    @Test
    fun `shared Spring reset handles populated scoped foreign keys and restores closed seeds repeatedly`() {
        try {
            // Synthetic historical catalog, not authenticated bootstrap completion.
            jdbcTemplate.update("UPDATE document_publication_state SET bootstrap_phase='reconciliation_required' WHERE id=1")
            jdbcTemplate.execute(complaintResource("fixtures/complaint/v13-rich.sql"))
            // Current nonlegacy relationships; rich legacy reset oracles remain explicitly historical.
            jdbcTemplate.execute(complaintResource("fixtures/complaint/v31_4-clean-start.sql"))
            resetState()
            resetState()
            requireNotNull(jdbcTemplate.dataSource).connection.use { connection ->
                connection.assertClosedComplaintSeeds()
                val tables = ComplaintMigrationIT.currentTables -
                    listOf("complaint_capacity_counters", "complaint_journal_control")
                for (table in tables) {
                    assertEquals(listOf("0"), connection.strings("SELECT count(*) FROM $table"), table)
                }
                assertEquals(listOf("0"), connection.strings("SELECT count(*) FROM users"))
            }
        } finally {
            // Do not leak schema-only publication fixtures into another Spring startup validator.
            resetState()
        }
    }
}
