package me.manga.kira.backend.complaint.infrastructure.terminal

/** Separate SEALED paid-closeout successor. Never clears or overwrites a preceding control seal/checkpoint. */
internal object TestClosedOrdinarySealSqlV1 {
    val insertActiveTail = """
        INSERT INTO complaint_test_terminal_intents (schema_version, operation_token, data_scope_id, test_only, object_kind, object_ordinal,
            object_id, object_key, routing_key_id, writer_generation, epoch_start, epoch_end, preparing_fencing_token,
            activation_catalog_generation, activation_catalog_hash, configuration_hash, journal_configuration_hash, terminal_encoding_hash,
            publication_ref, canonicalizer, canonical_bytes, canonical_hash, retention_floor, created_at, state)
        VALUES (1, ?::uuid, ?::uuid, true, 'EPOCH_SEAL', 0, ?, ?, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 'kcj-1', ?, ?, ?, ?, 'CANONICAL')
    """.trimIndent()
    val verifyRun = """
        UPDATE complaint_test_runs SET final_ordinary_epoch = ?, terminal_seal_epoch = ?, generation_seal_count = ?,
            generation_seal_root = ?, seal_set_bytes = ?, seal_set_hash = ?
        WHERE data_scope_id = ?::uuid AND test_only AND state = 'SEALED' AND accounting_version = 1
            AND unused_reserve = ?::bigint[] AND permanent_denial_bytes = ? AND permanent_denial_hash = ?
            AND final_ordinary_epoch IS NULL AND terminal_seal_epoch IS NULL AND generation_seal_count IS NULL
            AND generation_seal_root IS NULL AND seal_set_bytes IS NULL AND seal_set_hash IS NULL
    """.trimIndent()
}
