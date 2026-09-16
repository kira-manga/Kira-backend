package me.manga.kira.backend.database.complaint

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Raw Flyway tests never share the Spring database: its startup sequence inspection is database-wide. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ComplaintPostgresTest {
    protected lateinit var database: ComplaintTestDatabase

    @BeforeAll
    fun startComplaintDatabase() {
        database = ComplaintTestDatabase()
        database.start()
    }

    @AfterAll
    fun stopComplaintDatabase() {
        if (::database.isInitialized) database.close()
    }
}

class ComplaintTestDatabase : AutoCloseable {
    val postgres: PostgreSQLContainer<*> = newComplaintPostgres("kira_complaint_schema")

    fun start() {
        try {
            postgres.start()
        } catch (failure: RuntimeException) {
            try {
                close()
            } catch (cleanup: RuntimeException) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    override fun close() = postgres.stop()

    fun createSchema(): ComplaintSchema {
        val name = "complaint_" + UUID.randomUUID().toString().replace("-", "")
        connection().use { it.exec("CREATE SCHEMA $name") }
        return ComplaintSchema(this, name)
    }

    fun <T> schema(block: (ComplaintSchema) -> T): T = createSchema().use(block)

    fun connection(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
}

class ComplaintSchema(private val database: ComplaintTestDatabase, val name: String) : AutoCloseable {
    fun flyway(target: Int? = null, location: String = "classpath:db/migration"): Flyway = Flyway.configure()
        .dataSource(database.postgres.jdbcUrl, database.postgres.username, database.postgres.password)
        .defaultSchema(name)
        .schemas(name)
        .locations(location)
        .target(target?.let { MigrationVersion.fromVersion(it.toString()) } ?: MigrationVersion.LATEST)
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .outOfOrder(false)
        .validateMigrationNaming(true)
        .validateOnMigrate(true)
        .load()

    fun connection(): Connection {
        val connection = database.connection()
        try {
            connection.exec("SET search_path TO $name")
            return connection
        } catch (failure: java.sql.SQLException) {
            closeAfterFailure(connection, failure)
            throw failure
        } catch (failure: RuntimeException) {
            closeAfterFailure(connection, failure)
            throw failure
        }
    }

    override fun close() {
        // Owned random schema in an owned synthetic Testcontainer, never an environment datasource.
        database.connection().use { it.exec("DROP SCHEMA $name CASCADE") }
    }

    fun exec(sql: String) = connection().use { it.exec(sql) }

    fun strings(sql: String): List<String> = connection().use { it.strings(sql) }

    fun history(): List<String> = strings(
        "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
    )
}

fun newComplaintPostgres(databaseName: String): PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:17.6-alpine"))
    .withDatabaseName(databaseName)
    .withUsername("kira")
    .withPassword("synthetic-test-only")
    .withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8 --locale=C")
    .withCommand("postgres", "-c", "max_connections=35", "-c", "shared_buffers=64MB")
    .withReuse(false)

fun Connection.exec(sql: String) {
    createStatement().use {
        it.queryTimeout = 30
        it.execute(sql)
    }
}

fun Connection.strings(sql: String): List<String> = createStatement().use { statement ->
    statement.queryTimeout = 30
    statement.executeQuery(sql).use { result ->
        buildList {
            while (result.next()) add(result.getString(1))
        }
    }
}

fun complaintResourceBytes(path: String): ByteArray = requireNotNull(ComplaintSchema::class.java.classLoader.getResourceAsStream(path)) {
    "Missing synthetic test resource: $path"
}.use { it.readBytes() }

fun complaintResource(path: String): String = complaintResourceBytes(path).toString(Charsets.UTF_8)

fun sqlText(value: String): String = "'" + value.replace("'", "''") + "'"

private fun closeAfterFailure(connection: Connection, failure: Exception) {
    try {
        connection.close()
    } catch (cleanup: java.sql.SQLException) {
        failure.addSuppressed(cleanup)
    }
}

/** A failed PostgreSQL statement aborts its transaction; restore a savepoint before the next case. */
fun Connection.expectSqlFailure(sql: String, state: String = "23514", constraint: String? = null): java.sql.SQLException {
    check(!autoCommit)
    val savepoint = setSavepoint()
    try {
        return assertSqlFailure(state, constraint) { exec(sql) }
    } finally {
        rollback(savepoint)
        releaseSavepoint(savepoint)
    }
}

fun <T> Connection.withRollbackPoint(block: () -> T): T {
    check(!autoCommit)
    val savepoint = setSavepoint()
    try {
        return block()
    } finally {
        rollback(savepoint)
        releaseSavepoint(savepoint)
    }
}
