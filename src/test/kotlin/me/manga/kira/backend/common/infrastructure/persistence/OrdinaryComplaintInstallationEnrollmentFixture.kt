package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.ComplaintInstallationEnrollmentAudit
import me.manga.kira.backend.audit.infrastructure.JpaAuditRepositoryAdapter
import me.manga.kira.backend.audit.infrastructure.SpringDataAuditLogRepository
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationEnrollment
import me.manga.kira.backend.complaint.domain.ComplaintInstallationSession
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentCandidate
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentDisposition
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentRejection
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentResult
import me.manga.kira.backend.complaint.domain.InstallationSessionCandidate
import me.manga.kira.backend.complaint.domain.InstallationSessionPreflight
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.SessionPreflightResult
import me.manga.kira.backend.complaint.domain.SessionRefreshResult
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintInstallationSessionStore
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintInstallationEnrollmentStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationEnrollmentPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationSessionPhaseExecutor
import me.manga.kira.backend.security.CurrentUser
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Date
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Existing owned pool/PG/JPA and exact counter restoration only; no separate controller/database harness. */
internal fun withOrdinaryComplaintInstallationEnrollment(
    database: PgLifecycleDatabaseFixture,
    maximumPoolSize: Int = 2,
    test: (OrdinaryComplaintInstallationEnrollmentFixture) -> Unit,
) {
    withOrdinarySourceGrantCleanup(database, SystemPersistenceNanoClock, maximumPoolSize, includeAuditEntities = true) { ordinary ->
        SyntheticComplaintCounters(ordinary.foreignTemplate(), ordinary.cutoff).use { counters ->
            counters.seed(0, closed = false)
            OrdinaryComplaintInstallationEnrollmentFixture(ordinary, counters).use { fixture ->
                fixture.seedDaily(fixture.databaseDay(), 0, 100)
                test(fixture)
            }
        }
    }
}

