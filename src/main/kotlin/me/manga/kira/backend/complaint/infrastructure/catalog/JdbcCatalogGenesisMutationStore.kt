package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenSignatureSlot
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.UnverifiedGenesisPreparation
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.util.HexFormat

/** Dormant fixed G1 writes; never writes through the read-only snapshot phase or confers deployment/restore admission. */
internal class JdbcCatalogGenesisMutationStore(private val jdbc: JdbcTemplate) {
    fun prepare(input: CatalogGenesisMutationInput, capacity: JdbcComplaintCapacityStore): CatalogGenesisMutationOperation =
        CatalogGenesisMutationOperation.prepare(jdbc, input, capacity)

    fun persistSignature(input: CatalogGenesisMutationInput, capacity: JdbcComplaintCapacityStore): CatalogGenesisMutationOperation =
        CatalogGenesisMutationOperation.persistSignature(jdbc, input, capacity)

    fun complete(input: CatalogGenesisMutationInput, capacity: JdbcComplaintCapacityStore): CatalogGenesisMutationOperation =
        CatalogGenesisMutationOperation.complete(jdbc, input, capacity)

    fun project(input: CatalogGenesisMutationInput, capacity: JdbcComplaintCapacityStore): CatalogGenesisMutationOperation =
        CatalogGenesisMutationOperation.project(jdbc, input, capacity)
}

