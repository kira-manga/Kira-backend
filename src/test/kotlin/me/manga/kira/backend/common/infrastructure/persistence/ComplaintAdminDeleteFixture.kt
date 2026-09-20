package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.AuditRepository
import me.manga.kira.backend.audit.domain.ComplaintAuditAllocation
import me.manga.kira.backend.audit.domain.CountedAdminDeleteAuditEntry
import me.manga.kira.backend.audit.domain.CountedAdminDeleteAuditRepository
import me.manga.kira.backend.complaint.api.ComplaintAdminDeleteHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintAdminDeleteResponses
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryResponses
import me.manga.kira.backend.complaint.application.ComplaintAdminDeleteService
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeletePrecondition
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRequest
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.CommittedTestAdminDeleteWork
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteCandidate
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminJwtIdentityDecoder
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteApplyStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteReceiptStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteVerificationStore
import me.manga.kira.backend.complaint.infrastructure.TestAdminDeleteAuthorizationV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteLocalGraphV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestAdminDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminDeletePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminDeleteReadPhaseExecutor
import me.manga.kira.backend.complaint.journal.TestOwnerDeleteJournalPublisherFixture
import me.manga.kira.backend.security.AdminReadTestUserJwt
import me.manga.kira.backend.security.CurrentUser
import me.manga.kira.backend.security.IssuedScopedAdminStepUp
import me.manga.kira.backend.security.ScopedAdminStepUpScope
import me.manga.kira.backend.security.TestAdminDeleteJournalTupleV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.adminDeleteTestIngress
import me.manga.kira.backend.security.adminDeleteTestJournal
import me.manga.kira.backend.security.adminDeleteTestRequest
import me.manga.kira.backend.security.ownerCreateTestCapacityPolicy
import me.manga.kira.backend.security.ownerDeleteTestRouting
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource

/** The established real PG/JPA/deletion-pool lifecycle, not a new resource or launcher. */
internal fun withAdminDelete(database: PgLifecycleDatabaseFixture, test: (ComplaintAdminDeleteFixture) -> Unit) =
    withOwnerDeleteAllAuthorization(database) { existing ->
        OrdinaryComplaintTestInstallationFixture(existing.base).use { run ->
            ComplaintAdminDeleteFixture(existing, run).use(test)
        }
    }

/**
 * Source-only tests use synthetic lower run/control comparisons, never registered/current authority.
 * JWT signing/decoding, DB role, scoped issuer, receipts, SQL commit/release and provider readback all
 * retain their actual owners. Neither a work capability nor VERIFIED result is constructed here.
 */
