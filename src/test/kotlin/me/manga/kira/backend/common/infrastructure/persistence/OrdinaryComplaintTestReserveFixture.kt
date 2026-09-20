package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.domain.ComplaintTestReserveSpend
import me.manga.kira.backend.complaint.domain.ComplaintTestReserveSpendExpectation
import me.manga.kira.backend.complaint.domain.ComplaintTestReserveSpendResult
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationTestBinding
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintTestReserveStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintTestReserveSpendPhaseExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Existing owned PG/pool, synthetic counter restoration and retained recovery rows; no new database owner/harness. */
internal fun withOrdinaryComplaintTestReserve(
    database: PgLifecycleDatabaseFixture,
    maximumPoolSize: Int = 2,
    test: (OrdinaryComplaintTestReserveFixture) -> Unit,
) {
    withOrdinaryComplaintRecovery(database, maximumPoolSize) { base ->
        OrdinaryComplaintTestReserveFixture(base).use { fixture ->
            fixture.seed()
            test(fixture)
        }
    }
}

/** All vectors, bookkeeping and catalog shapes are explicitly synthetic; no production sizing or authority claim. */
internal class OrdinaryComplaintTestReserveFixture(val base: OrdinaryComplaintRecoveryFixture) : AutoCloseable {
    val ordinary = base.ordinary
    val observer = base.observer
    val counters = base.counters
    val scope = base.scope
    val original = ComplaintCapacityVector.of(LongArray(22) { it + 100L })
    val unused = ComplaintCapacityVector.of(LongArray(22) { it + 60L })
    val actual = ComplaintCapacityVector.of(LongArray(22) { it + 3L })
    val recovery = ComplaintCapacityVector.of(LongArray(22) { it + 5L })
    val otherTestReserve = ComplaintCapacityVector.of(LongArray(22) { 11L })
    val bookkeeping = base.bookkeeping + ComplaintCapacityVector.ZERO.with(ComplaintCapacityCounter.TEST_RUNS, 1)
        .with(ComplaintCapacityCounter.STORAGE_BYTES, 2_048)
    val jdbc = ComplaintTestReserveFixtureJdbc(this)
    val capacity = JdbcComplaintCapacityStore(jdbc, counters.syntheticPolicyDigest())
    val desired = ComplaintInstallationDesiredSettings.Configured(
        ComplaintInstallationMode.PRE_CUTOVER_TEST,
        1,
        1,
        scope,
        UUID.randomUUID(),
        UUID.randomUUID(),
        ByteArray(32) { (it + 80).toByte() },
    )
    val binding = ComplaintInstallationTestBinding(desired)
    val store = JdbcComplaintTestReserveStore(jdbc, capacity, desired)
    val observations = CopyOnWriteArrayList<Pair<ComplaintTestReserveFixtureStep, StepUpPhaseObservation>>()
    var afterStep: (ComplaintTestReserveFixtureStep) -> Unit = {}

    fun expectation(
        selectedScope: ComplaintDataScope = scope,
        promised: ComplaintCapacityVector = original,
        remaining: ComplaintCapacityVector = unused,
        toActual: ComplaintCapacityVector = actual,
        toRecovery: ComplaintCapacityVector = recovery,
    ): ComplaintTestReserveSpendExpectation = ComplaintTestReserveSpendExpectation.of(selectedScope, promised, remaining, toActual, toRecovery)

    fun executor(port: ComplaintTestReserveSpend = store): ComplaintTestReserveSpendPhaseExecutor =
        ComplaintTestReserveSpendPhaseExecutor(ordinary.ownership, port)

    fun execute(expected: ComplaintTestReserveSpendExpectation = expectation(), port: ComplaintTestReserveSpend = store): ComplaintTestReserveSpendResult {
        jdbc.resetCounts()
        val outcome = runCatching { executor(port).spend(expected) }
        assertReleased() // Retained fixture assertions must escape product exception sanitization, even on expected refusal.
        return outcome.getOrThrow()
    }

