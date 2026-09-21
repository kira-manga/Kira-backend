package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.builtins.ListSerializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogObjectMetadata
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogTestRunTerminalHistoryV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationManifestV3
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalAccountingPlanV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestInstallationManifestOperationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainRowsV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPurgeOperationV1
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogTestRunActivationParser
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** Bounded exact row copies only, not an activation/terminal proof or current-use capability. */
internal class CatalogTestRunTerminalMutationV1(row: ResultSet) {
    val token: UUID = checkNotNull(row.getObject("operation_token", UUID::class.java))
    val scope: UUID = checkNotNull(row.getObject("data_scope_id", UUID::class.java))
    val operation: String = checkNotNull(row.getString("operation_type"))
    val generation: Long = row.requiredTestActivationLong("successor_generation")
    private val predecessorGeneration = row.requiredTestActivationLong("predecessor_generation")
    private val predecessorHash = bytes(row, "predecessor_hash", 32)
    private val catalogWriter = checkNotNull(row.getObject("catalog_writer_generation", UUID::class.java))
    private val unsigned = bytes(row, "unsigned_bytes", 1, 131072)
    private val unsignedDigest = bytes(row, "unsigned_hash", 32)
    private val approvals = bytes(row, "approval_bytes", 1, 4096)
    private val approvalHash = bytes(row, "approval_hash", 32)
    private val signer = checkNotNull(row.getString("signer_one_id"))
    private val algorithm = checkNotNull(row.getString("signer_one_algorithm"))
    private val signature = row.getBytes("signature_bytes")?.copyOf()
    private val envelope = row.getBytes("envelope_bytes")?.copyOf()
    private val envelopeHash = row.getBytes("envelope_hash")?.copyOf()
    private val key = checkNotNull(row.getString("object_key"))
    val createdAt: Instant = checkNotNull(row.getTimestamp("created_at")).toInstant()
    val completed: CatalogTestRunActivationCompletedTailV1? = CatalogTestRunActivationCompletedTailV1.copy(row, projection = true)
    val signed: Boolean get() = signature != null
    val projectedAt: Instant? get() = completed?.projectedAt
    val head: CatalogLocalHead? get() = envelopeHash?.let { CatalogLocalHead(generation, HexFormat.of().formatHex(it)) }

    init {
        requireTestTerminalCatalog(row.requiredTestActivationBoolean("valid") && operation in setOf("TEST_RUN_ACTIVATION", "TEST_RUN_TERMINAL") &&
            predecessorGeneration == generation - 1 && (signature == null && envelope == null && envelopeHash == null ||
                signature?.size == 384 && envelope != null && envelope.size in 1..131072 && envelopeHash?.size == 32) &&
            (completed == null || signed) && (operation != "TEST_RUN_ACTIVATION" || completed?.projectedAt != null))
    }

    fun unsignedBytes(): ByteArray = unsigned.copyOf()
    fun unsignedHash(): ByteArray = unsignedDigest.copyOf()

    fun requireFrozen(input: CatalogTestRunTerminalFrozenV1) {
        val values = input.preparedArguments()
        requireTestTerminalCatalog(operation == "TEST_RUN_TERMINAL" && token == values[0] && scope == values[1] &&
            predecessorGeneration == values[2] && predecessorHash.contentEquals(values[3] as ByteArray) && generation == values[4] &&
            catalogWriter == values[5] && approvals.contentEquals(values[6] as ByteArray) && approvalHash.contentEquals(values[7] as ByteArray) &&
            unsigned.contentEquals(values[8] as ByteArray) && unsignedDigest.contentEquals(values[9] as ByteArray) &&
            signer == values[10] && algorithm == values[11] && key == values[12] && createdAt == input.createdAt)
    }

    fun requireSigned(value: CatalogTestRunTerminalSignedV1) {
        requireFrozen(value.frozen)
        value.requireExact(checkNotNull(signature), checkNotNull(envelope), checkNotNull(envelopeHash))
    }

    fun signatureInput(process: me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1,
        frozen: CatalogTestRunTerminalFrozenV1): CatalogTestRunTerminalSignedV1? {
        requireConnectionFree(); requireFrozen(frozen)
        return signature?.let { CatalogTestRunTerminalSignedV1.verify(process, frozen, it, checkNotNull(envelope)) }
    }

    fun requireImmutable(other: CatalogTestRunTerminalMutationV1) {
        requireTestTerminalCatalog(token == other.token && scope == other.scope && operation == other.operation && generation == other.generation &&
            predecessorGeneration == other.predecessorGeneration && predecessorHash.contentEquals(other.predecessorHash) && catalogWriter == other.catalogWriter &&
            unsigned.contentEquals(other.unsigned) && unsignedDigest.contentEquals(other.unsignedDigest) && approvals.contentEquals(other.approvals) &&
            approvalHash.contentEquals(other.approvalHash) && signer == other.signer && algorithm == other.algorithm && key == other.key && createdAt == other.createdAt)
    }

