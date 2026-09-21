package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.builtins.ListSerializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerPolicyV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisSignatureV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationManifestV3
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunTerminalEnvelopeV4
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunTerminalManifestV4
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunTerminalProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.snapshot
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceTargetV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalQuiescenceSourceV1
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteRows
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogTestRunTerminalParser
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalJsonV1
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate

/** Fixed paths; no caller-selected order, SQL or optional lock list. Preflight is a DIFFERENT boundary. */
internal enum class CatalogTestRunTerminalKindV1(val path: PersistencePhasePath) {
    CAPTURE(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_CAPTURE),
    ACQUIRE(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_ACQUIRE),
    PREPARE(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_PREPARE),
    RELOAD(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_RELOAD),
    SIGNATURE(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_SIGNATURE),
    COMPLETE(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_COMPLETE),
    PROJECT(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_PROJECT),
    RELEASE(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_TERMINAL_RELEASE),
}

/** One original-issued SQL selection; optional facts are fixed by its closed kind, never by a caller. */
internal class CatalogTestRunTerminalPhaseV1 private constructor(
    val original: CatalogTestRunTerminalV1,
    val kind: CatalogTestRunTerminalKindV1,
    val expected: CatalogTestRunTerminalSnapshotV1?,
    val frozen: CatalogTestRunTerminalFrozenV1?,
    val signed: CatalogTestRunTerminalSignedV1?,
    val proof: CatalogTestRunTerminalDeliveryReadbackV1?,
    val lease: CatalogTestRunActivationLeaseV1?,
    val leaseOwner: UUID?,
) {
    val path: PersistencePhasePath get() = kind.path
    val requiresEpochFence: Boolean get() = kind !== CatalogTestRunTerminalKindV1.ACQUIRE && kind !== CatalogTestRunTerminalKindV1.RELEASE
    val process: VersionBoundTestNamespaceProcessV1 get() = original.process
    val scope: UUID = process.consumers.journalConfiguration.scope.id
    val token: UUID = original.selectedToken()
    val maximumGenerations: Int = process.catalogReadback.chainPolicy.limits.maximumGenerations
    private val declaredRun = original.expectedDeclaration.activation.run()
    val installationLimit: Long = declaredRun.installationLimit
    val maximumVersions: Long = process.consumers.journalConfiguration.declaration().limits.capacity.maximumRetainedVersions
    val plan = me.manga.kira.backend.complaint.domain.terminal.TestTerminalAccountingPlanV1(installationLimit, maximumVersions)
    private val control = process.consumers.journalConfiguration.declaration()

    fun controlArguments(selectedScope: UUID): Array<Any?> = arrayOf(scope, process.desiredGeneration, process.implementationSchema,
        process.configurationHashBytes(), process.databaseIdentity, process.restoreIdentity, UUID.fromString(control.writer.generationId),
        UUID.fromString(process.catalogActivation.initialWriterRegistry().catalogWriter.generationId),
        terminalCatalogHex(process.catalogReadback.currentTrustBundleSha256), maximumGenerations, selectedScope)

    fun runArguments(activation: CatalogTestRunTerminalMutationV1): Array<Any?> = arrayOf(scope, process.configurationHashBytes(), installationLimit,
        activation.generation, terminalCatalogHex(checkNotNull(activation.head).envelopeSha256),
        Timestamp.from(checkNotNull(checkNotNull(activation.completed).projectedAt)), OwnerDeleteRows.array(plan.originalUnusedReserve))

    fun historyArguments(activation: CatalogTestRunTerminalMutationV1): Array<Any?> = arrayOf(activation.token, scope, activation.generation,
        terminalCatalogHex(checkNotNull(activation.head).envelopeSha256), activation.generation, maximumGenerations + 1L)

    fun requirePersistence(owner: PersistencePhaseOwnership, jdbc: JdbcTemplate) = original.requireSelectedPhase(this, owner, jdbc)
    override fun toString(): String = "CatalogTestRunTerminalPhaseV1(original-selected,no-independent-authority)"
    companion object {
        internal fun select(original: CatalogTestRunTerminalV1, kind: CatalogTestRunTerminalKindV1,
            expected: CatalogTestRunTerminalSnapshotV1?, frozen: CatalogTestRunTerminalFrozenV1?, signed: CatalogTestRunTerminalSignedV1?,
            proof: CatalogTestRunTerminalDeliveryReadbackV1?, lease: CatalogTestRunActivationLeaseV1?, leaseOwner: UUID?): CatalogTestRunTerminalPhaseV1 {
            requireConnectionFree(); original.requirePhaseSelection(kind)
            requireTestTerminalCatalog((kind === CatalogTestRunTerminalKindV1.CAPTURE) == (expected == null) &&
                (kind === CatalogTestRunTerminalKindV1.CAPTURE || frozen != null) &&
                (kind !== CatalogTestRunTerminalKindV1.ACQUIRE || leaseOwner != null && lease == null))
            return CatalogTestRunTerminalPhaseV1(original, kind, expected, frozen, signed, proof, lease, leaseOwner)
        }
    }
}

