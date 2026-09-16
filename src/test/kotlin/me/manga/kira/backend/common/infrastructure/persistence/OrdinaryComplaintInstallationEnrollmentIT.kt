package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.audit.domain.ComplaintInstallationEnrollmentAudit
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationEnrollment
import me.manga.kira.backend.complaint.domain.ComplaintInstallationSession
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentDisposition
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentRejection
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentResult
import me.manga.kira.backend.complaint.domain.InstallationSessionCandidate
import me.manga.kira.backend.complaint.domain.InstallationSessionPreflight
import me.manga.kira.backend.complaint.domain.InstallationSessionRejection
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.SessionPreflightResult
import me.manga.kira.backend.complaint.domain.SessionRefreshResult
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationSessionOperation
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintInstallationSessionStore
import me.manga.kira.backend.complaint.infrastructure.capacity.ComplaintInstallationEnrollmentOperation
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintInstallationEnrollmentStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationSessionPhaseExecutor
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.AbstractDataSource
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real-PG lower core/K06 evidence only; no HTTP/JWT/test-run mode or authenticated W04 headroom claim. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OrdinaryComplaintInstallationEnrollmentIT {
    private val database = lazy { PgLifecycleDatabaseFixture(OrdinaryComplaintInstallationEnrollmentIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `new pair daily admission and scope-only shared audit commit on one selected PID and transaction`() = withFixture { f ->
        val candidate = f.candidate()
        val before = f.state()
        f.afterStep = { step ->
            if (step in WRITE_STEPS) assertEquals(before, f.state(), "No counter, pair or audit side effect may commit independently.")
        }
        assertEquals(InstallationEnrollmentDisposition.CREATED, f.execute(candidate).disposition)
        val expected = listOf(
            EnrollmentFixtureStep.COUNTERS, EnrollmentFixtureStep.IDENTITY, EnrollmentFixtureStep.CREDENTIAL, EnrollmentFixtureStep.DATABASE_TIME,
            EnrollmentFixtureStep.CHARGE, EnrollmentFixtureStep.CHARGE, EnrollmentFixtureStep.DAILY, EnrollmentFixtureStep.CHARGE,
            EnrollmentFixtureStep.IDENTITY_INSERT, EnrollmentFixtureStep.CREDENTIAL_INSERT, EnrollmentFixtureStep.AUDIT,
        )
        assertEquals(expected, f.observations.map { it.first })
        val first = f.observations.first().second
        f.observations.forEach { (_, observed) ->
            assertSame(first.phase, observed.phase)
            assertSame(first.lease, observed.lease)
            assertEquals(first.identity, observed.identity)
        }
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, first.phase.databaseOutcome())
        assertEquals(List(6) { 1 }, f.jdbc.updates.map { it.second })
        f.assertCreationCharge(before)
        val after = f.state()
        assertEquals(1, after.identities.size)
        assertEquals(1, after.credentials.size)
        assertEquals(1, after.audits.size)
        assertEquals(1L, after.counters.getValue("installation_ids").dailyCount)
        assertArrayEquals(
            candidate.verifierBytes(),
            f.observer.queryForObject("SELECT secret_verifier FROM app_installations WHERE id = ?", ByteArray::class.java, candidate.installation.id),
        )
        assertEquals(
            listOf(true),
            f.observer.query(
                "SELECT a.actor_user_id IS NULL AND a.complaint_actor_kind = 'INSTALLATION' " +
                    "AND a.entity_type = 'complaint_scope' AND a.entity_id = i.data_scope_id::text " +
                    "AND a.complaint_data_scope_id = i.data_scope_id AND a.action = 'COMPLAINT_INSTALLATION_ENROLLED' " +
                    "AND a.detail = '{\"version\":1}'::jsonb AND a.created_at = i.created_at " +
                    "AND c.admission_utc_date = (i.created_at AT TIME ZONE 'UTC')::date " +
                    "FROM audit_log a, complaint_installation_ids i, complaint_capacity_counters c " +
                    "WHERE a.id = ? AND i.id = ? AND c.name = 'installation_ids'",
                { row, _ -> row.getBoolean(1) },
                f.auditIds.single(),
                candidate.installation.id,
            ),
        )
    }

    @Test
    fun `exact final daily slot admits and one over returns a no-write daily rejection without another permanent identity`() = withFixture { f ->
        val retainedDay = f.databaseDay().plusDays(2)
        f.seedDaily(retainedDay, 1, 2)
        assertEquals(InstallationEnrollmentDisposition.CREATED, f.execute(f.candidate()).disposition)
        val before = f.state()
        assertEquals(2L, before.counters.getValue("installation_ids").dailyCount)
        assertEquals(retainedDay, before.counters.getValue("installation_ids").day)
        f.rejected(f.candidate(), InstallationEnrollmentRejection.DAILY_LIMIT_REACHED)
        assertEquals(before, f.state())
    }

    @Test
    fun `null and earlier buckets reset on real database UTC day while a later stored day never reopens`() = withFixture { f ->
        for (old in listOf(null, f.databaseDay().minusDays(2))) {
            f.seedDaily(old, if (old == null) 0 else 2, 2)
            val candidate = f.candidate()
            assertEquals(InstallationEnrollmentDisposition.CREATED, f.execute(candidate).disposition)
            val day = f.observer.queryForObject(
                "SELECT (created_at AT TIME ZONE 'UTC')::date FROM complaint_installation_ids WHERE id = ?",
                { row, _ -> row.getDate(1).toLocalDate() },
                candidate.installation.id,
            )
            assertEquals(day, f.state().counters.getValue("installation_ids").day)
            assertEquals(1L, f.state().counters.getValue("installation_ids").dailyCount)
        }
        val future = f.databaseDay().plusDays(2)
        f.seedDaily(future, 0, 1)
        f.execute(f.candidate())
        assertEquals(future, f.state().counters.getValue("installation_ids").day)
        val before = f.state()
        f.rejected(f.candidate(), InstallationEnrollmentRejection.DAILY_LIMIT_REACHED)
        assertEquals(before, f.state())
    }

    @Test
    fun `UTC admission ignores both positive and negative session zones with a genuine differing date control`() = withFixture { f ->
        val differingDate = AtomicBoolean()
        for (zone in listOf("Pacific/Kiritimati", "Etc/GMT+12")) {
            f.seedDaily(f.databaseDay().minusDays(2), 2, 2)
            f.jdbc.beforeQuery = { step ->
                if (step === EnrollmentFixtureStep.COUNTERS) {
                    f.ordinary.jdbc.execute("SET LOCAL TIME ZONE '$zone'")
                    if (f.ordinary.jdbc.queryForObject(
                            "SELECT current_date <> (clock_timestamp() AT TIME ZONE 'UTC')::date",
                            Boolean::class.java,
                        ) == true
                    ) {
                        differingDate.set(true)
                    }
                }
            }
            val candidate = f.candidate()
            f.execute(candidate)
            assertEquals(
                f.observer.queryForObject(
                    "SELECT (created_at AT TIME ZONE 'UTC')::date FROM complaint_installation_ids WHERE id = ?",
                    { row, _ -> row.getDate(1).toLocalDate() },
                    candidate.installation.id,
                ),
                f.state().counters.getValue("installation_ids").day,
            )
        }
        assertTrue(differingDate.get(), "At least one actual session date must differ; a same-date timezone test is not evidence.")
    }

    @Test
    fun `database wall clock is sampled after all counter locks rather than at transaction start or from the injected clock`() = withFixture { f ->
        var afterCounterLock: Instant? = null
        var transactionStart: Instant? = null
        f.afterStep = { step ->
            if (step === EnrollmentFixtureStep.COUNTERS) {
                val times = f.ordinary.jdbc.queryForObject(
                    "SELECT transaction_timestamp(), clock_timestamp()",
                    { row, _ -> row.getTimestamp(1).toInstant() to row.getTimestamp(2).toInstant() },
                )!!
                transactionStart = times.first
                afterCounterLock = times.second
            }
        }
        val candidate = f.candidate()
        f.execute(candidate)
        val created = f.observer.queryForObject(
            "SELECT created_at FROM complaint_installation_ids WHERE id = ?",
            { row, _ -> row.getTimestamp(1).toInstant() },
            candidate.installation.id,
        )!!
        assertTrue(created >= checkNotNull(afterCounterLock))
        assertTrue(created > checkNotNull(transactionStart))
        assertFalse(created == f.ordinary.cutoff)
    }

    @Test
    fun `exact ACTIVE replay refreshes activity but never recharges a full daily bucket closed creation or enrolled audit`() = withFixture { f ->
        val candidate = f.candidate()
        f.seedDaily(f.databaseDay().plusDays(2), 0, 1)
        f.execute(candidate)
        f.observer.update("UPDATE complaint_capacity_counters SET configuration_closed = true, creation_limit = 0")
        val before = f.state()
        f.jdbc.updates.clear()
        val replay = f.execute(candidate)
        assertEquals(InstallationEnrollmentDisposition.EXACT_REPLAY, replay.disposition)
        assertEquals(1L, replay.credentialVersion)
        assertEquals(listOf(EnrollmentFixtureStep.REPLAY to 1), f.jdbc.updates)
        val after = f.state()
        assertEquals(before.counters, after.counters)
        assertEquals(before.identities, after.identities)
        assertEquals(before.audits, after.audits)
        assertEquals(1, after.credentials.size)
        assertEquals(2L, f.observer.queryForObject("SELECT version FROM app_installations WHERE id = ?", Long::class.java, candidate.installation.id))
        f.rejected(f.candidate(), InstallationEnrollmentRejection.CAPACITY_UNAVAILABLE)
        assertEquals(after, f.state())
    }

    @Test
    fun `each real charge daily pair and audit boundary plus later caller failure refunds the whole transaction`() = withFixture { f ->
        val candidate = f.candidate()
        val before = f.state()
        for (step in WRITE_STEPS - EnrollmentFixtureStep.REPLAY) {
            var reached = false
            f.afterStep = {
                if (it === step) {
                    reached = true
                    throw SyntheticInstallationEnrollmentFailure()
                }
            }
            rolledBack { f.execute(candidate) }
            assertTrue(reached)
            assertEquals(before, f.state())
        }
        f.afterStep = {}
        rolledBack {
            f.execute(
                candidate,
                ComplaintInstallationEnrollment {
                    f.store.enroll(it)
                    throw SyntheticInstallationEnrollmentFailure()
                },
            )
        }
        assertEquals(before, f.state())
    }

    @Test
    fun `failure after real replay update rolls activity back without touching its already committed creation`() = withFixture { f ->
        val candidate = f.candidate()
        f.execute(candidate)
        val before = f.state()
        f.afterStep = { if (it === EnrollmentFixtureStep.REPLAY) throw SyntheticInstallationEnrollmentFailure() }
        rolledBack { f.execute(candidate) }
        assertEquals(before, f.state())
    }

    @Test
    fun `actual zero-row daily CAS rolls back earlier charges and the intervening same-transaction bucket change`() = withFixture { f ->
        val candidate = f.candidate()
        val before = f.state()
        f.jdbc.beforeUpdate = {
            if (it === EnrollmentFixtureStep.DAILY) {
                assertEquals(
                    1,
                    f.ordinary.jdbc.update("UPDATE complaint_capacity_counters SET admission_count = admission_count + 1 WHERE name = 'installation_ids'"),
                )
            }
        }
        rolledBack { f.execute(candidate) }
        assertEquals(listOf(1, 1, 0), f.jdbc.updates.map { it.second })
        assertEquals(before, f.state())
    }

    @Test
    fun `genuine credential and shared audit SQL refusals leave neither pair nor daily or lifetime charge`() = withFixture { f ->
        val candidate = f.candidate()
        val before = f.state()
        val constraints = listOf(
            "app_installations" to "id <> '${candidate.installation.id}'::uuid",
            "audit_log" to "action <> 'COMPLAINT_INSTALLATION_ENROLLED'",
        )
        for ((table, predicate) in constraints) {
            f.observer.execute("ALTER TABLE $table ADD CONSTRAINT k06_synthetic_insert_refusal CHECK ($predicate) NOT VALID")
            try {
                rolledBack { f.execute(candidate) }
                assertEquals(before, f.state())
            } finally {
                f.observer.execute("ALTER TABLE $table DROP CONSTRAINT k06_synthetic_insert_refusal")
            }
        }
    }

    @Test
    fun `wrong secret platform unsupported test scope and an existing foreign-scope reservation never become replay`() = withFixture { f ->
        InstallationEnrollmentOutcomeCases(f).credentialRejections()
    }

    @Test
    fun `pending deleted retired recovery-reserved and orphan identities are never resurrected by enrollment`() = withFixture { f ->
        InstallationEnrollmentOutcomeCases(f).terminalRejections()
    }

    @Test
    fun `new enrollment respects creation headroom and all twenty-two configuration bindings before any identity change`() = withFixture { f ->
        val candidate = f.candidate()
        val before = f.state()
        for (expected in listOf(null, ByteArray(31), ByteArray(32) { 77 })) {
            f.jdbc.queries.clear()
            val store = JdbcComplaintInstallationEnrollmentStore(f.jdbc, JdbcComplaintCapacityStore(f.jdbc, expected), f.audit)
            rolledBack { f.execute(candidate, store) }
            assertFalse(f.jdbc.queries.contains(EnrollmentFixtureStep.IDENTITY))
            assertEquals(before, f.state())
        }
        f.observer.update("UPDATE complaint_capacity_counters SET creation_limit = hard_limit - free_units WHERE name = 'storage_bytes'")
        val exhausted = f.state()
        f.rejected(candidate, InstallationEnrollmentRejection.CAPACITY_UNAVAILABLE)
        assertEquals(exhausted, f.state())
        f.observer.update("UPDATE complaint_capacity_counters SET configuration_closed = true")
        val closed = f.state()
        f.rejected(candidate, InstallationEnrollmentRejection.CAPACITY_UNAVAILABLE)
        assertEquals(closed, f.state())
    }

    @Test
    fun `a held last unrelated counter prevents reaching any identity lock and a later fresh caller can proceed`() = withFixture { f ->
        val candidate = f.candidate()
        val before = f.state()
        f.counters.lockLastCounter().use {
            rolledBack { f.execute(candidate) }
            assertEquals(listOf(EnrollmentFixtureStep.COUNTERS), f.jdbc.queries)
            assertTrue(f.jdbc.updates.isEmpty())
            assertEquals(before, f.state())
        }
        assertEquals(InstallationEnrollmentDisposition.CREATED, f.execute(candidate).disposition)
    }

    @Test
    fun `malformed persisted daily buckets fail before identity SQL even if their database CHECK is temporarily absent`() = withFixture { f ->
        val candidate = f.candidate()
        val day = f.databaseDay()
        val definition = f.observer.queryForObject(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'complaint_capacity_counters'::regclass " +
                "AND conname = 'chk_complaint_capacity_admission'",
            String::class.java,
        )!!
        f.observer.execute("ALTER TABLE complaint_capacity_counters DROP CONSTRAINT chk_complaint_capacity_admission")
        try {
            for (corruption in listOf(
                "admission_count = -1",
                "admission_count = NULL",
                "admission_count = 101",
                "admission_daily_limit = NULL",
                "admission_utc_date = NULL, admission_count = 1",
                "admission_utc_date = 'infinity'::date",
            )) {
                f.seedDaily(day, 0, 100)
                f.observer.update("UPDATE complaint_capacity_counters SET $corruption WHERE name = 'installation_ids'")
                val before = f.state()
                f.jdbc.queries.clear()
                rolledBack { f.execute(candidate) }
                assertEquals(listOf(EnrollmentFixtureStep.COUNTERS), f.jdbc.queries)
                assertEquals(before, f.state())
            }
        } finally {
            f.seedDaily(day, 0, 100)
            f.observer.execute("ALTER TABLE complaint_capacity_counters ADD CONSTRAINT chk_complaint_capacity_admission $definition")
        }
    }

    @Test
    fun `fabricated missing prepared-only or substituted completion cannot commit even real paired work`() = withFixture { f ->
        val candidate = f.candidate()
        val before = f.state()
        val fabricated = InstallationEnrollmentResult.Enrolled(InstallationEnrollmentDisposition.CREATED, candidate.installation, 1, f.ordinary.cutoff)
        val ports = listOf(
            ComplaintInstallationEnrollment { fabricated },
            ComplaintInstallationEnrollment { InstallationEnrollmentResult.Rejected(InstallationEnrollmentRejection.CAPACITY_UNAVAILABLE) },
            ComplaintInstallationEnrollment {
                ComplaintInstallationEnrollmentOperation.prepare(f.jdbc, it)
                fabricated
            },
            ComplaintInstallationEnrollment {
                f.store.enroll(it)
                fabricated
            },
            ComplaintInstallationEnrollment {
                val result = f.store.enroll(it)
                f.preserveAssertions { assertThrows<PersistencePhaseException> { f.store.enroll(it) } }
                result
            },
        )
        for (port in ports) {
            rolledBack { f.execute(candidate, port) }
            assertEquals(before, f.state())
        }
        val phase = f.ordinary.ownership.enterComplaintInstallationEnrollment()
        try {
            phase.begin()
            assertThrows<PersistencePhaseException> { phase.commit() }
        } finally {
            phase.finish()
        }
        rolledBack { phase.installationEnrollment.result(fabricated) }
    }

    @Test
    fun `missing duplicate or mismatched enrollment audit cannot leave charged paired rows`() = withFixture { f ->
        val candidate = f.candidate()
        val before = f.state()
        val audits = listOf(
            ComplaintInstallationEnrollmentAudit { _, _, _ -> },
            ComplaintInstallationEnrollmentAudit { scope, allocation, at ->
                f.audit.record(scope, allocation, at)
                f.audit.record(scope, allocation, at)
            },
            ComplaintInstallationEnrollmentAudit { _, allocation, at ->
                f.service.recordInstallationEnrollment(ComplaintDataScope.of(UUID.randomUUID()), allocation, at)
            },
        )
        for (audit in audits) {
            rolledBack { f.execute(candidate, JdbcComplaintInstallationEnrollmentStore(f.jdbc, f.capacity, audit)) }
            assertEquals(before, f.state())
        }
    }

    @Test
    fun `wrong path resource or exact template and restored holder refuse without an alternate borrow or partial commit`() = withFixture { f ->
        val candidate = f.candidate()
        val before = f.state()
        val direct = assertThrows<PersistencePhaseException> { f.store.enroll(candidate) }
        assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, direct.code)
        val phase = f.ordinary.ownership.enterSourceGrantCleanup()
        try {
            phase.begin()
            assertThrows<PersistencePhaseException> { f.store.enroll(candidate) }
            assertThrows<PersistencePhaseException> { phase.commit() }
        } finally {
            phase.finish()
        }
        rolledBack { phase.result(0) }
        val borrows = AtomicInteger()
        val foreign = JdbcTemplate(object : AbstractDataSource() {
            override fun getConnection(): Connection {
                borrows.incrementAndGet()
                error("A refused resource must not borrow.")
            }

            override fun getConnection(username: String, password: String): Connection = connection
        })
        val stores = listOf(
            JdbcComplaintInstallationEnrollmentStore(foreign, f.capacity, f.audit),
            JdbcComplaintInstallationEnrollmentStore(f.jdbc, JdbcComplaintCapacityStore(foreign, f.counters.syntheticPolicyDigest()), f.audit),
            JdbcComplaintInstallationEnrollmentStore(f.jdbc, JdbcComplaintCapacityStore(f.ordinary.jdbc, f.counters.syntheticPolicyDigest()), f.audit),
        )
        for (store in stores) rolledBack { f.execute(candidate, store) }
        assertEquals(0, borrows.get())
        f.afterStep = {
            if (it === EnrollmentFixtureStep.DAILY) {
                val holder = TransactionSynchronizationManager.unbindResource(f.ordinary.pool)
                try {
                    assertThrows<PersistencePhaseException> { f.store.enroll(candidate) }
                } finally {
                    TransactionSynchronizationManager.bindResource(f.ordinary.pool, holder)
                }
            }
        }
        rolledBack { f.execute(candidate) }
        assertEquals(before, f.state())
    }

    @Test
    fun `a stale retained enrollment operation poisons a new owner without undoing its earlier known commit`() = withFixture { f ->
        val candidate = f.candidate()
        var retained: ComplaintInstallationEnrollmentOperation? = null
        f.execute(
            candidate,
            ComplaintInstallationEnrollment {
                val operation = ComplaintInstallationEnrollmentOperation.prepare(f.jdbc, it)
                retained = operation
                operation.enroll(f.capacity, f.audit)
            },
        )
        val before = f.state()
        val phase = f.ordinary.ownership.enterComplaintInstallationEnrollment()
        try {
            phase.begin()
            assertThrows<PersistencePhaseException> { checkNotNull(retained).enroll(f.capacity, f.audit) }
            assertThrows<PersistencePhaseException> { phase.commit() }
        } finally {
            phase.finish()
        }
        rolledBack { phase.installationEnrollment.result(null) }
        assertEquals(before, f.state())
    }

    @Test
    fun `concurrent final slot admits exactly one pair and the losing fresh retry still cannot exceed the daily cap`() = withFixture(3) { f ->
        f.seedDaily(f.databaseDay().plusDays(2), 0, 1)
        val ready = CountDownLatch(2)
        val firstAdmitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.jdbc.beforeQuery = {
            if (it === EnrollmentFixtureStep.COUNTERS) {
                firstAdmitted.countDown()
                ready.countDown()
                assertTrue(release.await(1_500, TimeUnit.MILLISECONDS))
            }
        }
        val candidates = listOf(f.candidate(), f.candidate())
        val results = List(2) { AtomicReference<InstallationEnrollmentResult?>() }
        val failures = List(2) { AtomicReference<Throwable?>() }
        val threads = candidates.mapIndexed { index, candidate ->
            Thread.ofPlatform().unstarted {
                try {
                    results[index].set(f.executor().enroll(candidate))
                } catch (problem: Throwable) {
                    failures[index].set(problem)
                }
            }
        }
        // Admission is deliberately fail-fast. Stage entry, not SQL: both admitted callers
        // still wait at COUNTERS and are released together to contend for the final daily slot.
        val readinessDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        val readiness = runCatching {
            threads[0].start()
            assertTrue(firstAdmitted.await((readinessDeadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS))
            threads[1].start()
            assertTrue(ready.await((readinessDeadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS))
        }
        release.countDown()
        // Both bounded joins always run. Choose the original cleanup precedence afterward,
        // without throwing from finally or losing the original readiness/child failure.
        val joins = threads.map { thread -> runCatching { finishThread(thread) } }
        val problems = listOfNotNull(readiness.exceptionOrNull()) + joins.mapNotNull { it.exceptionOrNull() }
        val problem = problems.lastOrNull()
        if (problem != null) {
            problems.filter { it !== problem }.forEach(problem::addSuppressed)
            for (index in failures.indices) {
                val childFailure = failures[index].get()
                val status = if (childFailure is PersistencePhaseException) {
                    "code=${childFailure.code}, databaseOutcome=${childFailure.databaseOutcome}, cleanupProven=${childFailure.cleanupProven}"
                } else {
                    "failure=${childFailure?.javaClass?.name ?: "NONE"}"
                }
                val diagnostic = AssertionError(
                    "Enrollment worker $index after release/join: state=${threads[index].state}, " +
                        "result=${results[index].get()}, $status",
                )
                if (childFailure != null) diagnostic.initCause(childFailure)
                problem.addSuppressed(diagnostic)
            }
            throw problem
        }
        f.assertReleased()
        assertEquals(1, results.count { (it.get() as? InstallationEnrollmentResult.Enrolled)?.disposition === InstallationEnrollmentDisposition.CREATED })
        val loser = results.indexOfFirst { it.get() !is InstallationEnrollmentResult.Enrolled }
        val refused = results[loser].get()
        if (refused is InstallationEnrollmentResult.Rejected) {
            assertEquals(null, failures[loser].get())
            assertEquals(InstallationEnrollmentRejection.DAILY_LIMIT_REACHED, refused.reason)
        } else {
            // The existing 100ms lock budget can expire before the winner releases; do not relabel that as daily admission.
            val failure = failures[loser].get()
            assertTrue(failure is PersistencePhaseException)
            assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, (failure as PersistencePhaseException).databaseOutcome)
            assertTrue(failure.cleanupProven)
        }
        assertEquals(1, f.state().identities.size)
        assertEquals(1, f.state().credentials.size)
        assertEquals(1, f.state().audits.size)
        assertEquals(1L, f.state().counters.getValue("installation_ids").dailyCount)
        f.jdbc.beforeQuery = {}
        val before = f.state()
        f.rejected(candidates[loser], InstallationEnrollmentRejection.DAILY_LIMIT_REACHED)
        assertEquals(before, f.state())
    }

    @Test
    fun `fixed lifecycle charges exceed real maximal identity and credential heap and every V14 index tuple`() = withFixture { f ->
        val candidate = f.candidate()
        f.execute(candidate)
        val id = candidate.installation.id
        assertInstallationSchema(f)
        assertLifecycleTupleBounds(f, id)
    }

    @Test
    fun `session preflight is one read-only released snapshot and a separate DB-timed refresh changes only activity and row version`() = withFixture { f ->
        InstallationSessionCoreCases(f).releasedSnapshotAndRefresh()
    }

    @Test
    fun `session typed rejections preserve verifier precedence and never resurrect or turn non-LIVE input into a continuation`() = withFixture { f ->
        InstallationSessionCoreCases(f).typedRejections()
    }

    @Test
    fun `session orphan contradictory and malformed persisted pairs fail instead of authenticating or inventing absence`() = withFixture { f ->
        InstallationSessionCoreCases(f).corruptPairs()
    }

    @Test
    fun `session locked refresh rejects real state verifier credential-version and scope changes after released preflight`() = withFixture { f ->
        InstallationSessionCoreCases(f).lockedRecheck()
    }

    @Test
    fun `two released session callers serialize valid refresh without revoking each other on activity row versions`() = withFixture(3) { f ->
        InstallationSessionCoreCases(f).concurrentRefresh()
    }

    @Test
    fun `session fabricated substituted and unreleased results cannot satisfy exact phase or continuation custody`() = withFixture { f ->
        InstallationSessionCoreCases(f).exactCustody()
    }

    @Test
    fun `session continuation refuses foreign issuer caller and ambient transaction before original one-use refresh`() = withFixture { f ->
        InstallationSessionCoreCases(f).issuerCallerAndAmbient()
    }

    @Test
    fun `session continuation cannot transfer to another owner through the same mutable JDBC template`() = withFixture { f ->
        withOrdinarySourceGrantCleanup(database.value, SystemPersistenceNanoClock, maximumPoolSize = 2, companion = f.ordinary.ownedPool) { other ->
            InstallationSessionCoreCases(f).differentOwner(other)
        }
    }

    @Test
    fun `session real update rollback and deferred commit failure never publish Refreshed or reusable continuation`() = withFixture { f ->
        InstallationSessionCoreCases(f).failedCompletion()
    }

    @Test
    fun `enrollment success retains the exact scoped identity and actual database clock through committed release`() = withFixture { f ->
        InstallationEnrollmentOutcomeCases(f).releasedFacts()
    }

    @Test
    fun `daily enrollment rejection uses the effective UTC reset while zero daily capacity and lifetime closure have no retry`() = withFixture { f ->
        InstallationEnrollmentOutcomeCases(f).dailyOutcomes()
    }

    @Test
    fun `enrollment contradictory orphan and malformed pairs fail storage while a coherent foreign pair is never recreated`() = withFixture { f ->
        InstallationEnrollmentOutcomeCases(f).storedPairOutcomes()
    }

    @Test
    fun `enrollment typed success and rejection cannot bypass actual commit finish rollback or completion-tail failures`() = withFixture { f ->
        InstallationEnrollmentOutcomeCases(f).completionGates()
    }

    private fun withFixture(maximumPoolSize: Int = 2, test: (OrdinaryComplaintInstallationEnrollmentFixture) -> Unit) =
        withOrdinaryComplaintInstallationEnrollment(database.value, maximumPoolSize, test)

    private companion object {
        val WRITE_STEPS = setOf(
            EnrollmentFixtureStep.CHARGE,
            EnrollmentFixtureStep.DAILY,
            EnrollmentFixtureStep.IDENTITY_INSERT,
            EnrollmentFixtureStep.CREDENTIAL_INSERT,
            EnrollmentFixtureStep.AUDIT,
            EnrollmentFixtureStep.REPLAY,
        )
    }
}

/** Thin cases on the already-owned enrollment fixture; no new launcher, admission authority or product activation. */
private class InstallationSessionCoreCases(private val f: OrdinaryComplaintInstallationEnrollmentFixture) {
    fun releasedSnapshotAndRefresh() {
        val candidate = enrolled()
        val id = candidate.installation.id
        val before = f.state()
        clearTrace()
        f.afterStep = { step ->
            if (step === EnrollmentFixtureStep.SESSION_SNAPSHOT) {
                assertEquals("on", f.ordinary.jdbc.queryForObject("SHOW transaction_read_only", String::class.java))
                assertEquals(PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_PREFLIGHT.name, TransactionSynchronizationManager.getCurrentTransactionName())
                assertEquals(before, f.state())
            }
        }
        val ticket = ready(candidate)
        assertEquals(before, f.state())
        assertEquals(listOf(EnrollmentFixtureStep.SESSION_SNAPSHOT), f.jdbc.queries)
        assertTrue(f.jdbc.updates.isEmpty())
        val read = f.observations.single().second
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, read.phase.databaseOutcome())
        assertEquals("InstallationSessionPreflight(redacted)", ticket.toString())

        f.observer.update("UPDATE app_installations SET last_authenticated_at = clock_timestamp() + interval '1 day' WHERE id = ?", id)
        val refreshBefore = f.state()
        val old = credential(id)
        var floor: Instant? = null
        var ceiling: Instant? = null
        clearTrace()
        f.jdbc.beforeQuery = { step ->
            if (step === EnrollmentFixtureStep.SESSION_TIME) {
                assertEquals("off", f.ordinary.jdbc.queryForObject("SHOW transaction_read_only", String::class.java))
                floor = databaseTime(f.ordinary.jdbc)
            }
        }
        f.afterStep = { step ->
            if (step === EnrollmentFixtureStep.SESSION_TIME) ceiling = databaseTime(f.ordinary.jdbc)
            assertEquals(refreshBefore, f.state(), "Refresh must not commit independently of its phase.")
        }
        val refreshed = f.refresh(ticket) as SessionRefreshResult.Refreshed
        assertEquals(candidate.installation, refreshed.installation)
        assertEquals(ticket.credentialVersion, refreshed.credentialVersion)
        assertTrue(refreshed.issuedAt >= checkNotNull(floor) && refreshed.issuedAt <= checkNotNull(ceiling))
        assertNotEquals(f.ordinary.cutoff, refreshed.issuedAt)
        assertEquals(
            listOf(EnrollmentFixtureStep.SESSION_IDENTITY, EnrollmentFixtureStep.SESSION_CREDENTIAL, EnrollmentFixtureStep.SESSION_TIME),
            f.jdbc.queries,
        )
        assertEquals(listOf(EnrollmentFixtureStep.SESSION_REFRESH to 1), f.jdbc.updates)
        val write = f.observations.first().second
        assertNotSame(read.phase, write.phase)
        assertNotEquals(read.identity.second, write.identity.second)
        f.observations.forEach { (_, observed) ->
            assertSame(write.phase, observed.phase)
            assertSame(write.lease, observed.lease)
            assertEquals(write.identity, observed.identity)
        }
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, write.phase.databaseOutcome())
        assertEquals(old.copy(rowVersion = old.rowVersion + 1), credential(id))
        val after = f.state()
        assertEquals(refreshBefore.copy(credentials = after.credentials), after)
    }

    fun typedRejections() {
        val absent = f.sessionCandidate(f.candidate().installation.id)
        rejected(absent, InstallationSessionRejection.INSTALLATION_NOT_FOUND)
        val active = enrolled()
        rejected(f.sessionCandidate(active.installation.id, ByteArray(32) { 99 }), InstallationSessionRejection.INSTALLATION_CREDENTIAL_REJECTED)
        for ((state, reason) in listOf(
            "DELETION_PENDING" to InstallationSessionRejection.INSTALLATION_DELETION_PENDING,
            "DELETED" to InstallationSessionRejection.INSTALLATION_DELETED,
            "RETIRED" to InstallationSessionRejection.INSTALLATION_RETIRED,
            "RECOVERY_RESERVED" to InstallationSessionRejection.INSTALLATION_RETIRED,
            "DELETED_EMPTY" to InstallationSessionRejection.INSTALLATION_DELETED,
        )) {
            val candidate = enrolled()
            changeState(candidate.installation.id, state)
            rejected(candidate, reason)
            if (state in setOf("DELETION_PENDING", "DELETED")) {
                rejected(f.sessionCandidate(candidate.installation.id, ByteArray(32) { 99 }), InstallationSessionRejection.INSTALLATION_CREDENTIAL_REJECTED)
            }
        }
        val foreign = enrolled()
        foreignReservation(foreign.installation.id)
        rejected(foreign, InstallationSessionRejection.INSTALLATION_SCOPE_MISMATCH)
        for (candidate in listOf(absent, active)) {
            val test = InstallationEnrollmentCredentials.prepareSession(
                ScopedInstallationId(candidate.installation.id, ComplaintDataScope.of(UUID.randomUUID())),
                ByteArray(32) { it.toByte() },
            )
            rejected(test, InstallationSessionRejection.INSTALLATION_SCOPE_MISMATCH)
        }
    }

    fun corruptPairs() {
        for (corruption in listOf("ACTIVE_ORPHAN", "PENDING_ORPHAN", "CONTRADICTORY")) {
            val candidate = enrolled()
            val id = candidate.installation.id
            if (corruption != "CONTRADICTORY") f.observer.update("DELETE FROM app_installations WHERE id = ?", id)
            if (corruption != "ACTIVE_ORPHAN") {
                f.observer.update("UPDATE complaint_installation_ids SET state = 'DELETION_PENDING' WHERE id = ?", id)
            }
            val before = f.state()
            clearTrace()
            rolledBack { f.preflight(candidate) }
            assertTrue(f.jdbc.updates.isEmpty())
            assertEquals(before, f.state())
        }
        val malformed = enrolled()
        val id = malformed.installation.id
        val createdAt = f.observer.queryForObject("SELECT created_at FROM app_installations WHERE id = ?", Timestamp::class.java, id)!!
        val definition = f.observer.queryForObject(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'app_installations'::regclass AND conname = 'chk_app_installations_times'",
            String::class.java,
        )!!
        f.observer.execute("ALTER TABLE app_installations DROP CONSTRAINT chk_app_installations_times")
        try {
            f.observer.update("UPDATE app_installations SET created_at = 'infinity'::timestamptz WHERE id = ?", id)
            val before = f.state()
            rolledBack { f.preflight(malformed) }
            assertEquals(before, f.state())
        } finally {
            f.observer.update("UPDATE app_installations SET created_at = ? WHERE id = ?", createdAt, id)
            f.observer.execute("ALTER TABLE app_installations ADD CONSTRAINT chk_app_installations_times $definition")
        }
    }

    fun lockedRecheck() {
        for ((change, reason) in listOf(
            "DELETION_PENDING" to InstallationSessionRejection.INSTALLATION_DELETION_PENDING,
            "DELETED" to InstallationSessionRejection.INSTALLATION_DELETED,
            "RETIRED" to InstallationSessionRejection.INSTALLATION_RETIRED,
            "SECRET" to InstallationSessionRejection.INSTALLATION_CREDENTIAL_REJECTED,
            "VERSION" to InstallationSessionRejection.INSTALLATION_CREDENTIAL_REJECTED,
            "SCOPE" to InstallationSessionRejection.INSTALLATION_SCOPE_MISMATCH,
        )) {
            val candidate = enrolled()
            val ticket = ready(candidate)
            val id = candidate.installation.id
            when (change) {
                "SECRET" -> f.observer.update("UPDATE app_installations SET secret_verifier = ? WHERE id = ?", ByteArray(32) { 77 }, id)
                "VERSION" -> f.observer.update("UPDATE app_installations SET credential_version = credential_version + 1 WHERE id = ?", id)
                "SCOPE" -> foreignReservation(id)
                else -> changeState(id, change)
            }
            val before = f.state()
            clearTrace()
            val result = f.refresh(ticket) as SessionRefreshResult.Rejected
            assertEquals(reason, result.reason)
            assertEquals(listOf(EnrollmentFixtureStep.SESSION_IDENTITY, EnrollmentFixtureStep.SESSION_CREDENTIAL), f.jdbc.queries)
            assertTrue(f.jdbc.updates.isEmpty())
            assertEquals(before, f.state())
            assertTrue(f.observations.all { it.second.phase.databaseOutcome() === PersistenceDatabaseOutcome.COMMITTED })
        }
    }

    fun concurrentRefresh() {
        val candidate = enrolled()
        val before = f.state()
        val old = credential(candidate.installation.id)
        val prepared = List(2) { CountDownLatch(1) }
        val enterRefresh = List(2) { CountDownLatch(1) }
        val firstEntered = CountDownLatch(1)
        val bothEntered = CountDownLatch(2)
        val releaseSql = CountDownLatch(1)
        clearTrace()
        f.jdbc.beforeQuery = { step ->
            if (step === EnrollmentFixtureStep.SESSION_IDENTITY) {
                firstEntered.countDown()
                bothEntered.countDown()
                assertTrue(releaseSql.await(1_500, TimeUnit.MILLISECONDS))
            }
        }
        OwnedCallerTestScope().use { callers ->
            callers.beforeClose {
                enterRefresh.forEach(CountDownLatch::countDown)
                releaseSql.countDown()
            }
            val calls = (0..1).map { index ->
                val call = callers.launch {
                    val ticket = (f.sessionExecutor().preflight(candidate) as SessionPreflightResult.Ready).continuation
                    requireConnectionFree()
                    prepared[index].countDown()
                    assertTrue(enterRefresh[index].await(5, TimeUnit.SECONDS))
                    f.sessionExecutor().refresh(ticket) as SessionRefreshResult.Refreshed
                }
                assertTrue(prepared[index].await(1_500, TimeUnit.MILLISECONDS))
                call
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            enterRefresh[0].countDown()
            assertTrue(firstEntered.await((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS))
            enterRefresh[1].countDown()
            assertTrue(bothEntered.await((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS))
            releaseSql.countDown()
            calls.forEach { assertEquals(1L, it.value().credentialVersion) }
        }
        f.assertReleased()
        val current = credential(candidate.installation.id)
        assertEquals(old.fixed, current.fixed)
        assertTrue(current.activity >= old.activity)
        assertEquals(old.rowVersion + 2, current.rowVersion)
        assertEquals(List(2) { EnrollmentFixtureStep.SESSION_REFRESH to 1 }, f.jdbc.updates)
        val after = f.state()
        assertEquals(before.copy(credentials = after.credentials), after)
    }

    fun exactCustody() {
        val candidate = enrolled()
        val fake = object : InstallationSessionPreflight {
            override val installation = candidate.installation
            override val credentialVersion = 1L
        }
        val before = f.state()
        val fabricated = listOf(
            SessionPreflightResult.Ready(fake),
            SessionPreflightResult.Rejected(InstallationSessionRejection.INSTALLATION_NOT_FOUND),
        )
        for (result in fabricated) {
            rolledBack {
                f.preflight(
                    candidate,
                    object : ComplaintInstallationSession by f.sessionStore {
                        override fun preflight(candidate: InstallationSessionCandidate): SessionPreflightResult = result
                    },
                )
            }
        }
        rolledBack { f.refresh(fake) }
        rolledBack {
            f.preflight(
                candidate,
                object : ComplaintInstallationSession by f.sessionStore {
                    override fun preflight(candidate: InstallationSessionCandidate): SessionPreflightResult =
                        SessionPreflightResult.Ready((f.sessionStore.preflight(candidate) as SessionPreflightResult.Ready).continuation)
                },
            )
        }
        val phase = f.ordinary.ownership.enterComplaintInstallationSessionPreflight()
        val captured: InstallationSessionPreflight
        try {
            phase.begin()
            val raw = f.sessionStore.preflight(candidate) as SessionPreflightResult.Ready
            captured = raw.continuation
            phase.installationSession.checkWork(raw)
            phase.commit()
            val notReleased = assertThrows<PersistencePhaseException> { ComplaintInstallationSessionOperation.releasePreflight(phase, raw) }
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, notReleased.databaseOutcome)
            assertFalse(notReleased.cleanupProven)
        } finally {
            phase.finish()
        }
        // A known commit alone is insufficient: nobody released this continuation after actual finalization.
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
        rolledBack { f.refresh(captured) }
        val ticket = ready(candidate)
        rolledBack {
            f.refresh(
                ticket,
                object : ComplaintInstallationSession by f.sessionStore {
                    override fun refresh(preflight: InstallationSessionPreflight): SessionRefreshResult {
                        val real = f.sessionStore.refresh(preflight) as SessionRefreshResult.Refreshed
                        return SessionRefreshResult.Refreshed(real.installation, real.credentialVersion, real.issuedAt)
                    }
                },
            )
        }
        assertEquals(before, f.state())
        rolledBack { f.refresh(ticket) }
    }

    fun issuerCallerAndAmbient() {
        val candidate = enrolled()
        val ticket = ready(candidate)
        val before = f.state()
        clearTrace()
        assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, assertThrows<PersistencePhaseException> { f.sessionStore.refresh(ticket) }.code)
        rolledBack { f.refresh(ticket, JdbcComplaintInstallationSessionStore(f.jdbc)) }
        OwnedCallerTestScope().use { callers ->
            callers.launch { rolledBack { f.sessionExecutor().refresh(ticket) } }.value()
        }
        f.assertReleased()
        val phase = f.ordinary.ownership.enterSourceGrantCleanup()
        try {
            phase.begin()
            assertThrows<PersistencePhaseException> { f.sessionExecutor().refresh(ticket) }
            assertThrows<PersistencePhaseException> { phase.commit() }
        } finally {
            phase.finish()
        }
        rolledBack { phase.result(0) }
        assertTrue(f.jdbc.queries.isEmpty())
        assertTrue(f.jdbc.updates.isEmpty())
        assertEquals(before, f.state())
        assertTrue(f.refresh(ticket) is SessionRefreshResult.Refreshed)
        val after = f.state()
        rolledBack { f.refresh(ticket) }
        assertEquals(after, f.state())
    }

    fun differentOwner(other: OrdinarySourceGrantCleanupFixture) {
        val candidate = enrolled()
        val movable = JdbcTemplate(f.ordinary.pool)
        val sameStore = JdbcComplaintInstallationSessionStore(movable)
        val ticket = (f.preflight(candidate, sameStore) as SessionPreflightResult.Ready).continuation
        val before = f.state()
        movable.dataSource = other.pool
        try {
            rolledBack { ComplaintInstallationSessionPhaseExecutor(other.ownership, sameStore).refresh(ticket) }
            assertEquals(0, other.admission.activeOwners())
            requireConnectionFree()
        } finally {
            movable.dataSource = f.ordinary.pool
        }
        assertEquals(before, f.state())
        assertTrue(f.refresh(ticket, sameStore) is SessionRefreshResult.Refreshed)
    }

    fun failedCompletion() {
        val candidate = enrolled()
        var ticket = ready(candidate)
        val before = f.state()
        clearTrace()
        f.afterStep = { if (it === EnrollmentFixtureStep.SESSION_REFRESH) throw SyntheticInstallationEnrollmentFailure() }
        rolledBack { f.refresh(ticket) }
        assertEquals(listOf(EnrollmentFixtureStep.SESSION_REFRESH to 1), f.jdbc.updates)
        assertEquals(before, f.state())
        rolledBack { f.refresh(ticket) }

        clearTrace()
        ticket = ready(candidate)
        clearTrace()
        val armed = AtomicBoolean()
        f.afterStep = { step ->
            if (step === EnrollmentFixtureStep.SESSION_REFRESH) {
                f.ordinary.jdbc.execute("CREATE TEMP TABLE kira_session_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                assertEquals(2, f.ordinary.jdbc.update("INSERT INTO kira_session_commit VALUES (1), (1)"))
                armed.set(true)
            }
        }
        val failure = assertThrows<PersistencePhaseException> { f.refresh(ticket) }
        assertTrue(armed.get(), "The real update and both deferred conflicting inserts must precede the actual COMMIT failure.")
        assertEquals(PersistenceDatabaseOutcome.UNKNOWN, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        assertEquals(listOf(EnrollmentFixtureStep.SESSION_REFRESH to 1), f.jdbc.updates)
        assertEquals(before, f.state())
        f.assertReleased()
        clearTrace()
        rolledBack { f.refresh(ticket) }
        assertTrue(f.jdbc.queries.isEmpty())
        assertEquals(before, f.state())
    }

    private fun enrolled(): InstallationSessionCandidate {
        val enrollment = f.candidate(platform = ComplaintPlatform.IOS)
        f.execute(enrollment)
        return f.sessionCandidate(enrollment.installation.id)
    }

    private fun ready(candidate: InstallationSessionCandidate): InstallationSessionPreflight =
        (f.preflight(candidate) as SessionPreflightResult.Ready).continuation

    private fun rejected(candidate: InstallationSessionCandidate, reason: InstallationSessionRejection) {
        val before = f.state()
        clearTrace()
        f.afterStep = { step ->
            if (step === EnrollmentFixtureStep.SESSION_SNAPSHOT) {
                assertEquals("on", f.ordinary.jdbc.queryForObject("SHOW transaction_read_only", String::class.java))
            }
        }
        assertEquals(reason, (f.preflight(candidate) as SessionPreflightResult.Rejected).reason)
        assertEquals(listOf(EnrollmentFixtureStep.SESSION_SNAPSHOT), f.jdbc.queries)
        assertTrue(f.jdbc.updates.isEmpty())
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, f.observations.single().second.phase.databaseOutcome())
        assertEquals(before, f.state())
    }

    private fun changeState(id: UUID, state: String) {
        when (state) {
            "DELETION_PENDING" -> f.observer.update("UPDATE app_installations SET state = 'DELETION_PENDING' WHERE id = ?", id)

            "DELETED" -> f.observer.update(
                "UPDATE app_installations SET state = 'DELETED', platform = NULL, owner_reference = NULL, last_authenticated_at = NULL, " +
                    "deleted_at = now(), verifier_expires_at = now() + interval '192 hours' WHERE id = ?",
                id,
            )

            else -> f.observer.update("DELETE FROM app_installations WHERE id = ?", id)
        }
        val identityState = if (state == "DELETED_EMPTY") "DELETED" else state
        f.observer.update(
            "UPDATE complaint_installation_ids SET state = ?, terminal_at = CASE WHEN ? IN ('DELETED','RETIRED') THEN now() ELSE NULL END WHERE id = ?",
            identityState,
            identityState,
            id,
        )
    }

    private fun foreignReservation(id: UUID) {
        f.observer.update("DELETE FROM app_installations WHERE id = ?", id)
        f.observer.update(
            "UPDATE complaint_installation_ids SET data_scope_id = ?, test_only = true, state = 'RECOVERY_RESERVED', terminal_at = NULL WHERE id = ?",
            UUID.randomUUID(),
            id,
        )
    }

    private fun clearTrace() {
        f.observations.clear()
        f.jdbc.queries.clear()
        f.jdbc.updates.clear()
        f.jdbc.beforeQuery = {}
        f.jdbc.beforeUpdate = {}
        f.afterStep = {}
    }

    private fun credential(id: UUID): SessionCredentialSnapshot = f.observer.queryForObject(
        "SELECT (to_jsonb(c) - ARRAY['last_authenticated_at','version'])::text, last_authenticated_at, version FROM app_installations c WHERE id = ?",
        { row, _ -> SessionCredentialSnapshot(row.getString(1), row.getTimestamp(2).toInstant(), row.getLong(3)) },
        id,
    )!!

    private fun databaseTime(jdbc: JdbcTemplate): Instant = jdbc.queryForObject("SELECT clock_timestamp()", { row, _ -> row.getTimestamp(1).toInstant() })!!
}

private data class SessionCredentialSnapshot(val fixed: String, val activity: Instant, val rowVersion: Long)

private fun assertInstallationSchema(f: OrdinaryComplaintInstallationEnrollmentFixture) {
    assertColumns(
        f,
        "complaint_installation_ids",
        "id:uuid,data_scope_id:uuid,test_only:boolean,state:character varying(24)," +
            "created_at:timestamp with time zone,terminal_at:timestamp with time zone",
    )
    assertColumns(
        f,
        "app_installations",
        "id:uuid,data_scope_id:uuid,test_only:boolean,secret_verifier:bytea,platform:character varying(8),state:character varying(24)," +
            "credential_version:bigint,owner_reference:uuid,created_at:timestamp with time zone,last_authenticated_at:timestamp with time zone," +
            "deleted_at:timestamp with time zone,verifier_expires_at:timestamp with time zone,version:bigint",
    )
    assertIndexes(
        f,
        "complaint_installation_ids",
        mapOf(
            "pk_complaint_installation_ids" to "id",
            "uq_complaint_installation_scope" to "id,data_scope_id",
            "idx_complaint_installation_scope" to "data_scope_id,id",
        ),
    )
    assertIndexes(
        f,
        "app_installations",
        mapOf(
            "pk_app_installations" to "id",
            "uq_app_installations_scope" to "id,data_scope_id",
            "uq_app_installations_owner_reference" to "owner_reference",
            "idx_app_installations_activity" to "state,last_authenticated_at,id",
            "idx_app_installations_expiry" to "verifier_expires_at,id",
            "idx_app_installations_scope" to "data_scope_id,id",
        ),
    )
}

private fun assertLifecycleTupleBounds(f: OrdinaryComplaintInstallationEnrollmentFixture, id: UUID) {
    // Synthetic shape changes for maximum-size evidence only: no retirement/deletion writer authority is claimed.
    for (state in listOf("ACTIVE", "DELETION_PENDING", "DELETED")) {
        if (state == "DELETED") {
            f.observer.update(
                "UPDATE app_installations SET state = 'DELETED', platform = NULL, owner_reference = NULL, last_authenticated_at = NULL, " +
                    "deleted_at = now(), verifier_expires_at = now() + interval '192 hours' WHERE id = ?",
                id,
            )
        } else {
            f.observer.update(
                "UPDATE app_installations SET state = ?, credential_version = ?, version = ? WHERE id = ?",
                state,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                id,
            )
        }
        assertTupleBounds(
            f,
            "app_installations",
            id,
            "ROW(id),ROW(id,data_scope_id),ROW(owner_reference),ROW(state,last_authenticated_at,id),ROW(verifier_expires_at,id),ROW(data_scope_id,id)",
            ComplaintCapacityCharges.INSTALLATION_CREDENTIAL[ComplaintCapacityCounter.STORAGE_BYTES],
        )
    }
    f.observer.update("DELETE FROM app_installations WHERE id = ?", id)
    for (state in listOf("ACTIVE", "DELETION_PENDING", "RECOVERY_RESERVED", "RETIRED", "DELETED")) {
        f.observer.update(
            "UPDATE complaint_installation_ids SET state = ?, terminal_at = CASE WHEN ? IN ('RETIRED','DELETED') THEN now() ELSE NULL END WHERE id = ?",
            state,
            state,
            id,
        )
        assertTupleBounds(
            f,
            "complaint_installation_ids",
            id,
            "ROW(id),ROW(id,data_scope_id),ROW(data_scope_id,id)",
            ComplaintCapacityCharges.INSTALLATION_ID[ComplaintCapacityCounter.STORAGE_BYTES],
        )
    }
}

private fun rolledBack(work: () -> Unit) {
    val failure = assertThrows<PersistencePhaseException> { work() }
    assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
    assertTrue(failure.cleanupProven)
}

private fun finishThread(thread: Thread) {
    thread.join(3_000)
    if (thread.isAlive) {
        thread.interrupt()
        thread.join(2_000)
    }
    assertFalse(thread.isAlive, "The owned synchronous caller must finish before fixture cleanup.")
}

private fun assertColumns(f: OrdinaryComplaintInstallationEnrollmentFixture, table: String, expected: String) {
    assertEquals(
        expected,
        f.observer.queryForObject(
            "SELECT string_agg(attname || ':' || format_type(atttypid, atttypmod), ',' ORDER BY attnum) " +
                "FROM pg_attribute WHERE attrelid = ?::regclass AND attnum > 0 AND NOT attisdropped",
            String::class.java,
            table,
        ),
    )
}

private fun assertIndexes(f: OrdinaryComplaintInstallationEnrollmentFixture, table: String, expected: Map<String, String>) {
    val actual = f.observer.query(
        "SELECT idx.relname, string_agg(pg_get_indexdef(i.indexrelid, n, true), ',' ORDER BY n), " +
            "i.indpred IS NOT NULL, i.indnatts = i.indnkeyatts, am.amname " +
            "FROM pg_index i JOIN pg_class idx ON idx.oid = i.indexrelid JOIN pg_am am ON idx.relam = am.oid " +
            "CROSS JOIN LATERAL generate_series(1, i.indnkeyatts) n WHERE i.indrelid = ?::regclass " +
            "GROUP BY idx.relname, (i.indpred IS NOT NULL), i.indnatts, i.indnkeyatts, am.amname ORDER BY idx.relname",
        { row, _ ->
            assertEquals(row.getString(1) == "idx_app_installations_expiry", row.getBoolean(3))
            assertTrue(row.getBoolean(4), "An unmeasured INCLUDE tuple must not hide in the catalogue.")
            assertEquals("btree", row.getString(5))
            row.getString(1) to row.getString(2)
        },
        table,
    ).toMap()
    assertEquals(expected, actual)
}

private fun assertTupleBounds(f: OrdinaryComplaintInstallationEnrollmentFixture, table: String, id: UUID, keys: String, charge: Long) {
    // Match each ROW(...) expression, not each comma-separated field within a composite key.
    val indexKeys = Regex("ROW\\([^)]*\\)").findAll(keys).map { "pg_column_size(${it.value})" }.toList()
    val sizes = f.observer.queryForObject(
        "SELECT pg_column_size(r), ${indexKeys.joinToString(",")} FROM $table r WHERE id = ?",
        { row, _ -> (1..indexKeys.size + 1).map(row::getInt) },
        id,
    )!!
    assertTrue(sizes.first() in 1 until 512)
    assertTrue(sizes.drop(1).all { it in 1 until 128 })
    assertTrue(charge >= (512L + 128L * indexKeys.size) * 8, "Logical charge includes a conservative margin over every tuple bound.")
    assertTrue(charge > sizes.sum())
}
