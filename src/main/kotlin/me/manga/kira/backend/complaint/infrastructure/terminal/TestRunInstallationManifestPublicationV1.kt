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
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStateV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalManifestSummaryV1
import me.manga.kira.backend.security.TestTerminalAttemptV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalCodecV1
import me.manga.kira.backend.security.TestTerminalContentV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * One finite same-process child of an actually successful all-chunks PREPARE. No enum, row,
 * supplied reference list or bare registration admits it. Each paid winner is reloaded under a
 * fresh fence, restored connection-free, frozen/released, authenticated, disposed, then VERIFIED.
 * No final seal, terminal catalog, APPLIED, capacity transfer or run-progress write exists here.
 * Only the actual completed original, with its genuine chunk fold, admits the separate purge child.
 */
internal class TestRunInstallationManifestPublicationV1 private constructor(internal val preparation: TestRunInstallationManifestV1) {
    internal val drain = preparation.drain
    internal val registration = preparation.registration
    internal val coordinator = preparation.coordinator
    internal val routing = preparation.routing
    internal val scope = preparation.scope
    internal val writer = preparation.writer
    internal val runContext = preparation.runContext
    internal val control = preparation.control
    internal val ordinaryCut = preparation.ordinaryCut
    internal val ordinarySeal = preparation.ordinarySeal
    internal val epoch = preparation.epoch
    internal val acquisition = registration.process.ordinarySeal ?: throw TestInstallationManifestExceptionV1()
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
    private var capture: TestInstallationManifestPublicationOperationV1? = null
    private var loaded: TestInstallationManifestPublicationOperationV1? = null
    private var frozen: TestInstallationManifestPublicationOperationV1? = null
    private var candidate: TestTerminalDurableRowV1? = null
    private var content: TestTerminalContentV1? = null
    private var codecAttempt: TestTerminalAttemptV1? = null
    private var custody: TestInstallationManifestCustodyV1? = null
    private var proof: TestInstallationManifestProofV1? = null
    private val rows = ArrayList<TestTerminalDurableRowV1>(5)
    private val publications = ArrayList<TestInstallationManifestPublicationRowsV1.Publication>(4)
    // Scalar, genuinely authenticated exact versions only. Never retain plaintext across chunks.
    private val references = ArrayList<TestInstallationManifestPublicationRowsV1.Verified>()
    private var completedSummary: TestTerminalManifestSummaryV1? = null
    private var purge: TestRunPurgePublicationV1? = null
    internal var step = TestInstallationManifestPublicationStepV1.CAPTURE
        private set
    internal var leaseToken = 0L
        private set
    internal var chunkIndex = -1
        private set
    internal val path get() = if (step === TestInstallationManifestPublicationStepV1.VERIFY)
        PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_VERIFY else PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PUBLICATION

    init {
        requireConnectionFree()
        preparation.requirePublicationPredecessor()
        acquisition.requireRetained(routing, registration.process.publicationLanes)
        preparation.retainPublication(this)
    }

    fun publish(): TestRunInstallationManifestPublicationResultV1 {
        requireManifest(caller === Thread.currentThread() && !started)
        started = true
        var complete = false
        try {
            capture = execute(TestInstallationManifestPublicationStepV1.CAPTURE)
            val chunks = capturedSource().startChunks(epoch)
            repeat(capturedSource().count) { index ->
                chunkIndex = index
                codecAttempt = codec.startAttempt(TestTerminalCodecKindV1.INSTALLATION_MANIFEST, budget)
                loaded = execute(TestInstallationManifestPublicationStepV1.LOAD)
                restoreCanonical()
                val owner = TestInstallationManifestCustodyV1.reserve(this).also { custody = it }
                owner.acquire()
                if (loadedRow().state === TestTerminalDurableStateV1.CANONICAL) {
                    val envelope = owner.seal()
                    try {
                        val canonical = loadedRow()
                        val retain = maxOf(canonical.binding.retentionFloor, acquisition.newRetention(codecAttempt(), canonical.binding.createdAt))
                        val at = maxOf(canonical.binding.createdAt, acquisition.sampleUtc().truncatedTo(ChronoUnit.SECONDS))
                        val wire = envelope.wireBytes()
                        candidate = try { ownRow(TestTerminalDurableRowV1.frozen(canonical, wire, retain, at)) } finally { wire.fill(0) }
                    } finally { envelope.close() }
                }
                // Also reload an existing frozen winner here: fresh current authority follows STS,
                // but no key generation, re-encryption, second charge or immutable-row UPDATE does.
                frozen = execute(TestInstallationManifestPublicationStepV1.FREEZE)
                proof = owner.publish()
                owner.close() // Actual native return AND shared-lane release precede receiptless SQL.
                checkNotNull(proof).requireOriginal(this)
                val verified = execute(TestInstallationManifestPublicationStepV1.VERIFY)
                requireManifest(references.size == index && index < 4096)
                val reference = checkNotNull(verified.publication).verifiedFacts()
                val canonical = content().canonicalBytes()
                try {
                    chunks.add(TestTerminalJsonV1(routing.journalConfiguration).installationManifest(canonical), reference.objectRef)
                } finally { canonical.fill(0) }
                references.add(reference)
                owner.requireRetired(this)
                custody = null
                clearChunk()
            }
            execute(TestInstallationManifestPublicationStepV1.COMPLETE)
            requireRunning()
            requireConnectionFree()
            completedSummary = chunks.finish()
            complete = true
        } catch (problem: Throwable) { observeFailure(problem) }
        finally {
            runCatching { custody?.close() }.exceptionOrNull()?.let(::observeFailure)
            clearChunk()
            finished = true
        }
        throwIfSignalled()
        requireManifest(complete && !cleanupUncertain && phase == null && !phaseEntered && custody == null && references.size == capturedSource().count)
        published = true
        return TestRunInstallationManifestPublicationResultV1.ALL_CHUNKS_AUTHENTICATED_AND_VERIFIED
    }

