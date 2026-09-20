package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import java.sql.BatchUpdateException
import java.sql.ClientInfoStatus
import java.sql.DataTruncation
import java.sql.SQLClientInfoException
import java.sql.SQLDataException
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.SQLInvalidAuthorizationSpecException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLNonTransientException
import java.sql.SQLRecoverableException
import java.sql.SQLSyntaxErrorException
import java.sql.SQLTimeoutException
import java.sql.SQLTransactionRollbackException
import java.sql.SQLTransientConnectionException
import java.sql.SQLTransientException
import java.sql.SQLWarning

internal const val RAW_JDBC_MARKER = "synthetic-raw-jdbc-detail"
internal const val RAW_JDBC_FRAME = "SyntheticRawJdbcFrame"

enum class JdbcFailureKind(val envelopeClass: Class<out SQLException>) {
    BASE(SQLException::class.java),
    TRANSIENT(SQLTransientException::class.java),
    TRANSIENT_CONNECTION(SQLTransientConnectionException::class.java),
    ROLLBACK(SQLTransactionRollbackException::class.java),
    TIMEOUT(SQLTimeoutException::class.java),
    NONTRANSIENT(SQLNonTransientException::class.java),
    NONTRANSIENT_CONNECTION(SQLNonTransientConnectionException::class.java),
    DATA(SQLDataException::class.java),
    INTEGRITY(SQLIntegrityConstraintViolationException::class.java),
    AUTHORIZATION(SQLInvalidAuthorizationSpecException::class.java),
    SYNTAX(SQLSyntaxErrorException::class.java),
    FEATURE(SQLFeatureNotSupportedException::class.java),
    RECOVERABLE(SQLRecoverableException::class.java),
    CLIENT_INFO(SQLClientInfoException::class.java),
    BATCH(BatchUpdateException::class.java),
    WARNING(SQLException::class.java),
    TRUNCATION(SQLException::class.java),
    ;

    val state: String get() = if (this == TRUNCATION) "22001" else "23505"
    val code: Int get() = if (this == TRUNCATION) 0 else -239

    fun raw(): SQLException {
        val failure = when (this) {
            BASE -> SQLException(RAW_JDBC_MARKER, state, code)
            TRANSIENT -> SQLTransientException(RAW_JDBC_MARKER, state, code)
            TRANSIENT_CONNECTION -> SQLTransientConnectionException(RAW_JDBC_MARKER, state, code)
            ROLLBACK -> SQLTransactionRollbackException(RAW_JDBC_MARKER, state, code)
            TIMEOUT -> SQLTimeoutException(RAW_JDBC_MARKER, state, code)
            NONTRANSIENT -> SQLNonTransientException(RAW_JDBC_MARKER, state, code)
            NONTRANSIENT_CONNECTION -> SQLNonTransientConnectionException(RAW_JDBC_MARKER, state, code)
            DATA -> SQLDataException(RAW_JDBC_MARKER, state, code)
            INTEGRITY -> SQLIntegrityConstraintViolationException(RAW_JDBC_MARKER, state, code)
            AUTHORIZATION -> SQLInvalidAuthorizationSpecException(RAW_JDBC_MARKER, state, code)
            SYNTAX -> SQLSyntaxErrorException(RAW_JDBC_MARKER, state, code)
            FEATURE -> SQLFeatureNotSupportedException(RAW_JDBC_MARKER, state, code)
            RECOVERABLE -> SQLRecoverableException(RAW_JDBC_MARKER, state, code)
            CLIENT_INFO -> SQLClientInfoException(RAW_JDBC_MARKER, state, code, mapOf(RAW_JDBC_MARKER to ClientInfoStatus.REASON_UNKNOWN))
            BATCH -> BatchUpdateException(RAW_JDBC_MARKER, state, code, intArrayOf(2, -3, 9))
            WARNING -> SQLWarning(RAW_JDBC_MARKER, state, code)
            TRUNCATION -> DataTruncation(7, true, false, 300, 200)
        }
        return failure.apply {
            initCause(IllegalArgumentException(RAW_JDBC_MARKER))
            addSuppressed(IllegalStateException(RAW_JDBC_MARKER))
            nextException = SQLException(RAW_JDBC_MARKER, "57014", 123)
            stackTrace = arrayOf(StackTraceElement(RAW_JDBC_FRAME, RAW_JDBC_MARKER, "synthetic.sql", 37))
        }
    }

