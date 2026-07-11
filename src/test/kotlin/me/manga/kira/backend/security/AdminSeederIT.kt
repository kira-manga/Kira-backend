package me.manga.kira.backend.security

import me.manga.kira.backend.support.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.test.context.TestPropertySource

/**
 * Test 11 (PLAN §11) — `AdminSeederIT`: with seed env set, exactly one ADMIN exists after startup;
 * idempotent on restart. Seeding is enabled here (overriding the test profile's default-off) with
 * throwaway credentials via [TestPropertySource]. The seeder is invoked directly — the same code
 * path the startup `ApplicationRunner` runs — so the assertion is deterministic after the base
 * class's per-test cleanup, and the "idempotent on restart" case is a second invocation.
 */
@TestPropertySource(
    properties = [
        "kira.admin.seed-enabled=true",
        "kira.admin.email=seed-admin@example.com",
        "kira.admin.password=seed-admin-password-1234",
    ],
)
class AdminSeederIT
    @Autowired
    constructor(
        private val adminSeeder: AdminSeeder,
    ) : AbstractIntegrationTest() {

        private fun adminCount(): Long =
            jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE role = 'ADMIN'", Long::class.java)!!

        @Test
        fun `seeds exactly one admin and is idempotent`() {
            adminSeeder.run(null)
            assertEquals(1L, adminCount())
            assertEquals(
                1L,
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM users WHERE lower(email) = 'seed-admin@example.com'",
                    Long::class.java,
                ),
            )

            // Idempotent: a second run (a "restart") creates no second admin and resets no password.
            adminSeeder.run(null)
            assertEquals(1L, adminCount())
        }

        @Test
        fun `an admin already present short-circuits (no duplicate)`() {
            adminSeeder.run(null)
            val before = adminCount()
            adminSeeder.run(null)
            assertTrue(before == 1L && adminCount() == 1L)
        }
    }
