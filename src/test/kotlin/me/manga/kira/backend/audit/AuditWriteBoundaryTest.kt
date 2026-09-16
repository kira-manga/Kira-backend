package me.manga.kira.backend.audit

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.audit.domain.AuditRepository
import me.manga.kira.backend.audit.domain.NewAuditEntry
import me.manga.kira.backend.audit.infrastructure.AuditLogEntity
import me.manga.kira.backend.audit.infrastructure.JpaAuditRepositoryAdapter
import me.manga.kira.backend.audit.infrastructure.SpringDataAuditLogRepository
import me.manga.kira.backend.security.CurrentUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AuditWriteBoundaryTest {
    private val now = Instant.parse("2026-09-14T00:00:00Z")
    private val explicitTime = Instant.parse("2011-02-03T04:05:06.123456789Z")
    private val actorId = UUID.fromString("33333333-3333-4333-8333-333333333333")

    @Test
    fun `both generic service entries reject every complaint action before encoding or recording`() {
        val audit = mock(AuditRepository::class.java)
        val service = AuditService(audit, CurrentUser(), Clock.fixed(now, ZoneOffset.UTC))
        for (action in AuditAction.entries.filter { it.wire.startsWith("COMPLAINT_") }) {
            val detail = mapOf("unsupported" to listOf("synthetic-private-body"))
            val recordFailure = assertThrows(IllegalArgumentException::class.java) {
                service.record(action, "complaint", "synthetic-private-subject", detail, actorUserId = null)
            }
            val recordAtFailure = assertThrows(IllegalArgumentException::class.java) {
                service.recordAt(action, "complaint", "synthetic-private-subject", explicitTime, detail, actorUserId = null)
            }
            assertEquals("Complaint audit writes are not available through the ordinary route.", recordFailure.message)
            assertEquals(recordFailure.message, recordAtFailure.message)
        }
        verifyNoInteractions(audit)
    }

    @Test
    fun `raw adapter refuses the whole complaint prefix including excluded imports and unknown actions before JPA`() {
        val jpa = mock(SpringDataAuditLogRepository::class.java)
        val adapter = JpaAuditRepositoryAdapter(jpa)
        val actions = AuditAction.entries.filter { it.wire.startsWith("COMPLAINT_") }.map { it.wire } + listOf(
            "COMPLAINT_IMPORT_SEALED",
            "COMPLAINT_IMPORT_PROMOTED",
            "COMPLAINT_FUTURE_ACTION",
            "COMPLAINT_",
            "COMPLAINT_synthetic-private-canary\n",
        )
        for (action in actions) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                adapter.record(NewAuditEntry(actorId, action, "complaint", "synthetic-private-subject", "not-json", explicitTime))
            }
            assertEquals("Complaint audit writes are not available through the ordinary route.", failure.message)
        }
        verifyNoInteractions(jpa)
    }

    @Test
    fun `ordinary source auth and tutorial writes retain scalar encoding ordering actors and explicit time`() {
        val audit = mock(AuditRepository::class.java)
        val service = AuditService(audit, CurrentUser(), Clock.fixed(now, ZoneOffset.UTC))
        val detail = linkedMapOf("z" to null, "string" to "stable-id", "boolean" to true, "int" to 7, "long" to Long.MAX_VALUE)
        val encoded = """{"z":null,"string":"stable-id","boolean":true,"int":7,"long":9223372036854775807}"""
        for ((action, entityType) in listOf(
            AuditAction.SOURCE_CREATED to AuditService.ENTITY_SOURCE,
            AuditAction.LOGIN_FAILED to AuditService.ENTITY_USER,
            AuditAction.TUTORIAL_PUBLISHED to AuditService.ENTITY_TUTORIAL,
        )) {
            val actor = if (action == AuditAction.LOGIN_FAILED) null else actorId
            service.recordAt(action, entityType, "stable-id", explicitTime, detail, actor)
            verify(audit).record(NewAuditEntry(actor, action.wire, entityType, "stable-id", encoded, explicitTime))
        }
        service.record(AuditAction.SOURCE_ENABLED, AuditService.ENTITY_SOURCE, "source-id", actorUserId = actorId)
        verify(audit).record(NewAuditEntry(actorId, "SOURCE_ENABLED", "source", "source-id", "{}", now))
        verifyNoMoreInteractions(audit)
    }

    @Test
    fun `ordinary scalar encoder still rejects unsupported types without recording`() {
        val audit = mock(AuditRepository::class.java)
        val service = AuditService(audit, CurrentUser(), Clock.fixed(now, ZoneOffset.UTC))
        for (unsupported in listOf(1.5, 1.5f, listOf("id"), mapOf("id" to "value"), arrayOf("id"), actorId)) {
            assertThrows(IllegalStateException::class.java) {
                service.recordAt(AuditAction.SOURCE_CREATED, "source", "id", explicitTime, mapOf("unsupported" to unsupported), actorId)
            }
        }
        verifyNoInteractions(audit)
    }

    @Test
    fun `complaint byte limit does not constrain the ordinary encoder`() {
        val audit = mock(AuditRepository::class.java)
        val service = AuditService(audit, CurrentUser(), Clock.fixed(now, ZoneOffset.UTC))
        val longIdentifier = "a".repeat(2049)
        service.recordAt(AuditAction.SOURCE_CREATED, "source", "id", explicitTime, mapOf("checksum" to longIdentifier), actorId)
        verify(audit).record(NewAuditEntry(actorId, "SOURCE_CREATED", "source", "id", "{\"checksum\":\"$longIdentifier\"}", explicitTime))
        verifyNoMoreInteractions(audit)
    }

    @Test
    fun `ordinary adapter maps the original fields verbatim with one JPA save`() {
        val jpa = mock(SpringDataAuditLogRepository::class.java)
        val adapter = JpaAuditRepositoryAdapter(jpa)
        val entry = NewAuditEntry(actorId, "TUTORIAL_PUBLISHED", "tutorial", "stable-id", """{"revision":7}""", explicitTime)
        adapter.record(entry)

        val invocations = mockingDetails(jpa).invocations.toList()
        assertEquals(listOf("save"), invocations.map { it.method.name })
        val saved = invocations.single().arguments.single() as AuditLogEntity
        assertEquals(entry.actorUserId, saved.actorUserId)
        assertEquals(entry.action, saved.action)
        assertEquals(entry.entityType, saved.entityType)
        assertEquals(entry.entityId, saved.entityId)
        assertEquals(entry.detailJson, saved.detail)
        assertEquals(entry.createdAt, saved.createdAt)
        assertEquals(null, saved.id)
        assertEquals(null, saved.complaintDataScopeId)
        assertEquals(null, saved.complaintActorKind)
    }
}
