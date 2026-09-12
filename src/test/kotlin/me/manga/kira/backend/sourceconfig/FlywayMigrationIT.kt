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
 * contain **exactly** V1 users through V13 source changesets, V13.1 credential versions and
 * V13.2 initial-catalog classification,
 * in version order, with `outOfOrder=false` (the migration history is complete as of Phase 9 — PLAN
 * §5/§15.9). A lower version can never appear after a higher one has been applied.
 */
class FlywayMigrationIT : AbstractIntegrationTest() {

    @Test
    fun `flyway history is exactly V1 through V13 then V13_1 and V13_2 in version order`() {
        val versions =
            jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history " +
                    "WHERE success = true AND version IS NOT NULL " +
                    "ORDER BY installed_rank",
                String::class.java,
            )
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "13.1", "13.2"), versions)
    }

    @Test
    fun `bootstrap constraints reject incomplete completion partial receipts and invalid phases`() {
        val before = jdbcTemplate.queryForMap("SELECT * FROM document_publication_state WHERE id = 1")
        assertEquals("pending", before["bootstrap_phase"])
        val invalidAssignments = listOf(
            "bootstrap_phase = 'complete'",
            "bootstrap_policy_id = 'partial-receipt'",
            "bootstrap_phase = 'reconciliation_required', bootstrap_payload_sha256 = '${"a".repeat(64)}'",
            "bootstrap_phase = 'unrecognized'",
        )
        for (assignment in invalidAssignments) {
            val rejected = assertThrows<DataIntegrityViolationException> {
                jdbcTemplate.update("UPDATE document_publication_state SET $assignment WHERE id = 1")
            }
            assertEquals("23514", (rejected.mostSpecificCause as SQLException).sqlState)
            assertEquals(before, jdbcTemplate.queryForMap("SELECT * FROM document_publication_state WHERE id = 1"))
        }
        val missingPhase = assertThrows<DataIntegrityViolationException> {
            jdbcTemplate.update("UPDATE document_publication_state SET bootstrap_phase = NULL WHERE id = 1")
        }
        assertEquals("23502", (missingPhase.mostSpecificCause as SQLException).sqlState)
        assertEquals(before, jdbcTemplate.queryForMap("SELECT * FROM document_publication_state WHERE id = 1"))
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
