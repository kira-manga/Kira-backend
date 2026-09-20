package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDailyAdmission
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteTuple
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.OwnerDeleteCapacityCharges
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStateV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProgressV1
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteVerificationOperation
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteRows
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteVerificationCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID

/** Fixed ordered SQL only. No provider, KMS, codecs with remote ports, caller SQL or work callback. */
internal class TestOrdinarySealOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val original: TestRunOrdinarySealV1,
) {
    internal val path = PersistencePhasePath.COMPLAINT_TEST_ORDINARY_SEAL
    internal val step = original.step
    internal lateinit var cut: TestOrdinarySealRowsV1.Control
        private set
    internal lateinit var manifest: TestOrdinarySealManifestV1
        private set
    internal var row: TestTerminalDurableRowV1? = null
        private set
    private var stage = Stage.NEW
    private var counters: JdbcComplaintCapacityStore.LockedTestOrdinarySeal? = null
    private lateinit var run: Run
    private var spend = false
    private var installationObservation: TestTerminalProgressV1? = null

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = belongsTo(selected) && stage === Stage.COMPLETE
    internal fun requireReleased() { phase.testOrdinarySeal.requireCommitted(this); requireConnectionFree() }
    internal fun releasedInstallationObservation(): TestTerminalProgressV1 {
        requireOrdinarySeal(step === TestOrdinarySealStepV1.VERIFY && stage === Stage.COMPLETE)
        requireReleased()
        return installationObservation ?: throw TestOrdinarySealExceptionV1()
    }

    private fun execute() {
        retained(Stage.NEW)
        stage = Stage.CONTROLS
        for (sql in listOf(TestRunSealingSqlV1.lockGlobalControl, TestRunSealingSqlV1.lockScopeControl)) {
            requireOrdinarySeal(jdbc.query(sql, { value, _ -> TestOrdinarySealRowsV1.boolean(value, "valid") }, *original.registration.sealingControlArguments()).single())
            retained(Stage.CONTROLS)
        }
        cut = control()
        if (step === TestOrdinarySealStepV1.CAPTURE) {
            val lease = jdbc.query(TestOrdinarySealSqlV1.acquire, { value, _ -> value.getLong("lease_token") },
                original.attemptId, original.leaseDurationMillis, original.scope).single()
            requireOrdinarySeal(lease > 0)
            original.retainLease(this, lease)
        }
        requireLease()
        stage = Stage.COUNTERS_REQUESTED
        counters = JdbcComplaintCapacityStore(jdbc, original.registration.process.consumers.capacityPolicy.digestBytes()).lockForTestOrdinarySeal(this)
        retained(Stage.COUNTERS_LOCKING)
        stage = Stage.RUN
        run = jdbc.query(TestRunSealingSqlV1.lockRun, { value, _ ->
            requireOrdinarySeal(TestOrdinarySealRowsV1.boolean(value, "valid") && value.getString("state") == "SEALED")
            Run(checkNotNull(value.getTimestamp("sealed_at")).toInstant(), vector(value, "original_reserve"), vector(value, "unused_reserve"),
                value.getLong("installation_limit"), value.getLong("enrolled_count"))
        }, *original.registration.sealingRunArguments()).single()
        retained(Stage.RUN)
        stage = Stage.AUDIT
        requireOrdinarySeal(jdbc.query(TestRunSealingSqlV1.lockAudit, { value, _ -> TestOrdinarySealRowsV1.boolean(value, "valid") },
            *original.registration.sealingAuditArguments(run.sealedAt)).single())
        retained(Stage.AUDIT)
        val audit = TestTerminalCapacityChargesV1.AUDIT
        requireOrdinarySeal(run.unused[ComplaintCapacityCounter.AUDIT_ROWS] == run.reserve[ComplaintCapacityCounter.AUDIT_ROWS] - audit[ComplaintCapacityCounter.AUDIT_ROWS] &&
            audit.fitsWithin(run.reserve - run.unused))
        stage = Stage.CONTROLS
        if (step === TestOrdinarySealStepV1.CAPTURE && cut.sequence == 0L) {
            requireOrdinarySeal(jdbc.update(TestOrdinarySealSqlV1.capture, original.captureId, original.scope, original.attemptId, original.leaseToken) == 1)
            cut = control()
        }
        requireOrdinarySeal(cut.sequence == 1L && cut.cutoff > 0 && !checkNotNull(cut.capturedAt).isBefore(run.sealedAt) && !checkNotNull(cut.capturedAt).isAfter(now()))
        if (step !== TestOrdinarySealStepV1.CAPTURE) cut.requireSameCut(original.capturedCut())
        stage = Stage.SIDECAR
        row = loadSidecar()
        cut.requireSidecar(row)
        row?.let { requireOrdinarySeal((audit + TestTerminalCapacityChargesV1.SIDECAR).fitsWithin(run.reserve - run.unused)) }

        stage = Stage.MANIFEST
        manifest = readManifest()
        if (step !== TestOrdinarySealStepV1.CAPTURE) manifest.requireSame(original.capturedManifest())
        when (step) {
            TestOrdinarySealStepV1.CAPTURE -> Unit
            TestOrdinarySealStepV1.PREPARE -> prepare()
            TestOrdinarySealStepV1.FREEZE -> freeze()
            TestOrdinarySealStepV1.VERIFY -> {
                verify()
                installationObservation = readInstallationSource()
            }
        }
        stage = Stage.TRANSFER
        checkNotNull(counters).settle(this)
        requireOrdinarySeal(checkNotNull(counters).completedFor(this))
        if (spend) {
            requireOrdinarySeal(jdbc.update(TestRunSealingSqlV1.spendRun, *original.registration.sealingRunArguments(),
                OwnerDeleteRows.array(run.unused - TestTerminalCapacityChargesV1.SIDECAR), Timestamp.from(run.sealedAt), OwnerDeleteRows.array(run.unused)) == 1)
        }
        requireLease() // A late provider or SQL completion never rehabilitates an expired/replaced database fence.
        retained(Stage.TRANSFER)
        stage = Stage.COMPLETE
    }

    private fun control(): TestOrdinarySealRowsV1.Control {
        retained(Stage.CONTROLS)
        return jdbc.query(TestOrdinarySealSqlV1.control, { value, _ -> TestOrdinarySealRowsV1.Control(value) }, original.scope).single().also { retained(Stage.CONTROLS) }
    }
    private fun loadSidecar(): TestTerminalDurableRowV1? = jdbc.query(TestOrdinarySealSqlV1.sidecar, { value, _ ->
        original.ownRow(TestOrdinarySealRowsV1.sidecar(value, original, cut))
    }, original.scope, original.routing.journalConfiguration.sealTerminalPrefix + "%").let { values ->
        retained(Stage.SIDECAR); requireOrdinarySeal(values.size <= 1); values.singleOrNull()
    }

    private fun prepare() {
        stage = Stage.SIDECAR
        requireOrdinarySeal(row == null) // A concurrent canonical winner is recovered by a NEW original attempt, never replaced here.
        val candidate = original.canonicalCandidate(this)
        requireOrdinarySeal(candidate.binding.preparingFencingToken == original.leaseToken && candidate.binding.operationToken == cut.token.toString() &&
            candidate.binding.epochEndInclusive == cut.cutoff && candidate.state === TestTerminalDurableStateV1.CANONICAL)
        val b = candidate.binding
        val bytes = candidate.canonicalBytes()
        try {
            requireOrdinarySeal(jdbc.update(TestOrdinarySealSqlV1.insert, b.operationToken, b.run.dataScopeId, b.objectId, b.objectKey, b.routingKeyId,
                b.writerGeneration, b.epochEndInclusive, b.preparingFencingToken, b.run.activationCatalogGeneration,
                TestOrdinarySealRowsV1.hex(b.run.activationCatalogSha256), TestOrdinarySealRowsV1.hex(b.run.configurationSha256),
                TestOrdinarySealRowsV1.hex(b.journalConfigurationSha256), TestOrdinarySealRowsV1.hex(b.run.terminalEncodingSha256),
                bytes, TestOrdinarySealRowsV1.hex(candidate.canonicalSha256), Timestamp.from(b.retentionFloor), Timestamp.from(b.createdAt)) == 1)
            requireOrdinarySeal(jdbc.update(TestOrdinarySealSqlV1.prepareControl, b.epochEndInclusive, b.writerGeneration, b.operationToken,
                b.objectKey, bytes, TestOrdinarySealRowsV1.hex(candidate.canonicalSha256), original.scope, cut.token, original.attemptId, original.leaseToken) == 1)
        } finally { bytes.fill(0) }
        spend = true
        requireOrdinarySeal(TestTerminalCapacityChargesV1.SIDECAR.fitsWithin(run.unused))
        row = checkNotNull(loadSidecar())
        original.requireSameCanonical(candidate, checkNotNull(row))
        stage = Stage.CONTROLS
        cut = control()
        cut.requireSidecar(row)
    }

    private fun freeze() {
        stage = Stage.SIDECAR
        val durable = checkNotNull(row)
        original.requireSameCanonical(original.preparedRow(), durable)
        val candidate = original.frozenCandidate(this)
        original.requireSameCanonical(candidate, durable)
        if (durable.state === TestTerminalDurableStateV1.CANONICAL) {
            val wire = checkNotNull(candidate.wireBytes())
            val metadata = checkNotNull(candidate.metadataBytes())
            try {
                val count = jdbc.update(TestOrdinarySealSqlV1.freeze, wire, TestOrdinarySealRowsV1.hex(checkNotNull(candidate.wireSha256)), candidate.checksumSha256,
                    Timestamp.from(checkNotNull(candidate.retainUntil)), metadata, TestOrdinarySealRowsV1.hex(checkNotNull(candidate.metadataSha256)),
                    Timestamp.from(checkNotNull(candidate.frozenAt)), candidate.binding.operationToken, original.scope, TestOrdinarySealRowsV1.hex(candidate.canonicalSha256))
                requireOrdinarySeal(count in 0..1)
            } finally { wire.fill(0); metadata.fill(0) }
        }
        // Never use the proposed randomness or UPDATE count as the winner. Reload the immutable row under the original fence.
        row = checkNotNull(loadSidecar())
        original.requireSameCanonical(candidate, checkNotNull(row))
        requireOrdinarySeal(row?.state === TestTerminalDurableStateV1.WIRE_FROZEN)
    }

    private fun verify() {
        stage = Stage.SIDECAR
        val durable = checkNotNull(row)
        original.requireSameFrozen(original.frozenRow(), durable)
        val proof = original.providerProof(this)
        val now = now()
        requireOrdinarySeal(!proof.verifiedAt.isAfter(now) && !proof.lastModified.isAfter(now) && proof.retainUntil.isAfter(now))
        if (cut.sealState == "SEAL_VERIFIED") {
            cut.requireProof(durable, proof)
            return
        }
        requireOrdinarySeal(cut.sealState == "SEAL_PREPARED")
        val bytes = proof.canonicalBytes(durable)
        try {
            requireOrdinarySeal(jdbc.update(TestOrdinarySealSqlV1.verifyControl, proof.version, TestOrdinarySealRowsV1.hex(checkNotNull(durable.wireSha256)),
                Timestamp.from(proof.retainUntil), Timestamp.from(proof.verifiedAt), bytes, TestOrdinarySealRowsV1.hex(me.manga.kira.backend.common.Sha256.hex(bytes)),
                original.scope, durable.binding.operationToken, TestOrdinarySealRowsV1.hex(durable.canonicalSha256), original.attemptId, original.leaseToken) == 1)
        } finally { bytes.fill(0) }
        stage = Stage.CONTROLS
        cut = control()
        cut.requireProof(durable, proof)
    }

    private fun readManifest(): TestOrdinarySealManifestV1 {
        val builder = TestOrdinarySealManifestV1.Builder(original.routing, cut.cutoff, original.codecAttempt)
        repeat(2) { pass ->
            requireCompleteRelation()
            var after: String? = null
            while (true) {
                retained(Stage.MANIFEST)
                val page = jdbc.query(TestOrdinarySealSqlV1.manifestPage, { value, _ ->
                    checkNotNull(value.getString("event_id")) to checkNotNull(value.getString("object_key"))
                }, original.scope, original.routing.journalConfiguration.ordinaryPrefix + "%", original.routing.journalConfiguration.sealTerminalPrefix + "%", after, after)
                retained(Stage.MANIFEST)
                if (page.isEmpty()) break
                page.forEach { (id, key) ->
                    requireOrdinarySeal(after?.let { key > it } != false)
                    addEntry(builder, id, key)
                    after = key
                }
            }
            requireCompleteRelation()
            if (pass == 0) builder.beginSecond()
        }
        return builder.finish()
    }

    /** Two entire reservation-led reads under the already-held SEALED run lock, not a state-filtered credential inventory. */
    private fun readInstallationSource(): TestTerminalProgressV1 {
        stage = Stage.INSTALLATIONS
        val binding = installationBinding()
        val source = TestInstallationSourceV1(original.routing.journalConfiguration, original.runContext, binding, run.installationLimit, run.enrolledCount)
        repeat(2) { pass ->
            val observed = if (pass == 0) binding else installationBinding()
            source.beginPass(observed, now().epochSecond)
            requireCompleteCredentials()
            var after: UUID? = null
            while (true) {
                retained(Stage.INSTALLATIONS)
                val page = jdbc.query(TestInstallationSourceSqlV1.page, { value, _ -> TestInstallationSourceV1.Row.read(value) }, original.scope, after, after)
                retained(Stage.INSTALLATIONS)
                if (page.isEmpty()) break
                page.forEach { value ->
                    retained(Stage.INSTALLATIONS)
                    source.entry(value)
                    after = UUID.fromString(value.id)
                }
            }
            requireCompleteCredentials()
            source.endPass(installationBinding(), now().epochSecond)
            retained(Stage.INSTALLATIONS)
        }
        return source.finish().also { retained(Stage.INSTALLATIONS) }
    }

    private fun installationBinding(): TestInstallationSourceV1.Binding {
        retained(Stage.INSTALLATIONS)
        return jdbc.query(TestRunSealingSqlV1.readScopeControl, { value, _ ->
            requireOrdinarySeal(TestOrdinarySealRowsV1.boolean(value, "valid") && value.getLong("lease_token") == original.leaseToken)
            TestInstallationSourceV1.Binding(checkNotNull(value.getObject("database_identity", UUID::class.java)).toString(),
                checkNotNull(value.getObject("restore_identity", UUID::class.java)).toString(), value.getLong("desired_generation"), value.getLong("lease_token"))
        }, *original.registration.sealingControlArguments()).single().also { retained(Stage.INSTALLATIONS) }
    }

    private fun requireCompleteCredentials() {
        retained(Stage.INSTALLATIONS)
        requireOrdinarySeal(jdbc.query(TestInstallationSourceSqlV1.credentialsComplete, { value, _ -> TestOrdinarySealRowsV1.boolean(value, "valid") }, original.scope).single())
        retained(Stage.INSTALLATIONS)
    }

    private fun addEntry(builder: TestOrdinarySealManifestV1.Builder, id: String, key: String) {
        retained(Stage.MANIFEST)
        val receipt = jdbc.query(TestOrdinarySealSqlV1.receipt, { value, _ ->
            requireOrdinarySeal(value.getString("actor_kind") == "INSTALLATION")
            Triple(OwnerDeleteRows.Receipt(value), checkNotNull(value.getString("stamp")), checkNotNull(value.getTimestamp("completed_at")).toInstant())
        }, id).single()
        val publication = jdbc.query(TestOrdinarySealSqlV1.publication, { value, _ ->
            Triple(OwnerDeleteRows.Publication(value), checkNotNull(value.getString("stamp")), checkNotNull(value.getTimestamp("applied_at")).toInstant())
        }, id).single()
        retained(Stage.MANIFEST)
        val p = publication.first
        val event = TestOwnerDeleteJournalCodecV1.restoreCanonical(original.routing, p.bytes, p.routingKey)
        p.requireEvent(event)
        requireOrdinarySeal(p.scope == original.scope && p.writer.toString() == original.writer && p.objectKey == key && p.epoch in 1..cut.cutoff &&
            p.state == "APPLIED" && !p.createdAt.isAfter(run.sealedAt))
        val tuple = ComplaintOwnerDeleteTuple(ScopedInstallationId(event.tuple.actorId, event.tuple.scope), event.tuple.operationKey,
            event.complaintIds().single(), event.tuple.fingerprintBytes())
        val r = receipt.first
        requireOrdinarySeal(r.valid && r.matches(tuple) && r.state == "COMPLETED" && r.completed() === ComplaintOwnerDeleteReceipt.Applied &&
            r.authorizedAt == p.createdAt && r.externalEvent == p.eventId && r.externalEpoch == p.epoch && r.externalVersion == p.objectVersion && r.externalHash.contentEquals(p.ciphertextHash))
        val proof = TestOwnerDeleteVerificationCodecV1(original.routing).parse(checkNotNull(p.verificationBytes), event)
        ComplaintOwnerDeleteVerificationOperation.requireColumns(proof, p)
        val at = now()
        requireOrdinarySeal(!Instant.parse(proof.verifiedAt).isAfter(at) && Instant.parse(proof.retainUntil).isAfter(at) &&
            !publication.third.isBefore(Instant.parse(proof.verifiedAt)) && !publication.third.isAfter(at) &&
            !receipt.third.isBefore(publication.third) && !receipt.third.isAfter(at))
        val appliedStamp = jdbc.query(TestOrdinarySealSqlV1.applied, { value, _ ->
            requireOrdinarySeal(value.getString("event_id") == p.eventId && value.getObject("data_scope_id", UUID::class.java) == p.scope &&
                TestOrdinarySealRowsV1.boolean(value, "test_only") && value.getObject("writer_generation", UUID::class.java) == p.writer &&
                value.getLong("journal_epoch") == p.epoch && value.getString("event_kind") == "OWNER_DELETE" && value.getInt("target_count") == 1 &&
                value.getString("object_key") == p.objectKey && value.getString("object_version") == p.objectVersion &&
                value.getBytes("ciphertext_hash").contentEquals(p.ciphertextHash) && !value.getTimestamp("applied_at").toInstant().isBefore(Instant.parse(proof.verifiedAt)) &&
                !value.getTimestamp("applied_at").toInstant().isAfter(at))
            checkNotNull(value.getString("stamp"))
        }, id).single()
        val recoveryStamp = jdbc.query(TestOrdinarySealSqlV1.recovery, { value, _ ->
            val recovery = OwnerDeleteRows.Recovery(value, original.routing.journalConfiguration.scope, id)
            requireOrdinarySeal(recovery.convertedAt != null && !recovery.convertedAt.isAfter(at) &&
                !recovery.convertedAt.isBefore(Instant.parse(proof.verifiedAt)) &&
                recovery.used[ComplaintCapacityCounter.JOURNAL_APPLIED] == 1L && OwnerDeleteCapacityCharges.APPLIED.fitsWithin(recovery.used))
            checkNotNull(value.getString("stamp"))
        }, id).single()
        retained(Stage.MANIFEST)
        builder.entry(p.objectKey, checkNotNull(p.objectVersion), java.util.HexFormat.of().formatHex(checkNotNull(p.ciphertextHash)),
            listOf(publication.second, receipt.second, appliedStamp, recoveryStamp).joinToString(":"))
    }

    private fun requireCompleteRelation() {
        retained(Stage.MANIFEST)
        requireOrdinarySeal(jdbc.query(TestOrdinarySealSqlV1.completeRelation, { value, _ -> TestOrdinarySealRowsV1.boolean(value, "valid") },
            original.scope, original.routing.journalConfiguration.ordinaryPrefix + "%", original.routing.journalConfiguration.sealTerminalPrefix + "%").single())
        retained(Stage.MANIFEST)
    }
    private fun now(): Instant = checkNotNull(jdbc.queryForObject("SELECT clock_timestamp()", { value, _ -> value.getTimestamp(1).toInstant() }))
    private fun requireLease() {
        requireOrdinarySeal(jdbc.query(TestOrdinarySealSqlV1.lease, { value, _ -> TestOrdinarySealRowsV1.boolean(value, "valid") },
            original.attemptId, original.leaseToken, original.scope).single())
    }
    internal fun beginCounterLock(selected: JdbcTemplate) { retained(Stage.COUNTERS_REQUESTED, selected); stage = Stage.COUNTERS_LOCKING }
    internal fun requireCounterTransfer(locked: JdbcComplaintCapacityStore.LockedTestOrdinarySeal, selected: JdbcTemplate) {
        retained(Stage.TRANSFER, selected); requireOrdinarySeal(counters === locked)
    }
    internal fun settleLockedLedger(selected: JdbcTemplate, ledger: ComplaintCapacityLedger, daily: ComplaintDailyAdmission, expectedDigest: ByteArray): ComplaintCapacityLedger {
        retained(Stage.TRANSFER, selected)
        ledger.configuration.requireMatching(expectedDigest)
        val policy = original.registration.process.consumers.capacityPolicy
        requireOrdinarySeal(ledger.balance.hardLimit == policy.hardLimit && ledger.balance.creationLimit == policy.creationLimit &&
            daily.dailyLimit == policy.dailyEnrollmentLimit && run.unused.fitsWithin(ledger.balance.testReserved))
        return if (spend) ledger.spendTestReserve(expectedDigest, TestTerminalCapacityChargesV1.SIDECAR, ComplaintCapacityVector.ZERO) else ledger
    }
    internal fun failed(problem: Throwable): Nothing {
        original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled()
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }
    private fun retained(expected: Stage, selected: JdbcTemplate = jdbc) {
        phase.testOrdinarySeal.requireRetained(this, selected)
        requireOrdinarySeal(stage === expected && step === original.step && selected === jdbc)
    }
    private class Run(val sealedAt: Instant, val reserve: ComplaintCapacityVector, val unused: ComplaintCapacityVector,
        val installationLimit: Long, val enrolledCount: Long)
    private enum class Stage { NEW, CONTROLS, COUNTERS_REQUESTED, COUNTERS_LOCKING, RUN, AUDIT, SIDECAR, MANIFEST, INSTALLATIONS, TRANSFER, COMPLETE }
    companion object {
        internal fun execute(jdbc: JdbcTemplate, original: TestRunOrdinarySealV1): TestOrdinarySealOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.testOrdinarySeal.requireOperation(original, jdbc)
                return TestOrdinarySealOperationV1(phase, jdbc, original).also { phase.testOrdinarySeal.retain(it, jdbc); it.execute() }
            } catch (problem: Throwable) { original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled(); throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED) }
        }
        private fun vector(row: ResultSet, name: String): ComplaintCapacityVector {
            val value = checkNotNull(row.getArray(name))
            try {
                requireOrdinarySeal(value.baseType == Types.BIGINT)
                val raw = value.array as? Array<*> ?: throw TestOrdinarySealExceptionV1()
                requireOrdinarySeal(raw.size == ComplaintCapacityEncoding.WIDTH)
                return ComplaintCapacityVector.of(LongArray(raw.size) { checkNotNull(raw[it] as? Long) })
            } finally { value.free() }
        }
    }
}
