package me.manga.kira.backend.common.infrastructure.persistence

import java.io.PrintWriter
import java.io.Writer
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.system.exitProcess

internal const val JDBC_PROBE_VERIFIED_EXIT = 23
internal const val JDBC_PROBE_INITIALIZATION_MARKER = "kira.test.jdbc-diagnostic-initialized"
internal const val POSTGRES_EXCEPTION_NAME = "org.postgresql.util.PSQLException"

enum class JdbcFailureProbeMode {
    WRITER_AT_PREPARE,
    WRITER_AFTER_PREPARE,
    WRITER_INTERRUPT,
    WRITER_FATAL,
    MISSING_DRIVER,
    WRONG_SUPERCLASS,
    OVERRIDDEN_STATE,
    OVERRIDDEN_CODE,
    COLD_PREPARATION,
    LINKAGE_ERROR,
    WAIT_FOR_TERMINATION,
}

/** Only launched in an owned test JVM; no JUnit, service, driver discovery or connection is needed. */
object SafeJdbcFailureProfileProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        when (val mode = JdbcFailureProbeMode.valueOf(args.single())) {
            JdbcFailureProbeMode.WRITER_AT_PREPARE,
            JdbcFailureProbeMode.WRITER_AFTER_PREPARE,
            JdbcFailureProbeMode.WRITER_INTERRUPT,
            JdbcFailureProbeMode.WRITER_FATAL,
            -> verifyWriterRejection(mode)

            JdbcFailureProbeMode.MISSING_DRIVER,
            JdbcFailureProbeMode.WRONG_SUPERCLASS,
            JdbcFailureProbeMode.OVERRIDDEN_STATE,
            JdbcFailureProbeMode.OVERRIDDEN_CODE,
            -> requireBoundaryFailure(PersistenceBoundaryFailureCode.UNSUPPORTED_JDBC_DIAGNOSTIC_CLASS) { SafeJdbcFailure.prepare() }

            JdbcFailureProbeMode.COLD_PREPARATION -> {
                SafeJdbcFailure.prepare()
                check(System.getProperty(JDBC_PROBE_INITIALIZATION_MARKER) == null)
            }

            JdbcFailureProbeMode.LINKAGE_ERROR -> verifyLinkageError()

            JdbcFailureProbeMode.WAIT_FOR_TERMINATION -> Thread.sleep(Long.MAX_VALUE)
        }
        // A normal fall-through/incorrect main class (exit0) is not an accepted probe receipt.
        exitProcess(JDBC_PROBE_VERIFIED_EXIT)
    }

    private fun verifyWriterRejection(mode: JdbcFailureProbeMode) {
        val helper = if (mode == JdbcFailureProbeMode.WRITER_AT_PREPARE) null else SafeJdbcFailure.prepare()
        // Construct the raw SQL fixture BEFORE the writer; otherwise fixture construction itself logs.
        val raw = SQLException("synthetic-driver-failure", "23505", -239)
        val sink = CountingWriter()
        val writer = PrintWriter(sink)
        DriverManager.setLogWriter(writer)
        if (helper == null) {
            requireBoundaryFailure(PersistenceBoundaryFailureCode.JDBC_LOG_WRITER_INSTALLED) { SafeJdbcFailure.prepare() }
        } else {
            val conversions: List<(Throwable) -> SQLException> = listOf(helper::sql, helper::clientInfo, helper::featureNotSupported)
            for (convert in conversions) verifyConversion(mode, raw, convert)
        }
        check(DriverManager.getLogWriter() === writer)
        check(sink.writes == 0 && sink.flushes == 0 && sink.closes == 0)
    }

    private fun verifyConversion(mode: JdbcFailureProbeMode, raw: SQLException, convert: (Throwable) -> SQLException) {
        when (mode) {
            JdbcFailureProbeMode.WRITER_FATAL -> {
                val fatal = object : Error() {
                    override val message: String get() = error("Fatal accessor must not execute")
                    override fun toString(): String = error("Fatal rendering must not execute")
                }
                try {
                    convert(fatal)
                    error("Fatal signal did not propagate")
                } catch (observed: Error) {
                    check(observed === fatal)
                }
            }

            JdbcFailureProbeMode.WRITER_INTERRUPT -> {
                Thread.interrupted()
                requireBoundaryFailure(PersistenceBoundaryFailureCode.JDBC_LOG_WRITER_INSTALLED) {
                    convert(InterruptedException("synthetic-interruption"))
                }
                check(Thread.currentThread().isInterrupted)
            }

            else -> requireBoundaryFailure(PersistenceBoundaryFailureCode.JDBC_LOG_WRITER_INSTALLED) { convert(raw) }
        }
    }

    private fun verifyLinkageError() {
        try {
            SafeJdbcFailure.prepare()
            error("Expected missing-superclass linkage failure")
        } catch (observed: NoClassDefFoundError) {
            check(observed.cause is ClassNotFoundException)
        }
    }

    private fun requireBoundaryFailure(code: PersistenceBoundaryFailureCode, action: () -> Unit) {
        try {
            action()
            error("Expected fixed non-SQL profile rejection")
        } catch (failure: PersistenceBoundaryException) {
            check(failure.code == code)
            check(failure.message == "Persistence boundary rejected: ${code.name}.")
            check(failure.cause == null && failure.suppressed.isEmpty())
        }
    }

    private class CountingWriter : Writer() {
        var writes = 0
        var flushes = 0
        var closes = 0

        override fun write(buffer: CharArray, offset: Int, count: Int) {
            writes++
        }

        override fun flush() {
            flushes++
        }

        override fun close() {
            closes++
        }
    }
}