    fun seed() {
        requireConnectionFree()
        base.seed()
        observations.clear()
        fixtureTransaction { selected ->
            selected.update("DELETE FROM complaint_test_runs WHERE data_scope_id = ?", scope.id)
            selected.update(
                "INSERT INTO complaint_test_runs (data_scope_id, test_only, state, configuration_hash, accounting_version, " +
                    "installation_limit, enrolled_count, original_reserve, unused_reserve, " +
                    "activation_catalog_generation, activation_catalog_hash, created_at) " +
                    "VALUES (?, true, 'ACTIVE', ?, 1, 25, 2, ?::bigint[], ?::bigint[], 1, ?, ?)",
                scope.id,
                desired.configurationHashBytes(),
                original.sqlArray(),
                unused.sqlArray(),
                counters.syntheticPolicyDigest(),
                Timestamp.from(ordinary.cutoff),
            )
            // Preexisting terminal obligations must remain RESERVED and byte-identical, not be spent/released by K05.
            selected.update("UPDATE complaint_journal_publications SET event_kind = 'INSTALLATION_MANIFEST' WHERE event_id = ?", base.eventId)
            selected.update(
                "UPDATE complaint_journal_publications SET event_kind = 'TEST_RUN_PURGE', target_count = 0 WHERE event_id = ?",
                base.otherEventId,
            )
            for (counter in ComplaintCapacityEncoding.lockOrder()) {
                val retained = base.promise[counter] + base.otherPromise[counter]
                val test = unused[counter] + otherTestReserve[counter]
                assertEquals(
                    1,
                    selected.update(
                        "UPDATE complaint_capacity_counters SET actual_units = ?, test_reserved_units = ?, free_units = ? WHERE name = ?",
                        bookkeeping[counter],
                        test,
                        100_000L - bookkeeping[counter] - retained - test,
                        counter.storedName,
                    ),
                )
            }
        }
    }

    fun state(): ComplaintTestReserveFixtureState {
        val retained = base.state()
        return ComplaintTestReserveFixtureState(
            retained.publications,
            retained.reservations,
            observer.queryForList("SELECT to_jsonb(r)::text FROM complaint_test_runs r WHERE data_scope_id = ?", String::class.java, scope.id),
            observer.queryForList(
                "SELECT (to_jsonb(r) - 'unused_reserve')::text FROM complaint_test_runs r WHERE data_scope_id = ?",
                String::class.java,
                scope.id,
            ),
            observer.query(
                "SELECT name, to_jsonb(c)::text, " +
                    "(to_jsonb(c) - ARRAY['actual_units','recovery_reserved_units','test_reserved_units','updated_at'])::text, " +
                    "free_units, actual_units, recovery_reserved_units, test_reserved_units FROM complaint_capacity_counters c ORDER BY name",
                { row, _ ->
                    row.getString(1) to ComplaintTestReserveCounter(
                        row.getString(2),
                        row.getString(3),
                        row.getLong(4),
                        row.getLong(5),
                        row.getLong(6),
                        row.getLong(7),
                    )
                },
            ).toMap(),
        )
    }

    fun assertSpent(before: ComplaintTestReserveFixtureState, toActual: ComplaintCapacityVector = actual, toRecovery: ComplaintCapacityVector = recovery) {
        val after = state()
        assertEquals(before.publications, after.publications)
        assertEquals(before.reservations, after.reservations)
        assertEquals(before.preservedRun, after.preservedRun)
        assertEquals(1, after.run.size)
        assertEquals(22, after.counters.size)
        for (counter in ComplaintCapacityEncoding.lockOrder()) {
            val old = before.counters.getValue(counter.storedName)
            val current = after.counters.getValue(counter.storedName)
            assertEquals(old.preserved, current.preserved)
            assertEquals(old.free, current.free)
            assertEquals(bookkeeping[counter] + toActual[counter], current.actual)
            assertEquals(old.actual + toActual[counter], current.actual)
            assertEquals(old.recovery + toRecovery[counter], current.recovery)
            assertEquals(otherTestReserve[counter] + unused[counter] - toActual[counter] - toRecovery[counter], current.test)
        }
        assertEquals(
            listOf(true),
            observer.query(
                "SELECT unused_reserve = ?::bigint[] AND state = 'ACTIVE' FROM complaint_test_runs WHERE data_scope_id = ?",
                { row, _ -> row.getBoolean(1) && !row.wasNull() },
                (unused - (toActual + toRecovery)).sqlArray(),
                scope.id,
            ),
        )
    }

