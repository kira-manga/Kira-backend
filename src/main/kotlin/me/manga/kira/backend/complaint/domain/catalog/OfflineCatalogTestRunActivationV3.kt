package me.manga.kira.backend.complaint.domain.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintCapacityException
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalDocumentV1
import me.manga.kira.backend.complaint.domain.snapshot
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalAccountingPlanV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEncodingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.security.SecretVersionException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Separate first-TEST-activation syntax. It does not widen any schema1/2 or later lifecycle profile. */
@Serializable
internal data class OfflineCatalogTestRunActivationEnvelopeV3(
    val schemaVersion: Int,
    val manifest: OfflineCatalogTestRunActivationManifestV3,
    val signatures: List<OfflineCatalogGenesisSignatureV1>,
)

@Serializable
internal data class OfflineCatalogTestRunActivationManifestV3(
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
    val history: CatalogTestRunActivationHistoryV1,
    val activationRecord: CatalogTestRunActivationRecordV1,
)

/** No own-envelope, signature or history digest: the history hash cannot refer to itself. */
@Serializable
internal data class CatalogTestRunActivationRecordV1(
    val operationToken: String,
    val generation: Long,
    val previousEnvelopeSha256: String,
    val run: CatalogTestRunActivationRunV1,
)

@Serializable
internal data class CatalogTestRunActivationRunV1(
    val testRunId: String,
    val implementationSchema: Int,
    val desiredGeneration: Long,
    val configurationSha256: String,
    val journalConfiguration: TestOwnerDeleteJournalDocumentV1,
    val firstPublicationEpoch: Long,
    val installationLimit: Long,
    val terminalEncoding: TestTerminalEncodingV1,
    val accounting: CatalogTestRunActivationAccountingV1,
    val noticeSeeds: List<CatalogTestRunNoticeSeedV1>,
)

/** Fixed22 arrays use stored counter order, not enum/name order, JSON objects, or LP32 staging-byte charges. */
@Serializable
internal data class CatalogTestRunActivationAccountingV1(
    val profile: String,
    val capacityEncodingVersion: Int,
    val activationCatalogPrepareActual: List<Long>,
    val activationProjectionActual: List<Long>,
    val originalUnusedReserve: List<Long>,
)

@Serializable
internal data class CatalogTestRunNoticeSeedV1(
    val noticeKey: String,
    val definitionVersion: Int,
    val resourceId: String,
    val definitionSha256: String,
)

/** Deliberately not GenesisEmptyHeadV1: exactly one of these seven closed heads has count1. */
@Serializable
internal data class CatalogTestRunActivationHeadV1(val count: Long, val sha256: String)

@Serializable
internal data class CatalogTestRunActivationHistoryV1(
    val expiredRestoreSources: CatalogTestRunActivationHeadV1,
    val testRunActivations: CatalogTestRunActivationHeadV1,
    val testRunTerminals: CatalogTestRunActivationHeadV1,
    val installationManifests: CatalogTestRunActivationHeadV1,
    val epochSeals: CatalogTestRunActivationHeadV1,
    val retirementAuthorizations: CatalogTestRunActivationHeadV1,
    val retirementCompletions: CatalogTestRunActivationHeadV1,
)

/** Defensive signature-checked declarations only; not provider evidence, an accepted head or a registered run. */
internal class CheckedOfflineCatalogTestRunActivationChain(
    val tail: CatalogTailEvidence,
    val trust: CatalogChainTrustEvidence,
    val rotation: CatalogRotationState.Stable,
    val encodedBytes: Long,
    manifest: OfflineCatalogTestRunActivationManifestV3,
    canonicalManifestBytes: ByteArray,
    canonicalEnvelopeBytes: ByteArray,
) {
    private val storedManifest = manifest.snapshot()
    private val storedManifestBytes = canonicalManifestBytes.copyOf()
    private val storedEnvelopeBytes = canonicalEnvelopeBytes.copyOf()

    val manifest: OfflineCatalogTestRunActivationManifestV3 get() = storedManifest.snapshot()
    val activationRecord: CatalogTestRunActivationRecordV1 get() = storedManifest.activationRecord.snapshot()
    val inventory: CatalogRestoreInventoryV1 get() = storedManifest.restoreInventory.snapshot()
    val canonicalManifestBytes: ByteArray get() = storedManifestBytes.copyOf()
    val canonicalEnvelopeBytes: ByteArray get() = storedEnvelopeBytes.copyOf()

    override fun toString(): String = "CheckedOfflineCatalogTestRunActivationChain(no-accepted-head,no-run-or-issuer-authority)"
}

