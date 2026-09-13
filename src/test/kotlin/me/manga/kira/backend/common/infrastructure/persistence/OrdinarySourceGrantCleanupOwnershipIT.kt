package me.manga.kira.backend.common.infrastructure.persistence

import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.EntityTransaction
import me.manga.kira.backend.complaint.infrastructure.transaction.OrdinaryPersistencePhaseExecutor
import me.manga.kira.backend.security.AdminStepUpService.Companion.SOURCE_ADMIN_MUTATION_SCOPE
import me.manga.kira.backend.security.JdbcSourceGrantCleanupStore
import me.manga.kira.backend.security.SourceGrantCleanup
import org.hibernate.engine.jdbc.spi.JdbcCoordinator
import org.hibernate.engine.spi.SessionImplementor
import org.hibernate.resource.jdbc.spi.LogicalConnectionImplementor
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.orm.jpa.EntityManagerFactoryInfo
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.vendor.HibernateJpaDialect
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionExecutionListener
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Connected oracles only; the existing fixture still owns PG, stock Hikari, driver and teardown. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OrdinarySourceGrantCleanupOwnershipIT {
    private val database = lazy { PgLifecycleDatabaseFixture().also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `ordinary JPA required join shares exact holder lease PID and transaction without completing the outer owner`() =
        withOrdinarySourceGrantCleanup(database.value) { f ->
            val id = UUID(0, 101)
            f.seedGrant(id, SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff)
            var before: Pair<Int, Long>? = null
            var joined: Pair<Int, Long>? = null
            var selected: PersistenceJdbcLease? = null
            var heldAfterJoin = false
            var invisibleAfterDelete = false
            val store = cleanupPort { cutoff ->
                val holder = selectedHolder(f)
                selected = ownedPoolLease(holder.connection)
                before = transactionIdentity(f)
                val status = f.manager.getTransaction(DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRED))
                joined = transactionIdentity(f)
                check(!status.isNewTransaction && !status.hasSavepoint())
                check(selectedHolder(f) === holder && ownedPoolLease(holder.connection) === selected)
                f.manager.commit(status)
                heldAfterJoin = f.admission.activeOwners() == 1 && !requireNotNull(selected).completion.quiescent() &&
                    f.ownedPool.lifecycle.actorSnapshot().futureLeaseEntries == 1L &&
                    requireNotNull(selected).state.context.transaction.databaseOutcome() === PersistenceDatabaseOutcome.NONE
                f.sourceStore.deleteEligibleSourceGrants(cutoff).also { invisibleAfterDelete = f.grantIds() == setOf(id) }
            }

            assertEquals(1, f.newExecutor(store).cleanupSourceGrants())
            assertEquals(before, joined)
            assertTrue(requireNotNull(before).first > 0 && requireNotNull(before).second > 0)
            assertTrue(heldAfterJoin && invisibleAfterDelete)
            assertTrue(f.grantIds().isEmpty())
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, requireNotNull(selected).completion.databaseOutcome())
            assertTrue(requireNotNull(selected).completion.quiescent())
            assertEquals(0, f.admission.activeOwners())
            requireConnectionFree()
        }

    @Test
    fun `wrong dispatch propagation resource and both extra borrow overloads refuse before suspension or checkout`() =
        withOrdinarySourceGrantCleanup(database.value) { f ->
            var refusals = 0
            var suspended = 0
            var positiveJoin = false
            val store = cleanupPort { cutoff ->
                val holder = selectedHolder(f)
                val lease = ownedPoolLease(holder.connection)
                val epoch = lease.state.epoch
                val futures = f.ownedPool.lifecycle.actorSnapshot().futureLeaseEntries
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun suspend() {
                        suspended++
                    }
                })
                val forbidden = listOf(
                    TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                    TransactionDefinition.PROPAGATION_NESTED,
                    TransactionDefinition.PROPAGATION_NOT_SUPPORTED,
                )
                forbidden.forEach { propagation ->
                    assertThrows<PersistencePhaseException> { f.manager.getTransaction(DefaultTransactionDefinition(propagation)) }
                    refusals++
                }
                assertThrows<PersistencePhaseException> { GuardedJpaTransactionManager(f.entityManagerFactory, f.pool).getTransaction(null) }
                assertThrows<PersistencePhaseException> { GuardedJdbcTransactionManager(f.pool).getTransaction(null) }
                assertThrows<PersistencePhaseException> { f.pool.connection }
                assertThrows<PersistencePhaseException> {
                    f.pool.getConnection(PgLifecycleDatabaseSettings.CANDIDATE, PgLifecycleDatabaseSettings.CANDIDATE_PASSWORD)
                }
                assertThrows<PersistencePhaseException> { f.newExecutor().cleanupSourceGrants() }
                assertThrows<PersistencePhaseException> { JdbcSourceGrantCleanupStore(f.foreignTemplate()).deleteEligibleSourceGrants(cutoff) }
                refusals += 6
                check(selectedHolder(f) === holder && ownedPoolLease(holder.connection).state.epoch === epoch)
                check(f.ownedPool.lifecycle.activeAcquisitions() == 0L && f.ownedPool.lifecycle.actorSnapshot().futureLeaseEntries == futures)
                val status = f.manager.getTransaction(null)
                positiveJoin = !status.isNewTransaction && transactionIdentity(f).first > 0
                f.manager.commit(status)
                f.sourceStore.deleteEligibleSourceGrants(cutoff)
            }

            assertEquals(0, f.newExecutor(store).cleanupSourceGrants())
            assertEquals(9, refusals)
            assertEquals(0, suspended)
            assertTrue(positiveJoin)
            assertNull(f.admission.tryComplaintBoundary(), "The ordinary size-one source floor is not complaint capacity.")
            assertEquals(0, f.admission.activeOwners())
        }

    @Test
    fun `ambient synchronization holders actual transaction and an unbound ordinary loan reject named entry`() =
        withOrdinarySourceGrantCleanup(database.value) { f ->
            assertEquals(0, f.newExecutor().cleanupSourceGrants()) // Live profile/credentials positive control.
            assertThrows<PersistencePhaseException> { f.sourceStore.deleteEligibleSourceGrants(f.cutoff) }
            TransactionSynchronizationManager.initSynchronization()
            try {
                assertThrows<PersistencePhaseException> { f.newExecutor().cleanupSourceGrants() }
                assertEquals(0, f.admission.activeOwners())
            } finally {
                TransactionSynchronizationManager.clearSynchronization()
            }
            for (key in listOf(f.pool, f.entityManagerFactory)) {
                TransactionSynchronizationManager.bindResource(key, Any()) // Negative fixture only, never an accepted holder.
                try {
                    assertThrows<PersistencePhaseException> { f.newExecutor().cleanupSourceGrants() }
                } finally {
                    TransactionSynchronizationManager.unbindResource(key)
                }
            }
            val ordinary = f.manager.getTransaction(null)
            try {
                val holder = selectedHolder(f)
                val lease = ownedPoolLease(holder.connection)
                assertThrows<PersistencePhaseException> { f.newExecutor().cleanupSourceGrants() }
                assertSame(lease, ownedPoolLease(selectedHolder(f).connection))
                assertEquals(0, f.admission.activeOwners())
            } finally {
                f.manager.rollback(ordinary)
            }
            f.pool.connection.use { connection ->
                val lease = ownedPoolLease(connection)
                assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
                assertThrows<PersistencePhaseException> { requireConnectionFree() }
                assertThrows<PersistencePhaseException> { f.newExecutor().cleanupSourceGrants() }
                assertFalse(lease.completion.quiescent())
                assertEquals(13, ownedPoolScalar(connection, "SELECT 13"))
            }
            requireConnectionFree()
            assertEquals(0, f.newExecutor().cleanupSourceGrants())
        }

    @Test
    fun `missing or foreign selected holder cannot authorize cleanup even with true transaction flags`() = withOrdinarySourceGrantCleanup(database.value) { f ->
        var refusals = 0
        val store = cleanupPort { cutoff ->
            val holder = TransactionSynchronizationManager.unbindResource(f.pool)
            try {
                assertThrows<PersistencePhaseException> { f.sourceStore.deleteEligibleSourceGrants(cutoff) }
                refusals++
                TransactionSynchronizationManager.bindResource(f.pool, ConnectionHolder(foreignConnectionSentinel()))
                try {
                    assertThrows<PersistencePhaseException> { f.sourceStore.deleteEligibleSourceGrants(cutoff) }
                    refusals++
                } finally {
                    TransactionSynchronizationManager.unbindResource(f.pool)
                }
            } finally {
                TransactionSynchronizationManager.bindResource(f.pool, holder)
            }
            f.sourceStore.deleteEligibleSourceGrants(cutoff)
        }
        assertEquals(0, f.newExecutor(store).cleanupSourceGrants())
        assertEquals(2, refusals)
    }

    @Test
    fun `wrapped unscoped JPA commits and releases immediately without acquiring short phase ownership`() =
        withOrdinarySourceGrantCleanup(database.value) { f ->
            val id = UUID(0, 102)
            f.seedGrant(id, SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff)
            val status = f.manager.getTransaction(DefaultTransactionDefinition().apply { setName("ordinary-positive-control") })
            val lease = ownedPoolLease(selectedHolder(f).connection)
            assertEquals("ordinary-positive-control", status.transactionName)
            assertNull(PersistencePhaseOwnership.current())
            assertEquals(0, f.admission.activeOwners())
            assertEquals(1, f.jdbc.update("DELETE FROM admin_step_up_grants WHERE id = ?", id))
            f.manager.commit(status)

            assertTrue(lease.completion.quiescent())
            assertTrue(f.grantIds().isEmpty())
            assertEquals(0L, f.ownedPool.lifecycle.actorSnapshot().futureLeaseEntries)
            requireConnectionFree()
        }

    @Test
    fun `foreign same endpoint or unprovable EMF metadata refuses before additional creation begin or either checkout`() =
        withOrdinarySourceGrantCleanup(database.value) { f ->
            val selected = CleanupFactoryObservation(f.entityManagerFactory)
            val manager = GuardedJpaTransactionManager(selected.factory, f.pool)
            val ownership = PersistencePhaseOwnership(f.admission, manager)
            val executor = OrdinaryPersistencePhaseExecutor(ownership, f.sourceStore, Clock.fixed(f.cutoff, ZoneOffset.UTC))
            OrdinaryAcquisitionObservation(f.ownedPool.lifecycle).use { acquisitions ->
                assertEquals(0, executor.cleanupSourceGrants()) // Real exact-pair begin/commit, calibrating both observations.
                assertEquals(1L to 1L, selected.snapshot())
                assertEquals(1, acquisitions.entries)
                f.withForeignFactory { foreignFactory, foreignCheckouts ->
                    val foreign = CleanupFactoryObservation(foreignFactory)
                    val entity = foreign.factory.createEntityManager()
                    try {
                        val transaction = entity.transaction
                        try {
                            transaction.begin()
                            assertEquals(23, (entity.createNativeQuery("SELECT 23").singleResult as Number).toInt())
                        } finally {
                            if (transaction.isActive) transaction.rollback()
                        }
                    } finally {
                        entity.close()
                    }
                    assertEquals(1L to 1L, foreign.snapshot()) // Not an inert factory, bad credentials or UNKNOWN profile.
                    assertTrue(foreignCheckouts() > 0)
                    assertNotSame(f.pool, (foreign.factory as EntityManagerFactoryInfo).dataSource)
                    requireConnectionFree()
                    val selectedBefore = selected.snapshot()
                    val foreignBefore = foreign.snapshot()
                    val selectedCheckoutsBefore = acquisitions.entries
                    val foreignCheckoutsBefore = foreignCheckouts()

                    val mismatch = assertThrows<PersistencePhaseException> { GuardedJpaTransactionManager(foreign.factory, f.pool) }
                    val unknown = assertThrows<PersistencePhaseException> {
                        GuardedJpaTransactionManager(cleanupFactoryWithoutMetadata(selected.factory), f.pool)
                    }
                    val absent = assertThrows<PersistencePhaseException> {
                        GuardedJpaTransactionManager(cleanupFactoryWithoutDataSource(selected.factory), f.pool)
                    }
                    assertEquals(PersistencePhaseFailureCode.RESOURCE_REFUSED, mismatch.code)
                    assertEquals(PersistencePhaseFailureCode.RESOURCE_REFUSED, unknown.code)
                    assertEquals(PersistencePhaseFailureCode.RESOURCE_REFUSED, absent.code)
                    assertEquals(selectedBefore, selected.snapshot())
                    assertEquals(foreignBefore, foreign.snapshot())
                    assertEquals(selectedCheckoutsBefore, acquisitions.entries)
                    assertEquals(foreignCheckoutsBefore, foreignCheckouts())
                    assertEquals(0, f.admission.activeOwners())
                    requireConnectionFree()
                }
            }
        }

    @Test
    fun `private JPA composition refuses execution listener configuration before any begin or lease`() = withOrdinarySourceGrantCleanup(database.value) { f ->
        val delegate = ownedCutField(f.manager, "delegate") as JpaTransactionManager
        assertSame(f.entityManagerFactory, delegate.entityManagerFactory)
        assertSame(f.pool, delegate.dataSource)
        assertEquals(HibernateJpaDialect::class.java, delegate.jpaDialect.javaClass)
        assertNotSame((f.entityManagerFactory as EntityManagerFactoryInfo).jpaDialect, delegate.jpaDialect)
        delegate.setTransactionExecutionListeners(listOf(object : TransactionExecutionListener {}))
        try {
            assertThrows<PersistencePhaseException> { f.newExecutor().cleanupSourceGrants() }
            assertEquals(0, f.admission.activeOwners())
            assertEquals(0L, f.ownedPool.lifecycle.activeAcquisitions())
            assertEquals(0L, f.ownedPool.lifecycle.actorSnapshot().futureLeaseEntries)
        } finally {
            delegate.setTransactionExecutionListeners(emptyList())
        }
        assertEquals(0, f.newExecutor().cleanupSourceGrants())
    }

    @Test
    fun `accepted real lease survives failed begin with no returned status until actual rollback close and receipt`() =
        withOrdinarySourceGrantCleanup(database.value) { f ->
            val beginReturned = AtomicBoolean()
            val body = AtomicBoolean()
            val retained = AtomicReference<PersistencePhaseContext>()
            val factory = failAfterRealBegin(f.entityManagerFactory) {
                retained.set(requireNotNull(PersistencePhaseOwnership.current()))
                beginReturned.set(true)
            }
            val manager = GuardedJpaTransactionManager(factory, f.pool)
            val ownership = PersistencePhaseOwnership(f.admission, manager)
            val executor = OrdinaryPersistencePhaseExecutor(
                ownership,
                cleanupPort {
                    body.set(true)
                    0
                },
                Clock.fixed(f.cutoff, ZoneOffset.UTC),
            )
            val failure = assertThrows<PersistencePhaseException> { executor.cleanupSourceGrants() }
            val phase = requireNotNull(retained.get())
            val status = ownedCutField(phase, "rootStatus") as PersistenceManagedStatus
            val receipt = ownedCutField(phase, "acquisition") as PersistenceLeaseCompletion

            assertTrue(beginReturned.get())
            assertFalse(body.get() || status.hasReturnedStatus())
            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
            assertTrue(failure.cleanupProven && receipt.quiescent())
            assertFalse((ownedCutField(phase, "createdEntityManager") as EntityManager).isOpen)
            assertEquals(0, f.admission.activeOwners())
            requireConnectionFree()
            assertEquals(0, f.newExecutor().cleanupSourceGrants())
        }

    @Test
    fun `post status selected holder materialization failure rolls back the retained real transaction without fallback borrowing`() =
        withOrdinarySourceGrantCleanup(database.value) { f ->
            var body = false
            val fault = PostBeginHolderFailure(f.entityManagerFactory)
            val manager = GuardedJpaTransactionManager(fault.factory, f.pool)
            val ownership = PersistencePhaseOwnership(f.admission, manager)
            val executor = OrdinaryPersistencePhaseExecutor(
                ownership,
                cleanupPort {
                    body = true
                    0
                },
                Clock.fixed(f.cutoff, ZoneOffset.UTC),
            )
            val failure = assertThrows<PersistencePhaseException> {
                executor.cleanupSourceGrants()
            }
            val phase = requireNotNull(fault.phase)
            val status = ownedCutField(phase, "rootStatus") as PersistenceManagedStatus
            val receipt = ownedCutField(phase, "acquisition") as PersistenceLeaseCompletion
            assertTrue(fault.injected && status.hasReturnedStatus() && status.isCompleted)
            assertFalse(body)
            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
            assertTrue(failure.cleanupProven && receipt.quiescent())
            assertEquals(0L, f.ownedPool.lifecycle.activeAcquisitions())
            assertEquals(0L, f.ownedPool.lifecycle.actorSnapshot().futureLeaseEntries)
            requireConnectionFree()
            assertEquals(0, f.newExecutor().cleanupSourceGrants())
        }

    @Test
    fun `an accepted but undelivered late acquisition never enters the body and retains custody through its real tail`() =
        withOrdinarySourceGrantCleanup(database.value) { f ->
            assertEquals(0, f.newExecutor().cleanupSourceGrants())
            val barrier = AtomicReference<OrdinaryAcquisitionTailBarrier>()
            val ready = CountDownLatch(1)
            val body = AtomicBoolean()
            val outcome = AtomicReference<Throwable?>()
            val thread = Thread.ofPlatform().unstarted {
                val tail = OrdinaryAcquisitionTailBarrier()
                barrier.set(tail)
                ready.countDown()
                try {
                    f.newExecutor(
                        cleanupPort {
                            body.set(true)
                            0
                        },
                    ).cleanupSourceGrants()
                } catch (failure: Throwable) {
                    outcome.set(failure)
                }
            }
            thread.start()
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                val tail = requireNotNull(barrier.get())
                tail.awaitEntered()
                val phase = requireNotNull(tail.phase)
                val receipt = ownedCutField(phase, "acquisition") as PersistenceLeaseCompletion
                val lease = ownedCutField(phase, "lease") as PersistenceJdbcLease
                assertTrue(receipt.matches(lease)) // Real checkout consent, but no returned connection/TransactionStatus.
                assertFalse(body.get() || receipt.quiescent())
                assertEquals(1, f.admission.activeOwners())
                assertEquals(1L, f.ownedPool.lifecycle.activeAcquisitions())
                Thread.sleep(2_050)
                assertFalse(body.get() || receipt.quiescent())
                assertEquals(1, f.admission.activeOwners())
                tail.release()
                thread.join(5_000)
                assertFalse(thread.isAlive)
                val failure = outcome.get() as PersistencePhaseException
                assertFalse(body.get() || tail.timedOut.get())
                assertEquals(PersistenceDatabaseOutcome.NONE, failure.databaseOutcome)
                assertTrue(failure.cleanupProven && receipt.quiescent())
                assertEquals(0, f.admission.activeOwners())
                // The accepted-but-undelivered handoff may seal this pool; there is deliberately no retry/reroute.
            } finally {
                barrier.get()?.release()
                thread.join(6_000)
                check(!thread.isAlive)
                barrier.get()?.close()
            }
        }

    @Test
    fun `positive remaining caps and the two second work budget share only one emergency allowance`() {
        val clock = OffsetNanoClock()
        withOrdinarySourceGrantCleanup(database.value, clock) { f ->
            val originalReadCap = f.pool.connection.use { it.networkTimeout }
            var sameWork = false
            var sameEmergency = false
            var caps: List<Long> = emptyList()
            var actualReadCap = 0
            var workRemaining = 0L
            var emergencyRemaining = 0L
            val store = cleanupPort { _ ->
                val phase = requireNotNull(PersistencePhaseOwnership.current())
                actualReadCap = selectedHolder(f).connection.networkTimeout // Actual JDBC scalar, not the MODEL clock below.
                caps = f.jdbc.queryForObject(
                    "SELECT current_setting('transaction_timeout')::interval, current_setting('statement_timeout')::interval, " +
                        "current_setting('idle_in_transaction_session_timeout')::interval, current_setting('lock_timeout')::interval",
                    { result, _ -> (1..4).map { index -> result.getString(index) } },
                )!!.map(::intervalMillis)
                val work = phase.cleanupBudget()
                clock.advance(750)
                sameWork = work === phase.cleanupBudget()
                workRemaining = work.remainingMillis(2_000)
                clock.advance(1_250)
                val emergency = phase.cleanupBudget()
                clock.advance(250)
                sameEmergency = emergency !== work && emergency === phase.cleanupBudget() && emergency === phase.cleanupBudget()
                emergencyRemaining = emergency.remainingMillis(1_000)
                throw CleanupFixtureFailure()
            }
            assertThrows<PersistencePhaseException> { f.newExecutor(store).cleanupSourceGrants() }
            settleCallingOwner()
            assertEquals(4, caps.size)
            assertTrue(caps[0] in 1..2_000 && caps[1] in 1..1_000 && caps[2] in 1..1_000 && caps[3] in 1..100)
            assertTrue(originalReadCap > 1_000 && actualReadCap in 1..1_000 && actualReadCap < originalReadCap)
            assertTrue(sameWork && sameEmergency)
            assertTrue(workRemaining in 1..1_250 && emergencyRemaining in 1..750)
            assertEquals(0, f.admission.activeOwners())
            // MODEL offset/identity assertions above are not lifetime evidence; the blocking case below is real PG.
        }
    }

    @Test
    fun `real blocking SQL after delete ends the old session and row lock without a future timeout receipt`() =
        withOrdinarySourceGrantCleanup(database.value) { f ->
            assertEquals(0, f.newExecutor().cleanupSourceGrants()) // Exclude cold pool/EMF startup from this work observation.
            val id = UUID(0, 103)
            f.seedGrant(id, SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff)
            var pid = 0
            var deleted = 0
            var acceptedAt = 0L
            var sessionSeen = false
            var lease: PersistenceJdbcLease? = null
            var target: CleanupSleepTarget? = null
            val observer = f.observePgSleep()
            try {
                observer.awaitReady() // Own connection/statement ready before the accepted-lease clock starts.
                val store = cleanupPort { cutoff ->
                    val phase = requireNotNull(PersistencePhaseOwnership.current())
                    val work = ownedCutField(phase, "work") as PersistenceTimeBudget
                    acceptedAt = ownedCutField(work, "startedAtNanos") as Long // The actual System.nanoTime lease-acceptance clock.
                    lease = ownedPoolLease(selectedHolder(f).connection)
                    pid = transactionIdentity(f).first
                    val session = f.session(pid)
                    sessionSeen = session?.inTransaction == true
                    target = CleanupSleepTarget(pid, requireNotNull(session).backendStart, acceptedAt).also(observer::watch)
                    deleted = f.sourceStore.deleteEligibleSourceGrants(cutoff)
                    f.jdbc.execute("SELECT pg_sleep(5)")
                    deleted
                }
                val failure = assertThrows<PersistencePhaseException> { f.newExecutor(store).cleanupSourceGrants() }
                settleCallingOwner()
                // Outside assertThrows: a setup/read-cap refusal before sleep dispatch cannot satisfy this oracle.
                assertEquals(requireNotNull(target), observer.witnessed())
                assertTrue(sessionSeen && pid > 0 && deleted == 1)
                assertNull(f.session(pid))
                assertTrue(failure.cleanupProven && requireNotNull(lease).completion.quiescent())
                assertEquals(setOf(id), f.grantIds())
                f.lockGrant(id).use { assertEquals(setOf(id), f.grantIds()) }
                val elapsedMillis = (System.nanoTime() - acceptedAt) / 1_000_000
                assertTrue(elapsedMillis in 1..3_000, "Accepted lease through real session/lock disposition and exact receipt must fit the admitted bound.")
                assertEquals(0, f.admission.activeOwners())
            } finally {
                observer.close() // Joins; the observer's finally closes its own connection, never the business lease.
            }
        }

    @Test
    fun `native commit failure stays unknown after Spring cleanup while authentic local quiescence permits only refund`() =
        withOrdinarySourceGrantCleanup(database.value) { f ->
            val id = UUID(0, 104)
            f.seedGrant(id, SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff)
            var pid = 0
            var killed = false
            var deleted = 0
            var receipt: PersistenceLeaseCompletion? = null
            var observation: OrdinaryCommitObservation? = null
            try {
                val store = cleanupPort { cutoff ->
                    val lease = ownedPoolLease(selectedHolder(f).connection)
                    receipt = lease.completion
                    pid = transactionIdentity(f).first
                    deleted = f.sourceStore.deleteEligibleSourceGrants(cutoff)
                    observation = OrdinaryCommitObservation(lease)
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun beforeCommit(readOnly: Boolean) {
                            f.terminateSession(pid) // Real remote fault; the tap below never injects a call/result/failure.
                            killed = true
                        }
                    })
                    deleted
                }
                val failure = assertThrows<PersistencePhaseException> { f.newExecutor(store).cleanupSourceGrants() }
                requireNotNull(observation).requireFailedNativeCommit() // Outside assertThrows, not inferred from beforeCommit/UNKNOWN.
                settleCallingOwner()
                assertTrue(killed && deleted == 1 && pid > 0)
                assertEquals(PersistenceDatabaseOutcome.UNKNOWN, failure.databaseOutcome)
                assertEquals(PersistenceDatabaseOutcome.UNKNOWN, requireNotNull(receipt).databaseOutcome())
                assertTrue(requireNotNull(receipt).quiescent())
                assertNull(f.session(pid))
                assertEquals(setOf(id), f.grantIds())
                assertEquals(0, f.admission.activeOwners())
                // No retry/reconciliation authority follows from this test's local ownership refund.
            } finally {
                observation?.close()
            }
        }

    @Test
    fun `real afterCommit interruption preserves durable committed outcome exact cleanup and original caller interrupt`() =
        withOrdinarySourceGrantCleanup(database.value) { f ->
            val id = UUID(0, 106)
            f.seedGrant(id, SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff)
            val caller = Thread.currentThread()
            var hookOnCaller = false
            var independentlyDurable = false
            var deleted = 0
            var lease: PersistenceJdbcLease? = null
            try {
                assertFalse(caller.isInterrupted)
                val store = cleanupPort { cutoff ->
                    lease = ownedPoolLease(selectedHolder(f).connection)
                    deleted = f.sourceStore.deleteEligibleSourceGrants(cutoff)
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() {
                            hookOnCaller = Thread.currentThread() === caller
                            // Independent connection after the real commit, before throwing/setting the caller's flag.
                            independentlyDurable = f.grantIds().isEmpty()
                            throw InterruptedException("Synthetic afterCommit interruption.")
                        }
                    })
                    deleted
                }
                val failure = assertThrows<PersistencePhaseException> { f.newExecutor(store).cleanupSourceGrants() }
                val receipt = requireNotNull(lease).completion
                assertTrue(hookOnCaller && independentlyDurable && deleted == 1)
                assertEquals(PersistencePhaseFailureCode.INTERRUPTED, failure.code)
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, failure.databaseOutcome)
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, receipt.databaseOutcome())
                assertTrue(failure.cleanupProven && receipt.matches(requireNotNull(lease)) && receipt.quiescent())
                assertEquals(0, f.admission.activeOwners())
                requireConnectionFree()
                assertSame(caller, Thread.currentThread())
                assertTrue(caller.isInterrupted)
            } finally {
                Thread.interrupted() // Test flag cleanup only, after all outcome/refund/interrupt assertions.
            }
        }

    @Test
    fun `a real post consent return tail retains permit past expiry and cannot refund or retire its successor early`() =
        withOrdinarySourceGrantCleanup(database.value) { f ->
            val id = UUID(0, 105)
            f.seedGrant(id, SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff)
            val originalSettings = f.pool.connection.use { it.networkTimeout to ownedPoolScalar(it, "SELECT pg_backend_pid()") }
            val barrier = AtomicReference<OrdinaryReturnTailBarrier>()
            val ready = CountDownLatch(1)
            val outcome = AtomicReference<Throwable?>()
            val oldConnection = AtomicReference<Connection>()
            val oldLease = AtomicReference<PersistenceJdbcLease>()
            val pid = AtomicLong()
            val cappedRead = AtomicLong()
            val thread = Thread.ofPlatform().unstarted {
                try {
                    val store = cleanupPort { cutoff ->
                        val connection = selectedHolder(f).connection
                        oldConnection.set(connection)
                        oldLease.set(ownedPoolLease(connection))
                        pid.set(transactionIdentity(f).first.toLong())
                        cappedRead.set(connection.networkTimeout.toLong())
                        barrier.set(OrdinaryReturnTailBarrier(f, requireNotNull(oldLease.get())))
                        ready.countDown()
                        f.sourceStore.deleteEligibleSourceGrants(cutoff)
                    }
                    f.newExecutor(store).cleanupSourceGrants()
                } catch (failure: Throwable) {
                    outcome.set(failure)
                }
            }
            thread.start()
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                val tail = requireNotNull(barrier.get())
                tail.awaitEntered()
                val lease = requireNotNull(oldLease.get())
                assertTrue(originalSettings.first > 1_000 && cappedRead.get() in 1..1_000)
                assertEquals(originalSettings.second, pid.get().toInt())
                assertTrue(tail.springUnbound && tail.connectionFreeRefused)
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, lease.completion.databaseOutcome())
                assertFalse(lease.completion.quiescent())
                assertEquals(1, f.admission.activeOwners())
                assertThrows<PersistencePhaseException> { f.newExecutor().cleanupSourceGrants() }
                assertTrue(f.grantIds().isEmpty())
                assertFalse(requireNotNull(f.session(pid.get().toInt())).inTransaction)
                Thread.sleep(2_050) // Hold the real tail beyond work expiry; never use expiry as its end.
                assertEquals(1, f.admission.activeOwners())
                assertFalse(lease.completion.quiescent())
                f.pool.connection.use { successor ->
                    val next = ownedPoolLease(successor)
                    assertNotSame(lease.state.epoch, next.state.epoch)
                    assertEquals(pid.get().toInt(), ownedPoolScalar(successor, "SELECT pg_backend_pid()"))
                    assertEquals(
                        originalSettings.first,
                        successor.networkTimeout,
                        "The same physical session must restore its actual JDBC read cap before reuse.",
                    )
                    tail.release()
                    thread.join(5_000)
                    assertFalse(thread.isAlive)
                    val failure = outcome.get() as PersistencePhaseException
                    assertEquals(PersistenceDatabaseOutcome.COMMITTED, failure.databaseOutcome)
                    assertTrue(failure.cleanupProven && lease.completion.quiescent())
                    assertEquals(0, f.admission.activeOwners())
                    oldConnection.get().close() // Exact duplicate old close is facade-only, even after successor reuse.
                    assertFalse(next.completion.quiescent())
                    assertEquals(17, ownedPoolScalar(successor, "SELECT 17"))
                    assertEquals(originalSettings.first, successor.networkTimeout)
                }
                assertFalse(tail.timedOut.get())
                assertEquals(0, f.newExecutor().cleanupSourceGrants())
            } finally {
                barrier.get()?.release()
                thread.join(6_000)
                check(!thread.isAlive) { "The actual retained test owner must terminate before fixture teardown." }
                barrier.get()?.close()
            }
        }
}

