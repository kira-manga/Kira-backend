package me.manga.kira.backend.common.infrastructure.persistence

import org.apache.commons.logging.LogFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.jdbc.DatabaseDriver
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.util.StringUtils
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Separate owned fixture: never mutates the accepted SafeJdbcFailure probe or its launch profile. */
internal class EndpointProbeProcess private constructor(private val process: Process) : AutoCloseable {
    val pid: Long = process.pid()
    val isAlive: Boolean get() = process.isAlive
    var exitObserved = false
        private set

    fun awaitVerified(timeoutMillis: Long = 15_000) {
        try {
            process.outputStream.close()
            check(process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) { "Endpoint probe observation timed out." }
            exitObserved = true
            check(process.exitValue() == ENDPOINT_PROBE_VERIFIED_EXIT) { "Endpoint probe rejected: exit ${process.exitValue()}." }
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        }
    }

    override fun close() {
        var interrupted = Thread.interrupted()
        val started = System.nanoTime()
        val allowance = TimeUnit.SECONDS.toNanos(5)
        try {
            if (process.isAlive) process.destroyForcibly()
            var remaining = allowance
            while (process.isAlive && remaining > 0) {
                try {
                    process.waitFor(remaining, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
                remaining = allowance - (System.nanoTime() - started)
            }
            check(!process.isAlive) { "Owned endpoint probe did not terminate within cleanup budget." }
            process.exitValue()
            exitObserved = true
            process.outputStream.close()
            process.inputStream.close()
            process.errorStream.close()
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    companion object {
        fun start(mode: EndpointProbeMode, providerRoot: Path? = null): EndpointProbeProcess {
            val types = listOf(
                EndpointResolutionProbe::class.java, PersistenceEndpointResolver::class.java, Unit::class.java,
                DataSourceProperties::class.java, DatabaseDriver::class.java, InitializingBean::class.java,
                StringUtils::class.java, LogFactory::class.java, JdbcTemplate::class.java, DataAccessException::class.java,
                Class.forName("org.postgresql.Driver", false, PersistenceEndpointResolver::class.java.classLoader),
            )
            val classpath = (types.map { Path.of(it.protectionDomain.codeSource.location.toURI()) } + listOfNotNull(providerRoot))
                .distinct().joinToString(File.pathSeparator)
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val encoding = when (mode) {
                EndpointProbeMode.DRIVER_UTF8 -> "UTF-8"
                EndpointProbeMode.DRIVER_LATIN1 -> "ISO-8859-1"
                EndpointProbeMode.STOCK_PROVIDER_SENTINEL -> ENDPOINT_SENTINEL_CHARSET
                else -> null
            }
            val command = listOf(java, "-Xms16m", "-Xmx64m", "-XX:MaxMetaspaceSize=64m") +
                listOfNotNull(encoding?.let { "-Dpostgresql.url.encoding=$it" }) +
                listOf("-cp", classpath, EndpointResolutionProbe::class.java.name, mode.name)
            val builder = ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            // No host credentials, launch agents, JVM options or application configuration are inherited.
            builder.environment().clear()
            return EndpointProbeProcess(builder.start())
        }
    }
}
