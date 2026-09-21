package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableBindingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableKindV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import java.sql.ResultSet
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Detached bounded comparisons; neither these shapes nor the shared TEST syntax issue terminal authority. */
internal object TestActiveOrdinarySealRecoveryRowsV1 {
    class Current(row: ResultSet) {
        val sampledAt: Instant = checkNotNull(row.getTimestamp("sampled_at")).toInstant()
        val leaseOwner: UUID? = row.getObject("lease_owner", UUID::class.java)
        val leaseToken = number(row, "lease_token")
        val leaseExpiresAt = row.getTimestamp("lease_expires_at")?.toInstant()
        val state = checkNotNull(row.getString("state"))
        val operationToken = checkNotNull(row.getObject("operation_token", UUID::class.java))
        val requestOwner = checkNotNull(row.getObject("request_owner", UUID::class.java))
        val requestToken = number(row, "request_token")
        val requestedAt = checkNotNull(row.getTimestamp("requested_at")).toInstant()
        val captureOwner = checkNotNull(row.getObject("capture_owner", UUID::class.java))
        val captureToken = number(row, "capture_token")
        val capturedAt = checkNotNull(row.getTimestamp("captured_at")).toInstant()
        private val global = hash(row, "global_fingerprint")
        private val run = hash(row, "run_fingerprint")
        private val control = hash(row, "control_fingerprint")
        private val controlStatic = hash(row, "control_static")
        private val paidStatic = hash(row, "paid_static")
        private val paid = hash(row, "slot_fingerprint")
        init { requireActiveSealRecovery(boolean(row, "valid") && state in setOf("CANONICAL", "WIRE_FROZEN")) }
        fun requireLease(original: TestActiveOrdinarySealRecoveryV1) = requireActiveSealRecovery(
            leaseOwner == original.attemptId && leaseToken == original.leaseToken && checkNotNull(leaseExpiresAt).isAfter(sampledAt),
        )
        fun requireSameStable(other: Current) = requireActiveSealRecovery(global == other.global && run == other.run &&
            controlStatic == other.controlStatic && paidStatic == other.paidStatic && operationToken == other.operationToken &&
            requestOwner == other.requestOwner && requestToken == other.requestToken && requestedAt == other.requestedAt &&
            captureOwner == other.captureOwner && captureToken == other.captureToken && capturedAt == other.capturedAt)
        fun requireSamePaid(other: Current) = requireActiveSealRecovery(paid == other.paid && state == other.state)
        fun requireSamePhysical(other: Current) { requireSameStable(other); requireSamePaid(other); requireActiveSealRecovery(control == other.control) }
    }

    fun intent(row: ResultSet, original: TestActiveOrdinarySealRecoveryV1, current: Current): TestTerminalDurableRowV1 {
        requireActiveSealRecovery(boolean(row, "valid") && hash(row, "seal_encoding_hash") == original.identity.sealEncodingSha256)
        val binding = TestTerminalDurableBindingV1(current.operationToken.toString(), original.runContext, original.routing.journalConfiguration.sha256,
            TestTerminalDurableKindV1.EPOCH_SEAL, 0, checkNotNull(row.getString("object_id")), checkNotNull(row.getString("object_key")),
            checkNotNull(row.getString("routing_key_id")), original.writer, 1, original.cutoff, row.getLong("preparing_fencing_token"),
            checkNotNull(row.getTimestamp("retention_floor")).toInstant(), checkNotNull(row.getTimestamp("created_at")).toInstant())
        requireActiveSealRecovery(binding.preparingFencingToken > current.captureToken && binding.preparingFencingToken <= current.leaseToken && !binding.createdAt.isBefore(current.capturedAt))
        val bytes = checkNotNull(row.getBytes("canonical_bytes"))
        val canonical = try { TestTerminalDurableRowV1.canonical(binding, bytes) } finally { bytes.fill(0) }
        try {
            requireActiveSealRecovery(canonical.canonicalSha256 == hash(row, "canonical_hash"))
            requireEmptyCanonical(canonical, original)
            if (row.getString("state") == "CANONICAL") return canonical
            requireActiveSealRecovery(row.getString("state") == "WIRE_FROZEN")
            val wire = checkNotNull(row.getBytes("wire_bytes"))
            val frozen = try { TestTerminalDurableRowV1.frozen(canonical, wire, checkNotNull(row.getTimestamp("retain_until")).toInstant(),
                checkNotNull(row.getTimestamp("frozen_at")).toInstant()) } finally { wire.fill(0) }
            try {
                requireActiveSealRecovery(frozen.wireSha256 == hash(row, "wire_hash") && frozen.metadataSha256 == hash(row, "metadata_hash") &&
                    frozen.checksumSha256 == row.getString("checksum_sha256") && frozen.contentType == row.getString("content_type") && frozen.objectLockMode == row.getString("object_lock_mode"))
                val expected = checkNotNull(frozen.metadataBytes())
                try {
                    val actual = checkNotNull(row.getBytes("metadata_bytes"))
                    try { requireActiveSealRecovery(expected.contentEquals(actual)) } finally { actual.fill(0) }
                } finally { expected.fill(0) }
                canonical.close()
                return frozen
            } catch (failure: Throwable) { frozen.close(); throw failure }
        } catch (failure: Throwable) { canonical.close(); throw failure }
    }
    private fun requireEmptyCanonical(row: TestTerminalDurableRowV1, original: TestActiveOrdinarySealRecoveryV1) {
        val bytes = row.canonicalBytes()
        val value = try { TestTerminalJsonV1(original.routing.journalConfiguration).epochSeal(bytes) } finally { bytes.fill(0) }
        val builder = TestActiveOrdinarySealManifestV1.Builder(original.routing, 1)
        builder.beginSecond()
        val empty = builder.finish()
        requireActiveSealRecovery(value.sealId == row.binding.objectId && value.writerGeneration == original.writer &&
            value.dataScopeKind == "TEST" && value.dataScopeId == original.scope.toString() &&
            value.epochStartInclusive == 1L && value.epochEndInclusive == 1L && value.precedingSealSha256.isEmpty() &&
            value.preparingFencingToken == row.binding.preparingFencingToken && value.eventCount == 0L &&
            empty.count == 0L && value.eventManifestSha256 == empty.sha256)
    }
    private fun number(row: ResultSet, name: String): Long = row.getLong(name).also { requireActiveSealRecovery(!row.wasNull()) }
    fun boolean(row: ResultSet, name: String): Boolean = row.getBoolean(name).also { requireActiveSealRecovery(!row.wasNull()) }
    fun hash(row: ResultSet, name: String): String = checkNotNull(row.getBytes(name)).let {
        try { requireActiveSealRecovery(it.size == 32); HexFormat.of().formatHex(it) } finally { it.fill(0) }
    }
    fun hex(value: String): ByteArray = HexFormat.of().parseHex(value)
}
