package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationEnrollment
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentDisposition
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentRejection
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentResult
import me.manga.kira.backend.complaint.domain.InstallationSessionRejection
import me.manga.kira.backend.complaint.domain.SessionPreflightResult
import me.manga.kira.backend.complaint.domain.SessionRefreshResult
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintInstallationSessionStore
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintInstallationEnrollmentStore
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real owned PG paths with explicitly synthetic D/P/run sizing. No HTTP, signed activation or complete-D producer claim. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OrdinaryComplaintTestInstallationIT {
    private val database = lazy { PgLifecycleDatabaseFixture(OrdinaryComplaintTestInstallationIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `TEST enrollment converts only its pair share with ordinary audit and daily admission on one owned transaction then replays without another slot`() =
        withFixture(limit = 1) { t ->
            val f = t.base
            val candidate = t.candidate()
            // Only the ordinary audit needs new creation headroom; pair slots/bytes were already committed by the reserve.
            for (counter in listOf(
                ComplaintCapacityCounter.APP_INSTALLATIONS,
                ComplaintCapacityCounter.INSTALLATION_IDS,
                ComplaintCapacityCounter.AUDIT_ROWS,
                ComplaintCapacityCounter.STORAGE_BYTES,
            )) {
                f.observer.update(
                    "UPDATE complaint_capacity_counters SET creation_limit = actual_units + recovery_reserved_units + test_reserved_units + ? WHERE name = ?",
                    ComplaintCapacityCharges.AUDIT[counter],
                    counter.storedName,
                )
            }
            val before = t.state()
            assertFalse(t.desired.configurationHashBytes().contentEquals(f.counters.syntheticPolicyDigest()))
            f.afterStep = { assertEquals(before, t.state(), "No counter, run, pair or audit change independently commits.") }
            val result = f.execute(candidate, t.store)
            assertEquals(InstallationEnrollmentDisposition.CREATED, result.disposition)
            assertEquals(candidate.installation, result.installation)
            assertEquals(1L, result.credentialVersion)
            assertEquals(f.jdbc.databaseTimes.single(), result.issuedAt)
            assertEquals(
                listOf(
                    EnrollmentFixtureStep.COUNTERS,
                    EnrollmentFixtureStep.TEST_RUN,
                    EnrollmentFixtureStep.IDENTITY,
                    EnrollmentFixtureStep.CREDENTIAL,
                    EnrollmentFixtureStep.DATABASE_TIME,
                ),
                f.jdbc.queries,
            )
            val first = f.observations.first().second
            f.observations.forEach { (_, observation) ->
                assertSame(first.phase, observation.phase)
                assertSame(first.lease, observation.lease)
                assertEquals(first.identity, observation.identity)
            }
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, first.phase.databaseOutcome())
            t.assertChargedOnce(before)
            assertEquals(
                1L,
                f.observer.queryForObject(
                    "SELECT count(*) FROM complaint_installation_ids i JOIN app_installations c ON (c.id,c.data_scope_id) = (i.id,i.data_scope_id) " +
                        "JOIN audit_log a ON a.complaint_data_scope_id = i.data_scope_id " +
                        "WHERE i.id = ? AND i.data_scope_id = ? AND i.test_only AND c.test_only " +
                        "AND a.action = 'COMPLAINT_INSTALLATION_ENROLLED' AND a.actor_user_id IS NULL " +
                        "AND a.entity_type = 'complaint_scope' AND a.entity_id = i.data_scope_id::text",
                    Long::class.java,
                    candidate.installation.id,
                    t.scope.id,
                ),
            )
            t.resetTrace()
            f.seedDaily(f.databaseDay().plusDays(2), 100, 100)
            f.observer.update("UPDATE complaint_capacity_counters SET configuration_closed = true")
            val full = t.state()
            assertEquals(InstallationEnrollmentDisposition.EXACT_REPLAY, f.execute(candidate, t.store).disposition)
            val replayed = t.state()
            assertEquals(full.copy(installations = full.installations.copy(credentials = replayed.installations.credentials)), replayed)
            assertEquals(listOf(EnrollmentFixtureStep.REPLAY to 1), f.jdbc.updates)
        }

    @Test
    fun `TEST session keeps one released read-only snapshot then locks the run before the pair without touching counters`() = withFixture { t ->
        val f = t.base
        val candidate = t.candidate()
        f.execute(candidate, t.store)
        t.resetTrace()
        f.afterStep = { step ->
            if (step === EnrollmentFixtureStep.SESSION_SNAPSHOT) {
                assertEquals("on", f.ordinary.jdbc.queryForObject("SHOW transaction_read_only", String::class.java))
            }
        }
        val before = t.state()
        val ready = f.preflight(t.session(candidate), t.sessions) as SessionPreflightResult.Ready
        requireConnectionFree()
        assertEquals(listOf(EnrollmentFixtureStep.SESSION_SNAPSHOT), f.jdbc.queries)
        assertEquals(before, t.state())
        val read = f.observations.single().second
        t.resetTrace()
        f.counters.lockLastCounter().use {
            val refreshed = f.refresh(ready.continuation, t.sessions) as SessionRefreshResult.Refreshed
            assertEquals(candidate.installation, refreshed.installation)
            assertEquals(ready.continuation.credentialVersion, refreshed.credentialVersion)
        }
        assertEquals(
            listOf(
                EnrollmentFixtureStep.TEST_RUN,
                EnrollmentFixtureStep.SESSION_IDENTITY,
                EnrollmentFixtureStep.SESSION_CREDENTIAL,
                EnrollmentFixtureStep.SESSION_TIME,
            ),
            f.jdbc.queries,
        )
        assertEquals(listOf(EnrollmentFixtureStep.SESSION_REFRESH to 1), f.jdbc.updates)
        assertNotSame(read.phase, f.observations.first().second.phase)
        val refreshed = t.state()
        assertEquals(before.copy(installations = before.installations.copy(credentials = refreshed.installations.credentials)), refreshed)

        t.resetTrace()
        val next = (f.preflight(t.session(candidate), t.sessions) as SessionPreflightResult.Ready).continuation
        t.resetTrace()
        f.afterStep = { if (it === EnrollmentFixtureStep.SESSION_REFRESH) throw SyntheticInstallationEnrollmentFailure() }
        rolledBack { f.refresh(next, t.sessions) }
        assertEquals(refreshed, t.state())
        t.resetTrace()
        rolledBack { f.refresh(next, t.sessions) }
        assertTrue(f.jdbc.queries.isEmpty(), "Failed refresh consumes the released continuation; it cannot be replayed.")
    }

    @Test
    fun `concurrent final TEST slot creates exactly one permanent pair and a fresh one-over request cannot spend terminal reserve`() =
        withFixture(maximumPoolSize = 3, limit = 1) { t ->
            val f = t.base
            val before = t.state()
            val firstEntered = CountDownLatch(1)
            val bothEntered = CountDownLatch(2)
            val release = CountDownLatch(1)
            f.jdbc.beforeQuery = { step ->
                if (step === EnrollmentFixtureStep.COUNTERS) {
                    firstEntered.countDown()
                    bothEntered.countDown()
                    assertTrue(release.await(1_500, TimeUnit.MILLISECONDS))
                }
            }
            val candidates = listOf(t.candidate(), t.candidate())
            val outcomes = OwnedCallerTestScope().use { callers ->
                callers.beforeClose { release.countDown() }
                val first = callers.launch { runCatching { f.executor(t.store).enroll(candidates[0]) } }
                assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
                val second = callers.launch { runCatching { f.executor(t.store).enroll(candidates[1]) } }
                assertTrue(bothEntered.await(1, TimeUnit.SECONDS))
                release.countDown()
                listOf(first.value(), second.value())
            }
            f.assertReleased()
            assertEquals(1, outcomes.count { it.getOrNull() is InstallationEnrollmentResult.Enrolled })
            val loser = outcomes.indexOfFirst { it.getOrNull() !is InstallationEnrollmentResult.Enrolled }
            outcomes[loser].exceptionOrNull()?.let { problem ->
                assertTrue(problem is PersistencePhaseException)
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, (problem as PersistencePhaseException).databaseOutcome)
                assertTrue(problem.cleanupProven) // The normal 100ms lock limit is not relabeled as a capacity rejection.
            } ?: assertEquals(
                InstallationEnrollmentRejection.CAPACITY_UNAVAILABLE,
                (outcomes[loser].getOrThrow() as InstallationEnrollmentResult.Rejected).reason,
            )
            t.assertChargedOnce(before)
            assertEquals(1, t.state().installations.identities.size)
            t.resetTrace()
            val full = t.state()
            assertEquals(
                InstallationEnrollmentRejection.CAPACITY_UNAVAILABLE,
                (f.attempt(candidates[loser], t.store) as InstallationEnrollmentResult.Rejected).reason,
            )
            assertEquals(full, t.state())
            assertTrue(f.jdbc.updates.isEmpty())
        }

    @Test
    fun `absent SEALED PURGING and PURGED scopes are terminal for retained and never-committed UUIDs even after deployment scope changes`() {
        for (state in listOf("ABSENT", "SEALED", "PURGING", "PURGED")) {
            withFixture { t ->
                val f = t.base
                val existing = t.candidate()
                f.execute(existing, t.store)
                if (state == "ABSENT") f.observer.update("DELETE FROM complaint_test_runs WHERE data_scope_id = ?", t.scope.id) else t.terminalState(state)
                val otherDesired = t.desired(ComplaintDataScope.of(UUID.randomUUID()))
                val otherStore = JdbcComplaintInstallationEnrollmentStore(f.jdbc, f.capacity, f.audit, otherDesired)
                val otherSessions = JdbcComplaintInstallationSessionStore(f.jdbc, otherDesired)
                for (candidate in listOf(existing, t.candidate())) {
                    t.resetTrace()
                    val before = t.state()
                    assertEquals(
                        InstallationEnrollmentRejection.INSTALLATION_SCOPE_RETIRED,
                        (f.attempt(candidate, otherStore) as InstallationEnrollmentResult.Rejected).reason,
                    )
                    assertEquals(listOf(EnrollmentFixtureStep.COUNTERS, EnrollmentFixtureStep.TEST_RUN), f.jdbc.queries)
                    assertEquals(
                        InstallationSessionRejection.INSTALLATION_SCOPE_RETIRED,
                        (f.preflight(t.session(candidate), otherSessions) as SessionPreflightResult.Rejected).reason,
                    )
                    assertEquals(before, t.state())
                    assertTrue(f.jdbc.updates.isEmpty())
                }
            }
        }
    }

    @Test
    fun `TEST constructor scope and independent D must match while counter P cannot stand in for D or be replaced by it`() = withFixture { t ->
        val f = t.base
        val candidate = t.candidate()
        val wrongScope = t.desired(ComplaintDataScope.of(UUID.randomUUID()))
        val otherStore = JdbcComplaintInstallationEnrollmentStore(f.jdbc, f.capacity, f.audit, wrongScope)
        val otherSessions = JdbcComplaintInstallationSessionStore(f.jdbc, wrongScope)
        val before = t.state()
        assertEquals(
            InstallationEnrollmentRejection.INSTALLATION_SCOPE_MISMATCH,
            (f.attempt(candidate, otherStore) as InstallationEnrollmentResult.Rejected).reason,
        )
        assertEquals(
            InstallationSessionRejection.INSTALLATION_SCOPE_MISMATCH,
            (f.preflight(t.session(candidate), otherSessions) as SessionPreflightResult.Rejected).reason,
        )
        val live = t.candidate(ComplaintDataScope.LIVE)
        assertEquals(
            InstallationEnrollmentRejection.INSTALLATION_SCOPE_MISMATCH,
            (f.attempt(live, t.store) as InstallationEnrollmentResult.Rejected).reason,
        )
        assertEquals(
            InstallationSessionRejection.INSTALLATION_SCOPE_MISMATCH,
            (f.preflight(t.session(live), t.sessions) as SessionPreflightResult.Rejected).reason,
        )
        assertEquals(before, t.state())

        f.execute(candidate, t.store)
        val ready = (f.preflight(t.session(candidate), t.sessions) as SessionPreflightResult.Ready).continuation
        f.observer.update("UPDATE complaint_test_runs SET configuration_hash = ? WHERE data_scope_id = ?", f.counters.syntheticPolicyDigest(), t.scope.id)
        t.resetTrace()
        val wrongD = t.state()
        rolledBack { f.attempt(candidate, t.store) }
        rolledBack { f.preflight(t.session(candidate), t.sessions) }
        rolledBack { f.refresh(ready, t.sessions) }
        assertEquals(wrongD, t.state())
        assertTrue(f.jdbc.updates.isEmpty())
        f.observer.update("UPDATE complaint_test_runs SET configuration_hash = ? WHERE data_scope_id = ?", t.desired.configurationHashBytes(), t.scope.id)
        f.observer.update("UPDATE complaint_capacity_counters SET configuration_hash = ?", t.desired.configurationHashBytes())
        t.resetTrace()
        val wrongP = t.state()
        rolledBack { f.attempt(t.candidate(), t.store) }
        assertEquals(listOf(EnrollmentFixtureStep.COUNTERS), f.jdbc.queries)
        assertEquals(wrongP, t.state())
    }

    @Test
    fun `TEST daily refusal and malformed reserved installation share leave the run permanent UUID and every balance untouched`() = withFixture { t ->
        val f = t.base
        f.seedDaily(f.databaseDay().plusDays(2), 1, 1)
        var before = t.state()
        assertEquals(
            InstallationEnrollmentRejection.DAILY_LIMIT_REACHED,
            (f.attempt(t.candidate(), t.store) as InstallationEnrollmentResult.Rejected).reason,
        )
        assertEquals(before, t.state())
        f.seedDaily(f.databaseDay(), 0, 100)
        val promised = t.state().balances.getValue("installation_ids").test
        f.observer.update(
            "UPDATE complaint_capacity_counters SET test_reserved_units = 0, free_units = free_units + ? WHERE name = 'installation_ids'",
            promised,
        )
        t.resetTrace()
        before = t.state()
        rolledBack { f.attempt(t.candidate(), t.store) }
        assertTrue(f.jdbc.updates.isEmpty(), "Run reserve cannot spend missing aggregate counter reserve.")
        assertEquals(before, t.state())
        f.observer.update(
            "UPDATE complaint_capacity_counters SET test_reserved_units = ?, free_units = free_units - ? WHERE name = 'installation_ids'",
            promised,
            promised,
        )
        f.observer.update(
            "UPDATE complaint_test_runs SET original_reserve = array_fill(0::bigint, ARRAY[22]), " +
                "unused_reserve = array_fill(0::bigint, ARRAY[22]) WHERE data_scope_id = ?",
            t.scope.id,
        )
        t.resetTrace()
        before = t.state()
        rolledBack { f.attempt(t.candidate(), t.store) }
        assertEquals(listOf(EnrollmentFixtureStep.COUNTERS, EnrollmentFixtureStep.TEST_RUN), f.jdbc.queries)
        assertTrue(f.jdbc.updates.isEmpty())
        assertEquals(before, t.state())
    }

    @Test
    fun `TEST run conversion paired inserts audit and actual zero-row run CAS all roll back with their earlier counter and daily work`() = withFixture { t ->
        val f = t.base
        val candidate = t.candidate()
        val before = t.state()
        for (point in listOf(
            EnrollmentFixtureStep.TEST_RUN_UPDATE,
            EnrollmentFixtureStep.IDENTITY_INSERT,
            EnrollmentFixtureStep.CREDENTIAL_INSERT,
            EnrollmentFixtureStep.AUDIT,
        )) {
            t.resetTrace()
            var reached = false
            f.afterStep = { step ->
                if (step === point) {
                    reached = true
                    throw SyntheticInstallationEnrollmentFailure()
                }
            }
            rolledBack { f.attempt(candidate, t.store) }
            assertTrue(reached)
            assertEquals(before, t.state())
        }
        t.resetTrace()
        f.jdbc.beforeUpdate = { step ->
            if (step === EnrollmentFixtureStep.TEST_RUN_UPDATE) {
                assertEquals(
                    1,
                    f.ordinary.jdbc.update("UPDATE complaint_test_runs SET enrolled_count = enrolled_count + 1 WHERE data_scope_id = ?", t.scope.id),
                )
            }
        }
        rolledBack { f.attempt(candidate, t.store) }
        assertTrue(f.jdbc.updates.contains(EnrollmentFixtureStep.TEST_RUN_UPDATE to 0))
        assertTrue(f.jdbc.updates.any { it.first === EnrollmentFixtureStep.DAILY && it.second == 1 })
        assertEquals(before, t.state())

        t.resetTrace()
        var completed = false
        val failure = assertThrows<PersistencePhaseException> {
            f.attempt(
                candidate,
                ComplaintInstallationEnrollment { input ->
                    t.store.enroll(input).also {
                        completed = true
                        f.ordinary.jdbc.execute("CREATE TEMP TABLE kira_test_installation_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                        assertEquals(2, f.ordinary.jdbc.update("INSERT INTO kira_test_installation_commit VALUES (1), (1)"))
                    }
                },
            )
        }
        assertTrue(completed)
        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        assertEquals(before, t.state(), "A store result before COMMIT is not enrollment or reserve-conversion success.")
    }

    @Test
    fun `last counter blocks TEST run access and sealing an actually locked run defeats delayed enrollment and released session preflight`() {
        withFixture { t ->
            val f = t.base
            val before = t.state()
            f.counters.lockLastCounter().use {
                rolledBack { f.attempt(t.candidate(), t.store) }
                assertEquals(listOf(EnrollmentFixtureStep.COUNTERS), f.jdbc.queries)
                t.lockedRun().use { it.rollback() } // No hidden earlier run lock is held.
                assertEquals(before, t.state())
            }
        }
        for (session in listOf(false, true)) withFixture { t -> sealingRace(t, session) }
    }

    @Test
    fun `sealing waits behind an enrollment that already owns its run until that exact pair and reserve conversion commit`() = withFixture { t ->
        val f = t.base
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holdOnce = AtomicBoolean(true)
        f.afterStep = { step ->
            if (step === EnrollmentFixtureStep.TEST_RUN && holdOnce.compareAndSet(true, false)) {
                locked.countDown()
                assertTrue(release.await(1_500, TimeUnit.MILLISECONDS))
            }
        }
        val candidate = t.candidate()
        val before = t.state()
        OwnedCallerTestScope().use { callers ->
            callers.beforeClose { release.countDown() }
            val enrollment = callers.launch { f.executor(t.store).enroll(candidate) }
            assertTrue(locked.await(1, TimeUnit.SECONDS))
            val sealerPid = AtomicInteger()
            val sealing = CountDownLatch(1)
            val sealer = callers.launch {
                checkNotNull(f.observer.dataSource).connection.use { connection ->
                    connection.autoCommit = false
                    val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
                    sealerPid.set(checkNotNull(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)))
                    jdbc.execute("SET LOCAL statement_timeout = '1500ms'")
                    sealing.countDown()
                    t.terminalState("SEALED", jdbc)
                    connection.commit()
                }
                true
            }
            assertTrue(sealing.await(1, TimeUnit.SECONDS))
            assertTrue(t.awaitBlocked(sealerPid.get()))
            assertEquals(before, t.state())
            release.countDown()
            assertTrue(enrollment.value() is InstallationEnrollmentResult.Enrolled)
            assertTrue(sealer.value())
        }
        f.assertReleased()
        t.assertChargedOnce(before)
        assertEquals("SEALED", f.observer.queryForObject("SELECT state FROM complaint_test_runs WHERE data_scope_id = ?", String::class.java, t.scope.id))
    }

    private fun sealingRace(t: OrdinaryComplaintTestInstallationFixture, session: Boolean) {
        val f = t.base
        val candidate = t.candidate()
        if (session) f.execute(candidate, t.store)
        t.resetTrace()
        val before = t.state()
        val entered = CountDownLatch(1)
        val pid = AtomicInteger()
        f.jdbc.beforeQuery = { step ->
            if (step === EnrollmentFixtureStep.TEST_RUN) {
                pid.set(observeStepUpPhase(f.ordinary).identity.first)
                entered.countDown()
            }
        }
        t.lockedRun().use { lock ->
            OwnedCallerTestScope().use { callers ->
                callers.beforeClose { lock.rollback() }
                val result = callers.launch {
                    if (session) {
                        val ready = f.sessionExecutor(t.sessions).preflight(t.session(candidate)) as SessionPreflightResult.Ready
                        requireConnectionFree()
                        f.sessionExecutor(t.sessions).refresh(ready.continuation)
                    } else {
                        f.executor(t.store).enroll(candidate)
                    }
                }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                assertTrue(t.awaitBlocked(pid.get()), "Observe the genuine PG run-row wait before committing SEALED.")
                assertEquals(
                    if (session) {
                        listOf(EnrollmentFixtureStep.SESSION_SNAPSHOT, EnrollmentFixtureStep.TEST_RUN)
                    } else {
                        listOf(EnrollmentFixtureStep.COUNTERS, EnrollmentFixtureStep.TEST_RUN)
                    },
                    f.jdbc.queries,
                )
                t.terminalState("SEALED", JdbcTemplate(SingleConnectionDataSource(lock, true)))
                lock.commit()
                val outcome = result.value()
                if (session) {
                    assertEquals(InstallationSessionRejection.INSTALLATION_SCOPE_RETIRED, (outcome as SessionRefreshResult.Rejected).reason)
                } else {
                    assertEquals(InstallationEnrollmentRejection.INSTALLATION_SCOPE_RETIRED, (outcome as InstallationEnrollmentResult.Rejected).reason)
                }
            }
        }
        f.assertReleased()
        assertTrue(f.jdbc.updates.isEmpty())
        assertEquals(before.copy(run = t.state().run), t.state())
    }

    private fun withFixture(maximumPoolSize: Int = 2, limit: Long = 2, test: (OrdinaryComplaintTestInstallationFixture) -> Unit) =
        withOrdinaryComplaintInstallationEnrollment(database.value, maximumPoolSize) { base ->
            OrdinaryComplaintTestInstallationFixture(base, limit).use(test)
        }

    private fun rolledBack(work: () -> Any?) {
        val failure = assertThrows<PersistencePhaseException> { work() }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
    }
}
