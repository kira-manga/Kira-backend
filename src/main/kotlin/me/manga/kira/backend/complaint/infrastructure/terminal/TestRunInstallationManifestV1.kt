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
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationManifestV1
import me.manga.kira.backend.security.TestTerminalAttemptV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalCodecV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * Concrete next edge after the actual registered strict drain. No bare registration, observation,
 * enum, supplied chunk or SQL callback admits this producer. PREPARE ONLY: no SDK/KMS/S3 call,
 * publication verification, purge, final terminal evidence or namespace transition is provided.
 */
internal class TestRunInstallationManifestV1 private constructor(internal val drain: TestRunOrdinaryDrainV1) {
    internal val registration = drain.registration
    internal val coordinator = registration.process.pools.catalogCoordinator
    internal val routing = registration.process.consumers.journalRouting
    internal val scope = drain.scope
    internal val writer = drain.writer
    internal val runContext = drain.runContext
    internal val control = drain.manifestControl()
    internal val ordinaryCut = drain.manifestCut()
    internal val ordinarySeal = drain.manifestSeal()
    internal val epoch = control.epoch
    internal val attemptId = UUID.randomUUID()
    internal val path = PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PREPARE
    internal val budget = PersistenceTimeBudget.start(routing.journalConfiguration.declaration().limits.deadlines.scanMillis.toLong(), coordinator.ownership.nanoClock)
    private val acquisition = registration.process.ordinarySeal ?: throw TestInstallationManifestExceptionV1()
    private val codec = TestTerminalCodecV1.fromRetained(routing, acquisition.nanoTime)
    private val caller = Thread.currentThread()
    private val failure = AtomicReference<Throwable?>()
    private val rows = ArrayList<TestTerminalDurableRowV1>(3)
    private var started = false
    private var finished = false
    private var completedPreparation = false
    private var publication: TestRunInstallationManifestPublicationV1? = null
    private var cleanupUncertain = false
    private var phaseEntered = false
    private var phase: PersistencePhaseContext? = null
    private var jdbc: JdbcTemplate? = null
    private var capture: TestInstallationManifestOperationV1? = null
    private var selected: TestInstallationManifestOperationV1? = null
    private var canonical: TestTerminalDurableRowV1? = null
    private var codecAttempt: TestTerminalAttemptV1? = null
    internal var step = TestInstallationManifestStepV1.CAPTURE
        private set
    internal var leaseToken = 0L
        private set
    internal var chunkIndex = -1
        private set

    init {
        requireConnectionFree()
        drain.requireManifestPredecessor()
        registration.requireUsable()
        acquisition.requireRetained(routing, registration.process.publicationLanes)
        requireManifest(control.sequence == 1L && epoch == Math.addExact(control.cutoff, 1L))
    }

    fun prepare(): TestRunInstallationManifestResultV1 {
        requireManifest(caller === Thread.currentThread() && !started)
        started = true
        var complete = false
        try {
            capture = execute(TestInstallationManifestStepV1.CAPTURE)
            val source = capturedSource()
            repeat(source.count) { index ->
                chunkIndex = index
                selected = execute(TestInstallationManifestStepV1.CHUNK)
                bindCanonical()
                val prepared = execute(TestInstallationManifestStepV1.PREPARE)
                requireSameCanonical(checkNotNull(canonical), checkNotNull(prepared.row))
                clearChunk()
            }
            execute(TestInstallationManifestStepV1.COMPLETE)
            requireRunning()
            requireConnectionFree()
            complete = true
        } catch (problem: Throwable) { observeFailure(problem) }
        finally { clearChunk(); finished = true }
        throwIfSignalled()
        requireManifest(complete && !cleanupUncertain && phase == null && !phaseEntered)
        completedPreparation = true
        return TestRunInstallationManifestResultV1.ALL_CHUNKS_PREPARED_NO_NETWORK
    }

    /** Only this actually completed original can admit a fresh publication child. */
    fun beginPublication(): TestRunInstallationManifestPublicationV1 = TestRunInstallationManifestPublicationV1.begin(this)

    internal fun requirePublicationPredecessor() {
        throwIfSignalled()
        requireManifest(caller === Thread.currentThread() && started && finished && completedPreparation && !cleanupUncertain &&
            phase == null && !phaseEntered && step === TestInstallationManifestStepV1.COMPLETE)
        drain.requireManifestPredecessor()
        registration.requireSealingOwner(coordinator.ownership)
        acquisition.requireRetained(routing, registration.process.publicationLanes)
    }
    internal fun retainPublication(candidate: TestRunInstallationManifestPublicationV1) {
        requireConnectionFree(); requirePublicationPredecessor()
        requireManifest(candidate.preparation === this)
        publication?.requireRetiredForRetry(this)
        publication = candidate
    }

    private fun execute(next: TestInstallationManifestStepV1): TestInstallationManifestOperationV1 {
        requireConnectionFree(); requireRunning(); requireManifest(phase == null && !phaseEntered)
        step = next
        return coordinator.testInstallationManifest.execute(this).also { it.requireReleased(); requireRunning() }
    }

