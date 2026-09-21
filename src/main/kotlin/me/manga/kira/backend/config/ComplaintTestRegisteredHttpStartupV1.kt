package me.manga.kira.backend.config

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManagerFactory
import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.infrastructure.AuditLogEntity
import me.manga.kira.backend.audit.infrastructure.JpaAuditRepositoryAdapter
import me.manga.kira.backend.audit.infrastructure.SpringDataAuditLogRepository
import me.manga.kira.backend.common.infrastructure.persistence.GuardedDataSource
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJpaTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.OrdinaryPersistenceAdmission
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipantRole
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentFailureV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.boundedTestDeploymentFailure
import me.manga.kira.backend.complaint.infrastructure.admission.requireTestDeployment
import me.manga.kira.backend.complaint.infrastructure.catalog.preferCatalogFreezeCleanup
import me.manga.kira.backend.security.ComplaintSecurityFailure
import me.manga.kira.backend.security.ComplaintSecurityResponses
import me.manga.kira.backend.security.CurrentUser
import me.manga.kira.backend.security.JwtKeyProvider
import me.manga.kira.backend.security.ProblemAccessDeniedHandler
import me.manga.kira.backend.security.ProblemAuthenticationEntryPoint
import me.manga.kira.backend.security.SecurityConfig
import me.manga.kira.backend.user.domain.UserRepository
import me.manga.kira.backend.user.infrastructure.JpaUserRepositoryAdapter
import me.manga.kira.backend.user.infrastructure.SpringDataUserRepository
import me.manga.kira.backend.user.infrastructure.UserEntity
import org.apache.catalina.LifecycleState
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer
import org.springframework.boot.web.server.Shutdown
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.DispatcherServlet
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.net.InetAddress
import java.time.Clock
import java.util.concurrent.locks.LockSupport
import java.util.function.Supplier

/**
 * A concrete original-JPA and real loopback servlet startup, not a caller-supplied graph or registrar.
 * Obtain from the original assembly BEFORE calling start(). The assembly retains this one child even
 * when initialization/closure does not return; it cannot close native pools/trust under unproved users.
 * No component scan, full-app coexistence, environment activation, LIVE route or launch qualification.
 */
