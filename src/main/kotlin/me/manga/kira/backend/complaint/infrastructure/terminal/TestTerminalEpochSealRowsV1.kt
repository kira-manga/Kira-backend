package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableBindingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableKindV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRoleV1
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** Successor-specific bounded comparisons. None of these rows manufactures a successful original. */
internal object TestTerminalEpochSealRowsV1 {
    class Control(row: ResultSet, original: TestRunTerminalEpochSealV1) {
        val epoch = row.getLong("publication_epoch")
        init {
            val preceding = original.control
            requireTerminalSeal(TestOrdinaryDrainRowsV1.boolean(row, "valid") && epoch in original.epoch..original.afterEpoch &&
                row.getLong("rotation_sequence") == preceding.sequence && !preceding.needsCapture &&
                row.getLong("rotation_epoch_before") == preceding.cutoff && row.getObject("rotation_id", UUID::class.java) == preceding.captureId &&
                row.getLong("rotation_capture_token") == preceding.captureFence && row.getTimestamp("rotation_captured_at")?.toInstant() == preceding.capturedAt &&
                row.getLong("seal_epoch") == preceding.previousSealEpoch && TestOrdinaryDrainRowsV1.hash(row, "history_hash") == preceding.historyHash)
        }
    }

    fun sidecar(row: ResultSet, original: TestRunTerminalEpochSealV1, sealedAt: Instant, now: Instant): TestTerminalDurableRowV1 {
        requireTerminalSeal(TestOrdinaryDrainRowsV1.boolean(row, "valid"))
        val run = TestTerminalRunContextV1(row.getObject("data_scope_id", UUID::class.java).toString(), row.getLong("activation_catalog_generation"),
            TestOrdinaryDrainRowsV1.hash(row, "activation_catalog_hash"), TestOrdinaryDrainRowsV1.hash(row, "configuration_hash"), TestOrdinaryDrainRowsV1.hash(row, "terminal_encoding_hash"))
        requireTerminalSeal(run == original.runContext && row.getString("object_kind") == "EPOCH_SEAL" && row.getInt("object_ordinal") == 1)
        val b = TestTerminalDurableBindingV1(row.getObject("operation_token", UUID::class.java).toString(), run,
            TestOrdinaryDrainRowsV1.hash(row, "journal_configuration_hash"), TestTerminalDurableKindV1.EPOCH_SEAL, 1,
            checkNotNull(row.getString("object_id")), checkNotNull(row.getString("object_key")), checkNotNull(row.getString("routing_key_id")),
            row.getObject("writer_generation", UUID::class.java).toString(), row.getLong("epoch_start"), row.getLong("epoch_end"), row.getLong("preparing_fencing_token"),
            checkNotNull(row.getTimestamp("retention_floor")).toInstant(), checkNotNull(row.getTimestamp("created_at")).toInstant())
        requireTerminalSeal(b.operationToken == original.intentId.toString() && b.writerGeneration == original.writer &&
            b.journalConfigurationSha256 == original.routing.journalConfiguration.sha256 && b.epochStartInclusive == original.epoch &&
            b.epochEndInclusive == original.epoch && b.preparingFencingToken == original.leaseToken && b.createdAt >= sealedAt && b.createdAt <= now)
        original.requireRetentionFloor(b)
        val bytes = checkNotNull(row.getBytes("canonical_bytes"))
        val canonical = try { TestTerminalDurableRowV1.canonical(b, bytes) } finally { bytes.fill(0) }
        try {
            requireTerminalSeal(canonical.canonicalSha256 == TestOrdinaryDrainRowsV1.hash(row, "canonical_hash"))
            if (row.getString("state") == "CANONICAL") return canonical
            requireTerminalSeal(row.getString("state") == "WIRE_FROZEN")
            val wire = checkNotNull(row.getBytes("wire_bytes"))
            val frozen = try { TestTerminalDurableRowV1.frozen(canonical, wire, checkNotNull(row.getTimestamp("retain_until")).toInstant(),
                checkNotNull(row.getTimestamp("frozen_at")).toInstant()) } finally { wire.fill(0) }
            try {
                requireTerminalSeal(checkNotNull(frozen.frozenAt) <= now && checkNotNull(frozen.retainUntil) > now &&
                    frozen.wireSha256 == TestOrdinaryDrainRowsV1.hash(row, "wire_hash") && frozen.metadataSha256 == TestOrdinaryDrainRowsV1.hash(row, "metadata_hash") &&
                    frozen.checksumSha256 == row.getString("checksum_sha256") && frozen.contentType == row.getString("content_type") && frozen.objectLockMode == row.getString("object_lock_mode"))
                val expected = frozen.metadataBytes(); val actual = row.getBytes("metadata_bytes")
                try { requireTerminalSeal(expected.contentEquals(actual)) } finally { expected?.fill(0); actual?.fill(0) }
                canonical.close()
                return frozen
            } catch (problem: Throwable) { frozen.close(); throw problem }
        } catch (problem: Throwable) { canonical.close(); throw problem }
    }

    fun requireReference(row: TestTerminalDurableRowV1, value: TestTerminalSealRefV1, original: TestRunTerminalEpochSealV1) {
        requireTerminalSeal(value.role === TestTerminalSealRoleV1.TERMINAL && value.writerGeneration == original.writer &&
            value.epochStartInclusive == original.epoch && value.epochEndInclusive == original.epoch &&
            value.precedingSealSha256 == original.ordinarySeal.objectRef.canonicalSha256 && value.sealId == row.binding.objectId &&
            value.objectRef.objectKey == row.binding.objectKey && value.objectRef.canonicalSha256 == row.canonicalSha256 && value.objectRef.ciphertextSha256 == row.wireSha256)
    }
}
