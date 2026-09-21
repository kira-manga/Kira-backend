package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.common.Sha256
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
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDenialStatementV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialStatementV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEvidenceDigestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInventoryWitnessV1
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalCanonicalV4
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalV1
import me.manga.kira.backend.complaint.infrastructure.journal.OrdinaryJournalRetentionV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOrdinaryInventoryReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOrdinaryInventoryReaderV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestTerminalInventoryReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestTerminalInventoryReaderV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintTestRunErasurePhaseExecutorV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.TestPostTerminalInventoryFoldV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.io.InterruptedIOException
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Untrusted bytes and read credentials only. No supplied successful-E token, row or work callback. */
internal class TestRunErasureRequestV1(
    val ordinaryApproval: ByteArray,
    val ordinaryRawEvidence: List<ByteArray>,
    val terminalApproval: ByteArray,
    val terminalRawEvidence: List<ByteArray>,
    val ordinaryReadCredentials: AwsSessionCredentials,
    val primaryReadCredentials: AwsSessionCredentials,
    val replicaReadCredentials: AwsSessionCredentials,
) {
    override fun toString(): String = "TestRunErasureRequestV1(untrusted-read-inputs,redacted)"
}

/**
 * One successful-E child OR one separately claimed fresh same-lineage process. Construction does
 * no I/O, admission, random acquisition or budget start: E spends its claim and retains this exact
 * identity before exposing it. No predecessor lease/deadline/native handle is inherited.
 *
 * A fresh origin can resume missing committed rows, never reconstruct a missing run/catalog. Every
 * original performs current dual native history, fresh denials and both actual inventory pairs.
 * Fixed bounded transactions use the retained routine-deletion root; UNKNOWN stays failed forever.
 */
