package me.manga.kira.backend.complaint.infrastructure.reconciliation

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
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStateV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationTailV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogReadbackRefreshCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReadbackHttpPairV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationCanonicalV3
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReadbackV3
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackAdapter
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import me.manga.kira.backend.complaint.infrastructure.catalog.preferSignerRotationCleanup
import me.manga.kira.backend.complaint.infrastructure.catalog.withSignerRotationCleanup
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealCustodyV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealProofV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalCodecV1
import me.manga.kira.backend.security.TestTerminalContentV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.io.InterruptedIOException
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/** Separate EMPTY pre-VERIFY original. No Captured/Recovered/A-success reconstruction or recharge.
 * A committed durable winner is comparison material, never proof of this original's commit/cleanup.
 */
internal class TestActiveOrdinarySealRecoveryV1 private constructor(
    internal val registration: ComplaintTestNamespaceRegistrationV1,
    internal val assembly: ComplaintTestProcessAssemblyV1,
    private val httpFixture: (() -> SdkHttpClient)?, private val clock: Clock,
) {
    internal val process = registration.process
    internal val identity = TestActiveFirstCutIdentityV1.fromRegistration(registration)
    internal val slot get() = checkNotNull(captured).current
    internal val coordinator = process.pools.catalogCoordinator
    internal val routing = process.consumers.journalRouting
    internal val acquisition = checkNotNull(process.ordinarySeal)
    private val recipe = checkNotNull(process.activeOrdinarySealRecovery)
    internal val scope = identity.scope
    internal val writer = identity.writer.toString()
    internal val cutoff = 1L
    internal val attemptId = UUID.randomUUID()
    private val nanoClock = TestActiveSealNanoClockV1(coordinator.ownership.nanoClock)
    internal val budget = PersistenceTimeBudget.start(routing.journalConfiguration.declaration().limits.deadlines.epochSealMillis.toLong(), nanoClock)
    internal val codec = TestTerminalCodecV1.fromRetained(routing, acquisition.nanoTime)
    internal val codecAttempt = codec.startAttempt(TestTerminalCodecKindV1.EPOCH_SEAL, budget)
    internal val runContext = TestTerminalRunContextV1(scope.toString(), identity.generation, hex(identity.activationCatalogHash()),
        hex(identity.configurationHash()), identity.sealEncodingSha256)
    private val renewal = TestActiveSealRenewalWindowV1(budget)
    internal var leaseToken = 0L
        private set
    internal var step = TestActiveOrdinarySealRecoveryStepV1.READ
        private set
    internal val path = PersistencePhasePath.COMPLAINT_TEST_ACTIVE_ORDINARY_SEAL_RECOVERY
    internal val lowerCutoffKey = "${routing.journalConfiguration.ordinaryPrefix}writer/$writer/epoch/${1L.toString().padStart(19, '0')}/"
    internal val upperCutoffKey = "${routing.journalConfiguration.ordinaryPrefix}writer/$writer/epoch/${(cutoff + 1).toString().padStart(19, '0')}/"
    private val caller = Thread.currentThread()
    private val failure = AtomicReference<Throwable?>()
    private var started = false
    private var finished = false
    private var successful = false
    private var phase: PersistencePhaseContext? = null
    private var phaseEntered = false
    private var cleanupUncertain = false
    private var jdbc: JdbcTemplate? = null
    private var captured: TestActiveOrdinarySealRecoveryOperationV1? = null
    private var prepared: TestActiveOrdinarySealRecoveryOperationV1? = null
    private var frozen: TestActiveOrdinarySealRecoveryOperationV1? = null
    private var verified: TestActiveOrdinarySealRecoveryOperationV1? = null
    private var wireCandidate: TestTerminalDurableRowV1? = null
    private var content: TestTerminalContentV1? = null
    private var custody: TestOrdinarySealCustodyV1? = null
    private var proof: TestOrdinarySealProofV1? = null
    private var acquired: TestActiveOrdinarySealRecoveryOperationV1? = null
    private var localClosed = false
    internal var previous: TestActiveOrdinarySealRecoveryOperationV1? = null
        private set
    private val readbackIdentity = registration.activeReadbackArguments()
    private val expected = CatalogTestRunActivationCanonicalV3.fromRetained(process, identity.installationLimit)
    private val construction = S3CatalogReadbackAdapter.Construction()
    private val readbackBudget = budget.capped(process.catalogReadback.totalAttemptMillis)
    private val http = CatalogSignerRotationReadbackHttpPairV1(this, readbackBudget)
    private var readbackStage = false
    private var readbackReserved = false
    private var providerClosed = false
    private var providerFailure: Throwable? = null
    private var raw: CatalogTestRunActivationReadbackV3? = null

    init {
        requireConnectionFree(); requireTarget()
        requireActiveSealRecovery(identity.writer.toString() == routing.journalConfiguration.declaration().writer.generationId &&
            scope == routing.journalConfiguration.scope.id && routing.journalConfiguration.registeredAdminBatchDelete)
    }

    fun recover(primary: AwsSessionCredentials, replica: AwsSessionCredentials): Completed {
        throwIfSignalled()
        requireActiveSealRecovery(caller === Thread.currentThread() && !started)
        started = true
        try {
            requireRunning()
            coordinator.catalogRefreshCustody.reserveTestActiveOrdinarySealRecovery(this); readbackReserved = true
            captured = execute(TestActiveOrdinarySealRecoveryStepV1.READ)
            prepared = captured
            if (preparedRow().state === TestTerminalDurableStateV1.WIRE_FROZEN) frozen = captured
            observe(primary, replica)
            renewal.beginDispatch(first = true)
            acquired = execute(TestActiveOrdinarySealRecoveryStepV1.ACQUIRE)
            renewal.committedAndReleased()
            val canonical = preparedRow()
            val bytes = canonical.canonicalBytes()
            content = try { codec.restoreCanonical(TestTerminalCodecKindV1.EPOCH_SEAL, bytes,
                canonical.binding.routingKeyId, canonical.binding.objectKey, canonical.canonicalSha256, codecAttempt) }
            finally { bytes.fill(0) }
            repeat(2) { renew(); execute(TestActiveOrdinarySealRecoveryStepV1.EMPTY) }
            localClosed = true
            renew()
            val native = TestOrdinarySealCustodyV1.reserve(this).also { custody = it }
            native.acquire()
            if (frozen == null) {
                renew()
                // Only a missing wire stage may generate randomness. Existing frozen bytes never enter this branch.
                val envelope = native.seal()
                try {
                    val at = acquisition.sampleUtc().truncatedTo(ChronoUnit.SECONDS)
                    requireActiveSealRecovery(!at.isBefore(canonical.binding.createdAt))
                    val retainUntil = maxOf(canonical.binding.retentionFloor, acquisition.newRetention(codecAttempt, canonical.binding.createdAt))
                    val wire = envelope.wireBytes()
                    wireCandidate = try { TestTerminalDurableRowV1.frozen(canonical, wire, retainUntil, at) } finally { wire.fill(0) }
                } finally { envelope.close() }
                renew()
                frozen = execute(TestActiveOrdinarySealRecoveryStepV1.FREEZE)
                requireReleased(frozen)
            }
            renew()
            proof = native.publish() // Frozen missing-object retention may refuse; no repair/retry/extension.
            native.close()
            checkNotNull(proof).requireOriginal(this)
            renew()
            verified = execute(TestActiveOrdinarySealRecoveryStepV1.VERIFY)
            requireReleased(verified); requireRunning()
            successful = true
        } catch (problem: Throwable) { observeFailure(problem) }
        finally {
            runCatching { custody?.close() }.exceptionOrNull()?.let(::observeFailure)
            runCatching(::closeReadback).exceptionOrNull()?.let(::observeFailure)
            if (readbackReserved) runCatching {
                coordinator.catalogRefreshCustody.releaseTestActiveOrdinarySealRecoveryAfterCleanup(this); readbackReserved = false
            }.exceptionOrNull()?.let(::observeFailure)
            content?.close(); wireCandidate?.close(); prepared?.intent?.close()
            if (frozen !== prepared) frozen?.intent?.close()
            readbackIdentity.filterIsInstance<ByteArray>().forEach { it.fill(0) }
            finished = true
        }
        throwIfSignalled(); requireActiveSealRecovery(successful)
        return Completed.issue(this)
    }

    private fun execute(selected: TestActiveOrdinarySealRecoveryStepV1): TestActiveOrdinarySealRecoveryOperationV1 {
        requireConnectionFree(); requireRunning(); requireActiveSealRecovery(phase == null && !phaseEntered)
        step = selected
        return coordinator.testActiveOrdinarySealRecovery.execute(this).also { previous = it }
    }
    internal fun renew() {
        requireCutoffRunning()
        renewal.beginDispatch(first = false)
        execute(TestActiveOrdinarySealRecoveryStepV1.RENEW)
        renewal.committedAndReleased()
    }
    internal fun phaseBudget(): PersistenceTimeBudget = if (step === TestActiveOrdinarySealRecoveryStepV1.READ)
        budget.capped(2_000) else renewal.phaseBudget()
    internal fun retainAcquiringToken(operation: TestActiveOrdinarySealRecoveryOperationV1, token: Long) {
        requireRunning()
        val before = checkNotNull(previous).current
        requireActiveSealRecovery(operation.original === this && step === TestActiveOrdinarySealRecoveryStepV1.ACQUIRE && leaseToken == 0L &&
            before.leaseToken < Long.MAX_VALUE && token == before.leaseToken + 1 && token > slot.captureToken && token > slot.requestToken)
        leaseToken = token // No use until this exact phase's known COMMIT and physical release.
    }
    internal fun nativeContinuationBudget(): PersistenceTimeBudget { requireConnectionFree(); requireCutoffRunning(); return renewal.providerBudget() }
    internal fun remainingNativeContinuationMillis(ceiling: Int): Int { requireConnectionFree(); requireCutoffRunning(); return renewal.remainingMillis(ceiling) }

    internal fun frozenCandidate(operation: TestActiveOrdinarySealRecoveryOperationV1): TestTerminalDurableRowV1 {
        requireActiveSealRecovery(operation.original === this && step === TestActiveOrdinarySealRecoveryStepV1.FREEZE); return checkNotNull(wireCandidate)
    }
    internal fun providerProof(operation: TestActiveOrdinarySealRecoveryOperationV1): TestOrdinarySealProofV1 {
        requireActiveSealRecovery(operation.original === this && step === TestActiveOrdinarySealRecoveryStepV1.VERIFY)
        return checkNotNull(proof).also { it.requireOriginal(this) }
    }
    internal fun preparedRow() = checkNotNull(checkNotNull(prepared).intent)
    internal fun frozenRow() = checkNotNull(checkNotNull(frozen).intent)
    internal fun content() = checkNotNull(content)
    internal fun requireObservedIntent(value: TestTerminalDurableRowV1?) {
        requireActiveSealRecovery(step !== TestActiveOrdinarySealRecoveryStepV1.READ)
        val observed = checkNotNull(value)
        requireSameCanonical(preparedRow(), observed)
        if (frozen != null) requireSameFrozen(frozenRow(), observed)
    }
    internal fun requireSameCanonical(a: TestTerminalDurableRowV1, b: TestTerminalDurableRowV1) {
        requireActiveSealRecovery(a.binding == b.binding && a.canonicalSha256 == b.canonicalSha256)
        val x = a.canonicalBytes(); val y = b.canonicalBytes()
        try { requireActiveSealRecovery(x.contentEquals(y)) } finally { x.fill(0); y.fill(0) }
    }
    internal fun requireSameFrozen(a: TestTerminalDurableRowV1, b: TestTerminalDurableRowV1) {
        requireSameCanonical(a, b)
        requireActiveSealRecovery(a.state === TestTerminalDurableStateV1.WIRE_FROZEN && b.state === a.state && a.wireSha256 == b.wireSha256 &&
            a.retainUntil == b.retainUntil && a.frozenAt == b.frozenAt && a.metadataSha256 == b.metadataSha256 && a.checksumSha256 == b.checksumSha256)
        val x = checkNotNull(a.wireBytes()); val y = checkNotNull(b.wireBytes())
        try { requireActiveSealRecovery(x.contentEquals(y)) } finally { x.fill(0); y.fill(0) }
    }
    internal fun requireUnverifiedSeal() { requireProvider(checkNotNull(custody), true); requireActiveSealRecovery(proof == null) }
    internal fun requireListedSealVersion(version: String?) {
        requireProvider(checkNotNull(custody), true)
        // This original restores a prior intent, never a prior proof. LIST still is not authentication.
        requireActiveSealRecovery(proof == null)
        if (version != null) me.manga.kira.backend.complaint.infrastructure.journal.aws.requireJournalVersion(version)
    }
    internal fun requireReservation() { requireConnectionFree(); requireCutoffRunning(); requireReleased(prepared); requireReleased(acquired); requireActiveSealRecovery(custody == null && localClosed) }
    internal fun requireProvider(selected: TestOrdinarySealCustodyV1, publication: Boolean) {
        requireConnectionFree(); requireCutoffRunning(); requireActiveSealRecovery(custody === selected && phase == null && !phaseEntered)
        requireReleased(prepared); requireReleased(acquired); requireActiveSealRecovery(localClosed)
        if (publication) { requireReleased(frozen); requireActiveSealRecovery(frozenRow().state === TestTerminalDurableStateV1.WIRE_FROZEN) }
    }
    internal fun requirePersistence(ownership: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requireRunning(); requireActiveSealRecovery(ownership === coordinator.ownership && selected.dataSource === coordinator.dataSource && ownership.manager === coordinator.manager)
        if (jdbc == null) jdbc = selected
        requireActiveSealRecovery(jdbc === selected)
    }
    internal fun requirePhaseEntry(ownership: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireConnectionFree(); requireRunning()
        requireActiveSealRecovery(ownership === coordinator.ownership && path === this.path && phase == null && !phaseEntered)
        phaseEntered = true
    }
    internal fun retainPhase(selected: PersistencePhaseContext) { requireRunning(); requireActiveSealRecovery(phaseEntered && phase == null); phase = selected }
    internal fun observePhaseCleanup(selected: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testActiveOrdinarySealRecoveryCleanupProven(this) || selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) {
                cleanupUncertain = true; observeFailure(TestActiveOrdinarySealRecoveryExceptionV1())
            } else { phase = null; phaseEntered = false }
        } catch (problem: Throwable) { cleanupUncertain = true; observeFailure(problem) }
    }
    internal fun authenticate(ownership: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requirePersistence(ownership, selected)
        val openings = process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.CATALOG_COORDINATOR }.openings().map { it.publicDriverProperties() }
        val user = openings.map { it["user"] }.distinct().single(); val database = openings.map { it["PGDBNAME"] }.distinct().single()
        requireActiveSealRecovery(selected.query("SELECT session_user = ? AND current_user = ? AND current_database() = ? AS valid",
            ResultSetExtractor { rows -> rows.next() && rows.getBoolean("valid") && !rows.wasNull() && !rows.next() }, user, user, database) == true)
        requirePersistence(ownership, selected)
    }
    internal fun requireMaintenanceGate(ownership: PersistencePhaseOwnership, path: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireRunning(); requireActiveSealRecovery(ownership === coordinator.ownership && path === this.path && phaseEntered && phase != null)
        registration.requireActiveIdentityGate(gate)
    }
    internal fun requireCutoffRunning() { requireRunning(); requireActiveSealRecovery(leaseToken > slot.captureToken); renewal.remainingMillis(1) }
    private fun requireTarget() {
        registration.requireActiveIdentityTarget(assembly)
        requireActiveSealRecovery(assembly.target === process && process.activeOrdinarySealRecovery === recipe)
        recipe.requireRetained(process.pools, routing, process.activeFirstCut, acquisition)
        acquisition.requireRetained(routing, process.publicationLanes)
    }
    private fun requireRunning() {
        throwIfSignalled()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("ACTIVE TEST seal interrupted.")
        requireActiveSealRecovery(caller === Thread.currentThread() && started && !finished && !cleanupUncertain)
        budget.remainingMillis(1); codecAttempt.remainingMillis(1) // NOT remainingProviderMillis: avoid a custody recursion.
        requireTarget()
        if (readbackReserved) coordinator.catalogRefreshCustody.requireTestActiveOrdinarySealRecovery(this)
    }
    private fun requireReleased(operation: TestActiveOrdinarySealRecoveryOperationV1?) { requireRunning(); requireActiveSealRecovery(phase == null && !phaseEntered); checkNotNull(operation).requireReleased() }
    internal fun observeFailure(problem: Throwable) {
        renewal.poison()
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("ACTIVE TEST seal cancelled.")
            problem is InterruptedException || problem is InterruptedIOException || problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED ->
                InterruptedException("ACTIVE TEST seal interrupted.")
            else -> TestActiveOrdinarySealRecoveryExceptionV1()
        }
        while (true) {
            val previous = failure.get()
            if (previous is Error || previous is CancellationException && retained !is Error || previous is InterruptedException && retained !is Error && retained !is CancellationException || previous != null && retained is TestActiveOrdinarySealRecoveryExceptionV1) return
            if (failure.compareAndSet(previous, retained)) return
        }
    }
    internal fun throwIfSignalled() { failure.get()?.let { if (it is InterruptedException) Thread.currentThread().interrupt(); throw it } }

    internal fun historyArguments(): Array<Any?> = arrayOf(readbackIdentity[0], scope, identity.generation, identity.activationCatalogHash(), process.catalogReadback.chainPolicy.limits.maximumGenerations + 1)
    internal fun requireAdmissionComparisons(operation: TestActiveOrdinarySealRecoveryOperationV1, tail: TestNamespaceRecoveryRegistrationTailV1, history: CatalogTestRunActivationHistoryV1) {
        requireRunning(); requireActiveSealRecovery(operation.original === this && tail.token == readbackIdentity[0] && tail.scope == scope && tail.generation == identity.generation &&
            tail.envelopeHash.contentEquals(readbackIdentity[3] as ByteArray) && tail.unsigned.contentEquals(readbackIdentity[4] as ByteArray) && tail.unsignedHash.contentEquals(readbackIdentity[5] as ByteArray))
        if (step !== TestActiveOrdinarySealRecoveryStepV1.READ) { checkNotNull(captured).tail.requireSame(tail); checkNotNull(captured).history.requireSame(history); requireRawReleased() }
    }
    internal fun requireRawReleased() { requireRunning(); requireActiveSealRecovery(raw != null && providerClosed && providerFailure == null && !readbackStage) }
    private fun observe(primary: AwsSessionCredentials, replica: AwsSessionCredentials) {
        readbackStage = true
        val reader = process.catalogReadback
        val at = clock.instant()
        val configured = reader.sdkLimits
        val millis = readbackBudget.remainingMillis(configured.requestTimeoutMillis)
        val limits = S3CatalogReadbackLimits(millis, minOf(configured.connectTimeoutMillis.toLong(), millis).toInt(), minOf(configured.readTimeoutMillis.toLong(), millis).toInt(),
            configured.maximumListBytes, configured.maximumErrorBytes, configured.maximumObjectBytes)
        val result = withSignerRotationCleanup({
            val adapter = S3CatalogReadbackAdapter.openOwned(construction, reader.currentBundleBytes(), reader.chainPolicy.trustBundlePolicy,
                primary, replica, limits, { http.open(limits, httpFixture) }, acquisition.nanoTime)
            CatalogTestRunActivationReadbackV3.verifyActiveCurrent(TimedReadback(adapter), reader.initialBundleBytes(), reader.currentBundleBytes(), reader.policyAt(at),
                identity.head, expected, checkNotNull(captured).history)
        }, ::closeReadback)
        requireRunning(); readbackBudget.remainingMillis(1)
        requireActiveSealRecovery(!clock.instant().isBefore(at) && providerClosed && providerFailure == null)
        checkNotNull(captured).tail.requireRaw(result, reader.chainPolicy.limits.maximumManifestRecords)
        raw = result; readbackStage = false
    }
    internal fun requireProviderRunning() {
        requireConnectionFree(); requireRunning(); readbackBudget.remainingMillis(1)
        requireActiveSealRecovery(readbackStage && phase == null && !phaseEntered && raw == null && !providerClosed)
        requireReleased(captured)
    }
    internal fun requireCustody(selected: CatalogReadbackRefreshCustodyV1) = requireActiveSealRecovery(caller === Thread.currentThread() && coordinator.catalogRefreshCustody === selected)
    private fun closeReadback() {
        val problem = runCatching { withSignerRotationCleanup({ construction.close() }, http::close) }.exceptionOrNull()
        if (problem != null) providerFailure = preferSignerRotationCleanup(providerFailure, problem)
        providerFailure?.let { throw it }
        providerClosed = true
    }
    internal fun requireActualReadbackCleanup() {
        requireConnectionFree(); requireCustody(coordinator.catalogRefreshCustody)
        requireActiveSealRecovery(providerClosed && providerFailure == null && phase == null && !phaseEntered && !cleanupUncertain)
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
                override fun read(destination: ByteArray, offset: Int, length: Int) = checked { body.read(destination, offset, length) }
                override fun close() = body.close()
            }
        }
        private fun <T> checked(action: () -> T): T { requireProviderRunning(); return action().also { requireProviderRunning() } }
    }

    /** Historical producer result only; not a healthy/content/Admin/checkpoint/terminal capability. */
    class Completed private constructor(val scope: UUID, val objectKey: String, val version: String, val ciphertextSha256: String) {
        companion object {
            internal fun issue(original: TestActiveOrdinarySealRecoveryV1): Completed {
                original.throwIfSignalled(); requireConnectionFree()
                requireActiveSealRecovery(original.caller === Thread.currentThread() && original.successful && original.finished && original.providerClosed && original.providerFailure == null &&
                    !original.readbackReserved && !original.cleanupUncertain && original.phase == null && !original.phaseEntered)
                // All closes and physical-release tails belong to the SAME original deadline.
                // A late cleanup cannot turn a durable historical row into this original's success.
                runCatching {
                    original.budget.remainingMillis(1); original.codecAttempt.remainingMillis(1); original.renewal.remainingMillis(1)
                }.exceptionOrNull()?.let(original::observeFailure)
                original.throwIfSignalled()
                checkNotNull(original.verified).requireReleased()
                val proof = checkNotNull(original.proof)
                proof.requireOriginal(original)
                // Bytes have been wiped; these immutable comparison properties remain usable.
                val frozen = original.frozenRow()
                return Completed(original.scope, frozen.binding.objectKey, proof.version, checkNotNull(frozen.wireSha256))
            }
        }
        override fun toString(): String = "TestActiveOrdinarySealRecoveryV1.Completed(historical-only,redacted)"
    }
    companion object {
        fun resume(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1): TestActiveOrdinarySealRecoveryV1 =
            TestActiveOrdinarySealRecoveryV1(registration, assembly, null, Clock.systemUTC())
        internal fun withHttpFixture(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1,
            http: () -> SdkHttpClient, clock: Clock): TestActiveOrdinarySealRecoveryV1 = TestActiveOrdinarySealRecoveryV1(registration, assembly, http, clock)
        private fun hex(value: ByteArray): String = try { HexFormat.of().formatHex(value) } finally { value.fill(0) }
    }
}

internal class TestActiveOrdinarySealRecoveryExceptionV1 : RuntimeException("ACTIVE TEST EMPTY pre-VERIFY seal recovery refused.", null, false, false)
internal fun requireActiveSealRecovery(value: Boolean) { if (!value) throw TestActiveOrdinarySealRecoveryExceptionV1() }
