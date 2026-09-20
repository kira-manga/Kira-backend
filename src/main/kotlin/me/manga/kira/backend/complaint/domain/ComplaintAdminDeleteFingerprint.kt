package me.manga.kira.backend.complaint.domain

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64

/** Exact independent v1 empty-body DELETE frame; no null/body, actor or credential slot. */
internal class ComplaintAdminDeleteFingerprint private constructor(private val digest: ByteArray) {
    val encoded: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)

    fun bytes(): ByteArray = digest.copyOf()

    override fun toString(): String = "ComplaintAdminDeleteFingerprint(redacted)"

    companion object {
        const val VERSION = 1
        const val MAX_FRAME_BYTES = 288
        const val ROUTE = "/api/v1/admin/complaints/{id}"

        fun of(request: ComplaintAdminDeleteRequest): ComplaintAdminDeleteFingerprint {
            val frame = frameBytes(request)
            return try {
                ComplaintAdminDeleteFingerprint(MessageDigest.getInstance("SHA-256").digest(frame))
            } finally {
                frame.fill(0)
            }
        }

        internal fun frameBytes(request: ComplaintAdminDeleteRequest): ByteArray {
            val buffer = ByteArrayOutputStream(MAX_FRAME_BYTES)
            DataOutputStream(buffer).use { frame ->
                frame.field("kira-complaint-request-fingerprint")
                frame.writeInt(VERSION)
                frame.field("DELETE")
                frame.field(ROUTE)
                frame.field(ComplaintAdminDeleteTuple.OPERATION)
                frame.field(request.scope.id.toString())
                frame.writeInt(1)
                frame.field(request.targetId.toString())
                frame.field(request.precondition.canonical)
            }
            return buffer.toByteArray().also { check(it.size <= MAX_FRAME_BYTES) { "Complaint frame exceeds limit." } }
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
