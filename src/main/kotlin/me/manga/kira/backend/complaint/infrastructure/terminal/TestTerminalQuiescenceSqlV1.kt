package me.manga.kira.backend.complaint.infrastructure.terminal

/** Existing V14 paid serial rows; no new schema, ordinary reader widening or publication mutation. */
internal object TestTerminalQuiescenceSqlV1 {
    val run = TestTerminalEpochSealSqlV1.run.replace("r.generation_seal_count BETWEEN 1 AND 2", "r.generation_seal_count = 2")
    val control = TestTerminalEpochSealSqlV1.control
    val runWithActiveHistory = TestTerminalEpochSealSqlV1.runWithActiveHistory.replace("r.generation_seal_count BETWEEN 2 AND 3", "r.generation_seal_count = 3")
    val controlWithActiveHistory = TestTerminalEpochSealSqlV1.controlWithActiveHistory
    val relation = TestTerminalEpochSealSqlV1.relation
        .replace("p.event_kind = 'INSTALLATION_MANIFEST' AND p.target_count BETWEEN 1 AND 500 AND p.state IN ('PREPARED', 'VERIFIED')",
            "p.event_kind = 'INSTALLATION_MANIFEST' AND p.target_count BETWEEN 1 AND 500 AND p.state = 'VERIFIED'")
        .replace("i.object_ordinal = 1 AND i.state IN ('CANONICAL', 'WIRE_FROZEN')", "i.object_ordinal = 1 AND i.state = 'WIRE_FROZEN'")
    val relationWithActiveHistory = TestTerminalEpochSealSqlV1.relationWithActiveHistory
        .replace("p.event_kind = 'INSTALLATION_MANIFEST' AND p.target_count BETWEEN 1 AND 500 AND p.state IN ('PREPARED', 'VERIFIED')",
            "p.event_kind = 'INSTALLATION_MANIFEST' AND p.target_count BETWEEN 1 AND 500 AND p.state = 'VERIFIED'")
        .replace("i.object_ordinal = 1 AND i.state IN ('CANONICAL', 'WIRE_FROZEN')", "i.object_ordinal = 1 AND i.state = 'WIRE_FROZEN'")
    val insertEntry = TestOrdinaryDrainSqlV1.insertEntry.replace("'PENDING'", "'VERIFIED_ONLY'")
    // EPOCH_SEAL deliberately has NULL event_id in V14. No fake seal event id or semantic-hash overloading.
    val deleteEntry = TestOrdinaryDrainSqlV1.deleteEntry.replace("event_id = ?", "event_id IS NOT DISTINCT FROM ?")
}
