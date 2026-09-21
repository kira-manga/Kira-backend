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
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEpochSealV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRefV1
import me.manga.kira.backend.complaint.infrastructure.journal.OrdinaryJournalRetentionV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.requireJournalVersion
import me.manga.kira.backend.security.TestTerminalAttemptV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalCodecV1
import me.manga.kira.backend.security.TestTerminalContentV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * First terminal-epoch close, descended from a genuinely completed purge publication. The one
 * rotation and paid CANONICAL intent commit together. Stops at an authenticated TERMINAL seal in
 * the full ordered local set: no terminal denial, final inventories, catalog or PURGING authority.
 * This bounded entry does not recover an existing terminal intent or rehabilitate a failed child.
 */
internal class TestRunTerminalEpochSealV1 private constructor(internal val purge: TestRunPurgePublicationV1) {
    internal val manifest = purge.manifest
    internal val drain = purge.drain
    internal val registration = purge.registration
    internal val coordinator = purge.coordinator
    internal val routing = purge.routing
    internal val scope = purge.scope
    internal val writer = purge.writer
    internal val runContext = purge.runContext
    internal val control = purge.control
    internal val ordinaryCut = purge.ordinaryCut
    internal val ordinarySeal = purge.ordinarySeal
    internal val epoch = purge.epoch
    internal val afterEpoch = Math.addExact(epoch, 1L)
    internal val acquisition = purge.acquisition
    internal val attemptId = UUID.randomUUID()
    internal val intentId = UUID.randomUUID()
    internal val budget = PersistenceTimeBudget.start(routing.journalConfiguration.declaration().limits.deadlines.scanMillis.toLong(), coordinator.ownership.nanoClock)
    internal val codec = TestTerminalCodecV1.fromRetained(routing, acquisition.nanoTime)
    internal val path = PersistencePhasePath.COMPLAINT_TEST_TERMINAL_EPOCH_SEAL
    private val caller = Thread.currentThread()
    private val failure = AtomicReference<Throwable?>()
    private var started = false
    private var finished = false
    private var sealed = false
    private var quiescence: TestRunTerminalQuiescenceV1? = null
    private var cleanupUncertain = false
    private var phaseEntered = false
    private var phase: PersistencePhaseContext? = null
    private var jdbc: JdbcTemplate? = null
    private var capture: TestTerminalEpochSealOperationV1? = null
    private var prepared: TestTerminalEpochSealOperationV1? = null
    private var frozen: TestTerminalEpochSealOperationV1? = null
    private var canonical: TestTerminalDurableRowV1? = null
    private var candidate: TestTerminalDurableRowV1? = null
    private var content: TestTerminalContentV1? = null
    private var attempt: TestTerminalAttemptV1? = null
    private var custody: TestOrdinarySealCustodyV1? = null
    private var proof: TestTerminalEpochSealProofV1? = null
    private var completed: TestTerminalSealRefV1? = null
    private val rows = ArrayList<TestTerminalDurableRowV1>(8)
    internal val codecAttempt: TestTerminalAttemptV1 get() = checkNotNull(attempt)
    internal var step = TestTerminalEpochSealStepV1.CAPTURE
        private set
    internal var leaseToken = 0L
        private set

    init {
        requireConnectionFree()
        purge.authenticatedPurge()
        requireTerminalSeal(!control.needsCapture && control.previousSealEpoch == (control.initialHistory?.reference?.epochEndInclusive ?: 0L) &&
            ordinarySeal.epochStartInclusive == control.ordinaryStart &&
            ordinarySeal.precedingSealSha256 == (control.initialHistory?.reference?.objectRef?.canonicalSha256 ?: "") && epoch == Math.addExact(control.cutoff, 1L) &&
            registration.process.catalogActivation.initialWriterRegistry().eventWriter.generationId == writer)
        acquisition.requireRetained(routing, registration.process.publicationLanes)
        purge.retainTerminalEpochSeal(this)
    }

