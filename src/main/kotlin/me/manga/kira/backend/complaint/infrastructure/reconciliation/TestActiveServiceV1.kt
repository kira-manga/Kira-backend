package me.manga.kira.backend.complaint.infrastructure.reconciliation

import jakarta.persistence.EntityManagerFactory
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.infrastructure.AuditLogEntity
import me.manga.kira.backend.audit.infrastructure.JpaAuditRepositoryAdapter
import me.manga.kira.backend.audit.infrastructure.SpringDataAuditLogRepository
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJdbcTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJpaTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.OrdinaryPersistenceAdmission
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipantRole
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleActivation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePoolLaunchProfile
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePublicTrustPreparation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.SystemPersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceActiveRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1
import me.manga.kira.backend.complaint.infrastructure.transaction.DeletionPersistenceAdmission
import me.manga.kira.backend.security.CurrentUser
import me.manga.kira.backend.user.infrastructure.UserEntity
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.LockSupport

/**
 * One non-web B owner: cold construction, ACTIVE identity registration, serial queue/recurrent
 * dispatch, and disposal all run on its original thread. No borrowed application graph or callbacks
 * for work/results/cleanup. Real begin remains UNKNOWN and refuses before file/provider work;
 * the raw-HTTP fixture path exercises this same implementation, not operational launch authority.
 */
