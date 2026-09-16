package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

class PostgresJdbcUrlTest {
    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("acceptedUrls")
    fun `supported URL vectors retain exact endpoint and extension values`(vector: EndpointUrlVector) {
        val endpoint = resolveEndpoint(vector.url, vector.inputProperties(), username = null, password = null)
        val properties = endpoint.driverProperties()
        assertEquals(vector.expectedProperties(), properties.stringPropertyNames().associateWith { properties.getProperty(it) })
        assertEquals(vector.loginMillis, endpoint.loginPolicy.durationMillis)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(
        strings = [
            "jdbc:postgresql://host", "jdbc:postgresql:/database", "jdbc:postgresql:////db", "jdbc:postgresql://host/db/extra",
            "jdbc:postgresql:bad%", "jdbc:postgresql:bad%1", "jdbc:postgresql:bad%ZZ", "jdbc:postgresql://?ApplicationName=bad%ZZ",
            "jdbc:postgresql://,first/db", "jdbc:postgresql://first,,second/db", "jdbc:postgresql://first,/db", "jdbc:postgresql://,/db",
            "jdbc:postgresql:///db", "jdbc:postgresql://host:/db", "jdbc:postgresql://host:0/db", "jdbc:postgresql://host:65536/db",
            "jdbc:postgresql://host:-1/db", "jdbc:postgresql://host:2147483648/db", "jdbc:postgresql://host: 5432/db", "jdbc:postgresql://host:1.2/db",
        ],
    )
    fun `malformed URL encoding host lists and port forms reject without a driver call`(url: String) {
        assertEndpointFailure(PersistenceBoundaryFailureCode.INVALID_JDBC_URL) { resolveEndpoint(url) }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["", "postgresql://host/db", "JDBC:postgresql://host/db", "jdbc:PostgreSQL://host/db", "jdbc:postgres://host/db"])
    fun `pure URL prefix matching is exact rather than generic URI normalization`(url: String) {
        assertEndpointFailure(PersistenceBoundaryFailureCode.INVALID_JDBC_URL) { PostgresJdbcUrl.parse(url, PersistenceUrlEncoding.UTF_8) }
    }

    @Test
    fun `properties do not broadcast ports or fill a missing port inside an explicitly assigned host URL list`() {
        assertEndpointFailure(PersistenceBoundaryFailureCode.INVALID_JDBC_URL) {
            resolveEndpoint(properties = endpointProperties("PGHOST" to "first,second"))
        }
        assertEndpointFailure(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_CONFLICT) {
            resolveEndpoint("jdbc:postgresql://host/", endpointProperties("PGPORT" to "6543"))
        }
        val explicit = resolveEndpoint("jdbc:postgresql://host/", endpointProperties("PGPORT" to "5432", "PGDBNAME" to "property-db"))
        assertEquals("property-db", explicit.driverProperties().getProperty("PGDBNAME"))
        assertEndpointFailure(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_CONFLICT) {
            resolveEndpoint("jdbc:postgresql:", endpointProperties("PGDBNAME" to "property-db"))
        }
    }

    @Test
    fun `property host and port lists retain empties for rejection and validate every tuple`() {
        for (hosts in listOf("", ",host", "host,", "host,,other", ",")) {
            assertEndpointFailure(PersistenceBoundaryFailureCode.INVALID_JDBC_URL) {
                resolveEndpoint(properties = endpointProperties("PGHOST" to hosts))
            }
        }
        for (ports in listOf("", ",5432", "5432,", "5432,,6543", "0", "65536", "5432,6543")) {
            assertEndpointFailure(PersistenceBoundaryFailureCode.INVALID_JDBC_URL) {
                resolveEndpoint(properties = endpointProperties("PGPORT" to ports))
            }
        }
    }

    @Test
    fun `character decoding is once only and uses the declared stock charset replacement behavior`() {
        assertEquals("café", resolveEndpoint("jdbc:postgresql:caf%C3%A9").driverProperties().getProperty("PGDBNAME"))
        assertEquals("café", resolveEndpoint("jdbc:postgresql:caf%E9", encoding = "ISO-8859-1").driverProperties().getProperty("PGDBNAME"))
        assertEquals("cafÃ©", resolveEndpoint("jdbc:postgresql:caf%C3%A9", encoding = "ISO-8859-1").driverProperties().getProperty("PGDBNAME"))
        assertEquals("\uFFFD(", resolveEndpoint("jdbc:postgresql:%C3%28").driverProperties().getProperty("PGDBNAME"))
        assertEquals("%2F", resolveEndpoint("jdbc:postgresql:%252F").driverProperties().getProperty("PGDBNAME"))
        assertEquals("a/b%?", resolveEndpoint("jdbc:postgresql:a%2Fb%25%3F").driverProperties().getProperty("PGDBNAME"))
    }

    companion object {
        @JvmStatic
        fun acceptedUrls(): Stream<EndpointUrlVector> = EndpointUrlCases.accepted.stream()
    }
}