private fun cleanupPort(block: (Instant) -> Int): SourceGrantCleanup = object : SourceGrantCleanup {
    override fun deleteEligibleSourceGrants(cutoff: Instant): Int = block(cutoff)
}

private fun selectedHolder(f: OrdinarySourceGrantCleanupFixture): ConnectionHolder = TransactionSynchronizationManager.getResource(f.pool) as ConnectionHolder

private fun transactionIdentity(f: OrdinarySourceGrantCleanupFixture): Pair<Int, Long> = f.jdbc.queryForObject(
    "SELECT pg_backend_pid(), txid_current()",
    { result, _ -> result.getInt(1) to result.getLong(2) },
)!!

private fun settleCallingOwner() {
    awaitLifecycleFact {
        runCatching {
            requireConnectionFree()
            true
        }.getOrDefault(false)
    }
}

private class CleanupFixtureFailure : RuntimeException("Synthetic cleanup fixture failure.")

private class OffsetNanoClock : PersistenceNanoClock {
    private val offset = AtomicLong()
    override fun nanoTime(): Long = System.nanoTime() + offset.get()
    fun advance(millis: Long) {
        offset.addAndGet(millis * 1_000_000)
    }
}

private fun intervalMillis(value: String): Long {
    val components = value.split(':')
    check(components.size == 3)
    return ((components[0].toLong() * 3_600 + components[1].toLong() * 60) * 1_000) +
        java.math.BigDecimal(components[2]).multiply(java.math.BigDecimal(1_000)).longValueExact()
}

