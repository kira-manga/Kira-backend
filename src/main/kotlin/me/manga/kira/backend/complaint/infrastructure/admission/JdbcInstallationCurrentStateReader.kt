package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationBootstrap
import me.manga.kira.backend.complaint.domain.ComplaintInstallationControlObservation
import me.manga.kira.backend.complaint.domain.ComplaintInstallationCurrentStateAssessment
import me.manga.kira.backend.complaint.domain.ComplaintInstallationCurrentStatePolicy
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunObservation
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunState
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** Fixed bounded reads, with no locks, updates or callbacks. Diagnostic equality alone remains incapable of bootstrap. */
internal class JdbcInstallationCurrentStateReader(private val jdbc: JdbcTemplate) {
    fun read(desired: ComplaintInstallationDesiredSettings.Configured, requestedScope: ComplaintDataScope): InstallationCurrentStateReadOperation =
        InstallationCurrentStateReadOperation.capture(jdbc, desired, requestedScope)

    fun requireBootstrapResources(ownership: PersistencePhaseOwnership, registration: ComplaintTestNamespaceRegistrationV1) =
        registration.requireInstallationResources(ownership, jdbc)

    fun readBootstrap(ownership: PersistencePhaseOwnership, registration: ComplaintTestNamespaceRegistrationV1): InstallationCurrentStateReadOperation =
        InstallationCurrentStateReadOperation.captureBootstrap(jdbc, ownership, registration)

    override fun toString(): String = "JdbcInstallationCurrentStateReader(read-only,registration-required-for-bootstrap)"
}

