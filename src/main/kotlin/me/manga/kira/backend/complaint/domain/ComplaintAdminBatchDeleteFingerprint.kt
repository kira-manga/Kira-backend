package me.manga.kira.backend.complaint.domain

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Same LP framing as STATUS, with a distinct operation and no status/proof/JSON-format field. */
internal object ComplaintAdminBatchDeleteFingerprint {
    const val MAX_FRAME_BYTES = 8192
    fun of(request: ComplaintAdminDeleteRequest): ByteArray {
        check(request.family == ComplaintAdminDeleteFamily.BATCH)
        val buffer = ByteArrayOutputStream(MAX_FRAME_BYTES)
        DataOutputStream(buffer).use { frame ->
            frame.field("kira-complaint-request-fingerprint")
            frame.writeInt(1)
            frame.field("POST")
            frame.field(ComplaintAdminBatchDeleteInput.ROUTE)
            frame.field(ComplaintAdminDeleteFamily.BATCH.operation)
            frame.field(request.scope.id.toString())
            frame.writeInt(request.targets.size)
            request.targets.forEach { frame.field(it.id.toString()); frame.field(it.precondition.canonical) }
        }
        val bytes = buffer.toByteArray()
        return try { check(bytes.size <= MAX_FRAME_BYTES); MessageDigest.getInstance("SHA-256").digest(bytes) }
        finally { bytes.fill(0) }
    }
    private fun DataOutputStream.field(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        try { writeInt(bytes.size); write(bytes) } finally { bytes.fill(0) }
    }
}
