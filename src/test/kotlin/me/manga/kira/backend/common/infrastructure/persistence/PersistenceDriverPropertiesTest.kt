package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.Collections
import java.util.Enumeration
import java.util.Properties
import java.util.stream.Stream

class PersistenceDriverPropertiesTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("guardedKeys")
    fun `every pinned guarded query key rejects repeated assignments even when equal`(name: String) {
        assertEndpointFailure(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_CONFLICT) {
            resolveEndpoint("jdbc:postgresql://?$name=same&$name=same")
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("guardedKeys")
    fun `every pinned guarded key rejects a disagreeing URL and property tier`(name: String) {
        assertEndpointFailure(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_CONFLICT) {
            resolveEndpoint("jdbc:postgresql://?$name=first", endpointProperties(name to "second"), username = null, password = null)
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("guardedKeys")
    fun `every pinned guarded key accepts exactly equal explicit copies without profile tightening`(name: String) {
        val value = when (name) {
            "PGHOST" -> "localhost"
            "PGPORT" -> "5432"
            "PGDBNAME" -> "same-db"
            "user" -> ENDPOINT_TEST_USER
            "password" -> ENDPOINT_TEST_PASSWORD
            "loginTimeout" -> "1.25"
            else -> "synthetic-setting"
        }
        val properties = endpointProperties("user" to ENDPOINT_TEST_USER, "password" to ENDPOINT_TEST_PASSWORD, name to value)
        val endpoint = resolveEndpoint("jdbc:postgresql://?$name=$value", properties, username = null, password = null)
        assertEquals(if (name == "loginTimeout") "0" else value, endpoint.driverProperties().getProperty(name))
        assertEquals(if (name == "loginTimeout") 1250L else 30_000L, endpoint.loginPolicy.durationMillis)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["host=a&PGHOST=a", "PORT=5432&PGPORT=5432", "DbNaMe=x&PGDBNAME=x", "user&user=", "sslmode&sslmode="])
    fun `only the documented alias and bare canonical keys participate in query duplicate checks`(query: String) {
        assertEndpointFailure(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_CONFLICT) { resolveEndpoint("jdbc:postgresql://?$query") }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["username", "databaseName", "pgHOST", "PGhost", "p%61ssword", "connecttimeout", "SERVICE", "ApplicationName"])
    fun `unknown lookalikes and non guarded keys retain exact last value and URL precedence`(name: String) {
        assertFalse(PersistenceDriverProperties.isGuarded(name))
        val endpoint = resolveEndpoint("jdbc:postgresql://?$name=first&$name=last", endpointProperties(name to "property"))
        assertEquals("last", endpoint.driverProperties().getProperty(name))
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["service", "service=", "service=synthetic", "service=first&service=second"])
    fun `exact service mechanisms are unsupported including bare and empty values`(query: String) {
        assertEndpointFailure(PersistenceBoundaryFailureCode.UNSUPPORTED_JDBC_SERVICE) { resolveEndpoint("jdbc:postgresql://?$query") }
    }

    @Test
    fun `service properties reject even when empty or inherited rather than reading any service path`() {
        for (value in listOf("", "synthetic-service")) {
            val properties = endpointProperties("service" to value)
            assertEndpointFailure(PersistenceBoundaryFailureCode.UNSUPPORTED_JDBC_SERVICE) { resolveEndpoint(properties = properties) }
            assertEndpointFailure(PersistenceBoundaryFailureCode.UNSUPPORTED_JDBC_SERVICE) { resolveEndpoint(properties = Properties(properties)) }
        }
    }

    @Test
    fun `input defaults and every output are defensive copies without mutable shared defaults`() {
        val inherited = endpointProperties("password" to ENDPOINT_TEST_PASSWORD, "currentSchema" to "inherited", "ApplicationName" to "grandparent")
        val defaults = Properties(inherited).apply { setProperty("ApplicationName", "parent") }
        val properties = Properties(defaults).apply { setProperty("user", ENDPOINT_TEST_USER) }
        val endpoint = resolveEndpoint(properties = properties, username = null, password = null)
        properties.clear()
        defaults.setProperty("ApplicationName", "changed")
        inherited.setProperty("password", "changed")
        inherited.setProperty("currentSchema", "changed")
        val first = endpoint.driverProperties()
        val second = endpoint.driverProperties()
        assertNotSame(first, second)
        assertEquals("parent", second.getProperty("ApplicationName"))
        assertEquals("inherited", second.getProperty("currentSchema"))
        assertEquals(ENDPOINT_TEST_PASSWORD, second.getProperty("password"))
        assertEquals(first.size, first.stringPropertyNames().size)
        first.clear()
        first.setProperty("password", "driver-mutation")
        assertEquals(second, endpoint.driverProperties())
        assertTrue(endpoint.credentialsMatch(ENDPOINT_TEST_USER, ENDPOINT_TEST_PASSWORD))
        assertFalse(endpoint.credentialsMatch(ENDPOINT_TEST_USER, "changed"))
        assertFalse(endpoint.credentialsMatch(null, ENDPOINT_TEST_PASSWORD))
        assertFalse(endpoint.credentialsMatch(ENDPOINT_TEST_USER, null))
    }

    @Test
    fun `non String direct and inherited names and values reject without rendering their objects`() {
        val hostile = object {
            override fun toString(): String = error("Must not render a configuration value")
        }
        val invalidKey = Properties().apply { put(hostile, "synthetic-value") }
        val invalidValue = Properties().apply { put("synthetic-key", hostile) }
        for (properties in listOf(invalidKey, invalidValue, Properties(invalidKey), Properties(invalidValue))) {
            assertEndpointFailure(PersistenceBoundaryFailureCode.INVALID_JDBC_PROPERTIES) { resolveEndpoint(properties = properties) }
        }
        // An explicit String can replace an invalid inherited value; it is the effective value.
        val explicit = Properties(invalidValue).apply { setProperty("synthetic-key", "explicit-string") }
        assertEquals("explicit-string", resolveEndpoint(properties = explicit).driverProperties().getProperty("synthetic-key"))
    }

    @Test
    fun `capture checks enumeration types and does not silently skip a non String value`() {
        val wrongName = object : Properties() {
            override fun propertyNames(): Enumeration<*> = Collections.enumeration(listOf(1))
        }
        assertEndpointFailure(PersistenceBoundaryFailureCode.INVALID_JDBC_PROPERTIES) { resolveEndpoint(properties = wrongName) }
        val absentValue = object : Properties() {
            override fun propertyNames(): Enumeration<*> = Collections.enumeration(listOf("synthetic"))
        }
        assertEndpointFailure(PersistenceBoundaryFailureCode.INVALID_JDBC_PROPERTIES) { resolveEndpoint(properties = absentValue) }
    }

    @Test
    fun `property callbacks are sanitized and an already interrupted caller keeps its state`() {
        val properties = object : Properties() {
            override fun propertyNames(): Enumeration<*> = error("synthetic-private-setting")
        }
        assertEndpointFailure(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED) { resolveEndpoint(properties = properties) }
        withEndpointInterruptIsolation {
            Thread.currentThread().interrupt()
            val endpoint = resolveEndpoint()
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(endpoint.credentialsMatch(ENDPOINT_TEST_USER, ENDPOINT_TEST_PASSWORD))
        }
    }

    @Test
    fun `opaque snapshots and login policy have no generated data representation or credential accessors`() {
        val endpoint = resolveEndpoint("jdbc:postgresql://synthetic-private-host/private-db")
        assertEquals("ResolvedPersistenceEndpoint(redacted)", endpoint.toString())
        assertEquals("PersistenceLoginPolicy(redacted)", endpoint.loginPolicy.toString())
        val names = endpoint.javaClass.declaredMethods.map { it.name }
        assertTrue(names.none { it == "copy" || it.startsWith("component") || it in setOf("getUsername", "getPassword", "getUrl") })
        assertEquals("jdbc:postgresql://", endpoint.driverUrl)
    }

    companion object {
        // Independent literal catalogue from the pinned PGProperty contract, not the production collection.
        private val EXPECTED_GUARDED_KEYS = listOf(
            "PGHOST", "PGPORT", "PGDBNAME", "user", "password",
            "currentSchema", "options", "readOnly", "readOnlyMode", "replication",
            "ssl", "sslmode", "sslNegotiation", "sslcert", "sslkey", "sslrootcert", "sslpassword",
            "sslpasswordcallback", "sslfactory", "sslfactoryarg", "sslhostnameverifier", "pemKeyAlgorithm",
            "authenticationPluginClassName", "requireAuth", "channelBinding", "scramMaxIterations", "gssEncMode",
            "gsslib", "gssUseDefaultCreds", "jaasApplicationName", "jaasLogin", "kerberosServerName", "sspiServiceClass", "useSpnego",
            "socketFactory", "socketFactoryArg", "localSocketAddress", "targetServerType", "loadBalanceHosts", "hostRecheckSeconds",
            "tcpKeepAlive", "tcpNoDelay", "protocolVersion", "receiveBufferSize", "sendBufferSize", "maxSendBufferSize",
            "loginTimeout", "connectTimeout", "socketTimeout", "cancelSignalTimeout", "queryTimeout", "gssResponseTimeout", "sslResponseTimeout",
            "loggerFile", "loggerLevel", "logServerErrorDetail", "logUnclosedConnections", "xmlFactoryFactory",
        )

        @JvmStatic
        fun guardedKeys(): Stream<String> {
            check(EXPECTED_GUARDED_KEYS.size == 58 && EXPECTED_GUARDED_KEYS.toSet().size == 58)
            return EXPECTED_GUARDED_KEYS.stream()
        }
    }
}
