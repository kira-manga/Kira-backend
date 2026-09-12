package me.manga.kira.backend.sourceconfig

import me.manga.kira.backend.support.AbstractIntegrationTest
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Types
import java.util.UUID

/**
 * PLAN §11 test 39 — `FlywayIncrementalOrderIT`: the migrations apply in phase order WITHOUT
 * `outOfOrder`, from every meaningful baseline. It runs programmatic Flyway against a FRESH, throwaway
 * DATABASE inside the shared Testcontainers Postgres instance (the Spring ITs use the default `test`
 * database), so the main test schema is untouched:
 *  - (a) migrate V1..V3 only, then migrate to latest → the remaining versions apply on top;
 *  - (b) migrate V1..V4 only, then migrate to latest → the remaining versions apply;
 *  - (c) populate V13, then apply V13.1 → backfill/default/checks without rewriting old history.
 *
 * The hazard this proves impossible: a lower version appearing after a higher one is applied. A fresh
 * DATABASE (not merely a schema) is used because a startup validator inspects `seq_document_revision`
 * via a database-wide `pg_sequences` query — a second schema in the SAME database would leak that
 * sequence into it. Reusing [AbstractIntegrationTest.postgres] keeps the singleton container; this class
 * boots no Spring context (no `@SpringBootTest`).
 */
class FlywayIncrementalOrderIT {
    private val host: String get() = AbstractIntegrationTest.postgres.host
    private val port: Int get() = AbstractIntegrationTest.postgres.firstMappedPort
    private val user: String get() = AbstractIntegrationTest.postgres.username
    private val password: String get() = AbstractIntegrationTest.postgres.password

    private val maintenanceUrl: String
        get() = "jdbc:postgresql://$host:$port/${AbstractIntegrationTest.postgres.databaseName}"

    private fun databaseUrl(database: String): String = "jdbc:postgresql://$host:$port/$database"

    @Test
    fun `V3 baseline then upgrade to latest applies the remaining migrations in order`() {
        withFreshDatabase("flyway_order_v3_baseline") { database ->
            flyway(database, MigrationVersion.fromVersion("3")).migrate()
            assertEquals(listOf("1", "2", "3"), historyVersions(database), "Phase-5 baseline is exactly V1..V3")

            flyway(database, MigrationVersion.LATEST).migrate()
            assertEquals(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "13.1"),
                historyVersions(database),
                "V4 through V13.1 apply on top, in order",
            )
        }
    }

    @Test
    fun `V4 baseline then upgrade to latest applies the remaining migrations in order`() {
        withFreshDatabase("flyway_order_v4_baseline") { database ->
            flyway(database, MigrationVersion.fromVersion("4")).migrate()
            assertEquals(listOf("1", "2", "3", "4"), historyVersions(database), "Phase-6 baseline is exactly V1..V4")

            flyway(database, MigrationVersion.LATEST).migrate()
            assertEquals(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "13.1"),
                historyVersions(database),
                "V5 through V13.1 apply on top, in order",
            )
        }
    }

    @Test
    fun `populated V13 upgrades to V13_1 without rewriting existing users or migration history`() {
        withFreshDatabase("flyway_order_v13_credentials") { database ->
            flyway(database, MigrationVersion.fromVersion("13")).migrate()
            val priorVersions = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13")
            assertEquals(priorVersions, historyVersions(database))
            val priorHistory = historyRows(database)
            val id = UUID.randomUUID()
            val hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("migration credential fixture password")
            DriverManager.getConnection(databaseUrl(database), user, password).use { connection ->
                connection.prepareStatement(
                    "INSERT INTO users (id, email, password_hash, role, enabled, created_at, updated_at) " +
                        "VALUES (?, 'Retained@Example.com', ?, 'ADMIN', false, " +
                        "TIMESTAMPTZ '2025-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00')",
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setString(2, hash)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            val priorUser = userState(database, id)
            val upgrade = flyway(database, MigrationVersion.LATEST)
            assertFalse(upgrade.configuration.isOutOfOrder)

            assertEquals(1, upgrade.migrate().migrationsExecuted)

            upgrade.validate()
            assertEquals(priorVersions + "13.1", historyVersions(database))
            assertEquals(priorHistory, historyRows(database).take(priorHistory.size))
            assertEquals(priorUser, userState(database, id))
            DriverManager.getConnection(databaseUrl(database), user, password).use { connection ->
                connection.prepareStatement("SELECT credential_version FROM users WHERE id = ?").use { statement ->
                    statement.setObject(1, id)
                    statement.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertEquals(0L, rs.getLong(1))
                        assertFalse(rs.wasNull())
                    }
                }
                connection.prepareStatement(
                    "INSERT INTO users (email, password_hash, role) " +
                        "VALUES ('new-after-upgrade@example.com', ?, 'USER') RETURNING credential_version",
                ).use { statement ->
                    statement.setString(1, hash)
                    statement.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertEquals(0L, rs.getLong(1))
                        assertFalse(rs.wasNull())
                    }
                }
                connection.prepareStatement("UPDATE users SET credential_version = ? WHERE id = ?").use { statement ->
                    statement.setObject(2, id)
                    statement.setLong(1, -1)
                    val negative = assertThrows<SQLException> { statement.executeUpdate() }
                    assertEquals("23514", negative.sqlState)
                    statement.setNull(1, Types.BIGINT)
                    val missing = assertThrows<SQLException> { statement.executeUpdate() }
                    assertEquals("23502", missing.sqlState)
                }
            }
            assertEquals(priorUser, userState(database, id))
            assertEquals(priorHistory, historyRows(database).take(priorHistory.size))
        }
    }

    private fun flyway(database: String, target: MigrationVersion): Flyway = Flyway
        .configure()
        .dataSource(databaseUrl(database), user, password)
        .locations("classpath:db/migration")
        .target(target)
        .outOfOrder(false)
        .load()

    /** Create a throwaway database, run [block] against it, and always drop it afterward. */
    private fun withFreshDatabase(database: String, block: (String) -> Unit) {
        adminExec("DROP DATABASE IF EXISTS $database WITH (FORCE)")
        adminExec("CREATE DATABASE $database")
        try {
            block(database)
        } finally {
            adminExec("DROP DATABASE IF EXISTS $database WITH (FORCE)")
        }
    }

    /** CREATE/DROP DATABASE run outside a transaction — DriverManager connections default to autocommit. */
    private fun adminExec(sql: String) {
        DriverManager.getConnection(maintenanceUrl, user, password).use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    private fun historyVersions(database: String): List<String> {
        DriverManager.getConnection(databaseUrl(database), user, password).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT version FROM flyway_schema_history " +
                            "WHERE success = true AND version IS NOT NULL ORDER BY installed_rank",
                    ).use { rs ->
                        val versions = mutableListOf<String>()
                        while (rs.next()) versions.add(rs.getString(1))
                        return versions
                    }
            }
        }
    }

    /** Preserve every field of the earlier successful history, including checksums and ranks. */
    private fun historyRows(database: String): List<List<String?>> = DriverManager
        .getConnection(databaseUrl(database), user, password)
        .use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM flyway_schema_history ORDER BY installed_rank").use { rs ->
                    buildList {
                        while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) })
                    }
                }
            }
        }

    private fun userState(database: String, id: UUID): List<String?> = DriverManager
        .getConnection(databaseUrl(database), user, password)
        .use { connection ->
            connection.prepareStatement("SELECT email, password_hash, role, enabled, created_at, updated_at FROM users WHERE id = ?").use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    (1..rs.metaData.columnCount).map { rs.getString(it) }
                }
            }
        }
}
