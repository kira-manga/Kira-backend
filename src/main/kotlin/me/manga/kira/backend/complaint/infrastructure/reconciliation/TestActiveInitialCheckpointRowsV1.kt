package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointDocumentV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointStorageV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableBindingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableKindV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestActiveInitialCheckpointReaderV1
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Detached bounded preimages only. Neither a row nor COMPLETE can issue a native proof or revive an original. */
internal object TestActiveInitialCheckpointRowsV1 {
    class Current(row: ResultSet) : AutoCloseable {
        val sampledAt: Instant = checkNotNull(row.getTimestamp("sampled_at")).toInstant()
        val leaseOwner: UUID? = row.getObject("lease_owner", UUID::class.java)
        val leaseToken = long(row, "lease_token")
        val leaseExpiresAt: Instant? = row.getTimestamp("lease_expires_at")?.toInstant()
        val operationToken: UUID = checkNotNull(row.getObject("operation_token", UUID::class.java))
        val requestToken = long(row, "request_token")
        val captureToken = long(row, "capture_token")
        val preparingToken = long(row, "preparing_fencing_token")
        val capturedAt: Instant = checkNotNull(row.getTimestamp("captured_at")).toInstant()
        val sealVerifiedAt: Instant = checkNotNull(row.getTimestamp("seal_verified_at")).toInstant()
        private val fingerprints = listOf("global_fingerprint", "run_fingerprint", "control_fingerprint", "slot_fingerprint").map { hash(row, it) }
        init {
            requireInitialCheckpoint(boolean(row, "valid") && row.getString("state") == "WIRE_FROZEN" && operationToken.version() == 4 &&
                leaseToken >= preparingToken && preparingToken > captureToken && captureToken >= requestToken && requestToken > 0 &&
                TestActiveInitialCheckpointDocumentV1.time(sampledAt) && TestActiveInitialCheckpointDocumentV1.time(sealVerifiedAt) &&
                !sealVerifiedAt.isBefore(capturedAt) && !sealVerifiedAt.isAfter(sampledAt))
        }
        fun requireLease(original: TestActiveInitialCheckpointV1) {
            requireInitialCheckpoint(leaseOwner == original.attemptId && leaseToken == original.leaseToken &&
                leaseToken > preparingToken && checkNotNull(leaseExpiresAt).isAfter(sampledAt))
        }
        fun requireSame(other: Current) {
            requireInitialCheckpoint(fingerprints == other.fingerprints && operationToken == other.operationToken &&
                preparingToken == other.preparingToken && capturedAt == other.capturedAt && sealVerifiedAt == other.sealVerifiedAt)
        }
        override fun close() = Unit // Only fixed scalar digests, no canonical/wire buffer retained here.
        override fun toString(): String = "InitialCheckpointCurrent(detached-comparisons,redacted)"
    }

