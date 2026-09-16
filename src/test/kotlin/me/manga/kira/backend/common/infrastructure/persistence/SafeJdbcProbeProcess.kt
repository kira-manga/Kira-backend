package me.manga.kira.backend.common.infrastructure.persistence

import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Owns exactly one child. Output is discarded at the OS boundary, with no buffer or reader worker. */
internal class SafeJdbcProbeProcess private constructor(private val process: Process) : AutoCloseable {
    val pid: Long = process.pid()
    val isAlive: Boolean get() = process.isAlive
    var exitObserved = false
        private set

    fun awaitVerified(timeoutMillis: Long = 15_000) {
        try {
            process.outputStream.close()
            check(process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) { "JDBC profile probe observation timed out." }
            exitObserved = true
            check(process.exitValue() == JDBC_PROBE_VERIFIED_EXIT) { "JDBC profile probe rejected: exit ${process.exitValue()}." }
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
                // Repeated interruptions cannot restart this one cleanup budget.
                remaining = allowance - (System.nanoTime() - started)
            }
            check(!process.isAlive) { "Owned JDBC profile probe did not terminate within cleanup budget." }
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
        fun start(mode: JdbcFailureProbeMode, driverClasspath: Path? = realDriverClasspath()): SafeJdbcProbeProcess {
            val classpath = listOfNotNull(
                classpathOf(SafeJdbcFailureProfileProbe::class.java),
                classpathOf(SafeJdbcFailure::class.java),
                classpathOf(Unit::class.java),
                driverClasspath,
            ).distinct().joinToString(File.pathSeparator)
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val builder = ProcessBuilder(
                java,
                "-Xms16m",
                "-Xmx64m",
                "-XX:MaxMetaspaceSize=64m",
                "-cp",
                classpath,
                SafeJdbcFailureProfileProbe::class.java.name,
                mode.name,
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            // No ambient credentials, application configuration or JAVA_TOOL_OPTIONS enter the probe.
            builder.environment().clear()
            return SafeJdbcProbeProcess(builder.start())
        }

        fun realDriverClasspath(): Path = classpathOf(Class.forName(POSTGRES_EXCEPTION_NAME, false, SafeJdbcFailure::class.java.classLoader))

        private fun classpathOf(type: Class<*>): Path = Path.of(type.protectionDomain.codeSource.location.toURI())
    }
}
