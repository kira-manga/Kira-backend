package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableBindingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableKindV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStateV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.infrastructure.journal.OrdinaryJournalRetentionV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.requireJournalVersion
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** Bounded database comparisons only. Mixed-state rows never issue network or successful-PREPARE authority. */
internal object TestInstallationManifestPublicationRowsV1 {
    /** Scalar comparison only, not a producer/proof constructor. Only a completed original retains these. */
    data class Verified(val id: String, val objectRef: TestTerminalObjectRefV1, val createdAt: Instant,
        val lastModified: Instant, val retainUntil: Instant, val verifiedAt: Instant, val verificationSha256: String) {
        override fun toString(): String = "TestInstallationManifestPublicationRowsV1.Verified(comparison-only,redacted)"
    }
    class Publication(row: ResultSet, original: TestRunInstallationManifestPublicationV1) : AutoCloseable {
        val id: String = checkNotNull(row.getString("event_id"))
        val key: String = checkNotNull(row.getString("object_key"))
        val routingKey: String = checkNotNull(row.getString("routing_key_id"))
        val count = row.getInt("target_count")
        val hash = TestOrdinaryDrainRowsV1.hash(row, "semantic_hash")
        val createdAt: Instant = checkNotNull(row.getTimestamp("created_at")).toInstant()
        val state: String = checkNotNull(row.getString("state"))
        val version: String? = row.getString("object_version")
        val ciphertext: String? = if (state == "VERIFIED") TestOrdinaryDrainRowsV1.hash(row, "ciphertext_hash") else null
        val objectCreatedAt: Instant? = row.getTimestamp("object_created_at")?.toInstant()
        val retainUntil: Instant? = row.getTimestamp("retain_until")?.toInstant()
        val verifiedAt: Instant? = row.getTimestamp("verified_at")?.toInstant()
        private val bytes = checkNotNull(row.getBytes("event_bytes"))
        private val verification = row.getBytes("verification_bytes")
        private val verificationHash = if (state == "VERIFIED") TestOrdinaryDrainRowsV1.hash(row, "verification_hash") else null
        init {
            try {
                requireManifest(TestOrdinaryDrainRowsV1.boolean(row, "valid_shape") && state in setOf("PREPARED", "VERIFIED") &&
                    row.getString("event_kind") == "INSTALLATION_MANIFEST" && row.getString("canonicalizer") == "kcj-1" &&
                    row.getObject("data_scope_id", UUID::class.java) == original.scope && TestOrdinaryDrainRowsV1.boolean(row, "test_only") &&
                    row.getObject("writer_generation", UUID::class.java).toString() == original.writer && row.getLong("journal_epoch") == original.epoch &&
                    count in 1..500 && row.getTimestamp("applied_at") == null && Sha256.hex(bytes) == hash)
                OrdinaryJournalRetentionV1.requireInstant(createdAt, true)
                if (state == "VERIFIED") {
                    requireJournalVersion(version)
                    OrdinaryJournalRetentionV1.requireInstant(checkNotNull(objectCreatedAt), true)
                    OrdinaryJournalRetentionV1.requireInstant(checkNotNull(retainUntil), true)
                    OrdinaryJournalRetentionV1.requireInstant(checkNotNull(verifiedAt), false)
                    requireManifest(verifiedAt.nano % 1000 == 0 && verifiedAt >= objectCreatedAt && retainUntil > verifiedAt &&
                        Sha256.hex(checkNotNull(verification)) == verificationHash)
                }
            } catch (problem: Throwable) { close(); throw problem }
        }
        fun requireSidecar(sidecar: TestTerminalDurableRowV1, expectedCount: Int) {
            val b = sidecar.binding
            requireManifest(b.objectId == id && b.objectKey == key && b.routingKeyId == routingKey && b.createdAt == createdAt &&
                count == expectedCount && sidecar.canonicalSha256 == hash)
            val canonical = sidecar.canonicalBytes()
            try { requireManifest(MessageDigest.isEqual(bytes, canonical)) } finally { canonical.fill(0) }
            if (state == "VERIFIED") requireManifest(sidecar.state === TestTerminalDurableStateV1.WIRE_FROZEN && sidecar.wireSha256 == ciphertext &&
                checkNotNull(retainUntil) >= checkNotNull(sidecar.retainUntil))
        }
        fun requireProof(sidecar: TestTerminalDurableRowV1, proof: TestInstallationManifestProofV1, expectedCount: Int, now: Instant) {
            requireSidecar(sidecar, expectedCount)
            requireManifest(state == "VERIFIED" && version == proof.version && ciphertext == sidecar.wireSha256 &&
                objectCreatedAt == proof.lastModified && retainUntil == proof.retainUntil && checkNotNull(retainUntil) > now && checkNotNull(verifiedAt) <= now)
            val expected = proof.canonicalBytes(sidecar, checkNotNull(verifiedAt))
            try { requireManifest(verification.contentEquals(expected)) } finally { expected.fill(0) }
        }
        fun verifiedFacts(): Verified {
            requireManifest(state == "VERIFIED")
            return Verified(id, TestTerminalObjectRefV1(key, checkNotNull(version), checkNotNull(ciphertext), hash), createdAt,
                checkNotNull(objectCreatedAt), checkNotNull(retainUntil), checkNotNull(verifiedAt), checkNotNull(verificationHash))
        }
        fun requireVerifiedReference(expected: Verified, expectedCount: Int, now: Instant) {
            requireManifest(state == "VERIFIED" && count == expectedCount && verifiedFacts() == expected &&
                checkNotNull(retainUntil) > now && checkNotNull(verifiedAt) <= now)
        }
        override fun close() { bytes.fill(0); verification?.fill(0) }
    }

