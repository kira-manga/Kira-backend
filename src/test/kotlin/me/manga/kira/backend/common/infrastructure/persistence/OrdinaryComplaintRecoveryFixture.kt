package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintRecoverySettlement
import me.manga.kira.backend.complaint.domain.ComplaintRecoverySettlementExpectation
import me.manga.kira.backend.complaint.domain.ComplaintRecoverySettlementResult
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintRecoverySettlementStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintRecoverySettlementPhaseExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import java.sql.Timestamp
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Same PG/pool/counter-restore owners. Seed vectors/bytes are synthetic, not W04 authority or a production charge policy. */
internal fun withOrdinaryComplaintRecovery(database: PgLifecycleDatabaseFixture, maximumPoolSize: Int = 2, test: (OrdinaryComplaintRecoveryFixture) -> Unit) {
    withOrdinarySourceGrantCleanup(database, maximumPoolSize = maximumPoolSize) { ordinary ->
        SyntheticComplaintCounters(ordinary.foreignTemplate(), ordinary.cutoff).use { counters ->
            OrdinaryComplaintRecoveryFixture(ordinary, counters).use { fixture ->
                fixture.seed()
                test(fixture)
            }
        }
    }
}

internal class OrdinaryComplaintRecoveryFixture(val ordinary: OrdinarySourceGrantCleanupFixture, val counters: SyntheticComplaintCounters) : AutoCloseable {
    val observer = ordinary.foreignTemplate()
    val scope = ComplaintDataScope.of(UUID.randomUUID())
    val eventId = syntheticEventId()
    val otherEventId = syntheticEventId()
    val promise = ComplaintCapacityVector.of(LongArray(22) { it + 3L })
    val otherPromise = ComplaintCapacityVector.of(LongArray(22) { 5L })
    val use = ComplaintCapacityVector.of(LongArray(22) { it + 2L })
    val bookkeeping = ComplaintCapacityVector.ZERO.with(ComplaintCapacityCounter.RECOVERY_RESERVATIONS, 2)
        .with(ComplaintCapacityCounter.JOURNAL_PUBLICATIONS, 2).with(ComplaintCapacityCounter.STORAGE_BYTES, 4_096)
    val jdbc = ComplaintRecoveryFixtureJdbc(this)
    val capacity = JdbcComplaintCapacityStore(jdbc, counters.syntheticPolicyDigest())
    val store = JdbcComplaintRecoverySettlementStore(jdbc, capacity)
    val observations = CopyOnWriteArrayList<Pair<ComplaintRecoveryFixtureStep, StepUpPhaseObservation>>()
    var afterStep: (ComplaintRecoveryFixtureStep) -> Unit = {}
    private val assertionFailure = AtomicReference<AssertionError?>()

    fun expectation(
        selectedEvent: String = eventId,
        selectedScope: ComplaintDataScope = scope,
        original: ComplaintCapacityVector = promise,
        actual: ComplaintCapacityVector = use,
    ): ComplaintRecoverySettlementExpectation = ComplaintRecoverySettlementExpectation.of(selectedEvent, selectedScope, original, actual)

    fun executor(port: ComplaintRecoverySettlement = store): ComplaintRecoverySettlementPhaseExecutor =
        ComplaintRecoverySettlementPhaseExecutor(ordinary.ownership, port)

    fun execute(
        expected: ComplaintRecoverySettlementExpectation = expectation(),
        port: ComplaintRecoverySettlement = store,
    ): ComplaintRecoverySettlementResult {
        jdbc.resetCounts()
        val outcome = runCatching { executor(port).settle(expected) }
        assertionFailure.get()?.let { throw it } // Product sanitization must not turn a failed test assertion into expected-fault evidence.
        return outcome.getOrThrow()
    }

    fun seed() {
        requireConnectionFree()
        counters.seed(0)
        fixtureTransaction { selected ->
            deleteRows(selected)
            seedEvent(selected, eventId, promise)
            seedEvent(selected, otherEventId, otherPromise)
            for (counter in ComplaintCapacityEncoding.lockOrder()) {
                val reserved = promise[counter] + otherPromise[counter]
                assertEquals(
                    1,
                    selected.update(
                        "UPDATE complaint_capacity_counters SET hard_limit = 100000, creation_limit = 0, actual_units = ?, " +
                            "recovery_reserved_units = ?, test_reserved_units = 11, free_units = ? WHERE name = ?",
                        bookkeeping[counter],
                        reserved,
                        100_000L - bookkeeping[counter] - reserved - 11,
                        counter.storedName,
                    ),
                )
            }
        }
    }