    private fun execute(next: TestInstallationManifestPublicationStepV1): TestInstallationManifestPublicationOperationV1 {
        requireConnectionFree(); requireRunning(); requireManifest(phase == null && !phaseEntered)
        step = next
        try { return coordinator.testInstallationManifestPublication.execute(this).also { it.requireReleased(); requireRunning() } }
        catch (problem: Throwable) {
            // Ownership retains the exact context BEFORE publication/permit effects. With no retained
            // context, failed entry never owned a holder; a retained unresolved context is never cleared here.
            if (phase == null) phaseEntered = false
            throw problem
        }
    }

    private fun restoreCanonical() {
        requireConnectionFree(); requireRunning(); checkNotNull(loaded).requireReleased()
        val row = loadedRow()
        val bytes = row.canonicalBytes()
        try {
            content = codec.restoreCanonical(TestTerminalCodecKindV1.INSTALLATION_MANIFEST, bytes, row.binding.routingKeyId,
                row.binding.objectKey, row.canonicalSha256, codecAttempt())
            // Decode one bounded chunk only, after SQL release. The locked fresh raw-source descriptor
            // commits to these entries; neither a generic row nor the original PREPARE enum is authority.
            val value = TestTerminalJsonV1(routing.journalConfiguration).installationManifest(bytes)
            val expected = expectedChunk()
            requireManifest(value.context().run == runContext && value.eventId == row.binding.objectId && value.writerGeneration == writer &&
                value.publicationEpoch == epoch && value.chunkIndex == chunkIndex && value.chunkCount == capturedSource().count &&
                value.installationCount == expected.count.toLong() && value.entriesSha256 == expected.entriesSha256 &&
                value.installationsSha256 == capturedSource().progress.installationReads().first().installationsSha256)
        } finally { bytes.fill(0) }
        requireRunning()
    }

    private fun clearChunk() {
        content?.close(); content = null
        rows.forEach { it.close() }; rows.clear()
        publications.forEach { it.close() }; publications.clear()
        loaded = null; frozen = null; candidate = null; codecAttempt = null; proof = null
    }