internal class TestRunErasureV1 private constructor(
    private val predecessor: CatalogTestRunTerminalV1?,
    internal val process: VersionBoundTestNamespaceProcessV1,
    private val ownership: PersistencePhaseOwnership,
    private val jdbc: JdbcTemplate,
    internal val installationLimit: Long,
) : AutoCloseable {
    internal val routing get() = process.consumers.journalRouting
    internal val acquisition get() = process.ordinarySeal ?: throw TestRunErasureExceptionV1()
    internal val scope get() = routing.journalConfiguration.scope.id
    internal val writer get() = routing.journalConfiguration.declaration().writer.generationId
    internal val runContext get() = checkNotNull(snapshot).run.context
    internal val cutoff get() = checkNotNull(snapshot).run.ordinaryEpoch
    internal val epoch get() = checkNotNull(snapshot).run.terminalEpoch
    internal lateinit var budget: PersistenceTimeBudget
        private set
    internal lateinit var expectedDeclaration: CatalogTestRunTerminalCanonicalV4
        private set
    internal lateinit var attemptId: UUID
        private set
    internal var step = TestRunErasureStepV1.CAPTURE
        private set
    internal val path get() = when (step) {
        TestRunErasureStepV1.CAPTURE, TestRunErasureStepV1.VERIFY -> PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_READ
        TestRunErasureStepV1.BATCH -> PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_BATCH
        TestRunErasureStepV1.FINAL -> PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_FINAL
    }
    private val caller = Thread.currentThread()
    private val failure = AtomicReference<Throwable?>()
    private var started = false
    private var closed = false
    private var cleanupProven = false
    private var cleanupUncertain = false
    private var phaseEntered = false
    private var phase: PersistencePhaseContext? = null
    private var stage = Stage.NEW
    private var snapshot: TestRunErasureRowsV1.Snapshot? = null
    private var evidence: TestRunErasureEvidenceV1? = null
    private var evidenceComplete = false
    private var ordinary: AdmittedErasureOrdinaryDenialV1? = null
    private var terminal: AdmittedErasureTerminalDenialV1? = null
    private var ordinaryReader: TestOrdinaryInventoryReaderV1? = null
    private var terminalReader: TestTerminalInventoryReaderV1? = null
    private var ordinaryStartClaimed = false
    private var terminalStartClaimed = false
    private var ordinaryRetired = false
    private var terminalRetired = false
    private var ordinaryPass = 0
    private var terminalPass = 0
    private var ordinaryStartedAt: Instant? = null
    private var terminalStartedAt: Instant? = null
    private val ordinaryWitnesses = arrayOfNulls<TestTerminalInventoryWitnessV1>(2)
    private val terminalWitnesses = arrayOfNulls<TestTerminalInventoryWitnessV1>(2)
    private var lastWallTime: Instant? = null
    private var fixturesConfigured = false
    private var readbackHttp: (() -> SdkHttpClient)? = null
    private var ordinaryS3: (() -> SdkHttpClient)? = null
    private var ordinaryKms: (() -> SdkHttpClient)? = null
    private var clock: Clock = Clock.systemUTC()

    /** Only raw HTTP/time fixtures on the already genuine unstarted original, never an authority seam. */
    internal fun withHttpFixtures(readback: () -> SdkHttpClient, ordinaryS3: () -> SdkHttpClient,
        ordinaryKms: () -> SdkHttpClient, clock: Clock): TestRunErasureV1 {
        requireConnectionFree(); requireErasure(caller === Thread.currentThread() && !started && !closed && !fixturesConfigured)
        fixturesConfigured = true; readbackHttp = readback; this.ordinaryS3 = ordinaryS3; this.ordinaryKms = ordinaryKms; this.clock = clock
        return this
    }

    fun erase(request: TestRunErasureRequestV1): TestRunErasureResultV1 {
        requireConnectionFree(); requireErasure(caller === Thread.currentThread() && !started && !closed)
        started = true
        var result: TestRunErasureResultV1? = null
        try {
            requireOrigin(); ownership.requireBoundComplaintDeletion(process.pools)
            requireErasure(jdbc.dataSource === process.pools.deletion)
            val scan = routing.journalConfiguration.declaration().limits.deadlines.scanMillis.toLong()
            budget = PersistenceTimeBudget.start(Math.addExact(Math.multiplyExact(2L, scan),
                Math.addExact(minOf(process.catalogReadback.totalAttemptMillis, 10_000L), 120_000L)), ownership.nanoClock)
            attemptId = UUID.randomUUID()
            expectedDeclaration = CatalogTestRunTerminalCanonicalV4.fromRetained(process, installationLimit)
            checkNotNull(process.ordinaryDenial).requireJournal(routing.journalConfiguration)
            checkNotNull(process.terminalDenial).requireJournal(routing.journalConfiguration)
            acquisition.requireRetained(routing, process.publicationLanes)
            stage = Stage.SQL
            snapshot = execute(TestRunErasureStepV1.CAPTURE).snapshot
            stage = Stage.CATALOG
            val native = TestRunErasureEvidenceV1(this)
            evidence = native // Retain BEFORE opening any native resource.
            native.observe(checkNotNull(snapshot), request.primaryReadCredentials, request.replicaReadCredentials, readbackHttp)
            predecessor?.requireErasureLineage(this, runContext, checkNotNull(snapshot).terminal.generation,
                checkNotNull(snapshot).terminal.head.envelopeSha256)
            stage = Stage.DENIALS
            ordinary = checkNotNull(process.ordinaryDenial).readmitErasure(this, request.ordinaryApproval, request.ordinaryRawEvidence)
            terminal = checkNotNull(process.terminalDenial).readmitErasure(this, request.terminalApproval, request.terminalRawEvidence)
            inventories(request.ordinaryReadCredentials)
            native.finish(); evidenceComplete = true
            stage = Stage.SQL
            if (checkNotNull(snapshot).run.purged) {
                snapshot = execute(TestRunErasureStepV1.VERIFY).snapshot
                result = TestRunErasureResultV1.PURGED_HISTORY_VERIFIED_ONLY
            } else {
                // A fixed per-invocation limit, never a caller strategy or durable progress cursor.
                // Absent committed rows are absent work for a separately claimed fresh origin.
                repeat(MAX_BATCHES) {
                    if (result == null) {
                        val batch = execute(TestRunErasureStepV1.BATCH)
                        snapshot = batch.snapshot
                        if (!batch.remaining) {
                            snapshot = execute(TestRunErasureStepV1.FINAL).snapshot
                            requireErasure(checkNotNull(snapshot).run.purged)
                            result = TestRunErasureResultV1.PURGED
                        }
                    }
                }
                if (result == null) result = TestRunErasureResultV1.PARTIAL_ERASURE_COMMITTED
            }
            requireRunning(); requireRetiredInventories()
        } catch (problem: Throwable) { observeFailure(problem) }
        finally { runCatching(::close).exceptionOrNull()?.let(::observeFailure) }
        throwIfSignalled()
        requireErasure(cleanupProven && !cleanupUncertain && phase == null && !phaseEntered)
        return checkNotNull(result)
    }

    private fun execute(next: TestRunErasureStepV1): TestRunErasureOperationV1 {
        requireConnectionFree(); requireRunning(); requireNoPhase(); requireErasure(stage === Stage.SQL)
        step = next
        return ComplaintTestRunErasurePhaseExecutorV1(ownership, jdbc).execute(this).also { it.requireReleased(); requireRunning() }
    }

    private fun inventories(credentials: AwsSessionCredentials) {
        stage = Stage.ORDINARY
        waitUntil(checkNotNull(ordinary).firstStartAfter())
        ordinaryReader = TestOrdinaryInventoryReaderV1.beginErasure(this, credentials, ordinaryS3, ordinaryKms, clock, acquisition.nanoTime)
        checkNotNull(ordinaryReader).scanPass(1)
        val a = checkNotNull(ordinary).statement
        waitUntil(nextPass(checkNotNull(ordinaryWitnesses[0]), a.acceptedRequestBoundSeconds, a.utcUncertaintySeconds))
        checkNotNull(ordinaryReader).scanPass(2)
        checkNotNull(ordinaryReader).close(); checkNotNull(ordinaryReader).requireRetiredErasurePair(this); ordinaryRetired = true
        stage = Stage.TERMINAL
        waitUntil(checkNotNull(terminal).firstStartAfter())
        terminalReader = TestTerminalInventoryReaderV1.beginErasure(this)
        checkNotNull(terminalReader).scanPass(1)
        val b = checkNotNull(terminal).statement
        waitUntil(nextPass(checkNotNull(terminalWitnesses[0]), b.acceptedRequestBoundSeconds, b.utcUncertaintySeconds))
        checkNotNull(terminalReader).scanPass(2)
        checkNotNull(terminalReader).close(); checkNotNull(terminalReader).requireRetiredErasurePair(this); terminalRetired = true
    }
    internal fun requireEvidence(value: TestRunErasureEvidenceV1) { requireRunning(); requireErasure(evidence === value) }
    internal fun authenticatedEvidence(): TestRunErasureEvidenceV1 {
        requireRunning(); requireErasure(evidenceComplete && ordinaryRetired && terminalRetired)
        return checkNotNull(evidence)
    }
    internal fun capturedSnapshot(): TestRunErasureRowsV1.Snapshot? { requireRunning(); return snapshot }
    internal fun terminalTargets(): List<TestTerminalQuiescenceTargetV1> { requireRunning(); return checkNotNull(evidence).terminalTargets() }

    internal fun requireOrdinaryInventoryStart() {
        requireConnectionFree(); requireRunning(); requireNoPhase(); checkNotNull(ordinary).requireOriginal(this)
        requireErasure(stage === Stage.ORDINARY && ordinaryReader == null && !ordinaryStartClaimed); ordinaryStartClaimed = true
    }
    internal fun requireTerminalInventoryStart() {
        requireConnectionFree(); requireRunning(); requireNoPhase(); checkNotNull(terminal).requireOriginal(this)
        requireErasure(stage === Stage.TERMINAL && ordinaryRetired && terminalReader == null && !terminalStartClaimed); terminalStartClaimed = true
    }
    internal fun requireOrdinaryInventoryReader(value: TestOrdinaryInventoryReaderV1) {
        requireConnectionFree(); requireRunning(); requireNoPhase(); checkNotNull(ordinary).requireOriginal(this)
        requireErasure(stage === Stage.ORDINARY && ordinaryReader === value && !ordinaryRetired); requireEvidenceTime(sampleWallTime())
    }
    internal fun requireTerminalInventoryReader(value: TestTerminalInventoryReaderV1) {
        requireConnectionFree(); requireRunning(); requireNoPhase(); checkNotNull(terminal).requireOriginal(this)
        requireErasure(stage === Stage.TERMINAL && terminalReader === value && !terminalRetired); requireEvidenceTime(sampleWallTime())
    }
    internal fun requireOrdinaryInventoryEvent(event: TestOwnerDeleteJournalEventV1) {
        requireOrdinaryInventoryReader(checkNotNull(ordinaryReader)); requireErasure(event.belongsTo(routing))
        TestOrdinaryDrainPersistenceV1.FamilyFacts(routing, cutoff).requireEvent(event)
    }
    internal fun beginOrdinaryInventoryPass(reader: TestOrdinaryInventoryReaderV1, pass: Int, at: Instant) {
        requireOrdinaryInventoryReader(reader); requireErasure(pass in 1..2 && pass == ordinaryPass + 1)
        val a = checkNotNull(ordinary)
        val after = if (pass == 1) a.firstStartAfter() else nextPass(checkNotNull(ordinaryWitnesses[0]), a.statement.acceptedRequestBoundSeconds, a.statement.utcUncertaintySeconds)
        requireErasure(at >= after && at <= sampleWallTime() && at.epochSecond >= checkNotNull(evidence).record.progress.completedCuts()[0].denial.secondInventory.completedAtEpochSecond)
        ordinaryPass = pass; ordinaryStartedAt = at
    }
    internal fun beginTerminalInventoryPass(reader: TestTerminalInventoryReaderV1, pass: Int, at: Instant) {
        requireTerminalInventoryReader(reader); requireErasure(pass in 1..2 && pass == terminalPass + 1)
        val b = checkNotNull(terminal)
        val after = if (pass == 1) b.firstStartAfter() else nextPass(checkNotNull(terminalWitnesses[0]), b.statement.acceptedRequestBoundSeconds, b.statement.utcUncertaintySeconds)
        requireErasure(at >= after && at <= sampleWallTime() && at.epochSecond >= checkNotNull(evidence).record.progress.completedCuts()[1].denial.secondInventory.completedAtEpochSecond)
        terminalPass = pass; terminalStartedAt = at
    }
    internal fun stageOrdinaryInventoryVersion(reader: TestOrdinaryInventoryReaderV1, pass: Int, value: TestOrdinaryInventoryReadbackV1) {
        requireOrdinaryInventoryReader(reader); requireErasure(pass == ordinaryPass); checkNotNull(evidence).ordinaryVersion(pass, value)
    }
    internal fun stageTerminalInventoryVersion(reader: TestTerminalInventoryReaderV1, pass: Int, value: TestTerminalInventoryReadbackV1) {
        requireTerminalInventoryReader(reader); requireErasure(pass == terminalPass); checkNotNull(evidence).terminalVersion(pass, value)
    }
    internal fun completeOrdinaryInventoryPass(reader: TestOrdinaryInventoryReaderV1, pass: Int, at: Instant, count: Long, bytes: Long) {
        requireOrdinaryInventoryReader(reader); reader.requireCompletedErasurePass(this, pass)
        requireErasure(pass == ordinaryPass && ordinaryWitnesses[pass - 1] == null && at >= checkNotNull(ordinaryStartedAt) && at <= sampleWallTime())
        checkNotNull(evidence).ordinaryComplete(pass, reader.erasureComparisonEntries(this, pass), count, bytes)
        ordinaryWitnesses[pass - 1] = TestTerminalInventoryWitnessV1(checkNotNull(ordinaryStartedAt).epochSecond,
            OrdinaryJournalRetentionV1.ceilingSecond(at).epochSecond, count, bytes,
            checkNotNull(evidence).record.progress.completedCuts()[0].denial.firstInventory.sha256)
    }
    internal fun completeTerminalInventoryPass(reader: TestTerminalInventoryReaderV1, pass: Int, at: Instant, value: TestPostTerminalInventoryFoldV1.Summary) {
        requireTerminalInventoryReader(reader); reader.requireCompletedErasurePass(this, pass)
        requireErasure(pass == terminalPass && terminalWitnesses[pass - 1] == null && at >= checkNotNull(terminalStartedAt) && at <= sampleWallTime())
        checkNotNull(evidence).terminalComplete(pass, reader.erasureComparisonEntries(this, pass), value)
        terminalWitnesses[pass - 1] = TestTerminalInventoryWitnessV1(checkNotNull(terminalStartedAt).epochSecond,
            OrdinaryJournalRetentionV1.ceilingSecond(at).epochSecond, value.versionCount, value.ciphertextByteCount, value.sha256)
    }
    internal fun requireRetiredInventories() {
        requireConnectionFree(); requireRunning(); requireErasure(ordinaryRetired && terminalRetired)
        checkNotNull(ordinaryReader).requireRetiredErasurePair(this); checkNotNull(terminalReader).requireRetiredErasurePair(this)
    }

    internal fun requireOrdinaryAuthority(value: TestOrdinaryDenialAuthorityPolicyV1) {
        requireRunning(); requireErasure(value === process.ordinaryDenial && evidence != null && snapshot != null)
    }
    internal fun requireTerminalAuthority(value: TestTerminalDenialAuthorityPolicyV1) {
        requireRunning(); requireErasure(value === process.terminalDenial && evidence != null && snapshot != null)
    }
    internal fun requireOrdinaryDenialContext(value: TestOrdinaryDenialStatementV1, digest: TestTerminalEvidenceDigestV1) {
        requireConnectionFree(); requireOrdinaryAuthority(checkNotNull(process.ordinaryDenial))
        val journal = routing.journalConfiguration; val declaration = journal.declaration(); val role = declaration.authorities.ordinary
        requireErasure(stage === Stage.DENIALS && ordinary == null && value.dataScopeId == runContext.dataScopeId &&
            value.activationCatalogGeneration == runContext.activationCatalogGeneration && value.activationCatalogSha256 == runContext.activationCatalogSha256 &&
            value.configurationSha256 == runContext.configurationSha256 && value.terminalEncodingSha256 == runContext.terminalEncodingSha256 &&
            value.initialWriterRegistrySha256 == process.catalogActivation.initialWriterRegistrySha256 && value.writerGeneration == writer &&
            value.databaseIdentity == process.databaseIdentity.toString() && value.restoreIdentity == process.restoreIdentity.toString() &&
            value.epochStartInclusive == 1L && value.epochEndInclusive == cutoff && value.ordinaryPrefix == journal.ordinaryPrefix &&
            value.bucket == declaration.journalLocation.bucket && value.accountId == declaration.journalLocation.accountId && value.region == declaration.journalLocation.region &&
            value.roleId == role.roleId && value.policy.policyId == role.policy.policyId && value.policy.version == role.policy.version && value.policy.sha256 == role.policy.sha256 &&
            value.evidenceRetainUntilEpochSecond > sampleWallTime().epochSecond)
        val old = checkNotNull(evidence).record.progress.completedCuts()[0].denial
        requireErasure(old.roleId == value.roleId && old.policy == value.policy && old.policyEvidence == digest && old.boundEvidence == value.boundEvidence &&
            old.denialEffectiveAtEpochSecond == value.denialEffectiveAtEpochSecond && old.lastSessionExpiryEpochSecond == value.lastSessionExpiryEpochSecond &&
            old.acceptedRequestBoundSeconds == value.acceptedRequestBoundSeconds)
    }
    internal fun requireTerminalDenialContext(value: TestTerminalDenialStatementV1, digest: TestTerminalEvidenceDigestV1) {
        requireConnectionFree(); requireTerminalAuthority(checkNotNull(process.terminalDenial))
        val journal = routing.journalConfiguration; val declaration = journal.declaration(); val role = declaration.authorities.sealTerminal
        val record = checkNotNull(evidence).record
        val seals = TestTerminalJsonV1(journal).encodeSealSet(record.sealSet)
        val sealHash = try { Sha256.hex(seals) } finally { seals.fill(0) }
        requireErasure(stage === Stage.DENIALS && ordinary != null && terminal == null && value.dataScopeId == runContext.dataScopeId &&
            value.activationCatalogGeneration == runContext.activationCatalogGeneration && value.activationCatalogSha256 == runContext.activationCatalogSha256 &&
            value.configurationSha256 == runContext.configurationSha256 && value.terminalEncodingSha256 == runContext.terminalEncodingSha256 &&
            value.initialWriterRegistrySha256 == process.catalogActivation.initialWriterRegistrySha256 && value.writerGeneration == writer &&
            value.databaseIdentity == process.databaseIdentity.toString() && value.restoreIdentity == process.restoreIdentity.toString() &&
            value.epochStartInclusive == 1L && value.epochEndInclusive == epoch && value.sealTerminalPrefix == journal.sealTerminalPrefix &&
            value.bucket == declaration.journalLocation.bucket && value.accountId == declaration.journalLocation.accountId && value.region == declaration.journalLocation.region &&
            value.sealedCandidate.completedTerminalSeal == record.sealSet.records().last() && value.sealedCandidate.completeSealSetSha256 == sealHash &&
            value.sealedCandidate.purge == record.purge.objectRef && value.roleId == role.roleId && value.policy.policyId == role.policy.policyId &&
            value.policy.version == role.policy.version && value.policy.sha256 == role.policy.sha256 && value.evidenceRetainUntilEpochSecond > sampleWallTime().epochSecond)
        val old = record.progress.completedCuts()[1].denial
        requireErasure(old.roleId == value.roleId && old.policy == value.policy && old.policyEvidence == digest && old.boundEvidence == value.boundEvidence &&
            old.denialEffectiveAtEpochSecond == value.denialEffectiveAtEpochSecond && old.lastSessionExpiryEpochSecond == value.lastSessionExpiryEpochSecond &&
            old.acceptedRequestBoundSeconds == value.acceptedRequestBoundSeconds)
    }

    internal fun requirePersistence(owner: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requireRunning(); ownership.requireBoundComplaintDeletion(process.pools)
        requireErasure(owner === ownership && selected === jdbc && selected.dataSource === process.pools.deletion)
    }
    internal fun requirePhaseEntry(owner: PersistencePhaseOwnership, selected: PersistencePhasePath) {
        requireConnectionFree(); requirePersistence(owner, jdbc); requireNoPhase()
        requireErasure(stage === Stage.SQL && selected === path && (step === TestRunErasureStepV1.CAPTURE) == (snapshot == null))
        if (step !== TestRunErasureStepV1.CAPTURE) { requireRetiredInventories(); authenticatedEvidence() }
        phaseEntered = true
    }
    internal fun retainPhase(value: PersistencePhaseContext) { requireRunning(); requireErasure(phaseEntered && phase == null); phase = value }
    internal fun requireOperation(operation: TestRunErasureOperationV1, selected: JdbcTemplate) {
        requirePersistence(ownership, selected)
        requireErasure(phaseEntered && operation.original === this && operation.path === path && operation.step === step && operation.belongsTo(checkNotNull(phase)))
    }
    internal fun observePhaseCleanup(value: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== value || !value.testRunErasureCleanupProven(this)) {
                cleanupUncertain = true; observeFailure(TestRunErasureExceptionV1())
            } else {
                phase = null; phaseEntered = false
                if (value.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) { cleanupUncertain = true; observeFailure(TestRunErasureExceptionV1()) }
            }
        } catch (problem: Throwable) { cleanupUncertain = true; observeFailure(problem) }
    }
    internal fun authenticate(owner: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requirePersistence(owner, selected)
        val openings = process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.DELETION }.openings().map { it.publicDriverProperties() }
        val user = checkNotNull(openings.map { it["user"] }.distinct().single())
        val database = checkNotNull(openings.map { it["PGDBNAME"] }.distinct().single())
        requireErasure(selected.query("SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated",
            ResultSetExtractor { row -> row.next() && row.getBoolean("authenticated") && !row.wasNull() && !row.next() }, user, user, database) == true)
        requirePersistence(owner, selected)
    }
    internal fun requireMaintenanceGate(owner: PersistencePhaseOwnership, selected: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requirePersistence(owner, jdbc); requireErasure(phaseEntered && phase != null && selected === path)
        val current = snapshot
        if (current == null) requireErasure(step === TestRunErasureStepV1.CAPTURE && gate.matchesErasureCapture(scope))
        else {
            val terminalBytes = current.terminal.unsignedBytes(); val terminalHash = current.terminal.unsignedHash()
            val activationBytes = current.activation.unsignedBytes(); val activationHash = current.activation.unsignedHash()
            try { requireErasure(gate.matchesErasureTuple(current.terminal.token, scope, terminalBytes, terminalHash,
                current.activation.token, activationBytes, activationHash)) }
            finally { terminalBytes.fill(0); terminalHash.fill(0); activationBytes.fill(0); activationHash.fill(0) }
        }
    }
    internal fun requireSqlTime(operation: TestRunErasureOperationV1, selected: JdbcTemplate, at: Instant) {
        requireOperation(operation, selected); requireEvidenceTime(at)
    }
    internal fun requireProviderRunning() { requireConnectionFree(); requireRunning(); requireNoPhase(); requireEvidenceTime(sampleWallTime()) }
    internal fun sampleWallTime(): Instant {
        requireConnectionFree(); requireRunning()
        val at = acquisition.sampleUtc()
        requireErasure(lastWallTime?.let { at >= it } != false); lastWallTime = at
        requireEvidenceTime(at); return at
    }
    private fun requireEvidenceTime(at: Instant) {
        requireErasure(at.epochSecond in 0..253_402_300_799L)
        ordinary?.let { requireErasure(it.statement.evidenceRetainUntilEpochSecond > at.epochSecond) }
        terminal?.let { requireErasure(it.statement.evidenceRetainUntilEpochSecond > at.epochSecond) }
    }
    internal fun requireRunning() {
        throwIfSignalled()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("TEST run erasure interrupted.")
        requireErasure(caller === Thread.currentThread() && started && !closed && !cleanupUncertain)
        budget.remainingMillis(1); requireOrigin(); ownership.requireBoundComplaintDeletion(process.pools)
        acquisition.requireRetained(routing, process.publicationLanes)
    }
    private fun requireOrigin() {
        requireErasure(caller === Thread.currentThread())
        process.requireRegistrationTarget()
        if (predecessor == null) process.requireTestRunErasure(this)
        else { requireErasure(predecessor.process === process); predecessor.requireErasureChild(this) }
    }
    private fun requireNoPhase() = requireErasure(phase == null && !phaseEntered)
    private fun waitUntil(after: Instant) {
        requireErasure(after.epochSecond in 0..253_402_300_799L)
        while (true) {
            requireConnectionFree(); requireRunning()
            if (sampleWallTime() >= after) return
            LockSupport.parkNanos(budget.remainingMillis(25) * 1_000_000L)
        }
    }
    internal fun observeFailure(problem: Throwable) {
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("TEST run erasure cancelled.")
            problem is InterruptedException || problem is InterruptedIOException || problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED ->
                InterruptedException("TEST run erasure interrupted.")
            else -> TestRunErasureExceptionV1()
        }
        while (true) {
            val old = failure.get()
            if (old is Error || old is CancellationException && retained !is Error || old is InterruptedException && retained !is Error && retained !is CancellationException ||
                old != null && retained is TestRunErasureExceptionV1) return
            if (failure.compareAndSet(old, retained)) return
        }
    }
    internal fun throwIfSignalled() { failure.get()?.let { if (it is InterruptedException) Thread.currentThread().interrupt(); throw it } }
    override fun close() {
        requireConnectionFree(); requireErasure(caller === Thread.currentThread())
        if (closed) { throwIfSignalled(); requireErasure(cleanupProven); return }
        closed = true; stage = Stage.CLOSED
        listOf(runCatching { ordinaryReader?.close() }, runCatching { terminalReader?.close() }, runCatching { evidence?.close() },
            runCatching { requireNoPhase(); requireErasure(!cleanupUncertain) }).forEach { it.exceptionOrNull()?.let(::observeFailure) }
        throwIfSignalled(); cleanupProven = true
    }
    override fun toString(): String = "TestRunErasureV1(one-original,same-lineage,routine-deletion,redacted)"
    private enum class Stage { NEW, SQL, CATALOG, DENIALS, ORDINARY, TERMINAL, CLOSED }
    companion object {
        private const val MAX_BATCHES = 128
        internal fun begin(predecessor: CatalogTestRunTerminalV1, owner: PersistencePhaseOwnership, jdbc: JdbcTemplate): TestRunErasureV1 =
            TestRunErasureV1(predecessor, predecessor.process, owner, jdbc, predecessor.expectedDeclaration.activation.run().installationLimit)

        /** The supplied limit is comparison-only; current retained activation/native/run must agree. */
        internal fun beginFresh(process: VersionBoundTestNamespaceProcessV1, owner: PersistencePhaseOwnership, jdbc: JdbcTemplate,
            installationLimit: Long): TestRunErasureV1 {
            requireConnectionFree()
            return TestRunErasureV1(null, process, owner, jdbc, installationLimit).also(process::claimTestRunErasure)
        }
        private fun nextPass(first: TestTerminalInventoryWitnessV1, bound: Long, uncertainty: Long): Instant =
            Instant.ofEpochSecond(Math.addExact(first.completedAtEpochSecond, Math.addExact(bound, Math.multiplyExact(2L, uncertainty))))
    }
}

internal enum class TestRunErasureStepV1 { CAPTURE, VERIFY, BATCH, FINAL }
internal enum class TestRunErasureResultV1 { PURGED, PARTIAL_ERASURE_COMMITTED, PURGED_HISTORY_VERIFIED_ONLY }
internal class TestRunErasureExceptionV1 : RuntimeException("TEST run erasure refused.", null, false, false)
internal fun requireErasure(allowed: Boolean) { if (!allowed) throw TestRunErasureExceptionV1() }
