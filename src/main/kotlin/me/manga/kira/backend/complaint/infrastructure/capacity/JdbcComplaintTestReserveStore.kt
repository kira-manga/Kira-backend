package me.manga.kira.backend.complaint.infrastructure.capacity

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintTestReserveSpend
import me.manga.kira.backend.complaint.domain.ComplaintTestReserveSpendExpectation
import me.manga.kira.backend.complaint.domain.ComplaintTestReserveSpendResult
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationTestBinding
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID

/**
 * Dormant/non-bean lower composition: counters BEFORE the existing ACTIVE run, in one ordinary
 * phase. Synthetic comparison/charge fixtures are not authenticated policy or allocation producers.
 * No creation, state transition, terminal reservation conversion, unused release or replay protocol.
 */
internal class JdbcComplaintTestReserveStore(
    private val jdbc: JdbcTemplate,
    private val capacity: JdbcComplaintCapacityStore,
    desired: ComplaintInstallationDesiredSettings.Configured,
) : ComplaintTestReserveSpend {
    private val binding = ComplaintInstallationTestBinding(desired)

    override fun spend(expectation: ComplaintTestReserveSpendExpectation): ComplaintTestReserveSpendResult =
        ComplaintTestReserveSpendOperation.prepare(jdbc, expectation, binding).spend(capacity)

    override fun toString(): String = "JdbcComplaintTestReserveStore(redacted)"
}