internal object OfflineCatalogTestRunActivationProtocol {
    const val SCHEMA_VERSION = 3
    const val PROFILE = "NEW_BACKEND_TEST_RUN_ACTIVATION_V1"
    const val OPERATION = "TEST_RUN_ACTIVATION"
    const val MAX_DOCUMENT_BYTES = TestTerminalCapacityChargesV1.MAX_CATALOG_DOCUMENT_BYTES
    const val NOTICE_DEFINITION_VERSION = 1
    const val NOTICE_COUNT = 2
    const val FIRST_PUBLICATION_EPOCH = 1L
}

/** Closed declaration checks only. Raw chain authentication and actual retained full-D comparison are separate. */
internal object OfflineCatalogTestRunActivationSyntaxV3 {
    // Match the existing trust-bundle signer grammar, which also permits a leading '.', '_' or '-'.
    private val signerKeyId = Regex("[A-Za-z0-9._-]{1,64}")

    fun validateManifest(manifest: OfflineCatalogTestRunActivationManifestV3) {
        requireOfflineTrustBundle(
            manifest.schemaVersion == OfflineCatalogTestRunActivationProtocol.SCHEMA_VERSION &&
                manifest.profile == OfflineCatalogTestRunActivationProtocol.PROFILE &&
                manifest.canonicalizerId == CanonicalJson.CANON_VERSION && manifest.operation == OfflineCatalogTestRunActivationProtocol.OPERATION,
            OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA,
        )
        requireOfflineTrustBundle(OfflineBootstrapGrammar.uuidV4(manifest.operationToken))
        requireOfflineTrustBundle(manifest.generation in 2L..OfflineCatalogChainProtocol.MAX_GENERATIONS)
        requireOfflineTrustBundle(
            OfflineBootstrapGrammar.sha256(manifest.previousEnvelopeSha256) &&
                OfflineBootstrapGrammar.sha256(manifest.initialTrustBundleEnvelopeSha256) &&
                OfflineBootstrapGrammar.uuidV4(manifest.catalogWriterGenerationId),
        )
        val policy = manifest.requiredSignerPolicy
        requireOfflineTrustBundle(policy.mode == "SINGLE" && policy.threshold == "ALL_MEMBERS" && policy.members.size == 1)
        requireOfflineTrustBundle(signerKeyId.matches(policy.members.single().keyId))
        requireOfflineTrustBundle(policy.members.single().algorithmId == OfflineTrustBundleProtocol.ALGORITHM_ID, OfflineTrustBundleFailure.UNSUPPORTED_ALGORITHM)
        validateApprovals(manifest)
        requireOfflineTrustBundle(manifest.inventoryDelta == CatalogInventoryDeltaV1(emptyList(), emptyList()))
        val record = manifest.activationRecord
        requireOfflineTrustBundle(
            record.operationToken == manifest.operationToken && record.generation == manifest.generation &&
                record.previousEnvelopeSha256 == manifest.previousEnvelopeSha256,
        )
        validateRun(record.run)
        val registry = manifest.initialWriterRegistry
        val writer = record.run.journalConfiguration.writer
        requireOfflineTrustBundle(
            manifest.catalogWriterGenerationId == registry.catalogWriter.generationId &&
                writer.databaseIdentity == registry.databaseIdentity && writer.restoreIdentity == registry.restoreIdentity &&
                writer.databaseIdentity == registry.eventWriter.databaseIdentity && writer.restoreIdentity == registry.eventWriter.restoreIdentity &&
                writer.generationId == registry.eventWriter.generationId,
        )
        requireOfflineTrustBundle(manifest.history == history(record), OfflineTrustBundleFailure.BUNDLE_HASH_MISMATCH)
    }

