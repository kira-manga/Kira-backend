package me.manga.kira.backend.complaint.infrastructure.admission

/** Fixed operator session, not SET ROLE, a supplied principal name or a boolean from outside this authenticated holder. */
internal const val AUTHENTICATE_DESIRED_OPERATOR_V1 = """
    SELECT session_user = 'kira_complaint_config_operator'
        AND current_user = 'kira_complaint_config_operator' AND current_database() = ? AS authenticated
"""

/** Closed schema inventory for comparison ONLY. No caller can select columns, SQL, a scope or a mutation. */
internal val DESIRED_PRESERVED_COLUMNS_V1 = listOf(
    "publication_epoch", "pending_projection_token", "retention_lease_owner", "retention_lease_token", "retention_lease_expires_at",
    "seal_state", "seal_epoch", "seal_writer_generation", "seal_operation_token", "seal_object_key", "seal_bytes", "seal_hash",
    "seal_object_version", "seal_ciphertext_hash", "seal_retain_until", "seal_verified_at", "seal_verification_bytes", "seal_verification_hash",
    "checkpoint_generation", "checkpoint_fencing_token", "checkpoint_catalog_generation", "checkpoint_catalog_hash", "checkpoint_writer_generation",
    "checkpoint_cutoff_epoch", "checkpoint_configuration_hash", "checkpoint_database_identity", "checkpoint_restore_identity", "checkpoint_schema",
    "checkpoint_started_at", "checkpoint_completed_at", "checkpoint_object_count", "checkpoint_byte_count", "checkpoint_result",
    "checkpoint_bytes", "checkpoint_hash",
    "rotation_sequence", "rotation_id", "rotation_state", "rotation_epoch_before", "rotation_implementation_schema", "rotation_desired_generation",
    "rotation_desired_configuration_hash", "rotation_database_identity", "rotation_restore_identity", "rotation_event_writer_generation",
    "rotation_accepted_catalog_generation", "rotation_accepted_catalog_hash", "rotation_trust_bundle_hash", "rotation_catalog_writer_generation",
    "rotation_request_owner", "rotation_request_token", "rotation_requested_at", "rotation_capture_owner", "rotation_capture_token",
    "rotation_captured_at", "rotation_epoch_after",
    "seal_format", "seal_rotation_id", "seal_rotation_sequence", "seal_preparing_fencing_token", "seal_routing_key_id",
    "seal_epoch_start", "seal_preceding_hash",
)

// The projection is generated ONLY from this fixed source inventory. CASE caps precede pgjdbc materialization;
// a violated cap still returns bounded_values=false and is a refusal, never a silently truncated comparison.
private val DESIRED_BYTE_LIMITS_V1 = mapOf(
    "desired_configuration_hash" to 32, "accepted_catalog_hash" to 32, "trust_bundle_hash" to 32,
    "seal_bytes" to 65_536, "seal_hash" to 32, "seal_ciphertext_hash" to 32, "seal_verification_bytes" to 65_536, "seal_verification_hash" to 32,
    "checkpoint_catalog_hash" to 32, "checkpoint_configuration_hash" to 32, "checkpoint_bytes" to 65_536, "checkpoint_hash" to 32,
    "rotation_desired_configuration_hash" to 32, "rotation_accepted_catalog_hash" to 32, "rotation_trust_bundle_hash" to 32, "seal_preceding_hash" to 32,
)
private val DESIRED_TEXT_LIMITS_V1 = mapOf(
    "seal_state" to 16,
    "seal_object_key" to 1024,
    "seal_object_version" to 4096,
    "checkpoint_result" to 16,
    "rotation_state" to 16,
    "seal_routing_key_id" to 128,
)
private val DESIRED_COLUMN_LIMITS_V1 = DESIRED_BYTE_LIMITS_V1 + DESIRED_TEXT_LIMITS_V1
private val DESIRED_CORE_COLUMNS_V1 = listOf(
    "data_scope_id", "test_only", "implementation_schema", "desired_generation", "desired_configuration_hash", "database_identity", "restore_identity",
    "event_writer_generation", "accepted_catalog_generation", "accepted_catalog_hash", "trust_bundle_hash", "catalog_writer_generation",
    "maintenance_closed", "creation_closed", "scan_requested", "lease_owner", "lease_token", "lease_expires_at", "updated_at",
)
private val DESIRED_CONTROL_SELECT_V1 = """
    SELECT ${(DESIRED_CORE_COLUMNS_V1 + DESIRED_PRESERVED_COLUMNS_V1).joinToString(",\n        ") { column ->
    val maximum = DESIRED_COLUMN_LIMITS_V1[column]
    if (maximum == null) "c.$column" else "CASE WHEN octet_length(c.$column) <= $maximum THEN c.$column END AS $column"
}},
        (${DESIRED_COLUMN_LIMITS_V1.entries.joinToString(" AND ") { (name, maximum) ->
    "(c.$name IS NULL OR octet_length(c.$name) <= $maximum)"
}}) AS bounded_values,
        isfinite(c.updated_at) AND (c.lease_expires_at IS NULL OR isfinite(c.lease_expires_at))
            AND (c.retention_lease_expires_at IS NULL OR isfinite(c.retention_lease_expires_at))
            AND (c.seal_retain_until IS NULL OR isfinite(c.seal_retain_until)) AND (c.seal_verified_at IS NULL OR isfinite(c.seal_verified_at))
            AND (c.checkpoint_started_at IS NULL OR isfinite(c.checkpoint_started_at)) AND (c.checkpoint_completed_at IS NULL OR isfinite(c.checkpoint_completed_at))
            AND (c.rotation_requested_at IS NULL OR isfinite(c.rotation_requested_at)) AND (c.rotation_captured_at IS NULL OR isfinite(c.rotation_captured_at)) AS finite_times
    FROM public.complaint_journal_control c WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid
""".trimIndent()

