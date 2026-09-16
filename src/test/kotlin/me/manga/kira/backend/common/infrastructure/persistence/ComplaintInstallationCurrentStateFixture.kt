package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationCurrentStateAssessment
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunState
import me.manga.kira.backend.complaint.infrastructure.admission.JdbcInstallationCurrentStateReader
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationCurrentStatePhaseExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.util.UUID

/** Existing PG/ordinary owner only. Every stored binding, catalog field and run transition here is synthetic, not provenance. */
internal class ComplaintInstallationCurrentStateFixture(val ordinary: OrdinarySourceGrantCleanupFixture) : AutoCloseable {
    val observer = ordinary.foreignTemplate()
    val reader = JdbcInstallationCurrentStateReader(ordinary.jdbc)
    val executor = ComplaintInstallationCurrentStatePhaseExecutor(ordinary.ownership, reader)
    val testScope = ComplaintDataScope.of(UUID.randomUUID())
    val otherScope = ComplaintDataScope.of(UUID.randomUUID())
    private val originalLive = rows("complaint_journal_control", ComplaintDataScope.LIVE).single()
    private val databaseIdentity = UUID.randomUUID()
    private val restoreIdentity = UUID.randomUUID()
    private val writerGeneration = UUID.randomUUID()
    private val catalogWriterGeneration = UUID.randomUUID()
    private val leases = mutableListOf<PersistenceJdbcLease>()

    init {
        for (scope in listOf(testScope, otherScope)) {
            assertTrue(rows("complaint_journal_control", scope).isEmpty())
            assertTrue(rows("complaint_test_runs", scope).isEmpty())
        }
    }

    fun desired(
        scope: ComplaintDataScope = ComplaintDataScope.LIVE,
        generation: Long = 7,
        hash: ByteArray = ByteArray(32) { 17 },
        database: UUID = databaseIdentity,
        restore: UUID = restoreIdentity,
    ): ComplaintInstallationDesiredSettings.Configured = ComplaintInstallationDesiredSettings.Configured(
        if (scope.testOnly) ComplaintInstallationMode.PRE_CUTOVER_TEST else ComplaintInstallationMode.LIVE,
        1,
        generation,
        scope,
        database,
        restore,
        hash,
    )

    fun seedClosedControl(scope: ComplaintDataScope = ComplaintDataScope.LIVE) {
        deleteControl(scope)
        assertEquals(
            1,
            observer.update(
                "INSERT INTO complaint_journal_control (data_scope_id, test_only, publication_epoch, desired_generation, implementation_schema, " +
                    "maintenance_closed, creation_closed, scan_requested, lease_token, retention_lease_token, updated_at) " +
                    "VALUES (?, ?, 1, 1, 1, true, true, true, 0, 0, ?)",
                scope.id,
                scope.testOnly,
                Timestamp.from(ordinary.cutoff),
            ),
        )
    }

    fun seedControl(desired: ComplaintInstallationDesiredSettings.Configured) {
        seedClosedControl(desired.scope)
        assertEquals(
            1,
            observer.update(
                "UPDATE complaint_journal_control SET desired_generation = ?, desired_configuration_hash = ?, database_identity = ?, " +
                    "restore_identity = ?, event_writer_generation = ?, accepted_catalog_generation = 1, accepted_catalog_hash = ?, " +
                    "trust_bundle_hash = ?, catalog_writer_generation = ? WHERE data_scope_id = ?",
                desired.desiredGeneration, desired.configurationHashBytes(), desired.databaseIdentity, desired.restoreIdentity,
                writerGeneration, ByteArray(32) { 23 }, ByteArray(32) { 29 }, catalogWriterGeneration, desired.scope.id,
            ),
        )
    }

    fun deleteControl(scope: ComplaintDataScope) {
        require(scope in ownedScopes())
        observer.update("DELETE FROM complaint_journal_control WHERE data_scope_id = ?", scope.id)
    }

    fun deleteRun(scope: ComplaintDataScope) {
        require(scope in listOf(testScope, otherScope))
        observer.update("DELETE FROM complaint_test_runs WHERE data_scope_id = ?", scope.id)
    }

    fun seedRun(scope: ComplaintDataScope = testScope, hash: ByteArray = desired(scope).configurationHashBytes()) {
        deleteRun(scope)
        assertEquals(
            1,
            observer.update(
                "INSERT INTO complaint_test_runs (data_scope_id, test_only, state, configuration_hash, accounting_version, installation_limit, " +
                    "enrolled_count, original_reserve, unused_reserve, activation_catalog_generation, activation_catalog_hash, created_at) " +
                    "VALUES (?, true, 'ACTIVE', ?, 1, 25, 0, array_fill(0::bigint, ARRAY[22]), array_fill(0::bigint, ARRAY[22]), 1, ?, ?)",
                scope.id,
                hash,
                ByteArray(32) { 31 },
                Timestamp.from(ordinary.cutoff),
            ),
        )
    }

