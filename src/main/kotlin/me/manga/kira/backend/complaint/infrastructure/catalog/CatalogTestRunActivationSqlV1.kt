package me.manga.kira.backend.complaint.infrastructure.catalog

/** Fixed SQL only. The permanent global predecessor is distinct from the not-yet-created TEST scope. */
internal object CatalogTestRunActivationSqlV1 {
    private const val GLOBAL = "'00000000-0000-0000-0000-000000000000'::uuid"
    private const val CONTROL_PREIMAGE = "(to_jsonb(c) - ARRAY['lease_owner','lease_token','lease_expires_at','updated_at','maintenance_closed','creation_closed'])::text"
    private val controlBaseBound = """
        NOT c.test_only AND c.implementation_schema = 1 AND c.publication_epoch > 0 AND c.desired_generation > 0
        AND c.accepted_catalog_generation BETWEEN 1 AND 65536 AND complaint_digest_valid(c.accepted_catalog_hash)
        AND complaint_digest_valid(c.trust_bundle_hash) AND complaint_is_v4(c.catalog_writer_generation)
        AND c.lease_token >= 0
        AND complaint_finite_times(c.updated_at, c.lease_expires_at, c.retention_lease_expires_at, c.seal_retain_until,
            c.seal_verified_at, c.checkpoint_started_at, c.checkpoint_completed_at)
        AND coalesce(octet_length(c.seal_bytes) <= 65536, true)
        AND coalesce(octet_length(c.seal_verification_bytes) <= 65536, true)
        AND coalesce(octet_length(c.checkpoint_bytes) <= 65536, true)
        AND octet_length($CONTROL_PREIMAGE) BETWEEN 1 AND 524288
    """.trimIndent()
    private val controlBound = "$controlBaseBound AND c.pending_projection_token IS NULL"

    // Exact bounded PostgreSQL row comparison bytes, not kcj-1, a full-D digest or a portable owner ticket.
    // Keep every epoch/scan/retention/seal/checkpoint field; only the separately checked closure/lease fields differ.
    private val controlSelect = """
        SELECT ($controlBound) IS TRUE AS valid,
            CASE WHEN ($controlBound) IS TRUE THEN
                $CONTROL_PREIMAGE
            END AS core_preimage,
            c.maintenance_closed, c.creation_closed, c.accepted_catalog_generation,
            CASE WHEN octet_length(c.accepted_catalog_hash) = 32 THEN c.accepted_catalog_hash END AS accepted_catalog_hash,
            c.database_identity, c.restore_identity, c.event_writer_generation, c.catalog_writer_generation,
            CASE WHEN octet_length(c.trust_bundle_hash) = 32 THEN c.trust_bundle_hash END AS trust_bundle_hash,
            c.lease_owner, c.lease_token, c.lease_expires_at, c.pending_projection_token
        FROM complaint_journal_control c WHERE c.data_scope_id = $GLOBAL
    """.trimIndent()
    val readControl = controlSelect
    val lockControl = controlSelect + "\nFOR UPDATE"

    private val deliveryExpected = """
        WITH expected AS MATERIALIZED (SELECT ?::bigint AS predecessor_generation, ?::bytea AS predecessor_hash,
            ?::bigint AS generation, ?::bytea AS envelope_hash, ?::uuid AS token)
    """.trimIndent()
    private val deliveryControlBound = """
        $controlBaseBound AND c.maintenance_closed AND c.creation_closed
        AND ((c.accepted_catalog_generation = e.predecessor_generation AND c.accepted_catalog_hash = e.predecessor_hash AND c.pending_projection_token IS NULL)
            OR (c.accepted_catalog_generation = e.generation AND c.accepted_catalog_hash = e.envelope_hash AND c.pending_projection_token = e.token))
    """.trimIndent()
    // Reconstruct only the known predecessor head/pending fields, using PostgreSQL's own exact row-JSON representation.
    // Every other original global-D/control column remains in the byte preimage. Target D is NEVER substituted.
    private val deliveryPreimage = """
        ((to_jsonb(c) - ARRAY['lease_owner','lease_token','lease_expires_at','updated_at','maintenance_closed','creation_closed'])
            || jsonb_build_object('accepted_catalog_generation', e.predecessor_generation, 'accepted_catalog_hash', e.predecessor_hash,
                'pending_projection_token', NULL::uuid))::text
    """.trimIndent()
    private val deliveryControlSelect = """
        $deliveryExpected
        SELECT ($deliveryControlBound) IS TRUE AS valid,
            CASE WHEN ($deliveryControlBound) IS TRUE THEN $deliveryPreimage END AS core_preimage,
            c.maintenance_closed, c.creation_closed, c.accepted_catalog_generation,
            CASE WHEN octet_length(c.accepted_catalog_hash) = 32 THEN c.accepted_catalog_hash END AS accepted_catalog_hash,
            c.database_identity, c.restore_identity, c.event_writer_generation, c.catalog_writer_generation,
            CASE WHEN octet_length(c.trust_bundle_hash) = 32 THEN c.trust_bundle_hash END AS trust_bundle_hash,
            c.lease_owner, c.lease_token, c.lease_expires_at, c.pending_projection_token
        FROM complaint_journal_control c CROSS JOIN expected e WHERE c.data_scope_id = $GLOBAL
    """.trimIndent()
    val readDeliveryControl = deliveryControlSelect
    val lockDeliveryControl = deliveryControlSelect + "\nFOR UPDATE OF c"

