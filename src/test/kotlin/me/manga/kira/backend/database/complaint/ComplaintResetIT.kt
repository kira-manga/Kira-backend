package me.manga.kira.backend.database.complaint

import me.manga.kira.backend.support.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ComplaintResetIT : AbstractIntegrationTest() {
    @Test
    fun `shared Spring reset handles populated scoped foreign keys and restores closed seeds repeatedly`() {
        try {
            jdbcTemplate.execute(complaintResource("fixtures/complaint/v13-rich.sql"))
            jdbcTemplate.execute(complaintResource("fixtures/complaint/v14-rich.sql"))
            resetState()
            resetState()
            requireNotNull(jdbcTemplate.dataSource).connection.use { connection ->
                connection.assertClosedComplaintSeeds()
                for (table in ComplaintMigrationIT.expectedTables - listOf("complaint_capacity_counters", "complaint_journal_control")) {
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