internal class TestActiveServiceV1 private constructor(
    private val nanoClock: PersistenceNanoClock,
    private val wallClock: () -> Instant,
    private val raw: RawHttp?,
) : AutoCloseable {
    private val caller = Thread.currentThread()
    private val startupBudget = PersistenceTimeBudget.start(60_000, nanoClock)
    private val clock = ServiceClock(wallClock)
    private val stopGate = Any()
    private val ownerFinished = CountDownLatch(1)
    @Volatile private var stopRequested = false
    @Volatile var status = TestActiveServiceStatusV1.RETAINED
        private set
    @Volatile var failureCode: TestActiveServiceFailureV1? = null
        private set
    private var entered = false
    private var serving = false
    private var startupCompleted = false
    private var closeEntered = false
    private var cleanupProven = false
    private var failure: Throwable? = null
    private var cleanupFailure: Throwable? = null
    private var closeBudget: PersistenceTimeBudget? = null
    private var sessions: TestActiveServiceSessionsV1? = null
    private var assemblyConstructing = false
    private var assembly: ComplaintTestProcessAssemblyV1? = null
    private var process: VersionBoundTestNamespaceProcessV1? = null
    private var assemblyClosed = false
    private var registrationConstructing = false
    private var registrationAttempt: ComplaintTestNamespaceActiveRegistrationAttemptV1? = null
    private var registration: ComplaintTestNamespaceRegistrationV1? = null
    private var registrationClosed = false
    private var factory: LocalContainerEntityManagerFactoryBean? = null
    private var factoryInitializing = false
    private var factoryInitialized = false
    private var factoryCloseReturned = false
    private var emf: EntityManagerFactory? = null
    private var ordinaryManager: GuardedJpaTransactionManager? = null
    private var ordinaryAdmission: OrdinaryPersistenceAdmission? = null
    private var ordinaryOwnership: PersistencePhaseOwnership? = null
    private var ordinaryJdbc: JdbcTemplate? = null
    private var installationBindingEntered = false
    private var installationBindingReturned = false
    private var deletionManager: GuardedJdbcTransactionManager? = null
    private var deletionAdmission: DeletionPersistenceAdmission? = null
    private var deletionOwnership: PersistencePhaseOwnership? = null
    private var deletionJdbc: JdbcTemplate? = null
    private var auditRepositories: JpaRepositoryFactory? = null
    private var auditRepository: JpaAuditRepositoryAdapter? = null
    private var audit: AuditService? = null
    private var queueConstructing = false
    private var queueOriginal: TestActiveOwnerDeleteQueueV1? = null
    private var queueCompleted: TestActiveOwnerDeleteQueueV1.Completed? = null
    private var recurrentConstructing = false
    private var recurrentOriginal: TestActiveRecurrentV1? = null
    private var recurrentResult: TestActiveRecurrentV1.Result? = null
    private var dispatchReserved = false
    private var cadenceNanos = 0L
    private var queueAllowanceNanos = 0L
    private var recurrentStartedAt: Long? = null
    private var sampledNanos: Long? = null

    /** Blocks on this owner until stop/failure and its one teardown attempt. No attempt result is caller-supplied. */
    @Suppress("TooGenericExceptionCaught")
    fun serve(manifest: Path, sessions: TestActiveServiceSessionsV1): TestActiveServiceStatusV1 {
        requireCaller()
        requireService(!entered && !closeEntered, TestActiveServiceFailureV1.STARTUP_REFUSED)
        entered = true
        serving = true
        this.sessions = sessions
        var context = TestActiveServiceFailureV1.STARTUP_REFUSED
        try {
            startupCheckpoint()
            sessions.requireValid()
            status = TestActiveServiceStatusV1.STARTING
            start(manifest, sessions)
            startupCheckpoint()
            startupCompleted = true
            status = TestActiveServiceStatusV1.RUNNING
            context = TestActiveServiceFailureV1.ATTEMPT_REFUSED
            dispatch()
        } catch (_: StartupStopped) {
            // Stop between startup stages drains anything already entered; it never promotes readiness.
        } catch (problem: Throwable) {
            recordFailure(problem, context)
        } finally {
            serving = false
            finishOwned()
        }
        failure?.let { if (it is Error) throw it }
        cleanupFailure?.let { if (it is Error) throw it }
        return status
    }

    /** The one cross-thread control: no cancellation, resource close, interrupt, lease action or work dispatch. */
    fun requestStop() {
        synchronized(stopGate) { stopRequested = true }
        LockSupport.unpark(caller)
    }

    /** Observes the owner's result; thread return alone does not turn CUSTODY_RETAINED into STOPPED. */
    fun awaitOwnerCompletion(): TestActiveServiceStatusV1 {
        requireService(Thread.currentThread() !== caller, TestActiveServiceFailureV1.STARTUP_REFUSED)
        var interrupted = false
        try {
            while (true) {
                try {
                    ownerFinished.await()
                    return status
                } catch (_: InterruptedException) {
                    interrupted = true // Observation cannot authorize foreign-thread disposal or forced exit.
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** CLI input failure before serve; retain the service and finish its original (still empty) ownership. */
    internal fun refuseInput(): TestActiveServiceStatusV1 {
        requireCaller()
        requireService(!entered && !closeEntered, TestActiveServiceFailureV1.INPUT_REFUSED)
        entered = true
        recordFailure(TestActiveServiceExceptionV1(TestActiveServiceFailureV1.INPUT_REFUSED), TestActiveServiceFailureV1.INPUT_REFUSED)
        finishOwned()
        return status
    }

    private fun start(manifest: Path, selected: TestActiveServiceSessionsV1) {
        startupCheckpoint()
        assemblyConstructing = true
        val original = (raw?.assembly(nanoClock, wallClock) ?: ComplaintTestProcessAssemblyV1.begin()).also { assembly = it }
        assemblyConstructing = false
        // There is deliberately NO real controlled-launch constructor. A file, opt-in flag or STS
        // identity is not authorization to upgrade UNKNOWN. Even secrets are not fetched on this path.
        requireService(raw?.launchProfile === PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY, TestActiveServiceFailureV1.LAUNCH_UNQUALIFIED)
        startupCheckpoint()
        original.assemble(manifest, selected.secrets, selected.sealBootstrap, selected.ordinaryPublication, selected.recoveryRead, selected.queueRecovery)
        val target = original.target.also { process = it }
        startupCheckpoint()
        requireService(target.activeFirstCut != null && target.activeCutoffPublication != null && target.initialCheckpoint != null &&
            target.activeRecurrent != null && target.activeOwnerDeleteQueue != null && target.pools.epochRotation != null,
            TestActiveServiceFailureV1.STARTUP_REFUSED)
        configureScheduling(target)
        val lifecycle = original.lifecycleOwner
        requireService(lifecycle.preparePublicTrust() === PersistencePublicTrustPreparation.READY, TestActiveServiceFailureV1.STARTUP_REFUSED)
        startupCheckpoint()
        requireService(target.pools.ordinary.start() === PersistenceLifecycleActivation.STARTED, TestActiveServiceFailureV1.STARTUP_REFUSED)
        startupCheckpoint()
        requireService(target.pools.ordinary.observePreparation() === PersistenceLifecycleObservation.READY, TestActiveServiceFailureV1.STARTUP_REFUSED)
        startupCheckpoint()
        requireService(target.pools.catalogCoordinator.prepare() === PersistenceLifecycleObservation.READY, TestActiveServiceFailureV1.STARTUP_REFUSED)
        startupCheckpoint()
        requireService(target.pools.deletion.prepareDeletion() === PersistenceLifecycleObservation.READY, TestActiveServiceFailureV1.STARTUP_REFUSED)
        startupCheckpoint()
        requireService(checkNotNull(target.pools.epochRotation).prepare() === PersistenceLifecycleObservation.READY, TestActiveServiceFailureV1.STARTUP_REFUSED)
        startupCheckpoint()
        registrationConstructing = true
        val attempt = (raw?.let { ComplaintTestNamespaceActiveRegistrationAttemptV1.withHttpFixture(original, it.catalog, clock) }
            ?: ComplaintTestNamespaceActiveRegistrationAttemptV1.begin(original)).also { registrationAttempt = it }
        registrationConstructing = false
        registration = attempt.register(selected.catalogPrimaryRead, selected.catalogReplicaRead)
        attempt.requireActualCleanup()
        startupCheckpoint()
        prepareApplicationResources(target, checkNotNull(registration))
    }

    private fun configureScheduling(target: VersionBoundTestNamespaceProcessV1) {
        val cadence = target.consumers.journalConfiguration.declaration().limits.deadlines.scanCadenceMillis.toLong()
        val recurrent = checkNotNull(target.activeRecurrent).totalAttemptMillis
        val queue = checkNotNull(target.activeOwnerDeleteQueue).totalAttemptMillis
        // Refuse a declared profile without serial headroom. Never make its D or deadlines more permissive.
        requireService(cadence > Math.addExact(recurrent, queue), TestActiveServiceFailureV1.SCHEDULING_REFUSED)
        cadenceNanos = Math.multiplyExact(cadence, 1_000_000L)
        queueAllowanceNanos = Math.multiplyExact(queue, 1_000_000L)
    }

    private fun prepareApplicationResources(target: VersionBoundTestNamespaceProcessV1, identity: ComplaintTestNamespaceRegistrationV1) {
        val pool = target.pools.ordinary
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
        startupCheckpoint()
        factoryInitializing = true
        originalFactory.afterPropertiesSet() // Own B pool only; no Spring context, migration or second DataSource.
        val initialized = checkNotNull(originalFactory.`object`).also { emf = it }
        factoryInitialized = true
        factoryInitializing = false
        startupCheckpoint()
        val manager = GuardedJpaTransactionManager(initialized, pool).also { ordinaryManager = it }
        val size = target.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.ORDINARY }.hikari.sizing.maximumPoolSize
        val permits = OrdinaryPersistenceAdmission(size).also { ordinaryAdmission = it }
        val ownership = PersistencePhaseOwnership(permits, manager, nanoClock = nanoClock).also { ordinaryOwnership = it }
        val jdbc = JdbcTemplate(pool).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }.also { ordinaryJdbc = it }
        installationBindingEntered = true
        identity.requireInstallationResources(ownership, jdbc)
        installationBindingReturned = true
        startupCheckpoint()
        val repositories = JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(initialized)).also { auditRepositories = it }
        val counted = JpaAuditRepositoryAdapter(repositories.getRepository(SpringDataAuditLogRepository::class.java)).also { auditRepository = it }
        audit = AuditService(counted, CurrentUser(), clock)
        val deletionPool = target.pools.deletion
        val deletionPermits = DeletionPersistenceAdmission().also { deletionAdmission = it }
        val deletionTransactions = GuardedJdbcTransactionManager(deletionPool).also { deletionManager = it }
        val deletionOwner = PersistencePhaseOwnership.deletion(deletionPermits, deletionTransactions, nanoClock).also { deletionOwnership = it }
        deletionJdbc = JdbcTemplate(deletionPool).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
        deletionOwner.requireBoundComplaintDeletion(target.pools)
        startupCheckpoint()
    }

    private fun dispatch() {
        while (!stopRequested) {
            requireCaller()
            requireService(!Thread.currentThread().isInterrupted, TestActiveServiceFailureV1.INTERRUPTED)
            val now = sampleNanos()
            val prior = recurrentStartedAt
            val remaining = if (prior == null) 0L else cadenceNanos - elapsed(prior, now)
            if (prior == null || remaining <= 0) {
                if (!reserveDispatch()) return
                // Anchor to actual start, not a series of missed ticks. No catch-up burst after an owner pause.
                recurrentStartedAt = now
                runRecurrent()
            } else if (remaining > queueAllowanceNanos) {
                if (!reserveDispatch()) return
                runQueue()
            } else {
                // Full queue allowance will not fit. Leave the due checkpoint independent of queue traffic.
                if (!stopRequested) LockSupport.parkNanos(remaining)
                continue
            }
            dispatchReserved = false // Only a privately completed and actually cleaned original reaches here.
        }
    }

    /** Stop linearizes against reservation, before any original is constructed; admitted work then drains. */
    private fun reserveDispatch(): Boolean = synchronized(stopGate) {
        if (stopRequested) false else {
            requireService(!dispatchReserved && failure == null, TestActiveServiceFailureV1.ATTEMPT_REFUSED)
            dispatchReserved = true
            true
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runQueue() {
        queueOriginal?.let {
            requireService(queueCompleted != null, TestActiveServiceFailureV1.ATTEMPT_REFUSED)
            it.requireActualCleanup()
        }
        requireQueueSchedulingRoom()
        val identity = checkNotNull(registration)
        val originalAssembly = checkNotNull(assembly)
        val owner = checkNotNull(deletionOwnership)
        val jdbc = checkNotNull(deletionJdbc)
        val originalAudit = checkNotNull(audit)
        queueConstructing = true
        queueCompleted = null
        val original = (raw?.let { TestActiveOwnerDeleteQueueV1.withHttpFixture(identity, originalAssembly, owner, jdbc, originalAudit, it.catalog, clock) }
            ?: TestActiveOwnerDeleteQueueV1.begin(identity, originalAssembly, owner, jdbc, originalAudit)).also { queueOriginal = it }
        queueConstructing = false
        val selected = checkNotNull(sessions)
        try {
            // Construction/owner pauses must not launder an old fit sample into a late native poll.
            requireQueueSchedulingRoom()
        } catch (problem: Throwable) {
            // This call site has NEVER invoked poll. The observer still requires its actual unused,
            // cancelled, unclaimed, phase-free and provider-closed original state; cancellation alone is not proof.
            original.cancel()
            throw problem
        }
        queueCompleted = original.poll(selected.catalogPrimaryRead, selected.catalogReplicaRead)
        original.requireActualCleanup()
    }

    private fun requireQueueSchedulingRoom() {
        val remaining = cadenceNanos - elapsed(checkNotNull(recurrentStartedAt), sampleNanos())
        requireService(remaining > queueAllowanceNanos, TestActiveServiceFailureV1.SCHEDULING_REFUSED)
    }

    private fun runRecurrent() {
        recurrentOriginal?.let {
            requireService(recurrentResult is TestActiveRecurrentV1.Completed, TestActiveServiceFailureV1.ATTEMPT_REFUSED)
            it.requireActualCleanup()
        }
        val identity = checkNotNull(registration)
        val originalAssembly = checkNotNull(assembly)
        recurrentConstructing = true
        recurrentResult = null
        val original = (raw?.let { TestActiveRecurrentV1.withHttpFixture(identity, originalAssembly, it.catalog, clock) }
            ?: TestActiveRecurrentV1.begin(identity, originalAssembly)).also { recurrentOriginal = it }
        recurrentConstructing = false
        val selected = checkNotNull(sessions)
        recurrentResult = original.checkpoint(selected.catalogPrimaryRead, selected.catalogReplicaRead)
        while (true) {
            when (val result = checkNotNull(recurrentResult)) {
                is TestActiveRecurrentV1.RecoveryRequired -> {
                    // Exact live result, same scan/renewal/native inventory and B resources. A stop
                    // request drains this bounded original; it does not skip APPLY or create a retry.
                    recurrentResult = result.apply(checkNotNull(deletionOwnership), checkNotNull(deletionJdbc), checkNotNull(audit))
                }
                is TestActiveRecurrentV1.Completed -> break
            }
        }
        original.requireActualCleanup()
        requireService(elapsed(checkNotNull(recurrentStartedAt), sampleNanos()) < cadenceNanos, TestActiveServiceFailureV1.SCHEDULING_REFUSED)
    }

    private fun sampleNanos(): Long {
        val current = nanoClock.nanoTime()
        sampledNanos?.let { elapsed(it, current) }
        sampledNanos = current
        return current
    }

    private fun elapsed(start: Long, now: Long): Long = (now - start).also {
        requireService(it >= 0, TestActiveServiceFailureV1.SCHEDULING_REFUSED)
    }

    private fun startupCheckpoint() {
        requireCaller()
        requireService(!Thread.currentThread().isInterrupted, TestActiveServiceFailureV1.INTERRUPTED)
        startupBudget.remainingMillis(1)
        if (stopRequested) throw StartupStopped()
    }

    private fun requireCaller() {
        requireService(caller === Thread.currentThread(), TestActiveServiceFailureV1.STARTUP_REFUSED)
        requireConnectionFree()
    }

    private fun recordFailure(problem: Throwable, otherwise: TestActiveServiceFailureV1) {
        if (failure == null || problem is Error) failure = problem
        if (problem is InterruptedException) caller.interrupt()
        if (failureCode == null) failureCode = when (problem) {
            is TestActiveServiceExceptionV1 -> problem.code
            is InterruptedException -> TestActiveServiceFailureV1.INTERRUPTED
            else -> otherwise
        }
    }

    /** No new service close budget on subsequent calls; producer budgets/slots are never touched here. */
    override fun close() {
        requireCaller()
        requireService(!serving, TestActiveServiceFailureV1.CLEANUP_UNPROVEN)
        finishOwned()
        requireCleanupProven()
    }

    internal fun requireCleanupProven() {
        requireCaller()
        requireService(cleanupProven, TestActiveServiceFailureV1.CLEANUP_UNPROVEN)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun finishOwned() {
        requireService(caller === Thread.currentThread(), TestActiveServiceFailureV1.CLEANUP_UNPROVEN)
        if (closeEntered) return
        closeEntered = true
        requestStop()
        status = TestActiveServiceStatusV1.STOPPING
        fun prove(work: () -> Unit): Boolean = try {
            work()
            true
        } catch (problem: Throwable) {
            if (cleanupFailure == null || problem is Error) cleanupFailure = problem
            failureCode = TestActiveServiceFailureV1.CLEANUP_UNPROVEN
            false
        }
        try {
            prove { closeBudget = if (startupCompleted) PersistenceTimeBudget.start(10_000, nanoClock) else startupBudget }
            // There is no listener, but permanently stop the original ingress owner before observing its slots.
            prove { process?.consumers?.ingressAdmission?.stopRegisteredStartupAdmission() }
            // Register always owns its finally. A thrown sticky close cannot establish released catalog custody.
            prove { registrationAttempt?.close() }
            // A failed recurrent may throw its work failure AFTER physical cleanup. Observe the actual
            // original separately rather than interpreting either close return or exception as disposal.
            runCatching { recurrentOriginal?.close() }.exceptionOrNull()?.let { recordFailure(it, TestActiveServiceFailureV1.ATTEMPT_REFUSED) }
            val childrenReleased = prove {
                requireConnectionFree()
                requireService(!assemblyConstructing && !registrationConstructing && !queueConstructing && !recurrentConstructing,
                    TestActiveServiceFailureV1.CLEANUP_UNPROVEN)
                registrationAttempt?.requireActualCleanup()
                queueOriginal?.requireActualCleanup() // Actual entered-poll cleanup, or the strictly cancelled/unused original; never cancel alone.
                recurrentOriginal?.requireActualCleanup()
                requireService(ordinaryAdmission?.activeOwners().let { it == null || it == 0 } &&
                    deletionAdmission?.activeOwners()?.totalOwners.let { it == null || it == 0 }, TestActiveServiceFailureV1.CLEANUP_UNPROVEN)
                process?.let { target ->
                    requireService(target.pools.catalogCoordinator.activeSnapshotOwners() == 0 &&
                        target.publicationLanes.activeOwners().totalOwners == 0L &&
                        target.consumers.ingressAdmission.registeredStartupAdmissionReleased(), TestActiveServiceFailureV1.CLEANUP_UNPROVEN)
                }
                requireService(!installationBindingEntered || installationBindingReturned, TestActiveServiceFailureV1.CLEANUP_UNPROVEN)
            }
            // Assembly otherwise proceeds to pool/trust shutdown even after a recipe-close failure.
            // Never call it while one service child, phase, native owner or construction remains unproved.
            if (!childrenReleased || cleanupFailure != null) return
            if (!prove { registration?.close(); registrationClosed = true }) return
            val factoryReleased = prove {
                factory?.let { original ->
                    original.destroy()
                    if (factoryInitialized) requireService(emf?.isOpen == false, TestActiveServiceFailureV1.CLEANUP_UNPROVEN)
                    factoryCloseReturned = true
                }
                requireService(!factoryInitializing && (factory == null || factoryCloseReturned), TestActiveServiceFailureV1.CLEANUP_UNPROVEN)
            }
            if (!factoryReleased) return
            if (!prove { assembly?.close(); assemblyClosed = true }) return
            prove {
                checkNotNull(closeBudget).remainingMillis(1)
                requireService(registrationClosed && assemblyClosed && !Thread.currentThread().isInterrupted, TestActiveServiceFailureV1.CLEANUP_UNPROVEN)
                cleanupProven = true
                sessions = null // No live original remains; do not retain separate immutable session transport unnecessarily.
            }
        } finally {
            status = when {
                !cleanupProven -> TestActiveServiceStatusV1.CUSTODY_RETAINED
                failure != null -> TestActiveServiceStatusV1.FAILED
                else -> TestActiveServiceStatusV1.STOPPED
            }
            ownerFinished.countDown() // This signals only the status above, not independent resource proof.
        }
    }

    override fun toString(): String = "TestActiveServiceV1(retained-owner,redacted,no-launch-authority)"

    private class StartupStopped : RuntimeException(null, null, false, false)

    private class ServiceClock(private val source: () -> Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = ServiceClock(source, zone)
        override fun instant(): Instant = source()
    }

    /** Raw transports only; this is not a caller-provided assembly factory or registration/result seam. */
    private class RawHttp(
        val secret: () -> SdkHttpClient,
        val catalog: () -> SdkHttpClient,
        val launchProfile: PersistencePoolLaunchProfile,
        val sts: ((() -> Int) -> SdkHttpClient)?,
        val kms: ((() -> Int) -> SdkHttpClient)?,
        val s3: ((() -> Int) -> SdkHttpClient)?,
        val ordinarySts: ((() -> Int) -> SdkHttpClient)?,
        val ordinaryKms: ((() -> Int) -> SdkHttpClient)?,
        val ordinaryS3: ((() -> Int) -> SdkHttpClient)?,
        val scannerSts: ((() -> Int) -> SdkHttpClient)?,
        val scannerKms: ((() -> Int) -> SdkHttpClient)?,
        val scannerS3: ((() -> Int) -> SdkHttpClient)?,
        val queueSqs: ((() -> Int) -> SdkHttpClient)?,
        val queueSts: ((() -> Int) -> SdkHttpClient)?,
        val queueKms: ((() -> Int) -> SdkHttpClient)?,
        val queueS3: ((() -> Int) -> SdkHttpClient)?,
    ) {
        fun assembly(nanoClock: PersistenceNanoClock, wallClock: () -> Instant): ComplaintTestProcessAssemblyV1 =
            ComplaintTestProcessAssemblyV1.withHttpFixture(secret, nanoClock, wallClock, launchProfile,
                sts, kms, s3, ordinarySts, ordinaryKms, ordinaryS3, scannerSts, scannerKms, scannerS3, queueSqs, queueSts, queueKms, queueS3)
    }

    companion object {
        /** No real-launch override. In particular, UNKNOWN is not upgraded from a file, flag or STS observation. */
        fun begin(): TestActiveServiceV1 = TestActiveServiceV1(SystemPersistenceNanoClock, Instant::now, null)

        internal fun withHttpFixture(
            secretHttpFactory: () -> SdkHttpClient,
            catalogHttpFactory: () -> SdkHttpClient,
            nanoClock: PersistenceNanoClock = SystemPersistenceNanoClock,
            wallClock: () -> Instant = Instant::now,
            runtimeLaunchProfile: PersistencePoolLaunchProfile = PersistencePoolLaunchProfile.UNKNOWN,
            sts: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            kms: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            s3: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            ordinarySts: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            ordinaryKms: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            ordinaryS3: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            scannerSts: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            scannerKms: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            scannerS3: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            queueSqs: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            queueSts: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            queueKms: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
            queueS3: ((remainingMillis: () -> Int) -> SdkHttpClient)? = null,
        ): TestActiveServiceV1 = TestActiveServiceV1(nanoClock, wallClock, RawHttp(secretHttpFactory, catalogHttpFactory, runtimeLaunchProfile,
            sts, kms, s3, ordinarySts, ordinaryKms, ordinaryS3, scannerSts, scannerKms, scannerS3, queueSqs, queueSts, queueKms, queueS3))
    }
}

/** Session transport only; the existing assembly and native adapters still authenticate their mapped uses. */
internal class TestActiveServiceSessionsV1(
    internal val secrets: AwsSessionCredentials,
    internal val sealBootstrap: AwsSessionCredentials,
    internal val ordinaryPublication: AwsSessionCredentials,
    internal val recoveryRead: AwsSessionCredentials,
    internal val queueRecovery: AwsSessionCredentials,
    internal val catalogPrimaryRead: AwsSessionCredentials,
    internal val catalogReplicaRead: AwsSessionCredentials,
) {
    internal fun requireValid() {
        listOf(secrets, sealBootstrap, ordinaryPublication, recoveryRead, queueRecovery, catalogPrimaryRead, catalogReplicaRead).forEach { session ->
            requireCredential(session.accessKeyId(), 128)
            requireCredential(session.secretAccessKey(), 256)
            requireCredential(session.sessionToken(), 16_384)
        }
        // Preserve, do not expand, the assembly's existing non-alias constraints for these role uses.
        requireService(ordinaryPublication.accessKeyId() != sealBootstrap.accessKeyId() &&
            recoveryRead.accessKeyId() != sealBootstrap.accessKeyId() && recoveryRead.accessKeyId() != ordinaryPublication.accessKeyId() &&
            queueRecovery.accessKeyId() != sealBootstrap.accessKeyId() && queueRecovery.accessKeyId() != ordinaryPublication.accessKeyId(),
            TestActiveServiceFailureV1.INPUT_REFUSED)
    }

    private fun requireCredential(value: String, maximum: Int) = requireService(
        value.length in 1..maximum && value.all { it in '!'..'~' }, TestActiveServiceFailureV1.INPUT_REFUSED,
    )
    override fun toString(): String = "TestActiveServiceSessionsV1(redacted,transport-only)"
}

/** FAILED has proved cleanup but failed work; CUSTODY_RETAINED has neither cleanup nor redispatch permission. */
internal enum class TestActiveServiceStatusV1 { RETAINED, STARTING, RUNNING, STOPPING, STOPPED, FAILED, CUSTODY_RETAINED }
internal enum class TestActiveServiceFailureV1 { INPUT_REFUSED, LAUNCH_UNQUALIFIED, STARTUP_REFUSED, SCHEDULING_REFUSED, ATTEMPT_REFUSED, INTERRUPTED, CLEANUP_UNPROVEN }
internal class TestActiveServiceExceptionV1(val code: TestActiveServiceFailureV1) : RuntimeException("TEST active service refused.", null, false, false)
private fun requireService(condition: Boolean, code: TestActiveServiceFailureV1) {
    if (!condition) throw TestActiveServiceExceptionV1(code)
}
