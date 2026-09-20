package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintGrantCleanupPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.OrdinaryPersistencePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ScopedAdminStepUpPhaseExecutor
import me.manga.kira.backend.config.KiraAdminStudioProperties
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.database.complaint.complaintResource
import me.manga.kira.backend.security.AdminStepUpService
import me.manga.kira.backend.security.AdminStepUpService.Companion.SOURCE_ADMIN_MUTATION_SCOPE
import me.manga.kira.backend.security.AuthThrottleService
import me.manga.kira.backend.security.IssuedAdminStepUp
import me.manga.kira.backend.security.JdbcAdminStepUpGrantRepository
import me.manga.kira.backend.security.JdbcComplaintGrantCleanupStore
import me.manga.kira.backend.security.JdbcScopedAdminStepUpStore
import me.manga.kira.backend.security.JdbcSourceGrantCleanupStore
import me.manga.kira.backend.security.ScopedAdminStepUpIssuer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.aop.framework.ProxyFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Reuse the existing owned PG/JPA fixture; these four cases follow the actual split live service. */
internal fun withSourceStepUpCleanup(database: PgLifecycleDatabaseFixture, test: (SourceStepUpCleanupFixture) -> Unit) {
    withOrdinarySourceGrantCleanup(database) { ordinary ->
        SourceStepUpCleanupFixture(ordinary).use(test)
    }
}

/** Fault hooks delegate actual SQL; cleanup and insertion now use separate committed/released phases. */
internal class SourceStepUpCleanupFixture(private val ordinary: OrdinarySourceGrantCleanupFixture) : AutoCloseable {
    private val selected = ordinary.pool
    private val factory = ordinary.entityManagerFactory
    private val observer = requireNotNull(ordinary.foreignTemplate().dataSource)
    val cutoff: Instant = ordinary.cutoff
    val userId: UUID = ordinary.userId
    val deletedCounts = mutableListOf<Int>()
    val insertedIds = mutableListOf<UUID>()
    val transactions = mutableListOf<Pair<Int, Long>>()
    var afterInsert: (UUID) -> Unit = {}
    val jdbc = object : JdbcTemplate(selected) {
        init {
            exceptionTranslator = SQLExceptionSubclassTranslator()
            queryTimeout = 2
        }

        override fun update(sql: String, vararg args: Any?): Int {
            val cleanup = sql.contains("DELETE FROM admin_step_up_grants AS grant_row")
            val insertion = sql.startsWith("INSERT INTO admin_step_up_grants")
            if (cleanup || insertion) observeTransaction()
            val count = super.update(sql, *args)
            if (cleanup) deletedCounts.add(count)
            if (insertion) {
                assertEquals(1, count)
                val id = args.first() as UUID
                insertedIds.add(id)
                afterInsert(id) // After the real INSERT, not a mocked successful write.
            }
            return count
        }
    }
    private val independent = JdbcTemplate(observer).apply {
        exceptionTranslator = SQLExceptionSubclassTranslator()
        queryTimeout = 2
    }
    private val clock = Clock.fixed(cutoff, ZoneOffset.UTC)
    private val properties = KiraAdminStudioProperties()
    private val passwords = PasswordEncoderFactories.createDelegatingPasswordEncoder()
    private val grants = JdbcAdminStepUpGrantRepository(jdbc)
    private val service = proxiedService()

    init {
        independent.execute(complaintResource("fixtures/complaint/reset.sql"))
    }

    fun issue(): IssuedAdminStepUp = service.issue(userId, PASSWORD, "127.0.0.1")

    fun seedGrant(id: UUID, scope: String, expiresAt: Instant, usedAt: Instant? = null) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
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

    /** Synthetic accounting only; configuration remains closed/null, never an authenticated complaint-policy opener. */
    fun seedComplaintCharges(count: Int) {
        for ((name, units) in listOf("moderation_grants" to count.toLong(), "storage_bytes" to count * 16_384L)) {
            assertEquals(
                1,
                independent.update(
                    "UPDATE complaint_capacity_counters SET hard_limit = ?, actual_units = ? " +
                        "WHERE name = ? AND configuration_closed AND configuration_hash IS NULL AND hard_limit = 0 AND actual_units = 0",
                    units,
                    units,
                    name,
                ),
            )
        }
    }

    fun grantIds(): Set<UUID> = read("SELECT id FROM admin_step_up_grants WHERE user_id = ? ORDER BY id", userId) {
        it.getObject(1, UUID::class.java)
    }.toSet()

    fun grantSnapshot(): List<String> = read("SELECT row_to_json(g)::text FROM admin_step_up_grants g WHERE user_id = ? ORDER BY id", userId) {
        it.getString(1)
    }

