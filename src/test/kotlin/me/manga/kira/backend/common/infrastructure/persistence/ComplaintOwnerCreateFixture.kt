package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.api.ComplaintInstallationHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerCreateHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryHttpHandler
import me.manga.kira.backend.complaint.application.ComplaintInstallationService
import me.manga.kira.backend.complaint.application.ComplaintOwnerCreateService
import me.manga.kira.backend.complaint.application.ComplaintOwnerHistoryService
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintReportFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintReportIdentity
import me.manga.kira.backend.complaint.domain.ComplaintReportMetadataInput
import me.manga.kira.backend.complaint.domain.ComplaintReportRequest
import me.manga.kira.backend.complaint.domain.ComplaintReportRequestResult
import me.manga.kira.backend.complaint.domain.ComplaintType
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationExchangeAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateCandidate
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerHistoryReadAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerOperationIdentity
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerCreateStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerHistoryStore
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerCreatePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerHistoryPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.historyTestCursors
import me.manga.kira.backend.security.historyTestJwt
import me.manga.kira.backend.security.historyTestRequest
import me.manga.kira.backend.security.ownerCreateTestCapacityPolicy
import me.manga.kira.backend.security.ownerCreateTestIngress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.sql.Connection
import java.sql.Timestamp
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Thin data/HTTP composition on the existing owned PG fixture, not another pool, service or activation harness. */
internal fun withComplaintOwnerCreate(database: PgLifecycleDatabaseFixture, test: (ComplaintOwnerCreateFixture) -> Unit) {
    withOrdinaryComplaintInstallationEnrollment(database, maximumPoolSize = 4) { base ->
        OrdinaryComplaintTestInstallationFixture(base).use { run ->
            ComplaintOwnerCreateFixture(base, run).use(test)
        }
    }
}

