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
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCompletedCutV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialStatementV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProgressV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealSetV1
import me.manga.kira.backend.complaint.infrastructure.journal.OrdinaryJournalRetentionV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestTerminalInventoryReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestTerminalInventoryReaderV1
import me.manga.kira.backend.security.TestPostTerminalInventoryEntryV1
import me.manga.kira.backend.security.TestPostTerminalInventoryFoldV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.io.InterruptedIOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * First-only child of the actual completed terminal seal. Own current authority and native pair,
 * then a paid SEAL_TERMINAL cut and exact scan recycle. Remains SEALED; never a catalog/erasure owner.
 * Neither supplied credentials, copied rows nor a historical predecessor renew this original.
 */
internal class TestRunTerminalQuiescenceV1 private constructor(internal val terminalSeal: TestRunTerminalEpochSealV1) {
    internal val purge = terminalSeal.purge
    internal val manifest = terminalSeal.manifest
    internal val drain = terminalSeal.drain
    internal val registration = terminalSeal.registration
    internal val coordinator = terminalSeal.coordinator
    internal val routing = terminalSeal.routing
    internal val scope = terminalSeal.scope
    internal val writer = terminalSeal.writer
    internal val runContext = terminalSeal.runContext
    internal val control = terminalSeal.control
    internal val ordinaryCut = terminalSeal.ordinaryCut
    internal val ordinarySeal = terminalSeal.ordinarySeal
    internal val epoch = terminalSeal.epoch
    internal val acquisition = terminalSeal.acquisition
    internal val sealedReference = terminalSeal.authenticatedSeal()
    internal val attemptId = UUID.randomUUID()
    internal val scanId = UUID.randomUUID()
    internal val path = PersistencePhasePath.COMPLAINT_TEST_TERMINAL_QUIESCENCE
    internal val budget = PersistenceTimeBudget.start(routing.journalConfiguration.declaration().limits.deadlines.scanMillis.toLong(), coordinator.ownership.nanoClock)
    internal val maximumVersions = routing.journalConfiguration.declaration().limits.capacity.maximumRetainedVersions
    internal val maximumFramedBytes = routing.journalConfiguration.declaration().limits.capacity.maximumScanStagingBytes
    private val authority = registration.process.terminalDenial ?: throw TestTerminalQuiescenceExceptionV1()
    private val caller = Thread.currentThread()
    private val failure = AtomicReference<Throwable?>()
    private var started = false
    private var finished = false
    private var completed = false
    private var cleanupUncertain = false
    private var phaseEntered = false
    private var phase: PersistencePhaseContext? = null
    private var jdbc: JdbcTemplate? = null
    private var native: TestTerminalInventoryReaderV1? = null
    private var admitted: AdmittedTerminalDenialV1? = null
    private var predecessor: TestTerminalProgressV1? = null
    private var paid: TestTerminalProgressV1? = null
    private var readback: TestTerminalInventoryReadbackV1? = null
    private var target: TestTerminalQuiescenceTargetV1? = null
    private val summaries = arrayOfNulls<TestTerminalQuiescenceRowsV1.Summary>(2)
    internal var step = TestTerminalQuiescenceStepV1.CAPTURE
        private set
    internal var leaseToken = 0L
        private set
    internal var inventoryPass = 0
        private set
    internal var inventoryTime: Instant? = null
        private set
    internal var inventorySummary: TestPostTerminalInventoryFoldV1.Summary? = null
        private set

