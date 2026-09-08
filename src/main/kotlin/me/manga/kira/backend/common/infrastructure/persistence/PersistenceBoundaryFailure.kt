package me.manga.kira.backend.common.infrastructure.persistence

internal enum class PersistenceBoundaryFailureCode {
    INVALID_TIME_BUDGET,
    TIME_BUDGET_EXHAUSTED,
    INVALID_POOL_CAPACITY,
    JDBC_LOG_WRITER_INSTALLED,
    UNSUPPORTED_JDBC_DIAGNOSTIC_CLASS,
    AMBIGUOUS_JDBC_ENDPOINT,
    UNSUPPORTED_JDBC_DRIVER,
    INVALID_JDBC_PROPERTIES,
    INVALID_JDBC_URL,
    JDBC_CONFIGURATION_CONFLICT,
    MISSING_JDBC_CREDENTIALS,
    UNSUPPORTED_JDBC_SERVICE,
    INVALID_LOGIN_POLICY,
    INVALID_JDBC_ENCODING,
    JDBC_CONFIGURATION_FAILED,
    HIDDEN_HIKARI_CONFIGURATION,
    JDBC_DRIVER_DEFAULT_RESOURCE,
    UNSUPPORTED_PERSISTENCE_LOGGING,
    JDBC_BOOTSTRAP_FAILED,
}

/** New persistence boundaries never attach a supplied value or a driver/callback exception. */
internal class PersistenceBoundaryException(val code: PersistenceBoundaryFailureCode) : RuntimeException("Persistence boundary rejected: ${code.name}.")

internal fun rejectPersistenceBoundary(code: PersistenceBoundaryFailureCode): Nothing = throw PersistenceBoundaryException(code)
