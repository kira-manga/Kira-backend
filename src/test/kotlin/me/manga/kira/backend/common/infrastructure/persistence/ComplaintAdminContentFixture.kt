package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.api.ComplaintAdminContentHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintAdminContentResponses
import me.manga.kira.backend.complaint.api.ComplaintAdminReadHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintAdminReadResponses
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryResponses
import me.manga.kira.backend.complaint.application.ComplaintAdminContentService
import me.manga.kira.backend.complaint.application.ComplaintAdminReadService
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintType
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminContentAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadAdapter
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminContentStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminReadStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminContentPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminReadPhaseExecutor
import me.manga.kira.backend.security.AdminReadTestUserJwt
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.IssuedScopedAdminStepUp
import me.manga.kira.backend.security.ScopedAdminStepUpScope
import me.manga.kira.backend.security.adminContentTestIngress
import me.manga.kira.backend.security.adminContentTestRequest
import me.manga.kira.backend.security.adminReadDetailRequest
import me.manga.kira.backend.security.adminReadTestCursors
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Detached request data only. It is not an authenticated Admin, grant, receipt or mutation authority. */
internal data class AdminContentAttempt(
    val id: UUID,
    val key: UUID = UUID.randomUUID(),
    val version: Long = 1,
    val type: ComplaintType? = ComplaintType.TECHNICAL,
    val subject: String? = "Edited subject",
    val body: String = "Edited body",
)

