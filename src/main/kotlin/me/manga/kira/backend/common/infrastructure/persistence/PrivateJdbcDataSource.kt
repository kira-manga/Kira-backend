package me.manga.kira.backend.common.infrastructure.persistence

import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.logging.Logger
import javax.sql.DataSource

/** Exact typed participant route only. Retained privately by the real Hikari configuration. */
internal class PrivateJdbcDataSource(
    private val owner: PersistenceJdbcLifecycleOwner,
    private val endpoint: ResolvedPersistenceEndpoint,
    private val lifecycle: PoolLifecycle,
) : DataSource {
    override fun getConnection(): Connection {
        val budget = PersistenceTimeBudget.start(endpoint.loginPolicy.durationMillis)
        if (!lifecycle.isAuthenticPoolCaller()) PersistenceJdbcGuardContext.refuse()
        val result = owner.requestOrdinaryPoolConnection()
        val facade = when (result) {
            is PersistenceFactoryResult.Success -> result.value
            is PersistenceFactoryResult.Failed, is PersistenceFactoryResult.Refused -> PersistenceJdbcGuardContext.refuse()
        }
        var delivered = false
        try {
            if (!facade.bindPool(lifecycle, budget)) PersistenceJdbcGuardContext.refuse()
            if (persistenceFactoryRemainingMillis(budget) == 0L) PersistenceJdbcGuardContext.refuse()
            delivered = true
            return facade
        } finally {
            if (!delivered) lifecycle.requestShutdown(budget) // Keep this allowance/partial custody, never retry a lost generation.
        }
    }

    override fun getConnection(username: String?, password: String?): Connection {
        if (!endpoint.credentialsMatch(username, password)) PersistenceJdbcGuardContext.refuse()
        return connection
    }

    override fun getLoginTimeout(): Int = endpoint.loginPolicy.jdbcSeconds
    override fun setLoginTimeout(seconds: Int) {
        if (!endpoint.loginPolicy.acceptsJdbcSeconds(seconds)) throw SQLFeatureNotSupportedException("Private persistence settings are immutable.")
    }
    override fun getLogWriter(): PrintWriter? = null
    override fun setLogWriter(out: PrintWriter?) {
        if (out != null) throw SQLFeatureNotSupportedException("Private persistence logging is immutable.")
    }
    override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException("Private persistence logger is not exposed.")
    override fun isWrapperFor(iface: Class<*>?): Boolean = iface?.isInstance(this) == true
    override fun <T : Any?> unwrap(iface: Class<T>?): T {
        if (iface?.isInstance(this) == true) return iface.cast(this)
        throw SQLException("Private persistence unwrap refused.")
    }
    override fun toString(): String = "PrivateJdbcDataSource(redacted)"
}
