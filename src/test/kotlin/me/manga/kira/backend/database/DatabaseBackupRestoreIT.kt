package me.manga.kira.backend.database

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Linux disposable drill: the real portable restore scripts/helper, PG17.6 custom dump FILE BYTES,
 * real Flyway predicates and media publication. No production credentials, traffic or release-image proof.
 * The fixed test-only bridges execute real clients in the target container; no host PG client fallback.
 */
class DatabaseBackupRestoreIT {
    @TempDir
    lateinit var temporary: Path

    @ParameterizedTest
    @ValueSource(strings = ["12", "13"])
    fun `selected source pair survives real restore before separate release thirteen migration`(sourceVersion: String) {
        withDatabases { source, target ->
            flyway(source, sourceVersion).migrate()
            val userId = UUID.randomUUID()
            insertUser(source, userId)
            val originalHistory = history(source)
            val pair = selectedPair(source)
            val environment = targetEnvironment(target)

            assertSuccess(restoreDatabase(pair, sourceVersion, environment))
            assertUser(target, userId)
            assertEquals(originalHistory, history(target))
            assertFalse(Files.exists(pair.mediaTarget))
            assertFalse(Files.exists(pair.attempt.resolve("pair.json")))
            assertDatabaseReceipt(pair, target, sourceVersion)

            // Originals are no longer available: only the frozen selected pair may be consumed.
            Files.delete(pair.dump)
            Files.delete(pair.media)
            Files.delete(pair.manifest)
            assertSuccess(run(listOf(scripts.resolve("restore-media.sh").toString(), pair.attempt.toString()), environment))
            assertArrayEquals(mediaBytes, Files.readAllBytes(pair.mediaTarget.resolve("tutorial/item.bin")))
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(pair.mediaTarget.resolve("tutorial/item.bin")),
            )
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(pair.mediaTarget))
            val pairReceipt = record(pair.attempt.resolve("pair.json"))
            assertEquals("kira.restore-pair.v1", pairReceipt.path("schema").asText())
            assertEquals(
                sha256(Files.readAllBytes(pair.attempt.resolve("db.json"))),
                pairReceipt.path("database_receipt_sha256").asText(),
            )
            assertEquals(pair.mediaTarget.toString(), pairReceipt.path("target").path("path").asText())
            assertFalse(Files.exists(pair.attempt.resolve("STOP.json")))