    /** Post-lock sample; no earlier clock value or transaction-start now() may authorize the lease. */
    val acquireLease = """
        WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
        UPDATE complaint_journal_control c
        SET lease_owner = ?::uuid, lease_token = c.lease_token + 1,
            lease_expires_at = sampled.sampled_at + interval '30 seconds', updated_at = sampled.sampled_at
        FROM sampled
        WHERE c.data_scope_id = $GLOBAL AND c.lease_token < 9223372036854775807 AND isfinite(sampled.sampled_at)
            AND (c.lease_owner IS NULL OR c.lease_expires_at <= sampled.sampled_at)
        RETURNING c.lease_owner, c.lease_token, c.lease_expires_at
    """.trimIndent()
    val currentLease = """
        WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
        SELECT (c.lease_owner = ?::uuid AND c.lease_token = ? AND c.lease_expires_at = ?::timestamptz
            AND isfinite(sampled.sampled_at) AND c.lease_expires_at > sampled.sampled_at) IS TRUE AS current
        FROM complaint_journal_control c CROSS JOIN sampled WHERE c.data_scope_id = $GLOBAL
    """.trimIndent()

    private val mutationColumnsMatch = """
        m.operation_token = ?::uuid AND m.data_scope_id = ?::uuid AND m.predecessor_generation = ? AND m.predecessor_hash = ?::bytea
        AND m.successor_generation = ? AND m.catalog_writer_generation = ?::uuid AND m.approval_bytes = ?::bytea AND m.approval_hash = ?::bytea
        AND m.unsigned_bytes = ?::bytea AND m.unsigned_hash = ?::bytea AND m.signer_one_id = ? AND m.signer_one_algorithm = ?
        AND m.object_key = ? AND m.created_at = ?::timestamptz AND m.operation_type = 'TEST_RUN_ACTIVATION' AND m.test_only
        AND m.canonicalizer = 'kcj-1' AND m.signer_policy = 'SINGLE'
        AND m.signer_two_id IS NULL AND m.signer_two_algorithm IS NULL AND m.signer_two_signature IS NULL
        AND m.projected_at IS NULL
    """.trimIndent()
    private val preparedState = """
        m.state = 'PREPARED' AND m.object_version IS NULL AND m.retain_until IS NULL
        AND m.primary_evidence_bytes IS NULL AND m.primary_evidence_hash IS NULL AND m.replica_evidence_bytes IS NULL AND m.replica_evidence_hash IS NULL
        AND m.completed_at IS NULL
    """.trimIndent()
    private val preparedColumnsMatch = "$mutationColumnsMatch AND $preparedState"
    private const val unsignedSignatureMatch = "m.signer_one_signature IS NULL AND m.envelope_bytes IS NULL AND m.envelope_hash IS NULL"
    private val signedSignatureMatch = """
        m.signer_one_signature = ?::bytea AND m.envelope_bytes = ?::bytea AND m.envelope_hash = ?::bytea
        AND octet_length(m.signer_one_signature) = 384 AND complaint_bytes_match(m.envelope_bytes, m.envelope_hash, 131072)
    """.trimIndent()
    private val preparedMatch = "$preparedColumnsMatch AND $unsignedSignatureMatch"
    private val signedPreparedMatch = "$preparedColumnsMatch AND $signedSignatureMatch"
    private val completedBound = """
        m.state = 'COMPLETED' AND m.projected_at IS NULL AND m.completed_at IS NOT NULL AND m.completed_at >= m.created_at
        AND complaint_finite_times(m.completed_at, m.retain_until)
        AND complaint_opaque_valid(m.object_version, 1024) AND m.object_version <> 'null' AND m.retain_until IS NOT NULL
        AND complaint_bytes_match(m.primary_evidence_bytes, m.primary_evidence_hash, 65536)
        AND complaint_bytes_match(m.replica_evidence_bytes, m.replica_evidence_hash, 65536)
    """.trimIndent()
    private val completedMatch = """
        $mutationColumnsMatch AND $signedSignatureMatch AND $completedBound
        AND m.object_version = ? AND m.retain_until = ?::timestamptz AND m.primary_evidence_bytes = ?::bytea
        AND m.primary_evidence_hash = ?::bytea AND m.replica_evidence_bytes = ?::bytea AND m.replica_evidence_hash = ?::bytea
        AND m.completed_at = ?::timestamptz
    """.trimIndent()
    private val historyBound = """
        m.canonicalizer = 'kcj-1' AND complaint_is_v4(m.operation_token) AND complaint_is_v4(m.catalog_writer_generation)
        AND m.successor_generation BETWEEN 1 AND 65536 AND m.predecessor_generation = m.successor_generation - 1
        AND complaint_digest_valid(m.predecessor_hash) AND complaint_bytes_match(m.approval_bytes, m.approval_hash, 4096)
        AND complaint_bytes_match(m.unsigned_bytes, m.unsigned_hash, 8388608)
        AND complaint_ascii_valid(m.object_key, 1024) AND complaint_ascii_valid(m.signer_one_id, 128)
        AND complaint_ascii_valid(m.signer_one_algorithm, 128)
        AND complaint_finite_times(m.created_at, m.completed_at, m.projected_at, m.retain_until)
        AND coalesce(octet_length(m.signer_one_signature) <= 384, true) AND coalesce(octet_length(m.signer_two_signature) <= 384, true)
        AND (m.signer_two_id IS NULL OR complaint_ascii_valid(m.signer_two_id, 128))
        AND (m.signer_two_algorithm IS NULL OR complaint_ascii_valid(m.signer_two_algorithm, 128))
        AND coalesce(octet_length(m.envelope_hash) = 32, true) AND coalesce(octet_length(m.primary_evidence_hash) = 32, true)
        AND coalesce(octet_length(m.replica_evidence_hash) = 32, true) AND (m.object_version IS NULL OR complaint_opaque_valid(m.object_version, 1024))
        AND ((m.operation_type = 'TEST_RUN_ACTIVATION' AND m.test_only AND complaint_scope_valid(m.data_scope_id, m.test_only)
                AND octet_length(m.unsigned_bytes) <= 131072
                AND ((m.state = 'PREPARED' AND (($unsignedSignatureMatch) OR (octet_length(m.signer_one_signature) = 384
                        AND complaint_bytes_match(m.envelope_bytes, m.envelope_hash, 131072))))
                    OR (($completedBound) AND octet_length(m.signer_one_signature) = 384
                        AND complaint_bytes_match(m.envelope_bytes, m.envelope_hash, 131072))))
            OR (m.data_scope_id IS NULL AND m.test_only IS NULL AND m.state = 'COMPLETED' AND m.completed_at IS NOT NULL AND m.projected_at IS NOT NULL
                AND complaint_bytes_match(m.envelope_bytes, m.envelope_hash, 8388608)
                AND octet_length(m.signer_one_signature) = 384
                AND ((m.signer_policy = 'SINGLE' AND m.signer_two_id IS NULL AND m.signer_two_algorithm IS NULL AND m.signer_two_signature IS NULL)
                    OR (m.signer_policy = 'ROTATION_OVERLAP' AND complaint_ascii_valid(m.signer_two_id,128)
                        AND complaint_ascii_valid(m.signer_two_algorithm,128) AND octet_length(m.signer_two_signature) = 384))
                AND complaint_opaque_valid(m.object_version, 1024) AND m.object_version <> 'null' AND m.retain_until IS NOT NULL
                AND complaint_bytes_match(m.primary_evidence_bytes, m.primary_evidence_hash, 65536)
                AND complaint_bytes_match(m.replica_evidence_bytes, m.replica_evidence_hash, 65536)))
    """.trimIndent()

