package me.manga.kira.backend.common.infrastructure.persistence

import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

/** Fixed role material, created only from the configuration already adopted by this root. No caller settings map. */
internal class VersionBoundPersistencePoolMaterial private constructor(
    private val endpoint: ResolvedPersistenceEndpoint,
    val role: PersistenceJdbcParticipantRole,
    val capacity: Int,
    private val endpointIdentity: VersionBoundPersistenceConfiguration.EndpointDescriptor,
    private val openings: List<PersistencePgDriverOpening.Configuration>,
) {
    val loginPolicy: PersistenceLoginPolicy = openings.first().loginPolicy
    val checkoutMillis: Long = when (role) {
        PersistenceJdbcParticipantRole.ORDINARY -> loginPolicy.durationMillis
        PersistenceJdbcParticipantRole.DELETION -> 500L
        PersistenceJdbcParticipantRole.CATALOG_COORDINATOR -> 250L
        PersistenceJdbcParticipantRole.EPOCH_ROTATION -> error("Epoch rotation is not a pool.")
    }

    fun opening(policy: PersistenceDriverAttemptPolicy): PersistencePgDriverOpening.Configuration =
        openings.singleOrNull { it.policy === policy } ?: rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED)

    internal fun construct(
        owner: PersistenceJdbcLifecycleOwner,
        binding: VersionBoundPersistencePoolBinding,
        launchProfile: PersistencePoolLaunchProfile,
    ): GuardedDataSource = GuardedDataSource.versionBound(owner, endpoint, capacity, role, binding, launchProfile)

    internal fun matchesInput(actualEndpoint: ResolvedPersistenceEndpoint, actualCapacity: Int, actualRole: PersistenceJdbcParticipantRole): Boolean =
        actualEndpoint === endpoint && actualCapacity == capacity && actualRole === role

    fun configure(pool: HikariDataSource) {
        pool.maximumPoolSize = capacity
        pool.minimumIdle = if (role === PersistenceJdbcParticipantRole.ORDINARY) 0 else capacity
        pool.connectionTimeout = checkoutMillis
        pool.validationTimeout = if (role === PersistenceJdbcParticipantRole.ORDINARY) minOf(checkoutMillis, 5_000L) else 250L
        pool.initializationFailTimeout = -1
        pool.idleTimeout = 600_000L
        pool.maxLifetime = 1_800_000L
        pool.keepaliveTime = 120_000L
        pool.leakDetectionThreshold = 0
        pool.isAutoCommit = true
        pool.isReadOnly = false
        pool.isIsolateInternalQueries = false
        pool.isRegisterMbeans = false
        pool.isAllowPoolSuspension = false
        // Diagnostic only, not serialized as configuration identity or used as role authority.
        pool.poolName = "kira-private-${role.name.lowercase()}"
    }

    fun capture(pool: HikariDataSource, lower: DataSource): VersionBoundPersistencePoolDescriptor {
        requirePool(closedInputs(pool, lower) && !pool.isRunning && !pool.isClosed)
        val minimumIdle = if (role === PersistenceJdbcParticipantRole.ORDINARY) 0 else capacity
        requirePool(pool.maximumPoolSize == capacity && pool.minimumIdle == minimumIdle)
        return VersionBoundPersistencePoolDescriptor(role, endpointIdentity, PersistenceHikariDescriptor.capture(pool), openings)
    }

    /** The actor owner separately binds the exact installed ThreadFactory and excludes other executable callbacks. */
    fun matches(pool: HikariDataSource, lower: DataSource, descriptor: VersionBoundPersistencePoolDescriptor): Boolean =
        closedInputs(pool, lower) && descriptor.hikari == PersistenceHikariDescriptor.capture(pool)

    private fun closedInputs(pool: HikariDataSource, lower: DataSource): Boolean = pool.javaClass === HikariDataSource::class.java &&
        pool.dataSource === lower && pool.jdbcUrl == null && pool.driverClassName == null && pool.dataSourceClassName == null &&
        pool.dataSourceJNDI == null && pool.username == null && pool.password == null && pool.dataSourceProperties.isEmpty() &&
        pool.healthCheckProperties.isEmpty() && pool.connectionInitSql == null && pool.connectionTestQuery == null &&
        pool.transactionIsolation == null && pool.catalog == null && pool.schema == null &&
        !pool.isRegisterMbeans && !pool.isAllowPoolSuspension

    override fun toString(): String = "VersionBoundPersistencePoolMaterial(redacted)"

    companion object {
        internal fun create(
            endpoint: ResolvedPersistenceEndpoint,
            role: PersistenceJdbcParticipantRole,
            ordinaryCapacity: Int,
            identity: VersionBoundPersistenceConfiguration.EndpointDescriptor,
        ): VersionBoundPersistencePoolMaterial {
            val policies = when (role) {
                PersistenceJdbcParticipantRole.ORDINARY -> listOf(
                    PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER,
                    PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONTRACT,
                    PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION,
                )

                PersistenceJdbcParticipantRole.DELETION -> listOf(PersistenceDriverAttemptPolicy.TRACKED_DELETION_CONJUNCTION)

                PersistenceJdbcParticipantRole.CATALOG_COORDINATOR -> listOf(PersistenceDriverAttemptPolicy.TRACKED_CATALOG_CONJUNCTION)

                PersistenceJdbcParticipantRole.EPOCH_ROTATION -> error("Epoch rotation is not a pool.")
            }
            val capacity = when (role) {
                PersistenceJdbcParticipantRole.ORDINARY -> ordinaryCapacity
                PersistenceJdbcParticipantRole.DELETION -> 4
                PersistenceJdbcParticipantRole.CATALOG_COORDINATOR -> 1
                PersistenceJdbcParticipantRole.EPOCH_ROTATION -> error("Epoch rotation is not a pool.")
            }
            val openings = policies.map { PersistencePgDriverOpening.Configuration.resolve(endpoint, it, PersistencePathStyle.POSIX) }
            return VersionBoundPersistencePoolMaterial(endpoint, role, capacity, identity, openings)
        }

        private fun requirePool(condition: Boolean) {
            if (!condition) rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED)
        }
    }
}