    fun recovery(row: ResultSet, original: TestRunInstallationManifestPublicationV1, publication: Publication) {
        requireManifest(TestOrdinaryDrainRowsV1.boolean(row, "valid") && row.getString("event_id") == publication.id &&
            row.getObject("data_scope_id", UUID::class.java) == original.scope && TestOrdinaryDrainRowsV1.boolean(row, "test_only") &&
            row.getTimestamp("created_at").toInstant() == publication.createdAt)
    }

    fun manifest(row: ResultSet, original: TestRunInstallationManifestPublicationV1, sealedAt: Instant, now: Instant): TestTerminalDurableRowV1 {
        requireManifest(TestOrdinaryDrainRowsV1.boolean(row, "valid"))
        val run = TestTerminalRunContextV1(row.getObject("data_scope_id", UUID::class.java).toString(), row.getLong("activation_catalog_generation"),
            TestOrdinaryDrainRowsV1.hash(row, "activation_catalog_hash"), TestOrdinaryDrainRowsV1.hash(row, "configuration_hash"), TestOrdinaryDrainRowsV1.hash(row, "terminal_encoding_hash"))
        requireManifest(run == original.runContext && row.getString("object_kind") == "INSTALLATION_MANIFEST" && row.getInt("object_ordinal") == original.chunkIndex)
        val b = TestTerminalDurableBindingV1(row.getObject("operation_token", UUID::class.java).toString(), run,
            TestOrdinaryDrainRowsV1.hash(row, "journal_configuration_hash"), TestTerminalDurableKindV1.INSTALLATION_MANIFEST, row.getInt("object_ordinal"),
            checkNotNull(row.getString("object_id")), checkNotNull(row.getString("object_key")), checkNotNull(row.getString("routing_key_id")),
            row.getObject("writer_generation", UUID::class.java).toString(), row.getLong("epoch_start"), row.getLong("epoch_end"), row.getLong("preparing_fencing_token"),
            checkNotNull(row.getTimestamp("retention_floor")).toInstant(), checkNotNull(row.getTimestamp("created_at")).toInstant())
        requireManifest(b.writerGeneration == original.writer && b.journalConfigurationSha256 == original.routing.journalConfiguration.sha256 &&
            b.epochStartInclusive == original.epoch && b.epochEndInclusive == original.epoch &&
            b.preparingFencingToken in (original.drain.leaseToken + 1)..original.preparation.leaseToken && b.createdAt >= sealedAt && b.createdAt <= now)
        original.requireRetentionFloor(b)
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
