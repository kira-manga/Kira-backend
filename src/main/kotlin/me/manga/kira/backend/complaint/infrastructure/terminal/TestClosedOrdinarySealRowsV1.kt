package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableBindingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableKindV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStateV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRoleV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealSetV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.TestTerminalRootsV1
import java.sql.ResultSet
import java.util.HexFormat
import java.util.UUID

/**
 * Distinct registered closed-range comparison. The previous c.seal/checkpoint is retained only as
 * immutable history, never relabeled as this actually verified ORDINARY seal or a TERMINAL seal.
 */
internal object TestClosedOrdinarySealRowsV1 {
    class Cut(original: TestRunOrdinaryDrainV1, run: TestOrdinaryDrainRowsV1.Run) {
        val control = original.capturedControl()
        val existing: TestTerminalSealRefV1?
        init {
            original.requirePaidProgress(run.progress)
            requireDrain(!control.needsCapture && control.cutoff == original.cutoff && control.epoch == Math.addExact(control.cutoff, 1L))
            existing = run.sealSetBytes?.let { bytes ->
                val set = TestTerminalJsonV1(original.routing.journalConfiguration).sealSet(bytes)
                requireDrain(set.dataScopeId == original.runContext.dataScopeId && set.activationCatalogGeneration == original.runContext.activationCatalogGeneration &&
                    set.activationCatalogSha256 == original.runContext.activationCatalogSha256)
                val record = set.records().last()
                requireDrain(set.records() == control.ordinarySeals(record))
                requireDrain(record.role === TestTerminalSealRoleV1.ORDINARY && record.writerGeneration == original.writer &&
                    record.epochStartInclusive == control.ordinaryStart && record.epochEndInclusive == control.cutoff &&
                    record.precedingSealSha256 == (control.initialHistory?.reference?.objectRef?.canonicalSha256 ?: "") &&
                    run.ordinaryEpoch == control.cutoff && run.reservedTerminalEpoch == control.epoch)
                val root = TestTerminalRootsV1(original.routing.journalConfiguration, run.installationLimit, run.plan.manifestChunkCount.toInt()).preTerminalSeals(set)
                requireDrain(run.sealCount == root.count && run.sealRoot.contentEquals(HexFormat.of().parseHex(root.sha256)))
                record
            }
        }
        fun requireSameCut(other: Cut) {
            control.requireSame(other.control)
            requireDrain(existing == other.existing)
        }
        fun requireUnverified() { requireDrain(existing == null) }
        fun requireListedVersion(candidate: String?) {
            existing?.let { requireDrain(candidate != null && candidate == it.objectRef.objectVersion) }
        }
        fun requireSidecar(row: TestTerminalDurableRowV1?) {
            if (existing == null) return
            val actual = checkNotNull(row)
            requireDrain(actual.state === TestTerminalDurableStateV1.WIRE_FROZEN && existing.sealId == actual.binding.objectId &&
                existing.objectRef.objectKey == actual.binding.objectKey && existing.objectRef.canonicalSha256 == actual.canonicalSha256 &&
                existing.objectRef.ciphertextSha256 == actual.wireSha256)
        }
        fun requireProof(row: TestTerminalDurableRowV1, proof: TestOrdinarySealProofV1) {
            requireSidecar(row)
            requireDrain(existing == reference(row, proof, control.initialHistory?.reference?.objectRef?.canonicalSha256 ?: ""))
        }
    }

    fun reference(row: TestTerminalDurableRowV1, proof: TestOrdinarySealProofV1, precedingHash: String = ""): TestTerminalSealRefV1 = TestTerminalSealRefV1(
        TestTerminalSealRoleV1.ORDINARY, row.binding.writerGeneration, row.binding.epochStartInclusive, row.binding.epochEndInclusive,
        row.binding.objectId, precedingHash, TestTerminalObjectRefV1(row.binding.objectKey, proof.version, checkNotNull(row.wireSha256), row.canonicalSha256))

