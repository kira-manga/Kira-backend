package me.manga.kira.backend.complaint.domain

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64

/** Fixed ADMIN_EDIT v1 framing. No frontend transport or already-integrated parity is implied. */
internal class ComplaintAdminContentFingerprint private constructor(private val digest: ByteArray) {
    val encoded: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    fun bytes(): ByteArray = digest.copyOf()
    override fun toString(): String = "ComplaintAdminContentFingerprint(redacted)"

    companion object {
        const val VERSION = 1
        const val MAX_FRAME_BYTES = 6144
        const val ROUTE = "/api/v1/admin/complaints/{id}/content"

        fun of(request: ComplaintAdminContentRequest): ComplaintAdminContentFingerprint {
            val frame = frameBytes(request)
            return try {
                ComplaintAdminContentFingerprint(MessageDigest.getInstance("SHA-256").digest(frame))
            } finally {
                frame.fill(0)
            }
        }

        /** Four-byte big-endian lengths, null=-1; transient prose must never enter receipt/audit/logs. */
        internal fun frameBytes(request: ComplaintAdminContentRequest): ByteArray {
            val buffer = FrameBuffer()
            return try {
                DataOutputStream(buffer).use { frame ->
                    frame.field("kira-complaint-request-fingerprint")
                    frame.writeInt(VERSION)
                    frame.field("PATCH")
                    frame.field(ROUTE)
                    frame.field(ComplaintAdminContentTuple.OPERATION)
                    frame.field(request.scope.id.toString())
                    frame.writeInt(1)
                    frame.field(request.targetId.toString())
                    frame.field(request.type?.name)
                    frame.field(request.subject)
                    frame.field(request.body)
                    frame.field(request.precondition.canonical)
                }
                buffer.toByteArray().also { check(it.size <= MAX_FRAME_BYTES) { "Complaint frame exceeds limit." } }
            } finally {
                buffer.erase()
            }
        }
    }

    private class FrameBuffer : ByteArrayOutputStream(MAX_FRAME_BYTES) {
        fun erase() {
            buf.fill(0)
            reset()
        }
    }
}

private fun DataOutputStream.field(value: String?) {
    if (value == null) {
        writeInt(-1)
    } else {
        val bytes = value.toByteArray(Charsets.UTF_8)
        try {
            writeInt(bytes.size)
            write(bytes)
        } finally {
            bytes.fill(0)
        }
    }
}
