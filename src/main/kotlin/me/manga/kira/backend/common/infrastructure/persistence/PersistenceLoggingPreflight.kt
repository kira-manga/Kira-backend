package me.manga.kira.backend.common.infrastructure.persistence

/** Origin verbosity observation only; nonverbose JDBC/factory redaction and runtime approval are separate. */
internal class PersistenceLoggingPreflight private constructor(private val jul: PersistenceJulLogging, private val logback: PersistenceLogbackLogging) {
    fun recheck() {
        jul.recheck()
        logback.recheck()
    }

    override fun toString(): String = "PersistenceLoggingPreflight"

    companion object {
        fun capture(): PersistenceLoggingPreflight = PersistenceLoggingPreflight(PersistenceJulLogging.capture(), PersistenceLogbackLogging.capture())
    }
}
