package me.manga.kira.backend.complaint.domain

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64

/** Fixed owner-edit v1 bytes. This does not claim a mobile edit producer or executed parity proof. */
internal class ComplaintOwnerEditFingerprint private constructor(private val digest: ByteArray) {
    val encoded: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)

    fun bytes(): ByteArray = digest.copyOf()

    override fun toString(): String = "ComplaintOwnerEditFingerprint(redacted)"

    companion object {
        const val VERSION = 1
        const val MAX_FRAME_BYTES = 6144
        const val ROUTE = "/api/v1/complaints/{id}/content"

        fun of(request: ComplaintOwnerEditRequest): ComplaintOwnerEditFingerprint {
            val frame = frameBytes(request)
            return try {
                ComplaintOwnerEditFingerprint(MessageDigest.getInstance("SHA-256").digest(frame))
            } finally {
                frame.fill(0)
            }
        }

        /** Transient prose-bearing frame only; never log or persist it. Four-byte big-endian lengths, null = -1. */
        internal fun frameBytes(request: ComplaintOwnerEditRequest): ByteArray {
            val buffer = ByteArrayOutputStream(MAX_FRAME_BYTES)
            DataOutputStream(buffer).use { frame ->
                frame.field("kira-complaint-request-fingerprint")
                frame.writeInt(VERSION)
                frame.field("PATCH")
                frame.field(ROUTE)
                frame.field(ComplaintOwnerEditTuple.OPERATION)
                frame.field(request.scope.id.toString())
                frame.writeInt(1)
                frame.field(request.targetId.toString())
                frame.field(request.subject)
                frame.field(request.body)
                frame.field(request.precondition.canonical)
            }
            return buffer.toByteArray().also { check(it.size <= MAX_FRAME_BYTES) { "Complaint frame exceeds limit." } }
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