    fun grantSnapshot(scope: String): List<String> = read(
        "SELECT row_to_json(g)::text FROM admin_step_up_grants g WHERE user_id = ? AND scope = ? ORDER BY id",
        userId,
        scope,
    ) { it.getString(1) }

    fun counterSnapshot(): List<String> = read("SELECT row_to_json(c)::text FROM complaint_capacity_counters c ORDER BY name") {
        it.getString(1)
    }.also { assertEquals(22, it.size) }

    fun issuedId(issued: IssuedAdminStepUp): UUID = read(
        "SELECT id, scope, created_at, expires_at, used_at FROM admin_step_up_grants WHERE user_id = ? AND token_hash = ?",
        userId,
        Sha256.hexUtf8(issued.token),
    ) { row ->
        assertEquals(SOURCE_ADMIN_MUTATION_SCOPE, row.getString("scope"))
        assertEquals(cutoff, row.getTimestamp("created_at").toInstant())
        assertEquals(cutoff.plus(properties.stepUpTtl), row.getTimestamp("expires_at").toInstant())
        assertEquals(cutoff.plus(properties.stepUpTtl), issued.expiresAt)
        assertNull(row.getTimestamp("used_at"))
        row.getObject("id", UUID::class.java)
    }.single()

    fun lockGrant(id: UUID): AutoCloseable {
        val connection = observer.connection
        try {
            connection.autoCommit = false
            connection.prepareStatement("SELECT id FROM admin_step_up_grants WHERE id = ? FOR UPDATE").use { statement ->
                statement.queryTimeout = 2
                statement.setObject(1, id)
                statement.executeQuery().use { rows ->
                    check(rows.next() && rows.getObject(1, UUID::class.java) == id && !rows.next())
                }
            }
        } catch (failure: Throwable) {
            connection.close()
            throw failure
        }
        return AutoCloseable {
            try {
                connection.rollback()
            } finally {
                connection.close()
            }
        }
    }

    fun assertSplitTransactions() {
        assertEquals(2, transactions.size, "One cleanup and one insert must execute through the real service.")
        assertEquals(2, transactions.map { it.second }.distinct().size, "Completed cleanup must precede a fresh issuance transaction.")
    }

    override fun close() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        independent.update("DELETE FROM admin_step_up_grants WHERE user_id = ?", userId)
        independent.execute(complaintResource("fixtures/complaint/reset.sql"))
    }

    private fun proxiedService(): AdminStepUpService {
        val capacity = JdbcComplaintCapacityStore(jdbc, expectedPolicyDigest = null)
        val phases = ScopedAdminStepUpPhaseExecutor(
            ordinary.ownership,
            JdbcScopedAdminStepUpStore(jdbc, capacity, clock, properties),
            OrdinaryPersistencePhaseExecutor(ordinary.ownership, JdbcSourceGrantCleanupStore(jdbc), clock),
            ComplaintGrantCleanupPhaseExecutor(ordinary.ownership, JdbcComplaintGrantCleanupStore(jdbc, capacity), clock),
        )
        val target = AdminStepUpService(
            ScopedAdminStepUpIssuer(phases, passwords, AuthThrottleService(KiraSecurityProperties(), clock)),
            grants,
            clock,
        )
        val advice = TransactionInterceptor().apply {
            transactionManager = ordinary.manager
            transactionAttributeSource = AnnotationTransactionAttributeSource()
            afterPropertiesSet()
        }
        return ProxyFactory(target).apply {
            isProxyTargetClass = true
            addAdvice(advice)
        }.proxy as AdminStepUpService
    }

    private fun observeTransaction() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        val connectionHolder = TransactionSynchronizationManager.getResource(selected) as ConnectionHolder
        val entityHolder = TransactionSynchronizationManager.getResource(factory) as EntityManagerHolder
        val identity = requireNotNull(jdbc.queryForObject("SELECT pg_backend_pid(), txid_current()", { rows, _ -> rows.getInt(1) to rows.getLong(2) }))
        val jpaIdentity = entityHolder.entityManager.createNativeQuery("SELECT pg_backend_pid(), txid_current()").singleResult as Array<*>
        assertEquals(identity.first, (jpaIdentity[0] as Number).toInt())
        assertEquals(identity.second, (jpaIdentity[1] as Number).toLong())
        assertSame(connectionHolder, TransactionSynchronizationManager.getResource(selected))
        transactions.add(identity)
    }

    /** Raw independent JDBC, even during the fault hook: do not enlist the observer through Spring's DataSourceUtils. */
    private fun <T> read(sql: String, vararg arguments: Any, row: (ResultSet) -> T): List<T> = observer.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.queryTimeout = 2
            arguments.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(row(rows)) }
            }
        }
    }

    private companion object {
        const val PASSWORD = "synthetic-w03-password"
    }
}
