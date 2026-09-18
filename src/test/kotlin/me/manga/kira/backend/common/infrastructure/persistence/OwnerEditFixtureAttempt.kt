package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.api.ComplaintOwnerCreateHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerEditHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerOperationResponse
import me.manga.kira.backend.complaint.application.ComplaintOwnerCreateService
import me.manga.kira.backend.complaint.application.ComplaintOwnerEditService
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditPrecondition
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditRequest
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerEditAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerEditCandidate
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerEditStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerEditPhaseExecutor
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ownerEditTestIngress
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID

internal class OwnerEditFixtureAttempt(val raw: ComplaintOwnerEditInput, val candidate: ComplaintOwnerEditCandidate) {
    val id: UUID get() = raw.targetId
    val key: UUID get() = raw.key
    override fun toString(): String = "OwnerEditFixtureAttempt(synthetic,redacted)"
}

/** Only edit/creation HTTP wiring on the SAME fixture: no new pool, database, lifecycle, token issuer or service harness. */
internal class OwnerEditFixture(val base: ComplaintOwnerCreateFixture, val ingress: ComplaintIngressAdmission = ownerEditTestIngress(base.policy)) {
    private val mapper = ObjectMapper()
    val responses = ComplaintOwnerOperationResponse()
    val store = JdbcComplaintOwnerEditStore(base.jdbc, base.capacity, base.base.service, base.run.desired)
    val phases = ComplaintOwnerEditPhaseExecutor(base.base.ordinary.ownership, store)
    val handler = ComplaintOwnerEditHttpHandler(
        ComplaintOwnerEditService(ComplaintOwnerEditAdapter(base.run.scope, base.jwt, phases, ingress)),
        ingress,
        responses,
    )
    val creations = ComplaintOwnerCreateHttpHandler(
        ComplaintOwnerCreateService(ComplaintOwnerCreateAdapter(base.run.scope, base.jwt, base.phases, ingress)),
        ingress,
        responses,
        handler,
    )

    fun attempt(
        id: UUID,
        subject: String? = "Edited subject",
        body: String = "Edited body",
        version: Long = 1,
        key: UUID = UUID.randomUUID(),
    ): OwnerEditFixtureAttempt {
        val raw = ComplaintOwnerEditInput(id, key, subject, body, ComplaintOwnerEditPrecondition.parse(id, "\"complaint-$id-v$version\""))
        return OwnerEditFixtureAttempt(raw, ComplaintOwnerEditCandidate.prepare(base.actor, ComplaintOwnerEditRequest.normalize(base.run.scope, raw)))
    }

    fun input(attempt: OwnerEditFixtureAttempt, bearer: String = base.token): MockHttpServletRequest = request(
        "PATCH",
        "/api/v1/complaints/${attempt.id}/content",
        bearer,
        linkedMapOf<String, Any?>().apply {
            if (attempt.raw.subject != null) put("subject", attempt.raw.subject)
            put("body", attempt.raw.body)
        },
    ).apply {
        addHeader("X-Kira-Idempotency-Key", attempt.key.toString())
        addHeader("If-Match", attempt.raw.precondition.canonical)
    }

    fun statusInput(
        attempt: OwnerEditFixtureAttempt,
        bearer: String = base.token,
        targets: List<UUID> = listOf(attempt.id),
        fingerprint: String = ComplaintOwnerEditFingerprint.of(attempt.candidate.request).encoded,
    ): MockHttpServletRequest = request(
        "POST",
        ComplaintOwnerCreateHttpHandler.STATUS,
        bearer,
        linkedMapOf("operation" to "OWNER_EDIT", "key" to attempt.key.toString(), "targetIds" to targets.map(UUID::toString), "fingerprint" to fingerprint),
    )

    /** No global idle assertion while another caller intentionally owns a phase. */
    fun send(request: MockHttpServletRequest): MockHttpServletResponse = MockHttpServletResponse().also {
        if (request.requestURI == ComplaintOwnerCreateHttpHandler.STATUS) creations.handleRequest(request, it) else handler.handleRequest(request, it)
    }

    fun edit(attempt: OwnerEditFixtureAttempt, bearer: String = base.token): MockHttpServletResponse = send(input(attempt, bearer)).also {
        base.assertReleased()
    }

    fun status(attempt: OwnerEditFixtureAttempt): MockHttpServletResponse = send(statusInput(attempt)).also { base.assertReleased() }

    private fun request(method: String, path: String, bearer: String, value: Map<String, Any?>): MockHttpServletRequest =
        MockHttpServletRequest(method, path).apply {
            remoteAddr = "192.0.2.1"
            contentType = "application/json"
            addHeader("Authorization", "Bearer $bearer")
            setContent(mapper.writeValueAsBytes(value))
        }
}
