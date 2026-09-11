package me.manga.kira.backend.sourceconfig

import me.manga.kira.backend.support.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import java.sql.SQLException

/**
 * PLAN §11 test 20 — `FlywayMigrationIT`, now in its FINAL form. The context boots against a clean
 * container (which implicitly validates every migration applies), and `flyway_schema_history` must
 * contain **exactly** V1 users through V13 source changesets, then V13.1 credential versions,
 * in version order, with `outOfOrder=false` (the migration history is complete as of Phase 9 — PLAN
 * §5/§15.9). A lower version can never appear after a higher one has been applied.
 */
class FlywayMigrationIT : AbstractIntegrationTest() {

    @Test
    fun `flyway history is exactly V1 through V13 then V13_1 in version order`() {
        val versions =
            jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history " +
                    "WHERE success = true AND version IS NOT NULL " +
                    "ORDER BY installed_rank",
                String::class.java,
            )
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "13.1"), versions)
    }

    @Test
    fun `fresh schema defaults credential version to zero and rejects negative and null versions`() {
        val hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("migration credential fixture password")
        val defaultVersion = jdbcTemplate.queryForObject(
            "INSERT INTO users (email, password_hash, role) VALUES (?, ?, 'USER') RETURNING credential_version",
            Long::class.java,
            "default-version@example.com",
            hash,
        )
        assertEquals(0L, defaultVersion)
        val negative = assertThrows<DataIntegrityViolationException> {
            jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, role, credential_version) VALUES (?, ?, 'USER', -1)",
                "negative-version@example.com",
                hash,
            )
        }
        assertEquals("23514", (negative.mostSpecificCause as SQLException).sqlState)
        val missing = assertThrows<DataIntegrityViolationException> {
            jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, role, credential_version) VALUES (?, ?, 'USER', NULL)",
                "null-version@example.com",
                hash,
            )
        }
        assertEquals("23502", (missing.mostSpecificCause as SQLException).sqlState)
    }
}
