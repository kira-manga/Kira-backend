package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationControlObservation
import me.manga.kira.backend.complaint.domain.ComplaintInstallationCurrentStateAssessment
import me.manga.kira.backend.complaint.domain.ComplaintInstallationCurrentStatePolicy
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunObservation
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunState
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.util.UUID

/** One fixed bounded V14 read. No locks, updates, callbacks, installation lookup or authority producer. */
internal class JdbcInstallationCurrentStateReader(private val jdbc: JdbcTemplate) {
    fun read(desired: ComplaintInstallationDesiredSettings.Configured, requestedScope: ComplaintDataScope): InstallationCurrentStateReadOperation =
        InstallationCurrentStateReadOperation.capture(jdbc, desired, requestedScope)

    override fun toString(): String = "JdbcInstallationCurrentStateReader(read-only,no-authority)"
}

/** The shared enum is never completion evidence: only this concrete retained operation can release its private result. */
internal class InstallationCurrentStateReadOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val desired: ComplaintInstallationDesiredSettings.Configured,
    private val requestedScope: ComplaintDataScope,
) {
    private var stage = Stage.RETAINED
    private var captured: ComplaintInstallationCurrentStateAssessment? = null

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected

    internal fun completedFor(selected: PersistencePhaseContext): Boolean = phase === selected && stage === Stage.COMPLETE

    val assessment: ComplaintInstallationCurrentStateAssessment
        get() {
            phase.installationCurrentState.requireCommitted(this)
            requireConnectionFree()
            return checkNotNull(captured)
        }

    @Suppress("TooGenericExceptionCaught")
    private fun read() {
        try {
            requireAt(Stage.RETAINED)
            stage = Stage.READING
            val connection = phase.installationCurrentState.connection(this, jdbc)
            val observed = connection.prepareStatement(CURRENT_STATE_SQL).use { statement ->
                statement.setObject(1, desired.scope.id)
                statement.setObject(2, requestedScope.id)
                statement.executeQuery().use { row ->
                    check(row.next())
                    val control = control(row)
                    val run = requestedRun(row)
                    check(!row.next()) // SQL caps at two; missing/duplicate joined rows never select an arbitrary winner.
                    ComplaintInstallationCurrentStatePolicy.assess(desired, requestedScope, control, run)
                }
            }
            requireAt(Stage.READING) // Both statement and result set have actually closed.
            captured = observed
            stage = Stage.COMPLETE
        } catch (problem: Throwable) {
            stage = Stage.FAILED
            phase.recordFailure(problem)
            PersistencePhaseOwnership.current()?.takeIf { it !== phase }?.recordFailure(problem)
            throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        }
    }

    private fun control(row: ResultSet): ComplaintInstallationControlObservation? {
        val id = row.getObject("control_scope", UUID::class.java) ?: return null
        check(id == desired.scope.id && requiredBoolean(row, "control_bounded"))
        return ComplaintInstallationControlObservation(
            ComplaintDataScope.of(id),
            requiredBoolean(row, "control_test_only"),
            row.getInt("implementation_schema").also { check(!row.wasNull()) },
            row.getLong("desired_generation").also { check(!row.wasNull()) },
            row.getBytes("configuration_hash"),
            row.getObject("database_identity", UUID::class.java),
            row.getObject("restore_identity", UUID::class.java),
            requiredBoolean(row, "maintenance_closed"),
            requiredBoolean(row, "creation_closed"),
            requiredBoolean(row, "scan_requested"),
            journalDegraded = null, // UNKNOWN: no database flag or configured intent proves ordinary journal health.
        )
    }

    private fun requestedRun(row: ResultSet): ComplaintInstallationRunObservation? {
        if (!requestedScope.testOnly) return null
        val id = row.getObject("run_scope", UUID::class.java) ?: return ComplaintInstallationRunObservation.Absent(requestedScope)
        check(id == requestedScope.id && requiredBoolean(row, "run_bounded"))
        return ComplaintInstallationRunObservation.Present(
            ComplaintDataScope.of(id),
            requiredBoolean(row, "run_test_only"),
            ComplaintInstallationRunState.valueOf(checkNotNull(row.getString("run_state"))),
            checkNotNull(row.getBytes("run_configuration_hash")),
        )
    }

    private fun requireAt(expected: Stage) {
        phase.installationCurrentState.requireRetained(this, jdbc)
        check(stage === expected)
    }

    private fun requiredBoolean(row: ResultSet, column: String): Boolean = row.getBoolean(column).also { check(!row.wasNull()) }

    override fun toString(): String = "InstallationCurrentStateReadOperation(sealed-diagnostic,no-authority)"

    private enum class Stage { RETAINED, READING, COMPLETE, FAILED }

    companion object {
        @Suppress("TooGenericExceptionCaught")
        internal fun capture(
            jdbc: JdbcTemplate,
            desired: ComplaintInstallationDesiredSettings.Configured,
            requestedScope: ComplaintDataScope,
        ): InstallationCurrentStateReadOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.installationCurrentState.requireOperation(jdbc)
                val operation = InstallationCurrentStateReadOperation(phase, jdbc, desired, requestedScope)
                phase.installationCurrentState.retain(operation, jdbc)
                operation.read()
                return operation
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        // One statement observes both exact rows in one PostgreSQL snapshot. No row/advisory locks or capability/freshness claim.
        private val CURRENT_STATE_SQL = """
            WITH selected AS (SELECT ?::uuid AS control_scope, ?::uuid AS requested_scope)
            SELECT c.data_scope_id AS control_scope, c.test_only AS control_test_only,
                c.implementation_schema, c.desired_generation,
                CASE WHEN octet_length(c.desired_configuration_hash) = 32 THEN c.desired_configuration_hash END AS configuration_hash,
                (c.desired_configuration_hash IS NULL OR octet_length(c.desired_configuration_hash) = 32) AS control_bounded,
                c.database_identity, c.restore_identity, c.maintenance_closed, c.creation_closed, c.scan_requested,
                r.data_scope_id AS run_scope, r.test_only AS run_test_only,
                CASE WHEN octet_length(r.state) <= 7 THEN r.state END AS run_state,
                CASE WHEN octet_length(r.configuration_hash) = 32 THEN r.configuration_hash END AS run_configuration_hash,
                (octet_length(r.state) <= 7 AND octet_length(r.configuration_hash) = 32) AS run_bounded
            FROM selected s
            LEFT JOIN complaint_journal_control c ON c.data_scope_id = s.control_scope
            LEFT JOIN complaint_test_runs r ON r.data_scope_id = s.requested_scope
                AND s.requested_scope <> '00000000-0000-0000-0000-000000000000'::uuid
            LIMIT 2
        """.trimIndent()
    }
}
