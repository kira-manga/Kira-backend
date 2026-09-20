package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.builtins.ListSerializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceComplaintMaintenanceGateV1
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalAccountingPlanV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialPrefixV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationCompletedTailV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationProjectionCountersV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReadbackV3
import me.manga.kira.backend.complaint.infrastructure.catalog.copyEvidence
import me.manga.kira.backend.complaint.infrastructure.catalog.requiredTestActivationBoolean
import me.manga.kira.backend.complaint.infrastructure.catalog.requiredTestActivationLong
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainRowsV1
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogTestRunActivationParser
import me.manga.kira.backend.security.TestTerminalJsonV1
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** Bounded detached facts only. None of these row/argument types issues a registration or continuation. */
internal class TestNamespaceRecoveryRegistrationControlV1(row: ResultSet) {
    val scope: UUID = checkNotNull(row.getObject("data_scope_id", UUID::class.java))
    val generation = row.requiredTestActivationLong("accepted_catalog_generation")
    val hash = boundedRecoveryBytes(row, "accepted_catalog_hash", 32, 32)
    val leaseToken = row.requiredTestActivationLong("lease_token")
    private val fingerprint = boundedRecoveryBytes(row, "fingerprint", 32, 32)
    init { requireRegistration(row.requiredTestActivationBoolean("valid")) }
    fun requireSame(other: TestNamespaceRecoveryRegistrationControlV1) = requireRegistration(
        scope == other.scope && generation == other.generation && hash.contentEquals(other.hash) && leaseToken == other.leaseToken &&
            fingerprint.contentEquals(other.fingerprint),
    )
}

internal class TestNamespaceRecoveryRegistrationTailV1(row: ResultSet, val scope: UUID) {
    val token: UUID = checkNotNull(row.getObject("operation_token", UUID::class.java))
    val generation = row.requiredTestActivationLong("successor_generation")
    val unsigned = boundedRecoveryBytes(row, "unsigned_bytes", 1, 131072)
    val unsignedHash = boundedRecoveryBytes(row, "unsigned_hash", 32, 32)
    private val envelope = boundedRecoveryBytes(row, "envelope_bytes", 1, 131072)
    val envelopeHash = boundedRecoveryBytes(row, "envelope_hash", 32, 32)
    private val approvals = boundedRecoveryBytes(row, "approval_bytes", 1, 4096)
    private val signer = checkNotNull(row.getString("signer_one_id"))
    private val algorithm = checkNotNull(row.getString("signer_one_algorithm"))
    private val signature = boundedRecoveryBytes(row, "signature_bytes", 384, 384)
    private val objectKey = checkNotNull(row.getString("object_key"))
    private val createdAt: Instant = checkNotNull(row.getTimestamp("created_at")).toInstant()
    val completed = checkNotNull(CatalogTestRunActivationCompletedTailV1.copy(row, projection = true))
    val head = CatalogLocalHead(generation, HexFormat.of().formatHex(envelopeHash))

    init {
        requireRegistration(row.requiredTestActivationBoolean("valid") && completed.projectedAt != null &&
            Sha256.hex(unsigned) == HexFormat.of().formatHex(unsignedHash) && Sha256.hex(envelope) == head.envelopeSha256)
    }

    fun requireGate(gate: PersistenceComplaintMaintenanceGateV1) =
        requireRegistration(gate.matchesProjected(token, scope, unsigned, unsignedHash))

