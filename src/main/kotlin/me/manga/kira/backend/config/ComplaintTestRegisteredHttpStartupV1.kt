package me.manga.kira.backend.config

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManagerFactory
import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletRequestEvent
import jakarta.servlet.ServletRequestListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import me.manga.kira.backend.KiraBackendApplication
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.infrastructure.AuditLogEntity
import me.manga.kira.backend.audit.infrastructure.JpaAuditRepositoryAdapter
import me.manga.kira.backend.audit.infrastructure.SpringDataAuditLogRepository
import me.manga.kira.backend.common.infrastructure.persistence.GuardedDataSource
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJdbcTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJpaTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.OrdinaryPersistenceAdmission
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipantRole
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteProcessBindingV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentFailureV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.boundedTestDeploymentFailure
import me.manga.kira.backend.complaint.infrastructure.admission.requireTestDeployment
import me.manga.kira.backend.complaint.infrastructure.catalog.preferCatalogFreezeCleanup
import me.manga.kira.backend.complaint.infrastructure.journal.TestAdminDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteAllJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestInitialCheckpointDeletionV1
import me.manga.kira.backend.complaint.infrastructure.transaction.DeletionPersistenceAdmission
import me.manga.kira.backend.completion.application.CompletionService
import me.manga.kira.backend.security.AuthThrottle
import me.manga.kira.backend.security.AuthThrottleService
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
import org.apache.catalina.core.StandardContext
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.ApplicationContextFactory
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer
import org.springframework.boot.web.server.Shutdown
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.core.PriorityOrdered
import org.springframework.core.env.MapPropertySource
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.DispatcherServlet
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.net.InetAddress
import java.time.Clock
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.locks.LockSupport
import java.util.function.Supplier
import javax.sql.DataSource

/**
 * A concrete original-JPA and real loopback servlet startup, not a caller-supplied graph or registrar.
 * Obtain from the original assembly BEFORE calling start(). The assembly retains this one child even
 * when initialization/closure does not return; it cannot close native pools/trust under unproved users.
 * Only the complete selector adds the existing normal application to these exact borrowed resources.
 * Older selectors remain narrow; neither path supplies LIVE routes or launch qualification.
 */
