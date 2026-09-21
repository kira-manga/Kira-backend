package me.manga.kira.backend.complaint.infrastructure.reconciliation

/** Closed V31 paid staging; no caller SQL, terminal reserve, temporary table or queue inference. */
internal object TestActiveRecurrentScanSqlV1 {
    private const val PAGE = 32
    val runs = """
        SELECT s.scan_id,s.pass,s.data_scope_id,s.test_only,s.restore_identity,s.desired_generation,s.fencing_token,
            s.writer_generation,s.cutoff_epoch,s.maximum_entries,s.maximum_bytes,s.entry_count,s.entry_bytes,
            CASE WHEN octet_length(s.state) BETWEEN 1 AND 16 THEN s.state END AS state,
            CASE WHEN octet_length(s.manifest_hash)=32 THEN s.manifest_hash END AS manifest_hash,
            s.started_at,s.finished_at,s.active_initial_seal_token,s.active_initial_storage_bytes,
            s.active_recurrent_seal_token,s.active_recurrent_storage_bytes,
            (complaint_test_terminal_instant_valid(s.started_at) AND complaint_finite_times(s.finished_at)
                AND ((s.state='SCANNING' AND s.manifest_hash IS NULL AND s.finished_at IS NULL)
                    OR (s.state='COMPLETE' AND complaint_digest_valid(s.manifest_hash) AND s.finished_at IS NOT NULL)
                    OR (s.state='ABANDONED' AND s.manifest_hash IS NULL AND s.finished_at IS NOT NULL))) IS TRUE AS valid,
            sha256(convert_to(to_jsonb(s)::text,'UTF8')) AS fingerprint
        FROM complaint_journal_scan_runs s WHERE s.data_scope_id=?::uuid ORDER BY s.pass LIMIT 3 FOR UPDATE
    """.trimIndent()
    val supported = """
        WITH e AS (SELECT ?::uuid AS scope,?::uuid AS writer)
        SELECT (NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_retirements r,e WHERE r.data_scope_id=e.scope)
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_publications p,e WHERE p.data_scope_id=e.scope AND
                (NOT p.test_only OR p.writer_generation<>e.writer OR p.event_kind NOT IN ('OWNER_DELETE','OWNER_DELETE_ALL','ADMIN_DELETE','ADMIN_BATCH_DELETE')))
            AND NOT EXISTS (SELECT 1 FROM complaint_deletion_journal_applied a,e WHERE a.data_scope_id=e.scope AND
                (NOT a.test_only OR a.writer_generation<>e.writer OR a.event_kind NOT IN ('OWNER_DELETE','OWNER_DELETE_ALL','ADMIN_DELETE','ADMIN_BATCH_DELETE')))) AS valid
    """.trimIndent()
    private val marker = """
        (a.test_only AND a.data_scope_id=e.data_scope_id AND a.writer_generation=e.writer_generation
            AND a.journal_epoch=e.journal_epoch AND a.event_kind=e.event_kind AND a.event_id=e.event_id
            AND a.ciphertext_hash=e.ciphertext_hash AND isfinite(a.applied_at)
            AND a.applied_at>='1970-01-01Z'::timestamptz AND a.applied_at<=clock_timestamp()
            AND ((a.event_kind IN ('OWNER_DELETE','ADMIN_DELETE') AND a.target_count=1)
                OR (a.event_kind='OWNER_DELETE_ALL' AND a.target_count BETWEEN 0 AND 100)
                OR (a.event_kind='ADMIN_BATCH_DELETE' AND a.target_count BETWEEN 1 AND 50))
            AND (p.event_id IS NULL OR (p.test_only AND p.data_scope_id=e.data_scope_id AND p.writer_generation=e.writer_generation
                AND p.journal_epoch=e.journal_epoch AND p.event_kind=e.event_kind AND p.object_key=e.object_key
                AND p.object_version=e.object_version AND p.ciphertext_hash=e.ciphertext_hash AND p.semantic_hash=e.semantic_hash
                AND p.target_count=a.target_count AND p.state='APPLIED' AND isfinite(p.applied_at)
                AND p.applied_at>=a.applied_at AND p.applied_at>=p.verified_at AND p.applied_at<=clock_timestamp()))) IS TRUE
    """.trimIndent()
    private val columns = """
        e.scan_id,e.pass,e.data_scope_id,e.test_only,e.writer_generation,e.journal_epoch,e.entry_bytes,e.replay_state,
        CASE WHEN octet_length(e.object_key) BETWEEN 1 AND 1024 THEN e.object_key END AS object_key,
        CASE WHEN octet_length(e.object_version) BETWEEN 1 AND 1024 THEN e.object_version END AS object_version,
        CASE WHEN octet_length(e.ciphertext_hash)=32 THEN e.ciphertext_hash END AS ciphertext_hash,
        CASE WHEN octet_length(e.semantic_hash)=32 THEN e.semantic_hash END AS semantic_hash,
        CASE WHEN octet_length(e.event_id)=43 THEN e.event_id END AS event_id,
        CASE WHEN octet_length(e.event_kind) BETWEEN 1 AND 32 THEN e.event_kind END AS event_kind,
        a.object_key IS NOT NULL AS applied_present,$marker AS applied_valid,a.target_count AS applied_target_count,a.applied_at AS applied_at
    """.trimIndent()
    private val joins = """
        FROM complaint_journal_scan_entries e
        LEFT JOIN complaint_deletion_journal_applied a ON a.object_key=e.object_key AND a.object_version=e.object_version
        LEFT JOIN complaint_journal_publications p ON p.event_id=e.event_id
    """.trimIndent()
    val entries = """
        SELECT $columns $joins
        WHERE e.scan_id=?::uuid AND e.pass=? AND e.data_scope_id=?::uuid
            AND (e.object_key COLLATE "C",e.object_version COLLATE "C")>(?::text COLLATE "C",?::text COLLATE "C")
        ORDER BY e.object_key COLLATE "C",e.object_version COLLATE "C" LIMIT $PAGE FOR UPDATE OF e
    """.trimIndent()
    val pending = """
        SELECT $columns $joins WHERE e.scan_id=?::uuid AND e.pass=1 AND e.data_scope_id=?::uuid AND e.replay_state='PENDING'
        ORDER BY e.object_key COLLATE "C",e.object_version COLLATE "C" LIMIT 1 FOR UPDATE OF e
    """.trimIndent()
    val exact = """
        SELECT $columns $joins WHERE e.scan_id=?::uuid AND e.pass=? AND e.data_scope_id=?::uuid
            AND e.object_key=? AND e.object_version=? FOR UPDATE OF e
    """.trimIndent()
    // The actual authenticated native event supplies target_count and lastModified here, not a staging DTO.
    val nativeApplied = """
        SELECT (a.test_only AND a.data_scope_id=?::uuid AND a.writer_generation=?::uuid AND a.journal_epoch=?
            AND a.event_kind=? AND a.event_id=? AND a.ciphertext_hash=? AND a.target_count=?
            AND isfinite(a.applied_at) AND a.applied_at>=?::timestamptz AND a.applied_at<=clock_timestamp()) IS TRUE AS valid
        FROM complaint_deletion_journal_applied a WHERE a.object_key=? AND a.object_version=? LIMIT 2
    """.trimIndent()
    val insertRun = """
        INSERT INTO complaint_journal_scan_runs (scan_id,pass,data_scope_id,test_only,restore_identity,desired_generation,fencing_token,
            writer_generation,cutoff_epoch,maximum_entries,maximum_bytes,entry_count,entry_bytes,state,manifest_hash,started_at,finished_at,
            active_recurrent_seal_token,active_recurrent_storage_bytes)
        VALUES (?::uuid,?,?::uuid,true,?::uuid,?,?,?::uuid,?,?,?,0,0,'SCANNING',NULL,?,NULL,?::uuid,4416)
    """.trimIndent()
    val insertEntry = """
        INSERT INTO complaint_journal_scan_entries (scan_id,pass,data_scope_id,test_only,object_key,object_version,ciphertext_hash,semantic_hash,
            event_id,event_kind,writer_generation,journal_epoch,entry_bytes,replay_state)
        VALUES (?::uuid,?,?::uuid,true,?,?,?,?,?,?,?::uuid,?,?,?)
    """.trimIndent()
    val incrementRun = """
        UPDATE complaint_journal_scan_runs SET entry_count=entry_count+1,entry_bytes=entry_bytes+?
        WHERE scan_id=?::uuid AND pass=? AND data_scope_id=?::uuid AND test_only AND active_recurrent_seal_token=scan_id
            AND active_recurrent_storage_bytes=4416 AND fencing_token=? AND state='SCANNING'
            AND entry_count=? AND entry_bytes=? AND entry_count<maximum_entries AND ?<=maximum_bytes-entry_bytes
    """.trimIndent()
    val completeRun = """
        UPDATE complaint_journal_scan_runs SET state='COMPLETE',manifest_hash=?,finished_at=?
        WHERE scan_id=?::uuid AND pass=? AND data_scope_id=?::uuid AND test_only AND active_recurrent_seal_token=scan_id
            AND active_recurrent_storage_bytes=4416 AND fencing_token=? AND state='SCANNING'
            AND entry_count=? AND entry_bytes=? AND started_at=? AND ?::timestamptz>=started_at
    """.trimIndent()
    val markApplied = """
        UPDATE complaint_journal_scan_entries SET replay_state='APPLIED'
        WHERE scan_id=?::uuid AND pass=? AND data_scope_id=?::uuid AND test_only AND object_key=? AND object_version=?
            AND ciphertext_hash=? AND semantic_hash=? AND event_id=? AND event_kind=? AND writer_generation=?::uuid
            AND journal_epoch=? AND entry_bytes=? AND replay_state='PENDING'
    """.trimIndent()
    val pair = """
        WITH a AS (SELECT * FROM complaint_journal_scan_entries WHERE scan_id=?::uuid AND pass=1),
             b AS (SELECT * FROM complaint_journal_scan_entries WHERE scan_id=?::uuid AND pass=2)
        SELECT NOT EXISTS (SELECT 1 FROM a FULL JOIN b USING (object_key,object_version)
            WHERE a.scan_id IS NULL OR b.scan_id IS NULL OR
                ROW(a.data_scope_id,a.test_only,a.writer_generation,a.journal_epoch,a.entry_bytes,a.event_id,a.event_kind,a.ciphertext_hash,a.semantic_hash,a.replay_state)
                IS DISTINCT FROM ROW(b.data_scope_id,b.test_only,b.writer_generation,b.journal_epoch,b.entry_bytes,b.event_id,b.event_kind,b.ciphertext_hash,b.semantic_hash,b.replay_state)) AS valid
    """.trimIndent()
    val abandon = """
        UPDATE complaint_journal_scan_runs SET state='ABANDONED',manifest_hash=NULL,finished_at=?
        WHERE scan_id=?::uuid AND pass=? AND data_scope_id=?::uuid AND test_only AND active_recurrent_seal_token=scan_id
            AND state IN ('SCANNING','COMPLETE') AND sha256(convert_to(to_jsonb(complaint_journal_scan_runs)::text,'UTF8'))=?
    """.trimIndent()
    val deleteEntry = """
        DELETE FROM complaint_journal_scan_entries WHERE scan_id=?::uuid AND pass=? AND data_scope_id=?::uuid AND test_only
            AND object_key=? AND object_version=? AND ciphertext_hash=? AND semantic_hash=? AND event_id=? AND event_kind=?
            AND writer_generation=?::uuid AND journal_epoch=? AND entry_bytes=? AND replay_state=?
    """.trimIndent()
    val deleteRun = """
        DELETE FROM complaint_journal_scan_runs s WHERE s.scan_id=?::uuid AND s.pass=? AND s.data_scope_id=?::uuid AND s.test_only
            AND s.active_recurrent_seal_token=s.scan_id AND s.active_recurrent_storage_bytes=4416 AND s.state='ABANDONED'
            AND sha256(convert_to(to_jsonb(s)::text,'UTF8'))=?
            AND NOT EXISTS (SELECT 1 FROM complaint_journal_scan_entries e WHERE e.scan_id=s.scan_id AND e.pass=s.pass)
    """.trimIndent()
    val noEntries = "SELECT NOT EXISTS (SELECT 1 FROM complaint_journal_scan_entries WHERE scan_id=?::uuid AND pass=?) AS valid"
    val noRuns = "SELECT NOT EXISTS (SELECT 1 FROM complaint_journal_scan_runs WHERE data_scope_id=?::uuid) AS valid"
    // All exact applied versions in the sealed range, including foreign-scope aliases under its key range.
    // Higher current-epoch applications are deliberately outside this closed range.
    val appliedPage = """
        SELECT CASE WHEN octet_length(a.object_key) BETWEEN 1 AND 1024 THEN a.object_key END AS object_key,
            CASE WHEN octet_length(a.object_version) BETWEEN 1 AND 1024 THEN a.object_version END AS object_version,
            CASE WHEN octet_length(a.event_id)=43 THEN a.event_id END AS event_id,
            CASE WHEN octet_length(a.ciphertext_hash)=32 THEN a.ciphertext_hash END AS ciphertext_hash,
            a.writer_generation,a.journal_epoch,
            CASE WHEN octet_length(a.event_kind) BETWEEN 1 AND 32 THEN a.event_kind END AS event_kind,a.target_count,
            a.data_scope_id,a.test_only,a.applied_at,isfinite(a.applied_at) AS finite
        FROM complaint_deletion_journal_applied a
        WHERE ((a.data_scope_id=?::uuid AND a.journal_epoch<=?) OR
                (a.object_key COLLATE "C">=?::text COLLATE "C" AND a.object_key COLLATE "C"<?::text COLLATE "C"))
            AND (a.object_key COLLATE "C",a.object_version COLLATE "C")>(?::text COLLATE "C",?::text COLLATE "C")
        ORDER BY a.object_key COLLATE "C",a.object_version COLLATE "C" LIMIT $PAGE
    """.trimIndent()
}