    internal val fullSealSet = TestTerminalSealSetV1.create(runContext.dataScopeId, runContext.activationCatalogGeneration,
        runContext.activationCatalogSha256, listOf(ordinarySeal, sealedReference))
    internal val fullSealSetSha256 = TestTerminalJsonV1(routing.journalConfiguration).encodeSealSet(fullSealSet).let {
        try { Sha256.hex(it) } finally { it.fill(0) }
    }
    internal val targets: List<TestTerminalQuiescenceTargetV1> = buildList {
        repeat(manifest.capturedSource().count) { index ->
            val facts = manifest.authenticatedFacts(index)
            add(TestTerminalQuiescenceTargetV1(TestTerminalCodecKindV1.INSTALLATION_MANIFEST, index, facts.id, epoch, epoch, facts.objectRef))
        }
        val purgeFacts = purge.authenticatedFactsForSeal()
        add(TestTerminalQuiescenceTargetV1(TestTerminalCodecKindV1.TEST_RUN_PURGE, 0, purgeFacts.id, epoch, epoch, purgeFacts.objectRef))
        add(TestTerminalQuiescenceTargetV1(TestTerminalCodecKindV1.EPOCH_SEAL, 0, ordinarySeal.sealId,
            ordinarySeal.epochStartInclusive, ordinarySeal.epochEndInclusive, ordinarySeal.objectRef))
        add(TestTerminalQuiescenceTargetV1(TestTerminalCodecKindV1.EPOCH_SEAL, 1, sealedReference.sealId, epoch, epoch, sealedReference.objectRef))
    }.sortedBy { it.objectRef.objectKey }

    init {
        requireConnectionFree()
        authority.requireJournal(routing.journalConfiguration)
        acquisition.requireRetained(routing, registration.process.publicationLanes)
        requireQuiescence(targets.size.toLong() in 3..maximumVersions && targets.map { it.objectRef.objectKey }.distinct().size == targets.size &&
            targets.all { it.objectRef.objectKey.startsWith(routing.journalConfiguration.sealTerminalPrefix) })
        terminalSeal.retainQuiescence(this)
    }

    fun quiesce(approval: ByteArray, rawEvidence: List<ByteArray>): TestRunTerminalQuiescenceResultV1 {
        requireConnectionFree(); requireQuiescence(caller === Thread.currentThread() && !started)
        started = true
        var success = false
        try {
            predecessor = execute(TestTerminalQuiescenceStepV1.CAPTURE).progress
            step = TestTerminalQuiescenceStepV1.ADMISSION
            admitted = authority.admit(this, approval, rawEvidence)
            step = TestTerminalQuiescenceStepV1.NATIVE
            native = TestTerminalInventoryReaderV1.begin(this)
            waitUntil(checkNotNull(admitted).firstStartAfter())
            checkNotNull(native).scanPass(1)
            val statement = admittedDenial().statement
            waitUntil(Instant.ofEpochSecond(Math.addExact(checkNotNull(summaries[0]).witness.completedAtEpochSecond,
                Math.addExact(statement.acceptedRequestBoundSeconds, Math.multiplyExact(2L, statement.utcUncertaintySeconds)))))
            checkNotNull(native).scanPass(2)
            waitUntil(Instant.ofEpochSecond(checkNotNull(summaries[1]).witness.completedAtEpochSecond))
            checkNotNull(native).close() // Full actual graph cleanup BEFORE durable cut/recycle eligibility.
            paid = execute(TestTerminalQuiescenceStepV1.WITNESS).progress
            do { val recycled = execute(TestTerminalQuiescenceStepV1.RECYCLE); requireRunning() } while (!recycled.recycled.isZero())
            execute(TestTerminalQuiescenceStepV1.COMPLETE)
            requireRunning(); success = true
        } catch (problem: Throwable) { observeFailure(problem) }
        finally {
            runCatching { native?.close() }.exceptionOrNull()?.let(::observeFailure)
            readback = null; target = null; finished = true
        }
        throwIfSignalled()
        requireQuiescence(success && !cleanupUncertain && phase == null && !phaseEntered && paid != null)
        completed = true
        return TestRunTerminalQuiescenceResultV1.TERMINAL_PREFIX_QUIESCENT_AND_SEALED
    }

    /** Historical only. A later signed-catalog child still needs its own one-use/current boundary. */
    internal fun authenticatedProgress(): TestTerminalProgressV1 {
        requireConnectionFree(); throwIfSignalled(); terminalSeal.requireQuiescence(this)
        requireQuiescence(caller === Thread.currentThread() && started && finished && completed && !cleanupUncertain && phase == null && !phaseEntered)
        return checkNotNull(paid)
    }
    internal fun authenticatedCut(): TestTerminalCompletedCutV1 = authenticatedProgress().completedCuts().last()