    private fun seedEvent(selected: JdbcTemplate, event: String, reserved: ComplaintCapacityVector) {
        val bytes = "{\"synthetic\":true}".toByteArray(Charsets.UTF_8)
        selected.update(
            "INSERT INTO complaint_journal_publications " +
                "(event_id, data_scope_id, test_only, writer_generation, journal_epoch, event_kind, target_count, routing_key_id, " +
                "object_key, canonicalizer, event_bytes, semantic_hash, state, created_at) " +
                "VALUES (?, ?, true, ?, 1, 'OWNER_DELETE', 1, 'synthetic-route', ?, 'kcj-1', ?, sha256(?), 'PREPARED', ?)",
            event,
            scope.id,
            UUID.randomUUID(),
            "synthetic/$event",
            bytes,
            bytes,
            Timestamp.from(ordinary.cutoff),
        )
        selected.update(
            "INSERT INTO complaint_recovery_capacity_reservations " +
                "(event_id, data_scope_id, test_only, publication_ref, state, accounting_version, reserved_amounts, created_at) " +
                "VALUES (?, ?, true, ?, 'RESERVED', 1, ?::bigint[], ?)",
            event,
            scope.id,
            event,
            reserved.sqlArray(),
            Timestamp.from(ordinary.cutoff),
        )
    }

    fun state(): ComplaintRecoveryFixtureState = ComplaintRecoveryFixtureState(
        rows("complaint_journal_publications"),
        rows("complaint_recovery_capacity_reservations"),
        observer.query(
            "SELECT name, to_jsonb(c)::text, (to_jsonb(c) - ARRAY['free_units','actual_units','recovery_reserved_units','updated_at'])::text, " +
                "free_units, actual_units, recovery_reserved_units FROM complaint_capacity_counters c ORDER BY name",
            { row, _ -> row.getString(1) to ComplaintRecoveryCounter(row.getString(2), row.getString(3), row.getLong(4), row.getLong(5), row.getLong(6)) },
        ).toMap(),
    )

    fun assertSettlement(before: ComplaintRecoveryFixtureState, actual: ComplaintCapacityVector = use) {
        val after = state()
        assertEquals(before.publications, after.publications)
        assertEquals(before.reservations.size, after.reservations.size)
        assertEquals(22, after.counters.size)
        for (counter in ComplaintCapacityEncoding.lockOrder()) {
            val old = before.counters.getValue(counter.storedName)
            val current = after.counters.getValue(counter.storedName)
            assertEquals(old.preserved, current.preserved)
            assertEquals(old.free + promise[counter] - actual[counter], current.free)
            assertEquals(old.actual + actual[counter], current.actual)
            assertEquals(otherPromise[counter], current.reserved)
            assertEquals(bookkeeping[counter] + actual[counter], current.actual)
        }
        assertEquals(
            listOf(true),
            observer.query(
                "SELECT state = 'CONVERTED' AND reserved_amounts = ?::bigint[] AND converted_amounts = ?::bigint[] " +
                    "AND isfinite(converted_at) AND converted_at >= created_at FROM complaint_recovery_capacity_reservations WHERE event_id = ?",
                { row, _ -> row.getBoolean(1) && !row.wasNull() },
                promise.sqlArray(),
                actual.sqlArray(),
                eventId,
            ),
        )
        assertEquals(
            "RESERVED",
            observer.queryForObject("SELECT state FROM complaint_recovery_capacity_reservations WHERE event_id = ?", String::class.java, otherEventId),
        )
    }

    fun checkpoint(step: ComplaintRecoveryFixtureStep) = preserveAssertions {
        observations.add(step to observeStepUpPhase(ordinary))
        afterStep(step)
    }

    fun <T> preserveAssertions(work: () -> T): T {
        try {
            return work()
        } catch (problem: AssertionError) {
            assertionFailure.compareAndSet(null, problem)
            throw problem
        }
    }

    fun assertReleased() {
        assertionFailure.get()?.let { throw it }
        assertTrue(observations.all { it.second.lease.completion.quiescent() })
        assertEquals(0, ordinary.admission.activeOwners())
        requireConnectionFree()
    }

