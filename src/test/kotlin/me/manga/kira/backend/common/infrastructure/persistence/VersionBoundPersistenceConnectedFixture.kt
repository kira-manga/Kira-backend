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
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

internal enum class ConnectedTlsClient { MATCHED, WRONG_CA, WRONG_HOST, WRONG_PASSWORD }

/** Thin use of the existing acquired configuration, fixed pools, root assertion owner and named JPA fixture. */
internal class VersionBoundPersistenceConnectedFixture(
    internal val database: PgLifecycleDatabaseFixture,
    val client: ConnectedTlsClient = ConnectedTlsClient.MATCHED,
    epochRotation: Boolean = false,
    private val desiredOperator: Boolean = false,
) : AutoCloseable {
    private val trustParent = database.versionBoundTls().publicTrustParent()
    private val suppliedPassword = when (client) {
        ConnectedTlsClient.WRONG_PASSWORD -> "synthetic-wrong-only"
        else -> if (desiredOperator) DESIRED_OPERATOR_TEST_PASSWORD else PgLifecycleDatabaseSettings.CANDIDATE_PASSWORD
    }.toByteArray(Charsets.UTF_8)
    var acquisitions = 0
        private set
    val acquired = AcquiredVersionedSecret.acquire(VersionBoundPersistenceTestInputs.binding()) { version ->
        acquisitions++
        SecretVersionSnapshot(version, suppliedPassword)
    }
    val configuration = if (desiredOperator) {
        VersionBoundPersistenceConfiguration.forDesiredInstallationOperator(
            acquired,
            if (client == ConnectedTlsClient.WRONG_HOST) "127.0.0.2" else database.host,
            database.port,
            PgLifecycleDatabaseSettings.DATABASE,
            database.versionBoundTls().publicTrust(client == ConnectedTlsClient.WRONG_CA),
            trustParent,
        )
    } else {
        VersionBoundPersistenceConfiguration.fromAcquired(
            acquired,
            if (client == ConnectedTlsClient.WRONG_HOST) "127.0.0.2" else database.host,
            database.port,
            PgLifecycleDatabaseSettings.DATABASE,
            PgLifecycleDatabaseSettings.CANDIDATE,
            2,
            database.versionBoundTls().publicTrust(client == ConnectedTlsClient.WRONG_CA),
            trustParent,
        )
    }
    val scope = PgLifecycleTestScope(
        when {
            desiredOperator -> configuration.bindDesiredInstallationOperatorOwner()
            epochRotation -> configuration.bindLifecycleOwnerWithEpochRotation()
            else -> configuration.bindLifecycleOwner()
        },
    )
    val owner = scope.owner
    lateinit var pools: VersionBoundPersistencePools
        private set
    private val sessions = linkedSetOf<TlsSession>()
    private var parentCreated = false
    private var trustPrepared = false
    private var stoppedBeforeClose = false
    private var closed = false

    init {
        check(!desiredOperator || !epochRotation)
        suppliedPassword.fill(0) // The connected path must use its captured acquisition, never a later caller buffer.
    }

    fun bind(
        profile: PersistencePoolLaunchProfile = PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY,
        nanoClock: PersistenceNanoClock = SystemPersistenceNanoClock,
    ) {
        pools = if (desiredOperator) owner.bindDesiredInstallationOperatorPools(nanoClock) else owner.bindVersionBoundPools(profile, nanoClock)
        // The original owner retains partial-shell custody if either named binding throws. The operator never selects TEST.
        Files.createDirectory(trustParent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        parentCreated = true
        assertEquals(PersistencePublicTrustPreparation.READY, owner.preparePublicTrust())
        trustPrepared = true
    }

    fun start() {
        check(!desiredOperator)
        assertEquals(PersistenceLifecycleActivation.STARTED, pools.ordinary.start(), client.name)
        awaitLifecycleFact { owner.snapshot().ordinaryReady && owner.snapshot().timerReady }
        assertEquals(PersistenceLifecycleObservation.READY, pools.ordinary.observePreparation())
        awaitLifecycleFact { scope.binding().isOwnedReceiverReady() }
    }

    fun startDesiredInstallationOperator() {
        check(desiredOperator)
        assertEquals(PersistenceLifecycleObservation.READY, owner.prepareDesiredInstallationOperator())
        assertEquals(PersistenceLifecycleObservation.READY, pools.catalogCoordinator.observePreparation())
        assertFalse(owner.snapshot().ordinaryReady || owner.snapshot().deletionReady)
        awaitLifecycleFact { owner.snapshot().catalogCoordinatorReady && owner.snapshot().timerReady }
    }

    fun tlsPid(source: GuardedDataSource): Int = source.connection.use(::tlsPid)

    fun tlsPid(connection: Connection): Int = connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT a.pid, a.backend_start, s.ssl, s.version, s.bits, current_user " +
                "FROM pg_stat_activity a JOIN pg_stat_ssl s USING (pid) WHERE a.pid = pg_backend_pid()",
        ).use(::retainTlsSession)
    }

    /** Observer-only proof for the nonpooled session; never borrows its private connection or first-delivery authority. */
    fun observeTlsPid(pid: Int): Int = ordinaryCleanupReader(database).connection.use { observer ->
        observer.prepareStatement(
            "SELECT a.pid, a.backend_start, s.ssl, s.version, s.bits, a.usename " +
                "FROM pg_stat_activity a JOIN pg_stat_ssl s USING (pid) WHERE a.pid = ? AND a.datname = current_database()",
        ).use { statement ->
            statement.queryTimeout = 2
            statement.setInt(1, pid)
            statement.executeQuery().use(::retainTlsSession).also { assertEquals(pid, it) }
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

    /** Stop this peer before another original root's shared Timer wait; this records sequencing, never retirement proof. */
    fun stopWithoutWaiting() {
        requireConnectionFree()
        owner.requestShutdown()
        pools.close()
        stoppedBeforeClose = true
    }

    override fun close() = closeSelected(listOf(this))

    /** Both independent roots stop before either shared driver Timer wait or database-wide session assertion. */
    fun closeWith(peer: VersionBoundPersistenceConnectedFixture) {
        check(peer !== this && peer.database === database)
        closeSelected(listOf(this, peer))
    }

    private fun completeClose() {
        assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, owner.observeShutdown())
        owner.epochRotation?.let { rotation ->
            assertEquals(PersistenceLifecycleObservation.EPOCH_ROTATION_LOCAL_ENDED, rotation.observeShutdown())
            val participant = poolTestField<PersistenceJdbcParticipant>(scope.root, "epochRotationParticipant")
            assertTrue(scope.actors(participant).all { it.termination().ended() && !it.thread.isAlive })
        }
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
                if (fixture.trustPrepared && !fixture.stoppedBeforeClose) {
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
            val allCandidates = "SELECT NOT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() AND usename IN (?, ?))"
            observer.prepareStatement(allCandidates).use { statement ->
                statement.queryTimeout = 2
                statement.setString(1, PgLifecycleDatabaseSettings.CANDIDATE)
                statement.setString(2, VersionBoundPersistenceConfiguration.DESIRED_INSTALLATION_OPERATOR_USERNAME)
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

    private fun retainTlsSession(row: ResultSet): Int {
        assertTrue(row.next())
        assertTrue(row.getBoolean(3) && !row.wasNull())
        assertTrue(row.getString(4) in setOf("TLSv1.2", "TLSv1.3") && row.getInt(5) >= 128)
        assertEquals(
            if (desiredOperator) VersionBoundPersistenceConfiguration.DESIRED_INSTALLATION_OPERATOR_USERNAME else PgLifecycleDatabaseSettings.CANDIDATE,
            row.getString(6),
        )
        val session = TlsSession(row.getInt(1), row.getTimestamp(2).toInstant())
        assertFalse(row.next())
        sessions.add(session)
        return session.pid
    }

    private data class TlsSession(val pid: Int, val started: Instant)
}

/** Dedicated synthetic fixture principal only. Production code never provisions or grants this login. */
internal const val DESIRED_OPERATOR_TEST_PASSWORD = "synthetic-desired-operator-only"