    private fun execute(next: TestTerminalQuiescenceStepV1): TestTerminalQuiescenceOperationV1 {
        requireConnectionFree(); requireRunning(); requireQuiescence(phase == null && !phaseEntered)
        step = next
        var operation: TestTerminalQuiescenceOperationV1? = null
        try {
            return coordinator.testTerminalQuiescence.execute(this).also { operation = it; it.requireReleased(); requireRunning() }
        } catch (problem: Throwable) {
            operation?.discardRow(); if (phase == null) phaseEntered = false
            throw problem
        }
    }
    internal fun requireAuthority(value: TestTerminalDenialAuthorityPolicyV1) {
        requireRunning(); requireQuiescence(value === authority && registration.process.terminalDenial === value && predecessor != null && leaseToken > terminalSeal.leaseToken)
    }
    internal fun requireDenialContext(value: TestTerminalDenialStatementV1) {
        requireAuthority(authority)
        val declaration = routing.journalConfiguration.declaration()
        val role = declaration.authorities.sealTerminal
        requireQuiescence(value.dataScopeId == runContext.dataScopeId && value.activationCatalogGeneration == runContext.activationCatalogGeneration &&
            value.activationCatalogSha256 == runContext.activationCatalogSha256 && value.configurationSha256 == runContext.configurationSha256 &&
            value.terminalEncodingSha256 == runContext.terminalEncodingSha256 &&
            value.initialWriterRegistrySha256 == registration.process.catalogActivation.initialWriterRegistrySha256 && value.writerGeneration == writer &&
            value.databaseIdentity == declaration.writer.databaseIdentity && value.restoreIdentity == declaration.writer.restoreIdentity &&
            value.epochStartInclusive == 1L && value.epochEndInclusive == epoch && value.sealTerminalPrefix == routing.journalConfiguration.sealTerminalPrefix &&
            value.bucket == declaration.journalLocation.bucket && value.accountId == declaration.journalLocation.accountId && value.region == declaration.journalLocation.region &&
            value.sealedCandidate.completedTerminalSeal == sealedReference && value.sealedCandidate.completeSealSetSha256 == fullSealSetSha256 &&
            value.sealedCandidate.purge == purge.authenticatedPurge() &&
            value.roleId == role.roleId && value.policy.policyId == role.policy.policyId && value.policy.version == role.policy.version && value.policy.sha256 == role.policy.sha256 &&
            value.evidenceRetainUntilEpochSecond > acquisition.sampleUtc().epochSecond)
    }
    internal fun admittedDenial(): AdmittedTerminalDenialV1 = checkNotNull(admitted).also { it.requireOriginal(this) }
    internal fun predecessorProgress(): TestTerminalProgressV1 = checkNotNull(predecessor)
    internal fun paidProgress(): TestTerminalProgressV1 = checkNotNull(paid)