internal class ComplaintOwnerCreateFixture(val base: OrdinaryComplaintInstallationEnrollmentFixture, val run: OrdinaryComplaintTestInstallationFixture) :
    AutoCloseable {
    val policy = ownerCreateTestCapacityPolicy()
    val ingress = ownerCreateTestIngress(policy)
    val jwt = historyTestJwt()
    val observer = base.observer
    val jdbc = OwnerCreateFixtureJdbc(this)
    val capacity = JdbcComplaintCapacityStore(jdbc, policy.digestBytes())
    val store = JdbcComplaintOwnerCreateStore(jdbc, capacity, base.service, run.desired)
    val phases = ComplaintOwnerCreatePhaseExecutor(base.ordinary.ownership, store)
    val handler = handler()
    val actor = ScopedInstallationId(UUID.randomUUID().also { base.ids.add(it) }, run.scope)
    private val mapper = ObjectMapper()
    private val resources = CopyOnWriteArrayList<UUID>()
    private val publications = CopyOnWriteArrayList<String>()
    val observations = CopyOnWriteArrayList<Pair<OwnerCreateFixtureStep, StepUpPhaseObservation>>()
    val enrollmentResponse: MockHttpServletResponse
    val sessionResponse: MockHttpServletResponse
    val token: String
    var beforeStep: (OwnerCreateFixtureStep) -> Unit = {}
    var afterStep: (OwnerCreateFixtureStep) -> Unit = {}

    init {
        // Existing disposable ledger has these exact vectors; replace only its explicitly synthetic P digest.
        assertEquals(22, observer.update("UPDATE complaint_capacity_counters SET configuration_hash = ?", policy.digestBytes()))
        enrollmentResponse = exchange(actor.id, session = false)
        assertEquals(201, enrollmentResponse.status)
        sessionResponse = exchange(actor.id, session = true)
        assertEquals(200, sessionResponse.status)
        token = json(sessionResponse)["accessToken"].asText()
        assertEquals(actor, jwt.verify(token).installation)
        base.assertReleased()
    }

    val historyPhases = ComplaintOwnerHistoryPhaseExecutor(base.ordinary.ownership, JdbcComplaintOwnerHistoryStore(base.ordinary.jdbc, run.scope))
    private val historyHandler = ComplaintOwnerHistoryHttpHandler(
        ComplaintOwnerHistoryService(
            ComplaintOwnerHistoryReadAdapter(
                run.scope,
                jwt,
                historyTestCursors(),
                historyPhases,
                ingress,
            ),
        ),
        ingress,
    )

    fun handler(selected: ComplaintIngressAdmission = ingress): ComplaintOwnerCreateHttpHandler = ComplaintOwnerCreateHttpHandler(
        ComplaintOwnerCreateService(ComplaintOwnerCreateAdapter(run.scope, jwt, phases, selected)),
        selected,
    )

    fun attempt(id: UUID = UUID.randomUUID(), key: UUID = UUID.randomUUID(), body: String = "  Synthetic body\r\nline  "): OwnerCreateFixtureAttempt {
        resources.addIfAbsent(id)
        val identity = checkNotNull(ComplaintReportIdentity.checked(id.toString(), key.toString(), run.scope.id.toString()))
        val request = (
            ComplaintReportRequest.normalize(
                identity,
                ComplaintType.TECHNICAL,
                "  Synthetic subject  ",
                body,
                ComplaintReportMetadataInput(null, "  fixture-os  ", "", ""),
            ) as ComplaintReportRequestResult.Accepted
            ).request
        return OwnerCreateFixtureAttempt(id, key, body, ComplaintOwnerCreateCandidate.prepare(actor, request))
    }

    fun input(attempt: OwnerCreateFixtureAttempt, bearer: String = token): MockHttpServletRequest = request(
        ComplaintOwnerCreateHttpHandler.CREATE,
        bearer,
        mapper.writeValueAsBytes(
            linkedMapOf(
                "id" to attempt.id.toString(),
                "type" to "TECHNICAL",
                "subject" to "  Synthetic subject  ",
                "body" to attempt.rawBody,
                "metadata" to linkedMapOf("appVersion" to null, "osVersion" to "  fixture-os  ", "manufacturer" to "", "deviceModel" to ""),
            ),
        ),
    )
        .apply { addHeader("X-Kira-Idempotency-Key", attempt.key.toString()) }

    fun create(attempt: OwnerCreateFixtureAttempt, selected: ComplaintOwnerCreateHttpHandler = handler, bearer: String = token): MockHttpServletResponse =
        send(input(attempt, bearer), selected).also { assertReleased() }

    fun status(
        attempt: OwnerCreateFixtureAttempt,
        fingerprint: String = ComplaintReportFingerprint.of(attempt.candidate.request).encoded,
        bearer: String = token,
    ): MockHttpServletResponse = send(
        request(
            ComplaintOwnerCreateHttpHandler.STATUS,
            bearer,
            mapper.writeValueAsBytes(
                linkedMapOf(
                    "operation" to "OWNER_CREATE",
                    "key" to attempt.key.toString(),
                    "targetIds" to listOf(attempt.id.toString()),
                    "fingerprint" to fingerprint,
                ),
            ),
        ),
    ).also { assertReleased() }

    fun send(request: MockHttpServletRequest, selected: ComplaintOwnerCreateHttpHandler = handler): MockHttpServletResponse =
        MockHttpServletResponse().also { selected.handleRequest(request, it) }

    fun history(): MockHttpServletResponse = MockHttpServletResponse().also {
        historyHandler.handleRequest(historyTestRequest(token), it)
        assertReleased()
    }

    fun identity(): ComplaintOwnerOperationIdentity = jwt.verify(token).let {
        ComplaintOwnerOperationIdentity(it.installation, it.credentialVersion, it.issuedAt, it.expiresAt)
    }

    fun exchange(id: UUID, session: Boolean): MockHttpServletResponse {
        base.ids.addIfAbsent(id)
        val exchange = ComplaintInstallationExchangeAdapter(
            run.desired,
            base.ordinary.ownership,
            base.jdbc,
            JdbcComplaintCapacityStore(base.jdbc, policy.digestBytes()),
            base.audit,
            ingress,
            jwt,
        )
        val handler = ComplaintInstallationHttpHandler(ComplaintInstallationService(exchange), ingress)
        val request = MockHttpServletRequest("POST", if (session) "/api/v1/installations/session" else "/api/v1/installations").apply {
            remoteAddr = "192.0.2.1"
            contentType = "application/json"
            setContent(
                mapper.writeValueAsBytes(
                    linkedMapOf<String, String>().apply {
                        put("installationId", id.toString())
                        put("secret", Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() }))
                        put("expectedDataScopeId", run.scope.id.toString())
                        if (!session) put("platform", "ANDROID")
                    },
                ),
            )
        }
        return MockHttpServletResponse().also {
            handler.handleRequest(request, it)
            base.assertReleased()
        }
    }

    fun json(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.contentAsByteArray)

    fun problem(response: MockHttpServletResponse, status: Int, code: String) {
        assertEquals(status, response.status)
        assertEquals(code, json(response)["errors"][0]["code"].asText())
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertEquals(response.contentAsByteArray.size.toString(), response.getHeader("Content-Length"))
    }

    fun state(): OwnerCreateFixtureState = OwnerCreateFixtureState(
        base.counters.snapshot(),
        observer.queryForList(
            "SELECT to_jsonb(r)::text FROM complaint_idempotency_receipts r WHERE actor_id = ? ORDER BY idempotency_key",
            String::class.java,
            actor.id,
        ),
        observer.queryForList("SELECT to_jsonb(r)::text FROM complaint_resource_ids r WHERE data_scope_id = ? ORDER BY id", String::class.java, run.scope.id),
        observer.queryForList("SELECT to_jsonb(r)::text FROM complaints r WHERE data_scope_id = ? ORDER BY id", String::class.java, run.scope.id),
        observer.queryForList("SELECT to_jsonb(r)::text FROM audit_log r WHERE complaint_data_scope_id = ? ORDER BY id", String::class.java, run.scope.id),
    )

    fun assertCharge(before: Map<String, CounterSnapshot>, amount: ComplaintCapacityVector) {
        val after = base.counters.snapshot()
        assertEquals(22, after.size)
        for (counter in ComplaintCapacityCounter.entries) {
            val old = before.getValue(counter.storedName)
            val current = after.getValue(counter.storedName)
            // Rejection precharges then refunds these dimensions in the SAME transaction: updated_at may advance.
            if (amount[counter] == 0L && ComplaintCapacityCharges.OWNER_CREATE[counter] == 0L) {
                assertEquals(old, current)
            } else {
                assertEquals(old.preserved, current.preserved, "Reservation/configuration state must not be repurposed.")
                assertEquals(old.free - amount[counter], current.free)
                assertEquals(old.actual + amount[counter], current.actual)
            }
        }
    }

    /** Legal synthetic row staging for business count/collision cases, not producer/accounting evidence. */
    fun resource(state: String = "LIVE", scope: ComplaintDataScope = run.scope, id: UUID = UUID.randomUUID()): UUID = id.also {
        resources.add(id)
        assertEquals(
            1,
            observer.update(
                "INSERT INTO complaint_resource_ids (id, data_scope_id, test_only, state, created_at, deleted_at) " +
                    "VALUES (?, ?, ?, ?, now(), CASE WHEN ? = 'DELETED' THEN now() END)",
                id,
                scope.id,
                scope.testOnly,
                state,
                state,
            ),
        )
    }

    fun content(pending: Boolean = false, parent: UUID? = null, id: UUID = UUID.randomUUID()): UUID {
        resource(if (pending) "DELETION_PENDING" else "LIVE", id = id)
        assertEquals(
            1,
            observer.update(
                "INSERT INTO complaints (id, data_scope_id, test_only, owner_id, ownership, kind, type, status, subject, body, " +
                    "parent_resource_id, platform, os_version, manufacturer, device_model, created_at, updated_at, version) " +
                    "VALUES (?, ?, true, ?, 'INSTALLATION', ?, 'TECHNICAL', 'OPEN', 'Synthetic row', 'Synthetic content', ?, 'ANDROID', '', '', '', ?, ?, 1)",
                id,
                run.scope.id,
                actor.id,
                if (parent ==
                    null
                ) {
                    "REPORT"
                } else {
                    "REPLY"
                },
                parent,
                Timestamp.from(base.ordinary.cutoff),
                Timestamp.from(base.ordinary.cutoff),
            ),
        )
        return id
    }

    internal fun trackReplyResource(id: UUID) {
        resources.addIfAbsent(id)
    }

    /** Legal committed deletion receipt shape only; not publication verification or an actual deletion producer. */
    fun authorizedDelete(attempt: OwnerCreateFixtureAttempt) {
        val event = Base64.getUrlEncoder().withoutPadding().encodeToString(attempt.candidate.tuple.fingerprintBytes())
        val bytes = "{\"synthetic\":true}".toByteArray()
        publications.add(event)
        assertEquals(
            1,
            observer.update(
                "INSERT INTO complaint_journal_publications (event_id,data_scope_id,test_only,writer_generation,journal_epoch," +
                    "event_kind,target_count,routing_key_id,object_key,canonicalizer,event_bytes,semantic_hash,state,created_at) " +
                    "VALUES (?, ?, true, ?, 1, 'OWNER_DELETE', 1, 'synthetic-key', ?, 'kcj-1', ?, sha256(?), 'PREPARED', now())",
                event,
                run.scope.id,
                UUID.randomUUID(),
                "synthetic/owner-create/$event",
                bytes,
                bytes,
            ),
        )
        assertEquals(
            1,
            observer.update(
                "INSERT INTO complaint_idempotency_receipts (actor_kind,actor_id,idempotency_key,operation,fingerprint,target_ids," +
                    "data_scope_id,test_only,state,publication_ref,created_at,authorized_at) " +
                    "VALUES ('INSTALLATION', ?, ?, 'OWNER_DELETE', ?, ARRAY[?::uuid], ?, true, 'AUTHORIZED_DELETE', ?, now(), now())",
                actor.id,
                attempt.key,
                attempt.candidate.tuple.fingerprintBytes(),
                attempt.id,
                run.scope.id,
                event,
            ),
        )
    }

    fun lock(sql: String, vararg values: Any): Connection = checkNotNull(observer.dataSource).connection.also { connection ->
        try {
            connection.autoCommit = false
            connection.prepareStatement(sql).use { statement ->
                statement.queryTimeout = 2
                values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.execute()
            }
        } catch (problem: Throwable) {
            connection.close()
            throw problem
        }
    }

    fun before(step: OwnerCreateFixtureStep) = base.preserveAssertions { beforeStep(step) }

    fun after(step: OwnerCreateFixtureStep) = base.preserveAssertions {
        observations.add(step to observeStepUpPhase(base.ordinary))
        afterStep(step)
    }

    fun assertReleased() {
        base.assertReleased()
        assertTrue(observations.all { it.second.lease.completion.quiescent() })
    }

    private fun request(path: String, bearer: String, body: ByteArray): MockHttpServletRequest = MockHttpServletRequest("POST", path).apply {
        remoteAddr = "192.0.2.1"
        contentType = "application/json"
        addHeader("Authorization", "Bearer $bearer")
        setContent(body)
    }

    override fun close() {
        assertReleased()
        base.ids.forEach { observer.update("DELETE FROM complaint_idempotency_receipts WHERE actor_id = ?", it) }
        publications.forEach { observer.update("DELETE FROM complaint_journal_publications WHERE event_id = ?", it) }
        observer.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ?", run.scope.id)
        resources.forEach { observer.update("DELETE FROM complaints WHERE id = ?", it) }
        resources.forEach { observer.update("DELETE FROM complaint_resource_ids WHERE id = ?", it) }
    }
}

