package me.manga.kira.backend.audit

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.AuditRepository
import me.manga.kira.backend.audit.domain.ComplaintAuditActor
import me.manga.kira.backend.audit.domain.ComplaintAuditActorKind
import me.manga.kira.backend.audit.domain.ComplaintAuditAllocation
import me.manga.kira.backend.audit.domain.ComplaintAuditMutation
import me.manga.kira.backend.audit.domain.ComplaintAuditResourceSubject
import me.manga.kira.backend.audit.domain.CountedComplaintAuditEntry
import me.manga.kira.backend.audit.infrastructure.JpaAuditRepositoryAdapter
import me.manga.kira.backend.audit.infrastructure.SpringDataAuditLogRepository
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.security.CurrentUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import java.time.Clock
import java.time.Instant
import java.util.UUID

class CountedComplaintAuditBoundaryTest {
    private val actor = ComplaintAuditActor.of(ComplaintAuditActorKind.ADMIN, UUID.fromString("33333333-3333-4333-8333-333333333333"))
    private val subject = ComplaintAuditResourceSubject.of(ComplaintDataScope.LIVE, "11111111-1111-4111-8111-111111111111")
    private val at = Instant.parse("2011-02-03T04:05:06.123456789Z")

    @Test
    fun `five typed payloads use the existing encoder and remain candidates without any persistence`() {
        val repository = mock(AuditRepository::class.java)
        val clock = mock(Clock::class.java)
        val service = AuditService(repository, CurrentUser(), clock)
        val mutations = listOf(
            ComplaintAuditMutation.Created(subject, actor, 1),
            ComplaintAuditMutation.Replied(subject, actor, 1),
            ComplaintAuditMutation.ContentEdited(subject, actor, Long.MAX_VALUE),
            ComplaintAuditMutation.StatusChanged(subject, actor, 2, ComplaintStatus.OPEN, ComplaintStatus.IN_PROGRESS),
            ComplaintAuditMutation.Closed(subject, actor, 3, ComplaintStatus.PLANNED),
        )
        for (mutation in mutations) {
            val prepared = service.prepareComplaintMutation(mutation, at)
            val entry = CountedComplaintAuditEntry(mutation, prepared.detailJson, prepared.createdAt)
            assertSame(mutation, entry.mutation)
            assertEquals(prepared.detailJson, entry.detailJson)
            assertEquals(at, entry.createdAt)
            assertEquals("CountedComplaintAuditEntry(redacted)", entry.toString())
        }
        verifyNoInteractions(repository, clock)
    }

    @Test
    fun `direct port payload cannot add prose proof identifiers nested objects or change typed scalar values`() {
        val mutation = ComplaintAuditMutation.ContentEdited(subject, actor, 2)
        val invalid = listOf(
            "{}",
            """{"version":1}""",
            """{"version":"2"}""",
            """{"version":2.0}""",
            """{"version":{"text":"synthetic-private"}}""",
            """{"version":2,"proof":"synthetic-private"}""",
            """{"version":2,"installationId":"synthetic-private"}""",
            """{"version":"synthetic-private","version":2}""",
            "{\"version\":2,\"text\":\"${"x".repeat(2048)}\"}",
        )
        for (detail in invalid) {
            val failure = assertThrows<IllegalArgumentException> { CountedComplaintAuditEntry(mutation, detail, at) }
            assertFalse(failure.message.orEmpty().contains("synthetic-private"))
        }
    }

    @Test
    fun `typed service with a caller-forged allocation fails closed before touching ordinary JPA`() {
        val jpa = mock(SpringDataAuditLogRepository::class.java)
        val clock = mock(Clock::class.java)
        val service = AuditService(JpaAuditRepositoryAdapter(jpa), CurrentUser(), clock)
        val mutation = ComplaintAuditMutation.ContentEdited(subject, actor, 2)

        assertThrows<PersistencePhaseException> { service.recordComplaintMutation(mutation, object : ComplaintAuditAllocation {}, at) }

        verifyNoInteractions(jpa, clock)
    }
}