    fun requireSame(other: CatalogTestRunTerminalMutationV1) {
        requireSignatureSame(other)
        requireTestTerminalCatalog((completed == null) == (other.completed == null))
        completed?.requireSame(checkNotNull(other.completed))
    }

    private fun requireSignatureSame(other: CatalogTestRunTerminalMutationV1) {
        requireImmutable(other)
        requireTestTerminalCatalog(signature.contentEquals(other.signature) && envelope.contentEquals(other.envelope) && envelopeHash.contentEquals(other.envelopeHash))
    }

    fun requireSignatureTransition(prior: CatalogTestRunTerminalMutationV1) {
        requireImmutable(prior)
        requireTestTerminalCatalog(signed && (completed == null) == (prior.completed == null))
        if (prior.signed) requireSignatureSame(prior)
        completed?.requireSame(checkNotNull(prior.completed))
    }

    fun requireCompletionTransition(prior: CatalogTestRunTerminalMutationV1) {
        requireSignatureSame(prior)
        val after = checkNotNull(completed)
        if (prior.completed == null) requireTestTerminalCatalog(after.projectedAt == null)
        else after.requireSame(prior.completed)
    }

    fun requireProjectionTransition(prior: CatalogTestRunTerminalMutationV1) {
        requireSignatureSame(prior)
        checkNotNull(completed).requireProjectionTransition(checkNotNull(prior.completed))
    }

    /** Genuine raw V3 and both native metadata are checked by the caller's complete prefix fold. */
    fun requireRawActivation(raw: ByteArray, primary: CatalogObjectMetadata, replica: CatalogObjectMetadata,
        expected: CatalogTestRunTerminalCanonicalV4, maximumRecords: Int): OfflineCatalogTestRunActivationManifestV3 {
        requireConnectionFree()
        requireTestTerminalCatalog(operation == "TEST_RUN_ACTIVATION" && raw.contentEquals(envelope))
        val parsed = OfflineCatalogTestRunActivationParser.parse(raw, maximumRecords, expected.maximumDocumentBytes)
        val manifest = parsed.manifest
        expected.activation.requireManifest(manifest)
        requireTestTerminalCatalog(manifest.operationToken == token.toString() && manifest.generation == generation &&
            manifest.activationRecord.run.testRunId == scope.toString() && manifest.catalogWriterGenerationId == catalogWriter.toString() &&
            manifest.previousEnvelopeSha256 == HexFormat.of().formatHex(predecessorHash) &&
            Instant.ofEpochSecond(manifest.creation.createdAtEpochSecond) == createdAt && key == CatalogReadbackProtocol.key(generation) &&
            CanonicalJson.canonicalize(OfflineCatalogTestRunActivationManifestV3.serializer(), manifest).toByteArray(Charsets.UTF_8).contentEquals(unsigned) &&
            CanonicalJson.canonicalize(ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), manifest.approvals).toByteArray(Charsets.UTF_8).contentEquals(approvals))
        val member = parsed.signatures.single()
        requireTestTerminalCatalog(member.keyId == signer && member.algorithmId == algorithm && Base64.getDecoder().decode(member.signatureBase64).contentEquals(signature))
        val hash = checkNotNull(head).envelopeSha256
        checkNotNull(completed).requireCustody(primary.requestBinding.versionId, checkNotNull(primary.retainUntilEpochSecond),
            copyEvidence(primary, hash), copyEvidence(replica, hash))
        return manifest
    }

    fun requireDual(proof: CatalogTestRunTerminalDeliveryReadbackV1) {
        requireTestTerminalCatalog(proof.state === CatalogTestRunTerminalDeliveryReadbackV1.State.DUAL_COPY)
        checkNotNull(completed).requireCustody(checkNotNull(proof.objectVersion), checkNotNull(proof.retainUntilEpochSecond),
            checkNotNull(proof.primaryEvidenceBytes()), checkNotNull(proof.replicaEvidenceBytes()))
    }

    override fun toString(): String = "CatalogTestRunTerminalMutationV1(bounded-row-facts,no-continuation-authority)"
}

