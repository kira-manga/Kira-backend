package me.manga.kira.backend.common.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import jakarta.persistence.EntityManagerFactory
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintGrantCleanupPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.OrdinaryPersistencePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ScopedAdminStepUpPhaseExecutor
import me.manga.kira.backend.config.KiraAdminStudioProperties
import me.manga.kira.backend.security.AuthThrottle
import me.manga.kira.backend.security.JdbcComplaintGrantCleanupStore
import me.manga.kira.backend.security.JdbcScopedAdminStepUpStore
import me.manga.kira.backend.security.JdbcSourceGrantCleanupStore
import me.manga.kira.backend.security.ScopedAdminStepUpIssuer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails
import org.springframework.boot.autoconfigure.jdbc.JdbcProperties
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.jdbc.support.SQLExceptionTranslator
import org.springframework.security.crypto.password.PasswordEncoder
import java.io.File
import java.time.Clock
import javax.sql.DataSource

/**
 * Live ordinary/source composition. Boot still owns the one EMF and Flyway authority.
 * This configuration supplies no runtime qualification, complaint policy or deletion activation.
 * Only guarded resources are beans; endpoints, the driver factory and raw Hikari stay private.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DataSourceProperties::class, JdbcProperties::class)
internal class OrdinaryPersistenceConfiguration {
    @Bean(name = ["dataSource", "ordinaryDataSource"], destroyMethod = "close")
    @Primary
    @Suppress("TooGenericExceptionCaught") // Close the retained owner before sanitizing any bootstrap failure.
    fun dataSource(properties: DataSourceProperties, details: ObjectProvider<JdbcConnectionDetails>, environment: Environment): GuardedDataSource =
        persistenceBootstrapBoundary {
            val prepared = OrdinaryPersistenceSettings.bind(properties, details.orderedStream().toList(), environment)
            val owner = PersistenceJdbcLifecycleOwner.sourceOnly(prepared.endpoint, prepared.pool.maximumPoolSize, pathStyle())
            val ordinary = GuardedDataSource.sourceOnly(owner, prepared.endpoint, prepared.pool)
            try {
                check(ordinary.start() === PersistenceLifecycleActivation.STARTED)
                // Metadata/owned-worker preparation only. Physical connectivity remains Boot's
                // ordinary Flyway/EMF/health work, never a deletion warm-up or health side effect.
                check(ordinary.observePreparation() === PersistenceLifecycleObservation.READY)
                ordinary
            } catch (failure: Throwable) {
                ordinary.close()
                rejectPersistenceBootstrapFailure(failure)
            }
        }

    @Bean(name = ["complaintDeletionDataSource"], destroyMethod = "close")
    fun complaintDeletionDataSource(@Qualifier("ordinaryDataSource") ordinary: GuardedDataSource): GuardedDataSource = ordinary.closedDeletionDataSource()

    @Bean(name = ["transactionManager", "ordinaryJpaTransactionManager"])
    @Primary
    fun transactionManager(
        entityManagerFactory: EntityManagerFactory,
        @Qualifier("ordinaryDataSource") ordinary: GuardedDataSource,
        customizers: ObjectProvider<TransactionManagerCustomizers>,
    ): GuardedJpaTransactionManager = GuardedJpaTransactionManager.sourceOnly(entityManagerFactory, ordinary, customizers.ifAvailable)

    @Bean(name = ["complaintDeletionTransactionManager"])
    fun complaintDeletionTransactionManager(@Qualifier("complaintDeletionDataSource") deletion: GuardedDataSource): GuardedJdbcTransactionManager =
        GuardedJdbcTransactionManager(deletion)

    @Bean(name = ["jdbcTemplate", "ordinaryJdbcTemplate"])
    @Primary
    fun jdbcTemplate(
        @Qualifier("ordinaryDataSource") ordinary: GuardedDataSource,
        properties: JdbcProperties,
        translator: ObjectProvider<SQLExceptionTranslator>,
    ): JdbcTemplate = JdbcTemplate(ordinary).apply {
        // Match Boot 3.5's JdbcTemplateConfiguration, including the optional unique translator.
        val template = properties.template
        isIgnoreWarnings = template.isIgnoreWarnings
        fetchSize = template.fetchSize
        maxRows = template.maxRows
        template.queryTimeout?.let { queryTimeout = it.seconds.toInt() }
        isSkipResultsProcessing = template.isSkipResultsProcessing
        isSkipUndeclaredResults = template.isSkipUndeclaredResults
        isResultsMapCaseInsensitive = template.isResultsMapCaseInsensitive
        translator.ifUnique?.let { exceptionTranslator = it }
    }

    @Bean(name = ["complaintDeletionJdbcTemplate"])
    fun complaintDeletionJdbcTemplate(@Qualifier("complaintDeletionDataSource") deletion: GuardedDataSource): JdbcTemplate =
        JdbcTemplate(deletion).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }

    @Bean(name = ["dbHealthContributor"])
    @ConditionalOnEnabledHealthIndicator("db")
    fun dbHealthContributor(@Qualifier("ordinaryDataSource") ordinary: GuardedDataSource): DataSourceHealthIndicator =
        DataSourceHealthIndicator(ordinary, ordinary.ordinaryValidationQuery())

    @Bean
    fun ordinaryPersistenceAdmission(@Qualifier("ordinaryDataSource") ordinary: GuardedDataSource): OrdinaryPersistenceAdmission =
        OrdinaryPersistenceAdmission.sourceOnly(ordinary.ordinaryPoolSize())

    @Bean
    fun ordinaryPersistenceOwnership(
        admission: OrdinaryPersistenceAdmission,
        @Qualifier("ordinaryJpaTransactionManager") manager: GuardedJpaTransactionManager,
    ): PersistencePhaseOwnership = PersistencePhaseOwnership(admission, manager)

    @Bean
    fun sourceStepUpIssuer(
        ownership: PersistencePhaseOwnership,
        @Qualifier("ordinaryJdbcTemplate") jdbc: JdbcTemplate,
        clock: Clock,
        properties: KiraAdminStudioProperties,
        passwords: PasswordEncoder,
        throttle: AuthThrottle,
    ): ScopedAdminStepUpIssuer {
        val sourceCleanup = OrdinaryPersistencePhaseExecutor(ownership, JdbcSourceGrantCleanupStore(jdbc), clock)
        // No authenticated complaint-policy digest is supplied. More importantly, the same
        // owner's sourceOnly admission refuses every complaint phase BEFORE any of this SQL.
        val closedCapacity = JdbcComplaintCapacityStore(jdbc, expectedPolicyDigest = null)
        val closedCleanup = ComplaintGrantCleanupPhaseExecutor(ownership, JdbcComplaintGrantCleanupStore(jdbc, closedCapacity), clock)
        val store = JdbcScopedAdminStepUpStore(jdbc, closedCapacity, clock, properties)
        return ScopedAdminStepUpIssuer(ScopedAdminStepUpPhaseExecutor(ownership, store, sourceCleanup, closedCleanup), passwords, throttle)
    }

    private fun pathStyle(): PersistencePathStyle = if (File.separatorChar == '\\') PersistencePathStyle.LOCAL_WINDOWS else PersistencePathStyle.POSIX
}

/** Cold binding only. It never initializes a pool or loads a supplied driver/alternate DataSource. */
internal class OrdinaryPersistenceSettings private constructor(val pool: HikariConfig, val endpoint: ResolvedPersistenceEndpoint) {
    override fun toString(): String = "OrdinaryPersistenceSettings(redacted)"