    class Control private constructor(
        val version: String, val retainUntil: Instant, val verifiedAt: Instant,
        private val canonical: ByteArray, private val proof: ByteArray,
    ) : AutoCloseable {
        fun requireNative(original: TestActiveInitialCheckpointV1, observed: TestActiveInitialCheckpointReaderV1.SealProof) {
            observed.requireOriginal(original)
            requireInitialCheckpoint(observed.version == version && observed.retainUntil == retainUntil &&
                !observed.verifiedAt.isBefore(verifiedAt))
            val expected = observed.canonicalVerificationBytes(original.frozenRow(), verifiedAt)
            try { requireInitialCheckpoint(proof.contentEquals(expected)) } finally { expected.fill(0) }
        }
        override fun close() { canonical.fill(0); proof.fill(0) }
        companion object {
            fun read(row: ResultSet, frozen: TestTerminalDurableRowV1, current: Current): Control {
                requireInitialCheckpoint(boolean(row, "valid") && row.getString("seal_state") == "SEAL_VERIFIED" &&
                    long(row, "seal_epoch") == 1L && row.getObject("seal_writer_generation", UUID::class.java).toString() == frozen.binding.writerGeneration &&
                    row.getObject("seal_operation_token", UUID::class.java) == current.operationToken && row.getString("seal_object_key") == frozen.binding.objectKey &&
                    hash(row, "seal_hash") == frozen.canonicalSha256 && hash(row, "seal_ciphertext_hash") == frozen.wireSha256)
                val canonical = checkNotNull(row.getBytes("seal_bytes"))
                var proof: ByteArray? = null
                try {
                    val expected = frozen.canonicalBytes()
                    try { requireInitialCheckpoint(canonical.contentEquals(expected)) } finally { expected.fill(0) }
                    val bytes = checkNotNull(row.getBytes("seal_verification_bytes")).also { proof = it }
                    requireInitialCheckpoint(Sha256.hex(bytes) == hash(row, "seal_verification_hash"))
                    val version = checkNotNull(row.getString("seal_object_version"))
                    me.manga.kira.backend.complaint.infrastructure.journal.aws.requireJournalVersion(version)
                    val at = checkNotNull(row.getTimestamp("seal_verified_at")).toInstant()
                    val until = checkNotNull(row.getTimestamp("seal_retain_until")).toInstant()
                    requireInitialCheckpoint(at == current.sealVerifiedAt && until.isAfter(current.sampledAt) &&
                        !until.isBefore(checkNotNull(frozen.retainUntil)))
                    return Control(version, until, at, canonical, bytes)
                } catch (failure: Throwable) { canonical.fill(0); proof?.fill(0); throw failure }
            }
        }
    }

    /** Every field is checked against the CURRENT full-D/first-seal row before refund or comparison. */
    class Scan(row: ResultSet, original: TestActiveInitialCheckpointV1, current: Current) {
        val scanId: UUID = checkNotNull(row.getObject("scan_id", UUID::class.java))
        val pass = row.getInt("pass").also { requireInitialCheckpoint(!row.wasNull() && it in 1..2) }
        val token = long(row, "fencing_token")
        val state = checkNotNull(row.getString("state"))
        val startedAt: Instant = checkNotNull(row.getTimestamp("started_at")).toInstant()
        val finishedAt: Instant? = row.getTimestamp("finished_at")?.toInstant()
        val manifestSha256: String? = row.getBytes("manifest_hash")?.let { value ->
            try { requireInitialCheckpoint(value.size == 32); HexFormat.of().formatHex(value) } finally { value.fill(0) }
        }
        private val scope = original.scope
        private val restore = original.identity.restoreIdentity
        private val desired = original.identity.desiredGeneration
        private val writer = original.identity.writer
        private val maximumEntries = original.maximumEntries
        private val maximumBytes = original.maximumBytes
        init {
            requireInitialCheckpoint(boolean(row, "valid") && boolean(row, "test_only") && scanId == current.operationToken &&
                row.getObject("data_scope_id", UUID::class.java) == scope && row.getObject("restore_identity", UUID::class.java) == restore &&
                long(row, "desired_generation") == desired && row.getObject("writer_generation", UUID::class.java) == writer &&
                long(row, "cutoff_epoch") == 1L && long(row, "maximum_entries") == maximumEntries && long(row, "maximum_bytes") == maximumBytes &&
                long(row, "entry_count") == 0L && long(row, "entry_bytes") == 0L &&
                row.getObject("active_initial_seal_token", UUID::class.java) == scanId &&
                long(row, "active_initial_storage_bytes") == TestActiveInitialCheckpointStorageV1.STORAGE_BYTES &&
                token > current.preparingToken && token <= current.leaseToken &&
                TestActiveInitialCheckpointDocumentV1.time(startedAt) && !startedAt.isBefore(current.sealVerifiedAt) && !startedAt.isAfter(current.sampledAt))
            if (state == "SCANNING") requireInitialCheckpoint(finishedAt == null && manifestSha256 == null)
            else {
                val end = checkNotNull(finishedAt)
                requireInitialCheckpoint(TestActiveInitialCheckpointDocumentV1.time(end) && !end.isBefore(startedAt) && !end.isAfter(current.sampledAt))
                requireInitialCheckpoint(if (state == "COMPLETE") manifestSha256 == original.manifestSha256 else state == "ABANDONED" && manifestSha256 == null)
            }
        }
        fun same(other: Scan): Boolean = scanId == other.scanId && pass == other.pass && token == other.token && state == other.state &&
            startedAt == other.startedAt && finishedAt == other.finishedAt && manifestSha256 == other.manifestSha256 &&
            scope == other.scope && restore == other.restore && desired == other.desired && writer == other.writer &&
            maximumEntries == other.maximumEntries && maximumBytes == other.maximumBytes
        fun deleteArguments(): Array<Any?> = arrayOf(scanId, pass, scope, token, scanId, restore, desired, writer, maximumEntries, maximumBytes,
            state, manifestSha256?.let(::hex), Timestamp.from(startedAt), finishedAt?.let(Timestamp::from))
        override fun toString(): String = "InitialCheckpointScan(detached-full-preimage,redacted,no-proof)"
    }