/** Detached paid schema4 intent. It does not prove a successful predecessor, denial, signature or SQL state. */
internal class CatalogTestRunTerminalFrozenV1 private constructor(
    private val original: CatalogTestRunTerminalV1,
    private val stored: OfflineCatalogTestRunTerminalManifestV4,
    private val unsigned: ByteArray,
) {
    private val hash = digest(unsigned)
    private val approvals = CanonicalJson.canonicalize(ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), stored.approvals).toByteArray(Charsets.UTF_8)
    private val approvalsHash = digest(approvals)
    private val capacityHash = original.process.consumers.capacityPolicy.digestBytes()
    private val context = stored.terminalRecord.context()
    private val json = TestTerminalJsonV1(original.process.consumers.journalConfiguration)
    private val progress = json.encodeProgress(stored.terminalRecord.progress)
    private val seals = json.encodeSealSet(stored.terminalRecord.sealSet)
    private val purge = json.encodePurge(stored.terminalRecord.purge.document)
    val token: UUID = UUID.fromString(stored.operationToken)
    val scope: UUID = UUID.fromString(context.dataScopeId)
    val generation: Long = stored.generation
    val predecessorHash: String = stored.previousEnvelopeSha256
    val catalogWriter: UUID = UUID.fromString(stored.catalogWriterGenerationId)
    val writer: UUID = UUID.fromString(stored.terminalRecord.closure.writerGeneration)
    val terminalEpoch: Long = stored.terminalRecord.closure.terminalEpoch
    val ordinaryEpoch: Long = stored.terminalRecord.closure.finalOrdinaryEpoch
    val createdAt: Instant = Instant.ofEpochSecond(stored.creation.createdAtEpochSecond)
    val prepareCharge: ComplaintCapacityVector = TestTerminalCapacityChargesV1.SCOPED_CATALOG
    val projectionCharge: ComplaintCapacityVector = TestTerminalCapacityChargesV1.AUDIT
    val activeHistoryCount: Int = stored.terminalRecord.sealSet.records().size - 2
    val hasActiveHistory: Boolean = activeHistoryCount > 0
    val targets: List<TestTerminalQuiescenceTargetV1> = buildList {
        stored.terminalRecord.installationManifest.chunks.forEach {
            add(TestTerminalQuiescenceTargetV1(TestTerminalCodecKindV1.INSTALLATION_MANIFEST, it.chunkIndex, it.eventId, terminalEpoch, terminalEpoch, it.objectRef))
        }
        add(TestTerminalQuiescenceTargetV1(TestTerminalCodecKindV1.TEST_RUN_PURGE, 0, stored.terminalRecord.purge.document.eventId,
            terminalEpoch, terminalEpoch, stored.terminalRecord.purge.objectRef))
        stored.terminalRecord.sealSet.records().forEachIndexed { index, seal ->
            val active = index < activeHistoryCount
            add(TestTerminalQuiescenceTargetV1(TestTerminalCodecKindV1.EPOCH_SEAL, if (active) index else index - activeHistoryCount, seal.sealId,
                seal.epochStartInclusive, seal.epochEndInclusive, seal.objectRef,
                if (!active) TestTerminalQuiescenceSourceV1.V21_TERMINAL_INTENT else if (index == 0) TestTerminalQuiescenceSourceV1.V26_ACTIVE_SEAL
                    else TestTerminalQuiescenceSourceV1.V31_ACTIVE_RECURRENT_SEAL))
        }
    }.sortedBy { it.objectRef.objectKey }
    private val arguments: Array<Any?> = arrayOf(token, scope, generation - 1, hex(predecessorHash), generation, catalogWriter,
        approvals, approvalsHash, unsigned, hash, stored.requiredSignerPolicy.members.single().keyId,
        stored.requiredSignerPolicy.members.single().algorithmId, CatalogReadbackProtocol.key(generation), Timestamp.from(createdAt))

    fun manifest(): OfflineCatalogTestRunTerminalManifestV4 = stored.snapshot()
    fun unsignedBytes(): ByteArray = unsigned.copyOf()
    fun unsignedHash(): ByteArray = hash.copyOf()
    fun approvalBytes(): ByteArray = approvals.copyOf()
    fun progressBytes(): ByteArray = progress.copyOf()
    fun progressHash(): ByteArray = digest(progress)
    fun sealSetBytes(): ByteArray = seals.copyOf()
    fun sealSetHash(): ByteArray = digest(seals)
    fun purgeBytes(): ByteArray = purge.copyOf()
    fun capacityDigest(): ByteArray = capacityHash.copyOf()
    fun preparedArguments(): Array<Any?> = copyTerminalArguments(arguments)
    fun requireOwner(candidate: CatalogTestRunTerminalV1) = requireTestTerminalCatalog(original === candidate)

    fun requireActivation(value: OfflineCatalogTestRunActivationManifestV3) {
        requireConnectionFree()
        original.expectedDeclaration.requireManifest(stored, value, predecessorHash)
    }

    override fun toString(): String = "CatalogTestRunTerminalFrozenV1(exact-schema4,not-approval-or-denial-authority)"

    companion object {
        internal fun capture(original: CatalogTestRunTerminalV1, bytes: ByteArray): CatalogTestRunTerminalFrozenV1 {
            requireConnectionFree()
            original.requireFrozenCapture()
            val process = original.process
            process.requireUnchangedConfiguration()
            val expected = original.expectedDeclaration
            requireTestTerminalCatalog(bytes.size in 1..expected.maximumDocumentBytes)
            val detached = bytes.copyOf()
            val parsed = OfflineCatalogTestRunTerminalParser.parseManifest(detached,
                process.catalogReadback.chainPolicy.limits.maximumManifestRecords, expected.maximumDocumentBytes)
            val context = parsed.terminalRecord.context()
            val run = expected.activation.run()
            requireTestTerminalCatalog(context.dataScopeId == run.testRunId && context.configurationSha256 == run.configurationSha256 &&
                context.terminalEncodingSha256 == TestTerminalProfileV1.encodingSha256 &&
                parsed.initialWriterRegistry == process.catalogActivation.initialWriterRegistry() &&
                parsed.requiredSignerPolicy == process.catalogActivation.requiredSignerPolicy().let {
                    CatalogSignerPolicyV1(it.mode, it.threshold, it.members.toList())
                } &&
                parsed.initialTrustBundleEnvelopeSha256 == process.catalogReadback.initialTrustBundleSha256 &&
                parsed.generation <= process.catalogReadback.chainPolicy.limits.maximumGenerations &&
                parsed.terminalRecord.installationManifest.summary.installationCount <= run.installationLimit &&
                parsed.terminalRecord.progress.completedCuts().all { it.desiredGeneration == run.desiredGeneration } &&
                parsed.approvals.all { it.approverId in process.catalogActivation.initialApproverIds() &&
                    it.approverId in process.catalogReadback.chainPolicy.currentApproverIds })
            expected.envelopeSize(parsed) // Complete fixed-width signature size bound BEFORE PREPARE/Sign.
            return CatalogTestRunTerminalFrozenV1(original, parsed.snapshot(), detached)
        }
    }
}

