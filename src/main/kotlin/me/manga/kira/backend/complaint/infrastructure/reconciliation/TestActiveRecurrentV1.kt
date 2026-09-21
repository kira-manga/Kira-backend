package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.EpochRotationLimits
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
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableBindingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableKindV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEpochSealV1
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
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalCodecV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.io.InterruptedIOException
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * One ordinary recurrent ACTIVE original, never a per-epoch/generic runner. A new original can
 * resume an immutable persisted intent, but cannot revive an earlier original's UNKNOWN result.
 * The V26/V27 initial path stays separate. All native calls, SQL, waits, cleanup and any private
 * scan-recovery handoff spend this one retained attempt and actual <=10s renewal window.
 */
internal class TestActiveRecurrentV1 private constructor(
    internal val registration: ComplaintTestNamespaceRegistrationV1,
    internal val assembly: ComplaintTestProcessAssemblyV1,
    private val httpFixture: (() -> SdkHttpClient)?, private val clock: Clock,
) : AutoCloseable {
    internal val process = registration.process
    internal val identity = TestActiveFirstCutIdentityV1.fromRegistration(registration)
    internal val coordinator = process.pools.catalogCoordinator
    internal val routing = process.consumers.journalRouting
    internal val recipe = checkNotNull(process.activeRecurrent)
    internal val acquisition = checkNotNull(process.ordinarySeal)
    internal val cutoffRecipe = checkNotNull(process.activeCutoffPublication)
    internal val scope = identity.scope
    internal val writer = identity.writer.toString()
    internal val attemptId = UUID.randomUUID()
    internal val requestedOperationToken = UUID.randomUUID()
    private val nanoClock = TestActiveSealNanoClockV1(coordinator.ownership.nanoClock)
    internal val budget = PersistenceTimeBudget.start(recipe.totalAttemptMillis, nanoClock)
    internal val codec = TestTerminalCodecV1.fromRetained(routing, recipe.nanoTime)
    private val codecAttempt = codec.startAttempt(TestTerminalCodecKindV1.EPOCH_SEAL, budget)
    private val renewal = TestActiveSealRenewalWindowV1(budget)
    internal val maximumEntries = routing.journalConfiguration.declaration().limits.capacity.maximumRetainedVersions
    internal val maximumBytes = routing.journalConfiguration.declaration().limits.capacity.maximumScanStagingBytes
    internal val maximumCiphertextBytes = Math.multiplyExact(maximumEntries, routing.journalConfiguration.declaration().limits.decoder.maximumEnvelopeBytes.toLong())
    internal var leaseToken = 0L
        private set
    internal var step = TestActiveRecurrentStepV1.READ
        private set
    internal val path = PersistencePhasePath.COMPLAINT_TEST_ACTIVE_RECURRENT
    internal val operationToken: UUID get() = currentIntent().token
    internal val epochStart: Long get() = currentIntent().epochStart
    internal val cutoff: Long get() = currentIntent().epochEnd
    internal val lowerAllKey: String get() = epochKey(1)
    internal val lowerCutoffKey: String get() = epochKey(epochStart)
    internal val upperCutoffKey: String get() = epochKey(Math.addExact(cutoff, 1))
    private val caller = Thread.currentThread()
    private val failure = AtomicReference<Throwable?>()
    private var started = false
    private var finished = false
    private var successful = false
    private var closing = false
    private var cleanupProven = false
    private var cleanupUncertain = false
    private var executing = false
    private var phase: PersistencePhaseContext? = null
    private var phaseEntered = false
    private var jdbc: JdbcTemplate? = null
    private var nativeClaimed = false
    private var leaseCommitted = false
    private var renewalEstablished = false
    private var firstRead: TestActiveRecurrentOperationV1? = null
    private var snapshot: TestActiveRecurrentOperationV1? = null
    private var committed: TestActiveRecurrentOperationV1? = null
    private var capturing = false
    private var captureTime: PersistenceTimeBudget? = null
    private var capture: TestActiveRecurrentCaptureOperationV1? = null
    private var captureAwaiting = false
    private val nativeSeals = LinkedHashMap<UUID, TestActiveRecurrentNativeSealV1>()
    private var nativeSeal: TestActiveRecurrentNativeSealV1? = null
    private var candidate: TestTerminalDurableRowV1? = null
    private var wireCandidate: TestTerminalDurableRowV1? = null
    private var manifest: TestActiveOrdinarySealManifestV1? = null
    private var publisher: TestOwnerDeleteJournalPublisherFactoryV1? = null
    private var publisherClosed = false
    private var evidence: ReleasedTestActiveCutoffPublicationV1? = null
    private var epochAfter = 0L to ""
    private var keyAfter = ""
    private var scan: TestActiveRecurrentScanV1? = null
    private var scanAfter = 0 to ("" to "")
    private var appliedAfter = "" to ""
    private var cleaning: Cleanup? = null
    private var waiting: RecoveryRequired? = null
    private val readbackIdentity = copyFirstCutArguments(registration.activeReadbackArguments())
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
        requireRecurrent(scope == routing.journalConfiguration.scope.id && writer == routing.journalConfiguration.declaration().writer.generationId &&
            routing.journalConfiguration.registeredAdminBatchDelete && readbackIdentity.size == 6)
    }

    /** Normal recurrence or fresh fenced recovery of the one already-paid recurrent intent. */
    fun checkpoint(primary: AwsSessionCredentials, replica: AwsSessionCredentials): Result {
        throwIfSignalled(); requireRecurrent(caller === Thread.currentThread() && !started)
        started = true
        try {
            requireConnectionFree(); requireRunning()
            recipe.claim(this); nativeClaimed = true
            coordinator.catalogRefreshCustody.reserveTestActiveRecurrent(this); readbackReserved = true
            firstRead = execute(TestActiveRecurrentStepV1.READ)
            observe(primary, replica)
            acquireLease()
            readNativePredecessors()
            if (state().checkpointSha256 != null) {
                requireRecurrent(state().sequence < TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS)
                renew(); execute(TestActiveRecurrentStepV1.REQUEST)
            }
            requireRecurrent(currentIntent().ordinal in 2..TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS)
            if (state().state == "REQUESTED") captureRequested()
            requireRecurrent(state().state == "CAPTURED" && leaseToken > checkNotNull(currentIntent().captureToken))
            manifest = TestActiveCutoffPublicationsV1.resolve(this)
            // Resolver owns all its native lanes and bounded pages; no receipt is converted to APPLY.
            closePublisher()
            renew(); bindCanonical(checkNotNull(manifest))
            try { execute(TestActiveRecurrentStepV1.CANONICAL) } finally { candidate?.close(); candidate = null }
            if (nativeSeals[operationToken] == null) observeSeal(null)
            renew(); execute(TestActiveRecurrentStepV1.VERIFY)
            // Scan ownership is genuinely higher than the immutable seal's preparing fence.
            renew(); execute(TestActiveRecurrentStepV1.RELEASE_LEASE); leaseCommitted = false
            acquireLease()
            cleanObsoleteStaging()
            scan = TestActiveRecurrentScanV1.begin(this)
            return advanceToBoundary()
        } catch (problem: Throwable) { return failAndClose(problem) }
    }

    private fun resume(result: RecoveryRequired): Result {
        try {
            requireConnectionFree(); requireCutoffRunning()
            requireRecurrent(waiting === result)
            checkNotNull(scan).requireContinuation(result.nativeInput)
            return advanceToBoundary()
        } catch (problem: Throwable) { return failAndClose(problem) }
    }
    private fun advanceToBoundary(): Result {
        requireCutoffRunning()
        val pending = checkNotNull(scan).advance()
        if (pending != null) {
            requireConnectionFree(); requireCutoffRunning(); pending.requireOriginal(this)
            return waiting?.takeIf { it.nativeInput === pending } ?: RecoveryRequired.issue(this, pending).also { waiting = it }
        }
        waiting = null
        checkNotNull(scan).requireNativeCleanup(); requireNoScanRows()
        renew(); committed = execute(TestActiveRecurrentStepV1.SUCCESS)
        leaseCommitted = false
        checkNotNull(committed).requireReleased(); requireRunning()
        successful = true
        close()
        return Completed.issue(this)
    }
    private fun failAndClose(problem: Throwable): Nothing {
        observeFailure(problem)
        runCatching(::close).exceptionOrNull()?.let(::observeFailure)
        throwIfSignalled()
        throw TestActiveRecurrentExceptionV1()
    }

    private fun execute(selected: TestActiveRecurrentStepV1): TestActiveRecurrentOperationV1 {
        requireConnectionFree(); requireRunning()
        requireRecurrent(!executing && phase == null && !phaseEntered && !capturing)
        step = selected; executing = true
        try {
            val operation = coordinator.testActiveRecurrent.execute(this)
            operation.requireReleased()
            if (selected != TestActiveRecurrentStepV1.EVIDENCE) {
                val prior = snapshot; snapshot = operation
                prior?.discardState() // A live cutoff page retains only its own <=32 publication rows until its finally block.
            }
            return operation
        } finally { executing = false }
    }
    private fun acquireLease() {
        requireConnectionFree(); requireRunning(); requireRecurrent(!leaseCommitted)
        renewal.beginDispatch(first = !renewalEstablished)
        execute(TestActiveRecurrentStepV1.ACQUIRE)
        renewal.committedAndReleased(); renewalEstablished = true; leaseCommitted = true
        if (captureAwaiting) { captureAwaiting = false; capture?.discardDetached() }
    }
    internal fun renew() {
        requireConnectionFree(); requireCutoffRunning()
        renewal.beginDispatch(first = false)
        execute(TestActiveRecurrentStepV1.RENEW)
        renewal.committedAndReleased()
    }
    internal fun phaseBudget(): PersistenceTimeBudget = if (step in setOf(TestActiveRecurrentStepV1.READ, TestActiveRecurrentStepV1.EVIDENCE))
        budget.capped(2_000) else renewal.phaseBudget()
    internal fun nativeContinuationBudget(): PersistenceTimeBudget { requireConnectionFree(); requireCutoffRunning(); return renewal.providerBudget() }
    internal fun remainingNativeContinuationMillis(ceiling: Int): Int { requireConnectionFree(); requireCutoffRunning(); return renewal.remainingMillis(ceiling) }
    internal fun retainAcquiringToken(operation: TestActiveRecurrentOperationV1, before: Long, token: Long) {
        requireOperation(operation, TestActiveRecurrentStepV1.ACQUIRE)
        requireRecurrent(!leaseCommitted && before < Long.MAX_VALUE && token == before + 1 && token > leaseToken &&
            token > operation.current.requestToken && token > (operation.current.captureToken ?: 0L))
        leaseToken = token // Not current native/scan authority until this exact phase is known committed AND released.
    }

    internal fun currentIntent(): TestActiveRecurrentIntentV1 = checkNotNull(snapshot).intent
    internal fun sealHistory(): TestActiveRecurrentHistoryV1 = checkNotNull(snapshot).history
    private fun state(): TestActiveRecurrentCurrentV1 = checkNotNull(snapshot).current
    internal fun requirePrior(operation: TestActiveRecurrentOperationV1) {
        requireOperation(operation, step)
        val before = snapshot
        if (step == TestActiveRecurrentStepV1.READ) { requireRecurrent(before == null); return }
        val old = checkNotNull(before)
        old.requireCommittedComparison()
        if (captureAwaiting) {
            requireRecurrent(step == TestActiveRecurrentStepV1.ACQUIRE && old.current.state == "REQUESTED")
            checkNotNull(capture).requireReleasedComparison(operation.current)
            requireRecurrent(old.intents.size == operation.intents.size)
            old.intents.dropLast(1).zip(operation.intents.dropLast(1)).forEach { (a, b) -> a.requireSame(b) }
        } else {
            old.current.requireSameContent(operation.current)
            requireRecurrent(old.intents.size == operation.intents.size)
            old.intents.zip(operation.intents).forEach { (a, b) -> a.requireSame(b) }
        }
        old.history.requireSame(operation.history)
        requireRecurrent(old.scans.size == operation.scans.size && old.scans.zip(operation.scans).all { (a, b) -> a.fingerprint == b.fingerprint })
        // Full D/J/current run shape is reread by SQL. Ordinary newer-epoch writes/accounting must
        // not be rejected merely because an otherwise valid run/global bookkeeping xmin changed.
    }

    private fun captureRequested() {
        renew(); requireConnectionFree(); requireCutoffRunning()
        requireRecurrent(state().state == "REQUESTED" && capture == null && !captureAwaiting)
        captureTime = renewal.providerBudget().capped(EpochRotationLimits.MAXIMUM_ROTATION_MILLIS)
        capturing = true
        try {
            val operation = recipe.resource.capture(this)
            requireRecurrent(capture === operation)
            operation.requireReleasedRow()
            captureAwaiting = true; leaseCommitted = false
        } finally { capturing = false }
        acquireLease()
    }
    internal fun captureBudget(): PersistenceTimeBudget {
        requireCore(recipe.resource)
        return checkNotNull(captureTime) // One pre-dispatch retained cap, never restarted by an accessor.
    }
    internal fun requireCore(resource: EpochRotationPersistence) {
        requireRunning()
        requireRecurrent(resource === recipe.resource && capturing && !executing && phase == null && !phaseEntered && leaseCommitted &&
            state().state == "REQUESTED" && currentIntent().ordinal in 2..14 && nativeSeal == null)
        checkNotNull(snapshot).requireCommittedComparison(); requireRawReleased()
        renewal.remainingMillis(1); checkNotNull(captureTime).remainingMillis(1)
    }
    internal fun requireCaptureMaintenanceGate(resource: EpochRotationPersistence, gate: PersistenceComplaintMaintenanceGateV1) {
        requireCore(resource); registration.requireActiveIdentityGate(gate)
    }
    internal fun retain(operation: TestActiveRecurrentCaptureOperationV1) {
        requireCore(recipe.resource); requireRecurrent(capture == null && operation.original === this); capture = operation
    }
    internal fun requireCapture(operation: TestActiveRecurrentCaptureOperationV1) {
        requireCore(recipe.resource); requireRecurrent(capture === operation && operation.original === this)
    }
    internal fun requireCapturePrior(operation: TestActiveRecurrentCaptureOperationV1, current: TestActiveRecurrentCurrentV1) {
        requireCapture(operation)
        state().requireSamePhysical(current); current.requireIntent(currentIntent()); current.requireLease(attemptId, leaseToken)
    }
    internal fun observeUnsettledNativeCleanup(resource: EpochRotationPersistence) {
        requireRecurrent(resource === recipe.resource)
        cleanupUncertain = true; observeFailure(TestActiveRecurrentExceptionV1())
    }

    private fun readNativePredecessors() {
        val tokens = checkNotNull(snapshot).intents.map { it.token }
        for (token in tokens) {
            val history = sealHistory().records.singleOrNull { it.entry.operationToken == token.toString() }
            val version = history?.entry?.objectVersion ?: state().sealVersion?.takeIf { state().id == token && state().sealState == "SEAL_VERIFIED" }
            if (version != null) observeSeal(version, token)
        }
    }
    private fun observeSeal(readOnlyVersion: String?, token: UUID = operationToken) {
        requireConnectionFree(); requireCutoffRunning()
        val source = checkNotNull(snapshot).intents.single { it.token == token }
        val owner = TestActiveRecurrentNativeSealV1.begin(this, source, readOnlyVersion)
        requireRecurrent(nativeSeals.put(token, owner) == null)
        nativeSeal = owner // Retain before any lane/provider construction or open.
        try { owner.run() } finally { nativeSeal = null }
    }
    internal fun requireNativeSealReservation(intent: TestActiveRecurrentIntentV1, version: String?) {
        requireConnectionFree(); requireCutoffRunning()
        requireRecurrent(nativeSeal == null && nativeSeals.size < TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS && nativeSeals[intent.token] == null &&
            checkNotNull(snapshot).intents.any { it === intent } && intent.payload != null && phase == null && !capturing)
        val archived = sealHistory().records.singleOrNull { it.entry.operationToken == intent.token.toString() }
        val expected = archived?.entry?.objectVersion ?: state().sealVersion?.takeIf { intent.token == state().id && state().sealState == "SEAL_VERIFIED" }
        requireRecurrent(version == expected && (version != null || intent.token == operationToken && intent.ordinal >= 2 && state().checkpointSha256 == null))
    }
    internal fun requireNativeSeal(owner: TestActiveRecurrentNativeSealV1) {
        requireConnectionFree(); requireCutoffRunning()
        requireRecurrent(nativeSeal === owner && nativeSeals[owner.operationToken] === owner && owner.original === this && phase == null && !capturing)
    }
    internal fun requireAllNative(operation: TestActiveRecurrentOperationV1) {
        requireOperation(operation, step)
        operation.history.records.forEach { record ->
            val source = operation.intents.single { it.token.toString() == record.entry.operationToken }
            val owner = checkNotNull(nativeSeals[source.token])
            owner.requireFrozen(checkNotNull(source.payload)); record.verification.requireNative(owner, checkNotNull(source.payload))
        }
        if (operation.current.sealState == "SEAL_VERIFIED") {
            val owner = checkNotNull(nativeSeals[operation.intent.token])
            owner.requireFrozen(checkNotNull(operation.intent.payload))
            operation.current.proof(operation.intent, acquisition).use { it.requireNative(owner, checkNotNull(operation.intent.payload)) }
        }
    }
    internal fun currentNative(operation: TestActiveRecurrentOperationV1): TestActiveRecurrentNativeSealV1 {
        requireOperation(operation, TestActiveRecurrentStepV1.VERIFY)
        return checkNotNull(nativeSeals[operation.intent.token]).also { it.releasedProof() }
    }
    internal fun freeze(owner: TestActiveRecurrentNativeSealV1, proposed: TestTerminalDurableRowV1): TestTerminalDurableRowV1 {
        requireNativeSeal(owner); requireRecurrent(owner.operationToken == operationToken && wireCandidate == null)
        wireCandidate = proposed
        return try { execute(TestActiveRecurrentStepV1.FREEZE); recurrentCopyPayload(checkNotNull(currentIntent().payload)) }
        finally { wireCandidate = null }
    }
    internal fun frozenCandidate(operation: TestActiveRecurrentOperationV1): TestTerminalDurableRowV1 {
        requireOperation(operation, TestActiveRecurrentStepV1.FREEZE)
        requireRecurrent(nativeSeal === nativeSeals[operation.intent.token]); return checkNotNull(wireCandidate)
    }
    private fun bindCanonical(expectedManifest: TestActiveOrdinarySealManifestV1) {
        requireConnectionFree(); requireCutoffRunning()
        val intent = currentIntent()
        val predecessor = checkNotNull(sealHistory().commitment).entries[intent.ordinal - 2]
        val existing = intent.payload
        if (existing != null) {
            val seal = intent.seal()
            requireRecurrent(seal.eventCount == expectedManifest.count && seal.eventManifestSha256 == expectedManifest.sha256 &&
                seal.precedingSealSha256 == predecessor.canonicalSha256)
            candidate = recurrentCopyPayload(existing)
            return // No new UUID/key/fence/time/canonical/frozen bytes may replace a committed winner.
        }
        val created = awaitCanonicalSecond()
        val value = TestTerminalEpochSealV1(1, "EPOCH_SEAL", "A".repeat(43), writer, "TEST", scope.toString(), epochStart, cutoff,
            expectedManifest.count, expectedManifest.sha256, predecessor.canonicalSha256, leaseToken)
        codec.canonicalizeEpochSeal(value, codecAttempt).use { encoded ->
            val binding = TestTerminalDurableBindingV1(intent.token.toString(), recurrentRunContext(identity), routing.journalConfiguration.sha256,
                TestTerminalDurableKindV1.EPOCH_SEAL, intent.ordinal - 1, encoded.route.journalId, encoded.route.objectKey, encoded.route.routingKeyId,
                writer, epochStart, cutoff, leaseToken, acquisition.newRetention(codecAttempt, created), created)
            val bytes = encoded.canonicalBytes()
            candidate = try { TestTerminalDurableRowV1.canonical(binding, bytes) } finally { bytes.fill(0) }
        }
    }
    private fun awaitCanonicalSecond(): Instant {
        while (true) {
            requireConnectionFree(); requireCutoffRunning()
            val now = acquisition.sampleUtc().truncatedTo(ChronoUnit.SECONDS)
            requireCutoffRunning()
            if (now >= checkNotNull(currentIntent().capturedAt)) return now
            Thread.sleep(remainingNativeContinuationMillis(25).toLong())
        }
    }
    internal fun canonicalCandidate(operation: TestActiveRecurrentOperationV1): TestTerminalDurableRowV1 {
        requireOperation(operation, TestActiveRecurrentStepV1.CANONICAL); return checkNotNull(candidate)
    }
    internal fun manifestFramedBytes(operation: TestActiveRecurrentOperationV1): Long {
        requireOperation(operation, TestActiveRecurrentStepV1.VERIFY)
        val selected = checkNotNull(manifest); val seal = operation.intent.seal()
        requireRecurrent(seal.eventCount == selected.count && seal.eventManifestSha256 == selected.sha256)
        return selected.framedBytes
    }

    internal fun epochPage(after: Pair<Long, String>): TestActiveRecurrentOperationV1 { epochAfter = after; return execute(TestActiveRecurrentStepV1.EPOCH_PAGE) }
    internal fun keyPage(after: String): TestActiveRecurrentOperationV1 { keyAfter = after; return execute(TestActiveRecurrentStepV1.KEY_PAGE) }
    internal fun epochCursor(): Pair<Long, String> { requireRecurrent(step == TestActiveRecurrentStepV1.EPOCH_PAGE); return epochAfter }
    internal fun keyCursor(): String { requireRecurrent(step == TestActiveRecurrentStepV1.KEY_PAGE); return keyAfter }
    internal fun persistObservation(work: ReleasedTestActiveCutoffPublicationV1) {
        work.requireOriginal(this); requireRecurrent(evidence == null); evidence = work
        try { execute(TestActiveRecurrentStepV1.EVIDENCE).requireReleased() } finally { evidence = null }
    }
    internal fun evidenceWork(operation: TestActiveRecurrentOperationV1): ReleasedTestActiveCutoffPublicationV1 {
        requireOperation(operation, TestActiveRecurrentStepV1.EVIDENCE); return checkNotNull(evidence)
    }
    internal fun requireEvidenceRunning() = requireRunning()
    internal fun requireCutoffRecipe(selected: VersionBoundTestActiveCutoffPublicationV1) { requireCutoffRunning(); requireRecurrent(selected === cutoffRecipe) }
    internal fun retainCutoffPublisher(factory: TestOwnerDeleteJournalPublisherFactoryV1, selected: VersionBoundTestActiveCutoffPublicationV1) {
        requireCutoffRecipe(selected); requireRecurrent(publisher == null); publisher = factory
    }
    internal fun requireCutoffPublisher(factory: TestOwnerDeleteJournalPublisherFactoryV1, selected: VersionBoundTestActiveCutoffPublicationV1) {
        requireCutoffRecipe(selected); requireRecurrent(publisher === factory && !publisherClosed)
    }
    private fun closePublisher() { publisher?.close(); publisherClosed = true }

    internal fun requireScanReservation() {
        requireConnectionFree(); requireCutoffRunning()
        requireRecurrent(scan == null && cleaning == null && state().sealState == "SEAL_VERIFIED" && state().checkpointSha256 == null &&
            leaseToken > currentIntent().preparingToken() && sealHistory().records.size == checkNotNull(snapshot).intents.size)
        requireNoScanRows()
    }
    internal fun requireScan(selected: TestActiveRecurrentScanV1) {
        requireCutoffRunning(); requireRecurrent(scan === selected && selected.original === this && !capturing)
    }
    internal fun scanStep(selected: TestActiveRecurrentScanV1, action: TestActiveRecurrentStepV1): TestActiveRecurrentOperationV1 {
        requireScan(selected)
        requireRecurrent(action in setOf(TestActiveRecurrentStepV1.START_PASS, TestActiveRecurrentStepV1.APPEND, TestActiveRecurrentStepV1.COMPLETE_PASS,
            TestActiveRecurrentStepV1.RECHECK_ENTRY))
        return execute(action)
    }
    internal fun scanPending(selected: TestActiveRecurrentScanV1): TestActiveRecurrentOperationV1 { requireScan(selected); return execute(TestActiveRecurrentStepV1.PENDING) }
    internal fun scanEntries(selected: TestActiveRecurrentScanV1, pass: Int, after: Pair<String, String>): TestActiveRecurrentOperationV1 {
        requireScan(selected); requireRecurrent(pass in 1..2); scanAfter = pass to after
        return execute(TestActiveRecurrentStepV1.ENTRY_PAGE)
    }
    internal fun scanPair(selected: TestActiveRecurrentScanV1): TestActiveRecurrentOperationV1 { requireScan(selected); return execute(TestActiveRecurrentStepV1.PAIR) }
    internal fun scanCursor(operation: TestActiveRecurrentOperationV1): Pair<Int, Pair<String, String>> {
        requireScanOperation(operation, checkNotNull(scan), TestActiveRecurrentStepV1.ENTRY_PAGE); return scanAfter
    }
    internal fun appliedPage(selected: TestActiveRecurrentScanV1, after: Pair<String, String>): TestActiveRecurrentOperationV1 {
        requireScan(selected); appliedAfter = after; return execute(TestActiveRecurrentStepV1.APPLIED_PAGE)
    }
    internal fun appliedCursor(operation: TestActiveRecurrentOperationV1): Pair<String, String> {
        requireScanOperation(operation, checkNotNull(scan), TestActiveRecurrentStepV1.APPLIED_PAGE); return appliedAfter
    }
    internal fun scanRows(): List<TestActiveRecurrentScanV1.Run> = checkNotNull(snapshot).scans
    internal fun scanForOperation(operation: TestActiveRecurrentOperationV1): TestActiveRecurrentScanV1 {
        requireOperation(operation, step); return checkNotNull(scan).also(::requireScan)
    }
    internal fun requireScanOperation(operation: TestActiveRecurrentOperationV1, selected: TestActiveRecurrentScanV1, action: TestActiveRecurrentStepV1) {
        requireOperation(operation, action); requireScan(selected)
    }
    internal fun requireNoScanRows() { requireRecurrent(checkNotNull(snapshot).scans.isEmpty()) }
    private fun cleanObsoleteStaging() {
        requireRecurrent(scan == null && scanRows().all { it.token < leaseToken })
        clean(Cleanup.OBSOLETE)
    }
    internal fun cleanStaging(selected: TestActiveRecurrentScanV1) {
        requireScan(selected); selected.requireNativeCleanup(); clean(Cleanup.SUCCESS)
    }
    private fun clean(mode: Cleanup) {
        requireConnectionFree(); requireCutoffRunning(); requireRecurrent(cleaning == null)
        cleaning = mode
        var removedEntries = 0L; var removedRuns = 0L
        try {
            while (scanRows().isNotEmpty()) {
                renew()
                val operation = execute(TestActiveRecurrentStepV1.CLEAN)
                requireRecurrent(operation.cleanedEntries > 0 || operation.cleanedRuns > 0)
                removedEntries = Math.addExact(removedEntries, operation.cleanedEntries.toLong())
                removedRuns = Math.addExact(removedRuns, operation.cleanedRuns.toLong())
                requireRecurrent(removedEntries <= Math.multiplyExact(2L, maximumEntries) && removedRuns <= 2)
            }
            requireNoScanRows()
        } finally { cleaning = null }
    }
    internal fun requireCleanup(operation: TestActiveRecurrentOperationV1): Boolean {
        requireOperation(operation, TestActiveRecurrentStepV1.CLEAN)
        return when (checkNotNull(cleaning)) {
            Cleanup.OBSOLETE -> { requireRecurrent(scan == null); false }
            Cleanup.SUCCESS -> { checkNotNull(scan).requireNativeCleanup(); true }
        }
    }

    internal fun requirePersistence(ownership: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requireRunning()
        requireRecurrent(executing && !capturing && ownership === coordinator.ownership && selected.dataSource === coordinator.dataSource && ownership.manager === coordinator.manager)
        if (jdbc == null) jdbc = selected
        requireRecurrent(jdbc === selected)
    }
    internal fun requirePhaseEntry(ownership: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireConnectionFree(); requireRunning()
        requireRecurrent(executing && ownership === coordinator.ownership && path === this.path && phase == null && !phaseEntered && !capturing)
        phaseEntered = true
    }
    internal fun retainPhase(selected: PersistencePhaseContext) { requireRunning(); requireRecurrent(phaseEntered && phase == null); phase = selected }
    internal fun observePhaseCleanup(selected: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testActiveRecurrentCleanupProven(this)) {
                cleanupUncertain = true; observeFailure(TestActiveRecurrentExceptionV1())
            } else {
                phase = null; phaseEntered = false
                // UNKNOWN poisons this original, even if its graph really retired. Only a separate
                // fresh original can reread history; this one's result never becomes successful.
                if (selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) observeFailure(TestActiveRecurrentExceptionV1())
            }
        } catch (problem: Throwable) { cleanupUncertain = true; observeFailure(problem) }
    }
    internal fun authenticationArguments(): Array<Any?> {
        requireRunning()
        val openings = process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.CATALOG_COORDINATOR }.openings().map { it.publicDriverProperties() }
        val user = openings.map { it["user"] }.distinct().single(); val database = openings.map { it["PGDBNAME"] }.distinct().single()
        return arrayOf(user, user, database)
    }
    internal fun authenticate(ownership: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requirePersistence(ownership, selected)
        requireRecurrent(selected.query(TestActiveRecurrentSqlV1.authenticate,
            ResultSetExtractor { rows -> rows.next() && rows.getBoolean("valid") && !rows.wasNull() && !rows.next() }, *authenticationArguments()) == true)
        requirePersistence(ownership, selected)
    }
    internal fun requireMaintenanceGate(ownership: PersistencePhaseOwnership, path: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireRunning(); requireRecurrent(ownership === coordinator.ownership && path === this.path && phaseEntered && phase != null)
        registration.requireActiveIdentityGate(gate)
    }
    private fun requireOperation(operation: TestActiveRecurrentOperationV1, action: TestActiveRecurrentStepV1) {
        requireRunning()
        requireRecurrent(executing && operation.original === this && operation.step == action && step == action && operation.belongsTo(checkNotNull(phase)))
    }
    internal fun requireRecipe(selected: VersionBoundTestActiveRecurrentV1) = requireRecurrent(caller === Thread.currentThread() && selected === recipe && process.activeRecurrent === selected)
    private fun requireTarget() {
        registration.requireActiveIdentityTarget(assembly)
        requireRecurrent(assembly.target === process && process.activeRecurrent === recipe && process.initialCheckpoint != null && process.activeFirstCut != null)
        recipe.requireRetained(routing, process.pools, acquisition)
        acquisition.requireRetained(routing, process.publicationLanes); cutoffRecipe.requireRetained(routing, process.publicationLanes)
    }
    internal fun requireCutoffRunning() { requireRunning(); requireRecurrent(leaseCommitted && leaseToken > 0); renewal.remainingMillis(1) }
    private fun requireRunning() {
        throwIfSignalled()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Recurrent TEST checkpoint interrupted.")
        requireRecurrent(caller === Thread.currentThread() && started && !finished && !closing && !cleanupUncertain)
        budget.remainingMillis(1); codecAttempt.remainingMillis(1)
        requireTarget()
        if (readbackReserved) coordinator.catalogRefreshCustody.requireTestActiveRecurrent(this)
    }
    internal fun abort() = observeFailure(TestActiveRecurrentExceptionV1())
    internal fun observeFailure(problem: Throwable) {
        renewal.poison()
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("Recurrent TEST checkpoint cancelled.")
            problem is InterruptedException || problem is InterruptedIOException || problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED ->
                InterruptedException("Recurrent TEST checkpoint interrupted.")
            else -> TestActiveRecurrentExceptionV1()
        }
        while (true) {
            val prior = failure.get()
            if (prior is Error || prior is CancellationException && retained !is Error || prior is InterruptedException && retained !is Error && retained !is CancellationException ||
                prior != null && retained is TestActiveRecurrentExceptionV1) return
            if (failure.compareAndSet(prior, retained)) return
        }
    }
    internal fun throwIfSignalled() { failure.get()?.let { if (it is InterruptedException) Thread.currentThread().interrupt(); throw it } }

    internal fun historyArguments(): Array<Any?> = arrayOf(readbackIdentity[0], scope, identity.generation, identity.activationCatalogHash(), process.catalogReadback.chainPolicy.limits.maximumGenerations + 1)
    internal fun requireAdmissionComparisons(operation: TestActiveRecurrentOperationV1, tail: TestNamespaceRecoveryRegistrationTailV1, history: CatalogTestRunActivationHistoryV1) {
        requireOperation(operation, step)
        requireRecurrent(tail.token == readbackIdentity[0] && tail.scope == scope && tail.generation == identity.generation &&
            tail.envelopeHash.contentEquals(readbackIdentity[3] as ByteArray) && tail.unsigned.contentEquals(readbackIdentity[4] as ByteArray) && tail.unsignedHash.contentEquals(readbackIdentity[5] as ByteArray))
        if (step != TestActiveRecurrentStepV1.READ) {
            checkNotNull(firstRead).tail.requireSame(tail); checkNotNull(firstRead).catalogHistory.requireSame(history); requireRawReleased()
        }
    }
    internal fun requireRawReleased() { requireRunning(); requireRecurrent(raw != null && providerClosed && providerFailure == null && !readbackStage) }
    private fun observe(primary: AwsSessionCredentials, replica: AwsSessionCredentials) {
        readbackStage = true
        val reader = process.catalogReadback; val at = clock.instant(); val configured = reader.sdkLimits
        val millis = readbackBudget.remainingMillis(configured.requestTimeoutMillis)
        val limits = S3CatalogReadbackLimits(millis, minOf(configured.connectTimeoutMillis.toLong(), millis).toInt(), minOf(configured.readTimeoutMillis.toLong(), millis).toInt(),
            configured.maximumListBytes, configured.maximumErrorBytes, configured.maximumObjectBytes)
        val result = withSignerRotationCleanup({
            val adapter = S3CatalogReadbackAdapter.openOwned(construction, reader.currentBundleBytes(), reader.chainPolicy.trustBundlePolicy,
                primary, replica, limits, { http.open(limits, httpFixture) }, recipe.nanoTime)
            CatalogTestRunActivationReadbackV3.verifyActiveCurrent(TimedReadback(adapter), reader.initialBundleBytes(), reader.currentBundleBytes(), reader.policyAt(at),
                identity.head, expected, checkNotNull(firstRead).catalogHistory)
        }, ::closeReadback)
        requireRunning(); readbackBudget.remainingMillis(1)
        requireRecurrent(!clock.instant().isBefore(at) && providerClosed && providerFailure == null)
        checkNotNull(firstRead).tail.requireRaw(result, reader.chainPolicy.limits.maximumManifestRecords)
        raw = result; readbackStage = false
    }
    internal fun requireProviderRunning() {
        requireConnectionFree(); requireRunning(); readbackBudget.remainingMillis(1)
        requireRecurrent(readbackStage && phase == null && !phaseEntered && raw == null && !providerClosed)
        checkNotNull(firstRead).requireReleased()
    }
    internal fun requireCustody(selected: CatalogReadbackRefreshCustodyV1) = requireRecurrent(caller === Thread.currentThread() && coordinator.catalogRefreshCustody === selected)
    private fun closeReadback() {
        val problem = runCatching { withSignerRotationCleanup(construction::close, http::close) }.exceptionOrNull()
        if (problem != null) providerFailure = preferSignerRotationCleanup(providerFailure, problem)
        providerFailure?.let { throw it }
        providerClosed = true
    }
    internal fun requireActualReadbackCleanup() {
        requireConnectionFree(); requireCustody(coordinator.catalogRefreshCustody)
        requireRecurrent(providerClosed && providerFailure == null && phase == null && !phaseEntered && !cleanupUncertain && !capturing)
    }
    internal fun requireNativeReleased(selected: VersionBoundTestActiveRecurrentV1) {
        requireConnectionFree(); requireRecipe(selected)
        requireRecurrent(phase == null && !phaseEntered && !cleanupUncertain && !capturing && nativeSeal == null && (publisher == null || publisherClosed))
        scan?.requirePhysicalReleased()
        nativeSeals.values.forEach { it.requirePhysicalCleanup() }
    }
    internal fun abortNative() { observeFailure(TestActiveRecurrentExceptionV1()); close() }
    override fun close() {
        requireRecurrent(caller === Thread.currentThread())
        if (closing) { if (!cleanupProven) throw TestActiveRecurrentExceptionV1(); return }
        closing = true
        if (!successful) observeFailure(TestActiveRecurrentExceptionV1())
        waiting = null
        fun attempt(body: () -> Unit) { runCatching(body).exceptionOrNull()?.let(::observeFailure) }
        attempt { scan?.close() }
        nativeSeals.values.forEach { owner -> attempt(owner::close) }
        attempt(::closePublisher); attempt(::closeReadback)
        if (nativeClaimed) attempt { recipe.release(this); nativeClaimed = false }
        if (readbackReserved) attempt { coordinator.catalogRefreshCustody.releaseTestActiveRecurrentAfterCleanup(this); readbackReserved = false }
        attempt { requireConnectionFree(); requireNativeReleased(recipe); requireActualReadbackCleanup(); requireRecurrent(!nativeClaimed && !readbackReserved); cleanupProven = true }
        snapshot?.discardDetached()
        if (firstRead !== snapshot) firstRead?.discardDetached()
        capture?.discardDetached(); candidate?.close(); candidate = null
        readbackIdentity.forEach { if (it is ByteArray) it.fill(0) }
        if (successful) attempt { budget.remainingMillis(1); codecAttempt.remainingMillis(1); renewal.remainingMillis(1) }
        finished = true
        throwIfSignalled()
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
    private fun epochKey(epoch: Long): String = "${routing.journalConfiguration.ordinaryPrefix}writer/$writer/epoch/${epoch.toString().padStart(19, '0')}/"
    private enum class Cleanup { OBSOLETE, SUCCESS }
    sealed class Result private constructor()
    /** Not success; the exact original remains live and bounded. No public delivery or callback is accepted. */
    class RecoveryRequired private constructor(private val original: TestActiveRecurrentV1, internal val nativeInput: TestActiveRecurrentScanRecoveryInputV1) : Result() {
        fun input(): TestActiveRecurrentScanRecoveryInputV1 {
            requireConnectionFree(); original.requireCutoffRunning(); requireRecurrent(original.waiting === this)
            nativeInput.requireOriginal(original); return nativeInput
        }
        fun resume(): Result = original.resume(this)
        override fun toString(): String = "RecurrentRecoveryRequired(private-native-scan-input,NOT_SUCCESS,redacted)"
        companion object { internal fun issue(original: TestActiveRecurrentV1, input: TestActiveRecurrentScanRecoveryInputV1): RecoveryRequired = RecoveryRequired(original, input) }
    }
    /** Known-committed historical metadata only. No current content/admin/health/terminal capability. */
    class Completed private constructor(val scope: UUID, val cutoffEpoch: Long, val fencingToken: Long, val checkpointSha256: String) : Result() {
        override fun toString(): String = "RecurrentCompleted(historical-only,redacted)"
        companion object {
            internal fun issue(original: TestActiveRecurrentV1): Completed {
                original.throwIfSignalled(); requireConnectionFree()
                requireRecurrent(original.caller === Thread.currentThread() && original.successful && original.finished && original.cleanupProven &&
                    !original.nativeClaimed && !original.readbackReserved && !original.cleanupUncertain && original.phase == null && !original.phaseEntered)
                original.budget.remainingMillis(1); original.renewal.remainingMillis(1)
                checkNotNull(original.scan).requireNativeCleanup()
                val committed = checkNotNull(original.committed); committed.requireReleased()
                return Completed(original.scope, committed.intent.epochEnd, original.leaseToken, checkNotNull(committed.checkpointSha256))
            }
        }
    }
    override fun toString(): String = "TestActiveRecurrentV1(one-original,source-only-review-required,redacted)"
    companion object {
        fun begin(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1): TestActiveRecurrentV1 =
            TestActiveRecurrentV1(registration, assembly, null, checkNotNull(registration.process.activeRecurrent).clock)
        internal fun withHttpFixture(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1,
            http: () -> SdkHttpClient, clock: Clock): TestActiveRecurrentV1 = TestActiveRecurrentV1(registration, assembly, http, clock)
    }
}
