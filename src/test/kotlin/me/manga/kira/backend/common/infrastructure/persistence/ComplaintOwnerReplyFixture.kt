package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.api.ComplaintOwnerCreateHttpHandler
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintReplyFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintReplyRequest
import me.manga.kira.backend.complaint.domain.ComplaintReportIdentity
import me.manga.kira.backend.complaint.domain.ComplaintReportMetadataInput
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerReplyCandidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID

/** Reply data helpers on the existing ordinary PG/create fixture, not another lifecycle or database harness. */
internal class OwnerReplyFixtureAttempt(val parentId: UUID, val id: UUID, val key: UUID, val rawBody: String, val candidate: ComplaintOwnerReplyCandidate) {
    override fun toString(): String = "OwnerReplyFixtureAttempt(synthetic,redacted)"
}

internal fun ComplaintOwnerCreateFixture.replyAttempt(
    parentId: UUID,
    id: UUID = UUID.randomUUID(),
    key: UUID = UUID.randomUUID(),
    body: String = "  Synthetic reply\r\nline  ",
): OwnerReplyFixtureAttempt {
    trackReplyResource(id)
    val identity = checkNotNull(ComplaintReportIdentity.checked(id.toString(), key.toString(), run.scope.id.toString()))
    val request = ComplaintReplyRequest.normalize(identity, parentId, body, ComplaintReportMetadataInput(null, "  fixture-os  ", "", ""))
    return OwnerReplyFixtureAttempt(parentId, id, key, body, ComplaintOwnerReplyCandidate.prepare(actor, request))
}

internal fun ComplaintOwnerCreateFixture.replyInput(attempt: OwnerReplyFixtureAttempt, bearer: String = token): MockHttpServletRequest = replyFixtureRequest(
    "/api/v1/complaints/${attempt.parentId}/replies",
    bearer,
    linkedMapOf(
        "id" to attempt.id.toString(),
        "body" to attempt.rawBody,
        "metadata" to linkedMapOf("appVersion" to null, "osVersion" to "  fixture-os  ", "manufacturer" to "", "deviceModel" to ""),
    ),
).apply { addHeader("X-Kira-Idempotency-Key", attempt.key.toString()) }

internal fun ComplaintOwnerCreateFixture.reply(
    attempt: OwnerReplyFixtureAttempt,
    selected: ComplaintOwnerCreateHttpHandler = handler,
    bearer: String = token,
): MockHttpServletResponse = send(replyInput(attempt, bearer), selected).also { assertReleased() }

internal fun ComplaintOwnerCreateFixture.replyStatus(
    attempt: OwnerReplyFixtureAttempt,
    operation: String = "OWNER_REPLY",
    targets: List<UUID> = listOf(attempt.parentId, attempt.id),
    fingerprint: String = ComplaintReplyFingerprint.of(attempt.candidate.request).encoded,
    bearer: String = token,
    selected: ComplaintOwnerCreateHttpHandler = handler,
): MockHttpServletResponse = send(
    replyFixtureRequest(
        ComplaintOwnerCreateHttpHandler.STATUS,
        bearer,
        linkedMapOf("operation" to operation, "key" to attempt.key.toString(), "targetIds" to targets.map(UUID::toString), "fingerprint" to fingerprint),
    ),
    selected,
).also { assertReleased() }

/** Synthetic exact-scope notice row only; not an activation seed/catalog manifest or locale approval. */
internal fun ComplaintOwnerCreateFixture.replyNotice(scope: ComplaintDataScope = run.scope): UUID {
    val id = UUID.nameUUIDFromBytes(UUID.randomUUID().toString().toByteArray()) // Canonical non-v4 parent is legal.
    resource(scope = scope, id = id)
    assertEquals(
        1,
        observer.update(
            "INSERT INTO complaints (id,data_scope_id,test_only,ownership,kind,status,notice_key,created_at,updated_at,version) " +
                "VALUES (?, ?, ?, 'SYSTEM', 'NOTICE', 'PINNED', ?, now(), now(), 1)",
            id, scope.id, scope.testOnly, "complaints.notice.fixture.$id",
        ),
    )
    return id
}

/** Legal fixture erasure, not the WORM deletion producer. Permanent reservation and child versions are retained. */
internal fun ComplaintOwnerCreateFixture.eraseReplyParent(id: UUID) {
    assertEquals(1, observer.update("DELETE FROM complaints WHERE id = ?", id))
    assertEquals(1, observer.update("UPDATE complaint_resource_ids SET state = 'DELETED', deleted_at = now() WHERE id = ?", id))
}

private fun replyFixtureRequest(path: String, bearer: String, body: Map<String, Any?>): MockHttpServletRequest = MockHttpServletRequest("POST", path).apply {
    remoteAddr = "192.0.2.1"
    contentType = "application/json"
    addHeader("Authorization", "Bearer $bearer")
    setContent(replyFixtureMapper.writeValueAsBytes(body))
}

private val replyFixtureMapper = ObjectMapper()
