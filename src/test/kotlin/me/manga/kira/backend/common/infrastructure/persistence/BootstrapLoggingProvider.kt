package me.manga.kira.backend.common.infrastructure.persistence

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.helpers.BasicMDCAdapter
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.NOPLoggerFactory
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider

internal object BootstrapProviderSignals {
    const val FOREIGN_RENDERING = "BootstrapForeignProvider"
    var loggerQueries = 0
    var renderQueries = 0
    var unfinishedRejections = 0
}

/** Test-only provider selected by an owned child's explicit SLF4J option, never a main service resource. */
class BootstrapLoggingProvider : SLF4JServiceProvider {
    private lateinit var factory: ILoggerFactory
    private val markers = BasicMarkerFactory()
    private val mdc = BasicMDCAdapter()

    override fun initialize() {
        val mode = BootstrapProbeCase.valueOf(System.getProperty("kira.synthetic.bootstrap.provider"))
        factory = when (mode) {
            BootstrapProbeCase.UNKNOWN_FACTORY -> ForeignBootstrapFactory()

            BootstrapProbeCase.NOP_FACTORY -> NOPLoggerFactory()

            BootstrapProbeCase.UNKNOWN_CONTEXT -> ForeignBootstrapContext().apply { armed = true }

            else -> LoggerContext().apply {
                getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).level = Level.INFO
                mdcAdapter = mdc
            }
        }
        if (mode == BootstrapProbeCase.UNFINISHED_FACTORY) {
            expectBootstrapFailure(PersistenceBoundaryFailureCode.UNSUPPORTED_PERSISTENCE_LOGGING) { PersistenceDriverBootstrap.prepare() }
            BootstrapProviderSignals.unfinishedRejections++
        }
    }

    override fun getLoggerFactory(): ILoggerFactory = factory
    override fun getMarkerFactory(): IMarkerFactory = markers
    override fun getMDCAdapter(): MDCAdapter = mdc
    override fun getRequestedApiVersion(): String = "2.0.99"
}

private class ForeignBootstrapFactory : ILoggerFactory {
    override fun getLogger(name: String): org.slf4j.Logger {
        BootstrapProviderSignals.loggerQueries++
        throw AssertionError("Foreign factory query")
    }

    override fun toString(): String {
        BootstrapProviderSignals.renderQueries++
        return BootstrapProviderSignals.FOREIGN_RENDERING
    }
}

private class ForeignBootstrapContext : LoggerContext() {
    var armed = false

    override fun getLogger(name: String): Logger {
        if (armed) {
            BootstrapProviderSignals.loggerQueries++
            throw AssertionError("Foreign context query")
        }
        return super.getLogger(name)
    }

    override fun toString(): String {
        BootstrapProviderSignals.renderQueries++
        return BootstrapProviderSignals.FOREIGN_RENDERING
    }
}
