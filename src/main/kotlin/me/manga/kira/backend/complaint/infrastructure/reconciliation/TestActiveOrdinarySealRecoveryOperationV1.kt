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

/** Closed recovery phase; no arbitrary callback, request/charge, canonical writer or prior-success issuer. */
internal class TestActiveOrdinarySealRecoveryOperationV1 private constructor(
    private val phase: PersistencePhaseContext, private val jdbc: JdbcTemplate,
    internal val original: TestActiveOrdinarySealRecoveryV1,
) {
    internal val step = original.step
    internal val path = original.path
    private var complete = false
    internal lateinit var current: TestActiveOrdinarySealRecoveryRowsV1.Current
        private set
    internal lateinit var accounting: TestActiveSealRecoveryAccountingV1
        private set
    internal lateinit var tail: TestNamespaceRecoveryRegistrationTailV1
        private set
    internal lateinit var history: CatalogTestRunActivationHistoryV1
        private set
    internal var intent: TestTerminalDurableRowV1? = null
        private set

    internal fun belongsTo(selected: PersistencePhaseContext) = phase === selected
    internal fun completedFor(selected: PersistencePhaseContext) = belongsTo(selected) && complete
    internal fun requireReleased() { phase.testActiveOrdinarySealRecovery.requireCommitted(this); requireConnectionFree() }
    internal fun discardDetached() { intent?.close(); intent = null }
    private fun retained() { phase.testActiveOrdinarySealRecovery.requireRetained(this, jdbc); requireActiveSealRecovery(!complete) }

    private fun execute() {
        retained()
        requireActiveSealRecovery(jdbc.query(TestActiveOrdinarySealRecoverySqlV1.lockGlobal, { _, _ -> true }).single())
        // Fixed global -> P22 -> scope -> run -> paid slot order, no E and no provider work under these locks.
        val counters = checkNotNull(jdbc.query(TestActiveOrdinarySealRecoverySqlV1.lockCounters,
            ResultSetExtractor { rows -> TestActiveSealRecoveryAccountingV1.readCounters(rows, original.process.consumers.capacityPolicy) }))
        requireActiveSealRecovery(jdbc.query(TestActiveOrdinarySealRecoverySqlV1.lockScope, { _, _ -> true }, original.scope).single())
        requireActiveSealRecovery(jdbc.query(TestActiveOrdinarySealRecoverySqlV1.lockRun, { _, _ -> true }, original.scope).single())
        requireActiveSealRecovery(jdbc.query(TestActiveOrdinarySealRecoverySqlV1.lockSlot, { _, _ -> true }, original.scope).single())
        current = current()
        accounting = accounting(counters)
        original.previous?.let { previous ->
            previous.current.requireSamePhysical(current)
            previous.accounting.requireSame(accounting)
        }
        readAdmission()
        val before = current
        intent = loadIntent()
        requireIntentTimes()
        control().use { it.requireIntent(intent); requireActiveSealRecovery(it.state == "SEAL_PREPARED") }
        requireEmpty()
        if (step !== TestActiveOrdinarySealRecoveryStepV1.READ) {
            original.requireRawReleased()
            original.requireObservedIntent(intent)
            if (step === TestActiveOrdinarySealRecoveryStepV1.ACQUIRE) {
                val token = jdbc.query(TestActiveOrdinarySealRecoverySqlV1.acquire,
                    { row, _ -> row.getLong("lease_token").also { requireActiveSealRecovery(!row.wasNull()) } },
                    original.attemptId, original.scope, current.operationToken, current.leaseToken).single()
                original.retainAcquiringToken(this, token)
            } else current.requireLease(original)
            if (step === TestActiveOrdinarySealRecoveryStepV1.RENEW) {
                requireActiveSealRecovery(jdbc.update(TestActiveOrdinarySealRecoverySqlV1.renew, original.scope, original.attemptId, original.leaseToken) == 1)
            }
            current = current(); current.requireLease(original)
            when (step) {
                TestActiveOrdinarySealRecoveryStepV1.ACQUIRE, TestActiveOrdinarySealRecoveryStepV1.RENEW,
                TestActiveOrdinarySealRecoveryStepV1.EMPTY -> Unit
                TestActiveOrdinarySealRecoveryStepV1.FREEZE -> freeze()
                TestActiveOrdinarySealRecoveryStepV1.VERIFY -> verify()
                else -> throw TestActiveOrdinarySealRecoveryExceptionV1()
            }
        }
        retained(); current = current(); before.requireSameStable(current)
        if (step !== TestActiveOrdinarySealRecoveryStepV1.FREEZE) before.requireSamePaid(current)
        if (step === TestActiveOrdinarySealRecoveryStepV1.READ || step === TestActiveOrdinarySealRecoveryStepV1.EMPTY) before.requireSamePhysical(current)
        requireIntentTimes()
        if (step !== TestActiveOrdinarySealRecoveryStepV1.VERIFY) control().use {
            it.requireIntent(intent); requireActiveSealRecovery(it.state == "SEAL_PREPARED")
        }
        val repeatedCounters = checkNotNull(jdbc.query(TestActiveOrdinarySealRecoverySqlV1.readCounters,
            ResultSetExtractor { rows -> TestActiveSealRecoveryAccountingV1.readCounters(rows, original.process.consumers.capacityPolicy) }))
        accounting.requireSame(accounting(repeatedCounters))
        if (step !== TestActiveOrdinarySealRecoveryStepV1.READ) {
            current.requireLease(original)
            requireActiveSealRecovery(jdbc.query(TestActiveOrdinarySealRecoverySqlV1.lease,
                { row, _ -> TestActiveOrdinarySealRecoveryRowsV1.boolean(row, "valid") }, original.attemptId, original.leaseToken, original.scope).single())
        }
        retained(); complete = true
    }

    private fun current(): TestActiveOrdinarySealRecoveryRowsV1.Current {
        retained()
        val args = original.identity.arguments()
        return try { jdbc.query(TestActiveOrdinarySealRecoverySqlV1.current,
            { row, _ -> TestActiveOrdinarySealRecoveryRowsV1.Current(row) }, *args).single() }
        finally { args.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
    }
    private fun accounting(counters: me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationProjectionCountersV1): TestActiveSealRecoveryAccountingV1 {
        val run = jdbc.query(TestActiveOrdinarySealRecoverySqlV1.runAccounting, { row, _ ->
            TestActiveSealRecoveryRunV1(row, original.identity, original.routing.journalConfiguration.declaration().limits.capacity.maximumRetainedVersions)
        }, original.scope).single()
        val counts = jdbc.query(TestActiveOrdinarySealRecoverySqlV1.installationCounts, { row, _ ->
            val ids = row.getLong("ids").also { requireActiveSealRecovery(!row.wasNull()) }
            val credentials = row.getLong("credentials").also { requireActiveSealRecovery(!row.wasNull()) }
            ids to credentials
        }, original.scope, original.scope).single()
        return TestActiveSealRecoveryAccountingV1.bind(counters, run, counts.first, counts.second)
    }
    private fun readAdmission() {
        val args = original.identity.tailArguments()
        tail = try { jdbc.query(TestNamespaceRecoveryRegistrationSqlV1.tail,
            { row, _ -> TestNamespaceRecoveryRegistrationTailV1(row, original.scope) }, *args).single() }
        finally { args.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
        val historyArgs = original.historyArguments()
        history = try { checkNotNull(jdbc.query(CatalogTestRunActivationSqlV1.lockRecoveryRegistrationHistory.removeSuffix("\nFOR UPDATE"),
            ResultSetExtractor { rows -> CatalogTestRunActivationHistoryV1.readActiveCurrent(rows, original.identity.generation,
                original.process.catalogReadback.chainPolicy.limits.maximumGenerations) }, *historyArgs)) }
        finally { historyArgs.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
        original.requireAdmissionComparisons(this, tail, history)
    }
    private fun requireEmpty() {
        listOf(TestActiveOrdinarySealRecoverySqlV1.writerBefore, TestActiveOrdinarySealRecoverySqlV1.writerAfter,
            TestActiveOrdinarySealRecoverySqlV1.epochPresent).forEach { sql ->
            requireActiveSealRecovery(jdbc.query(sql, { _, _ -> true }, original.scope, original.identity.writer).isEmpty()); retained()
        }
        requireActiveSealRecovery(jdbc.query(TestActiveOrdinarySealRecoverySqlV1.keyPresent, { _, _ -> true },
            original.lowerCutoffKey, original.upperCutoffKey).isEmpty())
    }
    private fun control() = jdbc.query(TestActiveOrdinarySealRecoverySqlV1.sealControl,
        { row, _ -> TestActiveOrdinarySealRowsV1.Control.read(row) }, original.scope).single()
    private fun loadIntent() = jdbc.query(TestActiveOrdinarySealRecoverySqlV1.slot,
        { row, _ -> TestActiveOrdinarySealRecoveryRowsV1.intent(row, original, current) }, current.operationToken, original.scope).single()
    private fun requireIntentTimes() {
        val row = checkNotNull(intent)
        requireActiveSealRecovery(!row.binding.createdAt.isAfter(current.sampledAt) && row.frozenAt?.isAfter(current.sampledAt) != true)
    }
    private fun freeze() {
        val durable = checkNotNull(intent)
        requireActiveSealRecovery(durable.state === TestTerminalDurableStateV1.CANONICAL)
        original.requireSameCanonical(original.preparedRow(), durable)
        val candidate = original.frozenCandidate(this)
        original.requireSameCanonical(candidate, durable)
        val wire = checkNotNull(candidate.wireBytes()); val metadata = checkNotNull(candidate.metadataBytes())
        try {
            requireActiveSealRecovery(jdbc.update(TestActiveOrdinarySealRecoverySqlV1.freeze, wire,
                TestActiveOrdinarySealRecoveryRowsV1.hex(checkNotNull(candidate.wireSha256)), candidate.checksumSha256,
                Timestamp.from(checkNotNull(candidate.retainUntil)), metadata,
                TestActiveOrdinarySealRecoveryRowsV1.hex(checkNotNull(candidate.metadataSha256)), Timestamp.from(checkNotNull(candidate.frozenAt)),
                current.operationToken, original.scope, TestActiveOrdinarySealRecoveryRowsV1.hex(candidate.canonicalSha256)) in 0..1)
        } finally { wire.fill(0); metadata.fill(0) }
        intent?.close(); intent = loadIntent()
        original.requireSameCanonical(candidate, checkNotNull(intent))
        requireActiveSealRecovery(intent?.state === TestTerminalDurableStateV1.WIRE_FROZEN)
    }
    private fun verify() {
        val durable = checkNotNull(intent)
        original.requireSameFrozen(original.frozenRow(), durable)
        val proof = original.providerProof(this) // Only this original's actual released native custody.
        requireActiveSealRecovery(!proof.verifiedAt.isAfter(current.sampledAt) && !proof.lastModified.isAfter(current.sampledAt) && proof.retainUntil.isAfter(current.sampledAt))
        val bytes = proof.canonicalBytes(durable)
        try {
            requireActiveSealRecovery(jdbc.update(TestActiveOrdinarySealRecoverySqlV1.verifyControl, proof.version,
                TestActiveOrdinarySealRecoveryRowsV1.hex(checkNotNull(durable.wireSha256)), Timestamp.from(proof.retainUntil),
                Timestamp.from(proof.verifiedAt), bytes, TestActiveOrdinarySealRecoveryRowsV1.hex(Sha256.hex(bytes)), original.scope,
                durable.binding.operationToken, TestActiveOrdinarySealRecoveryRowsV1.hex(durable.canonicalSha256), original.attemptId, original.leaseToken) == 1)
        } finally { bytes.fill(0) }
        control().use { it.requireProof(durable, proof) }
    }

    companion object {
        fun execute(jdbc: JdbcTemplate, original: TestActiveOrdinarySealRecoveryV1): TestActiveOrdinarySealRecoveryOperationV1 {
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            phase.testActiveOrdinarySealRecovery.requireOperation(original, jdbc)
            val operation = TestActiveOrdinarySealRecoveryOperationV1(phase, jdbc, original)
            phase.testActiveOrdinarySealRecovery.retain(operation, jdbc)
            try {
                operation.execute()
                if (operation.step !in setOf(TestActiveOrdinarySealRecoveryStepV1.READ, TestActiveOrdinarySealRecoveryStepV1.FREEZE)) operation.discardDetached()
                return operation
            } catch (failure: Throwable) { operation.discardDetached(); throw failure }
        }
    }
}

internal enum class TestActiveOrdinarySealRecoveryStepV1 { READ, ACQUIRE, RENEW, EMPTY, FREEZE, VERIFY }