internal class ComplaintAdminDeleteFixture(
    val existing: OwnerDeleteAllAuthorizationFixture,
    val run: OrdinaryComplaintTestInstallationFixture,
) : AutoCloseable {
    val base = existing.base
    val observer = base.observer
    val scope = run.scope
    val policy = ownerCreateTestCapacityPolicy()
    val ingress = adminDeleteTestIngress(policy)
    val routing = ownerDeleteTestRouting(adminDeleteTestJournal(scope))
    val lanes = JournalPublicationLanesV1(existing.routing.journalConfiguration)
    val ordinaryJdbc = AdminDeleteFixtureJdbc(this, base.ordinary.pool, deletion = false)
    val jdbc = AdminDeleteFixtureJdbc(this, existing.pool, deletion = true)
    val graph = TestOwnerDeleteLocalGraphV1(ordinaryJdbc, jdbc, ingress, routing, policy, lanes)
    val desired = graph.desiredSettings()
    val observations = CopyOnWriteArrayList<Pair<String, StepUpPhaseObservation>>()
    val statements = CopyOnWriteArrayList<String>()
    private val deletionObservations = ConcurrentHashMap<PersistencePhaseContext, StepUpPhaseObservation>()
    var beforeSql: (String) -> Unit = {}
    var afterSql: (String) -> Unit = {}
    val creator: ComplaintOwnerCreateFixture
    init {
        existing.installPolicy(policy)
        stageSyntheticComparisons()
        creator = ComplaintOwnerCreateFixture(base, run, desired)
    }
    val userJwt = AdminReadTestUserJwt()
    val decoder = ComplaintAdminJwtIdentityDecoder(scope, userJwt.decoder, userJwt.properties.clockSkew)
    val token = userJwt.signer.issue(user()).value
    val stepUp = ScopedStepUpFixture(base.ordinary, base.counters, policy.digestBytes(), phaseClock = Clock.systemUTC())
    val capacity = JdbcComplaintCapacityStore(jdbc, policy.digestBytes())
    val noDataKeys = NeverOwnerDeleteAllDataKeys()
    val codec = TestOwnerDeleteJournalCodecV1(routing, noDataKeys)
    private val counted = object : AuditRepository by base.repository, CountedAdminDeleteAuditRepository {
        override fun recordAdminDelete(entry: CountedAdminDeleteAuditEntry, allocation: ComplaintAuditAllocation) {
            before("AUDIT")
            base.repository.recordAdminDelete(entry, allocation)
            base.auditIds.add(checkNotNull(jdbc.queryForObject("SELECT currval(pg_get_serial_sequence('audit_log', 'id'))", Long::class.java)))
            after("AUDIT", deletion = true)
        }
    }
    private val audit = AuditService(counted, CurrentUser(), Clock.fixed(base.ordinary.cutoff, ZoneOffset.UTC))
    val store = JdbcComplaintAdminDeleteStore(jdbc, capacity, audit, graph, codec)
    val reads = ComplaintAdminDeleteReadPhaseExecutor(base.ordinary.ownership, JdbcComplaintAdminDeleteReceiptStore(ordinaryJdbc, graph))
    val verification = JdbcComplaintAdminDeleteVerificationStore(jdbc, graph, store)
    val apply = JdbcComplaintAdminDeleteApplyStore(jdbc, capacity, audit, graph, store, verification)
    val phases = ComplaintAdminDeletePhaseExecutor(existing.ownership, store, reads, verification, apply)
    val responseOwner = ComplaintOwnerHistoryResponses()

    fun user(): User = observer.queryForObject(
        "SELECT id, email, password_hash, role, enabled, created_at, updated_at, credential_version FROM users WHERE id = ?",
        { row, _ -> User(row.getObject("id", UUID::class.java), row.getString("email"), row.getString("password_hash"),
            Role.valueOf(row.getString("role")), row.getBoolean("enabled"), row.getTimestamp("created_at").toInstant(),
            row.getTimestamp("updated_at").toInstant(), row.getLong("credential_version")) }, base.ordinary.userId,
    )!!
    fun proof(scope: ScopedAdminStepUpScope = ScopedAdminStepUpScope.COMPLAINT): IssuedScopedAdminStepUp = stepUp.issue(scope)
    fun attempt(id: UUID, version: Long = 1, key: UUID = UUID.randomUUID()): AdminDeleteFixtureAttempt {
        val raw = ComplaintAdminDeleteInput(scope, id, key, ComplaintAdminDeletePrecondition.parse(id, "\"complaint-$id-v$version\""))
        return AdminDeleteFixtureAttempt(raw, ComplaintAdminDeleteCandidate.prepare(base.ordinary.userId, ComplaintAdminDeleteRequest.normalize(raw)))
    }
    fun input(attempt: AdminDeleteFixtureAttempt, proof: String? = null, bearer: String? = token): MockHttpServletRequest =
        adminDeleteTestRequest(scope, attempt.id, attempt.key, attempt.raw.precondition.version, bearer, proof)
    fun http(publishers: TestAdminDeleteJournalPublisherFactoryV1) = ComplaintAdminDeleteHttpHandler(
        ComplaintAdminDeleteService(ComplaintAdminDeleteAdapter(graph, decoder, reads, phases, publishers)), ingress, ComplaintAdminDeleteResponses(responseOwner),
    )
    fun send(request: MockHttpServletRequest, handler: ComplaintAdminDeleteHttpHandler): MockHttpServletResponse =
        MockHttpServletResponse().also { handler.handleRequest(request, it); assertReleased() }
    fun report(bearer: String = creator.token): UUID = creator.attempt().also { assertEquals(201, creator.create(it, bearer = bearer).status) }.id
    fun journalTuple(attempt: AdminDeleteFixtureAttempt, grantId: UUID, owner: UUID = creator.actor.id) =
        TestAdminDeleteJournalTupleV1(11, base.ordinary.userId, attempt.key, attempt.candidate.tuple.fingerprintBytes(), scope, grantId, owner)
    fun wire(attempt: AdminDeleteFixtureAttempt, grantId: UUID, owner: UUID = creator.actor.id): TestOwnerDeleteJournalPublisherFixture {
        val event = codec.canonicalizeAdmin(journalTuple(attempt, grantId, owner), attempt.id)
        return TestOwnerDeleteJournalPublisherFixture(routing, event).also { fixture ->
            fixture.wall = checkNotNull(observer.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)).toInstant()
            fixture.beforeOpen = {
                requireConnectionFree()
                fixture.wall = checkNotNull(observer.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)).toInstant()
            }
            fixture.beforePrepare = ::assertReleased
        }
    }
    fun factory(wire: TestOwnerDeleteJournalPublisherFixture): TestAdminDeleteJournalPublisherFactoryV1 =
        TestAdminDeleteJournalPublisherFactoryV1.withHttpFixture(lanes, store, routing, TestOwnerDeleteJournalPublisherFixture.CREDENTIALS,
            { wire.beforeOpen(); wire.httpClient() }, wire.kms::httpClient, wire.clock, { wire.nanos })
    fun prepared(attempt: AdminDeleteFixtureAttempt, proof: String, publishers: TestAdminDeleteJournalPublisherFactoryV1): CommittedTestAdminDeleteWork.Prepared =
        publishers.reserve().use {
            ingress.withIngress(input(attempt, proof)) { context ->
                ingress.startAdminDelete(context)
                val identity = decoder.decode(token)
                assertNull(reads.authenticate(identity).verdict.failure)
                val preflight = reads.preflight(identity, attempt.candidate.tuple)
                assertNull(preflight.failure); assertNull(preflight.receipt); assertFalse(preflight.authorized)
                assertReleased()
                val admitted = ingress.admitAdminDelete(context, attempt.candidate.tuple)
                val outcome = phases.authorize(identity, attempt.candidate, preflight, proof, admitted)
                assertReleased()
                assertInstanceOf(CommittedTestAdminDeleteWork.Prepared::class.java, assertInstanceOf(TestAdminDeleteAuthorizationV1.Continue::class.java, outcome).work)
            }
        }
    fun reload(attempt: AdminDeleteFixtureAttempt): CommittedTestAdminDeleteWork {
        val identity = decoder.decode(token)
        val preflight = reads.preflight(identity, attempt.candidate.tuple)
        assertTrue(preflight.authorized)
        return assertInstanceOf(TestAdminDeleteAuthorizationV1.Continue::class.java, phases.reload(identity, attempt.candidate, preflight)).work.also { assertReleased() }
    }
    fun counters(): Map<ComplaintCapacityCounter, DeleteAllCounter> = existing.counters()
    fun assertCounterDelta(before: Map<ComplaintCapacityCounter, DeleteAllCounter>, actual: ComplaintCapacityVector = ComplaintCapacityVector.ZERO,
        promised: ComplaintCapacityVector = ComplaintCapacityVector.ZERO, spent: ComplaintCapacityVector = ComplaintCapacityVector.ZERO,
        refund: ComplaintCapacityVector = ComplaintCapacityVector.ZERO) {
        val after = counters()
        ComplaintCapacityCounter.entries.forEach { counter ->
            val old = before.getValue(counter); val current = after.getValue(counter)
            assertEquals(old.preserved, current.preserved, counter.storedName)
            assertEquals(old.free - actual[counter] - promised[counter] + refund[counter], current.free, counter.storedName)
            assertEquals(old.actual + actual[counter] + spent[counter] - refund[counter], current.actual, counter.storedName)
            assertEquals(old.recovery + promised[counter] - spent[counter], current.recovery, counter.storedName)
            assertEquals(old.test, current.test, "No borrowing from terminal TEST reserve")
        }
    }
    // Observation also runs inside real phase hooks; never enlist another Spring resource.
    fun <T> observeOne(sql: String, vararg args: Any, read: (ResultSet) -> T): T =
        checkNotNull(observer.dataSource).connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next(), "Expected one observer row")
                    read(rows).also { assertFalse(rows.next(), "Expected only one observer row") }
                }
            }
        }
    fun scalar(sql: String, vararg args: Any): String? = observeOne(sql, *args) { it.getString(1) }
    fun state(): Map<String, List<String>> = TABLES.associateWith(::rows) + mapOf(
        "audit" to observer.queryForList("SELECT to_jsonb(a)::text FROM audit_log a WHERE complaint_data_scope_id = ? ORDER BY id", String::class.java, scope.id),
        "grants" to observer.queryForList("SELECT to_jsonb(g)::text FROM admin_step_up_grants g WHERE user_id = ? ORDER BY id", String::class.java, base.ordinary.userId),
        "counters" to observer.queryForList("SELECT to_jsonb(c)::text FROM complaint_capacity_counters c ORDER BY name", String::class.java),
    )
    fun rows(table: String): List<String> {
        require(table in TABLES)
        return observer.queryForList("SELECT to_jsonb(r)::text FROM $table r WHERE data_scope_id = ? ORDER BY to_jsonb(r)::text", String::class.java, scope.id)
    }
    fun before(sql: String) = base.preserveAssertions { beforeSql(sql) }
    fun after(sql: String, deletion: Boolean) = base.preserveAssertions {
        val observation = if (deletion) {
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            val holder = TransactionSynchronizationManager.getResource(existing.pool) as ConnectionHolder
            assertEquals(setOf(existing.pool), TransactionSynchronizationManager.getResourceMap().keys)
            val lease = ownedPoolLease(holder.connection)
            val observed = deletionObservations.computeIfAbsent(phase) {
                val identity = holder.connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT pg_backend_pid(), txid_current()").use { row -> check(row.next()); row.getInt(1) to row.getLong(2) }
                }
                StepUpPhaseObservation(phase, lease, identity)
            }
            assertSame(observed.lease, lease); assertFalse(lease.completion.quiescent()); observed
        } else {
            assertFalse(TransactionSynchronizationManager.hasResource(existing.pool)); observeStepUpPhase(base.ordinary)
        }
        observations.add(sql to observation)
        afterSql(sql)
    }
    fun assertReleased() {
        base.assertReleased(); existing.assertReleased()
        assertTrue(observations.all { it.second.lease.completion.quiescent() })
        assertEquals(0, noDataKeys.calls.get()); requireConnectionFree()
    }
    private fun stageSyntheticComparisons() = existing.transaction { sql ->
        val writer = graph.writer
        assertEquals(1, sql.update("UPDATE complaint_journal_control SET desired_generation = ?, desired_configuration_hash = ?, database_identity = ?, " +
            "restore_identity = ?, event_writer_generation = ?, seal_writer_generation = ?, checkpoint_writer_generation = ?, " +
            "checkpoint_configuration_hash = ?, checkpoint_database_identity = ?, checkpoint_restore_identity = ?, " +
            "checkpoint_started_at = clock_timestamp(), checkpoint_completed_at = clock_timestamp(), creation_closed = false WHERE data_scope_id = ?",
            desired.desiredGeneration, desired.configurationHashBytes(), desired.databaseIdentity, desired.restoreIdentity, writer, writer, writer,
            desired.configurationHashBytes(), desired.databaseIdentity, desired.restoreIdentity, ComplaintDataScope.LIVE.id))
        assertEquals(1, sql.update("INSERT INTO complaint_journal_control SELECT (jsonb_populate_record(NULL::complaint_journal_control, " +
            "to_jsonb(c) || jsonb_build_object('data_scope_id', ?::text, 'test_only', true))).* FROM complaint_journal_control c WHERE data_scope_id = ?",
            scope.id, ComplaintDataScope.LIVE.id))
        assertEquals(1, sql.update("UPDATE complaint_test_runs SET configuration_hash = ? WHERE data_scope_id = ?", desired.configurationHashBytes(), scope.id))
    }
    override fun close() {
        beforeSql = {}; afterSql = {}; assertReleased()
        try {
            existing.transaction { sql ->
                for (table in listOf("complaint_idempotency_receipts", "complaint_recovery_capacity_reservations", "complaint_deletion_journal_retirements",
                    "complaint_deletion_journal_applied", "complaint_journal_publications", "complaint_journal_control")) {
                    sql.update("DELETE FROM $table WHERE data_scope_id = ?", scope.id)
                }
            }
        } finally { creator.close() }
    }
    private companion object {
        val TABLES = setOf("complaint_installation_ids", "app_installations", "complaint_resource_ids", "complaints", "complaint_idempotency_receipts",
            "complaint_journal_publications", "complaint_recovery_capacity_reservations", "complaint_deletion_journal_applied",
            "complaint_deletion_journal_retirements", "complaint_test_runs", "complaint_journal_control")
    }
}