/** Thin composition on the existing actual owner-create/PG/JPA/issuer fixtures; no new resource or runner. */
internal class ComplaintAdminContentFixture(
    val base: ComplaintOwnerCreateFixture,
    val ingress: ComplaintIngressAdmission = adminContentTestIngress(base.policy),
    val responseOwner: ComplaintOwnerHistoryResponses = ComplaintOwnerHistoryResponses(),
) : AutoCloseable {
    val ordinary = base.base.ordinary
    val observer = base.observer
    val scope: ComplaintDataScope = base.run.scope
    val userJwt = AdminReadTestUserJwt()
    val decoderCalls = AtomicInteger()
    val decoder = JwtDecoder { value ->
        requireConnectionFree()
        decoderCalls.incrementAndGet()
        userJwt.decoder.decode(value)
    }
    val store = JdbcComplaintAdminContentStore(base.jdbc, base.capacity, base.base.service, base.run.desired)
    val phases = ComplaintAdminContentPhaseExecutor(ordinary.ownership, store)
    val writer = ComplaintAdminContentAdapter(scope, decoder, phases, ingress, userJwt.properties.clockSkew)
    val responses = ComplaintAdminContentResponses(responseOwner)
    val handler = ComplaintAdminContentHttpHandler(ComplaintAdminContentService(writer), ingress, responses)
    val reads = ComplaintAdminReadHttpHandler(
        ComplaintAdminReadService(
            ComplaintAdminReadAdapter(
                scope, decoder, adminReadTestCursors(),
                ComplaintAdminReadPhaseExecutor(ordinary.ownership, JdbcComplaintAdminReadStore(ordinary.jdbc, scope)),
                ingress, userJwt.properties.clockSkew,
            ),
        ),
        ingress, ComplaintAdminReadResponses(responseOwner),
    )
    val stepUp = ScopedStepUpFixture(ordinary, base.base.counters, base.policy.digestBytes(), phaseClock = Clock.systemUTC())
    val token = userJwt.signer.issue(user()).value
    private val mapper = ObjectMapper()

    /** Actual qualified normal JWT from the real fixture's bcrypt-hashed ADMIN row. No jwt() principal stub. */
    fun user(): User = observer.queryForObject(
        "SELECT id, email, password_hash, role, enabled, created_at, updated_at, credential_version FROM users WHERE id = ?",
        { row, _ ->
            User(
                row.getObject("id", UUID::class.java), row.getString("email"), row.getString("password_hash"), Role.valueOf(row.getString("role")),
                row.getBoolean("enabled"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(), row.getLong("credential_version"),
            )
        },
        ordinary.userId,
    )!!

    fun proof(scope: ScopedAdminStepUpScope = ScopedAdminStepUpScope.COMPLAINT): IssuedScopedAdminStepUp = stepUp.issue(scope)

    fun input(
        attempt: AdminContentAttempt,
        proof: String? = null,
        bearer: String? = token,
        selectedScope: ComplaintDataScope = scope,
    ): MockHttpServletRequest = adminContentTestRequest(
        selectedScope, attempt.id, attempt.key, attempt.version, bearer, proof,
        mapper.writeValueAsString(linkedMapOf<String, Any>().apply {
            attempt.type?.let { put("type", it.name) }
            attempt.subject?.let { put("subject", it) }
            put("body", attempt.body)
        }),
    )

    /** No global idle assertion while another caller intentionally retains the original SQL owner. */
    fun send(request: MockHttpServletRequest): MockHttpServletResponse = MockHttpServletResponse().also { handler.handleRequest(request, it) }

    fun edit(attempt: AdminContentAttempt, proof: String? = null, bearer: String? = token): MockHttpServletResponse =
        send(input(attempt, proof, bearer)).also { base.assertReleased() }

    fun detail(id: UUID): MockHttpServletResponse = MockHttpServletResponse().also {
        reads.handleRequest(adminReadDetailRequest(scope, id, token), it)
        base.assertReleased()
    }

    fun report(bearer: String = base.token): UUID = base.attempt().also { assertEquals(201, base.create(it, bearer = bearer).status) }.id

    fun acknowledged(response: MockHttpServletResponse, id: UUID, version: Long) {
        assertEquals(200, response.status)
        assertEquals("{\"id\":\"$id\",\"version\":$version}", response.contentAsString)
        assertEquals("\"complaint-$id-v$version\"", response.getHeader("ETag"))
        assertEquals(listOf("true"), response.getHeaders(CONSUMED).toList())
        assertEquals(response.contentAsByteArray.size.toString(), response.getHeader("Content-Length"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertNull(response.getHeader("Location"))
        base.assertReleased()
    }

    fun problem(response: MockHttpServletResponse, status: Int, code: String, consumed: Boolean = false) {
        base.problem(response, status, code)
        assertEquals(if (consumed) listOf("true") else emptyList<String>(), response.getHeaders(CONSUMED).toList())
        assertNull(response.getHeader("ETag"))
    }

    fun state(): AdminContentFixtureState = AdminContentFixtureState(
        base.state(),
        observer.queryForList("SELECT to_jsonb(r)::text FROM complaint_idempotency_receipts r WHERE actor_kind = 'ADMIN' AND actor_id = ? ORDER BY idempotency_key", String::class.java, ordinary.userId),
        observer.queryForList("SELECT to_jsonb(g)::text FROM admin_step_up_grants g WHERE user_id = ? ORDER BY id", String::class.java, ordinary.userId),
        observer.queryForList("SELECT to_jsonb(r)::text FROM complaint_test_runs r WHERE data_scope_id = ?", String::class.java, scope.id),
        observer.queryForList("SELECT to_jsonb(p)::text FROM complaint_journal_publications p WHERE data_scope_id = ? ORDER BY event_id", String::class.java, scope.id),
    )

    fun immutable(id: UUID): String = observer.queryForObject(
        "SELECT (to_jsonb(c) - ARRAY['type','subject','body','updated_at','version'])::text FROM complaints c WHERE id = ?", String::class.java, id,
    )!!

    override fun close() {
        base.assertReleased()
        observer.update("DELETE FROM complaint_idempotency_receipts WHERE actor_kind = 'ADMIN' AND actor_id = ? AND data_scope_id = ?", ordinary.userId, scope.id)
    }

    companion object { const val CONSUMED = "X-Kira-Admin-Step-Up-Consumed" }
}

internal data class AdminContentFixtureState(
    val ownerState: OwnerCreateFixtureState,
    val receipts: List<String>,
    val grants: List<String>,
    val run: List<String>,
    val journal: List<String>,
)