    private fun bindCanonical() {
        requireConnectionFree(); requireRunning()
        val loaded = checkNotNull(selected).also { it.requireReleased() }
        val source = capturedSource()
        val entries = loaded.releasedChunkEntries()
        val retained = loaded.row
        val attempt = codec.startAttempt(TestTerminalCodecKindV1.INSTALLATION_MANIFEST, budget).also { codecAttempt = it }
        val declaration = TestTerminalInstallationManifestV1.create(TestTerminalEventContextV1(runContext, "A".repeat(43), epoch, writer),
            chunkIndex, source.count, source.progress.installationReads().first().installationsSha256, entries)
        val expected = codec.canonicalizeInstallationManifest(declaration, attempt, retained?.binding?.routingKeyId)
        try {
            if (retained == null) {
                val created = loaded.databaseNow.truncatedTo(ChronoUnit.SECONDS)
                val binding = TestTerminalDurableBindingV1(UUID.randomUUID().toString(), runContext, routing.journalConfiguration.sha256,
                    TestTerminalDurableKindV1.INSTALLATION_MANIFEST, chunkIndex, expected.route.journalId, expected.route.objectKey,
                    expected.route.routingKeyId, writer, epoch, epoch, leaseToken, acquisition.newRetention(attempt, created), created)
                val bytes = expected.canonicalBytes()
                canonical = try { ownRow(TestTerminalDurableRowV1.canonical(binding, bytes)) } finally { bytes.fill(0) }
            } else {
                val actual = retained.canonicalBytes(); val computed = expected.canonicalBytes()
                try {
                    requireManifest(retained.state === TestTerminalDurableStateV1.CANONICAL && retained.binding.objectId == expected.route.journalId &&
                        retained.binding.objectKey == expected.route.objectKey && retained.canonicalSha256 == expected.canonicalSha256 && MessageDigest.isEqual(actual, computed))
                    codec.restoreCanonical(TestTerminalCodecKindV1.INSTALLATION_MANIFEST, actual, retained.binding.routingKeyId,
                        retained.binding.objectKey, retained.canonicalSha256, attempt).close()
                    canonical = retained
                } finally { actual.fill(0); computed.fill(0) }
            }
        } finally { expected.close() }
        requireRunning()
    }

    private fun clearChunk() {
        rows.forEach { it.close() }; rows.clear()
        selected = null; canonical = null; codecAttempt = null
    }
    internal fun capturedSource(): TestInstallationManifestSourceV1.Observation = checkNotNull(checkNotNull(capture).source)
    internal fun expectedChunk(): TestInstallationManifestSourceV1.Descriptor = capturedSource().chunk(chunkIndex)
    internal fun ownRow(row: TestTerminalDurableRowV1): TestTerminalDurableRowV1 {
        requireManifest(rows.size < 3)
        rows.add(row); return row
    }
    internal fun candidate(operation: TestInstallationManifestOperationV1): TestTerminalDurableRowV1 {
        requireRunning(); requireManifest(operation.original === this && step === TestInstallationManifestStepV1.PREPARE && selected != null)
        return checkNotNull(canonical)
    }
    internal fun requireSameCanonical(left: TestTerminalDurableRowV1, right: TestTerminalDurableRowV1) {
        requireManifest(left.binding == right.binding && left.canonicalSha256 == right.canonicalSha256 &&
            left.state === TestTerminalDurableStateV1.CANONICAL && right.state === left.state)
        val a = left.canonicalBytes(); val b = right.canonicalBytes()
        try { requireManifest(MessageDigest.isEqual(a, b)) } finally { a.fill(0); b.fill(0) }
    }
    internal fun requireRetentionFloor(binding: TestTerminalDurableBindingV1) {
        requireManifest(binding.retentionFloor >= acquisition.retention.lastPreRunRestoreHorizon.plusSeconds(31 * 86_400L))
    }
    internal fun retainLease(operation: TestInstallationManifestOperationV1, token: Long) {
        requireRunning(); requireManifest(operation.original === this && step === TestInstallationManifestStepV1.CAPTURE && leaseToken == 0L && token > drain.leaseToken)
        leaseToken = token
    }

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
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testInstallationManifestCleanupProven(this) ||
                selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) cleanupUncertain = true
            else { phase = null; phaseEntered = false }
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
        requireManifest(selected === path && phaseEntered && phase != null)
        registration.requireSealingGate(gate)
    }
    internal fun requireRunning() {
        throwIfSignalled()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("TEST installation manifest interrupted.")
        requireManifest(caller === Thread.currentThread() && started && !finished && !cleanupUncertain)
        budget.remainingMillis(1); codecAttempt?.remainingMillis(1)
        drain.requireManifestPredecessor()
        registration.requireSealingOwner(coordinator.ownership)
        acquisition.requireRetained(routing, registration.process.publicationLanes)
    }
    internal fun observeFailure(problem: Throwable) {
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("TEST installation manifest cancelled.")
            problem is InterruptedException || problem is InterruptedIOException || (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) ->
                InterruptedException("TEST installation manifest interrupted.")
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
    override fun toString(): String = "TestRunInstallationManifestV1(registered-strict-drain-prepare-only,redacted)"
    companion object {
        fun begin(drain: TestRunOrdinaryDrainV1): TestRunInstallationManifestV1 {
            requireConnectionFree(); drain.requireManifestPredecessor()
            return TestRunInstallationManifestV1(drain)
        }
    }
}

internal enum class TestInstallationManifestStepV1 { CAPTURE, CHUNK, PREPARE, COMPLETE }
internal enum class TestRunInstallationManifestResultV1 { ALL_CHUNKS_PREPARED_NO_NETWORK }
internal class TestInstallationManifestExceptionV1 : RuntimeException("TEST installation manifest refused.", null, false, false)
internal fun requireManifest(allowed: Boolean) { if (!allowed) throw TestInstallationManifestExceptionV1() }
