package me.manga.kira.backend.audit

import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.audit.domain.ComplaintAuditActor
import me.manga.kira.backend.audit.domain.ComplaintAuditActorKind
import me.manga.kira.backend.audit.domain.ComplaintAuditMutation
import me.manga.kira.backend.audit.domain.ComplaintAuditResourceSubject
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintField
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.ComplaintValidationException
import me.manga.kira.backend.complaint.domain.ComplaintValidationReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class ComplaintAuditMutationTest {
    private val resourceId = "11111111-1111-4111-8111-111111111111"
    private val testScopeId = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val adminId = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val subject = ComplaintAuditResourceSubject.of(ComplaintDataScope.of(testScopeId), resourceId)
    private val admin = ComplaintAuditActor.of(ComplaintAuditActorKind.ADMIN, adminId)

    @Test
    fun `non-W06 complaint catalogue is exactly the literal 23 identities rather than historical V14 25`() {
        val expected = setOf(
            "COMPLAINT_CREATED",
            "COMPLAINT_REPLIED",
            "COMPLAINT_CONTENT_EDITED",
            "COMPLAINT_STATUS_CHANGED",
            "COMPLAINT_CLOSED",
            "COMPLAINT_DELETE_AUTHORIZED",
            "COMPLAINT_DELETED",
            "COMPLAINT_INSTALLATION_ENROLLED",
            "COMPLAINT_INSTALLATION_DELETE_AUTHORIZED",
            "COMPLAINT_INSTALLATION_DELETED",
            "COMPLAINT_INSTALLATION_RETIRED",
            "COMPLAINT_RETENTION_AUTHORIZED",
            "COMPLAINT_RETENTION_APPLIED",
            "COMPLAINT_RECOVERY_APPLIED",
            "COMPLAINT_RECOVERY_CONFLICT",
            "COMPLAINT_TEST_RUN_ACTIVATED",
            "COMPLAINT_TEST_RUN_SEALED",
            "COMPLAINT_TEST_RUN_PURGED",
            "COMPLAINT_CATALOG_PROJECTED",
            "COMPLAINT_JOURNAL_RETIREMENT_AUTHORIZED",
            "COMPLAINT_JOURNAL_RETIRED",
            "COMPLAINT_CAPACITY_RECONCILED",
            "COMPLAINT_RESTORE_RECONCILED",
        )
        val actual = AuditAction.entries.filter { it.name.startsWith("COMPLAINT_") || it.wire.startsWith("COMPLAINT_") }
        assertEquals(23, actual.size)
        assertEquals(expected, actual.map { it.wire }.toSet())
        assertEquals(expected, actual.map { it.name }.toSet())
        assertFalse(AuditAction.entries.any { it.name == "COMPLAINT_IMPORT_SEALED" || it.wire == "COMPLAINT_IMPORT_SEALED" })
        assertFalse(AuditAction.entries.any { it.name == "COMPLAINT_IMPORT_PROMOTED" || it.wire == "COMPLAINT_IMPORT_PROMOTED" })
    }

    @Test
    fun `subjects require canonical resource syntax and an already checked live or test scope`() {
        // Server resource IDs are not restricted to v4, unlike non-live scope IDs.
        val serverResource = "aaaaaaaa-aaaa-1aaa-8aaa-aaaaaaaaaaaa"
        val live = ComplaintAuditResourceSubject.of(ComplaintDataScope.LIVE, serverResource)
        assertEquals(UUID.fromString(serverResource), live.resourceId)
        assertEquals(ComplaintDataScope.LIVE, live.scope)
        assertEquals(testScopeId, subject.scope.id)

        for (invalid in listOf("", "1-1-1-1-1", serverResource.uppercase(), "$resourceId\n", "synthetic-private-body")) {
            val failure = assertThrows(ComplaintValidationException::class.java) {
                ComplaintAuditResourceSubject.of(ComplaintDataScope.LIVE, invalid)
            }
            assertEquals(ComplaintField.RESOURCE_ID, failure.field)
            assertEquals(ComplaintValidationReason.NON_CANONICAL_UUID, failure.reason)
            assertEquals("Complaint validation failed: RESOURCE_ID/NON_CANONICAL_UUID.", failure.message)
        }
        for (invalid in listOf(serverResource, "00000000-0000-0000-0000-000000000001", "bbbbbbbb-bbbb-4bbb-0bbb-bbbbbbbbbbbb")) {
            val failure = assertThrows(ComplaintValidationException::class.java) {
                ComplaintAuditResourceSubject.of(ComplaintDataScope.of(UUID.fromString(invalid)), resourceId)
            }
            assertEquals(ComplaintField.DATA_SCOPE_ID, failure.field)
            assertEquals(ComplaintValidationReason.INVALID_SCOPE, failure.reason)
            assertEquals("Complaint validation failed: DATA_SCOPE_ID/INVALID_SCOPE.", failure.message)
        }
    }

    @Test
    fun `only ADMIN carries a user FK and invalid pair diagnostics reveal no UUID`() {
        assertEquals(setOf("ADMIN", "INSTALLATION", "SYSTEM"), ComplaintAuditActorKind.entries.map { it.name }.toSet())
        assertEquals(adminId, admin.adminUserId)
        for (kind in listOf(ComplaintAuditActorKind.INSTALLATION, ComplaintAuditActorKind.SYSTEM)) {
            assertEquals(null, ComplaintAuditActor.of(kind).adminUserId)
            val failure = assertThrows(IllegalArgumentException::class.java) { ComplaintAuditActor.of(kind, adminId) }
            assertEquals("Complaint audit Admin identity must be present exactly for ADMIN.", failure.message)
        }
        val missingAdmin = assertThrows(IllegalArgumentException::class.java) { ComplaintAuditActor.of(ComplaintAuditActorKind.ADMIN) }
        assertEquals("Complaint audit Admin identity must be present exactly for ADMIN.", missingAdmin.message)
    }

    @Test
    fun `every normal mutation requires a positive version and redacts its metadata`() {
        val mutations: List<(Long) -> ComplaintAuditMutation> = listOf(
            { ComplaintAuditMutation.Created(subject, admin, it) },
            { ComplaintAuditMutation.Replied(subject, admin, it) },
            { ComplaintAuditMutation.ContentEdited(subject, admin, it) },
            { ComplaintAuditMutation.StatusChanged(subject, admin, it, ComplaintStatus.OPEN, ComplaintStatus.IN_PROGRESS) },
            { ComplaintAuditMutation.Closed(subject, admin, it, ComplaintStatus.OPEN) },
        )
        for (build in mutations) {
            for (version in listOf(0L, -1L, Long.MIN_VALUE)) {
                val failure = assertThrows(IllegalArgumentException::class.java) { build(version) }
                assertEquals("Complaint audit version must be positive.", failure.message)
            }
            val maximum = build(Long.MAX_VALUE)
            assertEquals(Long.MAX_VALUE, maximum.version)
            val diagnostics = listOf(maximum.toString(), maximum.subject.toString(), maximum.actor.toString(), maximum.subject.scope.toString())
            for (diagnostic in diagnostics) {
                for (privateValue in listOf(resourceId, testScopeId.toString(), adminId.toString(), Long.MAX_VALUE.toString())) {
                    assertFalse(diagnostic.contains(privateValue))
                }
            }
        }
    }

    @Test
    fun `status change uses closed moderation vocabulary and closure does not accept a reason`() {
        for (target in listOf(ComplaintStatus.CLOSED, ComplaintStatus.PINNED, ComplaintStatus.UNKNOWN)) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                ComplaintAuditMutation.StatusChanged(subject, admin, 2, ComplaintStatus.OPEN, target)
            }
            assertEquals("Complaint audit status target must be an ordinary moderation target.", failure.message)
        }
        val noChange = assertThrows(IllegalArgumentException::class.java) {
            ComplaintAuditMutation.StatusChanged(subject, admin, 2, ComplaintStatus.OPEN, ComplaintStatus.OPEN)
        }
        assertEquals("Complaint audit status transition must change status.", noChange.message)

        val reopened = ComplaintAuditMutation.StatusChanged(subject, admin, 2, ComplaintStatus.CLOSED, ComplaintStatus.OPEN)
        assertEquals(ComplaintStatus.OPEN, reopened.toStatus)
        val reclosed = ComplaintAuditMutation.Closed(subject, admin, 3, ComplaintStatus.CLOSED)
        assertEquals(ComplaintStatus.CLOSED, reclosed.toStatus)
        // The actual writer must establish a changed closure; this metadata never carries its prose.
        assertEquals(AuditAction.COMPLAINT_CLOSED, reclosed.action)
    }
}
