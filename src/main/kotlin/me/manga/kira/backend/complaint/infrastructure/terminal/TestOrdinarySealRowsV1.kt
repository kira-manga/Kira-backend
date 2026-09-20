package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableBindingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableKindV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import java.sql.ResultSet
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Bounded comparisons only. The original fixed operation, not these row values, owns persistence. */
internal object TestOrdinarySealRowsV1 {
    class Control(row: ResultSet) {
        val epoch = row.getLong("publication_epoch")
        val sequence = row.getLong("rotation_sequence")
        val token: UUID? = row.getObject("rotation_id", UUID::class.java)
        val cutoff = row.getLong("rotation_epoch_before")
        val captureFence = row.getLong("rotation_capture_token")
        val capturedAt: Instant? = row.getTimestamp("rotation_captured_at")?.toInstant()
        val sealState: String? = row.getString("seal_state")
        private val sealEpoch = row.getLong("seal_epoch")
        private val sealWriter: UUID? = row.getObject("seal_writer_generation", UUID::class.java)
        private val sealToken: UUID? = row.getObject("seal_operation_token", UUID::class.java)
        private val sealKey: String? = row.getString("seal_object_key")
        private val sealBytes: ByteArray? = row.getBytes("seal_bytes")
        private val sealHash: ByteArray? = row.getBytes("seal_hash")
        private val version: String? = row.getString("seal_object_version")
        private val wireHash: ByteArray? = row.getBytes("seal_ciphertext_hash")
        private val retainUntil: Instant? = row.getTimestamp("seal_retain_until")?.toInstant()
        private val verifiedAt: Instant? = row.getTimestamp("seal_verified_at")?.toInstant()
        private val proofBytes: ByteArray? = row.getBytes("seal_verification_bytes")
        private val proofHash: ByteArray? = row.getBytes("seal_verification_hash")
        init { requireOrdinarySeal(boolean(row, "valid") && epoch > 0 && sequence in 0..1) }

        fun requireSameCut(other: Control) {
            requireOrdinarySeal(sequence == 1L && other.sequence == 1L && token == other.token && cutoff == other.cutoff && epoch == other.epoch &&
                captureFence == other.captureFence && capturedAt == other.capturedAt)
        }
        fun requireUnverified() { requireOrdinarySeal(sealState != "SEAL_VERIFIED") }
        fun requireListedVersion(candidate: String?) {
            // An already verified immutable version cannot be replaced if a later LIST omits it.
            if (sealState == "SEAL_VERIFIED") requireOrdinarySeal(candidate != null && candidate == version)
        }
        fun requireSidecar(row: TestTerminalDurableRowV1?) {
            if (row == null) {
                requireOrdinarySeal(sealState == null && sealEpoch == 0L && sealWriter == null && sealToken == null && sealKey == null &&
                    sealBytes == null && sealHash == null && version == null && wireHash == null && retainUntil == null && verifiedAt == null && proofBytes == null && proofHash == null)
                return
            }
            val canonical = row.canonicalBytes()
            try { requireOrdinarySeal(sealBytes.contentEquals(canonical) && sealHash.contentEquals(hex(row.canonicalSha256))) } finally { canonical.fill(0) }
            requireOrdinarySeal(sealState in setOf("SEAL_PREPARED", "SEAL_VERIFIED") && sealEpoch == cutoff &&
                sealWriter.toString() == row.binding.writerGeneration && sealToken.toString() == row.binding.operationToken && sealToken == token && sealKey == row.binding.objectKey)
            if (sealState == "SEAL_PREPARED") requireOrdinarySeal(version == null && wireHash == null && retainUntil == null && verifiedAt == null && proofBytes == null && proofHash == null)
            else requireOrdinarySeal(row.wireSha256 != null && wireHash.contentEquals(hex(checkNotNull(row.wireSha256))) &&
                version != null && retainUntil != null && verifiedAt != null && proofBytes != null && proofHash.contentEquals(hex(Sha256.hex(proofBytes))))
        }
        fun requireProof(row: TestTerminalDurableRowV1, proof: TestOrdinarySealProofV1) {
            requireSidecar(row)
            requireOrdinarySeal(sealState == "SEAL_VERIFIED" && version == proof.version && retainUntil == proof.retainUntil)
            val expected = proof.canonicalBytes(row, checkNotNull(verifiedAt))
            try { requireOrdinarySeal(proofBytes.contentEquals(expected)) } finally { expected.fill(0) }
        }
    }

