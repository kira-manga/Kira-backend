package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableBindingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import java.sql.ResultSet
import java.time.Instant

/** Existing bounded frozen-row materialization only. Caller checks original/kind/ordinal; no proof is issued. */
internal object TestTerminalSqlRowV1 {
    fun restore(row: ResultSet, b: TestTerminalDurableBindingV1, now: Instant): TestTerminalDurableRowV1 {
        val bytes = checkNotNull(row.getBytes("canonical_bytes"))
        val canonical = try { TestTerminalDurableRowV1.canonical(b, bytes) } finally { bytes.fill(0) }
        try {
            requireManifest(canonical.canonicalSha256 == TestOrdinaryDrainRowsV1.hash(row, "canonical_hash"))
            if (row.getString("state") == "CANONICAL") return canonical
            requireManifest(row.getString("state") == "WIRE_FROZEN")
            val wire = checkNotNull(row.getBytes("wire_bytes"))
            val frozen = try { TestTerminalDurableRowV1.frozen(canonical, wire, checkNotNull(row.getTimestamp("retain_until")).toInstant(),
                checkNotNull(row.getTimestamp("frozen_at")).toInstant()) } finally { wire.fill(0) }
            try {
                requireManifest(frozen.frozenAt!! <= now && frozen.retainUntil!! > now && frozen.wireSha256 == TestOrdinaryDrainRowsV1.hash(row, "wire_hash") &&
                    frozen.metadataSha256 == TestOrdinaryDrainRowsV1.hash(row, "metadata_hash") && frozen.checksumSha256 == row.getString("checksum_sha256") &&
                    frozen.contentType == row.getString("content_type") && frozen.objectLockMode == row.getString("object_lock_mode"))
                val expected = frozen.metadataBytes(); val actual = row.getBytes("metadata_bytes")
                try { requireManifest(expected.contentEquals(actual)) } finally { expected?.fill(0); actual?.fill(0) }
                canonical.close()
                return frozen
            } catch (problem: Throwable) { frozen.close(); throw problem }
        } catch (problem: Throwable) { canonical.close(); throw problem }
    }
}
