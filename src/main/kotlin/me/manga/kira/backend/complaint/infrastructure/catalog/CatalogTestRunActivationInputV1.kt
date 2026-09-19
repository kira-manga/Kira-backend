package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.builtins.ListSerializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationManifestV3
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.snapshot
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogTestRunActivationParser
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal enum class CatalogTestRunActivationKindV1(val path: PersistencePhasePath) {
    SNAPSHOT(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SNAPSHOT),
    LEASE_ACQUIRE(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_LEASE_ACQUIRE),
    PREPARE(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARE),
    PREPARED_RELOAD(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARED_RELOAD),
    SIGNATURE(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SIGNATURE),
    SIGNED_RELOAD(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SIGNED_RELOAD),
    DELIVERY_RELOAD(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_DELIVERY_RELOAD),
    COMPLETE(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_COMPLETE),
    PENDING_RELOAD(PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PENDING_RELOAD),
}

/** Detached exact input for ONE selected phase. Only its original owner can construct or spend it. */
internal class CatalogTestRunActivationInputV1 private constructor(
    internal val original: CatalogTestRunActivationV1,
    internal val kind: CatalogTestRunActivationKindV1,
    internal val frozen: CatalogTestRunActivationFrozenV1,
    internal val expected: CatalogTestRunActivationSnapshotV1?,
    internal val lease: CatalogTestRunActivationLeaseV1?,
    internal val leaseOwner: UUID?,
    internal val recovering: Boolean,
    internal val signed: CatalogTestRunActivationSignedV1?,
    internal val delivering: Boolean,
    internal val deliveryProof: CatalogTestRunActivationDeliveryReadbackV1?,
) {
    val path: PersistencePhasePath get() = kind.path
    val requiresEpochFence: Boolean get() = kind === CatalogTestRunActivationKindV1.PREPARE || kind === CatalogTestRunActivationKindV1.PREPARED_RELOAD ||
        kind === CatalogTestRunActivationKindV1.SIGNATURE || kind === CatalogTestRunActivationKindV1.SIGNED_RELOAD ||
        kind === CatalogTestRunActivationKindV1.DELIVERY_RELOAD || kind === CatalogTestRunActivationKindV1.COMPLETE ||
        kind === CatalogTestRunActivationKindV1.PENDING_RELOAD

    internal fun requirePersistence(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) = original.requireInput(this, ownership, jdbc)

    override fun toString(): String = "CatalogTestRunActivationInputV1(original-closed-phase,redacted)"

    companion object {
        internal fun create(original: CatalogTestRunActivationV1, kind: CatalogTestRunActivationKindV1): CatalogTestRunActivationInputV1 {
            requireConnectionFree()
            original.requireInputConstruction(kind)
            return CatalogTestRunActivationInputV1(
                original, kind, original.frozenInput(), original.expectedSnapshot(), original.currentLease(), original.acquisitionOwner(), original.recovering(),
                original.signedInput(), original.delivering(), original.deliveryProofInput(),
            )
        }
    }
}