    fun sidecar(row: ResultSet, original: TestRunOrdinarySealV1, cut: Control): TestTerminalDurableRowV1 {
        requireOrdinarySeal(boolean(row, "valid"))
        val run = TestTerminalRunContextV1(row.getObject("data_scope_id", UUID::class.java).toString(), row.getLong("activation_catalog_generation"),
            hash(row, "activation_catalog_hash"), hash(row, "configuration_hash"), hash(row, "terminal_encoding_hash"))
        requireOrdinarySeal(run == original.runContext && row.getString("object_kind") == "EPOCH_SEAL" && row.getInt("object_ordinal") == 0)
        val binding = TestTerminalDurableBindingV1(row.getObject("operation_token", UUID::class.java).toString(), run,
            hash(row, "journal_configuration_hash"), TestTerminalDurableKindV1.EPOCH_SEAL, 0,
            checkNotNull(row.getString("object_id")), checkNotNull(row.getString("object_key")), checkNotNull(row.getString("routing_key_id")),
            row.getObject("writer_generation", UUID::class.java).toString(), row.getLong("epoch_start"), row.getLong("epoch_end"),
            row.getLong("preparing_fencing_token"), checkNotNull(row.getTimestamp("retention_floor")).toInstant(), checkNotNull(row.getTimestamp("created_at")).toInstant())
        requireOrdinarySeal(binding.operationToken == cut.token.toString() && binding.epochStartInclusive == 1L && binding.epochEndInclusive == cut.cutoff &&
            binding.writerGeneration == original.writer && binding.journalConfigurationSha256 == original.routing.journalConfiguration.sha256 &&
            binding.preparingFencingToken >= cut.captureFence && binding.preparingFencingToken <= original.leaseToken &&
            !binding.createdAt.isBefore(checkNotNull(cut.capturedAt)))
        val bytes = checkNotNull(row.getBytes("canonical_bytes"))
        val canonical = try { TestTerminalDurableRowV1.canonical(binding, bytes) } finally { bytes.fill(0) }
        try {
            requireOrdinarySeal(canonical.canonicalSha256 == hash(row, "canonical_hash"))
            if (row.getString("state") == "CANONICAL") return canonical
            requireOrdinarySeal(row.getString("state") == "WIRE_FROZEN")
            val wire = checkNotNull(row.getBytes("wire_bytes"))
            val frozen = try { TestTerminalDurableRowV1.frozen(canonical, wire, checkNotNull(row.getTimestamp("retain_until")).toInstant(),
                checkNotNull(row.getTimestamp("frozen_at")).toInstant()) } finally { wire.fill(0) }
            try {
                requireOrdinarySeal(frozen.wireSha256 == hash(row, "wire_hash") && frozen.metadataSha256 == hash(row, "metadata_hash") &&
                    frozen.checksumSha256 == row.getString("checksum_sha256") && frozen.contentType == row.getString("content_type") && frozen.objectLockMode == row.getString("object_lock_mode"))
                val metadata = frozen.metadataBytes()
                try { requireOrdinarySeal(metadata.contentEquals(row.getBytes("metadata_bytes"))) } finally { metadata?.fill(0) }
                canonical.close()
                return frozen
            } catch (failure: Throwable) { frozen.close(); throw failure }
        } catch (failure: Throwable) { canonical.close(); throw failure }
    }
    fun boolean(row: ResultSet, column: String): Boolean = row.getBoolean(column).also { requireOrdinarySeal(!row.wasNull()) }
    fun hash(row: ResultSet, column: String): String = checkNotNull(row.getBytes(column)).let {
        try { requireOrdinarySeal(it.size == 32); HexFormat.of().formatHex(it) } finally { it.fill(0) }
    }
    fun hex(value: String): ByteArray = HexFormat.of().parseHex(value)
}
