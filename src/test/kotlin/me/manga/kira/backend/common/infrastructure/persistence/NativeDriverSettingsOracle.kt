package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.sql.SQLException
import java.util.Locale
import java.util.Properties

/** Public pure parsers only. No Driver, connection, provider, SSL constructor or private reflection. */
internal object NativeDriverSettingsOracle {
    fun auth(raw: String?): NativeDriverParse<Set<String>?> = parse {
        val values = publicClass("org.postgresql.core.AuthMethod")
            .getMethod("parseRequireAuth", String::class.java).invoke(null, raw) as Set<*>?
        values?.map { (it as Enum<*>).name.lowercase(Locale.ROOT).replace('_', '-') }?.toSet()
    }

    fun ssl(properties: Properties): NativeDriverParse<String> = mode("org.postgresql.jdbc.SslMode", properties)

    fun gss(properties: Properties): NativeDriverParse<String> = mode("org.postgresql.jdbc.GSSEncMode", properties)

    fun negotiation(raw: String?): NativeDriverParse<String> = parse {
        val value = publicClass("org.postgresql.jdbc.SslNegotiation").getMethod("of", String::class.java).invoke(null, raw)
        (value as Enum<*>).name
    }

    fun integer(property: NativeIntegerProperty, properties: Properties): NativeDriverParse<Int> = parse {
        val type = publicClass("org.postgresql.PGProperty")
        val constant = type.getField(property.driverConstant).get(null)
        type.getMethod("getInt", Properties::class.java).invoke(constant, properties) as Int
    }

    private fun mode(className: String, properties: Properties): NativeDriverParse<String> = parse {
        val value = publicClass(className).getMethod("of", Properties::class.java).invoke(null, properties)
        (value as Enum<*>).name
    }

    private fun publicClass(name: String): Class<*> = Class.forName(name).also {
        assertEquals("42.7.12", it.`package`.implementationVersion, "The oracle must use the pinned runtime dependency.")
        assertTrue(Modifier.isPublic(it.modifiers))
    }

    private fun <T> parse(action: () -> T): NativeDriverParse<T> = try {
        NativeDriverParse.Parsed(action())
    } catch (failure: InvocationTargetException) {
        // Missing methods, casts and non-SQL failures must fail the fixture, not become parser rejections.
        if (failure.targetException !is SQLException) throw failure
        NativeDriverParse.Invalid
    }
}

internal sealed interface NativeDriverParse<out T> {
    data class Parsed<T>(val value: T) : NativeDriverParse<T>
    data object Invalid : NativeDriverParse<Nothing>
}

internal enum class NativeIntegerProperty(val key: String, val driverConstant: String, val driverDefault: Int) {
    SCRAM("scramMaxIterations", "SCRAM_MAX_ITERATIONS", 100_000),
    CONNECT("connectTimeout", "CONNECT_TIMEOUT", 10),
    SOCKET("socketTimeout", "SOCKET_TIMEOUT", 0),
    CANCEL("cancelSignalTimeout", "CANCEL_SIGNAL_TIMEOUT", 10),
}