    internal fun capturedSource(): TestInstallationManifestSourceV1.Observation = checkNotNull(checkNotNull(capture).source)
    internal fun expectedChunk(): TestInstallationManifestSourceV1.Descriptor = preparation.capturedSource().chunk(chunkIndex)
    internal fun codecAttempt(): TestTerminalAttemptV1 = checkNotNull(codecAttempt)
    internal fun content(): TestTerminalContentV1 = checkNotNull(content)
    internal fun loadedRow(): TestTerminalDurableRowV1 = checkNotNull(checkNotNull(loaded).row)
    internal fun frozenRow(): TestTerminalDurableRowV1 = checkNotNull(checkNotNull(frozen).row)
    private fun frozenPublication(): TestInstallationManifestPublicationRowsV1.Publication = checkNotNull(checkNotNull(frozen).publication)
    internal fun ownRow(row: TestTerminalDurableRowV1): TestTerminalDurableRowV1 { requireManifest(rows.size < 5); rows.add(row); return row }
    internal fun ownPublication(value: TestInstallationManifestPublicationRowsV1.Publication): TestInstallationManifestPublicationRowsV1.Publication {
        requireManifest(publications.size < 4); publications.add(value); return value
    }
    internal fun frozenCandidate(operation: TestInstallationManifestPublicationOperationV1): TestTerminalDurableRowV1? {
        requireRunning(); requireManifest(operation.original === this && step === TestInstallationManifestPublicationStepV1.FREEZE)
        return candidate
    }
    internal fun providerProof(operation: TestInstallationManifestPublicationOperationV1): TestInstallationManifestProofV1 {
        requireRunning(); requireManifest(operation.original === this && step === TestInstallationManifestPublicationStepV1.VERIFY)
        return checkNotNull(proof).also { it.requireOriginal(this) }
    }
    internal fun requireProvider(owner: TestInstallationManifestCustodyV1, publication: Boolean) {
        requireConnectionFree(); requireRunning()
        requireManifest(custody === owner && phase == null && !phaseEntered)
        checkNotNull(loaded).requireReleased()
        if (publication) { checkNotNull(frozen).requireReleased(); requireManifest(frozenRow().state === TestTerminalDurableStateV1.WIRE_FROZEN) }
    }
    internal fun requireReservation() { requireConnectionFree(); requireRunning(); checkNotNull(loaded).requireReleased(); requireManifest(custody == null) }
    internal fun requireUnverifiedPublication() { requireRunning(); requireManifest(frozenPublication().state == "PREPARED") }
    internal fun requireListedPublicationVersion(version: String?) {
        requireRunning()
        val publication = frozenPublication()
        if (publication.state == "VERIFIED") requireManifest(version != null && version == publication.version)
    }
    internal fun requireRetentionFloor(binding: TestTerminalDurableBindingV1) { preparation.requireRetentionFloor(binding) }
    internal fun requireSameCanonical(left: TestTerminalDurableRowV1, right: TestTerminalDurableRowV1) {
        requireManifest(left.binding == right.binding && left.canonicalSha256 == right.canonicalSha256)
        val a = left.canonicalBytes(); val b = right.canonicalBytes()
        try { requireManifest(MessageDigest.isEqual(a, b)) } finally { a.fill(0); b.fill(0) }
    }
    internal fun requireSameFrozen(left: TestTerminalDurableRowV1, right: TestTerminalDurableRowV1) {
        requireSameCanonical(left, right)
        requireManifest(left.state === TestTerminalDurableStateV1.WIRE_FROZEN && right.state === left.state && left.wireSha256 == right.wireSha256 &&
            left.retainUntil == right.retainUntil && left.frozenAt == right.frozenAt && left.metadataSha256 == right.metadataSha256 && left.checksumSha256 == right.checksumSha256)
        val a = checkNotNull(left.wireBytes()); val b = checkNotNull(right.wireBytes())
        try { requireManifest(MessageDigest.isEqual(a, b)) } finally { a.fill(0); b.fill(0) }
    }

    internal fun requirePurgePredecessor() {
        preparation.requirePublicationPredecessor(); throwIfSignalled()
        requireManifest(caller === Thread.currentThread() && started && finished && published && !cleanupUncertain && phase == null &&
            !phaseEntered && custody == null && references.size == capturedSource().count && completedSummary != null)
    }
    internal fun beginPurgePublication(): TestRunPurgePublicationV1 = TestRunPurgePublicationV1.begin(this)