    fun validateRun(run: CatalogTestRunActivationRunV1) {
        requireOfflineTrustBundle(OfflineBootstrapGrammar.uuidV4(run.testRunId))
        requireOfflineTrustBundle(run.implementationSchema > 0 && run.desiredGeneration > 0)
        requireOfflineTrustBundle(OfflineBootstrapGrammar.sha256(run.configurationSha256))
        requireOfflineTrustBundle(run.firstPublicationEpoch == OfflineCatalogTestRunActivationProtocol.FIRST_PUBLICATION_EPOCH)
        requireOfflineTrustBundle(run.installationLimit in 1L..TestTerminalProfileV1.MAX_INSTALLATIONS)
        requireOfflineTrustBundle(
            run.terminalEncoding.profile == TestTerminalProfileV1.PROFILE && run.terminalEncoding.schemaVersion == TestTerminalProfileV1.SCHEMA_VERSION,
        )
        val journal = journal(run.journalConfiguration)
        requireOfflineTrustBundle(journal.scope.id.toString() == run.testRunId)
        val declaredAccounting = run.accounting
        requireOfflineTrustBundle(
            listOf(declaredAccounting.activationCatalogPrepareActual, declaredAccounting.activationProjectionActual, declaredAccounting.originalUnusedReserve).all {
                it.size == ComplaintCapacityEncoding.WIDTH && it.all { amount -> amount >= 0L }
            },
        )
        requireOfflineTrustBundle(declaredAccounting == accounting(run.installationLimit, journal.declaration().limits.capacity.maximumRetainedVersions))
        requireOfflineTrustBundle(run.noticeSeeds == noticeSeeds(run.testRunId))
    }

    fun journal(document: TestOwnerDeleteJournalDocumentV1): TestOwnerDeleteJournalConfigurationV1 = try {
        TestOwnerDeleteJournalConfigurationV1.fromDocument(document)
    } catch (_: IllegalArgumentException) {
        throw OfflineTrustBundleException(OfflineTrustBundleFailure.INVALID_DOCUMENT)
    } catch (_: SecretVersionException) {
        throw OfflineTrustBundleException(OfflineTrustBundleFailure.INVALID_DOCUMENT)
    }

    fun accounting(installationLimit: Long, maximumRetainedVersions: Long): CatalogTestRunActivationAccountingV1 {
        requireOfflineTrustBundle(installationLimit in 1L..TestTerminalProfileV1.MAX_INSTALLATIONS)
        val plan = try {
            TestTerminalAccountingPlanV1(installationLimit, maximumRetainedVersions)
        } catch (_: ComplaintCapacityException) {
            throw OfflineTrustBundleException(OfflineTrustBundleFailure.INVALID_DOCUMENT)
        }
        return CatalogTestRunActivationAccountingV1(
            TestTerminalAccountingPlanV1.PROFILE, ComplaintCapacityEncoding.VERSION,
            plan.activationCatalogPrepareActual.toLongArray().toList(), plan.activationProjectionActual.toLongArray().toList(),
            plan.originalUnusedReserve.toLongArray().toList(),
        )
    }

    fun history(record: CatalogTestRunActivationRecordV1): CatalogTestRunActivationHistoryV1 {
        val empty = CatalogTestRunActivationHeadV1(0, Sha256.hexUtf8("[]"))
        val batch = CanonicalJson.canonicalize(ListSerializer(CatalogTestRunActivationRecordV1.serializer()), listOf(record))
        val activation = CatalogTestRunActivationHeadV1(1, Sha256.hexUtf8(batch))
        return CatalogTestRunActivationHistoryV1(empty, activation, empty, empty, empty, empty, empty)
    }

