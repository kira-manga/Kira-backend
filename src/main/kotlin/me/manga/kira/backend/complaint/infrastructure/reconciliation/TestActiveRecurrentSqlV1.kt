package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.ComplaintInstallationTestRunRows
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllApplySql

/** Fixed recurrent statements; every caller is the closed original pooled/native operation. */
internal object TestActiveRecurrentSqlV1 {
    private const val GLOBAL = "'00000000-0000-0000-0000-000000000000'::uuid"
    val authenticate = TestActiveFirstCutSqlV1.authenticate
    val lockGlobal = TestActiveFirstCutSqlV1.lockGlobal
    val lockScope = TestActiveFirstCutSqlV1.lockScope
    val lockRun = TestActiveFirstCutSqlV1.lockRun
    val lockSlot = "SELECT operation_token FROM complaint_test_active_recurrent_seal_intents WHERE data_scope_id = ?::uuid AND operation_token = ?::uuid AND test_only FOR UPDATE"

    // Operational DB/native times preserve microseconds and the row decoder's [1970,10000)
    // range. The terminal whole-second helper remains only for wire retention.
    /**
     * Recurrent expected inventory only. UNION selects exact locators, NOT accepted history:
     * every physical P/E overlap is checked below and every E-only ALL row still needs the
     * original's authenticated-native four-route/N/P/L comparison before marker acceptance.
     * Both scope/epoch and unfiltered key probes retain malformed/foreign rows for refusal.
     */
    val manifestPage = """
        WITH b AS (SELECT ?::uuid AS scope, ?::bigint AS first_epoch, ?::bigint AS last_epoch,
            ?::text AS lower_key, ?::text AS upper_key, ?::text AS after_key, ?::text AS after_version),
        candidates AS (
            SELECT p.object_key, p.object_version FROM complaint_journal_publications p,b
            WHERE (p.data_scope_id=b.scope AND p.journal_epoch BETWEEN b.first_epoch AND b.last_epoch)
                OR (p.object_key COLLATE "C">=b.lower_key COLLATE "C" AND p.object_key COLLATE "C"<b.upper_key COLLATE "C")
            UNION
            SELECT a.object_key, a.object_version FROM complaint_deletion_journal_applied a,b
            WHERE (a.data_scope_id=b.scope AND a.journal_epoch BETWEEN b.first_epoch AND b.last_epoch)
                OR (a.object_key COLLATE "C">=b.lower_key COLLATE "C" AND a.object_key COLLATE "C"<b.upper_key COLLATE "C")
        ), page AS (
            SELECT k.* FROM candidates k,b WHERE b.after_key IS NULL OR
                (k.object_key COLLATE "C",coalesce(k.object_version,'') COLLATE "C")>(b.after_key COLLATE "C",b.after_version COLLATE "C")
            ORDER BY k.object_key COLLATE "C",coalesce(k.object_version,'') COLLATE "C" LIMIT ${TestActiveCutoffPublicationSqlV1.PAGE_SIZE}
        )
        SELECT CASE WHEN octet_length(k.object_key) BETWEEN 1 AND 1024 THEN k.object_key END AS manifest_key,
            CASE WHEN octet_length(k.object_version) BETWEEN 1 AND 1024 THEN k.object_version END AS manifest_version,
            p.event_id IS NOT NULL AS publication_present, a.object_key IS NOT NULL AS applied_present,
            sha256(convert_to((to_jsonb(p)||jsonb_build_object('row_xmin',p.xmin::text))::text,'UTF8')) AS publication_fingerprint,
            sha256(convert_to((to_jsonb(a)||jsonb_build_object('row_xmin',a.xmin::text))::text,'UTF8')) AS applied_fingerprint,
            CASE WHEN octet_length(a.event_id)=43 THEN a.event_id END AS applied_event_id,
            a.data_scope_id AS applied_scope, a.test_only AS applied_test_only, a.writer_generation AS applied_writer,
            a.journal_epoch AS applied_epoch, a.event_kind AS applied_kind, a.target_count AS applied_targets, a.applied_at,
            CASE WHEN octet_length(a.ciphertext_hash)=32 THEN a.ciphertext_hash END AS applied_ciphertext_hash,
            (a.test_only AND complaint_event_id_valid(a.event_id) AND complaint_is_v4(a.writer_generation)
                AND complaint_ascii_valid(a.object_key,1024) AND complaint_opaque_valid(a.object_version,1024) AND a.object_version<>'null'
                AND complaint_digest_valid(a.ciphertext_hash) AND isfinite(a.applied_at)
                AND a.applied_at >= '1970-01-01T00:00:00Z'::timestamptz AND a.applied_at < '10000-01-01T00:00:00Z'::timestamptz
                AND octet_length(to_jsonb(a)::text) BETWEEN 1 AND 16384) IS TRUE AS applied_valid,
            (CASE WHEN p.event_id IS NOT NULL AND a.object_key IS NOT NULL THEN
                p.state='APPLIED' AND p.test_only=a.test_only AND p.data_scope_id=a.data_scope_id AND p.writer_generation=a.writer_generation
                AND p.event_id=a.event_id AND p.object_key=a.object_key AND p.object_version=a.object_version
                AND p.journal_epoch=a.journal_epoch AND p.event_kind=a.event_kind AND p.target_count=a.target_count
                AND p.ciphertext_hash=a.ciphertext_hash AND p.applied_at=a.applied_at AND p.applied_at>=p.verified_at
                ELSE p.event_id IS NULL OR p.state='VERIFIED' END) IS TRUE AS overlap_valid,
            CASE WHEN octet_length(p.event_id)=43 THEN p.event_id END AS event_id,
            p.data_scope_id,p.test_only,p.writer_generation,p.journal_epoch,
            CASE WHEN octet_length(p.event_kind) BETWEEN 1 AND 32 THEN p.event_kind END AS event_kind,
            p.target_count,CASE WHEN octet_length(p.routing_key_id) BETWEEN 1 AND 64 THEN p.routing_key_id END AS routing_key_id,
            CASE WHEN octet_length(p.object_key) BETWEEN 1 AND 1024 THEN p.object_key END AS object_key,p.created_at,
            CASE WHEN octet_length(p.state) BETWEEN 1 AND 16 THEN p.state END AS state,
            CASE WHEN complaint_bytes_match(p.event_bytes,p.semantic_hash,65536) THEN p.event_bytes END AS event_bytes,
            CASE WHEN octet_length(p.semantic_hash)=32 THEN p.semantic_hash END AS semantic_hash,
            CASE WHEN octet_length(p.object_version) BETWEEN 1 AND 1024 THEN p.object_version END AS object_version,
            p.object_created_at,p.retain_until,p.verified_at,
            CASE WHEN octet_length(p.ciphertext_hash)=32 THEN p.ciphertext_hash END AS ciphertext_hash,
            CASE WHEN complaint_bytes_match(p.verification_bytes,p.verification_hash,65536) THEN p.verification_bytes END AS verification_bytes,
            CASE WHEN octet_length(p.verification_hash)=32 THEN p.verification_hash END AS verification_hash,
            (p.test_only AND complaint_is_v4(p.data_scope_id) AND complaint_is_v4(p.writer_generation)
                AND complaint_event_id_valid(p.event_id) AND p.journal_epoch>0 AND p.target_count BETWEEN 0 AND 100
                AND p.event_kind IN ('OWNER_DELETE','OWNER_DELETE_ALL','ADMIN_DELETE','ADMIN_BATCH_DELETE')
                AND complaint_ascii_valid(p.routing_key_id,64) AND complaint_ascii_valid(p.object_key,1024)
                AND p.canonicalizer='kcj-1' AND complaint_bytes_match(p.event_bytes,p.semantic_hash,65536)
                AND complaint_finite_times(p.created_at,p.object_created_at,p.retain_until,p.verified_at,p.applied_at)
                AND p.state IN ('VERIFIED','APPLIED') AND complaint_opaque_valid(p.object_version,1024) AND p.object_version<>'null'
                AND complaint_digest_valid(p.ciphertext_hash) AND p.object_created_at IS NOT NULL AND p.retain_until IS NOT NULL
                AND p.verified_at IS NOT NULL AND p.retain_until>p.verified_at AND p.object_created_at<=p.verified_at
                AND complaint_bytes_match(p.verification_bytes,p.verification_hash,65536)
                AND ((p.state='VERIFIED' AND p.applied_at IS NULL) OR (p.state='APPLIED' AND p.applied_at>=p.verified_at))
                AND octet_length(to_jsonb(p)::text) BETWEEN 1 AND 524288) IS TRUE AS valid
        FROM page k LEFT JOIN complaint_journal_publications p ON p.object_key=k.object_key
        LEFT JOIN complaint_deletion_journal_applied a ON a.object_key=k.object_key AND a.object_version=k.object_version
        ORDER BY k.object_key COLLATE "C",coalesce(k.object_version,'') COLLATE "C"
    """.trimIndent()