/** Both controls are copied under their prescribed locks. Only the global lease/head/pending fields may move. */
internal class CatalogTestRunTerminalControlV1(row: ResultSet) {
    val scope: UUID = checkNotNull(row.getObject("data_scope_id", UUID::class.java))
    val head = CatalogLocalHead(row.requiredTestActivationLong("accepted_catalog_generation"), HexFormat.of().formatHex(bytes(row, "accepted_catalog_hash", 32)))
    val pendingToken: UUID? = row.getObject("pending_projection_token", UUID::class.java)
    val leaseToken = row.requiredTestActivationLong("lease_token")
    private val core = checkNotNull(row.getString("core_preimage"))
    private val coreHash = bytes(row, "core_hash", 32)
    init { requireTestTerminalCatalog(row.requiredTestActivationBoolean("valid") && core.length in 1..524288) }
    fun requireCore(other: CatalogTestRunTerminalControlV1) = requireTestTerminalCatalog(scope == other.scope && core == other.core && coreHash.contentEquals(other.coreHash))
    fun requireSame(other: CatalogTestRunTerminalControlV1) {
        requireCore(other)
        requireTestTerminalCatalog(head == other.head && pendingToken == other.pendingToken)
        if (scope != UUID(0L, 0L)) requireTestTerminalCatalog(leaseToken == other.leaseToken)
    }
    fun coreHashBytes(): ByteArray = coreHash.copyOf()
    override fun toString(): String = "CatalogTestRunTerminalControlV1(bounded-exact-preimage,no-authority)"
}

internal class CatalogTestRunTerminalSnapshotV1(
    val global: CatalogTestRunTerminalControlV1,
    val scoped: CatalogTestRunTerminalControlV1,
    val activation: CatalogTestRunTerminalMutationV1,
    val history: CatalogTestRunActivationHistoryV1,
    val terminal: CatalogTestRunTerminalMutationV1?,
    val run: CatalogTestRunTerminalRunV1,
    val counters: CatalogTestRunActivationProjectionCountersV1,
    val activeHistory: CatalogTestRunTerminalActiveHistoryV1,
) {
    fun requireHead() {
        requireTestTerminalCatalog(activation.operation == "TEST_RUN_ACTIVATION" && scoped.scope == activation.scope &&
            scoped.head == activation.head && scoped.pendingToken == null)
        val final = terminal
        if (final == null || final.completed == null) requireTestTerminalCatalog(global.head == activation.head && global.pendingToken == null)
        else requireTestTerminalCatalog(global.head == final.head && global.pendingToken == if (final.projectedAt == null) final.token else null)
    }
    fun requireCore(other: CatalogTestRunTerminalSnapshotV1) {
        global.requireCore(other.global); scoped.requireSame(other.scoped); activation.requireSame(other.activation); history.requireSame(other.history)
        run.requireCore(other.run)
        activeHistory.requireSame(other.activeHistory)
        requireHead(); other.requireHead()
    }
    fun requireSame(other: CatalogTestRunTerminalSnapshotV1) {
        requireCore(other); global.requireSame(other.global); run.requireSame(other.run); counters.requireSame(other.counters)
        requireTestTerminalCatalog((terminal == null) == (other.terminal == null))
        terminal?.requireSame(checkNotNull(other.terminal))
    }
    override fun toString(): String = "CatalogTestRunTerminalSnapshotV1(detached,no-authority)"
}

