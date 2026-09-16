package me.manga.kira.backend.common.infrastructure.persistence

import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** Closed stock values: input must never trigger discovery or construction of a charset provider. */
internal enum class PersistenceUrlEncoding(private val charset: Charset) {
    US_ASCII(StandardCharsets.US_ASCII),
    ISO_8859_1(StandardCharsets.ISO_8859_1),
    UTF_8(StandardCharsets.UTF_8),
    UTF_16BE(StandardCharsets.UTF_16BE),
    UTF_16LE(StandardCharsets.UTF_16LE),
    UTF_16(StandardCharsets.UTF_16),
    ;

    fun decode(value: String): String = try {
        // The Charset-object overload does not perform name/provider lookup, even for rejected escapes.
        URLDecoder.decode(value, charset)
    } catch (_: IllegalArgumentException) {
        rejectPersistenceBoundary(PersistenceBoundaryFailureCode.INVALID_JDBC_URL)
    }

    companion object {
        fun select(name: String): PersistenceUrlEncoding {
            if (name.isEmpty() || name.any { it.code > 127 }) {
                rejectPersistenceBoundary(PersistenceBoundaryFailureCode.INVALID_JDBC_ENCODING)
            }
            return entries.firstOrNull { encoding ->
                encoding.charset.name().equals(name, ignoreCase = true) ||
                    encoding.charset.aliases().any { it.equals(name, ignoreCase = true) }
            } ?: rejectPersistenceBoundary(PersistenceBoundaryFailureCode.INVALID_JDBC_ENCODING)
        }
    }
}
