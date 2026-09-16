package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import java.net.URLClassLoader
import java.nio.file.Path
import java.sql.SQLException

class SafeJdbcFailureRuntimeProfileTest {
    @Test
    fun `real runtime only PostgreSQL exception retains safe state but not its driver type or raw detail`() {
        val raw = postgresFailure(SafeJdbcFailure::class.java.classLoader)
        assertEquals("42.7.12", raw.javaClass.`package`.implementationVersion)
        assertEquals("23505", raw.sqlState)
        val result = SafeJdbcFailure.prepare().sql(raw)
        assertEquals(SQLException::class.java, result.javaClass)
        assertNotSame(raw, result)
        assertSafeJdbcEnvelope(result, "23505", 0)
        assertEquals(
            DuplicateKeyException::class.java,
            SQLExceptionSubclassTranslator().translate("synthetic-jdbc-task", null, result)?.javaClass,
        )
    }

    @Test
    fun `real server error detail is absent from the emitted standard exception`() {
        val loader = SafeJdbcFailure::class.java.classLoader
        val serverType = Class.forName("org.postgresql.util.ServerErrorMessage", true, loader)
        val server = serverType.getConstructor(String::class.java)
            .newInstance("SERROR\u0000C23505\u0000M$RAW_JDBC_MARKER\u0000D$RAW_JDBC_MARKER\u0000\u0000")
        val exceptionType = Class.forName(POSTGRES_EXCEPTION_NAME, true, loader)
        val raw = exceptionType.getConstructor(serverType).newInstance(server) as SQLException
        assertTrue(raw.message.orEmpty().contains(RAW_JDBC_MARKER))
        assertSafeJdbcEnvelope(SafeJdbcFailure.prepare().sql(raw), "23505", 0)
    }

    @Test
    fun `an identically named PG class from a different loader is not scalar trusted`() {
        val helper = SafeJdbcFailure.prepare()
        val realType = Class.forName(POSTGRES_EXCEPTION_NAME, false, SafeJdbcFailure::class.java.classLoader)
        val jar = SafeJdbcProbeProcess.realDriverClasspath().toUri().toURL()
        URLClassLoader(arrayOf(jar), ClassLoader.getPlatformClassLoader()).use { foreignLoader ->
            val foreign = postgresFailure(foreignLoader)
            assertEquals(realType.name, foreign.javaClass.name)
            assertNotSame(realType, foreign.javaClass)
            assertEquals("23505", foreign.sqlState)
            assertSafeJdbcEnvelope(helper.sql(foreign), null, 0)
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(
        value = JdbcFailureProbeMode::class,
        names = ["WRITER_AT_PREPARE", "WRITER_AFTER_PREPARE", "WRITER_INTERRUPT", "WRITER_FATAL", "MISSING_DRIVER"],
    )
    fun `owned child verifies writer rejection fatal ordering interruption or actual missing driver`(mode: JdbcFailureProbeMode) {
        val driver = if (mode == JdbcFailureProbeMode.MISSING_DRIVER) null else SafeJdbcProbeProcess.realDriverClasspath()
        assertProbe(mode, driver)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(
        value = JdbcFailureProbeMode::class,
        names = ["WRONG_SUPERCLASS", "OVERRIDDEN_STATE", "OVERRIDDEN_CODE", "COLD_PREPARATION", "LINKAGE_ERROR"],
    )
    fun `owned child verifies diagnostic class shape cold initialization or fatal linkage`(mode: JdbcFailureProbeMode, @TempDir root: Path) {
        assertProbe(mode, JdbcDiagnosticClassFixture.compile(root, mode))
    }

    private fun assertProbe(mode: JdbcFailureProbeMode, driver: Path?) {
        val child = SafeJdbcProbeProcess.start(mode, driver)
        child.use { it.awaitVerified() }
        assertTrue(child.exitObserved)
        assertFalse(child.isAlive)
        println("JDBC_PROFILE_PROBE mode=$mode pid=${child.pid} verified_exit=$JDBC_PROBE_VERIFIED_EXIT exit_observed=true alive=false")
    }

    private fun postgresFailure(loader: ClassLoader): SQLException {
        val type = Class.forName(POSTGRES_EXCEPTION_NAME, true, loader)
        val stateType = Class.forName("org.postgresql.util.PSQLState", true, loader)
        val state = stateType.getField("UNIQUE_VIOLATION").get(null)
        return type.getConstructor(String::class.java, stateType).newInstance(RAW_JDBC_MARKER, state) as SQLException
    }
}