/** No SQL can escape through the negative holder; any attempted delegate dispatch fails the test. */
private fun foreignConnectionSentinel(): Connection = Proxy.newProxyInstance(
    Connection::class.java.classLoader,
    arrayOf(Connection::class.java),
) { _, _, _ -> throw AssertionError("Foreign holder reached JDBC.") } as Connection

/** Counts actual forwarded EM creation/begin attempts; metadata and every positive native operation stay real. */
private class CleanupFactoryObservation(original: EntityManagerFactory) {
    private val creations = AtomicLong()
    private val begins = AtomicLong()
    val factory: EntityManagerFactory = Proxy.newProxyInstance(
        EntityManagerFactory::class.java.classLoader,
        arrayOf(EntityManagerFactory::class.java, EntityManagerFactoryInfo::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.singleOrNull()

            "hashCode" -> System.identityHashCode(proxy)

            "toString" -> "ObservedCleanupFactory(redacted)"

            "createEntityManager", "createNativeEntityManager" -> {
                creations.incrementAndGet()
                val entity = invokeFixture(original, method, args) as EntityManager
                Proxy.newProxyInstance(EntityManager::class.java.classLoader, arrayOf(EntityManager::class.java)) { _, operation, parameters ->
                    if (operation.name == "getTransaction") {
                        val transaction = entity.transaction
                        Proxy.newProxyInstance(EntityTransaction::class.java.classLoader, arrayOf(EntityTransaction::class.java)) { _, call, input ->
                            if (call.name == "begin") begins.incrementAndGet()
                            invokeFixture(transaction, call, input)
                        }
                    } else {
                        invokeFixture(entity, operation, parameters)
                    }
                }
            }

            else -> invokeFixture(original, method, args)
        }
    } as EntityManagerFactory

