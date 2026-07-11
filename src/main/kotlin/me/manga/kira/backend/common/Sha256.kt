package me.manga.kira.backend.common

import java.security.MessageDigest

/**
 * SHA-256 hex digest utility (PLAN §3 common/, §5). Produces **lowercase** hex, matching the
 * ETag/checksum examples in PLAN §4.1 (`ETag: "a1b2…"`). Checksums are always computed over the
 * exact canonical UTF-8 bytes (§5) — never over a `jsonb` round-trip.
 */
object Sha256 {

    /** Lowercase SHA-256 hex of the given bytes. */
    fun hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val v = byte.toInt() and 0xFF
            out.append(HEX[v ushr 4])
            out.append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    /** Lowercase SHA-256 hex of the UTF-8 encoding of [text]. */
    fun hexUtf8(text: String): String = hex(text.toByteArray(Charsets.UTF_8))

    private val HEX = "0123456789abcdef".toCharArray()
}
