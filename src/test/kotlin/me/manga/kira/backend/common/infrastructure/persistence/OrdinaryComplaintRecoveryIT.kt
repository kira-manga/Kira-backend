package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintRecoverySettlement
import me.manga.kira.backend.complaint.domain.ComplaintRecoverySettlementExpectation
import me.manga.kira.backend.complaint.domain.ComplaintRecoverySettlementResult
import me.manga.kira.backend.complaint.infrastructure.capacity.ComplaintRecoverySettlementOperation
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintRecoverySettlementStore
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/** K04 accounting/ownership with synthetic expectations only. No authenticated W04 producer or terminal/catalog qualification. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OrdinaryComplaintRecoveryIT {
    private val database = lazy { PgLifecycleDatabaseFixture(OrdinaryComplaintRecoveryIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `one selected PID and transaction settle all dimensions preserving other reserve bookkeeping and closed policy`() = withFixture { f ->
        val before = f.state()
        f.afterStep = {
            if (it === ComplaintRecoveryFixtureStep.FIRST_COUNTER || it === ComplaintRecoveryFixtureStep.RESERVATION_UPDATED) {
                assertEquals(before, f.state(), "Neither counters nor reservation may commit independently.")
            }
        }

        assertEquals(ComplaintRecoverySettlementResult.SETTLED, f.execute())

        assertEquals(ComplaintRecoveryFixtureStep.entries, f.observations.map { it.first })
        val first = f.observations.first().second
        f.observations.forEach { (_, observed) ->
            assertSame(first.phase, observed.phase)
            assertSame(first.lease, observed.lease)
            assertEquals(first.identity, observed.identity)
        }
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, first.phase.databaseOutcome())
        assertEquals(List(22) { 1 }, f.jdbc.counterResults)
        assertEquals(listOf(1), f.jdbc.reservationResults)
        f.assertSettlement(before)
        f.assertReleased()
    }

    @Test
    fun `exact use and full unused release settle the whole promise without a second bookkeeping charge`() = withFixture { f ->
        for (use in listOf(f.promise, ComplaintCapacityVector.ZERO)) {
            f.seed()
            val before = f.state()
            assertEquals(ComplaintRecoverySettlementResult.SETTLED, f.execute(f.expectation(actual = use)))
            f.assertSettlement(before, use)
            val settled = f.state()
            assertEquals(ComplaintRecoverySettlementResult.REPLAYED, f.execute(f.expectation(actual = use)))
            assertEquals(settled, f.state())
            assertTrue(f.jdbc.counterResults.isEmpty() && f.jdbc.reservationResults.isEmpty())
        }
    }

    @Test
    fun `exact replay writes nothing and changed original use scope or event never reinterprets the locked reservation`() = withFixture { f ->
        val changedOriginal = f.promise.with(ComplaintCapacityCounter.STORAGE_BYTES, f.promise[ComplaintCapacityCounter.STORAGE_BYTES] + 1)
        assertRolledBack(f) { f.execute(f.expectation(original = changedOriginal)) }
        assertEquals(ComplaintRecoverySettlementResult.SETTLED, f.execute())
        val committed = f.state()
        val mismatches = listOf(
            f.expectation(original = changedOriginal),
            f.expectation(actual = f.promise),
            f.expectation(selectedScope = ComplaintDataScope.LIVE),
            f.expectation(selectedEvent = f.otherEventId),
            f.expectation(selectedEvent = "A".repeat(43)),
        )
        for (expected in mismatches) {
            assertRolledBack(f) { f.execute(expected) }
            assertEquals(committed, f.state())
            assertTrue(f.jdbc.counterResults.isEmpty() && f.jdbc.reservationResults.isEmpty())
        }
        assertEquals(ComplaintRecoverySettlementResult.REPLAYED, f.execute())
        assertEquals(committed, f.state())
        assertTrue(f.jdbc.counterResults.isEmpty() && f.jdbc.reservationResults.isEmpty())
    }

    @Test
    fun `terminal kinds and compacted publications are refused rather than inventing missing event authority`() = withFixture { f ->
        for (converted in listOf(false, true)) {
            f.seed()
            if (converted) f.execute()
            for (kind in listOf("INSTALLATION_MANIFEST", "TEST_RUN_PURGE")) {
                f.observer.update(
                    "UPDATE complaint_journal_publications SET event_kind = ?, target_count = ? WHERE event_id = ?",
                    kind,
                    if (kind == "TEST_RUN_PURGE") 0 else 1,
                    f.eventId,
                )
                val before = f.state()
                assertRolledBack(f) { f.execute() }
                assertEquals(before, f.state())
                assertTrue(f.jdbc.counterResults.isEmpty() && f.jdbc.reservationResults.isEmpty())
            }
        }
        f.observer.update("UPDATE complaint_journal_publications SET event_kind = 'OWNER_DELETE', target_count = 1 WHERE event_id = ?", f.eventId)
        assertEquals(1, f.observer.update("DELETE FROM complaint_journal_publications WHERE event_id = ?", f.eventId))
        val compacted = f.state()
        assertRolledBack(f) { f.execute() }
        assertEquals(compacted, f.state())
        assertTrue(f.jdbc.counterResults.isEmpty() && f.jdbc.reservationResults.isEmpty())
        // Refusal is the approved contraction. Compacted replay remains UNRESOLVED pending W04 authenticated event binding.
    }

    @Test
    fun `SQL one over and insufficient aggregate reserve fail before writes even with a valid original row`() = withFixture { f ->
        val counter = ComplaintCapacityCounter.STORAGE_BYTES
        val before = f.state()
        val oneOver = f.promise.with(counter, f.promise[counter] + 1)
        assertRolledBack(f) { f.execute(f.expectation(actual = oneOver)) }
        assertEquals(before, f.state())
        assertTrue(f.jdbc.counterResults.isEmpty())

        f.observer.update(
            "UPDATE complaint_capacity_counters SET recovery_reserved_units = ?, free_units = hard_limit - actual_units - test_reserved_units - ? " +
                "WHERE name = 'storage_bytes'",
            f.promise[counter] - 1,
            f.promise[counter] - 1,
        )
        val insufficient = f.state()
        assertRolledBack(f) { f.execute() }
        assertEquals(insufficient, f.state())
        assertTrue(f.jdbc.counterResults.isEmpty() && f.jdbc.reservationResults.isEmpty())
        // Each-dimension arithmetic/overflow already belongs to ComplaintCapacityLedgerTest; do not duplicate 22 DB cases.
    }

    @Test
    fun `SQL array metadata and reservation version state scope timestamps and reference are decoded fail closed`() = withFixture { f ->
        val corruptions = listOf(
            "reserved_amounts = array_fill(0::bigint, ARRAY[22], ARRAY[0])",
            "reserved_amounts = array_fill(0::bigint, ARRAY[2,11])",
            "reserved_amounts = array_fill(0::bigint, ARRAY[21])",
            "reserved_amounts = array_prepend(NULL::bigint, array_fill(0::bigint, ARRAY[21]))",
            "state = 'CONVERTED', converted_amounts = array_fill(0::bigint, ARRAY[22], ARRAY[0]), converted_at = now()",
            "accounting_version = 2",
            "state = 'RELEASED'",
            "test_only = false",
            "created_at = 'infinity'",
            "publication_ref = NULL",
        )
        for (set in corruptions) {
            f.withRelaxedChecks("complaint_recovery_capacity_reservations") {
                f.observer.update("UPDATE complaint_recovery_capacity_reservations SET $set WHERE event_id = ?", f.eventId)
                val before = f.state()
                assertRolledBack(f) { f.execute() }
                assertEquals(before, f.state())
                assertTrue(f.jdbc.counterResults.isEmpty() && f.jdbc.reservationResults.isEmpty())
            }
        }
    }

    @Test
    fun `missing or malformed counter catalogue and absent mismatched or inconsistent policy cannot settle`() = withFixture { f ->
        val corruptions = listOf(
            "DELETE FROM complaint_capacity_counters WHERE name = 'test_runs'",
            "UPDATE complaint_capacity_counters SET accounting_version = 2 WHERE name = 'test_runs'",
            "UPDATE complaint_capacity_counters SET name = 'unregistered' WHERE name = 'test_runs'",
            "UPDATE complaint_capacity_counters SET updated_at = 'infinity' WHERE name = 'test_runs'",
            "UPDATE complaint_capacity_counters SET admission_count = -1 WHERE name = 'installation_ids'",
            "UPDATE complaint_capacity_counters SET free_units = free_units + 1 WHERE name = 'test_runs'",
        )
        for (sql in corruptions) {
            f.withRelaxedChecks("complaint_capacity_counters") {
                f.observer.update(sql)
                val before = f.state()
                assertRolledBack(f) { f.execute() }
                assertEquals(before, f.state())
                assertTrue(f.jdbc.counterResults.isEmpty() && f.jdbc.reservationResults.isEmpty())
            }
        }
        for (expected in listOf(null, ByteArray(32) { 9 })) {
            val before = f.state()
            val store = JdbcComplaintRecoverySettlementStore(f.jdbc, JdbcComplaintCapacityStore(f.jdbc, expected))
            assertRolledBack(f) { f.execute(port = store) }
            assertEquals(before, f.state())
        }
        for (configuration in listOf("configuration_hash = NULL", "configuration_closed = false")) {
            f.observer.update("UPDATE complaint_capacity_counters SET $configuration WHERE name = 'test_runs'")
            val before = f.state()
            assertRolledBack(f) { f.execute() }
            assertEquals(before, f.state())
            f.observer.update(
                "UPDATE complaint_capacity_counters SET configuration_hash = ?, configuration_closed = true WHERE name = 'test_runs'",
                f.counters.syntheticPolicyDigest(),
            )
        }
    }

    @Test
    fun `caught non SQL failure after first counter or reservation transition cannot commit any partial work`() = withFixture { f ->
        val before = f.state()
        for (point in listOf(ComplaintRecoveryFixtureStep.FIRST_COUNTER, ComplaintRecoveryFixtureStep.RESERVATION_UPDATED)) {
            var reached = false
            f.afterStep = {
                if (it === point) {
                    reached = true
                    throw SyntheticComplaintRecoveryFailure()
                }
            }
            val swallowed = port(f) { expected ->
                assertThrows<PersistencePhaseException> { f.store.settle(expected) }
                ComplaintRecoverySettlementResult.SETTLED
            }
            assertRolledBack(f) { f.execute(port = swallowed) }
            assertTrue(reached)
            assertEquals(before, f.state())
        }
        f.afterStep = {}
        assertRolledBack(f) {
            f.execute(
                port = port(f) { expected ->
                    f.store.settle(expected)
                    throw SyntheticComplaintRecoveryFailure()
                },
            )
        }
        assertEquals(before, f.state())
    }

    @Test
    fun `real zero row counter or reservation CAS rolls back prior updates and the test deletion`() = withFixture { f ->
        val before = f.state()
        f.jdbc.beforeCounterUpdate = { attempt ->
            if (attempt == 2) assertEquals(1, f.ordinary.jdbc.update("DELETE FROM complaint_capacity_counters WHERE name = 'audit_rows'"))
        }
        assertRolledBack(f) { f.execute() }
        assertEquals(listOf(1, 0), f.jdbc.counterResults)
        assertEquals(before, f.state())

        f.jdbc.beforeCounterUpdate = {}
        f.jdbc.beforeReservationUpdate = {
            assertEquals(1, f.ordinary.jdbc.update("DELETE FROM complaint_recovery_capacity_reservations WHERE event_id = ?", f.eventId))
        }
        assertRolledBack(f) { f.execute() }
        assertEquals(List(22) { 1 }, f.jdbc.counterResults)
        assertEquals(listOf(0), f.jdbc.reservationResults)
        assertEquals(before, f.state())
    }

    @Test
    fun `fabricated result skipped completion early counter access and repeated settlement cannot complete the retained phase`() = withFixture { f ->
        val before = f.state()
        for (result in ComplaintRecoverySettlementResult.entries) {
            assertRolledBack(f) { f.execute(port = port(f) { result }) }
        }
        assertRolledBack(f) {
            f.execute(
                port = port(f) { expected ->
                    val operation = ComplaintRecoverySettlementOperation.lock(f.jdbc, expected)
                    assertThrows<PersistencePhaseException> { f.capacity.lockForRecoverySettlement(operation) }
                    ComplaintRecoverySettlementResult.SETTLED
                },
            )
        }
        assertRolledBack(f) {
            f.execute(
                port = port(f) { expected ->
                    ComplaintRecoverySettlementOperation.lock(f.jdbc, expected)
                    ComplaintRecoverySettlementResult.SETTLED // Genuine locks without the real transfer/CAS cannot complete.
                },
            )
        }
        assertRolledBack(f) {
            f.execute(
                port = port(f) { expected ->
                    val result = f.store.settle(expected)
                    assertThrows<PersistencePhaseException> { f.store.settle(expected) }
                    result
                },
            )
        }
        assertRolledBack(f) {
            f.execute(
                port = port(f) { expected ->
                    f.store.settle(expected)
                    ComplaintRecoverySettlementResult.REPLAYED // An enum cannot substitute for the retained actual result.
                },
            )
        }
        val phase = f.ordinary.ownership.enterComplaintRecoverySettlement()
        try {
            phase.begin()
            assertThrows<PersistencePhaseException> { phase.commit() }
        } finally {
            phase.finish()
        }
        assertRolledBack(f) { phase.complaintRecoverySettlementResult(ComplaintRecoverySettlementResult.SETTLED) }
        assertEquals(before, f.state())
    }

    @Test
    fun `stale and cross thread operation use poison the attempted owner without undoing an earlier known commit`() = withFixture { f ->
        lateinit var old: ComplaintRecoverySettlementOperation
        assertEquals(
            ComplaintRecoverySettlementResult.SETTLED,
            f.execute(
                port = port(f) { expected ->
                    old = ComplaintRecoverySettlementOperation.lock(f.jdbc, expected)
                    old.settle(f.capacity)
                },
            ),
        )
        val committed = f.state()
        assertRolledBack(f) {
            f.execute(
                port = port(f) {
                    assertThrows<PersistencePhaseException> { old.settle(f.capacity) }
                    ComplaintRecoverySettlementResult.REPLAYED
                },
            )
        }
        assertEquals(committed, f.state())

        val failed = AtomicReference<Throwable?>()
        assertRolledBack(f) {
            f.execute(
                port = port(f) { expected ->
                    val operation = ComplaintRecoverySettlementOperation.lock(f.jdbc, expected)
                    val thread = Thread.ofPlatform().start {
                        try {
                            operation.settle(f.capacity)
                        } catch (problem: Throwable) {
                            failed.set(problem)
                        }
                    }
                    finishThread(thread)
                    assertTrue(failed.get() is PersistencePhaseException)
                    ComplaintRecoverySettlementResult.REPLAYED
                },
            )
        }
        assertEquals(committed, f.state())
    }

    @Test
    fun `missing wrong nested or substituted phase resources cannot be repaired into a successful settlement`() = withFixture { f ->
        val before = f.state()
        assertThrows<PersistencePhaseException> { f.store.settle(f.expectation()) }
        val wrongPath = f.ordinary.ownership.enterSourceGrantCleanup()
        try {
            wrongPath.begin()
            assertThrows<PersistencePhaseException> { f.store.settle(f.expectation()) }
            assertThrows<PersistencePhaseException> { wrongPath.commit() }
        } finally {
            wrongPath.finish()
        }
        assertRolledBack(f) { wrongPath.result(0) }
        val borrows = AtomicInteger()
        val foreign = foreignTemplate(f, borrows)
        for (foreignRows in listOf(false, true)) {
            val store = JdbcComplaintRecoverySettlementStore(
                if (foreignRows) foreign else f.jdbc,
                JdbcComplaintCapacityStore(if (foreignRows) f.jdbc else foreign, f.counters.syntheticPolicyDigest()),
            )
            assertRolledBack(f) { f.execute(port = store) }
            assertEquals(0, borrows.get())
        }
        for (entity in listOf(false, true)) {
            f.afterStep = { if (it === ComplaintRecoveryFixtureStep.FIRST_COUNTER) replaceHolderAndRefuse(f, entity) }
            assertRolledBack(f) { f.execute() }
        }
        f.afterStep = {}
        assertRolledBack(f) {
            f.execute(
                port = port(f) { expected ->
                    val result = f.store.settle(expected)
                    assertThrows<PersistencePhaseException> { f.executor().settle(expected) }
                    result
                },
            )
        }
        assertEquals(before, f.state())
    }

    @Test
    fun `real counter lock wait follows publication and reservation locks on the same selected transaction`() = withFixture { f ->
        val before = f.state()
        val enteringCounters = CountDownLatch(1)
        val selected = AtomicReference<StepUpPhaseObservation?>()
        val result = AtomicReference<ComplaintRecoverySettlementResult?>()
        val failure = AtomicReference<Throwable?>()
        f.jdbc.beforeQuery = {
            if (it === ComplaintRecoveryFixtureStep.COUNTERS) {
                selected.set(observeStepUpPhase(f.ordinary))
                enteringCounters.countDown()
            }
        }
        val lock = f.counters.lockLastCounter()
        val thread = Thread.ofPlatform().start {
            try {
                result.set(f.executor().settle(f.expectation()))
            } catch (problem: Throwable) {
                failure.set(problem)
            }
        }
        try {
            assertTrue(enteringCounters.await(1, TimeUnit.SECONDS))
            assertTrue(f.awaitBlocked(checkNotNull(selected.get()).identity.first))
            assertEquals(listOf(ComplaintRecoveryFixtureStep.PUBLICATION, ComplaintRecoveryFixtureStep.RESERVATION), f.observations.map { it.first })
            assertEquals(before, f.state())
        } finally {
            lock.close()
            finishThread(thread)
        }
        failure.get()?.let { throw it }
        assertEquals(ComplaintRecoverySettlementResult.SETTLED, result.get())
        f.assertSettlement(before)
        f.assertReleased()
    }

    @Test
    fun `concurrent same event settlement waits on the publication and transfers exactly once`() = withFixture(maximumPoolSize = 3) { f ->
        assertEquals(2, f.ordinary.admission.ownerLimit)
        val before = f.state()
        val firstLocked = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntering = CountDownLatch(1)
        val holdFirst = AtomicBoolean(true)
        val firstPid = AtomicInteger()
        val secondPid = AtomicInteger()
        val results = listOf(AtomicReference<ComplaintRecoverySettlementResult?>(), AtomicReference<ComplaintRecoverySettlementResult?>())
        val failures = listOf(AtomicReference<Throwable?>(), AtomicReference<Throwable?>())
        f.afterStep = {
            if (it === ComplaintRecoveryFixtureStep.PUBLICATION && holdFirst.compareAndSet(true, false)) {
                firstPid.set(observeStepUpPhase(f.ordinary).identity.first)
                firstLocked.countDown()
                assertTrue(releaseFirst.await(1_500, TimeUnit.MILLISECONDS))
            }
        }
        val secondJdbc = ComplaintRecoveryFixtureJdbc(f).apply {
            beforeQuery = {
                if (it === ComplaintRecoveryFixtureStep.PUBLICATION) {
                    secondPid.set(observeStepUpPhase(f.ordinary).identity.first)
                    secondEntering.countDown()
                }
            }
        }
        val stores = listOf(
            f.store,
            JdbcComplaintRecoverySettlementStore(secondJdbc, JdbcComplaintCapacityStore(secondJdbc, f.counters.syntheticPolicyDigest())),
        )
        val threads = stores.mapIndexed { index, store ->
            Thread.ofPlatform().unstarted {
                try {
                    results[index].set(f.executor(store).settle(f.expectation()))
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
            assertTrue(f.awaitBlocked(secondPid.get(), firstPid.get()))
            assertEquals(before, f.state())
        } finally {
            releaseFirst.countDown()
            try {
                finishThread(threads[0])
            } finally {
                finishThread(threads[1])
            }
        }
        failures.forEach { it.get()?.let { problem -> throw problem } }
        assertEquals(listOf(ComplaintRecoverySettlementResult.SETTLED, ComplaintRecoverySettlementResult.REPLAYED), results.map { it.get() })
        assertEquals(List(22) { 1 }, f.jdbc.counterResults)
        assertEquals(listOf(1), f.jdbc.reservationResults)
        assertTrue(secondJdbc.counterResults.isEmpty() && secondJdbc.reservationResults.isEmpty())
        f.assertSettlement(before)
        f.assertReleased()
    }

    private fun withFixture(maximumPoolSize: Int = 2, test: (OrdinaryComplaintRecoveryFixture) -> Unit) =
        withOrdinaryComplaintRecovery(database.value, maximumPoolSize, test)

    private fun port(
        f: OrdinaryComplaintRecoveryFixture,
        work: (ComplaintRecoverySettlementExpectation) -> ComplaintRecoverySettlementResult,
    ): ComplaintRecoverySettlement = ComplaintRecoverySettlement { f.preserveAssertions { work(it) } }

    private fun assertRolledBack(f: OrdinaryComplaintRecoveryFixture, work: () -> Any?) {
        val failure = assertThrows<PersistencePhaseException> { work() }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        f.assertReleased()
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
        assertFalse(thread.isAlive, "Synthetic recovery caller must actually end before fixture cleanup.")
    }

    private fun replaceHolderAndRefuse(f: OrdinaryComplaintRecoveryFixture, entity: Boolean) {
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

    private fun foreignTemplate(f: OrdinaryComplaintRecoveryFixture, borrows: AtomicInteger): JdbcTemplate {
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
