package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStateV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationSqlV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationTailV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationSqlV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.sql.Timestamp
import java.time.temporal.ChronoUnit

/** One fixed bounded phase. Its private constructor and original known commit/release are not replaceable by visible rows. */
internal class TestActiveOrdinarySealOperationV1 private constructor(
    private val phase: PersistencePhaseContext, private val jdbc: JdbcTemplate, internal val original: TestActiveOrdinarySealV1,
) {
    internal val step = original.step
    internal val path = original.path
    private var complete = false
    private var rowsClosed = false
    internal var rows: List<TestActiveCutoffPublicationRowV1> = emptyList()
        private set
    internal var intent: TestTerminalDurableRowV1? = null
        private set
    internal lateinit var current: TestActiveOrdinarySealRowsV1.Current
        private set
    internal lateinit var tail: TestNamespaceRecoveryRegistrationTailV1
        private set
    internal lateinit var history: CatalogTestRunActivationHistoryV1
        private set

    internal fun belongsTo(selected: PersistencePhaseContext) = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext) = belongsTo(selected) && complete
    internal fun requireReleased() { phase.testActiveOrdinarySeal.requireCommitted(this); requireConnectionFree() }
    internal fun requirePreparedRow(row: TestActiveCutoffPublicationRowV1) {
        requireReleased()
        requireActiveSeal(step === TestActiveOrdinarySealStepV1.EPOCH_PAGE && !rowsClosed && rows.any { it === row } && row.state == "PREPARED")
    }
    internal fun closeRows() { rowsClosed = true; rows.forEach { it.close() } }
    internal fun discardDetached() { closeRows(); intent?.close(); intent = null }
    private fun retained() { phase.testActiveOrdinarySeal.requireRetained(this, jdbc); requireActiveSeal(!complete) }

    private fun execute() {
        retained()
        if (step === TestActiveOrdinarySealStepV1.EVIDENCE) {
            // The normal phase still requires actual M/shared, guarded current resources and authenticated SQL session.
            // This historical evidence write intentionally takes NO leader/control/domain/receipt/counter row locks.
            persistEvidence(); retained(); complete = true; return
        }
        requireActiveSeal(jdbc.query(TestActiveOrdinarySealSqlV1.lockGlobal, { _, _ -> true }).single())
        requireActiveSeal(jdbc.query(TestActiveOrdinarySealSqlV1.lockScope, { _, _ -> true }, original.scope).single())
        requireActiveSeal(jdbc.query(TestActiveOrdinarySealSqlV1.lockRun, { _, _ -> true }, original.scope).single())
        requireActiveSeal(jdbc.query(TestActiveOrdinarySealSqlV1.lockSlot, { _, _ -> true }, original.scope).single())
        current = current()
        readAdmission()
        if (step === TestActiveOrdinarySealStepV1.READ) {
            requireActiveSeal(current.state == "RESERVED")
            control().use { it.requireIntent(null) }
        } else {
            original.requireRawReleased()
            if (step === TestActiveOrdinarySealStepV1.ACQUIRE) {
                val token = jdbc.query(TestActiveOrdinarySealSqlV1.acquire, { row, _ -> row.getLong("lease_token").also { requireActiveSeal(!row.wasNull()) } },
                    original.attemptId, original.scope, original.slot.operationToken, maxOf(original.slot.captureToken, original.slot.requestToken)).single()
                // A recovered history may have relinquished a lease newer than its capture token.
                // The fixed UPDATE increments current control under this same scope lock; compare
                // against that actual pre-acquisition read, never infer freshness from the handoff.
                requireActiveSeal(token > current.leaseToken)
                original.retainAcquiringToken(this, token)
            } else current.requireLease(original)
            if (step === TestActiveOrdinarySealStepV1.RENEW) requireActiveSeal(jdbc.update(TestActiveOrdinarySealSqlV1.renew, original.scope, original.attemptId, original.leaseToken) == 1)
            current = current(); current.requireLease(original)
            intent = if (current.state == "RESERVED") null else loadIntent()
            intent?.let { value ->
                requireActiveSeal(!value.binding.createdAt.isAfter(current.sampledAt) && value.frozenAt?.isAfter(current.sampledAt) != true)
            }
            control().use { it.requireIntent(intent) }
            original.requireObservedIntent(intent)
            when (step) {
                TestActiveOrdinarySealStepV1.ACQUIRE, TestActiveOrdinarySealStepV1.RENEW -> Unit
                TestActiveOrdinarySealStepV1.EPOCH_PAGE -> {
                    refuseOtherWriters()
                    val after = original.epochCursor()
                    rows = page(TestActiveCutoffPublicationSqlV1.epochPage,
                        original.scope, original.identity.writer, original.cutoff, after.first, after.second)
                }
                TestActiveOrdinarySealStepV1.KEY_PAGE -> {
                    refuseOtherWriters()
                    rows = page(TestActiveCutoffPublicationSqlV1.keyPage,
                        original.lowerCutoffKey, original.upperCutoffKey, original.keyCursor())
                }
                TestActiveOrdinarySealStepV1.CANONICAL -> prepare()
                TestActiveOrdinarySealStepV1.FREEZE -> freeze()
                TestActiveOrdinarySealStepV1.VERIFY -> verify()
                else -> throw TestActiveOrdinarySealExceptionV1()
            }
            retained(); current = current(); current.requireLease(original)
            requireActiveSeal(jdbc.query(TestActiveOrdinarySealSqlV1.lease, { row, _ -> TestActiveOrdinarySealRowsV1.boolean(row, "valid") },
                original.attemptId, original.leaseToken, original.scope).single())
        }
        retained(); complete = true
    }
    private fun current(): TestActiveOrdinarySealRowsV1.Current {
        retained()
        val args = original.identity.arguments()
        return try { jdbc.query(TestActiveOrdinarySealSqlV1.current, { row, _ -> TestActiveOrdinarySealRowsV1.Current(row, original) }, *args).single() }
        finally { args.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
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
    private fun control() = jdbc.query(TestActiveOrdinarySealSqlV1.sealControl, { row, _ -> TestActiveOrdinarySealRowsV1.Control.read(row) }, original.scope).single()
    private fun loadIntent(): TestTerminalDurableRowV1 = jdbc.query(TestActiveOrdinarySealSqlV1.slot, { row, _ ->
        TestActiveOrdinarySealRowsV1.intent(row, original)
    }, original.slot.operationToken, original.scope).single()
    private fun page(sql: String, vararg arguments: Any?): List<TestActiveCutoffPublicationRowV1> = checkNotNull(jdbc.query(sql,
        ResultSetExtractor { result ->
            val detached = ArrayList<TestActiveCutoffPublicationRowV1>(TestActiveCutoffPublicationSqlV1.PAGE_SIZE)
            try {
                while (result.next()) {
                    requireActiveSeal(detached.size < TestActiveCutoffPublicationSqlV1.PAGE_SIZE)
                    detached.add(TestActiveCutoffPublicationRowV1.read(result))
                }
                detached
            } catch (problem: Throwable) { detached.forEach { it.close() }; throw problem }
        }, *arguments))
    private fun refuseOtherWriters() {
        listOf(TestActiveCutoffPublicationSqlV1.writerBefore, TestActiveCutoffPublicationSqlV1.writerAfter).forEach { sql ->
            requireActiveSeal(jdbc.query(sql, { _, _ -> true }, original.scope, original.identity.writer).isEmpty()); retained()
        }
    }
    private fun prepare() {
        requireActiveSeal(intent == null && current.state == "RESERVED")
        val candidate = original.canonicalCandidate(this)
        val binding = candidate.binding
        requireActiveSeal(!binding.createdAt.isAfter(current.sampledAt))
        val bytes = candidate.canonicalBytes()
        try {
            requireActiveSeal(jdbc.update(TestActiveOrdinarySealSqlV1.canonical, binding.objectId, binding.objectKey, binding.routingKeyId, binding.preparingFencingToken,
                TestActiveOrdinarySealRowsV1.hex(binding.run.terminalEncodingSha256), bytes, TestActiveOrdinarySealRowsV1.hex(candidate.canonicalSha256),
                Timestamp.from(binding.retentionFloor), Timestamp.from(binding.createdAt), original.slot.operationToken, original.scope) == 1)
            requireActiveSeal(jdbc.update(TestActiveOrdinarySealSqlV1.prepareControl, binding.epochEndInclusive, binding.writerGeneration, binding.operationToken,
                binding.objectKey, bytes, TestActiveOrdinarySealRowsV1.hex(candidate.canonicalSha256), original.scope, original.slot.operationToken, original.attemptId, original.leaseToken) == 1)
        } finally { bytes.fill(0) }
        intent = loadIntent(); original.requireSameCanonical(candidate, checkNotNull(intent))
        control().use { it.requireIntent(intent) }
    }
    private fun freeze() {
        val durable = checkNotNull(intent)
        original.requireSameCanonical(original.preparedRow(), durable)
        val candidate = original.frozenCandidate(this)
        original.requireSameCanonical(candidate, durable)
        if (durable.state === TestTerminalDurableStateV1.CANONICAL) {
            val wire = checkNotNull(candidate.wireBytes()); val metadata = checkNotNull(candidate.metadataBytes())
            try {
                requireActiveSeal(jdbc.update(TestActiveOrdinarySealSqlV1.freeze, wire, TestActiveOrdinarySealRowsV1.hex(checkNotNull(candidate.wireSha256)), candidate.checksumSha256,
                    Timestamp.from(checkNotNull(candidate.retainUntil)), metadata, TestActiveOrdinarySealRowsV1.hex(checkNotNull(candidate.metadataSha256)),
                    Timestamp.from(checkNotNull(candidate.frozenAt)), original.slot.operationToken, original.scope, TestActiveOrdinarySealRowsV1.hex(candidate.canonicalSha256)) in 0..1)
            } finally { wire.fill(0); metadata.fill(0) }
        }
        intent?.close()
        intent = loadIntent() // Only stored winner bytes proceed, never proposed randomness or an UPDATE count.
        original.requireSameCanonical(candidate, checkNotNull(intent))
        requireActiveSeal(intent?.state === TestTerminalDurableStateV1.WIRE_FROZEN)
    }
    private fun verify() {
        val durable = checkNotNull(intent)
        original.requireSameFrozen(original.frozenRow(), durable)
        val proof = original.providerProof(this)
        requireActiveSeal(!proof.verifiedAt.isAfter(current.sampledAt) && !proof.lastModified.isAfter(current.sampledAt) && proof.retainUntil.isAfter(current.sampledAt))
        control().use { before ->
            if (before.state == "SEAL_VERIFIED") { before.requireProof(durable, proof); return }
            requireActiveSeal(before.state == "SEAL_PREPARED")
        }
        val bytes = proof.canonicalBytes(durable)
        try {
            requireActiveSeal(jdbc.update(TestActiveOrdinarySealSqlV1.verifyControl, proof.version, TestActiveOrdinarySealRowsV1.hex(checkNotNull(durable.wireSha256)),
                Timestamp.from(proof.retainUntil), Timestamp.from(proof.verifiedAt), bytes, TestActiveOrdinarySealRowsV1.hex(Sha256.hex(bytes)), original.scope,
                durable.binding.operationToken, TestActiveOrdinarySealRowsV1.hex(durable.canonicalSha256), original.attemptId, original.leaseToken) == 1)
        } finally { bytes.fill(0) }
        control().use { it.requireProof(durable, proof) }
    }
    private fun persistEvidence() {
        val work = original.evidenceWork(this)
        val observed = work.evidence()
        val bytes = work.verificationBytes()
        val preimage = work.row.immutableArguments()
        try {
            val before = jdbc.query(TestActiveCutoffPublicationSqlV1.lock, { row, _ -> TestActiveCutoffPublicationRowV1.read(row) }, work.row.eventId).single()
            before.use {
                requireActiveSeal(before.sameImmutable(work.row))
                val inserted = before.state == "PREPARED"
                val winner = if (inserted) jdbc.query(TestActiveCutoffPublicationSqlV1.verified, { row, _ -> TestActiveCutoffPublicationRowV1.read(row) },
                    observed.versionId, TestActiveOrdinarySealRowsV1.hex(observed.wireSha256), Timestamp.from(observed.lastModified), Timestamp.from(observed.retainUntil),
                    Timestamp.from(observed.verifiedAt.truncatedTo(ChronoUnit.MICROS)), bytes, work.verificationHash(), *preimage).single() else before
                try {
                    requireActiveSeal(winner.sameImmutable(work.row) && winner.state in setOf("VERIFIED", "APPLIED"))
                    val proof = checkNotNull(winner.proof)
                    proof.requireParsed(work.event, original.routing)
                    proof.requireObservation(observed, bytes, inserted)
                } finally { if (winner !== before) winner.close() }
            }
        } finally { bytes.fill(0); preimage.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
    }
    companion object {
        fun execute(jdbc: JdbcTemplate, original: TestActiveOrdinarySealV1): TestActiveOrdinarySealOperationV1 {
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            phase.testActiveOrdinarySeal.requireOperation(original, jdbc)
            val operation = TestActiveOrdinarySealOperationV1(phase, jdbc, original)
            phase.testActiveOrdinarySeal.retain(operation, jdbc)
            try {
                operation.execute()
                if (operation.step !in setOf(TestActiveOrdinarySealStepV1.CANONICAL, TestActiveOrdinarySealStepV1.FREEZE)) {
                    operation.intent?.close(); operation.intent = null
                }
                return operation
            } catch (failure: Throwable) { operation.discardDetached(); throw failure }
        }
    }
}

internal enum class TestActiveOrdinarySealStepV1 { READ, ACQUIRE, RENEW, EPOCH_PAGE, KEY_PAGE, EVIDENCE, CANONICAL, FREEZE, VERIFY }