            // This is deliberately later than the source-version/paired-media stage.
            // Running this test's pinned migration resources is not exact release-image attestation.
            val release = flyway(target, "13")
            assertEquals(
                if (sourceVersion == "12") listOf("13") else emptyList<String>(),
                release.info().pending().map { it.version.version },
            )
            assertEquals(if (sourceVersion == "12") 1 else 0, release.migrate().migrationsExecuted)
            assertTrue(release.validateWithResult().validationSuccessful)
            assertEquals("13", release.info().current().version.version)
            assertEquals((1..13).map(Int::toString), history(target).map { it.version })
            assertTrue(history(target).all { it.success })
            assertUser(target, userId)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["empty", "failed-earlier", "wrong-source-version", "missing-history"])
    fun `real restored history predicate failures leave a stopped unverified database`(failure: String) {
        withDatabases { source, target ->
            flyway(source, "13").migrate()
            val userId = UUID.randomUUID()
            insertUser(source, userId)
            // Corrupt only this disposable source's history. These are real stored SQL
            // values, not simulated psql text; no claim about transactional Flyway failure behavior.
            when (failure) {
                "empty" -> execute(source, "DELETE FROM public.flyway_schema_history")
                "failed-earlier" -> execute(source, "UPDATE public.flyway_schema_history SET success=false WHERE version='1'")
                "missing-history" -> execute(source, "DROP TABLE public.flyway_schema_history")
            }
            val pair = selectedPair(source)
            val result = restoreDatabase(pair, if (failure == "wrong-source-version") "12" else "13", targetEnvironment(target))
            assertNotEquals(0, result.exitCode, result.output)
            assertTrue(Files.isRegularFile(pair.attempt.resolve("db-started.json")))
            assertTrue(Files.isRegularFile(pair.attempt.resolve("STOP.json")))
            assertFalse(Files.exists(pair.attempt.resolve("db.json")))
            assertFalse(Files.exists(pair.attempt.resolve("pair.json")))
            assertFalse(Files.exists(pair.mediaTarget))
            // A successful restore followed by a failed predicate is NOT rolled back.
            assertUser(target, userId)
            if (failure == "failed-earlier") {
                assertFalse(history(target).first().success)
                assertTrue(history(target).last().success)
                assertEquals("13", history(target).last().version)
            }
        }
    }

    @Test
    fun `media stage refuses real history mutation after database receipt`() {
        withDatabases { source, target ->
            flyway(source, "13").migrate()
            val pair = selectedPair(source)
            val environment = targetEnvironment(target)
            assertSuccess(restoreDatabase(pair, "13", environment))
            execute(target, "UPDATE public.flyway_schema_history SET checksum=CASE WHEN checksum=0 THEN 1 ELSE 0 END WHERE version='1'")
            val result = run(listOf(scripts.resolve("restore-media.sh").toString(), pair.attempt.toString()), environment)
            assertNotEquals(0, result.exitCode, result.output)
            assertTrue(Files.isRegularFile(pair.attempt.resolve("db.json")))
            assertTrue(Files.isRegularFile(pair.attempt.resolve("STOP.json")))
            assertFalse(Files.exists(pair.attempt.resolve("media-started.json")))
            assertFalse(Files.exists(pair.attempt.resolve("pair.json")))
            assertFalse(Files.exists(pair.mediaTarget))
        }
    }

    private fun selectedPair(source: PostgreSQLContainer<*>): SelectedPair {
        val directory = Files.createDirectory(
            temporary.resolve("selected"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
        val dump = directory.resolve("drill.dump")
        val containerFile = "/tmp/kira-restore-drill.dump"
        val produced = source.execInContainer(
            "pg_dump", "-U", source.username, "-d", source.databaseName,
            "--format=custom", "--compress=9", "--no-owner", "--no-acl", "--file=$containerFile",
        )
        assertEquals(0, produced.exitCode, produced.stderr)
        // Testcontainers exec stdout is decoded text. NEVER use it for the custom dump.
        source.copyFileFromContainer(containerFile) { input ->
            Files.newOutputStream(dump, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output -> input.copyTo(output) }
        }
        Files.setPosixFilePermissions(dump, PosixFilePermissions.fromString("rw-------"))
        Files.newInputStream(dump).use { input -> assertArrayEquals("PGDMP".toByteArray(Charsets.US_ASCII), input.readNBytes(5)) }

        val live = Files.createDirectories(temporary.resolve("source-media/tutorial"))
        Files.write(live.resolve("item.bin"), mediaBytes)
        val media = directory.resolve("drill.media.tar.gz")
        assertSuccess(run(listOf("tar", "-C", live.parent.toString(), "-czf", media.toString(), ".")))
        Files.setPosixFilePermissions(media, PosixFilePermissions.fromString("rw-------"))
        val manifest = directory.resolve("drill.bundle.json")
        val created = run(
            listOf(
                "python3", "-I", "-B", scripts.resolve("backup_bundle.py").toString(), "create",
                "--bundle", manifest.toString(), "--dump", dump.toString(), "--media", media.toString(),
            ),
        )
        assertSuccess(created)
        assertTrue(Regex("[0-9a-f]{64}\\n").matches(created.output), "create must return exactly digest plus newline")
        val pin = created.output.trimEnd('\n')
        assertEquals(sha256(Files.readAllBytes(manifest)), pin)
        return SelectedPair(manifest, dump, media, pin, temporary.resolve("attempt"), temporary.resolve("restored-media"))
    }

    private fun targetEnvironment(target: PostgreSQLContainer<*>): Map<String, String> {
        val bin = Files.createDirectory(temporary.resolve("pg17-clients"))
        val docker = executable("docker")
        require(target.containerId.matches(Regex("[0-9a-f]{64}")))
        for (name in listOf("pg_restore", "psql")) {
            val bridge = bin.resolve(name)
            Files.writeString(
                bridge,
                """
                |#!/bin/sh
                |set -eu
                |exec ${shellQuote(docker.toString())} exec -i \
                |  --env PGPASSWORD --env PGSSLMODE --env PGCLIENTENCODING ${target.containerId} $name "${'$'}@"
                |
                """.trimMargin(),
            )
            Files.setPosixFilePermissions(bridge, PosixFilePermissions.fromString("rwx------"))
        }
        // The helper's logical endpoint is inside this one exact disposable container.
        // docker exec transports raw stdin to its PG17 client; it does not mock SQL/history.
        return mapOf(
            "PATH" to "$bin:${System.getenv("PATH")}",
            "PGHOST" to "127.0.0.1",
            "PGPORT" to "5432",
            "PGDATABASE" to target.databaseName,
            "PGUSER" to target.username,
            "PGPASSWORD" to target.password,
            "PGSSLMODE" to "disable", // Isolated fixture only; never an installed TLS claim.
            "KIRA_ENVIRONMENT" to "test",
            "KIRA_ALLOW_DESTRUCTIVE_RESTORE_TEST" to "yes",
            "KIRA_RESTORE_CUSTODY_CONFIRMED" to "yes",
        )
    }

    private fun restoreDatabase(pair: SelectedPair, version: String, environment: Map<String, String>): CommandResult =
        run(
            listOf(
                scripts.resolve("verify-restore.sh").toString(), pair.manifest.toString(), pair.pin,
                pair.dump.toString(), pair.media.toString(), version, pair.attempt.toString(), pair.mediaTarget.toString(),
            ),
            environment,
        )

    private fun assertDatabaseReceipt(pair: SelectedPair, target: PostgreSQLContainer<*>, sourceVersion: String) {
        val request = record(pair.attempt.resolve("request.json"))
        val receipt = record(pair.attempt.resolve("db.json"))
        assertEquals("kira.restore-db.v1", receipt.path("schema").asText())
        assertEquals(sourceVersion, receipt.path("source_version").asText())
        assertEquals(request.path("id").asText(), receipt.path("id").asText())
        assertEquals(request.path("selection"), receipt.path("selection"))
        assertEquals(pair.pin, receipt.path("selection").path("manifest").path("sha256").asText())
        assertEquals(target.databaseName, receipt.path("identity").path("database").asText())
        assertEquals(databaseOid(target), receipt.path("identity").path("oid").asLong())
        assertTrue(Regex("[0-9a-f]{64}").matches(receipt.path("history_sha256").asText()))
        assertEquals(sha256(Files.readAllBytes(pair.attempt.resolve("request.json"))), receipt.path("request_sha256").asText())
    }

    private fun insertUser(postgres: PostgreSQLContainer<*>, id: UUID) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement(
                "INSERT INTO users(id, email, password_hash, role, enabled, created_at, updated_at) " +
                    "VALUES (?, 'restore@example.com', ?, 'ADMIN', true, now(), now())",
            ).use { statement ->
                statement.setObject(1, id)
                statement.setString(2, PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("unused-test-only"))
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun assertUser(postgres: PostgreSQLContainer<*>, id: UUID) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement("SELECT email, role, enabled FROM users WHERE id = ?").use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals("restore@example.com", result.getString("email"))
                    assertEquals("ADMIN", result.getString("role"))
                    assertTrue(result.getBoolean("enabled"))
                    assertFalse(result.next())
                }
            }
        }
    }

    private fun history(postgres: PostgreSQLContainer<*>): List<HistoryRow> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT installed_rank, version, type, script, checksum, success FROM public.flyway_schema_history ORDER BY installed_rank",
                ).use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                HistoryRow(
                                    result.getInt("installed_rank"), result.getString("version"), result.getString("type"),
                                    result.getString("script"), result.getObject("checksum") as Int?, result.getBoolean("success"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun execute(postgres: PostgreSQLContainer<*>, sql: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
    }

    private fun databaseOid(postgres: PostgreSQLContainer<*>): Long =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT oid::bigint FROM pg_catalog.pg_database WHERE datname=current_database()").use { result ->
                    assertTrue(result.next())
                    result.getLong(1)
                }
            }
        }

    private fun flyway(postgres: PostgreSQLContainer<*>, version: String): Flyway = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("classpath:db/migration")
        .target(version)
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .outOfOrder(false)
        .validateMigrationNaming(true)
        .validateOnMigrate(true)
        .load()

    private fun withDatabases(action: (PostgreSQLContainer<*>, PostgreSQLContainer<*>) -> Unit) {
        PostgreSQLContainer(image).withDatabaseName("kira_source").withUsername("kira").withPassword("test-only").use { source ->
            PostgreSQLContainer(image).withDatabaseName("kira_restore_fixture").withUsername("kira").withPassword("test-only").use { target ->
                source.start()
                target.start()
                action(source, target)
            }
        }
    }

    private fun run(arguments: List<String>, environment: Map<String, String> = emptyMap()): CommandResult {
        val stdout = Files.createTempFile(temporary, "command-", ".out")
        val stderr = Files.createTempFile(temporary, "command-", ".err")
        val builder =
            ProcessBuilder(arguments).directory(repository.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile())
        builder.environment().keys.removeIf { it.startsWith("PG") || it.startsWith("KIRA_") }
        builder.environment().putAll(environment)
        val process = builder.start()
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "disposable restore command timed out; no verified result")
            assertTrue(Files.size(stdout) <= 1024 * 1024 && Files.size(stderr) <= 1024 * 1024, "unexpected command output")
            return CommandResult(process.exitValue(), Files.readString(stdout), Files.readString(stderr))
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(10, TimeUnit.SECONDS)
            }
        }
    }

    private fun assertSuccess(result: CommandResult) = assertEquals(0, result.exitCode, result.error)

    private fun record(path: Path): JsonNode = ObjectMapper().readTree(Files.readAllBytes(path))

    private fun executable(name: String): Path =
        System.getenv("PATH").split(':')
            .map { Path.of(it).resolve(name).toAbsolutePath() }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
            ?: error("Real PG17 restore IT requires the Linux $name executable")

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class SelectedPair(
        val manifest: Path,
        val dump: Path,
        val media: Path,
        val pin: String,
        val attempt: Path,
        val mediaTarget: Path,
    )

    private data class HistoryRow(
        val rank: Int,
        val version: String?,
        val type: String,
        val script: String,
        val checksum: Int?,
        val success: Boolean,
    )

    private data class CommandResult(val exitCode: Int, val output: String, val error: String)

    private companion object {
        val image: DockerImageName = DockerImageName.parse("postgres:17.6-alpine")
        val repository: Path = Path.of("").toAbsolutePath().normalize()
        val scripts: Path = repository.resolve("scripts/db")
        val mediaBytes: ByteArray = byteArrayOf(0, 127, -1, 10, 42)
    }
}
