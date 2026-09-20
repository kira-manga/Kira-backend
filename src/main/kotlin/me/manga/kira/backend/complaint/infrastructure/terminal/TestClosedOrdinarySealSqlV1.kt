package me.manga.kira.backend.complaint.infrastructure.terminal

/** Separate SEALED paid-closeout successor. Never clears or overwrites a preceding control seal/checkpoint. */
internal object TestClosedOrdinarySealSqlV1 {
    val verifyRun = """
        UPDATE complaint_test_runs SET final_ordinary_epoch = ?, terminal_seal_epoch = ?, generation_seal_count = ?,
            generation_seal_root = ?, seal_set_bytes = ?, seal_set_hash = ?
        WHERE data_scope_id = ?::uuid AND test_only AND state = 'SEALED' AND accounting_version = 1
            AND unused_reserve = ?::bigint[] AND permanent_denial_bytes = ? AND permanent_denial_hash = ?
            AND final_ordinary_epoch IS NULL AND terminal_seal_epoch IS NULL AND generation_seal_count IS NULL
            AND generation_seal_root IS NULL AND seal_set_bytes IS NULL AND seal_set_hash IS NULL
    """.trimIndent()
}
