package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceComplaintMaintenanceGateV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipantRole
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogReadbackRefreshCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReadbackHttpPairV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationCanonicalV3
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReadbackV3
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationCustodyExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationCustodyFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunFirstProjectionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationControlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackAdapter
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import me.manga.kira.backend.complaint.infrastructure.catalog.preferSignerRotationCleanup
import me.manga.kira.backend.complaint.infrastructure.catalog.copyEvidence
import me.manga.kira.backend.complaint.infrastructure.catalog.withSignerRotationCleanup
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.io.InterruptedIOException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * One initial-only same-process identity release. CAPTURE -> genuine dual raw read -> fresh
 * M-exclusive RELEASE. Only original successful COMMIT plus actual cleanup can publish the local
 * latch; open SQL flags alone, including an ambiguous committed release, never admit a consumer.
 */
internal class ComplaintTestInitialAdmissionV1 private constructor(
    internal val registration: ComplaintTestNamespaceRegistrationV1,
    internal val assembly: ComplaintTestProcessAssemblyV1,
    private val httpFactory: (() -> SdkHttpClient)?,
    private val clock: Clock,
) : AutoCloseable {
    internal val process = registration.process
    private val caller = Thread.currentThread()
    internal val coordinator = process.pools.catalogCoordinator
    internal val budget = PersistenceTimeBudget.start(process.catalogReadback.totalAttemptMillis, coordinator.ownership.nanoClock)
    internal val facts = registration.claimInitialAdmission(this)
    private val expected = CatalogTestRunActivationCanonicalV3.fromRetained(process, facts.installationLimit)
    private val construction = S3CatalogReadbackAdapter.Construction()
    private val http = CatalogSignerRotationReadbackHttpPairV1(this, budget)
    private val signal = AtomicReference<Throwable?>()
    private var stage = Stage.NEW
    private var reserved = false
    private var released = false
    private var closed = false
    private var cleanupProven = false
    private var cleanupUncertain = false
    private var providerStarted = false
    private var providerClosed = false
    private var providerFailure: Throwable? = null
    private var closeFailure: Throwable? = null
    private var phase: PersistencePhaseContext? = null
    private var phaseEntered = false
    private var captured: TestInitialAdmissionOperationV1? = null
    private var finalRelease: TestInitialAdmissionOperationV1? = null
    private var capturedControls: TestInitialAdmissionControlsV1? = null
    private var capturedHistory: CatalogTestRunActivationHistoryV1? = null
    private var proof: CatalogTestRunActivationReadbackV3? = null
    private var successful = false
    private var issued = false

    init {
        requireConnectionFree()
        registration.requireInitialAdmissionTarget(assembly)
    }

    fun release(primary: AwsSessionCredentials, replica: AwsSessionCredentials) {
        requireRegistration(caller === Thread.currentThread())
        var failure: Throwable? = null
        try {
            requireConnectionFree()
            requireRunning()
            requireRegistration(stage === Stage.NEW)
            coordinator.catalogRefreshCustody.reserveTestInitialAdmission(this)
            reserved = true
            stage = Stage.CAPTURE
            captured = coordinator.testInitialAdmission.execute(this)
            capturedControls = checkNotNull(captured).controls
            capturedHistory = checkNotNull(captured).history
            stage = Stage.READBACK
            observe(primary, replica)
            stage = Stage.RELEASE
            finalRelease = coordinator.testInitialAdmission.execute(this)
            requireRunning()
            requireRegistration(providerClosed && providerFailure == null && phase == null)
            successful = true
        } catch (problem: Throwable) {
            observeFailure(problem)
            failure = problem
        } finally {
            runCatching(::close).exceptionOrNull()?.let { failure = preferSignerRotationCleanup(failure, it) }
        }
        throwIfSignalled()
        if (failure != null || !successful) throw ComplaintTestNamespaceRegistrationExceptionV1()
        registration.publishInitialAdmission(this)
    }

    private fun observe(primary: AwsSessionCredentials, replica: AwsSessionCredentials) {
        requireProviderRunning()
        val reader = process.catalogReadback
        val at = clock.instant()
        val policy = reader.policyAt(at)
        val configured = reader.sdkLimits
        val millis = budget.remainingMillis(configured.requestTimeoutMillis)
        val limits = S3CatalogReadbackLimits(millis, minOf(configured.connectTimeoutMillis.toLong(), millis).toInt(),
            minOf(configured.readTimeoutMillis.toLong(), millis).toInt(), configured.maximumListBytes, configured.maximumErrorBytes, configured.maximumObjectBytes)
        providerStarted = true
        val read = withSignerRotationCleanup({
            val adapter = S3CatalogReadbackAdapter.openOwned(construction, reader.currentBundleBytes(), reader.chainPolicy.trustBundlePolicy,
                primary, replica, limits, { http.open(limits, httpFactory) }, System::nanoTime)
            CatalogTestRunActivationReadbackV3.verifyInitialAdmission(TimedReadback(adapter), reader.initialBundleBytes(), reader.currentBundleBytes(), policy,
                facts.control.head, expected, checkNotNull(capturedHistory))
        }, ::closeProviders)
        requireRunning()
        requireRegistration(!clock.instant().isBefore(at))
        facts.requireRaw(read)
        requireRegistration(providerClosed && providerFailure == null)
        proof = read
    }

    internal fun requireProviderRunning() {
        requireConnectionFree()
        requireRunning()
        requireRegistration(stage === Stage.READBACK && phase == null && captured != null && proof == null)
    }

    internal fun requirePersistence(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireRunning()
        requireRegistration(ownership === coordinator.ownership && jdbc.dataSource === coordinator.dataSource &&
            ownership.manager === coordinator.manager && stage in setOf(Stage.CAPTURE, Stage.RELEASE))
        if (stage === Stage.RELEASE) requireRegistration(proof != null && providerClosed && providerFailure == null && captured != null)
    }

    internal fun requirePhaseEntry(ownership: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireConnectionFree()
        requireRunning()
        requireRegistration(ownership === coordinator.ownership && path === this.path &&
            !phaseEntered && phase == null && (stage === Stage.CAPTURE && captured == null || stage === Stage.RELEASE && finalRelease == null && proof != null))
        phaseEntered = true
    }

    internal fun retainPhase(selected: PersistencePhaseContext) {
        requireRunning()
        requireRegistration(phaseEntered && phase == null)
        phase = selected
    }

    internal fun observePhaseCleanup(selected: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testInitialAdmissionCleanupProven(this) ||
                selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) cleanupUncertain = true
            else { phase = null; phaseEntered = false }
        } catch (problem: Throwable) {
            cleanupUncertain = true
            observeFailure(problem)
        }
    }

    internal fun authenticate(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requirePersistence(ownership, jdbc)
        val opening = process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.CATALOG_COORDINATOR }.openings()
            .map { it.publicDriverProperties() }
        val user = opening.map { it["user"] }.distinct().single()
        val database = opening.map { it["PGDBNAME"] }.distinct().single()
        requireRegistration(jdbc.query("SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated",
            ResultSetExtractor { rows -> rows.next() && rows.getBoolean("authenticated") && !rows.wasNull() && !rows.next() }, user, user, database) == true)
        requirePersistence(ownership, jdbc)
    }

    internal fun requireMaintenanceGate(ownership: PersistencePhaseOwnership, path: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireRunning()
        requireRegistration(ownership === coordinator.ownership && path === this.path && phaseEntered && phase != null)
        registration.requireSealingGate(gate)
    }

    internal fun requireCustody(selected: CatalogReadbackRefreshCustodyV1) = requireRegistration(
        caller === Thread.currentThread() && coordinator.catalogRefreshCustody === selected,
    )

    private fun requireRunning() {
        throwIfSignalled()
        requireRegistration(caller === Thread.currentThread() && !closed && !released && !cleanupUncertain && !Thread.currentThread().isInterrupted)
        budget.remainingMillis(1)
        registration.requireInitialAdmissionTarget(assembly)
        if (reserved) coordinator.catalogRefreshCustody.requireTestInitialAdmission(this)
    }

    internal fun observeFailure(problem: Throwable) {
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("TEST initial admission cancelled.")
            problem is InterruptedException || problem is InterruptedIOException ||
                (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) ||
                (problem is CatalogReadbackException && problem.code === CatalogReadbackFailure.INTERRUPTED) ||
                (problem is CatalogSignerRotationFreezeExceptionV1 && problem.code === CatalogSignerRotationFreezeFailureV1.INTERRUPTED) ||
                (problem is CatalogTestRunActivationCustodyExceptionV1 && problem.code === CatalogTestRunActivationCustodyFailureV1.INTERRUPTED) ||
                (problem is CatalogTestRunActivationExceptionV1 && problem.code === CatalogTestRunActivationFailureV1.INTERRUPTED) ->
                InterruptedException("TEST initial admission interrupted.")
            else -> return
        }
        while (true) {
            val previous = signal.get()
            if (previous is Error || (previous is CancellationException && retained !is Error) ||
                (previous is InterruptedException && retained is InterruptedException)) return
            if (signal.compareAndSet(previous, retained)) return
        }
    }

    internal fun throwIfSignalled() {
        signal.get()?.let { if (it is InterruptedException) Thread.currentThread().interrupt(); throw it }
    }

    private fun closeProviders() {
        val failure = runCatching { withSignerRotationCleanup(construction::close, http::close) }.exceptionOrNull()
        if (failure != null) { observeFailure(failure); providerFailure = preferSignerRotationCleanup(providerFailure, failure) }
        providerFailure?.let { throw it }
        providerClosed = true
    }

    override fun close() {
        requireRegistration(caller === Thread.currentThread())
        if (closed) { closeFailure?.let { throw it }; return }
        closed = true
        closeFailure = ComplaintTestNamespaceRegistrationExceptionV1()
        val outcomes = listOf(runCatching(::closeProviders), runCatching(::requireConnectionFree), runCatching {
            requireRegistration(!cleanupUncertain && phase == null && !Thread.currentThread().isInterrupted)
            budget.remainingMillis(1)
        })
        var failure: Throwable? = null
        outcomes.forEach { it.exceptionOrNull()?.let { problem -> observeFailure(problem); failure = preferSignerRotationCleanup(failure, problem) } }
        throwIfSignalled()
        failure?.let { throw ComplaintTestNamespaceRegistrationExceptionV1() }
        cleanupProven = true
        if (reserved) coordinator.catalogRefreshCustody.releaseTestInitialAdmissionAfterCleanup(this)
        released = true
        closeFailure = null
    }

    internal fun requireActualCleanup() {
        requireConnectionFree()
        requireRegistration(caller === Thread.currentThread() && closed && cleanupProven && !cleanupUncertain && phase == null)
    }

    internal fun consumeAdmission(selected: ComplaintTestNamespaceRegistrationV1): TestInitialAdmissionControlsV1 {
        requireActualCleanup()
        requireRegistration(selected === registration && successful && released && closeFailure == null && !issued &&
            proof != null && providerStarted && providerClosed && providerFailure == null)
        checkNotNull(captured).requireReleased()
        checkNotNull(finalRelease).requireReleased()
        registration.requireInitialAdmissionTarget(assembly)
        budget.remainingMillis(1)
        issued = true
        return checkNotNull(capturedControls)
    }

    internal val path: PersistencePhasePath get() = when (stage) {
        Stage.CAPTURE -> PersistencePhasePath.COMPLAINT_TEST_INITIAL_ADMISSION_CAPTURE
        Stage.RELEASE -> PersistencePhasePath.COMPLAINT_TEST_INITIAL_ADMISSION_RELEASE
        else -> throw ComplaintTestNamespaceRegistrationExceptionV1()
    }

    internal fun requireExclusiveRelease(ownership: PersistencePhaseOwnership) {
        requireRunning()
        requireRegistration(ownership === coordinator.ownership && stage === Stage.RELEASE && phaseEntered && phase != null &&
            capturedControls != null && proof != null && providerClosed && providerFailure == null)
    }

    internal fun requireCapturedControls(observed: TestInitialAdmissionControlsV1) {
        requireRunning()
        if (stage === Stage.RELEASE) checkNotNull(capturedControls).requireSame(observed)
        else requireRegistration(stage === Stage.CAPTURE && captured == null)
    }

    private inner class TimedReadback(private val actual: S3CatalogReadbackAdapter) : CatalogReadbackPort {
        override fun listVersions(request: CatalogListRequest) = checked { actual.listVersions(request) }
        override fun openVersion(request: CatalogGetRequest): CatalogVersionBody {
            requireProviderRunning()
            val body = actual.openVersion(request)
            val check = runCatching(::requireProviderRunning)
            if (check.isFailure) return withSignerRotationCleanup({ check.getOrThrow(); body }, body::close)
            return object : CatalogVersionBody {
                override fun metadata() = checked { body.metadata() }
                override fun read(destination: ByteArray, offset: Int, length: Int): Int = checked { body.read(destination, offset, length) }
                override fun close() = body.close()
            }
        }
        private fun <T> checked(action: () -> T): T { requireProviderRunning(); return action().also { requireProviderRunning() } }
    }

    override fun toString(): String = "ComplaintTestInitialAdmissionV1(one-initial-identity-release,no-content-capability,redacted)"
    private enum class Stage { NEW, CAPTURE, READBACK, RELEASE }

    companion object {
        fun begin(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1): ComplaintTestInitialAdmissionV1 =
            ComplaintTestInitialAdmissionV1(registration, assembly, null, Clock.systemUTC())

        /** Raw transport/time only, never a supplied gate, proof, SQL outcome or admission latch. */
        internal fun withHttpFixture(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1,
            http: () -> SdkHttpClient, clock: Clock): ComplaintTestInitialAdmissionV1 =
            ComplaintTestInitialAdmissionV1(registration, assembly, http, clock)
    }
}

