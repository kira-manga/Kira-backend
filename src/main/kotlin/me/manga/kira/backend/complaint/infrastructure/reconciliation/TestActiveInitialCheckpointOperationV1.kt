package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationSqlV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationTailV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationSqlV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.sql.Timestamp

/** One fixed phase, never a supplied SQL callback. Returned observations require known COMMIT and physical release. */
internal class TestActiveInitialCheckpointOperationV1 private constructor(
    private val phase: PersistencePhaseContext, private val jdbc: JdbcTemplate, internal val original: TestActiveInitialCheckpointV1,
) {
    internal val step = original.step
    internal val path = original.path
    private var completed = false
    private var controlsLocked = false
    private var countersClaimed = false
    private var runLocked = false
    private var slotLocked = false
    private var scansLocked = false
    private var chargeReady = false
    private var refundRows: Int? = null
    private var counters: JdbcComplaintCapacityStore.LockedTestActiveInitialCheckpoint? = null
    internal lateinit var current: TestActiveInitialCheckpointRowsV1.Current
        private set
    internal lateinit var tail: TestNamespaceRecoveryRegistrationTailV1
        private set
    internal lateinit var history: CatalogTestRunActivationHistoryV1
        private set
    internal var intent: TestTerminalDurableRowV1? = null
        private set
    internal var control: TestActiveInitialCheckpointRowsV1.Control? = null
        private set
    internal var scans: List<TestActiveInitialCheckpointRowsV1.Scan> = emptyList()
        private set

    internal fun belongsTo(selected: PersistencePhaseContext) = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext) = belongsTo(selected) && completed
    internal fun requireReleased() { requireCommittedComparison(); requireConnectionFree() }
    /** Pure former phase proof; no new connection-free demand inside a later fixed comparison phase. */
    internal fun requireCommittedComparison() = phase.testActiveInitialCheckpoint.requireCommitted(this)
    internal fun discardDetached() { intent?.close(); intent = null; control?.close(); control = null }
    private fun retained() { phase.testActiveInitialCheckpoint.requireRetained(this, jdbc); requireInitialCheckpoint(!completed) }

    private fun run() {
        retained()
        requireInitialCheckpoint(jdbc.query(TestActiveInitialCheckpointSqlV1.lockGlobal, { _, _ -> true }).single())
        requireInitialCheckpoint(jdbc.query(TestActiveInitialCheckpointSqlV1.lockScope, { _, _ -> true }, original.scope).single())
        controlsLocked = true
        readAdmission()
        // Counter-before-row order for EVERY creation/refund path, including empty/superseded cleanup.
        if (step in ACCOUNTING_STEPS) counters = JdbcComplaintCapacityStore(jdbc, original.process.consumers.capacityPolicy.digestBytes())
            .lockForTestActiveInitialCheckpoint(this)
        requireInitialCheckpoint(jdbc.query(TestActiveInitialCheckpointSqlV1.lockRun, { _, _ -> true }, original.scope).single())
        runLocked = true
        requireInitialCheckpoint(jdbc.query(TestActiveInitialCheckpointSqlV1.lockSlot, { _, _ -> true }, original.scope).single())
        slotLocked = true
        current = readCurrent()
        original.requireState(current)
        scans = readScans(); scansLocked = true
        requirePrefix()
        when (step) {
            TestActiveInitialCheckpointStepV1.READ -> {
                intent = jdbc.query(TestActiveInitialCheckpointSqlV1.slot, { row, _ -> TestActiveInitialCheckpointRowsV1.intent(row, original, current) },
                    current.operationToken, original.scope).single()
                control = jdbc.query(TestActiveInitialCheckpointSqlV1.sealControl, { row, _ ->
                    TestActiveInitialCheckpointRowsV1.Control.read(row, checkNotNull(intent), current)
                }, original.scope).single()
            }
            TestActiveInitialCheckpointStepV1.ACQUIRE -> acquireAndClean()
            else -> {
                original.requireRawReleased(); current.requireLease(original); original.requirePriorScans(scans)
                when (step) {
                    TestActiveInitialCheckpointStepV1.RENEW -> requireInitialCheckpoint(jdbc.update(TestActiveInitialCheckpointSqlV1.renew,
                        original.scope, original.attemptId, original.leaseToken) == 1)
                    TestActiveInitialCheckpointStepV1.START_PASS -> startPass()
                    TestActiveInitialCheckpointStepV1.COMPLETE_PASS -> completePass()
                    TestActiveInitialCheckpointStepV1.SUCCESS -> success()
                    else -> throw TestActiveInitialCheckpointExceptionV1()
                }
            }
        }
        retained()
        if (step !== TestActiveInitialCheckpointStepV1.SUCCESS) {
            val after = readCurrent(); current.requireSame(after); original.requireState(after); current = after
            if (step !== TestActiveInitialCheckpointStepV1.READ) {
                current.requireLease(original)
                requireInitialCheckpoint(jdbc.query(TestActiveInitialCheckpointSqlV1.lease, { row, _ -> TestActiveInitialCheckpointRowsV1.boolean(row, "valid") },
                    original.attemptId, original.leaseToken, original.scope).single())
            }
        }
        if (step in ACCOUNTING_STEPS) requireInitialCheckpoint(checkNotNull(counters).completedFor(this))
        retained(); completed = true
    }

    private fun acquireAndClean() {
        original.requireRawReleased()
        val prior = current.leaseToken
        requireInitialCheckpoint(prior < Long.MAX_VALUE && (current.leaseOwner == null || !checkNotNull(current.leaseExpiresAt).isAfter(current.sampledAt)))
        val token = jdbc.query(TestActiveInitialCheckpointSqlV1.acquire, { row, _ -> TestActiveInitialCheckpointRowsV1.long(row, "lease_token") },
            original.attemptId, original.scope, current.operationToken, prior).single()
        original.retainAcquiringToken(this, prior, token)
        val after = readCurrent(); current.requireSame(after); current = after; current.requireLease(original)
        // ALL rows were read, checked and locked. One shared previous token and exact pass prefix only.
        requireInitialCheckpoint(scans.all { it.token <= prior && it.token < token })
        refundRows = scans.size
        deleteScans()
        checkNotNull(counters).refund(this)
        scans = readScans(); requireInitialCheckpoint(scans.isEmpty())
    }
    private fun startPass() {
        val pass = original.passNumber
        requireInitialCheckpoint(pass in 1..2 && scans.size == pass - 1 && scans.all { it.token == original.leaseToken && it.state == "COMPLETE" })
        if (pass == 2) requireCompleted(scans.single())
        requireInitialCheckpoint(scans.lastOrNull()?.finishedAt?.isAfter(current.sampledAt) != true)
        chargeReady = true
        checkNotNull(counters).charge(this)
        requireInitialCheckpoint(jdbc.update(TestActiveInitialCheckpointSqlV1.insertScan, current.operationToken, pass, original.scope,
            original.identity.restoreIdentity, original.identity.desiredGeneration, original.leaseToken, original.identity.writer,
            original.maximumEntries, original.maximumBytes, Timestamp.from(current.sampledAt), current.operationToken) == 1)
        scans = readScans(); requirePrefix()
        requireInitialCheckpoint(scans.size == pass && scans.last().pass == pass && scans.last().state == "SCANNING" && scans.last().token == original.leaseToken)
    }
    private fun completePass() {
        val pass = original.passNumber
        requireInitialCheckpoint(pass in 1..2 && scans.size == pass)
        val row = scans.single { it.pass == pass }
        val proof = original.completedPass(pass)
        requireInitialCheckpoint(row.same(original.stagedPass(pass)) && row.state == "SCANNING" && row.token == original.leaseToken &&
            proof.summary.startedAt == row.startedAt && !proof.summary.completedAt.isAfter(current.sampledAt))
        val hash = TestActiveInitialCheckpointRowsV1.hex(original.manifestSha256)
        try { requireInitialCheckpoint(jdbc.update(TestActiveInitialCheckpointSqlV1.completeScan, hash, Timestamp.from(proof.summary.completedAt),
            row.scanId, pass, original.scope, original.leaseToken, row.scanId, Timestamp.from(row.startedAt)) == 1) }
        finally { hash.fill(0) }
        scans = readScans(); requirePrefix(); scans.forEach(::requireCompleted)
    }
    private fun requireCompleted(row: TestActiveInitialCheckpointRowsV1.Scan) {
        val proof = original.completedPass(row.pass)
        requireInitialCheckpoint(row.state == "COMPLETE" && row.token == original.leaseToken && row.manifestSha256 == original.manifestSha256 &&
            row.startedAt == proof.summary.startedAt && row.finishedAt == proof.summary.completedAt)
    }
    private fun success() {
        requireInitialCheckpoint(scans.map { it.pass } == listOf(1, 2)); scans.forEach(::requireCompleted)
        val arguments = original.documentArguments(this)
        try {
            requireInitialCheckpoint(!(arguments[11] as Timestamp).toInstant().isAfter(current.sampledAt))
            refundRows = 2
            deleteScans()
            checkNotNull(counters).refund(this)
            requireInitialCheckpoint(jdbc.update(TestActiveInitialCheckpointSqlV1.success, *arguments, original.scope, original.attemptId, original.leaseToken) == 1)
            requireInitialCheckpoint(jdbc.query(TestActiveInitialCheckpointSqlV1.completed, { row, _ -> TestActiveInitialCheckpointRowsV1.boolean(row, "valid") },
                *arguments, original.leaseToken, original.scope).single())
            scans = readScans(); requireInitialCheckpoint(scans.isEmpty())
        } finally { arguments.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
    }
    private fun deleteScans() {
        retained(); requireInitialCheckpoint(refundRows == scans.size)
        scans.forEach { row ->
            val arguments = row.deleteArguments()
            try { requireInitialCheckpoint(jdbc.update(TestActiveInitialCheckpointSqlV1.deleteScan, *arguments) == 1) }
            finally { arguments.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
            retained()
        }
    }
    private fun requirePrefix() {
        requireInitialCheckpoint(scans.size <= 2 && scans.map { it.pass } == (1..scans.size).toList() && scans.map { it.token }.distinct().size <= 1)
        if (scans.size == 2) requireInitialCheckpoint(scans[0].state == "COMPLETE" && !scans[1].startedAt.isBefore(checkNotNull(scans[0].finishedAt)))
    }
    private fun readScans(): List<TestActiveInitialCheckpointRowsV1.Scan> {
        retained()
        return checkNotNull(jdbc.query(TestActiveInitialCheckpointSqlV1.scans, ResultSetExtractor { rows ->
            val values = ArrayList<TestActiveInitialCheckpointRowsV1.Scan>(2)
            while (rows.next()) {
                requireInitialCheckpoint(values.size < 2)
                values.add(TestActiveInitialCheckpointRowsV1.Scan(rows, original, current))
            }
            values
        }, original.scope))
    }
    private fun readCurrent(): TestActiveInitialCheckpointRowsV1.Current {
        retained()
        val arguments = original.identity.arguments()
        return try { jdbc.query(TestActiveInitialCheckpointSqlV1.current, { row, _ -> TestActiveInitialCheckpointRowsV1.Current(row) }, *arguments).single() }
        finally { arguments.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
    }
    private fun readAdmission() {
        retained()
        val args = original.identity.tailArguments()
        tail = try { jdbc.query(TestNamespaceRecoveryRegistrationSqlV1.tail, { row, _ -> TestNamespaceRecoveryRegistrationTailV1(row, original.scope) }, *args).single() }
        finally { args.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
        val historyArgs = original.historyArguments()
        history = try { checkNotNull(jdbc.query(CatalogTestRunActivationSqlV1.lockRecoveryRegistrationHistory.removeSuffix("\nFOR UPDATE"),
            ResultSetExtractor { rows -> CatalogTestRunActivationHistoryV1.readActiveCurrent(rows, original.identity.generation,
                original.process.catalogReadback.chainPolicy.limits.maximumGenerations) }, *historyArgs)) }
        finally { historyArgs.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
        original.requireAdmissionComparisons(this, tail, history)
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        retained()
        requireInitialCheckpoint(selected === jdbc && step in ACCOUNTING_STEPS && controlsLocked && !countersClaimed && !runLocked && !slotLocked && !scansLocked)
        countersClaimed = true
    }
    internal fun requireCounterRead(selected: JdbcTemplate) {
        retained(); requireInitialCheckpoint(selected === jdbc && countersClaimed && controlsLocked && !runLocked && !slotLocked && !scansLocked)
    }
    internal fun requireCharge(selected: JdbcComplaintCapacityStore.LockedTestActiveInitialCheckpoint, selectedJdbc: JdbcTemplate) {
        retained()
        requireInitialCheckpoint(selectedJdbc === jdbc && counters === selected && countersClaimed && runLocked && slotLocked && scansLocked &&
            step === TestActiveInitialCheckpointStepV1.START_PASS && chargeReady && refundRows == null)
        current.requireLease(original)
    }
    internal fun refundCount(selected: JdbcComplaintCapacityStore.LockedTestActiveInitialCheckpoint, selectedJdbc: JdbcTemplate): Int {
        retained()
        requireInitialCheckpoint(selectedJdbc === jdbc && counters === selected && countersClaimed && runLocked && slotLocked && scansLocked &&
            step in setOf(TestActiveInitialCheckpointStepV1.ACQUIRE, TestActiveInitialCheckpointStepV1.SUCCESS) && !chargeReady)
        current.requireLease(original)
        return checkNotNull(refundRows).also { requireInitialCheckpoint(it in 0..2 && it == scans.size) }
    }
    internal fun failed(problem: Throwable): Nothing {
        original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled()
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }
    override fun toString(): String = "InitialCheckpointOperation(fixed-ordinary-paid,no-terminal-authority)"
    companion object {
        private val ACCOUNTING_STEPS = setOf(TestActiveInitialCheckpointStepV1.ACQUIRE, TestActiveInitialCheckpointStepV1.START_PASS, TestActiveInitialCheckpointStepV1.SUCCESS)
        internal fun execute(jdbc: JdbcTemplate, original: TestActiveInitialCheckpointV1): TestActiveInitialCheckpointOperationV1 {
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            phase.testActiveInitialCheckpoint.requireOperation(original, jdbc)
            val operation = TestActiveInitialCheckpointOperationV1(phase, jdbc, original)
            phase.testActiveInitialCheckpoint.retain(operation, jdbc)
            try { operation.run(); return operation }
            catch (problem: Throwable) { operation.discardDetached(); throw problem }
        }
    }
}

internal enum class TestActiveInitialCheckpointStepV1 { READ, ACQUIRE, RENEW, START_PASS, COMPLETE_PASS, SUCCESS }
