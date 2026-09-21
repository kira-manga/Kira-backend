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
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRoleV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealSetV1
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

/** Every stage, including VERIFY, owns current M/RC,E,controls,sorted P/L,counters and SEALED run. */
internal class TestTerminalEpochSealOperationV1 private constructor(
    private val phase: PersistencePhaseContext, private val jdbc: JdbcTemplate, internal val original: TestRunTerminalEpochSealV1,
) {
    internal val path = original.path
    internal val step = original.step
    internal lateinit var manifest: TestTerminalEpochSealManifestV1
        private set
    internal lateinit var source: TestInstallationManifestSourceV1.Observation
        private set
    internal lateinit var databaseNow: Instant
        private set
    internal var row: TestTerminalDurableRowV1? = null
        private set
    internal var terminalReference: TestTerminalSealRefV1? = null
        private set
    private lateinit var run: TestOrdinaryDrainRowsV1.Run
    private var stage = Stage.NEW
    private var counters: JdbcComplaintCapacityStore.LockedTestTerminalEpochSeal? = null
    private var manifests = 0L
    private var terminalSeals = 0L
    private var spend = false
    private var publicationsLocked = false
    private val json = TestTerminalJsonV1(original.routing.journalConfiguration)
    // Bounded by authenticated <=4096 manifest descriptors, not by the configured J version cap.
    private val slots = (0 until original.manifest.capturedSource().count).map { index ->
        original.manifest.authenticatedChunk(index).objectKey to index
    }.plus(original.purge.authenticatedFactsForSeal().objectRef.objectKey to -1).sortedBy { it.first }

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = belongsTo(selected) && stage === Stage.COMPLETE && counters?.completedFor(this) == true
    internal fun requireReleased() { phase.testTerminalEpochSealBoundary.requireCommitted(this); requireConnectionFree() }

    private fun execute() {
        retained(Stage.NEW)
        stage = Stage.CONTROLS
        var current = requireControls()
        if (step === TestTerminalEpochSealStepV1.CAPTURE) {
            requireTerminalSeal(current.epoch == original.epoch)
            val token = jdbc.query(TestTerminalEpochSealSqlV1.acquire, { value, _ -> value.getLong("lease_token") }, original.attemptId,
                original.budget.remainingMillis(original.routing.journalConfiguration.declaration().limits.deadlines.scanMillis.toLong()), original.scope).single()
            original.retainLease(this, token)
        }
        requireLease()
        lockPublications()
        stage = Stage.COUNTERS_REQUESTED
        counters = JdbcComplaintCapacityStore(jdbc, original.registration.process.consumers.capacityPolicy.digestBytes()).lockForTestTerminalEpochSeal(this)
        retained(Stage.COUNTERS_LOCKING)
        stage = Stage.BODY
        run = readRun(); databaseNow = now()
        terminalSeals = requireCounts(run)
        requirePaidRemainder(run, terminalSeals)
        requireRelation()
        row = loadSidecar()
        requireTerminalSeal((row == null) == (terminalSeals == 0L) && current.epoch == original.epoch + terminalSeals)
        if (step === TestTerminalEpochSealStepV1.CAPTURE || step === TestTerminalEpochSealStepV1.PREPARE) {
            requireTerminalSeal(row == null && terminalSeals == 0L)
        } else requireTerminalSeal(row != null && terminalSeals == 1L && current.epoch == original.afterEpoch)
        requireSealSet(run, if (step === TestTerminalEpochSealStepV1.COMPLETE) original.completedForRecheck() else null)
        requireOrdinarySidecar()
        requirePurgeBinding()
        source = readSource()
        requireManifestMembership(source)
        manifest = readManifest()
        if (step !== TestTerminalEpochSealStepV1.CAPTURE) {
            original.capturedManifest().requireSame(manifest)
            original.capturedSource().requireSame(source)
        }
        when (step) {
            TestTerminalEpochSealStepV1.CAPTURE -> Unit
            TestTerminalEpochSealStepV1.PREPARE -> prepare()
            TestTerminalEpochSealStepV1.FREEZE -> freeze()
            TestTerminalEpochSealStepV1.VERIFY -> verify()
            TestTerminalEpochSealStepV1.COMPLETE -> {
                original.requireSameFrozen(original.frozenRow(), checkNotNull(row))
                terminalReference = original.completedForRecheck().also { TestTerminalEpochSealRowsV1.requireReference(checkNotNull(row), it, original) }
            }
        }
        requireRelation()
        stage = Stage.TRANSFER
        checkNotNull(counters).settle(this)
        requireTerminalSeal(checkNotNull(counters).completedFor(this))
        if (spend) requireTerminalSeal(jdbc.update(TestOrdinaryDrainSqlV1.spend, OwnerDeleteRows.array(run.unused - TestTerminalCapacityChargesV1.SIDECAR),
            original.scope, OwnerDeleteRows.array(run.unused)) == 1)
        val after = readRun()
        val count = requireCounts(after)
        requirePaidRemainder(after, count)
        requireTerminalSeal(count == terminalSeals + (if (spend) 1L else 0L) && after.reserve == run.reserve &&
            after.unused == run.unused - (if (spend) TestTerminalCapacityChargesV1.SIDECAR else ComplaintCapacityVector.ZERO) &&
            after.progressBytes.contentEquals(run.progressBytes) && after.progressHash.contentEquals(run.progressHash))
        requireSealSet(after, terminalReference)
        if (step !== TestTerminalEpochSealStepV1.VERIFY) requireTerminalSeal(after.sealSetBytes.contentEquals(run.sealSetBytes) && after.sealSetHash.contentEquals(run.sealSetHash))
        current = requireControls()
        requireTerminalSeal(current.epoch == original.epoch + count)
        requireLease()
        if (step === TestTerminalEpochSealStepV1.COMPLETE) requireTerminalSeal(jdbc.update(TestTerminalEpochSealSqlV1.release, original.scope, original.attemptId, original.leaseToken) == 1)
        retained(Stage.TRANSFER); stage = Stage.COMPLETE
    }

    private fun lockPublications() {
        retained(Stage.CONTROLS)
        requireTerminalSeal(!publicationsLocked && slots.map { it.first }.distinct().size == slots.size)
        for ((key, index) in slots) {
            original.requireRunning()
            if (index == -1) {
                val facts = original.purge.authenticatedFactsForSeal()
                jdbc.query(TestRunPurgeSqlV1.publication, { value, _ -> TestRunPurgeRowsV1.Publication(value, original.purge) }, facts.id).single().use { actual ->
                    actual.requireVerifiedReference(facts, 0, now()); requireTerminalSeal(actual.key == key)
                    jdbc.query(TestRunPurgeSqlV1.recovery, { value, _ -> TestRunPurgeRowsV1.recovery(value, original.purge, actual) },
                        OwnerDeleteRows.array(TestRunPurgeOperationV1.FUTURE), facts.id).single()
                }
            } else {
                val facts = original.manifest.authenticatedFacts(index)
                jdbc.query(TestInstallationManifestPublicationSqlV1.publication, { value, _ ->
                    TestInstallationManifestPublicationRowsV1.Publication(value, original.manifest)
                }, facts.id).single().use { actual ->
                    actual.requireVerifiedReference(facts, original.manifest.capturedSource().chunk(index).count, now()); requireTerminalSeal(actual.key == key)
                    jdbc.query(TestInstallationManifestSqlV1.recovery, { value, _ -> TestInstallationManifestPublicationRowsV1.recovery(value, original.manifest, actual) }, facts.id).single()
                }
            }
        }
        publicationsLocked = true
    }
    private fun readManifest(): TestTerminalEpochSealManifestV1 {
        retained(Stage.BODY); requireTerminalSeal(publicationsLocked)
        val builder = TestTerminalEpochSealManifestV1.Builder(original.routing.journalConfiguration, original.epoch, manifests.toInt())
        repeat(2) { pass ->
            requireRelation()
            for ((key, index) in slots) {
                retained(Stage.BODY)
                // Only already-held exact IDs: these repeated readers cannot acquire an unrelated P after counters.
                if (index == -1) {
                    val expected = original.purge.authenticatedFactsForSeal()
                    jdbc.query(TestRunPurgeSqlV1.publication, { value, _ -> TestRunPurgeRowsV1.Publication(value, original.purge) }, expected.id).single().use { actual ->
                        actual.requireVerifiedReference(expected, 0, now()); requireTerminalSeal(actual.key == key)
                        val facts = actual.verifiedFacts()
                        builder.entry("TEST_RUN_PURGE", 0, facts.id, facts.objectRef, facts.verificationSha256)
                    }
                } else {
                    val expected = original.manifest.authenticatedFacts(index)
                    jdbc.query(TestInstallationManifestPublicationSqlV1.publication, { value, _ ->
                        TestInstallationManifestPublicationRowsV1.Publication(value, original.manifest)
                    }, expected.id).single().use { actual ->
                        actual.requireVerifiedReference(expected, original.manifest.capturedSource().chunk(index).count, now()); requireTerminalSeal(actual.key == key)
                        val facts = actual.verifiedFacts()
                        builder.entry("INSTALLATION_MANIFEST", index, facts.id, facts.objectRef, facts.verificationSha256)
                    }
                }
                requireLease()
            }
            requireRelation()
            if (pass == 0) builder.beginSecond()
        }
        return builder.finish()
    }

    private fun prepare() {
        retained(Stage.BODY)
        val candidate = original.canonicalCandidate(this)
        val b = candidate.binding
        requireTerminalSeal(row == null && terminalSeals == 0L && candidate.state === TestTerminalDurableStateV1.CANONICAL &&
            b.operationToken == original.intentId.toString() && b.objectKind === TestTerminalDurableKindV1.EPOCH_SEAL && b.objectOrdinal == 1 &&
            b.run == original.runContext && b.writerGeneration == original.writer && b.epochStartInclusive == original.epoch && b.epochEndInclusive == original.epoch &&
            b.preparingFencingToken == original.leaseToken && b.createdAt >= run.sealedAt && b.createdAt <= now() &&
            b.journalConfigurationSha256 == original.routing.journalConfiguration.sha256)
        original.requireRetentionFloor(b)
        requireTerminalSeal(TestTerminalCapacityChargesV1.SIDECAR.fitsWithin(run.unused))
        val bytes = candidate.canonicalBytes()
        try {
            requireDescriptor(bytes)
            // The original rotation slot, ordinary seal and checkpoint are neither cleared nor rewritten.
            val rotate = if (original.control.initialHistory == null) TestTerminalEpochSealSqlV1.rotate else TestTerminalEpochSealSqlV1.rotateWithActiveHistory
            requireTerminalSeal(jdbc.update(rotate, original.afterEpoch, original.scope, original.epoch,
                original.writer, original.control.captureId, original.control.captureFence, original.control.cutoff, original.attemptId, original.leaseToken) == 1)
            requireTerminalSeal(jdbc.update(TestTerminalEpochSealSqlV1.insert, b.operationToken, original.scope, b.objectId, b.objectKey, b.routingKeyId,
                b.writerGeneration, b.epochStartInclusive, b.epochEndInclusive, b.preparingFencingToken, b.run.activationCatalogGeneration,
                hex(b.run.activationCatalogSha256), hex(b.run.configurationSha256), hex(b.journalConfigurationSha256), hex(b.run.terminalEncodingSha256),
                bytes, hex(candidate.canonicalSha256), Timestamp.from(b.retentionFloor), Timestamp.from(b.createdAt)) == 1)
        } finally { bytes.fill(0) }
        spend = true
        row = loadSidecar()
        original.requireSameCanonical(candidate, checkNotNull(row))
    }
    private fun freeze() {
        val current = checkNotNull(row)
        original.requireSameCanonical(original.preparedRow(), current)
        val candidate = original.frozenCandidate(this)
        original.requireSameCanonical(candidate, current)
        requireTerminalSeal(current.state === TestTerminalDurableStateV1.CANONICAL && candidate.state === TestTerminalDurableStateV1.WIRE_FROZEN &&
            checkNotNull(candidate.frozenAt) <= now())
        val wire = checkNotNull(candidate.wireBytes()); val metadata = checkNotNull(candidate.metadataBytes())
        try {
            requireTerminalSeal(jdbc.update(TestTerminalEpochSealSqlV1.freeze, wire, hex(checkNotNull(candidate.wireSha256)), candidate.checksumSha256,
                Timestamp.from(candidate.retainUntil), metadata, hex(checkNotNull(candidate.metadataSha256)), Timestamp.from(candidate.frozenAt),
                candidate.binding.operationToken, original.scope, hex(candidate.canonicalSha256)) == 1)
        } finally { wire.fill(0); metadata.fill(0) }
        row = loadSidecar()
        original.requireSameFrozen(candidate, checkNotNull(row))
    }
    private fun verify() {
        val durable = checkNotNull(row)
        original.requireSameFrozen(original.frozenRow(), durable)
        val bytes = durable.canonicalBytes()
        try { requireDescriptor(bytes) } finally { bytes.fill(0) }
        val proof = original.providerProof(this) // Actual native closure, not a reserved lane or stored row, is the issuer.
        val at = now()
        requireTerminalSeal(proof.lastModified <= proof.verifiedAt && proof.verifiedAt <= at && proof.retainUntil > at)
        val reference = TestTerminalSealRefV1(TestTerminalSealRoleV1.TERMINAL, original.writer, original.epoch, original.epoch,
            durable.binding.objectId, original.ordinarySeal.objectRef.canonicalSha256,
            TestTerminalObjectRefV1(durable.binding.objectKey, proof.version, checkNotNull(durable.wireSha256), durable.canonicalSha256))
        TestTerminalEpochSealRowsV1.requireReference(durable, reference, original)
        val full = sealSet(reference)
        val root = roots(run).fullSeals(full)
        val encoded = json.encodeSealSet(full)
        try {
            val sql = if (original.control.initialHistory == null) TestTerminalEpochSealSqlV1.verifyRun else TestTerminalEpochSealSqlV1.verifyRunWithActiveHistory
            requireTerminalSeal(jdbc.update(sql, hex(root.sha256), encoded, hex(Sha256.hex(encoded)),
                original.scope, OwnerDeleteRows.array(run.reserve), OwnerDeleteRows.array(run.unused), run.progressBytes, run.progressHash,
                original.control.cutoff, original.epoch, run.sealRoot, run.sealSetBytes, run.sealSetHash) == 1)
        } finally { encoded.fill(0) }
        terminalReference = reference
    }
    private fun requireDescriptor(bytes: ByteArray) {
        val seal = json.epochSeal(bytes)
        requireTerminalSeal(seal.writerGeneration == original.writer && seal.dataScopeId == original.runContext.dataScopeId &&
            seal.epochStartInclusive == original.epoch && seal.epochEndInclusive == original.epoch &&
            seal.precedingSealSha256 == original.ordinarySeal.objectRef.canonicalSha256 && seal.preparingFencingToken == original.leaseToken &&
            seal.eventCount == manifest.count && seal.eventManifestSha256 == manifest.sha256)
    }
    private fun loadSidecar(): TestTerminalDurableRowV1? {
        val observedAt = now()
        return jdbc.query(TestTerminalEpochSealSqlV1.sidecar, { value, _ -> original.ownRow(TestTerminalEpochSealRowsV1.sidecar(value, original, run.sealedAt, observedAt)) }, original.scope).singleOrNull()
    }

    private fun requirePurgeBinding() {
        val observedAt = now()
        jdbc.query(TestRunPurgeSqlV1.sidecar, { value, _ -> TestRunPurgeRowsV1.purge(value, original.purge, run.sealedAt, observedAt) }, original.scope).single().use { durable ->
            val expected = original.purge.authenticatedFactsForSeal()
            requireTerminalSeal(durable.state === TestTerminalDurableStateV1.WIRE_FROZEN && durable.binding.objectId == expected.id &&
                durable.binding.objectKey == expected.objectRef.objectKey && durable.canonicalSha256 == expected.objectRef.canonicalSha256 && durable.wireSha256 == expected.objectRef.ciphertextSha256)
            val bytes = durable.canonicalBytes()
            try {
                val purge = json.purge(bytes)
                requireTerminalSeal(purge.context().run == original.runContext && purge.writerGeneration == original.writer && purge.publicationEpoch == original.epoch &&
                    purge.eventId == expected.id && purge.finalOrdinaryEpoch == original.control.cutoff && purge.finalOrdinarySeal == original.ordinarySeal &&
                    purge.preTerminalSeals == roots(run).preTerminalSeals(sealSet(null)) && purge.preTerminalSeals == original.purge.capturedRoots().seals &&
                    purge.preTerminalInventory == original.purge.capturedRoots().inventory && purge.installationManifest == original.manifest.authenticatedSummary())
            } finally { bytes.fill(0) }
        }
    }
    private fun requireOrdinarySidecar() {
        val at = now()
        jdbc.query(TestTerminalEpochSealSqlV1.ordinarySidecar, { value, _ -> TestInstallationManifestRowsV1.requireOrdinarySidecar(value, original.manifest.preparation, at) },
            original.scope, original.routing.journalConfiguration.sealTerminalPrefix + "%").single()
    }
    private fun roots(current: TestOrdinaryDrainRowsV1.Run) = TestTerminalRootsV1(original.routing.journalConfiguration, current.installationLimit, current.plan.manifestChunkCount.toInt())
    private fun sealSet(terminal: TestTerminalSealRefV1?) = TestTerminalSealSetV1.create(original.runContext.dataScopeId,
        original.runContext.activationCatalogGeneration, original.runContext.activationCatalogSha256,
        original.control.ordinarySeals(original.ordinarySeal) + listOfNotNull(terminal))
    private fun requireSealSet(current: TestOrdinaryDrainRowsV1.Run, terminal: TestTerminalSealRefV1?) {
        val actual = json.sealSet(checkNotNull(current.sealSetBytes))
        val expected = sealSet(terminal)
        requireTerminalSeal(actual.dataScopeId == expected.dataScopeId && actual.activationCatalogGeneration == expected.activationCatalogGeneration &&
            actual.activationCatalogSha256 == expected.activationCatalogSha256 && actual.records() == expected.records())
        val root = if (terminal == null) roots(current).preTerminalSeals(expected) else roots(current).fullSeals(expected)
        requireTerminalSeal(current.sealCount == root.count && current.sealRoot.contentEquals(hex(root.sha256)))
        // A purge's immutable ordinary-only prefix root is NOT rewritten to the later mixed full root.
        requireTerminalSeal(roots(current).preTerminalSeals(sealSet(null)) == original.purge.capturedRoots().seals)
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
        requireTerminalSeal(source.count == predecessor.count && summary.chunkCount == source.count)
        repeat(source.count) { requireTerminalSeal(source.chunk(it) == predecessor.chunk(it)) }
        val read = source.progress.installationReads().first()
        requireTerminalSeal(read.installationsSha256 == summary.installationsSha256 && read.installationCount == summary.installationCount &&
            read.retiredCount == summary.retiredCount && read.deletedCount == summary.deletedCount)
    }
    private fun binding(): TestInstallationSourceV1.Binding {
        requireLease()
        return jdbc.query(TestRunSealingSqlV1.readScopeControl, { value, _ ->
            requireTerminalSeal(TestOrdinaryDrainRowsV1.boolean(value, "valid") && value.getLong("lease_token") == original.leaseToken)
            TestInstallationSourceV1.Binding(value.getObject("database_identity", UUID::class.java).toString(),
                value.getObject("restore_identity", UUID::class.java).toString(), value.getLong("desired_generation"), value.getLong("lease_token"))
        }, *original.registration.sealingControlArguments()).single()
    }
    private fun requireCompleteCredentials() {
        requireTerminalSeal(jdbc.query(TestInstallationSourceSqlV1.credentialsComplete, { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") }, original.scope).single())
    }
    private fun readRun(): TestOrdinaryDrainRowsV1.Run {
        original.requireRunning()
        val sql = if (original.control.initialHistory == null) TestTerminalEpochSealSqlV1.run else TestTerminalEpochSealSqlV1.runWithActiveHistory
        val current = jdbc.query(sql, { value, _ -> TestOrdinaryDrainRowsV1.Run(value, original.drain) }, *original.registration.sealingRunArguments()).single()
        requireTerminalSeal(current.progress?.context() == original.runContext && current.progress?.completedCuts() == listOf(original.ordinaryCut) &&
            current.ordinaryEpoch == original.control.cutoff && current.reservedTerminalEpoch == original.epoch)
        val reads = checkNotNull(current.progress).installationReads()
        requireTerminalSeal(reads.size == 2)
        reads.forEach { read ->
            val declaration = original.routing.journalConfiguration.declaration()
            requireTerminalSeal(read.databaseIdentity == declaration.writer.databaseIdentity && read.restoreIdentity == declaration.writer.restoreIdentity &&
                read.desiredGeneration == original.registration.process.desiredGeneration && read.fencingToken in (original.drain.leaseToken + 1)..original.manifest.preparation.leaseToken &&
                read.sourceHighWater.enrolledCount == current.enrolledCount && read.installationCount == current.enrolledCount &&
                read.completedAtEpochSecond <= now().epochSecond && read.startedAtEpochSecond >= current.sealedAt.epochSecond)
        }
        requireTerminalSeal(jdbc.query(TestRunSealingSqlV1.readAudit, { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") },
            *original.registration.sealingAuditArguments(current.sealedAt)).single())
        return current
    }
    private fun requireCounts(current: TestOrdinaryDrainRowsV1.Run): Long = jdbc.query(TestTerminalEpochSealSqlV1.counts, { value, _ ->
        val count = value.getLong("manifests")
        val seals = value.getLong("terminal_seals")
        requireTerminalSeal(count == TestTerminalSyntaxV1.chunkCount(current.enrolledCount).toLong() && count == original.manifest.capturedSource().count.toLong() &&
            value.getLong("other_intents") == 0L && value.getLong("ordinary_seals") == 1L && seals in 0..1 &&
            value.getLong("first_ordinal") == 0L && value.getLong("last_ordinal") == count - 1L &&
            value.getLong("publications") == count && value.getLong("reservations") == count && value.getLong("purges") == 1L &&
            value.getLong("purge_publications") == 1L && value.getLong("purge_reservations") == 1L &&
            value.getLong("scan_runs") == 0L && value.getLong("scan_entries") == 0L)
        manifests = count
        seals
    }, original.scope).single()
    private fun baselineActual(current: TestOrdinaryDrainRowsV1.Run): ComplaintCapacityVector =
        TestTerminalCapacityChargesV1.INSTALLATION_SHARE.scaled(current.enrolledCount) + TestTerminalCapacityChargesV1.AUDIT +
            TestTerminalCapacityChargesV1.TERMINAL_RUN_DELTA + TestTerminalCapacityChargesV1.SIDECAR +
            TestInstallationManifestOperationV1.MANIFEST_ACTUAL.scaled(manifests) + TestRunPurgeOperationV1.ACTUAL
    private fun requirePaidRemainder(current: TestOrdinaryDrainRowsV1.Run, count: Long) {
        val paid = baselineActual(current) + TestRunPurgeOperationV1.FUTURE + TestTerminalCapacityChargesV1.SIDECAR.scaled(count)
        requireTerminalSeal(current.plan.manifestFuturePromise.isZero() && current.plan.purgeFuturePromise == TestTerminalCapacityChargesV1.TERMINAL_RUN_DELTA + TestRunPurgeOperationV1.FUTURE &&
            paid.fitsWithin(current.reserve) && current.unused == current.reserve - paid)
    }
    private fun requireRelation() {
        original.requireRunning()
        val sql = if (original.control.initialHistory == null) TestTerminalEpochSealSqlV1.relation else TestTerminalEpochSealSqlV1.relationWithActiveHistory
        requireTerminalSeal(jdbc.query(sql, { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") }, original.scope,
            original.routing.journalConfiguration.ordinaryPrefix + "%", original.routing.journalConfiguration.sealTerminalPrefix + "%", original.writer,
            original.control.cutoff, original.epoch, original.routing.journalConfiguration.ownerDeleteAll, original.routing.journalConfiguration.registeredAdminDelete,
            original.routing.journalConfiguration.registeredAdminBatchDelete, OwnerDeleteRows.array(TestRunPurgeOperationV1.FUTURE)).single())
    }
    private fun requireControls(): TestTerminalEpochSealRowsV1.Control {
        original.requireRunning()
        for (sql in listOf(TestRunSealingSqlV1.lockGlobalControl, TestRunSealingSqlV1.lockScopeControl)) requireTerminalSeal(jdbc.query(sql,
            { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") }, *original.registration.sealingControlArguments()).single())
        TestOrdinaryDrainActiveHistoryV1.requireCurrent(jdbc, original.drain, original.control.initialHistory)
        val sql = if (original.control.initialHistory == null) TestTerminalEpochSealSqlV1.control else TestTerminalEpochSealSqlV1.controlWithActiveHistory
        return jdbc.query(sql, { value, _ -> TestTerminalEpochSealRowsV1.Control(value, original) }, original.scope).single()
    }
    private fun requireLease() {
        original.requireRunning()
        requireTerminalSeal(jdbc.query(TestOrdinarySealSqlV1.lease, { value, _ -> TestOrdinaryDrainRowsV1.boolean(value, "valid") }, original.attemptId, original.leaseToken, original.scope).single())
        requireTerminalSeal(now().epochSecond < original.drain.manifestDenialRetainUntil())
    }
    private fun now(): Instant = checkNotNull(jdbc.queryForObject("SELECT clock_timestamp()", { value, _ -> value.getTimestamp(1).toInstant() })).also {
        requireTerminalSeal(it.epochSecond in 0..253_402_300_799L)
    }
    internal fun beginCounterLock(selected: JdbcTemplate) { retained(Stage.COUNTERS_REQUESTED, selected); stage = Stage.COUNTERS_LOCKING }
    internal fun requireCounterTransfer(locked: JdbcComplaintCapacityStore.LockedTestTerminalEpochSeal, selected: JdbcTemplate) { retained(Stage.TRANSFER, selected); requireTerminalSeal(counters === locked) }
    internal fun settleLockedLedger(selected: JdbcTemplate, ledger: ComplaintCapacityLedger, daily: ComplaintDailyAdmission, expectedDigest: ByteArray): ComplaintCapacityLedger {
        retained(Stage.TRANSFER, selected)
        ledger.configuration.requireMatching(expectedDigest)
        val policy = original.registration.process.consumers.capacityPolicy
        requireTerminalSeal(ledger.balance.hardLimit == policy.hardLimit && ledger.balance.creationLimit == policy.creationLimit && daily.dailyLimit == policy.dailyEnrollmentLimit &&
            run.unused.fitsWithin(ledger.balance.testReserved) &&
            (baselineActual(run) + TestTerminalCapacityChargesV1.SIDECAR.scaled(terminalSeals) + original.control.ordinaryHistoryCharge).fitsWithin(ledger.balance.actual) &&
            TestRunPurgeOperationV1.FUTURE.fitsWithin(ledger.balance.recoveryReserved))
        if (!spend) return ledger
        requireTerminalSeal(step === TestTerminalEpochSealStepV1.PREPARE && terminalSeals == 0L && TestTerminalCapacityChargesV1.SIDECAR.fitsWithin(run.unused))
        return ledger.spendTestReserve(expectedDigest, TestTerminalCapacityChargesV1.SIDECAR, ComplaintCapacityVector.ZERO)
    }
    internal fun failed(problem: Throwable): Nothing {
        original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled()
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }
    private fun retained(expected: Stage, selected: JdbcTemplate = jdbc) {
        phase.testTerminalEpochSealBoundary.requireRetained(this, selected)
        requireTerminalSeal(stage === expected && step === original.step && selected === jdbc)
    }
    private enum class Stage { NEW, CONTROLS, COUNTERS_REQUESTED, COUNTERS_LOCKING, BODY, TRANSFER, COMPLETE }
    companion object {
        internal fun execute(jdbc: JdbcTemplate, original: TestRunTerminalEpochSealV1): TestTerminalEpochSealOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.testTerminalEpochSealBoundary.requireOperation(original, jdbc)
                return TestTerminalEpochSealOperationV1(phase, jdbc, original).also { phase.testTerminalEpochSealBoundary.retain(it, jdbc); it.execute() }
            } catch (problem: Throwable) { original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled(); throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED) }
        }
        private fun hex(value: String): ByteArray = HexFormat.of().parseHex(value)
    }
}
