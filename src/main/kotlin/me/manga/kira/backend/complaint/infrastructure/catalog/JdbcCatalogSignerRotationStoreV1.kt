package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.builtins.ListSerializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenSignatureSlot
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.util.HexFormat
import java.util.UUID

/** Fixed first-overlap store only. No G1 mutation, general history writer, COMPLETE/PROJECT, head write or callback. */
internal class JdbcCatalogSignerRotationStoreV1(private val jdbc: JdbcTemplate) {
    internal fun execute(input: CatalogSignerRotationSqlInputV1, capacity: JdbcComplaintCapacityStore): CatalogSignerRotationOperationV1 =
        CatalogSignerRotationOperationV1.execute(jdbc, input, capacity)
}

/** Shared epoch fence -> LIVE control -> exclusive catalog lock -> exact history -> counters, on the original one-slot coordinator. */
internal class CatalogSignerRotationOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val input: CatalogSignerRotationSqlInputV1,
) {
    private var stage = Stage.RETAINED
    private var original: List<StoredSignerRotationRow>? = null
    private var captured: List<StoredSignerRotationRow>? = null
    private var counters: JdbcComplaintCapacityStore.LockedCatalogSignerRotation? = null

    internal fun belongsTo(selected: PersistencePhaseContext, path: PersistencePhasePath): Boolean = phase === selected && input.path === path
    internal fun completedFor(selected: PersistencePhaseContext): Boolean = phase === selected && stage === Stage.COMPLETE

    val observation: CatalogSignerRotationObservationV1
        get() {
            phase.catalogSignerRotation.requireCommitted(this)
            requireConnectionFree()
            val rows = checkNotNull(captured)
            val genesis = rows[0].mutation()
            val manifest = OfflineTrustBundleParser.parseGenesis(checkNotNull(genesis.signedEnvelopeBytes)).manifest
            val approvals = CanonicalJson.canonicalize(ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), manifest.approvals).toByteArray()
            check(rows[0].approval.contentEquals(approvals))
            return CatalogSignerRotationObservationV1(genesis, rows.getOrNull(1)?.mutation())
        }

    @Suppress("TooGenericExceptionCaught")
    private fun run(capacity: JdbcComplaintCapacityStore) {
        try {
            requireAt(Stage.RETAINED)
            requireControl(LOCK_SIGNER_ROTATION_CONTROL)
            stage = Stage.CONTROL_LOCKED
            requireCurrentLease()
            check(jdbc.query(TRY_CATALOG_LOCK, { row, _ -> row.requiredRotationBoolean("locked") }).single())
            requireAt(Stage.CONTROL_LOCKED)
            stage = Stage.CATALOG_LOCKED
            original = readHistory(lock = true).also(::requirePreimage)
            stage = Stage.HISTORY_LOCKED
            val locked = capacity.lockForCatalogSignerRotation(this)
            requireAt(Stage.COUNTERS_LOCKING)
            check(locked.belongsTo(this))
            counters = locked
            stage = Stage.SETTLING_COUNTERS
            locked.settle(this)
            requireAt(Stage.SETTLING_COUNTERS)
            check(locked.settledFor(this))
            stage = Stage.WRITING
            requireCurrentLease()
            when (input.path) {
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ -> Unit

                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE -> check(
                    jdbc.update(INSERT_SIGNER_ROTATION_PREPARED, *input.insertArguments()) == 1,
                )

                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE -> {
                    if (!checkNotNull(original)[1].matchesSignatures(input.nextSignatures())) {
                        check(jdbc.update(WRITE_SIGNER_ROTATION_SIGNATURE, *input.signatureArguments()) == 1)
                    }
                }

                else -> error("Unsupported signer rotation phase.")
            }
            requireAt(Stage.WRITING)
            stage = Stage.REREADING
            requireCurrentLease()
            val reread = readHistory(lock = false)
            val before = checkNotNull(original)
            check(reread[0].sameBytes(before[0]))
            when (input.path) {
                PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ -> {
                    check(reread.size == before.size && reread.indices.all { reread[it].sameBytes(before[it]) })
                }

                else -> check(reread.size == 2 && reread[1].matchesSignatures(input.nextSignatures()))
            }
            requireCurrentLease()
            requireAt(Stage.REREADING)
            captured = reread
            stage = Stage.COMPLETE
        } catch (problem: Throwable) {
            failed(problem)
        }
    }

    private fun requireControl(sql: String) {
        phase.catalogSignerRotation.requireRetained(this, jdbc)
        check(jdbc.query(sql, { row, _ -> row.requiredRotationBoolean("binding_matches") }, *input.bindingArguments()).single())
        phase.catalogSignerRotation.requireRetained(this, jdbc)
    }

    private fun requireCurrentLease() = requireControl(READ_SIGNER_ROTATION_CURRENT_LEASE)

    private fun readHistory(lock: Boolean): List<StoredSignerRotationRow> {
        phase.catalogSignerRotation.requireRetained(this, jdbc)
        val rows = jdbc.query(
            if (lock) LOCK_SIGNER_ROTATION_HISTORY else READ_SIGNER_ROTATION_HISTORY,
            { row, _ -> StoredSignerRotationRow.copy(row) },
            *input.historyArguments(),
        )
        phase.catalogSignerRotation.requireRetained(this, jdbc)
        check(rows.size in 1..2) // LIMIT3 detects any unrelated/prior/extra history, not merely the global pending row.
        check(rows[0].genesisMatches && rows[0].bounded && rows[0].generation == 1L)
        rows.getOrNull(1)?.let { check(it.rotationMatches && it.bounded && it.generation == 2L) }
        return rows
    }

    private fun requirePreimage(rows: List<StoredSignerRotationRow>) {
        check((rows.size == 2) == input.requirePrepared)
        input.expectedGenesisFields()?.let { check(rows[0].matchesGenesis(it)) }
        if (input.expected?.mutation != null) check(rows[1].matchesSignatures(input.expectedSignatures()))
        if (input.path === PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE) check(rows.size == 1)
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        requireAt(Stage.HISTORY_LOCKED)
        check(selected === jdbc)
        stage = Stage.COUNTERS_LOCKING
    }

    internal fun requireCounterSettlement(locked: JdbcComplaintCapacityStore.LockedCatalogSignerRotation, selected: JdbcTemplate): Pair<Int, Boolean> {
        requireAt(Stage.SETTLING_COUNTERS)
        check(counters === locked && selected === jdbc)
        return checkNotNull(original).size to (input.path === PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE)
    }

    internal fun failed(problem: Throwable): Nothing {
        stage = Stage.FAILED
        phase.recordFailure(problem)
        PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private fun requireAt(expected: Stage) {
        phase.catalogSignerRotation.requireRetained(this, jdbc)
        check(stage === expected)
    }

    override fun toString(): String = "CatalogSignerRotationOperationV1(original-fenced-fullB-phase,head-unchanged,no-publication-authority)"

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
            input: CatalogSignerRotationSqlInputV1,
            capacity: JdbcComplaintCapacityStore,
        ): CatalogSignerRotationOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.catalogSignerRotation.requireOperation(input, jdbc)
                val operation = CatalogSignerRotationOperationV1(phase, jdbc, input)
                phase.catalogSignerRotation.retain(operation, jdbc)
                operation.run(capacity)
                return operation
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }
    }
}

