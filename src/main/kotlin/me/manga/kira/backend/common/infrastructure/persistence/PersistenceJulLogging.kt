package me.manga.kira.backend.common.infrastructure.persistence

import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.Logger

/** Strong references keep checked weak JUL registrations alive; this is not a reconfiguration lock. */
internal class PersistenceJulLogging private constructor(private val manager: LogManager, private var retained: Map<String, Logger>) {
    @Synchronized
    fun recheck() {
        if (LogManager.getLogManager() !== manager) rejectPersistenceLogging()
        val observed = inspect(manager)
        if (retained.any { (name, logger) -> observed[name] !== logger }) rejectPersistenceLogging()
        retained = observed
    }

    companion object {
        private val STOCK_LEVEL_NAMES = setOf("OFF", "SEVERE", "WARNING", "INFO", "CONFIG", "FINE", "FINER", "FINEST", "ALL")

        fun capture(): PersistenceJulLogging {
            val manager = LogManager.getLogManager()
            if (manager.javaClass !== LogManager::class.java) rejectPersistenceLogging()
            return PersistenceJulLogging(manager, inspect(manager))
        }

        private fun registered(manager: LogManager): Map<String, Logger> = buildMap {
            val names = manager.loggerNames
            while (names.hasMoreElements()) {
                val name = names.nextElement()
                manager.getLogger(name)?.let { put(name, it) }
            }
        }

        private fun inspect(manager: LogManager): Map<String, Logger> {
            // Foreign loggers are values only: no hashCode/equals/toString or virtual getters yet.
            val before = registered(manager)
            val targets = (PersistenceLoggerNames.postgres + before.keys.filter(PersistenceLoggerNames::isPostgres)).toSet()
            val ancestors = PersistenceLoggerNames.ancestors(targets)
            val configuredAncestors = ancestors.filter { hasConfiguration(manager, it) }
            val additions = (targets + configuredAncestors).filterNot(before::containsKey)
            for ((name, logger) in before) {
                val affected = name in targets || name in ancestors || additions.any { name.startsWith("$it.") }
                // Stock LogManager's root is its private RootLogger, not the public Logger class.
                if (name.isNotEmpty() && affected) requireStock(logger)
            }
            for (name in targets + ancestors) {
                if (manager.getProperty("$name.handlers") != null) rejectPersistenceLogging()
            }
            for (name in additions) {
                manager.getProperty("$name.level")?.let(::requireSupportedPendingLevel)
            }
            val materialized = linkedMapOf<String, Logger>()
            for (name in targets) {
                val logger = before[name] ?: Logger.getLogger(name)
                requireStock(logger)
                if (logger.isLoggable(Level.FINE)) rejectPersistenceLogging()
                materialized[name] = logger
            }
            // Preserve the precreation references even if concurrently removed; a later recheck rejects drift.
            return before + registered(manager) + materialized
        }

        private fun hasConfiguration(manager: LogManager, name: String): Boolean =
            manager.getProperty("$name.level") != null || manager.getProperty("$name.handlers") != null

        private fun requireStock(logger: Logger) {
            if (logger.javaClass !== Logger::class.java) rejectPersistenceLogging()
        }

        private fun requireSupportedPendingLevel(raw: String) {
            val value = raw.trim { it <= ' ' }
            if (value in STOCK_LEVEL_NAMES) return
            val digits = if (value.startsWith('+') || value.startsWith('-')) value.substring(1) else value
            if (digits.isEmpty() || digits.any { it !in '0'..'9' } || value.toIntOrNull() == null) rejectPersistenceLogging()
            // Never call Level.parse/findLevel: unsupported names may initiate resource-bundle lookup.
        }
    }
}
