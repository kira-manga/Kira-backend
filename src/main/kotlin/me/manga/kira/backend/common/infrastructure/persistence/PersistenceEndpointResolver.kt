package me.manga.kira.backend.common.infrastructure.persistence

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails
import java.util.Properties

/** Cold, unused prerequisite. Complete Hikari binding, driver preparation and owned pools are separate gates. */
internal object PersistenceEndpointResolver {
    fun resolve(
        details: List<JdbcConnectionDetails>,
        fallback: DataSourceProperties,
        driverProperties: Properties,
        checkoutMillis: Long,
        urlEncodingName: String? = null,
    ): ResolvedPersistenceEndpoint = runCatching {
        val supplied = when (details.size) {
            0 -> null
            1 -> details.single()
            else -> rejectPersistenceBoundary(PersistenceBoundaryFailureCode.AMBIGUOUS_JDBC_ENDPOINT)
        }
        // Named property access is inside the sanitized boundary, never a default-argument initializer.
        val encoding = PersistenceUrlEncoding.select(urlEncodingName ?: System.getProperty("postgresql.url.encoding", "UTF-8"))
        val selected = if (supplied == null) fallbackEndpoint(fallback) else suppliedEndpoint(supplied)
        val captured = PersistenceDriverProperties.capture(driverProperties)
        val ordinary = if (supplied == null) captured else PersistenceDriverProperties.withoutEndpoint(captured)
        val endpoint = PersistenceDriverProperties.merge(ordinary, PostgresJdbcUrl.parse(selected.url, encoding))
        val effective = PersistenceDriverProperties.merge(endpoint, selected.credentials)
        if (!effective.containsKey("user") || !effective.containsKey("password")) {
            rejectPersistenceBoundary(PersistenceBoundaryFailureCode.MISSING_JDBC_CREDENTIALS)
        }
        effective.putIfAbsent("PGHOST", "localhost")
        effective.putIfAbsent("PGPORT", "5432")
        effective.putIfAbsent("PGDBNAME", effective.getValue("user"))
        PostgresJdbcUrl.validateEndpoint(effective)
        val loginPolicy = PersistenceLoginPolicy.resolve(effective["loginTimeout"], checkoutMillis)
        effective["loginTimeout"] = "0"
        ResolvedPersistenceEndpoint(effective, loginPolicy)
    }.getOrElse(::configurationFailure)

    private fun suppliedEndpoint(details: JdbcConnectionDetails): SelectedEndpoint {
        requirePostgresDriver(details.driverClassName)
        return SelectedEndpoint(details.jdbcUrl, details.username, details.password)
    }

    private fun fallbackEndpoint(fallback: DataSourceProperties): SelectedEndpoint {
        // Validate a supplied class name before Boot's loadability check can consult that classloader.
        val explicitDriver = fallback.driverClassName
        if (explicitDriver != null) requirePostgresDriver(explicitDriver)
        requirePostgresDriver(explicitDriver ?: fallback.determineDriverClassName())
        return SelectedEndpoint(
            fallback.determineUrl(),
            fallback.username ?: fallback.determineUsername(),
            fallback.password ?: fallback.determinePassword(),
        )
    }

    private fun requirePostgresDriver(driver: String?) {
        if (driver != "org.postgresql.Driver") rejectPersistenceBoundary(PersistenceBoundaryFailureCode.UNSUPPORTED_JDBC_DRIVER)
    }

    private fun configurationFailure(failure: Throwable): Nothing {
        // runCatching is a capture boundary only: direct fatal signals are rethrown unchanged.
        if (failure is Error) throw failure
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        val code = if (failure is PersistenceBoundaryException) failure.code else PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED
        // Reconstruct even our own type: callbacks may have attached arbitrary causes/suppressed graphs.
        rejectPersistenceBoundary(code)
    }

    private class SelectedEndpoint(url: String?, username: String?, password: String?) {
        val url: String = url ?: rejectPersistenceBoundary(PersistenceBoundaryFailureCode.INVALID_JDBC_URL)
        val credentials = buildMap {
            if (username != null) put("user", username)
            if (password != null) put("password", password)
        }
    }
}
