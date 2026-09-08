package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Proxy
import java.lang.reflect.UndeclaredThrowableException
import java.sql.BatchUpdateException
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLClientInfoException
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.Properties
import javax.sql.CommonDataSource

class SafeJdbcFailureTest {
    private val helper = SafeJdbcFailure.prepare()

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(JdbcFailureKind::class)
    fun `every exact standard category preserves only allowed scalars in a fresh clean envelope`(kind: JdbcFailureKind) {
        val raw = kind.raw()
        val first = helper.sql(raw)
        val second = helper.sql(raw)
        assertEquals(kind.envelopeClass, first.javaClass)
        assertNotSame(raw, first)
        assertNotSame(first, second)
        assertSafeJdbcEnvelope(first, kind.state, kind.code)
        assertSafeJdbcEnvelope(second, kind.state, kind.code)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(JdbcFailureKind::class)
    fun `unknown inherited subclasses retain the standard category but no optional scalars`(kind: JdbcFailureKind) {
        val result = helper.sql(kind.unknownSubclass())
        assertEquals(kind.envelopeClass, result.javaClass)
        assertSafeJdbcEnvelope(result, null, 0)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(JdbcFailureKind::class)
    fun `narrow declarations override category without importing graphs or property metadata`(kind: JdbcFailureKind) {
        val raw = kind.raw()
        val clientInfo = helper.clientInfo(raw)
        val feature = helper.featureNotSupported(raw)
        assertEquals(SQLClientInfoException::class.java, clientInfo.javaClass)
        assertEquals(SQLFeatureNotSupportedException::class.java, feature.javaClass)
        assertSafeJdbcEnvelope(clientInfo, kind.state, kind.code)
        assertSafeJdbcEnvelope(feature, kind.state, kind.code)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["00000", "23505", "08006", "0A000", "JZ0C0", "ZZZZZ", "99999", "57014"])
    fun `exactly five uppercase ASCII alphanumeric state characters are retained`(state: String) {
        assertSafeJdbcEnvelope(helper.sql(SQLException(RAW_JDBC_MARKER, state, -268)), state, -268)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @NullSource
    @ValueSource(strings = ["", "2350", "235050", "23505 ", " 23505", "abcde", "0a000", "23\u000000", "2350\n", "٢٣٥٠٥", "ＡＢＣＤＥ", "23-05", "23😀5"])
    fun `missing or malformed SQLState is omitted without changing a trusted vendor code`(state: String?) {
        assertSafeJdbcEnvelope(helper.sql(SQLException(RAW_JDBC_MARKER, state, -239)), null, -239)
    }

    @Test
    fun `oversized state is rejected rather than truncated or replaced from the raw next exception`() {
        val raw = SQLException(RAW_JDBC_MARKER, "2".repeat(100_000), 1105)
        raw.nextException = SQLException(RAW_JDBC_MARKER, "23505", -239)
        assertSafeJdbcEnvelope(helper.sql(raw), null, 1105)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(ints = [Int.MIN_VALUE, -268, -239, -1, 0, 1, 2399, 500150, Int.MAX_VALUE])
    fun `trusted signed vendor codes remain exact including both extremes`(code: Int) {
        assertSafeJdbcEnvelope(helper.sql(SQLException(RAW_JDBC_MARKER, "23000", code)), "23000", code)
    }

    @Test
    fun `large batch counts are withheld as two empty nonnull arrays`() {
        val raw = BatchUpdateException(RAW_JDBC_MARKER, "23505", -239, longArrayOf(Long.MAX_VALUE, -3, 42), IllegalStateException(RAW_JDBC_MARKER))
        val result = helper.sql(raw)
        assertEquals(BatchUpdateException::class.java, result.javaClass)
        assertSafeJdbcEnvelope(result, "23505", -239)
    }

    @Test
    fun `mutating one emitted graph never contaminates a later envelope`() {
        val raw = SQLException(RAW_JDBC_MARKER, "23505", -239)
        val emitted = helper.sql(raw)
        emitted.nextException = SQLException(RAW_JDBC_MARKER)
        emitted.addSuppressed(IllegalArgumentException(RAW_JDBC_MARKER))
        val later = helper.sql(raw)
        assertNotSame(emitted, later)
        assertSafeJdbcEnvelope(later, "23505", -239)
    }

    @Test
    fun `actual client info proxy declarations expose the checked subtype for both overloads`() {
        val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, _, _ ->
            throw helper.clientInfo(IllegalStateException(RAW_JDBC_MARKER))
        } as Connection
        assertSafeJdbcEnvelope(assertThrows(SQLClientInfoException::class.java) { connection.setClientInfo("synthetic", "value") }, null, 0)
        assertSafeJdbcEnvelope(assertThrows(SQLClientInfoException::class.java) { connection.setClientInfo(Properties()) }, null, 0)
    }

    @Test
    fun `actual parent logger proxy declarations expose the required feature subtype`() {
        val source = Proxy.newProxyInstance(CommonDataSource::class.java.classLoader, arrayOf(CommonDataSource::class.java)) { _, _, _ ->
            throw helper.featureNotSupported(IllegalStateException(RAW_JDBC_MARKER))
        } as CommonDataSource
        val driver = Proxy.newProxyInstance(Driver::class.java.classLoader, arrayOf(Driver::class.java)) { _, _, _ ->
            throw helper.featureNotSupported(IllegalStateException(RAW_JDBC_MARKER))
        } as Driver
        assertSafeJdbcEnvelope(assertThrows(SQLFeatureNotSupportedException::class.java) { source.parentLogger }, null, 0)
        assertSafeJdbcEnvelope(assertThrows(SQLFeatureNotSupportedException::class.java) { driver.parentLogger }, null, 0)
    }

    @Test
    fun `a generic SQLException cannot satisfy a narrow proxy declaration`() {
        val source = Proxy.newProxyInstance(CommonDataSource::class.java.classLoader, arrayOf(CommonDataSource::class.java)) { _, _, _ ->
            throw helper.sql(IllegalStateException(RAW_JDBC_MARKER))
        } as CommonDataSource
        val wrapper = assertThrows(UndeclaredThrowableException::class.java) { source.parentLogger }
        assertEquals(SQLException::class.java, wrapper.undeclaredThrowable.javaClass)
    }
}
