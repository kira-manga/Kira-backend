package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.InterruptedIOException
import java.lang.reflect.InvocationTargetException
import java.net.SocketTimeoutException
import java.sql.BatchUpdateException
import java.sql.ClientInfoStatus
import java.sql.SQLClientInfoException
import java.sql.SQLException
import java.sql.SQLTimeoutException

class SafeJdbcFailureHostileInputTest {
    private val helper = SafeJdbcFailure.prepare()

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(JdbcFailureDeclaration::class)
    fun `hostile optional exception accessors never execute under any declaration`(declaration: JdbcFailureDeclaration) {
        val result = declaration.adapt(helper, HostileTimeout())
        if (declaration == JdbcFailureDeclaration.SQL) assertEquals(SQLTimeoutException::class.java, result.javaClass)
        assertSafeJdbcEnvelope(result, null, 0)
    }

    @Test
    fun `hostile client properties and batch count getters are never inspected`() {
        val clientInfo = helper.sql(HostileClientInfo())
        val batch = helper.sql(HostileBatch())
        assertEquals(SQLClientInfoException::class.java, clientInfo.javaClass)
        assertEquals(BatchUpdateException::class.java, batch.javaClass)
        assertSafeJdbcEnvelope(clientInfo, null, 0)
        assertSafeJdbcEnvelope(batch, null, 0)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(JdbcFailureDeclaration::class)
    fun `every direct Error retains identity without inspection or checked adaptation`(declaration: JdbcFailureDeclaration) {
        val errors = listOf(
            AssertionError(RAW_JDBC_MARKER),
            LinkageError(RAW_JDBC_MARKER),
            object : VirtualMachineError(RAW_JDBC_MARKER) {},
            Class.forName("java.lang.ThreadDeath").getConstructor().newInstance() as Error,
            HostileError(),
        )
        for (failure in errors) {
            assertSame(failure, assertThrows(Error::class.java) { declaration.adapt(helper, failure) })
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(JdbcFailureDeclaration::class)
    fun `direct InterruptedException restores the flag before returning its clean envelope`(declaration: JdbcFailureDeclaration) {
        withJdbcTestInterruptIsolation {
            val result = declaration.adapt(helper, InterruptedException(RAW_JDBC_MARKER))
            assertTrue(Thread.currentThread().isInterrupted)
            assertSafeJdbcEnvelope(result, null, 0)
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(JdbcFailureDeclaration::class)
    fun `an existing interrupt flag is never cleared during adaptation`(declaration: JdbcFailureDeclaration) {
        withJdbcTestInterruptIsolation {
            Thread.currentThread().interrupt()
            val result = declaration.adapt(helper, IllegalStateException(RAW_JDBC_MARKER))
            assertTrue(Thread.currentThread().isInterrupted)
            assertSafeJdbcEnvelope(result, null, 0)
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(JdbcFailureDeclaration::class)
    fun `socket IO and SQL timeout faults do not manufacture thread interruption`(declaration: JdbcFailureDeclaration) {
        withJdbcTestInterruptIsolation {
            for (failure in listOf(SocketTimeoutException(RAW_JDBC_MARKER), InterruptedIOException(RAW_JDBC_MARKER), SQLTimeoutException())) {
                val result = declaration.adapt(helper, failure)
                assertFalse(Thread.currentThread().isInterrupted)
                assertSafeJdbcEnvelope(result, null, 0)
            }
        }
    }

    @Test
    fun `generic unchecked failures retain no invented timeout or retry semantics`() {
        val result = helper.sql(HostileUnchecked())
        assertEquals(SQLException::class.java, result.javaClass)
        assertSafeJdbcEnvelope(result, null, 0)
    }

    @Test
    fun `arbitrary cause and invocation wrapper graphs are not recursively unwrapped`() {
        withJdbcTestInterruptIsolation {
            val wrapper = InvocationTargetException(InterruptedException(RAW_JDBC_MARKER))
            val hiddenError = IllegalStateException(RAW_JDBC_MARKER, HostileError())
            for (failure in listOf(wrapper, hiddenError)) {
                val result = helper.sql(failure)
                assertFalse(Thread.currentThread().isInterrupted)
                assertEquals(SQLException::class.java, result.javaClass)
                assertSafeJdbcEnvelope(result, null, 0)
            }
        }
    }

    private class HostileTimeout : SQLTimeoutException(RAW_JDBC_MARKER, "23505", -239) {
        override val message: String get() = forbiddenAccessor()
        override val cause: Throwable get() = forbiddenAccessor()
        override fun getLocalizedMessage(): String = forbiddenAccessor()
        override fun getSQLState(): String = forbiddenAccessor()
        override fun getErrorCode(): Int = forbiddenAccessor()
        override fun getNextException(): SQLException = forbiddenAccessor()
        override fun getStackTrace(): Array<StackTraceElement> = forbiddenAccessor()
        override fun iterator(): MutableIterator<Throwable> = forbiddenAccessor()
        override fun toString(): String = forbiddenAccessor()
        override fun hashCode(): Int = forbiddenAccessor()
        override fun equals(other: Any?): Boolean = forbiddenAccessor()
    }

    private class HostileClientInfo : SQLClientInfoException() {
        override fun getSQLState(): String = forbiddenAccessor()
        override fun getErrorCode(): Int = forbiddenAccessor()
        override fun getFailedProperties(): MutableMap<String, ClientInfoStatus> = forbiddenAccessor()
    }

    private class HostileBatch : BatchUpdateException() {
        override fun getSQLState(): String = forbiddenAccessor()
        override fun getErrorCode(): Int = forbiddenAccessor()
        override fun getUpdateCounts(): IntArray = forbiddenAccessor()
        override fun getLargeUpdateCounts(): LongArray = forbiddenAccessor()
    }

    private class HostileUnchecked : IllegalStateException() {
        override val message: String get() = forbiddenAccessor()
        override val cause: Throwable get() = forbiddenAccessor()
        override fun toString(): String = forbiddenAccessor()
    }

    private class HostileError : Error() {
        override val message: String get() = forbiddenAccessor()
        override val cause: Throwable get() = forbiddenAccessor()
        override fun getStackTrace(): Array<StackTraceElement> = forbiddenAccessor()
        override fun toString(): String = forbiddenAccessor()
    }

    companion object {
        private fun forbiddenAccessor(): Nothing = throw AssertionError("An unsafe input accessor executed")
    }
}