/** The shared enum is never completion evidence: only this concrete retained operation can release its private result. */
internal class InstallationCurrentStateReadOperation private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    private val desired: ComplaintInstallationDesiredSettings.Configured,
    private val requestedScope: ComplaintDataScope,
    private val bootstrapOwner: PersistencePhaseOwnership? = null,
    private val registration: ComplaintTestNamespaceRegistrationV1? = null,
) {
    private var stage = Stage.RETAINED
    private var captured: ComplaintInstallationCurrentStateAssessment? = null
    private var bootstrapCurrent = false
    private var bootstrapReleased = false

    internal fun belongsTo(selected: PersistencePhaseContext): Boolean = phase === selected

    internal fun completedFor(selected: PersistencePhaseContext): Boolean = phase === selected && stage === Stage.COMPLETE

    val assessment: ComplaintInstallationCurrentStateAssessment
        get() {
            phase.installationCurrentState.requireCommitted(this)
            requireConnectionFree()
            return checkNotNull(captured)
        }

    /** Only this actual registered read, after known commit AND physical release, can return its selected TEST scope. */
    fun bootstrap(selected: ComplaintTestNamespaceRegistrationV1): ComplaintInstallationBootstrap {
        phase.installationCurrentState.requireCommitted(this)
        requireConnectionFree()
        check(selected === registration && bootstrapCurrent && !bootstrapReleased)
        bootstrapReleased = true // No retained successful read can become a reusable cached scope authority.
        selected.requireInstallationResources(checkNotNull(bootstrapOwner), jdbc)
        check(desired.scope.testOnly && desired.scope == selected.process.desiredSettings().scope)
        return ComplaintInstallationBootstrap(desired.scope)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun read() {
        try {
            requireAt(Stage.RETAINED)
            stage = Stage.READING
            val connection = phase.installationCurrentState.connection(this, jdbc)
            if (registration != null) {
                readBootstrap(connection, registration)
                requireAt(Stage.READING)
                registration.requireInstallationPhaseResources(checkNotNull(bootstrapOwner), jdbc)
                bootstrapCurrent = true
                stage = Stage.COMPLETE
                return
            }
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

    private fun readBootstrap(connection: Connection, selected: ComplaintTestNamespaceRegistrationV1) {
        val owner = checkNotNull(bootstrapOwner)
        phase.installationCurrentState.requireOwner(owner)
        selected.requireInstallationPhaseResources(owner, jdbc)
        val expected = selected.bootstrapExpectedArguments()
        connection.prepareStatement(BOOTSTRAP_STATE_SQL).use { statement ->
            expected.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { row ->
                check(row.next() && requiredBoolean(row, "bootstrap_current") && !row.next())
            }
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

    override fun toString(): String = "InstallationCurrentStateReadOperation(sealed-read,registered-bootstrap-only)"

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

        @Suppress("TooGenericExceptionCaught")
        internal fun captureBootstrap(
            jdbc: JdbcTemplate,
            ownership: PersistencePhaseOwnership,
            registration: ComplaintTestNamespaceRegistrationV1,
        ): InstallationCurrentStateReadOperation {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.installationCurrentState.requireOwner(ownership)
                registration.requireInstallationPhaseResources(ownership, jdbc)
                phase.installationCurrentState.requireOperation(jdbc)
                val desired = registration.process.desiredSettings()
                check(desired.scope.testOnly)
                val operation = InstallationCurrentStateReadOperation(phase, jdbc, desired, desired.scope, ownership, registration)
                phase.installationCurrentState.retain(operation, jdbc)
                operation.read()
                return operation
            } catch (problem: Throwable) {
                phase.recordFailure(problem)
                throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
            }
        }

        // Three exact PK rows in one snapshot. The global current catalog/restore identities must still
        // agree with this registration too; old TEST control rows alone cannot establish current routing.
        // These SQL comparisons are not provenance. The privately issued registration and this exact
        // phase's retained operation/commit/cleanup seal remain mandatory. Maintenance flags are not gates.
        private val BOOTSTRAP_STATE_SQL = """
            WITH expected_run AS MATERIALIZED (
                SELECT ?::uuid AS scope, ?::bytea AS configuration_hash, ?::bigint AS installation_limit,
                    ?::bigint AS generation, ?::bytea AS activation_hash, ?::timestamptz AS created_at, ?::bigint[] AS original_reserve
            ), expected_control AS MATERIALIZED (
                SELECT ?::uuid AS scope, ?::bigint AS desired_generation, ?::integer AS implementation_schema,
                    ?::bytea AS configuration_hash, ?::uuid AS database_identity, ?::uuid AS restore_identity,
                    ?::uuid AS event_writer, ?::uuid AS catalog_writer, ?::bytea AS trust_hash, ?::bigint AS generation, ?::bytea AS activation_hash
            )
            SELECT (
                e.scope = d.scope AND r.test_only AND r.state = 'ACTIVE' AND r.sealed_at IS NULL
                AND r.purging_at IS NULL AND r.purged_at IS NULL AND r.accounting_version = 1
                AND r.configuration_hash = e.configuration_hash AND r.installation_limit = e.installation_limit
                AND r.installation_limit > 0 AND r.enrolled_count BETWEEN 0 AND r.installation_limit
                AND r.activation_catalog_generation = e.generation AND r.activation_catalog_hash = e.activation_hash
                AND r.created_at = e.created_at AND isfinite(r.created_at) AND r.original_reserve = e.original_reserve
                AND complaint_vector_valid(r.original_reserve) AND complaint_vector_lte(r.unused_reserve, r.original_reserve)
                AND c.test_only AND c.implementation_schema = d.implementation_schema AND c.desired_generation = d.desired_generation
                AND c.desired_configuration_hash = d.configuration_hash AND c.database_identity = d.database_identity
                AND c.restore_identity = d.restore_identity AND c.event_writer_generation = d.event_writer
                AND c.catalog_writer_generation = d.catalog_writer AND c.trust_bundle_hash = d.trust_hash
                AND c.accepted_catalog_generation = d.generation AND c.accepted_catalog_hash = d.activation_hash
                AND c.pending_projection_token IS NULL AND c.publication_epoch > 0 AND isfinite(c.updated_at)
                AND NOT g.test_only AND g.implementation_schema = 1 AND g.desired_generation > 0
                AND g.database_identity = d.database_identity AND g.restore_identity = d.restore_identity
                AND g.event_writer_generation = d.event_writer AND g.catalog_writer_generation = d.catalog_writer
                AND g.trust_bundle_hash = d.trust_hash AND g.accepted_catalog_generation = d.generation
                AND g.accepted_catalog_hash = d.activation_hash AND g.pending_projection_token IS NULL
                AND g.publication_epoch > 0 AND isfinite(g.updated_at)
            ) IS TRUE AS bootstrap_current
            FROM expected_run e CROSS JOIN expected_control d
            LEFT JOIN complaint_test_runs r ON r.data_scope_id = e.scope
            LEFT JOIN complaint_journal_control c ON c.data_scope_id = d.scope
            LEFT JOIN complaint_journal_control g ON g.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
            LIMIT 2
        """.trimIndent()

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
