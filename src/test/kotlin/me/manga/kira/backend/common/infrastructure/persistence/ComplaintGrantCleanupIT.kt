package me.manga.kira.backend.common.infrastructure.persistence

import com.zaxxer.hikari.HikariDataSource
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintGrantCleanupPhaseExecutor
import me.manga.kira.backend.security.AdminStepUpService.Companion.SOURCE_ADMIN_MUTATION_SCOPE
import me.manga.kira.backend.security.ComplaintGrantCleanup
import me.manga.kira.backend.security.ComplaintGrantCleanupBatch
import me.manga.kira.backend.security.JdbcComplaintGrantCleanupStore
import me.manga.kira.backend.security.SourceGrantCleanup
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Date
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** Actual existing PG/JPA/owned-pool fixture only. All policy/counter setup below is explicitly synthetic and restored. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComplaintGrantCleanupIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintGrantCleanupIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `P one refuses complaint entry before its store clock or permit while retaining the source floor`() =
        withOrdinarySourceGrantCleanup(database.value) { f ->
            var calls = 0
            val clock = CleanupCountingClock(f.cutoff)
            val port = complaintPort {
                calls++
                0
            }
            assertEquals(1, (lifecycleField(f.pool, "pool") as HikariDataSource).maximumPoolSize)

            val failure = assertThrows<PersistencePhaseException> { executor(f, port, clock).cleanupComplaintGrants() }

            assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, failure.code)
            assertEquals(PersistenceDatabaseOutcome.NONE, failure.databaseOutcome)
            assertEquals(0, calls)
            assertEquals(0, clock.samples.get())
            assertEquals(0, f.admission.activeOwners())
            assertEquals(0, f.newExecutor().cleanupSourceGrants())
            requireConnectionFree()
        }

    @Test
    fun `mixed cutoff and used grants delete and refund together on the same JPA holder PID and transaction`() = withCounters { f, counters ->
        f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff.minusNanos(1_000))
        f.seedGrant(id(2), COMPLAINT_SCOPE, f.cutoff)
        f.seedGrant(id(3), COMPLAINT_SCOPE, f.cutoff.plusSeconds(60), f.cutoff.minusSeconds(1))
        f.seedGrant(id(4), COMPLAINT_SCOPE, f.cutoff.plusNanos(1_000))
        f.seedGrant(id(5), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff.minusSeconds(60))
        f.seedGrant(id(6), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff.plusSeconds(60), f.cutoff.minusSeconds(1))
        counters.seed(4)
        val beforeRows = f.grantIds()
        val beforeCounters = counters.snapshot()
        val clock = CleanupCountingClock(f.cutoff)
        val real = store(f, counters)
        var observed = false
        val port = complaintPort { cutoff ->
            assertEquals(f.cutoff, cutoff)
            val holder = TransactionSynchronizationManager.getResource(f.pool) as ConnectionHolder
            val entityHolder = TransactionSynchronizationManager.getResource(f.entityManagerFactory) as EntityManagerHolder
            val identity = transactionIdentity(f.jdbc)
            assertEquals(identity.second, (entityHolder.entityManager.createNativeQuery("SELECT txid_current()").singleResult as Number).toLong())
            val deleted = real.deleteEligibleComplaintGrantsAndRefund(cutoff)
            assertSame(holder, TransactionSynchronizationManager.getResource(f.pool))
            assertSame(entityHolder, TransactionSynchronizationManager.getResource(f.entityManagerFactory))
            assertEquals(identity, transactionIdentity(f.jdbc))
            assertEquals(beforeRows, f.grantIds(), "Independent reads must not observe the in-flight deletion.")
            assertEquals(beforeCounters, counters.snapshot(), "Independent reads must not observe an in-flight refund.")
            observed = true
            deleted
        }

        assertEquals(3, executor(f, port, clock).cleanupComplaintGrants())

        assertTrue(observed)
        assertEquals(1, clock.samples.get())
        assertEquals(setOf(id(4), id(5), id(6)), f.grantIds())
        counters.assertRefund(beforeCounters, 3)
        assertEquals(0, executor(f, real).cleanupComplaintGrants())
        counters.assertRefund(beforeCounters, 3)
    }

    @Test
    fun `one batch deletes only the first fifty eligible UUIDs and refunds no unselected grant`() = withCounters { f, counters ->
        val eligible = (101L..170L).map(::id)
        eligible.reversed().forEach { f.seedGrant(it, COMPLAINT_SCOPE, f.cutoff) }
        f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff.plusSeconds(60))
        f.seedGrant(id(2), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff)
        counters.seed(71)
        val before = counters.snapshot()

        assertEquals(50, executor(f, store(f, counters)).cleanupComplaintGrants())

        assertEquals(eligible.drop(50).toSet() + setOf(id(1), id(2)), f.grantIds())
        counters.assertRefund(before, 50)
    }

    @Test
    fun `an independently locked earliest grant is skipped without refund until a later batch deletes it`() = withCounters { f, counters ->
        val eligible = (1L..52L).map(::id)
        eligible.forEach { f.seedGrant(it, COMPLAINT_SCOPE, f.cutoff) }
        counters.seed(52)
        val before = counters.snapshot()
        val real = store(f, counters)

        f.lockGrant(eligible.first()).use {
            assertEquals(50, executor(f, real).cleanupComplaintGrants())
            assertEquals(setOf(eligible.first(), eligible.last()), f.grantIds())
            counters.assertRefund(before, 50)
        }
        assertEquals(2, executor(f, real).cleanupComplaintGrants())
        assertTrue(f.grantIds().isEmpty())
        counters.assertRefund(before, 52)
    }

    @Test
    fun `closed creation with accounted grants permits refund using a copied synthetic expected binding`() = withCounters { f, counters ->
        f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff)
        counters.seed(1, closed = true)
        val expected = counters.syntheticPolicyDigest()
        val real = store(f, counters, expected)
        expected.fill(0) // Composition copied the trusted input; it does not read this mutable array later.
        val before = counters.snapshot()

        assertEquals(1, executor(f, real).cleanupComplaintGrants())

        assertTrue(f.grantIds().isEmpty())
        counters.assertRefund(before, 1) // Includes unchanged closed/hash/limits/reservations/daily state and all untouched20 rows.
    }

    @Test
    fun `empty eligible selection leaves unknown closed zero seeds untouched without a policy binding`() = withCounters { f, counters ->
        f.seedGrant(id(1), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff)
        f.seedGrant(id(2), COMPLAINT_SCOPE, f.cutoff.plusSeconds(60))
        val beforeRows = f.grantIds()
        val before = counters.snapshot() // No synthetic capacity seed here: these are the unchanged V14 closed zero rows.

        counters.lockLastCounter().use {
            assertEquals(0, executor(f, store(f, counters, expected = null)).cleanupComplaintGrants())
        }

        assertEquals(beforeRows, f.grantIds())
        assertEquals(before, counters.snapshot())
    }

    @Test
    fun `missing malformed and nonmatching expected bindings cannot be authenticated by matching stored hashes`() = withCounters { f, counters ->
        f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff)
        counters.seed(1)
        val before = counters.snapshot()
        val refusedBindings = listOf(null, ByteArray(31), ByteArray(32) { 90 })

        for (expected in refusedBindings) {
            assertRefused(f) { executor(f, store(f, counters, expected)).cleanupComplaintGrants() }
            assertEquals(setOf(id(1)), f.grantIds())
            assertEquals(before, counters.snapshot())
        }

        assertEquals(1, executor(f, store(f, counters)).cleanupComplaintGrants())
        counters.assertRefund(before, 1)
    }

    @Test
    fun `missing counter and disagreement on creation closure refuse the whole locked group`() {
        for (missing in listOf(true, false)) {
            withCounters { f, counters ->
                f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff)
                counters.seed(1)
                if (missing) {
                    assertEquals(1, f.foreignTemplate().update("DELETE FROM complaint_capacity_counters WHERE name = 'test_runs'"))
                } else {
                    assertEquals(1, f.foreignTemplate().update("UPDATE complaint_capacity_counters SET configuration_closed = false WHERE name = 'test_runs'"))
                }
                val before = counters.snapshot()

                assertRefused(f) { executor(f, store(f, counters)).cleanupComplaintGrants() }

                assertEquals(setOf(id(1)), f.grantIds())
                assertEquals(before, counters.snapshot())
            }
        }
    }

    @Test
    fun `balanced moderation or storage underflow does not retroactively account an uncharged grant`() {
        for (storageUnderflow in listOf(false, true)) {
            withCounters { f, counters ->
                f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff)
                counters.seed(if (storageUnderflow) 1 else 0, storageActual = if (storageUnderflow) 16_383 else 16_384)
                val before = counters.snapshot()

                assertRefused(f) { executor(f, store(f, counters)).cleanupComplaintGrants() }

                assertEquals(setOf(id(1)), f.grantIds())
                assertEquals(before, counters.snapshot())
            }
        }
    }

    @Test
    fun `a lock on the last unrelated counter prevents deletion and later releases cleanly`() = withCounters { f, counters ->
        f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff)
        counters.seed(1)
        val before = counters.snapshot()
        val real = store(f, counters)
        var observation: CounterLockOutcomeObservation? = null
        var workExit: Map<String, Any?> = emptyMap()
        val observedStore = complaintPort { cutoff ->
            val phase = requireNotNull(PersistencePhaseOwnership.current())
            val holder = TransactionSynchronizationManager.getResource(f.pool) as ConnectionHolder
            val tap = CounterLockOutcomeObservation(phase, ownedPoolLease(holder.connection))
            observation = tap
            try {
                real.deleteEligibleComplaintGrantsAndRefund(cutoff)
            } finally {
                workExit = tap.snapshot() // After the real SQL/adapter unwinds, before the phase's finally.
            }
        }
        AutoCloseable { observation?.close() }.use {
            val firstChecks = counters.lockLastCounter().use {
                val failure = assertThrows<PersistencePhaseException> { executor(f, observedStore).cleanupComplaintGrants() }
                val tap = requireNotNull(observation)
                val owners = f.admission.activeOwners()
                val quiescent = tap.lease.completion.quiescent()
                val actual = TransactionSynchronizationManager.isActualTransactionActive()
                val synchronization = TransactionSynchronizationManager.isSynchronizationActive()
                val resources = TransactionSynchronizationManager.getResourceMap().size
                val finished = tap.snapshot()
                println(
                    "COUNTER_LOCK_OUTCOME tuple=(${failure.code},${failure.databaseOutcome},${failure.cleanupProven}) " +
                        "beforeFree=(owners=$owners,quiescent=$quiescent,actual=$actual,sync=$synchronization,resources=$resources) " +
                        "work=[$workExit] final=[$finished]",
                )
                val free = runCatching { requireConnectionFree() } // Boundary facts above precede any reconciliation.
                val grantsAfter = f.grantIds()
                val countersAfter = counters.snapshot()
                listOf(
                    Executable { assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome) },
                    Executable { assertTrue(failure.cleanupProven) },
                    Executable { assertEquals(0, owners) },
                    Executable { assertTrue(quiescent) },
                    Executable { free.getOrThrow() },
                    Executable { assertEquals(setOf(id(1)), grantsAfter) },
                    Executable { assertEquals(before, countersAfter) },
                ) + tap.rollbackChecks(workExit, finished)
            }
            val retry = runCatching { executor(f, store(f, counters)).cleanupComplaintGrants() }
            assertAll(
                firstChecks + listOf(
                    Executable { assertEquals(1, retry.getOrThrow()) },
                    Executable { counters.assertRefund(before, 1) },
                    Executable { assertTrue(observation?.failed == false, "Passive lock-outcome observation failed.") },
                ),
            )
        }
    }

    @Test
    fun `caught genuine JDBC failure refuses further business and commit then rolls back and retires the exact source`() = withCounters { f, counters ->
        f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff)
        counters.seed(1)
        val before = counters.snapshot()
        val real = store(f, counters)
        var observation: CounterLockOutcomeObservation? = null
        var workExit: Map<String, Any?> = emptyMap()
        var caught = false
        var businessRefused = false
        var commitRefused = false
        val caughtStore = complaintPort { cutoff ->
            val deleted = real.deleteEligibleComplaintGrantsAndRefund(cutoff)
            assertEquals(1, deleted) // A genuine completed delete/refund batch must not become durable after the caught SQL error.
            val phase = requireNotNull(PersistencePhaseOwnership.current())
            val holder = TransactionSynchronizationManager.getResource(f.pool) as ConnectionHolder
            val tap = CounterLockOutcomeObservation(phase, ownedPoolLease(holder.connection))
            observation = tap
            try {
                assertThrows<DataAccessException> { f.jdbc.queryForObject("SELECT 1 / 0", Int::class.java) }
                caught = true
                val business = assertThrows<PersistencePhaseException> { f.jdbc.queryForObject("SELECT 1", Int::class.java) }
                businessRefused = business.code === PersistencePhaseFailureCode.WORK_FAILED
                val commit = assertThrows<PersistencePhaseException> { phase.commit() }
                commitRefused = commit.code === PersistencePhaseFailureCode.WORK_FAILED
                deleted
            } finally {
                workExit = tap.snapshot()
            }
        }
        AutoCloseable { observation?.close() }.use {
            val failure = assertThrows<PersistencePhaseException> { executor(f, caughtStore).cleanupComplaintGrants() }
            val tap = requireNotNull(observation)
            val finished = tap.snapshot()
            assertAll(
                tap.rollbackChecks(workExit, finished) + listOf(
                    Executable { assertTrue(caught && businessRefused && commitRefused) },
                    Executable { assertEquals(PersistencePhaseFailureCode.WORK_FAILED, failure.code) },
                    Executable { assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome) },
                    Executable { assertTrue(failure.cleanupProven && tap.lease.completion.quiescent()) },
                    Executable { assertEquals(0, f.admission.activeOwners()) },
                    Executable { requireConnectionFree() },
                    Executable { assertEquals(setOf(id(1)), f.grantIds()) },
                    Executable { assertEquals(before, counters.snapshot()) },
                    Executable { assertTrue(!tap.failed, "Passive caught-JDBC observation failed.") },
                ),
            )
        }
    }

    @Test
    fun `caught failure after real deletion first refund or second refund cannot commit a partial batch`() {
        for (point in listOf(CounterFault.BEFORE_FIRST, CounterFault.AFTER_FIRST, CounterFault.AFTER_SECOND)) {
            withCounters { f, counters ->
                f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff)
                counters.seed(1)
                val before = counters.snapshot()
                var observedUpdates = -1
                var swallowed = false
                val faultJdbc = CounterFaultJdbc(f.pool, point) { updates ->
                    assertEquals(0L, f.jdbc.queryForObject("SELECT count(*) FROM admin_step_up_grants WHERE id = ?", Long::class.java, id(1)))
                    assertEquals(setOf(id(1)), f.grantIds())
                    assertEquals(before, counters.snapshot())
                    val selected = counters.snapshot(f.jdbc)
                    val moderation = requireNotNull(before["moderation_grants"])
                    val storage = requireNotNull(before["storage_bytes"])
                    assertEquals(moderation.actual - (if (updates >= 1) 1L else 0L), selected.getValue("moderation_grants").actual)
                    assertEquals(storage.actual - (if (updates >= 2) 16_384L else 0L), selected.getValue("storage_bytes").actual)
                    observedUpdates = updates
                }
                val real = JdbcComplaintGrantCleanupStore(f.jdbc, JdbcComplaintCapacityStore(faultJdbc, counters.syntheticPolicyDigest()))
                val swallowing = complaintPort { cutoff ->
                    assertThrows<PersistencePhaseException> { real.deleteEligibleComplaintGrantsAndRefund(cutoff) }
                    swallowed = true
                    0 // Swallowing a real late failure must not turn this into an empty successful batch.
                }

                assertRefused(f) { executor(f, swallowing).cleanupComplaintGrants() }

                assertEquals(point.completedUpdates, observedUpdates)
                assertTrue(swallowed, "The adapter itself must have returned a bounded refusal before the caller swallowed it.")
                assertEquals(setOf(id(1)), f.grantIds())
                assertEquals(before, counters.snapshot())
                assertEquals(1, executor(f, store(f, counters)).cleanupComplaintGrants())
                counters.assertRefund(before, 1)
            }
        }
    }

    @Test
    fun `actual zero row second counter update rolls back deletion first refund and the missing counter`() = withCounters { f, counters ->
        f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff)
        counters.seed(1)
        val before = counters.snapshot()
        var observed = false
        val faultJdbc = CounterFaultJdbc(f.pool, CounterFault.MISSING_SECOND) { updates ->
            assertEquals(1, updates)
            assertEquals(
                0L,
                f.jdbc.queryForObject("SELECT count(*) FROM complaint_capacity_counters WHERE name = 'storage_bytes'", Long::class.java),
            )
            assertEquals(0L, f.jdbc.queryForObject("SELECT count(*) FROM admin_step_up_grants WHERE id = ?", Long::class.java, id(1)))
            assertEquals(before, counters.snapshot())
            observed = true
        }
        val real = JdbcComplaintGrantCleanupStore(f.jdbc, JdbcComplaintCapacityStore(faultJdbc, counters.syntheticPolicyDigest()))

        assertRefused(f) { executor(f, real).cleanupComplaintGrants() }

        assertTrue(observed)
        assertEquals(setOf(id(1)), f.grantIds())
        assertEquals(before, counters.snapshot())
    }

    @Test
    fun `a retained row reclassified before its actual delete yields zero and cannot earn any refund`() = withCounters { f, counters ->
        f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff)
        counters.seed(1)
        val before = counters.snapshot()
        var observed = false
        val faultJdbc = object : JdbcTemplate(f.pool) {
            override fun update(sql: String, vararg args: Any?): Int {
                if (sql.startsWith("DELETE FROM admin_step_up_grants")) {
                    assertEquals(1, f.jdbc.update("UPDATE admin_step_up_grants SET scope = ? WHERE id = ?", SOURCE_ADMIN_MUTATION_SCOPE, id(1)))
                    val deleted = super.update(sql, *args)
                    assertEquals(0, deleted)
                    observed = true
                    return deleted
                }
                return super.update(sql, *args)
            }
        }.apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
        val real = JdbcComplaintGrantCleanupStore(faultJdbc, JdbcComplaintCapacityStore(f.jdbc, counters.syntheticPolicyDigest()))

        assertRefused(f) { executor(f, real).cleanupComplaintGrants() }

        assertTrue(observed)
        assertEquals(setOf(id(1)), f.grantIds())
        assertEquals(
            COMPLAINT_SCOPE,
            f.foreignTemplate().queryForObject("SELECT scope FROM admin_step_up_grants WHERE id = ?", String::class.java, id(1)),
        )
        assertEquals(before, counters.snapshot())
    }

    @Test
    fun `a port failure after complete real work rolls back both durable effects`() = withCounters { f, counters ->
        f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff)
        counters.seed(1)
        val before = counters.snapshot()
        val real = store(f, counters)
        var reached = false
        val port = complaintPort { cutoff ->
            assertEquals(1, real.deleteEligibleComplaintGrantsAndRefund(cutoff))
            assertEquals(setOf(id(1)), f.grantIds())
            assertEquals(before, counters.snapshot())
            reached = true
            throw SyntheticCleanupFailure()
        }

        assertRefused(f) { executor(f, port).cleanupComplaintGrants() }

        assertTrue(reached)
        assertEquals(setOf(id(1)), f.grantIds())
        assertEquals(before, counters.snapshot())
    }

    @Test
    fun `caller returned zero one or fifty cannot substitute for a genuine completed batch`() = withCounters { f, counters ->
        f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff)
        counters.seed(1)
        val before = counters.snapshot()

        for (count in listOf(0, 1, 50)) {
            assertRefused(f) { executor(f, complaintPort { count }).cleanupComplaintGrants() }
            assertEquals(setOf(id(1)), f.grantIds())
            assertEquals(before, counters.snapshot())
        }
    }

    @Test
    fun `a false count after genuine work is refused instead of changing its refund entitlement`() = withCounters { f, counters ->
        f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff)
        counters.seed(1)
        val before = counters.snapshot()
        val real = store(f, counters)
        val port = complaintPort { cutoff -> real.deleteEligibleComplaintGrantsAndRefund(cutoff) + 1 }

        assertRefused(f) { executor(f, port).cleanupComplaintGrants() }

        assertEquals(setOf(id(1)), f.grantIds())
        assertEquals(before, counters.snapshot())
    }

    @Test
    fun `direct commit cannot bypass the complaint batch terminal state`() = withCounters { f, counters ->
        val before = counters.snapshot()
        val phase = f.ownership.enterComplaintGrantCleanup()
        try {
            phase.begin()
            assertThrows<PersistencePhaseException> { phase.commit() }
        } finally {
            phase.finish()
        }

        assertRefused(f) { phase.result(0) }
        assertEquals(before, counters.snapshot())
    }

    @Test
    fun `swallowed repeated cleanup after deletion and refund poisons rather than committing the first call`() = withCounters { f, counters ->
        f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff)
        counters.seed(1)
        val before = counters.snapshot()
        val real = store(f, counters)
        var refused = false
        val port = complaintPort { cutoff ->
            val deleted = real.deleteEligibleComplaintGrantsAndRefund(cutoff)
            assertThrows<PersistencePhaseException> { real.deleteEligibleComplaintGrantsAndRefund(cutoff) }
            refused = true
            deleted
        }

        assertRefused(f) { executor(f, port).cleanupComplaintGrants() }

        assertTrue(refused)
        assertEquals(setOf(id(1)), f.grantIds())
        assertEquals(before, counters.snapshot())
    }

    @Test
    fun `source and complaint operations reject the other named path even if the caller catches the refusal`() = withCounters { f, counters ->
        f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff)
        f.seedGrant(id(2), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff)
        counters.seed(1)
        val before = counters.snapshot()
        val real = store(f, counters)
        var complaintRefused = false
        var sourceRefused = false
        val complaint = complaintPort { cutoff ->
            val deleted = real.deleteEligibleComplaintGrantsAndRefund(cutoff)
            assertThrows<PersistencePhaseException> { f.sourceStore.deleteEligibleSourceGrants(cutoff) }
            complaintRefused = true
            deleted
        }
        val source = object : SourceGrantCleanup {
            override fun deleteEligibleSourceGrants(cutoff: Instant): Int {
                val deleted = f.sourceStore.deleteEligibleSourceGrants(cutoff)
                assertThrows<PersistencePhaseException> { real.deleteEligibleComplaintGrantsAndRefund(cutoff) }
                sourceRefused = true
                return deleted
            }
        }

        assertRefused(f) { executor(f, complaint).cleanupComplaintGrants() }
        assertRefused(f) { f.newExecutor(source).cleanupSourceGrants() }

        assertTrue(complaintRefused && sourceRefused)
        assertEquals(setOf(id(1), id(2)), f.grantIds())
        assertEquals(before, counters.snapshot())
    }

    @Test
    fun `foreign grant or counter template cannot switch the selected holder`() = withCounters { f, counters ->
        f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff)
        counters.seed(1)
        val before = counters.snapshot()
        for (foreignGrant in listOf(true, false)) {
            val grants = if (foreignGrant) f.foreignTemplate() else f.jdbc
            val capacity = if (foreignGrant) f.jdbc else f.foreignTemplate()
            val real = JdbcComplaintGrantCleanupStore(grants, JdbcComplaintCapacityStore(capacity, counters.syntheticPolicyDigest()))

            assertRefused(f) { executor(f, real).cleanupComplaintGrants() }

            assertEquals(setOf(id(1)), f.grantIds())
            assertEquals(before, counters.snapshot())
        }
    }

    @Test
    fun `restoring a removed holder cannot heal a swallowed late complaint refusal`() = withCounters { f, counters ->
        f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff)
        counters.seed(1)
        val before = counters.snapshot()
        val real = store(f, counters)
        var refused = false
        val port = complaintPort { cutoff ->
            val deleted = real.deleteEligibleComplaintGrantsAndRefund(cutoff)
            val holder = TransactionSynchronizationManager.unbindResource(f.pool)
            try {
                assertThrows<PersistencePhaseException> { real.deleteEligibleComplaintGrantsAndRefund(cutoff) }
                refused = true
            } finally {
                TransactionSynchronizationManager.bindResource(f.pool, holder)
            }
            deleted
        }

        assertRefused(f) { executor(f, port).cleanupComplaintGrants() }

        assertTrue(refused)
        assertEquals(setOf(id(1)), f.grantIds())
        assertEquals(before, counters.snapshot())
    }

    @Test
    fun `stale genuine batch use poisons the new owner without undoing an earlier known commit`() = withCounters { f, counters ->
        f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff)
        counters.seed(1)
        val capacity = JdbcComplaintCapacityStore(f.jdbc, counters.syntheticPolicyDigest())
        lateinit var old: ComplaintGrantCleanupBatch
        val first = complaintPort { cutoff ->
            old = ComplaintGrantCleanupBatch.lock(f.jdbc, cutoff)
            old.deleteAndRefund(capacity)
        }
        assertEquals(1, executor(f, first).cleanupComplaintGrants())
        assertTrue(f.grantIds().isEmpty())
        f.seedGrant(id(2), COMPLAINT_SCOPE, f.cutoff)
        counters.seed(1)
        val before = counters.snapshot()
        var refused = false
        val stale = complaintPort {
            assertThrows<PersistencePhaseException> { old.deleteAndRefund(capacity) }
            refused = true
            0
        }

        assertRefused(f) { executor(f, stale).cleanupComplaintGrants() }

        assertTrue(refused)
        assertEquals(setOf(id(2)), f.grantIds())
        assertEquals(before, counters.snapshot())
    }

    @Test
    fun `counter locking cannot be called early or replayed after a genuine batch completes`() {
        for (early in listOf(true, false)) {
            withCounters { f, counters ->
                f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff)
                counters.seed(1)
                val before = counters.snapshot()
                val capacity = JdbcComplaintCapacityStore(f.jdbc, counters.syntheticPolicyDigest())
                var refused = false
                val port = complaintPort { cutoff ->
                    val batch = ComplaintGrantCleanupBatch.lock(f.jdbc, cutoff)
                    val deleted = if (early) 0 else batch.deleteAndRefund(capacity)
                    assertThrows<PersistencePhaseException> { capacity.lockForComplaintGrantCleanup(batch) }
                    refused = true
                    deleted
                }

                assertRefused(f) { executor(f, port).cleanupComplaintGrants() }

                assertTrue(refused)
                assertEquals(setOf(id(1)), f.grantIds())
                assertEquals(before, counters.snapshot())
            }
        }
    }

    @Test
    fun `a caught nested source entry after complaint work cannot commit its completed batch`() = withCounters { f, counters ->
        f.seedGrant(id(1), COMPLAINT_SCOPE, f.cutoff)
        counters.seed(1)
        val before = counters.snapshot()
        val real = store(f, counters)
        var refused = false
        val port = complaintPort { cutoff ->
            val deleted = real.deleteEligibleComplaintGrantsAndRefund(cutoff)
            assertThrows<PersistencePhaseException> { f.newExecutor().cleanupSourceGrants() }
            refused = true
            deleted
        }

        assertRefused(f) { executor(f, port).cleanupComplaintGrants() }

        assertTrue(refused)
        assertEquals(setOf(id(1)), f.grantIds())
        assertEquals(before, counters.snapshot())
    }

    private fun withCounters(test: (OrdinarySourceGrantCleanupFixture, SyntheticComplaintCounters) -> Unit) {
        withOrdinarySourceGrantCleanup(database.value, maximumPoolSize = 2) { f ->
            assertEquals(2, (lifecycleField(f.pool, "pool") as HikariDataSource).maximumPoolSize)
            assertEquals(1, f.admission.ownerLimit)
            SyntheticComplaintCounters(f.foreignTemplate(), f.cutoff).use { counters -> test(f, counters) }
        }
    }

    private fun store(
        f: OrdinarySourceGrantCleanupFixture,
        counters: SyntheticComplaintCounters,
        expected: ByteArray? = counters.syntheticPolicyDigest(),
    ): JdbcComplaintGrantCleanupStore = JdbcComplaintGrantCleanupStore(f.jdbc, JdbcComplaintCapacityStore(f.jdbc, expected))

    private fun executor(
        f: OrdinarySourceGrantCleanupFixture,
        port: ComplaintGrantCleanup,
        clock: Clock = Clock.fixed(f.cutoff, ZoneOffset.UTC),
    ): ComplaintGrantCleanupPhaseExecutor = ComplaintGrantCleanupPhaseExecutor(f.ownership, port, clock)

    private fun complaintPort(work: (Instant) -> Int): ComplaintGrantCleanup = object : ComplaintGrantCleanup {
        override fun deleteEligibleComplaintGrantsAndRefund(cutoff: Instant): Int = work(cutoff)
    }

    private fun assertRefused(f: OrdinarySourceGrantCleanupFixture, work: () -> Unit) {
        val failure = assertThrows<PersistencePhaseException> { work() }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        assertEquals(0, f.admission.activeOwners())
        requireConnectionFree()
    }

    private fun transactionIdentity(jdbc: JdbcTemplate): Pair<Int, Long> = jdbc.query(
        "SELECT pg_backend_pid(), txid_current()",
        { result, _ -> result.getInt(1) to result.getLong(2) },
    ).single()

    private fun id(value: Long): UUID = UUID(0, value)

    private companion object {
        const val COMPLAINT_SCOPE = "complaint-moderation-mutation"
    }
}

