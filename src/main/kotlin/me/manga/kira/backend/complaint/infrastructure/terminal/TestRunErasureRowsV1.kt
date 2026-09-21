package me.manga.kira.backend.complaint.infrastructure.terminal

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.domain.InstallationCredentialState
import me.manga.kira.backend.complaint.domain.InstallationIdentityState
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointDocumentV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogObjectMetadata
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunTerminalHistoryV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunTerminalRecordV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationManifestV3
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunTerminalManifestV4
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalAccountingPlanV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableBindingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableKindV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableStateV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogFrozenManifestParser
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunTerminalReadbackV4
import me.manga.kira.backend.complaint.infrastructure.catalog.copyEvidence
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationCodecV1
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationRecordV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteVerificationCodecV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteVerificationRecordV1
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogTestRunActivationParser
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogTestRunTerminalParser
import me.manga.kira.backend.security.TestTerminalJsonV1
import me.manga.kira.backend.security.EpochSealFramesV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalBindingV1
import me.manga.kira.backend.security.TestPostTerminalInventoryEntryV1
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** Bounded local comparisons only. None of these row values authenticates a native history or issues work. */
internal object TestRunErasureRowsV1 {
    class Snapshot(
        val global: Control, val scoped: Control?, val activation: Mutation, val history: CatalogTestRunActivationHistoryV1,
        val terminal: Mutation, val run: Run, val active: Active?, val recurrent: TestOrdinaryDrainActiveHistoryV1?, val queueHash: String?,
    ) {
        fun requireHead() {
            requireErasure(global.scope == UUID(0, 0) && global.head == terminal.head &&
                activation.scope == run.scope && terminal.scope == run.scope && activation.operation == "TEST_RUN_ACTIVATION" &&
                terminal.operation == "TEST_RUN_TERMINAL" && terminal.generation == activation.generation + 1L &&
                terminal.predecessor == activation.head && run.context.activationCatalogGeneration == activation.generation &&
                run.context.activationCatalogSha256 == activation.head.envelopeSha256 && run.terminalHead == terminal.head &&
                activation.projectedAt == run.createdAt && terminal.projectedAt == run.purgingAt &&
                (active == null || recurrent == null) &&
                (if (run.purged) scoped == null && active == null && recurrent == null
                    else scoped != null && scoped.scope == run.scope && scoped.head == activation.head &&
                        (recurrent?.count ?: if (active == null) 0 else 1).toLong() == run.sealCount - 2L))
            // The first fresh CAPTURE must match E's durable source/archive+xmin commitment,
            // not adopt surviving rows as new custody. PURGED retains it without inventing rows.
            if (!run.purged) requireErasure(run.recurrentErasureHistoryHash == recurrent?.erasureCommitment(
                terminal.token, terminal.generation, terminal.head.envelopeSha256))
        }

        /** Only already committed eraser effects may differ; global/catalog/history are immutable. */
        fun requireImmutable(other: Snapshot) {
            requireHead(); other.requireHead()
            global.requireSame(other.global); activation.requireSame(other.activation); terminal.requireSame(other.terminal)
            history.requireSame(other.history); run.requireImmutable(other.run)
            requireErasure(queueHash == other.queueHash)
            if (!other.run.purged) {
                requireErasure((active == null) == (other.active == null))
                active?.requireSame(checkNotNull(other.active))
                requireErasure((recurrent == null) == (other.recurrent == null))
                recurrent?.requireSame(other.recurrent)
            } else requireErasure(other.active == null && other.recurrent == null)
            if (scoped == null) requireErasure(other.scoped == null)
            else if (other.scoped == null) requireErasure(other.run.purged)
            else scoped.requireCore(other.scoped)
        }

        fun requireSame(other: Snapshot) {
            requireImmutable(other); run.requireSame(other.run)
            requireErasure((scoped == null) == (other.scoped == null))
            scoped?.requireSame(checkNotNull(other.scoped))
        }

        /** Called only after each real version body has closed, within the one complete native round. */
        fun requireRaw(original: TestRunErasureV1, generation: Long, raw: ByteArray, metadata: CatalogObjectMetadata) {
            requireConnectionFree()
            requireErasure(metadata.requestBinding.key == CatalogReadbackProtocol.key(generation))
            when (generation) {
                activation.generation -> activation.requireRaw(original, raw, metadata)
                terminal.generation -> terminal.requireRaw(original, raw, metadata)
                else -> {
                    requireErasure(generation in 1 until activation.generation)
                    history.requireRaw(Math.toIntExact(generation - 1L), CatalogFrozenManifestParser.signed(raw,
                        original.process.catalogReadback.chainPolicy.limits), metadata)
                }
            }
        }

        fun requireNative(original: TestRunErasureV1, proof: CatalogTestRunTerminalReadbackV4) {
            requireConnectionFree(); requireHead()
            requireErasure(proof.expectedHead == terminal.head && proof.chain.tail.generation == terminal.generation &&
                proof.chain.tail.envelopeSha256 == terminal.head.envelopeSha256)
            val wire = proof.signedEnvelopeBytes()
            try { terminal.requireEnvelope(wire) } finally { wire.fill(0) }
            run.requireRecord(original, proof.chain.manifest.terminalRecord)
            active?.requireReference(proof.chain.manifest.terminalRecord)
            recurrent?.let { requireErasure(it.records.map { value -> value.reference } == proof.chain.manifest.terminalRecord.sealSet.records().dropLast(2)) }
        }
        override fun toString(): String = "TestRunErasureSnapshotV1(bounded-local-comparison,redacted)"
    }