    fun seal(): TestRunTerminalEpochSealResultV1 {
        requireTerminalSeal(caller === Thread.currentThread() && !started)
        started = true
        var complete = false
        try {
            capture = execute(TestTerminalEpochSealStepV1.CAPTURE)
            attempt = codec.startAttempt(TestTerminalCodecKindV1.EPOCH_SEAL, budget)
            bindCanonical()
            awaitActualCreationSecond(checkNotNull(canonical).binding.createdAt)
            prepared = execute(TestTerminalEpochSealStepV1.PREPARE)
            requireSameCanonical(checkNotNull(canonical), preparedRow())
            val owner = TestOrdinarySealCustodyV1.reserve(this).also { custody = it }
            owner.acquire()
            val envelope = owner.seal()
            try {
                val row = preparedRow()
                val retain = maxOf(row.binding.retentionFloor, acquisition.newRetention(codecAttempt, row.binding.createdAt))
                val at = maxOf(row.binding.createdAt, acquisition.sampleUtc().truncatedTo(ChronoUnit.SECONDS))
                val bytes = envelope.wireBytes()
                candidate = try { ownRow(TestTerminalDurableRowV1.frozen(row, bytes, retain, at)) } finally { bytes.fill(0) }
            } finally { envelope.close() }
            frozen = execute(TestTerminalEpochSealStepV1.FREEZE)
            proof = owner.publishTerminalEpoch()
            owner.close()
            checkNotNull(proof).requireOriginal(this)
            completed = checkNotNull(execute(TestTerminalEpochSealStepV1.VERIFY).terminalReference)
            owner.requireRetired(this)
            custody = null
            execute(TestTerminalEpochSealStepV1.COMPLETE)
            requireRunning(); requireConnectionFree()
            complete = true
        } catch (problem: Throwable) { observeFailure(problem) }
        finally {
            runCatching { custody?.close() }.exceptionOrNull()?.let(::observeFailure)
            content?.close(); content = null
            rows.forEach { it.close() }; rows.clear()
            finished = true
        }
        throwIfSignalled()
        requireTerminalSeal(complete && !cleanupUncertain && phase == null && !phaseEntered && custody == null && completed != null)
        sealed = true
        return TestRunTerminalEpochSealResultV1.TERMINAL_EPOCH_AUTHENTICATED_AND_SEALED
    }

    private fun execute(next: TestTerminalEpochSealStepV1): TestTerminalEpochSealOperationV1 {
        requireConnectionFree(); requireRunning(); requireTerminalSeal(phase == null && !phaseEntered)
        step = next
        try { return coordinator.testTerminalEpochSeal.execute(this).also { it.requireReleased(); requireRunning() } }
        catch (problem: Throwable) { if (phase == null) phaseEntered = false; throw problem }
    }
    private fun bindCanonical() {
        requireConnectionFree(); requireRunning()
        val captured = checkNotNull(capture).also { it.requireReleased() }
        requireTerminalSeal(captured.row == null)
        val expected = codec.canonicalizeEpochSeal(TestTerminalEpochSealV1(1, "EPOCH_SEAL", "A".repeat(43), writer, "TEST", scope.toString(),
            epoch, epoch, captured.manifest.count, captured.manifest.sha256, ordinarySeal.objectRef.canonicalSha256, leaseToken), codecAttempt)
        content = expected
        val created = OrdinaryJournalRetentionV1.ceilingSecond(captured.databaseNow)
        val binding = TestTerminalDurableBindingV1(intentId.toString(), runContext, routing.journalConfiguration.sha256,
            TestTerminalDurableKindV1.EPOCH_SEAL, 1, expected.route.journalId, expected.route.objectKey, expected.route.routingKeyId,
            writer, epoch, epoch, leaseToken, acquisition.newRetention(codecAttempt, created), created)
        val bytes = expected.canonicalBytes()
        canonical = try { ownRow(TestTerminalDurableRowV1.canonical(binding, bytes)) } finally { bytes.fill(0) }
    }
    /** Real wall-clock progress only, with no SQL, provider or lane held and no future sample substitution. */
    private fun awaitActualCreationSecond(createdAt: Instant) {
        while (true) {
            requireConnectionFree(); requireRunning(); requireTerminalSeal(custody == null && phase == null && !phaseEntered)
            if (acquisition.sampleUtc().truncatedTo(ChronoUnit.SECONDS) >= createdAt) return
            Thread.sleep(minOf(25L, codecAttempt.remainingMillis(25).toLong(), budget.remainingMillis(25)))
        }
    }