    /** Version1 default definitions only. These are identifiers/hash declarations, never content-row seeds. */
    fun noticeSeeds(testRunId: String): List<CatalogTestRunNoticeSeedV1> {
        requireOfflineTrustBundle(OfflineBootstrapGrammar.uuidV4(testRunId))
        return definitions.map { definition ->
            val frame = CanonicalJson.canonicalize(
                JsonArray(
                    listOf(
                        JsonPrimitive("kira-test-notice-id-v1"), JsonPrimitive(testRunId),
                        JsonPrimitive(definition.noticeKey), JsonPrimitive(definition.definitionVersion),
                    ),
                ),
            ).toByteArray(Charsets.UTF_8)
            val id = MessageDigest.getInstance("SHA-256").digest(frame).copyOf(16)
            id[6] = ((id[6].toInt() and 15) or 64).toByte()
            id[8] = ((id[8].toInt() and 63) or 128).toByte()
            val buffer = ByteBuffer.wrap(id)
            val resourceId = UUID(buffer.long, buffer.long).toString()
            val definitionBytes = CanonicalJson.canonicalize(NoticeDefinitionV1.serializer(), definition)
            CatalogTestRunNoticeSeedV1(definition.noticeKey, definition.definitionVersion, resourceId, Sha256.hexUtf8(definitionBytes))
        }
    }

    private fun validateApprovals(manifest: OfflineCatalogTestRunActivationManifestV3) {
        val created = manifest.creation.createdAtEpochSecond
        requireOfflineTrustBundle(created in 0L..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND)
        requireOfflineTrustBundle(manifest.oldestRestoreTimeEpochSecond in 0L..created)
        val ids = manifest.approvals.map { it.approverId }
        requireOfflineTrustBundle(ids.size == 2 && ids.distinct().size == 2 && ids == ids.sorted() && ids.all(OfflineBootstrapGrammar::approverId))
        requireOfflineTrustBundle(manifest.creation.creatorId in ids)
        requireOfflineTrustBundle(manifest.approvals.all { it.approvedAtEpochSecond in created..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND })
    }

    @Serializable
    private data class NoticeDefinitionV1(val noticeKey: String, val definitionVersion: Int, val defaultSubject: String, val defaultBody: String)

    // Frozen ledger 6e2b0349...6ea7a supplies prose, not these canonical definition/ID hashes.
    private val definitions = listOf(
        NoticeDefinitionV1(
            "complaints.notice.content-policy", OfflineCatalogTestRunActivationProtocol.NOTICE_DEFINITION_VERSION,
            "Adult content policy",
            "References to adult / 18+ content aren't allowed here. Please keep submissions consistent with our community guidelines.",
        ),
        NoticeDefinitionV1(
            "complaints.notice.source-requirements", OfflineCatalogTestRunActivationProtocol.NOTICE_DEFINITION_VERSION,
            "New manga site requirements",
            "Any new manga site must offer at least 200 titles, have no bot verification steps, and be worth the setup effort. " +
                "Adding a site takes significant time and work.",
        ),
    )
}

internal fun CatalogTestRunActivationAccountingV1.snapshot(): CatalogTestRunActivationAccountingV1 = copy(
    activationCatalogPrepareActual = activationCatalogPrepareActual.toList(),
    activationProjectionActual = activationProjectionActual.toList(),
    originalUnusedReserve = originalUnusedReserve.toList(),
)

internal fun CatalogTestRunActivationRunV1.snapshot(): CatalogTestRunActivationRunV1 = copy(
    journalConfiguration = journalConfiguration.snapshot(), accounting = accounting.snapshot(), noticeSeeds = noticeSeeds.toList(),
)

internal fun CatalogTestRunActivationRecordV1.snapshot(): CatalogTestRunActivationRecordV1 = copy(run = run.snapshot())

internal fun OfflineCatalogTestRunActivationManifestV3.snapshot(): OfflineCatalogTestRunActivationManifestV3 = copy(
    requiredSignerPolicy = requiredSignerPolicy.copy(members = requiredSignerPolicy.members.toList()),
    approvals = approvals.toList(), initialWriterRegistry = initialWriterRegistry.snapshot(), restoreInventory = restoreInventory.snapshot(),
    inventoryDelta = inventoryDelta.copy(addedSourceIds = inventoryDelta.addedSourceIds.toList(), addedCopyIds = inventoryDelta.addedCopyIds.toList()),
    activationRecord = activationRecord.snapshot(),
)
