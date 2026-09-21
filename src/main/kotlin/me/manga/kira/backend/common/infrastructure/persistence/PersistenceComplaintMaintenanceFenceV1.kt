package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.Connection
import java.util.UUID

/**
 * Fixed SQL-writer participation on the phase's original holder, before its existing locks.
 * The fresh durable TEST gate is checked after M, in a separate statement. This is SQL participation,
 * not an activation right or proof that provider/physical cleanup has ended.
 * One transaction try only: even a late true stays in the original phase's rollback custody.
 */
internal class PersistenceComplaintMaintenanceFenceV1(private val phase: PersistencePhaseContext) {
    private var stage = FenceStage.NEW
    private var active: PersistenceComplaintMaintenanceFenceBudgetV1? = null
    private var observedLock: Boolean? = null

    @Suppress("TooGenericExceptionCaught")
    fun acquire(connection: Connection, work: PersistenceTimeBudget) {
        try {
            phase.requireComplaintMaintenanceFence(this, connection)
            if (stage !== FenceStage.NEW) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            active = PersistenceComplaintMaintenanceFenceBudgetV1(work) // Before any prefix-specific settings or dispatch.
            stage = FenceStage.INSTALLING
            installLimits(connection)
            stage = FenceStage.SETTINGS_RETURNED
            requireRemaining()
            stage = FenceStage.TRYING
            val lock = if (phase.initialTestActivationPrepare(this, connection) || phase.initialTestAdmissionRelease(this, connection))
                TRY_EXCLUSIVE_LOCK else TRY_SHARED_LOCK
            observedLock = connection.prepareStatement(lock).use { statement ->
                statement.executeQuery().use { result ->
                    if (!result.next()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                    val locked = result.getBoolean(1)
                    if (result.wasNull() || result.next()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                    locked
                }
            }
            stage = FenceStage.OBSERVED
            requireRemaining() // Includes actual boolean and result/statement return, not just time before SELECT.
            phase.requireComplaintMaintenanceFence(this, connection)
            if (observedLock != true) refuse(PersistencePhaseFailureCode.ENTRY_REFUSED)
            stage = FenceStage.READING_GATE
            val gate = PersistenceComplaintMaintenanceGateV1.read(connection)
            requireRemaining() // The separate gate statement and its original descendants have returned/closed.
            phase.requireComplaintMaintenanceGate(this, connection, gate)
            stage = FenceStage.GATE_OBSERVED
            requireRemaining()
            stage = FenceStage.ACCEPTED
        } catch (failure: Throwable) {
            stage = FenceStage.FAILED
            if (failure is PersistenceBoundaryException) refuse(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
            phase.recordFailure(failure)
            throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        } finally {
            active = null // Original work/emergency cleanup remains callable after prefix expiry.
        }
    }

    fun accepted(): Boolean = stage === FenceStage.ACCEPTED

    fun active(): Boolean = active != null

    fun callBudget(normal: PersistenceTimeBudget): PersistenceTimeBudget = try {
        active?.dispatchBudget() ?: normal
    } catch (_: PersistenceBoundaryException) {
        refuse(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
    }

    fun readCeiling(normalMillis: Long): Long = if (active == null) normalMillis else minOf(normalMillis, DISPATCH_MILLIS)

    fun requireRemaining() {
        if (active != null) remainingMillis()
    }

    private fun remainingMillis(): Long = try {
        checkNotNull(active).remainingMillis()
    } catch (_: PersistenceBoundaryException) {
        refuse(PersistencePhaseFailureCode.TIME_BUDGET_EXHAUSTED)
    }

    private fun installLimits(connection: Connection) {
        connection.prepareStatement(LOCAL_LIMITS).use { statement ->
            statement.setString(1, remainingMillis().toString() + "ms")
            statement.setString(2, remainingMillis().toString() + "ms")
            statement.executeQuery().use { result ->
                if (!result.next() || result.next()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
            }
        }
    }

    private fun refuse(code: PersistencePhaseFailureCode): Nothing {
        phase.recordFailure(PersistencePhaseException(code))
        throw phase.failureException(code)
    }

    override fun toString(): String = "PersistenceComplaintMaintenanceFenceV1(redacted)"

    private enum class FenceStage { NEW, INSTALLING, SETTINGS_RETURNED, TRYING, OBSERVED, READING_GATE, GATE_OBSERVED, ACCEPTED, FAILED }

    companion object {
        // Facts shared with the concrete nonpooled epoch owner, not a caller-selected lock/SQL capability.
        internal const val TRY_SHARED_LOCK = "SELECT pg_try_advisory_xact_lock_shared(hashtextextended('complaint-maintenance-v1', 0))"
        internal const val TRY_EXCLUSIVE_LOCK = "SELECT pg_try_advisory_xact_lock(hashtextextended('complaint-maintenance-v1', 0))"
        internal const val LOCAL_LIMITS = "SELECT set_config('statement_timeout', ?, true), set_config('lock_timeout', ?, true)"
        internal const val PREFIX_MILLIS = 100L
        internal const val DISPATCH_MILLIS = 75L
    }
}

/** Bounded SQL facts only. The original named TEST owner must separately authenticate any continuation. */
internal class PersistenceComplaintMaintenanceGateV1 private constructor(
    val maintenanceClosed: Boolean,
    val creationClosed: Boolean,
    val pendingTestToken: UUID?,
    val pendingTestScope: UUID?,
    private val pendingUnsigned: ByteArray?,
    private val pendingHash: ByteArray?,
    private val pendingTestPrepared: Boolean,
    val projectedTestClosed: Boolean,
    private val projectedTestToken: UUID?,
    private val projectedTestScope: UUID?,
    private val projectedUnsigned: ByteArray?,
    private val projectedHash: ByteArray?,
    private val pendingCatalog: Boolean,
) {
    internal fun requireUnownedOpen() {
        if (pendingTestToken != null || projectedTestClosed) throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
    }

    internal fun matchesPrepared(token: UUID, scope: UUID, unsigned: ByteArray, hash: ByteArray): Boolean =
        maintenanceClosed && creationClosed && pendingTestPrepared && !projectedTestClosed && pendingTestToken == token && pendingTestScope == scope &&
            pendingUnsigned.contentEquals(unsigned) && pendingHash.contentEquals(hash)

    /** READ_GATE already binds the sole nonprojected COMPLETED row to accepted head/hash/writer and pending token. */
    internal fun matchesPending(token: UUID, scope: UUID, unsigned: ByteArray, hash: ByteArray): Boolean =
        maintenanceClosed && creationClosed && !pendingTestPrepared && !projectedTestClosed && pendingTestToken == token && pendingTestScope == scope &&
            pendingUnsigned.contentEquals(unsigned) && pendingHash.contentEquals(hash)

    /** Exact closed accepted-head facts only; the original TEST owner still authenticates both raw copies and the complete effect. */
    internal fun matchesProjected(token: UUID, scope: UUID, unsigned: ByteArray, hash: ByteArray): Boolean =
        maintenanceClosed && creationClosed && projectedTestClosed && pendingTestToken == null && !pendingTestPrepared &&
            projectedTestToken == token && projectedTestScope == scope && projectedUnsigned.contentEquals(unsigned) && projectedHash.contentEquals(hash)

    /** Named cold read-only capture only. Not a projected-ticket issuer or mutation/continuation predicate. */
    internal fun matchesClosedProjectedRecoveryScope(scope: UUID): Boolean =
        maintenanceClosed && creationClosed && projectedTestClosed && pendingTestToken == null && !pendingTestPrepared && projectedTestScope == scope

    /** Already-open projected identity comparisons only; never a gate opener or interrupted-cut issuer. */
    internal fun matchesOpenProjectedActiveRegistrationScope(scope: UUID): Boolean =
        !maintenanceClosed && !creationClosed && !projectedTestClosed && !pendingCatalog && pendingTestToken == null && !pendingTestPrepared &&
            projectedTestScope == scope && projectedTestToken != null && projectedUnsigned != null && projectedHash != null

    internal fun matchesOpenProjectedActive(token: UUID, scope: UUID, unsigned: ByteArray, hash: ByteArray): Boolean =
        matchesOpenProjectedActiveRegistrationScope(scope) && projectedTestToken == token &&
            projectedUnsigned.contentEquals(unsigned) && projectedHash.contentEquals(hash)

    override fun toString(): String = "PersistenceComplaintMaintenanceGateV1(bounded-facts,no-continuation-authority)"

    companion object {
        /** No lock acquisition is combined with this SELECT: RC must observe a snapshot taken AFTER M. */
        internal fun read(connection: Connection): PersistenceComplaintMaintenanceGateV1 = connection.prepareStatement(READ_GATE).use { statement ->
            statement.executeQuery().use { rows ->
                if (!rows.next() || !rows.getBoolean("valid") || rows.wasNull()) {
                    throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                }
                val closed = rows.getBoolean("maintenance_closed").also { check(!rows.wasNull()) }
                val creation = rows.getBoolean("creation_closed").also { check(!rows.wasNull()) }
                val token = rows.getObject("test_token", UUID::class.java)
                val scope = rows.getObject("test_scope", UUID::class.java)
                val unsigned = rows.getBytes("test_unsigned")?.copyOf()
                val hash = rows.getBytes("test_hash")?.copyOf()
                val prepared = rows.getBoolean("test_prepared").also { check(!rows.wasNull()) }
                val projected = rows.getBoolean("projected_test_closed").also { check(!rows.wasNull()) }
                val projectedToken = rows.getObject("projected_test_token", UUID::class.java)
                val projectedScope = rows.getObject("projected_test_scope", UUID::class.java)
                val projectedUnsigned = rows.getBytes("projected_test_unsigned")?.copyOf()
                val projectedHash = rows.getBytes("projected_test_hash")?.copyOf()
                val pendingCatalog = rows.getBoolean("catalog_pending").also { check(!rows.wasNull()) }
                if (rows.next() || (token != null && (scope == null || unsigned == null || hash == null)) ||
                    (projected && (projectedToken == null || projectedScope == null || projectedUnsigned == null || projectedHash == null))) {
                    throw PersistencePhaseException(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                }
                PersistenceComplaintMaintenanceGateV1(
                    closed, creation, token, scope, unsigned, hash, prepared, projected,
                    projectedToken, projectedScope, projectedUnsigned, projectedHash, pendingCatalog,
                )
            }
        }

        // PK global lookup, partial-unique pending predicate (LIMIT2 detects drift), unique accepted-generation lookup.
        internal val READ_GATE = """
            WITH pending AS MATERIALIZED (
                SELECT operation_token, operation_type, data_scope_id, test_only, predecessor_generation, predecessor_hash, successor_generation,
                    catalog_writer_generation, state, unsigned_bytes, unsigned_hash
                FROM complaint_catalog_mutations
                WHERE state = 'PREPARED' OR (state = 'COMPLETED' AND projected_at IS NULL)
                LIMIT 2
            )
            SELECT (NOT c.test_only AND c.publication_epoch > 0 AND c.desired_generation > 0 AND c.implementation_schema = 1
                AND (SELECT count(*) FROM pending) <= 1
                AND ((c.accepted_catalog_generation IS NULL AND c.accepted_catalog_hash IS NULL
                        AND c.trust_bundle_hash IS NULL AND c.catalog_writer_generation IS NULL AND h.operation_token IS NULL)
                    OR (c.accepted_catalog_generation BETWEEN 1 AND 65536 AND complaint_digest_valid(c.accepted_catalog_hash)
                        AND complaint_digest_valid(c.trust_bundle_hash) AND complaint_is_v4(c.catalog_writer_generation)
                        AND h.state = 'COMPLETED' AND h.envelope_hash = c.accepted_catalog_hash AND h.catalog_writer_generation = c.catalog_writer_generation))
                AND ((p.operation_token IS NULL AND c.pending_projection_token IS NULL AND (h.operation_token IS NULL OR h.projected_at IS NOT NULL))
                    OR (p.state = 'PREPARED' AND c.pending_projection_token IS NULL
                        AND (h.operation_token IS NULL OR h.projected_at IS NOT NULL)
                        AND p.predecessor_generation = coalesce(c.accepted_catalog_generation, 0)
                        AND p.predecessor_hash = coalesce(c.accepted_catalog_hash, decode(repeat('00', 32), 'hex'))
                        AND (h.operation_token IS NULL OR p.catalog_writer_generation = c.catalog_writer_generation))
                    OR (p.state = 'COMPLETED' AND c.pending_projection_token = p.operation_token
                        AND c.accepted_catalog_generation = p.successor_generation AND h.operation_token = p.operation_token))
                AND (h.operation_type IS DISTINCT FROM 'TEST_RUN_ACTIVATION'
                    OR (h.test_only AND complaint_scope_valid(h.data_scope_id, h.test_only)
                        AND octet_length(h.unsigned_bytes) BETWEEN 1 AND 131072 AND octet_length(h.unsigned_hash) = 32))
                AND (p.operation_type IS DISTINCT FROM 'TEST_RUN_ACTIVATION'
                    OR (p.test_only AND complaint_scope_valid(p.data_scope_id, p.test_only)
                        AND octet_length(p.unsigned_bytes) BETWEEN 1 AND 131072 AND octet_length(p.unsigned_hash) = 32))) IS TRUE AS valid,
                c.maintenance_closed, c.creation_closed, p.operation_token IS NOT NULL AS catalog_pending,
                CASE WHEN p.operation_type = 'TEST_RUN_ACTIVATION' THEN p.operation_token END AS test_token,
                CASE WHEN p.operation_type = 'TEST_RUN_ACTIVATION' THEN p.data_scope_id END AS test_scope,
                CASE WHEN p.operation_type = 'TEST_RUN_ACTIVATION' AND octet_length(p.unsigned_bytes) BETWEEN 1 AND 131072
                    THEN p.unsigned_bytes END AS test_unsigned,
                CASE WHEN p.operation_type = 'TEST_RUN_ACTIVATION' AND octet_length(p.unsigned_hash) = 32 THEN p.unsigned_hash END AS test_hash,
                (p.operation_type = 'TEST_RUN_ACTIVATION' AND p.state = 'PREPARED') IS TRUE AS test_prepared,
                (h.operation_type = 'TEST_RUN_ACTIVATION' AND h.projected_at IS NOT NULL AND c.maintenance_closed) IS TRUE AS projected_test_closed,
                CASE WHEN h.operation_type = 'TEST_RUN_ACTIVATION' AND h.projected_at IS NOT NULL THEN h.operation_token END AS projected_test_token,
                CASE WHEN h.operation_type = 'TEST_RUN_ACTIVATION' AND h.projected_at IS NOT NULL THEN h.data_scope_id END AS projected_test_scope,
                CASE WHEN h.operation_type = 'TEST_RUN_ACTIVATION' AND h.projected_at IS NOT NULL AND octet_length(h.unsigned_bytes) BETWEEN 1 AND 131072
                    THEN h.unsigned_bytes END AS projected_test_unsigned,
                CASE WHEN h.operation_type = 'TEST_RUN_ACTIVATION' AND h.projected_at IS NOT NULL AND octet_length(h.unsigned_hash) = 32
                    THEN h.unsigned_hash END AS projected_test_hash
            FROM complaint_journal_control c
            LEFT JOIN pending p ON true
            LEFT JOIN complaint_catalog_mutations h ON h.successor_generation = c.accepted_catalog_generation
            WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
        """.trimIndent()
    }
}

/** Every dispatch retains the one prefix deadline AND its original already-started work budget. */
internal class PersistenceComplaintMaintenanceFenceBudgetV1(work: PersistenceTimeBudget) {
    private val prefix = work.capped(PersistenceComplaintMaintenanceFenceV1.PREFIX_MILLIS)

    fun remainingMillis(): Long = prefix.remainingMillis(PersistenceComplaintMaintenanceFenceV1.DISPATCH_MILLIS)

    fun dispatchBudget(): PersistenceTimeBudget = prefix.capped(PersistenceComplaintMaintenanceFenceV1.DISPATCH_MILLIS)
}