    fun unknownSubclass(): SQLException = when (this) {
        BASE -> object : SQLException(RAW_JDBC_MARKER, state, code) {}
        TRANSIENT -> object : SQLTransientException(RAW_JDBC_MARKER, state, code) {}
        TRANSIENT_CONNECTION -> object : SQLTransientConnectionException(RAW_JDBC_MARKER, state, code) {}
        ROLLBACK -> object : SQLTransactionRollbackException(RAW_JDBC_MARKER, state, code) {}
        TIMEOUT -> object : SQLTimeoutException(RAW_JDBC_MARKER, state, code) {}
        NONTRANSIENT -> object : SQLNonTransientException(RAW_JDBC_MARKER, state, code) {}
        NONTRANSIENT_CONNECTION -> object : SQLNonTransientConnectionException(RAW_JDBC_MARKER, state, code) {}
        DATA -> object : SQLDataException(RAW_JDBC_MARKER, state, code) {}
        INTEGRITY -> object : SQLIntegrityConstraintViolationException(RAW_JDBC_MARKER, state, code) {}
        AUTHORIZATION -> object : SQLInvalidAuthorizationSpecException(RAW_JDBC_MARKER, state, code) {}
        SYNTAX -> object : SQLSyntaxErrorException(RAW_JDBC_MARKER, state, code) {}
        FEATURE -> object : SQLFeatureNotSupportedException(RAW_JDBC_MARKER, state, code) {}
        RECOVERABLE -> object : SQLRecoverableException(RAW_JDBC_MARKER, state, code) {}
        CLIENT_INFO -> object : SQLClientInfoException(RAW_JDBC_MARKER, state, code, null) {}
        BATCH -> object : BatchUpdateException(RAW_JDBC_MARKER, state, code, intArrayOf(42)) {}
        WARNING -> object : SQLWarning(RAW_JDBC_MARKER, state, code) {}
        TRUNCATION -> object : DataTruncation(7, true, false, 300, 200) {}
    }
}

enum class JdbcFailureDeclaration {
    SQL,
    CLIENT_INFO,
    FEATURE_NOT_SUPPORTED,
    ;

    internal fun adapt(helper: SafeJdbcFailure, failure: Throwable): SQLException = when (this) {
        SQL -> helper.sql(failure)
        CLIENT_INFO -> helper.clientInfo(failure)
        FEATURE_NOT_SUPPORTED -> helper.featureNotSupported(failure)
    }
}

internal fun assertSafeJdbcEnvelope(failure: SQLException, state: String?, code: Int) {
    assertEquals("Persistence operation failed.", failure.message)
    assertEquals(state, failure.sqlState)
    assertEquals(code, failure.errorCode)
    assertNull(failure.cause)
    assertNull(failure.nextException)
    assertTrue(failure.suppressed.isEmpty())
    assertThrows(IllegalStateException::class.java) { failure.initCause(IllegalStateException(RAW_JDBC_MARKER)) }
    assertNull(failure.cause)
    assertFalse(failure.stackTrace.any { it.className == RAW_JDBC_FRAME })
    assertTrue(failure.stackTrace.any { it.className == SafeJdbcFailure::class.java.name })
    assertFalse(failure.stackTraceToString().contains(RAW_JDBC_MARKER))
    if (failure is SQLClientInfoException) assertNull(failure.failedProperties)
    if (failure is BatchUpdateException) {
        assertTrue(failure.updateCounts.isEmpty())
        assertTrue(failure.largeUpdateCounts.isEmpty())
    }
}

internal fun withJdbcTestInterruptIsolation(action: () -> Unit) {
    val interruptedOnEntry = Thread.interrupted()
    try {
        action()
    } finally {
        Thread.interrupted()
        if (interruptedOnEntry) Thread.currentThread().interrupt()
    }
}
