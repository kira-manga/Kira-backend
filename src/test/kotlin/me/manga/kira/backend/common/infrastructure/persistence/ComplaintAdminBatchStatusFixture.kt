package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.api.ComplaintAdminBatchStatusHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintAdminBatchStatusResponses
import me.manga.kira.backend.complaint.application.ComplaintAdminBatchStatusService
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminBatchStatusAdapter
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminBatchStatusStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminBatchStatusPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.adminBatchStatusTestRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID

/** Only captured request values; neither a phase/issuer bypass nor an authenticated principal. */
internal data class AdminBatchStatusAttempt(
    val targets: List<Pair<UUID, Long>>,
    val status: ComplaintStatus = ComplaintStatus.IN_PROGRESS,
    val key: UUID = UUID.randomUUID(),
)

/** Reuses actual normal JWT, scoped issuer, paid counter/JPA ownership and cleanup; no new service or pool. */
internal class ComplaintAdminBatchStatusFixture(val content: ComplaintAdminContentFixture, val ingress: ComplaintIngressAdmission = content.ingress) {
    val base = content.base
    val store = JdbcComplaintAdminBatchStatusStore(base.jdbc, base.capacity, base.base.service, base.run.desired)
    val phases = ComplaintAdminBatchStatusPhaseExecutor(content.ordinary.ownership, store)
    val responses = ComplaintAdminBatchStatusResponses(content.responseOwner)
    val handler = ComplaintAdminBatchStatusHttpHandler(
        ComplaintAdminBatchStatusService(ComplaintAdminBatchStatusAdapter(content.scope, content.decoder, phases, ingress, content.userJwt.properties.clockSkew)),
        ingress, responses,
    )
    private val mapper = ObjectMapper()

    fun input(attempt: AdminBatchStatusAttempt, proof: String? = null, bearer: String? = content.token): MockHttpServletRequest = adminBatchStatusTestRequest(
        content.scope, attempt.key, bearer, proof, mapper.writeValueAsString(linkedMapOf(
            "action" to "STATUS", "status" to attempt.status.name,
            "targets" to attempt.targets.map { (id, version) -> linkedMapOf("id" to id.toString(), "actionTag" to "\"complaint-$id-v$version\"") },
        )),
    )

    fun send(request: MockHttpServletRequest): MockHttpServletResponse = MockHttpServletResponse().also { handler.handleRequest(request, it) }

    fun change(attempt: AdminBatchStatusAttempt, proof: String? = null, bearer: String? = content.token): MockHttpServletResponse =
        send(input(attempt, proof, bearer)).also { base.assertReleased() }

    fun acknowledged(response: MockHttpServletResponse, attempt: AdminBatchStatusAttempt) {
        assertEquals(200, response.status)
        val items = attempt.targets.sortedBy { it.first.toString() }.joinToString(",") { (id, version) -> "{\"id\":\"$id\",\"version\":${version + 1}}" }
        assertEquals("{\"items\":[$items]}", response.contentAsString)
        assertEquals("application/json;charset=UTF-8", response.contentType)
        assertEquals(listOf("true"), response.getHeaders(ComplaintAdminContentFixture.CONSUMED).toList())
        assertEquals(response.contentAsByteArray.size.toString(), response.getHeader("Content-Length"))
        assertEquals("no-store, no-transform", response.getHeader("Cache-Control"))
        assertEquals("1", response.getHeader("X-Kira-Complaint-Contract"))
        assertNull(response.getHeader("ETag"))
        assertNull(response.getHeader("Location"))
        base.assertReleased()
    }

    fun immutable(id: UUID): String = content.observer.queryForObject(
        "SELECT (to_jsonb(c) - ARRAY['status','closure_reason','closure_provenance','closure_actor_id','closed_at','updated_at','version'])::text FROM complaints c WHERE id = ?",
        String::class.java, id,
    )!!
}