    internal fun requireInventoryStart() {
        requireConnectionFree(); requireRunning(); admittedDenial()
        requireQuiescence(native == null && phase == null && step === TestTerminalQuiescenceStepV1.NATIVE)
    }
    internal fun requireInventoryReader(reader: TestTerminalInventoryReaderV1) {
        requireConnectionFree(); requireRunning(); admittedDenial()
        requireQuiescence(native === reader && phase == null && !phaseEntered && step in setOf(TestTerminalQuiescenceStepV1.NATIVE,
            TestTerminalQuiescenceStepV1.WITNESS, TestTerminalQuiescenceStepV1.RECYCLE, TestTerminalQuiescenceStepV1.COMPLETE) &&
            admittedDenial().statement.evidenceRetainUntilEpochSecond > acquisition.sampleUtc().epochSecond)
    }
    internal fun beginInventoryPass(reader: TestTerminalInventoryReaderV1, pass: Int, at: Instant) {
        requireInventoryReader(reader)
        requireQuiescence(pass in 1..2 && pass == inventoryPass + 1 && summaries[pass - 1] == null && at <= acquisition.sampleUtc())
        val statement = admittedDenial().statement
        val after = if (pass == 1) admittedDenial().firstStartAfter().epochSecond else Math.addExact(checkNotNull(summaries[0]).witness.completedAtEpochSecond,
            Math.addExact(statement.acceptedRequestBoundSeconds, Math.multiplyExact(2L, statement.utcUncertaintySeconds)))
        requireQuiescence(at.epochSecond >= after)
        inventoryPass = pass; inventoryTime = at
        execute(TestTerminalQuiescenceStepV1.BEGIN_PASS)
        step = TestTerminalQuiescenceStepV1.NATIVE
    }
    internal fun expectedRow(reader: TestTerminalInventoryReaderV1, key: String, version: String): TestTerminalDurableRowV1 {
        requireInventoryReader(reader); requireQuiescence(target == null && readback == null)
        target = targets.single { it.objectRef.objectKey == key && it.objectRef.objectVersion == version }
        try { return execute(TestTerminalQuiescenceStepV1.EXPECTED).takeRow() }
        finally { target = null; step = TestTerminalQuiescenceStepV1.NATIVE }
    }
    internal fun selectedTarget(): TestTerminalQuiescenceTargetV1 = checkNotNull(target)
    internal fun stageInventoryVersion(reader: TestTerminalInventoryReaderV1, pass: Int, value: TestTerminalInventoryReadbackV1) {
        requireInventoryReader(reader); value.requireOriginal(this)
        requireQuiescence(pass == inventoryPass && readback == null && paid == null)
        readback = value
        try { execute(TestTerminalQuiescenceStepV1.APPEND) }
        finally { readback = null; step = TestTerminalQuiescenceStepV1.NATIVE }
    }
    internal fun pendingEntry(): TestPostTerminalInventoryEntryV1 {
        requireRunning(); requireQuiescence(step === TestTerminalQuiescenceStepV1.APPEND)
        return checkNotNull(readback).entry
    }
    internal fun completeInventoryPass(reader: TestTerminalInventoryReaderV1, pass: Int, at: Instant, value: TestPostTerminalInventoryFoldV1.Summary) {
        requireInventoryReader(reader); reader.requireCompletedPass(this, pass)
        requireQuiescence(pass == inventoryPass && at <= acquisition.sampleUtc() && value.versionCount == targets.size.toLong())
        inventoryTime = OrdinaryJournalRetentionV1.ceilingSecond(at); inventorySummary = value
        summaries[pass - 1] = execute(TestTerminalQuiescenceStepV1.COMPLETE_PASS).summary
        step = TestTerminalQuiescenceStepV1.NATIVE
    }
    internal fun nativeEntries(pass: Int): List<TestPostTerminalInventoryEntryV1> = checkNotNull(native).comparisonEntries(this, pass)
    internal fun nativeSummary(pass: Int): TestTerminalQuiescenceRowsV1.Summary = checkNotNull(summaries[pass - 1])
    internal fun requireRetiredPair() { requireConnectionFree(); requireRunning(); checkNotNull(native).requireRetiredPair(this) }

