package me.manga.kira.backend.complaint.domain

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64

/** An exact normalized request digest, not authentication, a receipt or permission to dispatch. */
internal class ComplaintReportFingerprint private constructor(private val digest: ByteArray) {
    val version: Int get() = VERSION
    val encoded: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)

    fun bytes(): ByteArray = digest.copyOf()

    override fun toString(): String = "ComplaintReportFingerprint(redacted)"

    companion object {
        private const val VERSION = 1
        private const val MAX_FRAME_BYTES = 4_806

        fun of(request: ComplaintReportRequest): ComplaintReportFingerprint {
            val frame = frameBytes(request)
            return try {
                ComplaintReportFingerprint(MessageDigest.getInstance("SHA-256").digest(frame))
            } finally {
                frame.fill(0)
            }
        }

        /** Owned transient prose-bearing bytes for the producer and byte-identical contract fixtures; never persist/log. */
        internal fun frameBytes(request: ComplaintReportRequest): ByteArray {
            val buffer = ByteArrayOutputStream(MAX_FRAME_BYTES)
            DataOutputStream(buffer).use { frame ->
                frame.field("kira-complaint-request-fingerprint")
                frame.writeInt(VERSION)
                frame.field("POST")
                frame.field("/api/v1/complaints")
                frame.field(request.operation.name)
                frame.field(request.identity.dataScopeId)
                frame.writeInt(1)
                frame.field(request.identity.clientId.canonical)
                frame.field(request.type.name)
                frame.field(request.subject)
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

/** DataOutputStream writes four-byte big-endian words; -1 is the unsigned null sentinel FFFFFFFF. */
private fun DataOutputStream.field(value: String?) {
    if (value == null) {
        writeInt(-1)
    } else {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
}
