package me.manga.kira.backend.common.infrastructure.persistence

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.Properties
import java.util.UUID

/** Parent JUnit owns this exact disposable server. No Spring, pool, Flyway, business table or external datasource. */
internal class PgLifecycleDatabaseFixture : AutoCloseable {
    private val postgres = newPostgres()
    private lateinit var generation: Instant

    val host: String get() = postgres.host
    val port: Int get() = postgres.firstMappedPort

    fun start() {
        try {
            postgres.start()
            connection("w03o_bootstrap").use { connection ->
                connection.createStatement().use { statement ->
                    statement.queryTimeout = 2
                    statement.executeQuery(
                        "SELECT pg_postmaster_start_time(), current_setting('server_version_num'), current_setting('server_encoding')",
                    ).use { result ->
                        check(result.next())
                        generation = result.getTimestamp(1).toInstant()
                        check(result.getString(2) == "170006" && result.getString(3) == "UTF8" && !result.next())
                    }
                    statement.execute(
                        "CREATE ROLE ${PgLifecycleDatabaseSettings.CANDIDATE} LOGIN PASSWORD '${PgLifecycleDatabaseSettings.CANDIDATE_PASSWORD}'",
                    )
                }
            }
        } catch (failure: Throwable) {
            runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    fun observer(nonce: String = UUID.randomUUID().toString()): PgLifecycleDatabaseObserver {
        check(UUID.fromString(nonce).toString() == nonce)
        return PgLifecycleDatabaseObserver(connection("w03o_$nonce"), generation)
    }

    private fun connection(application: String): Connection {
        val properties = Properties().apply {
            setProperty("user", PgLifecycleDatabaseSettings.OBSERVER)
            setProperty("password", PgLifecycleDatabaseSettings.OBSERVER_PASSWORD)
            setProperty("ApplicationName", application)
            setProperty("assumeMinServerVersion", "17")
            setProperty("sslmode", "disable")
            setProperty("gssEncMode", "disable")
            setProperty("requireAuth", "scram-sha-256")
            setProperty("channelBinding", "disable")
            setProperty("loginTimeout", "4")
            setProperty("connectTimeout", "2")
            setProperty("socketTimeout", "2")
            setProperty("queryTimeout", "0") // Constructor control; observer statement timeouts are set only after actual connection return.
        }
        val connection = DriverManager.getConnection(postgres.jdbcUrl, properties)
        try {
            connection.autoCommit = true
            connection.setNetworkTimeout({ command -> command.run() }, 2_000)
            return connection
        } catch (failure: Throwable) {
            runCatching { connection.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun close() = postgres.stop()

    private fun newPostgres(): PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:17.6-alpine"))
        .withDatabaseName(PgLifecycleDatabaseSettings.DATABASE)
        .withUsername(PgLifecycleDatabaseSettings.OBSERVER)
        .withPassword(PgLifecycleDatabaseSettings.OBSERVER_PASSWORD)
        .withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8 --locale=C --auth-host=scram-sha-256")
        .withEnv("POSTGRES_HOST_AUTH_METHOD", "scram-sha-256")
        .withCommand("postgres", "-c", "max_connections=35", "-c", "shared_buffers=64MB", "-c", "password_encryption=scram-sha-256")
        .withReuse(false)
}