/** Synthetic configuration is not an authenticated mode/headroom/reconciliation producer. */
internal class OrdinaryComplaintInstallationEnrollmentFixture(val ordinary: OrdinarySourceGrantCleanupFixture, val counters: SyntheticComplaintCounters) :
    AutoCloseable {
    val observer = ordinary.foreignTemplate()
    val ids = CopyOnWriteArrayList<UUID>()
    val auditIds = CopyOnWriteArrayList<Long>()
    val jdbc = InstallationEnrollmentFixtureJdbc(this)
    val capacity = JdbcComplaintCapacityStore(jdbc, counters.syntheticPolicyDigest())
    val repository = JpaAuditRepositoryAdapter(
        JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(ordinary.entityManagerFactory))
            .getRepository(SpringDataAuditLogRepository::class.java),
    )
    val service = AuditService(repository, CurrentUser(), Clock.fixed(ordinary.cutoff, ZoneOffset.UTC))
    val audit = ComplaintInstallationEnrollmentAudit { scope, allocation, at ->
        service.recordInstallationEnrollment(scope, allocation, at)
        auditIds.add(checkNotNull(ordinary.jdbc.queryForObject("SELECT currval(pg_get_serial_sequence('audit_log', 'id'))", Long::class.java)))
        checkpoint(EnrollmentFixtureStep.AUDIT)
    }
    val store = JdbcComplaintInstallationEnrollmentStore(jdbc, capacity, audit)
    val sessionStore = JdbcComplaintInstallationSessionStore(jdbc)
    val observations = CopyOnWriteArrayList<Pair<EnrollmentFixtureStep, StepUpPhaseObservation>>()
    var afterStep: (EnrollmentFixtureStep) -> Unit = {}
    private val assertionFailure = AtomicReference<AssertionError?>()

    fun candidate(
        id: UUID = UUID.randomUUID(),
        scope: ComplaintDataScope = ComplaintDataScope.LIVE,
        platform: ComplaintPlatform = ComplaintPlatform.ANDROID,
        secret: ByteArray = ByteArray(32) { it.toByte() },
    ): InstallationEnrollmentCandidate {
        ids.addIfAbsent(id)
        return InstallationEnrollmentCredentials.prepare(ScopedInstallationId(id, scope), platform, secret)
    }

    fun executor(port: ComplaintInstallationEnrollment = store): ComplaintInstallationEnrollmentPhaseExecutor =
        ComplaintInstallationEnrollmentPhaseExecutor(ordinary.ownership, port)

    fun execute(candidate: InstallationEnrollmentCandidate, port: ComplaintInstallationEnrollment = store): InstallationEnrollmentResult.Enrolled =
        assertInstanceOf(InstallationEnrollmentResult.Enrolled::class.java, attempt(candidate, port))

    fun attempt(candidate: InstallationEnrollmentCandidate, port: ComplaintInstallationEnrollment = store): InstallationEnrollmentResult {
        val outcome = runCatching { executor(port).enroll(candidate) }
        assertReleased()
        return outcome.getOrThrow()
    }

    fun rejected(candidate: InstallationEnrollmentCandidate, reason: InstallationEnrollmentRejection): InstallationEnrollmentResult.Rejected {
        val before = state()
        observations.clear()
        jdbc.queries.clear()
        jdbc.updates.clear()
        jdbc.databaseTimes.clear()
        val result = assertInstanceOf(InstallationEnrollmentResult.Rejected::class.java, attempt(candidate))
        assertEquals(reason, result.reason)
        assertEquals(before, state())
        assertTrue(jdbc.updates.isEmpty())
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, observations.last().second.phase.databaseOutcome())
        return result
    }

    fun sessionCandidate(id: UUID, secret: ByteArray = ByteArray(32) { it.toByte() }): InstallationSessionCandidate =
        InstallationEnrollmentCredentials.prepareSession(ScopedInstallationId(id, ComplaintDataScope.LIVE), secret)

    fun sessionExecutor(port: ComplaintInstallationSession = sessionStore): ComplaintInstallationSessionPhaseExecutor =
        ComplaintInstallationSessionPhaseExecutor(ordinary.ownership, port)

    fun preflight(candidate: InstallationSessionCandidate, port: ComplaintInstallationSession = sessionStore): SessionPreflightResult {
        val outcome = runCatching { sessionExecutor(port).preflight(candidate) }
        assertReleased()
        return outcome.getOrThrow()
    }

    fun refresh(preflight: InstallationSessionPreflight, port: ComplaintInstallationSession = sessionStore): SessionRefreshResult {
        val outcome = runCatching { sessionExecutor(port).refresh(preflight) }
        assertReleased()
        return outcome.getOrThrow()
    }

    fun databaseDay(): LocalDate = checkNotNull(
        observer.queryForObject(
            "SELECT (clock_timestamp() AT TIME ZONE 'UTC')::date",
            { row, _ -> row.getDate(1).toLocalDate() },
        ),
    )

    fun seedDaily(day: LocalDate?, count: Long, limit: Long) {
        assertEquals(
            1,
            observer.update(
                "UPDATE complaint_capacity_counters SET admission_utc_date = ?, admission_count = ?, admission_daily_limit = ? WHERE name = 'installation_ids'",
                day?.let(Date::valueOf),
                count,
                limit,
            ),
        )
    }

    fun state(): EnrollmentFixtureState = EnrollmentFixtureState(
        ids.sorted().flatMap { rowJson("complaint_installation_ids", it) },
        ids.sorted().flatMap { rowJson("app_installations", it) },
        auditIds.sorted().flatMap { rowJson("audit_log", it) },
        observer.query(
            "SELECT name, to_jsonb(c)::text, " +
                "(to_jsonb(c) - ARRAY['free_units','actual_units','updated_at','admission_utc_date','admission_count'])::text, " +
                "free_units, actual_units, admission_utc_date, admission_count FROM complaint_capacity_counters c ORDER BY name",
            { row, _ ->
                row.getString(1) to EnrollmentCounterSnapshot(
                    row.getString(2),
                    row.getString(3),
                    row.getLong(4),
                    row.getLong(5),
                    row.getDate(6)?.toLocalDate(),
                    row.getLong(7).let { if (row.wasNull()) null else it },
                )
            },
        ).toMap(),
    )

    fun assertCreationCharge(before: EnrollmentFixtureState) {
        val after = state()
        assertEquals(22, after.counters.size)
        for (counter in ComplaintCapacityCounter.entries) {
            val old = before.counters.getValue(counter.storedName)
            val current = after.counters.getValue(counter.storedName)
            val charge = ComplaintCapacityCharges.INSTALLATION_ENROLLMENT[counter]
            if (charge == 0L) {
                assertEquals(old, current)
            } else {
                assertEquals(old.preserved, current.preserved)
                assertEquals(old.free - charge, current.free)
                assertEquals(old.actual + charge, current.actual)
            }
        }
    }

    fun checkpoint(step: EnrollmentFixtureStep) = preserveAssertions {
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

    private fun rowJson(table: String, id: Any): List<String> =
        observer.queryForList("SELECT to_jsonb(r)::text FROM $table r WHERE id = ?", String::class.java, id)

    override fun close() {
        assertReleased()
        auditIds.forEach { observer.update("DELETE FROM audit_log WHERE id = ?", it) }
        ids.forEach { observer.update("DELETE FROM app_installations WHERE id = ?", it) }
        ids.forEach { observer.update("DELETE FROM complaint_installation_ids WHERE id = ?", it) }
    }
}