    fun requireRaw(proof: CatalogTestRunActivationReadbackV3, maximumManifestRecords: Int) {
        requireConnectionFree()
        val manifest = proof.manifest()
        val parsed = OfflineCatalogTestRunActivationParser.parse(proof.signedEnvelopeBytes(), maximumManifestRecords)
        val signed = parsed.signatures.single()
        requireRegistration(proof.expectedHead == head && proof.signedEnvelopeBytes().contentEquals(envelope) &&
            proof.chain.canonicalManifestBytes.contentEquals(unsigned) && manifest.operationToken == token.toString() &&
            manifest.activationRecord.run.testRunId == scope.toString() && manifest.generation == generation &&
            createdAt == Instant.ofEpochSecond(manifest.creation.createdAtEpochSecond) && objectKey == CatalogReadbackProtocol.key(generation) &&
            signed.keyId == signer && signed.algorithmId == algorithm && Base64.getDecoder().decode(signed.signatureBase64).contentEquals(signature) &&
            CanonicalJson.canonicalize(ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), manifest.approvals)
                .toByteArray(Charsets.UTF_8).contentEquals(approvals))
        completed.requireCustody(proof.objectVersion, proof.retainUntilEpochSecond,
            copyEvidence(proof.primaryMetadata, proof.tail.envelopeSha256), copyEvidence(proof.replicaMetadata, proof.tail.envelopeSha256))
    }

    fun requireSame(other: TestNamespaceRecoveryRegistrationTailV1) {
        requireRegistration(token == other.token && scope == other.scope && head == other.head && unsigned.contentEquals(other.unsigned) &&
            unsignedHash.contentEquals(other.unsignedHash) && envelope.contentEquals(other.envelope) && approvals.contentEquals(other.approvals) &&
            signer == other.signer && algorithm == other.algorithm && signature.contentEquals(other.signature) && objectKey == other.objectKey && createdAt == other.createdAt)
        completed.requireSame(other.completed)
    }
}

internal class TestNamespaceRecoveryRegistrationBindingV1(
    process: VersionBoundTestNamespaceProcessV1, val tail: TestNamespaceRecoveryRegistrationTailV1, val installationLimit: Long,
) {
    val scope = process.consumers.journalConfiguration.scope.id
    val configurationHash = HexFormat.of().formatHex(process.configurationHashBytes())
    val plan = TestTerminalAccountingPlanV1(installationLimit, process.consumers.journalConfiguration.declaration().limits.capacity.maximumRetainedVersions)
    val runContext = TestTerminalRunContextV1(scope.toString(), tail.generation, tail.head.envelopeSha256, configurationHash, TestTerminalProfileV1.encodingSha256)
    private val run: Array<Any?> = arrayOf(scope, process.configurationHashBytes(), installationLimit, tail.generation, tail.envelopeHash.copyOf(),
        Timestamp.from(checkNotNull(tail.completed.projectedAt)), plan.originalUnusedReserve.toLongArray().joinToString(",", "{", "}"))
    private val controls: Array<Any?> = arrayOf(scope, process.desiredGeneration, process.implementationSchema, process.configurationHashBytes(),
        process.databaseIdentity, process.restoreIdentity, UUID.fromString(process.consumers.journalConfiguration.declaration().writer.generationId),
        UUID.fromString(process.catalogActivation.initialWriterRegistry().catalogWriter.generationId),
        HexFormat.of().parseHex(process.catalogReadback.currentTrustBundleSha256), tail.generation, tail.envelopeHash.copyOf())
    init { requireRegistration(installationLimit > 0 && tail.scope == scope) }
    fun runArguments(): Array<Any?> = copy(run)
    fun controlArguments(): Array<Any?> = copy(controls)
    private fun copy(values: Array<Any?>): Array<Any?> = values.map { value -> when (value) {
        is ByteArray -> value.copyOf()
        is Timestamp -> Timestamp.from(value.toInstant())
        else -> value
    } }.toTypedArray()
}