    fun set(original: TestRunOrdinaryDrainV1, row: TestTerminalDurableRowV1, proof: TestOrdinarySealProofV1): TestTerminalSealSetV1 =
        TestTerminalSealSetV1.create(original.runContext.dataScopeId, original.runContext.activationCatalogGeneration,
            original.runContext.activationCatalogSha256, original.capturedControl().let { control ->
                control.ordinarySeals(reference(row, proof, control.initialHistory?.reference?.objectRef?.canonicalSha256 ?: ""))
            })

    fun sidecar(row: ResultSet, original: TestRunOrdinarySealV1, cut: Cut): TestTerminalDurableRowV1 {
        val drain = checkNotNull(original.closedDrain)
        drain.requireClosedSeal(original)
        requireDrain(TestOrdinarySealRowsV1.boolean(row, "valid"))
        val run = TestTerminalRunContextV1(row.getObject("data_scope_id", UUID::class.java).toString(), row.getLong("activation_catalog_generation"),
            TestOrdinarySealRowsV1.hash(row, "activation_catalog_hash"), TestOrdinarySealRowsV1.hash(row, "configuration_hash"), TestOrdinarySealRowsV1.hash(row, "terminal_encoding_hash"))
        requireDrain(run == original.runContext && row.getString("object_kind") == "EPOCH_SEAL" && row.getInt("object_ordinal") == 0)
        val binding = TestTerminalDurableBindingV1(row.getObject("operation_token", UUID::class.java).toString(), run,
            TestOrdinarySealRowsV1.hash(row, "journal_configuration_hash"), TestTerminalDurableKindV1.EPOCH_SEAL, 0,
            checkNotNull(row.getString("object_id")), checkNotNull(row.getString("object_key")), checkNotNull(row.getString("routing_key_id")),
            row.getObject("writer_generation", UUID::class.java).toString(), row.getLong("epoch_start"), row.getLong("epoch_end"),
            row.getLong("preparing_fencing_token"), checkNotNull(row.getTimestamp("retention_floor")).toInstant(), checkNotNull(row.getTimestamp("created_at")).toInstant())
        val control = cut.control
        requireDrain(binding.operationToken == control.captureId.toString() && binding.epochStartInclusive == control.ordinaryStart && binding.epochEndInclusive == control.cutoff &&
            binding.writerGeneration == original.writer && binding.journalConfigurationSha256 == original.routing.journalConfiguration.sha256 &&
            binding.preparingFencingToken in control.captureFence..original.leaseToken && !binding.createdAt.isBefore(checkNotNull(control.capturedAt)))
        val bytes = checkNotNull(row.getBytes("canonical_bytes"))
        val canonical = try { TestTerminalDurableRowV1.canonical(binding, bytes) } finally { bytes.fill(0) }
        try {
            requireDrain(canonical.canonicalSha256 == TestOrdinarySealRowsV1.hash(row, "canonical_hash"))
            if (row.getString("state") == "CANONICAL") return canonical
            requireDrain(row.getString("state") == "WIRE_FROZEN")
            val wire = checkNotNull(row.getBytes("wire_bytes"))
            val frozen = try { TestTerminalDurableRowV1.frozen(canonical, wire, checkNotNull(row.getTimestamp("retain_until")).toInstant(),
                checkNotNull(row.getTimestamp("frozen_at")).toInstant()) } finally { wire.fill(0) }
            try {
                requireDrain(frozen.wireSha256 == TestOrdinarySealRowsV1.hash(row, "wire_hash") && frozen.metadataSha256 == TestOrdinarySealRowsV1.hash(row, "metadata_hash") &&
                    frozen.checksumSha256 == row.getString("checksum_sha256") && frozen.contentType == row.getString("content_type") && frozen.objectLockMode == row.getString("object_lock_mode"))
                val metadata = frozen.metadataBytes()
                try { requireDrain(metadata.contentEquals(row.getBytes("metadata_bytes"))) } finally { metadata?.fill(0) }
                canonical.close()
                return frozen
            } catch (failure: Throwable) { frozen.close(); throw failure }
        } catch (failure: Throwable) { canonical.close(); throw failure }
    }
}