    fun checkpoint(step: ComplaintTestReserveFixtureStep) = preserveAssertions {
        observations.add(step to observeStepUpPhase(ordinary))
        afterStep(step)
    }

    fun <T> preserveAssertions(work: () -> T): T = base.preserveAssertions(work)

    fun assertReleased() {
        base.assertReleased()
        assertTrue(observations.all { it.second.lease.completion.quiescent() })
        assertEquals(0, ordinary.admission.activeOwners())
        requireConnectionFree()
    }

    /** Legal stored shapes only; synthetic bytes do NOT establish authenticated catalog/terminal transition authority. */
    fun setInactiveState(state: String) {
        require(state in setOf("SEALED", "PURGING", "PURGED"))
        if (state == "SEALED") {
            observer.update("UPDATE complaint_test_runs SET state = 'SEALED', sealed_at = now() WHERE data_scope_id = ?", scope.id)
            return
        }
        val bytes = "synthetic-terminal-metadata".toByteArray(Charsets.UTF_8)
        observer.update(
            "UPDATE complaint_test_runs SET state = ?, sealed_at = now(), purging_at = now(), " +
                "purged_at = CASE WHEN ? = 'PURGED' THEN now() ELSE NULL END, " +
                "unused_reserve = CASE WHEN ? = 'PURGED' THEN array_fill(0::bigint, ARRAY[22]) ELSE unused_reserve END, " +
                "final_ordinary_epoch = 1, terminal_seal_epoch = 2, generation_seal_count = 1, generation_seal_root = configuration_hash, " +
                "seal_set_bytes = ?, seal_set_hash = sha256(?), event_manifest_count = 0, event_manifest_root = configuration_hash, " +
                "installation_manifest_count = enrolled_count, installation_manifest_root = configuration_hash, installation_chunk_count = 1, " +
                "retired_count = enrolled_count, deleted_count = 0, permanent_denial_bytes = ?, permanent_denial_hash = sha256(?), " +
                "terminal_event_id = ?, terminal_object_key = 'synthetic/terminal', terminal_object_version = 'synthetic-v1', " +
                "terminal_ciphertext_hash = configuration_hash, terminal_catalog_generation = 2, terminal_catalog_hash = configuration_hash " +
                "WHERE data_scope_id = ?",
            state, state, state, bytes, bytes, bytes, bytes, base.otherEventId, scope.id,
        )
    }

    /** Corruption is isolated fixture setup; exact row/CHECK restoration retains any original assertion as the primary failure. */
    fun withRelaxedRunChecks(work: () -> Unit) {
        val rows = observer.queryForObject(
            "SELECT jsonb_agg(to_jsonb(r))::text FROM complaint_test_runs r WHERE data_scope_id = ?",
            String::class.java,
            scope.id,
        )!!
        val checks = observer.query(
            "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'complaint_test_runs'::regclass AND contype = 'c' ORDER BY conname",
            { row, _ -> row.getString(1) to row.getString(2) },
        )
        val dropped = mutableListOf<Pair<String, String>>()
        val outcome = runCatching {
            checks.forEach { check ->
                observer.execute("ALTER TABLE complaint_test_runs DROP CONSTRAINT ${check.first}")
                dropped.add(check)
            }
            work()
        }
        val restoration = runCatching {
            fixtureTransaction { selected ->
                selected.update("DELETE FROM complaint_test_runs WHERE data_scope_id = ?", scope.id)
                selected.update("INSERT INTO complaint_test_runs SELECT * FROM jsonb_populate_recordset(NULL::complaint_test_runs, ?::jsonb)", rows)
                selected.execute("SET CONSTRAINTS ALL IMMEDIATE")
                dropped.forEach { (name, definition) -> selected.execute("ALTER TABLE complaint_test_runs ADD CONSTRAINT $name $definition") }
            }
        }
        outcome.exceptionOrNull()?.let { original ->
            restoration.exceptionOrNull()?.let { secondary ->
                if (secondary !== original) original.addSuppressed(secondary)
            }
        }
        outcome.getOrThrow()
        restoration.getOrThrow()
    }