internal class ComplaintTestRegisteredHttpStartupV1 private constructor(
    private val assembly: ComplaintTestProcessAssemblyV1,
    private val registration: ComplaintTestNamespaceRegistrationV1,
    private val replyPolicy: me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestInitialCheckpointCreateV1? = null,
    private val editPolicy: me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestInitialCheckpointCreateV1? = null,
    private val selectMe: Boolean = false, // Private route choice only; every original registration/owner check still applies.
    private val selectAdminReads: Boolean = false, // Private route selection, never born-with policy or current authority.
    private val selectAdminContent: Boolean = false,
    private val selectAdminStatus: Boolean = false,
    private val selectAdminBatchStatus: Boolean = false,
    private val deletionPolicy: VersionBoundTestInitialCheckpointDeletionV1? = null,
    private val selectOwnerDeleteAll: Boolean = false, // Explicit route sibling, not a second policy or deletion owner.
    private val selectComplete: Boolean = false, // One fixed owner/Admin cohort, never a family-by-family public switch.
) : AutoCloseable {
    private val startupBudget = PersistenceTimeBudget.start(60_000)
    private val ingress = registration.process.consumers.ingressAdmission
    private val ordinaryHttp = RegisteredOrdinaryHttpLifetimeV1()
    private val ordinaryProducers = RegisteredOrdinaryProducersV1()
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
    private var deletionAdmission: DeletionPersistenceAdmission? = null
    private var deletionManager: GuardedJdbcTransactionManager? = null
    private var deletionOwnership: PersistencePhaseOwnership? = null
    private var deletionJdbc: JdbcTemplate? = null
    private var deletionBindingEntered = false
    private var deletionBindingReturned = false
    private var ownerDeleteBinding: TestOwnerDeleteProcessBindingV1? = null
    private var ownerDeletePublisher: TestOwnerDeleteJournalPublisherFactoryV1? = null
    private var ownerDeletePublisherCloseReturned = false
    private var ownerDeleteAllPublisher: TestOwnerDeleteAllJournalPublisherFactoryV1? = null
    private var ownerDeleteAllPublisherCloseReturned = false
    private var ownerDeleteResources: TestOwnerDeleteProcessBindingV1.OwnerHttpResources? = null
    private var ownerDeleteAllResources: TestOwnerDeleteProcessBindingV1.OwnerDeleteAllHttpResources? = null
    private var adminDeleteResources: TestOwnerDeleteProcessBindingV1.AdminHttpResources? = null
    private var adminDeletePublisher: TestAdminDeleteJournalPublisherFactoryV1? = null
    private var adminDeletePublisherCloseReturned = false
    private var audit: AuditService? = null
    private var adminReadDecoder: JwtDecoder? = null
    private var adminReadCompositionClaimed = false
    private var adminContentPasswords: PasswordEncoder? = null
    private var adminContentCompositionClaimed = false
    private var adminStatusCompositionClaimed = false
    private var adminBatchStatusCompositionClaimed = false
    private var adminDeleteCompositionClaimed = false
    private var context: AnnotationConfigServletWebServerApplicationContext? = null
    private var contextInitializing = false
    private var contextInitialized = false
    private var contextCloseReturned = false
    private var server: TomcatWebServer? = null
    private var selectedPort: Int? = null
    private var sourceStepUpResourcesClaimed = false
    private var selectedSigningInputs: KiraSigningProperties? = null

    /** Listener location only, never current-state/receipt/namespace authority. */
    val localPort: Int
        get() {
            requireCaller()
            requireTestDeployment(started && !closeEntered && context?.isActive == true, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            registration.requireActiveIdentityTarget(assembly)
            return checkNotNull(selectedPort)
        }

    @Suppress("TooGenericExceptionCaught") // Keep exact owners before sanitizing any failed/unreturned startup.
    fun start(sourceSigning: KiraSigningProperties? = null, normalProperties: Map<String, String> = emptyMap()) {
        requireCaller()
        requireTestDeployment(!startEntered && !closeEntered, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
        startEntered = true
        try {
            requireTestDeployment(selectComplete || (sourceSigning == null && normalProperties.isEmpty()), ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            val normalInputs = sharedConfigurationInputs(sourceSigning, normalProperties)
            checkpoint()
            registration.requireActiveIdentityTarget(assembly)
            requireTestDeployment(registration.process.initialCheckpointCreate != null, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            requireTestDeployment(replyPolicy == null || replyPolicy === registration.process.initialCheckpointCreate, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            replyPolicy?.requireReplies()
            requireTestDeployment(editPolicy == null || editPolicy === replyPolicy, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            editPolicy?.requireEdits()
            requireTestDeployment(!selectMe || (replyPolicy == null && editPolicy == null) ||
                (replyPolicy != null && editPolicy === replyPolicy), ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            requireTestDeployment(!selectAdminReads || ((selectComplete || (!selectMe && replyPolicy == null && editPolicy == null)) &&
                registration.process.consumers.adminReadPolicy is me.manga.kira.backend.security.ComplaintAdminReadAdmissionPolicy.Bounded &&
                registration.process.consumers.adminCursorCodec != null), ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            requireTestDeployment(!selectAdminContent || (selectAdminReads &&
                registration.process.consumers.adminContentPolicy is me.manga.kira.backend.security.ComplaintAdminContentAdmissionPolicy.Bounded &&
                registration.process.consumers.adminStepUp != null), ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            requireTestDeployment(!selectAdminStatus || (selectAdminContent &&
                registration.process.consumers.adminStatusPolicy is me.manga.kira.backend.security.ComplaintAdminStatusAdmissionPolicy.Bounded),
                ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            requireTestDeployment(!selectAdminBatchStatus || (selectAdminStatus &&
                registration.process.consumers.adminBatchStatusPolicy is me.manga.kira.backend.security.ComplaintAdminBatchStatusAdmissionPolicy.Bounded),
                ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            requireTestDeployment(deletionPolicy == null || (deletionPolicy === registration.process.initialCheckpointDeletion &&
                (selectComplete || (!selectMe && replyPolicy == null && editPolicy == null && !selectAdminReads))), ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            requireTestDeployment(!selectOwnerDeleteAll || deletionPolicy != null, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            if (selectComplete) {
                requireTestDeployment(selectMe && replyPolicy != null && editPolicy === replyPolicy && selectAdminReads &&
                    selectAdminContent && selectAdminStatus && selectAdminBatchStatus && deletionPolicy != null && selectOwnerDeleteAll,
                    ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
                requireCompleteDeclarations(registration)
            }
            val pool = registration.process.pools.ordinary
            val originalFactory = LocalContainerEntityManagerFactoryBean().also { factory = it }
            originalFactory.dataSource = pool
            if (selectComplete) originalFactory.setPackagesToScan(KiraBackendApplication::class.java.packageName)
            else originalFactory.setPackagesToScan(UserEntity::class.java.packageName, AuditLogEntity::class.java.packageName)
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
            // The narrow graph needs only counted complaint audit insertion. The complete graph
            // resolves the real scanned AuditService below: normal auth audit writes also require
            // Spring Data's ordinary transactional repository advice, not a raw repository factory.
            val repositories = if (selectComplete) null else JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(checkNotNull(emf)))
            val narrowAudit = repositories?.let {
                val counted = JpaAuditRepositoryAdapter(it.getRepository(SpringDataAuditLogRepository::class.java))
                AuditService(counted, CurrentUser(), Clock.systemUTC()).also { original -> audit = original }
            }
            val composition = if (deletionPolicy != null) {
                val deletionPool = registration.process.pools.deletion
                // Prepare the original cold sibling before registration can pin its deletion pair.
                checkpoint()
                requireTestDeployment(deletionPool.prepareDeletion() === PersistenceLifecycleObservation.READY, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
                checkpoint()
                val deletionPermits = DeletionPersistenceAdmission().also { deletionAdmission = it }
                val deletionTx = GuardedJdbcTransactionManager(deletionPool).also { deletionManager = it }
                val deletionOwner = PersistencePhaseOwnership.deletion(deletionPermits, deletionTx).also { deletionOwnership = it }
                val deletionTemplate = JdbcTemplate(deletionPool).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }.also { deletionJdbc = it }
                deletionBindingEntered = true
                val binding = TestOwnerDeleteProcessBindingV1.fromRegistered(registration, assembly, owner, template, deletionOwner, deletionTemplate)
                    .also { ownerDeleteBinding = it; deletionBindingReturned = true }
                if (selectComplete) {
                    null // Resolve the normal audit/decoder/password beans during this exact refresh.
                } else {
                    val service = checkNotNull(narrowAudit)
                    val resources = binding.ownerHttpResources(service)
                    val publisher = resources.publisher().also { ownerDeletePublisher = it }
                    checkpoint()
                    if (selectOwnerDeleteAll) {
                        val allResources = binding.ownerDeleteAllHttpResources(service)
                        val allPublisher = allResources.publisher().also { ownerDeleteAllPublisher = it }
                        checkpoint()
                        ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateOwnerDeleteAll(
                            registration, assembly, owner, template, service, resources, publisher, allResources, allPublisher)
                    } else ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateOwnerDelete(
                        registration, assembly, owner, template, service, resources, publisher)
                }
            } else if (selectAdminReads) {
                null // The new concrete supplier resolves the original configured user decoder only during refresh.
            } else {
                val service = checkNotNull(narrowAudit)
                if (selectMe && editPolicy != null) {
                    ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateMeReplyEdit(registration, assembly, owner, template, service)
                } else if (selectMe) {
                    ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateMe(registration, assembly, owner, template, service)
                } else if (editPolicy != null) {
                    ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateReplyEdit(registration, assembly, owner, template, service)
                } else if (replyPolicy == null) {
                    ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreate(registration, assembly, owner, template, service)
                } else ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateReply(registration, assembly, owner, template, service)
            }
            val userKey = checkNotNull(registration.process.consumers.jwt.boundUserKeyProvider)
            val properties = KiraSecurityProperties(
                issuer = userKey.versionBoundIssuer, audience = userKey.versionBoundAudience,
                accessTokenTtl = userKey.versionBoundAccessTokenTtl, clockSkew = userKey.versionBoundClockSkew,
                trustForwardedHeaders = registration.process.consumers.trustForwardedHeaders,
                trustedProxies = registration.process.consumers.trustedProxies(),
            )
            userKey.requireMatchingConfiguration(properties)
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
            bean(selected, "jwtKeyProvider", JwtKeyProvider::class.java, userKey)
            if (selectComplete) {
                // Presence means this privately retained original, never arbitrary DataSource back-off.
                bean(selected, "registeredCompleteHttpStartup", ComplaintTestRegisteredHttpStartupV1::class.java, this)
                bean(selected, "complaintDeletionDataSource", GuardedDataSource::class.java, registration.process.pools.deletion)
                bean(selected, "complaintDeletionTransactionManager", GuardedJdbcTransactionManager::class.java, checkNotNull(deletionManager))
                bean(selected, "complaintDeletionJdbcTemplate", JdbcTemplate::class.java, checkNotNull(deletionJdbc))
                bean(selected, "authThrottleService", AuthThrottleService::class.java, checkNotNull(registration.process.consumers.adminStepUp).throttle)
                bean(selected, "registeredOrdinaryProducerRetention", RegisteredOrdinaryProducersV1::class.java, ordinaryProducers)
                selected.environment.propertySources.addFirst(MapPropertySource("registered-normal-inputs", normalInputs))
                selected.environment.propertySources.addFirst(MapPropertySource("registered-owned-boundaries", SHARED_OWNED_PROPERTIES))
            } else {
                val mapper = ObjectMapper()
                bean(selected, "auditService", AuditService::class.java, checkNotNull(narrowAudit))
                bean(selected, "kiraSecurityProperties", KiraSecurityProperties::class.java, properties)
                bean(selected, "userRepository", UserRepository::class.java, JpaUserRepositoryAdapter(checkNotNull(repositories).getRepository(SpringDataUserRepository::class.java)))
                bean(selected, "objectMapper", ObjectMapper::class.java, mapper)
                bean(selected, "problemAuthenticationEntryPoint", ProblemAuthenticationEntryPoint::class.java, ProblemAuthenticationEntryPoint(mapper))
                bean(selected, "problemAccessDeniedHandler", ProblemAccessDeniedHandler::class.java, ProblemAccessDeniedHandler(mapper))
            }
            if (selectAdminReads) {
                // One internal fixed bean recipe, retained by this context before refresh/any listening server.
                // No callback supplied by callers, decoder replacement, request-time lookup or independent graph.
                selected.registerBeanDefinition("registeredTestBootstrapComposition", RootBeanDefinition(ComplaintTestBootstrapHttpCompositionV1::class.java).apply {
                    if (selectAdminContent) setDependsOn("jwtDecoder", "passwordEncoder") else setDependsOn("jwtDecoder")
                    instanceSupplier = Supplier {
                        checkpoint()
                        requireTestDeployment(adminReadDecoder == null && !adminReadCompositionClaimed, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
                        val decoder = selected.getBean("jwtDecoder", JwtDecoder::class.java).also { adminReadDecoder = it }
                        val service = if (selectComplete) {
                            requireSharedRefresh()
                            requireTestDeployment(audit == null, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
                            selected.getBean(AuditService::class.java).also { audit = it }
                        } else checkNotNull(narrowAudit)
                        if (selectAdminContent) {
                            requireTestDeployment(adminContentPasswords == null && !adminContentCompositionClaimed, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
                            val passwords = selected.getBean("passwordEncoder", PasswordEncoder::class.java).also { adminContentPasswords = it }
                            if (selectComplete) {
                                // Original pairs were registered before resolving the normal service.
                                // This filter dependency completes before listener admission; retain
                                // each returned child before any next possibly throwing construction.
                                val binding = checkNotNull(ownerDeleteBinding)
                                val resources = binding.ownerHttpResources(service).also { ownerDeleteResources = it }
                                val publisher = resources.publisher().also { ownerDeletePublisher = it }
                                checkpoint()
                                val allResources = binding.ownerDeleteAllHttpResources(service).also { ownerDeleteAllResources = it }
                                val allPublisher = allResources.publisher().also { ownerDeleteAllPublisher = it }
                                checkpoint()
                                val adminResources = binding.adminHttpResources(service).also { adminDeleteResources = it }
                                val adminPublisher = adminResources.publisher().also { adminDeletePublisher = it }
                                checkpoint()
                                ComplaintTestBootstrapHttpCompositionV1.fromRegisteredComplete(
                                    registration, assembly, owner, template, service, this@ComplaintTestRegisteredHttpStartupV1, decoder, passwords,
                                    resources, publisher, allResources, allPublisher, adminResources, adminPublisher,
                                )
                            } else if (selectAdminBatchStatus) ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateAdminReadContentStatusBatchStatus(
                                registration, assembly, owner, template, service, this@ComplaintTestRegisteredHttpStartupV1, decoder, passwords,
                            ) else if (selectAdminStatus) ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateAdminReadContentStatus(
                                registration, assembly, owner, template, service, this@ComplaintTestRegisteredHttpStartupV1, decoder, passwords,
                            ) else ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateAdminReadContent(
                                registration, assembly, owner, template, service, this@ComplaintTestRegisteredHttpStartupV1, decoder, passwords,
                            )
                        } else ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateAdminRead(
                            registration, assembly, owner, template, service, this@ComplaintTestRegisteredHttpStartupV1, decoder,
                        )
                    }
                    destroyMethodName = ""
                })
            } else bean(selected, "registeredTestBootstrapComposition", ComplaintTestBootstrapHttpCompositionV1::class.java, checkNotNull(composition))
            checkpoint()
            contextInitializing = true
            if (selectComplete) {
                val application = SpringApplication(KiraBackendApplication::class.java).apply {
                    webApplicationType = WebApplicationType.SERVLET
                    setApplicationContextFactory(ApplicationContextFactory { selected })
                    setEnvironment(selected.environment)
                    setRegisterShutdownHook(false)
                    setAllowBeanDefinitionOverriding(false)
                    setLogStartupInfo(false)
                    addInitializers(ApplicationContextInitializer<AnnotationConfigServletWebServerApplicationContext> { actual ->
                        requireTestDeployment(actual === selected, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
                        requireTestDeployment(getAllSources() == setOf(KiraBackendApplication::class.java), ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
                        validateSharedConfiguration(actual)
                    })
                }
                requireTestDeployment(application.run() === selected, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            } else {
                selected.register(ComplaintTestRegisteredServletConfigurationV1::class.java, SecurityConfig::class.java,
                    WebDiagnosticsConfig::class.java, ComplaintTestBootstrapHttpConfigurationV1::class.java)
                selected.refresh() // Same narrow embedded listener, not MockMvc or a no-listen factory.
            }
            server = selected.webServer as TomcatWebServer
            contextInitialized = true
            contextInitializing = false
            checkpoint()
            registration.requireActiveIdentityTarget(assembly)
            registration.requireInstallationResources(owner, template)
            ownerDeleteBinding?.let {
                it.requireGraph(it.lower)
                registration.requireInitialDeletionPhaseResources(checkNotNull(deletionOwnership), checkNotNull(deletionJdbc))
            }
            requireTestDeployment(selected.isActive && checkNotNull(emf).isOpen, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            selectedPort = checkNotNull(server).port.also { requireTestDeployment(it in 1..65_535, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED) }
            if (selectComplete) {
                requireTestDeployment(sourceStepUpResourcesClaimed, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
                validateSharedConfiguration(selected)
                requireTestDeployment(selected.getBean(JwtKeyProvider::class.java) === userKey &&
                    selected.getBean(AuthThrottle::class.java) === checkNotNull(registration.process.consumers.adminStepUp).throttle,
                    ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
                val installedPools = selected.getBeansOfType(DataSource::class.java).values
                val installedFactories = selected.getBeansOfType(EntityManagerFactory::class.java).values
                requireTestDeployment(installedPools.size == 2 && installedPools.all { it === pool || it === registration.process.pools.deletion } &&
                    installedFactories.size == 1 && installedFactories.single() === emf,
                    ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
                ordinaryProducers.requireReady(selected)
                val tomcatContext = checkNotNull(server).tomcat.host.findChildren().filterIsInstance<StandardContext>().single()
                requireTestDeployment(!tomcatContext.fireRequestListenersOnForwards &&
                    tomcatContext.applicationEventListeners.filterIsInstance<ServletRequestListener>().firstOrNull() === ordinaryHttp,
                    ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
                ordinaryHttp.open() // Only after normal runners, actual listener shape and original identities passed.
            }
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
            ordinaryHttp.stop() // Same locked decision as new shared-endpoint admission.
            ingress.stopRegisteredStartupAdmission() // Irreversible stop; not a statement that existing requests released.
            registration.close()
            if (selectComplete) ordinaryProducers.stop() // Shutdown requests are not the termination proof below.
            awaitReleased(budget) // No EMF, server or borrowed native destruction until positive original-owner release.
        } catch (problem: Throwable) {
            remember(problem)
            cleanupFailure = boundedTestDeploymentFailure(checkNotNull(failure), ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
            throw checkNotNull(cleanupFailure)
        }
        // No clock error may skip an original close after release was positively established.
        attempt { if (budget == null) cleanupRefused() else budget.remainingMillis(1) }
        ownerDeletePublisher?.let { original -> attempt {
            original.close()
            requireTestDeployment(original.isClosed() && registration.process.publicationLanes.activeOwners().totalOwners == 0L,
                ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
            ownerDeletePublisherCloseReturned = true
        } }
        ownerDeleteAllPublisher?.let { original -> attempt {
            original.close()
            requireTestDeployment(original.isClosed() && registration.process.publicationLanes.activeOwners().totalOwners == 0L,
                ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
            ownerDeleteAllPublisherCloseReturned = true
        } }
        adminDeletePublisher?.let { original -> attempt {
            original.close()
            requireTestDeployment(original.isClosed() && registration.process.publicationLanes.activeOwners().totalOwners == 0L,
                ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
            adminDeletePublisherCloseReturned = true
        } }
        context?.let { selected ->
            attempt {
                selected.close() // All supplied persistence instances explicitly have no inferred destroy method.
                contextCloseReturned = true
                requireTestDeployment(!selected.isActive, ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
                if (contextInitialized) requireTestDeployment(server?.tomcat?.server?.state === LifecycleState.DESTROYED, ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
            }
        }
        // Stopped ingress + revoked registration + positive original SQL/native release, not a
        // standalone lane snapshot. A context/EMF initialization that threw stays unproved below.
        var released = false
        attempt {
            requireReleased()
            requireTestDeployment(ownerDeletePublisher == null || ownerDeletePublisherCloseReturned, ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
            requireTestDeployment(ownerDeleteAllPublisher == null || ownerDeleteAllPublisherCloseReturned, ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
            requireTestDeployment(adminDeletePublisher == null || adminDeletePublisherCloseReturned, ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
            released = true
        }
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
        val disposed = (factory == null || factoryCloseReturned) && (context == null || contextCloseReturned) &&
            (ownerDeletePublisher == null || ownerDeletePublisherCloseReturned) &&
            (ownerDeleteAllPublisher == null || ownerDeleteAllPublisherCloseReturned) &&
            (adminDeletePublisher == null || adminDeletePublisherCloseReturned)
        // A refused bind may belong to an earlier JPA owner. Disposing our own factory cannot
        // establish that owner released; keep assembly/native custody without inspecting or rebinding it.
        val bound = (!installationBindingEntered || installationBindingReturned) && (!deletionBindingEntered || deletionBindingReturned)
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

    /** Diagnostic counts only. Observers receive neither admission nor a cleanup/registration receipt. */
    internal fun ordinaryHttpObservation(): OrdinaryHttpObservation = ordinaryHttp.observation()

    internal fun ordinaryHttpFilter(): Filter {
        requireSharedRefresh()
        return ordinaryHttp.filter
    }

    internal fun ordinaryHttpListener(): ServletRequestListener {
        requireSharedRefresh()
        return ordinaryHttp
    }

    /** The source issuer uses the normal recipe, but only the original ordinary resources/throttle. */
    internal fun claimSourceStepUpResources(originalOwnership: PersistencePhaseOwnership, originalJdbc: JdbcTemplate,
        properties: KiraAdminStudioProperties, passwords: PasswordEncoder, throttle: AuthThrottle) {
        requireSharedRefresh()
        val selected = checkNotNull(context)
        val bound = checkNotNull(registration.process.consumers.adminStepUp)
        requireTestDeployment(!sourceStepUpResourcesClaimed && ownership === originalOwnership && jdbc === originalJdbc &&
            throttle === bound.throttle && properties == bound.properties &&
            selected.getBean(PasswordEncoder::class.java) === passwords,
            ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
        registration.requireIdentityAdmissionPhaseResources(originalOwnership, originalJdbc)
        requireSharedSecurity(selected.getBean(KiraSecurityProperties::class.java))
        sourceStepUpResourcesClaimed = true
    }

    private fun requireSharedRefresh() {
        requireCaller()
        requireTestDeployment(selectComplete && startEntered && !closeEntered && contextInitializing, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
        registration.requireActiveIdentityTarget(assembly)
        registration.requireInstallationResources(checkNotNull(ownership), checkNotNull(jdbc))
    }

    /** ConfigData/Binder, not another properties-bean supplier or another signing/key provider. */
    private fun validateSharedConfiguration(selected: AnnotationConfigServletWebServerApplicationContext) {
        requireCaller()
        requireTestDeployment(selectComplete && selected === context && !closeEntered, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
        val binder = Binder.get(selected.environment)
        requireSharedSecurity(binder.bindOrCreate("kira.security", KiraSecurityProperties::class.java))
        requireTestDeployment(binder.bindOrCreate("kira.admin-studio", KiraAdminStudioProperties::class.java) ==
            checkNotNull(registration.process.consumers.adminStepUp).properties, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
        selectedSigningInputs?.let { expected ->
            requireTestDeployment(binder.bindOrCreate("kira.signing", KiraSigningProperties::class.java) == expected,
                ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
        }
        SHARED_OWNED_PROPERTIES.forEach { (name, value) ->
            requireTestDeployment(selected.environment.getProperty(name) == value, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
        }
    }

    private fun requireSharedSecurity(properties: KiraSecurityProperties) {
        val consumers = registration.process.consumers
        checkNotNull(consumers.jwt.boundUserKeyProvider).requireMatchingConfiguration(properties)
        requireTestDeployment(properties.trustForwardedHeaders == consumers.trustForwardedHeaders &&
            properties.trustedProxies == consumers.trustedProxies() && properties.throttle == checkNotNull(consumers.adminStepUp).throttleSettings,
            ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
    }

    private fun sharedConfigurationInputs(signing: KiraSigningProperties?, supplied: Map<String, String>): Map<String, Any> {
        val inputs = LinkedHashMap<String, Any>()
        supplied.forEach { (name, value) -> inputs[name] = value }
        SHARED_OWNED_PROPERTIES.forEach { (name, value) ->
            requireTestDeployment(inputs[name] == null || inputs[name] == value, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
        }
        if (signing != null) {
            requireTestDeployment(inputs.keys.none { it.startsWith("kira.signing.") }, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            val snapshot = signing.copy(verificationKeys = signing.verificationKeys.map { it.copy() }).also { selectedSigningInputs = it }
            inputs["kira.signing.enabled"] = snapshot.enabled.toString()
            inputs["kira.signing.active-key-id"] = snapshot.activeKeyId ?: ""
            inputs["kira.signing.private-key"] = snapshot.privateKey ?: ""
            snapshot.verificationKeys.forEachIndexed { index, key ->
                inputs["kira.signing.verification-keys[$index].key-id"] = key.keyId
                inputs["kira.signing.verification-keys[$index].public-key"] = key.publicKey
            }
        }
        return Collections.unmodifiableMap(inputs)
    }

    /** One fixed-refresh construction claim, not a transferable decoder/read authority. */
    internal fun claimAdminReadComposition(
        originalRegistration: ComplaintTestNamespaceRegistrationV1,
        originalAssembly: ComplaintTestProcessAssemblyV1,
        originalOwnership: PersistencePhaseOwnership,
        originalJdbc: JdbcTemplate,
        originalDecoder: JwtDecoder,
    ) {
        requireCaller()
        requireTestDeployment(selectAdminReads && startEntered && !closeEntered && contextInitializing &&
            !adminReadCompositionClaimed && registration === originalRegistration && assembly === originalAssembly &&
            ownership === originalOwnership && jdbc === originalJdbc && adminReadDecoder === originalDecoder,
            ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
        registration.requireActiveIdentityTarget(assembly)
        registration.requireIdentityAdmissionPhaseResources(originalOwnership, originalJdbc)
        adminReadCompositionClaimed = true
    }

    /** This exact refresh's configured decoder AND password encoder; never a caller-substituted provider. */
    internal fun claimAdminContentComposition(
        originalRegistration: ComplaintTestNamespaceRegistrationV1,
        originalAssembly: ComplaintTestProcessAssemblyV1,
        originalOwnership: PersistencePhaseOwnership,
        originalJdbc: JdbcTemplate,
        originalDecoder: JwtDecoder,
        originalPasswords: PasswordEncoder,
    ) {
        requireCaller()
        requireTestDeployment(selectAdminContent && startEntered && !closeEntered && contextInitializing &&
            adminReadCompositionClaimed && !adminContentCompositionClaimed && registration === originalRegistration && assembly === originalAssembly &&
            ownership === originalOwnership && jdbc === originalJdbc && adminReadDecoder === originalDecoder && adminContentPasswords === originalPasswords,
            ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
        registration.requireActiveIdentityTarget(assembly)
        registration.requireIdentityAdmissionPhaseResources(originalOwnership, originalJdbc)
        adminContentCompositionClaimed = true
    }

    /** Additive status selection of this same refresh and issuer owner; not a second issuer claim. */
    internal fun claimAdminStatusComposition(
        originalRegistration: ComplaintTestNamespaceRegistrationV1,
        originalAssembly: ComplaintTestProcessAssemblyV1,
        originalOwnership: PersistencePhaseOwnership,
        originalJdbc: JdbcTemplate,
        originalDecoder: JwtDecoder,
        originalPasswords: PasswordEncoder,
    ) {
        requireCaller()
        requireTestDeployment(selectAdminStatus && selectAdminContent && startEntered && !closeEntered && contextInitializing &&
            adminContentCompositionClaimed && !adminStatusCompositionClaimed && registration === originalRegistration && assembly === originalAssembly &&
            ownership === originalOwnership && jdbc === originalJdbc && adminReadDecoder === originalDecoder && adminContentPasswords === originalPasswords,
            ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
        registration.requireActiveIdentityTarget(assembly)
        registration.requireIdentityAdmissionPhaseResources(originalOwnership, originalJdbc)
        adminStatusCompositionClaimed = true
    }

    /** One additional same-refresh choice, retaining the already-claimed content/status issuer and owners. */
    internal fun claimAdminBatchStatusComposition(
        originalRegistration: ComplaintTestNamespaceRegistrationV1,
        originalAssembly: ComplaintTestProcessAssemblyV1,
        originalOwnership: PersistencePhaseOwnership,
        originalJdbc: JdbcTemplate,
        originalDecoder: JwtDecoder,
        originalPasswords: PasswordEncoder,
    ) {
        requireCaller()
        requireTestDeployment(selectAdminBatchStatus && selectAdminStatus && selectAdminContent && startEntered && !closeEntered && contextInitializing &&
            adminContentCompositionClaimed && adminStatusCompositionClaimed && !adminBatchStatusCompositionClaimed &&
            registration === originalRegistration && assembly === originalAssembly && ownership === originalOwnership && jdbc === originalJdbc &&
            adminReadDecoder === originalDecoder && adminContentPasswords === originalPasswords, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
        registration.requireActiveIdentityTarget(assembly)
        registration.requireIdentityAdmissionPhaseResources(originalOwnership, originalJdbc)
        adminBatchStatusCompositionClaimed = true
    }

    /** Both deletion routes belong to this same refresh, issuer and already-retained original deletion resources. */
    internal fun claimAdminDeleteComposition(
        originalRegistration: ComplaintTestNamespaceRegistrationV1,
        originalAssembly: ComplaintTestProcessAssemblyV1,
        originalOwnership: PersistencePhaseOwnership,
        originalJdbc: JdbcTemplate,
        originalDecoder: JwtDecoder,
        originalPasswords: PasswordEncoder,
        originalOwnerResources: TestOwnerDeleteProcessBindingV1.OwnerHttpResources,
        originalAdminResources: TestOwnerDeleteProcessBindingV1.AdminHttpResources,
        originalPublisher: TestAdminDeleteJournalPublisherFactoryV1,
    ) {
        requireCaller()
        requireTestDeployment(selectComplete && startEntered && !closeEntered && contextInitializing &&
            adminReadCompositionClaimed && adminContentCompositionClaimed && adminStatusCompositionClaimed &&
            adminBatchStatusCompositionClaimed && !adminDeleteCompositionClaimed &&
            registration === originalRegistration && assembly === originalAssembly && ownership === originalOwnership && jdbc === originalJdbc &&
            adminReadDecoder === originalDecoder && adminContentPasswords === originalPasswords &&
            ownerDeleteResources === originalOwnerResources && adminDeleteResources === originalAdminResources && adminDeletePublisher === originalPublisher,
            ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
        registration.requireActiveIdentityTarget(assembly)
        registration.requireIdentityAdmissionPhaseResources(originalOwnership, originalJdbc)
        registration.requireInitialDeletionPhaseResources(checkNotNull(deletionOwnership), checkNotNull(deletionJdbc))
        adminDeleteCompositionClaimed = true
    }

    private fun awaitReleased(budget: PersistenceTimeBudget?) {
        while (true) {
            if (originalOwnersReleased()) return
            if (Thread.currentThread().isInterrupted || budget == null) cleanupRefused()
            val millis = checkNotNull(budget).remainingMillis(5)
            LockSupport.parkNanos(millis * 1_000_000L) // Observation only, bounded by the one original close attempt.
        }
    }

    private fun requireReleased() = requireTestDeployment(
        originalOwnersReleased(),
        ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN,
    )

    /** Called only after original ingress stop and registration revocation, never a public quiescence receipt. */
    private fun originalOwnersReleased(): Boolean =
        ingress.registeredStartupAdmissionReleased() && admission?.activeOwners().let { it == null || it == 0 } &&
            deletionAdmission?.activeOwners()?.totalOwners.let { it == null || it == 0 } &&
            (deletionPolicy == null || registration.process.publicationLanes.activeOwners().totalOwners == 0L) &&
            (!selectComplete || (ordinaryHttp.released() && ordinaryProducers.released()))

    private fun checkpoint() {
        requireCaller()
        requireTestDeployment(!Thread.currentThread().isInterrupted, ComplaintTestDeploymentFailureV1.INTERRUPTED)
        startupBudget.remainingMillis(1)
    }

    private fun requireCaller() { requireConnectionFree(); assembly.requireRegisteredHttpStartup(this) }
    private fun cleanupRefused(): Nothing = throw ComplaintTestDeploymentExceptionV1(ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN)
    override fun toString(): String = "ComplaintTestRegisteredHttpStartupV1(loopback-TEST-only,redacted,no-launch-authority)"

    companion object {
        // These are custody restrictions of this retained TEST listener, not alternate normal-service
        // defaults. ConfigData still supplies every ordinary feature/property. No second migrations,
        // management listener, pool/key factory, framework shutdown hook or unowned virtual executor.
        private val SHARED_OWNED_PROPERTIES: Map<String, Any> = Collections.unmodifiableMap(mapOf(
            "spring.main.web-application-type" to "servlet",
            "spring.main.sources" to "", // The constructor supplies the sole primary Class source.
            "spring.main.allow-bean-definition-overriding" to "false",
            "spring.main.register-shutdown-hook" to "false",
            "spring.main.lazy-initialization" to "false",
            "spring.jpa.open-in-view" to "false",
            "spring.jpa.hibernate.ddl-auto" to "validate",
            "spring.jpa.generate-ddl" to "false",
            "spring.flyway.enabled" to "false",
            "spring.sql.init.mode" to "never",
            "spring.jmx.enabled" to "false",
            "spring.threads.virtual.enabled" to "false",
            "spring.mvc.pathmatch.matching-strategy" to "path-pattern-parser",
            "server.address" to "127.0.0.1",
            "server.port" to "0",
            "server.ssl.enabled" to "false",
            "server.servlet.context-path" to "",
            "server.forward-headers-strategy" to "none",
            "server.tomcat.remoteip.remote-ip-header" to "",
            "server.tomcat.remoteip.protocol-header" to "",
            "server.shutdown" to "immediate",
            "management.server.port" to "0",
        ))

        /** Inert child construction only. start/close require the assembly's exact retained identity/caller. */
        internal fun retained(assembly: ComplaintTestProcessAssemblyV1, registration: ComplaintTestNamespaceRegistrationV1): ComplaintTestRegisteredHttpStartupV1 =
            ComplaintTestRegisteredHttpStartupV1(assembly, registration)

        /** Explicit read-only projection selection, never a new birth policy, readiness grant or default mount. */
        internal fun retainedWithMe(assembly: ComplaintTestProcessAssemblyV1, registration: ComplaintTestNamespaceRegistrationV1): ComplaintTestRegisteredHttpStartupV1 =
            ComplaintTestRegisteredHttpStartupV1(assembly, registration, selectMe = true)

        /** Existing deletion birth policy and original publisher only; no new declaration or default route expansion. */
        internal fun retainedWithOwnerDelete(assembly: ComplaintTestProcessAssemblyV1, registration: ComplaintTestNamespaceRegistrationV1): ComplaintTestRegisteredHttpStartupV1 {
            val policy = checkNotNull(registration.process.initialCheckpointDeletion)
            requireTestDeployment(registration.process.initialCheckpointCreate != null, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            return ComplaintTestRegisteredHttpStartupV1(assembly, registration, deletionPolicy = policy)
        }

        /** Body-secret ALL plus the unchanged owner cohort, sharing the original deletion binding and native lanes. */
        internal fun retainedWithOwnerDeleteAll(assembly: ComplaintTestProcessAssemblyV1, registration: ComplaintTestNamespaceRegistrationV1): ComplaintTestRegisteredHttpStartupV1 {
            val policy = checkNotNull(registration.process.initialCheckpointDeletion)
            requireTestDeployment(registration.process.initialCheckpointCreate != null, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            return ComplaintTestRegisteredHttpStartupV1(assembly, registration, deletionPolicy = policy, selectOwnerDeleteAll = true)
        }

        /** One intended coherent listener. Every required family was already independently declared at process birth. */
        internal fun retainedComplete(assembly: ComplaintTestProcessAssemblyV1, registration: ComplaintTestNamespaceRegistrationV1): ComplaintTestRegisteredHttpStartupV1 {
            requireCompleteDeclarations(registration)
            val create = checkNotNull(registration.process.initialCheckpointCreate)
            val deletion = checkNotNull(registration.process.initialCheckpointDeletion)
            return ComplaintTestRegisteredHttpStartupV1(assembly, registration, replyPolicy = create, editPolicy = create, selectMe = true,
                selectAdminReads = true, selectAdminContent = true, selectAdminStatus = true, selectAdminBatchStatus = true,
                deletionPolicy = deletion, selectOwnerDeleteAll = true, selectComplete = true)
        }

        private fun requireCompleteDeclarations(registration: ComplaintTestNamespaceRegistrationV1) {
            val process = registration.process
            val consumers = process.consumers
            requireTestDeployment(process.initialCheckpointCreate != null && process.initialCheckpointDeletion != null &&
                consumers.adminReadPolicy is me.manga.kira.backend.security.ComplaintAdminReadAdmissionPolicy.Bounded && consumers.adminCursorCodec != null &&
                consumers.adminContentPolicy is me.manga.kira.backend.security.ComplaintAdminContentAdmissionPolicy.Bounded &&
                consumers.adminStatusPolicy is me.manga.kira.backend.security.ComplaintAdminStatusAdmissionPolicy.Bounded &&
                consumers.adminBatchStatusPolicy is me.manga.kira.backend.security.ComplaintAdminBatchStatusAdmissionPolicy.Bounded &&
                consumers.adminDeletePolicy is me.manga.kira.backend.security.ComplaintAdminDeleteAdmissionPolicy.Bounded &&
                consumers.adminBatchDeletePolicy is me.manga.kira.backend.security.ComplaintAdminBatchDeleteAdmissionPolicy.Bounded &&
                consumers.ownerDeleteAllPolicy is me.manga.kira.backend.security.ComplaintOwnerDeleteAllAdmissionPolicy.Bounded &&
                consumers.adminStepUp != null && consumers.journalConfiguration.registeredAdminDelete &&
                consumers.journalConfiguration.registeredAdminBatchDelete && consumers.journalConfiguration.ownerDeleteAll,
                ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            checkNotNull(process.initialCheckpointCreate).requireReplies()
            checkNotNull(process.initialCheckpointCreate).requireEdits()
        }

        /** Explicit pre-D read consumer required; no normal owner startup acquires these routes. */
        internal fun retainedWithAdminReads(assembly: ComplaintTestProcessAssemblyV1, registration: ComplaintTestNamespaceRegistrationV1): ComplaintTestRegisteredHttpStartupV1 {
            requireTestDeployment(registration.process.consumers.adminReadPolicy is me.manga.kira.backend.security.ComplaintAdminReadAdmissionPolicy.Bounded &&
                registration.process.consumers.adminCursorCodec != null, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            return ComplaintTestRegisteredHttpStartupV1(assembly, registration, selectAdminReads = true)
        }

        internal fun retainedWithAdminContent(assembly: ComplaintTestProcessAssemblyV1, registration: ComplaintTestNamespaceRegistrationV1): ComplaintTestRegisteredHttpStartupV1 {
            requireTestDeployment(registration.process.consumers.adminReadPolicy is me.manga.kira.backend.security.ComplaintAdminReadAdmissionPolicy.Bounded &&
                registration.process.consumers.adminCursorCodec != null &&
                registration.process.consumers.adminContentPolicy is me.manga.kira.backend.security.ComplaintAdminContentAdmissionPolicy.Bounded &&
                registration.process.consumers.adminStepUp != null, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            return ComplaintTestRegisteredHttpStartupV1(assembly, registration, selectAdminReads = true, selectAdminContent = true)
        }

        internal fun retainedWithAdminContentStatus(assembly: ComplaintTestProcessAssemblyV1, registration: ComplaintTestNamespaceRegistrationV1): ComplaintTestRegisteredHttpStartupV1 {
            requireTestDeployment(registration.process.consumers.adminReadPolicy is me.manga.kira.backend.security.ComplaintAdminReadAdmissionPolicy.Bounded &&
                registration.process.consumers.adminCursorCodec != null &&
                registration.process.consumers.adminContentPolicy is me.manga.kira.backend.security.ComplaintAdminContentAdmissionPolicy.Bounded &&
                registration.process.consumers.adminStatusPolicy is me.manga.kira.backend.security.ComplaintAdminStatusAdmissionPolicy.Bounded &&
                registration.process.consumers.adminStepUp != null, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            return ComplaintTestRegisteredHttpStartupV1(assembly, registration, selectAdminReads = true, selectAdminContent = true, selectAdminStatus = true)
        }

        internal fun retainedWithAdminContentStatusBatchStatus(assembly: ComplaintTestProcessAssemblyV1, registration: ComplaintTestNamespaceRegistrationV1): ComplaintTestRegisteredHttpStartupV1 {
            requireTestDeployment(registration.process.consumers.adminReadPolicy is me.manga.kira.backend.security.ComplaintAdminReadAdmissionPolicy.Bounded &&
                registration.process.consumers.adminCursorCodec != null &&
                registration.process.consumers.adminContentPolicy is me.manga.kira.backend.security.ComplaintAdminContentAdmissionPolicy.Bounded &&
                registration.process.consumers.adminStatusPolicy is me.manga.kira.backend.security.ComplaintAdminStatusAdmissionPolicy.Bounded &&
                registration.process.consumers.adminBatchStatusPolicy is me.manga.kira.backend.security.ComplaintAdminBatchStatusAdmissionPolicy.Bounded &&
                registration.process.consumers.adminStepUp != null, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
            return ComplaintTestRegisteredHttpStartupV1(assembly, registration, selectAdminReads = true, selectAdminContent = true,
                selectAdminStatus = true, selectAdminBatchStatus = true)
        }

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

        /** Explicit combined cohort, retaining the same existing EDIT-capable policy and original me projection. */
        internal fun retainedWithMeReplyEdit(assembly: ComplaintTestProcessAssemblyV1, registration: ComplaintTestNamespaceRegistrationV1): ComplaintTestRegisteredHttpStartupV1 {
            val policy = checkNotNull(registration.process.initialCheckpointCreate)
            policy.requireReplies()
            policy.requireEdits()
            return ComplaintTestRegisteredHttpStartupV1(assembly, registration, policy, policy, selectMe = true)
        }
    }
}

private fun <T : Any> bean(context: AnnotationConfigServletWebServerApplicationContext, name: String, type: Class<T>, instance: T, vararg aliases: String) {
    context.registerBeanDefinition(name, RootBeanDefinition(type).apply {
        instanceSupplier = Supplier { instance }
        destroyMethodName = ""
        isPrimary = name in setOf("dataSource", "entityManagerFactory", "transactionManager", "jdbcTemplate")
    })
    aliases.forEach { context.registerAlias(name, it) }
}

internal data class OrdinaryHttpObservation(val accepting: Boolean, val activeRequests: Int)

/**
 * This pinned Tomcat request listener is initialized first/destroyed last (checked before admission
 * opens). Real requestDestroyed follows synchronous filter/error tails and ALL async completion
 * listeners. Neither initial filter return, onError/onTimeout nor an early onComplete is release.
 * The current preview's in-memory engine callbacks finish inline; this does not authorize detached
 * coroutine jobs, AsyncContext.start work or new independently executing endpoint producers.
 */
private class RegisteredOrdinaryHttpLifetimeV1 : ServletRequestListener {
    private val lock = Any()
    private val requests = IdentityHashMap<ServletRequest, RequestLifetime>()
    private var accepting = false
    private var stopped = false
    private var unproved = false
    private var active = 0

    override fun requestInitialized(event: ServletRequestEvent) {
        synchronized(lock) {
            val request = event.servletRequest
            if (requests.containsKey(request) || request.getAttribute(ATTRIBUTE) != null) {
                refuseProof()
                return
            }
            val lifetime = RequestLifetime(request)
            requests[request] = lifetime
            request.setAttribute(ATTRIBUTE, lifetime)
        }
    }

    override fun requestDestroyed(event: ServletRequestEvent) {
        synchronized(lock) {
            val lifetime = requests[event.servletRequest]
            if (lifetime == null) {
                refuseProof()
                return
            }
            // Attribute-listener callbacks, if any, must finish before the final release count.
            // A throwing callback leaves this original lifetime retained rather than guessing exit.
            event.servletRequest.removeAttribute(ATTRIBUTE)
            requests.remove(event.servletRequest)
            if (lifetime.admitted) {
                if (active <= 0) refuseProof() else active -= 1
            }
        }
    }

    val filter: Filter = Filter { request, response, chain ->
        val http = request as HttpServletRequest
        val admitted = synchronized(lock) {
            val lifetime = request.getAttribute(ATTRIBUTE) as? RequestLifetime
            if (unproved || lifetime == null || requests[lifetime.request] !== lifetime) false
            else if (lifetime.admitted) true // ASYNC/ERROR stays on the original lease even after stop.
            else if (accepting && !stopped && http.dispatcherType == DispatcherType.REQUEST) {
                lifetime.admitted = true
                active += 1
                true
            } else false
        }
        if (admitted) chain.doFilter(request, response)
        else {
            val output = response as HttpServletResponse
            output.status = 503
            output.setHeader("Cache-Control", "no-store")
            output.setHeader("Retry-After", "1")
            output.contentType = "application/problem+json;charset=UTF-8"
            output.setContentLength(UNAVAILABLE.size)
            if (http.method != "HEAD") output.outputStream.write(UNAVAILABLE)
        }
    }

    fun open() = synchronized(lock) {
        requireTestDeployment(!accepting && !stopped && !unproved, ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
        accepting = true
    }

    fun stop() = synchronized(lock) { accepting = false; stopped = true }
    fun observation(): OrdinaryHttpObservation = synchronized(lock) { OrdinaryHttpObservation(accepting, active) }
    fun released(): Boolean = synchronized(lock) { stopped && !unproved && active == 0 }

    private fun refuseProof() { accepting = false; unproved = true }
    private class RequestLifetime(val request: ServletRequest, var admitted: Boolean = false)

    companion object {
        private const val ATTRIBUTE = "me.manga.kira.backend.registeredOrdinaryRequestLifetime"
        private val UNAVAILABLE = ("""{"type":"about:blank","title":"Service Unavailable","status":503,"errors":[""" +
            """{"code":"SERVICE_UNAVAILABLE","message":"The service is stopping or unavailable."}]}""").toByteArray(Charsets.UTF_8)
    }
}

/**
 * Observe the actual normal Boot producers, without replacing/wrapping their beans or creating any
 * workers. Context refresh/failure may invoke their ordinary destroy methods; only the retained
 * original executors' TERMINATED state proves they cannot enter the borrowed persistence graph.
 */
private class RegisteredOrdinaryProducersV1 : BeanPostProcessor, PriorityOrdered {
    private val executors = IdentityHashMap<Any, ExecutorService?>()
    private var completion: CompletionService? = null
    private var stopped = false

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any {
        if (bean is ThreadPoolTaskExecutor || bean is ThreadPoolTaskScheduler) {
            check(!stopped && !executors.containsKey(bean))
            executors[bean] = null // Initialization that throws is retained/unproved, never guessed absent.
        }
        if (bean is CompletionService) {
            check(!stopped && completion == null)
            completion = bean
        }
        return bean
    }

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        when (bean) {
            is ThreadPoolTaskExecutor -> executors[bean] = bean.threadPoolExecutor
            is ThreadPoolTaskScheduler -> executors[bean] = bean.scheduledThreadPoolExecutor
        }
        return bean
    }

    fun requireReady(context: AnnotationConfigServletWebServerApplicationContext) {
        val schedulers = context.getBeansOfType(TaskScheduler::class.java).values
        val tasks = context.getBeansOfType(AsyncTaskExecutor::class.java).values
        val completions = context.getBeansOfType(CompletionService::class.java).values
        requireTestDeployment(!stopped && schedulers.size == 1 && (schedulers + tasks).all { executors[it] != null } &&
            completions.size == (if (completion == null) 0 else 1) && completions.all { it === completion },
            ComplaintTestDeploymentFailureV1.PROCESS_REFUSED)
    }

    @Suppress("TooGenericExceptionCaught")
    fun stop() {
        check(!stopped)
        stopped = true
        var failure: Throwable? = null
        fun attempt(work: () -> Unit) {
            try { work() } catch (problem: Throwable) { failure = preferCatalogFreezeCleanup(failure, problem) }
        }
        completion?.let { attempt { it.shutdown() } }
        executors.values.filterNotNull().forEach { original -> attempt { original.shutdownNow() } }
        failure?.let { throw it }
    }

    fun released(): Boolean = stopped && (completion?.registeredHttpWorkTerminated() != false) &&
        executors.values.all { it?.isTerminated == true }
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
            // This restriction mints no context and precedes body buffering. Owner paths use the
            // fixed original bridge; selected Admin reads finish in their original-ingress handler.
            order = Ordered.HIGHEST_PRECEDENCE + 1
            addUrlPatterns("/*")
            isAsyncSupported = false
        }
}