    class Mutation(row: ResultSet) {
        val token = uuid(row, "operation_token")
        val scope = uuid(row, "data_scope_id")
        val operation = text(row, "operation_type", 32)
        val generation = long(row, "successor_generation")
        val predecessor = CatalogLocalHead(long(row, "predecessor_generation"), hash(row, "predecessor_hash"))
        private val writer = uuid(row, "catalog_writer_generation")
        private val unsigned = bytes(row, "unsigned_bytes", 131072)
        private val unsignedDigest = bytes(row, "unsigned_hash", 32, 32)
        private val approvals = bytes(row, "approval_bytes", 4096)
        private val approvalHash = bytes(row, "approval_hash", 32, 32)
        private val signer = text(row, "signer_one_id", 128)
        private val algorithm = text(row, "signer_one_algorithm", 128)
        private val signature = bytes(row, "signature_bytes", 384, 384)
        private val envelope = bytes(row, "envelope_bytes", 131072)
        val head = CatalogLocalHead(generation, hash(row, "envelope_hash"))
        private val key = text(row, "object_key", 1024)
        private val version = text(row, "object_version", 1024)
        val createdAt = time(row, "created_at")
        val completedAt = time(row, "completed_at")
        val projectedAt = time(row, "projected_at")
        private val retainUntil = time(row, "retain_until")
        private val primary = bytes(row, "primary_evidence_bytes", 65536)
        private val replica = bytes(row, "replica_evidence_bytes", 65536)
        init {
            requireErasure(boolean(row, "valid") && boolean(row, "completed") &&
                operation in setOf("TEST_RUN_ACTIVATION", "TEST_RUN_TERMINAL") && predecessor.generation + 1L == generation &&
                createdAt <= completedAt && completedAt <= projectedAt && projectedAt < retainUntil &&
                key == CatalogReadbackProtocol.key(generation) && version != "null" &&
                Sha256.hex(unsigned) == HexFormat.of().formatHex(unsignedDigest) && Sha256.hex(approvals) == HexFormat.of().formatHex(approvalHash) &&
                Sha256.hex(envelope) == head.envelopeSha256 && Sha256.hex(primary) == hash(row, "primary_evidence_hash") &&
                Sha256.hex(replica) == hash(row, "replica_evidence_hash"))
        }
        fun unsignedBytes(): ByteArray = unsigned.copyOf()
        fun unsignedHash(): ByteArray = unsignedDigest.copyOf()
        fun requireEnvelope(raw: ByteArray) = requireErasure(raw.contentEquals(envelope))
        fun requireSame(other: Mutation) = requireErasure(token == other.token && scope == other.scope && operation == other.operation &&
            generation == other.generation && predecessor == other.predecessor && writer == other.writer && unsigned.contentEquals(other.unsigned) &&
            unsignedDigest.contentEquals(other.unsignedDigest) && approvals.contentEquals(other.approvals) && approvalHash.contentEquals(other.approvalHash) &&
            signer == other.signer && algorithm == other.algorithm && signature.contentEquals(other.signature) && envelope.contentEquals(other.envelope) &&
            head == other.head && key == other.key && version == other.version && createdAt == other.createdAt && completedAt == other.completedAt &&
            projectedAt == other.projectedAt && retainUntil == other.retainUntil && primary.contentEquals(other.primary) && replica.contentEquals(other.replica))

