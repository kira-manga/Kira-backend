package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.DriverManager

/** Cold configuration boundary only. No observation here certifies an immutable runtime profile. */
internal inline fun <T> persistenceBootstrapBoundary(action: () -> T): T = runCatching(action).getOrElse(::rejectPersistenceBootstrapFailure)

internal fun rejectPersistenceBootstrapFailure(failure: Throwable): Nothing {
    if (failure is Error) throw failure
    if (failure is InterruptedException) Thread.currentThread().interrupt()
    val code = if (failure is PersistenceBoundaryException) failure.code else PersistenceBoundaryFailureCode.JDBC_BOOTSTRAP_FAILED
    // Even a callback-supplied boundary exception may carry a cause/suppressed graph: always replace it.
    rejectPersistenceBoundary(code)
}

internal fun requirePersistenceBootstrapGlobals() {
    if (DriverManager.getLogWriter() != null) rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_LOG_WRITER_INSTALLED)
    // Presence only, including empty. Never stat, open, normalize or render a referenced config path.
    if (System.getProperty("hikaricp.configurationFile") != null) {
        rejectPersistenceBoundary(PersistenceBoundaryFailureCode.HIDDEN_HIKARI_CONFIGURATION)
    }
}

internal fun requireNoPersistenceDriverDefaults(definingLoader: ClassLoader?) {
    val loader = definingLoader ?: ClassLoader.getSystemClassLoader()
    if (loader.getResources("org/postgresql/driverconfig.properties").hasMoreElements()) {
        rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_DRIVER_DEFAULT_RESOURCE)
    }
}

internal fun rejectPersistenceLogging(): Nothing = rejectPersistenceBoundary(PersistenceBoundaryFailureCode.UNSUPPORTED_PERSISTENCE_LOGGING)
