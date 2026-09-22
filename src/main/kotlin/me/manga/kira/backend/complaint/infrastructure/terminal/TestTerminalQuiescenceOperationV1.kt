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
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCompletedCutV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialCutV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialPrefixV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStateV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInventoryWitnessV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProgressV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealSetV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSyntaxV1
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteRows
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.security.TestPostTerminalInventoryFoldV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.TestTerminalRootsV1
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** One fenced phase: current controls, sorted P/L, counters, SEALED run, immutable lineage, paid pair. */
internal class TestTerminalQuiescenceOperationV1 private constructor(
    private val phase: PersistencePhaseContext, private val jdbc: JdbcTemplate, internal val original: TestRunTerminalQuiescenceV1,
) {
    internal val path = original.path
    internal val step = original.step
    internal var progress: TestTerminalProgressV1? = null
        private set
    internal var summary: TestTerminalQuiescenceRowsV1.Summary? = null
        private set
    internal var recycled = ComplaintCapacityVector.ZERO
        private set
    private var row: TestTerminalDurableRowV1? = null
    private lateinit var run: TestOrdinaryDrainRowsV1.Run
    private var stage = Stage.NEW
    private var counters: JdbcComplaintCapacityStore.LockedTestTerminalQuiescence? = null
    private var manifests = 0L
    private var publicationsLocked = false
    private var scans: List<TestTerminalQuiescenceRowsV1.Scan> = emptyList()
    private var initialScanCharge = ComplaintCapacityVector.ZERO
    private var spend = ComplaintCapacityVector.ZERO
    private var progressWrite: ByteArray? = null
    private val json = TestTerminalJsonV1(original.routing.journalConfiguration)
    private val slots = (0 until original.manifest.capturedSource().count).map { index ->
        original.manifest.authenticatedChunk(index).objectKey to index
    }.plus(original.purge.authenticatedFactsForSeal().objectRef.objectKey to -1).sortedBy { it.first }

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = belongsTo(selected) && stage === Stage.COMPLETE && counters?.completedFor(this) == true
    internal fun requireReleased() { phase.testTerminalQuiescenceBoundary.requireCommitted(this); requireConnectionFree() }
    internal fun takeRow(): TestTerminalDurableRowV1 { requireReleased(); return checkNotNull(row).also { row = null } }
    internal fun discardRow() { row?.close(); row = null }

    private fun execute() {
        retained(Stage.NEW); stage = Stage.CONTROLS
        requireControls()
        if (step === TestTerminalQuiescenceStepV1.CAPTURE) {
            val token = jdbc.query(TestOrdinarySealSqlV1.acquire, { value, _ -> value.getLong("lease_token") }, original.attemptId,
                original.budget.remainingMillis(original.routing.journalConfiguration.declaration().limits.deadlines.scanMillis.toLong()), original.scope).single()
            original.retainLease(this, token)
        }
        requireLease(); lockPublications()
        stage = Stage.COUNTERS_REQUESTED
        counters = JdbcComplaintCapacityStore(jdbc, original.registration.process.consumers.capacityPolicy.digestBytes()).lockForTestTerminalQuiescence(this)
        retained(Stage.COUNTERS_LOCKING); stage = Stage.BODY
        run = readRun(); requireCounts(run)
        scans = readScans(); initialScanCharge = scanCharge()
        requirePaidRemainder(run, initialScanCharge)
        requireRelation(); requireSealSet(run); requireAllSidecars()
        requireManifestMembership(readSource()) // Two actual fresh source passes; historical witnesses are NOT rewritten.
        when (step) {
            TestTerminalQuiescenceStepV1.CAPTURE -> {
                requireQuiescence(scans.isEmpty() && initialScanCharge.isZero())
                progress = checkNotNull(run.progress)
            }
            TestTerminalQuiescenceStepV1.BEGIN_PASS -> beginPass()
            TestTerminalQuiescenceStepV1.EXPECTED -> row = loadSidecar(original.selectedTarget())
            TestTerminalQuiescenceStepV1.APPEND -> append()
            TestTerminalQuiescenceStepV1.COMPLETE_PASS -> completePass()
            TestTerminalQuiescenceStepV1.WITNESS -> witness()
            TestTerminalQuiescenceStepV1.RECYCLE -> recycle()
            TestTerminalQuiescenceStepV1.COMPLETE -> requireQuiescence(scans.isEmpty() && initialScanCharge.isZero())
            else -> throw TestTerminalQuiescenceExceptionV1()
        }
        requireRelation(); stage = Stage.TRANSFER
        checkNotNull(counters).settle(this)
        requireQuiescence(checkNotNull(counters).completedFor(this))
        val unused = run.unused - spend + recycled
        if (progressWrite != null) {
            val bytes = checkNotNull(progressWrite)
            try { requireQuiescence(jdbc.update(TestOrdinaryDrainSqlV1.spendAndProgress, OwnerDeleteRows.array(unused), bytes, hex(Sha256.hex(bytes)),
                original.scope, OwnerDeleteRows.array(run.unused), run.progressBytes, run.progressHash) == 1) }
            finally { bytes.fill(0); progressWrite = null }
        } else if (!spend.isZero() || !recycled.isZero()) {
            requireQuiescence(jdbc.update(TestOrdinaryDrainSqlV1.spend, OwnerDeleteRows.array(unused), original.scope, OwnerDeleteRows.array(run.unused)) == 1)
        }
        val after = readRun(progress)
        requireCounts(after)
        requirePaidRemainder(after, scanCharge())
        requireQuiescence(after.unused == unused && after.reserve == run.reserve && after.sealSetBytes.contentEquals(run.sealSetBytes) &&
            after.sealSetHash.contentEquals(run.sealSetHash) && after.sealRoot.contentEquals(run.sealRoot) && after.sealCount == run.sealCount)
        if (step !== TestTerminalQuiescenceStepV1.WITNESS) requireQuiescence(after.progressBytes.contentEquals(run.progressBytes) && after.progressHash.contentEquals(run.progressHash))
        requireControls(); requireLease()
        if (step === TestTerminalQuiescenceStepV1.COMPLETE) requireQuiescence(jdbc.update(TestInstallationManifestSqlV1.release,
            original.scope, original.attemptId, original.leaseToken) == 1)
        retained(Stage.TRANSFER); stage = Stage.COMPLETE
    }

    private fun lockPublications() {
        retained(Stage.CONTROLS)
        requireQuiescence(!publicationsLocked && slots.map { it.first }.distinct().size == slots.size)
        for ((key, index) in slots) {
            original.requireRunning()
            if (index == -1) {
                val facts = original.purge.authenticatedFactsForSeal()
                jdbc.query(TestRunPurgeSqlV1.publication, { value, _ -> TestRunPurgeRowsV1.Publication(value, original.purge) }, facts.id).single().use { actual ->
                    actual.requireVerifiedReference(facts, 0, now()); requireQuiescence(actual.key == key)
                    jdbc.query(TestRunPurgeSqlV1.recovery, { value, _ -> TestRunPurgeRowsV1.recovery(value, original.purge, actual) },
                        OwnerDeleteRows.array(TestRunPurgeOperationV1.FUTURE), facts.id).single()
                }
            } else {
                val facts = original.manifest.authenticatedFacts(index)
                jdbc.query(TestInstallationManifestPublicationSqlV1.publication, { value, _ ->
                    TestInstallationManifestPublicationRowsV1.Publication(value, original.manifest)
                }, facts.id).single().use { actual ->
                    actual.requireVerifiedReference(facts, original.manifest.capturedSource().chunk(index).count, now()); requireQuiescence(actual.key == key)
                    jdbc.query(TestInstallationManifestSqlV1.recovery, { value, _ -> TestInstallationManifestPublicationRowsV1.recovery(value, original.manifest, actual) }, facts.id).single()
                }
            }
        }
        publicationsLocked = true
    }
    private fun readSource(): TestInstallationManifestSourceV1.Observation {
        val source = TestInstallationManifestSourceV1(original.routing.journalConfiguration, original.runContext, binding(), run.installationLimit,
            run.enrolledCount, original.manifest.capturedSource().progress.installationReads().first())
        repeat(2) {
            source.begin(binding(), now().epochSecond); requireCompleteCredentials()
            var after: UUID? = null
            while (true) {
                retained(Stage.BODY)
                val page = jdbc.query(TestInstallationSourceSqlV1.page, { value, _ -> TestInstallationSourceV1.Row.read(value) }, original.scope, after, after)
                if (page.isEmpty()) break
                page.forEach { value -> original.requireRunning(); source.entry(value); after = UUID.fromString(value.id) }
            }
            requireCompleteCredentials(); source.end(binding(), now().epochSecond)
        }
        return source.finish().also { retained(Stage.BODY) }
    }
    private fun requireManifestMembership(source: TestInstallationManifestSourceV1.Observation) {
        val predecessor = original.manifest.capturedSource()
        val summary = original.manifest.authenticatedSummary()
        requireQuiescence(source.count == predecessor.count && summary.chunkCount == source.count)
        repeat(source.count) { requireQuiescence(source.chunk(it) == predecessor.chunk(it)) }
        val read = source.progress.installationReads().first()
        requireQuiescence(read.installationsSha256 == summary.installationsSha256 && read.installationCount == summary.installationCount &&
            read.retiredCount == summary.retiredCount && read.deletedCount == summary.deletedCount)
    }
    private fun binding(): TestInstallationSourceV1.Binding {
        requireLease()
        return jdbc.query(TestRunSealingSqlV1.readScopeControl, { value, _ ->
            requireQuiescence(TestOrdinaryDrainRowsV1.boolean(value, "valid") && value.getLong("lease_token") == original.leaseToken)
            TestInstallationSourceV1.Binding(value.getObject("database_identity", UUID::class.java).toString(),
                value.getObject("restore_identity", UUID::class.java).toString(), value.getLong("desired_generation"), value.getLong("lease_token"))
        }, *original.registration.sealingControlArguments()).single()
    }
    private fun requireCompleteCredentials() {
        requireQuiescence(jdbc.query(TestInstallationSourceSqlV1.credentialsComplete, { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") }, original.scope).single())
    }

    private fun readRun(expected: TestTerminalProgressV1? = null): TestOrdinaryDrainRowsV1.Run {
        original.requireRunning()
        val sql = if (original.control.initialHistory == null) TestTerminalQuiescenceSqlV1.run else TestTerminalQuiescenceSqlV1.runWithActiveHistory
        val current = jdbc.query(sql, { value, _ -> TestOrdinaryDrainRowsV1.Run(value, original.drain) },
            *original.registration.sealingRunArguments()).single()
        val observed = checkNotNull(current.progress)
        requireQuiescence(observed.context() == original.runContext && current.ordinaryEpoch == original.control.cutoff && current.reservedTerminalEpoch == original.epoch)
        val required = expected ?: when (step) {
            TestTerminalQuiescenceStepV1.CAPTURE -> null
            TestTerminalQuiescenceStepV1.RECYCLE, TestTerminalQuiescenceStepV1.COMPLETE -> original.paidProgress()
            else -> original.predecessorProgress()
        }
        if (required == null) requireQuiescence(observed.completedCuts() == listOf(original.ordinaryCut))
        else requireQuiescence(observed.context() == required.context() && observed.completedCuts() == required.completedCuts() &&
            observed.installationReads() == required.installationReads())
        val reads = observed.installationReads()
        requireQuiescence(reads.size == 2)
        reads.forEach { read ->
            val declaration = original.routing.journalConfiguration.declaration()
            requireQuiescence(read.databaseIdentity == declaration.writer.databaseIdentity && read.restoreIdentity == declaration.writer.restoreIdentity &&
                read.desiredGeneration == original.registration.process.desiredGeneration && read.fencingToken in (original.drain.leaseToken + 1)..original.manifest.preparation.leaseToken &&
                read.sourceHighWater.enrolledCount == current.enrolledCount && read.installationCount == current.enrolledCount &&
                read.completedAtEpochSecond <= now().epochSecond && read.startedAtEpochSecond >= current.sealedAt.epochSecond)
        }
        requireQuiescence(jdbc.query(TestRunSealingSqlV1.readAudit, { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") },
            *original.registration.sealingAuditArguments(current.sealedAt)).single())
        return current
    }
    private fun requireCounts(current: TestOrdinaryDrainRowsV1.Run) {
        jdbc.query(TestTerminalEpochSealSqlV1.counts, { value, _ ->
            val count = value.getLong("manifests")
            requireQuiescence(count == TestTerminalSyntaxV1.chunkCount(current.enrolledCount).toLong() && count == original.manifest.capturedSource().count.toLong() &&
                value.getLong("other_intents") == 0L && value.getLong("ordinary_seals") == 1L && value.getLong("terminal_seals") == 1L &&
                value.getLong("first_ordinal") == 0L && value.getLong("last_ordinal") == count - 1L && value.getLong("publications") == count &&
                value.getLong("reservations") == count && value.getLong("purges") == 1L && value.getLong("purge_publications") == 1L && value.getLong("purge_reservations") == 1L &&
                value.getLong("scan_runs") in 0..2 && value.getLong("scan_entries") in 0..Math.multiplyExact(2L, original.targets.size.toLong()))
            manifests = count
        }, original.scope).single()
    }
    private fun baselineActual(current: TestOrdinaryDrainRowsV1.Run): ComplaintCapacityVector =
        TestTerminalCapacityChargesV1.INSTALLATION_SHARE.scaled(current.enrolledCount) + TestTerminalCapacityChargesV1.AUDIT +
            TestTerminalCapacityChargesV1.TERMINAL_RUN_DELTA + TestTerminalCapacityChargesV1.SIDECAR.scaled(2) +
            TestInstallationManifestOperationV1.MANIFEST_ACTUAL.scaled(manifests) + TestRunPurgeOperationV1.ACTUAL
    private fun requirePaidRemainder(current: TestOrdinaryDrainRowsV1.Run, charge: ComplaintCapacityVector) {
        val paid = baselineActual(current) + TestRunPurgeOperationV1.FUTURE + charge
        requireQuiescence(current.plan.manifestFuturePromise.isZero() &&
            current.plan.purgeFuturePromise == TestTerminalCapacityChargesV1.TERMINAL_RUN_DELTA + TestRunPurgeOperationV1.FUTURE &&
            paid.fitsWithin(current.reserve) && current.unused == current.reserve - paid)
    }
    private fun requireSealSet(current: TestOrdinaryDrainRowsV1.Run) {
        val set = json.sealSet(checkNotNull(current.sealSetBytes))
        requireQuiescence(set.records() == original.fullSealSet.records() && set.dataScopeId == original.runContext.dataScopeId &&
            set.activationCatalogGeneration == original.runContext.activationCatalogGeneration && set.activationCatalogSha256 == original.runContext.activationCatalogSha256 &&
            Sha256.hex(checkNotNull(current.sealSetBytes)) == original.fullSealSetSha256)
        val full = roots().fullSeals(set)
        requireQuiescence(current.sealCount == full.count && current.sealRoot.contentEquals(hex(full.sha256)))
        val prefix = TestTerminalSealSetV1.create(original.runContext.dataScopeId, original.runContext.activationCatalogGeneration,
            original.runContext.activationCatalogSha256, original.control.ordinarySeals(original.ordinarySeal))
        requireQuiescence(roots().preTerminalSeals(prefix) == original.purge.capturedRoots().seals)
    }
    private fun roots() = TestTerminalRootsV1(original.routing.journalConfiguration, run.installationLimit, run.plan.manifestChunkCount.toInt())

    private fun requireAllSidecars() {
        // One complete fresh history for this call only; every target still passes the same exact checks.
        val historyRows = original.control.initialHistory?.frozenRows(jdbc, original.drain) ?: emptyList()
        try {
            requireQuiescence(historyRows.size == original.targets.count { it.source !== TestTerminalQuiescenceSourceV1.V21_TERMINAL_INTENT })
            original.targets.forEach { target -> loadSidecar(target, historyRows).use { /* Own comparison rows, never predecessor-owned buffers. */ } }
        } finally { historyRows.forEach { it.close() } }
    }
    private fun loadSidecar(target: TestTerminalQuiescenceTargetV1, historyRows: List<TestTerminalDurableRowV1>? = null): TestTerminalDurableRowV1 {
        val at = now()
        requireQuiescence(original.targets.any { it === target })
        val loaded = if (target.source !== TestTerminalQuiescenceSourceV1.V21_TERMINAL_INTENT) {
            val history = checkNotNull(original.control.initialHistory)
            val record = history.record(target.ordinal)
            requireQuiescence(target.kind === TestTerminalCodecKindV1.EPOCH_SEAL &&
                (target.source === TestTerminalQuiescenceSourceV1.V26_ACTIVE_SEAL) == (record.binding.objectOrdinal == 0) &&
                target.objectRef == record.reference.objectRef && target.startEpoch == record.reference.epochStartInclusive && target.endEpoch == record.reference.epochEndInclusive)
            // Each A source remains ordinary-paid. No fallback to or alias of a V21 ordinal.
            if (historyRows == null) history.frozen(jdbc, original.drain, record) // Standalone EXPECTED retains its own fresh complete read.
            else historyRows.single { it.binding == record.binding }
        } else when (target.kind) {
            TestTerminalCodecKindV1.INSTALLATION_MANIFEST -> oneSidecar(TestInstallationManifestSqlV1.sidecar, { value ->
                TestInstallationManifestPublicationRowsV1.manifest(value, original.manifest, run.sealedAt, at, target.ordinal)
            }, original.scope, target.ordinal)
            TestTerminalCodecKindV1.TEST_RUN_PURGE -> oneSidecar(TestRunPurgeSqlV1.sidecar, { value ->
                TestRunPurgeRowsV1.purge(value, original.purge, run.sealedAt, at)
            }, original.scope)
            TestTerminalCodecKindV1.EPOCH_SEAL -> if (target.ordinal == 0) oneSidecar(TestTerminalEpochSealSqlV1.ordinarySidecar, { value ->
                TestInstallationManifestRowsV1.ordinarySidecarForInventory(value, original.manifest.preparation, at)
            }, original.scope, original.routing.journalConfiguration.sealTerminalPrefix + "%") else oneSidecar(TestTerminalEpochSealSqlV1.sidecar, { value ->
                TestTerminalEpochSealRowsV1.sidecar(value, original.terminalSeal, run.sealedAt, at)
            }, original.scope)
        }
        try {
            val b = loaded.binding
            requireQuiescence(loaded.state === TestTerminalDurableStateV1.WIRE_FROZEN && b.run == original.runContext &&
                b.objectId == target.id && b.objectOrdinal == target.ordinal && b.epochStartInclusive == target.startEpoch && b.epochEndInclusive == target.endEpoch &&
                b.objectKey == target.objectRef.objectKey && loaded.canonicalSha256 == target.objectRef.canonicalSha256 && loaded.wireSha256 == target.objectRef.ciphertextSha256)
            val bytes = loaded.canonicalBytes()
            try {
                if (target.kind === TestTerminalCodecKindV1.TEST_RUN_PURGE) {
                    val purge = json.purge(bytes)
                    requireQuiescence(purge.context().run == original.runContext && purge.writerGeneration == original.writer && purge.publicationEpoch == original.epoch &&
                        purge.eventId == target.id && purge.finalOrdinaryEpoch == original.control.cutoff && purge.finalOrdinarySeal == original.ordinarySeal &&
                        purge.preTerminalSeals == original.purge.capturedRoots().seals && purge.preTerminalInventory == original.purge.capturedRoots().inventory &&
                        purge.installationManifest == original.manifest.authenticatedSummary())
                } else if (target.kind === TestTerminalCodecKindV1.EPOCH_SEAL && target.source === TestTerminalQuiescenceSourceV1.V21_TERMINAL_INTENT && target.ordinal == 1) {
                    val seal = json.epochSeal(bytes)
                    val completed = original.terminalSeal.capturedManifest()
                    TestTerminalEpochSealRowsV1.requireReference(loaded, original.sealedReference, original.terminalSeal)
                    requireQuiescence(seal.preparingFencingToken == original.terminalSeal.leaseToken && seal.precedingSealSha256 == original.ordinarySeal.objectRef.canonicalSha256 &&
                        seal.eventCount == completed.count && seal.eventManifestSha256 == completed.sha256)
                }
            } finally { bytes.fill(0) }
            return loaded
        } catch (problem: Throwable) { loaded.close(); throw problem }
    }

    /** Own every materialized buffer even if a hostile second row or later mapper/query cleanup fails. */
    private fun oneSidecar(sql: String, materialize: (ResultSet) -> TestTerminalDurableRowV1, vararg args: Any?): TestTerminalDurableRowV1 {
        val allocated = ArrayList<TestTerminalDurableRowV1>(1)
        try {
            val rows = jdbc.query(sql, { value, _ ->
                requireQuiescence(allocated.isEmpty())
                materialize(value).also(allocated::add)
            }, *args)
            requireQuiescence(rows.size == 1)
            return rows.single().also { allocated.clear() }
        } finally { allocated.forEach(TestTerminalDurableRowV1::close) }
    }

    private fun readScans(): List<TestTerminalQuiescenceRowsV1.Scan> = jdbc.query(TestOrdinaryDrainSqlV1.runs,
        { value, _ -> TestTerminalQuiescenceRowsV1.Scan(value, original) }, original.scope).also { requireQuiescence(it.size <= 2) }
    private fun scanCharge(): ComplaintCapacityVector = jdbc.query(TestOrdinaryDrainSqlV1.pool,
        { value, _ -> run.plan.scanPool.chargeFor(value.getLong("runs"), value.getLong("entries")) }, original.scope, original.scope).single()
    private fun beginPass() {
        val pass = original.inventoryPass
        requireQuiescence(pass == 1 && scans.isEmpty() || pass == 2 && scans.size == 1 && scans.single().pass == 1 && scans.single().state == "COMPLETE")
        if (pass == 2) scans.single().requireSummary(original.nativeSummary(1))
        requireQuiescence(jdbc.update(TestOrdinaryDrainSqlV1.insertRun, original.scanId, pass, original.scope,
            original.admittedDenial().statement.restoreIdentity, original.registration.process.desiredGeneration, original.leaseToken, original.writer,
            original.epoch, original.maximumVersions, original.maximumFramedBytes, Timestamp.from(checkNotNull(original.inventoryTime))) == 1)
        spend = TestTerminalCapacityChargesV1.SCAN_RUN
    }
    private fun activeScan(): TestTerminalQuiescenceRowsV1.Scan = scans.single { it.pass == original.inventoryPass }.also { requireQuiescence(it.state == "SCANNING") }
    private fun append() {
        val scan = activeScan()
        val entry = original.pendingEntry()
        val charge = entry.framedBytes()
        requireQuiescence(jdbc.update(TestTerminalQuiescenceSqlV1.insertEntry, original.scanId, scan.pass, original.scope,
            entry.objectRef.objectKey, entry.objectRef.objectVersion, hex(entry.objectRef.ciphertextSha256), hex(entry.objectRef.canonicalSha256),
            entry.eventId, entry.kind.name, original.writer, entry.epochEndInclusive, entry.ciphertextByteCount) == 1)
        requireQuiescence(jdbc.update(TestOrdinaryDrainSqlV1.appendRun, charge, original.scanId, scan.pass, original.scope, charge) == 1)
        spend = TestTerminalCapacityChargesV1.SCAN_ENTRY
    }
    private fun completePass() {
        val scan = activeScan()
        val folded = fold(scan, checkNotNull(original.inventoryTime))
        val native = checkNotNull(original.inventorySummary)
        requireQuiescence(folded.witness.versionCount == native.versionCount && folded.witness.byteCount == native.ciphertextByteCount &&
            folded.witness.sha256 == native.sha256 && folded.framedBytes == native.framedByteCount && folded.entryFramedBytes == scan.framedBytes)
        requireQuiescence(jdbc.update(TestOrdinaryDrainSqlV1.finishRun, hex(folded.witness.sha256), Timestamp.from(checkNotNull(original.inventoryTime)),
            folded.framedBytes, original.scanId, scan.pass, original.scope, scan.count, scan.framedBytes, folded.framedBytes) == 1)
        summary = folded
    }
    private fun entryPage(pass: Int, after: Pair<String, String>?): List<TestTerminalQuiescenceRowsV1.Entry> =
        jdbc.query(TestOrdinaryDrainSqlV1.entryPage, { value, _ -> TestTerminalQuiescenceRowsV1.entry(value, original, pass) },
            original.scanId, pass, original.scope, after?.first, after?.first, after?.second)
    private fun fold(scan: TestTerminalQuiescenceRowsV1.Scan, end: Instant): TestTerminalQuiescenceRowsV1.Summary {
        val native = original.nativeEntries(scan.pass)
        val fold = TestPostTerminalInventoryFoldV1(original.routing.journalConfiguration, original.runContext, original.epoch, original.targets.size.toLong())
        var after: Pair<String, String>? = null
        var index = 0
        while (true) {
            retained(Stage.BODY)
            val page = entryPage(scan.pass, after)
            if (page.isEmpty()) break
            page.forEach { entry ->
                requireQuiescence(index < native.size)
                entry.requireNative(native[index]); fold.entry(native[index]); index++; after = entry.locator
            }
        }
        requireQuiescence(index == native.size && index.toLong() == scan.count)
        val value = fold.finish()
        return TestTerminalQuiescenceRowsV1.Summary(TestTerminalInventoryWitnessV1(scan.startedAt.epochSecond, end.epochSecond,
            value.versionCount, value.ciphertextByteCount, value.sha256), value.framedByteCount, value.entryFramedByteCount)
    }
    private fun witness() {
        requireQuiescence(scans.map { it.pass } == listOf(1, 2))
        val summaries = scans.map { scan ->
            val value = fold(scan, checkNotNull(scan.finishedAt)); scan.requireSummary(value)
            requireQuiescence(value == original.nativeSummary(scan.pass)); value
        }
        requireQuiescence(original.nativeEntries(1) == original.nativeEntries(2) && summaries[0].framedBytes == summaries[1].framedBytes)
        var after: Pair<String, String>? = null
        while (true) {
            val first = entryPage(1, after); val second = entryPage(2, after)
            requireQuiescence(first == second)
            if (first.isEmpty()) break
            after = first.last().locator; retained(Stage.BODY)
        }
        val admitted = original.admittedDenial(); val statement = admitted.statement
        val cut = TestTerminalCompletedCutV1(original.writer, TestTerminalDenialPrefixV1.SEAL_TERMINAL, 1, original.epoch, original.scanId.toString(),
            statement.databaseIdentity, statement.restoreIdentity, original.registration.process.desiredGeneration, original.leaseToken, summaries[0].framedBytes,
            TestTerminalDenialCutV1(statement.roleId, statement.policy, statement.denialEffectiveAtEpochSecond, statement.lastSessionExpiryEpochSecond,
                statement.acceptedRequestBoundSeconds, admitted.policyEvidence, statement.boundEvidence, summaries[0].witness, summaries[1].witness))
        val previous = original.predecessorProgress()
        val value = TestTerminalProgressV1.create(original.runContext, previous.completedCuts() + cut, previous.installationReads())
        progressWrite = json.encodeProgress(value)
        requireQuiescence(checkNotNull(progressWrite).size <= TestTerminalProgressV1.MAX_CANONICAL_BYTES)
        progress = value // Existing lifetime envelope ALREADY paid. No spend here.
    }
    private fun recycle() {
        scans.forEach { it.requireSummary(original.nativeSummary(it.pass)) }
        val page = jdbc.query(TestOrdinaryDrainSqlV1.recyclePage, { value, _ ->
            val pass = value.getInt("pass")
            pass to TestTerminalQuiescenceRowsV1.entry(value, original, pass)
        }, original.scope)
        for ((pass, discovered) in page) {
            retained(Stage.BODY); requireQuiescence(scans.any { it.pass == pass })
            val actual = jdbc.query(TestOrdinaryDrainSqlV1.exactRecycleEntry, { value, _ -> TestTerminalQuiescenceRowsV1.entry(value, original, pass) },
                original.scanId, pass, discovered.key, discovered.version).single()
            requireQuiescence(actual == discovered)
            actual.requireNative(original.nativeEntries(pass).single { it.objectRef.objectKey == actual.key })
            requireQuiescence(jdbc.update(TestTerminalQuiescenceSqlV1.deleteEntry, original.scanId, pass, original.scope, actual.key, actual.version,
                hex(actual.ciphertext), hex(actual.canonical), actual.eventId, actual.kind.name, original.writer, actual.epoch, actual.ciphertextBytes, "VERIFIED_ONLY") == 1)
            recycled += TestTerminalCapacityChargesV1.SCAN_ENTRY
        }
        scans.forEach { scan ->
            if (jdbc.queryForObject(TestOrdinaryDrainSqlV1.scanEntryCount, Long::class.java, scan.id, scan.pass) == 0L) {
                requireQuiescence(jdbc.update(TestOrdinaryDrainSqlV1.deleteRun, scan.id, scan.pass, original.scope) == 1)
                recycled += TestTerminalCapacityChargesV1.SCAN_RUN
            }
        }
        requireQuiescence(recycled.fitsWithin(initialScanCharge))
    }

    private fun requireRelation() {
        original.requireRunning()
        val sql = if (original.control.initialHistory == null) TestTerminalQuiescenceSqlV1.relation else TestTerminalQuiescenceSqlV1.relationWithActiveHistory
        requireQuiescence(jdbc.query(sql, { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") }, original.scope,
            original.routing.journalConfiguration.ordinaryPrefix + "%", original.routing.journalConfiguration.sealTerminalPrefix + "%", original.writer,
            original.control.cutoff, original.epoch, original.routing.journalConfiguration.ownerDeleteAll, original.routing.journalConfiguration.registeredAdminDelete,
            original.routing.journalConfiguration.registeredAdminBatchDelete, OwnerDeleteRows.array(TestRunPurgeOperationV1.FUTURE)).single())
    }
    private fun requireControls() {
        original.requireRunning()
        for (sql in listOf(TestRunSealingSqlV1.lockGlobalControl, TestRunSealingSqlV1.lockScopeControl)) requireQuiescence(jdbc.query(sql,
            { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") }, *original.registration.sealingControlArguments()).single())
        TestOrdinaryDrainActiveHistoryV1.requireCurrent(jdbc, original.drain, original.control.initialHistory)
        val sql = if (original.control.initialHistory == null) TestTerminalQuiescenceSqlV1.control else TestTerminalQuiescenceSqlV1.controlWithActiveHistory
        requireQuiescence(jdbc.query(sql, { value, _ -> TestTerminalEpochSealRowsV1.Control(value, original.terminalSeal) },
            original.scope).single().epoch == original.terminalSeal.afterEpoch)
    }
    private fun requireLease() {
        original.requireRunning()
        requireQuiescence(jdbc.query(TestOrdinarySealSqlV1.lease, { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") }, original.attemptId, original.leaseToken, original.scope).single())
        val at = now().epochSecond
        requireQuiescence(at < original.drain.manifestDenialRetainUntil())
        if (step !== TestTerminalQuiescenceStepV1.CAPTURE) requireQuiescence(at < original.admittedDenial().statement.evidenceRetainUntilEpochSecond)
    }
    private fun now(): Instant = checkNotNull(jdbc.queryForObject("SELECT clock_timestamp()", { value, _ -> value.getTimestamp(1).toInstant() })).also {
        requireQuiescence(it.epochSecond in 0..253_402_300_799L)
    }
    internal fun beginCounterLock(selected: JdbcTemplate) { retained(Stage.COUNTERS_REQUESTED, selected); stage = Stage.COUNTERS_LOCKING }
    internal fun requireCounterTransfer(value: JdbcComplaintCapacityStore.LockedTestTerminalQuiescence, selected: JdbcTemplate) {
        retained(Stage.TRANSFER, selected); requireQuiescence(counters === value)
    }
    internal fun settleLockedLedger(selected: JdbcTemplate, ledger: ComplaintCapacityLedger, daily: ComplaintDailyAdmission, expectedDigest: ByteArray): ComplaintCapacityLedger {
        retained(Stage.TRANSFER, selected); ledger.configuration.requireMatching(expectedDigest)
        val policy = original.registration.process.consumers.capacityPolicy
        requireQuiescence(ledger.balance.hardLimit == policy.hardLimit && ledger.balance.creationLimit == policy.creationLimit && daily.dailyLimit == policy.dailyEnrollmentLimit &&
            run.unused.fitsWithin(ledger.balance.testReserved) &&
            (baselineActual(run) + initialScanCharge + original.control.ordinaryHistoryCharge).fitsWithin(ledger.balance.actual) &&
            TestRunPurgeOperationV1.FUTURE.fitsWithin(ledger.balance.recoveryReserved) && spend.fitsWithin(run.unused))
        if (!recycled.isZero()) {
            requireQuiescence(step === TestTerminalQuiescenceStepV1.RECYCLE && spend.isZero())
            return ledger.recycleTestScanPool(expectedDigest, run.plan.scanPool, run.plan.scanPool.ceiling - initialScanCharge, recycled)
        }
        if (!spend.isZero()) {
            requireQuiescence(step in setOf(TestTerminalQuiescenceStepV1.BEGIN_PASS, TestTerminalQuiescenceStepV1.APPEND))
            return ledger.spendTestReserve(expectedDigest, spend, ComplaintCapacityVector.ZERO)
        }
        return ledger
    }
    internal fun failed(problem: Throwable): Nothing {
        original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled()
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }
    private fun retained(expected: Stage, selected: JdbcTemplate = jdbc) {
        phase.testTerminalQuiescenceBoundary.requireRetained(this, selected)
        requireQuiescence(stage === expected && step === original.step && selected === jdbc)
    }
    private enum class Stage { NEW, CONTROLS, COUNTERS_REQUESTED, COUNTERS_LOCKING, BODY, TRANSFER, COMPLETE }
    companion object {
        internal fun execute(jdbc: JdbcTemplate, original: TestRunTerminalQuiescenceV1): TestTerminalQuiescenceOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            var operation: TestTerminalQuiescenceOperationV1? = null
            try {
                phase.testTerminalQuiescenceBoundary.requireOperation(original, jdbc)
                return TestTerminalQuiescenceOperationV1(phase, jdbc, original).also { operation = it; phase.testTerminalQuiescenceBoundary.retain(it, jdbc); it.execute() }
            } catch (problem: Throwable) {
                operation?.discardRow(); operation?.progressWrite?.fill(0)
                original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled()
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
        private fun hex(value: String): ByteArray = HexFormat.of().parseHex(value)
    }
}
