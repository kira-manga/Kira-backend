package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails
import java.util.Properties

class PersistenceEndpointResolverTest {
    @Test
    fun `real JdbcConnectionDetails default driver inference remains compatible with authoritative selection`() {
        val details = object : JdbcConnectionDetails {
            override fun getJdbcUrl(): String = "jdbc:postgresql://selected/"
            override fun getUsername(): String = ""
            override fun getPassword(): String = " \t "
        }
        val endpoint = PersistenceEndpointResolver.resolve(listOf(details), UnusedEndpointFallback(), Properties(), 30_000, "UTF-8")
        assertEquals("selected", endpoint.driverProperties().getProperty("PGHOST"))
        assertTrue(endpoint.credentialsMatch("", " \t "))
        assertEquals("", endpoint.driverProperties().getProperty("PGDBNAME"))
    }

    @Test
    fun `real Boot inferred and explicit PostgreSQL fallback resolve the same selected values`() {
        val fallback = endpointFallback("jdbc:postgresql://Database.Example:6543/catalog?sslmode=verify-full")
        fallback.afterPropertiesSet()
        val inferred = PersistenceEndpointResolver.resolve(emptyList(), fallback, Properties(), 30_000, "UTF-8")
        fallback.driverClassName = "org.postgresql.Driver"
        val explicit = PersistenceEndpointResolver.resolve(emptyList(), fallback, Properties(), 30_000, "UTF-8")
        assertEquals(inferred.driverProperties(), explicit.driverProperties())
        assertEquals("Database.Example", explicit.driverProperties().getProperty("PGHOST"))
        assertEquals("6543", explicit.driverProperties().getProperty("PGPORT"))
        assertEquals("catalog", explicit.driverProperties().getProperty("PGDBNAME"))
        assertEquals("verify-full", explicit.driverProperties().getProperty("sslmode"))
        assertEquals("jdbc:postgresql://", explicit.driverUrl)
        assertEquals("0", explicit.driverProperties().getProperty("loginTimeout"))
        assertEquals(30_000L, explicit.loginPolicy.durationMillis)
    }

    @ParameterizedTest(name = "{displayName} [{index}]")
    @ValueSource(strings = ["", " ", " \t "])
    fun `raw nonnull Boot credentials survive even when determine methods would discard them`(credential: String) {
        val fallback = endpointFallback(username = credential, password = credential)
        assertNull(fallback.determineUsername())
        assertNull(fallback.determinePassword())
        val result = PersistenceEndpointResolver.resolve(emptyList(), fallback, Properties(), 30_000, "UTF-8")
        assertEquals(credential, result.driverProperties().getProperty("user"))
        assertEquals(credential, result.driverProperties().getProperty("password"))
        assertEquals(credential, result.driverProperties().getProperty("PGDBNAME"))
        assertTrue(result.credentialsMatch(credential, credential))
    }

    @Test
    fun `one details object replaces stale fallback and canonical property endpoint without consulting fallback getters`() {
        val fallback = UnusedEndpointFallback()
        val properties = endpointProperties(
            "PGHOST" to "stale",
            "PGPORT" to "invalid-stale",
            "PGDBNAME" to "stale-db",
            "user" to "stale-user",
            "password" to "stale-password",
            "currentSchema" to "selected_schema",
            "sslmode" to "verify-full",
            "host" to "inert-property-alias",
        )
        val result = PersistenceEndpointResolver.resolve(listOf(EndpointTestDetails()), fallback, properties, 30_000, "UTF-8")
        val actual = result.driverProperties()
        assertEquals("selected", actual.getProperty("PGHOST"))
        assertEquals("6543", actual.getProperty("PGPORT"))
        assertEquals("selected-db", actual.getProperty("PGDBNAME"))
        assertEquals(ENDPOINT_TEST_USER, actual.getProperty("user"))
        assertEquals(ENDPOINT_TEST_PASSWORD, actual.getProperty("password"))
        assertEquals("selected_schema", actual.getProperty("currentSchema"))
        assertEquals("verify-full", actual.getProperty("sslmode"))
        assertEquals("inert-property-alias", actual.getProperty("host"))
        assertEquals(0, fallback.calls)
        assertEquals("stale-password", properties.getProperty("password"))
    }

