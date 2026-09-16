package me.manga.kira.backend.audit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.application.requireComplaintAuditPayloadSize
import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.audit.domain.AuditRepository
import me.manga.kira.backend.audit.domain.ComplaintAuditActor
import me.manga.kira.backend.audit.domain.ComplaintAuditActorKind
import me.manga.kira.backend.audit.domain.ComplaintAuditMutation
import me.manga.kira.backend.audit.domain.ComplaintAuditResourceSubject
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.security.CurrentUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Structural metadata candidates only; these tests cannot establish an actor's authority or a real row transition. */
class ComplaintAuditPreparationTest {
    private val audit = mock(AuditRepository::class.java)
    private val clock = mock(Clock::class.java)
    private val service = AuditService(audit, CurrentUser(), clock)
    private val at = Instant.parse("2011-02-03T04:05:06.123456789Z")
    private val resourceId = "11111111-1111-4111-8111-111111111111"
    private val testScope = ComplaintDataScope.of(UUID.fromString("22222222-2222-4222-8222-222222222222"))
    private val admin = ComplaintAuditActor.of(ComplaintAuditActorKind.ADMIN, UUID.fromString("33333333-3333-4333-8333-333333333333"))
    private val installation = ComplaintAuditActor.of(ComplaintAuditActorKind.INSTALLATION)

    @Test
    fun `five preparations preserve typed metadata and explicit time with exact scalar keys and zero persistence`() {
        val live = ComplaintAuditResourceSubject.of(ComplaintDataScope.LIVE, resourceId)
        val test = ComplaintAuditResourceSubject.of(testScope, resourceId)
        val cases = listOf(
            Expected(ComplaintAuditMutation.Created(live, installation, 1), AuditAction.COMPLAINT_CREATED, """{"version":1}"""),
            Expected(ComplaintAuditMutation.Replied(test, admin, 1), AuditAction.COMPLAINT_REPLIED, """{"version":1}"""),
            Expected(ComplaintAuditMutation.ContentEdited(live, admin, 2), AuditAction.COMPLAINT_CONTENT_EDITED, """{"version":2}"""),
            Expected(
                ComplaintAuditMutation.StatusChanged(test, admin, 3, ComplaintStatus.CLOSED, ComplaintStatus.IN_PROGRESS),
                AuditAction.COMPLAINT_STATUS_CHANGED,
                """{"version":3,"fromStatus":"CLOSED","toStatus":"IN_PROGRESS"}""",
            ),
            Expected(
                ComplaintAuditMutation.Closed(live, admin, 4, ComplaintStatus.CLOSED),
                AuditAction.COMPLAINT_CLOSED,
                """{"version":4,"fromStatus":"CLOSED","toStatus":"CLOSED"}""",
            ),
        )
        for ((mutation, expectedAction, expectedJson) in cases) {
            val prepared = service.prepareComplaintMutation(mutation, at)
            assertEquals(expectedAction, prepared.action)
            assertSame(mutation.subject, prepared.subject)
            assertSame(mutation.actor, prepared.actor)
            assertEquals(at, prepared.createdAt)
            assertEquals(expectedJson, prepared.detailJson)
            val payload = Json.parseToJsonElement(prepared.detailJson).jsonObject
            assertEquals(Json.parseToJsonElement(expectedJson).jsonObject, payload)
            assertEquals(JsonPrimitive(mutation.version), payload.getValue("version"))
            assertTrue(payload.values.all { it is JsonPrimitive })
            assertTrue(prepared.detailJson.toByteArray(Charsets.UTF_8).size <= 2048)
            val diagnostic = prepared.toString()
            for (privateValue in listOf(resourceId, testScope.id.toString(), admin.adminUserId.toString(), at.toString(), expectedJson)) {
                assertFalse(diagnostic.contains(privateValue))
            }
        }
        verifyNoInteractions(audit, clock)
    }

    @Test
    fun `maximum version and every transition shape stay below the payload budget for every actor and scope kind`() {
        val targets = listOf(
            ComplaintStatus.OPEN,
            ComplaintStatus.IN_PROGRESS,
            ComplaintStatus.PLANNED,
            ComplaintStatus.RESOLVED,
            ComplaintStatus.NOT_PLANNED,
        )
        val actors = listOf(admin, installation, ComplaintAuditActor.of(ComplaintAuditActorKind.SYSTEM))
        var maximumBytes = 0
        for (scope in listOf(ComplaintDataScope.LIVE, testScope)) {
            val subject = ComplaintAuditResourceSubject.of(scope, resourceId)
            for (actor in actors) {
                val mutations = mutableListOf<ComplaintAuditMutation>(
                    ComplaintAuditMutation.Created(subject, actor, Long.MAX_VALUE),
                    ComplaintAuditMutation.Replied(subject, actor, Long.MAX_VALUE),
                    ComplaintAuditMutation.ContentEdited(subject, actor, Long.MAX_VALUE),
                )
                for (from in ComplaintStatus.entries) {
                    mutations += ComplaintAuditMutation.Closed(subject, actor, Long.MAX_VALUE, from)
                    for (to in targets.filter { it != from }) {
                        mutations += ComplaintAuditMutation.StatusChanged(subject, actor, Long.MAX_VALUE, from, to)
                    }
                }
                for (mutation in mutations) {
                    val prepared = service.prepareComplaintMutation(mutation, at)
                    val bytes = prepared.detailJson.toByteArray(Charsets.UTF_8).size
                    maximumBytes = maxOf(maximumBytes, bytes)
                    assertTrue(bytes <= 2048)
                    assertEquals(JsonPrimitive(Long.MAX_VALUE), Json.parseToJsonElement(prepared.detailJson).jsonObject.getValue("version"))
                }
            }
        }
        assertEquals(83, maximumBytes)
        assertEquals(2048, ComplaintCapacityCharges.MAX_AUDIT_PAYLOAD_BYTES)
        verifyNoInteractions(audit, clock)
    }

    @Test
    fun `payload size check measures encoded UTF-8 bytes at exact and one-over boundaries`() {
        // Synthetic encoded objects exercise the size check alone, not an arbitrary-detail prepare API.
        val exactAscii = "{\"x\":\"${"a".repeat(2040)}\"}"
        val exactMultibyte = "{\"x\":\"${"é".repeat(1020)}\"}"
        for (exact in listOf(exactAscii, exactMultibyte)) {
            assertEquals(2048, exact.toByteArray(Charsets.UTF_8).size)
            requireComplaintAuditPayloadSize(exact)
            val oneOver = exact.dropLast(2) + "a\"}"
            assertEquals(2049, oneOver.toByteArray(Charsets.UTF_8).size)
            val failure = assertThrows(IllegalArgumentException::class.java) { requireComplaintAuditPayloadSize(oneOver) }
            assertEquals("Complaint audit payload exceeds the byte limit.", failure.message)
        }
        assertTrue(exactMultibyte.length < 2048)
        verifyNoInteractions(audit, clock)
    }

    private data class Expected(val mutation: ComplaintAuditMutation, val action: AuditAction, val json: String)
}