internal data class EnrollmentCounterSnapshot(
    val full: String,
    val preserved: String,
    val free: Long,
    val actual: Long,
    val day: LocalDate?,
    val dailyCount: Long?,
)

internal data class EnrollmentFixtureState(
    val identities: List<String>,
    val credentials: List<String>,
    val audits: List<String>,
    val counters: Map<String, EnrollmentCounterSnapshot>,
)

internal enum class EnrollmentFixtureStep {
    COUNTERS,
    TEST_RUN,
    IDENTITY,
    CREDENTIAL,
    DATABASE_TIME,
    CHARGE,
    DAILY,
    TEST_RUN_UPDATE,
    IDENTITY_INSERT,
    CREDENTIAL_INSERT,
    AUDIT,
    REPLAY,
    SESSION_SNAPSHOT,
    SESSION_IDENTITY,
    SESSION_CREDENTIAL,
    SESSION_TIME,
    SESSION_REFRESH,
}

/** Observations/faults bracket real SQL; no returned row/update count is synthesized. */
internal class InstallationEnrollmentFixtureJdbc(private val fixture: OrdinaryComplaintInstallationEnrollmentFixture) : JdbcTemplate(fixture.ordinary.pool) {
    val updates = CopyOnWriteArrayList<Pair<EnrollmentFixtureStep, Int>>()
    val queries = CopyOnWriteArrayList<EnrollmentFixtureStep>()
    val databaseTimes = CopyOnWriteArrayList<Instant>()
    var beforeQuery: (EnrollmentFixtureStep) -> Unit = {}
    var beforeUpdate: (EnrollmentFixtureStep) -> Unit = {}

    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>): List<T> {
        val step = queryStep(sql)
        step?.let {
            fixture.preserveAssertions {
                queries.add(it)
                beforeQuery(it)
            }
        }
        val observedMapper = if (step === EnrollmentFixtureStep.DATABASE_TIME) {
            RowMapper<T> { row, index ->
                databaseTimes.add(checkNotNull(row.getTimestamp("observed_at")).toInstant())
                rowMapper.mapRow(row, index)
            }
        } else {
            rowMapper
        }
        return super.query(sql, observedMapper).also { step?.let(fixture::checkpoint) }
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
        val step = updateStep(sql)
        step?.let { fixture.preserveAssertions { beforeUpdate(it) } }
        return super.update(sql, *args).also { count ->
            if (step != null) {
                updates.add(step to count)
                if (count == 1) fixture.checkpoint(step)
            }
        }
    }

    private fun queryStep(sql: String): EnrollmentFixtureStep? = when {
        sql.contains("FROM (VALUES (?::uuid)) AS requested(id)") -> EnrollmentFixtureStep.SESSION_SNAPSHOT
        sql.contains("FROM (VALUES (?::uuid, ?::uuid)) AS requested(id, data_scope_id)") -> EnrollmentFixtureStep.SESSION_SNAPSHOT
        sql.contains("FROM complaint_test_runs r WHERE") -> EnrollmentFixtureStep.TEST_RUN
        sql.contains("FROM complaint_installation_ids i WHERE") -> EnrollmentFixtureStep.SESSION_IDENTITY
        sql.contains("FROM app_installations c WHERE") -> EnrollmentFixtureStep.SESSION_CREDENTIAL
        sql.startsWith("WITH session_time AS MATERIALIZED") -> EnrollmentFixtureStep.SESSION_TIME
        sql.startsWith("SELECT name, ordinal, accounting_version") -> EnrollmentFixtureStep.COUNTERS
        sql.contains("FROM complaint_installation_ids WHERE") -> EnrollmentFixtureStep.IDENTITY
        sql.contains("FROM app_installations WHERE") -> EnrollmentFixtureStep.CREDENTIAL
        sql.startsWith("WITH sampled AS MATERIALIZED") -> EnrollmentFixtureStep.DATABASE_TIME
        else -> null
    }

    private fun updateStep(sql: String): EnrollmentFixtureStep? = when {
        sql.startsWith("UPDATE complaint_test_runs SET enrolled_count") -> EnrollmentFixtureStep.TEST_RUN_UPDATE

        sql.startsWith("UPDATE complaint_capacity_counters") ->
            if (sql.contains("SET admission_utc_date")) EnrollmentFixtureStep.DAILY else EnrollmentFixtureStep.CHARGE

        sql.startsWith("INSERT INTO complaint_installation_ids") -> EnrollmentFixtureStep.IDENTITY_INSERT

        sql.startsWith("INSERT INTO app_installations") -> EnrollmentFixtureStep.CREDENTIAL_INSERT

        sql.startsWith("UPDATE app_installations") -> if (sql.contains("AND secret_verifier = ? AND credential_version = ? AND version = ?")) {
            EnrollmentFixtureStep.SESSION_REFRESH
        } else {
            EnrollmentFixtureStep.REPLAY
        }

        else -> null
    }
}

