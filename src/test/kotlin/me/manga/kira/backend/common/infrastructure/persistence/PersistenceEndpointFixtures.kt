package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails
import org.springframework.boot.jdbc.EmbeddedDatabaseConnection
import java.util.Properties

internal const val ENDPOINT_TEST_USER = "synthetic-user"
internal const val ENDPOINT_TEST_PASSWORD = "synthetic-password"

internal fun endpointProperties(vararg entries: Pair<String, String>): Properties = Properties().apply {
    entries.forEach { (name, value) -> setProperty(name, value) }
}

internal fun endpointFallback(
    url: String = "jdbc:postgresql://",
    username: String? = ENDPOINT_TEST_USER,
    password: String? = ENDPOINT_TEST_PASSWORD,
    driver: String? = null,
): DataSourceProperties = DataSourceProperties().apply {
    this.url = url
    this.username = username
    this.password = password
    driverClassName = driver
    embeddedDatabaseConnection = EmbeddedDatabaseConnection.NONE
}

internal fun resolveEndpoint(
    url: String = "jdbc:postgresql://",
    properties: Properties = Properties(),
    username: String? = ENDPOINT_TEST_USER,
    password: String? = ENDPOINT_TEST_PASSWORD,
    encoding: String? = "UTF-8",
    checkoutMillis: Long = 30_000,
): ResolvedPersistenceEndpoint = PersistenceEndpointResolver.resolve(
    emptyList(),
    endpointFallback(url, username, password),
    properties,
    checkoutMillis,
    encoding,
)

internal fun assertEndpointFailure(code: PersistenceBoundaryFailureCode, action: () -> Unit): PersistenceBoundaryException {
    val failure = assertThrows(PersistenceBoundaryException::class.java, action)
    assertEquals(code, failure.code)
    assertEquals("Persistence boundary rejected: ${code.name}.", failure.message)
    assertNull(failure.cause)
    assertTrue(failure.suppressed.isEmpty())
    return failure
}

internal open class EndpointTestDetails(
    private val url: String? = "jdbc:postgresql://selected:6543/selected-db",
    private val username: String? = ENDPOINT_TEST_USER,
    private val password: String? = ENDPOINT_TEST_PASSWORD,
    private val driver: String? = "org.postgresql.Driver",
) : JdbcConnectionDetails {
    override fun getJdbcUrl(): String? = url
    override fun getUsername(): String? = username
    override fun getPassword(): String? = password
    override fun getDriverClassName(): String? = driver
}

internal inline fun <T> withEndpointInterruptIsolation(action: () -> T): T {
    val interruptedOnEntry = Thread.interrupted()
    try {
        return action()
    } finally {
        Thread.interrupted()
        if (interruptedOnEntry) Thread.currentThread().interrupt()
    }
}
