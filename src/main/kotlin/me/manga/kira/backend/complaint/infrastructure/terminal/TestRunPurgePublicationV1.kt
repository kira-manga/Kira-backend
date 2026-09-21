package me.manga.kira.backend.complaint.infrastructure.terminal

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
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableBindingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableKindV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStateV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEventContextV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPurgeV1
import me.manga.kira.backend.security.TestTerminalAttemptV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalCodecV1
import me.manga.kira.backend.security.TestTerminalContentV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * Actual successful-manifest child, initial registry with either no history or the exact retained A.
 * Own canonical PREPARE and WIRE_FROZEN release precede its provider. Stops at receiptless
 * VERIFIED; no terminal seal, denial, catalog acceptance, erasure or PURGED authority is issued.
 */
internal class TestRunPurgePublicationV1 private constructor(internal val manifest: TestRunInstallationManifestPublicationV1) {
    internal val drain = manifest.drain
    internal val registration = manifest.registration
    internal val coordinator = manifest.coordinator
    internal val routing = manifest.routing
    internal val scope = manifest.scope
    internal val writer = manifest.writer
    internal val runContext = manifest.runContext
    internal val control = manifest.control
    internal val ordinaryCut = manifest.ordinaryCut
    internal val ordinarySeal = manifest.ordinarySeal
    internal val epoch = manifest.epoch
    internal val acquisition = manifest.acquisition
    internal val attemptId = UUID.randomUUID()
    internal val budget = PersistenceTimeBudget.start(routing.journalConfiguration.declaration().limits.deadlines.scanMillis.toLong(), coordinator.ownership.nanoClock)
    internal val codec = TestTerminalCodecV1.fromRetained(routing, acquisition.nanoTime)
    private val caller = Thread.currentThread()
    private val failure = AtomicReference<Throwable?>()
    private var started = false
    private var finished = false
    private var published = false
    private var cleanupUncertain = false
    private var phaseEntered = false
    private var phase: PersistencePhaseContext? = null
    private var jdbc: JdbcTemplate? = null
    private var capture: TestRunPurgeOperationV1? = null
    private var loaded: TestRunPurgeOperationV1? = null
    private var frozen: TestRunPurgeOperationV1? = null
    private var canonical: TestTerminalDurableRowV1? = null
    private var candidate: TestTerminalDurableRowV1? = null
    private var content: TestTerminalContentV1? = null
    private var codecAttempt: TestTerminalAttemptV1? = null
    private var custody: TestRunPurgeCustodyV1? = null
    private var proof: TestRunPurgeProofV1? = null
    private var verified: TestRunPurgeRowsV1.Verified? = null
    private var terminalEpochSeal: TestRunTerminalEpochSealV1? = null
    private val rows = ArrayList<TestTerminalDurableRowV1>(8)
    private val publications = ArrayList<TestRunPurgeRowsV1.Publication>(8)
    internal var step = TestRunPurgeStepV1.CAPTURE
        private set
    internal var leaseToken = 0L
        private set
    internal val path get() = if (step === TestRunPurgeStepV1.VERIFY)
        PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_VERIFY else PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_PUBLICATION

    init {
        requireConnectionFree()
        manifest.requirePurgePredecessor()
        // A is an independently verified ordinary prefix, never omitted, repriced or relabelled.
        requirePurge(control.previousSealEpoch == (control.initialHistory?.reference?.epochEndInclusive ?: 0L) &&
            ordinarySeal.epochStartInclusive == control.ordinaryStart &&
            ordinarySeal.precedingSealSha256 == (control.initialHistory?.reference?.objectRef?.canonicalSha256 ?: "") &&
            epoch == Math.addExact(control.cutoff, 1L) &&
            registration.process.catalogActivation.initialWriterRegistry().eventWriter.generationId == writer)
        acquisition.requireRetained(routing, registration.process.publicationLanes)
        manifest.retainPurge(this)
    }