    fun snapshot(): Pair<Long, Long> = creations.get() to begins.get()
}

private fun cleanupFactoryWithoutMetadata(factory: EntityManagerFactory): EntityManagerFactory = Proxy.newProxyInstance(
    EntityManagerFactory::class.java.classLoader,
    arrayOf(EntityManagerFactory::class.java),
) { proxy, method, args ->
    when (method.name) {
        "equals" -> proxy === args?.singleOrNull()
        "hashCode" -> System.identityHashCode(proxy)
        "toString" -> "UnknownCleanupFactoryMetadata(redacted)"
        else -> invokeFixture(factory, method, args)
    }
} as EntityManagerFactory

private fun cleanupFactoryWithoutDataSource(factory: EntityManagerFactory): EntityManagerFactory = Proxy.newProxyInstance(
    EntityManagerFactory::class.java.classLoader,
    arrayOf(EntityManagerFactory::class.java, EntityManagerFactoryInfo::class.java),
) { proxy, method, args ->
    when (method.name) {
        "equals" -> proxy === args?.singleOrNull()
        "hashCode" -> System.identityHashCode(proxy)
        "toString" -> "MissingCleanupResourceMetadata(redacted)"
        "getDataSource" -> null
        else -> invokeFixture(factory, method, args)
    }
} as EntityManagerFactory