    @Test
    fun `ambiguous details reject before every candidate or fallback getter`() {
        var detailCalls = 0
        val details = object : EndpointTestDetails() {
            override fun getDriverClassName(): String {
                detailCalls++
                error("Getter must not execute")
            }
        }
        val fallback = UnusedEndpointFallback()
        assertEndpointFailure(PersistenceBoundaryFailureCode.AMBIGUOUS_JDBC_ENDPOINT) {
            PersistenceEndpointResolver.resolve(listOf(details, details), fallback, Properties(), 30_000, "UTF-8")
        }
        assertEquals(0, detailCalls)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `selected details cannot recover missing credentials from the stale ordinary tier`() {
        assertEndpointFailure(PersistenceBoundaryFailureCode.MISSING_JDBC_CREDENTIALS) {
            PersistenceEndpointResolver.resolve(
                listOf(EndpointTestDetails(username = null, password = null)),
                UnusedEndpointFallback(),
                endpointProperties("user" to ENDPOINT_TEST_USER, "password" to ENDPOINT_TEST_PASSWORD),
                30_000,
                "UTF-8",
            )
        }
        val endpoint = PersistenceEndpointResolver.resolve(
            listOf(EndpointTestDetails(url = "jdbc:postgresql://?user=selected&password=", username = null, password = null)),
            UnusedEndpointFallback(),
            endpointProperties("user" to "stale-user", "password" to "stale-password"),
            30_000,
            "UTF-8",
        )
        assertTrue(endpoint.credentialsMatch("selected", ""))
    }

    @Test
    fun `explicit URL or String properties can fill absent fallback credentials without ambient defaults`() {
        val fromUrl = resolveEndpoint(
            url = "jdbc:postgresql://?user=synthetic+user&password=p%2B%25",
            username = null,
            password = null,
        )
        assertTrue(fromUrl.credentialsMatch("synthetic user", "p+%"))
        val fromProperties = resolveEndpoint(
            properties = endpointProperties("user" to "", "password" to ""),
            username = null,
            password = null,
        )
        assertTrue(fromProperties.credentialsMatch("", ""))
        for ((username, password) in listOf(null to ENDPOINT_TEST_PASSWORD, ENDPOINT_TEST_USER to null, null to null)) {
            assertEndpointFailure(PersistenceBoundaryFailureCode.MISSING_JDBC_CREDENTIALS) {
                resolveEndpoint(username = username, password = password)
            }
        }
    }

    @Test
    fun `credential contradictions inside the selected authority reject rather than depending on Hikari overwrite order`() {
        for (url in listOf("jdbc:postgresql://?user=other", "jdbc:postgresql://?password=other")) {
            assertEndpointFailure(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_CONFLICT) { resolveEndpoint(url) }
            assertEndpointFailure(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_CONFLICT) {
                PersistenceEndpointResolver.resolve(listOf(EndpointTestDetails(url = url)), UnusedEndpointFallback(), Properties(), 30_000, "UTF-8")
            }
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}]")
    @ValueSource(strings = ["", " ", "org.postgresql.Driver ", "synthetic.UntrustedDriver", "org.h2.Driver"])
    fun `an unsupported explicit driver rejects before Boot fallback determination`(driver: String) {
        val fallback = object : DataSourceProperties() {
            override fun getDriverClassName(): String = driver
            override fun determineDriverClassName(): String = error("Must not resolve unsupported class")
            override fun determineUrl(): String = error("Must not resolve unsupported endpoint")
        }
        assertEndpointFailure(PersistenceBoundaryFailureCode.UNSUPPORTED_JDBC_DRIVER) {
            PersistenceEndpointResolver.resolve(emptyList(), fallback, Properties(), 30_000, "UTF-8")
        }
        assertEndpointFailure(PersistenceBoundaryFailureCode.UNSUPPORTED_JDBC_DRIVER) {
            PersistenceEndpointResolver.resolve(listOf(EndpointTestDetails(driver = driver)), fallback, Properties(), 30_000, "UTF-8")
        }
    }

    @Test
    fun `non PostgreSQL inferred driver and null details driver cannot enable a different database`() {
        assertEndpointFailure(PersistenceBoundaryFailureCode.UNSUPPORTED_JDBC_DRIVER) {
            resolveEndpoint("jdbc:mysql://synthetic/database")
        }
        assertEndpointFailure(PersistenceBoundaryFailureCode.UNSUPPORTED_JDBC_DRIVER) {
            PersistenceEndpointResolver.resolve(listOf(EndpointTestDetails(driver = null)), UnusedEndpointFallback(), Properties(), 30_000, "UTF-8")
        }
    }

    @Test
    fun `null details URL and failed Boot fallback are fixed non SQL configuration rejections`() {
        assertEndpointFailure(PersistenceBoundaryFailureCode.INVALID_JDBC_URL) {
            PersistenceEndpointResolver.resolve(listOf(EndpointTestDetails(url = null)), UnusedEndpointFallback(), Properties(), 30_000, "UTF-8")
        }
        val absent = endpointFallback().apply { url = null }
        assertEndpointFailure(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED) {
            PersistenceEndpointResolver.resolve(emptyList(), absent, Properties(), 30_000, "UTF-8")
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(EndpointGetter::class)
    fun `getter failures are neither formatted nor attached at the public cold boundary`(getter: EndpointGetter) {
        val hostile = object : RuntimeException() {
            override val message: String get() = error("Must not read message")
            override val cause: Throwable get() = error("Must not read cause")
            override fun getStackTrace(): Array<StackTraceElement> = error("Must not read stack")
            override fun toString(): String = error("Must not format failure")
        }
        assertEndpointFailure(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED) {
            resolveThrowingDetails(getter, hostile)
        }
    }

    @Test
    fun `callback supplied boundary exceptions are reconstructed without their mutable throwable graph`() {
        val original = PersistenceBoundaryException(PersistenceBoundaryFailureCode.INVALID_JDBC_URL)
        original.initCause(IllegalStateException("synthetic-cause"))
        original.addSuppressed(IllegalStateException("synthetic-suppressed"))
        original.stackTrace = arrayOf(StackTraceElement("synthetic-sensitive-class", "synthetic-method", "synthetic-file", 1))
        val observed = assertEndpointFailure(PersistenceBoundaryFailureCode.INVALID_JDBC_URL) {
            resolveThrowingDetails(EndpointGetter.URL, original)
        }
        assertNotSame(original, observed)
        assertTrue(observed.stackTrace.none { it.className == "synthetic-sensitive-class" })
    }

    @Test
    fun `direct Error identity propagates and direct interruption is restored without retaining its cause`() {
        val fatal = object : Error() {
            override fun toString(): String = error("Must not format fatal signal")
        }
        assertSame(fatal, assertThrows(Error::class.java) { resolveThrowingDetails(EndpointGetter.PASSWORD, fatal) })
        withEndpointInterruptIsolation {
            assertEndpointFailure(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED) {
                resolveThrowingDetails(EndpointGetter.USER, InterruptedException("synthetic-interruption"))
            }
            assertTrue(Thread.currentThread().isInterrupted)
        }
    }

    private fun resolveThrowingDetails(getter: EndpointGetter, failure: Throwable) {
        val details = object : EndpointTestDetails() {
            override fun getDriverClassName(): String? = if (getter == EndpointGetter.DRIVER) throw failure else super.getDriverClassName()
            override fun getJdbcUrl(): String? = if (getter == EndpointGetter.URL) throw failure else super.getJdbcUrl()
            override fun getUsername(): String? = if (getter == EndpointGetter.USER) throw failure else super.getUsername()
            override fun getPassword(): String? = if (getter == EndpointGetter.PASSWORD) throw failure else super.getPassword()
        }
        PersistenceEndpointResolver.resolve(listOf(details), UnusedEndpointFallback(), Properties(), 30_000, "UTF-8")
    }

    enum class EndpointGetter { DRIVER, URL, USER, PASSWORD }

    private class UnusedEndpointFallback : DataSourceProperties() {
        var calls = 0
        private fun forbidden(): Nothing {
            calls++
            error("Unused fallback must not be read")
        }

        override fun getUrl(): String = forbidden()
        override fun getUsername(): String = forbidden()
        override fun getPassword(): String = forbidden()
        override fun getDriverClassName(): String = forbidden()
        override fun determineUrl(): String = forbidden()
        override fun determineUsername(): String = forbidden()
        override fun determinePassword(): String = forbidden()
        override fun determineDriverClassName(): String = forbidden()
    }
}