/** Actual full-D declaration plus canonical approved intent. Not a supplied digest, SQL snapshot or signed/provider proof. */
internal class CatalogTestRunActivationFrozenV1 private constructor(
    private val original: CatalogTestRunActivationV1,
    private val storedManifest: OfflineCatalogTestRunActivationManifestV3,
    private val unsigned: ByteArray,
) {
    private val unsignedDigest = HexFormat.of().parseHex(Sha256.hex(unsigned))
    private val approvals = CanonicalJson.canonicalize(
        ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), storedManifest.approvals,
    ).toByteArray(Charsets.UTF_8)
    private val approvalDigest = HexFormat.of().parseHex(Sha256.hex(approvals))
    private val policyDigest = original.process.consumers.capacityPolicy.digestBytes()
    val policy = original.process.consumers.capacityPolicy
    val token: UUID = UUID.fromString(storedManifest.operationToken)
    val scope: UUID = UUID.fromString(storedManifest.activationRecord.run.testRunId)
    val generation: Long = storedManifest.generation
    val predecessorHash: String = storedManifest.previousEnvelopeSha256
    private val predecessorDigest = HexFormat.of().parseHex(predecessorHash)
    val catalogWriter: UUID = UUID.fromString(storedManifest.catalogWriterGenerationId)
    val currentTrustHash: String = original.process.catalogReadback.currentTrustBundleSha256
    val databaseIdentity: UUID = original.process.databaseIdentity
    val restoreIdentity: UUID = original.process.restoreIdentity
    val eventWriter: UUID = UUID.fromString(original.process.consumers.journalConfiguration.declaration().writer.generationId)
    val maximumGenerations: Int = original.process.catalogReadback.chainPolicy.limits.maximumGenerations
    val prepareCharge: ComplaintCapacityVector = ComplaintCapacityVector.of(storedManifest.activationRecord.run.accounting.activationCatalogPrepareActual.toLongArray())
    val projectionCharge: ComplaintCapacityVector = ComplaintCapacityVector.of(storedManifest.activationRecord.run.accounting.activationProjectionActual.toLongArray())
    val reserve: ComplaintCapacityVector = ComplaintCapacityVector.of(storedManifest.activationRecord.run.accounting.originalUnusedReserve.toLongArray())
    private val runHash = HexFormat.of().parseHex(storedManifest.activationRecord.run.configurationSha256)
    private val noticeIds = storedManifest.activationRecord.run.noticeSeeds.map { UUID.fromString(it.resourceId) }
    private val preparedValues: Array<Any?> = arrayOf(
        token, scope, generation - 1, predecessorDigest, generation, catalogWriter,
        approvals, approvalDigest, unsigned, unsignedDigest,
        storedManifest.requiredSignerPolicy.members.single().keyId, storedManifest.requiredSignerPolicy.members.single().algorithmId,
        CatalogReadbackProtocol.key(generation), Timestamp.from(Instant.ofEpochSecond(storedManifest.creation.createdAtEpochSecond)),
    )
    private val preflightValues: Array<Any?> = arrayOf(
        scope, noticeIds[0], noticeIds[1], storedManifest.activationRecord.run.noticeSeeds[0].noticeKey,
        storedManifest.activationRecord.run.noticeSeeds[1].noticeKey, runHash, storedManifest.activationRecord.run.installationLimit,
        generation, Timestamp.from(Instant.ofEpochSecond(storedManifest.creation.createdAtEpochSecond)),
    )

    fun manifest(): OfflineCatalogTestRunActivationManifestV3 = storedManifest.snapshot()
    fun unsignedBytes(): ByteArray = unsigned.copyOf()
    fun unsignedHash(): ByteArray = unsignedDigest.copyOf()
    fun predecessorHashBytes(): ByteArray = predecessorDigest.copyOf()
    fun approvalBytes(): ByteArray = approvals.copyOf()
    fun capacityDigest(): ByteArray = policyDigest.copyOf()

    /** Fixed fourteen V14 PREPARED columns, assembled before checkout. No JDBC Array/stream/JSON parsing under locks. */
    fun preparedArguments(): Array<Any?> = copyArguments(preparedValues)

    fun preflightArguments(): Array<Any?> = copyArguments(preflightValues)

    private fun copyArguments(values: Array<Any?>): Array<Any?> = values.map { value ->
        when (value) {
            is ByteArray -> value.copyOf()
            is Timestamp -> Timestamp.from(value.toInstant())
            else -> value
        }
    }.toTypedArray()

    override fun toString(): String = "CatalogTestRunActivationFrozenV1(actual-fullD,canonical-unsigned,no-publication-authority)"

    companion object {
        internal fun capture(original: CatalogTestRunActivationV1, bytes: ByteArray): CatalogTestRunActivationFrozenV1 {
            requireConnectionFree()
            original.requireInputCapture()
            val maximum = minOf(original.process.catalogReadback.chainPolicy.limits.maximumEnvelopeBytes, OfflineCatalogTestRunActivationProtocol.MAX_DOCUMENT_BYTES)
            check(bytes.size in 1..maximum)
            val detached = bytes.copyOf()
            val manifest = OfflineCatalogTestRunActivationParser.parseManifest(
                detached, original.process.catalogReadback.chainPolicy.limits.maximumManifestRecords, maximum,
            )
            original.expectedDeclaration.requireManifest(manifest)
            check(manifest.generation in 2..original.process.catalogReadback.chainPolicy.limits.maximumGenerations.toLong())
            check(manifest.approvals.all { it.approverId in original.process.catalogActivation.initialApproverIds() &&
                it.approverId in original.process.catalogReadback.chainPolicy.currentApproverIds })
            check(manifest.creation.creatorId in manifest.approvals.map { it.approverId })
            return CatalogTestRunActivationFrozenV1(original, manifest.snapshot(), detached)
        }
    }
}