    /** Legal V14 shapes, not a real activation/seal/purge producer. No genuine terminal or reserve work is claimed. */
    fun retireRun(state: ComplaintInstallationRunState) {
        require(state != ComplaintInstallationRunState.ACTIVE)
        if (state == ComplaintInstallationRunState.SEALED) {
            assertEquals(1, observer.update("UPDATE complaint_test_runs SET state = 'SEALED', sealed_at = now() WHERE data_scope_id = ?", testScope.id))
            return
        }
        val bytes = "synthetic-current-state-terminal".toByteArray(Charsets.UTF_8)
        assertEquals(
            1,
            observer.update(
                "UPDATE complaint_test_runs SET state = ?, sealed_at = now(), purging_at = now(), " +
                    "purged_at = CASE WHEN ? = 'PURGED' THEN now() ELSE NULL END, " +
                    "unused_reserve = array_fill(0::bigint, ARRAY[22]), final_ordinary_epoch = 1, terminal_seal_epoch = 2, " +
                    "generation_seal_count = 1, generation_seal_root = configuration_hash, seal_set_bytes = ?, seal_set_hash = sha256(?), " +
                    "event_manifest_count = 0, event_manifest_root = configuration_hash, installation_manifest_count = 0, " +
                    "installation_manifest_root = configuration_hash, installation_chunk_count = 0, retired_count = 0, deleted_count = 0, " +
                    "permanent_denial_bytes = ?, permanent_denial_hash = sha256(?), terminal_event_id = ?, " +
                    "terminal_object_key = 'synthetic/current-state', terminal_object_version = 'synthetic-v1', " +
                    "terminal_ciphertext_hash = configuration_hash, terminal_catalog_generation = 2, terminal_catalog_hash = configuration_hash " +
                    "WHERE data_scope_id = ?",
                state.name, state.name, bytes, bytes, bytes, bytes, "A".repeat(43), testScope.id,
            ),
        )
    }

    fun assertAssessment(
        expected: ComplaintInstallationCurrentStateAssessment,
        desired: ComplaintInstallationDesiredSettings,
        requested: ComplaintDataScope = ComplaintDataScope.LIVE,
    ) {
        val before = state()
        val outcome = runCatching { executor.assess(desired, requested) }
        assertReleased()
        assertEquals(expected, outcome.getOrThrow())
        assertEquals(before, state())
    }

    fun <T> withPhase(
        enter: () -> PersistencePhaseContext = ordinary.ownership::enterComplaintInstallationCurrentState,
        work: (PersistencePhaseContext) -> T,
    ): T {
        val phase = enter()
        try {
            phase.begin()
            val holder = TransactionSynchronizationManager.getResource(ordinary.pool) as ConnectionHolder
            leases.add(ownedPoolLease(holder.connection))
            return work(phase)
        } finally {
            phase.finish()
            assertReleased()
        }
    }

    /** Separate observer transaction: NOWAIT locks and committed updates must succeed while the diagnostic read remains open. */
    fun mutateUnlockedRows() {
        requireConnectionFree()
        checkNotNull(observer.dataSource).connection.use { connection ->
            connection.autoCommit = false
            try {
                for (table in listOf("complaint_journal_control", "complaint_test_runs")) {
                    connection.prepareStatement("SELECT data_scope_id FROM $table WHERE data_scope_id = ? FOR UPDATE NOWAIT").use { statement ->
                        statement.queryTimeout = 1
                        statement.setObject(1, testScope.id)
                        statement.executeQuery().use { row ->
                            assertTrue(row.next())
                            assertEquals(testScope.id, row.getObject(1, UUID::class.java))
                            assertFalse(row.next())
                        }
                    }
                }
                connection.prepareStatement("UPDATE complaint_journal_control SET desired_generation = desired_generation + 1 WHERE data_scope_id = ?")
                    .use { statement ->
                        statement.setObject(1, testScope.id)
                        assertEquals(1, statement.executeUpdate())
                    }
                connection.prepareStatement("UPDATE complaint_test_runs SET state = 'SEALED', sealed_at = now() WHERE data_scope_id = ?").use { statement ->
                    statement.setObject(1, testScope.id)
                    assertEquals(1, statement.executeUpdate())
                }
                connection.commit()
            } finally {
                connection.rollback()
            }
        }
        requireConnectionFree()
    }

    fun state(): List<String> = listOf("complaint_journal_control", "complaint_test_runs").flatMap { table ->
        ownedScopes().flatMap { scope -> rows(table, scope) }
    }

    fun assertReleased() {
        assertEquals(0, ordinary.admission.activeOwners())
        assertEquals(0L, ordinary.ownedPool.lifecycle.activeAcquisitions())
        assertEquals(0L, ordinary.ownedPool.lifecycle.actorSnapshot().futureLeaseEntries)
        assertTrue(leases.all { it.completion.quiescent() })
        requireConnectionFree()
    }

    private fun ownedScopes(): List<ComplaintDataScope> = listOf(ComplaintDataScope.LIVE, testScope, otherScope)

    private fun rows(table: String, scope: ComplaintDataScope): List<String> = observer.queryForList(
        "SELECT to_jsonb(r)::text FROM $table r WHERE data_scope_id = ?",
        String::class.java,
        scope.id,
    )

    override fun close() {
        assertReleased()
        for (scope in listOf(testScope, otherScope)) deleteRun(scope)
        for (scope in ownedScopes()) deleteControl(scope)
        assertEquals(
            1,
            observer.update(
                "INSERT INTO complaint_journal_control SELECT (jsonb_populate_record(NULL::complaint_journal_control, ?::jsonb)).*",
                originalLive,
            ),
        )
        assertEquals(listOf(originalLive), rows("complaint_journal_control", ComplaintDataScope.LIVE))
    }
}