internal class AdminDeleteFixtureAttempt(val raw: ComplaintAdminDeleteInput, val candidate: ComplaintAdminDeleteCandidate) {
    val id get() = raw.targetId
    val key get() = raw.key
}

/** Passive/fault brackets only. No row, epoch, work, provider observation or release result is substituted. */
internal class AdminDeleteFixtureJdbc(private val fixture: ComplaintAdminDeleteFixture, source: DataSource, private val deletion: Boolean) : JdbcTemplate(source) {
    init { exceptionTranslator = SQLExceptionSubclassTranslator() }
    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>): List<T> = around(sql) { super.query(sql, rowMapper) }
    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> = around(sql) { super.query(sql, rowMapper, *args) }
    override fun <T : Any?> queryForObject(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): T? = around(sql) { super.queryForObject(sql, rowMapper, *args) }
    override fun <T : Any?> queryForObject(sql: String, requiredType: Class<T>, vararg args: Any?): T? = queryForObject(sql, getSingleColumnRowMapper(requiredType), *args)
    override fun update(sql: String, vararg args: Any?): Int = around(sql) { super.update(sql, *args) }
    private fun <T> around(sql: String, operation: () -> T): T {
        fixture.statements.add(sql); fixture.before(sql)
        return operation().also { fixture.after(sql, deletion) }
    }
}
