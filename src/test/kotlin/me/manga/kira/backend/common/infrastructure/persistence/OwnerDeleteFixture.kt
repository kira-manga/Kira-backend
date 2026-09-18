package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.AuditRepository
import me.manga.kira.backend.audit.domain.ComplaintAuditAllocation
import me.manga.kira.backend.audit.domain.CountedOwnerDeleteAuditEntry
import me.manga.kira.backend.audit.domain.CountedOwnerDeleteAuditRepository
import me.manga.kira.backend.complaint.api.ComplaintOwnerCreateHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerDeleteHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerEditHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerOperationResponse
import me.manga.kira.backend.complaint.application.ComplaintOwnerCreateService
import me.manga.kira.backend.complaint.application.ComplaintOwnerDeleteService
import me.manga.kira.backend.complaint.application.ComplaintOwnerEditService
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeletePrecondition
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteRequest
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.infrastructure.CommittedTestOwnerDeleteWork
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteCandidate
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteObservation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerEditAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerOperationIdentity
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteApplyStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteReceiptStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteVerificationStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerEditStore
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteAuthorizationV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteLocalGraphV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeletePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteReadPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerEditPhaseExecutor
import me.manga.kira.backend.complaint.journal.TestOwnerDeleteJournalPublisherFixture
import me.manga.kira.backend.security.ComplaintAdmittedOwnerDelete
import me.manga.kira.backend.security.CurrentUser
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalTupleV1
import me.manga.kira.backend.security.ownerCreateTestCapacityPolicy
import me.manga.kira.backend.security.ownerDeleteTestIngress
import me.manga.kira.backend.security.ownerDeleteTestJournal
import me.manga.kira.backend.security.ownerDeleteTestRouting
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
import java.sql.Timestamp
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource

/** Reuses the existing ordinary/deletion lifecycle verbatim. No launcher, pool, controller or activation is added. */
internal fun withOwnerDelete(database: PgLifecycleDatabaseFixture, test: (OwnerDeleteFixture) -> Unit) =
    withOwnerDelete(database, ordinaryMaximumPoolSize = 2, test = test)

/** Explicit real-pool sizing for paired ordinary callers; the ordinary P-minus-one budget is unchanged. */
internal fun withOwnerDelete(database: PgLifecycleDatabaseFixture, ordinaryMaximumPoolSize: Int, test: (OwnerDeleteFixture) -> Unit) {
    withOwnerDeleteAllAuthorization(database, ordinaryMaximumPoolSize = ordinaryMaximumPoolSize) { existing ->
        OrdinaryComplaintTestInstallationFixture(existing.base).use { run ->
            OwnerDeleteFixture(existing, run).use(test)
        }
    }
}

/**
 * Thin one-target glue. ACTIVE/run/checkpoint/catalog rows below are SYNTHETIC COMPARISONS ONLY.
 * No RegisteredProjectedTestNamespace/current binding, quiescence or VERIFIED DTO is fabricated.
 * Enrollment, create/reply, SQL commit/release and raw provider readback use their real producers.
 */
