package me.manga.kira.backend.complaint.domain

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64

/** Fixed v1 STATUS framing, text-sorted intact ID/tag pairs; neither proof nor JSON formatting is identity. */
internal class ComplaintAdminBatchStatusFingerprint private constructor(private val digest: ByteArray) {
    val encoded: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    fun bytes(): ByteArray = digest.copyOf()
    override fun toString(): String = "ComplaintAdminBatchStatusFingerprint(redacted)"

    companion object {
        const val VERSION = 1
        const val MAX_FRAME_BYTES = 8192

        fun of(request: ComplaintAdminBatchStatusRequest): ComplaintAdminBatchStatusFingerprint {
            val frame = frameBytes(request)
            return try {
                ComplaintAdminBatchStatusFingerprint(MessageDigest.getInstance("SHA-256").digest(frame))
            } finally {
                frame.fill(0)
            }
        }

        /** Four-byte big-endian lengths and count, matching the existing complaint framing convention. */
        internal fun frameBytes(request: ComplaintAdminBatchStatusRequest): ByteArray {
            val buffer = FrameBuffer()
            return try {
                DataOutputStream(buffer).use { frame ->
                    frame.field("kira-complaint-request-fingerprint")
                    frame.writeInt(VERSION)
                    frame.field("POST")
                    frame.field(ComplaintAdminBatchStatusTuple.ROUTE)
                    frame.field(ComplaintAdminBatchStatusTuple.OPERATION)
                    frame.field(request.scope.id.toString())
                    frame.writeInt(request.targets.size)
                    request.targets.forEach { target ->
                        frame.field(target.id.toString())
                        frame.field(target.precondition.canonical)
                    }
                    frame.field(request.status.name)
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

private fun DataOutputStream.field(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    try {
        writeInt(bytes.size)
        write(bytes)
    } finally {
        bytes.fill(0)
    }
}
