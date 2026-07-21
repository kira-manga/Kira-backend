package me.manga.kira.backend.sourceconfig

import me.manga.kira.backend.support.AbstractIntegrationTest
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * PLAN §11 test 39 — `FlywayIncrementalOrderIT`: the migrations apply in phase order WITHOUT
 * `outOfOrder`, from every meaningful baseline. It runs programmatic Flyway against a FRESH, throwaway
 * DATABASE inside the shared Testcontainers Postgres instance (the Spring ITs use the default `test`
 * database), so the main test schema is untouched:
 *  - (a) migrate V1..V3 only, then migrate to latest → V4..V9 apply on top;
 *  - (b) migrate V1..V4 only, then migrate to latest → V5..V9 apply.
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
    fun `V3 baseline then upgrade to latest applies V4 through V9 in order`() {
        withFreshDatabase("flyway_order_v3_baseline") { database ->
            flyway(database, MigrationVersion.fromVersion("3")).migrate()
            assertEquals(listOf("1", "2", "3"), historyVersions(database), "Phase-5 baseline is exactly V1..V3")

            flyway(database, MigrationVersion.LATEST).migrate()
            assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9"), historyVersions(database), "V4..V9 apply on top, in order")
        }
    }

    @Test
    fun `V4 baseline then upgrade to latest applies V5 through V9`() {
        withFreshDatabase("flyway_order_v4_baseline") { database ->
            flyway(database, MigrationVersion.fromVersion("4")).migrate()
            assertEquals(listOf("1", "2", "3", "4"), historyVersions(database), "Phase-6 baseline is exactly V1..V4")

            flyway(database, MigrationVersion.LATEST).migrate()
            assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9"), historyVersions(database), "V5..V9 apply on top, in order")
        }
    }

    private fun flyway(database: String, target: MigrationVersion): Flyway = Flyway
        .configure()
        .dataSource(databaseUrl(database), user, password)
        .locations("classpath:db/migration")
        .target(target)
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
}