internal class OwnerDeleteFixture(
    val existing: OwnerDeleteAllAuthorizationFixture,
    val run: OrdinaryComplaintTestInstallationFixture,
) : AutoCloseable {
    val base = existing.base
    val observer = base.observer
    val scope = run.scope
    val policy = ownerCreateTestCapacityPolicy()
    val ingress = ownerDeleteTestIngress(policy)
    val routing = ownerDeleteTestRouting(ownerDeleteTestJournal(scope))
    // TEST is retained in the very same registry that can account for the existing LIVE family.
    val lanes = JournalPublicationLanesV1(existing.routing.journalConfiguration)
    val ordinaryJdbc = OwnerDeleteFixtureJdbc(this, base.ordinary.pool, deletion = false)
    val jdbc = OwnerDeleteFixtureJdbc(this, existing.pool, deletion = true)
    val graph = TestOwnerDeleteLocalGraphV1(ordinaryJdbc, jdbc, ingress, routing, policy, lanes)
    val desired = graph.desiredSettings()
    val observations = CopyOnWriteArrayList<Pair<OwnerDeleteFixtureStep, StepUpPhaseObservation>>()
    val statements = CopyOnWriteArrayList<String>()
    private val deletionObservations = ConcurrentHashMap<PersistencePhaseContext, StepUpPhaseObservation>()
    var beforeStep: (OwnerDeleteFixtureStep) -> Unit = {}
    var afterStep: (OwnerDeleteFixtureStep) -> Unit = {}
    val creator: ComplaintOwnerCreateFixture

    init {
        existing.installPolicy(policy)
        stageSyntheticComparisons()
        creator = ComplaintOwnerCreateFixture(base, run, desired)
    }

    val capacity = JdbcComplaintCapacityStore(jdbc, policy.digestBytes())
    // Shared existing no-key port proves authorization does not invoke a provider; no LIVE codec/work is reused.
    val noDataKeys = NeverOwnerDeleteAllDataKeys()
    val codec = TestOwnerDeleteJournalCodecV1(routing, noDataKeys)
    private val counted = object : AuditRepository by base.repository, CountedOwnerDeleteAuditRepository {
        override fun recordOwnerDelete(entry: CountedOwnerDeleteAuditEntry, allocation: ComplaintAuditAllocation) {
            before(OwnerDeleteFixtureStep.AUDIT)
            base.repository.recordOwnerDelete(entry, allocation)
            base.auditIds.add(checkNotNull(jdbc.queryForObject("SELECT currval(pg_get_serial_sequence('audit_log', 'id'))", Long::class.java)))
            after(OwnerDeleteFixtureStep.AUDIT, deletion = true)
        }
    }
    val audit = AuditService(counted, CurrentUser(), Clock.fixed(base.ordinary.cutoff, ZoneOffset.UTC))
    val store = JdbcComplaintOwnerDeleteStore(jdbc, capacity, audit, graph, codec)
    val receipts = JdbcComplaintOwnerDeleteReceiptStore(ordinaryJdbc, graph)
    val reads = ComplaintOwnerDeleteReadPhaseExecutor(base.ordinary.ownership, receipts)
    val verification = JdbcComplaintOwnerDeleteVerificationStore(jdbc, graph, store)
    val apply = JdbcComplaintOwnerDeleteApplyStore(jdbc, capacity, audit, graph, store, verification)
    val phases = ComplaintOwnerDeletePhaseExecutor(existing.ownership, store, reads, verification, apply)
    private val mapper = ObjectMapper()

    fun attempt(id: UUID, version: Long = 1, key: UUID = UUID.randomUUID()): OwnerDeleteFixtureAttempt {
        val raw = ComplaintOwnerDeleteInput(id, key, ComplaintOwnerDeletePrecondition.parse(id, "\"complaint-$id-v$version\""))
        val candidate = ComplaintOwnerDeleteCandidate.prepare(creator.actor, ComplaintOwnerDeleteRequest.normalize(scope, raw))
        return OwnerDeleteFixtureAttempt(raw, candidate)
    }

    fun input(attempt: OwnerDeleteFixtureAttempt, bearer: String = creator.token): MockHttpServletRequest =
        MockHttpServletRequest("DELETE", "/api/v1/complaints/${attempt.id}").apply {
            remoteAddr = "192.0.2.1"
            addHeader("Authorization", "Bearer $bearer")
            addHeader("X-Kira-Idempotency-Key", attempt.key.toString())
            addHeader("If-Match", attempt.raw.precondition.canonical)
            setContent(ByteArray(0))
        }

    fun statusInput(attempt: OwnerDeleteFixtureAttempt, bearer: String = creator.token): MockHttpServletRequest =
        MockHttpServletRequest("POST", ComplaintOwnerCreateHttpHandler.STATUS).apply {
            remoteAddr = "192.0.2.1"
            contentType = "application/json"
            addHeader("Authorization", "Bearer $bearer")
            setContent(
                mapper.writeValueAsBytes(
                    linkedMapOf(
                        "operation" to "OWNER_DELETE", "key" to attempt.key.toString(), "targetIds" to listOf(attempt.id.toString()),
                        "fingerprint" to ComplaintOwnerDeleteFingerprint.of(attempt.candidate.request).encoded,
                    ),
                ),
            )
        }

    fun http(publishers: TestOwnerDeleteJournalPublisherFactoryV1): OwnerDeleteFixtureHttp {
        val responses = ComplaintOwnerOperationResponse()
        val deletes = ComplaintOwnerDeleteHttpHandler(ComplaintOwnerDeleteService(ComplaintOwnerDeleteAdapter(graph, creator.jwt, reads, phases, publishers)), ingress, responses)
        val editStore = JdbcComplaintOwnerEditStore(creator.jdbc, creator.capacity, base.service, desired)
        val editPhases = ComplaintOwnerEditPhaseExecutor(base.ordinary.ownership, editStore)
        val edits = ComplaintOwnerEditHttpHandler(ComplaintOwnerEditService(ComplaintOwnerEditAdapter(scope, creator.jwt, editPhases, ingress)), ingress, responses)
        val creates = ComplaintOwnerCreateHttpHandler(
            ComplaintOwnerCreateService(ComplaintOwnerCreateAdapter(scope, creator.jwt, creator.phases, ingress)), ingress, responses, edits, deletes,
        )
        return OwnerDeleteFixtureHttp(deletes, creates, edits)
    }

    /** No global-idle assertion here: race tests intentionally retain another caller's actual lock. */
    fun send(request: MockHttpServletRequest, http: OwnerDeleteFixtureHttp): MockHttpServletResponse = MockHttpServletResponse().also {
        when {
            request.method == "DELETE" -> http.deletes.handleRequest(request, it)
            request.method == "PATCH" -> http.edits.handleRequest(request, it)
            else -> http.creates.handleRequest(request, it)
        }
    }

    fun delete(attempt: OwnerDeleteFixtureAttempt, http: OwnerDeleteFixtureHttp, bearer: String = creator.token): MockHttpServletResponse =
        send(input(attempt, bearer), http).also { assertReleased() }

    fun status(attempt: OwnerDeleteFixtureAttempt, http: OwnerDeleteFixtureHttp, bearer: String = creator.token): MockHttpServletResponse =
        send(statusInput(attempt, bearer), http).also { assertReleased() }

    fun journalTuple(attempt: OwnerDeleteFixtureAttempt, epoch: Long = 11, credentialVersion: Long = creator.identity().credentialVersion) =
        TestOwnerDeleteJournalTupleV1(epoch, creator.actor.id, credentialVersion, attempt.key, attempt.candidate.tuple.fingerprintBytes(), scope)

    fun wire(attempt: OwnerDeleteFixtureAttempt, epoch: Long = 11): TestOwnerDeleteJournalPublisherFixture {
        val event = codec.canonicalize(journalTuple(attempt, epoch), listOf(attempt.id))
        return TestOwnerDeleteJournalPublisherFixture(routing, event).also { fixture ->
            // Synthetic provider timestamp, sampled connection-free; never a current-policy/activation input.
            fixture.wall = checkNotNull(observer.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)).toInstant()
            fixture.beforeOpen = {
                requireConnectionFree()
                fixture.wall = checkNotNull(observer.queryForObject("SELECT clock_timestamp()", Timestamp::class.java)).toInstant()
            }
            fixture.beforePrepare = ::assertReleased
        }
    }

    fun <T> admitted(
        attempt: OwnerDeleteFixtureAttempt,
        action: (ComplaintOwnerOperationIdentity, ComplaintOwnerDeleteObservation, ComplaintAdmittedOwnerDelete) -> T,
    ): T = ingress.withIngress(input(attempt)) { context ->
        ingress.startOwnerDelete(context)
        val identity = creator.identity()
        assertEquals(ComplaintPlatform.ANDROID, reads.authenticate(identity).platform)
        val preflight = reads.preflight(identity, attempt.candidate.tuple)
        assertNull(preflight.failure)
        assertNull(preflight.receipt)
        assertFalse(preflight.authorized)
        assertReleased()
        action(identity, preflight, ingress.admitOwnerDelete(context, attempt.candidate.tuple))
    }

    /** Genuine AUTH/release under the same pre-reserved publication budget; returned work is never constructed by the fixture. */
    fun prepared(attempt: OwnerDeleteFixtureAttempt, publishers: TestOwnerDeleteJournalPublisherFactoryV1): CommittedTestOwnerDeleteWork.Prepared =
        publishers.reserve().use {
            val result = admitted(attempt) { identity, preflight, admission -> phases.authorize(identity, attempt.candidate, preflight, admission) }
            assertReleased()
            val work = assertInstanceOf(TestOwnerDeleteAuthorizationV1.Continue::class.java, result).work
            assertInstanceOf(CommittedTestOwnerDeleteWork.Prepared::class.java, work)
        }

    fun reload(attempt: OwnerDeleteFixtureAttempt): CommittedTestOwnerDeleteWork {
        val identity = creator.identity()
        val preflight = reads.preflight(identity, attempt.candidate.tuple)
        assertTrue(preflight.authorized)
        return assertInstanceOf(TestOwnerDeleteAuthorizationV1.Continue::class.java, phases.reload(identity, attempt.candidate, preflight)).work.also {
            assertReleased()
        }
    }

    fun counters(): Map<ComplaintCapacityCounter, DeleteAllCounter> = existing.counters()

    fun assertCounterDelta(
        before: Map<ComplaintCapacityCounter, DeleteAllCounter>,
        actual: ComplaintCapacityVector = ComplaintCapacityVector.ZERO,
        promised: ComplaintCapacityVector = ComplaintCapacityVector.ZERO,
        spent: ComplaintCapacityVector = ComplaintCapacityVector.ZERO,
        refund: ComplaintCapacityVector = ComplaintCapacityVector.ZERO,
    ) {
        val after = counters()
        assertEquals(22, after.size)
        ComplaintCapacityCounter.entries.forEach { counter ->
            val old = before.getValue(counter)
            val current = after.getValue(counter)
            assertEquals(old.preserved, current.preserved, counter.storedName)
            assertEquals(old.free - actual[counter] - promised[counter] + refund[counter], current.free, counter.storedName)
            assertEquals(old.actual + actual[counter] + spent[counter] - refund[counter], current.actual, counter.storedName)
            assertEquals(old.recovery + promised[counter] - spent[counter], current.recovery, counter.storedName)
            assertEquals(old.test, current.test, "Ordinary deletion may not spend the TEST terminal reserve")
            assertEquals(old.free + old.actual + old.recovery + old.test, current.free + current.actual + current.recovery + current.test)
        }
    }

    /** Exact durable snapshot; timestamp/sequence gaps are not normalized into an assumed rollback. */
    fun state(): Map<String, List<String>> = linkedMapOf(
        "identities" to rows("complaint_installation_ids"), "credentials" to rows("app_installations"),
        "resources" to rows("complaint_resource_ids"), "content" to rows("complaints"),
        "receipts" to rows("complaint_idempotency_receipts"), "publications" to rows("complaint_journal_publications"),
        "reservations" to rows("complaint_recovery_capacity_reservations"), "applied" to rows("complaint_deletion_journal_applied"),
        "retirements" to rows("complaint_deletion_journal_retirements"),
        "audit" to observer.queryForList("SELECT to_jsonb(r)::text FROM audit_log r WHERE complaint_data_scope_id = ? ORDER BY id", String::class.java, scope.id),
        "run" to rows("complaint_test_runs"), "control" to rows("complaint_journal_control"),
        "counters" to observer.queryForList("SELECT to_jsonb(r)::text FROM complaint_capacity_counters r ORDER BY name", String::class.java),
    )

    fun rows(table: String): List<String> {
        require(table in TABLES)
        return observer.queryForList("SELECT to_jsonb(r)::text FROM $table r WHERE data_scope_id = ? ORDER BY to_jsonb(r)::text", String::class.java, scope.id)
    }

    fun scalar(sql: String, vararg values: Any): String? = observer.queryForObject(sql, String::class.java, *values)

    fun before(step: OwnerDeleteFixtureStep) = base.preserveAssertions { beforeStep(step) }

    fun after(step: OwnerDeleteFixtureStep, deletion: Boolean) = base.preserveAssertions {
        val observation = if (deletion) {
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            val holder = TransactionSynchronizationManager.getResource(existing.pool) as ConnectionHolder
            assertEquals(setOf(existing.pool), TransactionSynchronizationManager.getResourceMap().keys)
            // Other registered callers may own an ordinary phase in race tests; this caller must not.
            assertFalse(TransactionSynchronizationManager.hasResource(base.ordinary.pool))
            val lease = ownedPoolLease(holder.connection)
            val observed = deletionObservations.computeIfAbsent(phase) {
                val identity = holder.connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT pg_backend_pid(), txid_current()").use { row ->
                        check(row.next())
                        row.getInt(1) to row.getLong(2)
                    }
                }
                StepUpPhaseObservation(phase, lease, identity)
            }
            assertSame(observed.lease, lease)
            assertFalse(lease.completion.quiescent())
            observed
        } else {
            assertFalse(TransactionSynchronizationManager.hasResource(existing.pool))
            observeStepUpPhase(base.ordinary)
        }
        observations.add(step to observation)
        afterStep(step)
    }

    fun assertReleased() {
        base.assertReleased()
        existing.assertReleased()
        assertTrue(observations.all { it.second.lease.completion.quiescent() })
        assertEquals(0, noDataKeys.calls.get())
        requireConnectionFree()
    }

    private fun stageSyntheticComparisons() = existing.transaction { selected ->
        val writer = graph.writer
        // Existing fixture already owns the global row's restoration. These are necessary comparisons, not signed/current custody.
        assertEquals(
            1,
            selected.update(
                "UPDATE complaint_journal_control SET desired_generation = ?, desired_configuration_hash = ?, database_identity = ?, " +
                    "restore_identity = ?, event_writer_generation = ?, seal_writer_generation = ?, checkpoint_writer_generation = ?, " +
                    "checkpoint_configuration_hash = ?, checkpoint_database_identity = ?, checkpoint_restore_identity = ?, " +
                    "checkpoint_started_at = clock_timestamp(), checkpoint_completed_at = clock_timestamp(), creation_closed = false WHERE data_scope_id = ?",
                desired.desiredGeneration, desired.configurationHashBytes(), desired.databaseIdentity, desired.restoreIdentity, writer, writer, writer,
                desired.configurationHashBytes(), desired.databaseIdentity, desired.restoreIdentity, ComplaintDataScope.LIVE.id,
            ),
        )
        assertEquals(
            1,
            selected.update(
                "INSERT INTO complaint_journal_control SELECT (jsonb_populate_record(NULL::complaint_journal_control, " +
                    "to_jsonb(c) || jsonb_build_object('data_scope_id', ?::text, 'test_only', true))).* " +
                    "FROM complaint_journal_control c WHERE data_scope_id = ?",
                scope.id, ComplaintDataScope.LIVE.id,
            ),
        )
        assertEquals(1, selected.update("UPDATE complaint_test_runs SET configuration_hash = ? WHERE data_scope_id = ?", desired.configurationHashBytes(), scope.id))
    }

    override fun close() {
        beforeStep = {}
        afterStep = {}
        assertReleased()
        try {
            existing.transaction { selected ->
                // Scope-exclusive fixture data; no caller-created retirement or terminal proof is accepted.
                selected.update("DELETE FROM complaint_idempotency_receipts WHERE data_scope_id = ?", scope.id)
                selected.update("DELETE FROM complaint_recovery_capacity_reservations WHERE data_scope_id = ?", scope.id)
                selected.update("DELETE FROM complaint_deletion_journal_retirements WHERE data_scope_id = ?", scope.id)
                selected.update("DELETE FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", scope.id)
                selected.update("DELETE FROM complaint_journal_publications WHERE data_scope_id = ?", scope.id)
                selected.update("DELETE FROM complaint_journal_control WHERE data_scope_id = ?", scope.id)
            }
        } finally {
            creator.close()
        }
        // Factories are caller-owned and closed by each test. A failed native close is never silently retried here.
    }

    private companion object {
        val TABLES = setOf(
            "complaint_installation_ids", "app_installations", "complaint_resource_ids", "complaints", "complaint_idempotency_receipts",
            "complaint_journal_publications", "complaint_recovery_capacity_reservations", "complaint_deletion_journal_applied",
            "complaint_deletion_journal_retirements", "complaint_test_runs", "complaint_journal_control",
        )
    }
}