internal val LOCK_DESIRED_CONTROL_V1 = "$DESIRED_CONTROL_SELECT_V1\nFOR UPDATE"
internal val READ_DESIRED_CONTROL_V1 = DESIRED_CONTROL_SELECT_V1

/** A LATER statement after the row lock returns: never use a pre-wait MVCC snapshot to certify absence. */
internal const val READ_DESIRED_PENDING_V1 = """
    SELECT NOT EXISTS (
        SELECT 1 FROM public.complaint_catalog_mutations WHERE state = 'PREPARED' OR (state = 'COMPLETED' AND projected_at IS NULL)
    ) AS no_pending
"""
internal const val READ_DESIRED_PRISTINE_V1 = """
    SELECT NOT EXISTS (SELECT 1 FROM public.complaint_catalog_mutations)
        AND NOT EXISTS (SELECT 1 FROM public.complaint_test_runs)
        AND NOT EXISTS (SELECT 1 FROM public.complaint_journal_control WHERE data_scope_id <> '00000000-0000-0000-0000-000000000000'::uuid)
        AND NOT EXISTS (SELECT 1 FROM public.complaint_journal_publications)
        AND NOT EXISTS (SELECT 1 FROM public.complaint_journal_scan_runs) AS pristine
"""

/** Locked full old B plus exact CURRENT nullable lease/gates/update-time preimage, not a stale phase-one lease sample. */
private val DESIRED_PREIMAGE_V1 = """
    NOT c.test_only AND c.implementation_schema = 1 AND c.desired_generation = ?
        AND c.desired_configuration_hash IS NOT DISTINCT FROM ?::bytea
        AND c.database_identity IS NOT DISTINCT FROM ?::uuid AND c.restore_identity IS NOT DISTINCT FROM ?::uuid
        AND c.event_writer_generation IS NOT DISTINCT FROM ?::uuid AND c.accepted_catalog_generation IS NOT DISTINCT FROM ?::bigint
        AND c.accepted_catalog_hash IS NOT DISTINCT FROM ?::bytea AND c.trust_bundle_hash IS NOT DISTINCT FROM ?::bytea
        AND c.catalog_writer_generation IS NOT DISTINCT FROM ?::uuid AND c.pending_projection_token IS NOT DISTINCT FROM ?::uuid
        AND c.lease_owner IS NOT DISTINCT FROM ?::uuid AND c.lease_token = ? AND c.lease_expires_at IS NOT DISTINCT FROM ?::timestamptz
        AND c.updated_at = ?::timestamptz AND c.maintenance_closed = ? AND c.creation_closed = ? AND c.scan_requested = ?
""".trimIndent()
private const val DESIRED_RETURNING_V1 = "RETURNING c.updated_at, sampled.sampled_at, isfinite(c.updated_at) AND isfinite(sampled.sampled_at) AS finite_times"

internal val BOOTSTRAP_DESIRED_V1 = """
    WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    UPDATE public.complaint_journal_control c SET desired_configuration_hash = ?::bytea, updated_at = sampled.sampled_at
    FROM sampled
    WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid AND ($DESIRED_PREIMAGE_V1)
        AND c.desired_generation = 1 AND c.desired_configuration_hash IS NULL AND isfinite(sampled.sampled_at)
    $DESIRED_RETURNING_V1
""".trimIndent()

internal val CLOSE_DESIRED_GATES_V1 = """
    WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    UPDATE public.complaint_journal_control c SET maintenance_closed = true, creation_closed = true, scan_requested = true, updated_at = sampled.sampled_at
    FROM sampled
    WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid AND ($DESIRED_PREIMAGE_V1) AND isfinite(sampled.sampled_at)
    $DESIRED_RETURNING_V1
""".trimIndent()

internal val SUPERSEDE_DESIRED_V1 = """
    WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    UPDATE public.complaint_journal_control c SET desired_configuration_hash = ?::bytea, desired_generation = ?,
        lease_token = CASE WHEN c.lease_token BETWEEN 0 AND 9223372036854775806 THEN c.lease_token + 1 ELSE NULL END,
        lease_owner = NULL, lease_expires_at = NULL, updated_at = sampled.sampled_at
    FROM sampled
    WHERE c.data_scope_id = '00000000-0000-0000-0000-000000000000'::uuid AND ($DESIRED_PREIMAGE_V1)
        AND c.maintenance_closed AND c.creation_closed AND c.scan_requested AND c.pending_projection_token IS NULL
        AND c.rotation_state IS NULL AND c.seal_state IS NULL AND c.lease_token < 9223372036854775807
        AND NOT EXISTS (SELECT 1 FROM public.complaint_catalog_mutations WHERE state = 'PREPARED' OR (state = 'COMPLETED' AND projected_at IS NULL))
        AND isfinite(sampled.sampled_at)
    $DESIRED_RETURNING_V1
""".trimIndent()
