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
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProgressV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRefV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.journal.OrdinaryJournalRetentionV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalCodecV1
import me.manga.kira.backend.security.TestTerminalContentV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * One original finite registered attempt. First/current-writer ORDINARY range only; no gate opening,
 * autodrain, checkpoint, provider/lineage quiescence, terminal closure, settlement or reserve release.
 * Cold deployment/retention intake is deliberately absent outside controlled HTTP qualification.
 */
internal class TestRunOrdinarySealV1 private constructor(
    internal val registration: ComplaintTestNamespaceRegistrationV1,
    internal val closedDrain: TestRunOrdinaryDrainV1? = null,
) {
    internal val acquisition = registration.process.ordinarySeal ?: throw TestOrdinarySealExceptionV1()
    private val caller = Thread.currentThread()
    internal val coordinator = registration.process.pools.catalogCoordinator
    internal val routing = registration.process.consumers.journalRouting
    internal val scope = routing.journalConfiguration.scope.id
    internal val writer = routing.journalConfiguration.declaration().writer.generationId
    internal val budget = closedDrain?.budget?.capped(routing.journalConfiguration.declaration().limits.deadlines.epochSealMillis.toLong())
        ?: PersistenceTimeBudget.start(routing.journalConfiguration.declaration().limits.deadlines.epochSealMillis.toLong(), coordinator.ownership.nanoClock)
    internal val codec = TestTerminalCodecV1.fromRetained(routing, acquisition.nanoTime)
    internal val codecAttempt = codec.startAttempt(TestTerminalCodecKindV1.EPOCH_SEAL, budget)
    internal val attemptId = closedDrain?.attemptId ?: UUID.randomUUID()
    internal val captureId = UUID.randomUUID()
    internal val leaseDurationMillis = routing.journalConfiguration.declaration().limits.deadlines.epochSealMillis.toLong()
    internal var leaseToken: Long = closedDrain?.leaseToken ?: 0
        private set
    internal val runContext: TestTerminalRunContextV1
    internal var step = TestOrdinarySealStepV1.CAPTURE
        private set
    internal val path get() = PersistencePhasePath.COMPLAINT_TEST_ORDINARY_SEAL
    private val failure = AtomicReference<Throwable?>()
    private var started = false
    private var finished = false
    private var jdbc: JdbcTemplate? = null
    private var phase: PersistencePhaseContext? = null
    private var phaseEntered = false
    private var cleanupUncertain = false
    private var capture: TestOrdinarySealOperationV1? = null
    private var prepared: TestOrdinarySealOperationV1? = null
    private var frozen: TestOrdinarySealOperationV1? = null
    private var canonicalCandidate: TestTerminalDurableRowV1? = null
    private var frozenCandidate: TestTerminalDurableRowV1? = null
    private var content: TestTerminalContentV1? = null
    private var custody: TestOrdinarySealCustodyV1? = null
    private var proof: TestOrdinarySealProofV1? = null
    private var installationObservation: TestTerminalProgressV1? = null
    private var completedStrictReference: TestTerminalSealRefV1? = null
    private val rows = ArrayList<TestTerminalDurableRowV1>()

    init {
        requireConnectionFree()
        registration.requireUsable()
        // This explicit profile has only the independently denied native-drain successor. An
        // empty SQL relation does not make the legacy local seal a substitute for that cut.
        requireOrdinarySeal(!routing.journalConfiguration.adminDelete || routing.journalConfiguration.registeredAdminDelete && closedDrain != null)
        acquisition.requireRetained(routing, registration.process.publicationLanes)
        val args = registration.sealingRunArguments()
        runContext = TestTerminalRunContextV1(scope.toString(), args[3] as Long, HexFormat.of().formatHex(args[4] as ByteArray),
            HexFormat.of().formatHex(args[1] as ByteArray), TestTerminalProfileV1.encodingSha256)
        closedDrain?.let {
            requireDrain(it.registration === registration && it.runContext == runContext && leaseToken > 0)
            it.retainClosedSeal(this)
        }
    }

    fun seal(): TestRunOrdinarySealResultV1 {
        requireOrdinarySeal(caller === Thread.currentThread() && !started)
        started = true
        var complete = false
        try {
            requireRunning()
            capture = coordinator.testOrdinarySeal.execute(this)
            bindCanonical()
            if (checkNotNull(capture).row == null) {
                step = TestOrdinarySealStepV1.PREPARE
                prepared = coordinator.testOrdinarySeal.execute(this)
            } else prepared = capture
            requireReleased(prepared)
            val custody = TestOrdinarySealCustodyV1.reserve(this).also { this.custody = it }
            custody.acquire()
            if (preparedRow().state === TestTerminalDurableStateV1.CANONICAL) {
                val envelope = custody.seal()
                try {
                    val canonical = preparedRow()
                    val retain = maxOf(canonical.binding.retentionFloor, acquisition.newRetention(codecAttempt, canonical.binding.createdAt))
                    val at = maxOf(canonical.binding.createdAt, OrdinaryJournalRetentionV1.ceilingSecond(acquisition.sampleUtc()))
                    val wire = envelope.wireBytes()
                    frozenCandidate = try { ownRow(TestTerminalDurableRowV1.frozen(canonical, wire, retain, at)) } finally { wire.fill(0) }
                } finally { envelope.close() }
                step = TestOrdinarySealStepV1.FREEZE
                frozen = coordinator.testOrdinarySeal.execute(this)
            } else frozen = prepared
            requireReleased(frozen)
            proof = custody.publish()
            custody.close() // Actual native close is a prerequisite to the final database proof write.
            checkNotNull(proof).requireOriginal(this)
            step = TestOrdinarySealStepV1.VERIFY
            val verified = coordinator.testOrdinarySeal.execute(this)
            verified.requireReleased()
            requireRunning()
            requireConnectionFree()
            if (closedDrain == null) installationObservation = verified.releasedInstallationObservation()
            else completedStrictReference = TestClosedOrdinarySealRowsV1.reference(frozenRow(), checkNotNull(proof))
            complete = true
        } catch (problem: Throwable) {
            observeFailure(problem)
        } finally {
            runCatching { custody?.close() }.exceptionOrNull()?.let(::observeFailure)
            content?.close()
            rows.forEach { it.close() }
            finished = true
        }
        throwIfSignalled()
        requireOrdinarySeal(complete)
        return if (closedDrain == null) TestRunOrdinarySealResultV1.CAPTURED_LOCAL_ORDINARY_SET_SEAL_VERIFIED
        else TestRunOrdinarySealResultV1.POST_DENIAL_ORDINARY_SET_SEAL_VERIFIED
    }

    /** Released historical local observations only. Not a reusable barrier, terminal intent, denial or purge authority. */
    fun localInstallationObservation(): TestTerminalProgressV1 {
        requireOrdinarySeal(closedDrain == null && caller === Thread.currentThread() && started && finished && !cleanupUncertain && phase == null && !phaseEntered)
        throwIfSignalled()
        requireConnectionFree()
        return installationObservation ?: throw TestOrdinarySealExceptionV1()
    }

    /** Retained producer link only. A new manifest original must still reacquire every current SQL comparison. */
    internal fun completedStrictReference(drain: TestRunOrdinaryDrainV1): TestTerminalSealRefV1 {
        requireOrdinarySeal(caller === Thread.currentThread() && closedDrain === drain && started && finished &&
            !cleanupUncertain && phase == null && !phaseEntered)
        throwIfSignalled()
        return completedStrictReference ?: throw TestOrdinarySealExceptionV1()
    }

    private fun bindCanonical() {
        val captured = checkNotNull(capture)
        requireReleased(captured)
        val retained = captured.row
        val fence = retained?.binding?.preparingFencingToken ?: leaseToken
        val cutoff = if (closedDrain == null) captured.cut.cutoff else checkNotNull(captured.closedCut).control.cutoff
        val capturedAt = if (closedDrain == null) checkNotNull(captured.cut.capturedAt) else checkNotNull(checkNotNull(captured.closedCut).control.capturedAt)
        val token = if (closedDrain == null) checkNotNull(captured.cut.token) else checkNotNull(checkNotNull(captured.closedCut).control.captureId)
        val value = TestTerminalEpochSealV1(1, "EPOCH_SEAL", "A".repeat(43), writer, "TEST", scope.toString(), 1, cutoff,
            captured.manifest.count, captured.manifest.sha256, "", fence)
        val expected = codec.canonicalizeEpochSeal(value, codecAttempt, retained?.binding?.routingKeyId)
        if (retained == null) {
            content = expected
            val created = OrdinaryJournalRetentionV1.ceilingSecond(capturedAt)
            val binding = TestTerminalDurableBindingV1(token.toString(), runContext, routing.journalConfiguration.sha256,
                TestTerminalDurableKindV1.EPOCH_SEAL, 0, expected.route.journalId, expected.route.objectKey, expected.route.routingKeyId,
                writer, 1, cutoff, fence, acquisition.newRetention(codecAttempt, created), created)
            val bytes = expected.canonicalBytes()
            canonicalCandidate = try { ownRow(TestTerminalDurableRowV1.canonical(binding, bytes)) } finally { bytes.fill(0) }
        } else {
            try {
                val actual = retained.canonicalBytes()
                val computed = expected.canonicalBytes()
                try {
                    requireOrdinarySeal(retained.binding.objectId == expected.route.journalId && retained.binding.objectKey == expected.route.objectKey &&
                        retained.canonicalSha256 == expected.canonicalSha256 && MessageDigest.isEqual(actual, computed))
                    content = codec.restoreCanonical(TestTerminalCodecKindV1.EPOCH_SEAL, actual, retained.binding.routingKeyId,
                        retained.binding.objectKey, retained.canonicalSha256, codecAttempt)
                } finally { actual.fill(0); computed.fill(0) }
            } finally { expected.close() }
        }
    }

    internal fun requirePersistence(ownership: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requireRunning()
        registration.requireSealingOwner(ownership)
        requireOrdinarySeal(selected.dataSource === coordinator.dataSource)
        if (jdbc == null) jdbc = selected
        requireOrdinarySeal(jdbc === selected)
    }
    internal fun requirePhaseEntry(ownership: PersistencePhaseOwnership, selected: PersistencePhasePath) {
        requireConnectionFree(); requireRunning(); registration.requireSealingOwner(ownership)
        requireOrdinarySeal(selected === path && !phaseEntered && phase == null)
        phaseEntered = true
    }
    internal fun retainPhase(selected: PersistencePhaseContext) { requireRunning(); requireOrdinarySeal(phaseEntered && phase == null); phase = selected }
    internal fun observePhaseCleanup(selected: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testOrdinarySealCleanupProven(this) || selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) cleanupUncertain = true
            else { phase = null; phaseEntered = false }
        } catch (problem: Throwable) { cleanupUncertain = true; observeFailure(problem) }
    }
    internal fun authenticate(ownership: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requirePersistence(ownership, selected)
        val openings = registration.process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.CATALOG_COORDINATOR }.openings().map { it.publicDriverProperties() }
        val user = openings.map { it["user"] }.distinct().single()
        val database = openings.map { it["PGDBNAME"] }.distinct().single()
        requireOrdinarySeal(selected.query("SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated",
            ResultSetExtractor { values -> values.next() && values.getBoolean("authenticated") && !values.wasNull() && !values.next() }, user, user, database) == true)
        requirePersistence(ownership, selected)
    }
    internal fun requireMaintenanceGate(ownership: PersistencePhaseOwnership, selected: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireRunning(); registration.requireSealingOwner(ownership)
        requireOrdinarySeal(selected === path && phaseEntered && phase != null)
        registration.requireSealingGate(gate)
    }
    internal fun retainLease(operation: TestOrdinarySealOperationV1, token: Long) {
        requireRunning(); requireOrdinarySeal(operation.original === this && step === TestOrdinarySealStepV1.CAPTURE && leaseToken == 0L)
        leaseToken = token
    }
    internal fun ownRow(row: TestTerminalDurableRowV1): TestTerminalDurableRowV1 { rows.add(row); return row }
    internal fun capturedCut() = checkNotNull(capture).cut
    internal fun capturedClosedCut() = checkNotNull(checkNotNull(capture).closedCut)
    internal fun requireUnverifiedSeal() {
        if (closedDrain == null) capturedCut().requireUnverified() else capturedClosedCut().requireUnverified()
    }
    internal fun requireListedSealVersion(version: String?) {
        if (closedDrain == null) capturedCut().requireListedVersion(version) else capturedClosedCut().requireListedVersion(version)
    }
    internal fun capturedManifest() = checkNotNull(capture).manifest
    internal fun preparedRow(): TestTerminalDurableRowV1 = checkNotNull(checkNotNull(prepared).row)
    internal fun frozenRow(): TestTerminalDurableRowV1 = checkNotNull(checkNotNull(frozen).row)
    internal fun canonicalCandidate(operation: TestOrdinarySealOperationV1): TestTerminalDurableRowV1 {
        requireOrdinarySeal(operation.original === this && step === TestOrdinarySealStepV1.PREPARE); return checkNotNull(canonicalCandidate)
    }
    internal fun frozenCandidate(operation: TestOrdinarySealOperationV1): TestTerminalDurableRowV1 {
        requireOrdinarySeal(operation.original === this && step === TestOrdinarySealStepV1.FREEZE); return checkNotNull(frozenCandidate)
    }
    internal fun providerProof(operation: TestOrdinarySealOperationV1): TestOrdinarySealProofV1 {
        requireOrdinarySeal(operation.original === this && step === TestOrdinarySealStepV1.VERIFY)
        return checkNotNull(proof).also { it.requireOriginal(this) }
    }
    internal fun requireProvider(selected: TestOrdinarySealCustodyV1, publication: Boolean) {
        requireConnectionFree(); requireRunning()
        requireOrdinarySeal(custody === selected && phase == null && !phaseEntered)
        requireReleased(prepared)
        if (publication) { requireReleased(frozen); requireOrdinarySeal(frozenRow().state === TestTerminalDurableStateV1.WIRE_FROZEN) }
    }
    internal fun requireReservation() { requireConnectionFree(); requireRunning(); requireReleased(prepared); requireOrdinarySeal(custody == null) }
    internal fun content(): TestTerminalContentV1 = checkNotNull(content)
    private fun requireReleased(operation: TestOrdinarySealOperationV1?) {
        requireRunning(); requireOrdinarySeal(phase == null && !phaseEntered)
        checkNotNull(operation).requireReleased()
    }
    internal fun requireSameCanonical(a: TestTerminalDurableRowV1, b: TestTerminalDurableRowV1) {
        requireOrdinarySeal(a.binding == b.binding && a.canonicalSha256 == b.canonicalSha256)
        val left = a.canonicalBytes(); val right = b.canonicalBytes()
        try { requireOrdinarySeal(MessageDigest.isEqual(left, right)) } finally { left.fill(0); right.fill(0) }
    }
    internal fun requireSameFrozen(a: TestTerminalDurableRowV1, b: TestTerminalDurableRowV1) {
        requireSameCanonical(a, b)
        requireOrdinarySeal(a.state === TestTerminalDurableStateV1.WIRE_FROZEN && b.state === a.state && a.wireSha256 == b.wireSha256 &&
            a.retainUntil == b.retainUntil && a.frozenAt == b.frozenAt && a.metadataSha256 == b.metadataSha256 && a.checksumSha256 == b.checksumSha256)
        val left = checkNotNull(a.wireBytes()); val right = checkNotNull(b.wireBytes())
        try { requireOrdinarySeal(MessageDigest.isEqual(left, right)) } finally { left.fill(0); right.fill(0) }
    }
    private fun requireRunning() {
        throwIfSignalled()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("TEST ordinary seal interrupted.")
        requireOrdinarySeal(caller === Thread.currentThread() && !finished && !cleanupUncertain)
        budget.remainingMillis(1); codecAttempt.remainingMillis(1)
        registration.requireSealingOwner(coordinator.ownership)
        acquisition.requireRetained(routing, registration.process.publicationLanes)
        closedDrain?.requireClosedSeal(this)
    }
    internal fun observeFailure(problem: Throwable) {
        closedDrain?.observeFailure(problem)
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("TEST ordinary seal cancelled.")
            problem is InterruptedException || problem is InterruptedIOException || (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) ->
                InterruptedException("TEST ordinary seal interrupted.")
            else -> TestOrdinarySealExceptionV1()
        }
        while (true) {
            val previous = failure.get()
            if (previous is Error || (previous is CancellationException && retained !is Error) ||
                (previous is InterruptedException && retained !is Error && retained !is CancellationException) || (previous != null && retained is TestOrdinarySealExceptionV1)) return
            if (failure.compareAndSet(previous, retained)) return
        }
    }
    internal fun throwIfSignalled() { failure.get()?.let { if (it is InterruptedException) Thread.currentThread().interrupt(); throw it } }
    override fun toString(): String = "TestRunOrdinarySealV1(first-registered-local-set-only,redacted)"
    companion object {
        fun begin(registration: ComplaintTestNamespaceRegistrationV1): TestRunOrdinarySealV1 = TestRunOrdinarySealV1(registration)
        internal fun forClosedDrain(drain: TestRunOrdinaryDrainV1): TestRunOrdinarySealV1 = TestRunOrdinarySealV1(drain.registration, drain)
    }
}

internal enum class TestOrdinarySealStepV1 { CAPTURE, PREPARE, FREEZE, VERIFY }
internal enum class TestRunOrdinarySealResultV1 { CAPTURED_LOCAL_ORDINARY_SET_SEAL_VERIFIED, POST_DENIAL_ORDINARY_SET_SEAL_VERIFIED }
internal class TestOrdinarySealExceptionV1 : RuntimeException("TEST ordinary seal refused.", null, false, false)
internal fun requireOrdinarySeal(allowed: Boolean) { if (!allowed) throw TestOrdinarySealExceptionV1() }
