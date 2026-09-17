package me.manga.kira.backend.complaint.domain

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64

/** Comparison data only. Neither a digest nor a caller-created candidate authenticates an installation. */
internal class ComplaintDeleteAllFingerprint private constructor(private val digest: ByteArray) {
    val encoded: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)

    fun bytes(): ByteArray = digest.copyOf()

    override fun toString(): String = "ComplaintDeleteAllFingerprint(redacted)"

    companion object {
        /** The fixed preflight calls this only after retained-verifier and scope/version authentication. */
        fun of(candidate: InstallationDeletionCandidate): ComplaintDeleteAllFingerprint {
            val frame = frameBytes(candidate)
            return try {
                ComplaintDeleteAllFingerprint(MessageDigest.getInstance("SHA-256").digest(frame))
            } finally {
                frame.fill(0)
            }
        }

        /**
         * V6 §6.1, version 1: u32be byte lengths + UTF-8 fields; u32be version and target count.
         * Field order is domain, version, method, route, operation, scope, count, installation,
         * submitted version (canonical decimal), null precondition (FFFFFFFF). Scope/installation
         * each appear once in their designated slots. Secret and separately compared key are NOT
         * fingerprint fields; matching this digest never substitutes for either comparison.
         */
        internal fun frameBytes(candidate: InstallationDeletionCandidate): ByteArray {
            val buffer = ByteArrayOutputStream(MAX_FRAME_BYTES)
            DataOutputStream(buffer).use { frame ->
                frame.field("kira-complaint-request-fingerprint")
                frame.writeInt(1)
                frame.field("POST")
                frame.field("/api/v1/installations/delete-all")
                frame.field("OWNER_DELETE_ALL")
                frame.field(candidate.installation.scope.id.toString())
                frame.writeInt(1)
                frame.field(candidate.installation.id.toString())
                frame.field(candidate.credentialVersion.toString())
                frame.writeInt(-1)
            }
            return buffer.toByteArray().also { check(it.size <= MAX_FRAME_BYTES) }
        }

        private const val MAX_FRAME_BYTES = 256
    }
}

private fun DataOutputStream.field(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}
