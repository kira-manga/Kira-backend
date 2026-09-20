package me.manga.kira.backend.complaint

import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeletePrecondition
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRequest
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

class ComplaintAdminDeleteFingerprintTest {
    @Test
    fun `independent empty Admin DELETE frame has no owner body or proof slot and preserves exact Long`() {
        val target = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val scope = ComplaintDataScope.of(UUID.fromString("b2222222-2222-4222-8222-222222222222"))
        val tag = "\"complaint-$target-v9223372036854775807\""
        val request = ComplaintAdminDeleteRequest.normalize(ComplaintAdminDeleteInput(scope, target, UUID.randomUUID(), ComplaintAdminDeletePrecondition.parse(target, tag)))
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun field(text: String) { val raw = text.toByteArray(Charsets.UTF_8); out.writeInt(raw.size); out.write(raw) }
            field("kira-complaint-request-fingerprint"); out.writeInt(1); field("DELETE"); field("/api/v1/admin/complaints/{id}")
            field("ADMIN_DELETE"); field(scope.id.toString()); out.writeInt(1); field(target.toString()); field(tag)
        }
        assertArrayEquals(bytes.toByteArray(), ComplaintAdminDeleteFingerprint.frameBytes(request))
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()), ComplaintAdminDeleteFingerprint.of(request).bytes())
        assertTrue(bytes.size() <= 288)
        assertFalse(bytes.toString(Charsets.UTF_8).contains("null"))
        val changed = ComplaintAdminDeleteRequest.normalize(ComplaintAdminDeleteInput(scope, target, request.key, ComplaintAdminDeletePrecondition.parse(target, "\"complaint-$target-v9007199254740993\"")))
        assertFalse(ComplaintAdminDeleteFingerprint.of(changed).bytes().contentEquals(ComplaintAdminDeleteFingerprint.of(request).bytes()))
    }
}