    fun intent(row: ResultSet, original: TestActiveInitialCheckpointV1, current: Current): TestTerminalDurableRowV1 {
        requireInitialCheckpoint(boolean(row, "valid") && hash(row, "seal_encoding_hash") == original.identity.sealEncodingSha256)
        val binding = TestTerminalDurableBindingV1(current.operationToken.toString(), original.runContext, original.routing.journalConfiguration.sha256,
            TestTerminalDurableKindV1.EPOCH_SEAL, 0, checkNotNull(row.getString("object_id")), checkNotNull(row.getString("object_key")),
            checkNotNull(row.getString("routing_key_id")), original.writer, 1, 1, row.getLong("preparing_fencing_token"),
            checkNotNull(row.getTimestamp("retention_floor")).toInstant(), checkNotNull(row.getTimestamp("created_at")).toInstant())
        requireInitialCheckpoint(binding.preparingFencingToken == current.preparingToken && current.preparingToken > current.captureToken && !binding.createdAt.isBefore(current.capturedAt))
        val bytes = checkNotNull(row.getBytes("canonical_bytes"))
        val canonical = try { TestTerminalDurableRowV1.canonical(binding, bytes) } finally { bytes.fill(0) }
        try {
            requireInitialCheckpoint(canonical.canonicalSha256 == hash(row, "canonical_hash"))
            // A restart accepts an already frozen intent only; it cannot reconstruct or publish A success.
            requireInitialCheckpoint(row.getString("state") == "WIRE_FROZEN")
            val wire = checkNotNull(row.getBytes("wire_bytes"))
            val frozen = try { TestTerminalDurableRowV1.frozen(canonical, wire, checkNotNull(row.getTimestamp("retain_until")).toInstant(),
                checkNotNull(row.getTimestamp("frozen_at")).toInstant()) } finally { wire.fill(0) }
            try {
                requireInitialCheckpoint(frozen.wireSha256 == hash(row, "wire_hash") && frozen.metadataSha256 == hash(row, "metadata_hash") &&
                    frozen.checksumSha256 == row.getString("checksum_sha256") && frozen.contentType == row.getString("content_type") && frozen.objectLockMode == row.getString("object_lock_mode"))
                val expected = checkNotNull(frozen.metadataBytes())
                try {
                    val actual = checkNotNull(row.getBytes("metadata_bytes"))
                    try { requireInitialCheckpoint(expected.contentEquals(actual)) } finally { actual.fill(0) }
                } finally { expected.fill(0) }
                canonical.close()
                return frozen
            } catch (failure: Throwable) { frozen.close(); throw failure }
        } catch (failure: Throwable) { canonical.close(); throw failure }
    }

    fun boolean(row: ResultSet, name: String): Boolean = row.getBoolean(name).also { requireInitialCheckpoint(!row.wasNull()) }
    fun long(row: ResultSet, name: String): Long = row.getLong(name).also { requireInitialCheckpoint(!row.wasNull()) }
    fun hash(row: ResultSet, name: String): String = checkNotNull(row.getBytes(name)).let {
        try { requireInitialCheckpoint(it.size == 32); HexFormat.of().formatHex(it) } finally { it.fill(0) }
    }
    fun hex(value: String): ByteArray = HexFormat.of().parseHex(value)
}
