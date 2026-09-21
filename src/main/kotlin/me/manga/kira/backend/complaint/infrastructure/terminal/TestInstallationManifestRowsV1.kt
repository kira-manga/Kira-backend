package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableBindingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableKindV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.infrastructure.journal.OrdinaryJournalRetentionV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** Bounded comparisons only. All rows are selected by the actual original under its own current fence. */
internal object TestInstallationManifestRowsV1 {
    class Publication(row: ResultSet, original: TestRunInstallationManifestV1) : AutoCloseable {
        val id: String = checkNotNull(row.getString("event_id"))
        val key: String = checkNotNull(row.getString("object_key"))
        val routingKey: String = checkNotNull(row.getString("routing_key_id"))
        val count = row.getInt("target_count")
        val hash = TestOrdinaryDrainRowsV1.hash(row, "semantic_hash")
        val createdAt: Instant = checkNotNull(row.getTimestamp("created_at")).toInstant()
        private val bytes = checkNotNull(row.getBytes("event_bytes"))
        init {
            requireManifest(TestOrdinaryDrainRowsV1.boolean(row, "valid_shape") && row.getString("state") == "PREPARED" &&
                row.getString("event_kind") == "INSTALLATION_MANIFEST" && row.getString("canonicalizer") == "kcj-1" &&
                row.getObject("data_scope_id", UUID::class.java) == original.scope && TestOrdinaryDrainRowsV1.boolean(row, "test_only") &&
                row.getObject("writer_generation", UUID::class.java).toString() == original.writer && row.getLong("journal_epoch") == original.epoch && count in 1..500)
        }
        fun requireSidecar(sidecar: TestTerminalDurableRowV1, expectedCount: Int) {
            val b = sidecar.binding
            requireManifest(b.objectId == id && b.objectKey == key && b.routingKeyId == routingKey && b.createdAt == createdAt &&
                count == expectedCount && sidecar.canonicalSha256 == hash)
            val canonical = sidecar.canonicalBytes()
            try { requireManifest(MessageDigest.isEqual(bytes, canonical)) } finally { canonical.fill(0) }
        }
        override fun close() { bytes.fill(0) }
    }

    fun recovery(row: ResultSet, original: TestRunInstallationManifestV1, publication: Publication) {
        requireManifest(TestOrdinaryDrainRowsV1.boolean(row, "valid") && row.getString("event_id") == publication.id &&
            row.getObject("data_scope_id", UUID::class.java) == original.scope && TestOrdinaryDrainRowsV1.boolean(row, "test_only") &&
            row.getTimestamp("created_at").toInstant() == publication.createdAt)
    }

    fun manifest(row: ResultSet, original: TestRunInstallationManifestV1, sealedAt: Instant, now: Instant): TestTerminalDurableRowV1 {
        requireManifest(TestOrdinaryDrainRowsV1.boolean(row, "valid") && row.getString("state") == "CANONICAL")
        val b = binding(row, original)
        requireManifest(b.objectKind === TestTerminalDurableKindV1.INSTALLATION_MANIFEST && b.objectOrdinal == original.chunkIndex &&
            b.epochStartInclusive == original.epoch && b.epochEndInclusive == original.epoch &&
            b.preparingFencingToken in (original.drain.leaseToken + 1)..original.leaseToken && b.createdAt >= sealedAt && b.createdAt <= now)
        original.requireRetentionFloor(b)
        val bytes = checkNotNull(row.getBytes("canonical_bytes"))
        val canonical = try { TestTerminalDurableRowV1.canonical(b, bytes) } finally { bytes.fill(0) }
        try {
            requireManifest(canonical.canonicalSha256 == TestOrdinaryDrainRowsV1.hash(row, "canonical_hash"))
            return canonical
        } catch (problem: Throwable) { canonical.close(); throw problem }
    }

