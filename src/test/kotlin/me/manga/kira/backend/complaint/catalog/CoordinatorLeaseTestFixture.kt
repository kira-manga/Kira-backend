package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseBindingV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.CurrentAcceptedCatalogRefreshV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintCoordinatorLeasePersistencePhaseExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** The same original TLS/root fixture and genuine owned SDK -> G1 result; never a supplied head or lease success. */
internal fun withCoordinatorLease(tls: VersionBoundPersistenceConnectedFixture, test: (CoordinatorLeaseTestFixture) -> Unit) =
    withProcessBoundCatalogGenesis(
        tls,
        bindProcess = { consumers, pools ->
            val writer = consumers.journalConfiguration.declaration().writer
            VersionBoundComplaintProcessConfiguration.fromRetained(
                consumers,
                pools,
                1,
                7,
                UUID.fromString(writer.databaseIdentity),
                UUID.fromString(writer.restoreIdentity),
                VersionBoundCatalogReadbackTestFixture.settings(),
            )
        },
    ) { genesis ->
        genesis.stageSigned()
        val wire = CurrentAcceptedCatalogRefreshHttpFixture(genesis)
        val refresh = wire.owner().use { it.refresh() }
        wire.assertFullReadback()
        val fixture = CoordinatorLeaseTestFixture(genesis, refresh)
        try {
            test(fixture)
        } finally {
            fixture.jdbc.beforeSql = {}
            fixture.jdbc.afterSql = {}
            fixture.released()
        }
    }

/** Probe callbacks surround real JDBC only. Outer G1/counter fixtures retain their original exact-row cleanup. */
internal class CoordinatorLeaseTestFixture(val genesis: ProcessBoundCatalogGenesisFixture, val refresh: CurrentAcceptedCatalogRefreshV1.Result) {
    val process = genesis.process
    val coordinator = genesis.coordinator
    val observer = genesis.observer
    val binding = CatalogCoordinatorLeaseBindingV1.fromRetained(process, refresh)
    val jdbc = CoordinatorLeaseProbeJdbc(genesis)
    val phases = ComplaintCoordinatorLeasePersistencePhaseExecutor(coordinator, jdbc)

    fun retainedOperation(): CatalogCoordinatorLeaseOperation =
        ownedCutField(checkNotNull(jdbc.phase).coordinatorLease, "retained") as CatalogCoordinatorLeaseOperation

    fun state(): List<String> = genesis.state()

    fun unchangedOutsideLease(): List<String> =
        observer.queryForList("SELECT to_jsonb(m)::text FROM complaint_catalog_mutations m ORDER BY operation_token", String::class.java) +
            observer.queryForList(
                "SELECT (to_jsonb(c) - ARRAY['lease_owner','lease_token','lease_expires_at','updated_at'])::text " +
                    "FROM complaint_journal_control c ORDER BY data_scope_id",
                String::class.java,
            ) + observer.queryForList("SELECT to_jsonb(c)::text FROM complaint_capacity_counters c ORDER BY name", String::class.java)

    fun row(): CoordinatorLeaseRow = checkNotNull(
        observer.queryForObject(
            "SELECT lease_owner, lease_token, lease_expires_at, updated_at FROM complaint_journal_control WHERE data_scope_id = ?",
            { row, _ ->
                CoordinatorLeaseRow(
                    row.getObject("lease_owner", UUID::class.java),
                    row.getLong("lease_token"),
                    row.getTimestamp("lease_expires_at")?.toInstant(),
                    row.getTimestamp("updated_at").toInstant(),
                )
            },
            ComplaintDataScope.LIVE.id,
        ),
    )

    /** Explicit fixture expiry of its own row, not a production clock, renewal, receipt or authorized supersession. */
    fun expireForTest() {
        assertEquals(
            1,
            observer.update(
                "UPDATE complaint_journal_control SET lease_expires_at = updated_at WHERE data_scope_id = ? AND lease_owner IS NOT NULL",
                ComplaintDataScope.LIVE.id,
            ),
        )
    }

    fun released() {
        jdbc.assertNoLostAssertions()
        genesis.released()
    }
}

internal data class CoordinatorLeaseRow(val owner: UUID?, val token: Long, val expiresAt: Instant?, val updatedAt: Instant)

internal enum class CoordinatorLeaseSqlStep { LOCK_CONTROL, WRITE_CONTROL, READ_CONTROL }

/** Observes the actual original holder; expected sanitized refusals cannot hide an assertion made in a SQL cut. */
internal class CoordinatorLeaseProbeJdbc(private val genesis: ProcessBoundCatalogGenesisFixture) : JdbcTemplate(genesis.coordinator.dataSource) {
    var phase: PersistencePhaseContext? = null
        private set
    var observation: StepUpPhaseObservation? = null
        private set
    val steps = mutableListOf<CoordinatorLeaseSqlStep>()
    var beforeSql: (CoordinatorLeaseSqlStep) -> Unit = {}
    var afterSql: (CoordinatorLeaseSqlStep) -> Unit = {}
    private val assertionFailure = AtomicReference<AssertionError?>()

    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>): List<T> = observed(sql) { super.query(sql, rowMapper) }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> =
        observed(sql) { super.query(sql, rowMapper, *args) }

    override fun update(sql: String, vararg args: Any?): Int = observed(sql) { super.update(sql, *args) }

    fun assertNoLostAssertions() {
        assertionFailure.get()?.let { throw it }
    }

    fun <T> preserveAssertions(action: () -> T): T = try {
        action()
    } catch (failure: AssertionError) {
        assertionFailure.compareAndSet(null, failure)
        throw failure
    }

    private fun <T> observed(sql: String, action: () -> T): T = preserveAssertions {
        val current = checkNotNull(PersistencePhaseOwnership.current())
        val holder = TransactionSynchronizationManager.getResource(genesis.coordinator.dataSource) as ConnectionHolder
        assertEquals(setOf(genesis.coordinator.dataSource), TransactionSynchronizationManager.getResourceMap().keys)
        assertSame(genesis.coordinator.dataSource, dataSource)
        assertEquals(1, genesis.coordinator.activeSnapshotOwners())
        assertFalse(holder.connection.isReadOnly)
        if (phase !== current) {
            val identity = holder.connection.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_backend_pid(), txid_current()").use { row ->
                    assertTrue(row.next())
                    val result = row.getInt(1) to row.getLong(2)
                    assertFalse(row.next())
                    result
                }
            }
            phase = current
            observation = StepUpPhaseObservation(current, ownedPoolLease(holder.connection), identity)
        }
        val step = when {
            sql.contains("FOR UPDATE") -> CoordinatorLeaseSqlStep.LOCK_CONTROL
            sql.contains("UPDATE complaint_journal_control") -> CoordinatorLeaseSqlStep.WRITE_CONTROL
            sql.contains("FROM complaint_journal_control") -> CoordinatorLeaseSqlStep.READ_CONTROL
            else -> error("Unexpected coordinator lease statement.")
        }
        steps.add(step)
        beforeSql(step)
        action().also { afterSql(step) }
    }
}
