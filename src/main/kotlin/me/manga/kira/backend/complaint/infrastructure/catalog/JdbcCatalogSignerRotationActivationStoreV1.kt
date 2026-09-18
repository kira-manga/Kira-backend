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

/** Fixed immediate activation3 only; no changes to the G1 or overlap2 stores or their history guards. */
internal class JdbcCatalogSignerRotationActivationStoreV1(private val jdbc: JdbcTemplate) {
    internal fun execute(input: CatalogSignerRotationActivationInputV1, capacity: JdbcComplaintCapacityStore): CatalogSignerRotationActivationOperationV1 =
        CatalogSignerRotationActivationOperationV1.execute(jdbc, input, capacity)
}

/** Original phase -> locked LIVE control -> later exact lease -> catalog -> full history -> retained capacity. */
internal class CatalogSignerRotationActivationOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val input: CatalogSignerRotationActivationInputV1,
) {
    private var stage = Stage.RETAINED
    private var original: List<StoredSignerRotationActivationRowV1>? = null
    private var captured: List<StoredSignerRotationActivationRowV1>? = null
    private var counters: JdbcComplaintCapacityStore.LockedCatalogSignerRotationActivation? = null
    private var released: CatalogSignerRotationActivationObservationV1? = null

    internal fun belongsTo(selected: PersistencePhaseContext, path: PersistencePhasePath): Boolean = phase === selected && input.path === path
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = phase === selected && stage === Stage.COMPLETE

    val observation: CatalogSignerRotationActivationObservationV1
        get() {
            requireReleased()
            return released ?: CatalogSignerRotationActivationObservationV1.issuedBy(this).also { released = it }
        }

    internal fun releasedRows(): List<StoredSignerRotationActivationRowV1> {
        requireReleased()
        return checkNotNull(captured).toList()
    }

    private fun requireReleased() {
        phase.catalogSignerRotationActivation.requireCommitted(this)
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
            requireEntryLease() // No authority from a clock sampled before the control lock returned.
            check(jdbc.query(TRY_CATALOG_LOCK, { row, _ -> row.requiredActivationBoolean("locked") }).single())
            requireAt(Stage.CONTROL_LOCKED)
            stage = Stage.CATALOG_LOCKED
            original = readOriginalHistory().also(::requirePreimage).toList()
            requireAt(Stage.CATALOG_LOCKED)
            stage = Stage.HISTORY_LOCKED
            settleCounters(capacity)
            stage = Stage.WRITING
            requireEntryLease()
            write()
            requireAt(Stage.WRITING)
            stage = Stage.REREADING
            requireFinalLease() // COMPLETE now uses B3/pending3; PROJECT now uses B3/null.
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
        CatalogSignerRotationActivationKindV1.INITIAL_HEAD_READ,
        CatalogSignerRotationActivationKindV1.HEAD_RECHECK,
        CatalogSignerRotationActivationKindV1.PREPARE,
        CatalogSignerRotationActivationKindV1.INITIAL_PREPARED_READ,
        CatalogSignerRotationActivationKindV1.PREPARED_RECHECK,
        CatalogSignerRotationActivationKindV1.SIGNATURE,
        CatalogSignerRotationActivationKindV1.COMPLETE,
        -> requireControl(LOCK_SIGNER_ROTATION_ACTIVATION_HEAD_CONTROL, input.headBindingArguments())

        CatalogSignerRotationActivationKindV1.INITIAL_PENDING_READ,
        CatalogSignerRotationActivationKindV1.PENDING_RECHECK,
        CatalogSignerRotationActivationKindV1.PROJECT,
        -> requireControl(LOCK_SIGNER_ROTATION_ACTIVATION_PENDING_CONTROL, input.pendingBindingArguments())

        CatalogSignerRotationActivationKindV1.INITIAL_PROJECTED_READ,
        CatalogSignerRotationActivationKindV1.PROJECTED_RECHECK,
        -> requireControl(LOCK_SIGNER_ROTATION_ACTIVATION_PROJECTED_CONTROL, input.projectedBindingArguments())
    }

    private fun requireEntryLease() = when (input.kind) {
        CatalogSignerRotationActivationKindV1.INITIAL_HEAD_READ,
        CatalogSignerRotationActivationKindV1.HEAD_RECHECK,
        CatalogSignerRotationActivationKindV1.PREPARE,
        CatalogSignerRotationActivationKindV1.INITIAL_PREPARED_READ,
        CatalogSignerRotationActivationKindV1.PREPARED_RECHECK,
        CatalogSignerRotationActivationKindV1.SIGNATURE,
        CatalogSignerRotationActivationKindV1.COMPLETE,
        -> requireControl(READ_SIGNER_ROTATION_ACTIVATION_HEAD_LEASE, input.headBindingArguments())

        CatalogSignerRotationActivationKindV1.INITIAL_PENDING_READ,
        CatalogSignerRotationActivationKindV1.PENDING_RECHECK,
        CatalogSignerRotationActivationKindV1.PROJECT,
        -> requireControl(READ_SIGNER_ROTATION_ACTIVATION_PENDING_LEASE, input.pendingBindingArguments())

        CatalogSignerRotationActivationKindV1.INITIAL_PROJECTED_READ,
        CatalogSignerRotationActivationKindV1.PROJECTED_RECHECK,
        -> requireControl(READ_SIGNER_ROTATION_ACTIVATION_PROJECTED_LEASE, input.projectedBindingArguments())
    }

    private fun requireFinalLease() = when (input.kind) {
        CatalogSignerRotationActivationKindV1.INITIAL_HEAD_READ,
        CatalogSignerRotationActivationKindV1.HEAD_RECHECK,
        CatalogSignerRotationActivationKindV1.INITIAL_PREPARED_READ,
        CatalogSignerRotationActivationKindV1.PREPARED_RECHECK,
        CatalogSignerRotationActivationKindV1.PREPARE,
        CatalogSignerRotationActivationKindV1.SIGNATURE,
        -> requireControl(READ_SIGNER_ROTATION_ACTIVATION_HEAD_LEASE, input.headBindingArguments())

        CatalogSignerRotationActivationKindV1.INITIAL_PENDING_READ,
        CatalogSignerRotationActivationKindV1.PENDING_RECHECK,
        CatalogSignerRotationActivationKindV1.COMPLETE,
        -> requireControl(READ_SIGNER_ROTATION_ACTIVATION_PENDING_LEASE, input.pendingBindingArguments())

        CatalogSignerRotationActivationKindV1.INITIAL_PROJECTED_READ,
        CatalogSignerRotationActivationKindV1.PROJECTED_RECHECK,
        CatalogSignerRotationActivationKindV1.PROJECT,
        -> requireControl(READ_SIGNER_ROTATION_ACTIVATION_PROJECTED_LEASE, input.projectedBindingArguments())
    }

    private fun requireFinalControl() = when (input.kind) {
        CatalogSignerRotationActivationKindV1.INITIAL_HEAD_READ,
        CatalogSignerRotationActivationKindV1.HEAD_RECHECK,
        CatalogSignerRotationActivationKindV1.INITIAL_PREPARED_READ,
        CatalogSignerRotationActivationKindV1.PREPARED_RECHECK,
        CatalogSignerRotationActivationKindV1.PREPARE,
        CatalogSignerRotationActivationKindV1.SIGNATURE,
        -> requireControl(READ_SIGNER_ROTATION_ACTIVATION_HEAD_CONTROL, input.headBindingArguments())

        CatalogSignerRotationActivationKindV1.INITIAL_PENDING_READ,
        CatalogSignerRotationActivationKindV1.PENDING_RECHECK,
        CatalogSignerRotationActivationKindV1.COMPLETE,
        -> requireControl(READ_SIGNER_ROTATION_ACTIVATION_PENDING_CONTROL, input.pendingBindingArguments())

        CatalogSignerRotationActivationKindV1.INITIAL_PROJECTED_READ,
        CatalogSignerRotationActivationKindV1.PROJECTED_RECHECK,
        CatalogSignerRotationActivationKindV1.PROJECT,
        -> requireControl(READ_SIGNER_ROTATION_ACTIVATION_PROJECTED_CONTROL, input.projectedBindingArguments())
    }

    private fun requireControl(sql: String, arguments: Array<Any?>) {
        phase.catalogSignerRotationActivation.requireRetained(this, jdbc)
        check(jdbc.query(sql, { row, _ -> row.requiredActivationBoolean("binding_matches") }, *arguments).single())
        phase.catalogSignerRotationActivation.requireRetained(this, jdbc)
    }

    private fun readOriginalHistory(): List<StoredSignerRotationActivationRowV1> = when (input.kind) {
        CatalogSignerRotationActivationKindV1.INITIAL_HEAD_READ,
        CatalogSignerRotationActivationKindV1.HEAD_RECHECK,
        CatalogSignerRotationActivationKindV1.PREPARE,
        -> readHistory(LOCK_SIGNER_ROTATION_ACTIVATION_HEAD_HISTORY, input.initialHistoryArguments(), 2)

        CatalogSignerRotationActivationKindV1.INITIAL_PREPARED_READ,
        CatalogSignerRotationActivationKindV1.PREPARED_RECHECK,
        CatalogSignerRotationActivationKindV1.SIGNATURE,
        CatalogSignerRotationActivationKindV1.COMPLETE,
        -> readHistory(LOCK_SIGNER_ROTATION_ACTIVATION_PREPARED_HISTORY, input.initialHistoryArguments(), 3)

        CatalogSignerRotationActivationKindV1.INITIAL_PENDING_READ,
        CatalogSignerRotationActivationKindV1.PENDING_RECHECK,
        CatalogSignerRotationActivationKindV1.PROJECT,
        -> readHistory(LOCK_SIGNER_ROTATION_ACTIVATION_PENDING_HISTORY, input.initialHistoryArguments(), 3)

        CatalogSignerRotationActivationKindV1.INITIAL_PROJECTED_READ,
        CatalogSignerRotationActivationKindV1.PROJECTED_RECHECK,
        -> readHistory(LOCK_SIGNER_ROTATION_ACTIVATION_PROJECTED_HISTORY, input.initialHistoryArguments(), 3)
    }

    private fun readFinalHistory(): List<StoredSignerRotationActivationRowV1> = when (input.kind) {
        CatalogSignerRotationActivationKindV1.INITIAL_HEAD_READ,
        CatalogSignerRotationActivationKindV1.HEAD_RECHECK,
        -> readHistory(READ_SIGNER_ROTATION_ACTIVATION_HEAD_HISTORY, input.finalHistoryArguments(), 2)

        CatalogSignerRotationActivationKindV1.INITIAL_PREPARED_READ,
        CatalogSignerRotationActivationKindV1.PREPARED_RECHECK,
        CatalogSignerRotationActivationKindV1.PREPARE,
        CatalogSignerRotationActivationKindV1.SIGNATURE,
        -> readHistory(READ_SIGNER_ROTATION_ACTIVATION_PREPARED_HISTORY, input.finalHistoryArguments(), 3)

        CatalogSignerRotationActivationKindV1.INITIAL_PENDING_READ,
        CatalogSignerRotationActivationKindV1.PENDING_RECHECK,
        CatalogSignerRotationActivationKindV1.COMPLETE,
        -> readHistory(READ_SIGNER_ROTATION_ACTIVATION_PENDING_HISTORY, input.finalHistoryArguments(), 3)

        CatalogSignerRotationActivationKindV1.INITIAL_PROJECTED_READ,
        CatalogSignerRotationActivationKindV1.PROJECTED_RECHECK,
        CatalogSignerRotationActivationKindV1.PROJECT,
        -> readHistory(READ_SIGNER_ROTATION_ACTIVATION_PROJECTED_HISTORY, input.finalHistoryArguments(), 3)
    }

    private fun readHistory(sql: String, arguments: Array<Any?>, expectedRows: Int): List<StoredSignerRotationActivationRowV1> {
        phase.catalogSignerRotationActivation.requireRetained(this, jdbc)
        val rows = jdbc.query(sql, { row, _ -> StoredSignerRotationActivationRowV1.copy(row) }, *arguments)
        phase.catalogSignerRotationActivation.requireRetained(this, jdbc)
        check(rows.size == expectedRows) // Fixed call sites: exactly2 before PREPARE, exactly3 afterwards. Always unfiltered LIMIT4.
        rows[0].requireGenesis()
        rows[1].requireOverlap()
        rows.getOrNull(2)?.requireActivation()
        return rows
    }

    private fun requirePreimage(rows: List<StoredSignerRotationActivationRowV1>) {
        when (input.kind) {
            CatalogSignerRotationActivationKindV1.INITIAL_HEAD_READ,
            CatalogSignerRotationActivationKindV1.INITIAL_PREPARED_READ,
            CatalogSignerRotationActivationKindV1.INITIAL_PENDING_READ,
            CatalogSignerRotationActivationKindV1.INITIAL_PROJECTED_READ,
            -> check(input.expected == null)

            // Cold reads capture actual rows; they do not invent a precrash preimage.

            CatalogSignerRotationActivationKindV1.HEAD_RECHECK,
            CatalogSignerRotationActivationKindV1.PREPARED_RECHECK,
            CatalogSignerRotationActivationKindV1.PENDING_RECHECK,
            CatalogSignerRotationActivationKindV1.PROJECTED_RECHECK,
            CatalogSignerRotationActivationKindV1.PREPARE,
            CatalogSignerRotationActivationKindV1.SIGNATURE,
            CatalogSignerRotationActivationKindV1.COMPLETE,
            CatalogSignerRotationActivationKindV1.PROJECT,
            -> check(checkNotNull(input.expected).matchesHistory(rows)) // Full33 for every existing row before any write.
        }
        when (input.kind) {
            CatalogSignerRotationActivationKindV1.INITIAL_HEAD_READ,
            CatalogSignerRotationActivationKindV1.HEAD_RECHECK,
            CatalogSignerRotationActivationKindV1.PREPARE,
            -> check(rows.size == 2)

            CatalogSignerRotationActivationKindV1.INITIAL_PREPARED_READ,
            CatalogSignerRotationActivationKindV1.PREPARED_RECHECK,
            CatalogSignerRotationActivationKindV1.SIGNATURE,
            -> rows[2].requirePrepared()

            CatalogSignerRotationActivationKindV1.COMPLETE -> {
                rows[2].requirePrepared()
                rows[2].requireSigned()
            }

            CatalogSignerRotationActivationKindV1.INITIAL_PENDING_READ,
            CatalogSignerRotationActivationKindV1.PENDING_RECHECK,
            CatalogSignerRotationActivationKindV1.PROJECT,
            -> rows[2].requirePending()

            CatalogSignerRotationActivationKindV1.INITIAL_PROJECTED_READ,
            CatalogSignerRotationActivationKindV1.PROJECTED_RECHECK,
            -> rows[2].requireProjected()
        }
    }

    private fun settleCounters(capacity: JdbcComplaintCapacityStore) {
        requireAt(Stage.HISTORY_LOCKED)
        val locked = capacity.lockForCatalogSignerRotationActivation(this)
        requireAt(Stage.COUNTERS_LOCKING)
        check(locked.belongsTo(this))
        counters = locked
        stage = Stage.SETTLING_COUNTERS
        requireEntryLease() // Also sample after capacity locking, before the sole possible capacity charge.
        locked.settle(this)
        requireAt(Stage.SETTLING_COUNTERS)
        check(locked.settledFor(this))
    }

    private fun write() {
        requireAt(Stage.WRITING)
        when (input.kind) {
            CatalogSignerRotationActivationKindV1.INITIAL_HEAD_READ,
            CatalogSignerRotationActivationKindV1.INITIAL_PREPARED_READ,
            CatalogSignerRotationActivationKindV1.INITIAL_PENDING_READ,
            CatalogSignerRotationActivationKindV1.INITIAL_PROJECTED_READ,
            CatalogSignerRotationActivationKindV1.HEAD_RECHECK,
            CatalogSignerRotationActivationKindV1.PREPARED_RECHECK,
            CatalogSignerRotationActivationKindV1.PENDING_RECHECK,
            CatalogSignerRotationActivationKindV1.PROJECTED_RECHECK,
            -> Unit

            CatalogSignerRotationActivationKindV1.PREPARE -> check(jdbc.update(INSERT_SIGNER_ROTATION_ACTIVATION_PREPARED, *input.insertArguments()) == 1)

            CatalogSignerRotationActivationKindV1.SIGNATURE -> {
                val arguments = input.signatureArguments()
                if (!checkNotNull(original)[2].matchesNextSignature(arguments)) {
                    check(jdbc.update(WRITE_SIGNER_ROTATION_ACTIVATION_SIGNATURE, *arguments) == 1)
                }
            }

            CatalogSignerRotationActivationKindV1.COMPLETE -> {
                check(jdbc.update(WRITE_SIGNER_ROTATION_ACTIVATION_COMPLETION, *input.completionArguments()) == 1)
                requireAt(Stage.WRITING)
                check(jdbc.update(WRITE_SIGNER_ROTATION_ACTIVATION_HEAD, *input.headArguments()) == 1)
            }

            CatalogSignerRotationActivationKindV1.PROJECT -> {
                check(jdbc.update(WRITE_SIGNER_ROTATION_ACTIVATION_PROJECTION, *input.projectionArguments()) == 1)
                requireAt(Stage.WRITING)
                check(jdbc.update(WRITE_SIGNER_ROTATION_ACTIVATION_CLEAR_PENDING, *input.clearPendingArguments()) == 1)
            }
        }
    }

    private fun requirePostimage(rows: List<StoredSignerRotationActivationRowV1>) {
        val before = checkNotNull(original)
        check(rows[0].same(before[0]) && rows[1].same(before[1])) // Full33 G1 AND G2, including lifecycle and both original evidence blobs.
        when (input.kind) {
            CatalogSignerRotationActivationKindV1.INITIAL_HEAD_READ,
            CatalogSignerRotationActivationKindV1.INITIAL_PREPARED_READ,
            CatalogSignerRotationActivationKindV1.INITIAL_PENDING_READ,
            CatalogSignerRotationActivationKindV1.INITIAL_PROJECTED_READ,
            CatalogSignerRotationActivationKindV1.HEAD_RECHECK,
            CatalogSignerRotationActivationKindV1.PREPARED_RECHECK,
            CatalogSignerRotationActivationKindV1.PENDING_RECHECK,
            CatalogSignerRotationActivationKindV1.PROJECTED_RECHECK,
            -> check(rows.size == before.size && rows.indices.all { rows[it].same(before[it]) })

            CatalogSignerRotationActivationKindV1.PREPARE -> {
                check(before.size == 2 && rows.size == 3)
                rows[2].requirePrepared()
                rows[2].requireUnsigned()
            }

            CatalogSignerRotationActivationKindV1.SIGNATURE -> rows[2].requireSignatureOf(before[2])

            CatalogSignerRotationActivationKindV1.COMPLETE -> rows[2].requireCompletionOf(before[2])

            CatalogSignerRotationActivationKindV1.PROJECT -> rows[2].requireProjectionOf(before[2])
        }
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        requireAt(Stage.HISTORY_LOCKED)
        check(selected === jdbc)
        stage = Stage.COUNTERS_LOCKING
    }

    internal fun requireCounterSettlement(
        locked: JdbcComplaintCapacityStore.LockedCatalogSignerRotationActivation,
        selected: JdbcTemplate,
    ): Pair<Int, Boolean> {
        requireAt(Stage.SETTLING_COUNTERS)
        check(counters === locked && selected === jdbc)
        return checkNotNull(original).size to (input.kind === CatalogSignerRotationActivationKindV1.PREPARE)
    }

    internal fun failed(problem: Throwable): Nothing {
        stage = Stage.FAILED
        phase.recordFailure(problem)
        PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private fun requireAt(expected: Stage) {
        phase.catalogSignerRotationActivation.requireRetained(this, jdbc)
        check(stage === expected)
    }

    override fun toString(): String = "CatalogSignerRotationActivationOperationV1(original-fenced-phase,fixed-activation3-only)"

    private enum class Stage {
        RETAINED,
        CONTROL_LOCKED,
        CATALOG_LOCKED,
        HISTORY_LOCKED,
        COUNTERS_LOCKING,
        SETTLING_COUNTERS,
        WRITING,
        REREADING,
        COMPLETE,
        FAILED,
    }

    companion object {
        @Suppress("TooGenericExceptionCaught")
        internal fun execute(
            jdbc: JdbcTemplate,
            input: CatalogSignerRotationActivationInputV1,
            capacity: JdbcComplaintCapacityStore,
        ): CatalogSignerRotationActivationOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.catalogSignerRotationActivation.requireOperation(input, jdbc)
                val expectedPath = when (input.kind) {
                    CatalogSignerRotationActivationKindV1.INITIAL_HEAD_READ,
                    CatalogSignerRotationActivationKindV1.INITIAL_PREPARED_READ,
                    CatalogSignerRotationActivationKindV1.INITIAL_PENDING_READ,
                    CatalogSignerRotationActivationKindV1.INITIAL_PROJECTED_READ,
                    CatalogSignerRotationActivationKindV1.HEAD_RECHECK,
                    CatalogSignerRotationActivationKindV1.PREPARED_RECHECK,
                    CatalogSignerRotationActivationKindV1.PENDING_RECHECK,
                    CatalogSignerRotationActivationKindV1.PROJECTED_RECHECK,
                    -> PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_READ

                    CatalogSignerRotationActivationKindV1.PREPARE -> PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PREPARE

                    CatalogSignerRotationActivationKindV1.SIGNATURE -> PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_SIGNATURE

                    CatalogSignerRotationActivationKindV1.COMPLETE -> PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_COMPLETE

                    CatalogSignerRotationActivationKindV1.PROJECT -> PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PROJECT
                }
                check(input.path === expectedPath)
                val operation = CatalogSignerRotationActivationOperationV1(phase, jdbc, input)
                phase.catalogSignerRotationActivation.retain(operation, jdbc)
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

/** Actual committed-and-released local data, never raw evidence or permission to Sign, PUT, complete or project. */
internal class CatalogSignerRotationActivationObservationV1 private constructor(
    private val genesisRow: StoredSignerRotationActivationRowV1,
    private val overlapRow: StoredSignerRotationActivationRowV1,
    private val activationRow: StoredSignerRotationActivationRowV1?,
) {
    val genesis: CatalogFrozenMutation = genesisRow.mutation()
    val overlap: CatalogFrozenMutation = overlapRow.mutation()
    val mutation: CatalogFrozenMutation? = activationRow?.mutation()
    val state: String? get() = activationRow?.state
    val completedAt: Instant? get() = activationRow?.completedAt
    val projectedAt: Instant? get() = activationRow?.projectedAt

    internal fun overlapCopyArguments(): Array<Any?> = overlapRow.copyArguments()
    internal fun copyArguments(): Array<Any?> = checkNotNull(activationRow).copyArguments()

    /** Detached full33 arrays in V14 column order, for bounded connection-free reconciliation only. */
    internal fun historyArguments(): List<Array<Any?>> {
        requireConnectionFree()
        return listOfNotNull(genesisRow, overlapRow, activationRow).map { it.historyArguments() }
    }

    internal fun samePriorHistory(other: CatalogSignerRotationActivationObservationV1): Boolean {
        requireConnectionFree()
        return genesisRow.same(other.genesisRow) && overlapRow.same(other.overlapRow)
    }

    internal fun requireSame(other: CatalogSignerRotationActivationObservationV1) {
        requireConnectionFree()
        requireSignerRotation(
            samePriorHistory(other) &&
                (
                    (activationRow == null && other.activationRow == null) ||
                        (activationRow != null && other.activationRow != null && activationRow.same(other.activationRow))
                    ),
            CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED,
        )
    }

    /** Full bounded equality under locks; no input detachment, hashing, JSON parsing or callbacks. */
    internal fun matchesHistory(rows: List<StoredSignerRotationActivationRowV1>): Boolean =
        rows.size == (if (activationRow == null) 2 else 3) && genesisRow.same(rows[0]) && overlapRow.same(rows[1]) &&
            (activationRow == null || activationRow.same(rows[2]))

    override fun toString(): String = "CatalogSignerRotationActivationObservationV1(actual-full33-history,no-authority)"

    companion object {
        internal fun issuedBy(operation: CatalogSignerRotationActivationOperationV1): CatalogSignerRotationActivationObservationV1 {
            val rows = operation.releasedRows()
            check(rows.size in 2..3)
            return CatalogSignerRotationActivationObservationV1(rows[0], rows[1], rows.getOrNull(2))
        }
    }
}

/** All 33 V14 columns copied from bounded JDBC projections, private and immutable, with mutation-specific equality. */
internal class StoredSignerRotationActivationRowV1 private constructor(
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
    val state: String get() = lifecycle.state
    val completedAt: Instant? get() = lifecycle.completedAt
    val projectedAt: Instant? get() = lifecycle.projectedAt

    internal fun requireGenesis() {
        check(shape.bounded && shape.genesisMatches && !shape.overlapMatches && !shape.activationMatches && header.successorGeneration == 1L)
        requireProjected()
    }

    internal fun requireOverlap() {
        check(shape.bounded && shape.overlapMatches && !shape.genesisMatches && !shape.activationMatches && header.successorGeneration == 2L)
        requireProjected()
    }

    internal fun requireActivation() {
        check(shape.bounded && shape.activationMatches && !shape.genesisMatches && !shape.overlapMatches && header.successorGeneration == 3L)
        check(signers.policy == "SINGLE" && signers.secondId == null && signers.secondAlgorithm == null && signers.secondSignature == null)
    }

    internal fun requirePrepared() {
        check(lifecycle.state == "PREPARED" && lifecycle.completedAt == null && lifecycle.projectedAt == null && copies.empty())
    }

    internal fun requireUnsigned() {
        requireActivation()
        check(signers.firstSignature == null && envelope.bytes == null && envelope.hash == null)
    }

    internal fun requireSigned() {
        check(signers.firstSignature != null && envelope.bytes != null && envelope.hash != null)
    }

    internal fun requirePending() {
        requireSigned()
        check(lifecycle.state == "COMPLETED" && lifecycle.completedAt != null && lifecycle.projectedAt == null && copies.complete())
    }

    internal fun requireProjected() {
        requireSigned()
        check(lifecycle.state == "COMPLETED" && lifecycle.completedAt != null && lifecycle.projectedAt != null && copies.complete())
    }

    internal fun matchesNextSignature(arguments: Array<Any?>): Boolean {
        check(arguments.size == 17) // Before A14 + new S3, all detached before phase entry.
        return signers.firstSignature.contentEquals(arguments[14] as ByteArray?) && envelope.bytes.contentEquals(arguments[15] as ByteArray?) &&
            envelope.hash.contentEquals(arguments[16] as ByteArray?)
    }

    internal fun requireSignatureOf(before: StoredSignerRotationActivationRowV1) {
        before.requirePrepared()
        requirePrepared()
        requireSigned()
        check(sameUnsigned(before) && copies.same(before.copies) && lifecycle.same(before.lifecycle))
        if (before.signers.firstSignature != null) check(same(before)) // Exact replay cannot replace a signature or envelope.
    }

    internal fun requireCompletionOf(before: StoredSignerRotationActivationRowV1) {
        before.requirePrepared()
        before.requireSigned()
        requirePending()
        check(sameFrozen(before)) // Only state/completed_at and SQL-bound C6 change; projected_at remains NULL.
    }

    internal fun requireProjectionOf(before: StoredSignerRotationActivationRowV1) {
        before.requirePending()
        requireProjected()
        check(lifecycle.completedAt == before.lifecycle.completedAt && sameFrozen(before) && copies.same(before.copies))
    }

    internal fun same(other: StoredSignerRotationActivationRowV1): Boolean = sameFrozen(other) && copies.same(other.copies) && lifecycle.same(other.lifecycle)

    private fun sameFrozen(other: StoredSignerRotationActivationRowV1): Boolean =
        sameUnsigned(other) && envelope.same(other.envelope) && signers.sameSignatures(other.signers)

    private fun sameUnsigned(other: StoredSignerRotationActivationRowV1): Boolean =
        header.same(other.header) && approval.same(other.approval) && unsigned.same(other.unsigned) &&
            signers.sameIdentity(other.signers) && objectKey == other.objectKey && createdAt == other.createdAt

    internal fun mutation(): CatalogFrozenMutation {
        requireConnectionFree()
        return CatalogFrozenMutation(
            1,
            header.token.toString(),
            checkNotNull(unsigned.bytes),
            HexFormat.of().formatHex(checkNotNull(unsigned.hash)),
            envelope.bytes,
            envelope.hash?.let(HexFormat.of()::formatHex),
            listOfNotNull(
                CatalogFrozenSignatureSlot(signers.firstId, signers.firstAlgorithm, signers.firstSignature),
                signers.secondId?.let { CatalogFrozenSignatureSlot(it, checkNotNull(signers.secondAlgorithm), signers.secondSignature) },
            ),
        )
    }

    /** The actual independent C6, never rebuilt from manifest/envelope bytes. */
    internal fun copyArguments(): Array<Any?> {
        requireConnectionFree()
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

    internal fun historyArguments(): Array<Any?> {
        requireConnectionFree()
        return arrayOf(
            header.token, header.operation, header.scope, header.testOnly, header.predecessorGeneration, header.predecessorHash.copyOf(),
            header.successorGeneration, header.writer, approval.bytes?.copyOf(), approval.hash?.copyOf(), header.canonicalizer,
            unsigned.bytes?.copyOf(), unsigned.hash?.copyOf(), signers.policy, signers.firstId, signers.firstAlgorithm, signers.firstSignature?.copyOf(),
            signers.secondId, signers.secondAlgorithm, signers.secondSignature?.copyOf(), envelope.bytes?.copyOf(), envelope.hash?.copyOf(),
            objectKey, copies.version, copies.retainUntil?.let(Timestamp::from),
            copies.primary.bytes?.copyOf(), copies.primary.hash?.copyOf(), copies.replica.bytes?.copyOf(), copies.replica.hash?.copyOf(),
            lifecycle.state, Timestamp.from(createdAt), lifecycle.completedAt?.let(Timestamp::from), lifecycle.projectedAt?.let(Timestamp::from),
        )
    }

    override fun toString(): String = "StoredSignerRotationActivationRowV1(bounded-private-full33,redacted)"

    private class Shape(val genesisMatches: Boolean, val overlapMatches: Boolean, val activationMatches: Boolean, val bounded: Boolean)

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
        val firstSignature: ByteArray?,
        val secondId: String?,
        val secondAlgorithm: String?,
        val secondSignature: ByteArray?,
    ) {
        fun sameIdentity(other: Signers): Boolean = policy == other.policy && firstId == other.firstId && firstAlgorithm == other.firstAlgorithm &&
            secondId == other.secondId && secondAlgorithm == other.secondAlgorithm

        fun sameSignatures(other: Signers): Boolean = firstSignature.contentEquals(other.firstSignature) && secondSignature.contentEquals(other.secondSignature)

        companion object {
            fun copy(row: ResultSet): Signers = Signers(
                checkNotNull(row.getString("signer_policy")),
                checkNotNull(row.getString("signer_one_id")),
                checkNotNull(row.getString("signer_one_algorithm")),
                row.getBytes("signer_one_signature")?.copyOf(),
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
        internal fun copy(row: ResultSet): StoredSignerRotationActivationRowV1 {
            val shape = Shape(
                row.requiredActivationBoolean("genesis_matches"),
                row.requiredActivationBoolean("overlap_matches"),
                row.requiredActivationBoolean("activation_matches"),
                row.requiredActivationBoolean("bounded"),
            )
            check(shape.bounded) // Do not confuse a CASE-truncated optional field with an accepted NULL.
            return StoredSignerRotationActivationRowV1(
                shape,
                Header(
                    checkNotNull(row.getObject("operation_token", UUID::class.java)), checkNotNull(row.getString("operation_type")),
                    row.getObject("data_scope_id", UUID::class.java), row.nullableActivationBoolean("test_only"),
                    row.requiredActivationLong("predecessor_generation"), checkNotNull(row.getBytes("predecessor_hash")).copyOf(),
                    row.requiredActivationLong("successor_generation"), checkNotNull(row.getObject("catalog_writer_generation", UUID::class.java)),
                    checkNotNull(row.getString("canonicalizer")),
                ),
                Document.copy(row, "approval"), Document.copy(row, "unsigned"), Document.copy(row, "envelope"), Signers.copy(row),
                checkNotNull(row.getString("object_key")), checkNotNull(row.getTimestamp("created_at")).toInstant(),
                Copies.copy(row), Lifecycle.copy(row),
            )
        }
    }
}

private fun ResultSet.requiredActivationBoolean(column: String): Boolean = getBoolean(column).also { check(!wasNull()) }
private fun ResultSet.nullableActivationBoolean(column: String): Boolean? = getBoolean(column).let { if (wasNull()) null else it }
private fun ResultSet.requiredActivationLong(column: String): Long = getLong(column).also { check(!wasNull()) }
