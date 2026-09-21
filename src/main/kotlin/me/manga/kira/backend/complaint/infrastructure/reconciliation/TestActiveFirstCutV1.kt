package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.EpochRotationPersistence
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
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationTailV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogReadbackRefreshCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReadbackHttpPairV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationCanonicalV3
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReadbackV3
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
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One genuine ACTIVE scoped first cut. Current read -> dual raw -> fresh lease -> paid REQUEST ->
 * actual NONPOOLED capture. One original J deadline covers every phase/read/cleanup; no stored row
 * can issue this successful original after UNKNOWN, timeout or cleanup loss. No seal/checkpoint writer.
 */
internal class TestActiveFirstCutV1 private constructor(
    internal val registration: ComplaintTestNamespaceRegistrationV1,
    internal val assembly: ComplaintTestProcessAssemblyV1,
    private val httpFactory: (() -> SdkHttpClient)?,
    private val clock: Clock,
) : AutoCloseable {
    internal val process = registration.process
    private val caller = Thread.currentThread()
    internal val coordinator = process.pools.catalogCoordinator
    private val policy = checkNotNull(process.activeFirstCut)
    internal val budget = PersistenceTimeBudget.start(policy.totalAttemptMillis, coordinator.ownership.nanoClock)
    internal val identity = TestActiveFirstCutIdentityV1.fromRegistration(registration)
    private val expected = CatalogTestRunActivationCanonicalV3.fromRetained(process, identity.installationLimit)
    private val readbackIdentity = copyFirstCutArguments(registration.activeReadbackArguments())
    private val readerBudget = budget.capped(process.catalogReadback.totalAttemptMillis)
    private val construction = S3CatalogReadbackAdapter.Construction()
    private val http = CatalogSignerRotationReadbackHttpPairV1(this, readerBudget)
    internal val owner = UUID.randomUUID()
    internal val nonce = UUID.randomUUID()
    private val signal = AtomicReference<Throwable?>()
    private val aborted = AtomicBoolean()
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
    private var controlOperation: TestActiveFirstCutOperationV1? = null
    private var requested: TestActiveFirstCutOperationV1? = null
    private var current: TestActiveFirstCutStateV1? = null
    private var tail: TestNamespaceRecoveryRegistrationTailV1? = null
    private var history: CatalogTestRunActivationHistoryV1? = null
    private var proof: CatalogTestRunActivationReadbackV3? = null
    private var lease: TestActiveFirstCutLeaseV1? = null
    private var native: TestActiveFirstCutCaptureOperationV1? = null
    private var detached: TestActiveFirstCutSlotV1? = null
    private var successful = false
    private var issued = false

    init {
        requireConnectionFree()
        requireTarget()
        requireFirstCut(readbackIdentity.size == 6 && readbackIdentity[1] == identity.scope && readbackIdentity[2] == identity.generation &&
            (readbackIdentity[3] as ByteArray).contentEquals(identity.activationCatalogHash()))
    }

    fun capture(primary: AwsSessionCredentials, replica: AwsSessionCredentials): Captured {
        requireFirstCut(caller === Thread.currentThread())
        var failure: Throwable? = null
        try {
            requireConnectionFree()
            requireRunning()
            requireFirstCut(stage === Stage.NEW)
            coordinator.catalogRefreshCustody.reserveTestActiveFirstCut(this)
            reserved = true
            stage = Stage.READ
            val read = coordinator.testActiveFirstCut.execute(this)
            current = read.releasedState()
            tail = read.releasedTail()
            history = read.releasedHistory()
            stage = Stage.READBACK
            observe(primary, replica)
            stage = Stage.LEASE
            controlOperation = null
            val acquired = coordinator.testActiveFirstCut.execute(this)
            current = acquired.releasedState()
            lease = checkNotNull(current).lease.also { requireFirstCut(it?.owner == owner) }
            stage = Stage.REQUEST
            controlOperation = null
            requested = coordinator.testActiveFirstCut.execute(this)
            current = checkNotNull(requested).releasedState()
            requireRunning()
            requireConnectionFree() // In particular, no global/scope/counter/run holder crosses the exclusive E wait.
            stage = Stage.CAPTURING
            val captured = policy.resource.capture(this)
            val row = captured.requireReleasedRow()
            requireFirstCut(native === captured && phase == null && providerClosed && providerFailure == null)
            detached = TestActiveFirstCutSlotV1.copy(identity, row)
            successful = true
            stage = Stage.CAPTURED
        } catch (problem: Throwable) {
            observeFailure(problem)
            abort()
            failure = problem
        } finally {
            runCatching(::close).exceptionOrNull()?.let { failure = preferSignerRotationCleanup(failure, it) }
        }
        throwIfSignalled()
        if (failure != null || !successful) throw (failure as? PersistencePhaseException ?: TestActiveFirstCutExceptionV1())
        return Captured.issue(this)
    }

    private fun observe(primary: AwsSessionCredentials, replica: AwsSessionCredentials) {
        requireProviderRunning()
        val reader = process.catalogReadback
        val at = clock.instant()
        val readPolicy = reader.policyAt(at)
        val configured = reader.sdkLimits
        val millis = readerBudget.remainingMillis(configured.requestTimeoutMillis)
        val limits = S3CatalogReadbackLimits(millis, minOf(configured.connectTimeoutMillis.toLong(), millis).toInt(),
            minOf(configured.readTimeoutMillis.toLong(), millis).toInt(), configured.maximumListBytes, configured.maximumErrorBytes, configured.maximumObjectBytes)
        providerStarted = true
        val read = withSignerRotationCleanup({
            val adapter = S3CatalogReadbackAdapter.openOwned(construction, reader.currentBundleBytes(), reader.chainPolicy.trustBundlePolicy,
                primary, replica, limits, { http.open(limits, httpFactory) }, System::nanoTime)
            CatalogTestRunActivationReadbackV3.verifyActiveCurrent(TimedReadback(adapter), reader.initialBundleBytes(), reader.currentBundleBytes(), readPolicy,
                identity.head, expected, checkNotNull(history))
        }, ::closeProviders)
        requireRunning()
        requireFirstCut(!clock.instant().isBefore(at))
        checkNotNull(tail).requireRaw(read, reader.chainPolicy.limits.maximumManifestRecords)
        requireFirstCut(providerClosed && providerFailure == null)
        proof = read
    }

    internal fun requireProviderRunning() {
        requireConnectionFree()
        requireRunning()
        readerBudget.remainingMillis(1)
        requireFirstCut(stage === Stage.READBACK && phase == null && tail != null && history != null && proof == null)
    }

    internal fun requirePersistence(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireRunning()
        requireFirstCut(ownership === coordinator.ownership && jdbc.dataSource === coordinator.dataSource && ownership.manager === coordinator.manager &&
            stage in setOf(Stage.READ, Stage.LEASE, Stage.REQUEST))
        if (stage !== Stage.READ) requireFirstCut(proof != null && providerClosed && providerFailure == null && current != null)
    }

    internal fun requirePhaseEntry(ownership: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireConnectionFree()
        requireRunning()
        requireFirstCut(ownership === coordinator.ownership && path === this.path && !phaseEntered && phase == null && controlOperation == null)
        phaseEntered = true
    }

    internal fun retainPhase(selected: PersistencePhaseContext) {
        requireRunning()
        requireFirstCut(phaseEntered && phase == null)
        phase = selected
    }

    internal fun observePhaseCleanup(selected: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testActiveFirstCutCleanupProven(this) ||
                selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) cleanupUncertain = true
            else { phase = null; phaseEntered = false }
        } catch (problem: Throwable) {
            cleanupUncertain = true
            observeFailure(problem)
        }
    }

    internal fun authenticate(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requirePersistence(ownership, jdbc)
        requireFirstCut(jdbc.query(TestActiveFirstCutSqlV1.authenticate,
            ResultSetExtractor { rows -> rows.next() && rows.getBoolean("valid") && !rows.wasNull() && !rows.next() }, *authenticationArguments()) == true)
        requirePersistence(ownership, jdbc)
    }

    internal fun authenticationArguments(): Array<Any?> {
        requireRunning()
        val descriptor = process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.CATALOG_COORDINATOR }
        val openings = descriptor.openings().map { it.publicDriverProperties() }
        val user = openings.map { it["user"] }.distinct().single()
        val database = openings.map { it["PGDBNAME"] }.distinct().single()
        return arrayOf(user, user, database)
    }

    internal fun requireMaintenanceGate(ownership: PersistencePhaseOwnership, path: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireRunning()
        requireFirstCut(ownership === coordinator.ownership && path === this.path && phaseEntered && phase != null)
        registration.requireActiveIdentityGate(gate)
    }

    internal fun requireCore(resource: EpochRotationPersistence) {
        requireRunning()
        requireFirstCut(resource === policy.resource && stage === Stage.CAPTURING && phase == null && !phaseEntered &&
            providerClosed && providerFailure == null && proof != null && lease != null)
        checkNotNull(requested).requireSealedHandoff()
    }

    internal fun requireCaptureMaintenanceGate(resource: EpochRotationPersistence, gate: PersistenceComplaintMaintenanceGateV1) {
        requireCore(resource)
        registration.requireActiveIdentityGate(gate)
    }

    internal fun retain(operation: TestActiveFirstCutOperationV1) {
        requireRunning()
        requireFirstCut(controlOperation == null && operation.original === this && operation.path === path)
        controlOperation = operation
    }

    internal fun retain(operation: TestActiveFirstCutCaptureOperationV1) {
        requireCore(policy.resource)
        requireFirstCut(native == null && operation.original === this)
        native = operation
    }

    internal fun requireCapture(operation: TestActiveFirstCutCaptureOperationV1) {
        requireCore(policy.resource)
        requireFirstCut(native === operation && operation.original === this)
    }

    internal fun requirePrior(observation: TestActiveFirstCutStateV1) {
        requireRunning()
        val before = current
        if (stage === Stage.READ) requireFirstCut(before == null) else checkNotNull(before).requireSamePhysical(observation)
        if (stage === Stage.REQUEST || stage === Stage.CAPTURING) observation.requireCurrent(checkNotNull(lease))
    }

    internal fun requireRawComparisons(selectedTail: TestNamespaceRecoveryRegistrationTailV1, selectedHistory: CatalogTestRunActivationHistoryV1) {
        requireRunning()
        requireFirstCut(selectedTail.token == readbackIdentity[0] && selectedTail.scope == identity.scope && selectedTail.generation == identity.generation &&
            selectedTail.envelopeHash.contentEquals(readbackIdentity[3] as ByteArray) && selectedTail.unsigned.contentEquals(readbackIdentity[4] as ByteArray) &&
            selectedTail.unsignedHash.contentEquals(readbackIdentity[5] as ByteArray))
        if (stage !== Stage.READ) {
            checkNotNull(tail).requireSame(selectedTail)
            checkNotNull(history).requireSame(selectedHistory)
            requireFirstCut(proof != null && providerClosed && providerFailure == null)
        }
    }

    internal fun historyArguments(): Array<Any?> = arrayOf(readbackIdentity[0], identity.scope, identity.generation,
        identity.activationCatalogHash(), process.catalogReadback.chainPolicy.limits.maximumGenerations + 1)

    internal fun selectedLease(): TestActiveFirstCutLeaseV1 {
        requireRunning()
        return checkNotNull(lease)
    }

    internal fun requireCustody(selected: CatalogReadbackRefreshCustodyV1) = requireFirstCut(
        caller === Thread.currentThread() && coordinator.catalogRefreshCustody === selected,
    )

    private fun requireTarget() {
        registration.requireActiveIdentityTarget(assembly)
        requireFirstCut(assembly.target === process && process.activeFirstCut === policy)
        policy.requireRetained(process.pools, process.consumers.journalConfiguration, process.ordinarySeal)
    }

    private fun requireRunning() {
        throwIfSignalled()
        requireFirstCut(caller === Thread.currentThread() && !closed && !released && !aborted.get() && !cleanupUncertain && !Thread.currentThread().isInterrupted)
        budget.remainingMillis(1)
        requireTarget()
        if (reserved) coordinator.catalogRefreshCustody.requireTestActiveFirstCut(this)
        budget.remainingMillis(1)
    }

    internal fun abort() { aborted.set(true) }

    /** Poison only, before the first close; later native reclamation cannot repair a missed cleanup cut. */
    internal fun observeUnsettledNativeCleanup(resource: EpochRotationPersistence) {
        requireFirstCut(caller === Thread.currentThread() && resource === policy.resource &&
            stage === Stage.CAPTURING && aborted.get() && !closed)
        cleanupUncertain = true
    }

    internal fun observeFailure(problem: Throwable) {
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("ACTIVE TEST first cut cancelled.")
            problem is InterruptedException || problem is InterruptedIOException ||
                (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) ||
                (problem is CatalogReadbackException && problem.code === CatalogReadbackFailure.INTERRUPTED) ||
                (problem is CatalogSignerRotationFreezeExceptionV1 && problem.code === CatalogSignerRotationFreezeFailureV1.INTERRUPTED) ->
                InterruptedException("ACTIVE TEST first cut interrupted.")
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
        requireFirstCut(caller === Thread.currentThread())
        if (closed) { closeFailure?.let { throw it }; return }
        closed = true
        closeFailure = TestActiveFirstCutExceptionV1()
        val outcomes = listOf(runCatching(::closeProviders), runCatching(::requireConnectionFree), runCatching {
            requireFirstCut(!cleanupUncertain && phase == null && !Thread.currentThread().isInterrupted)
            budget.remainingMillis(1)
        })
        var failure: Throwable? = null
        outcomes.forEach { it.exceptionOrNull()?.let { problem -> observeFailure(problem); failure = preferSignerRotationCleanup(failure, problem) } }
        throwIfSignalled()
        failure?.let { throw TestActiveFirstCutExceptionV1() }
        cleanupProven = true
        if (reserved) coordinator.catalogRefreshCustody.releaseTestActiveFirstCutAfterCleanup(this)
        released = true
        closeFailure = null
    }

    internal fun requireActualCleanup() {
        requireConnectionFree()
        requireFirstCut(caller === Thread.currentThread() && closed && cleanupProven && !cleanupUncertain && phase == null)
    }

    private fun consumeCaptured(): TestActiveFirstCutSlotV1 {
        requireActualCleanup()
        requireFirstCut(successful && stage === Stage.CAPTURED && !aborted.get() && released && closeFailure == null && !issued &&
            providerStarted && providerClosed && providerFailure == null && proof != null && native != null && requested != null)
        requireTarget()
        budget.remainingMillis(1) // Issuance/final return tails still spend the original first-cut budget.
        issued = true
        return checkNotNull(detached)
    }

    internal val path: PersistencePhasePath get() = when (stage) {
        Stage.READ -> PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_READ
        Stage.LEASE -> PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_LEASE
        Stage.REQUEST -> PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_REQUEST
        else -> throw TestActiveFirstCutExceptionV1()
    }

    /** Historical known success only. No closed phase/JDBC/provider graph, local deadline or lease authority is retained. */
    internal class Captured private constructor(
        private val registration: ComplaintTestNamespaceRegistrationV1,
        private val assembly: ComplaintTestProcessAssemblyV1,
        private val slot: TestActiveFirstCutSlotV1,
    ) {
        private val claimed = AtomicBoolean()
        fun claimSeal(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1): SealHandoff =
            SealHandoff.claim(this, registration, assembly)

        private fun consumeSeal(selectedRegistration: ComplaintTestNamespaceRegistrationV1, selectedAssembly: ComplaintTestProcessAssemblyV1): TestActiveFirstCutSlotV1 {
            requireConnectionFree()
            requireFirstCut(registration === selectedRegistration && assembly === selectedAssembly)
            registration.requireActiveIdentityTarget(assembly)
            requireFirstCut(assembly.target === registration.process && claimed.compareAndSet(false, true))
            return slot
        }

        override fun toString(): String = "TestActiveFirstCutV1.Captured(historical-one-use,no-current-lease-or-health-authority)"
        companion object {
            internal fun issue(original: TestActiveFirstCutV1): Captured = Captured(original.registration, original.assembly, original.consumeCaptured())
        }

        internal fun claimDetached(selectedRegistration: ComplaintTestNamespaceRegistrationV1, selectedAssembly: ComplaintTestProcessAssemblyV1): TestActiveFirstCutSlotV1 =
            consumeSeal(selectedRegistration, selectedAssembly)
    }

    internal class SealHandoff private constructor(
        val registration: ComplaintTestNamespaceRegistrationV1,
        val assembly: ComplaintTestProcessAssemblyV1,
        val slot: TestActiveFirstCutSlotV1,
    ) {
        val process = registration.process
        fun requireRetained() {
            registration.requireActiveIdentityTarget(assembly)
            requireFirstCut(assembly.target === process && registration.process === process)
            checkNotNull(process.activeFirstCut).requireRetained(process.pools, process.consumers.journalConfiguration, process.ordinarySeal)
        }
        override fun toString(): String = "TestActiveFirstCutV1.SealHandoff(historical-captured-range,sealer-needs-fresh-authority)"
        companion object {
            internal fun claim(captured: Captured, registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1): SealHandoff =
                SealHandoff(registration, assembly, captured.claimDetached(registration, assembly)).also { it.requireRetained() }
        }
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

    override fun toString(): String = "TestActiveFirstCutV1(original-J,first-range-only,no-seal-checkpoint-or-content-authority)"
    private enum class Stage { NEW, READ, READBACK, LEASE, REQUEST, CAPTURING, CAPTURED }

    companion object {
        fun begin(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1): TestActiveFirstCutV1 =
            TestActiveFirstCutV1(registration, assembly, null, Clock.systemUTC())
        internal fun withHttpFixture(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1,
            http: () -> SdkHttpClient, clock: Clock): TestActiveFirstCutV1 = TestActiveFirstCutV1(registration, assembly, http, clock)
    }
}