    internal fun capturedManifest() = checkNotNull(capture).manifest
    internal fun capturedSource() = checkNotNull(capture).source
    internal fun canonicalCandidate(operation: TestTerminalEpochSealOperationV1): TestTerminalDurableRowV1 {
        requireRunning(); requireTerminalSeal(operation.original === this && step === TestTerminalEpochSealStepV1.PREPARE)
        return checkNotNull(canonical)
    }
    internal fun content(): TestTerminalContentV1 = checkNotNull(content)
    internal fun preparedRow(): TestTerminalDurableRowV1 = checkNotNull(checkNotNull(prepared).row)
    internal fun frozenRow(): TestTerminalDurableRowV1 = checkNotNull(checkNotNull(frozen).row)
    internal fun ownRow(row: TestTerminalDurableRowV1): TestTerminalDurableRowV1 { requireTerminalSeal(rows.size < 8); rows.add(row); return row }
    internal fun frozenCandidate(operation: TestTerminalEpochSealOperationV1): TestTerminalDurableRowV1 {
        requireRunning(); requireTerminalSeal(operation.original === this && step === TestTerminalEpochSealStepV1.FREEZE)
        return checkNotNull(candidate)
    }
    internal fun providerProof(operation: TestTerminalEpochSealOperationV1): TestTerminalEpochSealProofV1 {
        requireRunning(); requireTerminalSeal(operation.original === this && step === TestTerminalEpochSealStepV1.VERIFY)
        return checkNotNull(proof).also { it.requireOriginal(this) }
    }
    internal fun requireProvider(owner: TestOrdinarySealCustodyV1, publication: Boolean) {
        requireConnectionFree(); requireRunning(); requireTerminalSeal(custody === owner && phase == null && !phaseEntered)
        checkNotNull(prepared).requireReleased()
        if (publication) { checkNotNull(frozen).requireReleased(); requireTerminalSeal(frozenRow().state === TestTerminalDurableStateV1.WIRE_FROZEN) }
    }
    internal fun requireReservation() { requireConnectionFree(); requireRunning(); checkNotNull(prepared).requireReleased(); requireTerminalSeal(custody == null) }
    internal fun requireUnverifiedSeal() { requireRunning(); requireTerminalSeal(completed == null && step === TestTerminalEpochSealStepV1.FREEZE) }
    internal fun requireListedSealVersion(version: String?) {
        requireUnverifiedSeal()
        version?.let(::requireJournalVersion) // Syntax only; native exact LIST/GET still authenticates the winner.
    }
    internal fun requireRetentionFloor(binding: TestTerminalDurableBindingV1) = purge.requireRetentionFloor(binding)
    internal fun requireSameCanonical(left: TestTerminalDurableRowV1, right: TestTerminalDurableRowV1) {
        requireTerminalSeal(left.binding == right.binding && left.canonicalSha256 == right.canonicalSha256)
        val a = left.canonicalBytes(); val b = right.canonicalBytes()
        try { requireTerminalSeal(MessageDigest.isEqual(a, b)) } finally { a.fill(0); b.fill(0) }
    }
    internal fun requireSameFrozen(left: TestTerminalDurableRowV1, right: TestTerminalDurableRowV1) {
        requireSameCanonical(left, right)
        requireTerminalSeal(left.state === TestTerminalDurableStateV1.WIRE_FROZEN && right.state === left.state && left.wireSha256 == right.wireSha256 &&
            left.retainUntil == right.retainUntil && left.frozenAt == right.frozenAt && left.metadataSha256 == right.metadataSha256 && left.checksumSha256 == right.checksumSha256)
        val a = checkNotNull(left.wireBytes()); val b = checkNotNull(right.wireBytes())
        try { requireTerminalSeal(MessageDigest.isEqual(a, b)) } finally { a.fill(0); b.fill(0) }
    }
    internal fun completedForRecheck(): TestTerminalSealRefV1 {
        requireRunning(); requireTerminalSeal(step === TestTerminalEpochSealStepV1.COMPLETE)
        return checkNotNull(completed)
    }
    internal fun authenticatedSeal(): TestTerminalSealRefV1 {
        requireConnectionFree(); requireSuccessfulTerminalSeal()
        return checkNotNull(completed)
    }
    private fun requireSuccessfulTerminalSeal() {
        purge.requireTerminalEpochSeal(this); throwIfSignalled()
        requireTerminalSeal(caller === Thread.currentThread() && started && finished && sealed && !cleanupUncertain && phase == null && !phaseEntered && custody == null)
    }
    /** Historical successful original only; the child acquires its own current fence and budget. */
    internal fun beginTerminalQuiescence(): TestRunTerminalQuiescenceV1 = TestRunTerminalQuiescenceV1.begin(this)
    internal fun retainQuiescence(child: TestRunTerminalQuiescenceV1) {
        authenticatedSeal()
        requireTerminalSeal(child.terminalSeal === this && quiescence == null)
        quiescence = child
    }
    internal fun requireQuiescence(child: TestRunTerminalQuiescenceV1) {
        requireSuccessfulTerminalSeal()
        requireTerminalSeal(child.terminalSeal === this && quiescence === child)
    }

