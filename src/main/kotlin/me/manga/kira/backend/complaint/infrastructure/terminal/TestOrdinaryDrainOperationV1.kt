package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDailyAdmission
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCompletedCutV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialCutV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialPrefixV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProgressV1
import me.manga.kira.backend.complaint.infrastructure.OwnerDeletePersistenceSql
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteRows
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.security.TestTerminalJsonV1
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.util.HexFormat
import java.util.UUID

/** Fixed SQL stages only. Native reads, raw evidence admission and waiting stay outside the holder. */
internal class TestOrdinaryDrainOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val original: TestRunOrdinaryDrainV1,
) {
    internal val path = PersistencePhasePath.COMPLAINT_TEST_ORDINARY_DRAIN
    internal val step = original.step
    internal lateinit var run: TestOrdinaryDrainRowsV1.Run
        private set
    internal var scanCharge = ComplaintCapacityVector.ZERO
        private set
    internal var progress: TestTerminalProgressV1? = null
        private set
    internal var summary: TestOrdinaryDrainRowsV1.Summary? = null
        private set
    internal var entries: List<TestOrdinaryDrainRowsV1.Entry> = emptyList()
        private set
    internal var primaries: List<String> = emptyList()
        private set
    private var stage = Stage.NEW
    private var counters: JdbcComplaintCapacityStore.LockedTestOrdinaryDrain? = null
    private var initialScanCharge = ComplaintCapacityVector.ZERO
    private var sidecars = 0L
    private var spend = ComplaintCapacityVector.ZERO
    private var recycled = ComplaintCapacityVector.ZERO
    private var released = ComplaintCapacityVector.ZERO
    private var progressWrite: ByteArray? = null
    private var selectedPrimary: TestOrdinaryDrainPersistenceV1.Primary? = null
    private var discovery: List<Discovered> = emptyList()
    private var scans: List<TestOrdinaryDrainRowsV1.Scan> = emptyList()

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = belongsTo(selected) && stage === Stage.COMPLETE && counters?.completedFor(this) == true
    internal fun requireReleased() { phase.testOrdinaryDrainBoundary.requireCommitted(this); requireConnectionFree() }

    private fun execute() {
        retained(Stage.NEW)
        requireDrain(step in SQL_STEPS)
        if (step in setOf(TestOrdinaryDrainStepV1.ABANDON, TestOrdinaryDrainStepV1.RECYCLE)) {
            // Discovery locks nothing. Every selected physical row is compared again under the
            // subsequent controls/counters/run/scan lock prefix before it can be removed/repaid.
            discovery = jdbc.query(TestOrdinaryDrainSqlV1.recyclePage, { row, _ -> Discovered(
                checkNotNull(row.getObject("scan_id", UUID::class.java)), row.getInt("pass"), TestOrdinaryDrainRowsV1.Entry.read(row, original)) }, original.scope)
        }
        stage = Stage.CONTROLS
        if (step === TestOrdinaryDrainStepV1.OPEN) {
            TestOrdinaryDrainPersistenceV1.lockControlIdentities(jdbc, original)
            val before = TestOrdinaryDrainPersistenceV1.readControl(jdbc, original)
            val lease = jdbc.query(TestOrdinarySealSqlV1.acquire, { row, _ -> row.getLong("lease_token") }, original.attemptId,
                original.budget.remainingMillis(original.routing.journalConfiguration.declaration().limits.deadlines.scanMillis.toLong()), original.scope).single()
            requireDrain(lease > 0)
            val control = TestOrdinaryDrainPersistenceV1.readControl(jdbc, original)
            control.requireSame(before)
            original.retainCapture(this, control, lease)
        } else TestOrdinaryDrainPersistenceV1.requireControls(jdbc, original)
        TestOrdinaryDrainPersistenceV1.requireLease(jdbc, original)
        TestOrdinaryDrainPersistenceV1.requireSupported(jdbc, original)

        if (step === TestOrdinaryDrainStepV1.CONVERT) {
            stage = Stage.PRIMARY
            selectedPrimary = TestOrdinaryDrainPersistenceV1.primary(jdbc, original, checkNotNull(original.selectedPrimary), locked = true)
        }
        stage = Stage.COUNTERS_REQUESTED
        counters = JdbcComplaintCapacityStore(jdbc, original.registration.process.consumers.capacityPolicy.digestBytes()).lockForTestOrdinaryDrain(this)
        retained(Stage.COUNTERS_LOCKING)
        stage = Stage.BODY
        run = TestOrdinaryDrainPersistenceV1.readRun(jdbc, original)
        scans = TestOrdinaryDrainPersistenceV1.scans(jdbc, original)
        sidecars = TestOrdinaryDrainPersistenceV1.sidecars(jdbc, original, run)
        initialScanCharge = TestOrdinaryDrainPersistenceV1.scanCharge(jdbc, original, run)
        run.requirePaidRemainder(initialScanCharge, sidecars)
        progress = run.progress
        if (step !in setOf(TestOrdinaryDrainStepV1.OPEN, TestOrdinaryDrainStepV1.CAPTURE, TestOrdinaryDrainStepV1.ABANDON,
                TestOrdinaryDrainStepV1.BEGIN_PASS, TestOrdinaryDrainStepV1.APPEND, TestOrdinaryDrainStepV1.COMPLETE_PASS, TestOrdinaryDrainStepV1.WITNESS)) {
            original.requirePaidProgress(run.progress)
        }
        when (step) {
            TestOrdinaryDrainStepV1.OPEN -> {
                val control = TestOrdinaryDrainPersistenceV1.readControl(jdbc, original)
                if (control.sequence == 1L) {
                    requireDrain(!checkNotNull(control.capturedAt).isBefore(run.sealedAt))
                    TestOrdinaryDrainPersistenceV1.requireAllPrimaries(jdbc, original, run, converted = false, staged = false)
                } else requireDrain(run.progress == null && scans.isEmpty() && sidecars == 0L)
            }
            TestOrdinaryDrainStepV1.CAPTURE -> capture()
            TestOrdinaryDrainStepV1.ABANDON -> recycle(abandon = true)
            TestOrdinaryDrainStepV1.BEGIN_PASS -> beginPass()
            TestOrdinaryDrainStepV1.APPEND -> append()
            TestOrdinaryDrainStepV1.COMPLETE_PASS -> completePass()
            TestOrdinaryDrainStepV1.WITNESS -> witness()
            TestOrdinaryDrainStepV1.RECOVERY_PAGE -> {
                TestOrdinaryDrainPersistenceV1.requirePaidScanHeaders(original, scans, completePair = false)
                entries = scans.firstOrNull()?.let { TestOrdinaryDrainPersistenceV1.entryPage(jdbc, original, it.id, it.pass, original.afterEntry) } ?: emptyList()
            }
            TestOrdinaryDrainStepV1.PRIMARY_PAGE -> primaries = TestOrdinaryDrainPersistenceV1.primaryPage(jdbc, original, original.afterPrimary)
            TestOrdinaryDrainStepV1.CONVERT -> convert()
            TestOrdinaryDrainStepV1.CLOSEOUT -> closeout()
            TestOrdinaryDrainStepV1.RECYCLE -> recycle(abandon = false)
            TestOrdinaryDrainStepV1.READY -> {
                original.requireCloseoutReady()
                closeout()
                requireDrain(scans.isEmpty() && initialScanCharge.isZero())
            }
            else -> throw TestOrdinaryDrainExceptionV1()
        }
        retained(Stage.BODY)
        scanCharge = TestOrdinaryDrainPersistenceV1.scanCharge(jdbc, original, run)
        val staged = when (step) {
            TestOrdinaryDrainStepV1.BEGIN_PASS, TestOrdinaryDrainStepV1.APPEND -> spend
            else -> ComplaintCapacityVector.ZERO
        }
        requireDrain(scanCharge == initialScanCharge + staged - recycled)
        val afterUnused = run.unused - spend + recycled
        requireDrain(afterUnused.fitsWithin(run.reserve))
        stage = Stage.TRANSFER
        checkNotNull(counters).settle(this)
        requireDrain(checkNotNull(counters).completedFor(this))
        val bytes = progressWrite
        if (bytes != null) {
            try {
                requireDrain(jdbc.update(TestOrdinaryDrainSqlV1.spendAndProgress, OwnerDeleteRows.array(afterUnused), bytes, hex(Sha256.hex(bytes)),
                    original.scope, OwnerDeleteRows.array(run.unused), run.progressBytes, run.progressHash) == 1)
            } finally { bytes.fill(0); progressWrite = null }
        } else if (afterUnused != run.unused) {
            requireDrain(jdbc.update(TestOrdinaryDrainSqlV1.spend, OwnerDeleteRows.array(afterUnused), original.scope, OwnerDeleteRows.array(run.unused)) == 1)
        }
        // Re-read the entire disjoint reserve equation, not just an aggregate bound. This also
        // catches legacy non-NULL blobs whose lifetime envelope was never paid at the first cut.
        val afterRun = TestOrdinaryDrainPersistenceV1.readRun(jdbc, original)
        afterRun.requirePaidRemainder(scanCharge, sidecars)
        requireDrain(afterRun.unused == afterUnused)
        TestOrdinaryDrainPersistenceV1.requireControls(jdbc, original)
        retained(Stage.TRANSFER)
        stage = Stage.COMPLETE
    }

    private fun capture() {
        requireDrain(run.progress == null && sidecars == 0L && scans.isEmpty())
        TestOrdinaryDrainPersistenceV1.requireAllPrimaries(jdbc, original, run, converted = false, staged = false)
        val before = TestOrdinaryDrainPersistenceV1.readControl(jdbc, original)
        requireDrain(before.sequence == 0L)
        requireDrain(jdbc.update(TestOrdinaryDrainSqlV1.capture, original.captureId, original.scope, original.attemptId, original.leaseToken) == 1)
        val control = TestOrdinaryDrainPersistenceV1.readControl(jdbc, original)
        requireDrain(!checkNotNull(control.capturedAt).isBefore(run.sealedAt))
        original.retainCapture(this, control, original.leaseToken)
    }

    private fun beginPass() {
        requireDrain(run.progress == null && sidecars == 0L)
        original.admittedDenial()
        val pass = original.inventoryPass
        if (pass == 1) requireDrain(scans.isEmpty()) else requireDrain(pass == 2 && scans.size == 1 && scans.single().id == original.scanId &&
            scans.single().pass == 1 && scans.single().state == "COMPLETE" && scans.single().fence == original.leaseToken)
        val statement = original.admittedDenial().statement
        requireDrain(jdbc.update(TestOrdinaryDrainSqlV1.insertRun, original.scanId, pass, original.scope, statement.restoreIdentity,
            original.registration.process.desiredGeneration, original.leaseToken, original.writer, original.cutoff, original.maximumVersions,
            original.maximumFramedBytes, Timestamp.from(checkNotNull(original.inventoryTime))) == 1)
        spend = TestTerminalCapacityChargesV1.SCAN_RUN
    }

    private fun append() {
        requireDrain(run.progress == null && sidecars == 0L)
        val scan = activeScan()
        val entry = original.pendingEntry(this)
        val charge = entry.framedBytes()
        // Plain INSERT: nonadjacent native repeats/cycles cannot be filtered or adopted as progress.
        requireDrain(jdbc.update(TestOrdinaryDrainSqlV1.insertEntry, original.scanId, scan.pass, original.scope, entry.key, entry.version,
            hex(entry.ciphertext), hex(entry.semantic), entry.eventId, original.writer, entry.epoch, entry.ciphertextBytes) == 1)
        requireDrain(jdbc.update(TestOrdinaryDrainSqlV1.appendRun, charge, original.scanId, scan.pass, original.scope, charge) == 1)
        spend = TestTerminalCapacityChargesV1.SCAN_ENTRY
    }

    private fun activeScan(): TestOrdinaryDrainRowsV1.Scan = scans.single { it.pass == original.inventoryPass }.also {
        requireDrain(it.id == original.scanId && it.fence == original.leaseToken && it.state == "SCANNING")
    }

    private fun completePass() {
        requireDrain(run.progress == null && sidecars == 0L)
        val scan = activeScan()
        requireDrain(scan.count == original.nativeVersionCount)
        val folded = fold(scan, checkNotNull(original.inventoryTime))
        requireDrain(folded.witness.byteCount == original.nativeCiphertextBytes && folded.entryFramedBytes == scan.framedBytes)
        requireDrain(jdbc.update(TestOrdinaryDrainSqlV1.finishRun, hex(folded.witness.sha256), Timestamp.from(checkNotNull(original.inventoryTime)),
            folded.framedBytes, original.scanId, scan.pass, original.scope, scan.count, scan.framedBytes, folded.framedBytes) == 1)
        summary = folded
    }

    private fun fold(scan: TestOrdinaryDrainRowsV1.Scan, end: java.time.Instant): TestOrdinaryDrainRowsV1.Summary {
        val fold = TestOrdinaryDrainRowsV1.Fold(original, scan.count)
        var after: Pair<String, String>? = null
        while (true) {
            retained(Stage.BODY)
            val page = TestOrdinaryDrainPersistenceV1.entryPage(jdbc, original, scan.id, scan.pass, after)
            if (page.isEmpty()) break
            page.forEach { entry -> fold.entry(entry); after = entry.locator }
        }
        return fold.finish(scan.startedAt, end)
    }

    private fun witness() {
        requireDrain(run.progress == null && sidecars == 0L && scans.size == 2 && scans.map { it.pass } == listOf(1, 2))
        val summaries = scans.map { scan ->
            requireDrain(scan.id == original.scanId && scan.fence == original.leaseToken && scan.state == "COMPLETE")
            fold(scan, checkNotNull(scan.finishedAt)).also {
                requireDrain(it == original.nativeSummary(scan.pass) && it.framedBytes == scan.framedBytes && it.witness.sha256 == scan.root)
            }
        }
        // Counts/roots alone are insufficient: compare every canonical metadata field of both
        // complete sets in fixed key/version pages. No latest-version or primary-only filtering.
        var after: Pair<String, String>? = null
        while (true) {
            retained(Stage.BODY)
            val left = TestOrdinaryDrainPersistenceV1.entryPage(jdbc, original, original.scanId, 1, after)
            val right = TestOrdinaryDrainPersistenceV1.entryPage(jdbc, original, original.scanId, 2, after)
            requireDrain(left == right && left.all { it.replay == "PENDING" })
            if (left.isEmpty()) break
            after = left.last().locator
        }
        val admitted = original.admittedDenial()
        val statement = admitted.statement
        val first = summaries[0]
        val second = summaries[1]
        requireDrain(first.framedBytes == second.framedBytes)
        val denial = TestTerminalDenialCutV1(statement.roleId, statement.policy, statement.denialEffectiveAtEpochSecond,
            statement.lastSessionExpiryEpochSecond, statement.acceptedRequestBoundSeconds, admitted.policyEvidence, statement.boundEvidence,
            first.witness, second.witness)
        val cut = TestTerminalCompletedCutV1(original.writer, TestTerminalDenialPrefixV1.ORDINARY, 1, original.cutoff,
            original.scanId.toString(), statement.databaseIdentity, statement.restoreIdentity, original.registration.process.desiredGeneration,
            original.leaseToken, first.framedBytes, denial)
        val value = TestTerminalProgressV1.create(original.runContext, listOf(cut), emptyList())
        progressWrite = TestTerminalJsonV1(original.routing.journalConfiguration).encodeProgress(value)
        requireDrain(checkNotNull(progressWrite).size <= TestTerminalProgressV1.MAX_CANONICAL_BYTES)
        spend = TestTerminalCapacityChargesV1.TERMINAL_RUN_DELTA // Entire existing lifetime envelope; once, no new row/index.
        progress = value
    }

    private fun convert() {
        val value = checkNotNull(selectedPrimary)
        TestOrdinaryDrainPersistenceV1.requirePaidScanHeaders(original, scans, completePair = value.recovery.state != "CONVERTED")
        TestOrdinaryDrainPersistenceV1.requireAppliedCut(jdbc, original)
        TestOrdinaryDrainPersistenceV1.requirePrimary(jdbc, original, run, value, converted = false,
            staged = value.recovery.state != "CONVERTED", lockDomain = true)
        if (value.recovery.state == "CONVERTED") return // Immutable exact replay: no counter transfer and no timestamp rewrite.
        requireDrain(value.recovery.state == "PARTIAL" && sidecars == 0L && run.sealSetBytes == null)
        released = value.recovery.remaining
        requireDrain(jdbc.update(TestOrdinaryDrainSqlV1.convert, value.publication.eventId, original.scope,
            OwnerDeleteRows.array(value.recovery.promise), OwnerDeleteRows.array(value.recovery.used), Timestamp.from(value.recovery.lastAppliedAt)) == 1)
    }

    private fun closeout() {
        TestOrdinaryDrainPersistenceV1.requirePaidScanHeaders(original, scans, completePair = false)
        TestOrdinaryDrainPersistenceV1.requireAllPrimaries(jdbc, original, run, converted = true, staged = false)
        TestOrdinaryDrainPersistenceV1.requireAppliedCut(jdbc, original)
        scans.forEach { scan ->
            var after: Pair<String, String>? = null
            var count = 0L
            while (true) {
                retained(Stage.BODY)
                val page = TestOrdinaryDrainPersistenceV1.entryPage(jdbc, original, scan.id, scan.pass, after)
                if (page.isEmpty()) break
                page.forEach { entry ->
                    requireDrain(entry.replay == "APPLIED" && count < scan.count)
                    val applied = jdbc.query(OwnerDeletePersistenceSql.LOCK_APPLIED.removeSuffix(" FOR UPDATE"), { row, _ ->
                        TestOrdinaryDrainPersistenceV1.Applied.read(row, original) }, entry.key, entry.version).single()
                    applied.requireEntry(entry)
                    count++; after = entry.locator
                }
            }
        }
    }

    private fun recycle(abandon: Boolean) {
        if (abandon) {
            requireDrain(run.progress == null && sidecars == 0L)
            original.admittedDenial()
            TestOrdinaryDrainPersistenceV1.requireAllPrimaries(jdbc, original, run, converted = false, staged = false)
            scans.forEach { requireDrain(jdbc.update(TestOrdinaryDrainSqlV1.abandon, it.id, it.pass, original.scope) == 1) }
        } else {
            original.requireCloseoutReady()
            closeout()
        }
        discovery.forEach { discovered ->
            retained(Stage.BODY)
            val scan = scans.single { it.id == discovered.id && it.pass == discovered.pass }
            if (!abandon) requireDrain(scan.id == original.scanId && discovered.entry.replay == "APPLIED")
            val actual = jdbc.query(TestOrdinaryDrainSqlV1.exactRecycleEntry, { row, _ ->
                requireDrain(row.getObject("scan_id", UUID::class.java) == discovered.id && row.getInt("pass") == discovered.pass)
                TestOrdinaryDrainRowsV1.Entry.read(row, original)
            }, discovered.id, discovered.pass, discovered.entry.key, discovered.entry.version).single()
            requireDrain(actual == discovered.entry)
            requireDrain(jdbc.update(TestOrdinaryDrainSqlV1.deleteEntry, discovered.id, discovered.pass, original.scope, actual.key, actual.version,
                hex(actual.ciphertext), hex(actual.semantic), actual.eventId, original.writer, actual.epoch, actual.ciphertextBytes, actual.replay) == 1)
            recycled += TestTerminalCapacityChargesV1.SCAN_ENTRY
        }
        scans.forEach { scan ->
            retained(Stage.BODY)
            // The surviving physical rows, not the immutable scan.entry_count, decide row removal.
            if (jdbc.queryForObject(TestOrdinaryDrainSqlV1.scanEntryCount, Long::class.java, scan.id, scan.pass) == 0L) {
                requireDrain(jdbc.update(TestOrdinaryDrainSqlV1.deleteRun, scan.id, scan.pass, original.scope) == 1)
                recycled += TestTerminalCapacityChargesV1.SCAN_RUN
            }
        }
        requireDrain(recycled.fitsWithin(initialScanCharge))
    }

    internal fun beginCounterLock(selected: JdbcTemplate) { retained(Stage.COUNTERS_REQUESTED, selected); stage = Stage.COUNTERS_LOCKING }
    internal fun requireCounterTransfer(locked: JdbcComplaintCapacityStore.LockedTestOrdinaryDrain, selected: JdbcTemplate) {
        retained(Stage.TRANSFER, selected); requireDrain(counters === locked)
    }
    internal fun settleLockedLedger(selected: JdbcTemplate, ledger: ComplaintCapacityLedger, daily: ComplaintDailyAdmission,
        expectedDigest: ByteArray): ComplaintCapacityLedger {
        retained(Stage.TRANSFER, selected)
        ledger.configuration.requireMatching(expectedDigest)
        val policy = original.registration.process.consumers.capacityPolicy
        requireDrain(ledger.balance.hardLimit == policy.hardLimit && ledger.balance.creationLimit == policy.creationLimit &&
            daily.dailyLimit == policy.dailyEnrollmentLimit && run.unused.fitsWithin(ledger.balance.testReserved) &&
            initialScanCharge.fitsWithin(ledger.balance.actual) && spend.fitsWithin(run.unused))
        if (!released.isZero()) requireDrain(step === TestOrdinaryDrainStepV1.CONVERT && spend.isZero() && recycled.isZero())
        if (!recycled.isZero()) requireDrain(step in setOf(TestOrdinaryDrainStepV1.RECYCLE, TestOrdinaryDrainStepV1.ABANDON) && spend.isZero() && released.isZero())
        return when {
            !released.isZero() -> ledger.releaseRecovery(expectedDigest, released) // P-U only; U is already actual.
            !recycled.isZero() -> ledger.recycleTestScanPool(expectedDigest, run.plan.scanPool, run.plan.scanPool.ceiling - initialScanCharge, recycled)
            !spend.isZero() -> ledger.spendTestReserve(expectedDigest, spend, ComplaintCapacityVector.ZERO)
            else -> ledger
        }
    }
    internal fun failed(problem: Throwable): Nothing {
        original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled()
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }
    private fun retained(expected: Stage, selected: JdbcTemplate = jdbc) {
        phase.testOrdinaryDrainBoundary.requireRetained(this, selected)
        requireDrain(stage === expected && step === original.step && selected === jdbc)
    }
    private data class Discovered(val id: UUID, val pass: Int, val entry: TestOrdinaryDrainRowsV1.Entry)
    private enum class Stage { NEW, CONTROLS, PRIMARY, COUNTERS_REQUESTED, COUNTERS_LOCKING, BODY, TRANSFER, COMPLETE }
    companion object {
        private val SQL_STEPS = setOf(TestOrdinaryDrainStepV1.OPEN, TestOrdinaryDrainStepV1.CAPTURE, TestOrdinaryDrainStepV1.ABANDON,
            TestOrdinaryDrainStepV1.BEGIN_PASS, TestOrdinaryDrainStepV1.APPEND, TestOrdinaryDrainStepV1.COMPLETE_PASS, TestOrdinaryDrainStepV1.WITNESS,
            TestOrdinaryDrainStepV1.RECOVERY_PAGE, TestOrdinaryDrainStepV1.PRIMARY_PAGE, TestOrdinaryDrainStepV1.CONVERT,
            TestOrdinaryDrainStepV1.CLOSEOUT, TestOrdinaryDrainStepV1.RECYCLE, TestOrdinaryDrainStepV1.READY)
        internal fun execute(jdbc: JdbcTemplate, original: TestRunOrdinaryDrainV1): TestOrdinaryDrainOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.testOrdinaryDrainBoundary.requireOperation(original, jdbc)
                return TestOrdinaryDrainOperationV1(phase, jdbc, original).also { phase.testOrdinaryDrainBoundary.retain(it, jdbc); it.execute() }
            } catch (problem: Throwable) {
                original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled()
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
        private fun hex(value: String): ByteArray = HexFormat.of().parseHex(value)
    }
}