/** Calibrated caller-local acquisition history, not a sample of outstanding-acquisition occupancy. */
private class OrdinaryAcquisitionObservation(private val lifecycle: PoolLifecycle) :
    ThreadLocal<PoolCallFrame?>(),
    AutoCloseable {
    private val caller = Thread.currentThread()
    private val storage = requireNotNull(ownedCutField(PoolCallFrames, "storage"))
    private val field = storage.javaClass.getDeclaredField("current").apply { check(trySetAccessible()) }

    @Suppress("UNCHECKED_CAST")
    private val delegate = field.get(storage) as ThreadLocal<PoolCallFrame?>
    var entries = 0
        private set

    init {
        assertNull(delegate.get())
        field.set(storage, this)
    }

    override fun get(): PoolCallFrame? = delegate.get()

    override fun set(value: PoolCallFrame?) {
        delegate.set(value)
        if (Thread.currentThread() === caller && value?.kind === PoolCallKind.ACQUISITION && ownedCutField(value, "pool") === lifecycle) entries++
    }

    override fun remove() = delegate.remove()

    override fun close() {
        assertSame(this, field.get(storage))
        field.set(storage, delegate)
        assertNull(delegate.get())
    }
}

/**
 * Same own-project instance-TL/driver-Invocation observation seam as the retained owned-cut tests.
 * No call, driver, transaction result or completion field is written. Original TL storage is delegated.
 */
