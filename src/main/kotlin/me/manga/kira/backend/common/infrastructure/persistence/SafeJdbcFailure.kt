package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.BatchUpdateException
import java.sql.DataTruncation
import java.sql.DriverManager
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

/**
 * Failure envelopes for protected JDBC boundaries, not a general business-exception translator.
 * The owner must record sticky poison BEFORE calling this helper, even if adaptation itself fails.
 * No envelope grants rollback, cleanup, reuse or runtime-profile authority.
 */
internal class SafeJdbcFailure private constructor(private val postgresExceptionClass: Class<*>) {
    fun sql(failure: Throwable): SQLException {
        val (state, code) = checkedScalars(failure)
        return when (failure) {
            is SQLTransientConnectionException -> SQLTransientConnectionException(REASON, state, code, null)

            is SQLTransactionRollbackException -> SQLTransactionRollbackException(REASON, state, code, null)

            is SQLTimeoutException -> SQLTimeoutException(REASON, state, code, null)

            is SQLTransientException -> SQLTransientException(REASON, state, code, null)

            is SQLNonTransientConnectionException -> SQLNonTransientConnectionException(REASON, state, code, null)

            is SQLDataException -> SQLDataException(REASON, state, code, null)

            is SQLIntegrityConstraintViolationException -> SQLIntegrityConstraintViolationException(REASON, state, code, null)

            is SQLInvalidAuthorizationSpecException -> SQLInvalidAuthorizationSpecException(REASON, state, code, null)

            is SQLSyntaxErrorException -> SQLSyntaxErrorException(REASON, state, code, null)

            is SQLFeatureNotSupportedException -> SQLFeatureNotSupportedException(REASON, state, code, null)

            is SQLNonTransientException -> SQLNonTransientException(REASON, state, code, null)

            is SQLRecoverableException -> SQLRecoverableException(REASON, state, code, null)

            is SQLClientInfoException -> SQLClientInfoException(REASON, state, code, null, null)

            // Empty means withheld counts, not evidence that no statement committed.
            is BatchUpdateException -> BatchUpdateException(REASON, state, code, intArrayOf(), null)

            else -> SQLException(REASON, state, code, null)
        }
    }

    /** Only for a method whose checked declaration is SQLClientInfoException. */
    fun clientInfo(failure: Throwable): SQLClientInfoException {
        val (state, code) = checkedScalars(failure)
        return SQLClientInfoException(REASON, state, code, null, null)
    }

    /** Only for a method whose checked declaration is SQLFeatureNotSupportedException. */
    fun featureNotSupported(failure: Throwable): SQLFeatureNotSupportedException {
        val (state, code) = checkedScalars(failure)
        return SQLFeatureNotSupportedException(REASON, state, code, null)
    }

    private fun checkedScalars(failure: Throwable): Scalars {
        if (failure is Error) throw failure
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        requireNoJdbcLogWriter()
        if (failure !is SQLException) return Scalars(null, 0)
        val type = failure.javaClass
        if (type !== postgresExceptionClass && type !in JDK_SCALAR_TYPES) return Scalars(null, 0)
        // Exact class trust precedes virtual getter dispatch. Never probe unknown subclass accessors.
        val state = failure.sqlState?.takeIf { value ->
            value.length == 5 && value.all { it in '0'..'9' || it in 'A'..'Z' }
        }
        return Scalars(state, failure.errorCode)
    }

    private data class Scalars(val state: String?, val code: Int)

    companion object {
        private const val REASON = "Persistence operation failed."
        private val JDK_SCALAR_TYPES = setOf(
            SQLException::class.java,
            SQLTransientException::class.java,
            SQLTransientConnectionException::class.java,
            SQLTransactionRollbackException::class.java,
            SQLTimeoutException::class.java,
            SQLNonTransientException::class.java,
            SQLNonTransientConnectionException::class.java,
            SQLDataException::class.java,
            SQLIntegrityConstraintViolationException::class.java,
            SQLInvalidAuthorizationSpecException::class.java,
            SQLSyntaxErrorException::class.java,
            SQLFeatureNotSupportedException::class.java,
            SQLRecoverableException::class.java,
            SQLClientInfoException::class.java,
            BatchUpdateException::class.java,
            SQLWarning::class.java,
            DataTruncation::class.java,
        )

        /**
         * Cold preparation BEFORE ownership. Loading/linking can read classpath bytes and take loader
         * locks even with initialization disabled; this is not bounded failing-hot-path discovery.
         * Structural checks do not attest a driver artifact or the controlled no-mutation JVM profile.
         */
        fun prepare(): SafeJdbcFailure {
            requireNoJdbcLogWriter()
            return try {
                val type = Class.forName("org.postgresql.util.PSQLException", false, SafeJdbcFailure::class.java.classLoader)
                if (
                    type.superclass !== SQLException::class.java ||
                    type.getMethod("getSQLState").declaringClass !== SQLException::class.java ||
                    type.getMethod("getErrorCode").declaringClass !== SQLException::class.java
                ) {
                    rejectPersistenceBoundary(PersistenceBoundaryFailureCode.UNSUPPORTED_JDBC_DIAGNOSTIC_CLASS)
                }
                SafeJdbcFailure(type)
            } catch (_: ReflectiveOperationException) {
                rejectPersistenceBoundary(PersistenceBoundaryFailureCode.UNSUPPORTED_JDBC_DIAGNOSTIC_CLASS)
            } catch (_: SecurityException) {
                rejectPersistenceBoundary(PersistenceBoundaryFailureCode.UNSUPPORTED_JDBC_DIAGNOSTIC_CLASS)
            }
        }

        private fun requireNoJdbcLogWriter() {
            // SQL exception construction can invoke this global writer. Never clear or replace it.
            // A null observation still requires the controlled immutable-null-writer launch profile.
            if (DriverManager.getLogWriter() != null) {
                rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_LOG_WRITER_INSTALLED)
            }
        }
    }
}