/** Passive instance-TL observation for real ordinary SQL failures; never changes native/call/outcome state. */
private class CounterLockOutcomeObservation(private val phase: PersistencePhaseContext, val lease: PersistenceJdbcLease) :
    ThreadLocal<PersistenceJdbcGuardCall?>(),
    AutoCloseable {
    private val caller = Thread.currentThread()
    private val context = lease.state.context
    private val owner = ownedCutField(lease, "ownership") as PersistenceOwnership
    private val sealed = ownedCutField(owner, "terminalSealed") as AtomicBoolean
    private val frames = context.javaClass.getDeclaredField("frames").apply { check(trySetAccessible()) }

    @Suppress("UNCHECKED_CAST")
    private val delegate = frames.get(context) as ThreadLocal<PersistenceJdbcGuardCall?>
    private val driver = PersistenceJdbcGuardCall::class.java.getDeclaredField("driver").apply { check(trySetAccessible()) }
    private var query: PersistenceJdbcGuardCall? = null
    private var queryNative: PersistencePgOwnedCutAccess.Invocation? = null
    private var rollbackCall: PersistenceJdbcGuardCall? = null
    private var rollback: PersistencePgOwnedCutAccess.Invocation? = null
    private var rollbackArmed = false
    private var rollbackReadCap = false
    var failed = false
        private set

    init {
        check(delegate.get() == null)
        frames.set(context, this)
    }

    override fun get(): PersistenceJdbcGuardCall? = delegate.get().also { call ->
        if (Thread.currentThread() === caller && call != null) {
            try {
                val native = driver.get(call) as? PersistencePgOwnedCutAccess.Invocation
                when (native?.let { ownedCutField(it.cell, "operation") }) {
                    "executeQuery" -> {
                        query = call
                        queryNative = native
                    }

                    "rollback" -> {
                        rollbackCall = call
                        rollback = native
                        rollbackArmed = rollbackArmed || ownedCutField(requireNotNull(native).cell, "armed") == true
                    }

                    "setNetworkTimeout" -> if (ownedCutField(phase, "stage").toString() == "ROLLING_BACK") rollbackReadCap = true

                    else -> Unit
                }
            } catch (_: Throwable) {
                failed = true // An observation failure never substitutes for the genuine SQL/cleanup failure.
            }
        }
    }

    override fun set(value: PersistenceJdbcGuardCall?) = delegate.set(value)
    override fun remove() = delegate.remove()

    fun snapshot(): Map<String, Any?> = try {
        val status = ownedCutField(phase, "rootStatus") as? PersistenceManagedStatus
        val rollbackDue = status?.hasReturnedStatus() == true && !status.isCompleted &&
            ownedCutField(phase, "beginEnded") == true && ownedCutField(phase, "completionActive") == false
        val queryEnded = query?.let { ownedCutField(it, "finishReturned") } == true &&
            queryNative?.let { ownedCutField(it.cell, "disarmed") == true && ownedCutField(it.cell, "ended") == true } == true
        val returned = ownedCutField(lease, "transfer") as? PersistenceJdbcPoolTransfer
        mapOf(
            "db" to lease.completion.databaseOutcome(), "poison" to lease.state.epoch.poisoned(), "retire" to owner.retirementRequested(),
            "sealed" to sealed.get(), "graphFailed" to context.graphFailed(), "query" to query?.let { ownedCutField(it, "outcome") },
            "producerSealed" to ownedCutField(creatorEpochState(lease), "sealed"),
            "queryEnded" to queryEnded, "queryArmed" to queryNative?.let { ownedCutField(it.cell, "armed") },
            "queryPreparationFailed" to query?.let { ownedCutField(it, "driverPreparationFailure") },
            "queryExact" to (query != null && queryNative?.callKey === query && queryNative?.owner?.root === ownedPoolRoot(lease)),
            "rollbackDue" to rollbackDue, "completionEnd" to ownedCutField(phase, "completionEnded"), "rbCapNative" to rollbackReadCap,
            "rbPrepared" to (rollback != null), "rbArmed" to rollbackArmed, "rbEnd" to rollback?.let { ownedCutField(it.cell, "ended") },
            "rbDisarmed" to rollback?.let { ownedCutField(it.cell, "disarmed") },
            "rbGuardEnded" to rollbackCall?.let { ownedCutField(it, "finishReturned") },
            "rbOutcome" to rollbackCall?.let { ownedCutField(it, "outcome") }, "rbKind" to rollbackCall?.let { ownedCutField(it, "kind") },
            "rbPreparationFailed" to rollbackCall?.let { ownedCutField(it, "driverPreparationFailure") },
            "rbExact" to (rollbackCall != null && rollback?.callKey === rollbackCall && rollback?.owner?.root === ownedPoolRoot(lease)),
            "returnPrepared" to (returned != null), "returnExact" to (returned?.source === lease.state),
            "returnConsented" to (returned?.consented() == true), "returnEnded" to (returned?.actualEnded() == true), "observerFailed" to failed,
        )
    } catch (_: Throwable) {
        failed = true
        mapOf("observerFailed" to true)
    }

    /** Capture assertions against the two frozen boundaries, not later races or diagnostic-string parsing. */
    fun rollbackChecks(work: Map<String, Any?>, finished: Map<String, Any?>): List<Executable> {
        val before = mapOf(
            "db" to PersistenceDatabaseOutcome.NONE, "poison" to false, "retire" to false, "sealed" to false, "graphFailed" to false,
            "producerSealed" to false,
            "query" to PersistenceJdbcCallOutcome.ORDINARY_FAILURE, "queryEnded" to true, "queryArmed" to true,
            "queryPreparationFailed" to false, "queryExact" to true, "rollbackDue" to true, "completionEnd" to false,
            "rbCapNative" to false, "rbPrepared" to false, "rbArmed" to false, "returnPrepared" to false, "observerFailed" to false,
        )
        val after = mapOf(
            "db" to PersistenceDatabaseOutcome.ROLLED_BACK, "poison" to false, "retire" to true, "sealed" to true, "graphFailed" to false,
            "producerSealed" to true,
            "rollbackDue" to false, "completionEnd" to true, "rbCapNative" to true, "rbPrepared" to true, "rbArmed" to true,
            "rbEnd" to true, "rbDisarmed" to true, "rbGuardEnded" to true, "rbOutcome" to PersistenceJdbcCallOutcome.RETURNED,
            "rbKind" to PersistenceJdbcGuardCallKind.CLEANUP, "rbPreparationFailed" to false, "rbExact" to true,
            "returnPrepared" to true, "returnExact" to true, "returnConsented" to false, "returnEnded" to true, "observerFailed" to false,
        )
        return before.map { (key, expected) -> Executable { assertEquals(expected, work[key], "work.$key") } } +
            after.map { (key, expected) -> Executable { assertEquals(expected, finished[key], "final.$key") } }
    }

    override fun close() {
        check(frames.get(context) === this)
        frames.set(context, delegate)
        check(delegate.get() == null)
    }
}