private class OrdinaryCommitObservation(lease: PersistenceJdbcLease) :
    ThreadLocal<PersistenceJdbcGuardCall?>(),
    AutoCloseable {
    private val caller = Thread.currentThread()
    private val context = lease.state.context
    private val root = ownedPoolRoot(lease)
    private val field = context.javaClass.getDeclaredField("frames").apply { check(trySetAccessible()) }

    @Suppress("UNCHECKED_CAST")
    private val delegate = field.get(context) as ThreadLocal<PersistenceJdbcGuardCall?>
    private val driver = PersistenceJdbcGuardCall::class.java.getDeclaredField("driver").apply { check(trySetAccessible()) }
    private var selected: PersistenceJdbcGuardCall? = null
    private var invocation: PersistencePgOwnedCutAccess.Invocation? = null
    private var additionalCommit = false
    private var preparedBeforeArm = false
    private var failedWhileArmed = false
    private var observationFailure: Throwable? = null

    init {
        assertNull(delegate.get())
        field.set(context, this)
    }

    // One live-call discriminator excludes setup, wrapping and later-rollback failures without moving any observation.
    @Suppress("ComplexCondition")
    override fun get(): PersistenceJdbcGuardCall? = delegate.get().also { call ->
        if (Thread.currentThread() === caller && call != null) {
            try {
                val native = driver.get(call) as? PersistencePgOwnedCutAccess.Invocation
                if (native != null && ownedCutField(native.cell, "operation") == "commit") {
                    if (selected == null) {
                        selected = call
                        invocation = native
                    } else if (selected !== call) {
                        additionalCommit = true
                    }
                    if (selected === call) {
                        val armed = ownedCutField(native.cell, "armed") == true
                        if (!armed && context.transaction.databaseOutcome() === PersistenceDatabaseOutcome.NONE) preparedBeforeArm = true
                        // Only this live, armed lower commit has failed here, BEFORE any subsequent rollback.
                        // Preparation/arm failures and a returned commit's wrapping failure are excluded separately.
                        if (armed && ownedCutField(native.cell, "ended") == false &&
                            ownedCutField(call, "driverPreparationFailure") == false &&
                            ownedCutField(call, "outcome") === PersistenceJdbcCallOutcome.ORDINARY_FAILURE &&
                            context.transaction.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN
                        ) {
                            failedWhileArmed = true
                        }
                    }
                }
            } catch (failure: Throwable) {
                observationFailure = failure // An observation error must not manufacture the tested lower-call failure.
            }
        }
    }

    override fun set(value: PersistenceJdbcGuardCall?) = delegate.set(value)
    override fun remove() = delegate.remove()

    fun requireFailedNativeCommit() {
        assertNull(observationFailure)
        assertFalse(additionalCommit)
        assertTrue(preparedBeforeArm && failedWhileArmed)
        val call = requireNotNull(selected)
        val native = requireNotNull(invocation)
        assertSame(call, native.callKey)
        assertSame(root, native.owner.root)
        assertEquals("commit", ownedCutField(native.cell, "operation"))
        assertEquals(PersistenceJdbcGuardCallKind.BUSINESS, ownedCutField(call, "kind"))
        assertEquals(true, ownedCutField(call, "driverArmAttempted"))
        assertEquals(false, ownedCutField(call, "driverPreparationFailure"))
        assertEquals(PersistenceJdbcCallOutcome.ORDINARY_FAILURE, ownedCutField(call, "outcome"))
        assertEquals(true, ownedCutField(call, "ended"))
        assertEquals(true, ownedCutField(native.cell, "armed"))
        assertEquals(true, ownedCutField(native.cell, "disarmed"))
        assertEquals(true, ownedCutField(native.cell, "ended"))
    }

    override fun close() {
        assertSame(this, field.get(context))
        field.set(context, delegate)
        assertNull(delegate.get())
    }
}

