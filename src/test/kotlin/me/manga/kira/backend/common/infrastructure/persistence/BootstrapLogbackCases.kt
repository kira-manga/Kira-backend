package me.manga.kira.backend.common.infrastructure.persistence

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.read.ListAppender
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import org.slf4j.helpers.NOPLoggerFactory
import java.nio.file.Path

internal object BootstrapLogbackCases {
    fun verify(mode: BootstrapProbeCase, root: Path) {
        BootstrapDriverLoader(root, mode).use { loader ->
            when (mode) {
                BootstrapProbeCase.LOGBACK_LEVEL_MATRIX -> levelMatrix(loader)

                BootstrapProbeCase.LOGBACK_LATE_CHILD -> lateChild(loader)

                BootstrapProbeCase.LOGBACK_FILTER -> filter(loader)

                BootstrapProbeCase.UNKNOWN_FACTORY, BootstrapProbeCase.UNKNOWN_CONTEXT -> unknownFactory(loader)

                BootstrapProbeCase.NOP_FACTORY -> {
                    expectLoggingFailure { PersistenceDriverBootstrap.prepareWithLoader(loader) }
                    val factory = LoggerFactory.getILoggerFactory()
                    check(factory.javaClass === NOPLoggerFactory::class.java)
                    check(!factory.getLogger("synthetic-positive").isInfoEnabled)
                    requireSyntheticDriverCold()
                }

                BootstrapProbeCase.UNFINISHED_FACTORY -> unfinishedFactory(loader)

                BootstrapProbeCase.LOGBACK_DRIFT -> drift(loader)

                else -> error("Unimplemented Logback probe")
            }
        }
    }

    private fun levelMatrix(loader: BootstrapDriverLoader) {
        val context = context()
        var rejected = 0
        var allowed = 0
        val all = Level.toLevel(Level.ALL_INT)
        check(all.toInt() == Level.ALL_INT && all.toString() == "ALL")
        for (name in PersistenceLoggerNames.slf4j) {
            val logger = context.getLogger(name)
            val prior = logger.level
            for (level in listOf(Level.DEBUG, Level.TRACE, all)) {
                logger.level = level
                expectLoggingFailure { PersistenceDriverBootstrap.prepareWithLoader(loader) }
                check(logger.level === level)
                rejected++
            }
            for (level in listOf(Level.INFO, Level.WARN, Level.ERROR, Level.OFF)) {
                logger.level = level
                PersistenceDriverBootstrap.prepareWithLoader(loader)
                check(logger.level === level)
                allowed++
            }
            logger.level = prior
        }
        requireSyntheticDriverCold()
        check(rejected == 126 && allowed == 168)
        println("BOOTSTRAP_LOGBACK_MATRIX emitters_and_roots=42 verbose_rejections=126 nonverbose_acceptances=168")
    }

    private fun lateChild(loader: BootstrapDriverLoader) {
        val context = context()
        val parent = context.getLogger("com.zaxxer.hikari").apply { level = Level.INFO }
        val child = context.getLogger("com.zaxxer.hikari.bootstrap.dynamic").apply { level = Level.DEBUG }
        expectLoggingFailure { PersistenceDriverBootstrap.prepareWithLoader(loader) }
        check(parent.level === Level.INFO && child.level === Level.DEBUG)
        child.level = Level.INFO
        val lookalikes = listOf("com.zaxxer.hikariExtra.dynamic", "org.postgresqlExtra.dynamic").map {
            context.getLogger(it).apply { level = Level.TRACE }
        }
        PersistenceDriverBootstrap.prepareWithLoader(loader)
        check(lookalikes.all { it.level === Level.TRACE })
        requireSyntheticDriverCold()
    }

    private fun filter(loader: BootstrapDriverLoader) {
        val context = context()
        val logger = context.getLogger("com.zaxxer.hikari.pool.PoolBase").apply { level = Level.INFO }
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        val filter = BootstrapMessageFilter().apply { start() }
        context.addTurboFilter(filter)
        expectLoggingFailure { PersistenceDriverBootstrap.prepareWithLoader(loader) }
        check(filter.calls == 0)
        requireSyntheticDriverCold()
        // Real Logback positive: the no-message query is false, while message-sensitive ACCEPT emits DEBUG.
        check(!logger.isDebugEnabled)
        logger.debug("synthetic-message")
        check(filter.calls == 2 && appender.list.single().formattedMessage == "synthetic-message")
        println("BOOTSTRAP_FILTER guard_invocations=0 isDebugEnabled=false message_sensitive_debug_emitted=true")
    }

    private fun unknownFactory(loader: BootstrapDriverLoader) {
        expectLoggingFailure { PersistenceDriverBootstrap.prepareWithLoader(loader) }
        check(BootstrapProviderSignals.loggerQueries == 0 && BootstrapProviderSignals.renderQueries == 0)
        requireSyntheticDriverCold()
        val factory = LoggerFactory.getILoggerFactory()
        check(runCatching { factory.getLogger("synthetic-positive") }.exceptionOrNull() is AssertionError)
        check(BootstrapProviderSignals.loggerQueries == 1 && BootstrapProviderSignals.renderQueries == 0)
        check(factory.toString() == BootstrapProviderSignals.FOREIGN_RENDERING)
        check(BootstrapProviderSignals.loggerQueries == 1 && BootstrapProviderSignals.renderQueries == 1)
    }

    private fun unfinishedFactory(loader: BootstrapDriverLoader) {
        val handle = PersistenceDriverBootstrap.prepareWithLoader(loader)
        check(BootstrapProviderSignals.unfinishedRejections == 1)
        check(BootstrapProviderSignals.loggerQueries == 0 && BootstrapProviderSignals.renderQueries == 0)
        requireSyntheticDriverCold()
        handle.construct()
        check(System.getProperty(BOOTSTRAP_DRIVER_MARKER) == "initialized")
        println("BOOTSTRAP_UNFINISHED inner_rejected=1 final_context_accepted=true runtime_authority=UNKNOWN")
    }

    private fun drift(loader: BootstrapDriverLoader) {
        val context = context()
        val handle = PersistenceDriverBootstrap.prepareWithLoader(loader)
        val child = context.getLogger("org.postgresql.bootstrap.later").apply { level = Level.DEBUG }
        expectLoggingFailure { handle.construct() }
        requireSyntheticDriverCold()
        child.level = Level.INFO
        val filter = BootstrapMessageFilter().apply { start() }
        context.addTurboFilter(filter)
        expectLoggingFailure { handle.construct() }
        check(filter.calls == 0)
        requireSyntheticDriverCold()
    }

    private fun context(): LoggerContext = LoggerFactory.getILoggerFactory() as LoggerContext

    private fun expectLoggingFailure(action: () -> Unit) {
        expectBootstrapFailure(PersistenceBoundaryFailureCode.UNSUPPORTED_PERSISTENCE_LOGGING, action)
    }
}

private class BootstrapMessageFilter : TurboFilter() {
    var calls = 0

    override fun decide(marker: Marker?, logger: Logger?, level: Level?, format: String?, params: Array<out Any?>?, t: Throwable?): FilterReply {
        calls++
        return if (format == "synthetic-message") FilterReply.ACCEPT else FilterReply.NEUTRAL
    }
}
