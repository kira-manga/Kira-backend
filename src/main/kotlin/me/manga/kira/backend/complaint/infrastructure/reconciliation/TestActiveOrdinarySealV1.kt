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
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableBindingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableKindV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStateV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEpochSealV1
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
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalPublisherFactoryV1
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * First-range ACTIVE seal original. Typed original/recovered history is claimed privately once,
 * then a fresh higher scoped lease and current raw/full-D are required. No checkpoint, health or APPLY.
 * Every SQL success below is known COMMIT AND physical release; failure/UNKNOWN poisons this original.
 */
internal class TestActiveOrdinarySealV1 private constructor(
    private val origin: Origin,
    private val httpFixture: (() -> SdkHttpClient)?, private val clock: Clock,
) {
    internal val registration = origin.registration
    internal val process = origin.process
    internal val slot = origin.slot
    internal val identity = slot.identity
    internal val coordinator = process.pools.catalogCoordinator
    internal val routing = process.consumers.journalRouting
    internal val acquisition = checkNotNull(process.ordinarySeal)
    internal val cutoffRecipe = checkNotNull(process.activeCutoffPublication)
    internal val scope = identity.scope
    internal val writer = identity.writer.toString()
    internal val cutoff = slot.epochEnd
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
    internal var step = TestActiveOrdinarySealStepV1.READ
        private set
    internal val path get() = if (step === TestActiveOrdinarySealStepV1.EVIDENCE) PersistencePhasePath.COMPLAINT_TEST_ACTIVE_CUTOFF_EVIDENCE
        else PersistencePhasePath.COMPLAINT_TEST_ACTIVE_ORDINARY_SEAL
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
    private var captured: TestActiveOrdinarySealOperationV1? = null
    private var prepared: TestActiveOrdinarySealOperationV1? = null
    private var frozen: TestActiveOrdinarySealOperationV1? = null
    private var verified: TestActiveOrdinarySealOperationV1? = null
    private var candidate: TestTerminalDurableRowV1? = null
    private var wireCandidate: TestTerminalDurableRowV1? = null
    private var content: TestTerminalContentV1? = null
    private var custody: TestOrdinarySealCustodyV1? = null
    private var proof: TestOrdinarySealProofV1? = null
    private var publisher: TestOwnerDeleteJournalPublisherFactoryV1? = null
    private var selectedEvidence: ReleasedTestActiveCutoffPublicationV1? = null
    private var epochAfter = 0L to ""
    private var keyAfter = "" // The lower bound itself is malformed event material and must not be skipped.
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
        requireConnectionFree(); origin.requireRetained()
        requireActiveSeal(identity.writer.toString() == routing.journalConfiguration.declaration().writer.generationId &&
            scope == routing.journalConfiguration.scope.id && slot.epochStart == 1L && cutoff == 1L && slot.epochAfter == 2L &&
            routing.journalConfiguration.registeredAdminBatchDelete)
        acquisition.requireRetained(routing, process.publicationLanes); cutoffRecipe.requireRetained(routing, process.publicationLanes)
    }

    fun seal(primary: AwsSessionCredentials, replica: AwsSessionCredentials): Verified {
        throwIfSignalled() // Reentry must preserve the original cancellation/error, not downgrade it.
        requireActiveSeal(caller === Thread.currentThread() && !started)
        started = true
        try {
            requireRunning()
            coordinator.catalogRefreshCustody.reserveTestActiveOrdinarySeal(this); readbackReserved = true
            captured = execute(TestActiveOrdinarySealStepV1.READ)
            observe(primary, replica)
            renewal.beginDispatch(first = true)
            execute(TestActiveOrdinarySealStepV1.ACQUIRE)
            renewal.committedAndReleased()
            val manifest = TestActiveCutoffPublicationsV1(this).resolve()
            renew()
            bindCanonical(manifest)
            prepared = execute(TestActiveOrdinarySealStepV1.CANONICAL)
            requireReleased(prepared)
            renew()
            val native = TestOrdinarySealCustodyV1.reserve(this).also { custody = it }
            native.acquire()
            renew() // Explicit actual DB renewal between native calls, never in a callback/clock.
            val envelope = native.seal()
            try {
                val canonical = preparedRow()
                val at = acquisition.sampleUtc().truncatedTo(ChronoUnit.SECONDS)
                requireActiveSeal(!at.isBefore(canonical.binding.createdAt))
                val retainUntil = maxOf(canonical.binding.retentionFloor, acquisition.newRetention(codecAttempt, canonical.binding.createdAt))
                val wire = envelope.wireBytes()
                wireCandidate = try { TestTerminalDurableRowV1.frozen(canonical, wire, retainUntil, at) } finally { wire.fill(0) }
            } finally { envelope.close() }
            renew()
            frozen = execute(TestActiveOrdinarySealStepV1.FREEZE)
            requireReleased(frozen)
            renew()
            proof = native.publish()
            native.close() // A returned proof is not cleanup. Actual native close+lane release must precede VERIFY.
            checkNotNull(proof).requireOriginal(this)
            renew()
            verified = execute(TestActiveOrdinarySealStepV1.VERIFY)
            requireReleased(verified); requireRunning()
            successful = true
        } catch (problem: Throwable) { observeFailure(problem) }
        finally {
            runCatching { custody?.close() }.exceptionOrNull()?.let(::observeFailure)
            runCatching { publisher?.close() }.exceptionOrNull()?.let(::observeFailure)
            runCatching(::closeReadback).exceptionOrNull()?.let(::observeFailure)
            if (readbackReserved) runCatching {
                coordinator.catalogRefreshCustody.releaseTestActiveOrdinarySealAfterCleanup(this); readbackReserved = false
            }.exceptionOrNull()?.let(::observeFailure)
            content?.close(); candidate?.close(); wireCandidate?.close(); prepared?.intent?.close(); frozen?.intent?.close()
            readbackIdentity.filterIsInstance<ByteArray>().forEach { it.fill(0) }
            finished = true
        }
        throwIfSignalled(); requireActiveSeal(successful)
        return Verified.issue(this)
    }

    private fun execute(selected: TestActiveOrdinarySealStepV1): TestActiveOrdinarySealOperationV1 {
        requireConnectionFree(); requireRunning(); requireActiveSeal(phase == null && !phaseEntered)
        step = selected
        return coordinator.testActiveOrdinarySeal.execute(this)
    }
    internal fun renew() {
        requireCutoffRunning()
        renewal.beginDispatch(first = false)
        execute(TestActiveOrdinarySealStepV1.RENEW)
        renewal.committedAndReleased()
    }
    internal fun epochPage(after: Pair<Long, String>): TestActiveOrdinarySealOperationV1 { epochAfter = after; return execute(TestActiveOrdinarySealStepV1.EPOCH_PAGE) }
    internal fun keyPage(after: String): TestActiveOrdinarySealOperationV1 { keyAfter = after; return execute(TestActiveOrdinarySealStepV1.KEY_PAGE) }
    internal fun epochCursor(): Pair<Long, String> { requireActiveSeal(step === TestActiveOrdinarySealStepV1.EPOCH_PAGE); return epochAfter }
    internal fun keyCursor(): String { requireActiveSeal(step === TestActiveOrdinarySealStepV1.KEY_PAGE); return keyAfter }
    internal fun persistObservation(work: ReleasedTestActiveCutoffPublicationV1) {
        work.requireOriginal(this); selectedEvidence = work
        try { execute(TestActiveOrdinarySealStepV1.EVIDENCE).requireReleased() } finally { selectedEvidence = null }
    }
    internal fun evidenceWork(operation: TestActiveOrdinarySealOperationV1): ReleasedTestActiveCutoffPublicationV1 {
        requireActiveSeal(operation.original === this && step === TestActiveOrdinarySealStepV1.EVIDENCE)
        return checkNotNull(selectedEvidence)
    }
    internal fun phaseBudget(): PersistenceTimeBudget = if (step === TestActiveOrdinarySealStepV1.EVIDENCE || step === TestActiveOrdinarySealStepV1.READ)
        budget.capped(2_000) else renewal.phaseBudget()
    internal fun retainAcquiringToken(operation: TestActiveOrdinarySealOperationV1, token: Long) {
        requireRunning(); requireActiveSeal(operation.original === this && step === TestActiveOrdinarySealStepV1.ACQUIRE && leaseToken == 0L &&
            token > slot.captureToken && token > slot.requestToken)
        leaseToken = token // Comparison only until that operation's COMMIT and physical release.
    }
    internal fun nativeContinuationBudget(): PersistenceTimeBudget { requireConnectionFree(); requireCutoffRunning(); return renewal.providerBudget() }
    internal fun remainingNativeContinuationMillis(ceiling: Int): Int { requireConnectionFree(); requireCutoffRunning(); return renewal.remainingMillis(ceiling) }

    private fun bindCanonical(manifest: TestActiveOrdinarySealManifestV1) {
        requireCutoffRunning()
        val created = awaitCanonicalSecond()
        val value = TestTerminalEpochSealV1(1, "EPOCH_SEAL", "A".repeat(43), writer, "TEST", scope.toString(), 1, cutoff, manifest.count, manifest.sha256, "", leaseToken)
        val encoded = codec.canonicalizeEpochSeal(value, codecAttempt).also { content = it }
        val binding = TestTerminalDurableBindingV1(slot.operationToken.toString(), runContext, routing.journalConfiguration.sha256, TestTerminalDurableKindV1.EPOCH_SEAL,
            0, encoded.route.journalId, encoded.route.objectKey, encoded.route.routingKeyId, writer, 1, cutoff, leaseToken, acquisition.newRetention(codecAttempt, created), created)
        val bytes = encoded.canonicalBytes()
        candidate = try { TestTerminalDurableRowV1.canonical(binding, bytes) } finally { bytes.fill(0) }
    }
    /**
     * A legitimate fast capture may still be in its fractional UTC second. Wait for an ACTUAL
     * admissible whole second, rather than burn the one-use handoff or invent a future timestamp.
     * No SQL, E, provider or J lane is held. Every short sleep is inside the same original/codec/
     * last-renewal deadlines; a late wake, interruption or backward clock irreversibly refuses it.
     */
    private fun awaitCanonicalSecond(): Instant {
        while (true) {
            requireConnectionFree(); requireCutoffRunning()
            val sampled = acquisition.sampleUtc().truncatedTo(ChronoUnit.SECONDS)
            requireCutoffRunning()
            if (!sampled.isBefore(slot.capturedAt)) return sampled
            Thread.sleep(remainingNativeContinuationMillis(25).toLong())
        }
    }
    internal fun canonicalCandidate(operation: TestActiveOrdinarySealOperationV1): TestTerminalDurableRowV1 {
        requireActiveSeal(operation.original === this && step === TestActiveOrdinarySealStepV1.CANONICAL); return checkNotNull(candidate)
    }
    internal fun frozenCandidate(operation: TestActiveOrdinarySealOperationV1): TestTerminalDurableRowV1 {
        requireActiveSeal(operation.original === this && step === TestActiveOrdinarySealStepV1.FREEZE); return checkNotNull(wireCandidate)
    }
    internal fun providerProof(operation: TestActiveOrdinarySealOperationV1): TestOrdinarySealProofV1 {
        requireActiveSeal(operation.original === this && step === TestActiveOrdinarySealStepV1.VERIFY)
        return checkNotNull(proof).also { it.requireOriginal(this) }
    }
    internal fun preparedRow() = checkNotNull(checkNotNull(prepared).intent)
    internal fun frozenRow() = checkNotNull(checkNotNull(frozen).intent)
    internal fun content() = checkNotNull(content)
    internal fun requireObservedIntent(value: TestTerminalDurableRowV1?) {
        if (prepared == null) requireActiveSeal(value == null)
        else {
            requireSameCanonical(preparedRow(), checkNotNull(value))
            if (frozen != null) requireSameFrozen(frozenRow(), value)
        }
    }
    internal fun requireSameCanonical(a: TestTerminalDurableRowV1, b: TestTerminalDurableRowV1) {
        requireActiveSeal(a.binding == b.binding && a.canonicalSha256 == b.canonicalSha256)
        val x = a.canonicalBytes(); val y = b.canonicalBytes()
        try { requireActiveSeal(x.contentEquals(y)) } finally { x.fill(0); y.fill(0) }
    }
    internal fun requireSameFrozen(a: TestTerminalDurableRowV1, b: TestTerminalDurableRowV1) {
        requireSameCanonical(a, b)
        requireActiveSeal(a.state === TestTerminalDurableStateV1.WIRE_FROZEN && b.state === a.state && a.wireSha256 == b.wireSha256 &&
            a.retainUntil == b.retainUntil && a.frozenAt == b.frozenAt && a.metadataSha256 == b.metadataSha256 && a.checksumSha256 == b.checksumSha256)
        val x = checkNotNull(a.wireBytes()); val y = checkNotNull(b.wireBytes())
        try { requireActiveSeal(x.contentEquals(y)) } finally { x.fill(0); y.fill(0) }
    }
    internal fun requireUnverifiedSeal() { requireProvider(checkNotNull(custody), true); requireActiveSeal(proof == null) }
    internal fun requireListedSealVersion(version: String?) {
        requireProvider(checkNotNull(custody), true)
        // This first-only owner cannot inherit a prior seal intent/proof. An existing exact-key
        // version still requires the unchanged native frozen-wire GET/AEAD path; LIST is no proof.
        requireActiveSeal(proof == null)
        if (version != null) me.manga.kira.backend.complaint.infrastructure.journal.aws.requireJournalVersion(version)
    }
    internal fun requireReservation() { requireConnectionFree(); requireCutoffRunning(); requireReleased(prepared); requireActiveSeal(custody == null) }
    internal fun requireProvider(selected: TestOrdinarySealCustodyV1, publication: Boolean) {
        requireConnectionFree(); requireCutoffRunning(); requireActiveSeal(custody === selected && phase == null && !phaseEntered)
        requireReleased(prepared)
        if (publication) { requireReleased(frozen); requireActiveSeal(frozenRow().state === TestTerminalDurableStateV1.WIRE_FROZEN) }
    }
    internal fun requireCutoffRecipe(recipe: VersionBoundTestActiveCutoffPublicationV1) { requireCutoffRunning(); requireActiveSeal(recipe === cutoffRecipe) }
    internal fun retainCutoffPublisher(selected: TestOwnerDeleteJournalPublisherFactoryV1, recipe: VersionBoundTestActiveCutoffPublicationV1) {
        requireCutoffRecipe(recipe); requireActiveSeal(publisher == null); publisher = selected
    }
    internal fun requireCutoffPublisher(selected: TestOwnerDeleteJournalPublisherFactoryV1, recipe: VersionBoundTestActiveCutoffPublicationV1) {
        requireCutoffRecipe(recipe); requireActiveSeal(publisher === selected)
    }

    internal fun requirePersistence(ownership: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requireRunning(); requireActiveSeal(ownership === coordinator.ownership && selected.dataSource === coordinator.dataSource && ownership.manager === coordinator.manager)
        if (jdbc == null) jdbc = selected
        requireActiveSeal(jdbc === selected)
    }
    internal fun requirePhaseEntry(ownership: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireConnectionFree(); requireRunning()
        requireActiveSeal(ownership === coordinator.ownership && path === this.path && phase == null && !phaseEntered)
        phaseEntered = true
    }
    internal fun retainPhase(selected: PersistencePhaseContext) { requireRunning(); requireActiveSeal(phaseEntered && phase == null); phase = selected }
    internal fun observePhaseCleanup(selected: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testActiveOrdinarySealCleanupProven(this) || selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) {
                cleanupUncertain = true; observeFailure(TestActiveOrdinarySealExceptionV1())
            } else { phase = null; phaseEntered = false }
        } catch (problem: Throwable) { cleanupUncertain = true; observeFailure(problem) }
    }
    internal fun authenticate(ownership: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requirePersistence(ownership, selected)
        val openings = process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.CATALOG_COORDINATOR }.openings().map { it.publicDriverProperties() }
        val user = openings.map { it["user"] }.distinct().single(); val database = openings.map { it["PGDBNAME"] }.distinct().single()
        requireActiveSeal(selected.query("SELECT session_user = ? AND current_user = ? AND current_database() = ? AS valid",
            ResultSetExtractor { rows -> rows.next() && rows.getBoolean("valid") && !rows.wasNull() && !rows.next() }, user, user, database) == true)
        requirePersistence(ownership, selected)
    }
    internal fun requireMaintenanceGate(ownership: PersistencePhaseOwnership, path: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireRunning(); requireActiveSeal(ownership === coordinator.ownership && path === this.path && phaseEntered && phase != null)
        registration.requireActiveIdentityGate(gate)
    }
    internal fun requireEvidenceRunning() { requireRunning() } // No continuing leadership is conferred by historical evidence.
    internal fun requireCutoffRunning() { requireRunning(); requireActiveSeal(leaseToken > slot.captureToken); renewal.remainingMillis(1) }
    private fun requireRunning() {
        throwIfSignalled()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("ACTIVE TEST seal interrupted.")
        requireActiveSeal(caller === Thread.currentThread() && started && !finished && !cleanupUncertain)
        budget.remainingMillis(1); codecAttempt.remainingMillis(1) // NOT remainingProviderMillis: avoid a custody recursion.
        origin.requireRetained(); acquisition.requireRetained(routing, process.publicationLanes); cutoffRecipe.requireRetained(routing, process.publicationLanes)
        if (readbackReserved) coordinator.catalogRefreshCustody.requireTestActiveOrdinarySeal(this)
    }
    private fun requireReleased(operation: TestActiveOrdinarySealOperationV1?) { requireRunning(); requireActiveSeal(phase == null && !phaseEntered); checkNotNull(operation).requireReleased() }
    internal fun observeFailure(problem: Throwable) {
        renewal.poison()
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("ACTIVE TEST seal cancelled.")
            problem is InterruptedException || problem is InterruptedIOException || problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED ->
                InterruptedException("ACTIVE TEST seal interrupted.")
            else -> TestActiveOrdinarySealExceptionV1()
        }
        while (true) {
            val previous = failure.get()
            if (previous is Error || previous is CancellationException && retained !is Error || previous is InterruptedException && retained !is Error && retained !is CancellationException || previous != null && retained is TestActiveOrdinarySealExceptionV1) return
            if (failure.compareAndSet(previous, retained)) return
        }
    }
    internal fun throwIfSignalled() { failure.get()?.let { if (it is InterruptedException) Thread.currentThread().interrupt(); throw it } }

    internal fun historyArguments(): Array<Any?> = arrayOf(readbackIdentity[0], scope, identity.generation, identity.activationCatalogHash(), process.catalogReadback.chainPolicy.limits.maximumGenerations + 1)
    internal fun requireAdmissionComparisons(operation: TestActiveOrdinarySealOperationV1, tail: TestNamespaceRecoveryRegistrationTailV1, history: CatalogTestRunActivationHistoryV1) {
        requireRunning(); requireActiveSeal(operation.original === this && tail.token == readbackIdentity[0] && tail.scope == scope && tail.generation == identity.generation &&
            tail.envelopeHash.contentEquals(readbackIdentity[3] as ByteArray) && tail.unsigned.contentEquals(readbackIdentity[4] as ByteArray) && tail.unsignedHash.contentEquals(readbackIdentity[5] as ByteArray))
        if (step !== TestActiveOrdinarySealStepV1.READ) { checkNotNull(captured).tail.requireSame(tail); checkNotNull(captured).history.requireSame(history); requireRawReleased() }
    }
    internal fun requireRawReleased() { requireRunning(); requireActiveSeal(raw != null && providerClosed && providerFailure == null && !readbackStage) }
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
        requireActiveSeal(!clock.instant().isBefore(at) && providerClosed && providerFailure == null)
        checkNotNull(captured).tail.requireRaw(result, reader.chainPolicy.limits.maximumManifestRecords)
        raw = result; readbackStage = false
    }
    internal fun requireProviderRunning() {
        requireConnectionFree(); requireRunning(); readbackBudget.remainingMillis(1)
        requireActiveSeal(readbackStage && phase == null && !phaseEntered && raw == null && !providerClosed)
        requireReleased(captured)
    }
    internal fun requireCustody(selected: CatalogReadbackRefreshCustodyV1) = requireActiveSeal(caller === Thread.currentThread() && coordinator.catalogRefreshCustody === selected)
    private fun closeReadback() {
        val problem = runCatching { withSignerRotationCleanup({ construction.close() }, http::close) }.exceptionOrNull()
        if (problem != null) providerFailure = preferSignerRotationCleanup(providerFailure, problem)
        providerFailure?.let { throw it }
        providerClosed = true
    }
    internal fun requireActualReadbackCleanup() {
        requireConnectionFree(); requireCustody(coordinator.catalogRefreshCustody)
        requireActiveSeal(providerClosed && providerFailure == null && phase == null && !phaseEntered && !cleanupUncertain)
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
    class Verified private constructor(val scope: UUID, val objectKey: String, val version: String, val ciphertextSha256: String) {
        companion object {
            internal fun issue(original: TestActiveOrdinarySealV1): Verified {
                original.throwIfSignalled(); requireConnectionFree()
                requireActiveSeal(original.caller === Thread.currentThread() && original.successful && original.finished && original.providerClosed && original.providerFailure == null &&
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
                return Verified(original.scope, frozen.binding.objectKey, proof.version, checkNotNull(frozen.wireSha256))
            }
        }
        override fun toString(): String = "TestActiveOrdinarySealV1.Verified(historical-only,redacted)"
    }
    /**
     * Closed provenance, not a caller-implementable row/current flag. Each private constructor is
     * reached only by its own typed one-use claim. Recovered never constructs the old Captured,
     * and both histories still enter the same RESERVED-only/current-raw/fresh-lease seal pipeline.
     */
    private sealed class Origin private constructor() {
        class Original private constructor(val handoff: TestActiveFirstCutV1.SealHandoff) : Origin() {
            companion object {
                fun claim(captured: TestActiveFirstCutV1.Captured, registration: ComplaintTestNamespaceRegistrationV1,
                    assembly: ComplaintTestProcessAssemblyV1): Original = Original(captured.claimSeal(registration, assembly))
            }
        }
        class Recovered private constructor(val handoff: TestActiveFirstCutSuccessorV1.RecoveredSealHandoff) : Origin() {
            companion object {
                fun claim(recovered: TestActiveFirstCutSuccessorV1.Recovered, registration: ComplaintTestNamespaceRegistrationV1,
                    assembly: ComplaintTestProcessAssemblyV1): Recovered = Recovered(recovered.claimSeal(registration, assembly))
            }
        }
        val registration get() = when (this) { is Original -> handoff.registration; is Recovered -> handoff.registration }
        val process get() = when (this) { is Original -> handoff.process; is Recovered -> handoff.process }
        val slot get() = when (this) { is Original -> handoff.slot; is Recovered -> handoff.slot }
        fun requireRetained() = when (this) { is Original -> handoff.requireRetained(); is Recovered -> handoff.requireRetained() }
    }

    companion object {
        fun begin(captured: TestActiveFirstCutV1.Captured, registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1): TestActiveOrdinarySealV1 =
            TestActiveOrdinarySealV1(Origin.Original.claim(captured, registration, assembly), null, Clock.systemUTC())
        fun begin(recovered: TestActiveFirstCutSuccessorV1.Recovered, registration: ComplaintTestNamespaceRegistrationV1,
            assembly: ComplaintTestProcessAssemblyV1): TestActiveOrdinarySealV1 =
            TestActiveOrdinarySealV1(Origin.Recovered.claim(recovered, registration, assembly), null, Clock.systemUTC())
        internal fun withHttpFixture(captured: TestActiveFirstCutV1.Captured, registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1,
            http: () -> SdkHttpClient, clock: Clock): TestActiveOrdinarySealV1 = TestActiveOrdinarySealV1(Origin.Original.claim(captured, registration, assembly), http, clock)
        internal fun withHttpFixture(recovered: TestActiveFirstCutSuccessorV1.Recovered, registration: ComplaintTestNamespaceRegistrationV1,
            assembly: ComplaintTestProcessAssemblyV1, http: () -> SdkHttpClient, clock: Clock): TestActiveOrdinarySealV1 =
            TestActiveOrdinarySealV1(Origin.Recovered.claim(recovered, registration, assembly), http, clock)
        private fun hex(value: ByteArray): String = try { HexFormat.of().formatHex(value) } finally { value.fill(0) }
    }
}

internal class TestActiveOrdinarySealExceptionV1 : RuntimeException("ACTIVE TEST ordinary seal refused.", null, false, false)
internal fun requireActiveSeal(value: Boolean) { if (!value) throw TestActiveOrdinarySealExceptionV1() }
