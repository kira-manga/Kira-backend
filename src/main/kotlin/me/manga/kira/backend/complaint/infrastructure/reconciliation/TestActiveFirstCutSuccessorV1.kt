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
 * Distinct original for an already-paid RESERVED first cut. Current read -> dual raw -> fresh
 * higher lease -> either actual NONPOOLED requested capture or captured-only recheck/relinquish.
 * No REQUEST/charge, old-Captured construction, or CANONICAL/WIRE continuation. Every phase and
 * cleanup tail spends this original's one J deadline; a failed/UNKNOWN original stays spent.
 */
internal class TestActiveFirstCutSuccessorV1 private constructor(
    internal val registration: ComplaintTestNamespaceRegistrationV1,
    internal val assembly: ComplaintTestProcessAssemblyV1,
    private val httpFactory: (() -> SdkHttpClient)?,
    private val clock: Clock,
) : AutoCloseable {
    internal val process = registration.process
    private val caller = Thread.currentThread()
    internal val coordinator = process.pools.catalogCoordinator
    private val policy = checkNotNull(process.activeFirstCutSuccessor)
    internal val budget = PersistenceTimeBudget.start(policy.totalAttemptMillis, coordinator.ownership.nanoClock)
    internal val identity = TestActiveFirstCutIdentityV1.fromRegistration(registration)
    private val expected = CatalogTestRunActivationCanonicalV3.fromRetained(process, identity.installationLimit)
    private val readbackIdentity = copyFirstCutArguments(registration.activeReadbackArguments())
    private val readerBudget = budget.capped(process.catalogReadback.totalAttemptMillis)
    private val construction = S3CatalogReadbackAdapter.Construction()
    private val http = CatalogSignerRotationReadbackHttpPairV1(this, readerBudget)
    internal val owner = UUID.randomUUID()
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
    private var controlOperation: TestActiveFirstCutSuccessorOperationV1? = null
    private var fenced: TestActiveFirstCutSuccessorOperationV1? = null
    private var relinquished: TestActiveFirstCutSuccessorOperationV1? = null
    private var current: TestActiveFirstCutStateV1? = null
    private var accounting: TestActiveFirstCutSuccessorAccountingV1? = null
    private var tail: TestNamespaceRecoveryRegistrationTailV1? = null
    private var history: CatalogTestRunActivationHistoryV1? = null
    private var proof: CatalogTestRunActivationReadbackV3? = null
    private var lease: TestActiveFirstCutLeaseV1? = null
    private var native: TestActiveFirstCutSuccessorCaptureOperationV1? = null
    private var detached: TestActiveFirstCutSlotV1? = null
    private var successful = false
    private var issued = false

    init {
        requireConnectionFree()
        requireTarget()
        requireFirstCutSuccessor(readbackIdentity.size == 6 && readbackIdentity[1] == identity.scope && readbackIdentity[2] == identity.generation &&
            (readbackIdentity[3] as ByteArray).contentEquals(identity.activationCatalogHash()))
    }

    fun recover(primary: AwsSessionCredentials, replica: AwsSessionCredentials): Recovered {
        requireFirstCutSuccessor(caller === Thread.currentThread())
        var failure: Throwable? = null
        try {
            requireConnectionFree()
            requireRunning()
            requireFirstCutSuccessor(stage === Stage.NEW)
            coordinator.catalogRefreshCustody.reserveTestActiveFirstCutSuccessor(this)
            reserved = true
            stage = Stage.READ
            val read = coordinator.testActiveFirstCutSuccessor.execute(this)
            current = read.releasedState()
            accounting = read.releasedAccounting()
            tail = read.releasedTail()
            history = read.releasedHistory()
            stage = Stage.READBACK
            observe(primary, replica)
            stage = Stage.LEASE
            controlOperation = null
            val acquired = coordinator.testActiveFirstCutSuccessor.execute(this)
            fenced = acquired
            current = acquired.releasedState()
            lease = checkNotNull(current).lease.also { requireFirstCutSuccessor(it?.owner == owner) }
            requireRunning()
            requireConnectionFree() // In particular, no global/scope/counter/run holder crosses the exclusive E wait.
            val row = when (checkNotNull(current).state) {
                "REQUESTED" -> {
                    stage = Stage.CAPTURING
                    val captured = policy.resource.capture(this)
                    captured.requireReleasedRow().also {
                        requireFirstCutSuccessor(native === captured && relinquished == null)
                    }
                }
                "CAPTURED" -> {
                    stage = Stage.RELEASE
                    controlOperation = null
                    val releasedControl = coordinator.testActiveFirstCutSuccessor.execute(this)
                    relinquished = releasedControl
                    releasedControl.releasedState().also { requireFirstCutSuccessor(native == null) }
                }
                else -> throw TestActiveFirstCutSuccessorExceptionV1()
            }
            requireFirstCutSuccessor(phase == null && providerClosed && providerFailure == null)
            detached = TestActiveFirstCutSlotV1.copy(identity, row)
            successful = true
            stage = Stage.RECOVERED
        } catch (problem: Throwable) {
            observeFailure(problem)
            abort()
            failure = problem
        } finally {
            runCatching(::close).exceptionOrNull()?.let { failure = preferSignerRotationCleanup(failure, it) }
        }
        throwIfSignalled()
        if (failure != null || !successful) throw (failure as? PersistencePhaseException ?: TestActiveFirstCutSuccessorExceptionV1())
        return Recovered.issue(this)
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
        requireFirstCutSuccessor(!clock.instant().isBefore(at))
        checkNotNull(tail).requireRaw(read, reader.chainPolicy.limits.maximumManifestRecords)
        requireFirstCutSuccessor(providerClosed && providerFailure == null)
        proof = read
    }

    internal fun requireProviderRunning() {
        requireConnectionFree()
        requireRunning()
        readerBudget.remainingMillis(1)
        requireFirstCutSuccessor(stage === Stage.READBACK && phase == null && tail != null && history != null && proof == null)
    }

    internal fun requirePersistence(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireRunning()
        requireFirstCutSuccessor(ownership === coordinator.ownership && jdbc.dataSource === coordinator.dataSource && ownership.manager === coordinator.manager &&
            stage in setOf(Stage.READ, Stage.LEASE, Stage.RELEASE))
        if (stage !== Stage.READ) requireFirstCutSuccessor(proof != null && providerClosed && providerFailure == null && current != null)
    }

    internal fun requirePhaseEntry(ownership: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireConnectionFree()
        requireRunning()
        requireFirstCutSuccessor(ownership === coordinator.ownership && path === this.path && !phaseEntered && phase == null && controlOperation == null)
        phaseEntered = true
    }

    internal fun retainPhase(selected: PersistencePhaseContext) {
        requireRunning()
        requireFirstCutSuccessor(phaseEntered && phase == null)
        phase = selected
    }

    internal fun observePhaseCleanup(selected: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testActiveFirstCutSuccessorCleanupProven(this) ||
                selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) cleanupUncertain = true
            else { phase = null; phaseEntered = false }
        } catch (problem: Throwable) {
            cleanupUncertain = true
            observeFailure(problem)
        }
    }

    internal fun authenticate(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requirePersistence(ownership, jdbc)
        requireFirstCutSuccessor(jdbc.query(TestActiveFirstCutSuccessorSqlV1.authenticate,
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
        requireFirstCutSuccessor(ownership === coordinator.ownership && path === this.path && phaseEntered && phase != null)
        registration.requireActiveIdentityGate(gate)
    }

    internal fun requireCore(resource: EpochRotationPersistence) {
        requireRunning()
        requireFirstCutSuccessor(resource === policy.resource && stage === Stage.CAPTURING && phase == null && !phaseEntered &&
            providerClosed && providerFailure == null && proof != null && lease != null)
        checkNotNull(fenced).requireCaptureHandoff()
    }

    internal fun requireCaptureMaintenanceGate(resource: EpochRotationPersistence, gate: PersistenceComplaintMaintenanceGateV1) {
        requireCore(resource)
        registration.requireActiveIdentityGate(gate)
    }

    internal fun retain(operation: TestActiveFirstCutSuccessorOperationV1) {
        requireRunning()
        requireFirstCutSuccessor(controlOperation == null && operation.original === this && operation.path === path)
        controlOperation = operation
    }

    internal fun retain(operation: TestActiveFirstCutSuccessorCaptureOperationV1) {
        requireCore(policy.resource)
        requireFirstCutSuccessor(native == null && operation.original === this)
        native = operation
    }

    internal fun requireCapture(operation: TestActiveFirstCutSuccessorCaptureOperationV1) {
        requireCore(policy.resource)
        requireFirstCutSuccessor(native === operation && operation.original === this)
    }

    internal fun requirePrior(observation: TestActiveFirstCutStateV1) {
        requireRunning()
        val before = current
        if (stage === Stage.READ) requireFirstCutSuccessor(before == null) else checkNotNull(before).requireSamePhysical(observation)
        if (stage === Stage.RELEASE || stage === Stage.CAPTURING) observation.requireCurrent(checkNotNull(lease))
    }

    internal fun requireRawComparisons(selectedTail: TestNamespaceRecoveryRegistrationTailV1, selectedHistory: CatalogTestRunActivationHistoryV1) {
        requireRunning()
        requireFirstCutSuccessor(selectedTail.token == readbackIdentity[0] && selectedTail.scope == identity.scope && selectedTail.generation == identity.generation &&
            selectedTail.envelopeHash.contentEquals(readbackIdentity[3] as ByteArray) && selectedTail.unsigned.contentEquals(readbackIdentity[4] as ByteArray) &&
            selectedTail.unsignedHash.contentEquals(readbackIdentity[5] as ByteArray))
        if (stage !== Stage.READ) {
            checkNotNull(tail).requireSame(selectedTail)
            checkNotNull(history).requireSame(selectedHistory)
            requireFirstCutSuccessor(proof != null && providerClosed && providerFailure == null)
        }
    }

    internal fun requireAccounting(observed: TestActiveFirstCutSuccessorAccountingV1) {
        requireRunning()
        if (stage === Stage.READ) requireFirstCutSuccessor(accounting == null)
        else checkNotNull(accounting).requireSame(observed)
    }

    internal fun historyArguments(): Array<Any?> = arrayOf(readbackIdentity[0], identity.scope, identity.generation,
        identity.activationCatalogHash(), process.catalogReadback.chainPolicy.limits.maximumGenerations + 1)

    internal fun selectedLease(): TestActiveFirstCutLeaseV1 {
        requireRunning()
        return checkNotNull(lease)
    }

    /** Historical operation identity is discovered by the fixed READ, never supplied by a caller. */
    internal fun selectedSlot(): UUID {
        requireRunning()
        return checkNotNull(checkNotNull(current).id)
    }

    internal fun requireCustody(selected: CatalogReadbackRefreshCustodyV1) = requireFirstCutSuccessor(
        caller === Thread.currentThread() && coordinator.catalogRefreshCustody === selected,
    )

    private fun requireTarget() {
        registration.requireActiveIdentityTarget(assembly)
        requireFirstCutSuccessor(assembly.target === process && process.activeFirstCutSuccessor === policy)
        policy.requireRetained(process.pools, process.consumers.journalConfiguration, process.activeFirstCut, process.ordinarySeal)
    }

    private fun requireRunning() {
        throwIfSignalled()
        requireFirstCutSuccessor(caller === Thread.currentThread() && !closed && !released && !aborted.get() && !cleanupUncertain && !Thread.currentThread().isInterrupted)
        budget.remainingMillis(1)
        requireTarget()
        if (reserved) coordinator.catalogRefreshCustody.requireTestActiveFirstCutSuccessor(this)
        budget.remainingMillis(1)
    }

    internal fun abort() { aborted.set(true) }

    internal fun observeFailure(problem: Throwable) {
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("ACTIVE TEST first-cut successor cancelled.")
            problem is InterruptedException || problem is InterruptedIOException ||
                (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) ||
                (problem is CatalogReadbackException && problem.code === CatalogReadbackFailure.INTERRUPTED) ||
                (problem is CatalogSignerRotationFreezeExceptionV1 && problem.code === CatalogSignerRotationFreezeFailureV1.INTERRUPTED) ->
                InterruptedException("ACTIVE TEST first-cut successor interrupted.")
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
        requireFirstCutSuccessor(caller === Thread.currentThread())
        if (closed) { closeFailure?.let { throw it }; return }
        closed = true
        closeFailure = TestActiveFirstCutSuccessorExceptionV1()
        val outcomes = listOf(runCatching(::closeProviders), runCatching(::requireConnectionFree), runCatching {
            requireFirstCutSuccessor(!cleanupUncertain && phase == null && !Thread.currentThread().isInterrupted)
            budget.remainingMillis(1)
        })
        var failure: Throwable? = null
        outcomes.forEach { it.exceptionOrNull()?.let { problem -> observeFailure(problem); failure = preferSignerRotationCleanup(failure, problem) } }
        throwIfSignalled()
        failure?.let { throw TestActiveFirstCutSuccessorExceptionV1() }
        cleanupProven = true
        if (reserved) coordinator.catalogRefreshCustody.releaseTestActiveFirstCutSuccessorAfterCleanup(this)
        released = true
        closeFailure = null
    }

    internal fun requireActualCleanup() {
        requireConnectionFree()
        requireFirstCutSuccessor(caller === Thread.currentThread() && closed && cleanupProven && !cleanupUncertain && phase == null)
    }

    private fun consumeRecovered(): TestActiveFirstCutSlotV1 {
        requireActualCleanup()
        requireFirstCutSuccessor(successful && stage === Stage.RECOVERED && !aborted.get() && released && closeFailure == null && !issued &&
            providerStarted && providerClosed && providerFailure == null && proof != null && fenced != null &&
            ((native != null && relinquished == null) || (native == null && relinquished != null)))
        requireTarget()
        budget.remainingMillis(1) // Only this successor's on-time known result may issue its historical handoff.
        issued = true
        return checkNotNull(detached)
    }

    internal val path: PersistencePhasePath get() = when (stage) {
        Stage.READ -> PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_SUCCESSOR_READ
        Stage.LEASE -> PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_SUCCESSOR_LEASE
        Stage.RELEASE -> PersistencePhasePath.COMPLAINT_TEST_ACTIVE_FIRST_CUT_SUCCESSOR_RELEASE
        else -> throw TestActiveFirstCutSuccessorExceptionV1()
    }

    /** Historical known success only. No closed phase/JDBC/provider graph, local deadline or lease authority is retained. */
    internal class Recovered private constructor(
        private val registration: ComplaintTestNamespaceRegistrationV1,
        private val assembly: ComplaintTestProcessAssemblyV1,
        private val slot: TestActiveFirstCutSlotV1,
    ) {
        private val claimed = AtomicBoolean()
        fun claimSeal(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1): RecoveredSealHandoff =
            RecoveredSealHandoff.claim(this, registration, assembly)

        private fun consumeSeal(selectedRegistration: ComplaintTestNamespaceRegistrationV1, selectedAssembly: ComplaintTestProcessAssemblyV1): TestActiveFirstCutSlotV1 {
            requireConnectionFree()
            requireFirstCutSuccessor(registration === selectedRegistration && assembly === selectedAssembly)
            registration.requireActiveIdentityTarget(assembly)
            requireFirstCutSuccessor(assembly.target === registration.process && registration.process.activeFirstCutSuccessor != null &&
                claimed.compareAndSet(false, true))
            return slot
        }

        override fun toString(): String = "TestActiveFirstCutSuccessorV1.Recovered(historical-one-use,no-current-lease-or-health-authority)"
        companion object {
            internal fun issue(original: TestActiveFirstCutSuccessorV1): Recovered = Recovered(original.registration, original.assembly, original.consumeRecovered())
        }

        internal fun claimDetached(selectedRegistration: ComplaintTestNamespaceRegistrationV1, selectedAssembly: ComplaintTestProcessAssemblyV1): TestActiveFirstCutSlotV1 =
            consumeSeal(selectedRegistration, selectedAssembly)
    }

    internal class RecoveredSealHandoff private constructor(
        val registration: ComplaintTestNamespaceRegistrationV1,
        val assembly: ComplaintTestProcessAssemblyV1,
        val slot: TestActiveFirstCutSlotV1,
    ) {
        val process = registration.process
        fun requireRetained() {
            registration.requireActiveIdentityTarget(assembly)
            requireFirstCutSuccessor(assembly.target === process && registration.process === process)
            checkNotNull(process.activeFirstCutSuccessor).requireRetained(process.pools, process.consumers.journalConfiguration, process.activeFirstCut, process.ordinarySeal)
        }
        override fun toString(): String = "TestActiveFirstCutSuccessorV1.RecoveredSealHandoff(historical-reserved-range,sealer-needs-fresh-authority)"
        companion object {
            internal fun claim(recovered: Recovered, registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1): RecoveredSealHandoff =
                RecoveredSealHandoff(registration, assembly, recovered.claimDetached(registration, assembly)).also { it.requireRetained() }
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

    override fun toString(): String = "TestActiveFirstCutSuccessorV1(distinct-original-J,paid-reserved-only,no-seal-checkpoint-or-content-authority)"
    private enum class Stage { NEW, READ, READBACK, LEASE, RELEASE, CAPTURING, RECOVERED }

    companion object {
        fun resume(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1): TestActiveFirstCutSuccessorV1 =
            TestActiveFirstCutSuccessorV1(registration, assembly, null, Clock.systemUTC())
        internal fun withHttpFixture(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1,
            http: () -> SdkHttpClient, clock: Clock): TestActiveFirstCutSuccessorV1 = TestActiveFirstCutSuccessorV1(registration, assembly, http, clock)
    }
}

internal fun requireFirstCutSuccessor(value: Boolean) { if (!value) throw TestActiveFirstCutSuccessorExceptionV1() }
internal class TestActiveFirstCutSuccessorExceptionV1 : RuntimeException("ACTIVE TEST first-cut successor refused.", null, false, false)
