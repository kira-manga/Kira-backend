package me.manga.kira.backend.sourceconfig

import me.manga.kira.backend.support.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 20 — `FlywayMigrationIT`, now in its FINAL form. The context boots against a clean
 * container (which implicitly validates every migration applies), and `flyway_schema_history` must
 * contain **exactly** V1 users through V6 completion retention,
 * in version order, with `outOfOrder=false` (the migration history is complete as of Phase 9 — PLAN
 * §5/§15.9). A lower version can never appear after a higher one has been applied.
 */
class FlywayMigrationIT : AbstractIntegrationTest() {

    @Test
    fun `flyway history is exactly V1 through V6 in version order`() {
        val versions =
            jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history " +
                    "WHERE success = true AND version IS NOT NULL " +
                    "ORDER BY installed_rank",
                String::class.java,
            )
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), versions)
    }
}