    fun lockRun(): AutoCloseable {
        val connection = checkNotNull(observer.dataSource).connection
        try {
            connection.autoCommit = false
            connection.prepareStatement("SELECT data_scope_id FROM complaint_test_runs WHERE data_scope_id = ? FOR UPDATE NOWAIT").use { statement ->
                statement.queryTimeout = 2
                statement.setObject(1, scope.id)
                statement.executeQuery().use { row -> check(row.next() && !row.next()) }
            }
        } catch (problem: Throwable) {
            connection.close()
            throw problem
        }
        return AutoCloseable {
            try {
                connection.rollback()
            } finally {
                connection.close()
            }
        }
    }

    private fun fixtureTransaction(work: (JdbcTemplate) -> Unit) {
        requireConnectionFree()
        checkNotNull(observer.dataSource).connection.use { connection ->
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
        assertReleased()
        fixtureTransaction { it.update("DELETE FROM complaint_test_runs WHERE data_scope_id = ?", scope.id) }
    }
}

internal data class ComplaintTestReserveCounter(val full: String, val preserved: String, val free: Long, val actual: Long, val recovery: Long, val test: Long)

internal data class ComplaintTestReserveFixtureState(
    val publications: List<String>,
    val reservations: List<String>,
    val run: List<String>,
    val preservedRun: List<String>,
    val counters: Map<String, ComplaintTestReserveCounter>,
)

internal enum class ComplaintTestReserveFixtureStep { COUNTERS, RUN, FIRST_COUNTER, RUN_UPDATED }

/** Observe/fault actual SQL calls and returned counts; never replace rows, counts or owned completion with a fake witness. */
internal class ComplaintTestReserveFixtureJdbc(private val fixture: OrdinaryComplaintTestReserveFixture) : JdbcTemplate(fixture.ordinary.pool) {
    val counterResults = mutableListOf<Int>()
    val runResults = mutableListOf<Int>()
    val queries = CopyOnWriteArrayList<ComplaintTestReserveFixtureStep>()
    var beforeQuery: (ComplaintTestReserveFixtureStep) -> Unit = {}
    var beforeCounterUpdate: (Int) -> Unit = {}
    var beforeRunUpdate: () -> Unit = {}

    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    fun resetCounts() {
        counterResults.clear()
        runResults.clear()
        queries.clear()
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>): List<T> {
        val step = queryStep(sql)
        step?.let {
            fixture.preserveAssertions {
                queries.add(it)
                beforeQuery(it)
            }
        }
        return super.query(sql, rowMapper).also { step?.let(fixture::checkpoint) }
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> {
        val step = queryStep(sql)
        step?.let {
            fixture.preserveAssertions {
                queries.add(it)
                beforeQuery(it)
            }
        }
        return super.query(sql, rowMapper, *args).also { step?.let(fixture::checkpoint) }
    }

    override fun update(sql: String, vararg args: Any?): Int {
        val counter = sql.startsWith("UPDATE complaint_capacity_counters")
        val run = sql.startsWith("UPDATE complaint_test_runs")
        if (counter) fixture.preserveAssertions { beforeCounterUpdate(counterResults.size + 1) }
        if (run) fixture.preserveAssertions(beforeRunUpdate)
        return super.update(sql, *args).also { updated ->
            if (counter) {
                counterResults.add(updated)
                if (counterResults.size == 1 && updated == 1) fixture.checkpoint(ComplaintTestReserveFixtureStep.FIRST_COUNTER)
            }
            if (run) {
                runResults.add(updated)
                if (updated == 1) fixture.checkpoint(ComplaintTestReserveFixtureStep.RUN_UPDATED)
            }
        }
    }

    private fun queryStep(sql: String): ComplaintTestReserveFixtureStep? = when {
        sql.startsWith("SELECT name, ordinal, accounting_version") -> ComplaintTestReserveFixtureStep.COUNTERS
        sql.startsWith("SELECT data_scope_id") && sql.contains("FROM complaint_test_runs") -> ComplaintTestReserveFixtureStep.RUN
        else -> null
    }
}

internal class SyntheticComplaintTestReserveFailure : Exception("Synthetic test reserve spending failure.")
