package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipant
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhysicalEntry
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhysicalFactoryBinding
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSession
import me.manga.kira.backend.common.infrastructure.persistence.PoolLifecycle
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseAcquisitionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseBindingV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseCampaignV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CurrentAcceptedCatalogRefreshV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import kotlin.concurrent.withLock

/** Existing owned TLS/PG and genuine SDK -> G1 setup. No supplied lease, cutoff, native result or replacement executor. */
internal fun withEpochRotation(tls: VersionBoundPersistenceConnectedFixture, test: (EpochRotationTestFixture) -> Unit) = withProcessBoundCatalogGenesis(
    tls,
    bindProcess = { consumers, pools ->
        val writer = consumers.journalConfiguration.declaration().writer
        VersionBoundComplaintProcessConfiguration.fromRetainedWithEpochRotation(
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
    EpochRotationTestFixture(tls, genesis, refresh).use(test)
}

/** Only observer SQL and read-only own-project reflection; production phases retain their original JdbcTemplate identity. */
internal class EpochRotationTestFixture(
    val tls: VersionBoundPersistenceConnectedFixture,
    val genesis: ProcessBoundCatalogGenesisFixture,
    refresh: CurrentAcceptedCatalogRefreshV1.Result,
) : AutoCloseable {
    val process = genesis.process
    val observer = genesis.observer
    val coordinator = process.pools.catalogCoordinator
    val resource = checkNotNull(process.pools.epochRotation)
    val protocol = coordinator.epochRotation
    val binding = CatalogCoordinatorLeaseBindingV1.fromRetained(process, refresh)
    val pooledPids: Set<Int>
        get() = listOf(process.pools.ordinary, process.pools.deletion, coordinator.dataSource).map(tls::tlsPid).toSet()
    private val campaigns = mutableListOf<CatalogCoordinatorLeaseCampaignV1>()
    private val participant = poolTestField<PersistenceJdbcParticipant>(tls.scope.root, "epochRotationParticipant")
    private val physicalBinding = poolTestField<PersistencePhysicalFactoryBinding>(participant, "binding")

    fun prepare() {
        assertSame(tls.owner.epochRotation, resource)
        assertSame(tls.owner.versionBoundPools, process.pools)
        assertTrue(resource.belongsTo(process.pools))
        assertEquals(3, pooledPids.size)
        assertEquals(PersistenceLifecycleObservation.NOT_REQUESTED, resource.observePreparation())
        assertTrue(entries().isEmpty())
        assertEquals(PersistenceLifecycleObservation.READY, resource.prepare())
        assertTrue(entries().isEmpty(), "Preparing the role must not open a fourth pool or an idle physical connection.")
    }

    fun acquire(): CatalogCoordinatorLeaseAcquisitionV1 = coordinator.lease.acquire(binding).also { campaigns.add(it.campaign) }

    fun successor(previous: CatalogCoordinatorLeaseCampaignV1): CatalogCoordinatorLeaseAcquisitionV1 {
        coordinator.lease.relinquish(previous)
        released()
        return acquire()
    }

    fun entries(): List<PersistencePhysicalEntry> = physicalBinding.ledger.lock.withLock { physicalBinding.ledger.entries.filterNotNull() }

    fun released() {
        assertEquals(0, coordinator.activeSnapshotOwners())
        assertEquals(0L, poolTestField<PoolLifecycle>(coordinator.dataSource, "lifecycle").activeAcquisitions())
        genesis.released()
    }

    fun awaitReclaimed(entry: PersistencePhysicalEntry) {
        awaitLifecycleFact { entry.jdbc.terminalCompletion().reclaimed() }
        assertTrue(entry.jdbc.terminalCompletion().reclaimed(), "Retirement requests and empty counts are not physical reclamation receipts.")
        awaitLifecycleFact { entries().isEmpty() }
        requireConnectionFree()
    }

    fun row(): EpochRotationObservedRow = checkNotNull(
        observer.queryForObject(
            "SELECT publication_epoch, scan_requested, rotation_sequence, rotation_id, rotation_state, rotation_epoch_before, " +
                "rotation_event_writer_generation, rotation_request_owner, rotation_request_token, rotation_requested_at, " +
                "rotation_capture_owner, rotation_capture_token, rotation_captured_at, rotation_epoch_after " +
                "FROM complaint_journal_control WHERE data_scope_id = ?",
            { row, _ ->
                val slot = row.getObject("rotation_id", UUID::class.java)?.let { id ->
                    EpochRotationObservedSlot(
                        id,
                        checkNotNull(row.getString("rotation_state")),
                        row.getLong("rotation_epoch_before"),
                        checkNotNull(row.getObject("rotation_event_writer_generation", UUID::class.java)),
                        provenance(row, "request", "requested"),
                        row.getObject("rotation_capture_owner", UUID::class.java)?.let { provenance(row, "capture", "captured") },
                        row.getLong("rotation_epoch_after").let { if (row.wasNull()) null else it },
                    )
                }
                EpochRotationObservedRow(row.getLong("publication_epoch"), row.getBoolean("scan_requested"), row.getLong("rotation_sequence"), slot)
            },
            ComplaintDataScope.LIVE.id,
        ),
    )

    /** A real independent shared transaction: never a before-query callback mistaken for an acquired PG fence. */
    fun <T> sharedEpochHolder(action: (Connection, JdbcTemplate, Int) -> T): T = independentTransaction { connection, jdbc ->
        jdbc.execute("SELECT pg_advisory_xact_lock_shared(hashtextextended('complaint-journal-epoch', 0))")
        val pid = checkNotNull(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java))
        action(connection, jdbc, pid)
    }

    fun waitingCapture(holderPid: Int): Pair<PgLifecycleDatabaseSession, PersistencePhysicalEntry> {
        var session: PgLifecycleDatabaseSession? = null
        awaitLifecycleFact(2_000) {
            session = observer.query(
                "SELECT a.pid, a.backend_start FROM pg_stat_activity a WHERE a.datname = current_database() AND a.usename = ? " +
                    "AND ? = ANY(pg_blocking_pids(a.pid)) AND EXISTS " +
                    "(SELECT 1 FROM pg_locks l WHERE l.pid = a.pid AND l.locktype = 'advisory' AND NOT l.granted)",
                { row, _ -> PgLifecycleDatabaseSession(row.getInt(1), row.getTimestamp(2).toInstant()) },
                PgLifecycleDatabaseSettings.CANDIDATE,
                holderPid,
            ).singleOrNull()
            session != null
        }
        return checkNotNull(session) to entries().single()
    }

    /** A fixture-owned deferred FK makes the actual PG COMMIT fail; no driver/commit-result proxy or production callback. */
    fun <T> deferredCommitRefusal(allowedState: String?, action: () -> T): T {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val table = "kira_rotation_cut_$nonce"
        val constraint = "kira_rotation_fk_$nonce"
        assertEquals(0L, observer.queryForObject("SELECT count(*) FROM pg_class WHERE relname = ?", Long::class.java, table))
        assertEquals(0L, observer.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = ?", Long::class.java, constraint))
        try {
            observer.execute("CREATE TABLE $table (state text PRIMARY KEY)")
            if (allowedState != null) assertEquals(1, observer.update("INSERT INTO $table VALUES (?)", allowedState))
            observer.execute(
                "ALTER TABLE complaint_journal_control ADD CONSTRAINT $constraint FOREIGN KEY (rotation_state) " +
                    "REFERENCES $table(state) DEFERRABLE INITIALLY DEFERRED",
            )
            return action()
        } finally {
            // Unique names were absent before this fixture's DDL. Never clean another test's relation or constraint.
            observer.execute("ALTER TABLE complaint_journal_control DROP CONSTRAINT IF EXISTS $constraint")
            observer.execute("DROP TABLE IF EXISTS $table")
        }
    }

    fun outsideRotationAndLease(): List<String> =
        observer.queryForList("SELECT to_jsonb(m)::text FROM complaint_catalog_mutations m ORDER BY operation_token", String::class.java) +
            observer.queryForList(
                "SELECT (to_jsonb(c) - ARRAY['publication_epoch','scan_requested','updated_at','lease_owner','lease_token','lease_expires_at'," +
                    "'rotation_sequence','rotation_id','rotation_state','rotation_epoch_before','rotation_implementation_schema'," +
                    "'rotation_desired_generation','rotation_desired_configuration_hash','rotation_database_identity','rotation_restore_identity'," +
                    "'rotation_event_writer_generation','rotation_accepted_catalog_generation','rotation_accepted_catalog_hash','rotation_trust_bundle_hash'," +
                    "'rotation_catalog_writer_generation','rotation_request_owner','rotation_request_token','rotation_requested_at'," +
                    "'rotation_capture_owner','rotation_capture_token','rotation_captured_at','rotation_epoch_after'])::text " +
                    "FROM complaint_journal_control c ORDER BY data_scope_id",
                String::class.java,
            ) + observer.queryForList("SELECT to_jsonb(c)::text FROM complaint_capacity_counters c ORDER BY name", String::class.java)

    override fun close() {
        campaigns.forEach(CatalogCoordinatorLeaseCampaignV1::close)
        awaitLifecycleFact { entries().isEmpty() }
        released()
        assertFalse(tls.owner.snapshot().weakEvidenceUsed)
        // The outer exact-row fixture, original pools/root and TLS session assertions remain the cleanup owners.
    }

    private fun <T> independentTransaction(action: (Connection, JdbcTemplate) -> T): T = checkNotNull(observer.dataSource).connection.use { connection ->
        connection.autoCommit = false
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true)).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
        try {
            action(connection, jdbc)
        } finally {
            connection.rollback()
        }
    }

    private fun provenance(row: ResultSet, owner: String, time: String): EpochRotationObservedProvenance = EpochRotationObservedProvenance(
        checkNotNull(row.getObject("rotation_${owner}_owner", UUID::class.java)),
        row.getLong("rotation_${owner}_token"),
        checkNotNull(row.getTimestamp("rotation_${time}_at")).toInstant(),
    )
}

internal data class EpochRotationObservedRow(val epoch: Long, val scanRequested: Boolean, val sequence: Long, val slot: EpochRotationObservedSlot?)

internal data class EpochRotationObservedSlot(
    val id: UUID,
    val state: String,
    val epochBefore: Long,
    val writer: UUID,
    val request: EpochRotationObservedProvenance,
    val capture: EpochRotationObservedProvenance?,
    val epochAfter: Long?,
)

internal data class EpochRotationObservedProvenance(val owner: UUID, val token: Long, val at: Instant)
