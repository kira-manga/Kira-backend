package me.manga.kira.backend.sourceconfig

import me.manga.kira.backend.support.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 20 — `FlywayMigrationIT` (phase-aware). The context boots against a clean container
 * (which implicitly validates every migration applies), and `flyway_schema_history` must contain
 * **exactly the migrations that exist at the current phase, in version order** — V1..V3 at Phase 5
 * (`outOfOrder=false`). The expectation grows with the build (V1..V4 from Phase 6, the final V1..V5
 * from Phase 9); it must never reference a migration its phase hasn't created (PLAN §5/§15).
 */
class FlywayMigrationIT : AbstractIntegrationTest() {

    @Test
    fun `flyway history is exactly V1 V2 V3 in version order`() {
        val versions =
            jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history " +
                    "WHERE success = true AND version IS NOT NULL " +
                    "ORDER BY installed_rank",
                String::class.java,
            )
        assertEquals(listOf("1", "2", "3"), versions)
    }
}