/** One retained concrete SQL operation. Counters cannot be reached until the existing mutation/history is locked. */
internal class CatalogGenesisMutationOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val input: CatalogGenesisMutationInput,
) {
    private var stage = Stage.RETAINED
    private var original: StoredGenesisMutation? = null
    private var counters: JdbcComplaintCapacityStore.LockedCatalogGenesis? = null
    private var captured: StoredGenesisMutation? = null
    private var control: StoredGenesisControl? = null
    private var finalizationState: CatalogGenesisFinalizationState? = null

    fun belongsTo(selected: PersistencePhaseContext, path: PersistencePhasePath): Boolean = phase === selected && input.path === path

    fun completedFor(selected: PersistencePhaseContext): Boolean = phase === selected && stage === Stage.COMPLETE

    val observation: UnverifiedGenesisPreparation
        get() {
            phase.catalogGenesis.requireCommitted(this)
            requireConnectionFree()
            check(input.finalization == null)
            return checkNotNull(captured).observation(input)
        }

    val finalizationObservation: CatalogGenesisFinalizationObservation
        get() {
            phase.catalogGenesis.requireCommitted(this)
            requireConnectionFree()
            requireProcessBinding()
            return checkNotNull(input.finalization).observation(checkNotNull(finalizationState))
        }

    val processBoundProjection: ProcessBoundCatalogGenesisProjection
        get() = ProcessBoundCatalogGenesisProjection.issuedBy(this)

    /** Only this actual retained PROJECT operation can issue the private handle, after known commit AND release. */
    internal fun requireReleasedProcessProjection(): Pair<CatalogGenesisFinalizationInput, CatalogGenesisFinalizationState> {
        phase.catalogGenesis.requireCommitted(this)
        requireConnectionFree()
        val finalization = checkNotNull(input.finalization)
        requireCatalogReadback(
            input.path === PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT && finalization.binding.process != null &&
                finalizationState in setOf(CatalogGenesisFinalizationState.PROJECTED, CatalogGenesisFinalizationState.ALREADY_PROJECTED),
            CatalogReadbackFailure.INVALID_LOCAL_STATE,
        )
        requireProcessBinding()
        return finalization to checkNotNull(finalizationState)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun execute(capacity: JdbcComplaintCapacityStore) {
        try {
            requireAt(Stage.RETAINED, jdbc)
            if (input.finalization == null) {
                check(jdbc.query(LOCK_GENESIS_CONTROL, { row, _ -> row.requiredBoolean("genesis_closed") }).single())
            } else {
                control = readControl(lock = true).also(::requireControl)
            }
            requireAt(Stage.RETAINED, jdbc)
            stage = Stage.CONTROL_LOCKED
            check(jdbc.query(TRY_CATALOG_LOCK, { row, _ -> row.requiredBoolean("locked") }).single())
            requireAt(Stage.CONTROL_LOCKED, jdbc)
            stage = Stage.CATALOG_LOCKED
            original = readMutation(lock = true)
            requireAt(Stage.CATALOG_LOCKED, jdbc)
            original?.requireFrozen()
            if (input.path === PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE) {
                checkNotNull(original).requireSignature(input.before)
            }
            if (input.finalization != null) requireFinalizationPreimage()
            stage = Stage.MUTATION_LOCKED
            val locked = capacity.lockForCatalogGenesis(this)
            requireAt(Stage.COUNTERS_LOCKING, jdbc)
            check(locked.belongsTo(this))
            counters = locked
            stage = Stage.SETTLING_COUNTERS
            locked.settle(this)
            requireAt(Stage.SETTLING_COUNTERS, jdbc)
            check(locked.settledFor(this))
            stage = Stage.WRITING
            write()
            requireAt(Stage.WRITING, jdbc)
            stage = Stage.REREADING
            val reread = checkNotNull(readMutation(lock = false))
            requireAt(Stage.REREADING, jdbc)
            reread.requireFrozen()
            if (input.finalization == null) {
                if (input.path === PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE && original != null) {
                    reread.requireSameSignature(checkNotNull(original)) // Diagnostic replay preserves any existing exact bytes.
                } else {
                    reread.requireSignature(input.after)
                }
            }
            captured = reread
            if (input.finalization != null) {
                checkFinalizationReread(reread, readControl(lock = false))
                requireAt(Stage.REREADING, jdbc)
            }
            stage = Stage.COMPLETE
        } catch (problem: Throwable) {
            failed(problem)
        }
    }

    private fun readMutation(lock: Boolean): StoredGenesisMutation? {
        val finalization = input.finalization != null
        val sql = if (finalization) {
            if (lock) LOCK_GENESIS_FINALIZATION else READ_GENESIS_FINALIZATION
        } else {
            if (lock) LOCK_GENESIS_MUTATION else READ_GENESIS_MUTATION
        }
        val arguments = if (finalization) input.finalizationArguments(this, jdbc) else input.frozenArguments()
        val rows = jdbc.query(sql, { row, _ -> StoredGenesisMutation.copy(row, finalization) }, *arguments)
        check(rows.size <= 1) // Query locks at most two across ALL history, not merely pending rows.
        return rows.singleOrNull()
    }

    private fun write() {
        if (input.finalization != null) {
            writeFinalization()
        } else if (original == null) {
            check(input.path === PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE)
            check(jdbc.update(INSERT_GENESIS, *input.frozenArguments()) == 1)
        } else if (input.path === PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE && !checkNotNull(original).sameSignature(input.after)) {
            // CAS includes the complete immutable tuple and exact nullable signature/envelope preimage.
            check(jdbc.update(WRITE_GENESIS_SIGNATURE, *input.afterSignatureArguments(), *input.frozenArguments(), *input.beforeSignatureArguments()) == 1)
        }
    }

    private fun readControl(lock: Boolean): StoredGenesisControl = jdbc.query(
        if (lock) LOCK_GENESIS_FINAL_CONTROL else READ_GENESIS_FINAL_CONTROL,
        { row, _ -> StoredGenesisControl.copy(row) },
        *input.finalizationControlArguments(this, jdbc),
    ).single()

    private fun requireFinalizationPreimage() {
        val row = checkNotNull(original)
        // Finalization's SQL frozen-match includes every signature/envelope byte. Do not clone them again under locks.
        val state = checkNotNull(row.finalization)
        val head = checkNotNull(control)
        when (input.path) {
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE -> check(state.prepared && head.absent)

            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT -> {
                if (state.pending) {
                    check(!checkNotNull(input.finalization).replayOnly && head.pending)
                } else {
                    // A snapshot with no pending row can authorize only this locked, exact no-op.
                    check(state.projected && head.projected && head.identitiesProjected)
                }
            }

            else -> error("Unsupported G1 finalization phase.")
        }
    }

    private fun writeFinalization() {
        val arguments = input.finalizationArguments(this, jdbc)
        val controlArguments = input.finalizationControlArguments(this, jdbc)
        when (input.path) {
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE -> {
                check(jdbc.update(WRITE_GENESIS_COMPLETION, *arguments) == 1)
                requireAt(Stage.WRITING, jdbc)
                check(jdbc.update(WRITE_GENESIS_HEAD, *controlArguments) == 1)
            }

            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT -> {
                if (checkNotNull(checkNotNull(original).finalization).pending) {
                    check(jdbc.update(WRITE_GENESIS_PROJECTION, *arguments) == 1)
                    requireAt(Stage.WRITING, jdbc)
                    check(jdbc.update(WRITE_GENESIS_INITIAL_CONTROL, *controlArguments) == 1)
                }
            }

            else -> error("Unsupported G1 finalization phase.")
        }
    }

    private fun checkFinalizationReread(row: StoredGenesisMutation, head: StoredGenesisControl) {
        requireControl(head)
        val before = checkNotNull(checkNotNull(original).finalization)
        val after = checkNotNull(row.finalization)
        finalizationState = when (input.path) {
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE -> {
                check(after.pending && head.pending)
                CatalogGenesisFinalizationState.COMPLETED_PENDING
            }

            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT -> {
                check(after.projected && head.projected && head.identitiesProjected && before.completedAt == after.completedAt)
                if (before.projected) {
                    check(before.projectedAt == after.projectedAt)
                    CatalogGenesisFinalizationState.ALREADY_PROJECTED
                } else {
                    CatalogGenesisFinalizationState.PROJECTED
                }
            }

            else -> error("Unsupported G1 finalization phase.")
        }
    }

    private fun requireControl(control: StoredGenesisControl) {
        check(control.initialMatches)
        if (input.path === PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT && input.finalization?.binding?.process != null) {
            check(control.currentProcessMatches) // Same locked SELECT and final reread; NULL is never bound D.
        }
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        requireAt(Stage.MUTATION_LOCKED, selected)
        stage = Stage.COUNTERS_LOCKING
    }

    internal fun requireFinalizationArguments(candidate: CatalogGenesisMutationInput, selected: JdbcTemplate) {
        phase.catalogGenesis.requireRetained(this, selected)
        check(candidate === input && selected === jdbc && input.finalization != null)
        check(stage in setOf(Stage.RETAINED, Stage.CATALOG_LOCKED, Stage.WRITING, Stage.REREADING))
    }

    internal fun requireCounterSettlement(locked: JdbcComplaintCapacityStore.LockedCatalogGenesis, selected: JdbcTemplate): Boolean {
        requireAt(Stage.SETTLING_COUNTERS, selected)
        check(counters === locked)
        return original != null
    }

    internal fun failed(problem: Throwable): Nothing {
        stage = Stage.FAILED
        phase.recordFailure(problem)
        PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private fun requireAt(expected: Stage, selected: JdbcTemplate) {
        phase.catalogGenesis.requireRetained(this, selected)
        check(stage === expected && selected === jdbc)
        requireProcessBinding()
    }

    private fun requireProcessBinding() {
        input.finalization?.let { phase.catalogGenesis.requireProcessBinding(it.binding, jdbc) }
    }

    override fun toString(): String = "CatalogGenesisMutationOperation(sealed-G1-observation,no-publication-authority)"

    private enum class Stage {
        RETAINED,
        CONTROL_LOCKED,
        CATALOG_LOCKED,
        MUTATION_LOCKED,
        COUNTERS_LOCKING,
        SETTLING_COUNTERS,
        WRITING,
        REREADING,
        COMPLETE,
        FAILED,
    }

    companion object {
        internal fun prepare(jdbc: JdbcTemplate, input: CatalogGenesisMutationInput, capacity: JdbcComplaintCapacityStore): CatalogGenesisMutationOperation =
            execute(jdbc, input, capacity, PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE)

        internal fun persistSignature(
            jdbc: JdbcTemplate,
            input: CatalogGenesisMutationInput,
            capacity: JdbcComplaintCapacityStore,
        ): CatalogGenesisMutationOperation = execute(jdbc, input, capacity, PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE)

        internal fun complete(jdbc: JdbcTemplate, input: CatalogGenesisMutationInput, capacity: JdbcComplaintCapacityStore): CatalogGenesisMutationOperation =
            execute(jdbc, input, capacity, PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE)

        internal fun project(jdbc: JdbcTemplate, input: CatalogGenesisMutationInput, capacity: JdbcComplaintCapacityStore): CatalogGenesisMutationOperation =
            execute(jdbc, input, capacity, PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT)

        @Suppress("TooGenericExceptionCaught")
        private fun execute(
            jdbc: JdbcTemplate,
            input: CatalogGenesisMutationInput,
            capacity: JdbcComplaintCapacityStore,
            expected: PersistencePhasePath,
        ): CatalogGenesisMutationOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.catalogGenesis.requireOperation(jdbc, expected)
                check(input.path === expected)
                val operation = CatalogGenesisMutationOperation(phase, jdbc, input)
                phase.catalogGenesis.retain(operation, jdbc)
                operation.execute(capacity)
                return operation
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
    }
}

/** Only bounded columns reach JDBC. SQL compares every other frozen column without materializing it. */
private class StoredGenesisMutation(
    private val frozenMatches: Boolean,
    private val bounded: Boolean,
    private val unsigned: ByteArray?,
    private val unsignedHash: ByteArray?,
    private val signature: ByteArray?,
    private val envelope: ByteArray?,
    private val envelopeHash: ByteArray?,
    val finalization: StoredGenesisFinalization?,
) {
    fun requireFrozen() = check(bounded && frozenMatches)

    fun requireSignature(expected: CatalogFrozenMutation) = check(sameSignature(expected))

    fun sameSignature(expected: CatalogFrozenMutation): Boolean = signature.contentEquals(expected.signatureSlots.single().signatureBytes) &&
        envelope.contentEquals(expected.signedEnvelopeBytes) && envelopeHash?.let { HexFormat.of().formatHex(it) } == expected.signedEnvelopeSha256

    fun requireSameSignature(expected: StoredGenesisMutation) {
        check(signature.contentEquals(expected.signature) && envelope.contentEquals(expected.envelope) && envelopeHash.contentEquals(expected.envelopeHash))
    }

    fun observation(input: CatalogGenesisMutationInput): UnverifiedGenesisPreparation {
        val signer = input.before.signatureSlots.single()
        return UnverifiedGenesisPreparation(
            CatalogFrozenMutation(
                1,
                input.before.operationToken,
                checkNotNull(unsigned),
                HexFormat.of().formatHex(checkNotNull(unsignedHash)),
                envelope,
                envelopeHash?.let { HexFormat.of().formatHex(it) },
                listOf(CatalogFrozenSignatureSlot(signer.keyId, signer.algorithmId, signature)),
            ),
        )
    }

    companion object {
        fun copy(row: ResultSet, finalization: Boolean = false): StoredGenesisMutation = StoredGenesisMutation(
            row.requiredBoolean("frozen_matches"),
            row.requiredBoolean("bounded"),
            row.getBytes("unsigned_bytes")?.copyOf(),
            row.getBytes("unsigned_hash")?.copyOf(),
            row.getBytes("signer_one_signature")?.copyOf(),
            row.getBytes("envelope_bytes")?.copyOf(),
            row.getBytes("envelope_hash")?.copyOf(),
            if (finalization) StoredGenesisFinalization.copy(row) else null,
        )
    }
}

private class StoredGenesisFinalization(
    val prepared: Boolean,
    val pending: Boolean,
    val projected: Boolean,
    val completedAt: Instant?,
    val projectedAt: Instant?,
) {
    companion object {
        fun copy(row: ResultSet): StoredGenesisFinalization = StoredGenesisFinalization(
            row.requiredBoolean("final_prepared"),
            row.requiredBoolean("final_pending"),
            row.requiredBoolean("final_projected"),
            row.getTimestamp("completed_at")?.toInstant(),
            row.getTimestamp("projected_at")?.toInstant(),
        )
    }
}

private class StoredGenesisControl(
    val initialMatches: Boolean,
    val currentProcessMatches: Boolean,
    val absent: Boolean,
    val pending: Boolean,
    val projected: Boolean,
    val identitiesProjected: Boolean,
) {
    companion object {
        fun copy(row: ResultSet): StoredGenesisControl = StoredGenesisControl(
            row.requiredBoolean("initial_matches"),
            row.requiredBoolean("current_process_matches"),
            row.requiredBoolean("head_absent"),
            row.requiredBoolean("head_pending"),
            row.requiredBoolean("head_projected"),
            row.requiredBoolean("identities_projected"),
        )
    }
}

private fun ResultSet.requiredBoolean(column: String): Boolean = getBoolean(column).also { check(!wasNull()) }
