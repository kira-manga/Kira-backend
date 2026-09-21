package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInventoryWitnessV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.requireJournalVersion
import me.manga.kira.backend.security.TestPostTerminalInventoryEntryV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** Comparison-only rows of THIS paid serial pair, not native observations or adoptable completion. */
internal object TestTerminalQuiescenceRowsV1 {
    class Scan(row: ResultSet, original: TestRunTerminalQuiescenceV1) {
        val id: UUID = checkNotNull(row.getObject("scan_id", UUID::class.java))
        val pass = row.getInt("pass")
        val state: String = checkNotNull(row.getString("state"))
        val count = row.getLong("entry_count")
        val framedBytes = row.getLong("entry_bytes")
        val startedAt: Instant = checkNotNull(row.getTimestamp("started_at")).toInstant()
        val finishedAt: Instant? = row.getTimestamp("finished_at")?.toInstant()
        val root: String? = row.getBytes("manifest_hash")?.let { bytes ->
            try { requireQuiescence(bytes.size == 32); java.util.HexFormat.of().formatHex(bytes) } finally { bytes.fill(0) }
        }
        init {
            val declaration = original.routing.journalConfiguration.declaration()
            requireQuiescence(id == original.scanId && pass in 1..2 && row.getLong("fencing_token") == original.leaseToken &&
                row.getObject("data_scope_id", UUID::class.java) == original.scope && TestOrdinaryDrainRowsV1.boolean(row, "test_only") &&
                row.getObject("restore_identity", UUID::class.java).toString() == declaration.writer.restoreIdentity &&
                row.getLong("desired_generation") == original.registration.process.desiredGeneration &&
                row.getObject("writer_generation", UUID::class.java).toString() == original.writer && row.getLong("cutoff_epoch") == original.epoch &&
                row.getLong("maximum_entries") == original.maximumVersions && row.getLong("maximum_bytes") == original.maximumFramedBytes &&
                count in 0..original.targets.size.toLong() && framedBytes in 0..original.maximumFramedBytes &&
                startedAt.epochSecond in 0..253_402_300_799L && (finishedAt == null || finishedAt >= startedAt) &&
                (state == "SCANNING" && root == null && finishedAt == null || state == "COMPLETE" && root != null && finishedAt != null))
        }
        fun requireSummary(summary: Summary) {
            requireQuiescence(state == "COMPLETE" && count == summary.witness.versionCount && root == summary.witness.sha256 &&
                framedBytes == summary.framedBytes && startedAt.epochSecond == summary.witness.startedAtEpochSecond &&
                finishedAt?.epochSecond == summary.witness.completedAtEpochSecond)
        }
    }

    data class Entry(val key: String, val version: String, val ciphertext: String, val canonical: String,
        val eventId: String?, val kind: TestTerminalCodecKindV1, val writer: String, val epoch: Long, val ciphertextBytes: Long) {
        val locator: Pair<String, String> get() = key to version
        fun requireNative(value: TestPostTerminalInventoryEntryV1) {
            requireQuiescence(key == value.objectRef.objectKey && version == value.objectRef.objectVersion && ciphertext == value.objectRef.ciphertextSha256 &&
                canonical == value.objectRef.canonicalSha256 && eventId == value.eventId && kind === value.kind && writer == value.writerGeneration &&
                epoch == value.epochEndInclusive && ciphertextBytes == value.ciphertextByteCount)
        }
    }
    fun entry(row: ResultSet, original: TestRunTerminalQuiescenceV1, pass: Int): Entry {
        requireQuiescence(row.getObject("scan_id", UUID::class.java) == original.scanId && row.getInt("pass") == pass && pass in 1..2 &&
            row.getObject("data_scope_id", UUID::class.java) == original.scope && TestOrdinaryDrainRowsV1.boolean(row, "test_only") &&
            row.getString("replay_state") == "VERIFIED_ONLY")
        val value = Entry(checkNotNull(row.getString("object_key")), requireJournalVersion(row.getString("object_version")),
            TestOrdinaryDrainRowsV1.hash(row, "ciphertext_hash"), TestOrdinaryDrainRowsV1.hash(row, "semantic_hash"), row.getString("event_id"),
            TestTerminalCodecKindV1.valueOf(checkNotNull(row.getString("event_kind"))), row.getObject("writer_generation", UUID::class.java).toString(),
            row.getLong("journal_epoch"), row.getLong("entry_bytes"))
        val expected = original.targets.single { it.objectRef.objectKey == value.key }
        requireQuiescence(value.version == expected.objectRef.objectVersion && value.ciphertext == expected.objectRef.ciphertextSha256 &&
            value.canonical == expected.objectRef.canonicalSha256 && value.kind === expected.kind && value.writer == original.writer && value.epoch == expected.endEpoch &&
            value.eventId == (if (expected.kind === TestTerminalCodecKindV1.EPOCH_SEAL) null else expected.id) &&
            value.ciphertextBytes in 1..original.routing.journalConfiguration.declaration().limits.decoder.maximumEnvelopeBytes.toLong())
        return value
    }
    data class Summary(val witness: TestTerminalInventoryWitnessV1, val framedBytes: Long, val entryFramedBytes: Long)
}
