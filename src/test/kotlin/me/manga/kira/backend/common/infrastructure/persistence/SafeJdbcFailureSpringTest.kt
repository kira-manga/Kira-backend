package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.core.SpringVersion
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.dao.PermissionDeniedDataAccessException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.dao.RecoverableDataAccessException
import org.springframework.dao.TransientDataAccessResourceException
import org.springframework.jdbc.BadSqlGrammarException
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import java.sql.BatchUpdateException
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

class SafeJdbcFailureSpringTest {
    @ParameterizedTest(name = "{displayName} [{index}] {0}")
    @MethodSource("translations")
    fun `locked Spring translator consumes the sanitized subtype and scalars`(label: String, raw: SQLException, expected: Class<out DataAccessException>?) {
        val envelope = SafeJdbcFailure.prepare().sql(raw)
        val translated = SQLExceptionSubclassTranslator().translate("synthetic-jdbc-task", null, envelope)
        assertEquals(expected, translated?.javaClass, label)
        if (translated != null) {
            assertSame(envelope, translated.cause)
            assertFalse(translated.stackTraceToString().contains(RAW_JDBC_MARKER))
        }
    }

    @Test
    fun `batch translation uses accepted top level metadata rather than the discarded raw chain`() {
        val raw = BatchUpdateException(RAW_JDBC_MARKER, "23505", 0, intArrayOf(7, -3))
        raw.nextException = SQLTimeoutException(RAW_JDBC_MARKER, "57014", 0)
        val envelope = SafeJdbcFailure.prepare().sql(raw)
        val translated = SQLExceptionSubclassTranslator().translate("synthetic-jdbc-task", null, envelope)
        assertEquals(DuplicateKeyException::class.java, translated?.javaClass)
        assertSafeJdbcEnvelope(envelope, "23505", 0)
    }

    @Test
    fun `consumer evidence runs against the researched Spring version`() {
        assertEquals("6.2.19", SpringVersion.getVersion())
    }

    companion object {
        @JvmStatic
        fun translations(): List<Arguments> = listOf(
            Arguments.of("transient connection", SQLTransientConnectionException(), TransientDataAccessResourceException::class.java),
            Arguments.of("rollback serialization", SQLTransactionRollbackException(RAW_JDBC_MARKER, "40001"), CannotAcquireLockException::class.java),
            Arguments.of("rollback without state", SQLTransactionRollbackException(), PessimisticLockingFailureException::class.java),
            Arguments.of("timeout without state", SQLTimeoutException(), QueryTimeoutException::class.java),
            Arguments.of("nontransient connection", SQLNonTransientConnectionException(), DataAccessResourceFailureException::class.java),
            Arguments.of("data", SQLDataException(), DataIntegrityViolationException::class.java),
            Arguments.of("integrity duplicate", SQLIntegrityConstraintViolationException(RAW_JDBC_MARKER, "23505"), DuplicateKeyException::class.java),
            Arguments.of("integrity without state", SQLIntegrityConstraintViolationException(), DataIntegrityViolationException::class.java),
            Arguments.of("authorization", SQLInvalidAuthorizationSpecException(), PermissionDeniedDataAccessException::class.java),
            Arguments.of("syntax", SQLSyntaxErrorException(), BadSqlGrammarException::class.java),
            Arguments.of("feature", SQLFeatureNotSupportedException(), InvalidDataAccessApiUsageException::class.java),
            Arguments.of("recoverable", SQLRecoverableException(), RecoverableDataAccessException::class.java),
            Arguments.of("transient family fallback", SQLTransientException(RAW_JDBC_MARKER, "23505"), DuplicateKeyException::class.java),
            Arguments.of("nontransient family fallback", SQLNonTransientException(RAW_JDBC_MARKER, "23505"), DuplicateKeyException::class.java),
            Arguments.of("client info fallback", SQLClientInfoException(RAW_JDBC_MARKER, "23505", 0, null), DuplicateKeyException::class.java),
            Arguments.of("signed vendor code", SQLException(RAW_JDBC_MARKER, "23000", -268), DuplicateKeyException::class.java),
            Arguments.of("generic query timeout", SQLException(RAW_JDBC_MARKER, "57014"), QueryTimeoutException::class.java),
            Arguments.of("unclassified generic failure", SQLException(RAW_JDBC_MARKER), null),
        )
    }
}