/** Only test-local synthetic state. Never a producer of production policy authority. All original22 rows are restored in finally. */
internal class SyntheticComplaintCounters(private val jdbc: JdbcTemplate, private val at: Instant) : AutoCloseable {
    private val original = requireNotNull(
        jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(c) ORDER BY name)::text FROM complaint_capacity_counters c", String::class.java),
    )

    fun syntheticPolicyDigest(): ByteArray = ByteArray(32) { (it + 1).toByte() }

    fun seed(grants: Long, storageActual: Long = grants * 16_384 + 97, closed: Boolean = true) {
        for (counter in ComplaintCapacityEncoding.lockOrder()) {
            val actual = when (counter) {
                ComplaintCapacityCounter.MODERATION_GRANTS -> grants
                ComplaintCapacityCounter.STORAGE_BYTES -> storageActual
                else -> counter.storedOrdinal.toLong() + 17
            }
            val installation = counter === ComplaintCapacityCounter.INSTALLATION_IDS
            assertEquals(
                1,
                jdbc.update(
                    "UPDATE complaint_capacity_counters SET configuration_hash = ?, configuration_closed = ?, hard_limit = 10000000, " +
                        "creation_limit = 9000000, free_units = ?, actual_units = ?, recovery_reserved_units = 7, test_reserved_units = 11, " +
                        "admission_utc_date = ?, admission_count = ?, admission_daily_limit = ?, updated_at = ? WHERE name = ?",
                    syntheticPolicyDigest(), closed, 10_000_000L - actual - 18, actual,
                    if (installation) Date.valueOf("2026-09-11") else null,
                    if (installation) 7L else null,
                    if (installation) 100L else null,
                    Timestamp.from(at), counter.storedName,
                ),
            )
        }
    }

    fun snapshot(selected: JdbcTemplate = jdbc): Map<String, CounterSnapshot> = selected.query(
        "SELECT name, to_jsonb(c)::text, (to_jsonb(c) - ARRAY['free_units','actual_units','updated_at'])::text, " +
            "free_units, actual_units FROM complaint_capacity_counters c ORDER BY name",
        { result, _ ->
            result.getString(1) to CounterSnapshot(result.getString(2), result.getString(3), result.getLong(4), result.getLong(5))
        },
    ).toMap()

    fun assertRefund(before: Map<String, CounterSnapshot>, removed: Int) {
        val after = snapshot()
        assertEquals(before.keys, after.keys)
        for ((name, old) in before) {
            val current = after.getValue(name)
            val units = when (name) {
                "moderation_grants" -> removed.toLong()
                "storage_bytes" -> removed * 16_384L
                else -> 0L
            }
            if (units == 0L) {
                assertEquals(old, current)
            } else {
                assertEquals(old.preserved, current.preserved)
                assertEquals(old.free + units, current.free)
                assertEquals(old.actual - units, current.actual)
            }
        }
    }

    fun lockLastCounter(): AutoCloseable {
        val connection = requireNotNull(jdbc.dataSource).connection
        try {
            connection.autoCommit = false
            connection.prepareStatement("SELECT name FROM complaint_capacity_counters WHERE name = 'test_runs' FOR UPDATE").use { statement ->
                statement.queryTimeout = 2
                statement.executeQuery().use { result -> check(result.next() && result.getString(1) == "test_runs" && !result.next()) }
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

    override fun close() {
        requireConnectionFree()
        requireNotNull(jdbc.dataSource).connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM complaint_capacity_counters").use { it.executeUpdate() }
                connection.prepareStatement(
                    "INSERT INTO complaint_capacity_counters SELECT * FROM jsonb_populate_recordset(NULL::complaint_capacity_counters, ?::jsonb)",
                ).use { statement ->
                    statement.setString(1, original)
                    assertEquals(ComplaintCapacityEncoding.WIDTH, statement.executeUpdate())
                }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }
}

internal data class CounterSnapshot(val full: String, val preserved: String, val free: Long, val actual: Long)

private enum class CounterFault(val completedUpdates: Int) { BEFORE_FIRST(0), AFTER_FIRST(1), AFTER_SECOND(2), MISSING_SECOND(1) }

/** Faults are above real JdbcTemplate SQL: deletes/updates and the actual zero row result are never mocked. */
private class CounterFaultJdbc(dataSource: DataSource, private val point: CounterFault, private val observe: (Int) -> Unit) : JdbcTemplate(dataSource) {
    private var calls = 0
    private var completedUpdates = 0

    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    override fun update(sql: String, vararg args: Any?): Int {
        if (!sql.startsWith("UPDATE complaint_capacity_counters")) return super.update(sql, *args)
        calls++
        if (point === CounterFault.BEFORE_FIRST && calls == 1) failAfterObservation()
        if (point === CounterFault.MISSING_SECOND && calls == 2) {
            assertEquals(1, super.update("DELETE FROM complaint_capacity_counters WHERE name = 'storage_bytes'"))
        }
        val changed = super.update(sql, *args)
        if (changed == 1) completedUpdates++
        val failAfterUpdate = when (point) {
            CounterFault.AFTER_FIRST -> calls == 1
            CounterFault.AFTER_SECOND -> calls == 2
            else -> false
        }
        if (failAfterUpdate) {
            failAfterObservation()
        }
        if (point === CounterFault.MISSING_SECOND && calls == 2) {
            assertEquals(0, changed)
            observe(completedUpdates)
        }
        return changed
    }

    private fun failAfterObservation(): Nothing {
        observe(completedUpdates)
        throw SyntheticCleanupFailure()
    }
}

private class SyntheticCleanupFailure : RuntimeException("Synthetic failure after actual cleanup SQL.")

private class CleanupCountingClock(private val first: Instant, val samples: AtomicInteger = AtomicInteger(), private val zone: ZoneId = ZoneOffset.UTC) :
    Clock() {
    override fun instant(): Instant = if (samples.getAndIncrement() == 0) first else first.plusSeconds(3_600)

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = CleanupCountingClock(first, samples, zone)
}