    internal fun retainPurge(candidate: TestRunPurgePublicationV1) {
        requireConnectionFree(); requirePurgePredecessor()
        requireManifest(candidate.manifest === this)
        purge?.requireRetiredForRetry(this)
        purge = candidate
    }
    internal fun authenticatedSummary(): TestTerminalManifestSummaryV1 {
        requirePurgePredecessor()
        return checkNotNull(completedSummary)
    }
    /** Exact successful original; source summaries or a terminal result enum cannot admit a purge. */
    internal fun authenticatedChunk(index: Int): TestTerminalObjectRefV1 {
        requirePurgePredecessor()
        return references[index].objectRef
    }
    internal fun authenticatedFacts(index: Int): TestInstallationManifestPublicationRowsV1.Verified {
        requirePurgePredecessor()
        return references[index]
    }
    internal fun completedChunkForRecheck(index: Int): TestInstallationManifestPublicationRowsV1.Verified {
        requireRunning(); requireManifest(step === TestInstallationManifestPublicationStepV1.COMPLETE && references.size == capturedSource().count)
        return references[index]
    }
    internal fun requireRetiredForRetry(selected: TestRunInstallationManifestV1) {
        requireConnectionFree()
        requireManifest(caller === Thread.currentThread() && preparation === selected && finished)
        phase?.let {
            // An UNKNOWN/failed child remains failed even after genuine resource retirement. This
            // narrow physical fact permits only a NEW child; its fresh SQL still needs lease expiry.
            requireManifest(it.testInstallationManifestPublicationResourcesRetired(this))
            phase = null; phaseEntered = false
        }
        requireManifest(!phaseEntered)
        custody?.requireRetired(this)
        purge?.requireRetiredForRetry(this)
    }
    internal fun retainLease(operation: TestInstallationManifestPublicationOperationV1, token: Long) {
        requireRunning(); requireManifest(operation.original === this && step === TestInstallationManifestPublicationStepV1.CAPTURE && leaseToken == 0L && token > preparation.leaseToken)
        leaseToken = token
    }
    internal fun phaseBudget(): PersistenceTimeBudget = budget.capped(codecAttempt?.remainingMillis(Int.MAX_VALUE)?.toLong() ?: budget.remainingMillis(2_000))
    internal fun requirePersistence(ownership: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requireRunning(); registration.requireSealingOwner(ownership)
        requireManifest(selected.dataSource === coordinator.dataSource)
        if (jdbc == null) jdbc = selected
        requireManifest(jdbc === selected)
    }
    internal fun requirePhaseEntry(ownership: PersistencePhaseOwnership, selected: PersistencePhasePath) {
        requireConnectionFree(); requireRunning(); registration.requireSealingOwner(ownership)
        requireManifest(selected === path && phase == null && !phaseEntered)
        phaseEntered = true
    }
    internal fun retainPhase(selected: PersistencePhaseContext) { requireRunning(); requireManifest(phaseEntered && phase == null); phase = selected }
    internal fun observePhaseCleanup(selected: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testInstallationManifestPublicationCleanupProven(this)) cleanupUncertain = true
            else {
                phase = null; phaseEntered = false
                if (selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) { cleanupUncertain = true; observeFailure(TestInstallationManifestExceptionV1()) }
            }
        } catch (problem: Throwable) { cleanupUncertain = true; observeFailure(problem) }
    }
    internal fun authenticate(ownership: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requirePersistence(ownership, selected)
        val openings = registration.process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.CATALOG_COORDINATOR }.openings().map { it.publicDriverProperties() }
        val user = openings.map { it["user"] }.distinct().single(); val database = openings.map { it["PGDBNAME"] }.distinct().single()
        requireManifest(selected.query("SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated",
            ResultSetExtractor { row -> row.next() && row.getBoolean("authenticated") && !row.wasNull() && !row.next() }, user, user, database) == true)
        requirePersistence(ownership, selected)
    }
    internal fun requireMaintenanceGate(ownership: PersistencePhaseOwnership, selected: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireRunning(); registration.requireSealingOwner(ownership)
        requireManifest(selected === path && step !== TestInstallationManifestPublicationStepV1.VERIFY && phaseEntered && phase != null)
        registration.requireSealingGate(gate)
    }
    internal fun requireRunning() {
        throwIfSignalled()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("TEST installation manifest publication interrupted.")
        requireManifest(caller === Thread.currentThread() && started && !finished && !cleanupUncertain)
        budget.remainingMillis(1); codecAttempt?.remainingMillis(1)
        preparation.requirePublicationPredecessor()
        registration.requireSealingOwner(coordinator.ownership)
        acquisition.requireRetained(routing, registration.process.publicationLanes)
    }
    internal fun observeFailure(problem: Throwable) {
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("TEST installation manifest publication cancelled.")
            problem is InterruptedException || problem is InterruptedIOException || (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) ->
                InterruptedException("TEST installation manifest publication interrupted.")
            else -> TestInstallationManifestExceptionV1()
        }
        while (true) {
            val old = failure.get()
            if (old is Error || old is CancellationException && retained !is Error || old is InterruptedException && retained !is Error && retained !is CancellationException ||
                old != null && retained is TestInstallationManifestExceptionV1) return
            if (failure.compareAndSet(old, retained)) return
        }
    }
    internal fun throwIfSignalled() { failure.get()?.let { if (it is InterruptedException) Thread.currentThread().interrupt(); throw it } }
    override fun toString(): String = "TestRunInstallationManifestPublicationV1(actual-preparation-child,redacted)"
    companion object {
        internal fun begin(preparation: TestRunInstallationManifestV1): TestRunInstallationManifestPublicationV1 {
            requireConnectionFree(); preparation.requirePublicationPredecessor()
            return TestRunInstallationManifestPublicationV1(preparation)
        }
    }
}

internal enum class TestInstallationManifestPublicationStepV1 { CAPTURE, LOAD, FREEZE, VERIFY, COMPLETE }
internal enum class TestRunInstallationManifestPublicationResultV1 { ALL_CHUNKS_AUTHENTICATED_AND_VERIFIED }
