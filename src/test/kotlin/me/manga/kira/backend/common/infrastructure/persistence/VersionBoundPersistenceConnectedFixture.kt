package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.security.AcquiredVersionedSecret
import me.manga.kira.backend.security.SecretVersionSnapshot
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant

internal enum class ConnectedTlsClient { MATCHED, WRONG_CA, WRONG_HOST, WRONG_PASSWORD }

/** Thin use of the existing acquired configuration, fixed pools, root assertion owner and named JPA fixture. */
internal class VersionBoundPersistenceConnectedFixture(
    private val database: PgLifecycleDatabaseFixture,
    val client: ConnectedTlsClient = ConnectedTlsClient.MATCHED,
) : AutoCloseable {
    private val trustParent = database.versionBoundTls().publicTrustParent()
    private val suppliedPassword = when (client) {
        ConnectedTlsClient.WRONG_PASSWORD -> "synthetic-wrong-only"
        else -> PgLifecycleDatabaseSettings.CANDIDATE_PASSWORD
    }.toByteArray(Charsets.UTF_8)
    var acquisitions = 0
        private set
    val acquired = AcquiredVersionedSecret.acquire(VersionBoundPersistenceTestInputs.binding()) { version ->
        acquisitions++
        SecretVersionSnapshot(version, suppliedPassword)
    }
    val configuration = VersionBoundPersistenceConfiguration.fromAcquired(
        acquired,
        if (client == ConnectedTlsClient.WRONG_HOST) "127.0.0.2" else database.host,
        database.port,
        PgLifecycleDatabaseSettings.DATABASE,
        PgLifecycleDatabaseSettings.CANDIDATE,
        2,
        database.versionBoundTls().publicTrust(client == ConnectedTlsClient.WRONG_CA),
        trustParent,
    )
    val scope = PgLifecycleTestScope(configuration.bindLifecycleOwner())
    val owner = scope.owner
    lateinit var pools: VersionBoundPersistencePools
        private set
    private val sessions = linkedSetOf<TlsSession>()
    private var parentCreated = false
    private var trustPrepared = false
    private var closed = false

    init {
        suppliedPassword.fill(0) // The connected path must use its captured acquisition, never a later caller buffer.
    }

    fun bind(profile: PersistencePoolLaunchProfile = PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY) {
        pools = owner.bindVersionBoundPools(profile) // The owner already retains partial-shell custody if this throws.
        Files.createDirectory(trustParent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        parentCreated = true
        assertEquals(PersistencePublicTrustPreparation.READY, owner.preparePublicTrust())
        trustPrepared = true
    }

    fun start() {
        assertEquals(PersistenceLifecycleActivation.STARTED, pools.ordinary.start(), client.name)
        awaitLifecycleFact { owner.snapshot().ordinaryReady && owner.snapshot().timerReady }
        assertEquals(PersistenceLifecycleObservation.READY, pools.ordinary.observePreparation())
        awaitLifecycleFact { scope.binding().isOwnedReceiverReady() }
    }

    fun tlsPid(source: GuardedDataSource): Int = source.connection.use(::tlsPid)

    fun tlsPid(connection: Connection): Int = connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT a.pid, a.backend_start, s.ssl, s.version, s.bits, current_user " +
                "FROM pg_stat_activity a JOIN pg_stat_ssl s USING (pid) WHERE a.pid = pg_backend_pid()",
        ).use { row ->
            assertTrue(row.next())
            assertTrue(row.getBoolean(3) && !row.wasNull())
            assertTrue(row.getString(4) in setOf("TLSv1.2", "TLSv1.3") && row.getInt(5) >= 128)
            assertEquals(PgLifecycleDatabaseSettings.CANDIDATE, row.getString(6))
            val session = TlsSession(row.getInt(1), row.getTimestamp(2).toInstant())
            assertFalse(row.next())
            sessions.add(session)
            session.pid
        }
    }

    fun withEnrollment(test: (OrdinaryComplaintInstallationEnrollmentFixture) -> Unit) {
        val reader = ordinaryCleanupReader(database)
        Flyway.configure().dataSource(reader).locations("classpath:db/migration").load().migrate()
        JdbcTemplate(reader).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }.execute(
            "GRANT USAGE ON SCHEMA public TO ${PgLifecycleDatabaseSettings.CANDIDATE}; " +
                "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${PgLifecycleDatabaseSettings.CANDIDATE}; " +
                "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${PgLifecycleDatabaseSettings.CANDIDATE}",
        )
        val factory = ordinaryCleanupFactory(pools.ordinary, includeAuditEntities = true)
        try {
            factory.afterPropertiesSet()
            val owned = OwnedCutPool(scope, pools.ordinary) // Assertion handle, not a separately started/closed root or pool.
            OrdinarySourceGrantCleanupFixture(owned, checkNotNull(factory.`object`), reader, maximumPoolSize = 2).use { ordinary ->
                SyntheticComplaintCounters(ordinary.foreignTemplate(), ordinary.cutoff).use { counters ->
                    counters.seed(0, closed = false)
                    OrdinaryComplaintInstallationEnrollmentFixture(ordinary, counters).use { enrollment ->
                        enrollment.seedDaily(enrollment.databaseDay(), 0, 100)
                        test(enrollment)
                    }
                }
            }
        } finally {
            factory.destroy() // Before the same fixed pool/root close; no alternate cleanup owner is introduced.
        }
    }

    fun trustPath(): Path {
        val endpoint = poolTestField<ResolvedPersistenceEndpoint>(configuration, "endpoint")
        return Path.of(endpoint.driverProperties().getProperty("sslrootcert"))
    }

    override fun close() = closeSelected(listOf(this))

    /** Both independent roots stop before either shared driver Timer wait or database-wide session assertion. */
    fun closeWith(peer: VersionBoundPersistenceConnectedFixture) {
        check(peer !== this && peer.database === database)
        closeSelected(listOf(this, peer))
    }

    private fun completeClose() {
        assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, owner.observeShutdown())
        assertEquals(PersistencePublicTrustRelease.RELEASED, owner.releasePublicTrustAfterShutdown())
        assertFalse(Files.exists(trustPath().parent))
        if (parentCreated) Files.delete(trustParent) // Never recursively remove unknown/retained trust material.
        assertSessionsEnded()
        requireConnectionFree()
        closed = true
    }

    private fun closeSelected(fixtures: List<VersionBoundPersistenceConnectedFixture>) {
        val selected = fixtures.filterNot { it.closed }
        val shutdown = selected.map { runCatching { it.owner.requestShutdown() } }
        val beforeClose = selected.map { fixture ->
            runCatching {
                if (fixture.trustPrepared) {
                    assertEquals(PersistencePublicTrustRelease.RETAINED, fixture.owner.releasePublicTrustAfterShutdown())
                }
            }
        }
        val poolsClosed = selected.map { runCatching { checkNotNull(it.owner.versionBoundPools).close() } }
        val rootsClosed = selected.map { runCatching { it.scope.close() } }
        requireCleanup(shutdown + beforeClose + poolsClosed + rootsClosed)
        // All roots have actually ended before either retains its unchanged global candidate-session proof.
        requireCleanup(selected.map { runCatching { it.completeClose() } })
    }

    private fun requireCleanup(results: List<Result<*>>) {
        val failures = results.mapNotNull { it.exceptionOrNull() }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).filterNot { it === first }.forEach(first::addSuppressed)
            throw first
        }
    }

    private fun assertSessionsEnded() {
        ordinaryCleanupReader(database).connection.use { observer ->
            val selectedSessions = "SELECT NOT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = ? AND backend_start = ?)"
            observer.prepareStatement(selectedSessions).use { statement ->
                statement.queryTimeout = 2
                sessions.forEach { session ->
                    statement.setInt(1, session.pid)
                    statement.setTimestamp(2, Timestamp.from(session.started))
                    awaitLifecycleFact {
                        statement.executeQuery().use { row ->
                            check(row.next())
                            val ended = row.getBoolean(1)
                            check(!row.next())
                            ended
                        }
                    }
                }
            }
            // This SAME_THREAD class owns the only candidate roots; paired close ends BOTH before this unchanged proof.
            val allCandidates = "SELECT NOT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() AND usename = ?)"
            observer.prepareStatement(allCandidates).use { statement ->
                statement.queryTimeout = 2
                statement.setString(1, PgLifecycleDatabaseSettings.CANDIDATE)
                awaitLifecycleFact {
                    statement.executeQuery().use { row ->
                        check(row.next())
                        val ended = row.getBoolean(1)
                        check(!row.next())
                        ended
                    }
                }
            }
        }
    }

    private data class TlsSession(val pid: Int, val started: Instant)
}
