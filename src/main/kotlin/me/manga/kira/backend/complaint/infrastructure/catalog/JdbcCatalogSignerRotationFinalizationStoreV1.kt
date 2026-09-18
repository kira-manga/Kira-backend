package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenSignatureSlot
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Fixed overlap2 finalization only. The private delivery owner supplies all authority and pre-entry buffers. */
internal class JdbcCatalogSignerRotationFinalizationStoreV1(private val jdbc: JdbcTemplate) {
    internal fun execute(input: CatalogSignerRotationFinalizationInputV1, capacity: JdbcComplaintCapacityStore): CatalogSignerRotationFinalizationOperationV1 =
        CatalogSignerRotationFinalizationOperationV1.execute(jdbc, input, capacity)
}

/** Original phase -> LIVE control -> exclusive catalog lock -> exact G1+2 history -> noncharging locked capacity verification. */
internal class CatalogSignerRotationFinalizationOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val input: CatalogSignerRotationFinalizationInputV1,
) {
    private var stage = Stage.RETAINED
    private var original: List<StoredSignerRotationFinalizationRowV1>? = null
    private var captured: List<StoredSignerRotationFinalizationRowV1>? = null
    private var counters: JdbcComplaintCapacityStore.LockedCatalogSignerRotationFinalization? = null
    private var released: CatalogSignerRotationFinalizationObservationV1? = null

    internal fun belongsTo(selected: PersistencePhaseContext, path: PersistencePhasePath): Boolean = phase === selected && input.path === path
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = phase === selected && stage === Stage.COMPLETE

    val observation: CatalogSignerRotationFinalizationObservationV1
        get() {
            requireReleased()
            return released ?: CatalogSignerRotationFinalizationObservationV1.issuedBy(this).also { released = it }
        }

    internal fun releasedRows(): List<StoredSignerRotationFinalizationRowV1> {
        requireReleased()
        return checkNotNull(captured).toList()
    }

    private fun requireReleased() {
        phase.catalogSignerRotationFinalization.requireCommitted(this)
        requireConnectionFree()
        check(stage === Stage.COMPLETE)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun run(capacity: JdbcComplaintCapacityStore) {
        try {
            requireAt(Stage.RETAINED)
            lockControl()
            requireAt(Stage.RETAINED)
            stage = Stage.CONTROL_LOCKED
            requireEntryLease() // The control lock has returned; this is a genuinely later DB-time sample.
            check(jdbc.query(TRY_CATALOG_LOCK, { row, _ -> row.requiredFinalizationBoolean("locked") }).single())
            requireAt(Stage.CONTROL_LOCKED)
            stage = Stage.CATALOG_LOCKED
            original = readOriginalHistory().also(::requirePreimage).toList()
            requireAt(Stage.CATALOG_LOCKED)
            stage = Stage.HISTORY_LOCKED
            verifyCounters(capacity)
            stage = Stage.WRITING
            requireEntryLease()
            write()
            requireAt(Stage.WRITING)
            stage = Stage.REREADING
            requireFinalLease() // COMPLETE is now B2/pending2; PROJECT is now B2/null, never the old B1 binding.
            val reread = readFinalHistory()
            requirePostimage(reread)
            requireFinalControl()
            requireFinalLease()
            requireAt(Stage.REREADING)
            captured = reread.toList()
            stage = Stage.COMPLETE
        } catch (problem: Throwable) {
            failed(problem)
        }
    }

    private fun lockControl() = when (input.kind) {
        CatalogSignerRotationFinalizationKindV1.INITIAL_READ,
        CatalogSignerRotationFinalizationKindV1.RECHECK,
        CatalogSignerRotationFinalizationKindV1.COMPLETE,
        -> requireControl(LOCK_SIGNER_ROTATION_FINAL_PREPARED_CONTROL, input.preparedBindingArguments())

        CatalogSignerRotationFinalizationKindV1.INITIAL_PENDING_READ,
        CatalogSignerRotationFinalizationKindV1.PENDING_RECHECK,
        CatalogSignerRotationFinalizationKindV1.PROJECT,
        -> requireControl(LOCK_SIGNER_ROTATION_FINAL_PENDING_CONTROL, input.pendingBindingArguments())

        CatalogSignerRotationFinalizationKindV1.INITIAL_PROJECTED_READ,
        CatalogSignerRotationFinalizationKindV1.PROJECTED_RECHECK,
        -> requireControl(LOCK_SIGNER_ROTATION_FINAL_PROJECTED_CONTROL, input.projectedBindingArguments())
    }

    private fun requireEntryLease() = when (input.kind) {
        CatalogSignerRotationFinalizationKindV1.INITIAL_READ,
        CatalogSignerRotationFinalizationKindV1.RECHECK,
        CatalogSignerRotationFinalizationKindV1.COMPLETE,
        -> requireControl(READ_SIGNER_ROTATION_FINAL_PREPARED_LEASE, input.preparedBindingArguments())

        CatalogSignerRotationFinalizationKindV1.INITIAL_PENDING_READ,
        CatalogSignerRotationFinalizationKindV1.PENDING_RECHECK,
        CatalogSignerRotationFinalizationKindV1.PROJECT,
        -> requireControl(READ_SIGNER_ROTATION_FINAL_PENDING_LEASE, input.pendingBindingArguments())

        CatalogSignerRotationFinalizationKindV1.INITIAL_PROJECTED_READ,
        CatalogSignerRotationFinalizationKindV1.PROJECTED_RECHECK,
        -> requireControl(READ_SIGNER_ROTATION_FINAL_PROJECTED_LEASE, input.projectedBindingArguments())
    }

    private fun requireFinalLease() = when (input.kind) {
        CatalogSignerRotationFinalizationKindV1.INITIAL_READ,
        CatalogSignerRotationFinalizationKindV1.RECHECK,
        -> requireControl(READ_SIGNER_ROTATION_FINAL_PREPARED_LEASE, input.preparedBindingArguments())

        CatalogSignerRotationFinalizationKindV1.INITIAL_PENDING_READ,
        CatalogSignerRotationFinalizationKindV1.PENDING_RECHECK,
        CatalogSignerRotationFinalizationKindV1.COMPLETE,
        -> requireControl(READ_SIGNER_ROTATION_FINAL_PENDING_LEASE, input.pendingBindingArguments())

        CatalogSignerRotationFinalizationKindV1.INITIAL_PROJECTED_READ,
        CatalogSignerRotationFinalizationKindV1.PROJECTED_RECHECK,
        CatalogSignerRotationFinalizationKindV1.PROJECT,
        -> requireControl(READ_SIGNER_ROTATION_FINAL_PROJECTED_LEASE, input.projectedBindingArguments())
    }

    private fun requireFinalControl() = when (input.kind) {
        CatalogSignerRotationFinalizationKindV1.INITIAL_READ,
        CatalogSignerRotationFinalizationKindV1.RECHECK,
        -> requireControl(READ_SIGNER_ROTATION_FINAL_PREPARED_CONTROL, input.preparedBindingArguments())

        CatalogSignerRotationFinalizationKindV1.INITIAL_PENDING_READ,
        CatalogSignerRotationFinalizationKindV1.PENDING_RECHECK,
        CatalogSignerRotationFinalizationKindV1.COMPLETE,
        -> requireControl(READ_SIGNER_ROTATION_FINAL_PENDING_CONTROL, input.pendingBindingArguments())

        CatalogSignerRotationFinalizationKindV1.INITIAL_PROJECTED_READ,
        CatalogSignerRotationFinalizationKindV1.PROJECTED_RECHECK,
        CatalogSignerRotationFinalizationKindV1.PROJECT,
        -> requireControl(READ_SIGNER_ROTATION_FINAL_PROJECTED_CONTROL, input.projectedBindingArguments())
    }

    private fun requireControl(sql: String, arguments: Array<Any?>) {
        phase.catalogSignerRotationFinalization.requireRetained(this, jdbc)
        check(jdbc.query(sql, { row, _ -> row.requiredFinalizationBoolean("binding_matches") }, *arguments).single())
        phase.catalogSignerRotationFinalization.requireRetained(this, jdbc)
    }

    private fun readOriginalHistory(): List<StoredSignerRotationFinalizationRowV1> = when (input.kind) {
        CatalogSignerRotationFinalizationKindV1.INITIAL_READ ->
            readHistory(LOCK_SIGNER_ROTATION_FINAL_INITIAL_HISTORY, input.initialHistoryArguments())

        CatalogSignerRotationFinalizationKindV1.INITIAL_PENDING_READ ->
            readHistory(LOCK_SIGNER_ROTATION_FINAL_INITIAL_PENDING_HISTORY, input.initialCopyHistoryArguments())

        CatalogSignerRotationFinalizationKindV1.INITIAL_PROJECTED_READ ->
            readHistory(LOCK_SIGNER_ROTATION_FINAL_INITIAL_PROJECTED_HISTORY, input.initialCopyHistoryArguments())

        CatalogSignerRotationFinalizationKindV1.RECHECK,
        CatalogSignerRotationFinalizationKindV1.COMPLETE,
        -> readHistory(LOCK_SIGNER_ROTATION_FINAL_PREPARED_HISTORY, input.preparedHistoryArguments())

        CatalogSignerRotationFinalizationKindV1.PENDING_RECHECK,
        CatalogSignerRotationFinalizationKindV1.PROJECT,
        -> readHistory(LOCK_SIGNER_ROTATION_FINAL_PENDING_HISTORY, input.pendingHistoryArguments())

        CatalogSignerRotationFinalizationKindV1.PROJECTED_RECHECK ->
            readHistory(LOCK_SIGNER_ROTATION_FINAL_PROJECTED_HISTORY, input.pendingHistoryArguments())
    }

    private fun readFinalHistory(): List<StoredSignerRotationFinalizationRowV1> = when (input.kind) {
        // G21 cannot be detached before any first observation. Each initial reread compares both full locked preimages in memory.
        CatalogSignerRotationFinalizationKindV1.INITIAL_READ ->
            readHistory(READ_SIGNER_ROTATION_FINAL_INITIAL_HISTORY, input.initialHistoryArguments())

        CatalogSignerRotationFinalizationKindV1.INITIAL_PENDING_READ ->
            readHistory(READ_SIGNER_ROTATION_FINAL_INITIAL_PENDING_HISTORY, input.initialCopyHistoryArguments())

        CatalogSignerRotationFinalizationKindV1.INITIAL_PROJECTED_READ ->
            readHistory(READ_SIGNER_ROTATION_FINAL_INITIAL_PROJECTED_HISTORY, input.initialCopyHistoryArguments())

        CatalogSignerRotationFinalizationKindV1.RECHECK ->
            readHistory(READ_SIGNER_ROTATION_FINAL_PREPARED_HISTORY, input.preparedHistoryArguments())

        CatalogSignerRotationFinalizationKindV1.PENDING_RECHECK,
        CatalogSignerRotationFinalizationKindV1.COMPLETE,
        -> readHistory(READ_SIGNER_ROTATION_FINAL_PENDING_HISTORY, input.pendingHistoryArguments())

        CatalogSignerRotationFinalizationKindV1.PROJECTED_RECHECK,
        CatalogSignerRotationFinalizationKindV1.PROJECT,
        -> readHistory(READ_SIGNER_ROTATION_FINAL_PROJECTED_HISTORY, input.pendingHistoryArguments())
    }

    private fun readHistory(sql: String, arguments: Array<Any?>): List<StoredSignerRotationFinalizationRowV1> {
        phase.catalogSignerRotationFinalization.requireRetained(this, jdbc)
        val rows = jdbc.query(sql, { row, _ -> StoredSignerRotationFinalizationRowV1.copy(row) }, *arguments)
        phase.catalogSignerRotationFinalization.requireRetained(this, jdbc)
        check(rows.size == 2) // Every SELECT is an unfiltered LIMIT3; any extra/prior/unrelated history rejects.
        rows[0].requireGenesis()
        rows[1].requireRotation()
        return rows
    }

    private fun requirePreimage(rows: List<StoredSignerRotationFinalizationRowV1>) {
        when (input.kind) {
            CatalogSignerRotationFinalizationKindV1.INITIAL_READ -> {
                check(input.expected == null)
                rows[1].requirePrepared()
            }

            CatalogSignerRotationFinalizationKindV1.INITIAL_PENDING_READ -> {
                check(input.expected == null)
                rows[1].requirePending()
            }

            CatalogSignerRotationFinalizationKindV1.INITIAL_PROJECTED_READ -> {
                check(input.expected == null)
                rows[1].requireProjected()
            }

            CatalogSignerRotationFinalizationKindV1.RECHECK,
            CatalogSignerRotationFinalizationKindV1.COMPLETE,
            -> {
                check(checkNotNull(input.expected).matchesHistory(rows))
                rows[1].requirePrepared()
            }

            CatalogSignerRotationFinalizationKindV1.PENDING_RECHECK,
            CatalogSignerRotationFinalizationKindV1.PROJECT,
            -> {
                check(checkNotNull(input.expected).matchesHistory(rows))
                rows[1].requirePending()
            }

            CatalogSignerRotationFinalizationKindV1.PROJECTED_RECHECK -> {
                check(checkNotNull(input.expected).matchesHistory(rows))
                rows[1].requireProjected()
            }
        }
    }

    private fun verifyCounters(capacity: JdbcComplaintCapacityStore) {
        requireAt(Stage.HISTORY_LOCKED)
        val locked = capacity.lockForCatalogSignerRotationFinalization(this)
        requireAt(Stage.COUNTERS_LOCKING)
        check(locked.belongsTo(this))
        counters = locked
        stage = Stage.VERIFYING_COUNTERS
        locked.verify(this)
        requireAt(Stage.VERIFYING_COUNTERS)
        check(locked.verifiedFor(this))
    }

    private fun write() {
        requireAt(Stage.WRITING)
        when (input.kind) {
            CatalogSignerRotationFinalizationKindV1.INITIAL_READ,
            CatalogSignerRotationFinalizationKindV1.INITIAL_PENDING_READ,
            CatalogSignerRotationFinalizationKindV1.INITIAL_PROJECTED_READ,
            CatalogSignerRotationFinalizationKindV1.RECHECK,
            CatalogSignerRotationFinalizationKindV1.PENDING_RECHECK,
            CatalogSignerRotationFinalizationKindV1.PROJECTED_RECHECK,
            -> Unit

            CatalogSignerRotationFinalizationKindV1.COMPLETE -> {
                check(jdbc.update(WRITE_SIGNER_ROTATION_FINAL_COMPLETION, *input.completionArguments()) == 1)
                requireAt(Stage.WRITING)
                check(jdbc.update(WRITE_SIGNER_ROTATION_FINAL_HEAD, *input.headArguments()) == 1)
            }

            CatalogSignerRotationFinalizationKindV1.PROJECT -> {
                check(jdbc.update(WRITE_SIGNER_ROTATION_FINAL_PROJECTION, *input.projectionArguments()) == 1)
                requireAt(Stage.WRITING)
                check(jdbc.update(WRITE_SIGNER_ROTATION_FINAL_CLEAR_PENDING, *input.clearPendingArguments()) == 1)
            }
        }
    }

    private fun requirePostimage(rows: List<StoredSignerRotationFinalizationRowV1>) {
        val before = checkNotNull(original)
        check(rows[0].same(before[0])) // All 33 actual G1 columns, including lifecycle and both evidence blobs.
        when (input.kind) {
            CatalogSignerRotationFinalizationKindV1.INITIAL_READ,
            CatalogSignerRotationFinalizationKindV1.INITIAL_PENDING_READ,
            CatalogSignerRotationFinalizationKindV1.INITIAL_PROJECTED_READ,
            CatalogSignerRotationFinalizationKindV1.RECHECK,
            CatalogSignerRotationFinalizationKindV1.PENDING_RECHECK,
            CatalogSignerRotationFinalizationKindV1.PROJECTED_RECHECK,
            -> check(rows[1].same(before[1]))

            CatalogSignerRotationFinalizationKindV1.COMPLETE -> rows[1].requireCompletionOf(before[1])

            CatalogSignerRotationFinalizationKindV1.PROJECT -> rows[1].requireProjectionOf(before[1])
        }
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        requireAt(Stage.HISTORY_LOCKED)
        check(selected === jdbc)
        stage = Stage.COUNTERS_LOCKING
    }

    internal fun requireCounterVerification(locked: JdbcComplaintCapacityStore.LockedCatalogSignerRotationFinalization, selected: JdbcTemplate) {
        requireAt(Stage.VERIFYING_COUNTERS)
        check(counters === locked && selected === jdbc && checkNotNull(original).size == 2)
    }

    internal fun failed(problem: Throwable): Nothing {
        stage = Stage.FAILED
        phase.recordFailure(problem)
        PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private fun requireAt(expected: Stage) {
        phase.catalogSignerRotationFinalization.requireRetained(this, jdbc)
        check(stage === expected)
    }

    override fun toString(): String = "CatalogSignerRotationFinalizationOperationV1(original-fenced-phase,closed-overlap2-only)"

    private enum class Stage {
        RETAINED,
        CONTROL_LOCKED,
        CATALOG_LOCKED,
        HISTORY_LOCKED,
        COUNTERS_LOCKING,
        VERIFYING_COUNTERS,
        WRITING,
        REREADING,
        COMPLETE,
        FAILED,
    }

    companion object {
        @Suppress("TooGenericExceptionCaught")
        internal fun execute(
            jdbc: JdbcTemplate,
            input: CatalogSignerRotationFinalizationInputV1,
            capacity: JdbcComplaintCapacityStore,
        ): CatalogSignerRotationFinalizationOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.catalogSignerRotationFinalization.requireOperation(input, jdbc)
                val expectedPath = when (input.kind) {
                    CatalogSignerRotationFinalizationKindV1.INITIAL_READ,
                    CatalogSignerRotationFinalizationKindV1.INITIAL_PENDING_READ,
                    CatalogSignerRotationFinalizationKindV1.INITIAL_PROJECTED_READ,
                    CatalogSignerRotationFinalizationKindV1.RECHECK,
                    CatalogSignerRotationFinalizationKindV1.PENDING_RECHECK,
                    CatalogSignerRotationFinalizationKindV1.PROJECTED_RECHECK,
                    -> PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_FINAL_READ

                    CatalogSignerRotationFinalizationKindV1.COMPLETE -> PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_COMPLETE

                    CatalogSignerRotationFinalizationKindV1.PROJECT -> PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PROJECT
                }
                check(input.path === expectedPath)
                val operation = CatalogSignerRotationFinalizationOperationV1(phase, jdbc, input)
                phase.catalogSignerRotationFinalization.retain(operation, jdbc)
                operation.run(capacity)
                return operation
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
    }
}

/** Actual committed-and-released immutable history data, not a COMPLETE/PROJECT or raw-readback capability. */
internal class CatalogSignerRotationFinalizationObservationV1 private constructor(
    private val genesisRow: StoredSignerRotationFinalizationRowV1,
    private val rotationRow: StoredSignerRotationFinalizationRowV1,
) {
    val genesis: CatalogFrozenMutation = genesisRow.mutation()
    val mutation: CatalogFrozenMutation = rotationRow.mutation()
    val completedAt: Instant? get() = rotationRow.completedAt
    val projectedAt: Instant? get() = rotationRow.projectedAt

    internal fun genesisArguments(): Array<Any?> = genesisRow.genesisArguments()
    internal fun copyArguments(): Array<Any?> = rotationRow.copyArguments()

    internal fun requireSame(other: CatalogSignerRotationFinalizationObservationV1) {
        requireConnectionFree()
        requireSignerRotation(
            genesisRow.same(other.genesisRow) && rotationRow.same(other.rotationRow),
            CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED,
        )
    }

    /** Bounded internal comparison only. Never detaches inputs or parses/hashes data while the phase holds locks. */
    internal fun matchesHistory(rows: List<StoredSignerRotationFinalizationRowV1>): Boolean =
        rows.size == 2 && genesisRow.same(rows[0]) && rotationRow.same(rows[1])

    override fun toString(): String = "CatalogSignerRotationFinalizationObservationV1(actual-local-history,no-authority)"

    companion object {
        internal fun issuedBy(operation: CatalogSignerRotationFinalizationOperationV1): CatalogSignerRotationFinalizationObservationV1 {
            val rows = operation.releasedRows()
            check(rows.size == 2)
            return CatalogSignerRotationFinalizationObservationV1(rows[0], rows[1])
        }
    }
}

/** All 33 V14 columns copied once from bounded projections, kept private, and compared without hashing or JSON work. */
internal class StoredSignerRotationFinalizationRowV1 private constructor(
    private val shape: Shape,
    private val header: Header,
    private val approval: Document,
    private val unsigned: Document,
    private val envelope: Document,
    private val signers: Signers,
    private val objectKey: String,
    private val createdAt: Instant,
    private val copies: Copies,
    private val lifecycle: Lifecycle,
) {
    val completedAt: Instant? get() = lifecycle.completedAt
    val projectedAt: Instant? get() = lifecycle.projectedAt

    internal fun requireGenesis() {
        check(shape.bounded && shape.genesisMatches && !shape.rotationMatches && header.successorGeneration == 1L)
    }

    internal fun requireRotation() {
        check(shape.bounded && shape.rotationMatches && !shape.genesisMatches && header.successorGeneration == 2L)
    }

    internal fun requirePrepared() {
        check(lifecycle.state == "PREPARED" && lifecycle.completedAt == null && lifecycle.projectedAt == null && copies.empty())
    }

    internal fun requirePending() {
        check(lifecycle.state == "COMPLETED" && lifecycle.completedAt != null && lifecycle.projectedAt == null && copies.complete())
    }

    internal fun requireProjected() {
        check(lifecycle.state == "COMPLETED" && lifecycle.completedAt != null && lifecycle.projectedAt != null && copies.complete())
    }

    internal fun requireCompletionOf(before: StoredSignerRotationFinalizationRowV1) {
        before.requirePrepared()
        requirePending()
        check(sameFrozen(before)) // Only state/completion and exact SQL-bound C6 may change; projected_at stays NULL.
    }

    internal fun requireProjectionOf(before: StoredSignerRotationFinalizationRowV1) {
        before.requirePending()
        check(lifecycle.state == "COMPLETED" && lifecycle.projectedAt != null && lifecycle.completedAt == before.lifecycle.completedAt)
        check(sameFrozen(before) && copies.same(before.copies)) // projected_at is the only mutation column allowed to change.
    }

    internal fun same(other: StoredSignerRotationFinalizationRowV1): Boolean = sameFrozen(other) && copies.same(other.copies) && lifecycle.same(other.lifecycle)

    private fun sameFrozen(other: StoredSignerRotationFinalizationRowV1): Boolean =
        header.same(other.header) && approval.same(other.approval) && unsigned.same(other.unsigned) && envelope.same(other.envelope) &&
            signers.same(other.signers) && objectKey == other.objectKey && createdAt == other.createdAt

    internal fun mutation(): CatalogFrozenMutation {
        requireConnectionFree()
        return CatalogFrozenMutation(
            1,
            header.token.toString(),
            checkNotNull(unsigned.bytes),
            HexFormat.of().formatHex(checkNotNull(unsigned.hash)),
            checkNotNull(envelope.bytes),
            HexFormat.of().formatHex(checkNotNull(envelope.hash)),
            listOfNotNull(
                CatalogFrozenSignatureSlot(signers.firstId, signers.firstAlgorithm, signers.firstSignature),
                signers.secondId?.let { CatalogFrozenSignatureSlot(it, checkNotNull(signers.secondAlgorithm), signers.secondSignature) },
            ),
        )
    }

    /** G21, detached before the next phase. Fixed G1 fields remain covered by SQL and full-row equality. */
    internal fun genesisArguments(): Array<Any?> {
        requireConnectionFree()
        requireGenesis()
        return arrayOf(
            header.token, header.writer,
            approval.bytes?.copyOf(), approval.hash?.copyOf(), unsigned.bytes?.copyOf(), unsigned.hash?.copyOf(),
            signers.firstId, signers.firstAlgorithm, signers.firstSignature.copyOf(),
            envelope.bytes?.copyOf(), envelope.hash?.copyOf(), objectKey, Timestamp.from(createdAt),
            copies.version, copies.retainUntil?.let(Timestamp::from),
            copies.primary.bytes?.copyOf(), copies.primary.hash?.copyOf(), copies.replica.bytes?.copyOf(), copies.replica.hash?.copyOf(),
            lifecycle.completedAt?.let(Timestamp::from), lifecycle.projectedAt?.let(Timestamp::from),
        )
    }

    /** C6 from the actual complete operation2 row. Independent evidence blobs are never reconstructed. */
    internal fun copyArguments(): Array<Any?> {
        requireConnectionFree()
        requireRotation()
        check(lifecycle.state == "COMPLETED" && copies.complete())
        return arrayOf(
            copies.version,
            copies.retainUntil?.let(Timestamp::from),
            copies.primary.bytes?.copyOf(),
            copies.primary.hash?.copyOf(),
            copies.replica.bytes?.copyOf(),
            copies.replica.hash?.copyOf(),
        )
    }

    override fun toString(): String = "StoredSignerRotationFinalizationRowV1(bounded-private-columns,redacted)"

    private class Shape(val genesisMatches: Boolean, val rotationMatches: Boolean, val bounded: Boolean)

    private class Header(
        val token: UUID,
        val operation: String,
        val scope: UUID?,
        val testOnly: Boolean?,
        val predecessorGeneration: Long,
        val predecessorHash: ByteArray,
        val successorGeneration: Long,
        val writer: UUID,
        val canonicalizer: String,
    ) {
        fun same(other: Header): Boolean = token == other.token && operation == other.operation && scope == other.scope && testOnly == other.testOnly &&
            predecessorGeneration == other.predecessorGeneration && predecessorHash.contentEquals(other.predecessorHash) &&
            successorGeneration == other.successorGeneration && writer == other.writer && canonicalizer == other.canonicalizer
    }

    private class Document(val bytes: ByteArray?, val hash: ByteArray?) {
        fun same(other: Document): Boolean = bytes.contentEquals(other.bytes) && hash.contentEquals(other.hash)

        companion object {
            fun copy(row: ResultSet, prefix: String): Document = Document(row.getBytes("${prefix}_bytes")?.copyOf(), row.getBytes("${prefix}_hash")?.copyOf())
        }
    }

    private class Signers(
        val policy: String,
        val firstId: String,
        val firstAlgorithm: String,
        val firstSignature: ByteArray,
        val secondId: String?,
        val secondAlgorithm: String?,
        val secondSignature: ByteArray?,
    ) {
        fun same(other: Signers): Boolean = policy == other.policy && firstId == other.firstId && firstAlgorithm == other.firstAlgorithm &&
            firstSignature.contentEquals(other.firstSignature) && secondId == other.secondId && secondAlgorithm == other.secondAlgorithm &&
            secondSignature.contentEquals(other.secondSignature)

        companion object {
            fun copy(row: ResultSet): Signers = Signers(
                checkNotNull(row.getString("signer_policy")),
                checkNotNull(row.getString("signer_one_id")),
                checkNotNull(row.getString("signer_one_algorithm")),
                checkNotNull(row.getBytes("signer_one_signature")).copyOf(),
                row.getString("signer_two_id"),
                row.getString("signer_two_algorithm"),
                row.getBytes("signer_two_signature")?.copyOf(),
            )
        }
    }

    private class Copies(val version: String?, val retainUntil: Instant?, val primary: Document, val replica: Document) {
        fun empty(): Boolean = version == null && retainUntil == null && primary.bytes == null && primary.hash == null &&
            replica.bytes == null && replica.hash == null

        fun complete(): Boolean = version != null && retainUntil != null && primary.bytes != null && primary.hash != null &&
            replica.bytes != null && replica.hash != null

        fun same(other: Copies): Boolean = version == other.version && retainUntil == other.retainUntil &&
            primary.same(other.primary) && replica.same(other.replica)

        companion object {
            fun copy(row: ResultSet): Copies = Copies(
                row.getString("object_version"),
                row.getTimestamp("retain_until")?.toInstant(),
                Document.copy(row, "primary_evidence"),
                Document.copy(row, "replica_evidence"),
            )
        }
    }

    private class Lifecycle(val state: String, val completedAt: Instant?, val projectedAt: Instant?) {
        fun same(other: Lifecycle): Boolean = state == other.state && completedAt == other.completedAt && projectedAt == other.projectedAt

        companion object {
            fun copy(row: ResultSet): Lifecycle = Lifecycle(
                checkNotNull(row.getString("state")),
                row.getTimestamp("completed_at")?.toInstant(),
                row.getTimestamp("projected_at")?.toInstant(),
            )
        }
    }

    companion object {
        internal fun copy(row: ResultSet): StoredSignerRotationFinalizationRowV1 {
            val shape = Shape(
                row.requiredFinalizationBoolean("genesis_matches"),
                row.requiredFinalizationBoolean("rotation_matches"),
                row.requiredFinalizationBoolean("bounded"),
            )
            check(shape.bounded) // Never treat a CASE-truncated optional field as an accepted absence.
            return StoredSignerRotationFinalizationRowV1(
                shape,
                Header(
                    checkNotNull(row.getObject("operation_token", UUID::class.java)), checkNotNull(row.getString("operation_type")),
                    row.getObject("data_scope_id", UUID::class.java), row.nullableFinalizationBoolean("test_only"),
                    row.requiredFinalizationLong("predecessor_generation"), checkNotNull(row.getBytes("predecessor_hash")).copyOf(),
                    row.requiredFinalizationLong("successor_generation"), checkNotNull(row.getObject("catalog_writer_generation", UUID::class.java)),
                    checkNotNull(row.getString("canonicalizer")),
                ),
                Document.copy(row, "approval"), Document.copy(row, "unsigned"), Document.copy(row, "envelope"), Signers.copy(row),
                checkNotNull(row.getString("object_key")), checkNotNull(row.getTimestamp("created_at")).toInstant(),
                Copies.copy(row), Lifecycle.copy(row),
            )
        }
    }
}

private fun ResultSet.requiredFinalizationBoolean(column: String): Boolean = getBoolean(column).also { check(!wasNull()) }
private fun ResultSet.nullableFinalizationBoolean(column: String): Boolean? = getBoolean(column).let { if (wasNull()) null else it }
private fun ResultSet.requiredFinalizationLong(column: String): Long = getLong(column).also { check(!wasNull()) }