/** Complete bounded run comparison; no progress/denial is admitted by reading a stored blob. */
internal class CatalogTestRunTerminalRunV1(row: ResultSet, maximumVersions: Long) {
    val scope: UUID = checkNotNull(row.getObject("data_scope_id", UUID::class.java))
    val state: String = checkNotNull(row.getString("state"))
    val sealedAt: Instant = checkNotNull(row.getTimestamp("sealed_at")).toInstant()
    val purgingAt: Instant? = row.getTimestamp("purging_at")?.toInstant()
    val installationLimit = row.requiredTestActivationLong("installation_limit")
    val enrolled = row.requiredTestActivationLong("enrolled_count")
    val reserve = TestOrdinaryDrainRowsV1.vector(row, "original_reserve")
    val unused = TestOrdinaryDrainRowsV1.vector(row, "unused_reserve")
    val plan = TestTerminalAccountingPlanV1(installationLimit, maximumVersions)
    private val progress = bytes(row, "progress_bytes", 1, 51291)
    private val seals = bytes(row, "seal_set_bytes", 1, 65536)
    private val ordinaryEpoch = row.requiredTestActivationLong("final_ordinary_epoch")
    private val terminalEpoch = row.requiredTestActivationLong("terminal_seal_epoch")
    private val sealRoot = HexFormat.of().formatHex(bytes(row, "generation_seal_root", 32))
    val sealCount = row.requiredTestActivationLong("generation_seal_count")
    private val core = checkNotNull(row.getString("core_preimage"))
    private val effect: List<Any?> = listOf(
        nullableLong(row, "event_manifest_count"), hexOrNull(row, "event_manifest_root"),
        nullableLong(row, "installation_manifest_count"), hexOrNull(row, "installation_manifest_root"), nullableLong(row, "installation_chunk_count"),
        nullableLong(row, "retired_count"), nullableLong(row, "deleted_count"), row.getString("terminal_event_id"), row.getString("terminal_object_key"),
        row.getString("terminal_object_version"), hexOrNull(row, "terminal_ciphertext_hash"), nullableLong(row, "terminal_catalog_generation"), hexOrNull(row, "terminal_catalog_hash"),
    )
    init {
        requireTestTerminalCatalog(row.requiredTestActivationBoolean("valid") && state in setOf("SEALED", "PURGING") &&
            core.length in 1..524288 && reserve == plan.originalUnusedReserve && sealCount in 2L..3L)
    }
    fun requireFrozen(input: CatalogTestRunTerminalFrozenV1) {
        val record = input.manifest().terminalRecord
        requireTestTerminalCatalog(scope == input.scope && ordinaryEpoch == input.ordinaryEpoch && terminalEpoch == input.terminalEpoch &&
            sealedAt.epochSecond == record.sealedAtEpochSecond && enrolled == record.installationManifest.summary.installationCount &&
            sealCount == record.sealSet.records().size.toLong() &&
            progress.contentEquals(input.progressBytes()) && seals.contentEquals(input.sealSetBytes()) &&
            sealRoot == CatalogTestRunTerminalHistoryV1.sealHead(record.sealSet.records()).sha256)
    }
    fun requirePayment(prepared: Boolean, projected: Boolean) {
        requireTestTerminalCatalog(!projected || prepared)
        val chunks = me.manga.kira.backend.complaint.domain.terminal.TestTerminalSyntaxV1.chunkCount(enrolled).toLong()
        val beforeCatalog = TestTerminalCapacityChargesV1.INSTALLATION_SHARE.scaled(enrolled) + TestTerminalCapacityChargesV1.AUDIT +
            TestTerminalCapacityChargesV1.TERMINAL_RUN_DELTA + TestTerminalCapacityChargesV1.SIDECAR.scaled(2) +
            TestInstallationManifestOperationV1.MANIFEST_ACTUAL.scaled(chunks) + TestRunPurgeOperationV1.ACTUAL + TestRunPurgeOperationV1.FUTURE
        val paid = beforeCatalog + (if (prepared) TestTerminalCapacityChargesV1.SCOPED_CATALOG else ComplaintCapacityVector.ZERO) +
            (if (projected) TestTerminalCapacityChargesV1.AUDIT else ComplaintCapacityVector.ZERO)
        requireTestTerminalCatalog(paid.fitsWithin(reserve) && unused == reserve - paid && (state == "PURGING") == projected)
    }
    fun requireProjected(input: CatalogTestRunTerminalFrozenV1, signed: CatalogTestRunTerminalSignedV1, at: Instant) {
        requireFrozen(input)
        val record = input.manifest().terminalRecord
        val purge = record.purge
        val summary = record.installationManifest.summary
        requireTestTerminalCatalog(state == "PURGING" && purgingAt == at && effect == listOf(
            purge.document.preTerminalInventory.count, purge.document.preTerminalInventory.sha256,
            summary.installationCount, summary.installationsSha256, summary.chunkCount.toLong(), summary.retiredCount, summary.deletedCount,
            purge.document.eventId, purge.objectRef.objectKey, purge.objectRef.objectVersion, purge.objectRef.ciphertextSha256,
            input.generation, signed.envelopeSha256,
        ))
    }
    fun requireCore(other: CatalogTestRunTerminalRunV1) = requireTestTerminalCatalog(scope == other.scope && core == other.core && reserve == other.reserve)
    fun coreSha256(): String = me.manga.kira.backend.common.Sha256.hexUtf8(core)
    fun requireSame(other: CatalogTestRunTerminalRunV1) {
        requireCore(other)
        requireTestTerminalCatalog(state == other.state && unused == other.unused && purgingAt == other.purgingAt && effect == other.effect)
    }
    override fun toString(): String = "CatalogTestRunTerminalRunV1(exact-bounded-run,no-denial-authority)"
    private fun nullableLong(row: ResultSet, name: String): Long? = row.getLong(name).let { if (row.wasNull()) null else it }
    private fun hexOrNull(row: ResultSet, name: String): String? = row.getBytes(name)?.let { requireTestTerminalCatalog(it.size == 32); HexFormat.of().formatHex(it) }
}

private fun bytes(row: ResultSet, name: String, size: Int): ByteArray = bytes(row, name, size, size)
private fun bytes(row: ResultSet, name: String, minimum: Int, maximum: Int): ByteArray =
    checkNotNull(row.getBytes(name)).also { requireTestTerminalCatalog(it.size in minimum..maximum) }.copyOf()