    /**
     * Private fixed-order LP32 frames: signed big-endian length (-1 for NULL), then exact bytes.
     * Integers/UUIDs/timestamps use PostgreSQL binary send formats (timestamptz = microseconds since 2000-01-01 UTC).
     * All variable fields are bounded BEFORE hashing. No row JSON, signature or blob is sent to JDBC.
     * This is only a local comparison fingerprint, never kcj-1, full D, an approval or a portable authority.
     */
    private fun frame(fields: List<String>): String = fields.joinToString(" || ") { bytes ->
        "(CASE WHEN $bytes IS NULL THEN int4send(-1) ELSE int4send(octet_length($bytes)) || $bytes END)"
    }

    private val rawFrame = frame(
        listOf(
            "convert_to('${CatalogTestRunActivationHistoryV1.RAW_DOMAIN}', 'UTF8')", "convert_to(m.operation_type, 'UTF8')",
            "uuid_send(m.operation_token)", "int8send(m.successor_generation)", "m.predecessor_hash", "uuid_send(m.catalog_writer_generation)",
            "m.approval_hash", "m.unsigned_hash", "m.envelope_hash", "convert_to(m.signer_policy, 'UTF8')",
            "convert_to(m.signer_one_id, 'UTF8')", "convert_to(m.signer_one_algorithm, 'UTF8')", "m.signer_one_signature",
            "convert_to(m.signer_two_id, 'UTF8')", "convert_to(m.signer_two_algorithm, 'UTF8')", "m.signer_two_signature",
            "convert_to(m.object_key, 'UTF8')", "convert_to(m.object_version, 'UTF8')", "timestamptz_send(m.created_at)",
            "int8send(octet_length(m.unsigned_bytes)::bigint)", "int8send(octet_length(m.envelope_bytes)::bigint)",
        ),
    )