/** Fault the actual begin after it succeeds; stock Spring must roll back/close the retained EM without a status. */
private fun failAfterRealBegin(factory: EntityManagerFactory, began: () -> Unit): EntityManagerFactory = Proxy.newProxyInstance(
    EntityManagerFactory::class.java.classLoader,
    arrayOf(EntityManagerFactory::class.java, EntityManagerFactoryInfo::class.java),
) { proxy, method, args ->
    when (method.name) {
        "equals" -> proxy === args?.singleOrNull()

        "hashCode" -> System.identityHashCode(proxy)

        "toString" -> "FailedBeginFactory(redacted)"

        "createEntityManager", "createNativeEntityManager" -> {
            val entity = invokeFixture(factory, method, args) as EntityManager
            Proxy.newProxyInstance(EntityManager::class.java.classLoader, arrayOf(EntityManager::class.java)) { _, operation, parameters ->
                if (operation.name == "getTransaction") {
                    val transaction = entity.transaction
                    Proxy.newProxyInstance(EntityTransaction::class.java.classLoader, arrayOf(EntityTransaction::class.java)) { _, call, input ->
                        val result = invokeFixture(transaction, call, input)
                        if (call.name == "begin") {
                            began()
                            throw CleanupFixtureFailure()
                        }
                        result
                    }
                } else {
                    invokeFixture(entity, operation, parameters)
                }
            }
        }

        else -> invokeFixture(factory, method, args)
    }
} as EntityManagerFactory

private fun invokeFixture(target: Any, method: Method, arguments: Array<out Any?>?): Any? = try {
    method.invoke(target, *(arguments ?: emptyArray()))
} catch (failure: InvocationTargetException) {
    throw failure.targetException
}