internal class SyntheticInstallationEnrollmentFailure : RuntimeException("Synthetic enrollment boundary failure.")

/** New cases on the same real owned enrollment fixture; direct row staging is synthetic, never recovery/mode authority. */
internal class InstallationEnrollmentOutcomeCases(private val f: OrdinaryComplaintInstallationEnrollmentFixture) {
    fun releasedFacts() {
        val candidate = f.candidate()
        var captured: InstallationEnrollmentResult? = null
        val enrolled = f.execute(
            candidate,
            ComplaintInstallationEnrollment { input ->
                f.store.enroll(input).also { captured = it }
            },
        )
        assertSame(captured, enrolled)
        assertEquals(candidate.installation, enrolled.installation)
        assertEquals(1L, enrolled.credentialVersion)
        assertEquals(f.jdbc.databaseTimes.single(), enrolled.issuedAt)
        assertNotEquals(f.ordinary.cutoff, enrolled.issuedAt)
        val initial = f.observer.queryForObject(
            "SELECT i.created_at, c.created_at, c.last_authenticated_at FROM complaint_installation_ids i " +
                "JOIN app_installations c ON c.id = i.id WHERE i.id = ?",
            { row, _ -> List(3) { index -> row.getTimestamp(index + 1).toInstant() } },
            candidate.installation.id,
        )!!
        assertEquals(List(3) { enrolled.issuedAt }, initial)
        val future = f.observer.queryForObject(
            "UPDATE app_installations SET last_authenticated_at = clock_timestamp() + interval '1 day' WHERE id = ? RETURNING last_authenticated_at",
            { row, _ -> row.getTimestamp(1).toInstant() },
            candidate.installation.id,
        )!!
        val before = f.state()
        f.jdbc.databaseTimes.clear()
        val replay = f.execute(candidate)
        assertEquals(InstallationEnrollmentDisposition.EXACT_REPLAY, replay.disposition)
        assertEquals(candidate.installation, replay.installation)
        assertEquals(enrolled.credentialVersion, replay.credentialVersion)
        assertEquals(f.jdbc.databaseTimes.single(), replay.issuedAt)
        assertTrue(replay.issuedAt < future)
        assertEquals(
            future,
            f.observer.queryForObject(
                "SELECT last_authenticated_at FROM app_installations WHERE id = ?",
                { row, _ -> row.getTimestamp(1).toInstant() },
                candidate.installation.id,
            ),
        )
        val after = f.state()
        assertEquals(before.counters, after.counters)
        assertEquals(before.identities, after.identities)
        assertEquals(before.audits, after.audits)
        assertEquals("InstallationEnrollmentResult.Enrolled(redacted)", replay.toString())
    }

