package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableBindingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableKindV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealProofV1
import java.sql.ResultSet
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Detached bounded comparisons; neither these shapes nor the shared TEST syntax issue terminal authority. */
internal object TestActiveOrdinarySealRowsV1 {
    class Current(row: ResultSet, original: TestActiveOrdinarySealV1) {
        val sampledAt: Instant = checkNotNull(row.getTimestamp("sampled_at")).toInstant()
        val leaseOwner: UUID? = row.getObject("lease_owner", UUID::class.java)
        val leaseToken = row.getLong("lease_token").also { requireActiveSeal(!row.wasNull()) }
        val leaseExpiresAt = row.getTimestamp("lease_expires_at")?.toInstant()
        val state = checkNotNull(row.getString("state"))
        init {
            requireActiveSeal(boolean(row, "valid"))
            val slot = original.slot
            requireActiveSeal(row.getObject("operation_token", UUID::class.java) == slot.operationToken &&
                row.getObject("request_owner", UUID::class.java) == slot.requestOwner && row.getLong("request_token") == slot.requestToken &&
                row.getTimestamp("requested_at")?.toInstant() == slot.requestedAt && row.getObject("capture_owner", UUID::class.java) == slot.captureOwner &&
                row.getLong("capture_token") == slot.captureToken && row.getTimestamp("captured_at")?.toInstant() == slot.capturedAt)
            requireActiveSeal(state in setOf("RESERVED", "CANONICAL", "WIRE_FROZEN"))
            if (state == "RESERVED") {
                val actual = checkNotNull(row.getBytes("slot_fingerprint")); val expected = slot.fingerprint()
                try { requireActiveSeal(actual.contentEquals(expected)) } finally { actual.fill(0); expected.fill(0) }
            }
        }
        fun requireLease(original: TestActiveOrdinarySealV1) {
            requireActiveSeal(leaseOwner == original.attemptId && leaseToken == original.leaseToken && checkNotNull(leaseExpiresAt).isAfter(sampledAt))
        }
    }

    class Control private constructor(
        val state: String?, private val epoch: Long, private val writer: UUID?, private val token: UUID?,
        private val key: String?, private val canonical: ByteArray?, private val hash: ByteArray?,
        private val version: String?, private val wireHash: ByteArray?, private val retainUntil: Instant?,
        private val verifiedAt: Instant?, private val proofBytes: ByteArray?, private val proofHash: ByteArray?,
    ) : AutoCloseable {
        fun requireIntent(value: TestTerminalDurableRowV1?) {
            if (value == null) {
                requireActiveSeal(state == null && epoch == 0L && writer == null && token == null && key == null && canonical == null && hash == null &&
                    version == null && wireHash == null && retainUntil == null && verifiedAt == null && proofBytes == null && proofHash == null)
                return
            }
            requireActiveSeal(state in setOf("SEAL_PREPARED", "SEAL_VERIFIED") && epoch == value.binding.epochEndInclusive &&
                writer.toString() == value.binding.writerGeneration && token.toString() == value.binding.operationToken && key == value.binding.objectKey)
            val expected = value.canonicalBytes(); val expectedHash = hex(value.canonicalSha256)
            try { requireActiveSeal(canonical.contentEquals(expected) && hash.contentEquals(expectedHash)) } finally { expected.fill(0); expectedHash.fill(0) }
            if (state == "SEAL_PREPARED") requireActiveSeal(version == null && wireHash == null && retainUntil == null && verifiedAt == null && proofBytes == null && proofHash == null)
            else requireActiveSeal(value.wireSha256 != null && wireHash?.let { HexFormat.of().formatHex(it) } == value.wireSha256 &&
                version != null && version.length in 1..1024 && version != "null" && retainUntil != null && verifiedAt != null && retainUntil.isAfter(verifiedAt) &&
                proofBytes != null && proofHash?.let { HexFormat.of().formatHex(it) } == Sha256.hex(proofBytes))
        }
        fun requireProof(value: TestTerminalDurableRowV1, proof: TestOrdinarySealProofV1) {
            requireIntent(value)
            requireActiveSeal(state == "SEAL_VERIFIED" && version == proof.version && retainUntil == proof.retainUntil)
            val expected = proof.canonicalBytes(value, checkNotNull(verifiedAt))
            try { requireActiveSeal(proofBytes.contentEquals(expected)) } finally { expected.fill(0) }
        }
        fun requireUnverified() = requireActiveSeal(state != "SEAL_VERIFIED")
        fun requireVersion(observed: String?) { if (state == "SEAL_VERIFIED") requireActiveSeal(observed != null && observed == version) }
        override fun close() { canonical?.fill(0); hash?.fill(0); wireHash?.fill(0); proofBytes?.fill(0); proofHash?.fill(0) }
        companion object {
            fun read(row: ResultSet): Control {
                requireActiveSeal(boolean(row, "valid")) // Raw NULL/state shape, never CASE-to-NULL absence.
                val allocated = ArrayList<ByteArray>(5)
                fun buffer(name: String): ByteArray? = row.getBytes(name)?.also { allocated.add(it) }
                try {
                    return Control(row.getString("seal_state"), row.getLong("seal_epoch"), row.getObject("seal_writer_generation", UUID::class.java),
                        row.getObject("seal_operation_token", UUID::class.java), row.getString("seal_object_key"), buffer("seal_bytes"), buffer("seal_hash"),
                        row.getString("seal_object_version"), buffer("seal_ciphertext_hash"), row.getTimestamp("seal_retain_until")?.toInstant(),
                        row.getTimestamp("seal_verified_at")?.toInstant(), buffer("seal_verification_bytes"), buffer("seal_verification_hash"))
                } catch (failure: Throwable) { allocated.forEach { it.fill(0) }; throw failure }
            }
        }
    }