/** Genuine exact signature verification only. No original Sign arm or native provenance follows from this value. */
internal class CatalogTestRunTerminalSignedV1 private constructor(
    internal val frozen: CatalogTestRunTerminalFrozenV1,
    private val signature: ByteArray,
    private val envelope: ByteArray,
) {
    val envelopeSha256: String = Sha256.hex(envelope)
    val signatureSha256: String = Sha256.hex(signature)
    private val hash = digest(envelope)
    fun signatureBytes(): ByteArray = signature.copyOf()
    fun envelopeBytes(): ByteArray = envelope.copyOf()
    fun envelopeHash(): ByteArray = hash.copyOf()
    fun signatureArguments(): Array<Any?> = arrayOf(signature.copyOf(), envelope.copyOf(), hash.copyOf())
    fun requireExact(actualSignature: ByteArray, actualEnvelope: ByteArray, actualHash: ByteArray) {
        requireTestTerminalCatalog(signature.contentEquals(actualSignature) && envelope.contentEquals(actualEnvelope) && hash.contentEquals(actualHash))
    }
    override fun toString(): String = "CatalogTestRunTerminalSignedV1(exact-signature,not-native-or-SQL-authority)"

    companion object {
        fun verify(process: VersionBoundTestNamespaceProcessV1, frozen: CatalogTestRunTerminalFrozenV1,
            signatureBytes: ByteArray, retainedEnvelope: ByteArray? = null): CatalogTestRunTerminalSignedV1 {
            requireConnectionFree()
            process.requireUnchangedConfiguration()
            requireTestTerminalCatalog(signatureBytes.size == OfflineTrustBundleProtocol.SIGNATURE_BYTES)
            val signature = signatureBytes.copyOf()
            val key = process.catalogActivation.signingKey
            val manifest = frozen.manifest()
            val member = manifest.requiredSignerPolicy.members.single()
            requireTestTerminalCatalog(manifest.requiredSignerPolicy.mode == "SINGLE" && member.keyId == key.keyId && member.algorithmId == key.algorithmId)
            val unsigned = frozen.unsignedBytes()
            val frame = OfflineCatalogGenesisCrypto.signatureFrame(key.keyId, unsigned)
            try { OfflineTrustBundleCrypto.verify(key.publicKey(), frame, signature) } finally { frame.fill(0) }
            val envelope = CanonicalJson.canonicalize(OfflineCatalogTestRunTerminalEnvelopeV4.serializer(),
                OfflineCatalogTestRunTerminalEnvelopeV4(4, manifest,
                    listOf(OfflineCatalogGenesisSignatureV1(key.keyId, key.algorithmId, Base64.getEncoder().encodeToString(signature))))).toByteArray(Charsets.UTF_8)
            val limits = process.catalogReadback.chainPolicy.limits
            val parsed = OfflineCatalogTestRunTerminalParser.parse(envelope, limits.maximumManifestRecords,
                minOf(limits.maximumEnvelopeBytes, OfflineCatalogTestRunTerminalProtocol.MAX_DOCUMENT_BYTES))
            // Terminal progress/seal/purge are immutable classes, not structurally comparable data classes.
            val parsedUnsigned = CanonicalJson.canonicalize(OfflineCatalogTestRunTerminalManifestV4.serializer(), parsed.manifest).toByteArray(Charsets.UTF_8)
            requireTestTerminalCatalog(parsedUnsigned.contentEquals(unsigned) && (retainedEnvelope == null || envelope.contentEquals(retainedEnvelope)))
            process.requireUnchangedConfiguration()
            return CatalogTestRunTerminalSignedV1(frozen, signature, envelope)
        }
    }
}

internal fun copyTerminalArguments(values: Array<Any?>): Array<Any?> = values.map { value ->
    when (value) { is ByteArray -> value.copyOf(); is Timestamp -> Timestamp.from(value.toInstant()); else -> value }
}.toTypedArray()

internal fun terminalCatalogHex(value: String): ByteArray = HexFormat.of().parseHex(value)
private fun hex(value: String): ByteArray = terminalCatalogHex(value)
private fun digest(value: ByteArray): ByteArray = hex(Sha256.hex(value))

internal class CatalogTestRunTerminalExceptionV1 : RuntimeException("TEST terminal catalog refused.", null, false, false)
internal fun requireTestTerminalCatalog(condition: Boolean) { if (!condition) throw CatalogTestRunTerminalExceptionV1() }