    fun dailyOutcomes() {
        for (daysAhead in listOf(0L, 2L)) {
            val retained = f.databaseDay().plusDays(daysAhead)
            f.seedDaily(retained, 1, 1)
            val rejection = f.rejected(f.candidate(), InstallationEnrollmentRejection.DAILY_LIMIT_REACHED)
            val sampled = f.jdbc.databaseTimes.single()
            val reset = retained.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
            val duration = Duration.between(sampled, reset)
            assertEquals(duration.seconds + if (duration.nano == 0) 0L else 1L, rejection.retryAfterSeconds)
            if (daysAhead > 0) assertTrue(checkNotNull(rejection.retryAfterSeconds) > 86_400)
        }
        for (retained in listOf(null, f.databaseDay().plusDays(2))) {
            f.seedDaily(retained, 0, 0)
            val rejection = f.rejected(f.candidate(), InstallationEnrollmentRejection.CAPACITY_UNAVAILABLE)
            assertNull(rejection.retryAfterSeconds, "A zero limit cannot promise a reset into nonzero capacity.")
        }
        f.seedDaily(f.databaseDay().plusDays(2), 1, 1)
        f.observer.update("UPDATE complaint_capacity_counters SET configuration_closed = true")
        val closed = f.rejected(f.candidate(), InstallationEnrollmentRejection.CAPACITY_UNAVAILABLE)
        assertNull(closed.retryAfterSeconds, "Creation closure takes precedence over a retrying daily bucket.")
        f.observer.update("UPDATE complaint_capacity_counters SET configuration_closed = false")
        f.seedDaily(f.databaseDay(), 0, 10)
        f.observer.update(
            "UPDATE complaint_capacity_counters SET hard_limit = ?, creation_limit = ?, actual_units = ?, free_units = 0, " +
                "recovery_reserved_units = 0, test_reserved_units = 0 WHERE name = 'storage_bytes'",
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
        )
        val full = f.rejected(f.candidate(), InstallationEnrollmentRejection.CAPACITY_UNAVAILABLE)
        assertNull(full.retryAfterSeconds, "A valid full Long ceiling is lifetime capacity, not arithmetic or rate-limit fiction.")
    }

    fun credentialRejections() {
        val candidate = f.candidate()
        f.execute(candidate)
        f.rejected(
            f.candidate(candidate.installation.id, platform = ComplaintPlatform.IOS, secret = ByteArray(32) { 99 }),
            InstallationEnrollmentRejection.INSTALLATION_CREDENTIAL_REJECTED,
        )
        f.rejected(f.candidate(candidate.installation.id, platform = ComplaintPlatform.IOS), InstallationEnrollmentRejection.INSTALLATION_PLATFORM_MISMATCH)
        val before = f.state()
        storageRefused(f.candidate(candidate.installation.id, scope = ComplaintDataScope.of(UUID.randomUUID())))
        assertEquals(before, f.state())
        val uninsertedTest = f.candidate(scope = ComplaintDataScope.of(UUID.randomUUID()))
        f.jdbc.queries.clear()
        storageRefused(uninsertedTest)
        assertTrue(f.jdbc.queries.isEmpty(), "Unsupported test enrollment must not borrow a reserve share or reach identity SQL.")
        val other = f.candidate()
        f.observer.update(
            "INSERT INTO complaint_installation_ids (id, data_scope_id, test_only, state, created_at) VALUES (?, ?, true, 'RECOVERY_RESERVED', now())",
            other.installation.id,
            UUID.randomUUID(),
        )
        f.rejected(other, InstallationEnrollmentRejection.INSTALLATION_RETIRED)
    }