    // Every V14 row column is represented: the five bounded blobs use their validated digest AND length.
    private val rowFrame = frame(
        listOf(
            "convert_to('kira-test-activation-sql-row-v1', 'UTF8')", "uuid_send(m.operation_token)", "convert_to(m.operation_type, 'UTF8')",
            "uuid_send(m.data_scope_id)", "boolsend(m.test_only)", "int8send(m.predecessor_generation)", "m.predecessor_hash",
            "int8send(m.successor_generation)", "uuid_send(m.catalog_writer_generation)",
            "int8send(octet_length(m.approval_bytes)::bigint)", "m.approval_hash", "convert_to(m.canonicalizer, 'UTF8')",
            "int8send(octet_length(m.unsigned_bytes)::bigint)", "m.unsigned_hash", "convert_to(m.signer_policy, 'UTF8')",
            "convert_to(m.signer_one_id, 'UTF8')", "convert_to(m.signer_one_algorithm, 'UTF8')", "m.signer_one_signature",
            "convert_to(m.signer_two_id, 'UTF8')", "convert_to(m.signer_two_algorithm, 'UTF8')", "m.signer_two_signature",
            "int8send(octet_length(m.envelope_bytes)::bigint)", "m.envelope_hash", "convert_to(m.object_key, 'UTF8')", "convert_to(m.object_version, 'UTF8')",
            "timestamptz_send(m.retain_until)", "int8send(octet_length(m.primary_evidence_bytes)::bigint)", "m.primary_evidence_hash",
            "int8send(octet_length(m.replica_evidence_bytes)::bigint)", "m.replica_evidence_hash", "convert_to(m.state, 'UTF8')",
            "timestamptz_send(m.created_at)", "timestamptz_send(m.completed_at)", "timestamptz_send(m.projected_at)",
        ),
    )