    fun publish(): TestRunPurgePublicationResultV1 {
        requirePurge(caller === Thread.currentThread() && !started)
        started = true
        var complete = false
        try {
            capture = execute(TestRunPurgeStepV1.CAPTURE)
            codecAttempt = codec.startAttempt(TestTerminalCodecKindV1.TEST_RUN_PURGE, budget)
            bindCanonical()
            loaded = execute(TestRunPurgeStepV1.PREPARE)
            requireSameCanonical(checkNotNull(canonical), loadedRow())
            val bytes = loadedRow().canonicalBytes()
            content = try { codec.restoreCanonical(TestTerminalCodecKindV1.TEST_RUN_PURGE, bytes, loadedRow().binding.routingKeyId,
                loadedRow().binding.objectKey, loadedRow().canonicalSha256, codecAttempt()) } finally { bytes.fill(0) }
            val owner = TestRunPurgeCustodyV1.reserve(this).also { custody = it }
            owner.acquire()
            if (loadedRow().state === TestTerminalDurableStateV1.CANONICAL) {
                val envelope = owner.seal()
                try {
                    val row = loadedRow()
                    val retain = maxOf(row.binding.retentionFloor, acquisition.newRetention(codecAttempt(), row.binding.createdAt))
                    val at = maxOf(row.binding.createdAt, acquisition.sampleUtc().truncatedTo(ChronoUnit.SECONDS))
                    val wire = envelope.wireBytes()
                    candidate = try { ownRow(TestTerminalDurableRowV1.frozen(row, wire, retain, at)) } finally { wire.fill(0) }
                } finally { envelope.close() }
            }
            frozen = execute(TestRunPurgeStepV1.FREEZE)
            proof = owner.publish()
            owner.close()
            checkNotNull(proof).requireOriginal(this)
            verified = checkNotNull(execute(TestRunPurgeStepV1.VERIFY).publication).verifiedFacts()
            owner.requireRetired(this)
            custody = null
            execute(TestRunPurgeStepV1.COMPLETE)
            requireRunning(); requireConnectionFree()
            complete = true
        } catch (problem: Throwable) { observeFailure(problem) }
        finally {
            runCatching { custody?.close() }.exceptionOrNull()?.let(::observeFailure)
            content?.close(); content = null
            rows.forEach { it.close() }; rows.clear()
            publications.forEach { it.close() }; publications.clear()
            finished = true
        }
        throwIfSignalled()
        requirePurge(complete && !cleanupUncertain && phase == null && !phaseEntered && custody == null && verified != null)
        published = true
        return TestRunPurgePublicationResultV1.PURGE_AUTHENTICATED_AND_VERIFIED
    }

    private fun execute(next: TestRunPurgeStepV1): TestRunPurgeOperationV1 {
        requireConnectionFree(); requireRunning(); requirePurge(phase == null && !phaseEntered)
        step = next
        try { return coordinator.testRunPurge.execute(this).also { it.requireReleased(); requireRunning() } }
        catch (problem: Throwable) { if (phase == null) phaseEntered = false; throw problem }
    }
    private fun bindCanonical() {
        requireConnectionFree(); requireRunning()
        val captured = checkNotNull(capture).also { it.requireReleased() }
        val roots = capturedRoots()
        val descriptor = TestTerminalPurgeV1.create(TestTerminalEventContextV1(runContext, "A".repeat(43), epoch, writer), control.cutoff,
            ordinarySeal, roots.seals, roots.inventory, manifest.authenticatedSummary())
        val existing = captured.row
        val expected = codec.canonicalizePurge(descriptor, codecAttempt(), existing?.binding?.routingKeyId)
        try {
            if (existing == null) {
                val created = captured.databaseNow.truncatedTo(ChronoUnit.SECONDS)
                val binding = TestTerminalDurableBindingV1(UUID.randomUUID().toString(), runContext, routing.journalConfiguration.sha256,
                    TestTerminalDurableKindV1.TEST_RUN_PURGE, 0, expected.route.journalId, expected.route.objectKey, expected.route.routingKeyId,
                    writer, epoch, epoch, leaseToken, acquisition.newRetention(codecAttempt(), created), created)
                val bytes = expected.canonicalBytes()
                canonical = try { ownRow(TestTerminalDurableRowV1.canonical(binding, bytes)) } finally { bytes.fill(0) }
            } else {
                val actual = existing.canonicalBytes(); val computed = expected.canonicalBytes()
                try {
                    requirePurge(existing.binding.objectId == expected.route.journalId && existing.binding.objectKey == expected.route.objectKey &&
                        existing.canonicalSha256 == expected.canonicalSha256 && MessageDigest.isEqual(actual, computed))
                    codec.restoreCanonical(TestTerminalCodecKindV1.TEST_RUN_PURGE, actual, existing.binding.routingKeyId,
                        existing.binding.objectKey, existing.canonicalSha256, codecAttempt()).close()
                    canonical = existing
                } finally { actual.fill(0); computed.fill(0) }
            }
        } finally { expected.close() }
        requireRunning()
    }

