package me.manga.kira.backend.common.infrastructure.persistence

import java.math.BigDecimal

/** A finite external establishment budget; pgjdbc's own unowned ConnectThread stays disabled. */
internal class PersistenceLoginPolicy private constructor(val durationMillis: Long) {
    val jdbcSeconds: Int get() = ((durationMillis + 999) / 1000).toInt()

    /** A fractional duration has no exact integer-second setter value, even its rounded getter. */
    fun acceptsJdbcSeconds(seconds: Int): Boolean = seconds > 0 && seconds.toLong() * 1000 == durationMillis

    override fun toString(): String = "PersistenceLoginPolicy(redacted)"

    companion object {
        private val MAX_MILLIS = minOf(Long.MAX_VALUE / 1_000_000, Int.MAX_VALUE * 1000L)
        private val MAX_SECONDS = BigDecimal.valueOf(MAX_MILLIS, 3)
        private val MIN_SECONDS = BigDecimal.valueOf(1, 3)
        private val DECIMAL_SECONDS = Regex("[+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

        fun resolve(seconds: String?, checkoutMillis: Long): PersistenceLoginPolicy {
            if (checkoutMillis !in 1..MAX_MILLIS) rejectPersistenceBoundary(PersistenceBoundaryFailureCode.INVALID_LOGIN_POLICY)
            if (seconds == null) return PersistenceLoginPolicy(checkoutMillis)
            if (seconds.length !in 1..64 || !DECIMAL_SECONDS.matches(seconds)) {
                rejectPersistenceBoundary(PersistenceBoundaryFailureCode.INVALID_LOGIN_POLICY)
            }
            return fromDecimal(seconds, checkoutMillis)
        }

        private fun fromDecimal(seconds: String, checkoutMillis: Long): PersistenceLoginPolicy = try {
            val decimal = BigDecimal(seconds)
            if (decimal.signum() == 0) {
                PersistenceLoginPolicy(checkoutMillis)
            } else {
                // Compare magnitudes BEFORE expanding scale. A short huge exponent must not allocate a huge integer.
                if (decimal < MIN_SECONDS || decimal > MAX_SECONDS) {
                    rejectPersistenceBoundary(PersistenceBoundaryFailureCode.INVALID_LOGIN_POLICY)
                }
                PersistenceLoginPolicy(decimal.movePointRight(3).longValueExact())
            }
        } catch (_: NumberFormatException) {
            rejectPersistenceBoundary(PersistenceBoundaryFailureCode.INVALID_LOGIN_POLICY)
        } catch (_: ArithmeticException) {
            rejectPersistenceBoundary(PersistenceBoundaryFailureCode.INVALID_LOGIN_POLICY)
        }
    }
}