    // Six fixed-width columns, <256 encoded payload bytes/row, at most the original65536+1 overflow sentinel.
    // fetch128 limits driver buffering even for rejected histories; the overflow row must fail, never truncate acceptance.
    private fun historySelect(tailMatch: String): String = """
        SELECT ($tailMatch) IS TRUE AS prepared_matches, ($historyBound) IS TRUE AS valid,
            m.successor_generation,
            CASE WHEN ($historyBound) IS TRUE THEN sha256($rowFrame) END AS row_digest,
            CASE WHEN ($historyBound) IS TRUE AND m.state = 'COMPLETED' THEN sha256($rawFrame) END AS raw_binding,
            CASE WHEN ($historyBound) IS TRUE AND m.state = 'COMPLETED' THEN timestamptz_send(m.retain_until) END AS retain_until_wire
        FROM complaint_catalog_mutations m ORDER BY m.successor_generation LIMIT ?
    """.trimIndent()
    val readHistory = historySelect(preparedMatch)
    val lockHistory = readHistory + "\nFOR UPDATE"
    val readSignedHistory = historySelect(signedPreparedMatch)
    val lockSignedHistory = readSignedHistory + "\nFOR UPDATE"
    val readCompletedHistory = historySelect(completedMatch)
    val lockCompletedHistory = readCompletedHistory + "\nFOR UPDATE"

    /** At most ONE exact TEST tail, bounded before detaching; never raw historical rows or a parser under locks. */
    val readPreparedTail = """
        SELECT (($preparedColumnsMatch) AND (($unsignedSignatureMatch) OR ($signedSignatureMatch))) IS TRUE AS valid,
            CASE WHEN octet_length(m.signer_one_signature) = 384 THEN m.signer_one_signature END AS signature_bytes,
            CASE WHEN complaint_bytes_match(m.envelope_bytes, m.envelope_hash, 131072) THEN m.envelope_bytes END AS envelope_bytes,
            CASE WHEN octet_length(m.envelope_hash) = 32 THEN m.envelope_hash END AS envelope_hash
        FROM complaint_catalog_mutations m WHERE m.successor_generation = ?
    """.trimIndent()

    /** Exactly one signed TEST tail, either still PREPARED or complete/pending. No projected/partial row can pass. */
    val readDeliveryTail = """
        SELECT (($mutationColumnsMatch) AND ($signedSignatureMatch) AND (($preparedState) OR ($completedBound))) IS TRUE AS valid,
            m.state = 'COMPLETED' AS completed,
            CASE WHEN octet_length(m.signer_one_signature) = 384 THEN m.signer_one_signature END AS signature_bytes,
            CASE WHEN complaint_bytes_match(m.envelope_bytes, m.envelope_hash, 131072) THEN m.envelope_bytes END AS envelope_bytes,
            CASE WHEN octet_length(m.envelope_hash) = 32 THEN m.envelope_hash END AS envelope_hash,
            CASE WHEN ($completedBound) THEN m.object_version END AS object_version,
            CASE WHEN ($completedBound) THEN m.retain_until END AS retain_until,
            CASE WHEN ($completedBound) THEN m.primary_evidence_bytes END AS primary_evidence_bytes,
            CASE WHEN ($completedBound) THEN m.primary_evidence_hash END AS primary_evidence_hash,
            CASE WHEN ($completedBound) THEN m.replica_evidence_bytes END AS replica_evidence_bytes,
            CASE WHEN ($completedBound) THEN m.replica_evidence_hash END AS replica_evidence_hash,
            CASE WHEN ($completedBound) THEN m.completed_at END AS completed_at
        FROM complaint_catalog_mutations m WHERE m.successor_generation = ?
    """.trimIndent()

    val complete = """
        WITH sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
        UPDATE complaint_catalog_mutations m
        SET object_version = ?, retain_until = ?::timestamptz, primary_evidence_bytes = ?::bytea, primary_evidence_hash = ?::bytea,
            replica_evidence_bytes = ?::bytea, replica_evidence_hash = ?::bytea, completed_at = sampled.sampled_at, state = 'COMPLETED'
        FROM sampled
        WHERE ($signedPreparedMatch) AND isfinite(sampled.sampled_at) AND sampled.sampled_at >= m.created_at
    """.trimIndent()
    val markPending = """
        $deliveryExpected
        UPDATE complaint_journal_control c
        SET accepted_catalog_generation = e.generation, accepted_catalog_hash = e.envelope_hash,
            pending_projection_token = e.token, updated_at = clock_timestamp()
        FROM expected e
        WHERE c.data_scope_id = $GLOBAL AND c.maintenance_closed AND c.creation_closed AND c.pending_projection_token IS NULL
            AND c.accepted_catalog_generation = e.predecessor_generation AND c.accepted_catalog_hash = e.predecessor_hash
            AND c.lease_owner = ?::uuid AND c.lease_token = ? AND c.lease_expires_at = ?::timestamptz
    """.trimIndent()

