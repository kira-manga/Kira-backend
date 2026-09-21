package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunObservation
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunState
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.requireRegistration
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.util.UUID

/** Constructor-captured independent D, never P or an observed row. Necessary comparison only, not activation/routing authority. */
internal class ComplaintInstallationTestBinding private constructor(
    desired: ComplaintInstallationDesiredSettings.Configured,
    internal val registration: ComplaintTestNamespaceRegistrationV1?,
) {
    constructor(desired: ComplaintInstallationDesiredSettings.Configured) : this(desired, null)
    constructor(registration: ComplaintTestNamespaceRegistrationV1) : this(registration.process.desiredSettings(), registration)
    init {
        require(desired.mode === ComplaintInstallationMode.PRE_CUTOVER_TEST && desired.scope.testOnly)
    }

    val scope = desired.scope
    private val configurationHash = desired.configurationHashBytes()

    fun requireConfiguration(observed: ByteArray) = check(configurationHash.contentEquals(observed))

    /** Called only inside the retained enrollment/session holder. No control lock is taken after counters/run. */
    internal fun requireCurrent(jdbc: JdbcTemplate) {
        val retained = registration ?: return // Existing explicit lower comparison-only cores stay non-issuers.
        retained.requireReleasedIdentityAdmission()
        requireRegistration(jdbc.query(CURRENT_IDENTITY, { row, _ ->
            row.getBoolean("identity_current").also { requireRegistration(!row.wasNull()) }
        }, *retained.identityAdmissionArguments()).single())
        retained.requireReleasedIdentityAdmission()
    }

    /** An unobserved run is not an absent run. Terminal requested scopes take precedence over the current configured scope. */
    fun compare(requested: ComplaintDataScope, observed: ComplaintInstallationRunObservation?): ComplaintInstallationTestComparison {
        if (!requested.testOnly) return ComplaintInstallationTestComparison.SCOPE_MISMATCH
        check(observed != null && observed.scope == requested)
        if (observed is ComplaintInstallationRunObservation.Absent) return ComplaintInstallationTestComparison.SCOPE_RETIRED
        check(observed is ComplaintInstallationRunObservation.Present)
        if (observed.state !== ComplaintInstallationRunState.ACTIVE) return ComplaintInstallationTestComparison.SCOPE_RETIRED
        if (requested != scope) return ComplaintInstallationTestComparison.SCOPE_MISMATCH
        requireConfiguration(observed.configurationHashBytes())
        return ComplaintInstallationTestComparison.MATCHING_COMPARISON
    }

    override fun toString(): String = "ComplaintInstallationTestBinding(comparison-only,redacted)"

    private companion object {
        // Identity-only: a valid current tuple is not SUCCESS checkpoint/seal/queue health. In
        // particular scan_requested may remain true. Bootstrap's read-only exception is separate.
        val CURRENT_IDENTITY = """
            WITH expected_run AS MATERIALIZED (
                SELECT ?::uuid AS scope, ?::bytea AS configuration_hash, ?::bigint AS installation_limit,
                    ?::bigint AS generation, ?::bytea AS activation_hash, ?::timestamptz AS created_at, ?::bigint[] AS original_reserve
            ), expected_control AS MATERIALIZED (
                SELECT ?::uuid AS scope, ?::bigint AS desired_generation, ?::integer AS implementation_schema,
                    ?::bytea AS configuration_hash, ?::uuid AS database_identity, ?::uuid AS restore_identity,
                    ?::uuid AS event_writer, ?::uuid AS catalog_writer, ?::bytea AS trust_hash, ?::bigint AS generation, ?::bytea AS activation_hash
            ), expected_global AS MATERIALIZED (SELECT ?::bigint AS desired_generation, ?::bytea AS configuration_hash)
            SELECT (
                e.scope = d.scope AND r.test_only AND r.state = 'ACTIVE' AND (${ComplaintInstallationTestRunRows.activeShape})
                AND r.accounting_version = 1 AND r.configuration_hash = e.configuration_hash AND r.installation_limit = e.installation_limit
                AND r.installation_limit > 0 AND r.enrolled_count BETWEEN 0 AND r.installation_limit
                AND r.activation_catalog_generation = e.generation AND r.activation_catalog_hash = e.activation_hash
                AND r.created_at = e.created_at AND isfinite(r.created_at) AND r.original_reserve = e.original_reserve
                AND complaint_vector_valid(r.original_reserve) AND complaint_vector_lte(r.unused_reserve, r.original_reserve)
                AND c.test_only AND c.implementation_schema = d.implementation_schema AND c.desired_generation = d.desired_generation
                AND c.desired_configuration_hash = d.configuration_hash AND c.database_identity = d.database_identity
                AND c.restore_identity = d.restore_identity AND c.event_writer_generation = d.event_writer
                AND c.catalog_writer_generation = d.catalog_writer AND c.trust_bundle_hash = d.trust_hash
                AND c.accepted_catalog_generation = d.generation AND c.accepted_catalog_hash = d.activation_hash
                AND NOT c.maintenance_closed AND NOT c.creation_closed AND c.pending_projection_token IS NULL
                AND c.publication_epoch > 0 AND isfinite(c.updated_at)
                AND NOT g.test_only AND g.implementation_schema = 1 AND g.desired_generation = b.desired_generation
                AND g.desired_configuration_hash IS NOT DISTINCT FROM b.configuration_hash
                AND g.database_identity = d.database_identity AND g.restore_identity = d.restore_identity
                AND g.event_writer_generation = d.event_writer AND g.catalog_writer_generation = d.catalog_writer
                AND g.trust_bundle_hash = d.trust_hash AND g.accepted_catalog_generation = d.generation AND g.accepted_catalog_hash = d.activation_hash
                AND NOT g.maintenance_closed AND NOT g.creation_closed AND g.pending_projection_token IS NULL
                AND g.publication_epoch > 0 AND isfinite(g.updated_at)
            ) IS TRUE AS identity_current
            FROM expected_run e CROSS JOIN expected_control d CROSS JOIN expected_global b
            LEFT JOIN complaint_test_runs r ON r.data_scope_id = e.scope
            LEFT JOIN complaint_journal_control c ON c.data_scope_id = d.scope
            LEFT JOIN complaint_journal_control g ON g.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
            LIMIT 2
        """.trimIndent()
    }
}

