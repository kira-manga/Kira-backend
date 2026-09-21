package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDailyAdmission
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStateV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationEntryV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProgressV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSyntaxV1
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteRows
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.security.EpochSealFramesV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.TestTerminalRootsV1
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/**
 * Existing normal coordinator root, M/RC and epoch-fence lifecycle. Only fixed SQL lives here.
 * Existing publication/recovery locks precede counters/run; new rows are inserted afterwards.
 * The run delta was paid by WITNESS, so writing bounded source progress cannot spend it again.
 */
internal class TestInstallationManifestOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val original: TestRunInstallationManifestV1,
) {
    internal val path = original.path
    internal val step = original.step
    internal lateinit var databaseNow: Instant
        private set
    internal var source: TestInstallationManifestSourceV1.Observation? = null
        private set
    internal var chunk: TestInstallationManifestSourceV1.Chunk? = null
        private set
    internal var row: TestTerminalDurableRowV1? = null
        private set
    private lateinit var run: TestOrdinaryDrainRowsV1.Run
    private var stage = Stage.NEW
    private var counters: JdbcComplaintCapacityStore.LockedTestInstallationManifest? = null
    private var publication: TestInstallationManifestRowsV1.Publication? = null
    private var manifests = 0L
    private var spend = ComplaintCapacityVector.ZERO
    private var progressWrite: ByteArray? = null
    private val json = TestTerminalJsonV1(original.routing.journalConfiguration)

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = belongsTo(selected) && stage === Stage.COMPLETE && counters?.completedFor(this) == true
    internal fun requireReleased() { phase.testInstallationManifestBoundary.requireCommitted(this); requireConnectionFree() }

    internal fun releasedChunkEntries(): List<TestTerminalInstallationEntryV1> {
        requireReleased(); original.requireRunning()
        requireManifest(step === TestInstallationManifestStepV1.CHUNK && original.step === step)
        val selected = checkNotNull(chunk)
        return try { selected.entries() } finally { selected.close(); chunk = null }
    }

    private fun execute() {
        retained(Stage.NEW)
        try {
            stage = Stage.CONTROLS
            requireControls()
            if (step === TestInstallationManifestStepV1.CAPTURE) {
                val token = jdbc.query(TestInstallationManifestSqlV1.acquire, { value, _ -> value.getLong("lease_token") }, original.attemptId,
                    original.budget.remainingMillis(original.routing.journalConfiguration.declaration().limits.deadlines.scanMillis.toLong()),
                    original.scope, original.drain.attemptId, original.drain.leaseToken).single()
                original.retainLease(this, token)
            }
            requireLease()
            if (step === TestInstallationManifestStepV1.CHUNK || step === TestInstallationManifestStepV1.PREPARE) lockPublication()
            stage = Stage.COUNTERS_REQUESTED
            counters = JdbcComplaintCapacityStore(jdbc, original.registration.process.consumers.capacityPolicy.digestBytes()).lockForTestInstallationManifest(this)
            retained(Stage.COUNTERS_LOCKING)
            stage = Stage.BODY
            run = readRun()
            databaseNow = now()
            manifests = manifestCount(run)
            requirePaidRemainder(run, manifests)
            requireStrictSeal(run)
            when (step) {
                TestInstallationManifestStepV1.CAPTURE -> capture()
                TestInstallationManifestStepV1.CHUNK -> {
                    requireChunkSlot()
                    row = loadSidecar()
                    requirePublication(row)
                    chunk = readChunk()
                }
                TestInstallationManifestStepV1.PREPARE -> prepare()
                TestInstallationManifestStepV1.COMPLETE -> {
                    requireManifest(manifests == original.capturedSource().count.toLong())
                    requireRelation()
                    requireAppliedCut()
                    source = readSource()
                    original.capturedSource().requireSame(checkNotNull(source))
                    requireRelation()
                }
            }
            stage = Stage.TRANSFER
            checkNotNull(counters).settle(this)
            requireManifest(checkNotNull(counters).completedFor(this))
            val newProgress = progressWrite
            if (newProgress != null) {
                requireManifest(spend.isZero() && jdbc.update(TestOrdinaryDrainSqlV1.spendAndProgress, OwnerDeleteRows.array(run.unused),
                    newProgress, hex(Sha256.hex(newProgress)), original.scope, OwnerDeleteRows.array(run.unused), run.progressBytes, run.progressHash) == 1)
            } else if (!spend.isZero()) {
                requireManifest(jdbc.update(TestOrdinaryDrainSqlV1.spend, OwnerDeleteRows.array(run.unused - spend), original.scope, OwnerDeleteRows.array(run.unused)) == 1)
            }
            val after = readRun()
            requirePaidRemainder(after, manifests + if (spend.isZero()) 0L else 1L)
            requireManifest(after.unused == run.unused - spend && after.sealSetBytes.contentEquals(run.sealSetBytes) && after.sealSetHash.contentEquals(run.sealSetHash))
            if (newProgress == null) requireManifest(after.progressBytes.contentEquals(run.progressBytes) && after.progressHash.contentEquals(run.progressHash))
            else requireManifest(after.progressBytes.contentEquals(newProgress) && after.progressHash.contentEquals(hex(Sha256.hex(newProgress))))
            requireControls()
            requireLease()
            if (step === TestInstallationManifestStepV1.COMPLETE) {
                requireManifest(jdbc.update(TestInstallationManifestSqlV1.release, original.scope, original.attemptId, original.leaseToken) == 1)
            }
            retained(Stage.TRANSFER)
            stage = Stage.COMPLETE
        } finally {
            publication?.close()
            progressWrite?.fill(0)
            progressWrite = null
        }
    }

    private fun capture() {
        requireRelation()
        requireAppliedCut()
        source = readSource()
        if (checkNotNull(run.progress).installationReads().isEmpty()) {
            requireManifest(manifests == 0L)
            val progress = TestTerminalProgressV1.create(original.runContext, checkNotNull(run.progress).completedCuts(), checkNotNull(source).progress.installationReads())
            progressWrite = json.encodeProgress(progress)
        }
        requireRelation()
    }

    private fun prepare() {
        requireChunkSlot()
        row = loadSidecar()
        requirePublication(row)
        val candidate = original.candidate(this)
        val observed = readChunk()
        val bytes = candidate.canonicalBytes()
        try {
            // The private original owns the immutable codec-created bytes from the released CHUNK.
            // This fresh complete chunk must equal its captured raw/entry descriptor. Do not retain
            // a second decoded identity list or run the terminal codec while SQL is owned.
            val b = candidate.binding
            requireManifest(candidate.state === TestTerminalDurableStateV1.CANONICAL && observed.size == original.expectedChunk().count &&
                b.objectOrdinal == original.chunkIndex && b.epochStartInclusive == original.epoch && b.epochEndInclusive == original.epoch &&
                b.run == original.runContext && b.writerGeneration == original.writer && b.journalConfigurationSha256 == original.routing.journalConfiguration.sha256)
            if (row == null) {
                requireManifest(original.chunkIndex.toLong() == manifests && b.preparingFencingToken == original.leaseToken &&
                    b.createdAt >= run.sealedAt && b.createdAt <= now())
                original.requireRetentionFloor(b)
                requireManifest(jdbc.update(TestInstallationManifestSqlV1.insertPublication, b.objectId, original.scope, b.writerGeneration, b.epochEndInclusive,
                    observed.size, b.routingKeyId, b.objectKey, bytes, hex(candidate.canonicalSha256), Timestamp.from(b.createdAt)) == 1)
                requireManifest(jdbc.update(TestInstallationManifestSqlV1.insertRecovery, b.objectId, original.scope, b.objectId, Timestamp.from(b.createdAt)) == 1)
                requireManifest(jdbc.update(TestInstallationManifestSqlV1.insertSidecar, b.operationToken, original.scope, b.objectOrdinal, b.objectId, b.objectKey,
                    b.routingKeyId, b.writerGeneration, b.epochStartInclusive, b.epochEndInclusive, b.preparingFencingToken, b.run.activationCatalogGeneration,
                    hex(b.run.activationCatalogSha256), hex(b.run.configurationSha256), hex(b.journalConfigurationSha256), hex(b.run.terminalEncodingSha256),
                    b.objectId, bytes, hex(candidate.canonicalSha256), Timestamp.from(b.retentionFloor), Timestamp.from(b.createdAt)) == 1)
                spend = MANIFEST_ACTUAL
                // All three newly inserted rows are already owned by this transaction. Reload them,
                // not a row count or proposed bytes, before the same transaction pays their slice.
                lockPublication()
                row = loadSidecar()
                requirePublication(row)
            }
            original.requireSameCanonical(candidate, checkNotNull(row))
            requireManifest(manifestCount(run) == manifests + if (spend.isZero()) 0L else 1L)
        } finally { bytes.fill(0); observed.close() }
    }

    private fun requireChunkSlot() {
        requireManifest(checkNotNull(run.progress).installationReads().size == 2 && original.chunkIndex in 0 until original.capturedSource().count &&
            original.chunkIndex.toLong() <= manifests)
    }
    private fun lockPublication() {
        original.requireRunning()
        requireManifest(publication == null)
        val ids = jdbc.query(TestInstallationManifestSqlV1.discover, { value, _ -> checkNotNull(value.getString("object_id")) }, original.scope, original.chunkIndex)
        requireManifest(ids.size <= 1)
        ids.singleOrNull()?.let { id ->
            publication = jdbc.query(TestInstallationManifestSqlV1.publication, { value, _ -> TestInstallationManifestRowsV1.Publication(value, original) }, id).single()
            jdbc.query(TestInstallationManifestSqlV1.recovery, { value, _ -> TestInstallationManifestRowsV1.recovery(value, original, checkNotNull(publication)) }, id).single()
        }
    }
    private fun requirePublication(sidecar: TestTerminalDurableRowV1?) {
        requireManifest((sidecar == null) == (publication == null) && (sidecar == null) == (original.chunkIndex.toLong() == manifests && spend.isZero()))
        sidecar?.let { checkNotNull(publication).requireSidecar(it, original.expectedChunk().count) }
    }
    private fun loadSidecar(): TestTerminalDurableRowV1? {
        val observedAt = now()
        return jdbc.query(TestInstallationManifestSqlV1.sidecar,
            { value, _ -> original.ownRow(TestInstallationManifestRowsV1.manifest(value, original, run.sealedAt, observedAt)) }, original.scope, original.chunkIndex).also {
            requireManifest(it.size <= 1)
        }.singleOrNull()
    }

    private fun readSource(): TestInstallationManifestSourceV1.Observation {
        val source = TestInstallationManifestSourceV1(original.routing.journalConfiguration, original.runContext, binding(), run.installationLimit,
            run.enrolledCount, checkNotNull(run.progress).installationReads().firstOrNull())
        repeat(2) {
            source.begin(binding(), now().epochSecond)
            requireCompleteCredentials()
            var after: UUID? = null
            while (true) {
                original.requireRunning()
                val page = jdbc.query(TestInstallationSourceSqlV1.page, { value, _ -> TestInstallationSourceV1.Row.read(value) }, original.scope, after, after)
                if (page.isEmpty()) break
                page.forEach { value -> original.requireRunning(); source.entry(value); after = UUID.fromString(value.id) }
            }
            requireCompleteCredentials()
            source.end(binding(), now().epochSecond)
        }
        return source.finish().also { original.requireRunning() }
    }
    private fun readChunk(): TestInstallationManifestSourceV1.Chunk {
        binding(); requireCompleteCredentials()
        val expected = original.expectedChunk()
        val chunk = TestInstallationManifestSourceV1.Chunk(original.runContext, expected.ordinal, expected.afterId, original.drain.maximumFramedBytes)
        var after: UUID? = expected.afterId?.let(UUID::fromString)
        while (true) {
            original.requireRunning()
            val page = jdbc.query(TestInstallationManifestSqlV1.chunkPage, { value, _ -> TestInstallationSourceV1.Row.read(value) },
                original.scope, after, after, UUID.fromString(expected.lastId))
            if (page.isEmpty()) break
            page.forEach { value -> original.requireRunning(); chunk.entry(value); after = UUID.fromString(value.id) }
        }
        requireManifest(chunk.descriptor() == expected)
        requireCompleteCredentials(); binding()
        return chunk
    }
    private fun binding(): TestInstallationSourceV1.Binding {
        requireLease()
        return jdbc.query(TestRunSealingSqlV1.readScopeControl, { value, _ ->
            requireManifest(TestOrdinaryDrainRowsV1.boolean(value, "valid") && value.getLong("lease_token") == original.leaseToken)
            TestInstallationSourceV1.Binding(value.getObject("database_identity", UUID::class.java).toString(),
                value.getObject("restore_identity", UUID::class.java).toString(), value.getLong("desired_generation"), value.getLong("lease_token"))
        }, *original.registration.sealingControlArguments()).single()
    }
    private fun requireCompleteCredentials() {
        requireManifest(jdbc.query(TestInstallationSourceSqlV1.credentialsComplete, { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") }, original.scope).single())
    }

    private fun readRun(): TestOrdinaryDrainRowsV1.Run {
        original.requireRunning()
        val run = jdbc.query(TestOrdinaryDrainSqlV1.run, { value, _ -> TestOrdinaryDrainRowsV1.Run(value, original.drain) }, *original.registration.sealingRunArguments()).single()
        requireManifest(run.progress?.context() == original.runContext && run.progress?.completedCuts() == listOf(original.ordinaryCut) &&
            run.ordinaryEpoch == original.control.cutoff && run.reservedTerminalEpoch == original.epoch)
        val reads = checkNotNull(run.progress).installationReads()
        reads.forEach { read ->
            val declaration = original.routing.journalConfiguration.declaration()
            requireManifest(read.databaseIdentity == declaration.writer.databaseIdentity && read.restoreIdentity == declaration.writer.restoreIdentity &&
                read.desiredGeneration == original.registration.process.desiredGeneration && read.fencingToken in (original.drain.leaseToken + 1)..original.leaseToken &&
                read.sourceHighWater.enrolledCount == run.enrolledCount && read.installationCount == run.enrolledCount &&
                read.completedAtEpochSecond <= now().epochSecond && read.startedAtEpochSecond >= run.sealedAt.epochSecond)
        }
        requireManifest(jdbc.query(TestRunSealingSqlV1.readAudit, { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") },
            *original.registration.sealingAuditArguments(run.sealedAt)).single())
        return run
    }
    private fun requireStrictSeal(run: TestOrdinaryDrainRowsV1.Run) {
        val set = json.sealSet(checkNotNull(run.sealSetBytes))
        requireManifest(set.dataScopeId == original.runContext.dataScopeId && set.activationCatalogGeneration == original.runContext.activationCatalogGeneration &&
            set.activationCatalogSha256 == original.runContext.activationCatalogSha256 && set.records() == listOf(original.ordinarySeal))
        val root = TestTerminalRootsV1(original.routing.journalConfiguration, run.installationLimit, run.plan.manifestChunkCount.toInt()).preTerminalSeals(set)
        requireManifest(run.sealCount == root.count && run.sealRoot.contentEquals(hex(root.sha256)))
        val observedAt = now()
        jdbc.query(TestInstallationManifestSqlV1.ordinarySidecar, { value, _ -> TestInstallationManifestRowsV1.requireOrdinarySidecar(value, original, observedAt) },
            original.scope, original.routing.journalConfiguration.sealTerminalPrefix + "%").single()
    }
    private fun manifestCount(run: TestOrdinaryDrainRowsV1.Run): Long = jdbc.query(TestInstallationManifestSqlV1.counts, { value, _ ->
        val count = value.getLong("manifests")
        requireManifest(count in 0..TestTerminalSyntaxV1.chunkCount(run.enrolledCount).toLong() && value.getLong("other_intents") == 1L &&
            value.getLong("first_ordinal") == 0L && value.getLong("last_ordinal") == count - 1L && value.getLong("publications") == count &&
            value.getLong("reservations") == count && value.getLong("scan_runs") == 0L && value.getLong("scan_entries") == 0L)
        count
    }, original.scope).single()
    private fun spent(run: TestOrdinaryDrainRowsV1.Run, count: Long): ComplaintCapacityVector =
        TestTerminalCapacityChargesV1.INSTALLATION_SHARE.scaled(run.enrolledCount) + TestTerminalCapacityChargesV1.AUDIT +
            TestTerminalCapacityChargesV1.TERMINAL_RUN_DELTA + TestTerminalCapacityChargesV1.SIDECAR + MANIFEST_ACTUAL.scaled(count)
    private fun requirePaidRemainder(run: TestOrdinaryDrainRowsV1.Run, count: Long) {
        val paid = spent(run, count)
        requireManifest(run.plan.manifestFuturePromise.isZero() && paid.fitsWithin(run.reserve) && run.unused == run.reserve - paid)
    }
    private fun requireRelation() {
        original.requireRunning()
        requireManifest(jdbc.query(TestInstallationManifestSqlV1.relation, { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") }, original.scope,
            original.routing.journalConfiguration.ordinaryPrefix + "%", original.routing.journalConfiguration.sealTerminalPrefix + "%",
            original.writer, original.control.cutoff, original.epoch,
            original.routing.journalConfiguration.ownerDeleteAll, original.routing.journalConfiguration.registeredAdminDelete, original.routing.journalConfiguration.registeredAdminBatchDelete).single())
    }
    private fun requireAppliedCut() {
        TestInstallationManifestFamilyJoinV1.requireClosed(jdbc, this, run)
        val expected = original.ordinaryCut.denial.firstInventory
        val hash = MessageDigest.getInstance("SHA-256")
        var framed = EpochSealFramesV1.update(hash, listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", original.writer,
            original.routing.journalConfiguration.ordinaryPrefix, "TEST", original.scope.toString(), "1", original.control.cutoff.toString(), expected.versionCount.toString()))
        var count = 0L
        var after: Pair<String, String>? = null
        while (true) {
            original.requireRunning()
            val page = TestInstallationManifestAppliedPageV1.page(jdbc, this, after)
            if (page.isEmpty()) break
            page.forEach { value ->
                requireManifest(count < expected.versionCount && after?.let { TestOrdinaryDrainRowsV1.compare(it, value.locator) < 0 } != false)
                framed = Math.addExact(framed, EpochSealFramesV1.update(hash, listOf(value.key, value.version, value.ciphertext)))
                requireManifest(framed <= original.drain.maximumFramedBytes)
                count++; after = value.locator
            }
        }
        requireManifest(count == expected.versionCount && framed == original.ordinaryCut.framedByteCount && HexFormat.of().formatHex(hash.digest()) == expected.sha256)
    }
    internal fun requireAppliedPage(selected: JdbcTemplate) {
        retained(Stage.BODY, selected)
        requireManifest(step === TestInstallationManifestStepV1.CAPTURE || step === TestInstallationManifestStepV1.COMPLETE)
    }
    private fun requireControls() {
        original.requireRunning()
        for (sql in listOf(TestRunSealingSqlV1.lockGlobalControl, TestRunSealingSqlV1.lockScopeControl)) {
            requireManifest(jdbc.query(sql, { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") }, *original.registration.sealingControlArguments()).single())
        }
        jdbc.query(TestOrdinaryDrainSqlV1.control, { value, _ -> TestOrdinaryDrainRowsV1.Control(value) }, original.scope).single().requireSame(original.control)
    }
    private fun requireLease() {
        original.requireRunning()
        requireManifest(jdbc.query(TestOrdinarySealSqlV1.lease, { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") },
            original.attemptId, original.leaseToken, original.scope).single())
        requireManifest(now().epochSecond < original.drain.manifestDenialRetainUntil())
    }
    private fun now(): Instant = checkNotNull(jdbc.queryForObject("SELECT clock_timestamp()", { value, _ -> value.getTimestamp(1).toInstant() })).also {
        requireManifest(it.epochSecond in 0..253_402_300_799L)
    }

    internal fun beginCounterLock(selected: JdbcTemplate) { retained(Stage.COUNTERS_REQUESTED, selected); stage = Stage.COUNTERS_LOCKING }
    internal fun requireCounterTransfer(locked: JdbcComplaintCapacityStore.LockedTestInstallationManifest, selected: JdbcTemplate) {
        retained(Stage.TRANSFER, selected); requireManifest(counters === locked)
    }
    internal fun settleLockedLedger(selected: JdbcTemplate, ledger: ComplaintCapacityLedger, daily: ComplaintDailyAdmission, expectedDigest: ByteArray): ComplaintCapacityLedger {
        retained(Stage.TRANSFER, selected)
        ledger.configuration.requireMatching(expectedDigest)
        val policy = original.registration.process.consumers.capacityPolicy
        requireManifest(ledger.balance.hardLimit == policy.hardLimit && ledger.balance.creationLimit == policy.creationLimit &&
            daily.dailyLimit == policy.dailyEnrollmentLimit && run.unused.fitsWithin(ledger.balance.testReserved) &&
            spent(run, manifests).fitsWithin(ledger.balance.actual) && spend.fitsWithin(run.unused))
        return if (spend.isZero()) ledger else ledger.spendTestReserve(expectedDigest, spend, ComplaintCapacityVector.ZERO)
    }
    internal fun failed(problem: Throwable): Nothing {
        original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled()
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }
    private fun retained(expected: Stage, selected: JdbcTemplate = jdbc) {
        phase.testInstallationManifestBoundary.requireRetained(this, selected)
        requireManifest(stage === expected && step === original.step && selected === jdbc)
    }
    private enum class Stage { NEW, CONTROLS, COUNTERS_REQUESTED, COUNTERS_LOCKING, BODY, TRANSFER, COMPLETE }
    companion object {
        internal val MANIFEST_ACTUAL: ComplaintCapacityVector = TestTerminalCapacityChargesV1.PUBLICATION_WITH_RESERVATION + TestTerminalCapacityChargesV1.SIDECAR
        internal fun execute(jdbc: JdbcTemplate, original: TestRunInstallationManifestV1): TestInstallationManifestOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.testInstallationManifestBoundary.requireOperation(original, jdbc)
                return TestInstallationManifestOperationV1(phase, jdbc, original).also { phase.testInstallationManifestBoundary.retain(it, jdbc); it.execute() }
            } catch (problem: Throwable) { original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled(); throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED) }
        }
        private fun hex(value: String): ByteArray = HexFormat.of().parseHex(value)
    }
}
