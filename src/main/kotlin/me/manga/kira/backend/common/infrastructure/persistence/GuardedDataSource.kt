package me.manga.kira.backend.common.infrastructure.persistence

import com.zaxxer.hikari.HikariDataSource
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * PRIVATE, unavailable by default, and deliberately not a Spring bean. This retains the real
 * lower factory, one inert stock Hikari, and its actor/lifecycle owner before any initialization.
 * CONTROLLED_TEST_ONLY is a fixture selection, not evidence approving a production launch image.
 */
internal class GuardedDataSource(
    private val owner: PersistenceJdbcLifecycleOwner,
    private val endpoint: ResolvedPersistenceEndpoint,
    maximumPoolSize: Int,
    private val launchProfile: PersistencePoolLaunchProfile = PersistencePoolLaunchProfile.UNKNOWN,
) : DataSource, AutoCloseable {
    private val pool = HikariDataSource()
    private val lifecycle = PoolLifecycle(pool, owner)
    private val lower = PrivateJdbcDataSource(owner, endpoint, lifecycle)

    init {
        require(maximumPoolSize > 0)
        pool.dataSource = lower
        pool.maximumPoolSize = maximumPoolSize
        pool.minimumIdle = 0
        pool.initializationFailTimeout = -1
        pool.connectionTimeout = maxOf(250L, endpoint.loginPolicy.durationMillis)
        pool.validationTimeout = minOf(pool.connectionTimeout, 5_000L)
        pool.isRegisterMbeans = false
        // No jdbcUrl, credentials, custom executors, metrics/health/exception callback or config escape.
        if (launchProfile === PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY && !lifecycle.installThreadFactory()) {
            throw SQLException("Private persistence pool profile refused.")
        }
    }

    fun start(): PersistenceLifecycleActivation {
        if (launchProfile !== PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY) return PersistenceLifecycleActivation.FAILED
        return owner.start()
    }

    fun observePreparation(): PersistenceLifecycleObservation = owner.observeOrdinaryPreparation()

    override fun getConnection(): Connection {
        val budget = PersistenceTimeBudget.start(endpoint.loginPolicy.durationMillis)
        if (!businessReady()) PersistenceJdbcGuardContext.refuse()
        val acquisition = lifecycle.prepareAcquisition(budget)
        if (!acquisition.enter()) PersistenceJdbcGuardContext.refuse()
        var entitlement: PoolLifecycle.LeaseEntitlement? = null
        var facade: LeaseJdbcFacade? = null
        var obtained = false
        var endAttempted = false
        var delivered = false
        try {
            val handle = pool.connection
            obtained = true
            // Capture before any unwrap, association, allocation or facade construction can fail.
            if (!acquisition.capture(handle)) {
                acquisition.failBeforeEnd()
                PersistenceJdbcGuardContext.refuse()
            }
            entitlement = acquisition.prepareLeaseEntitlement() ?: PersistenceJdbcGuardContext.refuse()
            val physical = handle.unwrap(PhysicalJdbcFacade::class.java)
            val prepared = physical.prepareLease(this, handle, entitlement, budget)
            facade = prepared
            endAttempted = true
            if (!acquisition.end() || !acquisition.actualFrameEnded()) PersistenceJdbcGuardContext.refuse()
            // No borrower exposure until the actual acquisition TL/bookkeeping tail has ended.
            delivered = true
            return prepared
        } finally {
            try {
                if (!delivered) {
                    if (obtained || endAttempted) acquisition.failBeforeEnd()
                    try {
                        facade?.deliveryFailed()
                    } finally {
                        try {
                            entitlement?.revoke() // This connection was never returned to a borrower.
                        } finally {
                            acquisition.handoff() // Exact state retains the handle; no guessed raw close.
                        }
                    }
                }
            } finally {
                // An ENDING failure is retained, not retried as though it were an untouched frame.
                if (!endAttempted && !acquisition.end()) lifecycle.requestShutdown(budget)
            }
        }
    }

    override fun getConnection(username: String?, password: String?): Connection {
        if (!endpoint.credentialsMatch(username, password)) PersistenceJdbcGuardContext.refuse()
        return connection
    }

    internal fun ownsPool(identity: PersistenceJdbcPoolIdentity): Boolean = identity.boundTo(lifecycle)

    internal fun businessReady(): Boolean = launchProfile === PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY && lifecycle.businessReady()

    internal fun evictOwned(lease: PersistenceJdbcLease, handle: Connection, budget: PersistenceTimeBudget) {
        if (!lifecycle.isAuthenticPoolCaller()) PersistenceJdbcGuardContext.refuse()
        if (!lease.claimEviction(this, handle, budget)) return
        pool.evictConnection(handle) // Exact nontransferable source retirement was claimed first.
    }

    fun requestShutdown(): PoolLifecycle.ShutdownReceipt? = lifecycle.requestShutdown()

    fun shutdownInvocation(): PoolShutdownInvocation = lifecycle.closePool()

    override fun close() {
        lifecycle.closePool() // Request/one close, not a native or actor-completion receipt.
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
    override fun toString(): String = "GuardedDataSource(redacted)"
}

internal enum class PersistencePoolLaunchProfile { UNKNOWN, CONTROLLED_TEST_ONLY }