    internal fun retainLease(operation: TestTerminalQuiescenceOperationV1, token: Long) {
        requireRunning(); requireQuiescence(operation.original === this && step === TestTerminalQuiescenceStepV1.CAPTURE && leaseToken == 0L && token > terminalSeal.leaseToken)
        leaseToken = token
    }
    internal fun phaseBudget(): PersistenceTimeBudget = budget
    internal fun requirePersistence(owner: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requireRunning(); registration.requireSealingOwner(owner); requireQuiescence(selected.dataSource === coordinator.dataSource)
        if (jdbc == null) jdbc = selected
        requireQuiescence(jdbc === selected)
    }
    internal fun requirePhaseEntry(owner: PersistencePhaseOwnership, selected: PersistencePhasePath) {
        requireConnectionFree(); requireRunning(); registration.requireSealingOwner(owner)
        requireQuiescence(selected === path && phase == null && !phaseEntered)
        if (step in setOf(TestTerminalQuiescenceStepV1.WITNESS, TestTerminalQuiescenceStepV1.RECYCLE, TestTerminalQuiescenceStepV1.COMPLETE)) requireRetiredPair()
        phaseEntered = true
    }
    internal fun retainPhase(selected: PersistencePhaseContext) { requireRunning(); requireQuiescence(phaseEntered && phase == null); phase = selected }
    internal fun observePhaseCleanup(selected: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testTerminalQuiescenceCleanupProven(this)) cleanupUncertain = true
            else {
                phase = null; phaseEntered = false
                if (selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) { cleanupUncertain = true; observeFailure(TestTerminalQuiescenceExceptionV1()) }
            }
        } catch (problem: Throwable) { cleanupUncertain = true; observeFailure(problem) }
    }
    internal fun authenticate(owner: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requirePersistence(owner, selected)
        val openings = registration.process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.CATALOG_COORDINATOR }.openings().map { it.publicDriverProperties() }
        val user = openings.map { it["user"] }.distinct().single(); val database = openings.map { it["PGDBNAME"] }.distinct().single()
        requireQuiescence(selected.query("SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated",
            ResultSetExtractor { row -> row.next() && row.getBoolean("authenticated") && !row.wasNull() && !row.next() }, user, user, database) == true)
        requirePersistence(owner, selected)
    }
    internal fun requireMaintenanceGate(owner: PersistencePhaseOwnership, selected: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireRunning(); registration.requireSealingOwner(owner)
        requireQuiescence(selected === path && phaseEntered && phase != null); registration.requireSealingGate(gate)
    }
    internal fun requireRunning() {
        throwIfSignalled()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("TEST terminal quiescence interrupted.")
        requireQuiescence(caller === Thread.currentThread() && started && !finished && !cleanupUncertain)
        budget.remainingMillis(1); terminalSeal.requireQuiescence(this); registration.requireSealingOwner(coordinator.ownership)
        acquisition.requireRetained(routing, registration.process.publicationLanes)
    }
    private fun waitUntil(after: Instant) {
        requireQuiescence(after.epochSecond in 0..253_402_300_799L)
        while (true) {
            requireConnectionFree(); requireRunning()
            if (acquisition.sampleUtc() >= after) return
            LockSupport.parkNanos(budget.remainingMillis(25) * 1_000_000L)
        }
    }
    internal fun observeFailure(problem: Throwable) {
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("TEST terminal quiescence cancelled.")
            problem is InterruptedException || problem is InterruptedIOException || problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED ->
                InterruptedException("TEST terminal quiescence interrupted.")
            else -> TestTerminalQuiescenceExceptionV1()
        }
        while (true) {
            val old = failure.get()
            if (old is Error || old is CancellationException && retained !is Error || old is InterruptedException && retained !is Error && retained !is CancellationException ||
                old != null && retained is TestTerminalQuiescenceExceptionV1) return
            if (failure.compareAndSet(old, retained)) return
        }
    }
    internal fun throwIfSignalled() { failure.get()?.let { if (it is InterruptedException) Thread.currentThread().interrupt(); throw it } }
    override fun toString(): String = "TestRunTerminalQuiescenceV1(original-only,SEALED,no-catalog-authority,redacted)"
    companion object { internal fun begin(original: TestRunTerminalEpochSealV1): TestRunTerminalQuiescenceV1 = TestRunTerminalQuiescenceV1(original) }
}

/** Historical expected locator only. Native LIST/GET/AEAD and current SQL must still agree exactly. */
internal data class TestTerminalQuiescenceTargetV1(val kind: TestTerminalCodecKindV1, val ordinal: Int, val id: String,
    val startEpoch: Long, val endEpoch: Long, val objectRef: TestTerminalObjectRefV1)
internal enum class TestTerminalQuiescenceStepV1 { CAPTURE, ADMISSION, NATIVE, BEGIN_PASS, EXPECTED, APPEND, COMPLETE_PASS, WITNESS, RECYCLE, COMPLETE }
internal enum class TestRunTerminalQuiescenceResultV1 { TERMINAL_PREFIX_QUIESCENT_AND_SEALED }
internal class TestTerminalQuiescenceExceptionV1 : RuntimeException("TEST terminal quiescence refused.", null, false, false)
internal fun requireQuiescence(allowed: Boolean) { if (!allowed) throw TestTerminalQuiescenceExceptionV1() }