/** Nonsecret snapshot of an actual configured pool and the exact role recipes used by its physical participant. Not D. */
internal class VersionBoundPersistencePoolDescriptor internal constructor(
    val role: PersistenceJdbcParticipantRole,
    identity: VersionBoundPersistenceConfiguration.EndpointDescriptor,
    val hikari: PersistenceHikariDescriptor,
    configurations: List<PersistencePgDriverOpening.Configuration>,
) {
    val authenticationPassword = identity.authenticationPassword
    val publicTrustSha256 = identity.publicTrustSha256
    val publicTrustByteCount = identity.publicTrustByteCount
    val publicTrustCertificateCount = identity.publicTrustCertificateCount
    private val recipes = configurations.map(::OpeningDescriptor)

    fun openings(): List<OpeningDescriptor> = recipes.toList()

    override fun toString(): String = "VersionBoundPersistencePoolDescriptor(redacted)"

    class OpeningDescriptor internal constructor(configuration: PersistencePgDriverOpening.Configuration) {
        val policy = configuration.policy
        val driverUrl = configuration.driverUrl
        val loginBudgetMillis = configuration.loginPolicy.durationMillis
        private val properties = configuration.publicDriverProperties()

        fun publicDriverProperties(): Map<String, String> = properties.toMutableMap()

        override fun toString(): String = "VersionBoundPersistenceOpeningDescriptor(redacted)"
    }
}

/** Only effective public scalar settings. Credentials, lower factory, callbacks and generated pool names never escape. */
internal data class PersistenceHikariDescriptor private constructor(val sizing: Sizing, val timing: Timing, val behavior: Behavior) {
    data class Sizing(val maximumPoolSize: Int, val minimumIdle: Int)

    data class Timing(
        val connectionTimeoutMillis: Long,
        val validationTimeoutMillis: Long,
        val initializationFailTimeoutMillis: Long,
        val idleTimeoutMillis: Long,
        val maxLifetimeMillis: Long,
        val keepaliveTimeMillis: Long,
        val leakDetectionThresholdMillis: Long,
    )

    data class Behavior(val autoCommit: Boolean, val readOnly: Boolean, val isolateInternalQueries: Boolean)

    companion object {
        internal fun capture(pool: HikariDataSource): PersistenceHikariDescriptor = PersistenceHikariDescriptor(
            Sizing(pool.maximumPoolSize, pool.minimumIdle),
            Timing(
                pool.connectionTimeout,
                pool.validationTimeout,
                pool.initializationFailTimeout,
                pool.idleTimeout,
                pool.maxLifetime,
                pool.keepaliveTime,
                pool.leakDetectionThreshold,
            ),
            Behavior(pool.isAutoCommit, pool.isReadOnly, pool.isIsolateInternalQueries),
        )
    }
}