    internal fun capturedSource() = checkNotNull(checkNotNull(capture).source)
    internal fun capturedRoots() = checkNotNull(checkNotNull(capture).roots)
    internal fun canonicalCandidate(operation: TestRunPurgeOperationV1): TestTerminalDurableRowV1 {
        requireRunning(); requirePurge(operation.original === this && step === TestRunPurgeStepV1.PREPARE)
        return checkNotNull(canonical)
    }
    internal fun codecAttempt(): TestTerminalAttemptV1 = checkNotNull(codecAttempt)
    internal fun content(): TestTerminalContentV1 = checkNotNull(content)
    internal fun loadedRow(): TestTerminalDurableRowV1 = checkNotNull(checkNotNull(loaded).row)
    internal fun frozenRow(): TestTerminalDurableRowV1 = checkNotNull(checkNotNull(frozen).row)
    private fun frozenPublication(): TestRunPurgeRowsV1.Publication = checkNotNull(checkNotNull(frozen).publication)
    internal fun ownRow(row: TestTerminalDurableRowV1): TestTerminalDurableRowV1 { requirePurge(rows.size < 8); rows.add(row); return row }
    internal fun ownPublication(value: TestRunPurgeRowsV1.Publication): TestRunPurgeRowsV1.Publication {
        requirePurge(publications.size < 8); publications.add(value); return value
    }
    internal fun frozenCandidate(operation: TestRunPurgeOperationV1): TestTerminalDurableRowV1? {
        requireRunning(); requirePurge(operation.original === this && step === TestRunPurgeStepV1.FREEZE)
        return candidate
    }
    internal fun providerProof(operation: TestRunPurgeOperationV1): TestRunPurgeProofV1 {
        requireRunning(); requirePurge(operation.original === this && step === TestRunPurgeStepV1.VERIFY)
        return checkNotNull(proof).also { it.requireOriginal(this) }
    }
    internal fun requireProvider(owner: TestRunPurgeCustodyV1, publication: Boolean) {
        requireConnectionFree(); requireRunning(); requirePurge(custody === owner && phase == null && !phaseEntered)
        checkNotNull(loaded).requireReleased()
        if (publication) { checkNotNull(frozen).requireReleased(); requirePurge(frozenRow().state === TestTerminalDurableStateV1.WIRE_FROZEN) }
    }
    internal fun requireReservation() { requireConnectionFree(); requireRunning(); checkNotNull(loaded).requireReleased(); requirePurge(custody == null) }
    internal fun requireUnverifiedPublication() { requireRunning(); requirePurge(frozenPublication().state == "PREPARED") }
    internal fun requireListedPublicationVersion(version: String?) {
        requireRunning()
        val publication = frozenPublication()
        if (publication.state == "VERIFIED") requirePurge(version != null && version == publication.version)
    }
    internal fun requireRetentionFloor(binding: TestTerminalDurableBindingV1) { manifest.requireRetentionFloor(binding) }
    internal fun requireSameCanonical(left: TestTerminalDurableRowV1, right: TestTerminalDurableRowV1) {
        requirePurge(left.binding == right.binding && left.canonicalSha256 == right.canonicalSha256)
        val a = left.canonicalBytes(); val b = right.canonicalBytes()
        try { requirePurge(MessageDigest.isEqual(a, b)) } finally { a.fill(0); b.fill(0) }
    }
    internal fun requireSameFrozen(left: TestTerminalDurableRowV1, right: TestTerminalDurableRowV1) {
        requireSameCanonical(left, right)
        requirePurge(left.state === TestTerminalDurableStateV1.WIRE_FROZEN && right.state === left.state && left.wireSha256 == right.wireSha256 &&
            left.retainUntil == right.retainUntil && left.frozenAt == right.frozenAt && left.metadataSha256 == right.metadataSha256 && left.checksumSha256 == right.checksumSha256)
        val a = checkNotNull(left.wireBytes()); val b = checkNotNull(right.wireBytes())
        try { requirePurge(MessageDigest.isEqual(a, b)) } finally { a.fill(0); b.fill(0) }
    }
    internal fun completedForRecheck(): TestRunPurgeRowsV1.Verified {
        requireRunning(); requirePurge(step === TestRunPurgeStepV1.COMPLETE)
        return checkNotNull(verified)
    }
    internal fun authenticatedPurge(): TestTerminalObjectRefV1 {
        requireConnectionFree(); requireSuccessfulPurge()
        return checkNotNull(verified).objectRef
    }
    private fun requireSuccessfulPurge() {
        manifest.requirePurgePredecessor(); throwIfSignalled()
        requirePurge(caller === Thread.currentThread() && started && finished && published && !cleanupUncertain && phase == null && !phaseEntered && custody == null)
    }
    /** The actual successful original only; physical retirement of a failed purge is not this entry. */
    internal fun beginTerminalEpochSeal(): TestRunTerminalEpochSealV1 = TestRunTerminalEpochSealV1.begin(this)