    fun intent(row: ResultSet, original: TestActiveOrdinarySealV1): TestTerminalDurableRowV1 {
        requireActiveSeal(boolean(row, "valid") && hash(row, "seal_encoding_hash") == original.identity.sealEncodingSha256)
        val binding = TestTerminalDurableBindingV1(original.slot.operationToken.toString(), original.runContext, original.routing.journalConfiguration.sha256,
            TestTerminalDurableKindV1.EPOCH_SEAL, 0, checkNotNull(row.getString("object_id")), checkNotNull(row.getString("object_key")),
            checkNotNull(row.getString("routing_key_id")), original.writer, 1, original.cutoff, row.getLong("preparing_fencing_token"),
            checkNotNull(row.getTimestamp("retention_floor")).toInstant(), checkNotNull(row.getTimestamp("created_at")).toInstant())
        requireActiveSeal(binding.preparingFencingToken == original.leaseToken && original.leaseToken > original.slot.captureToken && !binding.createdAt.isBefore(original.slot.capturedAt))
        val bytes = checkNotNull(row.getBytes("canonical_bytes"))
        val canonical = try { TestTerminalDurableRowV1.canonical(binding, bytes) } finally { bytes.fill(0) }
        try {
            requireActiveSeal(canonical.canonicalSha256 == hash(row, "canonical_hash"))
            if (row.getString("state") == "CANONICAL") return canonical
            requireActiveSeal(row.getString("state") == "WIRE_FROZEN")
            val wire = checkNotNull(row.getBytes("wire_bytes"))
            val frozen = try { TestTerminalDurableRowV1.frozen(canonical, wire, checkNotNull(row.getTimestamp("retain_until")).toInstant(),
                checkNotNull(row.getTimestamp("frozen_at")).toInstant()) } finally { wire.fill(0) }
            try {
                requireActiveSeal(frozen.wireSha256 == hash(row, "wire_hash") && frozen.metadataSha256 == hash(row, "metadata_hash") &&
                    frozen.checksumSha256 == row.getString("checksum_sha256") && frozen.contentType == row.getString("content_type") && frozen.objectLockMode == row.getString("object_lock_mode"))
                val expected = checkNotNull(frozen.metadataBytes())
                try {
                    val actual = checkNotNull(row.getBytes("metadata_bytes"))
                    try { requireActiveSeal(expected.contentEquals(actual)) } finally { actual.fill(0) }
                } finally { expected.fill(0) }
                canonical.close()
                return frozen
            } catch (failure: Throwable) { frozen.close(); throw failure }
        } catch (failure: Throwable) { canonical.close(); throw failure }
    }
    fun boolean(row: ResultSet, name: String): Boolean = row.getBoolean(name).also { requireActiveSeal(!row.wasNull()) }
    fun hash(row: ResultSet, name: String): String = checkNotNull(row.getBytes(name)).let {
        try { requireActiveSeal(it.size == 32); HexFormat.of().formatHex(it) } finally { it.fill(0) }
    }
    fun hex(value: String): ByteArray = HexFormat.of().parseHex(value)
}
