package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/**
 * Dormant locked observations, NOT deletion authority. The same phase retains these private rows;
 * no snapshot, expected writer/catalog value, success token or business continuation is returned.
 * A syntactic scope selects rows only. W04 must still authenticate membership and match authority.
 */
internal class PersistenceDeletionControlSnapshot(private val phase: PersistencePhaseContext, private val scope: ComplaintDataScope) {
    private var stage = ControlStage.NEW
    private var global: ControlRow? = null
    private var exact: ControlRow? = null

    fun capture(connection: Connection) {
        runCatching {
            phase.requireDeletionControlSnapshot(this, connection)
            if (stage !== ControlStage.NEW) refuse(PersistencePhaseFailureCode.WORK_FAILED)
            stage = ControlStage.GLOBAL_LOCKING
            global = read(connection, ComplaintDataScope.LIVE)
            stage = ControlStage.GLOBAL_RETURNED
            phase.requireDeletionControlSnapshot(this, connection)
            requireAvailable(checkNotNull(global), ComplaintDataScope.LIVE)
            if (scope.testOnly) {
                stage = ControlStage.EXACT_LOCKING
                exact = read(connection, scope)
                stage = ControlStage.EXACT_RETURNED
                phase.requireDeletionControlSnapshot(this, connection)
                requireAvailable(checkNotNull(exact), scope)
            }
            phase.requireDeletionControlSnapshot(this, connection)
            stage = ControlStage.SNAPSHOT_RETAINED
        }.getOrElse { failure ->
            stage = ControlStage.FAILED
            phase.recordFailure(failure)
            throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        }
    }

    /** Only the snapshot-only phase's completion predicate; no mutation port accepts this helper. */
    fun captured(): Boolean = stage === ControlStage.SNAPSHOT_RETAINED

    fun active(): Boolean = stage !== ControlStage.NEW && stage !== ControlStage.SNAPSHOT_RETAINED && stage !== ControlStage.FAILED

    private fun read(connection: Connection, selectedScope: ComplaintDataScope): ControlRow =
        connection.prepareStatement(if (selectedScope.testOnly) EXACT_CONTROL else GLOBAL_CONTROL).use { statement ->
            if (selectedScope.testOnly) statement.setObject(1, selectedScope.id)
            statement.executeQuery().use { result ->
                if (!result.next()) refuse(PersistencePhaseFailureCode.ENTRY_REFUSED)
                val row = decode(result)
                if (result.next()) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
                row
            }
        }

    private fun requireAvailable(row: ControlRow, selectedScope: ComplaintDataScope) {
        if (row.scope != selectedScope) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        if (row.maintenanceClosed || row.scanRequested) refuse(PersistencePhaseFailureCode.ENTRY_REFUSED)
        // creationClosed and the actual epoch/catalog/writer fields remain observations, never authority.
    }

    private fun decode(result: ResultSet): ControlRow {
        val observedScope = ComplaintDataScope.of(checkNotNull(result.getObject("data_scope_id", UUID::class.java)))
        if (boolean(result, "test_only") != observedScope.testOnly) refuse(PersistencePhaseFailureCode.RESOURCE_REFUSED)
        return ControlRow(
            observedScope,
            boolean(result, "maintenance_closed"),
            boolean(result, "creation_closed"),
            boolean(result, "scan_requested"),
            result.getLong("publication_epoch").also { check(!result.wasNull()) },
            result.getObject("event_writer_generation", UUID::class.java),
            result.getLong("accepted_catalog_generation").let { if (result.wasNull()) null else it },
            result.getBytes("accepted_catalog_hash"),
            result.getObject("catalog_writer_generation", UUID::class.java),
            result.getObject("pending_projection_token", UUID::class.java),
        )
    }

    private fun boolean(result: ResultSet, column: String): Boolean = result.getBoolean(column).also { check(!result.wasNull()) }

    private fun refuse(code: PersistencePhaseFailureCode): Nothing {
        phase.recordFailure(PersistencePhaseException(code))
        throw phase.failureException(code)
    }

    override fun toString(): String = "PersistenceDeletionControlSnapshot(redacted)"

    private enum class ControlStage { NEW, GLOBAL_LOCKING, GLOBAL_RETURNED, EXACT_LOCKING, EXACT_RETURNED, SNAPSHOT_RETAINED, FAILED }

    /** Materialized database facts only; deliberately private and without a generated data-bearing diagnostic. */
    private class ControlRow(
        val scope: ComplaintDataScope,
        val maintenanceClosed: Boolean,
        val creationClosed: Boolean,
        val scanRequested: Boolean,
        val publicationEpoch: Long,
        val eventWriterGeneration: UUID?,
        val acceptedCatalogGeneration: Long?,
        val acceptedCatalogHash: ByteArray?,
        val catalogWriterGeneration: UUID?,
        val pendingProjectionToken: UUID?,
    ) {
        override fun toString(): String = "DeletionControlRow(redacted)"
    }

    companion object {
        private const val CONTROL_COLUMNS = "SELECT data_scope_id, test_only, maintenance_closed, creation_closed, scan_requested, " +
            "publication_epoch, event_writer_generation, accepted_catalog_generation, accepted_catalog_hash, " +
            "catalog_writer_generation, pending_projection_token FROM complaint_journal_control "
        private const val GLOBAL_CONTROL = CONTROL_COLUMNS + "WHERE data_scope_id = '00000000-0000-0000-0000-000000000000' FOR UPDATE"
        private const val EXACT_CONTROL = CONTROL_COLUMNS +
            "WHERE data_scope_id = ? AND data_scope_id <> '00000000-0000-0000-0000-000000000000' FOR UPDATE"
    }
}