/** Retained before counter SQL; an observed run and private stage cursor are not W04 allocation authority. */
internal class ComplaintTestReserveSpendOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val expectation: ComplaintTestReserveSpendExpectation,
    private val binding: ComplaintInstallationTestBinding,
) {
    private var stage = Stage.PREPARED
    private var counters: JdbcComplaintCapacityStore.LockedTestReserveSpend? = null
    private var run: LockedRun? = null
    private var remaining: ComplaintCapacityVector? = null

    internal fun belongsTo(candidate: PersistencePhaseContext): Boolean = phase === candidate

    internal fun completedResult(candidate: PersistencePhaseContext): ComplaintTestReserveSpendResult? =
        if (phase === candidate && stage === Stage.COMPLETE) ComplaintTestReserveSpendResult.SPENT else null

    @Suppress("TooGenericExceptionCaught")
    internal fun spend(capacity: JdbcComplaintCapacityStore): ComplaintTestReserveSpendResult {
        try {
            requireAt(Stage.PREPARED, jdbc)
            stage = Stage.COUNTERS_REQUESTED
            val locked = capacity.lockForTestReserveSpend(this)
            requireAt(Stage.COUNTERS_LOCKING, jdbc)
            check(locked.belongsTo(this))
            counters = locked
            stage = Stage.RUN_LOCKING
            run = jdbc.query(LOCK_RUN, { row, _ -> readRun(row, expectation, binding) }, expectation.scope.id).single()
            requireAt(Stage.RUN_LOCKING, jdbc)
            stage = Stage.TRANSFERRING
            locked.transfer(this) // Both run remainder and the entire ledger are checked before its first UPDATE.
            requireAt(Stage.TRANSFERRING, jdbc)
            check(locked.completedFor(this))
            stage = Stage.UPDATING_RUN
            updateUnused()
            requireAt(Stage.UPDATING_RUN, jdbc)
            stage = Stage.COMPLETE
            return ComplaintTestReserveSpendResult.SPENT
        } catch (problem: Throwable) {
            failed(problem)
        }
    }

    internal fun beginCounterLock(selected: JdbcTemplate) {
        requireAt(Stage.COUNTERS_REQUESTED, selected)
        stage = Stage.COUNTERS_LOCKING
    }

    internal fun spendLockedLedger(selected: JdbcTemplate, ledger: ComplaintCapacityLedger, expectedDigest: ByteArray): ComplaintCapacityLedger {
        requireAt(Stage.TRANSFERRING, selected)
        val original = checkNotNull(run)
        binding.requireConfiguration(original.configurationHash) // Run D is independent of the counter-policy P below.
        val spent = expectation.toActual + expectation.toRecovery
        check(spent.fitsWithin(original.unused))
        val after = ledger.spendTestReserve(expectedDigest, expectation.toActual, expectation.toRecovery)
        remaining = original.unused - spent
        return after
    }

    internal fun requireCounterTransfer(locked: JdbcComplaintCapacityStore.LockedTestReserveSpend, selected: JdbcTemplate) {
        requireAt(Stage.TRANSFERRING, selected)
        if (counters !== locked) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
    }

    internal fun failed(problem: Throwable): Nothing {
        phase.recordFailure(problem)
        PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private fun requireAt(expected: Stage, selected: JdbcTemplate) {
        phase.requireComplaintTestReserveSpend(this, selected)
        if (stage !== expected) failed(PersistencePhaseException(PersistencePhaseFailureCode.WORK_FAILED))
    }

    private fun updateUnused() {
        requireAt(Stage.UPDATING_RUN, jdbc)
        val original = checkNotNull(run)
        val updated = jdbc.update(
            SPEND_RUN,
            checkNotNull(remaining).sqlArray(),
            expectation.scope.id,
            expectation.accountingVersion,
            original.configurationHash,
            expectation.originalReserve.sqlArray(),
            original.unused.sqlArray(),
        )
        check(updated == 1)
    }

    override fun toString(): String = "ComplaintTestReserveSpendOperation(redacted)"

    private class LockedRun(val configurationHash: ByteArray, val unused: ComplaintCapacityVector)

    private enum class Stage { PREPARED, COUNTERS_REQUESTED, COUNTERS_LOCKING, RUN_LOCKING, TRANSFERRING, UPDATING_RUN, COMPLETE }

    companion object {
        private val LOCK_RUN = """
            SELECT data_scope_id, test_only, state, configuration_hash, accounting_version,
                installation_limit, enrolled_count, activation_catalog_generation, activation_catalog_hash,
                original_reserve, array_ndims(original_reserve) AS original_dimensions,
                array_lower(original_reserve, 1) AS original_lower, cardinality(original_reserve) AS original_width,
                unused_reserve, array_ndims(unused_reserve) AS unused_dimensions,
                array_lower(unused_reserve, 1) AS unused_lower, cardinality(unused_reserve) AS unused_width,
                isfinite(created_at) AS finite_created,
                sealed_at IS NULL AND purging_at IS NULL AND purged_at IS NULL
                    AND final_ordinary_epoch IS NULL AND terminal_seal_epoch IS NULL AND generation_seal_count IS NULL
                    AND generation_seal_root IS NULL AND seal_set_bytes IS NULL AND seal_set_hash IS NULL
                    AND event_manifest_count IS NULL AND event_manifest_root IS NULL
                    AND installation_manifest_count IS NULL AND installation_manifest_root IS NULL
                    AND installation_chunk_count IS NULL AND retired_count IS NULL AND deleted_count IS NULL
                    AND permanent_denial_bytes IS NULL AND permanent_denial_hash IS NULL
                    AND terminal_event_id IS NULL AND terminal_object_key IS NULL AND terminal_object_version IS NULL
                    AND terminal_ciphertext_hash IS NULL AND terminal_catalog_generation IS NULL AND terminal_catalog_hash IS NULL
                    AND recurrent_erasure_history_hash IS NULL AS active_shape
            FROM complaint_test_runs
            WHERE data_scope_id = ?
            FOR UPDATE
        """.trimIndent()
        private val SPEND_RUN = """
            UPDATE complaint_test_runs
            SET unused_reserve = ?::bigint[]
            WHERE data_scope_id = ? AND test_only = true AND state = 'ACTIVE' AND accounting_version = ?
                AND configuration_hash = ? AND original_reserve = ?::bigint[] AND unused_reserve = ?::bigint[]
        """.trimIndent()

        @Suppress("TooGenericExceptionCaught")
        internal fun prepare(
            jdbc: JdbcTemplate,
            expectation: ComplaintTestReserveSpendExpectation,
            binding: ComplaintInstallationTestBinding,
        ): ComplaintTestReserveSpendOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.requireComplaintTestReserveSpend(jdbc)
                val operation = ComplaintTestReserveSpendOperation(phase, jdbc, expectation, binding)
                phase.retainComplaintTestReserveSpend(operation, jdbc) // Retained before any counter or run SQL/decoder can fail.
                return operation
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        private fun readRun(row: ResultSet, expected: ComplaintTestReserveSpendExpectation, binding: ComplaintInstallationTestBinding): LockedRun {
            check(expected.scope == binding.scope)
            check(expected.scope.testOnly && requiredBoolean(row, "test_only"))
            check(row.getObject("data_scope_id", UUID::class.java) == expected.scope.id)
            check(row.getString("state") == "ACTIVE" && requiredBoolean(row, "active_shape") && requiredBoolean(row, "finite_created"))
            val version = requiredInt(row, "accounting_version")
            ComplaintCapacityEncoding.requireVersion(version)
            check(version == expected.accountingVersion)
            val limit = requiredLong(row, "installation_limit")
            check(limit > 0 && requiredLong(row, "enrolled_count") in 0L..limit)
            check(requiredLong(row, "activation_catalog_generation") in 1L..65_536L && row.getBytes("activation_catalog_hash")?.size == 32)
            val original = readVector(row, "original", version)
            val unused = readVector(row, "unused", version)
            check(original == expected.originalReserve && unused == expected.expectedUnused && unused.fitsWithin(original))
            val configurationHash = checkNotNull(row.getBytes("configuration_hash"))
            check(configurationHash.size == 32)
            binding.requireConfiguration(configurationHash)
            return LockedRun(configurationHash, unused)
        }

        private fun readVector(row: ResultSet, prefix: String, version: Int): ComplaintCapacityVector {
            check(requiredInt(row, "${prefix}_dimensions") == 1 && requiredInt(row, "${prefix}_lower") == 1)
            check(requiredInt(row, "${prefix}_width") == ComplaintCapacityEncoding.WIDTH)
            val array = checkNotNull(row.getArray("${prefix}_reserve"))
            try {
                check(array.baseType == Types.BIGINT)
                val values = array.array as? Array<*> ?: error("Invalid test reserve representation.")
                check(values.size == ComplaintCapacityEncoding.WIDTH)
                return ComplaintCapacityVector.of(LongArray(values.size) { checkNotNull(values[it] as? Long) }, version)
            } finally {
                array.free()
            }
        }

        private fun ComplaintCapacityVector.sqlArray(): String = toLongArray().joinToString(separator = ",", prefix = "{", postfix = "}")

        private fun requiredInt(row: ResultSet, name: String): Int = row.getInt(name).also { check(!row.wasNull()) }

        private fun requiredLong(row: ResultSet, name: String): Long = row.getLong(name).also { check(!row.wasNull()) }

        private fun requiredBoolean(row: ResultSet, name: String): Boolean = row.getBoolean(name).also { check(!row.wasNull()) }
    }
}