internal enum class ComplaintInstallationTestComparison { MATCHING_COMPARISON, SCOPE_MISMATCH, SCOPE_RETIRED }

/** Bounded selected run fields. Callers retain their concrete operation before SQL and own the actual transaction/lock order. */
internal object ComplaintInstallationTestRunRows {
    val activeShape = """
        r.sealed_at IS NULL AND r.purging_at IS NULL AND r.purged_at IS NULL
            AND r.final_ordinary_epoch IS NULL AND r.terminal_seal_epoch IS NULL AND r.generation_seal_count IS NULL
            AND r.generation_seal_root IS NULL AND r.seal_set_bytes IS NULL AND r.seal_set_hash IS NULL
            AND r.event_manifest_count IS NULL AND r.event_manifest_root IS NULL
            AND r.installation_manifest_count IS NULL AND r.installation_manifest_root IS NULL
            AND r.installation_chunk_count IS NULL AND r.retired_count IS NULL AND r.deleted_count IS NULL
            AND r.permanent_denial_bytes IS NULL AND r.permanent_denial_hash IS NULL
            AND r.terminal_event_id IS NULL AND r.terminal_object_key IS NULL AND r.terminal_object_version IS NULL
            AND r.terminal_ciphertext_hash IS NULL AND r.terminal_catalog_generation IS NULL AND r.terminal_catalog_hash IS NULL
            AND r.recurrent_erasure_history_hash IS NULL
    """.trimIndent()
    val columns = """
        r.data_scope_id AS run_scope, r.test_only AS run_test_only,
        CASE WHEN octet_length(r.state) <= 7 THEN r.state END AS run_state,
        CASE WHEN octet_length(r.configuration_hash) = 32 THEN r.configuration_hash END AS run_configuration_hash,
        isfinite(r.created_at) AND (r.sealed_at IS NULL OR isfinite(r.sealed_at))
            AND (r.purging_at IS NULL OR isfinite(r.purging_at)) AND (r.purged_at IS NULL OR isfinite(r.purged_at)) AS run_finite_times,
        $activeShape AS run_active_shape
    """.trimIndent()

    val lock = "SELECT $columns FROM complaint_test_runs r WHERE r.data_scope_id = ? FOR UPDATE"

    fun read(row: ResultSet, requested: ComplaintDataScope): ComplaintInstallationRunObservation? {
        if (!requested.testOnly) return null
        val id = row.getObject("run_scope", UUID::class.java) ?: return ComplaintInstallationRunObservation.Absent(requested)
        check(id == requested.id && requiredBoolean(row, "run_test_only") && requiredBoolean(row, "run_finite_times"))
        val state = ComplaintInstallationRunState.valueOf(checkNotNull(row.getString("run_state")))
        if (state === ComplaintInstallationRunState.ACTIVE) check(requiredBoolean(row, "run_active_shape"))
        return ComplaintInstallationRunObservation.Present(requested, true, state, checkNotNull(row.getBytes("run_configuration_hash")))
    }

    private fun requiredBoolean(row: ResultSet, column: String): Boolean = row.getBoolean(column).also { check(!row.wasNull()) }
}
