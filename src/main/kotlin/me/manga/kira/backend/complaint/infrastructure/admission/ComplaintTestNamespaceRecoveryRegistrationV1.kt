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
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackAdapter
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import me.manga.kira.backend.complaint.infrastructure.catalog.preferSignerRotationCleanup
import me.manga.kira.backend.complaint.infrastructure.catalog.withSignerRotationCleanup
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.io.InterruptedIOException
import java.time.Clock
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * Independent closed TEST recovery admission from the actual protected cold assembly. Never replays
 * PROJECT, consumes a first-projection ticket, changes writer generation or grants mutation ingress.
 * Current SQL facts are comparison inputs only until complete raw dual-copy authentication and the
 * original capture/readback/recheck/native/transaction cleanup have all finished under one budget.
 */
internal class ComplaintTestNamespaceRecoveryRegistrationAttemptV1 private constructor(
    internal val assembly: ComplaintTestProcessAssemblyV1,
    private val httpFactory: (() -> SdkHttpClient)?,
    private val clock: Clock,
) : AutoCloseable {
    internal val process = assembly.target
    private val caller = Thread.currentThread()
    internal val coordinator = process.pools.catalogCoordinator
    internal val budget = PersistenceTimeBudget.start(process.catalogReadback.totalAttemptMillis, coordinator.ownership.nanoClock)
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
    private var captured: TestNamespaceRecoveryRegistrationOperationV1? = null
    private var capturedSnapshot: TestNamespaceRecoveryRegistrationSnapshotV1? = null
    private var rechecked: TestNamespaceRecoveryRegistrationOperationV1? = null
    private var proof: CatalogTestRunActivationReadbackV3? = null
    private var gate: PersistenceComplaintMaintenanceGateV1? = null
    private var successful = false
    private var issued = false

    init {
        requireConnectionFree()
        process.requireRegistrationTarget()
        process.claimRecoveryRegistration(this)
        requireRegistration(process.ordinarySeal != null && process.ordinaryDenial != null)
    }

    fun register(primary: AwsSessionCredentials, replica: AwsSessionCredentials): ComplaintTestNamespaceRegistrationV1 {
        requireRegistration(caller === Thread.currentThread())
        var failure: Throwable? = null
        try {
            requireConnectionFree()
            requireRunning()
            requireRegistration(stage === Stage.NEW)
            coordinator.catalogRefreshCustody.reserveTestRecoveryRegistration(this)
            reserved = true
            stage = Stage.CAPTURE
            captured = coordinator.testNamespaceRecoveryRegistration.execute(this)
            capturedSnapshot = checkNotNull(captured).snapshot
            stage = Stage.READBACK
            observe(primary, replica)
            stage = Stage.RECHECK
            rechecked = coordinator.testNamespaceRecoveryRegistration.execute(this)
            checkNotNull(rechecked).snapshot.requireSame(checkNotNull(captured).snapshot)
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
        return ComplaintTestNamespaceRegistrationV1.issuedByRecovery(this)
    }

    private fun observe(primary: AwsSessionCredentials, replica: AwsSessionCredentials) {
        requireProviderRunning()
        val snapshot = checkNotNull(captured).snapshot
        val expected = CatalogTestRunActivationCanonicalV3.fromRetained(process, snapshot.binding.installationLimit)
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
            CatalogTestRunActivationReadbackV3.verifyRecovery(TimedReadback(adapter), reader.initialBundleBytes(), reader.currentBundleBytes(), policy,
                snapshot.tail.head, expected, snapshot.history)
        }, ::closeProviders)
        requireRunning()
        requireRegistration(!clock.instant().isBefore(at))
        snapshot.tail.requireRaw(read, reader.chainPolicy.limits.maximumManifestRecords)
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
            ownership.manager === coordinator.manager && stage in setOf(Stage.CAPTURE, Stage.RECHECK))
        if (stage === Stage.RECHECK) requireRegistration(proof != null && providerClosed && providerFailure == null && captured != null)
    }

    internal fun requirePhaseEntry(ownership: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireConnectionFree()
        requireRunning()
        requireRegistration(ownership === coordinator.ownership && path === PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_RECOVERY_REGISTRATION &&
            !phaseEntered && phase == null && (stage === Stage.CAPTURE && captured == null || stage === Stage.RECHECK && rechecked == null && proof != null))
        phaseEntered = true
    }

    internal fun retainPhase(selected: PersistencePhaseContext) {
        requireRunning()
        requireRegistration(phaseEntered && phase == null)
        phase = selected
    }

    internal fun observePhaseCleanup(selected: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testRecoveryRegistrationCleanupProven(this) ||
                selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) cleanupUncertain = true
            else { phase = null; phaseEntered = false; gate = null }
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
        requireRegistration(ownership === coordinator.ownership && path === PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_RECOVERY_REGISTRATION && phaseEntered && phase != null)
        requireRegistration(this.gate == null && gate.matchesClosedProjectedRecoveryScope(process.consumers.journalConfiguration.scope.id))
        capturedSnapshot?.tail?.requireGate(gate)
        this.gate = gate
    }

    /** The initial M gate is re-bound to the exact subsequently locked activation before any result. */
    internal fun requireCapturedGate(tail: TestNamespaceRecoveryRegistrationTailV1) {
        requireRunning()
        tail.requireGate(checkNotNull(gate))
    }

    internal fun requireCustody(selected: CatalogReadbackRefreshCustodyV1) = requireRegistration(
        caller === Thread.currentThread() && coordinator.catalogRefreshCustody === selected,
    )

    private fun requireRunning() {
        throwIfSignalled()
        requireRegistration(caller === Thread.currentThread() && !closed && !released && !cleanupUncertain && !Thread.currentThread().isInterrupted)
        budget.remainingMillis(1)
        requireRegistration(assembly.target === process)
        process.requireRegistrationTarget()
        if (reserved) coordinator.catalogRefreshCustody.requireTestRecoveryRegistration(this)
    }

    internal fun observeFailure(problem: Throwable) {
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("TEST recovery registration cancelled.")
            problem is InterruptedException || problem is InterruptedIOException ||
                (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) ||
                (problem is CatalogReadbackException && problem.code === CatalogReadbackFailure.INTERRUPTED) ||
                (problem is CatalogSignerRotationFreezeExceptionV1 && problem.code === CatalogSignerRotationFreezeFailureV1.INTERRUPTED) ||
                (problem is CatalogTestRunActivationCustodyExceptionV1 && problem.code === CatalogTestRunActivationCustodyFailureV1.INTERRUPTED) ||
                (problem is CatalogTestRunActivationExceptionV1 && problem.code === CatalogTestRunActivationFailureV1.INTERRUPTED) ->
                InterruptedException("TEST recovery registration interrupted.")
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
        if (reserved) coordinator.catalogRefreshCustody.releaseTestRecoveryRegistrationAfterCleanup(this)
        released = true
        closeFailure = null
    }

    internal fun requireActualCleanup() {
        requireConnectionFree()
        requireRegistration(caller === Thread.currentThread() && closed && cleanupProven && !cleanupUncertain && phase == null)
    }

    internal fun consumeRegistration(): TestNamespaceRecoveryRegistrationBindingV1 {
        requireActualCleanup()
        requireRegistration(successful && released && closeFailure == null && !issued && proof != null && providerStarted && providerClosed)
        checkNotNull(captured).requireReleased()
        checkNotNull(rechecked).requireReleased()
        requireRegistration(assembly.target === process)
        process.requireRegistrationTarget()
        budget.remainingMillis(1)
        issued = true
        return checkNotNull(rechecked).snapshot.binding
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

    override fun toString(): String = "ComplaintTestNamespaceRecoveryRegistrationAttemptV1(original-cold-runtime,closed-recovery-only,redacted)"
    private enum class Stage { NEW, CAPTURE, READBACK, RECHECK }

    companion object {
        fun begin(assembly: ComplaintTestProcessAssemblyV1): ComplaintTestNamespaceRecoveryRegistrationAttemptV1 =
            ComplaintTestNamespaceRecoveryRegistrationAttemptV1(assembly, null, Clock.systemUTC())

        /** Raw transport/time only. No supplied proof, registered state, SQL result or cleanup seam. */
        internal fun withHttpFixture(assembly: ComplaintTestProcessAssemblyV1, http: () -> SdkHttpClient,
            clock: Clock): ComplaintTestNamespaceRecoveryRegistrationAttemptV1 =
            ComplaintTestNamespaceRecoveryRegistrationAttemptV1(assembly, http, clock)
    }
}