internal class OwnerCreateFixtureAttempt(val id: UUID, val key: UUID, val rawBody: String, val candidate: ComplaintOwnerCreateCandidate) {
    override fun toString(): String = "OwnerCreateFixtureAttempt(synthetic,redacted)"
}

internal data class OwnerCreateFixtureState(
    val counters: Map<String, CounterSnapshot>,
    val receipts: List<String>,
    val resources: List<String>,
    val content: List<String>,
    val audits: List<String>,
)

internal enum class OwnerCreateFixtureStep {
    AUTH, OBSERVE, CLAIM, COUNTERS, CHARGE, RUN, OWNER, CREDENTIAL, COLLISION,
    PARENT_CANDIDATE, PARENT_RESOURCE, PARENT_CONTENT, DISCARD_RESOURCE, RESOURCE, CONTENT, COMPLETE,
}

/** Passive/fault hooks bracket genuine SQL. No result, commit state or physical-release flag is forged. */
internal class OwnerCreateFixtureJdbc(private val fixture: ComplaintOwnerCreateFixture) : JdbcTemplate(fixture.base.ordinary.pool) {
    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>): List<T> {
        val step = step(sql)
        step?.let(fixture::before)
        return super.query(sql, rowMapper).also { step?.let(fixture::after) }
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> {
        val step = step(sql)
        step?.let(fixture::before)
        return super.query(sql, rowMapper, *args).also { step?.let(fixture::after) }
    }

    override fun <T : Any?> queryForObject(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): T? {
        val step = step(sql)
        step?.let(fixture::before)
        return super.queryForObject(sql, rowMapper, *args).also { step?.let(fixture::after) }
    }

    override fun <T : Any?> queryForObject(sql: String, requiredType: Class<T>, vararg args: Any?): T? =
        queryForObject(sql, getSingleColumnRowMapper(requiredType), *args)

    override fun update(sql: String, vararg args: Any?): Int {
        val step = step(sql)
        step?.let(fixture::before)
        return super.update(sql, *args).also { step?.let(fixture::after) }
    }

    private fun step(sql: String): OwnerCreateFixtureStep? = when {
        sql.contains("LEFT JOIN complaint_idempotency_receipts") -> OwnerCreateFixtureStep.OBSERVE
        sql.startsWith("WITH actor AS") -> OwnerCreateFixtureStep.AUTH
        sql.startsWith("INSERT INTO complaint_idempotency_receipts") -> OwnerCreateFixtureStep.CLAIM
        sql.startsWith("SELECT name, ordinal, accounting_version") -> OwnerCreateFixtureStep.COUNTERS
        sql.startsWith("UPDATE complaint_capacity_counters") -> OwnerCreateFixtureStep.CHARGE
        sql.contains("FROM complaint_test_runs r WHERE") -> OwnerCreateFixtureStep.RUN
        sql.contains("FROM complaint_installation_ids WHERE") -> OwnerCreateFixtureStep.OWNER
        sql.contains("FROM app_installations WHERE") -> OwnerCreateFixtureStep.CREDENTIAL
        sql.startsWith("SELECT EXISTS (SELECT 1 FROM complaint_resource_ids") -> OwnerCreateFixtureStep.COLLISION
        sql.startsWith("SELECT EXISTS") && sql.contains("FROM complaints c WHERE") -> OwnerCreateFixtureStep.PARENT_CANDIDATE
        sql.startsWith("SELECT state FROM complaint_resource_ids") -> OwnerCreateFixtureStep.PARENT_RESOURCE
        sql.contains("FROM complaints c WHERE") -> OwnerCreateFixtureStep.PARENT_CONTENT
        sql.startsWith("DELETE FROM complaint_resource_ids") -> OwnerCreateFixtureStep.DISCARD_RESOURCE
        sql.startsWith("INSERT INTO complaint_resource_ids") -> OwnerCreateFixtureStep.RESOURCE
        sql.contains("INSERT INTO complaints") -> OwnerCreateFixtureStep.CONTENT
        sql.contains("UPDATE complaint_idempotency_receipts") -> OwnerCreateFixtureStep.COMPLETE
        else -> null
    }
}