    fun terminalRejections() {
        for (state in listOf("DELETION_PENDING", "DELETED", "DELETED_EMPTY", "RETIRED", "RECOVERY_RESERVED", "ACTIVE_ORPHAN")) {
            val candidate = f.candidate()
            f.execute(candidate)
            val id = candidate.installation.id
            when (state) {
                "DELETION_PENDING" -> f.observer.update("UPDATE app_installations SET state = 'DELETION_PENDING' WHERE id = ?", id)

                "DELETED" -> f.observer.update(
                    "UPDATE app_installations SET state = 'DELETED', platform = NULL, owner_reference = NULL, last_authenticated_at = NULL, " +
                        "deleted_at = now(), verifier_expires_at = now() + interval '192 hours' WHERE id = ?",
                    id,
                )

                else -> f.observer.update("DELETE FROM app_installations WHERE id = ?", id)
            }
            val identityState = when (state) {
                "ACTIVE_ORPHAN" -> "ACTIVE"
                "DELETED_EMPTY" -> "DELETED"
                else -> state
            }
            f.observer.update(
                "UPDATE complaint_installation_ids SET state = ?, terminal_at = CASE WHEN ? IN ('DELETED','RETIRED') THEN now() ELSE NULL END WHERE id = ?",
                identityState,
                identityState,
                id,
            )
            if (state == "ACTIVE_ORPHAN") {
                val before = f.state()
                storageRefused(candidate)
                assertEquals(before, f.state())
            } else {
                if (state in listOf("DELETION_PENDING", "DELETED")) {
                    f.rejected(f.candidate(id, secret = ByteArray(32) { 99 }), InstallationEnrollmentRejection.INSTALLATION_CREDENTIAL_REJECTED)
                }
                val reason = when (state) {
                    "DELETION_PENDING" -> InstallationEnrollmentRejection.INSTALLATION_DELETION_PENDING
                    "DELETED", "DELETED_EMPTY" -> InstallationEnrollmentRejection.INSTALLATION_DELETED
                    else -> InstallationEnrollmentRejection.INSTALLATION_RETIRED
                }
                f.rejected(candidate, reason)
            }
        }
    }

    fun storedPairOutcomes() {
        for (identityState in listOf("DELETION_PENDING", "RETIRED", "DELETED")) {
            val candidate = f.candidate()
            f.execute(candidate)
            f.observer.update(
                "UPDATE complaint_installation_ids SET state = ?, terminal_at = CASE WHEN ? IN ('RETIRED','DELETED') THEN now() ELSE NULL END WHERE id = ?",
                identityState,
                identityState,
                candidate.installation.id,
            )
            storageRefused(candidate) // A separately valid ACTIVE credential contradicts each selected reservation.
        }
        val overflow = f.candidate()
        f.execute(overflow)
        f.observer.update("UPDATE app_installations SET version = ? WHERE id = ?", Long.MAX_VALUE, overflow.installation.id)
        storageRefused(overflow)
        malformedTime()
        contradictoryScopes()
    }

    private fun malformedTime() {
        val candidate = f.candidate()
        f.execute(candidate)
        val definition = constraint("chk_app_installations_times")
        f.observer.execute("ALTER TABLE app_installations DROP CONSTRAINT chk_app_installations_times")
        try {
            f.observer.update("UPDATE app_installations SET last_authenticated_at = 'infinity'::timestamptz WHERE id = ?", candidate.installation.id)
            storageRefused(candidate)
        } finally {
            f.observer.update("UPDATE app_installations SET last_authenticated_at = created_at WHERE id = ?", candidate.installation.id)
            f.observer.execute("ALTER TABLE app_installations ADD CONSTRAINT chk_app_installations_times $definition")
        }
    }

    private fun contradictoryScopes() {
        val definition = constraint("fk_app_installations_reservation")
        val staged = mutableListOf<UUID>()
        f.observer.execute("ALTER TABLE app_installations DROP CONSTRAINT fk_app_installations_reservation")
        try {
            val orphan = f.candidate()
            staged.add(orphan.installation.id)
            f.execute(orphan)
            f.observer.update("DELETE FROM complaint_installation_ids WHERE id = ?", orphan.installation.id)
            storageRefused(orphan)

            val candidate = f.candidate()
            staged.add(candidate.installation.id)
            f.execute(candidate)
            val testScope = UUID.randomUUID()
            f.observer.update("UPDATE app_installations SET data_scope_id = ?, test_only = true WHERE id = ?", testScope, candidate.installation.id)
            storageRefused(candidate)
            f.observer.update("UPDATE complaint_installation_ids SET data_scope_id = ?, test_only = true WHERE id = ?", testScope, candidate.installation.id)
            f.rejected(f.candidate(candidate.installation.id, secret = ByteArray(32) { 99 }), InstallationEnrollmentRejection.INSTALLATION_CREDENTIAL_REJECTED)
            f.rejected(candidate, InstallationEnrollmentRejection.INSTALLATION_SCOPE_MISMATCH)
        } finally {
            staged.forEach { f.observer.update("DELETE FROM app_installations WHERE id = ?", it) }
            staged.forEach { f.observer.update("DELETE FROM complaint_installation_ids WHERE id = ?", it) }
            f.observer.execute("ALTER TABLE app_installations ADD CONSTRAINT fk_app_installations_reservation $definition")
        }
    }

