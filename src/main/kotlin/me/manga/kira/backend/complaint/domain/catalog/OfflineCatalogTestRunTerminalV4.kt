package me.manga.kira.backend.complaint.domain.catalog

import kotlinx.serialization.Serializable
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialPrefixV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalManifestSummaryV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProgressV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPurgeV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRoleV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealSetV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSyntaxV1

/** Distinct first terminal schema. No old V3/empty-history parser accepts this envelope. */
@Serializable
internal data class OfflineCatalogTestRunTerminalEnvelopeV4(
    val schemaVersion: Int,
    val manifest: OfflineCatalogTestRunTerminalManifestV4,
    val signatures: List<OfflineCatalogGenesisSignatureV1>,
)

@Serializable
internal data class OfflineCatalogTestRunTerminalManifestV4(
    val schemaVersion: Int,
    val profile: String,
    val canonicalizerId: String,
    val operation: String,
    val operationToken: String,
    val generation: Long,
    val previousEnvelopeSha256: String,
    val initialTrustBundleEnvelopeSha256: String,
    val catalogWriterGenerationId: String,
    val requiredSignerPolicy: CatalogSignerPolicyV1,
    val creation: OfflineCatalogGenesisCreationV1,
    val approvals: List<OfflineCatalogGenesisApprovalV1>,
    val oldestRestoreTimeEpochSecond: Long,
    val initialWriterRegistry: OfflineBootstrapRegistryV1,
    val restoreInventory: CatalogRestoreInventoryV1,
    val inventoryDelta: CatalogInventoryDeltaV1,
    val history: CatalogTestRunTerminalHistoryV1,
    val terminalRecord: CatalogTestRunTerminalRecordV1,
)

/** No current envelope/signature/history hash: each history batch has a noncyclic preimage. */
@Serializable
internal data class CatalogTestRunTerminalRecordV1(
    val operationToken: String,
    val generation: Long,
    val previousEnvelopeSha256: String,
    val sealedAtEpochSecond: Long,
    val closure: CatalogTestRunTerminalClosureV1,
    val purge: CatalogTestRunTerminalPurgeV1,
    val installationManifest: CatalogTestRunTerminalInstallationV1,
    val sealSet: TestTerminalSealSetV1,
    val progress: TestTerminalProgressV1,
) {
    fun context(): TestTerminalRunContextV1 = progress.context()
}

/** Closing one TEST range does not change the global writer's ACTIVE state or credentials. */
@Serializable
internal data class CatalogTestRunTerminalClosureV1(
    val state: String,
    val writerGeneration: String,
    val databaseIdentity: String,
    val restoreIdentity: String,
    val ordinaryPrefix: String,
    val sealTerminalPrefix: String,
    val firstEpoch: Long,
    val finalOrdinaryEpoch: Long,
    val terminalEpoch: Long,
)

@Serializable
internal data class CatalogTestRunTerminalPurgeV1(val objectRef: TestTerminalObjectRefV1, val document: TestTerminalPurgeV1)

@Serializable
internal data class CatalogTestRunTerminalInstallationV1(
    val summary: TestTerminalManifestSummaryV1,
    val chunks: List<CatalogTestRunTerminalChunkV1>,
)

/** Plaintext UUIDs are deliberately absent. Exact native chunks must substantiate these commitments. */
@Serializable
internal data class CatalogTestRunTerminalChunkV1(
    val chunkIndex: Int,
    val eventId: String,
    val installationCount: Long,
    val retiredCount: Long,
    val deletedCount: Long,
    val entriesSha256: String,
    val objectRef: TestTerminalObjectRefV1,
)

internal object OfflineCatalogTestRunTerminalProtocol {
    const val SCHEMA_VERSION = 4
    const val PROFILE = "NEW_BACKEND_TEST_RUN_TERMINAL_V1"
    const val OPERATION = "TEST_RUN_TERMINAL"
    const val MAX_DOCUMENT_BYTES = TestTerminalCapacityChargesV1.MAX_CATALOG_DOCUMENT_BYTES
}