internal class ComplaintTestRegisteredHttpStartupV1 private constructor(
    private val assembly: ComplaintTestProcessAssemblyV1,
    private val registration: ComplaintTestNamespaceRegistrationV1,
    private val replyPolicy: me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestInitialCheckpointCreateV1? = null,
    private val editPolicy: me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestInitialCheckpointCreateV1? = null,
) : AutoCloseable {
    private val startupBudget = PersistenceTimeBudget.start(60_000)
    private val ingress = registration.process.consumers.ingressAdmission
    private var startEntered = false
    private var closeEntered = false
    private var started = false
    private var cleanupProven = false
    private val unresolvedClose = ComplaintTestDeploymentExceptionV1(ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
    private var cleanupFailure: Throwable? = null
    private var factory: LocalContainerEntityManagerFactoryBean? = null
    private var factoryInitializing = false
    private var factoryInitialized = false
    private var factoryCloseReturned = false
    private var emf: EntityManagerFactory? = null
    private var admission: OrdinaryPersistenceAdmission? = null
    private var ownership: PersistencePhaseOwnership? = null
    private var jdbc: JdbcTemplate? = null
    private var installationBindingEntered = false
    private var installationBindingReturned = false
    private var audit: AuditService? = null
    private var context: AnnotationConfigServletWebServerApplicationContext? = null
    private var contextInitializing = false
    private var contextInitialized = false
    private var contextCloseReturned = false
    private var server: TomcatWebServer? = null
    private var selectedPort: Int? = null

    /** Listener location only, never current-state/receipt/namespace authority. */
    val localPort: Int
        get() {
            requireCaller()
            requireTestDeployment(started && !closeEntered && context?.isActive == true, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            registration.requireActiveIdentityTarget(assembly)
            return checkNotNull(selectedPort)
        }

    @Suppress("TooGenericExceptionCaught") // Keep exact owners before sanitizing any failed/unreturned startup.
    fun start() {
        requireCaller()
        requireTestDeployment(!startEntered && !closeEntered, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
        startEntered = true
        try {
            checkpoint()
            registration.requireActiveIdentityTarget(assembly)
            requireTestDeployment(registration.process.initialCheckpointCreate != null, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            requireTestDeployment(replyPolicy == null || replyPolicy === registration.process.initialCheckpointCreate, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            replyPolicy?.requireReplies()
            requireTestDeployment(editPolicy == null || editPolicy === replyPolicy, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            editPolicy?.requireEdits()
            val pool = registration.process.pools.ordinary
            val originalFactory = LocalContainerEntityManagerFactoryBean().also { factory = it }
            originalFactory.dataSource = pool
            originalFactory.setPackagesToScan(UserEntity::class.java.packageName, AuditLogEntity::class.java.packageName)
            originalFactory.jpaVendorAdapter = HibernateJpaVendorAdapter().apply {
                setDatabasePlatform("org.hibernate.dialect.PostgreSQLDialect")
                setGenerateDdl(false)
                setShowSql(false)
            }
            originalFactory.setJpaPropertyMap(mapOf(
                "hibernate.hbm2ddl.auto" to "validate",
                "hibernate.jdbc.time_zone" to "UTC",
                "hibernate.boot.allow_jdbc_metadata_access" to "false",
            ))
            checkpoint()
            factoryInitializing = true
            originalFactory.afterPropertiesSet() // Original guarded pool only. No migration, new DS or provider override.
            emf = checkNotNull(originalFactory.`object`)
            factoryInitialized = true
            factoryInitializing = false
            checkpoint()
            val manager = GuardedJpaTransactionManager(checkNotNull(emf), pool)
            val size = registration.process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.ORDINARY }.hikari.sizing.maximumPoolSize
            val permits = OrdinaryPersistenceAdmission(size).also { admission = it }
            val owner = PersistencePhaseOwnership(permits, manager).also { ownership = it }
            val template = JdbcTemplate(pool).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }.also { jdbc = it }
            installationBindingEntered = true
            registration.requireInstallationResources(owner, template) // One exact pair; a previous/foreign binding refuses, never rebinds.
            installationBindingReturned = true
            val repositories = JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(checkNotNull(emf)))
            val counted = JpaAuditRepositoryAdapter(repositories.getRepository(SpringDataAuditLogRepository::class.java))
            val service = AuditService(counted, CurrentUser(), Clock.systemUTC()).also { audit = it }
            val composition = if (editPolicy != null) {
                ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateReplyEdit(registration, assembly, owner, template, service)
            } else if (replyPolicy == null) {
                ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreate(registration, assembly, owner, template, service)
            } else {
                ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateReply(registration, assembly, owner, template, service)
            }
            val userKey = checkNotNull(registration.process.consumers.jwt.boundUserKeyProvider)
            val properties = KiraSecurityProperties(
                issuer = userKey.versionBoundIssuer, audience = userKey.versionBoundAudience,
                accessTokenTtl = userKey.versionBoundAccessTokenTtl, clockSkew = userKey.versionBoundClockSkew,
                trustForwardedHeaders = registration.process.consumers.trustForwardedHeaders,
                trustedProxies = registration.process.consumers.trustedProxies(),
            )
            userKey.requireMatchingConfiguration(properties)
            val mapper = ObjectMapper()
            val selected = AnnotationConfigServletWebServerApplicationContext().also { context = it }
            selected.setAllowBeanDefinitionOverriding(false)
            // Fixed original instances BEFORE conditional configuration evaluation. No Spring destroy
            // inference for borrowed pool/EMF; the retained owner below alone orders actual cleanup.
            bean(selected, "dataSource", GuardedDataSource::class.java, pool, "ordinaryDataSource")
            bean(selected, "entityManagerFactory", EntityManagerFactory::class.java, checkNotNull(emf))
            bean(selected, "transactionManager", GuardedJpaTransactionManager::class.java, manager, "ordinaryJpaTransactionManager")
            bean(selected, "ordinaryPersistenceAdmission", OrdinaryPersistenceAdmission::class.java, permits)
            bean(selected, "ordinaryPersistenceOwnership", PersistencePhaseOwnership::class.java, owner)
            bean(selected, "jdbcTemplate", JdbcTemplate::class.java, template, "ordinaryJdbcTemplate")
            bean(selected, "auditService", AuditService::class.java, service)
            bean(selected, "jwtKeyProvider", JwtKeyProvider::class.java, userKey)
            bean(selected, "kiraSecurityProperties", KiraSecurityProperties::class.java, properties)
            bean(selected, "userRepository", UserRepository::class.java, JpaUserRepositoryAdapter(repositories.getRepository(SpringDataUserRepository::class.java)))
            bean(selected, "objectMapper", ObjectMapper::class.java, mapper)
            bean(selected, "problemAuthenticationEntryPoint", ProblemAuthenticationEntryPoint::class.java, ProblemAuthenticationEntryPoint(mapper))
            bean(selected, "problemAccessDeniedHandler", ProblemAccessDeniedHandler::class.java, ProblemAccessDeniedHandler(mapper))
            bean(selected, "registeredTestBootstrapComposition", ComplaintTestBootstrapHttpCompositionV1::class.java, composition)
            selected.register(ComplaintTestRegisteredServletConfigurationV1::class.java, SecurityConfig::class.java,
                WebDiagnosticsConfig::class.java, ComplaintTestBootstrapHttpConfigurationV1::class.java)
            checkpoint()
            contextInitializing = true
            selected.refresh() // Actual embedded Tomcat/DispatcherServlet/filters, not MockMvc or a no-listen factory.
            server = selected.webServer as TomcatWebServer
            contextInitialized = true
            contextInitializing = false
            checkpoint()
            registration.requireActiveIdentityTarget(assembly)
            registration.requireInstallationResources(owner, template)
            requireTestDeployment(selected.isActive && checkNotNull(emf).isOpen, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            selectedPort = checkNotNull(server).port.also { requireTestDeployment(it in 1..65_535, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED) }
            started = true
        } catch (problem: Throwable) {
            val cleanup = runCatching { closeWithin(startupBudget) }.exceptionOrNull()
            throw boundedTestDeploymentFailure(if (cleanup == null) problem else preferCatalogFreezeCleanup(problem, cleanup), ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
        }
    }

    /** A failed/expired original close never gets a new wait or another destruction attempt. */
    override fun close() {
        requireCaller()
        if (cleanupProven) return
        if (closeEntered) throw checkNotNull(cleanupFailure)
        val budget = runCatching { if (started) PersistenceTimeBudget.start(10_000) else startupBudget }
        closeWithin(budget.getOrNull(), budget.exceptionOrNull())
    }

    /** Assembly shutdown passes its already-started original budget, not a renewed child allowance. */
    @Suppress("TooGenericExceptionCaught") // Preserve custody on every failure, including Error and interruption.
    internal fun closeWithin(budget: PersistenceTimeBudget?, timingFailure: Throwable? = null) {
        requireCaller()
        if (cleanupProven) return
        if (closeEntered) throw checkNotNull(cleanupFailure)
        cleanupFailure = unresolvedClose // Reentrant/throwing cleanup cannot expose an empty or reusable state.
        closeEntered = true
        started = false
        var failure: Throwable? = timingFailure
        fun remember(problem: Throwable) { failure = preferCatalogFreezeCleanup(failure, problem) }
        fun attempt(work: () -> Unit) { try { work() } catch (problem: Throwable) { remember(problem) } }
        try {
            ingress.stopRegisteredStartupAdmission() // Irreversible stop; not a statement that existing requests released.
            registration.close()
            awaitReleased(budget) // No EMF, server or borrowed native destruction until positive original-owner release.
        } catch (problem: Throwable) {
            remember(problem)
            cleanupFailure = boundedTestDeploymentFailure(checkNotNull(failure), ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
            throw checkNotNull(cleanupFailure)
        }
        // No clock error may skip an original close after release was positively established.
        attempt { if (budget == null) cleanupRefused() else budget.remainingMillis(1) }
        context?.let { selected ->
            attempt {
                selected.close() // All supplied persistence instances explicitly have no inferred destroy method.
                contextCloseReturned = true
                requireTestDeployment(!selected.isActive, ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
                if (contextInitialized) requireTestDeployment(server?.tomcat?.server?.state === LifecycleState.DESTROYED, ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
            }
        }
        // Stopped ingress + zero original ordinary owners is stable; no other route on this fixed
        // context can reach JPA. A context/EMF initialization that threw still stays unproved below.
        var released = false
        attempt { requireReleased(); released = true }
        if (released) {
            factory?.let { original -> attempt {
                original.destroy()
                factoryCloseReturned = true
                if (factoryInitialized) requireTestDeployment(emf?.isOpen == false, ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
            } }
        }
        attempt { if (budget == null) cleanupRefused() else budget.remainingMillis(1) }
        val initialized = !factoryInitializing && !contextInitializing
        // Each returned close's actual state was checked in its guarded attempt above. Do not
        // repeat a potentially throwing provider getter outside the retained failure boundary.
        val disposed = (factory == null || factoryCloseReturned) && (context == null || contextCloseReturned)
        // A refused bind may belong to an earlier JPA owner. Disposing our own factory cannot
        // establish that owner released; keep assembly/native custody without inspecting or rebinding it.
        val bound = !installationBindingEntered || installationBindingReturned
        if (failure == null && initialized && disposed && bound) {
            cleanupProven = true
            return
        }
        cleanupFailure = boundedTestDeploymentFailure(failure ?: ComplaintTestDeploymentExceptionV1(ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN),
            ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
        throw checkNotNull(cleanupFailure)
    }

    internal fun requireCleanupProven() {
        requireCaller()
        requireTestDeployment(cleanupProven, ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
    }

    private fun awaitReleased(budget: PersistenceTimeBudget?) {
        while (true) {
            if (ingress.registeredStartupAdmissionReleased() && admission?.activeOwners().let { it == null || it == 0 }) return
            if (Thread.currentThread().isInterrupted || budget == null) cleanupRefused()
            val millis = checkNotNull(budget).remainingMillis(5)
            LockSupport.parkNanos(millis * 1_000_000L) // Observation only, bounded by the one original close attempt.
        }
    }

    private fun requireReleased() = requireTestDeployment(
        ingress.registeredStartupAdmissionReleased() && admission?.activeOwners().let { it == null || it == 0 },
        ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN,
    )

    private fun checkpoint() {
        requireCaller()
        requireTestDeployment(!Thread.currentThread().isInterrupted, ComplaintTestDeploymentFailureV1.INTERRUPTED)
        startupBudget.remainingMillis(1)
    }

    private fun requireCaller() { requireConnectionFree(); assembly.requireRegisteredHttpStartup(this) }
    private fun cleanupRefused(): Nothing = throw ComplaintTestDeploymentExceptionV1(ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
    override fun toString(): String = "ComplaintTestRegisteredHttpStartupV1(loopback-TEST-only,redacted,no-launch-authority)"

    companion object {
        /** Inert child construction only. start/close require the assembly's exact retained identity/caller. */
        internal fun retained(assembly: ComplaintTestProcessAssemblyV1, registration: ComplaintTestNamespaceRegistrationV1): ComplaintTestRegisteredHttpStartupV1 =
            ComplaintTestRegisteredHttpStartupV1(assembly, registration)

        /** Original reply-capable full-D/pool pin, not a public readiness switch or default startup expansion. */
        internal fun retainedWithReplies(assembly: ComplaintTestProcessAssemblyV1, registration: ComplaintTestNamespaceRegistrationV1): ComplaintTestRegisteredHttpStartupV1 {
            val policy = checkNotNull(registration.process.initialCheckpointCreate)
            policy.requireReplies()
            return ComplaintTestRegisteredHttpStartupV1(assembly, registration, policy)
        }

        /** Dedicated EDIT birth policy, retaining the same original concrete policy used by its CREATE/REPLY subset. */
        internal fun retainedWithEdits(assembly: ComplaintTestProcessAssemblyV1, registration: ComplaintTestNamespaceRegistrationV1): ComplaintTestRegisteredHttpStartupV1 {
            val policy = checkNotNull(registration.process.initialCheckpointCreate)
            policy.requireEdits()
            return ComplaintTestRegisteredHttpStartupV1(assembly, registration, policy, policy)
        }
    }
}

private fun <T : Any> bean(context: AnnotationConfigServletWebServerApplicationContext, name: String, type: Class<T>, instance: T, vararg aliases: String) {
    context.registerBeanDefinition(name, RootBeanDefinition(type).apply { instanceSupplier = Supplier { instance }; destroyMethodName = "" })
    aliases.forEach { context.registerAlias(name, it) }
}

/**
 * Explicitly registered lite configuration: deliberately NO @Configuration/@Component/component scan.
 * Normal Boot startup never discovers this server. No autoconfiguration creates another DS, EMF,
 * migration provider, account key or source-app service. The extra filter only closes other paths.
 */
@EnableWebMvc
internal class ComplaintTestRegisteredServletConfigurationV1 {
    @Bean
    fun servletWebServerFactory(): TomcatServletWebServerFactory = TomcatServletWebServerFactory(0).apply {
        address = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        shutdown = Shutdown.IMMEDIATE // Original requests are positively drained BEFORE context.close().
    }

    @Bean
    fun dispatcherServlet(context: WebApplicationContext): DispatcherServlet = DispatcherServlet(context)

    @Bean
    fun dispatcherServletRegistration(dispatcher: DispatcherServlet): ServletRegistrationBean<DispatcherServlet> =
        ServletRegistrationBean(dispatcher, "/").apply { setLoadOnStartup(1); isAsyncSupported = false }

    @Bean
    fun installationSecurityFilterRegistration(): DelegatingFilterProxyRegistrationBean =
        DelegatingFilterProxyRegistrationBean("springSecurityFilterChain").apply {
            order = SecurityProperties.DEFAULT_FILTER_ORDER
            setDispatcherTypes(DispatcherType.REQUEST)
            isAsyncSupported = false
        }

    @Bean
    fun selectedTestNamespaceOnly(composition: ComplaintTestBootstrapHttpCompositionV1): FilterRegistrationBean<Filter> =
        FilterRegistrationBean<Filter>(Filter { request, response, chain ->
            val http = request as HttpServletRequest
            if (composition.mapsRequest(http)) chain.doFilter(request, response)
            else ComplaintSecurityResponses.problem(http, response as HttpServletResponse, ComplaintSecurityFailure.NOT_FOUND)
        }).apply {
            // Both this restriction and the original ingress precede body buffering. It mints no
            // context; the fixed original bridge still owns every possible persistence request.
            order = Ordered.HIGHEST_PRECEDENCE + 1
            addUrlPatterns("/*")
            isAsyncSupported = false
        }
}
