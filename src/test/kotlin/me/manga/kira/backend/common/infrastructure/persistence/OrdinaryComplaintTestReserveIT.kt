package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintTestReserveSpend
import me.manga.kira.backend.complaint.domain.ComplaintTestReserveSpendExpectation
import me.manga.kira.backend.complaint.domain.ComplaintTestReserveSpendResult
import me.manga.kira.backend.complaint.infrastructure.capacity.ComplaintTestReserveSpendOperation
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintTestReserveStore
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/** K05 lower SQL composition only. Synthetic bookkeeping/comparison data never certifies W04 catalog/run/terminal authority. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OrdinaryComplaintTestReserveIT {
    private val database = lazy { PgLifecycleDatabaseFixture(OrdinaryComplaintTestReserveIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `counter then ACTIVE run on one selected PID and transaction spend all dimensions without refunding terminal allocations`() = withFixture { f ->
        val before = f.state()
        f.afterStep = {
            if (it === ComplaintTestReserveFixtureStep.FIRST_COUNTER || it === ComplaintTestReserveFixtureStep.RUN_UPDATED) {
                assertEquals(before, f.state(), "No intermediate counter or owning-run change may independently commit.")
            }
        }

        assertEquals(ComplaintTestReserveSpendResult.SPENT, f.execute())

        assertEquals(ComplaintTestReserveFixtureStep.entries, f.observations.map { it.first })
        val first = f.observations.first().second
        f.observations.forEach { (_, observed) ->
            assertSame(first.phase, observed.phase)
            assertSame(first.lease, observed.lease)
            assertEquals(first.identity, observed.identity)
        }
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, first.phase.databaseOutcome())
        assertEquals(List(22) { 1 }, f.jdbc.counterResults)
        assertEquals(listOf(1), f.jdbc.runResults)
        f.assertSpent(before)
        f.assertReleased()
    }

    @Test
    fun `exact all actual all recovery and zero use preserve free bookkeeping and other reserves with creation closed`() = withFixture { f ->
        val uses = listOf(
            f.unused to ComplaintCapacityVector.ZERO,
            ComplaintCapacityVector.ZERO to f.unused,
            ComplaintCapacityVector.ZERO to ComplaintCapacityVector.ZERO,
        )
        for ((actual, recovery) in uses) {
            f.seed()
            val before = f.state()
            assertEquals(
                22L,
                f.observer.queryForObject(
                    "SELECT count(*) FROM complaint_capacity_counters WHERE configuration_closed AND creation_limit = 0",
                    Long::class.java,
                ),
            )
            assertEquals(ComplaintTestReserveSpendResult.SPENT, f.execute(f.expectation(toActual = actual, toRecovery = recovery)))
            f.assertSpent(before, actual, recovery)
            assertEquals(listOf(1), f.jdbc.runResults)
            if (actual.isZero() && recovery.isZero()) {
                assertTrue(f.jdbc.counterResults.isEmpty())
                assertEquals(before, f.state())
            }
        }
    }

    @Test
    fun `changed original unused scope and repeated old expectations cannot invent a replay or spend another allocation`() = withFixture { f ->
        val dimension = ComplaintCapacityCounter.STORAGE_BYTES
        val wrong = listOf(
            f.expectation(promised = f.original.with(dimension, f.original[dimension] + 1)),
            f.expectation(remaining = f.unused.with(dimension, f.unused[dimension] - 1)),
            f.expectation(selectedScope = ComplaintDataScope.of(UUID.randomUUID())),
        )
        val before = f.state()
        for (expected in wrong) {
            assertRolledBack(f) { f.execute(expected) }
            assertNoWrites(f)
            assertEquals(before, f.state())
        }
        assertThrows<IllegalArgumentException> { f.expectation(selectedScope = ComplaintDataScope.LIVE) }
        assertEquals(ComplaintTestReserveSpendResult.SPENT, f.execute())
        val committed = f.state()
        assertRolledBack(f) { f.execute() }
        assertNoWrites(f)
        assertEquals(committed, f.state(), "A second call with the old unused vector is refused, not relabeled as idempotent replay.")
    }

    @Test
    fun `combined one over aggregate shortfall and checked addition overflow fail before any update`() = withFixture { f ->
        val dimension = ComplaintCapacityCounter.STORAGE_BYTES
        val before = f.state()
        val uses = listOf(
            f.unused to ComplaintCapacityVector.units(dimension, 1),
            ComplaintCapacityVector.units(dimension, Long.MAX_VALUE) to ComplaintCapacityVector.units(dimension, 1),
        )
        for ((actual, recovery) in uses) {
            assertRolledBack(f) { f.execute(f.expectation(toActual = actual, toRecovery = recovery)) }
            assertNoWrites(f)
            assertEquals(before, f.state())
        }
        val tooLittle = f.actual[dimension] + f.recovery[dimension] - 1
        f.observer.update(
            "UPDATE complaint_capacity_counters SET test_reserved_units = ?, free_units = hard_limit - actual_units - recovery_reserved_units - ? " +
                "WHERE name = 'storage_bytes'",
            tooLittle,
            tooLittle,
        )
        val insufficient = f.state()
        assertRolledBack(f) { f.execute() }
        assertNoWrites(f)
        assertEquals(insufficient, f.state())
        // Each-dimension vector arithmetic is already covered by the pure ledger tests, not another 22-case DB replay.
    }

    @Test
    fun `legal SEALED PURGING and PURGED rows refuse even zero spending without terminal catalog authority`() = withFixture { f ->
        for (state in listOf("SEALED", "PURGING", "PURGED")) {
            f.seed()
            f.setInactiveState(state)
            val before = f.state()
            assertRolledBack(f) {
                f.execute(
                    f.expectation(
                        remaining = if (state == "PURGED") ComplaintCapacityVector.ZERO else f.unused,
                        toActual = ComplaintCapacityVector.ZERO,
                        toRecovery = ComplaintCapacityVector.ZERO,
                    ),
                )
            }
            assertNoWrites(f)
            assertEquals(before, f.state())
        }
    }

    @Test
    fun `SQL vector metadata ACTIVE shape version scope timestamps and catalogue fields are decoded fail closed`() = withFixture { f ->
        val corruptions = listOf(
            "original_reserve = array_fill(0::bigint, ARRAY[22], ARRAY[0])",
            "unused_reserve = array_fill(0::bigint, ARRAY[2,11])",
            "unused_reserve = array_fill(0::bigint, ARRAY[21])",
            "unused_reserve = array_prepend(NULL::bigint, array_fill(0::bigint, ARRAY[21]))",
            "original_reserve = array_fill(-1::bigint, ARRAY[22])",
            "accounting_version = 2",
            "test_only = false",
            "state = 'UNKNOWN'",
            "created_at = 'infinity'",
            "sealed_at = now()",
            "terminal_catalog_hash = configuration_hash",
            "installation_limit = 0",
            "enrolled_count = 26",
            "activation_catalog_generation = 0",
            "activation_catalog_hash = decode('00', 'hex')",
            "configuration_hash = decode('00', 'hex')",
        )
        for (set in corruptions) {
            f.withRelaxedRunChecks {
                f.observer.update("UPDATE complaint_test_runs SET $set WHERE data_scope_id = ?", f.scope.id)
                val before = f.state()
                assertRolledBack(f) { f.execute() }
                assertNoWrites(f)
                assertEquals(before, f.state())
            }
        }
        f.withRelaxedRunChecks {
            val enlarged = f.original + ComplaintCapacityVector.units(ComplaintCapacityCounter.STORAGE_BYTES, 1)
            f.observer.update("UPDATE complaint_test_runs SET unused_reserve = ?::bigint[] WHERE data_scope_id = ?", enlarged.sqlArray(), f.scope.id)
            val before = f.state()
            assertRolledBack(f) { f.execute(f.expectation(remaining = enlarged)) }
            assertNoWrites(f)
            assertEquals(before, f.state(), "Matching caller data cannot make unused larger than the stored original promise.")
        }
    }

    @Test
    fun `missing counter catalogue or run and absent inconsistent or mismatched policy cannot spend`() = withFixture { f ->
        val corruptions = listOf(
            "DELETE FROM complaint_capacity_counters WHERE name = 'test_runs'",
            "UPDATE complaint_capacity_counters SET accounting_version = 2 WHERE name = 'test_runs'",
            "UPDATE complaint_capacity_counters SET free_units = free_units + 1 WHERE name = 'test_runs'",
            "UPDATE complaint_capacity_counters SET configuration_closed = false WHERE name = 'test_runs'",
        )
        for (sql in corruptions) {
            f.base.withRelaxedChecks("complaint_capacity_counters") {
                f.observer.update(sql)
                val before = f.state()
                assertRolledBack(f) { f.execute() }
                assertNoWrites(f)
                assertEquals(listOf(ComplaintTestReserveFixtureStep.COUNTERS), f.jdbc.queries)
                assertEquals(before, f.state())
            }
        }
        for (expected in listOf(null, ByteArray(32) { 9 })) {
            val before = f.state()
            assertRolledBack(f) { f.execute(port = JdbcComplaintTestReserveStore(f.jdbc, JdbcComplaintCapacityStore(f.jdbc, expected))) }
            assertNoWrites(f)
            assertEquals(before, f.state())
        }
        f.observer.update("UPDATE complaint_test_runs SET configuration_hash = ? WHERE data_scope_id = ?", ByteArray(32) { 9 }, f.scope.id)
        val wrongRunPolicy = f.state()
        assertRolledBack(f) { f.execute() }
        assertNoWrites(f)
        assertEquals(wrongRunPolicy, f.state())
        f.observer.update("DELETE FROM complaint_test_runs WHERE data_scope_id = ?", f.scope.id)
        val absent = f.state()
        assertRolledBack(f) { f.execute() }
        assertNoWrites(f)
        assertEquals(absent, f.state())
    }

    @Test
    fun `caught non SQL failures after counter or run writes and a late caller failure roll the entire spend back`() = withFixture { f ->
        val before = f.state()
        for (point in listOf(ComplaintTestReserveFixtureStep.FIRST_COUNTER, ComplaintTestReserveFixtureStep.RUN_UPDATED)) {
            var reached = false
            f.afterStep = {
                if (it === point) {
                    reached = true
                    throw SyntheticComplaintTestReserveFailure()
                }
            }
            assertRolledBack(f) {
                f.execute(
                    port = port(f) { expected ->
                        assertThrows<PersistencePhaseException> { f.store.spend(expected) }
                        ComplaintTestReserveSpendResult.SPENT
                    },
                )
            }
            assertTrue(reached)
            assertEquals(before, f.state())
        }
        f.afterStep = {}
        assertRolledBack(f) {
            f.execute(
                port = port(f) { expected ->
                    f.store.spend(expected)
                    throw SyntheticComplaintTestReserveFailure()
                },
            )
        }
        assertEquals(before, f.state())
    }

    @Test
    fun `real zero row counter and run CAS roll back earlier changes and the fixture intervention`() = withFixture { f ->
        val before = f.state()
        f.jdbc.beforeCounterUpdate = { attempt ->
            if (attempt == 2) assertEquals(1, f.ordinary.jdbc.update("DELETE FROM complaint_capacity_counters WHERE name = 'audit_rows'"))
        }
        assertRolledBack(f) { f.execute() }
        assertEquals(listOf(1, 0), f.jdbc.counterResults)
        assertEquals(before, f.state())

        f.jdbc.beforeCounterUpdate = {}
        f.jdbc.beforeRunUpdate = {
            assertEquals(1, f.ordinary.jdbc.update("UPDATE complaint_test_runs SET unused_reserve = original_reserve WHERE data_scope_id = ?", f.scope.id))
        }
        assertRolledBack(f) { f.execute() }
        assertEquals(List(22) { 1 }, f.jdbc.counterResults)
        assertEquals(listOf(0), f.jdbc.runResults)
        assertEquals(before, f.state())
    }

    @Test
    fun `fabricated result prepared only early counter access and repeated use cannot complete the retained operation`() = withFixture { f ->
        val before = f.state()
        assertRolledBack(f) { f.execute(port = port(f) { ComplaintTestReserveSpendResult.SPENT }) }
        assertRolledBack(f) {
            f.execute(
                port = port(f) { expected ->
                    ComplaintTestReserveSpendOperation.prepare(f.jdbc, expected)
                    ComplaintTestReserveSpendResult.SPENT
                },
            )
        }
        assertRolledBack(f) {
            f.execute(
                port = port(f) { expected ->
                    val operation = ComplaintTestReserveSpendOperation.prepare(f.jdbc, expected)
                    assertThrows<PersistencePhaseException> { f.capacity.lockForTestReserveSpend(operation) }
                    ComplaintTestReserveSpendResult.SPENT
                },
            )
        }
        assertRolledBack(f) {
            f.execute(
                port = port(f) { expected ->
                    val result = f.store.spend(expected)
                    assertThrows<PersistencePhaseException> { f.store.spend(expected) }
                    result
                },
            )
        }
        val phase = f.ordinary.ownership.enterComplaintTestReserveSpend()
        try {
            phase.begin()
            assertThrows<PersistencePhaseException> { phase.commit() }
        } finally {
            phase.finish()
        }
        assertRolledBack(f) { phase.complaintTestReserveSpendResult(ComplaintTestReserveSpendResult.SPENT) }
        assertEquals(before, f.state())
    }

    @Test
    fun `stale and cross thread use poison the attempted owner without undoing an earlier committed allocation`() = withFixture { f ->
        lateinit var old: ComplaintTestReserveSpendOperation
        assertEquals(
            ComplaintTestReserveSpendResult.SPENT,
            f.execute(
                port = port(f) { expected ->
                    old = ComplaintTestReserveSpendOperation.prepare(f.jdbc, expected)
                    old.spend(f.capacity)
                },
            ),
        )
        val committed = f.state()
        assertRolledBack(f) {
            f.execute(
                port = port(f) {
                    assertThrows<PersistencePhaseException> { old.spend(f.capacity) }
                    ComplaintTestReserveSpendResult.SPENT
                },
            )
        }
        assertEquals(committed, f.state())
        val failure = AtomicReference<Throwable?>()
        assertRolledBack(f) {
            f.execute(
                port = port(f) { expected ->
                    val operation = ComplaintTestReserveSpendOperation.prepare(f.jdbc, expected)
                    val thread = Thread.ofPlatform().start {
                        try {
                            operation.spend(f.capacity)
                        } catch (problem: Throwable) {
                            failure.set(problem)
                        }
                    }
                    finishThread(thread)
                    assertTrue(failure.get() is PersistencePhaseException)
                    ComplaintTestReserveSpendResult.SPENT
                },
            )
        }
        assertEquals(committed, f.state())
    }

    @Test
    fun `missing wrong nested and substituted phase resources fail without a foreign borrow or repair into success`() = withFixture { f ->
        val before = f.state()
        assertThrows<PersistencePhaseException> { f.store.spend(f.expectation()) }
        val wrongPath = f.ordinary.ownership.enterSourceGrantCleanup()
        try {
            wrongPath.begin()
            assertThrows<PersistencePhaseException> { f.store.spend(f.expectation()) }
            assertThrows<PersistencePhaseException> { wrongPath.commit() }
        } finally {
            wrongPath.finish()
        }
        assertRolledBack(f) { wrongPath.result(0) }
        val borrows = AtomicInteger()
        val foreign = foreignTemplate(f, borrows)
        for (foreignRows in listOf(false, true)) {
            val store = JdbcComplaintTestReserveStore(
                if (foreignRows) foreign else f.jdbc,
                JdbcComplaintCapacityStore(if (foreignRows) f.jdbc else foreign, f.counters.syntheticPolicyDigest()),
            )
            assertRolledBack(f) { f.execute(port = store) }
            assertEquals(0, borrows.get())
        }
        for (entity in listOf(false, true)) {
            f.afterStep = { if (it === ComplaintTestReserveFixtureStep.FIRST_COUNTER) replaceHolderAndRefuse(f, entity) }
            assertRolledBack(f) { f.execute() }
        }
        f.afterStep = {}
        assertRolledBack(f) {
            f.execute(
                port = port(f) { expected ->
                    val result = f.store.spend(expected)
                    assertThrows<PersistencePhaseException> { f.executor().spend(expected) }
                    result
                },
            )
        }
        assertEquals(before, f.state())
    }

    @Test
    fun `real counter and run waits prove all counter locks precede the run on the same selected transaction`() = withFixture { f ->
        for (point in listOf(ComplaintTestReserveFixtureStep.COUNTERS, ComplaintTestReserveFixtureStep.RUN)) {
            f.seed()
            f.jdbc.resetCounts()
            val before = f.state()
            val entering = CountDownLatch(1)
            val selected = AtomicReference<StepUpPhaseObservation?>()
            val result = AtomicReference<ComplaintTestReserveSpendResult?>()
            val failure = AtomicReference<Throwable?>()
            f.jdbc.beforeQuery = {
                if (it === point) {
                    selected.set(observeStepUpPhase(f.ordinary))
                    entering.countDown()
                }
            }
            val lock = if (point === ComplaintTestReserveFixtureStep.COUNTERS) f.counters.lockLastCounter() else f.lockRun()
            val thread = Thread.ofPlatform().start {
                try {
                    result.set(f.executor().spend(f.expectation()))
                } catch (problem: Throwable) {
                    failure.set(problem)
                }
            }
            try {
                assertTrue(entering.await(1, TimeUnit.SECONDS))
                assertTrue(f.base.awaitBlocked(checkNotNull(selected.get()).identity.first))
                if (point === ComplaintTestReserveFixtureStep.COUNTERS) {
                    assertTrue(f.observations.isEmpty())
                    f.lockRun().use { } // The run can actually be locked NOWAIT while this caller waits for the last counter.
                } else {
                    assertEquals(listOf(ComplaintTestReserveFixtureStep.COUNTERS), f.observations.map { it.first })
                    assertFirstCounterLocked(f)
                }
                assertEquals(before, f.state())
            } finally {
                lock.close()
                finishThread(thread)
            }
            failure.get()?.let { throw it }
            assertEquals(ComplaintTestReserveSpendResult.SPENT, result.get())
            f.assertSpent(before)
            f.assertReleased()
        }
    }

    @Test
    fun `concurrent same expected slice waits on counters and only one spends while the stale caller rolls back`() = withFixture(maximumPoolSize = 3) { f ->
        assertEquals(2, f.ordinary.admission.ownerLimit)
        val before = f.state()
        val firstLocked = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntering = CountDownLatch(1)
        val holdFirst = AtomicBoolean(true)
        val firstPid = AtomicInteger()
        val secondPid = AtomicInteger()
        val results = listOf(AtomicReference<ComplaintTestReserveSpendResult?>(), AtomicReference<ComplaintTestReserveSpendResult?>())
        val failures = listOf(AtomicReference<Throwable?>(), AtomicReference<Throwable?>())
        f.afterStep = {
            if (it === ComplaintTestReserveFixtureStep.COUNTERS && holdFirst.compareAndSet(true, false)) {
                firstPid.set(observeStepUpPhase(f.ordinary).identity.first)
                firstLocked.countDown()
                assertTrue(releaseFirst.await(1_500, TimeUnit.MILLISECONDS))
            }
        }
        val secondJdbc = ComplaintTestReserveFixtureJdbc(f).apply {
            beforeQuery = {
                if (it === ComplaintTestReserveFixtureStep.COUNTERS) {
                    secondPid.set(observeStepUpPhase(f.ordinary).identity.first)
                    secondEntering.countDown()
                }
            }
        }
        val stores = listOf(f.store, JdbcComplaintTestReserveStore(secondJdbc, JdbcComplaintCapacityStore(secondJdbc, f.counters.syntheticPolicyDigest())))
        val threads = stores.mapIndexed { index, store ->
            Thread.ofPlatform().unstarted {
                try {
                    results[index].set(f.executor(store).spend(f.expectation()))
                } catch (problem: Throwable) {
                    failures[index].set(problem)
                }
            }
        }
        threads[0].start()
        try {
            assertTrue(firstLocked.await(1, TimeUnit.SECONDS))
            threads[1].start()
            assertTrue(secondEntering.await(1, TimeUnit.SECONDS))
            assertTrue(f.base.awaitBlocked(secondPid.get(), firstPid.get()))
            assertEquals(before, f.state())
        } finally {
            releaseFirst.countDown()
            try {
                finishThread(threads[0])
            } finally {
                finishThread(threads[1])
            }
        }
        failures[0].get()?.let { throw it }
        assertEquals(ComplaintTestReserveSpendResult.SPENT, results[0].get())
        assertEquals(null, results[1].get())
        val stale = failures[1].get()
        assertTrue(stale is PersistencePhaseException)
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, (stale as PersistencePhaseException).databaseOutcome)
        assertTrue(stale.cleanupProven)
        assertTrue(secondJdbc.counterResults.isEmpty() && secondJdbc.runResults.isEmpty())
        f.assertSpent(before)
        f.assertReleased()
    }

    private fun withFixture(maximumPoolSize: Int = 2, test: (OrdinaryComplaintTestReserveFixture) -> Unit) =
        withOrdinaryComplaintTestReserve(database.value, maximumPoolSize, test)

    private fun port(
        f: OrdinaryComplaintTestReserveFixture,
        work: (ComplaintTestReserveSpendExpectation) -> ComplaintTestReserveSpendResult,
    ): ComplaintTestReserveSpend = ComplaintTestReserveSpend { f.preserveAssertions { work(it) } }

    private fun assertRolledBack(f: OrdinaryComplaintTestReserveFixture, work: () -> Any?) {
        val failure = assertThrows<PersistencePhaseException> { work() }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        f.assertReleased()
    }

    private fun assertNoWrites(f: OrdinaryComplaintTestReserveFixture) {
        assertTrue(f.jdbc.counterResults.isEmpty() && f.jdbc.runResults.isEmpty())
    }

    private fun assertFirstCounterLocked(f: OrdinaryComplaintTestReserveFixture) {
        checkNotNull(f.observer.dataSource).connection.use { connection ->
            connection.createStatement().use { statement ->
                val blocked = assertThrows<SQLException> {
                    statement.executeQuery(
                        "SELECT name FROM complaint_capacity_counters WHERE name = 'app_installations' FOR UPDATE NOWAIT",
                    ).use { }
                }
                assertEquals("55P03", blocked.sqlState)
            }
        }
    }

    private fun finishThread(thread: Thread) {
        try {
            thread.join(1_500)
        } finally {
            if (thread.isAlive) {
                thread.interrupt()
                thread.join(500)
            }
        }
        assertFalse(thread.isAlive, "Synthetic test reserve caller must actually end before fixture cleanup.")
    }

    private fun replaceHolderAndRefuse(f: OrdinaryComplaintTestReserveFixture, entity: Boolean) {
        val key: Any = if (entity) f.ordinary.entityManagerFactory else f.ordinary.pool
        val original = TransactionSynchronizationManager.unbindResource(key)
        try {
            val changed = if (entity) {
                EntityManagerHolder((original as EntityManagerHolder).entityManager)
            } else {
                ConnectionHolder((original as ConnectionHolder).connection)
            }
            TransactionSynchronizationManager.bindResource(key, changed)
            assertThrows<PersistencePhaseException> { checkNotNull(PersistencePhaseOwnership.current()).requireParticipation() }
        } finally {
            TransactionSynchronizationManager.unbindResource(key)
            TransactionSynchronizationManager.bindResource(key, original)
        }
    }

    private fun foreignTemplate(f: OrdinaryComplaintTestReserveFixture, borrows: AtomicInteger): JdbcTemplate {
        val delegate = checkNotNull(f.observer.dataSource)
        val foreign = object : DataSource by delegate {
            override fun getConnection(): Connection {
                borrows.incrementAndGet()
                return delegate.connection
            }

            override fun getConnection(username: String, password: String): Connection {
                borrows.incrementAndGet()
                return delegate.getConnection(username, password)
            }
        }
        return JdbcTemplate(foreign).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
    }
}
