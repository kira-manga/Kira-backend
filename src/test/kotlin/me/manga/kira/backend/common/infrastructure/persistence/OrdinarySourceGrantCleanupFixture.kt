package me.manga.kira.backend.common.infrastructure.persistence

import jakarta.persistence.EntityManagerFactory
import me.manga.kira.backend.complaint.infrastructure.transaction.OrdinaryPersistencePhaseExecutor
import me.manga.kira.backend.security.JdbcSourceGrantCleanupStore
import me.manga.kira.backend.security.SourceGrantCleanup
import me.manga.kira.backend.user.infrastructure.UserEntity
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import java.sql.Connection
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import javax.sql.DataSource

/** Reuses the retained PG/owned-pool owner. No second container, fake business table or product switch. */
internal fun withOrdinarySourceGrantCleanup(database: PgLifecycleDatabaseFixture, test: (OrdinarySourceGrantCleanupFixture) -> Unit) {
    withOrdinarySourceGrantCleanup(database, SystemPersistenceNanoClock, test)
}

internal fun withOrdinarySourceGrantCleanup(
    database: PgLifecycleDatabaseFixture,
    nanoClock: PersistenceNanoClock,
    test: (OrdinarySourceGrantCleanupFixture) -> Unit,
) {
    val reader = ordinaryCleanupReader(database)
    Flyway.configure().dataSource(reader).locations("classpath:db/migration").load().migrate()
    JdbcTemplate(reader).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }.execute(
        "GRANT USAGE ON SCHEMA public TO ${PgLifecycleDatabaseSettings.CANDIDATE}; " +
            "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${PgLifecycleDatabaseSettings.CANDIDATE}; " +
            "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${PgLifecycleDatabaseSettings.CANDIDATE}",
    )
    withOwnedCutPool(database) { owned ->
        val emf = ordinaryCleanupFactory(owned.pool)
        try {
            emf.afterPropertiesSet()
            OrdinarySourceGrantCleanupFixture(owned, requireNotNull(emf.`object`), reader, nanoClock).use(test)
        } finally {
            emf.destroy() // Before the same existing OwnedCutPool/PG owner verifies real teardown.
        }
    }
}

/** Independent-reader/lock connections are test-only and never lend authority to the guarded phase. */
internal class OrdinarySourceGrantCleanupFixture(
    val ownedPool: OwnedCutPool,
    val entityManagerFactory: EntityManagerFactory,
    private val reader: DriverManagerDataSource,
    nanoClock: PersistenceNanoClock = SystemPersistenceNanoClock,
) : AutoCloseable {
    val cutoff: Instant = Instant.parse("2026-09-12T12:00:00Z")
    val pool: GuardedDataSource get() = ownedPool.pool
    val jdbc = JdbcTemplate(pool).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
    val sourceStore: SourceGrantCleanup = JdbcSourceGrantCleanupStore(jdbc)
    val admission = OrdinaryPersistenceAdmission(1)
    val manager = GuardedJpaTransactionManager(entityManagerFactory, pool)
    val ownership = PersistencePhaseOwnership(admission, manager, nanoClock = nanoClock)
    val userId: UUID = UUID.randomUUID()
    private val independent = JdbcTemplate(reader).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }

    init {
        independent.update(
            "INSERT INTO users (id, email, password_hash, role, enabled, created_at, updated_at) VALUES (?, ?, ?, 'ADMIN', true, ?, ?)",
            userId,
            "w03-$userId@example.invalid",
            PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("synthetic-w03-password"),
            Timestamp.from(cutoff.minusSeconds(86_400)),
            Timestamp.from(cutoff.minusSeconds(86_400)),
        )
    }

    fun newExecutor(store: SourceGrantCleanup = sourceStore, clock: Clock = Clock.fixed(cutoff, ZoneOffset.UTC)): OrdinaryPersistencePhaseExecutor =
        OrdinaryPersistencePhaseExecutor(ownership, store, clock)

    fun seedGrant(id: UUID, scope: String, expiresAt: Instant, usedAt: Instant? = null) {
        independent.update(
            "INSERT INTO admin_step_up_grants (id, user_id, token_hash, scope, created_at, expires_at, used_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            id,
            userId,
            id.toString().replace("-", "").padEnd(64, '0'),
            scope,
            Timestamp.from(minOf(cutoff, expiresAt).minusSeconds(3_600)),
            Timestamp.from(expiresAt),
            usedAt?.let(Timestamp::from),
        )
    }

    fun grantIds(): Set<UUID> = independent.queryForList("SELECT id FROM admin_step_up_grants ORDER BY id", UUID::class.java).toSet()

    fun session(pid: Int): CleanupSession? = independent.query(
        "SELECT backend_start, xact_start IS NOT NULL FROM pg_stat_activity WHERE pid = ? AND datname = current_database()",
        { result, _ -> CleanupSession(result.getTimestamp(1).toInstant(), result.getBoolean(2)) },
        pid,
    ).singleOrNull()

    /** Test-only real remote termination fault; never installed on a product port/bean. */
    fun terminateSession(pid: Int) {
        check(independent.queryForObject("SELECT pg_terminate_backend(?)", Boolean::class.java, pid) == true)
    }

    fun foreignTemplate(): JdbcTemplate = JdbcTemplate(reader).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }

    /** Real, healthy same-endpoint EMF on a different DataSource, not another pool or a bad-profile refusal. */
    fun withForeignFactory(test: (EntityManagerFactory, () -> Long) -> Unit) {
        val attempts = AtomicLong()
        val foreign = object : DataSource by reader {
            override fun getConnection(): Connection {
                attempts.incrementAndGet()
                return reader.connection
            }
            override fun getConnection(username: String, password: String): Connection {
                attempts.incrementAndGet()
                return reader.getConnection(username, password)
            }
        }
        val emf = ordinaryCleanupFactory(foreign)
        try {
            emf.afterPropertiesSet()
            test(requireNotNull(emf.`object`), attempts::get)
        } finally {
            emf.destroy()
        }
    }

    fun observePgSleep(): CleanupPgSleepObserver = CleanupPgSleepObserver(reader)

    fun lockGrant(id: UUID): AutoCloseable {
        val connection = reader.connection
        try {
            connection.autoCommit = false
            connection.prepareStatement("SELECT id FROM admin_step_up_grants WHERE id = ? FOR UPDATE").use { statement ->
                statement.queryTimeout = 2
                statement.setObject(1, id)
                statement.executeQuery().use { result ->
                    check(result.next() && result.getObject(1, UUID::class.java) == id && !result.next())
                }
            }
        } catch (failure: Throwable) {
            connection.close()
            throw failure
        }
        val released = AtomicBoolean()
        return AutoCloseable {
            if (released.compareAndSet(false, true)) {
                try {
                    connection.rollback()
                } finally {
                    connection.close()
                }
            }
        }
    }

    override fun close() {
        requireConnectionFree() // Never hide a leaked named owner by deleting rows in a second transaction.
        independent.update("DELETE FROM admin_step_up_grants WHERE user_id = ?", userId)
        independent.update("DELETE FROM users WHERE id = ?", userId)
    }
}