        fun requireRaw(original: TestRunErasureV1, raw: ByteArray, metadata: CatalogObjectMetadata) {
            requireConnectionFree(); requireEnvelope(raw)
            val limits = original.process.catalogReadback.chainPolicy.limits
            val manifestBytes: ByteArray
            val approvalBytes: ByteArray
            val member: me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisSignatureV1
            if (operation == "TEST_RUN_ACTIVATION") {
                val parsed = OfflineCatalogTestRunActivationParser.parse(raw, limits.maximumManifestRecords, 131072)
                val manifest = parsed.manifest
                original.expectedDeclaration.activation.requireManifest(manifest)
                requireErasure(manifest.operationToken == token.toString() && manifest.generation == generation &&
                    manifest.activationRecord.run.testRunId == scope.toString() && manifest.catalogWriterGenerationId == writer.toString() &&
                    manifest.previousEnvelopeSha256 == predecessor.envelopeSha256 && Instant.ofEpochSecond(manifest.creation.createdAtEpochSecond) == createdAt)
                manifestBytes = CanonicalJson.canonicalize(OfflineCatalogTestRunActivationManifestV3.serializer(), manifest).toByteArray(Charsets.UTF_8)
                approvalBytes = CanonicalJson.canonicalize(ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), manifest.approvals).toByteArray(Charsets.UTF_8)
                member = parsed.signatures.single()
            } else {
                val parsed = OfflineCatalogTestRunTerminalParser.parse(raw, limits.maximumManifestRecords, 131072)
                val manifest = parsed.manifest
                requireErasure(manifest.operationToken == token.toString() && manifest.generation == generation &&
                    manifest.terminalRecord.context().dataScopeId == scope.toString() && manifest.catalogWriterGenerationId == writer.toString() &&
                    manifest.previousEnvelopeSha256 == predecessor.envelopeSha256 && Instant.ofEpochSecond(manifest.creation.createdAtEpochSecond) == createdAt)
                manifestBytes = CanonicalJson.canonicalize(OfflineCatalogTestRunTerminalManifestV4.serializer(), manifest).toByteArray(Charsets.UTF_8)
                approvalBytes = CanonicalJson.canonicalize(ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), manifest.approvals).toByteArray(Charsets.UTF_8)
                member = parsed.signatures.single()
            }
            try { requireErasure(manifestBytes.contentEquals(unsigned) && approvalBytes.contentEquals(approvals)) }
            finally { manifestBytes.fill(0); approvalBytes.fill(0) }
            val decoded = Base64.getDecoder().decode(member.signatureBase64)
            try { requireErasure(member.keyId == signer && member.algorithmId == algorithm && decoded.contentEquals(signature)) }
            finally { decoded.fill(0) }
            val actual = copyEvidence(metadata, head.envelopeSha256)
            try {
                requireErasure(metadata.requestBinding.key == key && metadata.requestBinding.versionId == version &&
                    Instant.ofEpochSecond(checkNotNull(metadata.retainUntilEpochSecond)) == retainUntil &&
                    actual.contentEquals(if (metadata.requestBinding.location.role == "PRIMARY") primary else replica))
            } finally { actual.fill(0) }
        }
        override fun toString(): String = "TestRunErasureMutationV1(exact-canonical-custody-comparison,redacted)"
    }

    class Control(row: ResultSet) {
        val scope = uuid(row, "data_scope_id")
        val head = CatalogLocalHead(long(row, "accepted_catalog_generation"), hash(row, "accepted_catalog_hash"))
        val leaseToken = long(row, "lease_token")
        val leaseOwner: UUID? = row.getObject("lease_owner", UUID::class.java)
        val leaseExpiresAt: Instant? = row.getTimestamp("lease_expires_at")?.toInstant()
        val physicalHash = hash(row, "physical_hash")
        val coreHash = hash(row, "core_hash")
        init { requireErasure(boolean(row, "valid") && leaseToken >= 0 && (leaseOwner == null) == (leaseExpiresAt == null)) }
        fun requireCore(other: Control) = requireErasure(scope == other.scope && head == other.head && coreHash == other.coreHash)
        fun requireSame(other: Control) { requireCore(other); requireErasure(physicalHash == other.physicalHash) }
        override fun toString(): String = "TestRunErasureControlV1(exact-current-comparison,redacted)"
    }

    class Run(row: ResultSet, original: TestRunErasureV1) {
        val scope = uuid(row, "data_scope_id")
        val purged = text(row, "state", 16) == "PURGED"
        val context = TestTerminalRunContextV1(scope.toString(), long(row, "activation_catalog_generation"), hash(row, "activation_catalog_hash"),
            hash(row, "configuration_hash"), TestTerminalProfileV1.encodingSha256)
        val terminalHead = CatalogLocalHead(long(row, "terminal_catalog_generation"), hash(row, "terminal_catalog_hash"))
        val createdAt = time(row, "created_at")
        val sealedAt = time(row, "sealed_at")
        val purgingAt = time(row, "purging_at")
        val purgedAt: Instant? = row.getTimestamp("purged_at")?.toInstant()
        val installationLimit = long(row, "installation_limit")
        val enrolled = long(row, "enrolled_count")
        val ordinaryEpoch = long(row, "final_ordinary_epoch")
        val terminalEpoch = long(row, "terminal_seal_epoch")
        val sealCount = long(row, "generation_seal_count")
        val recurrentErasureHistoryHash = row.getBytes("recurrent_erasure_history_hash")?.let {
            try { requireErasure(it.size == 32); HexFormat.of().formatHex(it) } finally { it.fill(0) }
        }
        private val sealRoot = hash(row, "generation_seal_root")
        private val progress = bytes(row, "progress_bytes", 51291)
        private val seals = bytes(row, "seal_set_bytes", 65536)
        val reserve = vector(row, "original_reserve")
        val unused = vector(row, "unused_reserve")
        val physicalHash = hash(row, "physical_hash")
        private val immutable = hash(row, "core_hash")
        private val effects = listOf(long(row, "event_manifest_count"), hash(row, "event_manifest_root"),
            long(row, "installation_manifest_count"), hash(row, "installation_manifest_root"), long(row, "installation_chunk_count"),
            long(row, "retired_count"), long(row, "deleted_count"), text(row, "terminal_event_id", 43), text(row, "terminal_object_key", 1024),
            text(row, "terminal_object_version", 1024), hash(row, "terminal_ciphertext_hash"))
        init {
            requireErasure(boolean(row, "valid") && scope == original.scope && installationLimit == original.installationLimit &&
                context.configurationSha256 == HexFormat.of().formatHex(original.process.configurationHashBytes()) &&
                reserve == TestTerminalAccountingPlanV1(installationLimit, original.routing.journalConfiguration.declaration().limits.capacity.maximumRetainedVersions).originalUnusedReserve &&
                unused.fitsWithin(reserve) && sealedAt >= createdAt && purgingAt >= sealedAt && terminalEpoch == Math.addExact(ordinaryEpoch, 1L) &&
                sealCount in 2..16 && Sha256.hex(progress) == hash(row, "progress_hash") && Sha256.hex(seals) == hash(row, "seal_set_hash") &&
                (recurrentErasureHistoryHash != null) == (sealCount >= 4L) &&
                purged == (purgedAt != null) && (!purged || unused.isZero()))
        }
        fun requireImmutable(other: Run) = requireErasure(scope == other.scope && immutable == other.immutable && reserve == other.reserve &&
            context == other.context && terminalHead == other.terminalHead && progress.contentEquals(other.progress) && seals.contentEquals(other.seals) &&
            recurrentErasureHistoryHash == other.recurrentErasureHistoryHash)
        fun requireSame(other: Run) { requireImmutable(other); requireErasure(physicalHash == other.physicalHash && purged == other.purged && unused == other.unused && purgedAt == other.purgedAt) }
        fun requireRecord(original: TestRunErasureV1, record: CatalogTestRunTerminalRecordV1) {
            requireConnectionFree()
            val json = TestTerminalJsonV1(original.routing.journalConfiguration)
            val encodedProgress = json.encodeProgress(record.progress); val encodedSeals = json.encodeSealSet(record.sealSet)
            try { requireErasure(encodedProgress.contentEquals(progress) && encodedSeals.contentEquals(seals)) }
            finally { encodedProgress.fill(0); encodedSeals.fill(0) }
            val summary = record.installationManifest.summary
            val purge = record.purge
            requireErasure(record.context() == context && record.generation == terminalHead.generation && record.sealedAtEpochSecond == sealedAt.epochSecond &&
                record.closure.finalOrdinaryEpoch == ordinaryEpoch && record.closure.terminalEpoch == terminalEpoch && record.sealSet.records().size.toLong() == sealCount &&
                record.installationManifest.summary.installationCount == enrolled && CatalogTestRunTerminalHistoryV1.sealHead(record.sealSet.records()).sha256 == sealRoot &&
                effects == listOf(purge.document.preTerminalInventory.count, purge.document.preTerminalInventory.sha256, summary.installationCount,
                    summary.installationsSha256, summary.chunkCount.toLong(), summary.retiredCount, summary.deletedCount, purge.document.eventId,
                    purge.objectRef.objectKey, purge.objectRef.objectVersion, purge.objectRef.ciphertextSha256))
        }
        fun requireUnused(dispositionAudits: Long) {
            requireErasure(dispositionAudits in 0..enrolled)
            val chunks = me.manga.kira.backend.complaint.domain.terminal.TestTerminalSyntaxV1.chunkCount(enrolled).toLong()
            val baseline = TestTerminalCapacityChargesV1.INSTALLATION_SHARE.scaled(enrolled) + TestTerminalCapacityChargesV1.AUDIT +
                TestTerminalCapacityChargesV1.TERMINAL_RUN_DELTA + TestTerminalCapacityChargesV1.SIDECAR.scaled(2) +
                TestInstallationManifestOperationV1.MANIFEST_ACTUAL.scaled(chunks) + TestRunPurgeOperationV1.ACTUAL + TestRunPurgeOperationV1.FUTURE +
                TestTerminalCapacityChargesV1.SCOPED_CATALOG + TestTerminalCapacityChargesV1.AUDIT
            val spent = baseline + TestTerminalCapacityChargesV1.AUDIT.scaled(dispositionAudits)
            requireErasure(spent.fitsWithin(reserve) && (if (purged) unused.isZero() else unused == reserve - spent))
        }
        override fun toString(): String = "TestRunErasureRunV1(retained-run-progress-comparison,redacted)"
    }

    class Publication(row: ResultSet, original: TestRunErasureV1, at: Instant) {
        val id = text(row, "event_id", 43)
        val key = text(row, "object_key", 1024)
        val kind = text(row, "event_kind", 32)
        val epoch = long(row, "journal_epoch")
        val targetCount = row.getInt("target_count")
        val routingKey = text(row, "routing_key_id", 128)
        val state = text(row, "state", 16)
        val ref = TestTerminalObjectRefV1(key, text(row, "object_version", 1024), hash(row, "ciphertext_hash"), hash(row, "semantic_hash"))
        val createdAt = time(row, "created_at")
        val objectCreatedAt = time(row, "object_created_at")
        val retainUntil = time(row, "retain_until")
        val verifiedAt = time(row, "verified_at")
        val appliedAt: Instant? = row.getTimestamp("applied_at")?.toInstant()
        val physicalHash = hash(row, "physical_hash")
        val verificationHash = hash(row, "verification_hash")
        private val verificationSize: Int
        init {
            requireErasure(boolean(row, "valid_shape") && uuid(row, "data_scope_id") == original.scope && boolean(row, "test_only") &&
                uuid(row, "writer_generation").toString() == original.writer && row.getString("canonicalizer") == "kcj-1" &&
                createdAt <= verifiedAt && objectCreatedAt <= verifiedAt && verifiedAt <= at && retainUntil > at &&
                objectCreatedAt.nano == 0 && retainUntil.nano == 0 && verifiedAt.nano % 1000 == 0 &&
                (appliedAt == null || appliedAt >= verifiedAt && appliedAt <= at))
            val bytes = bytes(row, "event_bytes", 65536); val proof = bytes(row, "verification_bytes", 65536)
            verificationSize = proof.size
            try {
                requireErasure(Sha256.hex(bytes) == ref.canonicalSha256 && Sha256.hex(proof) == verificationHash)
                if (kind in setOf("OWNER_DELETE", "OWNER_DELETE_ALL", "ADMIN_DELETE", "ADMIN_BATCH_DELETE")) {
                    val expected = ordinaryVerification(original)
                    try { requireErasure(expected.contentEquals(proof)) } finally { expected.fill(0) }
                } else requireErasure(kind in setOf("INSTALLATION_MANIFEST", "TEST_RUN_PURGE"))
            }
            finally { bytes.fill(0); proof.fill(0) }
        }
        private fun ordinaryVerification(original: TestRunErasureV1): ByteArray {
            // Fixed comparison serialization, never a native/readback or successful-VERIFY issuer.
            if (kind == "OWNER_DELETE_ALL") return OwnerDeleteAllVerificationCodecV1(OwnerDeleteAllJournalBindingV1(original.routing)).canonicalBytes(
                OwnerDeleteAllVerificationRecordV1(1, kind, original.scope.toString(), true, original.routing.journalConfiguration.sha256,
                    id, original.writer, epoch, routingKey, key, ref.canonicalSha256, ref.objectVersion, ref.ciphertextSha256,
                    objectCreatedAt.toString(), "COMPLIANCE", retainUntil.toString(), verifiedAt.toString()))
            val codec = when (kind) {
                "OWNER_DELETE" -> TestOwnerDeleteVerificationCodecV1(original.routing)
                "ADMIN_DELETE" -> TestOwnerDeleteVerificationCodecV1.forAdmin(original.routing)
                "ADMIN_BATCH_DELETE" -> TestOwnerDeleteVerificationCodecV1.forAdminBatch(original.routing)
                else -> throw TestRunErasureExceptionV1()
            }
            return codec.canonicalBytes(TestOwnerDeleteVerificationRecordV1(1, kind, original.scope.toString(), true,
                original.routing.journalConfiguration.sha256, id, original.writer, epoch, routingKey, key, ref.canonicalSha256,
                ref.objectVersion, ref.ciphertextSha256, objectCreatedAt.toString(), "COMPLIANCE", retainUntil.toString(), verifiedAt.toString()))
        }
        fun requireOrdinary(evidence: TestRunErasureEvidenceV1): TestRunErasureEvidenceV1.OrdinaryFact {
            val native = evidence.ordinary(key, ref.objectVersion)
            val entry = native.entry
            requireErasure(state == "APPLIED" && appliedAt != null && id == entry.eventId && kind == entry.kind && epoch == entry.epoch &&
                targetCount == native.targetCount && routingKey == native.routingKey && ref.canonicalSha256 == entry.canonicalSha256 &&
                ref.ciphertextSha256 == entry.wireSha256 && objectCreatedAt == entry.lastModified && retainUntil == entry.retainUntil)
            return native
        }
        fun requireTerminal(evidence: TestRunErasureEvidenceV1) {
            val target = evidence.terminalTargets().single { it.id == id && it.kind.name == kind }
            val native = evidence.terminal(key, ref.objectVersion)
            val expectedCount = if (kind == "TEST_RUN_PURGE") 0L else checkNotNull(evidence.document(key).manifest).installationCount
            requireErasure(state == "VERIFIED" && appliedAt == null && ref == target.objectRef && epoch == target.endEpoch &&
                targetCount.toLong() == expectedCount && objectCreatedAt == native.lastModified && retainUntil == native.retainUntil &&
                ref == native.objectRef)
        }
        fun requireTerminalSidecar(frozen: TestTerminalDurableRowV1) {
            val b = frozen.binding
            requireErasure(kind == b.objectKind.name && kind in setOf("INSTALLATION_MANIFEST", "TEST_RUN_PURGE") &&
                id == b.objectId && key == b.objectKey && routingKey == b.routingKeyId && createdAt == b.createdAt &&
                epoch == b.epochStartInclusive && epoch == b.epochEndInclusive && ref.canonicalSha256 == frozen.canonicalSha256 &&
                ref.ciphertextSha256 == frozen.wireSha256 && retainUntil >= checkNotNull(frozen.retainUntil))
            // Both existing terminal producers use this closed comparison schema. The stored raw
            // bytes were hashed above; bind that exact digest/length to the complete expected form
            // without retaining a second raw buffer or constructing either producer's private proof.
            val expected = CanonicalJson.canonicalize(buildJsonObject {
                put("schemaVersion", 1); put("objectKind", kind); put("objectOrdinal", b.objectOrdinal)
                put("dataScopeId", b.run.dataScopeId); put("writerGeneration", b.writerGeneration)
                put("epochStartInclusive", b.epochStartInclusive); put("epochEndInclusive", b.epochEndInclusive)
                put("operationToken", b.operationToken); put("configurationSha256", b.run.configurationSha256)
                put("journalConfigurationSha256", b.journalConfigurationSha256)
                put("objectKey", key); put("objectId", id); put("objectVersion", ref.objectVersion)
                put("canonicalSha256", ref.canonicalSha256); put("ciphertextSha256", ref.ciphertextSha256)
                put("lastModified", objectCreatedAt.toString()); put("requestedRetainUntil", checkNotNull(frozen.retainUntil).toString())
                put("retainUntil", retainUntil.toString()); put("objectLockMode", "COMPLIANCE"); put("verifiedAt", verifiedAt.toString())
            }).toByteArray(Charsets.UTF_8)
            try { requireErasure(expected.size == verificationSize && Sha256.hex(expected) == verificationHash) } finally { expected.fill(0) }
        }
        override fun toString(): String = "TestRunErasurePublicationV1(bounded-native-comparison,redacted)"
    }

    class Recovery(row: ResultSet, original: TestRunErasureV1, p: Publication, at: Instant, native: TestRunErasureEvidenceV1.OrdinaryFact?) {
        val id = text(row, "event_id", 43)
        val physicalHash = hash(row, "physical_hash")
        val promise = vector(row, "reserved_amounts")
        val used: ComplaintCapacityVector? = if (row.getObject("converted_amounts") == null) null else vector(row, "converted_amounts")
        val convertedAt: Instant? = row.getTimestamp("converted_at")?.toInstant()
        private val createdAt = time(row, "created_at")
        init {
            requireErasure(id == p.id && row.getString("publication_ref") == id && uuid(row, "data_scope_id") == original.scope && boolean(row, "test_only") &&
                boolean(row, "finite") && row.getInt("accounting_version") == 1 && createdAt <= at)
            if (native == null) {
                val expected = if (p.kind == "TEST_RUN_PURGE") TestRunPurgeOperationV1.FUTURE else ComplaintCapacityVector.ZERO
                requireErasure(row.getString("state") == "RESERVED" && used == null && convertedAt == null && promise == expected && createdAt == p.createdAt)
            } else {
                val actual = checkNotNull(used)
                requireErasure(row.getString("state") == "CONVERTED" && convertedAt != null && convertedAt >= maxOf(p.verifiedAt, checkNotNull(p.appliedAt), createdAt) && convertedAt <= at &&
                    promise == native.recoveryPromise && actual.fitsWithin(promise) && !actual.isZero())
                val installs = actual[ComplaintCapacityCounter.INSTALLATION_IDS]; val resources = actual[ComplaintCapacityCounter.RESOURCE_IDS]
                val audits = actual[ComplaintCapacityCounter.AUDIT_ROWS]; val applied = actual[ComplaintCapacityCounter.JOURNAL_APPLIED]
                requireErasure(installs in 0..native.ownerCount.toLong() && resources in 0..native.targetBound.toLong() &&
                    audits in 0..(if (p.kind == "OWNER_DELETE_ALL") 113L else native.targetBound + 4L) && applied in 1..4 &&
                    actual == ComplaintCapacityCharges.INSTALLATION_ID.scaled(installs) + ComplaintCapacityCharges.RESOURCE_ID.scaled(resources) +
                        ComplaintCapacityCharges.AUDIT.scaled(audits) + me.manga.kira.backend.complaint.domain.OwnerDeleteCapacityCharges.APPLIED.scaled(applied))
            }
        }
        override fun toString(): String = "TestRunErasureRecoveryV1(native-family-comparison,redacted)"
    }

    /** Current V26 canonical/wire plus retained initial checkpoint; never a new A producer. */
    class Active(row: ResultSet, original: TestRunErasureV1, run: Run) {
        val token = uuid(row, "operation_token")
        val physicalHash = hash(row, "slot_fingerprint")
        private val historyHash = hash(row, "history_fingerprint")
        private lateinit var ref: TestTerminalObjectRefV1
        private val id = text(row, "object_id", 43)
        private lateinit var modifiedAt: Instant
        private lateinit var verifiedAt: Instant
        private lateinit var requestedUntil: Instant
        private lateinit var retainedUntil: Instant
        private var wireBytes = 0L
        init {
            requireErasure(boolean(row, "valid") && run.sealCount == 3L && !run.purged &&
                hash(row, "seal_encoding_hash") == run.context.terminalEncodingSha256)
            val binding = TestTerminalDurableBindingV1(token.toString(), run.context, original.routing.journalConfiguration.sha256,
                TestTerminalDurableKindV1.EPOCH_SEAL, 0, id, text(row, "object_key", 1024), text(row, "routing_key_id", 64), original.writer,
                1, 1, long(row, "preparing_fencing_token"), time(row, "retention_floor"), time(row, "created_at"))
            requireErasure(binding.retentionFloor >= original.acquisition.retention.lastPreRunRestoreHorizon.plusSeconds(31 * 86400L))
            TestTerminalSqlRowV1.restore(row, binding, time(row, "sampled_at")).use { frozen ->
                requireErasure(frozen.state === TestTerminalDurableStateV1.WIRE_FROZEN)
                val canonical = frozen.canonicalBytes()
                val seal = try { TestTerminalJsonV1(original.routing.journalConfiguration).epochSeal(canonical) } finally { canonical.fill(0) }
                val frame = EpochSealFramesV1.frame(listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", original.writer,
                    original.routing.journalConfiguration.ordinaryPrefix, "TEST", original.scope.toString(), "1", "1", "0"))
                val framed = frame.size.toLong()
                val emptyRoot = try { Sha256.hex(frame) } finally { frame.fill(0) }
                requireErasure(seal.sealId == id && seal.eventCount == 0L && seal.eventManifestSha256 == emptyRoot &&
                    seal.preparingFencingToken == binding.preparingFencingToken && seal.epochStartInclusive == 1L && seal.epochEndInclusive == 1L &&
                    seal.writerGeneration == original.writer && seal.dataScopeId == original.scope.toString() && seal.precedingSealSha256.isEmpty())
                val version = text(row, "seal_object_version", 1024)
                ref = TestTerminalObjectRefV1(binding.objectKey, version, checkNotNull(frozen.wireSha256), frozen.canonicalSha256)
                requestedUntil = checkNotNull(frozen.retainUntil)
                retainedUntil = time(row, "seal_retain_until")
                val wire = checkNotNull(frozen.wireBytes())
                try { wireBytes = wire.size.toLong() } finally { wire.fill(0) }
                val checkpoint = bytes(row, "checkpoint_bytes", 65536)
                try {
                    requireErasure(Sha256.hex(checkpoint) == hash(row, "checkpoint_hash"))
                    val passes = CanonicalJson.json.parseToJsonElement(checkpoint.toString(Charsets.UTF_8)).jsonObject.getValue("passes").jsonArray
                    requireErasure(passes.size == 2)
                    fun pass(index: Int): TestActiveInitialCheckpointDocumentV1.Pass {
                        val value = passes[index].jsonObject
                        return TestActiveInitialCheckpointDocumentV1.Pass(Instant.parse(value.getValue("startedAt").jsonPrimitive.content),
                            Instant.parse(value.getValue("completedAt").jsonPrimitive.content))
                    }
                    val document = TestActiveInitialCheckpointDocumentV1(original.scope.toString(), original.process.desiredGeneration,
                        long(row, "checkpoint_fencing_token"), run.context.configurationSha256, original.routing.journalConfiguration.sha256,
                        original.process.databaseIdentity.toString(), original.process.restoreIdentity.toString(), run.context.activationCatalogGeneration,
                        run.context.activationCatalogSha256, original.process.catalogReadback.currentTrustBundleSha256,
                        original.process.catalogActivation.catalogWriterGenerationId, original.writer, token.toString(), binding.objectKey, version,
                        frozen.canonicalSha256, checkNotNull(frozen.wireSha256), emptyRoot, framed, pass(0), pass(1))
                    val expected = document.canonicalBytes()
                    try { requireErasure(expected.contentEquals(checkpoint) && document.startedAt == time(row, "checkpoint_started_at") &&
                        document.completedAt == time(row, "checkpoint_completed_at")) } finally { expected.fill(0) }
                } finally { checkpoint.fill(0) }
                val verification = bytes(row, "seal_verification_bytes", 65536)
                try {
                    val at = time(row, "seal_verified_at"); verifiedAt = at
                    val modified = Instant.parse(CanonicalJson.json.parseToJsonElement(verification.toString(Charsets.UTF_8)).jsonObject.getValue("lastModified").jsonPrimitive.content)
                    modifiedAt = modified
                    requireErasure(modified.nano == 0 && modified <= at && modified.epochSecond in 0..253_402_300_799L)
                    val expected = testOrdinarySealVerificationBytesV1(frozen, version, modified, time(row, "seal_retain_until"), at)
                    try { requireErasure(expected.contentEquals(verification) && Sha256.hex(verification) == hash(row, "seal_verification_hash")) }
                    finally { expected.fill(0) }
                } finally { verification.fill(0) }
            }
        }
        fun requireReference(record: CatalogTestRunTerminalRecordV1) = requireErasure(record.sealSet.records().size == 3 &&
            record.sealSet.records().first().objectRef == ref && record.sealSet.records().first().sealId == id)
        fun requireNative(value: TestPostTerminalInventoryEntryV1) {
            if (value.objectRef.objectKey == ref.objectKey) requireErasure(value.objectRef == ref && value.epochStartInclusive == 1L && value.epochEndInclusive == 1L &&
                value.lastModified == modifiedAt && value.lastModified <= verifiedAt && value.requestedRetainUntil == requestedUntil && value.retainUntil == retainedUntil &&
                value.ciphertextByteCount == wireBytes)
        }
        fun requireSame(other: Active) = requireErasure(token == other.token && physicalHash == other.physicalHash && historyHash == other.historyHash && ref == other.ref && id == other.id)
        override fun toString(): String = "TestRunErasureActiveV1(exact-historical-comparison,redacted)"
    }

    class Sidecar(row: ResultSet, original: TestRunErasureV1, evidence: TestRunErasureEvidenceV1, at: Instant, publication: Publication?) {
        val token = uuid(row, "operation_token")
        val physicalHash = hash(row, "physical_hash")
        val key = text(row, "object_key", 1024)
        init {
            requireErasure(boolean(row, "valid") && uuid(row, "data_scope_id") == original.scope)
            val target = evidence.terminalTargets().single { it.objectRef.objectKey == key && it.source === TestTerminalQuiescenceSourceV1.V21_TERMINAL_INTENT }
            val kind = TestTerminalDurableKindV1.valueOf(target.kind.name)
            val binding = TestTerminalDurableBindingV1(token.toString(), original.runContext, original.routing.journalConfiguration.sha256,
                kind, target.ordinal, text(row, "object_id", 43), key, text(row, "routing_key_id", 64), uuid(row, "writer_generation").toString(),
                long(row, "epoch_start"), long(row, "epoch_end"), long(row, "preparing_fencing_token"), time(row, "retention_floor"), time(row, "created_at"))
            requireErasure(row.getString("object_kind") == target.kind.name && row.getInt("object_ordinal") == target.ordinal &&
                binding.objectId == target.id && binding.writerGeneration == original.writer && binding.epochStartInclusive == target.startEpoch &&
                binding.epochEndInclusive == target.endEpoch && long(row, "activation_catalog_generation") == original.runContext.activationCatalogGeneration &&
                hash(row, "activation_catalog_hash") == original.runContext.activationCatalogSha256 && hash(row, "configuration_hash") == original.runContext.configurationSha256 &&
                hash(row, "journal_configuration_hash") == original.routing.journalConfiguration.sha256 && hash(row, "terminal_encoding_hash") == original.runContext.terminalEncodingSha256 &&
                row.getString("publication_ref") == (if (kind === TestTerminalDurableKindV1.EPOCH_SEAL) null else target.id) &&
                binding.routingKeyId == TestRunErasureEvidenceV1.routingKey(target, original.routing.journalConfiguration) &&
                (publication == null) == (kind === TestTerminalDurableKindV1.EPOCH_SEAL) &&
                binding.createdAt >= original.capturedSnapshot()!!.run.sealedAt &&
                binding.retentionFloor >= original.acquisition.retention.lastPreRunRestoreHorizon.plusSeconds(31 * 86400L))
            TestTerminalSqlRowV1.restore(row, binding, at).use { frozen ->
                requireErasure(frozen.state === TestTerminalDurableStateV1.WIRE_FROZEN && frozen.canonicalSha256 == target.objectRef.canonicalSha256 &&
                    frozen.wireSha256 == target.objectRef.ciphertextSha256)
                publication?.requireTerminalSidecar(frozen)
                val native = evidence.terminal(key, target.objectRef.objectVersion)
                val wire = checkNotNull(frozen.wireBytes())
                try { requireErasure(wire.size.toLong() == native.ciphertextByteCount && frozen.retainUntil == native.requestedRetainUntil &&
                    native.retainUntil >= checkNotNull(frozen.retainUntil) && native.retainUntil > at) } finally { wire.fill(0) }
            }
        }
        override fun toString(): String = "TestRunErasureSidecarV1(exact-native-comparison,redacted)"
    }

    class Applied(row: ResultSet, original: TestRunErasureV1, evidence: TestRunErasureEvidenceV1, at: Instant) {
        val key = text(row, "object_key", 1024)
        val version = text(row, "object_version", 1024)
        val id = text(row, "event_id", 43)
        val physicalHash = hash(row, "physical_hash")
        private val appliedAt = time(row, "applied_at")
        private val native = evidence.ordinary(key, version)
        init {
            val entry = native.entry
            requireErasure(uuid(row, "data_scope_id") == original.scope && boolean(row, "test_only") && boolean(row, "finite") &&
                uuid(row, "writer_generation").toString() == original.writer && long(row, "journal_epoch") == entry.epoch &&
                row.getString("event_kind") == entry.kind && row.getInt("target_count") == native.targetCount &&
                id == entry.eventId && hash(row, "ciphertext_hash") == entry.wireSha256 && appliedAt <= at)
        }
        fun belongsTo(p: Publication): Boolean = native.aliases.any { it.id == p.id && it.key == p.key && it.canonicalSha256 == p.ref.canonicalSha256 }
        fun requirePrimary(pair: Pair<Publication, Recovery>) {
            val (p, l) = pair
            requireErasure(belongsTo(p) && p.state == "APPLIED" && l.id == p.id &&
                appliedAt >= p.verifiedAt && appliedAt <= checkNotNull(l.convertedAt))
        }
        override fun toString(): String = "TestRunErasureAppliedV1(exact-native-comparison,redacted)"
    }

    class Installation(row: ResultSet) {
        val id = uuid(row, "id")
        val state = InstallationIdentityState.valueOf(text(row, "state", 24))
        val terminalAt = row.getTimestamp("terminal_at")?.toInstant()
        val physicalHash = hash(row, "physical_hash")
        init { requireErasure(boolean(row, "valid") && state !== InstallationIdentityState.DELETION_PENDING &&
            (terminalAt != null) == (state in setOf(InstallationIdentityState.RETIRED, InstallationIdentityState.DELETED))) }
        override fun toString(): String = "TestRunErasureInstallationV1(locked-comparison,redacted)"
    }
    class Credential(row: ResultSet) {
        val id = uuid(row, "id")
        val state = InstallationCredentialState.valueOf(text(row, "state", 24))
        val physicalHash = hash(row, "physical_hash")
        init { requireErasure(boolean(row, "valid") && state !== InstallationCredentialState.DELETION_PENDING) }
        override fun toString(): String = "TestRunErasureCredentialV1(no-verifier,redacted)"
    }
    class Counts(row: ResultSet) {
        val installations = long(row, "installations")
        val mutableInstallations = long(row, "mutable_installations")
        val credentials = long(row, "credentials")
        val resources = long(row, "resources")
        val content = long(row, "content")
        val notices = long(row, "notices")
        val receipts = long(row, "receipts")
        val deletionReceipts = long(row, "deletion_receipts")
        val applied = long(row, "applied")
        val publications = long(row, "publications")
        val ordinaryPublications = long(row, "ordinary_publications")
        val reservations = long(row, "reservations")
        val sidecars = long(row, "sidecars")
        val activeSeals = long(row, "active_seals")
        val recurrentSeals = long(row, "recurrent_seals")
        val checkpointArchives = long(row, "checkpoint_archives")
        val queueObservations = long(row, "queue_observations")
        val catalogs = long(row, "catalogs")
        val controls = long(row, "controls")
        val audits = long(row, "audits")
        val remaining: Boolean get() = listOf(mutableInstallations, credentials, resources, content, notices, receipts, deletionReceipts, applied, ordinaryPublications).any { it != 0L }
        val actual: ComplaintCapacityVector get() = ComplaintCapacityCharges.INSTALLATION_ID.scaled(installations) +
            ComplaintCapacityCharges.INSTALLATION_CREDENTIAL.scaled(credentials) + ComplaintCapacityCharges.RESOURCE_ID.scaled(resources) +
            ComplaintCapacityCharges.INSTALLATION_CONTENT_V1.scaled(content) + TestTerminalCapacityChargesV1.SYSTEM_NOTICE.scaled(notices) +
            ComplaintCapacityCharges.NORMAL_RECEIPT.scaled(receipts) + OwnerDeleteAllCapacityCharges.RECEIPT.scaled(deletionReceipts) +
            OwnerDeleteAllCapacityCharges.APPLIED.scaled(applied) + OwnerDeleteAllCapacityCharges.PUBLICATION.scaled(publications) +
            OwnerDeleteAllCapacityCharges.RESERVATION.scaled(reservations) + TestTerminalCapacityChargesV1.SIDECAR.scaled(sidecars) +
            ComplaintCapacityVector.units(ComplaintCapacityCounter.STORAGE_BYTES, Math.addExact(Math.multiplyExact(activeSeals, 2097152L), Math.multiplyExact(queueObservations, 8192L))) +
            TestActiveRecurrentStorageV1.INTENT.scaled(recurrentSeals) + TestActiveRecurrentStorageV1.HISTORY.scaled(checkpointArchives) +
            TestTerminalCapacityChargesV1.SCOPED_CATALOG.scaled(catalogs) + TestTerminalCapacityChargesV1.CONTROL.scaled(controls) +
            TestTerminalCapacityChargesV1.ACTIVE_RUN + TestTerminalCapacityChargesV1.TERMINAL_RUN_DELTA + ComplaintCapacityCharges.AUDIT.scaled(audits)
        fun requireShape(snapshot: Snapshot) {
            requireErasure(installations == snapshot.run.enrolled && catalogs == 2L && reservations == publications &&
                controls == (if (snapshot.run.purged) 0L else 1L) &&
                activeSeals == (if (snapshot.active == null && snapshot.recurrent == null) 0L else 1L) &&
                recurrentSeals == (snapshot.recurrent?.count?.minus(1)?.toLong() ?: 0L) &&
                checkpointArchives == (snapshot.recurrent?.count?.toLong() ?: 0L) &&
                queueObservations == (if (snapshot.queueHash == null) 0L else 1L))
            if (snapshot.run.purged) requireErasure(!remaining && sidecars == 0L && publications == 0L)
        }
        override fun toString(): String = "TestRunErasureCountsV1(physical-logical-price-comparison,redacted)"
    }
    class Audits(row: ResultSet, snapshot: Snapshot) {
        val dispositions = long(row, "dispositions")
        val purges = long(row, "purges")
        init { requireErasure(boolean(row, "valid") && purges == (if (snapshot.run.purged) 1L else 0L)); snapshot.run.requireUnused(dispositions) }
        override fun toString(): String = "TestRunErasureAuditsV1(fixed-scope-only,redacted)"
    }

    fun boolean(row: ResultSet, name: String): Boolean = row.getBoolean(name).also { requireErasure(!row.wasNull()) }
    fun long(row: ResultSet, name: String): Long = row.getLong(name).also { requireErasure(!row.wasNull()) }
    fun uuid(row: ResultSet, name: String): UUID = checkNotNull(row.getObject(name, UUID::class.java))
    fun time(row: ResultSet, name: String): Instant = checkNotNull(row.getTimestamp(name)).toInstant().also { requireErasure(it.epochSecond in 0..253_402_300_799L) }
    fun hash(row: ResultSet, name: String): String = bytes(row, name, 32, 32).let { try { HexFormat.of().formatHex(it) } finally { it.fill(0) } }
    fun text(row: ResultSet, name: String, maximum: Int): String = checkNotNull(row.getString(name)).also { requireErasure(it.isNotEmpty() && it.length <= maximum) }
    fun bytes(row: ResultSet, name: String, maximum: Int, minimum: Int = 1): ByteArray = checkNotNull(row.getBytes(name)).also { requireErasure(it.size in minimum..maximum) }
    fun vector(row: ResultSet, name: String): ComplaintCapacityVector = TestOrdinaryDrainRowsV1.vector(row, name)
}