/** Current SEALED remainder, not fresh PROJECT's zero-enrollment/initial-global-counter snapshot. */
internal class TestNamespaceRecoveryRegistrationRunV1(row: ResultSet, binding: TestNamespaceRecoveryRegistrationBindingV1,
    process: VersionBoundTestNamespaceProcessV1, val control: TestOrdinaryDrainRowsV1.Control, leaseToken: Long) {
    val sealedAt: Instant = checkNotNull(row.getTimestamp("sealed_at")).toInstant()
    val enrolled = row.requiredTestActivationLong("enrolled_count")
    val reserve = TestOrdinaryDrainRowsV1.vector(row, "original_reserve")
    val unused = TestOrdinaryDrainRowsV1.vector(row, "unused_reserve")
    private val bytes = row.getBytes("progress_bytes")
    private val hash = row.getBytes("progress_hash")
    val progress = bytes?.let { TestTerminalJsonV1(process.consumers.journalConfiguration).progress(it) }
    val sealSet = row.getBytes("seal_set_bytes")
    private val sealHash = row.getBytes("seal_set_hash")

    init {
        requireRegistration(row.requiredTestActivationBoolean("valid") && reserve == binding.plan.originalUnusedReserve &&
            row.requiredTestActivationLong("installation_limit") == binding.installationLimit && enrolled in 0..binding.installationLimit)
        requireRegistration((bytes == null && hash == null) || (bytes != null && hash?.size == 32 && Sha256.hex(bytes) == HexFormat.of().formatHex(hash)))
        if (progress != null) {
            requireRegistration(progress.context() == binding.runContext && progress.installationReads().isEmpty() && progress.completedCuts().size == 1 && control.sequence == 1L)
            val cut = progress.completedCuts().single()
            val j = process.consumers.journalConfiguration.declaration()
            requireRegistration(cut.prefixKind === TestTerminalDenialPrefixV1.ORDINARY && cut.writerGeneration == j.writer.generationId &&
                cut.databaseIdentity == process.databaseIdentity.toString() && cut.restoreIdentity == process.restoreIdentity.toString() &&
                cut.desiredGeneration == process.desiredGeneration && cut.fencingToken in 1..leaseToken &&
                cut.epochStartInclusive == 1L && cut.epochEndInclusive == control.cutoff &&
                cut.framedByteCount <= j.limits.capacity.maximumScanStagingBytes &&
                cut.denial.firstInventory.versionCount <= j.limits.capacity.maximumRetainedVersions)
        }
        requireRegistration((sealSet == null && sealHash == null) || (sealSet != null && sealHash?.size == 32 && progress != null &&
            Sha256.hex(sealSet) == HexFormat.of().formatHex(sealHash)))
    }

    fun requireRemainder(scanCharge: ComplaintCapacityVector, sidecars: Long) {
        requireRegistration(sidecars in 0..1 && (sealSet == null || sidecars == 1L))
        val spent = TestTerminalCapacityChargesV1.INSTALLATION_SHARE.scaled(enrolled) + TestTerminalCapacityChargesV1.AUDIT + scanCharge +
            TestTerminalCapacityChargesV1.SIDECAR.scaled(sidecars) +
            (if (progress == null) ComplaintCapacityVector.ZERO else TestTerminalCapacityChargesV1.TERMINAL_RUN_DELTA)
        requireRegistration(spent.fitsWithin(reserve) && unused == reserve - spent)
    }
}

internal class TestNamespaceRecoveryRegistrationSnapshotV1(
    val tail: TestNamespaceRecoveryRegistrationTailV1,
    val binding: TestNamespaceRecoveryRegistrationBindingV1,
    val history: CatalogTestRunActivationHistoryV1,
    private val global: TestNamespaceRecoveryRegistrationControlV1,
    private val scoped: TestNamespaceRecoveryRegistrationControlV1,
    private val counters: CatalogTestRunActivationProjectionCountersV1,
    private val runFingerprint: ByteArray,
    private val scanFingerprints: List<ByteArray>,
    private val sidecarFingerprints: List<ByteArray>,
) {
    fun requireSame(other: TestNamespaceRecoveryRegistrationSnapshotV1) {
        tail.requireSame(other.tail); global.requireSame(other.global); scoped.requireSame(other.scoped); history.requireSame(other.history); counters.requireSame(other.counters)
        requireRegistration(binding.installationLimit == other.binding.installationLimit && binding.configurationHash == other.binding.configurationHash &&
            runFingerprint.contentEquals(other.runFingerprint) && scanFingerprints.size == other.scanFingerprints.size &&
            scanFingerprints.indices.all { scanFingerprints[it].contentEquals(other.scanFingerprints[it]) } && sidecarFingerprints.size == other.sidecarFingerprints.size &&
            sidecarFingerprints.indices.all { sidecarFingerprints[it].contentEquals(other.sidecarFingerprints[it]) })
    }
}

internal fun boundedRecoveryBytes(row: ResultSet, column: String, minimum: Int, maximum: Int): ByteArray =
    checkNotNull(row.getBytes(column)).also { requireRegistration(it.size in minimum..maximum) }.copyOf()