    internal fun retainTerminalEpochSeal(candidate: TestRunTerminalEpochSealV1) {
        authenticatedPurge()
        requirePurge(candidate.purge === this && terminalEpochSeal == null)
        terminalEpochSeal = candidate
    }
    internal fun requireTerminalEpochSeal(candidate: TestRunTerminalEpochSealV1) {
        requireSuccessfulPurge()
        requirePurge(candidate.purge === this && terminalEpochSeal === candidate)
    }
    /** Historical authenticated comparison facts. A successor must read/lock its own current rows. */
    internal fun authenticatedFactsForSeal(): TestRunPurgeRowsV1.Verified {
        requireSuccessfulPurge()
        return checkNotNull(verified)
    }
    internal fun requireRetiredForRetry(selected: TestRunInstallationManifestPublicationV1) {
        requireConnectionFree(); requirePurge(caller === Thread.currentThread() && manifest === selected && finished)
        // Once the terminal child is claimed, replacing this ancestor must not create a second
        // terminal original even if that child's PREPARE rolled back before any durable intent.
        requirePurge(terminalEpochSeal == null)
        phase?.let { requirePurge(it.testRunPurgeResourcesRetired(this)); phase = null; phaseEntered = false }
        requirePurge(!phaseEntered)
        custody?.requireRetired(this)
    }
    internal fun retainLease(operation: TestRunPurgeOperationV1, token: Long) {
        requireRunning(); requirePurge(operation.original === this && step === TestRunPurgeStepV1.CAPTURE && leaseToken == 0L && token > manifest.leaseToken)
        leaseToken = token
    }
    internal fun phaseBudget(): PersistenceTimeBudget = budget.capped(codecAttempt?.remainingMillis(Int.MAX_VALUE)?.toLong() ?: budget.remainingMillis(2_000))
    internal fun requirePersistence(ownership: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requireRunning(); registration.requireSealingOwner(ownership); requirePurge(selected.dataSource === coordinator.dataSource)
        if (jdbc == null) jdbc = selected
        requirePurge(jdbc === selected)
    }
    internal fun requirePhaseEntry(ownership: PersistencePhaseOwnership, selected: PersistencePhasePath) {
        requireConnectionFree(); requireRunning(); registration.requireSealingOwner(ownership)
        requirePurge(selected === path && phase == null && !phaseEntered); phaseEntered = true
    }
    internal fun retainPhase(selected: PersistencePhaseContext) { requireRunning(); requirePurge(phaseEntered && phase == null); phase = selected }
    internal fun observePhaseCleanup(selected: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testRunPurgeCleanupProven(this)) cleanupUncertain = true
            else {
                phase = null; phaseEntered = false
                if (selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) { cleanupUncertain = true; observeFailure(TestRunPurgeExceptionV1()) }
            }
        } catch (problem: Throwable) { cleanupUncertain = true; observeFailure(problem) }
    }
    internal fun authenticate(ownership: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requirePersistence(ownership, selected)
        val openings = registration.process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.CATALOG_COORDINATOR }.openings().map { it.publicDriverProperties() }
        val user = openings.map { it["user"] }.distinct().single(); val database = openings.map { it["PGDBNAME"] }.distinct().single()
        requirePurge(selected.query("SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated",
            ResultSetExtractor { row -> row.next() && row.getBoolean("authenticated") && !row.wasNull() && !row.next() }, user, user, database) == true)
        requirePersistence(ownership, selected)
    }
    internal fun requireMaintenanceGate(ownership: PersistencePhaseOwnership, selected: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireRunning(); registration.requireSealingOwner(ownership)
        requirePurge(selected === path && step !== TestRunPurgeStepV1.VERIFY && phaseEntered && phase != null)
        registration.requireSealingGate(gate)
    }
    internal fun requireRunning() {
        throwIfSignalled()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("TEST run purge publication interrupted.")
        requirePurge(caller === Thread.currentThread() && started && !finished && !cleanupUncertain)
        budget.remainingMillis(1); codecAttempt?.remainingMillis(1)
        manifest.requirePurgePredecessor(); registration.requireSealingOwner(coordinator.ownership)
        acquisition.requireRetained(routing, registration.process.publicationLanes)
    }
    internal fun observeFailure(problem: Throwable) {
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("TEST run purge publication cancelled.")
            problem is InterruptedException || problem is InterruptedIOException || (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) ->
                InterruptedException("TEST run purge publication interrupted.")
            else -> TestRunPurgeExceptionV1()
        }
        while (true) {
            val old = failure.get()
            if (old is Error || old is CancellationException && retained !is Error || old is InterruptedException && retained !is Error && retained !is CancellationException ||
                old != null && retained is TestRunPurgeExceptionV1) return
            if (failure.compareAndSet(old, retained)) return
        }
    }
    internal fun throwIfSignalled() { failure.get()?.let { if (it is InterruptedException) Thread.currentThread().interrupt(); throw it } }
    override fun toString(): String = "TestRunPurgePublicationV1(actual-manifest-child,receiptless-verified-only,redacted)"
    companion object {
        internal fun begin(manifest: TestRunInstallationManifestPublicationV1): TestRunPurgePublicationV1 {
            requireConnectionFree(); manifest.requirePurgePredecessor()
            return TestRunPurgePublicationV1(manifest)
        }
    }
}

internal enum class TestRunPurgeStepV1 { CAPTURE, PREPARE, FREEZE, VERIFY, COMPLETE }
internal enum class TestRunPurgePublicationResultV1 { PURGE_AUTHENTICATED_AND_VERIFIED }
internal class TestRunPurgeExceptionV1 : RuntimeException("TEST run purge publication refused.", null, false, false)
internal fun requirePurge(allowed: Boolean) { if (!allowed) throw TestRunPurgeExceptionV1() }