    companion object {
        fun bind(properties: DataSourceProperties, details: List<JdbcConnectionDetails>, environment: Environment): OrdinaryPersistenceSettings =
            persistenceBootstrapBoundary {
                requirePersistenceBootstrapGlobals()
                PersistenceLoggingPreflight.capture().recheck()
                if (details.size > 1) rejectPersistenceBoundary(PersistenceBoundaryFailureCode.AMBIGUOUS_JDBC_ENDPOINT)
                if ((properties.type != null && properties.type !== HikariDataSource::class.java) || properties.jndiName != null) {
                    rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_CONFLICT)
                }
                val pool = OrdinaryHikariBinding()
                properties.name?.takeIf(String::isNotBlank)?.let { pool.poolName = it }
                Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(pool))
                val fallback = if (details.isEmpty()) effectiveFallback(properties, pool) else properties
                val endpoint = PersistenceEndpointResolver.resolve(details, fallback, pool.dataSourceProperties, pool.connectionTimeout)
                // Before Hikari validation, diagnostics or copying into the retained pool.
                pool.jdbcUrl = null
                pool.username = null
                pool.password = null
                pool.driverClassName = null
                pool.dataSourceProperties.clear()
                OrdinaryPersistenceSettings(pool, endpoint)
            }

        private fun effectiveFallback(properties: DataSourceProperties, pool: OrdinaryHikariBinding): DataSourceProperties = DataSourceProperties().apply {
            driverClassName = pool.driverClassName ?: properties.driverClassName
            url = pool.jdbcUrl ?: properties.determineUrl()
            username = pool.username ?: properties.username ?: properties.determineUsername()
            password = pool.password ?: properties.password ?: properties.determinePassword()
        }
    }
}

/** Bind ordinary Hikari fields without its driver setter's eager load/constructor/logging path. */
private class OrdinaryHikariBinding : HikariConfig() {
    private var selectedDriver: String? = null

    override fun getDriverClassName(): String? = selectedDriver
    override fun setDriverClassName(driverClassName: String?) {
        selectedDriver = driverClassName
    }

    override fun setConnectionTimeout(connectionTimeoutMs: Long) {
        if (connectionTimeoutMs <= 0) rejectPersistenceBoundary(PersistenceBoundaryFailureCode.INVALID_LOGIN_POLICY)
        super.setConnectionTimeout(connectionTimeoutMs)
    }

    override fun setDataSource(dataSource: DataSource?) {
        if (dataSource != null) conflict()
    }

    override fun setDataSourceClassName(className: String?) {
        if (className != null) conflict()
    }

    override fun setDataSourceJNDI(jndiDataSource: String?) {
        if (jndiDataSource != null) conflict()
    }

    override fun setRegisterMbeans(register: Boolean) {
        if (register) conflict() // A writable raw Hikari config/factory must not escape through JMX.
    }

    override fun setAllowPoolSuspension(allowPoolSuspension: Boolean) {
        if (allowPoolSuspension) conflict() // Suspension bypasses the required finite checkout allowance.
    }

    private fun conflict(): Nothing = rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_CONFLICT)
}
