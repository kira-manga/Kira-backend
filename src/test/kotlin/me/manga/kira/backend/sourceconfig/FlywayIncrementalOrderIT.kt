package me.manga.kira.backend.sourceconfig

import me.manga.kira.backend.support.AbstractIntegrationTest
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
 *  - (d) independently populate V13.1, then apply V13.2 → conservative bootstrap classification,
 *        preserving all prior state, migration bytes/checksums, pointers and sequence gaps.
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
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "13.1", "13.2"),
                historyVersions(database),
                "V4 through V13.2 apply on top, in order",
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
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "13.1", "13.2"),
                historyVersions(database),
                "V5 through V13.2 apply on top, in order",
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
            val upgrade = flyway(database, MigrationVersion.fromVersion("13.1"))
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

    @Test
    fun `source-pristine V13_1 with unrelated users and auth history becomes pending`() {
        withFreshDatabase("flyway_bootstrap_pristine") { database ->
            flyway(database, MigrationVersion.fromVersion("13.1")).migrate()
            val actor = insertBootstrapMigrationUser(database)
            execute(
                database,
                "INSERT INTO audit_log (actor_user_id, action, entity_type, entity_id, detail, created_at) " +
                    "VALUES (?, 'LOGIN_SUCCESS', 'user', ?, '{}', TIMESTAMPTZ '2026-09-01T00:00:00Z')",
                actor,
                actor.toString(),
            )
            execute(
                database,
                "INSERT INTO admin_step_up_grants (id, user_id, token_hash, scope, created_at, expires_at) " +
                    "VALUES (?, ?, ?, 'source-admin-mutation', " +
                    "TIMESTAMPTZ '2026-09-01T00:00:00Z', TIMESTAMPTZ '2026-09-01T00:05:00Z')",
                UUID.randomUUID(),
                actor,
                "a".repeat(64),
            )

            upgradeBootstrapAndAssertPreserved(database, "pending")
        }
    }

    @ParameterizedTest(name = "V13.1 {0} history requires reconciliation")
    @ValueSource(strings = ["head", "revision", "validation", "editor", "changeset", "v1_unpointed", "v1_pointed", "v2_history"])
    fun `source history in V13_1 requires reconciliation without adoption or rewriting`(scenario: String) {
        withFreshDatabase("flyway_bootstrap_$scenario") { database ->
            flyway(database, MigrationVersion.fromVersion("13.1")).migrate()
            seedBootstrapMigrationHistory(database, scenario)
            // Preserve a legitimate consumed-sequence gap; migration must not allocate or reset it.
            rows(database, "SELECT setval('seq_document_revision', 157, true)")

            upgradeBootstrapAndAssertPreserved(database, "reconciliation_required")
        }
    }

    @Test
    fun `V13_2 refuses a missing singleton without inventing state or changing prior history`() {
        withFreshDatabase("flyway_bootstrap_missing_singleton") { database ->
            flyway(database, MigrationVersion.fromVersion("13.1")).migrate()
            execute(database, "DELETE FROM document_publication_state WHERE id = 1")
            val priorHistory = historyRows(database)
            val priorState = bootstrapMigrationState(database)
            val upgrade = flyway(database, MigrationVersion.fromVersion("13.2"))

            assertThrows<FlywayException> { upgrade.migrate() }

            assertEquals(priorHistory, historyRows(database), "failed transactional migration must not append successful history")
            assertEquals(priorState, bootstrapMigrationState(database))
            assertTrue(rows(database, "SELECT * FROM document_publication_state").isEmpty(), "no replacement singleton")
            assertTrue(
                rows(
                    database,
                    "SELECT column_name FROM information_schema.columns " +
                        "WHERE table_schema = 'public' AND table_name = 'document_publication_state' AND column_name = 'bootstrap_phase'",
                ).isEmpty(),
                "the failed migration must not leave its new columns behind",
            )
        }
    }

    private fun upgradeBootstrapAndAssertPreserved(database: String, phase: String) {
        val priorHistory = historyRows(database)
        val priorVersions = historyVersions(database)
        assertEquals("13.1", priorVersions.last())
        val priorState = bootstrapMigrationState(database)
        val upgrade = flyway(database, MigrationVersion.fromVersion("13.2"))
        assertFalse(upgrade.configuration.isOutOfOrder)

        assertEquals(1, upgrade.migrate().migrationsExecuted)
        upgrade.validate()

        assertEquals(priorVersions + "13.2", historyVersions(database))
        assertEquals(priorHistory, historyRows(database).take(priorHistory.size))
        assertEquals(priorState, bootstrapMigrationState(database), "classification must preserve every pre-existing value")
        val receipt = rows(
            database,
            "SELECT bootstrap_phase, bootstrap_policy_id, bootstrap_reference_sha256, bootstrap_payload_sha256, " +
                "bootstrap_document_revision, bootstrap_document_checksum, bootstrap_catalog_revision, " +
                "bootstrap_catalog_checksum, bootstrap_completed_at, bootstrap_actor_id FROM document_publication_state",
        ).single()
        assertEquals(phase, receipt.first())
        assertTrue(receipt.drop(1).all { it == null }, "migration never fabricates an origin receipt")
        if (phase == "pending") {
            assertEquals(listOf(listOf<String?>(null)), rows(database, "SELECT latest_document_revision FROM document_publication_state"))
        }
    }

    private fun seedBootstrapMigrationHistory(database: String, scenario: String) {
        val actor = insertBootstrapMigrationUser(database)
        val source = UUID.randomUUID()
        val revision = UUID.randomUUID()
        if (scenario in setOf("head", "revision", "validation", "editor", "v2_history")) {
            execute(
                database,
                "INSERT INTO source_configs (id, api, display_name, language, engine, status, position, base_url, created_at, updated_at) " +
                    "VALUES (?, 'Retained Source', 'Retained source', 'en', 'generic', 'draft', 17, " +
                    "'https://example.invalid', TIMESTAMPTZ '2025-01-01T00:00:00Z', TIMESTAMPTZ '2026-01-01T00:00:00Z')",
                source,
            )
        }
        if (scenario in setOf("revision", "validation", "v2_history")) {
            execute(
                database,
                "INSERT INTO source_config_revisions (id, source_config_id, revision_number, config_canonical_json, checksum, " +
                    "canon_version, status, created_by, notes, created_at) " +
                    "VALUES (?, ?, 3, '{\"api\":\"Retained Source\"}', ?, 'kcj-1', 'draft', ?, 'retained history', now())",
                revision,
                source,
                "b".repeat(64),
                actor,
            )
        }
        if (scenario == "validation") {
            execute(
                database,
                "INSERT INTO source_validation_results (id, revision_id, valid, errors, warnings, rules_version, validated_at) " +
                    "VALUES (?, ?, false, '[{\"code\":\"RETAINED\"}]', '[]', 'historical-rules', now())",
                UUID.randomUUID(),
                revision,
            )
        }
        if (scenario == "editor") {
            execute(
                database,
                "INSERT INTO source_editor_drafts (id, source_config_id, based_on_revision_number, content_json, version, " +
                    "created_by, updated_by, created_at, updated_at) VALUES (?, ?, 3, '{unfinished', 7, ?, ?, now(), now())",
                UUID.randomUUID(),
                source,
                actor,
                actor,
            )
        }
        if (scenario == "changeset") {
            // V13 allows this work-in-progress independently of every source head/revision table.
            execute(
                database,
                "INSERT INTO source_changesets (id, name, operations_json, created_by, updated_by, created_at, updated_at) " +
                    "VALUES (?, 'Unapplied source plan', '[]', ?, ?, now(), now())",
                UUID.randomUUID(),
                actor,
                actor,
            )
        }
        if (scenario.startsWith("v1_") || scenario == "v2_history") {
            seedBootstrapMigrationArtifacts(database, scenario, actor, source, revision)
        }
    }

    private fun seedBootstrapMigrationArtifacts(database: String, scenario: String, actor: UUID, source: UUID, revision: UUID) {
        execute(
            database,
            "INSERT INTO published_documents (id, document_revision, schema_version, document_json, checksum, canon_version, " +
                "source_count, created_by, created_at, notes) VALUES (?, 137, 1, '{\"revision\":137}', ?, 'kcj-1', 0, ?, " +
                "TIMESTAMPTZ '2026-09-01T00:00:00Z', 'retained snapshot')",
            UUID.randomUUID(),
            "c".repeat(64),
            actor,
        )
        if (scenario != "v1_unpointed") {
            execute(database, "UPDATE document_publication_state SET latest_document_revision = 137 WHERE id = 1")
        }
        if (scenario != "v2_history") return
        execute(database, "UPDATE source_config_revisions SET status = 'published', published_at = now() WHERE id = ?", revision)
        execute(
            database,
            "UPDATE source_configs SET status = 'disabled', current_published_revision_id = ?, published_at = now() WHERE id = ?",
            revision,
            source,
        )
        val catalog = UUID.randomUUID()
        execute(
            database,
            "INSERT INTO published_source_catalogs (id, catalog_revision, schema_version, source_schema_version, manifest_json, " +
                "checksum, canon_version, source_count, created_by, created_at, signature_format, signature_algorithm, " +
                "signing_key_id, signature_base64) VALUES (?, 137, 1, 1, '{\"catalogRevision\":137}', ?, 'kcj-1', 1, ?, " +
                "TIMESTAMPTZ '2026-09-01T00:00:00Z', 'retained-format', 'Ed25519', 'retained-key', 'retained-signature')",
            catalog,
            "d".repeat(64),
            actor,
        )
        execute(
            database,
            "INSERT INTO published_source_catalog_entries (catalog_id, source_config_id, source_revision_id, api, source_revision, " +
                "checksum, source_order, lifecycle, engine, source_signing_key_id, source_signature) " +
                "VALUES (?, ?, ?, 'Retained Source', 3, ?, 0, 'disabled', 'generic', 'retained-key', 'retained-source-signature')",
            catalog,
            source,
            revision,
            "b".repeat(64),
        )
        execute(database, "INSERT INTO published_source_catalog_removed (catalog_id, api) VALUES (?, 'Previously removed')", catalog)
    }

    private fun insertBootstrapMigrationUser(database: String): UUID = UUID.randomUUID().also { id ->
        val hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("bootstrap migration fixture password")
        execute(
            database,
            "INSERT INTO users (id, email, password_hash, role, credential_version) VALUES (?, 'retained-user@example.invalid', ?, 'ADMIN', 7)",
            id,
            hash,
        )
    }

    /** Compare stored rows, not counts alone; singleton's original columns deliberately exclude the new fields. */
    private fun bootstrapMigrationState(database: String): Map<String, List<List<String?>>> = buildMap {
        val tables = listOf(
            "users",
            "audit_log",
            "admin_step_up_grants",
            "source_configs",
            "source_config_revisions",
            "source_validation_results",
            "source_editor_drafts",
            "source_changesets",
            "published_documents",
            "published_source_catalogs",
            "published_source_catalog_entries",
            "published_source_catalog_removed",
        )
        for (table in tables) {
            put(table, rows(database, "SELECT * FROM $table ORDER BY 1"))
        }
        put("singleton", rows(database, "SELECT id, latest_document_revision, updated_at FROM document_publication_state ORDER BY id"))
        put("sequence", rows(database, "SELECT last_value, is_called FROM seq_document_revision"))
    }

    private fun execute(database: String, sql: String, vararg parameters: Any) {
        DriverManager.getConnection(databaseUrl(database), user, password).use { connection ->
            connection.prepareStatement(sql).use { statement ->
                parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }
    }

    private fun rows(database: String, sql: String): List<List<String?>> = DriverManager
        .getConnection(databaseUrl(database), user, password)
        .use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    buildList {
                        while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) })
                    }
                }
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