/** Only finite bounded columns escape the driver. No hashing/parsing/providers or caller callbacks under the locks. */
private class StoredSignerRotationRow(
    private val shape: StoredSignerRotationShape,
    val generation: Long,
    private val token: UUID,
    private val firstId: String,
    private val firstAlgorithm: String,
    private val secondId: String?,
    private val secondAlgorithm: String?,
    private val unsigned: ByteArray?,
    private val unsignedHash: ByteArray?,
    val approval: ByteArray?,
    private val signatureOne: ByteArray?,
    private val signatureTwo: ByteArray?,
    private val envelope: ByteArray?,
    private val envelopeHash: ByteArray?,
) {
    val genesisMatches: Boolean get() = shape.genesisMatches
    val rotationMatches: Boolean get() = shape.rotationMatches
    val bounded: Boolean get() = shape.bounded

    fun matchesSignatures(expected: Array<Any?>): Boolean = signatureOne.contentEquals(expected[0] as ByteArray?) &&
        signatureTwo.contentEquals(expected[1] as ByteArray?) && envelope.contentEquals(expected[2] as ByteArray?) &&
        envelopeHash.contentEquals(expected[3] as ByteArray?)

    fun matchesGenesis(expected: Array<Any?>): Boolean = token == expected[0] && unsigned.contentEquals(expected[1] as ByteArray?) &&
        unsignedHash.contentEquals(expected[2] as ByteArray?) && signatureOne.contentEquals(expected[3] as ByteArray?) &&
        envelope.contentEquals(expected[4] as ByteArray?) && envelopeHash.contentEquals(expected[5] as ByteArray?) &&
        approval.contentEquals(expected[6] as ByteArray?)

    fun sameBytes(other: StoredSignerRotationRow): Boolean = token == other.token && firstId == other.firstId && firstAlgorithm == other.firstAlgorithm &&
        secondId == other.secondId && secondAlgorithm == other.secondAlgorithm && unsigned.contentEquals(other.unsigned) &&
        unsignedHash.contentEquals(other.unsignedHash) &&
        approval.contentEquals(other.approval) && signatureOne.contentEquals(other.signatureOne) && signatureTwo.contentEquals(other.signatureTwo) &&
        envelope.contentEquals(other.envelope) && envelopeHash.contentEquals(other.envelopeHash)

    fun mutation(): CatalogFrozenMutation = CatalogFrozenMutation(
        1,
        token.toString(),
        checkNotNull(unsigned),
        HexFormat.of().formatHex(checkNotNull(unsignedHash)),
        envelope,
        envelopeHash?.let(HexFormat.of()::formatHex),
        listOfNotNull(
            CatalogFrozenSignatureSlot(firstId, firstAlgorithm, signatureOne),
            secondId?.let {
                CatalogFrozenSignatureSlot(it, checkNotNull(secondAlgorithm), signatureTwo)
            },
        ),
    )

    companion object {
        fun copy(row: ResultSet): StoredSignerRotationRow = StoredSignerRotationRow(
            StoredSignerRotationShape(
                row.requiredRotationBoolean("genesis_matches"),
                row.requiredRotationBoolean("rotation_matches"),
                row.requiredRotationBoolean("bounded"),
            ),
            row.getLong("successor_generation").also { check(!row.wasNull()) }, checkNotNull(row.getObject("operation_token", UUID::class.java)),
            checkNotNull(
                row.getString("signer_one_id"),
            ),
            checkNotNull(row.getString("signer_one_algorithm")), row.getString("signer_two_id"), row.getString("signer_two_algorithm"),
            row.getBytes("unsigned_bytes")?.copyOf(), row.getBytes("unsigned_hash")?.copyOf(), row.getBytes("approval_bytes")?.copyOf(),
            row.getBytes("signer_one_signature")?.copyOf(), row.getBytes("signer_two_signature")?.copyOf(),
            row.getBytes("envelope_bytes")?.copyOf(), row.getBytes("envelope_hash")?.copyOf(),
        )
    }
}

/** The same three bounded SQL shape predicates; grouping does not turn observations into operation authority. */
private class StoredSignerRotationShape(val genesisMatches: Boolean, val rotationMatches: Boolean, val bounded: Boolean)

private fun ResultSet.requiredRotationBoolean(column: String): Boolean = getBoolean(column).also { check(!wasNull()) }