/** Fault the actual selected lazy getter after status retention, never bind/substitute a Spring holder. */
private class PostBeginHolderFailure(original: EntityManagerFactory) {
    var phase: PersistencePhaseContext? = null
        private set
    var injected = false
        private set
    val factory: EntityManagerFactory = Proxy.newProxyInstance(
        EntityManagerFactory::class.java.classLoader,
        arrayOf(EntityManagerFactory::class.java, EntityManagerFactoryInfo::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.singleOrNull()

            "hashCode" -> System.identityHashCode(proxy)

            "toString" -> "MaterializationFailureFactory(redacted)"

            "createEntityManager", "createNativeEntityManager" -> {
                val entity = invokeFixture(original, method, args) as EntityManager
                Proxy.newProxyInstance(EntityManager::class.java.classLoader, arrayOf(EntityManager::class.java)) { _, call, input ->
                    if (call.name == "unwrap" && input?.singleOrNull() === SessionImplementor::class.java) {
                        guardedSession(entity.unwrap(SessionImplementor::class.java))
                    } else {
                        invokeFixture(entity, call, input)
                    }
                }
            }

            else -> invokeFixture(original, method, args)
        }
    } as EntityManagerFactory

    private fun guardedSession(session: SessionImplementor): SessionImplementor = Proxy.newProxyInstance(
        SessionImplementor::class.java.classLoader,
        arrayOf(SessionImplementor::class.java),
    ) { _, method, args ->
        if (method.name == "getJdbcCoordinator") guardedCoordinator(session.jdbcCoordinator) else invokeFixture(session, method, args)
    } as SessionImplementor

    private fun guardedCoordinator(coordinator: JdbcCoordinator): JdbcCoordinator = Proxy.newProxyInstance(
        JdbcCoordinator::class.java.classLoader,
        arrayOf(JdbcCoordinator::class.java),
    ) { _, method, args ->
        if (method.name == "getLogicalConnection") guardedLogical(coordinator.logicalConnection) else invokeFixture(coordinator, method, args)
    } as JdbcCoordinator

    private fun guardedLogical(logical: LogicalConnectionImplementor): LogicalConnectionImplementor = Proxy.newProxyInstance(
        LogicalConnectionImplementor::class.java.classLoader,
        arrayOf(LogicalConnectionImplementor::class.java),
    ) { _, method, args ->
        val returned = invokeFixture(logical, method, args)
        if (method.name == "getPhysicalConnection" && !injected) {
            val current = PersistencePhaseOwnership.current()
            val status = current?.let { ownedCutField(it, "rootStatus") as? PersistenceManagedStatus }
            if (status?.hasReturnedStatus() == true) {
                phase = current
                injected = true
                throw CleanupFixtureFailure()
            }
        }
        returned
    } as LogicalConnectionImplementor
}

/** Hold the authentic acquisition bookkeeping end after checkout consent, not a fake Future result. */
private class OrdinaryAcquisitionTailBarrier :
    ThreadLocal<PoolCallFrame?>(),
    AutoCloseable {
    private val caller = Thread.currentThread()
    private val storage = requireNotNull(ownedCutField(PoolCallFrames, "storage"))
    private val field = storage.javaClass.getDeclaredField("current").apply { check(trySetAccessible()) }

    @Suppress("UNCHECKED_CAST")
    private val delegate = field.get(storage) as ThreadLocal<PoolCallFrame?>
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val once = AtomicBoolean()
    val timedOut = AtomicBoolean()

    @Volatile var phase: PersistencePhaseContext? = null
        private set

    init {
        check(delegate.get() == null)
        field.set(storage, this)
    }

    override fun get(): PoolCallFrame? = delegate.get()
    override fun set(value: PoolCallFrame?) = delegate.set(value)

    override fun remove() {
        if (Thread.currentThread() === caller && delegate.get()?.kind === PoolCallKind.ACQUISITION && once.compareAndSet(false, true)) {
            phase = requireNotNull(PersistencePhaseOwnership.current())
            entered.countDown()
            if (!released.await(5, TimeUnit.SECONDS)) timedOut.set(true)
        }
        delegate.remove()
    }

    fun awaitEntered() = assertTrue(entered.await(5, TimeUnit.SECONDS))
    fun release() = released.countDown()

    override fun close() {
        release()
        assertSame(this, field.get(storage))
        field.set(storage, delegate)
        assertNull(delegate.get())
    }
}

/**
 * Own-project TL interception after Hikari consent/return and dispatch end, before the genuine
 * RETURN actor end. Original values/restoration are delegated; no receipt/count is fabricated.
 */
private class OrdinaryReturnTailBarrier(private val fixture: OrdinarySourceGrantCleanupFixture, private val lease: PersistenceJdbcLease) :
    ThreadLocal<PoolCallFrame?>(),
    AutoCloseable {
    private val caller = Thread.currentThread()
    private val storage = requireNotNull(ownedCutField(PoolCallFrames, "storage"))
    private val field = storage.javaClass.getDeclaredField("current").apply { check(trySetAccessible()) }

    @Suppress("UNCHECKED_CAST")
    private val delegate = field.get(storage) as ThreadLocal<PoolCallFrame?>
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val once = AtomicBoolean()
    val timedOut = AtomicBoolean()

    @Volatile var springUnbound = false
        private set

    @Volatile var connectionFreeRefused = false
        private set

    init {
        check(delegate.get() == null)
        field.set(storage, this)
    }

    override fun get(): PoolCallFrame? = delegate.get()
    override fun set(value: PoolCallFrame?) = delegate.set(value)

    // The one-shot barrier may arm only on this caller's already-consented RETURN, before delegating its actual end.
    @Suppress("ComplexCondition")
    override fun remove() {
        val frame = delegate.get()
        val transfer = ownedCutField(lease, "transfer") as? PersistenceJdbcPoolTransfer
        if (Thread.currentThread() === caller && frame?.kind === PoolCallKind.RETURN &&
            transfer?.consented() == true && once.compareAndSet(false, true)
        ) {
            check(!fixture.ownedPool.lifecycle.ownershipLockHeld())
            springUnbound = PersistencePhaseOwnership.springConnectionFree()
            connectionFreeRefused = runCatching { requireConnectionFree() }.exceptionOrNull() is PersistencePhaseException
            entered.countDown()
            if (!released.await(5, TimeUnit.SECONDS)) timedOut.set(true)
        }
        delegate.remove()
    }

    fun awaitEntered() = assertTrue(entered.await(5, TimeUnit.SECONDS))
    fun release() = released.countDown()

    override fun close() {
        release()
        assertSame(this, field.get(storage))
        field.set(storage, delegate)
        assertNull(delegate.get())
    }
}