internal class OwnerDeleteFixtureAttempt(val raw: ComplaintOwnerDeleteInput, val candidate: ComplaintOwnerDeleteCandidate) {
    val id: UUID get() = raw.targetId
    val key: UUID get() = raw.key
    override fun toString(): String = "OwnerDeleteFixtureAttempt(synthetic,redacted)"
}

internal class OwnerDeleteFixtureHttp(
    val deletes: ComplaintOwnerDeleteHttpHandler,
    val creates: ComplaintOwnerCreateHttpHandler,
    val edits: ComplaintOwnerEditHttpHandler,
)

internal enum class OwnerDeleteFixtureStep {
    AUTH, PREFLIGHT, CONTROL, RECEIPT, CLAIM, COUNTERS, CHARGE, RUN, OWNER, CREDENTIAL, CANDIDATE, RESOURCE, CONTENT,
    RESERVATION, PUBLICATION, PENDING, AUTHORIZE_RECEIPT, VERIFY, DELETE_CONTENT, TOMBSTONE, APPLIED, APPLY_PUBLICATION, COMPLETE, SPEND, AUDIT,
}

/** Passive/fault brackets around real SQL on the existing exact data source. Never substitutes a row/result/release flag. */
internal class OwnerDeleteFixtureJdbc(
    private val fixture: OwnerDeleteFixture,
    source: DataSource,
    private val deletion: Boolean,
) : JdbcTemplate(source) {
    init { exceptionTranslator = SQLExceptionSubclassTranslator() }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>): List<T> = around(sql) { super.query(sql, rowMapper) }
    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> = around(sql) { super.query(sql, rowMapper, *args) }
    override fun <T : Any?> queryForObject(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): T? = around(sql) { super.queryForObject(sql, rowMapper, *args) }
    override fun <T : Any?> queryForObject(sql: String, requiredType: Class<T>, vararg args: Any?): T? = queryForObject(sql, getSingleColumnRowMapper(requiredType), *args)
    override fun update(sql: String, vararg args: Any?): Int = around(sql) { super.update(sql, *args) }

    private fun <T> around(sql: String, action: () -> T): T {
        fixture.statements.add(sql)
        // Multiline interpolation can leave leading whitespace after trimIndent; execute and record the original SQL.
        val step = step(sql.trimStart())
        step?.let(fixture::before)
        return action().also { step?.let { fixture.after(it, deletion) } }
    }

    private fun step(sql: String): OwnerDeleteFixtureStep? = when {
        sql.contains("LEFT JOIN complaint_idempotency_receipts") -> OwnerDeleteFixtureStep.PREFLIGHT
        sql.startsWith("WITH actor AS") -> OwnerDeleteFixtureStep.AUTH
        sql.contains("FROM complaint_journal_control") -> OwnerDeleteFixtureStep.CONTROL
        sql.startsWith("INSERT INTO complaint_idempotency_receipts") -> OwnerDeleteFixtureStep.CLAIM
        sql.contains("FROM complaint_idempotency_receipts") -> OwnerDeleteFixtureStep.RECEIPT
        sql.startsWith("SELECT name, ordinal, accounting_version") -> OwnerDeleteFixtureStep.COUNTERS
        sql.startsWith("UPDATE complaint_capacity_counters") -> OwnerDeleteFixtureStep.CHARGE
        sql.contains("FROM complaint_test_runs") -> OwnerDeleteFixtureStep.RUN
        sql.contains("FROM complaint_installation_ids") -> OwnerDeleteFixtureStep.OWNER
        sql.contains("FROM app_installations") -> OwnerDeleteFixtureStep.CREDENTIAL
        sql.startsWith("SELECT EXISTS") && sql.contains("FROM complaints") -> OwnerDeleteFixtureStep.CANDIDATE
        sql.contains("FROM complaint_resource_ids") -> OwnerDeleteFixtureStep.RESOURCE
        sql.startsWith("DELETE FROM complaints") -> OwnerDeleteFixtureStep.DELETE_CONTENT
        sql.contains("FROM complaints") -> OwnerDeleteFixtureStep.CONTENT
        sql.startsWith("INSERT INTO complaint_recovery_capacity_reservations") || sql.contains("FROM complaint_recovery_capacity_reservations") -> OwnerDeleteFixtureStep.RESERVATION
        sql.startsWith("INSERT INTO complaint_journal_publications") || sql.contains("FROM complaint_journal_publications") -> OwnerDeleteFixtureStep.PUBLICATION
        sql.startsWith("UPDATE complaint_resource_ids") && sql.contains("SET state = 'DELETION_PENDING'") -> OwnerDeleteFixtureStep.PENDING
        sql.startsWith("UPDATE complaint_resource_ids") && sql.contains("SET state = 'DELETED'") -> OwnerDeleteFixtureStep.TOMBSTONE
        sql.startsWith("UPDATE complaint_journal_publications") && sql.contains("SET state = 'APPLIED'") -> OwnerDeleteFixtureStep.APPLY_PUBLICATION
        sql.startsWith("UPDATE complaint_journal_publications") -> OwnerDeleteFixtureStep.VERIFY
        sql.startsWith("INSERT INTO complaint_deletion_journal_applied") -> OwnerDeleteFixtureStep.APPLIED
        sql.contains("UPDATE complaint_idempotency_receipts") && sql.contains("SET state = 'AUTHORIZED_DELETE'") -> OwnerDeleteFixtureStep.AUTHORIZE_RECEIPT
        sql.contains("UPDATE complaint_idempotency_receipts") -> OwnerDeleteFixtureStep.COMPLETE
        sql.startsWith("UPDATE complaint_recovery_capacity_reservations") -> OwnerDeleteFixtureStep.SPEND
        else -> null
    }
}

/** Independent literal 22-slot expectations; never derive test charges from the producer's constants. */
internal object OwnerDeleteLiteralCharges {
    val installation = vector(0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 16384, 0)
    val credential = vector(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 16384, 0)
    val resource = vector(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 16384, 0)
    val receipt = vector(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 131072, 0)
    val publication = vector(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 262144, 0)
    val reservation = vector(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 16384, 0)
    val authorization = vector(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 1, 0, 0, 0, 475136, 0)
    val promise = vector(0, 5, 0, 0, 0, 0, 0, 1, 0, 4, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 491520, 0)
    val ordinaryApply = vector(0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 98304, 0)
    val content = vector(0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 262144, 0)
    val missingBookkeeping = vector(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 1, 0, 0, 0, 409600, 0)
    val reconstructAbsent = vector(0, 1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 131072, 0)
    val appliedOnly = vector(0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 32768, 0)
    val audit = vector(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 65536, 0)
    private fun vector(vararg values: Long): ComplaintCapacityVector = ComplaintCapacityVector.of(values.also { require(it.size == 22) })
}