    /** Deliberately corrupt only this isolated fixture, restoring exact rows and original CHECK definitions. No migration change. */
    fun withRelaxedChecks(table: String, work: () -> Unit) {
        require(table in setOf("complaint_capacity_counters", "complaint_recovery_capacity_reservations"))
        val rows = observer.queryForObject("SELECT jsonb_agg(to_jsonb(r))::text FROM $table r", String::class.java)!!
        val checks = observer.query(
            "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = ?::regclass AND contype = 'c' ORDER BY conname",
            { row, _ -> row.getString(1) to row.getString(2) },
            table,
        )
        val dropped = mutableListOf<Pair<String, String>>()
        val outcome = runCatching {
            checks.forEach { check ->
                observer.execute("ALTER TABLE $table DROP CONSTRAINT ${check.first}")
                dropped.add(check)
            }
            work()
        }
        val restoration = runCatching {
            fixtureTransaction { selected ->
                selected.update("DELETE FROM $table")
                selected.update("INSERT INTO $table SELECT * FROM jsonb_populate_recordset(NULL::$table, ?::jsonb)", rows)
                // Drain deferred restoration triggers before CHECK DDL in this same transaction.
                selected.execute("SET CONSTRAINTS ALL IMMEDIATE")
                dropped.forEach { (name, definition) -> selected.execute("ALTER TABLE $table ADD CONSTRAINT $name $definition") }
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

    fun awaitBlocked(pid: Int, blocker: Int? = null): Boolean {
        val deadline = System.nanoTime() + 800_000_000L
        do {
            val blocked = observer.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = ? AND datname = current_database() " +
                    "AND wait_event_type = 'Lock' AND cardinality(pg_blocking_pids(pid)) > 0 " +
                    "AND (?::integer IS NULL OR ?::integer = ANY(pg_blocking_pids(pid))))",
                Boolean::class.java,
                pid,
                blocker,
                blocker,
            ) == true
            if (blocked) return true
            LockSupport.parkNanos(1_000_000L)
        } while (System.nanoTime() < deadline)
        return false
    }

    private fun rows(table: String): List<String> = observer.queryForList(
        "SELECT to_jsonb(r)::text FROM $table r WHERE event_id IN (?, ?) ORDER BY event_id",
        String::class.java,
        eventId,
        otherEventId,
    )

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

    private fun deleteRows(selected: JdbcTemplate) {
        selected.update("DELETE FROM complaint_recovery_capacity_reservations WHERE event_id IN (?, ?)", eventId, otherEventId)
        selected.update("DELETE FROM complaint_journal_publications WHERE event_id IN (?, ?)", eventId, otherEventId)
    }

    override fun close() {
        assertReleased()
        fixtureTransaction(::deleteRows)
    }

    private fun syntheticEventId(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(UUID.randomUUID().toString().replace("-", "").toByteArray(Charsets.US_ASCII))
}

internal data class ComplaintRecoveryCounter(val full: String, val preserved: String, val free: Long, val actual: Long, val reserved: Long)

internal data class ComplaintRecoveryFixtureState(
    val publications: List<String>,
    val reservations: List<String>,
    val counters: Map<String, ComplaintRecoveryCounter>,
)

internal enum class ComplaintRecoveryFixtureStep { PUBLICATION, RESERVATION, COUNTERS, FIRST_COUNTER, RESERVATION_UPDATED }

/** Callbacks observe/fault real SQL above the driver; no fabricated rows, update counts or completion/authority receipts. */
internal class ComplaintRecoveryFixtureJdbc(private val fixture: OrdinaryComplaintRecoveryFixture) : JdbcTemplate(fixture.ordinary.pool) {
    val counterResults = mutableListOf<Int>()
    val reservationResults = mutableListOf<Int>()
    var beforeQuery: (ComplaintRecoveryFixtureStep) -> Unit = {}
    var beforeCounterUpdate: (Int) -> Unit = {}
    var beforeReservationUpdate: () -> Unit = {}

    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    fun resetCounts() {
        counterResults.clear()
        reservationResults.clear()
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>): List<T> {
        val step = queryStep(sql)
        step?.let { fixture.preserveAssertions { beforeQuery(it) } }
        return super.query(sql, rowMapper).also { step?.let(fixture::checkpoint) }
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> {
        val step = queryStep(sql)
        step?.let { fixture.preserveAssertions { beforeQuery(it) } }
        return super.query(sql, rowMapper, *args).also { step?.let(fixture::checkpoint) }
    }

    override fun update(sql: String, vararg args: Any?): Int {
        val counter = sql.startsWith("UPDATE complaint_capacity_counters")
        val reservation = sql.startsWith("UPDATE complaint_recovery_capacity_reservations")
        if (counter) fixture.preserveAssertions { beforeCounterUpdate(counterResults.size + 1) }
        if (reservation) fixture.preserveAssertions(beforeReservationUpdate)
        return super.update(sql, *args).also { updated ->
            if (counter) {
                counterResults.add(updated)
                if (counterResults.size == 1 && updated == 1) fixture.checkpoint(ComplaintRecoveryFixtureStep.FIRST_COUNTER)
            }
            if (reservation) {
                reservationResults.add(updated)
                if (updated == 1) fixture.checkpoint(ComplaintRecoveryFixtureStep.RESERVATION_UPDATED)
            }
        }
    }

    private fun queryStep(sql: String): ComplaintRecoveryFixtureStep? = when {
        sql.startsWith("SELECT event_id") && sql.contains("FROM complaint_journal_publications") -> ComplaintRecoveryFixtureStep.PUBLICATION
        sql.startsWith("SELECT event_id") && sql.contains("FROM complaint_recovery_capacity_reservations") -> ComplaintRecoveryFixtureStep.RESERVATION
        sql.startsWith("SELECT name, ordinal, accounting_version") -> ComplaintRecoveryFixtureStep.COUNTERS
        else -> null
    }
}

internal fun ComplaintCapacityVector.sqlArray(): String = toLongArray().joinToString(separator = ",", prefix = "{", postfix = "}")

internal class SyntheticComplaintRecoveryFailure : Exception("Synthetic recovery settlement failure.")