    fun requireOrdinarySidecar(row: ResultSet, original: TestRunInstallationManifestV1, now: Instant) {
        val expected = original.ordinarySeal
        val b = binding(row, original)
        requireManifest(TestOrdinaryDrainRowsV1.boolean(row, "valid") && row.getString("state") == "WIRE_FROZEN" &&
            b.objectKind === TestTerminalDurableKindV1.EPOCH_SEAL && b.objectOrdinal == 0 && b.operationToken == original.control.captureId.toString() &&
            b.epochStartInclusive == original.control.ordinaryStart && b.epochEndInclusive == original.control.cutoff &&
            b.preparingFencingToken in original.control.captureFence..original.drain.leaseToken &&
            b.objectId == expected.sealId && b.objectKey == expected.objectRef.objectKey &&
            TestOrdinaryDrainRowsV1.hash(row, "canonical_hash") == expected.objectRef.canonicalSha256 &&
            TestOrdinaryDrainRowsV1.hash(row, "wire_hash") == expected.objectRef.ciphertextSha256)
        val retainUntil = checkNotNull(row.getTimestamp("retain_until")).toInstant()
        val frozenAt = checkNotNull(row.getTimestamp("frozen_at")).toInstant()
        OrdinaryJournalRetentionV1.requireInstant(retainUntil, true); OrdinaryJournalRetentionV1.requireInstant(frozenAt, true)
        requireManifest(frozenAt >= b.createdAt && frozenAt <= now && retainUntil >= b.retentionFloor && retainUntil > now)
        // pgjdbc may lend the row's binary bytea buffer; wipe only our copy before inventory rereads.
        val bytes = checkNotNull(row.getBytes("canonical_bytes")).copyOf()
        try {
            val seal = TestTerminalJsonV1(original.routing.journalConfiguration).epochSeal(bytes)
            requireManifest(seal.sealId == expected.sealId && seal.writerGeneration == original.writer && seal.dataScopeId == original.runContext.dataScopeId &&
                seal.epochStartInclusive == original.control.ordinaryStart && seal.epochEndInclusive == original.control.cutoff &&
                seal.precedingSealSha256 == (original.control.initialHistory?.reference?.objectRef?.canonicalSha256 ?: "") &&
                seal.preparingFencingToken == b.preparingFencingToken && seal.eventCount == original.ordinarySealManifest.count &&
                seal.eventManifestSha256 == original.ordinarySealManifest.sha256)
        } finally { bytes.fill(0) }
    }

    /** Fresh comparison bytes for the post-terminal native reader, never reused completed-producer buffers. */
    fun ordinarySidecarForInventory(row: ResultSet, original: TestRunInstallationManifestV1, now: Instant): TestTerminalDurableRowV1 {
        requireOrdinarySidecar(row, original, now)
        return TestTerminalSqlRowV1.restore(row, binding(row, original), now)
    }

    private fun binding(row: ResultSet, original: TestRunInstallationManifestV1): TestTerminalDurableBindingV1 {
        val run = TestTerminalRunContextV1(row.getObject("data_scope_id", UUID::class.java).toString(), row.getLong("activation_catalog_generation"),
            TestOrdinaryDrainRowsV1.hash(row, "activation_catalog_hash"), TestOrdinaryDrainRowsV1.hash(row, "configuration_hash"), TestOrdinaryDrainRowsV1.hash(row, "terminal_encoding_hash"))
        requireManifest(run == original.runContext)
        return TestTerminalDurableBindingV1(row.getObject("operation_token", UUID::class.java).toString(), run,
            TestOrdinaryDrainRowsV1.hash(row, "journal_configuration_hash"), TestTerminalDurableKindV1.valueOf(checkNotNull(row.getString("object_kind"))),
            row.getInt("object_ordinal"), checkNotNull(row.getString("object_id")), checkNotNull(row.getString("object_key")), checkNotNull(row.getString("routing_key_id")),
            row.getObject("writer_generation", UUID::class.java).toString(), row.getLong("epoch_start"), row.getLong("epoch_end"), row.getLong("preparing_fencing_token"),
            checkNotNull(row.getTimestamp("retention_floor")).toInstant(), checkNotNull(row.getTimestamp("created_at")).toInstant()).also {
            requireManifest(it.writerGeneration == original.writer && it.journalConfigurationSha256 == original.routing.journalConfiguration.sha256)
        }
    }
}