    internal fun retainLease(operation: TestTerminalEpochSealOperationV1, token: Long) {
        requireRunning(); requireTerminalSeal(operation.original === this && step === TestTerminalEpochSealStepV1.CAPTURE && leaseToken == 0L && token > purge.leaseToken)
        leaseToken = token
    }
    internal fun phaseBudget(): PersistenceTimeBudget = budget.capped(attempt?.remainingMillis(Int.MAX_VALUE)?.toLong() ?: budget.remainingMillis(2_000))
    internal fun requirePersistence(ownership: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requireRunning(); registration.requireSealingOwner(ownership); requireTerminalSeal(selected.dataSource === coordinator.dataSource)
        if (jdbc == null) jdbc = selected
        requireTerminalSeal(jdbc === selected)
    }
    internal fun requirePhaseEntry(ownership: PersistencePhaseOwnership, selected: PersistencePhasePath) {
        requireConnectionFree(); requireRunning(); registration.requireSealingOwner(ownership)
        requireTerminalSeal(selected === path && phase == null && !phaseEntered); phaseEntered = true
    }
    internal fun retainPhase(selected: PersistencePhaseContext) { requireRunning(); requireTerminalSeal(phaseEntered && phase == null); phase = selected }
    internal fun observePhaseCleanup(selected: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testTerminalEpochSealCleanupProven(this)) cleanupUncertain = true
            else {
                phase = null; phaseEntered = false
                if (selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) { cleanupUncertain = true; observeFailure(TestTerminalEpochSealExceptionV1()) }
            }
        } catch (problem: Throwable) { cleanupUncertain = true; observeFailure(problem) }
    }
    internal fun authenticate(ownership: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requirePersistence(ownership, selected)
        val openings = registration.process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.CATALOG_COORDINATOR }.openings().map { it.publicDriverProperties() }
        val user = openings.map { it["user"] }.distinct().single(); val database = openings.map { it["PGDBNAME"] }.distinct().single()
        requireTerminalSeal(selected.query("SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated",
            ResultSetExtractor { row -> row.next() && row.getBoolean("authenticated") && !row.wasNull() && !row.next() }, user, user, database) == true)
        requirePersistence(ownership, selected)
    }
    internal fun requireMaintenanceGate(ownership: PersistencePhaseOwnership, selected: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireRunning(); registration.requireSealingOwner(ownership)
        requireTerminalSeal(selected === path && phaseEntered && phase != null)
        registration.requireSealingGate(gate)
    }
    internal fun requireRunning() {
        throwIfSignalled()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("TEST terminal epoch seal interrupted.")
        requireTerminalSeal(caller === Thread.currentThread() && started && !finished && !cleanupUncertain)
        budget.remainingMillis(1); attempt?.remainingMillis(1)
        // This is historical successful provenance, never a borrowed active SQL operation.
        purge.requireTerminalEpochSeal(this); registration.requireSealingOwner(coordinator.ownership)
        acquisition.requireRetained(routing, registration.process.publicationLanes)
    }
    internal fun observeFailure(problem: Throwable) {
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("TEST terminal epoch seal cancelled.")
            problem is InterruptedException || problem is InterruptedIOException || (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) ->
                InterruptedException("TEST terminal epoch seal interrupted.")
            else -> TestTerminalEpochSealExceptionV1()
        }
        while (true) {
            val old = failure.get()
            if (old is Error || old is CancellationException && retained !is Error || old is InterruptedException && retained !is Error && retained !is CancellationException ||
                old != null && retained is TestTerminalEpochSealExceptionV1) return
            if (failure.compareAndSet(old, retained)) return
        }
    }
    internal fun throwIfSignalled() { failure.get()?.let { if (it is InterruptedException) Thread.currentThread().interrupt(); throw it } }
    override fun toString(): String = "TestRunTerminalEpochSealV1(actual-purge-child,no-terminal-closeout-authority,redacted)"
    companion object {
        internal fun begin(purge: TestRunPurgePublicationV1): TestRunTerminalEpochSealV1 {
            requireConnectionFree(); purge.authenticatedPurge()
            return TestRunTerminalEpochSealV1(purge)
        }
    }
}

internal enum class TestTerminalEpochSealStepV1 { CAPTURE, PREPARE, FREEZE, VERIFY, COMPLETE }
internal enum class TestRunTerminalEpochSealResultV1 { TERMINAL_EPOCH_AUTHENTICATED_AND_SEALED }
internal class TestTerminalEpochSealExceptionV1 : RuntimeException("TEST terminal epoch seal refused.", null, false, false)
internal fun requireTerminalSeal(allowed: Boolean) { if (!allowed) throw TestTerminalEpochSealExceptionV1() }
