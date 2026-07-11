package me.manga.kira.backend

import me.manga.kira.backend.support.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Phase 1 green gate (PLAN §15.1): the Spring context boots against a real PostgreSQL
 * (Testcontainers), Flyway applies V1, and Hibernate `ddl-auto=validate` passes. The assertions
 * confirm V1 actually ran (not merely that the context started).
 */
class ContextLoadsIT : AbstractIntegrationTest() {

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun contextLoads() {
        // security_state singleton row was seeded by V1.
        assertEquals(
            1L,
            jdbc.queryForObject("SELECT count(*) FROM security_state WHERE id = 1", Long::class.java),
        )
        // users table exists and starts empty (no code seeds users in Phase 1).
        assertEquals(
            0L,
            jdbc.queryForObject("SELECT count(*) FROM users", Long::class.java),
        )
        // The case-insensitive email uniqueness index from V1 is present.
        assertEquals(
            1L,
            jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_users_email_lower'",
                Long::class.java,
            ),
        )
    }
}
