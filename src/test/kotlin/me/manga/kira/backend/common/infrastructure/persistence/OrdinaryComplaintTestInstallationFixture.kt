package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentCandidate
import me.manga.kira.backend.complaint.domain.InstallationSessionCandidate
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintInstallationSessionStore
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintInstallationEnrollmentStore
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import java.sql.Connection
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.locks.LockSupport

/** Thin scoped data on the existing owned installation fixture, not a new pool/harness or an activation/sizing producer. */
internal class OrdinaryComplaintTestInstallationFixture(val base: OrdinaryComplaintInstallationEnrollmentFixture, val limit: Long = 2) : AutoCloseable {
    val scope = ComplaintDataScope.of(UUID.randomUUID())
    val desired = desired(scope)
    val share = ComplaintCapacityCharges.INSTALLATION_ID + ComplaintCapacityCharges.INSTALLATION_CREDENTIAL
    val terminal = ComplaintCapacityVector.ZERO.with(ComplaintCapacityCounter.JOURNAL_PUBLICATIONS, 2)
        .with(ComplaintCapacityCounter.RECOVERY_RESERVATIONS, 2).with(ComplaintCapacityCounter.STORAGE_BYTES, 32_768)
    val original = share.scaled(limit) + terminal
    val store = JdbcComplaintInstallationEnrollmentStore(base.jdbc, base.capacity, base.audit, desired)
    val sessions = JdbcComplaintInstallationSessionStore(base.jdbc, desired)

    init {
        fixtureTransaction { jdbc ->
            assertEquals(
                1,
                jdbc.update(
                    "INSERT INTO complaint_test_runs (data_scope_id, test_only, state, configuration_hash, accounting_version, " +
                        "installation_limit, enrolled_count, original_reserve, unused_reserve, " +
                        "activation_catalog_generation, activation_catalog_hash, created_at) " +
                        "VALUES (?, true, 'ACTIVE', ?, 1, ?, 0, ?::bigint[], ?::bigint[], 1, ?, ?)",
                    scope.id,
                    desired.configurationHashBytes(),
                    limit,
                    original.sqlArray(),
                    original.sqlArray(),
                    ByteArray(32) { 47 },
                    Timestamp.from(base.ordinary.cutoff),
                ),
            )
            val bookkeeping = ComplaintCapacityVector.ZERO.with(ComplaintCapacityCounter.TEST_RUNS, 1)
                .with(ComplaintCapacityCounter.STORAGE_BYTES, 2_048)
            for (counter in ComplaintCapacityEncoding.lockOrder()) {
                assertEquals(
                    1,
                    jdbc.update(
                        "UPDATE complaint_capacity_counters SET actual_units = actual_units + ?, " +
                            "test_reserved_units = test_reserved_units + ?, free_units = free_units - ? WHERE name = ?",
                        bookkeeping[counter],
                        original[counter],
                        bookkeeping[counter] + original[counter],
                        counter.storedName,
                    ),
                )
            }
        }
    }

    fun desired(selected: ComplaintDataScope, hash: ByteArray = ByteArray(32) { (it + 80).toByte() }) = ComplaintInstallationDesiredSettings.Configured(
        ComplaintInstallationMode.PRE_CUTOVER_TEST,
        1,
        1,
        selected,
        UUID.randomUUID(),
        UUID.randomUUID(),
        hash,
    )

    fun candidate(selected: ComplaintDataScope = scope): InstallationEnrollmentCandidate = base.candidate(scope = selected)

    fun session(candidate: InstallationEnrollmentCandidate): InstallationSessionCandidate = InstallationEnrollmentCredentials.prepareSession(
        ScopedInstallationId(candidate.installation.id, candidate.installation.scope),
        ByteArray(32) { it.toByte() },
    )

    fun state(): TestInstallationSnapshot = TestInstallationSnapshot(
        base.state(),
        base.observer.queryForList("SELECT to_jsonb(r)::text FROM complaint_test_runs r WHERE data_scope_id = ?", String::class.java, scope.id),
        base.observer.query(
            "SELECT name, free_units, actual_units, recovery_reserved_units, test_reserved_units FROM complaint_capacity_counters ORDER BY name",
            { row, _ ->
                row.getString(1) to TestInstallationBalance(row.getLong(2), row.getLong(3), row.getLong(4), row.getLong(5))
            },
        ).toMap(),
    )

    fun assertChargedOnce(before: TestInstallationSnapshot) {
        val after = state()
        for (counter in ComplaintCapacityEncoding.lockOrder()) {
            val old = before.balances.getValue(counter.storedName)
            val current = after.balances.getValue(counter.storedName)
            assertEquals(old.free - ComplaintCapacityCharges.AUDIT[counter], current.free)
            assertEquals(old.actual + share[counter] + ComplaintCapacityCharges.AUDIT[counter], current.actual)
            assertEquals(old.recovery, current.recovery)
            assertEquals(old.test - share[counter], current.test)
            assertEquals(old.free + old.actual + old.recovery + old.test, current.free + current.actual + current.recovery + current.test)
        }
        assertEquals(1L, base.observer.queryForObject("SELECT enrolled_count FROM complaint_test_runs WHERE data_scope_id = ?", Long::class.java, scope.id))
        assertEquals(
            true,
            base.observer.queryForObject(
                "SELECT original_reserve = ?::bigint[] AND unused_reserve = ?::bigint[] AND installation_limit = ? " +
                    "AND configuration_hash = ? FROM complaint_test_runs WHERE data_scope_id = ?",
                Boolean::class.java,
                original.sqlArray(),
                (original - share).sqlArray(),
                limit,
                desired.configurationHashBytes(),
                scope.id,
            ),
        )
        assertEquals(
            before.installations.counters.getValue("installation_ids").dailyCount!! + 1,
            after.installations.counters.getValue("installation_ids").dailyCount,
        )
    }

