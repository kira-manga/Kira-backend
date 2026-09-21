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
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSyntaxV1
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
 * Current phases: M/RC, exclusive E, controls, existing publication/recovery, counters, SEALED run,
 * bounded source and sidecar. VERIFY is deliberately a DIFFERENT path: publication only, then stop.
 * No callback, zero-promise conversion, APPLIED, run write or capacity mutation is available here.
 */
internal class TestInstallationManifestPublicationOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val original: TestRunInstallationManifestPublicationV1,
) {
    internal val path = original.path
    internal val step = original.step
    internal var source: TestInstallationManifestSourceV1.Observation? = null
        private set
    internal var row: TestTerminalDurableRowV1? = null
        private set
    internal var publication: TestInstallationManifestPublicationRowsV1.Publication? = null
        private set
    private lateinit var run: TestOrdinaryDrainRowsV1.Run
    private var stage = Stage.NEW
    private var counters: JdbcComplaintCapacityStore.LockedTestInstallationManifestPublication? = null
    private var manifests = 0L
    private val json = TestTerminalJsonV1(original.routing.journalConfiguration)

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = belongsTo(selected) && stage === Stage.COMPLETE &&
        (if (step === TestInstallationManifestPublicationStepV1.VERIFY) counters == null else counters?.completedFor(this) == true)
    internal fun requireReleased() { phase.testInstallationManifestPublicationBoundary.requireCommitted(this); requireConnectionFree() }

    private fun execute() {
        retained(Stage.NEW)
        if (step === TestInstallationManifestPublicationStepV1.VERIFY) {
            verifyPublicationOnly()
            retained(Stage.VERIFICATION)
            stage = Stage.COMPLETE
            return
        }
        stage = Stage.CONTROLS
        requireControls()
        if (step === TestInstallationManifestPublicationStepV1.CAPTURE) {
            val token = jdbc.query(TestInstallationManifestPublicationSqlV1.acquire, { value, _ -> value.getLong("lease_token") }, original.attemptId,
                original.budget.remainingMillis(original.routing.journalConfiguration.declaration().limits.deadlines.scanMillis.toLong()), original.scope).single()
            original.retainLease(this, token)
        }
        requireLease()
        when (step) {
            TestInstallationManifestPublicationStepV1.LOAD, TestInstallationManifestPublicationStepV1.FREEZE -> lockChunkPublication()
            TestInstallationManifestPublicationStepV1.COMPLETE -> lockCompletedPublications()
            else -> Unit
        }
        stage = Stage.COUNTERS_REQUESTED
        counters = JdbcComplaintCapacityStore(jdbc, original.registration.process.consumers.capacityPolicy.digestBytes()).lockForTestInstallationManifestPublication(this)
        retained(Stage.COUNTERS_LOCKING)
        stage = Stage.BODY
        run = readRun()
        manifests = manifestCount(run)
        requirePaidRemainder(run)
        requireStrictSeal()
        requireRelation()
        when (step) {
            TestInstallationManifestPublicationStepV1.CAPTURE -> {
                requireAppliedCut()
                source = readSource()
                requirePreparedSource(checkNotNull(source))
            }
            TestInstallationManifestPublicationStepV1.LOAD -> {
                row = loadSidecar()
                checkNotNull(publication).requireSidecar(checkNotNull(row), original.expectedChunk().count)
                requireCurrentChunk()
            }
            TestInstallationManifestPublicationStepV1.FREEZE -> freeze()
            TestInstallationManifestPublicationStepV1.COMPLETE -> {
                requireAppliedCut()
                source = readSource()
                original.capturedSource().requireSame(checkNotNull(source))
            }
            TestInstallationManifestPublicationStepV1.VERIFY -> throw TestInstallationManifestExceptionV1()
        }
        requireRelation()
        stage = Stage.CHECKED
        checkNotNull(counters).checkPaid(this)
        requireManifest(checkNotNull(counters).completedFor(this))
        val after = readRun()
        requirePaidRemainder(after)
        requireManifest(manifestCount(after) == manifests && after.unused == run.unused && after.reserve == run.reserve &&
            after.sealSetBytes.contentEquals(run.sealSetBytes) && after.sealSetHash.contentEquals(run.sealSetHash) &&
            after.progressBytes.contentEquals(run.progressBytes) && after.progressHash.contentEquals(run.progressHash))
        requireControls()
        requireLease()
        if (step === TestInstallationManifestPublicationStepV1.COMPLETE) requireManifest(jdbc.update(TestInstallationManifestPublicationSqlV1.release,
            original.scope, original.attemptId, original.leaseToken) == 1)
        retained(Stage.CHECKED)
        stage = Stage.COMPLETE
    }

    private fun lockChunkPublication() {
        requireManifest(publication == null && original.chunkIndex in 0 until original.preparation.capturedSource().count)
        val id = jdbc.query(TestInstallationManifestPublicationSqlV1.discover, { value, _ -> checkNotNull(value.getString("object_id")) },
            original.scope, original.chunkIndex).single()
        publication = original.ownPublication(loadPublication(id))
        jdbc.query(TestInstallationManifestPublicationSqlV1.recovery,
            { value, _ -> TestInstallationManifestPublicationRowsV1.recovery(value, original, checkNotNull(publication)) }, id).single()
    }

    /** Deterministic key order BEFORE counters/run. Every plaintext snapshot is disposed immediately. */
    private fun lockCompletedPublications() {
        val ordinals = (0 until original.capturedSource().count).sortedBy { original.completedChunkForRecheck(it).objectRef.objectKey }
        for (ordinal in ordinals) {
            original.requireRunning()
            val expected = original.completedChunkForRecheck(ordinal)
            val id = jdbc.query(TestInstallationManifestPublicationSqlV1.discover, { value, _ -> checkNotNull(value.getString("object_id")) }, original.scope, ordinal).single()
            requireManifest(id == expected.id)
            loadPublication(id).use { actual ->
                actual.requireVerifiedReference(expected, original.preparation.capturedSource().chunk(ordinal).count, now())
                jdbc.query(TestInstallationManifestPublicationSqlV1.recovery,
                    { value, _ -> TestInstallationManifestPublicationRowsV1.recovery(value, original, actual) }, id).single()
            }
        }
    }

    private fun loadPublication(id: String): TestInstallationManifestPublicationRowsV1.Publication =
        jdbc.query(TestInstallationManifestPublicationSqlV1.publication, { value, _ -> TestInstallationManifestPublicationRowsV1.Publication(value, original) }, id).single()

    private fun loadSidecar(): TestTerminalDurableRowV1 {
        val observedAt = now()
        return jdbc.query(TestInstallationManifestPublicationSqlV1.sidecar,
            { value, _ -> original.ownRow(TestInstallationManifestPublicationRowsV1.manifest(value, original, run.sealedAt, observedAt)) }, original.scope, original.chunkIndex).single()
    }

    private fun freeze() {
        row = loadSidecar()
        val current = checkNotNull(row)
        checkNotNull(publication).requireSidecar(current, original.expectedChunk().count)
        original.requireSameCanonical(original.loadedRow(), current)
        requireCurrentChunk()
        val proposed = original.frozenCandidate(this)
        if (current.state === TestTerminalDurableStateV1.CANONICAL) {
            requireManifest(checkNotNull(publication).state == "PREPARED")
            val candidate = checkNotNull(proposed)
            original.requireSameCanonical(current, candidate)
            requireManifest(candidate.state === TestTerminalDurableStateV1.WIRE_FROZEN && checkNotNull(candidate.frozenAt) <= now())
            val wire = checkNotNull(candidate.wireBytes()); val metadata = checkNotNull(candidate.metadataBytes())
            try {
                requireManifest(jdbc.update(TestInstallationManifestPublicationSqlV1.freeze, wire, hex(checkNotNull(candidate.wireSha256)), candidate.checksumSha256,
                    Timestamp.from(candidate.retainUntil), metadata, hex(checkNotNull(candidate.metadataSha256)), Timestamp.from(candidate.frozenAt),
                    candidate.binding.operationToken, original.scope, original.chunkIndex, hex(candidate.canonicalSha256)) == 1)
            } finally { wire.fill(0); metadata.fill(0) }
            row = loadSidecar()
            original.requireSameFrozen(candidate, checkNotNull(row))
        } else if (proposed == null) {
            original.requireSameFrozen(original.loadedRow(), current)
        }
        // If a different genuine freeze won, keep its exact bytes; a randomized losing candidate
        // has no dispatch authority. V21 forbids rewriting the canonical or frozen winner.
        requireManifest(checkNotNull(row).state === TestTerminalDurableStateV1.WIRE_FROZEN)
        checkNotNull(publication).requireSidecar(checkNotNull(row), original.expectedChunk().count)
    }

    /** No current-authority recheck is smuggled behind the publication lock. Only the closed proof can enter. */
    private fun verifyPublicationOnly() {
        stage = Stage.VERIFICATION
        val proof = original.providerProof(this)
        val frozen = original.frozenRow()
        publication = original.ownPublication(loadPublication(frozen.binding.objectId))
        checkNotNull(publication).requireSidecar(frozen, original.expectedChunk().count)
        val observedAt = now()
        requireManifest(proof.verifiedAt <= observedAt && proof.retainUntil > observedAt)
        if (checkNotNull(publication).state == "PREPARED") {
            val bytes = proof.canonicalBytes(frozen)
            try {
                requireManifest(jdbc.update(TestInstallationManifestPublicationSqlV1.verify, proof.version, hex(checkNotNull(frozen.wireSha256)),
                    Timestamp.from(proof.lastModified), Timestamp.from(proof.retainUntil), Timestamp.from(proof.verifiedAt), bytes, hex(Sha256.hex(bytes)),
                    frozen.binding.objectId, original.scope, hex(frozen.canonicalSha256)) == 1)
            } finally { bytes.fill(0) }
            publication = original.ownPublication(loadPublication(frozen.binding.objectId))
        }
        checkNotNull(publication).requireProof(frozen, proof, original.expectedChunk().count, now())
        retained(Stage.VERIFICATION)
    }

    private fun requirePreparedSource(observed: TestInstallationManifestSourceV1.Observation) {
        val prepared = original.preparation.capturedSource()
        requireManifest(prepared.count == observed.count)
        repeat(observed.count) { requireManifest(prepared.chunk(it) == observed.chunk(it)) }
        requireManifest(prepared.progress.installationReads().first().installationsSha256 == observed.progress.installationReads().first().installationsSha256)
    }
    private fun readSource(): TestInstallationManifestSourceV1.Observation {
        val source = TestInstallationManifestSourceV1(original.routing.journalConfiguration, original.runContext, binding(), run.installationLimit,
            run.enrolledCount, checkNotNull(run.progress).installationReads().first())
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
    private fun requireCurrentChunk() {
        binding(); requireCompleteCredentials()
        val expected = original.expectedChunk()
        TestInstallationManifestSourceV1.Chunk(original.runContext, expected.ordinal, expected.afterId, original.drain.maximumFramedBytes).use { chunk ->
            var after: UUID? = expected.afterId?.let(UUID::fromString)
            while (true) {
                original.requireRunning()
                val page = jdbc.query(TestInstallationManifestSqlV1.chunkPage, { value, _ -> TestInstallationSourceV1.Row.read(value) },
                    original.scope, after, after, UUID.fromString(expected.lastId))
                if (page.isEmpty()) break
                page.forEach { value -> original.requireRunning(); chunk.entry(value); after = UUID.fromString(value.id) }
            }
            requireManifest(chunk.descriptor() == expected)
        }
        requireCompleteCredentials(); binding()
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
        requireManifest(reads.size == 2)
        reads.forEach { read ->
            val declaration = original.routing.journalConfiguration.declaration()
            requireManifest(read.databaseIdentity == declaration.writer.databaseIdentity && read.restoreIdentity == declaration.writer.restoreIdentity &&
                read.desiredGeneration == original.registration.process.desiredGeneration && read.fencingToken in (original.drain.leaseToken + 1)..original.preparation.leaseToken &&
                read.sourceHighWater.enrolledCount == run.enrolledCount && read.installationCount == run.enrolledCount &&
                read.completedAtEpochSecond <= now().epochSecond && read.startedAtEpochSecond >= run.sealedAt.epochSecond)
        }
        requireManifest(jdbc.query(TestRunSealingSqlV1.readAudit, { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") },
            *original.registration.sealingAuditArguments(run.sealedAt)).single())
        return run
    }
    private fun requireStrictSeal() {
        val set = json.sealSet(checkNotNull(run.sealSetBytes))
        requireManifest(set.dataScopeId == original.runContext.dataScopeId && set.activationCatalogGeneration == original.runContext.activationCatalogGeneration &&
            set.activationCatalogSha256 == original.runContext.activationCatalogSha256 && set.records() == listOf(original.ordinarySeal))
        val root = TestTerminalRootsV1(original.routing.journalConfiguration, run.installationLimit, run.plan.manifestChunkCount.toInt()).preTerminalSeals(set)
        requireManifest(run.sealCount == root.count && run.sealRoot.contentEquals(hex(root.sha256)))
        val observedAt = now()
        jdbc.query(TestInstallationManifestSqlV1.ordinarySidecar,
            { value, _ -> TestInstallationManifestRowsV1.requireOrdinarySidecar(value, original.preparation, observedAt) },
            original.scope, original.routing.journalConfiguration.sealTerminalPrefix + "%").single()
    }
    private fun manifestCount(run: TestOrdinaryDrainRowsV1.Run): Long = jdbc.query(TestInstallationManifestPublicationSqlV1.counts, { value, _ ->
        val count = value.getLong("manifests")
        requireManifest(count == TestTerminalSyntaxV1.chunkCount(run.enrolledCount).toLong() && count == original.preparation.capturedSource().count.toLong() &&
            value.getLong("other_intents") == 1L && value.getLong("first_ordinal") == 0L && value.getLong("last_ordinal") == count - 1L &&
            value.getLong("publications") == count && value.getLong("reservations") == count && value.getLong("scan_runs") == 0L && value.getLong("scan_entries") == 0L)
        count
    }, original.scope).single()
    private fun spent(run: TestOrdinaryDrainRowsV1.Run): ComplaintCapacityVector =
        TestTerminalCapacityChargesV1.INSTALLATION_SHARE.scaled(run.enrolledCount) + TestTerminalCapacityChargesV1.AUDIT +
            TestTerminalCapacityChargesV1.TERMINAL_RUN_DELTA + TestTerminalCapacityChargesV1.SIDECAR + TestInstallationManifestOperationV1.MANIFEST_ACTUAL.scaled(manifests)
    private fun requirePaidRemainder(run: TestOrdinaryDrainRowsV1.Run) {
        val paid = spent(run)
        requireManifest(run.plan.manifestFuturePromise.isZero() && paid.fitsWithin(run.reserve) && run.unused == run.reserve - paid)
    }
    private fun requireRelation() {
        original.requireRunning()
        requireManifest(jdbc.query(TestInstallationManifestPublicationSqlV1.relation, { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") }, original.scope,
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
        requireManifest(step === TestInstallationManifestPublicationStepV1.CAPTURE || step === TestInstallationManifestPublicationStepV1.COMPLETE)
    }
    private fun requireControls() {
        original.requireRunning()
        for (sql in listOf(TestRunSealingSqlV1.lockGlobalControl, TestRunSealingSqlV1.lockScopeControl)) requireManifest(jdbc.query(sql,
            { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") }, *original.registration.sealingControlArguments()).single())
        jdbc.query(TestOrdinaryDrainSqlV1.control, { value, _ -> TestOrdinaryDrainRowsV1.Control(value) }, original.scope).single().requireSame(original.control)
    }
    private fun requireLease() {
        original.requireRunning()
        requireManifest(jdbc.query(TestOrdinarySealSqlV1.lease, { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") }, original.attemptId, original.leaseToken, original.scope).single())
        requireManifest(now().epochSecond < original.drain.manifestDenialRetainUntil())
    }
    private fun now(): Instant = checkNotNull(jdbc.queryForObject("SELECT clock_timestamp()", { value, _ -> value.getTimestamp(1).toInstant() })).also {
        requireManifest(it.epochSecond in 0..253_402_300_799L)
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        retained(Stage.COUNTERS_REQUESTED, selected); requireManifest(step !== TestInstallationManifestPublicationStepV1.VERIFY); stage = Stage.COUNTERS_LOCKING
    }
    internal fun requireCounterCheck(locked: JdbcComplaintCapacityStore.LockedTestInstallationManifestPublication, selected: JdbcTemplate) {
        retained(Stage.CHECKED, selected); requireManifest(counters === locked)
    }
    internal fun requirePaidLedger(selected: JdbcTemplate, ledger: ComplaintCapacityLedger, daily: ComplaintDailyAdmission, expectedDigest: ByteArray) {
        retained(Stage.CHECKED, selected)
        ledger.configuration.requireMatching(expectedDigest)
        val policy = original.registration.process.consumers.capacityPolicy
        requireManifest(ledger.balance.hardLimit == policy.hardLimit && ledger.balance.creationLimit == policy.creationLimit &&
            daily.dailyLimit == policy.dailyEnrollmentLimit && run.unused.fitsWithin(ledger.balance.testReserved) && spent(run).fitsWithin(ledger.balance.actual))
    }
    internal fun failed(problem: Throwable): Nothing {
        original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled()
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }
    private fun retained(expected: Stage, selected: JdbcTemplate = jdbc) {
        phase.testInstallationManifestPublicationBoundary.requireRetained(this, selected)
        requireManifest(stage === expected && step === original.step && selected === jdbc)
    }
    private enum class Stage { NEW, CONTROLS, COUNTERS_REQUESTED, COUNTERS_LOCKING, BODY, CHECKED, VERIFICATION, COMPLETE }
    companion object {
        internal fun execute(jdbc: JdbcTemplate, original: TestRunInstallationManifestPublicationV1): TestInstallationManifestPublicationOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.testInstallationManifestPublicationBoundary.requireOperation(original, jdbc)
                return TestInstallationManifestPublicationOperationV1(phase, jdbc, original).also { phase.testInstallationManifestPublicationBoundary.retain(it, jdbc); it.execute() }
            } catch (problem: Throwable) { original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled(); throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED) }
        }
        private fun hex(value: String): ByteArray = HexFormat.of().parseHex(value)
    }
}