    fun completionGates() {
        for (rejection in listOf(false, true)) {
            releaseBarrier(rejection)
            for (failure in CompletionFailure.entries) failedCompletion(rejection, failure)
        }
    }

    private fun releaseBarrier(rejection: Boolean) {
        f.seedDaily(f.databaseDay(), 0, if (rejection) 0 else 100)
        val candidate = f.candidate()
        val phase = f.ordinary.ownership.enterComplaintInstallationEnrollment()
        var outcome: InstallationEnrollmentResult? = null
        try {
            phase.begin()
            val actual = f.store.enroll(candidate)
            outcome = actual
            phase.installationEnrollment.checkWork(actual)
            assertEquals(rejection, actual is InstallationEnrollmentResult.Rejected)
            val early = assertThrows<PersistencePhaseException> { phase.installationEnrollment.result(outcome) }
            assertEquals(PersistenceDatabaseOutcome.NONE, early.databaseOutcome)
            assertFalse(early.cleanupProven)
            phase.commit()
            val unreleased = assertThrows<PersistencePhaseException> { phase.installationEnrollment.result(outcome) }
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, unreleased.databaseOutcome)
            assertFalse(unreleased.cleanupProven)
        } finally {
            phase.finish()
        }
        assertSame(outcome, phase.installationEnrollment.result(outcome))
        f.assertReleased()
    }

    private fun failedCompletion(rejection: Boolean, mode: CompletionFailure) {
        f.seedDaily(f.databaseDay(), 0, if (rejection) 0 else 100)
        val candidate = f.candidate()
        val before = f.state()
        var retained: InstallationEnrollmentResult? = null
        var owner: PersistencePhaseContext? = null
        var returned: InstallationEnrollmentResult? = null
        val afterCommit = AtomicReference(false)
        val failure = assertThrows<PersistencePhaseException> {
            returned = f.attempt(
                candidate,
                ComplaintInstallationEnrollment { input ->
                    owner = checkNotNull(PersistencePhaseOwnership.current())
                    val actual = f.store.enroll(input)
                    retained = actual
                    when (mode) {
                        CompletionFailure.ROLLBACK -> throw SyntheticInstallationEnrollmentFailure()

                        CompletionFailure.COMMIT -> {
                            f.ordinary.jdbc.execute("CREATE TEMP TABLE kira_enrollment_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                            check(f.ordinary.jdbc.update("INSERT INTO kira_enrollment_commit VALUES (1), (1)") == 2)
                        }

                        CompletionFailure.TAIL -> TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                            override fun afterCommit() {
                                afterCommit.set(true)
                                throw SyntheticInstallationEnrollmentFailure()
                            }
                        })
                    }
                    actual
                },
            )
        }
        assertEquals(rejection, checkNotNull(retained) is InstallationEnrollmentResult.Rejected)
        assertNull(returned)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertTrue(failure.cleanupProven)
        val expected = when (mode) {
            CompletionFailure.ROLLBACK -> PersistenceDatabaseOutcome.ROLLED_BACK
            CompletionFailure.COMMIT -> PersistenceDatabaseOutcome.UNKNOWN
            CompletionFailure.TAIL -> PersistenceDatabaseOutcome.COMMITTED
        }
        assertEquals(expected, failure.databaseOutcome)
        assertEquals(mode === CompletionFailure.TAIL, afterCommit.get())
        assertThrows<PersistencePhaseException> { checkNotNull(owner).installationEnrollment.result(retained) }
        if (mode !== CompletionFailure.TAIL || rejection) assertEquals(before, f.state())
        f.assertReleased()
    }

    private fun storageRefused(candidate: InstallationEnrollmentCandidate) {
        val before = f.state()
        f.jdbc.updates.clear()
        val failure = assertThrows<PersistencePhaseException> { f.attempt(candidate) }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, failure.databaseOutcome)
        assertTrue(failure.cleanupProven)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertTrue(f.jdbc.updates.isEmpty())
        assertEquals(before, f.state())
    }

    private fun constraint(name: String): String = f.observer.queryForObject(
        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'app_installations'::regclass AND conname = ?",
        String::class.java,
        name,
    )!!

    private enum class CompletionFailure { ROLLBACK, COMMIT, TAIL }
}
