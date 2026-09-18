package me.manga.kira.backend.complaint.domain

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64

/** Fixed v1 reply frame. No claim of app byte parity: the app currently implements report production only. */
internal class ComplaintReplyFingerprint private constructor(private val digest: ByteArray) {
    val version: Int get() = VERSION
    val encoded: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)

    fun bytes(): ByteArray = digest.copyOf()

    override fun toString(): String = "ComplaintReplyFingerprint(redacted)"

    companion object {
        private const val VERSION = 1
        private const val MAX_FRAME_BYTES = 4_096

        fun of(request: ComplaintReplyRequest): ComplaintReplyFingerprint {
            val frame = frameBytes(request)
            return try {
                ComplaintReplyFingerprint(MessageDigest.getInstance("SHA-256").digest(frame))
            } finally {
                frame.fill(0)
            }
        }

        /** Transient prose-bearing fixture/producer bytes. Never persist or log this frame. */
        internal fun frameBytes(request: ComplaintReplyRequest): ByteArray {
            val buffer = ByteArrayOutputStream(MAX_FRAME_BYTES)
            DataOutputStream(buffer).use { frame ->
                frame.field("kira-complaint-request-fingerprint")
                frame.writeInt(VERSION)
                frame.field("POST")
                frame.field("/api/v1/complaints/{id}/replies")
                frame.field(request.operation.name)
                frame.field(request.identity.dataScopeId)
                frame.writeInt(2)
                frame.field(request.parentId.toString())
                frame.field(request.identity.clientId.canonical)
                frame.field(request.body)
                frame.field(request.metadata.appVersion)
                frame.field(request.metadata.osVersion)
                frame.field(request.metadata.manufacturer)
                frame.field(request.metadata.deviceModel)
                frame.field(null)
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
