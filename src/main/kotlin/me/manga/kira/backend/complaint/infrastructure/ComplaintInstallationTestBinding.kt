package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunObservation
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRunState
import java.sql.ResultSet
import java.util.UUID

/** Constructor-captured independent D, never P or an observed row. Necessary comparison only, not activation/routing authority. */
internal class ComplaintInstallationTestBinding(desired: ComplaintInstallationDesiredSettings.Configured) {
    init {
        require(desired.mode === ComplaintInstallationMode.PRE_CUTOVER_TEST && desired.scope.testOnly)
    }

    val scope = desired.scope
    private val configurationHash = desired.configurationHashBytes()

    fun requireConfiguration(observed: ByteArray) = check(configurationHash.contentEquals(observed))

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
}

internal enum class ComplaintInstallationTestComparison { MATCHING_COMPARISON, SCOPE_MISMATCH, SCOPE_RETIRED }

/** Bounded selected run fields. Callers retain their concrete operation before SQL and own the actual transaction/lock order. */
internal object ComplaintInstallationTestRunRows {
    val columns = """
        r.data_scope_id AS run_scope, r.test_only AS run_test_only,
        CASE WHEN octet_length(r.state) <= 7 THEN r.state END AS run_state,
        CASE WHEN octet_length(r.configuration_hash) = 32 THEN r.configuration_hash END AS run_configuration_hash,
        isfinite(r.created_at) AND (r.sealed_at IS NULL OR isfinite(r.sealed_at))
            AND (r.purging_at IS NULL OR isfinite(r.purging_at)) AND (r.purged_at IS NULL OR isfinite(r.purged_at)) AS run_finite_times,
        r.sealed_at IS NULL AND r.purging_at IS NULL AND r.purged_at IS NULL
            AND r.final_ordinary_epoch IS NULL AND r.terminal_seal_epoch IS NULL AND r.generation_seal_count IS NULL
            AND r.generation_seal_root IS NULL AND r.seal_set_bytes IS NULL AND r.seal_set_hash IS NULL
            AND r.event_manifest_count IS NULL AND r.event_manifest_root IS NULL
            AND r.installation_manifest_count IS NULL AND r.installation_manifest_root IS NULL
            AND r.installation_chunk_count IS NULL AND r.retired_count IS NULL AND r.deleted_count IS NULL
            AND r.permanent_denial_bytes IS NULL AND r.permanent_denial_hash IS NULL
            AND r.terminal_event_id IS NULL AND r.terminal_object_key IS NULL AND r.terminal_object_version IS NULL
            AND r.terminal_ciphertext_hash IS NULL AND r.terminal_catalog_generation IS NULL AND r.terminal_catalog_hash IS NULL AS run_active_shape
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
