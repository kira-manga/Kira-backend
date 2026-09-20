package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationSqlV1
import me.manga.kira.backend.complaint.infrastructure.catalog.TRY_CATALOG_LOCK
import me.manga.kira.backend.complaint.infrastructure.catalog.requiredTestActivationBoolean
import me.manga.kira.backend.complaint.infrastructure.catalog.requiredTestActivationLong
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainRowsV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainSqlV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingSqlV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.HexFormat
import java.util.UUID

/**
 * Named read/lock-only admission. M then exclusive E then global/scope/catalog/history/counters/run.
 * No counters/run/lease are repaid, reset or mutated. Current progress is NOT a denial proof; the
 * registered downstream drain must independently readmit denial, take a new fence and recheck its
 * actual native/SQL obligations. No caller supplies an installation limit or trusted SQL outcome.
 */
internal class TestNamespaceRecoveryRegistrationOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val original: ComplaintTestNamespaceRecoveryRegistrationAttemptV1,
) {
    private val process = original.process
    private val journal = process.consumers.journalConfiguration
    private val scope = journal.scope.id
    private val writer = UUID.fromString(journal.declaration().writer.generationId)
    private val catalogWriter = UUID.fromString(process.catalogActivation.initialWriterRegistry().catalogWriter.generationId)
    private val maximumGenerations = process.catalogReadback.chainPolicy.limits.maximumGenerations
    private var historyLocked = false
    private var countersClaimed = false
    private var completed = false
    private var result: TestNamespaceRecoveryRegistrationSnapshotV1? = null

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = belongsTo(selected) && completed
    internal fun requireReleased() { phase.testNamespaceRecoveryRegistration.requireCommitted(this); requireConnectionFree() }
    internal val snapshot: TestNamespaceRecoveryRegistrationSnapshotV1 get() { requireReleased(); return checkNotNull(result) }

    private fun run() {
        requireRetained()
        val global = control(UUID(0L, 0L))
        val scoped = control(scope)
        requireRegistration(scoped.generation == global.generation && scoped.hash.contentEquals(global.hash))
        requireRegistration(jdbc.query(TRY_CATALOG_LOCK, { row, _ -> row.requiredTestActivationBoolean("locked") }).single())
        requireRetained()
        val tail = jdbc.query(TestNamespaceRecoveryRegistrationSqlV1.tail, { row, _ -> TestNamespaceRecoveryRegistrationTailV1(row, scope) },
            scope, global.generation, global.hash, catalogWriter).single()
        original.requireCapturedGate(tail)
        val history = checkNotNull(jdbc.query(CatalogTestRunActivationSqlV1.lockRecoveryRegistrationHistory, ResultSetExtractor { rows ->
            CatalogTestRunActivationHistoryV1.readRecoveryRegistration(rows, tail.generation, maximumGenerations)
        }, tail.token, scope, tail.generation, tail.envelopeHash, maximumGenerations + 1))
        requireRetained()
        historyLocked = true
        val counters = JdbcComplaintCapacityStore(jdbc, process.consumers.capacityPolicy.digestBytes()).lockForTestNamespaceRecoveryRegistration(this)
        val policy = process.consumers.capacityPolicy
        requireRegistration(counters.balance.hardLimit == policy.hardLimit && counters.balance.creationLimit == policy.creationLimit &&
            counters.daily.dailyLimit == policy.dailyEnrollmentLimit)
        requireRetained()
        val limit = jdbc.query(TestNamespaceRecoveryRegistrationSqlV1.lockRunIdentity, { row, _ ->
            requireRegistration(row.getObject("data_scope_id", UUID::class.java) == scope)
            row.requiredTestActivationLong("installation_limit")
        }).single()
        val binding = TestNamespaceRecoveryRegistrationBindingV1(process, tail, limit)
        val captured = jdbc.query(TestOrdinaryDrainSqlV1.control, { row, _ -> TestOrdinaryDrainRowsV1.Control(row) }, scope).single()
        val run = jdbc.query(TestOrdinaryDrainSqlV1.run, { row, _ ->
            TestNamespaceRecoveryRegistrationRunV1(row, binding, process, captured, scoped.leaseToken)
        }, *binding.runArguments()).single()
        requireRetained()
        requireRegistration(jdbc.query(TestRunSealingSqlV1.readAudit, { row, _ -> row.requiredTestActivationBoolean("valid") },
            scope, tail.generation, Timestamp.from(run.sealedAt)).single())
        requireRegistration(jdbc.query(TestOrdinaryDrainSqlV1.supported, { row, _ -> row.requiredTestActivationBoolean("valid") },
            scope, journal.ordinaryPrefix + "%", journal.sealTerminalPrefix + "%", writer, journal.ownerDeleteAll, journal.registeredAdminDelete).single())
        requireRetained()
        var actualEntries = 0L
        val scans = jdbc.query(TestNamespaceRecoveryRegistrationSqlV1.scans, { row, _ ->
            actualEntries = Math.addExact(actualEntries, requireScan(row, run, scoped.leaseToken))
            boundedRecoveryBytes(row, "fingerprint", 32, 32)
        }, scope)
        requireRegistration(scans.size <= 2 && (scans.isEmpty() || captured.sequence == 1L))
        val charge = jdbc.query(TestOrdinaryDrainSqlV1.pool, { row, _ ->
            requireRegistration(row.requiredTestActivationLong("runs") == scans.size.toLong() && row.requiredTestActivationLong("entries") == actualEntries)
            binding.plan.scanPool.chargeFor(scans.size.toLong(), actualEntries)
        }, scope, scope).single()
        requireRetained()
        val sidecars = jdbc.query(TestNamespaceRecoveryRegistrationSqlV1.sidecars, { row, _ ->
            requireRegistration(run.progress != null && row.requiredTestActivationBoolean("valid") &&
                row.getObject("data_scope_id", UUID::class.java) == scope && row.getObject("writer_generation", UUID::class.java) == writer &&
                row.getString("object_kind") == "EPOCH_SEAL" && row.getInt("object_ordinal") == 0 &&
                row.getObject("operation_token", UUID::class.java) == captured.captureId && row.getLong("epoch_start") == 1L &&
                row.getLong("epoch_end") == captured.cutoff && row.getLong("preparing_fencing_token") in 1..scoped.leaseToken &&
                row.getLong("activation_catalog_generation") == tail.generation && row.getBytes("activation_catalog_hash").contentEquals(tail.envelopeHash) &&
                row.getBytes("configuration_hash").contentEquals(process.configurationHashBytes()) &&
                TestOrdinaryDrainRowsV1.hash(row, "terminal_encoding_hash") == TestTerminalProfileV1.encodingSha256 &&
                TestOrdinaryDrainRowsV1.hash(row, "journal_configuration_hash") == journal.sha256)
            boundedRecoveryBytes(row, "fingerprint", 32, 32)
        }, scope, journal.sealTerminalPrefix + "%")
        requireRegistration(sidecars.size <= 1)
        run.requireRemainder(charge, sidecars.size.toLong())
        requireRegistration(counters.balance.actual[ComplaintCapacityCounter.TEST_RUNS] == 1L && counters.balance.testReserved == run.unused &&
            (charge + TestTerminalCapacityChargesV1.SIDECAR.scaled(sidecars.size.toLong())).fitsWithin(counters.balance.actual))
        val fingerprint = jdbc.query(TestNamespaceRecoveryRegistrationSqlV1.runFingerprint,
            { row, _ -> boundedRecoveryBytes(row, "fingerprint", 32, 32) }, scope).single()
        // Same locks, no backwards acquisition: compare exact current control facts again before returning.
        control(UUID(0L, 0L)).requireSame(global)
        control(scope).requireSame(scoped)
        requireRetained()
        result = TestNamespaceRecoveryRegistrationSnapshotV1(tail, binding, history, global, scoped, counters, fingerprint, scans, sidecars)
        completed = true
    }

    private fun control(selected: UUID): TestNamespaceRecoveryRegistrationControlV1 {
        requireRetained()
        return jdbc.query(TestNamespaceRecoveryRegistrationSqlV1.lockControl, { row, _ -> TestNamespaceRecoveryRegistrationControlV1(row) },
            scope, process.desiredGeneration, process.implementationSchema, process.configurationHashBytes(), process.databaseIdentity, process.restoreIdentity,
            writer, catalogWriter, HexFormat.of().parseHex(process.catalogReadback.currentTrustBundleSha256), maximumGenerations.toLong(), selected)
            .single().also { requireRegistration(it.scope == selected); requireRetained() }
    }

    /** Existing paid scan pool only, including abandoned/partially recycled staging. No row count is an inventory witness. */
    private fun requireScan(row: ResultSet, run: TestNamespaceRecoveryRegistrationRunV1, leaseToken: Long): Long {
        requireRetained()
        val id = checkNotNull(row.getObject("scan_id", UUID::class.java))
        val pass = row.getInt("pass")
        val count = row.requiredTestActivationLong("entry_count")
        val actual = row.requiredTestActivationLong("actual_entries")
        val framed = row.requiredTestActivationLong("entry_bytes")
        val fence = row.requiredTestActivationLong("fencing_token")
        val state = row.getString("state")
        val started = checkNotNull(row.getTimestamp("started_at")).toInstant()
        val finished = row.getTimestamp("finished_at")?.toInstant()
        val root = row.getBytes("manifest_hash")
        val capacity = journal.declaration().limits.capacity
        requireRegistration(id.version() == 4 && id.variant() == 2 && pass in 1..2 && row.getObject("data_scope_id", UUID::class.java) == scope &&
            row.requiredTestActivationBoolean("test_only") && row.getObject("restore_identity", UUID::class.java) == process.restoreIdentity &&
            row.getLong("desired_generation") == process.desiredGeneration && row.getObject("writer_generation", UUID::class.java) == writer &&
            fence in 1..leaseToken && row.getLong("cutoff_epoch") == run.control.cutoff &&
            row.getLong("maximum_entries") == capacity.maximumRetainedVersions && row.getLong("maximum_bytes") == capacity.maximumScanStagingBytes &&
            count in 0..capacity.maximumRetainedVersions && actual in 0..count && framed in 0..capacity.maximumScanStagingBytes &&
            started.epochSecond >= 0 && (finished == null || !finished.isBefore(started)))
        requireRegistration((state == "SCANNING" && root == null && finished == null && actual == count) ||
            (state == "COMPLETE" && root?.size == 32 && finished != null) || (state == "ABANDONED" && root == null && finished != null))
        run.progress?.completedCuts()?.single()?.let { cut ->
            val witness = if (pass == 1) cut.denial.firstInventory else cut.denial.secondInventory
            requireRegistration(id.toString() == cut.scanId && state == "COMPLETE" && fence == cut.fencingToken &&
                count == witness.versionCount && framed == cut.framedByteCount && root != null && HexFormat.of().formatHex(root) == witness.sha256 &&
                started.epochSecond == witness.startedAtEpochSecond && finished?.epochSecond == witness.completedAtEpochSecond)
        }
        return actual
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        requireRetained(); requireRegistration(selected === jdbc && historyLocked && !countersClaimed && !completed); countersClaimed = true
    }
    internal fun requireCounterRead(selected: JdbcTemplate) {
        requireRetained(); requireRegistration(selected === jdbc && historyLocked && countersClaimed && !completed)
    }
    private fun requireRetained() = phase.testNamespaceRecoveryRegistration.requireRetained(this, jdbc)
    override fun toString(): String = "TestNamespaceRecoveryRegistrationOperationV1(read-only-current-SEALED-obligations,redacted)"

    companion object {
        internal fun execute(jdbc: JdbcTemplate, original: ComplaintTestNamespaceRecoveryRegistrationAttemptV1): TestNamespaceRecoveryRegistrationOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.testNamespaceRecoveryRegistration.requireOperation(original, jdbc)
                val operation = TestNamespaceRecoveryRegistrationOperationV1(phase, jdbc, original)
                phase.testNamespaceRecoveryRegistration.retain(operation, jdbc)
                operation.run()
                return operation
            } catch (problem: Throwable) {
                original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled()
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
    }
}