/** Compact original PROJECT comparisons. No FrozenV1, signature owner, provider or closed phase graph. */
internal class TestInitialAdmissionFactsV1(completed: CatalogTestRunFirstProjectionV1.State) {
    val scope: UUID = completed.frozen.scope
    val generation: Long = completed.frozen.generation
    val maximumGenerations: Int = completed.frozen.maximumGenerations
    val installationLimit: Long = completed.frozen.manifest().activationRecord.run.installationLimit
    val control = completed.snapshot.control
    val history = completed.snapshot.history
    val projection = checkNotNull(completed.snapshot.projectionRows)
    val tail = checkNotNull(completed.snapshot.completedTail)
    val projectedAt: Instant = checkNotNull(tail.projectedAt)
    val noticeIds: Set<UUID> = completed.frozen.projectionNoticeIds().toSet()
    private val envelope = completed.signed.envelopeBytes()
    private val globalArguments = completed.signed.deliveryControlArguments()
    private val historyValues = arrayOf(*completed.frozen.preparedArguments(), *completed.signed.signatureArguments(),
        *tail.projectionArguments(), maximumGenerations + 1)
    private val effectValues = completed.frozen.projectionArguments(completed.signed, projectedAt)

    fun controlArguments(): Array<Any?> = copy(globalArguments)
    fun historyArguments(): Array<Any?> = copy(historyValues)
    fun effectArguments(): Array<Any?> = copy(effectValues)

    fun requireControl(current: CatalogTestRunActivationControlV1) {
        current.requireSame(control, closed = false)
        requireRegistration(current.maintenanceClosed && current.creationClosed && current.pendingToken == null && current.leaseToken == control.leaseToken)
    }

    fun requireRaw(read: CatalogTestRunActivationReadbackV3) {
        requireRegistration(read.signedEnvelopeBytes().contentEquals(envelope))
        tail.requireCustody(read.objectVersion, read.retainUntilEpochSecond,
            copyEvidence(read.primaryMetadata, read.tail.envelopeSha256), copyEvidence(read.replicaMetadata, read.tail.envelopeSha256))
    }

    private fun copy(values: Array<Any?>): Array<Any?> = values.map { value -> when (value) {
        is ByteArray -> value.copyOf()
        is Timestamp -> Timestamp.from(value.toInstant())
        else -> value
    } }.toTypedArray()

    override fun toString(): String = "TestInitialAdmissionFactsV1(bounded-original-comparisons,no-authority)"
}
