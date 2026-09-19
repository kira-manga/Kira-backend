package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.api.ComplaintAdminStatusHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintAdminStatusResponses
import me.manga.kira.backend.complaint.api.ComplaintOwnerDetailHttpHandler
import me.manga.kira.backend.complaint.api.ownerDetailTestRequest
import me.manga.kira.backend.complaint.application.ComplaintAdminStatusService
import me.manga.kira.backend.complaint.application.ComplaintOwnerDetailService
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusOperation
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminStatusAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDetailReadAdapter
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminStatusStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDetailStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminStatusPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDetailPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.adminStatusTestRequest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID

/** Detached request values only; no supplied actor/time or assumed authenticated authority. */
internal data class AdminStatusAttempt(
    val id: UUID,
    val operation: ComplaintAdminStatusOperation = ComplaintAdminStatusOperation.ADMIN_STATUS,
    val key: UUID = UUID.randomUUID(),
    val version: Long = 1,
    val status: ComplaintStatus = ComplaintStatus.IN_PROGRESS,
    val reason: String = "Synthetic reason",
)

/** Reuse all lifecycle, actual normal JWT/issuer, receipt assertions and cleanup from the content fixture. */
internal class ComplaintAdminStatusFixture(val content: ComplaintAdminContentFixture, val ingress: ComplaintIngressAdmission = content.ingress) {
    val base = content.base
    val store = JdbcComplaintAdminStatusStore(base.jdbc, base.capacity, base.base.service, base.run.desired)
    val phases = ComplaintAdminStatusPhaseExecutor(content.ordinary.ownership, store)
    val responses = ComplaintAdminStatusResponses(content.responseOwner)
    val handler = ComplaintAdminStatusHttpHandler(
        ComplaintAdminStatusService(ComplaintAdminStatusAdapter(content.scope, content.decoder, phases, ingress, content.userJwt.properties.clockSkew)),
        ingress, responses,
    )
    private val mapper = ObjectMapper()
    private val owner = ComplaintOwnerDetailHttpHandler(
        ComplaintOwnerDetailService(ComplaintOwnerDetailReadAdapter(
            content.scope, base.jwt, base.historyPhases,
            ComplaintOwnerDetailPhaseExecutor(content.ordinary.ownership, JdbcComplaintOwnerDetailStore(content.ordinary.jdbc, content.scope)),
            ingress,
        )), ingress, content.responseOwner,
    )

    fun input(attempt: AdminStatusAttempt, proof: String? = null, bearer: String? = content.token): MockHttpServletRequest = adminStatusTestRequest(
        attempt.operation, content.scope, attempt.id, attempt.key, attempt.version, bearer, proof,
        mapper.writeValueAsString(if (attempt.operation == ComplaintAdminStatusOperation.ADMIN_STATUS) mapOf("status" to attempt.status.name) else mapOf("reason" to attempt.reason)),
    )

    fun send(request: MockHttpServletRequest): MockHttpServletResponse = MockHttpServletResponse().also { handler.handleRequest(request, it) }

    fun change(attempt: AdminStatusAttempt, proof: String? = null, bearer: String? = content.token): MockHttpServletResponse =
        send(input(attempt, proof, bearer)).also { base.assertReleased() }

    fun ownerDetail(id: UUID, bearer: String = base.token): MockHttpServletResponse = MockHttpServletResponse().also {
        owner.handleRequest(ownerDetailTestRequest(id, bearer), it)
        base.assertReleased()
    }

    fun immutable(id: UUID): String = content.observer.queryForObject(
        "SELECT (to_jsonb(c) - ARRAY['status','closure_reason','closure_provenance','closure_actor_id','closed_at','updated_at','version'])::text FROM complaints c WHERE id = ?",
        String::class.java, id,
    )!!
}
