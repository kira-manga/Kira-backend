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
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableKindV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStateV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSyntaxV1
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteRows
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.TestTerminalRootsV1
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Current M/RC,E,controls,P/L,counters/run phases; receiptless VERIFY is publication-only. */
internal class TestRunPurgeOperationV1 private constructor(
    private val phase: PersistencePhaseContext, private val jdbc: JdbcTemplate, internal val original: TestRunPurgePublicationV1,
) {
    internal val path = original.path
    internal val step = original.step
    internal var source: TestInstallationManifestSourceV1.Observation? = null
        private set
    internal var roots: TestRunPurgeInventorySourceV1.Roots? = null
        private set
    internal var row: TestTerminalDurableRowV1? = null
        private set
    internal var publication: TestRunPurgeRowsV1.Publication? = null
        private set
    internal lateinit var databaseNow: Instant
        private set
    private lateinit var run: TestOrdinaryDrainRowsV1.Run
    private var stage = Stage.NEW
    private var counters: JdbcComplaintCapacityStore.LockedTestRunPurge? = null
    private var manifests = 0L
    private var purges = 0L
    private var spend = ComplaintCapacityVector.ZERO
    private val json = TestTerminalJsonV1(original.routing.journalConfiguration)

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = belongsTo(selected) && stage === Stage.COMPLETE &&
        (if (step === TestRunPurgeStepV1.VERIFY) counters == null else counters?.completedFor(this) == true)
    internal fun requireReleased() { phase.testRunPurgeBoundary.requireCommitted(this); requireConnectionFree() }

    private fun execute() {
        retained(Stage.NEW)
        if (step === TestRunPurgeStepV1.VERIFY) {
            verifyPublicationOnly(); retained(Stage.VERIFICATION); stage = Stage.COMPLETE; return
        }
        stage = Stage.CONTROLS
        requireControls()
        if (step === TestRunPurgeStepV1.CAPTURE) {
            val token = jdbc.query(TestRunPurgeSqlV1.acquire, { value, _ -> value.getLong("lease_token") }, original.attemptId,
                original.budget.remainingMillis(original.routing.journalConfiguration.declaration().limits.deadlines.scanMillis.toLong()), original.scope).single()
            original.retainLease(this, token)
        }
        requireLease()
        lockPublications()
        stage = Stage.COUNTERS_REQUESTED
        counters = JdbcComplaintCapacityStore(jdbc, original.registration.process.consumers.capacityPolicy.digestBytes()).lockForTestRunPurge(this)
        retained(Stage.COUNTERS_LOCKING)
        stage = Stage.BODY
        run = readRun(); databaseNow = now()
        purges = requireCounts(run)
        requirePaidRemainder(run, purges)
        requireStrictSeal(); requireRelation()
        row = loadSidecar()
        requirePurge((row == null) == (publication == null) && (row == null) == (purges == 0L))
        row?.let { checkNotNull(publication).requireSidecar(it, 0) }
        // All real source passes are owned by THIS active operation; finished drain readers remain closed.
        source = readSource()
        requireManifestMembership(checkNotNull(source))
        roots = TestRunPurgeInventorySourceV1.read(jdbc, this, run)
        if (step !== TestRunPurgeStepV1.CAPTURE) {
            original.capturedSource().requireSame(checkNotNull(source))
            requirePurge(roots == original.capturedRoots())
        }
        when (step) {
            TestRunPurgeStepV1.CAPTURE -> Unit
            TestRunPurgeStepV1.PREPARE -> prepare()
            TestRunPurgeStepV1.FREEZE -> freeze()
            TestRunPurgeStepV1.COMPLETE -> {
                requirePurge(purges == 1L)
                original.requireSameFrozen(original.frozenRow(), checkNotNull(row))
                checkNotNull(publication).requireVerifiedReference(original.completedForRecheck(), 0, now())
            }
            TestRunPurgeStepV1.VERIFY -> throw TestRunPurgeExceptionV1()
        }
        requireRelation()
        stage = Stage.TRANSFER
        checkNotNull(counters).settle(this)
        requirePurge(checkNotNull(counters).completedFor(this))
        if (!spend.isZero()) requirePurge(jdbc.update(TestOrdinaryDrainSqlV1.spend, OwnerDeleteRows.array(run.unused - spend),
            original.scope, OwnerDeleteRows.array(run.unused)) == 1)
        val after = readRun()
        val afterPurges = requireCounts(after)
        requirePaidRemainder(after, afterPurges)
        requirePurge(afterPurges == purges + if (spend.isZero()) 0L else 1L)
        requirePurge(after.unused == run.unused - spend && after.reserve == run.reserve &&
            after.progressBytes.contentEquals(run.progressBytes) && after.progressHash.contentEquals(run.progressHash) &&
            after.sealSetBytes.contentEquals(run.sealSetBytes) && after.sealSetHash.contentEquals(run.sealSetHash))
        requireControls(); requireLease()
        if (step === TestRunPurgeStepV1.COMPLETE) requirePurge(jdbc.update(TestRunPurgeSqlV1.release, original.scope, original.attemptId, original.leaseToken) == 1)
        retained(Stage.TRANSFER); stage = Stage.COMPLETE
    }

    /** Merge the one purge locator with the bounded existing manifest references before P/L locks. */
    private fun lockPublications() {
        val purge = jdbc.query(TestRunPurgeSqlV1.discover, { value, _ ->
            checkNotNull(value.getString("object_id")) to checkNotNull(value.getString("object_key"))
        }, original.scope).singleOrNull()
        val slots = (0 until original.manifest.capturedSource().count).map { original.manifest.authenticatedChunk(it).objectKey to it }.toMutableList()
        purge?.let { slots.add(it.second to -1) }
        requirePurge(slots.map { it.first }.distinct().size == slots.size)
        for ((key, ordinal) in slots.sortedBy { it.first }) {
            original.requireRunning()
            if (ordinal == -1) {
                loadPurgePublication(checkNotNull(purge).first)
                requirePurge(checkNotNull(publication).key == key)
            } else {
                val expected = original.manifest.authenticatedFacts(ordinal)
                val id = jdbc.query(TestInstallationManifestSqlV1.discover, { value, _ -> checkNotNull(value.getString("object_id")) }, original.scope, ordinal).single()
                requirePurge(id == expected.id)
                jdbc.query(TestInstallationManifestPublicationSqlV1.publication, { value, _ ->
                    TestInstallationManifestPublicationRowsV1.Publication(value, original.manifest)
                }, id).single().use { actual ->
                    actual.requireVerifiedReference(expected, original.manifest.capturedSource().chunk(ordinal).count, now())
                    jdbc.query(TestInstallationManifestSqlV1.recovery, { value, _ ->
                        TestInstallationManifestPublicationRowsV1.recovery(value, original.manifest, actual)
                    }, id).single()
                }
            }
        }
    }
    private fun loadPurgePublication(id: String) {
        requirePurge(publication == null)
        publication = original.ownPublication(loadPublication(id))
        jdbc.query(TestRunPurgeSqlV1.recovery, { value, _ -> TestRunPurgeRowsV1.recovery(value, original, checkNotNull(publication)) },
            OwnerDeleteRows.array(FUTURE), id).single()
    }
    private fun loadPublication(id: String): TestRunPurgeRowsV1.Publication =
        jdbc.query(TestRunPurgeSqlV1.publication, { value, _ -> TestRunPurgeRowsV1.Publication(value, original) }, id).single()
    private fun loadSidecar(): TestTerminalDurableRowV1? {
        val observedAt = now()
        return jdbc.query(TestRunPurgeSqlV1.sidecar, { value, _ -> original.ownRow(TestRunPurgeRowsV1.purge(value, original, run.sealedAt, observedAt)) },
            original.scope).singleOrNull()
    }

    private fun prepare() {
        val candidate = original.canonicalCandidate(this)
        val bytes = candidate.canonicalBytes()
        try {
            val b = candidate.binding
            requirePurge(b.objectKind === TestTerminalDurableKindV1.TEST_RUN_PURGE && b.objectOrdinal == 0 && b.run == original.runContext &&
                b.writerGeneration == original.writer && b.epochStartInclusive == original.epoch && b.epochEndInclusive == original.epoch &&
                b.journalConfigurationSha256 == original.routing.journalConfiguration.sha256)
            if (row == null) {
                requirePurge(purges == 0L && publication == null && candidate.state === TestTerminalDurableStateV1.CANONICAL &&
                    b.preparingFencingToken == original.leaseToken && b.createdAt >= run.sealedAt && b.createdAt <= now())
                original.requireRetentionFloor(b)
                requirePurge(jdbc.update(TestRunPurgeSqlV1.insertPublication, b.objectId, original.scope, b.writerGeneration, b.epochEndInclusive,
                    b.routingKeyId, b.objectKey, bytes, hex(candidate.canonicalSha256), Timestamp.from(b.createdAt)) == 1)
                requirePurge(jdbc.update(TestRunPurgeSqlV1.insertRecovery, b.objectId, original.scope, b.objectId, OwnerDeleteRows.array(FUTURE), Timestamp.from(b.createdAt)) == 1)
                requirePurge(jdbc.update(TestRunPurgeSqlV1.insertSidecar, b.operationToken, original.scope, b.objectId, b.objectKey, b.routingKeyId,
                    b.writerGeneration, b.epochStartInclusive, b.epochEndInclusive, b.preparingFencingToken, b.run.activationCatalogGeneration,
                    hex(b.run.activationCatalogSha256), hex(b.run.configurationSha256), hex(b.journalConfigurationSha256), hex(b.run.terminalEncodingSha256),
                    b.objectId, bytes, hex(candidate.canonicalSha256), Timestamp.from(b.retentionFloor), Timestamp.from(b.createdAt)) == 1)
                // New rows are already held by this transaction; never acquire an unrelated P after counters.
                loadPurgePublication(b.objectId)
                row = loadSidecar()
                spend = ACTUAL + FUTURE
            }
            original.requireSameCanonical(candidate, checkNotNull(row))
            checkNotNull(publication).requireSidecar(checkNotNull(row), 0)
            requirePurge(requireCounts(run) == purges + if (spend.isZero()) 0L else 1L)
        } finally { bytes.fill(0) }
    }
    private fun freeze() {
        val current = checkNotNull(row)
        requirePurge(purges == 1L)
        original.requireSameCanonical(original.loadedRow(), current)
        val proposed = original.frozenCandidate(this)
        if (current.state === TestTerminalDurableStateV1.CANONICAL) {
            requirePurge(checkNotNull(publication).state == "PREPARED")
            val candidate = checkNotNull(proposed)
            original.requireSameCanonical(current, candidate)
            requirePurge(candidate.state === TestTerminalDurableStateV1.WIRE_FROZEN && checkNotNull(candidate.frozenAt) <= now())
            val wire = checkNotNull(candidate.wireBytes()); val metadata = checkNotNull(candidate.metadataBytes())
            try {
                requirePurge(jdbc.update(TestRunPurgeSqlV1.freeze, wire, hex(checkNotNull(candidate.wireSha256)), candidate.checksumSha256,
                    Timestamp.from(candidate.retainUntil), metadata, hex(checkNotNull(candidate.metadataSha256)), Timestamp.from(candidate.frozenAt),
                    candidate.binding.operationToken, original.scope, hex(candidate.canonicalSha256)) == 1)
            } finally { wire.fill(0); metadata.fill(0) }
            row = loadSidecar()
            original.requireSameFrozen(candidate, checkNotNull(row))
        } else if (proposed == null) original.requireSameFrozen(original.loadedRow(), current)
        requirePurge(checkNotNull(row).state === TestTerminalDurableStateV1.WIRE_FROZEN)
        checkNotNull(publication).requireSidecar(checkNotNull(row), 0)
    }
    private fun verifyPublicationOnly() {
        stage = Stage.VERIFICATION
        val proof = original.providerProof(this)
        val frozen = original.frozenRow()
        publication = original.ownPublication(loadPublication(frozen.binding.objectId))
        checkNotNull(publication).requireSidecar(frozen, 0)
        val observedAt = now()
        requirePurge(proof.verifiedAt <= observedAt && proof.retainUntil > observedAt)
        if (checkNotNull(publication).state == "PREPARED") {
            val bytes = proof.canonicalBytes(frozen)
            try {
                requirePurge(jdbc.update(TestRunPurgeSqlV1.verify, proof.version, hex(checkNotNull(frozen.wireSha256)), Timestamp.from(proof.lastModified),
                    Timestamp.from(proof.retainUntil), Timestamp.from(proof.verifiedAt), bytes, hex(Sha256.hex(bytes)),
                    frozen.binding.objectId, original.scope, hex(frozen.canonicalSha256)) == 1)
            } finally { bytes.fill(0) }
            publication = original.ownPublication(loadPublication(frozen.binding.objectId))
        }
        checkNotNull(publication).requireProof(frozen, proof, 0, now())
        retained(Stage.VERIFICATION)
    }

    private fun readSource(): TestInstallationManifestSourceV1.Observation {
        val source = TestInstallationManifestSourceV1(original.routing.journalConfiguration, original.runContext, binding(), run.installationLimit,
            run.enrolledCount, original.manifest.capturedSource().progress.installationReads().first())
        repeat(2) {
            source.begin(binding(), now().epochSecond); requireCompleteCredentials()
            var after: UUID? = null
            while (true) {
                original.requireRunning()
                val page = jdbc.query(TestInstallationSourceSqlV1.page, { value, _ -> TestInstallationSourceV1.Row.read(value) }, original.scope, after, after)
                if (page.isEmpty()) break
                page.forEach { value -> original.requireRunning(); source.entry(value); after = UUID.fromString(value.id) }
            }
            requireCompleteCredentials(); source.end(binding(), now().epochSecond)
        }
        return source.finish().also { original.requireRunning() }
    }
    private fun requireManifestMembership(source: TestInstallationManifestSourceV1.Observation) {
        val predecessor = original.manifest.capturedSource()
        val summary = original.manifest.authenticatedSummary()
        requirePurge(source.count == predecessor.count && summary.chunkCount == source.count)
        repeat(source.count) { requirePurge(source.chunk(it) == predecessor.chunk(it)) }
        val read = source.progress.installationReads().first()
        requirePurge(read.installationsSha256 == summary.installationsSha256 && read.installationCount == summary.installationCount &&
            read.retiredCount == summary.retiredCount && read.deletedCount == summary.deletedCount)
    }
    private fun binding(): TestInstallationSourceV1.Binding {
        requireLease()
        return jdbc.query(TestRunSealingSqlV1.readScopeControl, { value, _ ->
            requirePurge(TestOrdinaryDrainRowsV1.boolean(value, "valid") && value.getLong("lease_token") == original.leaseToken)
            TestInstallationSourceV1.Binding(value.getObject("database_identity", UUID::class.java).toString(),
                value.getObject("restore_identity", UUID::class.java).toString(), value.getLong("desired_generation"), value.getLong("lease_token"))
        }, *original.registration.sealingControlArguments()).single()
    }
    private fun requireCompleteCredentials() {
        requirePurge(jdbc.query(TestInstallationSourceSqlV1.credentialsComplete, { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") }, original.scope).single())
    }
    private fun readRun(): TestOrdinaryDrainRowsV1.Run {
        original.requireRunning()
        val run = jdbc.query(TestOrdinaryDrainSqlV1.run, { value, _ -> TestOrdinaryDrainRowsV1.Run(value, original.drain) }, *original.registration.sealingRunArguments()).single()
        requirePurge(run.progress?.context() == original.runContext && run.progress?.completedCuts() == listOf(original.ordinaryCut) &&
            run.ordinaryEpoch == original.control.cutoff && run.reservedTerminalEpoch == original.epoch)
        val reads = checkNotNull(run.progress).installationReads()
        requirePurge(reads.size == 2)
        reads.forEach { read ->
            val declaration = original.routing.journalConfiguration.declaration()
            requirePurge(read.databaseIdentity == declaration.writer.databaseIdentity && read.restoreIdentity == declaration.writer.restoreIdentity &&
                read.desiredGeneration == original.registration.process.desiredGeneration && read.fencingToken in (original.drain.leaseToken + 1)..original.manifest.preparation.leaseToken &&
                read.sourceHighWater.enrolledCount == run.enrolledCount && read.installationCount == run.enrolledCount &&
                read.completedAtEpochSecond <= now().epochSecond && read.startedAtEpochSecond >= run.sealedAt.epochSecond)
        }
        requirePurge(jdbc.query(TestRunSealingSqlV1.readAudit, { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") },
            *original.registration.sealingAuditArguments(run.sealedAt)).single())
        return run
    }
    private fun requireStrictSeal() {
        val set = json.sealSet(checkNotNull(run.sealSetBytes))
        requirePurge(set.dataScopeId == original.runContext.dataScopeId && set.activationCatalogGeneration == original.runContext.activationCatalogGeneration &&
            set.activationCatalogSha256 == original.runContext.activationCatalogSha256 && set.records() == listOf(original.ordinarySeal))
        val root = TestTerminalRootsV1(original.routing.journalConfiguration, run.installationLimit, run.plan.manifestChunkCount.toInt()).preTerminalSeals(set)
        requirePurge(run.sealCount == root.count && run.sealRoot.contentEquals(hex(root.sha256)))
        val observedAt = now()
        jdbc.query(TestInstallationManifestSqlV1.ordinarySidecar, { value, _ ->
            TestInstallationManifestRowsV1.requireOrdinarySidecar(value, original.manifest.preparation, observedAt)
        }, original.scope, original.routing.journalConfiguration.sealTerminalPrefix + "%").single()
    }
    private fun requireCounts(run: TestOrdinaryDrainRowsV1.Run): Long = jdbc.query(TestRunPurgeSqlV1.counts, { value, _ ->
        val count = value.getLong("manifests")
        val purgeCount = value.getLong("purges")
        requirePurge(count == TestTerminalSyntaxV1.chunkCount(run.enrolledCount).toLong() && count == original.manifest.capturedSource().count.toLong() &&
            value.getLong("other_intents") == 1L && value.getLong("first_ordinal") == 0L && value.getLong("last_ordinal") == count - 1L &&
            value.getLong("publications") == count && value.getLong("reservations") == count && purgeCount in 0..1 &&
            value.getLong("purge_publications") == purgeCount && value.getLong("purge_reservations") == purgeCount &&
            value.getLong("scan_runs") == 0L && value.getLong("scan_entries") == 0L)
        manifests = count
        purgeCount
    }, original.scope).single()
    private fun baselineActual(run: TestOrdinaryDrainRowsV1.Run): ComplaintCapacityVector =
        TestTerminalCapacityChargesV1.INSTALLATION_SHARE.scaled(run.enrolledCount) + TestTerminalCapacityChargesV1.AUDIT +
            TestTerminalCapacityChargesV1.TERMINAL_RUN_DELTA + TestTerminalCapacityChargesV1.SIDECAR + TestInstallationManifestOperationV1.MANIFEST_ACTUAL.scaled(manifests)
    private fun requirePaidRemainder(run: TestOrdinaryDrainRowsV1.Run, count: Long) {
        val paid = baselineActual(run) + (ACTUAL + FUTURE).scaled(count)
        requirePurge(run.plan.manifestFuturePromise.isZero() && run.plan.purgeFuturePromise == TestTerminalCapacityChargesV1.TERMINAL_RUN_DELTA + FUTURE &&
            paid.fitsWithin(run.reserve) && run.unused == run.reserve - paid)
    }
    private fun requireRelation() {
        original.requireRunning()
        requirePurge(jdbc.query(TestRunPurgeSqlV1.relation, { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") }, original.scope,
            original.routing.journalConfiguration.ordinaryPrefix + "%", original.routing.journalConfiguration.sealTerminalPrefix + "%",
            original.writer, original.control.cutoff, original.epoch, original.routing.journalConfiguration.ownerDeleteAll,
            original.routing.journalConfiguration.registeredAdminDelete, OwnerDeleteRows.array(FUTURE)).single())
    }
    internal fun requireInventoryPage(selected: JdbcTemplate) { retained(Stage.BODY, selected); requirePurge(step !== TestRunPurgeStepV1.VERIFY) }
    private fun requireControls() {
        original.requireRunning()
        for (sql in listOf(TestRunSealingSqlV1.lockGlobalControl, TestRunSealingSqlV1.lockScopeControl)) requirePurge(jdbc.query(sql,
            { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") }, *original.registration.sealingControlArguments()).single())
        val control = jdbc.query(TestOrdinaryDrainSqlV1.control, { value, _ -> TestOrdinaryDrainRowsV1.Control(value) }, original.scope).single()
        control.requireSame(original.control)
        requirePurge(control.previousSealEpoch == 0L)
    }
    private fun requireLease() {
        original.requireRunning()
        requirePurge(jdbc.query(TestOrdinarySealSqlV1.lease, { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") }, original.attemptId, original.leaseToken, original.scope).single())
        requirePurge(now().epochSecond < original.drain.manifestDenialRetainUntil())
    }
    private fun now(): Instant = checkNotNull(jdbc.queryForObject("SELECT clock_timestamp()", { value, _ -> value.getTimestamp(1).toInstant() })).also {
        requirePurge(it.epochSecond in 0..253_402_300_799L)
    }
    internal fun beginCounterLock(selected: JdbcTemplate) { retained(Stage.COUNTERS_REQUESTED, selected); requirePurge(step !== TestRunPurgeStepV1.VERIFY); stage = Stage.COUNTERS_LOCKING }
    internal fun requireCounterTransfer(locked: JdbcComplaintCapacityStore.LockedTestRunPurge, selected: JdbcTemplate) { retained(Stage.TRANSFER, selected); requirePurge(counters === locked) }
    internal fun settleLockedLedger(selected: JdbcTemplate, ledger: ComplaintCapacityLedger, daily: ComplaintDailyAdmission, expectedDigest: ByteArray): ComplaintCapacityLedger {
        retained(Stage.TRANSFER, selected)
        ledger.configuration.requireMatching(expectedDigest)
        val policy = original.registration.process.consumers.capacityPolicy
        requirePurge(ledger.balance.hardLimit == policy.hardLimit && ledger.balance.creationLimit == policy.creationLimit &&
            daily.dailyLimit == policy.dailyEnrollmentLimit && run.unused.fitsWithin(ledger.balance.testReserved) &&
            (baselineActual(run) + ACTUAL.scaled(purges)).fitsWithin(ledger.balance.actual) && FUTURE.scaled(purges).fitsWithin(ledger.balance.recoveryReserved) &&
            spend.fitsWithin(run.unused))
        if (spend.isZero()) return ledger
        requirePurge(step === TestRunPurgeStepV1.PREPARE && purges == 0L && spend == ACTUAL + FUTURE)
        return ledger.spendTestReserve(expectedDigest, ACTUAL, FUTURE)
    }
    internal fun failed(problem: Throwable): Nothing {
        original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled()
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }
    private fun retained(expected: Stage, selected: JdbcTemplate = jdbc) {
        phase.testRunPurgeBoundary.requireRetained(this, selected)
        requirePurge(stage === expected && step === original.step && selected === jdbc)
    }
    private enum class Stage { NEW, CONTROLS, COUNTERS_REQUESTED, COUNTERS_LOCKING, BODY, TRANSFER, VERIFICATION, COMPLETE }
    companion object {
        internal val ACTUAL = TestTerminalCapacityChargesV1.PUBLICATION_WITH_RESERVATION + TestTerminalCapacityChargesV1.SIDECAR
        internal val FUTURE = TestTerminalCapacityChargesV1.AUDIT // Lifetime Delta already paid by this exact completed predecessor.
        internal fun execute(jdbc: JdbcTemplate, original: TestRunPurgePublicationV1): TestRunPurgeOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.testRunPurgeBoundary.requireOperation(original, jdbc)
                return TestRunPurgeOperationV1(phase, jdbc, original).also { phase.testRunPurgeBoundary.retain(it, jdbc); it.execute() }
            } catch (problem: Throwable) { original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled(); throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED) }
        }
        private fun hex(value: String): ByteArray = HexFormat.of().parseHex(value)
    }
}