    // Fixed comparison-only readers under the original recurrent global/scoped holder. No
    // backward N/P/L row-lock acquisition after run/staging locks, and no APPLY or repair writes.
    fun retainedAllReceipt(scope: ComplaintDataScope) = OwnerDeleteAllApplySql.test(scope).LOCK_RECEIPTS.removeSuffix(" FOR UPDATE")
    fun retainedAllPublication(scope: ComplaintDataScope) = OwnerDeleteAllApplySql.test(scope).LOCK_PUBLICATION.removeSuffix(" FOR UPDATE")
    fun retainedAllReservation(scope: ComplaintDataScope) = OwnerDeleteAllApplySql.test(scope).LOCK_RECOVERY.removeSuffix(" FOR UPDATE")
    fun retainedAllApplied(scope: ComplaintDataScope) = OwnerDeleteAllApplySql.test(scope).QUEUE_APPLIED_FAMILY
    private val expected = """
        WITH e AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bytea AS configuration_hash, ?::bigint AS installation_limit,
            ?::bigint AS generation, ?::bytea AS activation_hash, ?::timestamptz AS created_at, ?::bigint[] AS original_reserve),
        d AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bigint AS desired_generation, ?::integer AS implementation_schema,
            ?::bytea AS configuration_hash, ?::uuid AS database_identity, ?::uuid AS restore_identity,
            ?::uuid AS event_writer, ?::uuid AS catalog_writer, ?::bytea AS trust_hash, ?::bigint AS generation, ?::bytea AS activation_hash),
        b AS MATERIALIZED (SELECT ?::bigint AS desired_generation, ?::bytea AS configuration_hash),
        j AS MATERIALIZED (SELECT ?::bytea AS journal_hash), s AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
    """.trimIndent()
    private val auxiliary = """
        c.retention_lease_owner IS NULL AND c.retention_lease_token = 0 AND c.retention_lease_expires_at IS NULL
        AND c.seal_format IS NULL AND c.seal_rotation_id IS NULL AND c.seal_rotation_sequence IS NULL
        AND c.seal_preparing_fencing_token IS NULL AND c.seal_routing_key_id IS NULL AND c.seal_epoch_start IS NULL AND c.seal_preceding_hash IS NULL
    """.trimIndent()
    private val sealShape = """
        ((c.seal_state IS NULL AND c.seal_epoch IS NULL AND c.seal_writer_generation IS NULL AND c.seal_operation_token IS NULL
            AND c.seal_object_key IS NULL AND c.seal_bytes IS NULL AND c.seal_hash IS NULL AND c.seal_object_version IS NULL
            AND c.seal_ciphertext_hash IS NULL AND c.seal_retain_until IS NULL AND c.seal_verified_at IS NULL
            AND c.seal_verification_bytes IS NULL AND c.seal_verification_hash IS NULL)
        OR (c.seal_state IN ('SEAL_PREPARED','SEAL_VERIFIED') AND c.seal_epoch>0
            AND complaint_is_v4(c.seal_writer_generation) AND complaint_is_v4(c.seal_operation_token)
            AND complaint_ascii_valid(c.seal_object_key,1024) AND complaint_bytes_match(c.seal_bytes,c.seal_hash,65536)
            AND ((c.seal_state='SEAL_PREPARED' AND c.seal_object_version IS NULL AND c.seal_ciphertext_hash IS NULL
                AND c.seal_retain_until IS NULL AND c.seal_verified_at IS NULL AND c.seal_verification_bytes IS NULL AND c.seal_verification_hash IS NULL)
            OR (c.seal_state='SEAL_VERIFIED' AND complaint_opaque_valid(c.seal_object_version,1024) AND c.seal_object_version<>'null'
                AND complaint_digest_valid(c.seal_ciphertext_hash) AND complaint_test_terminal_instant_valid(c.seal_retain_until)
                AND isfinite(c.seal_verified_at)
                AND c.seal_verified_at >= '1970-01-01T00:00:00Z'::timestamptz AND c.seal_verified_at < '10000-01-01T00:00:00Z'::timestamptz
                AND complaint_bytes_match(c.seal_verification_bytes,c.seal_verification_hash,65536)))))
    """.trimIndent()
    private val checkpointShape = """
        ((c.checkpoint_bytes IS NULL AND c.checkpoint_hash IS NULL AND c.checkpoint_generation IS NULL AND c.checkpoint_fencing_token IS NULL
            AND c.checkpoint_catalog_generation IS NULL AND c.checkpoint_catalog_hash IS NULL AND c.checkpoint_writer_generation IS NULL
            AND c.checkpoint_cutoff_epoch IS NULL AND c.checkpoint_configuration_hash IS NULL AND c.checkpoint_database_identity IS NULL
            AND c.checkpoint_restore_identity IS NULL AND c.checkpoint_schema IS NULL AND c.checkpoint_started_at IS NULL
            AND c.checkpoint_completed_at IS NULL AND c.checkpoint_object_count IS NULL AND c.checkpoint_byte_count IS NULL AND c.checkpoint_result IS NULL)
        OR (complaint_bytes_match(c.checkpoint_bytes,c.checkpoint_hash,65536) AND c.checkpoint_result='SUCCESS'
            AND complaint_digest_valid(c.checkpoint_catalog_hash) AND complaint_digest_valid(c.checkpoint_configuration_hash)))
    """.trimIndent()
    val read = """
        $expected
        SELECT (e.scope = d.scope AND r.test_only AND r.state = 'ACTIVE' AND (${ComplaintInstallationTestRunRows.activeShape})
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
            AND NOT c.maintenance_closed AND c.pending_projection_token IS NULL AND ($auxiliary) AND ($sealShape) AND ($checkpointShape)
            AND NOT g.test_only AND g.implementation_schema = 1 AND g.desired_generation = b.desired_generation
            AND g.desired_configuration_hash IS NOT DISTINCT FROM b.configuration_hash
            AND g.database_identity = d.database_identity AND g.restore_identity = d.restore_identity
            AND g.event_writer_generation = d.event_writer AND g.catalog_writer_generation = d.catalog_writer
            AND g.trust_bundle_hash = d.trust_hash AND g.accepted_catalog_generation = d.generation AND g.accepted_catalog_hash = d.activation_hash
            AND NOT g.maintenance_closed AND g.pending_projection_token IS NULL AND g.publication_epoch > 0
            AND c.rotation_sequence BETWEEN 1 AND 14 AND c.rotation_state IN ('REQUESTED','CAPTURED')
            AND c.rotation_implementation_schema = d.implementation_schema AND c.rotation_desired_generation = d.desired_generation
            AND c.rotation_desired_configuration_hash = d.configuration_hash AND c.rotation_database_identity = d.database_identity
            AND c.rotation_restore_identity = d.restore_identity AND c.rotation_event_writer_generation = d.event_writer
            AND c.rotation_accepted_catalog_generation = d.generation AND c.rotation_accepted_catalog_hash = d.activation_hash
            AND c.rotation_trust_bundle_hash = d.trust_hash AND c.rotation_catalog_writer_generation = d.catalog_writer
            AND c.lease_token >= c.rotation_request_token AND c.lease_token >= coalesce(c.rotation_capture_token, 0)
            AND complaint_finite_times(c.updated_at, g.updated_at, c.lease_expires_at, c.rotation_requested_at, c.rotation_captured_at,
                c.seal_retain_until, c.seal_verified_at, c.checkpoint_started_at, c.checkpoint_completed_at, s.sampled_at)
            AND s.sampled_at >= c.updated_at AND s.sampled_at >= g.updated_at
            AND isfinite(s.sampled_at)
            AND s.sampled_at >= '1970-01-01T00:00:00Z'::timestamptz AND s.sampled_at < '10000-01-01T00:00:00Z'::timestamptz
            AND octet_length(to_jsonb(c)::text) BETWEEN 1 AND 524288 AND octet_length(to_jsonb(g)::text) BETWEEN 1 AND 524288
            AND octet_length(to_jsonb(r)::text) BETWEEN 1 AND 524288
            AND NOT EXISTS (SELECT 1 FROM complaint_test_terminal_intents t WHERE t.data_scope_id = e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_runs x WHERE x.data_scope_id = e.scope AND x.active_recurrent_seal_token IS NULL)
            AND (c.checkpoint_bytes IS NULL OR complaint_bytes_match(c.checkpoint_bytes, c.checkpoint_hash, 65536))
            AND (c.seal_bytes IS NULL OR complaint_bytes_match(c.seal_bytes, c.seal_hash, 65536))
            AND (c.seal_verification_bytes IS NULL OR complaint_bytes_match(c.seal_verification_bytes, c.seal_verification_hash, 65536))
        ) IS TRUE AS valid, s.sampled_at,
            c.publication_epoch, c.rotation_sequence, c.rotation_id, c.rotation_state, c.scan_requested, c.rotation_epoch_before, c.rotation_epoch_after,
            c.rotation_request_owner, c.rotation_request_token, c.rotation_requested_at, c.rotation_capture_owner, c.rotation_capture_token, c.rotation_captured_at,
            c.lease_owner, c.lease_token, c.lease_expires_at, c.seal_state, c.seal_operation_token, c.seal_epoch, c.seal_writer_generation,
            CASE WHEN octet_length(c.seal_object_key) BETWEEN 1 AND 1024 THEN c.seal_object_key END AS seal_object_key,
            CASE WHEN complaint_bytes_match(c.seal_bytes, c.seal_hash, 65536) THEN c.seal_bytes END AS seal_bytes,
            CASE WHEN octet_length(c.seal_hash) = 32 THEN c.seal_hash END AS seal_hash,
            CASE WHEN octet_length(c.seal_object_version) BETWEEN 1 AND 1024 THEN c.seal_object_version END AS seal_object_version,
            CASE WHEN octet_length(c.seal_ciphertext_hash) = 32 THEN c.seal_ciphertext_hash END AS seal_ciphertext_hash,
            c.seal_retain_until, c.seal_verified_at,
            CASE WHEN complaint_bytes_match(c.seal_verification_bytes, c.seal_verification_hash, 65536) THEN c.seal_verification_bytes END AS seal_verification_bytes,
            CASE WHEN octet_length(c.seal_verification_hash) = 32 THEN c.seal_verification_hash END AS seal_verification_hash,
            c.checkpoint_generation, c.checkpoint_fencing_token, c.checkpoint_catalog_generation,
            CASE WHEN octet_length(c.checkpoint_catalog_hash)=32 THEN c.checkpoint_catalog_hash END AS checkpoint_catalog_hash,
            c.checkpoint_writer_generation, c.checkpoint_cutoff_epoch,
            CASE WHEN octet_length(c.checkpoint_configuration_hash)=32 THEN c.checkpoint_configuration_hash END AS checkpoint_configuration_hash,
            c.checkpoint_database_identity,
            c.checkpoint_restore_identity, c.checkpoint_schema, c.checkpoint_started_at, c.checkpoint_completed_at,
            c.checkpoint_object_count, c.checkpoint_byte_count, c.checkpoint_result,
            CASE WHEN complaint_bytes_match(c.checkpoint_bytes, c.checkpoint_hash, 65536) THEN c.checkpoint_bytes END AS checkpoint_bytes,
            CASE WHEN octet_length(c.checkpoint_hash) = 32 THEN c.checkpoint_hash END AS checkpoint_hash,
            sha256(convert_to((to_jsonb(c) || jsonb_build_object('row_xmin',c.xmin::text))::text,'UTF8')) AS control_fingerprint,
            sha256(convert_to((to_jsonb(c) - ARRAY['lease_owner','lease_token','lease_expires_at','updated_at'])::text,'UTF8')) AS content_fingerprint,
            sha256(convert_to((to_jsonb(g) || jsonb_build_object('row_xmin',g.xmin::text))::text,'UTF8')) AS global_fingerprint,
            sha256(convert_to((to_jsonb(r) || jsonb_build_object('row_xmin',r.xmin::text))::text,'UTF8')) AS run_fingerprint,
            CASE WHEN c.rotation_sequence = 1 THEN (SELECT sha256(convert_to((to_jsonb(i) || jsonb_build_object('row_xmin',i.xmin::text))::text,'UTF8'))
                FROM complaint_test_active_seal_intents i WHERE i.data_scope_id = e.scope AND i.operation_token = c.rotation_id)
            ELSE (SELECT sha256(convert_to((to_jsonb(i) || jsonb_build_object('row_xmin',i.xmin::text))::text,'UTF8'))
                FROM complaint_test_active_recurrent_seal_intents i WHERE i.data_scope_id = e.scope AND i.operation_token = c.rotation_id) END AS slot_fingerprint
        FROM e CROSS JOIN d CROSS JOIN b CROSS JOIN j CROSS JOIN s
        LEFT JOIN complaint_test_runs r ON r.data_scope_id = e.scope
        LEFT JOIN complaint_journal_control c ON c.data_scope_id = d.scope
        LEFT JOIN complaint_journal_control g ON g.data_scope_id = $GLOBAL LIMIT 2
    """.trimIndent()
    private val header = """
        i.schema_version, i.operation_token, i.data_scope_id, i.test_only, i.run_created_at,
        i.implementation_schema, i.desired_generation, i.configuration_hash, i.journal_configuration_hash,
        i.database_identity, i.restore_identity, i.writer_generation, i.activation_catalog_generation,
        i.activation_catalog_hash, i.accepted_catalog_generation, i.accepted_catalog_hash, i.trust_bundle_hash,
        i.catalog_writer_generation, i.rotation_sequence, i.epoch_start, i.epoch_end, i.request_owner, i.request_token,
        i.requested_at, i.charged_storage_bytes, i.capture_owner, i.capture_token, i.captured_at, i.epoch_after,
        CASE WHEN octet_length(i.state) BETWEEN 1 AND 16 THEN i.state END AS state,
        sha256(convert_to((to_jsonb(i) || jsonb_build_object('row_xmin',i.xmin::text))::text,'UTF8')) AS slot_fingerprint,
        (i.schema_version = 1 AND i.test_only AND i.state IN ('RESERVED','CANONICAL','WIRE_FROZEN')
            AND octet_length(to_jsonb(i)::text) BETWEEN 1 AND 524288
            AND isfinite(i.run_created_at) AND i.run_created_at >= '1970-01-01T00:00:00Z'::timestamptz AND i.run_created_at < '10000-01-01T00:00:00Z'::timestamptz
            AND isfinite(i.requested_at) AND i.requested_at >= '1970-01-01T00:00:00Z'::timestamptz AND i.requested_at < '10000-01-01T00:00:00Z'::timestamptz
            AND complaint_finite_times(i.captured_at)
            AND (i.state<>'RESERVED' OR (i.object_id IS NULL AND i.object_key IS NULL AND i.routing_key_id IS NULL
                AND i.preparing_fencing_token IS NULL AND i.seal_encoding_hash IS NULL AND i.canonicalizer IS NULL
                AND i.canonical_bytes IS NULL AND i.canonical_hash IS NULL AND i.retention_floor IS NULL AND i.created_at IS NULL
                AND i.wire_bytes IS NULL AND i.wire_hash IS NULL AND i.checksum_sha256 IS NULL AND i.content_type IS NULL
                AND i.object_lock_mode IS NULL AND i.retain_until IS NULL AND i.metadata_bytes IS NULL AND i.metadata_hash IS NULL AND i.frozen_at IS NULL))) IS TRUE AS valid
    """.trimIndent()
    val initialHeaders = "SELECT $header, NULL::uuid AS predecessor_operation_token, NULL::bytea AS predecessor_checkpoint_hash, NULL::bytea AS predecessor_history_hash FROM complaint_test_active_seal_intents i WHERE i.data_scope_id = ?::uuid LIMIT 2 FOR UPDATE"
    val recurrentHeaders = "SELECT $header, i.predecessor_operation_token, i.predecessor_checkpoint_hash, i.predecessor_history_hash FROM complaint_test_active_recurrent_seal_intents i WHERE i.data_scope_id = ?::uuid ORDER BY i.rotation_sequence LIMIT 14 FOR UPDATE"
    val initialPayload = TestActiveOrdinarySealSqlV1.slot
    val recurrentPayload = TestActiveOrdinarySealSqlV1.slot.replace("FROM complaint_test_active_seal_intents i", "FROM complaint_test_active_recurrent_seal_intents i")
    val history = """
        SELECT schema_version, data_scope_id, test_only, ordinal, operation_token, source, initial_seal_token, recurrent_seal_token,
            CASE WHEN complaint_bytes_match(entry_bytes,entry_hash,16384) THEN entry_bytes END AS entry_bytes, entry_hash,
            CASE WHEN octet_length(object_version) BETWEEN 1 AND 1024 THEN object_version END AS object_version,
            CASE WHEN complaint_bytes_match(verification_bytes,verification_hash,65536) THEN verification_bytes END AS verification_bytes, verification_hash,
            CASE WHEN complaint_bytes_match(checkpoint_bytes,checkpoint_hash,65536) THEN checkpoint_bytes END AS checkpoint_bytes, checkpoint_hash,
            archived_at, charged_storage_bytes, checkpointed_at,
            (schema_version = 1 AND test_only AND charged_storage_bytes = 2097152
                AND complaint_bytes_match(entry_bytes,entry_hash,16384) AND complaint_bytes_match(verification_bytes,verification_hash,65536)
                AND (checkpoint_bytes IS NULL OR complaint_bytes_match(checkpoint_bytes,checkpoint_hash,65536))) IS TRUE AS valid
        FROM complaint_test_active_checkpoint_history WHERE data_scope_id = ?::uuid ORDER BY ordinal LIMIT 15 FOR UPDATE
    """.trimIndent()
    val insertHistory = """
        INSERT INTO complaint_test_active_checkpoint_history (schema_version,data_scope_id,test_only,ordinal,operation_token,source,
            initial_seal_token,recurrent_seal_token,entry_bytes,entry_hash,object_version,verification_bytes,verification_hash,
            checkpoint_bytes,checkpoint_hash,archived_at,charged_storage_bytes,checkpointed_at)
        VALUES (1,?::uuid,true,?,?::uuid,?,?::uuid,?::uuid,?,?,?,?,?,?,?,?,2097152,?)
    """.trimIndent()
    val acquire = TestActiveFirstCutSqlV1.acquireLease + "\nRETURNING c.lease_token"
    val renew = TestActiveOrdinarySealSqlV1.renew
    val lease = TestActiveOrdinarySealSqlV1.lease
    val request = """
        WITH s AS MATERIALIZED (SELECT clock_timestamp() AS at)
        UPDATE complaint_journal_control c SET scan_requested=true,rotation_sequence=c.rotation_sequence+1,rotation_id=?::uuid,
            rotation_state='REQUESTED',rotation_epoch_before=c.publication_epoch,rotation_request_owner=c.lease_owner,
            rotation_request_token=c.lease_token,rotation_requested_at=s.at,rotation_capture_owner=NULL,rotation_capture_token=NULL,
            rotation_captured_at=NULL,rotation_epoch_after=NULL,
            seal_state=NULL,seal_epoch=NULL,seal_writer_generation=NULL,seal_operation_token=NULL,seal_object_key=NULL,
            seal_bytes=NULL,seal_hash=NULL,seal_object_version=NULL,seal_ciphertext_hash=NULL,seal_retain_until=NULL,seal_verified_at=NULL,
            seal_verification_bytes=NULL,seal_verification_hash=NULL,
            checkpoint_generation=NULL,checkpoint_fencing_token=NULL,checkpoint_catalog_generation=NULL,checkpoint_catalog_hash=NULL,
            checkpoint_writer_generation=NULL,checkpoint_cutoff_epoch=NULL,checkpoint_configuration_hash=NULL,checkpoint_database_identity=NULL,
            checkpoint_restore_identity=NULL,checkpoint_schema=NULL,checkpoint_started_at=NULL,checkpoint_completed_at=NULL,
            checkpoint_object_count=NULL,checkpoint_byte_count=NULL,checkpoint_result=NULL,checkpoint_bytes=NULL,checkpoint_hash=NULL, updated_at=s.at FROM s
        WHERE c.data_scope_id=?::uuid AND c.test_only AND c.rotation_sequence BETWEEN 1 AND 13 AND c.rotation_state='CAPTURED'
            AND c.publication_epoch BETWEEN 2 AND 9223372036854775806 AND NOT c.scan_requested
            AND c.seal_state='SEAL_VERIFIED' AND c.checkpoint_result='SUCCESS'
            AND c.lease_owner=?::uuid AND c.lease_token=? AND c.lease_expires_at=? AND c.lease_expires_at>s.at AND s.at>=c.updated_at
            AND sha256(convert_to((to_jsonb(c)||jsonb_build_object('row_xmin',c.xmin::text))::text,'UTF8'))=?
            AND EXISTS (SELECT 1 FROM complaint_test_active_checkpoint_history h WHERE h.data_scope_id=c.data_scope_id
                AND h.operation_token=c.rotation_id AND h.ordinal=c.rotation_sequence AND h.checkpoint_bytes=c.checkpoint_bytes
                AND h.checkpoint_hash=c.checkpoint_hash AND h.checkpointed_at=c.checkpoint_completed_at)
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_runs x WHERE x.data_scope_id=c.data_scope_id)
    """.trimIndent()
    val insertSlot = """
        $expected
        INSERT INTO complaint_test_active_recurrent_seal_intents (predecessor_operation_token,predecessor_checkpoint_hash,predecessor_history_hash,
            schema_version,operation_token,data_scope_id,test_only,run_created_at,implementation_schema,desired_generation,configuration_hash,
            journal_configuration_hash,database_identity,restore_identity,writer_generation,activation_catalog_generation,activation_catalog_hash,
            accepted_catalog_generation,accepted_catalog_hash,trust_bundle_hash,catalog_writer_generation,rotation_sequence,epoch_start,epoch_end,
            request_owner,request_token,requested_at,charged_storage_bytes,state)
        SELECT ?::uuid,?::bytea,?::bytea,1,c.rotation_id,e.scope,true,e.created_at,d.implementation_schema,d.desired_generation,d.configuration_hash,j.journal_hash,
            d.database_identity,d.restore_identity,d.event_writer,e.generation,e.activation_hash,d.generation,d.activation_hash,d.trust_hash,d.catalog_writer,
            c.rotation_sequence,c.rotation_epoch_before,c.rotation_epoch_before,c.rotation_request_owner,c.rotation_request_token,c.rotation_requested_at,2097152,'RESERVED'
        FROM e CROSS JOIN d CROSS JOIN b CROSS JOIN j CROSS JOIN s JOIN complaint_journal_control c ON c.data_scope_id=d.scope
        WHERE e.scope=d.scope AND c.test_only AND c.rotation_state='REQUESTED' AND c.rotation_id=?::uuid
    """.trimIndent()
    val capture = TestActiveFirstCutSqlV1.capture
        .replace("c.rotation_sequence = 1 AND c.rotation_state = 'REQUESTED' AND c.rotation_epoch_before = 1 AND c.publication_epoch = 1", "c.rotation_sequence BETWEEN 2 AND 14 AND c.rotation_state = 'REQUESTED' AND c.rotation_epoch_before = c.publication_epoch AND c.publication_epoch BETWEEN 2 AND 9223372036854775806")
    val captureSlot = TestActiveFirstCutSqlV1.captureSlot.replace("complaint_test_active_seal_intents", "complaint_test_active_recurrent_seal_intents")
    val canonical = TestActiveOrdinarySealSqlV1.canonical.replace("complaint_test_active_seal_intents", "complaint_test_active_recurrent_seal_intents")
    val freeze = TestActiveOrdinarySealSqlV1.freeze.replace("complaint_test_active_seal_intents", "complaint_test_active_recurrent_seal_intents")
    val prepareControl = TestActiveOrdinarySealSqlV1.prepareControl.replace("rotation_sequence = 1", "rotation_sequence BETWEEN 2 AND 14")
    val verifyControl = TestActiveOrdinarySealSqlV1.verifyControl
    val releaseLease = """
        WITH s AS MATERIALIZED (SELECT clock_timestamp() AS at)
        UPDATE complaint_journal_control c SET lease_owner=NULL,lease_expires_at=NULL,updated_at=s.at FROM s
        WHERE c.data_scope_id=?::uuid AND c.test_only AND c.lease_owner=?::uuid AND c.lease_token=?
            AND c.lease_expires_at>s.at AND s.at>=c.updated_at AND c.rotation_state='CAPTURED'
    """.trimIndent()
    val success = TestActiveInitialCheckpointSqlV1.success.replace("c.publication_epoch = 2", "c.publication_epoch = c.seal_epoch + 1 AND c.rotation_sequence BETWEEN 2 AND 14")
    val completed = TestActiveInitialCheckpointSqlV1.completed
    val checkpointHistory = """
        UPDATE complaint_test_active_checkpoint_history h SET checkpoint_bytes=c.checkpoint_bytes,checkpoint_hash=c.checkpoint_hash,checkpointed_at=c.checkpoint_completed_at
        FROM complaint_journal_control c WHERE h.data_scope_id=?::uuid AND h.operation_token=?::uuid AND h.checkpoint_bytes IS NULL
            AND c.data_scope_id=h.data_scope_id AND c.test_only AND c.seal_operation_token=h.operation_token AND c.checkpoint_result='SUCCESS'
            AND c.lease_owner IS NULL AND c.lease_token=? AND c.checkpoint_fencing_token=c.lease_token
    """.trimIndent()
}