    /** Legal row shapes only. This fixture does not execute or authenticate the terminal protocol. */
    fun terminalState(state: String, jdbc: JdbcTemplate = base.observer) {
        require(state in setOf("SEALED", "PURGING", "PURGED"))
        if (state == "SEALED") {
            assertEquals(1, jdbc.update("UPDATE complaint_test_runs SET state = 'SEALED', sealed_at = now() WHERE data_scope_id = ?", scope.id))
            return
        }
        val bytes = "synthetic-test-installation-terminal".toByteArray(Charsets.UTF_8)
        assertEquals(
            1,
            jdbc.update(
                "UPDATE complaint_test_runs SET state = ?, sealed_at = now(), purging_at = now(), " +
                    "purged_at = CASE WHEN ? = 'PURGED' THEN now() ELSE NULL END, " +
                    "unused_reserve = CASE WHEN ? = 'PURGED' THEN array_fill(0::bigint, ARRAY[22]) ELSE unused_reserve END, " +
                    "final_ordinary_epoch = 1, terminal_seal_epoch = 2, generation_seal_count = 1, generation_seal_root = configuration_hash, " +
                    "seal_set_bytes = ?, seal_set_hash = sha256(?), event_manifest_count = 0, event_manifest_root = configuration_hash, " +
                    "installation_manifest_count = enrolled_count, installation_manifest_root = configuration_hash, " +
                    "installation_chunk_count = CASE WHEN enrolled_count = 0 THEN 0 ELSE 1 END, retired_count = enrolled_count, deleted_count = 0, " +
                    "permanent_denial_bytes = ?, permanent_denial_hash = sha256(?), terminal_event_id = ?, " +
                    "terminal_object_key = 'synthetic/terminal', terminal_object_version = 'synthetic-v1', " +
                    "terminal_ciphertext_hash = configuration_hash, terminal_catalog_generation = 2, " +
                    "terminal_catalog_hash = configuration_hash WHERE data_scope_id = ?",
                state, state, state, bytes, bytes, bytes, bytes, "A".repeat(43), scope.id,
            ),
        )
    }

    fun lockedRun(): Connection = checkNotNull(base.observer.dataSource).connection.also { connection ->
        try {
            connection.autoCommit = false
            connection.prepareStatement("SELECT data_scope_id FROM complaint_test_runs WHERE data_scope_id = ? FOR UPDATE NOWAIT").use { statement ->
                statement.queryTimeout = 2
                statement.setObject(1, scope.id)
                statement.executeQuery().use { row -> assertTrue(row.next() && !row.next()) }
            }
        } catch (problem: Throwable) {
            connection.close()
            throw problem
        }
    }

    fun awaitBlocked(pid: Int): Boolean {
        val deadline = System.nanoTime() + 800_000_000L
        do {
            if (base.observer.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = ? AND datname = current_database() " +
                        "AND wait_event_type = 'Lock' AND cardinality(pg_blocking_pids(pid)) > 0)",
                    Boolean::class.java,
                    pid,
                ) == true
            ) {
                return true
            }
            LockSupport.parkNanos(1_000_000L)
        } while (System.nanoTime() < deadline)
        return false
    }

    fun resetTrace() {
        base.afterStep = {}
        base.jdbc.beforeQuery = {}
        base.jdbc.beforeUpdate = {}
        base.jdbc.queries.clear()
        base.jdbc.updates.clear()
        base.observations.clear()
    }

    private fun fixtureTransaction(work: (JdbcTemplate) -> Unit) {
        requireConnectionFree()
        checkNotNull(base.observer.dataSource).connection.use { connection ->
            connection.autoCommit = false
            try {
                work(JdbcTemplate(SingleConnectionDataSource(connection, true)).apply { exceptionTranslator = SQLExceptionSubclassTranslator() })
                connection.commit()
            } catch (problem: Throwable) {
                connection.rollback()
                throw problem
            }
        }
    }

    override fun close() {
        base.assertReleased()
        base.observer.update("DELETE FROM complaint_test_runs WHERE data_scope_id = ?", scope.id)
    }

    private fun ComplaintCapacityVector.sqlArray(): String = toLongArray().joinToString(separator = ",", prefix = "{", postfix = "}")
}

internal data class TestInstallationBalance(val free: Long, val actual: Long, val recovery: Long, val test: Long)

internal data class TestInstallationSnapshot(
    val installations: EnrollmentFixtureState,
    val run: List<String>,
    val balances: Map<String, TestInstallationBalance>,
)