internal data class CleanupSession(val backendStart: Instant, val inTransaction: Boolean)

internal data class CleanupSleepTarget(val pid: Int, val backendStart: Instant, val acceptedAtNanos: Long)

/** Independent observation only. No business/phase context crosses to this thread or its connection. */
internal class CleanupPgSleepObserver(reader: DriverManagerDataSource) : AutoCloseable {
    private val ready = CountDownLatch(1)
    private val targetReady = CountDownLatch(1)
    private val stopping = AtomicBoolean()
    private val target = AtomicReference<CleanupSleepTarget>()
    private val witness = AtomicReference<CleanupSleepTarget>()
    private val problem = AtomicReference<Throwable>()
    private val thread = Thread.ofPlatform().unstarted {
        try {
            reader.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() AND pid = ? " +
                        "AND backend_start = ? AND state = 'active' AND wait_event_type = 'Timeout' AND wait_event = 'PgSleep')",
                ).use { statement ->
                    statement.queryTimeout = 1
                    ready.countDown()
                    check(targetReady.await(5, TimeUnit.SECONDS) || stopping.get())
                    if (!stopping.get()) {
                        val selected = requireNotNull(target.get())
                        statement.setInt(1, selected.pid)
                        statement.setTimestamp(2, Timestamp.from(selected.backendStart))
                        while (!stopping.get() && System.nanoTime() - selected.acceptedAtNanos < 3_000_000_000L) {
                            val sleeping = statement.executeQuery().use { result ->
                                check(result.next())
                                result.getBoolean(1).also { check(!result.next()) }
                            }
                            if (sleeping) {
                                witness.set(selected)
                                break
                            }
                            LockSupport.parkNanos(1_000_000)
                        }
                    }
                }
            } // This observer alone closes its statement/connection, including every failed observation.
        } catch (failure: Throwable) {
            problem.set(failure)
        } finally {
            ready.countDown()
        }
    }

    init {
        thread.start()
    }

    fun awaitReady() {
        check(ready.await(5, TimeUnit.SECONDS) && problem.get() == null)
    }

    fun watch(selected: CleanupSleepTarget) {
        check(target.compareAndSet(null, selected))
        targetReady.countDown()
    }

    fun witnessed(): CleanupSleepTarget? = witness.get()

    override fun close() {
        stopping.set(true)
        targetReady.countDown()
        thread.join(3_000)
        check(!thread.isAlive) { "Independent PgSleep observer must end before fixture teardown." }
        check(problem.get() == null) { "Independent PgSleep observer failed." }
    }
}

private fun ordinaryCleanupFactory(selected: DataSource): LocalContainerEntityManagerFactoryBean = LocalContainerEntityManagerFactoryBean().apply {
    dataSource = selected
    setPackagesToScan(UserEntity::class.java.packageName)
    jpaVendorAdapter = HibernateJpaVendorAdapter().apply {
        setDatabasePlatform("org.hibernate.dialect.PostgreSQLDialect")
        setGenerateDdl(false)
        setShowSql(false)
    }
    setJpaPropertyMap(
        mapOf(
            "hibernate.hbm2ddl.auto" to "validate",
            "hibernate.jdbc.time_zone" to "UTC",
            "hibernate.boot.allow_jdbc_metadata_access" to "false",
        ),
    )
}

private fun ordinaryCleanupReader(database: PgLifecycleDatabaseFixture): DriverManagerDataSource = DriverManagerDataSource().apply {
    setUrl("jdbc:postgresql://${database.host}:${database.port}/${PgLifecycleDatabaseSettings.DATABASE}")
    username = PgLifecycleDatabaseSettings.OBSERVER
    password = PgLifecycleDatabaseSettings.OBSERVER_PASSWORD
    connectionProperties = Properties().apply {
        setProperty("ApplicationName", "w03o_cleanup")
        setProperty("sslmode", "disable")
        setProperty("gssEncMode", "disable")
        setProperty("requireAuth", "scram-sha-256")
        setProperty("channelBinding", "disable")
        setProperty("loginTimeout", "0")
        setProperty("connectTimeout", "2")
        setProperty("socketTimeout", "2")
    }
}
