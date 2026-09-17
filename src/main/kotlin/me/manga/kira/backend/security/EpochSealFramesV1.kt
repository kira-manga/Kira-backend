package me.manga.kira.backend.security

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/** Fixed LP32BE-UTF8 grammar; bounded one-frame buffers and incremental manifest updates. */
internal object EpochSealFramesV1 {
    const val DOMAIN = "kira-complaint-journal-epoch-seal-v1"
    const val AAD_DOMAIN = "kira-complaint-journal-aad-v1"
    const val KMS_DOMAIN = "kira-complaint-journal-kms-context-v1"
    const val CONTEXT_KEY = "kira-complaint-journal-context-v1"
    private const val MAX_FRAME_BYTES = 2 * 98_304

    fun frame(fields: List<String>): ByteArray {
        requireEpochSeal(fields.size in 1..64, EpochSealFailureV1.LIMIT_EXCEEDED)
        val encoded = ArrayList<ByteArray>(fields.size)
        try {
            var length = 0L
            fields.forEach {
                val bytes = utf8(it, MAX_FRAME_BYTES).also(encoded::add)
                length += 4L + bytes.size
                requireEpochSeal(length <= MAX_FRAME_BYTES, EpochSealFailureV1.LIMIT_EXCEEDED)
            }
            val output = ByteBuffer.allocate(length.toInt())
            encoded.forEach { output.putInt(it.size).put(it) }
            return output.array()
        } finally {
            encoded.forEach { it.fill(0) }
        }
    }

    fun update(digest: MessageDigest, fields: List<String>): Long {
        val bytes = frame(fields)
        return try {
            digest.update(bytes)
            bytes.size.toLong()
        } finally {
            bytes.fill(0)
        }
    }

    fun utf8(value: String, maximumBytes: Int): ByteArray {
        requireEpochSeal(value.length <= maximumBytes, EpochSealFailureV1.LIMIT_EXCEEDED)
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > maximumBytes || bytes.toString(Charsets.UTF_8) != value) {
            bytes.fill(0)
            throw EpochSealExceptionV1(EpochSealFailureV1.INVALID_INPUT)
        }
        return bytes
    }

    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun opaque(value: String) {
        requireEpochSeal(value.length == 43 && value.all { (it.isLetterOrDigit() && it.code < 128) || it == '-' || it == '_' })
        val decoded = Base64.getUrlDecoder().decode(value)
        try {
            requireEpochSeal(decoded.size == 32 && encode(decoded) == value)
        } finally {
            decoded.fill(0)
        }
    }
}