    /** The caller must already hold the original TEST M/E/control/history order and a fresh post-lock lease sample. */
    val persistSignature = """
        UPDATE complaint_catalog_mutations m SET signer_one_signature = ?::bytea, envelope_bytes = ?::bytea, envelope_hash = ?::bytea
        WHERE ($preparedMatch)
    """.trimIndent()

    /** Reads only: no not-yet-created scope row is locked and no later-class row is taken ahead of counters. */
    val preflight = """
        WITH expected AS MATERIALIZED (SELECT ?::uuid AS scope, ?::uuid AS first_id, ?::uuid AS second_id,
            ?::text AS first_key, ?::text AS second_key, ?::bytea AS configuration_hash, ?::bigint AS installation_limit,
            ?::bigint AS generation, ?::timestamptz AS created_at)
        SELECT (complaint_scope_valid(e.scope, true) AND complaint_is_v4(e.first_id) AND complaint_is_v4(e.second_id)
            AND e.first_id <> e.second_id AND e.first_key <> e.second_key
            AND e.first_key COLLATE "C" ~ '^[a-z0-9._-]{1,96}$' AND e.second_key COLLATE "C" ~ '^[a-z0-9._-]{1,96}$'
            AND complaint_digest_valid(e.configuration_hash) AND e.installation_limit > 0 AND e.generation BETWEEN 2 AND 65536
            AND isfinite(e.created_at)
            AND NOT EXISTS (SELECT 1 FROM complaint_test_runs)
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_control WHERE data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaint_resource_ids WHERE id IN (e.first_id, e.second_id) OR data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaints WHERE id IN (e.first_id, e.second_id)
                OR parent_resource_id IN (e.first_id, e.second_id) OR data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaint_installation_ids WHERE data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM app_installations WHERE data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_publications WHERE data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaint_idempotency_receipts WHERE data_scope_id = e.scope
                OR target_ids && ARRAY[e.first_id, e.second_id] OR ack_ids && ARRAY[e.first_id, e.second_id])
            AND NOT EXISTS (SELECT 1 FROM installation_deletion_receipts WHERE data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_applied WHERE data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_retirements WHERE data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaint_recovery_capacity_reservations WHERE data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_runs WHERE data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_entries WHERE data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaint_import_runs WHERE data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaint_import_staging WHERE data_scope_id = e.scope
                OR assigned_id IN (e.first_id, e.second_id) OR assigned_parent_id IN (e.first_id, e.second_id))
            AND NOT EXISTS (SELECT 1 FROM complaint_import_artifacts WHERE data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaint_legacy_records WHERE data_scope_id = e.scope
                OR assigned_id IN (e.first_id, e.second_id) OR assigned_parent_id IN (e.first_id, e.second_id))
            AND NOT EXISTS (SELECT 1 FROM audit_log WHERE complaint_data_scope_id = e.scope)) IS TRUE AS allowed
        FROM expected e
    """.trimIndent()

    val closeControl = """
        UPDATE complaint_journal_control SET maintenance_closed = true, creation_closed = true, updated_at = clock_timestamp()
        WHERE data_scope_id = $GLOBAL AND pending_projection_token IS NULL
            AND lease_owner = ?::uuid AND lease_token = ? AND lease_expires_at = ?::timestamptz
    """.trimIndent()
    val insertPrepared = """
        INSERT INTO complaint_catalog_mutations (
            operation_token, data_scope_id, predecessor_generation, predecessor_hash, successor_generation, catalog_writer_generation,
            approval_bytes, approval_hash, unsigned_bytes, unsigned_hash, signer_one_id, signer_one_algorithm, object_key, created_at,
            operation_type, test_only, canonicalizer, signer_policy, state
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'TEST_RUN_ACTIVATION', true, 'kcj-1', 'SINGLE', 'PREPARED')
    """.trimIndent()
}
