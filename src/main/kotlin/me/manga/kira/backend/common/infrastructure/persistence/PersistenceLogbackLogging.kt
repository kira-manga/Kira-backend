package me.manga.kira.backend.common.infrastructure.persistence

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory

/** Uses the selected context, never a replacement logging system or an arbitrary provider simulation. */
internal class PersistenceLogbackLogging private constructor(private val context: LoggerContext, private var retained: Map<String, Logger>) {
    @Synchronized
    fun recheck() {
        if (LoggerFactory.getILoggerFactory() !== context) rejectPersistenceLogging()
        val observed = inspect(context)
        if (retained.any { (name, logger) -> observed[name] !== logger }) rejectPersistenceLogging()
        retained = observed
    }

    companion object {
        private val NONVERBOSE_LEVELS = setOf(Level.INFO, Level.WARN, Level.ERROR, Level.OFF)

        fun capture(): PersistenceLogbackLogging {
            val factory = LoggerFactory.getILoggerFactory()
            if (factory.javaClass !== LoggerContext::class.java) rejectPersistenceLogging()
            val context = factory as LoggerContext
            return PersistenceLogbackLogging(context, inspect(context))
        }

        private fun inspect(context: LoggerContext): Map<String, Logger> {
            if (context.turboFilterList.isNotEmpty()) rejectPersistenceLogging()
            val names = PersistenceLoggerNames.slf4j + context.loggerList.map { it.name }.filter(PersistenceLoggerNames::isProtected)
            return names.associateWith { name ->
                val logger = context.getLogger(name)
                if (logger.javaClass !== Logger::class.java || logger.effectiveLevel !in NONVERBOSE_LEVELS) rejectPersistenceLogging()
                logger
            }
        }
    }
}