/** Declaration checks only; neither syntax nor the history fold proves denial, scanning or a current run. */
internal object OfflineCatalogTestRunTerminalSyntaxV4 {
    fun validateManifest(value: OfflineCatalogTestRunTerminalManifestV4) {
        requireOfflineTrustBundle(value.schemaVersion == 4 && value.profile == OfflineCatalogTestRunTerminalProtocol.PROFILE &&
            value.canonicalizerId == CanonicalJson.CANON_VERSION && value.operation == OfflineCatalogTestRunTerminalProtocol.OPERATION,
            OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
        requireOfflineTrustBundle(OfflineBootstrapGrammar.uuidV4(value.operationToken) && OfflineBootstrapGrammar.uuidV4(value.catalogWriterGenerationId))
        requireOfflineTrustBundle(value.generation in 3..OfflineCatalogChainProtocol.MAX_GENERATIONS &&
            OfflineBootstrapGrammar.sha256(value.previousEnvelopeSha256) && OfflineBootstrapGrammar.sha256(value.initialTrustBundleEnvelopeSha256))
        val policy = value.requiredSignerPolicy
        requireOfflineTrustBundle(policy.mode == "SINGLE" && policy.threshold == "ALL_MEMBERS" && policy.members.size == 1)
        requireOfflineTrustBundle(policy.members.single().keyId.matches(Regex("[A-Za-z0-9._-]{1,64}")) &&
            policy.members.single().algorithmId == OfflineTrustBundleProtocol.ALGORITHM_ID)
        val at = value.creation.createdAtEpochSecond
        val ids = value.approvals.map { it.approverId }
        requireOfflineTrustBundle(at in 0..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND &&
            value.oldestRestoreTimeEpochSecond in 0..at && ids.size == 2 && ids.distinct().size == 2 && ids == ids.sorted() &&
            ids.all(OfflineBootstrapGrammar::approverId) && value.creation.creatorId in ids &&
            value.approvals.all { it.approvedAtEpochSecond in at..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND })
        requireOfflineTrustBundle(value.inventoryDelta == CatalogInventoryDeltaV1(emptyList(), emptyList()))
        val record = value.terminalRecord
        requireOfflineTrustBundle(record.operationToken == value.operationToken && record.generation == value.generation &&
            record.previousEnvelopeSha256 == value.previousEnvelopeSha256)
        validateRecord(record)
        requireOfflineTrustBundle(record.sealedAtEpochSecond <= at && record.progress.completedCuts().all { it.denial.secondInventory.completedAtEpochSecond <= at })
        val registry = value.initialWriterRegistry
        requireOfflineTrustBundle(value.catalogWriterGenerationId == registry.catalogWriter.generationId &&
            record.closure.writerGeneration == registry.eventWriter.generationId &&
            record.closure.databaseIdentity == registry.databaseIdentity && record.closure.restoreIdentity == registry.restoreIdentity &&
            record.closure.databaseIdentity == registry.eventWriter.databaseIdentity && record.closure.restoreIdentity == registry.eventWriter.restoreIdentity)
        CatalogTestRunTerminalHistoryV1.requireTerminalHeads(value.history, record)
    }

    fun validateRecord(record: CatalogTestRunTerminalRecordV1) {
        val context = record.context()
        val closure = record.closure
        requireOfflineTrustBundle(OfflineBootstrapGrammar.uuidV4(record.operationToken) && record.sealedAtEpochSecond in 0..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND)
        requireOfflineTrustBundle(record.generation == context.activationCatalogGeneration + 1 && record.previousEnvelopeSha256 == context.activationCatalogSha256)
        listOf(closure.writerGeneration, closure.databaseIdentity, closure.restoreIdentity).forEach { requireOfflineTrustBundle(OfflineBootstrapGrammar.uuidV4(it)) }
        requireOfflineTrustBundle(closure.state == "SEALED" && closure.firstEpoch == 1L && closure.finalOrdinaryEpoch > 0 &&
            closure.terminalEpoch == TestTerminalSyntaxV1.nextEpoch(closure.finalOrdinaryEpoch))
        requireOfflineTrustBundle(closure.ordinaryPrefix == TestTerminalSyntaxV1.ordinaryPrefix(closure.writerGeneration, context.dataScopeId) &&
            closure.sealTerminalPrefix == TestTerminalSyntaxV1.sealTerminalPrefix(closure.writerGeneration, context.dataScopeId))
        val seals = record.sealSet
        val records = seals.records()
        // Declaration grammar only: up to fourteen ACTIVE ranges plus exactly two new seals.
        // Original-source SQL, native readback, and payment remain independently required by E.
        requireOfflineTrustBundle(seals.dataScopeId == context.dataScopeId && seals.activationCatalogGeneration == context.activationCatalogGeneration &&
            seals.activationCatalogSha256 == context.activationCatalogSha256 && records.size in 2..16 &&
            records.all { it.writerGeneration == closure.writerGeneration })
        val ordinary = records.dropLast(1)
        val terminal = records.last()
        requireOfflineTrustBundle(ordinary.all { it.role == TestTerminalSealRoleV1.ORDINARY } &&
            ordinary.first().epochStartInclusive == 1L && ordinary.first().precedingSealSha256.isEmpty() &&
            ordinary.last().epochEndInclusive == closure.finalOrdinaryEpoch &&
            terminal.role == TestTerminalSealRoleV1.TERMINAL && terminal.epochStartInclusive == closure.terminalEpoch &&
            terminal.epochEndInclusive == closure.terminalEpoch && terminal.precedingSealSha256 == ordinary.last().objectRef.canonicalSha256)
        if (records.size > 3) requireOfflineTrustBundle(ordinary.first().epochEndInclusive == 1L &&
            ordinary.zipWithNext().all { (prior, next) -> next.epochStartInclusive == TestTerminalSyntaxV1.nextEpoch(prior.epochEndInclusive) &&
                next.precedingSealSha256 == prior.objectRef.canonicalSha256 })
        if (records.size == 3) requireOfflineTrustBundle(closure.finalOrdinaryEpoch == 2L &&
            ordinary[0].epochEndInclusive == 1L && ordinary[1].epochStartInclusive == 2L &&
            ordinary[1].precedingSealSha256 == ordinary[0].objectRef.canonicalSha256)
        val purge = record.purge.document
        requireOfflineTrustBundle(purge.context().run == context && purge.writerGeneration == closure.writerGeneration &&
            purge.publicationEpoch == closure.terminalEpoch && purge.finalOrdinaryEpoch == closure.finalOrdinaryEpoch && purge.finalOrdinarySeal == ordinary.last() &&
            purge.installationManifest == record.installationManifest.summary)
        val canonical = CanonicalJson.canonicalize(TestTerminalPurgeV1.serializer(), purge)
        requireOfflineTrustBundle(Sha256.hexUtf8(canonical) == record.purge.objectRef.canonicalSha256)
        // Payload IDs and object-key suffixes have independent HMAC domains. Syntax checks both;
        // the actual retained-route codec/readers additionally bind their exact derived pair.
        TestTerminalSyntaxV1.opaque(purge.eventId)
        TestTerminalSyntaxV1.terminalKey(record.purge.objectRef.objectKey, closure.writerGeneration, context.dataScopeId, closure.terminalEpoch, "test-run-purge")
        records.forEach {
            TestTerminalSyntaxV1.opaque(it.sealId)
            TestTerminalSyntaxV1.terminalKey(it.objectRef.objectKey, closure.writerGeneration, context.dataScopeId, it.epochEndInclusive, "epoch-seal")
        }
        requireOfflineTrustBundle(CatalogTestRunTerminalHistoryV1.sealHead(ordinary).let {
            it.count == purge.preTerminalSeals.count && it.sha256 == purge.preTerminalSeals.sha256
        })
        val cuts = record.progress.completedCuts()
        requireOfflineTrustBundle(cuts.size == 2 && cuts[0].prefixKind == TestTerminalDenialPrefixV1.ORDINARY &&
            cuts[1].prefixKind == TestTerminalDenialPrefixV1.SEAL_TERMINAL && cuts[0].epochEndInclusive == closure.finalOrdinaryEpoch &&
            cuts[1].epochEndInclusive == closure.terminalEpoch && cuts.all { it.epochStartInclusive == 1L &&
                it.writerGeneration == closure.writerGeneration && it.databaseIdentity == closure.databaseIdentity && it.restoreIdentity == closure.restoreIdentity })
        requireOfflineTrustBundle(cuts[0].desiredGeneration == cuts[1].desiredGeneration && cuts[0].denial.roleId != cuts[1].denial.roleId &&
            cuts.all { it.denial.firstInventory.startedAtEpochSecond >= record.sealedAtEpochSecond })
        requireOfflineTrustBundle(cuts[1].denial.firstInventory.startedAtEpochSecond >= cuts[0].denial.secondInventory.completedAtEpochSecond)
        requireOfflineTrustBundle(cuts[1].denial.firstInventory.versionCount == record.installationManifest.chunks.size.toLong() + 1L + records.size)
        requireOfflineTrustBundle(purge.preTerminalInventory.count == TestTerminalSyntaxV1.add(cuts[0].denial.firstInventory.versionCount, records.size - 1L))
        val reads = record.progress.installationReads()
        val summary = record.installationManifest.summary
        requireOfflineTrustBundle(reads.size == 2 && reads.all {
            it.databaseIdentity == closure.databaseIdentity && it.restoreIdentity == closure.restoreIdentity && it.desiredGeneration == cuts[0].desiredGeneration &&
                it.startedAtEpochSecond >= record.sealedAtEpochSecond && it.installationCount == summary.installationCount &&
                it.retiredCount == summary.retiredCount && it.deletedCount == summary.deletedCount && it.chunkCount == summary.chunkCount &&
                it.installationsSha256 == summary.installationsSha256
        })
        requireOfflineTrustBundle(reads.last().completedAtEpochSecond <= cuts[1].denial.firstInventory.startedAtEpochSecond)
        CatalogTestRunTerminalHistoryV1.requireChunks(record)
        val keys = records.map { it.objectRef.objectKey } + record.purge.objectRef.objectKey + record.installationManifest.chunks.map { it.objectRef.objectKey }
        requireOfflineTrustBundle(keys.distinct().size == keys.size)
    }

    /** Genuine raw chain folding additionally binds all declarations back to the exact retained activation. */
    fun requireActivation(value: OfflineCatalogTestRunTerminalManifestV4, activation: OfflineCatalogTestRunActivationManifestV3, activationHash: String) {
        validateManifest(value)
        val record = value.terminalRecord
        val run = activation.activationRecord.run
        val context = record.context()
        val journal = OfflineCatalogTestRunActivationSyntaxV3.journal(run.journalConfiguration)
        val limits = journal.declaration().limits.capacity
        val maximumCiphertextBytes = Math.multiplyExact(limits.maximumRetainedVersions, journal.declaration().limits.decoder.maximumEnvelopeBytes.toLong())
        requireOfflineTrustBundle(value.generation == activation.generation + 1 && value.previousEnvelopeSha256 == activationHash &&
            context.activationCatalogGeneration == activation.generation && context.activationCatalogSha256 == activationHash &&
            context.dataScopeId == run.testRunId && context.configurationSha256 == run.configurationSha256 &&
            context.terminalEncodingSha256 == TestTerminalProfileV1.encodingSha256 && value.initialWriterRegistry == activation.initialWriterRegistry &&
            value.restoreInventory == activation.restoreInventory && value.oldestRestoreTimeEpochSecond == activation.oldestRestoreTimeEpochSecond)
        requireOfflineTrustBundle(value.history == CatalogTestRunTerminalHistoryV1.append(activation.history, record))
        requireOfflineTrustBundle(record.installationManifest.summary.installationCount <= run.installationLimit &&
            record.installationManifest.chunks.size.toLong() < limits.maximumRetainedVersions && record.progress.completedCuts().all {
                it.desiredGeneration == run.desiredGeneration && it.framedByteCount <= limits.maximumScanStagingBytes &&
                    it.denial.firstInventory.versionCount <= limits.maximumRetainedVersions && it.denial.firstInventory.byteCount <= maximumCiphertextBytes
            })
        requireOfflineTrustBundle(record.progress.installationReads().all {
            it.sourceHighWater.framedByteCount <= limits.maximumScanStagingBytes && it.installationsFramedBytes <= limits.maximumScanStagingBytes &&
                it.chunkSetFramedBytes <= limits.maximumScanStagingBytes
        })
    }
}

internal fun CatalogTestRunTerminalRecordV1.snapshot(): CatalogTestRunTerminalRecordV1 = copy(
    installationManifest = installationManifest.copy(chunks = TestTerminalSyntaxV1.snapshot(installationManifest.chunks, TestTerminalProfileV1.MAX_MANIFEST_CHUNKS)),
    sealSet = TestTerminalSealSetV1.create(sealSet.dataScopeId, sealSet.activationCatalogGeneration, sealSet.activationCatalogSha256, sealSet.records()),
    progress = TestTerminalProgressV1.create(progress.context(), progress.completedCuts(), progress.installationReads()),
)

internal fun OfflineCatalogTestRunTerminalManifestV4.snapshot(): OfflineCatalogTestRunTerminalManifestV4 = copy(
    requiredSignerPolicy = requiredSignerPolicy.copy(members = requiredSignerPolicy.members.toList()), approvals = approvals.toList(),
    initialWriterRegistry = initialWriterRegistry.snapshot(), restoreInventory = restoreInventory.snapshot(),
    inventoryDelta = inventoryDelta.copy(addedSourceIds = inventoryDelta.addedSourceIds.toList(), addedCopyIds = inventoryDelta.addedCopyIds.toList()),
    terminalRecord = terminalRecord.snapshot(),
)

/** Signed declarations only. This cannot issue a native readback, denial, SQL lease, PURGING state or restore capability. */
internal class CheckedOfflineCatalogTestRunTerminalChain(
    val tail: CatalogTailEvidence,
    val trust: CatalogChainTrustEvidence,
    val rotation: CatalogRotationState.Stable,
    val encodedBytes: Long,
    activation: OfflineCatalogTestRunActivationManifestV3,
    manifest: OfflineCatalogTestRunTerminalManifestV4,
    manifestBytes: ByteArray,
    envelopeBytes: ByteArray,
) {
    private val storedActivation = activation.snapshot()
    private val storedManifest = manifest.snapshot()
    private val storedManifestBytes = manifestBytes.copyOf()
    private val storedEnvelopeBytes = envelopeBytes.copyOf()
    val activation get() = storedActivation.snapshot()
    val manifest get() = storedManifest.snapshot()
    val canonicalManifestBytes get() = storedManifestBytes.copyOf()
    val canonicalEnvelopeBytes get() = storedEnvelopeBytes.copyOf()
    override fun toString(): String = "CheckedOfflineCatalogTestRunTerminalChain(signature-declarations,no-runtime-authority)"
}
