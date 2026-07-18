package me.manga.kira.backend.database

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/** Disposable logical backup/restore drill against the production PostgreSQL major/minor image. */
class DatabaseBackupRestoreIT {
    @Test
    fun `latest schema and representative data survive pg dump restore and Flyway validation`() {
        source.start()
        target.start()
        try {
            val sourceFlyway = flyway(source)
            sourceFlyway.migrate()
            val userId = UUID.randomUUID()
            DriverManager.getConnection(source.jdbcUrl, source.username, source.password).use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO users(id, email, password_hash, role, enabled, created_at, updated_at)
                    VALUES (?, 'restore@example.com', '{noop}unused', 'ADMIN', true, now(), now())
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.executeUpdate()
                }
            }

            val dump = source.execInContainer(
                "pg_dump",
                "-U",
                source.username,
                "-d",
                source.databaseName,
                "--no-owner",
                "--no-acl",
            )
            assertEquals(0, dump.exitCode, dump.stderr)
            assertTrue(dump.stdout.contains("flyway_schema_history"))
            target.copyFileToContainer(Transferable.of(dump.stdout.toByteArray(), 0b110_000_000), "/tmp/kira-restore.sql")
            val restore = target.execInContainer(
                "psql", "-v", "ON_ERROR_STOP=1", "-U", target.username, "-d", target.databaseName, "-f", "/tmp/kira-restore.sql",
            )
            assertEquals(0, restore.exitCode, restore.stderr)

            DriverManager.getConnection(target.jdbcUrl, target.username, target.password).use { connection ->
                connection.prepareStatement("SELECT role FROM users WHERE id = ?").use { statement ->
                    statement.setObject(1, userId)
                    statement.executeQuery().use { result ->
                        assertTrue(result.next())
                        assertEquals("ADMIN", result.getString(1))
                    }
                }
            }
            assertEquals(0, flyway(target).migrate().migrationsExecuted)
            assertTrue(flyway(target).validateWithResult().validationSuccessful)
        } finally {
            target.stop()
            source.stop()
        }
    }

    private fun flyway(postgres: PostgreSQLContainer<*>): Flyway = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .cleanDisabled(true)
        .validateMigrationNaming(true)
        .load()

    private companion object {
        val image: DockerImageName = DockerImageName.parse("postgres:17.6-alpine")
        val source = PostgreSQLContainer(image).withDatabaseName("kira_source").withUsername("kira").withPassword("test-only")
        val target = PostgreSQLContainer(image).withDatabaseName("kira_restore").withUsername("kira").withPassword("test-only")
    }
}
