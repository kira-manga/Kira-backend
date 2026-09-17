package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryResponses
import me.manga.kira.backend.complaint.application.ComplaintOwnerHistoryService
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.SessionPreflightResult
import me.manga.kira.backend.complaint.domain.SessionRefreshResult
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerHistoryIdentity
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerHistoryReadAdapter
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerHistoryStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerHistoryPhaseExecutor
import me.manga.kira.backend.security.IssuedInstallationJwt
import me.manga.kira.backend.security.historyTestCursors
import me.manga.kira.backend.security.historyTestIngress
import me.manga.kira.backend.security.historyTestJwt
import me.manga.kira.backend.security.historyTestRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Thin owned rows on the existing PG/ordinary fixture. Synthetic data is not mode/restore/import authority. */
internal class ComplaintOwnerHistoryFixture(
    val base: OrdinaryComplaintInstallationEnrollmentFixture,
    val run: OrdinaryComplaintTestInstallationFixture,
) : AutoCloseable {
    val ordinary = base.ordinary
    val observer = base.observer
    val jwt = historyTestJwt()
    val cursors = historyTestCursors()
    val ingress = historyTestIngress()
    val store = JdbcComplaintOwnerHistoryStore(ordinary.jdbc, run.scope)
    val phases = ComplaintOwnerHistoryPhaseExecutor(ordinary.ownership, store)
    val reader = newReader()
    val responses = ComplaintOwnerHistoryResponses()
    val handler = ComplaintOwnerHistoryHttpHandler(ComplaintOwnerHistoryService(reader), ingress, responses)
    val session = enrolledSession()
    val actor: ScopedInstallationId = session.installation
    val token: IssuedInstallationJwt = jwt.issue(actor, session.credentialVersion, session.issuedAt)
    private val resources = linkedSetOf<UUID>()
    private val leases = mutableListOf<PersistenceJdbcLease>()
    private val mapper = ObjectMapper()

    fun newReader(): ComplaintOwnerHistoryReadAdapter = ComplaintOwnerHistoryReadAdapter(run.scope, jwt, cursors, phases, ingress)

    /** Real TEST enrollment, real secret comparison and real committed/released session refresh. */
    fun enrolledSession(selected: OrdinaryComplaintTestInstallationFixture = run): SessionRefreshResult.Refreshed {
        val candidate = selected.candidate()
        base.execute(candidate, selected.store)
        val ready = base.preflight(selected.session(candidate), selected.sessions) as SessionPreflightResult.Ready
        return base.refresh(ready.continuation, selected.sessions) as SessionRefreshResult.Refreshed
    }

    fun identity(): ComplaintOwnerHistoryIdentity = ComplaintOwnerHistoryIdentity(actor, session.credentialVersion, token.expiresAt)

    fun request(bearer: String? = token.value, query: String? = "limit=50"): MockHttpServletResponse = MockHttpServletResponse().also {
        handler.handleRequest(historyTestRequest(bearer, query), it)
    }

    fun json(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.contentAsByteArray)

    fun itemIds(response: MockHttpServletResponse): List<UUID> = json(response)["items"].map { UUID.fromString(it["id"].asText()) }

    fun cursor(response: MockHttpServletResponse): String? = json(response)["nextCursor"].let { if (it.isNull) null else it.asText() }

    fun content(
        owner: ScopedInstallationId = actor,
        id: UUID = UUID.randomUUID(),
        parent: UUID? = null,
        key: String? = null,
        at: Instant = ordinary.cutoff,
        body: String = "Synthetic history body",
        resourceState: String = "LIVE",
    ): UUID {
        resource(id, owner.scope, at, resourceState)
        assertEquals(
            1,
            observer.update(
                "INSERT INTO complaints (id, data_scope_id, test_only, owner_id, ownership, kind, type, status, notice_key, " +
                    "subject, body, parent_resource_id, app_version, platform, os_version, manufacturer, device_model, " +
                    "created_at, updated_at, version) " +
                    "VALUES (?, ?, ?, ?, 'INSTALLATION', ?, ?, 'OPEN', ?, ?, ?, ?, NULL, 'ANDROID', '', '', '', ?, ?, 1)",
                id, owner.scope.id, owner.scope.testOnly, owner.id, if (parent == null) "REPORT" else "REPLY",
                if (key == null) "TECHNICAL" else "CUSTOM", key, if (key == null) "Synthetic subject" else null,
                body, parent, Timestamp.from(at), Timestamp.from(at),
            ),
        )
        return id
    }

    fun notice(scope: ComplaintDataScope = run.scope, key: String = "history.fixture.${UUID.randomUUID()}"): UUID {
        val id = UUID.randomUUID()
        resource(id, scope, ordinary.cutoff, "LIVE")
        assertEquals(
            1,
            observer.update(
                "INSERT INTO complaints (id, data_scope_id, test_only, ownership, kind, status, notice_key, created_at, updated_at, version) " +
                    "VALUES (?, ?, ?, 'SYSTEM', 'NOTICE', 'PINNED', ?, ?, ?, 1)",
                id, scope.id, scope.testOnly, key, Timestamp.from(ordinary.cutoff), Timestamp.from(ordinary.cutoff),
            ),
        )
        return id
    }

    /** Handwritten synthetic exclusion row only. No Firestore read, importer, ownership recovery or W06 implementation. */
    fun legacy(): UUID {
        val id = UUID.randomUUID()
        resource(id, ComplaintDataScope.LIVE, ordinary.cutoff, "LIVE")
        assertEquals(
            1,
            observer.update(
                "INSERT INTO complaints (id, data_scope_id, test_only, ownership, kind, type, status, subject, body, " +
                    "legacy_collection, legacy_key_id, legacy_document_hmac, legacy_payload_hash, legacy_reconciliation_code, " +
                    "created_at, updated_at, version) " +
                    "VALUES (?, ?, false, 'LEGACY_UNCLAIMED', 'REPORT', 'TECHNICAL', 'UNKNOWN', 'Synthetic legacy', 'Synthetic exclusion', " +
                    "'HISTORY_FIXTURE', 'synthetic-history', ?, ?, 'MAPPED', ?, ?, 1)",
                id, ComplaintDataScope.LIVE.id, ByteArray(32) { 7 }, ByteArray(32) { 11 },
                Timestamp.from(ordinary.cutoff), Timestamp.from(ordinary.cutoff),
            ),
        )
        return id
    }

    fun eraseParent(id: UUID) {
        require(id in resources)
        assertEquals(1, observer.update("DELETE FROM complaints WHERE id = ?", id))
        assertEquals(
            1,
            observer.update("UPDATE complaint_resource_ids SET state = 'DELETED', deleted_at = ? WHERE id = ?", Timestamp.from(ordinary.cutoff), id),
        )
    }

    fun state(): Pair<TestInstallationSnapshot, List<String>> = run.state() to
        listOf("complaint_resource_ids", "complaints").flatMap { table ->
            resources.sortedBy(UUID::toString).flatMap { id ->
                observer.queryForList("SELECT to_jsonb(r)::text FROM $table r WHERE id = ?", String::class.java, id)
            }
        }

    fun <T> withPhase(
        enter: () -> PersistencePhaseContext = ordinary.ownership::enterComplaintOwnerHistoryPage,
        work: (PersistencePhaseContext) -> T,
    ): T {
        val phase = enter()
        try {
            phase.begin()
            val holder = TransactionSynchronizationManager.getResource(ordinary.pool) as ConnectionHolder
            leases.add(ownedPoolLease(holder.connection))
            return work(phase)
        } finally {
            phase.finish()
            assertReleased()
        }
    }

    fun assertReleased() {
        base.assertReleased()
        assertEquals(0L, ordinary.ownedPool.lifecycle.activeAcquisitions())
        assertEquals(0L, ordinary.ownedPool.lifecycle.actorSnapshot().futureLeaseEntries)
        assertTrue(leases.all { it.completion.quiescent() })
        requireConnectionFree()
    }

    private fun resource(id: UUID, scope: ComplaintDataScope, at: Instant, state: String) {
        require(state in setOf("LIVE", "DELETION_PENDING") && resources.add(id))
        assertEquals(
            1,
            observer.update(
                "INSERT INTO complaint_resource_ids (id, data_scope_id, test_only, state, created_at) VALUES (?, ?, ?, ?, ?)",
                id, scope.id, scope.testOnly, state, Timestamp.from(at),
            ),
        )
    }

    override fun close() {
        assertReleased()
        // Parent references target the permanent registry: all child content must be removed first.
        resources.forEach { observer.update("DELETE FROM complaints WHERE id = ?", it) }
        resources.forEach { observer.update("DELETE FROM complaint_resource_ids WHERE id = ?", it) }
    }
}
