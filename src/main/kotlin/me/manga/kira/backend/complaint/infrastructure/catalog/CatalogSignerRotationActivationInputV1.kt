package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.builtins.ListSerializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

internal enum class CatalogSignerRotationActivationKindV1 {
    INITIAL_HEAD_READ,
    INITIAL_PREPARED_READ,
    INITIAL_PENDING_READ,
    INITIAL_PROJECTED_READ,
    HEAD_RECHECK,
    PREPARED_RECHECK,
    PENDING_RECHECK,
    PROJECTED_RECHECK,
    PREPARE,
    SIGNATURE,
    COMPLETE,
    PROJECT,
    ;

    internal val path: PersistencePhasePath get() = when (this) {
        PREPARE -> PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PREPARE
        SIGNATURE -> PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_SIGNATURE
        COMPLETE -> PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_COMPLETE
        PROJECT -> PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PROJECT
        else -> PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_READ
    }
}

/** Detached O17/A14/C6 from this original owner only; no caller generation, B, allowance or SQL selector. */
internal class CatalogSignerRotationActivationInputV1 private constructor(
    internal val original: CatalogSignerRotationActivationV1,
    internal val kind: CatalogSignerRotationActivationKindV1,
    internal val expected: CatalogSignerRotationActivationObservationV1?,
    internal val after: CatalogFrozenMutation?,
) {
    internal val path = kind.path
    private val inputs = original.inputs
    private val manifest = inputs.manifest
    private val proof = original.readbackForInput()
    private val acquired = original.activationBindingArguments()
    private val headBinding = acquired.takeIf { it[5] == 2L }
    private val before = expected?.mutation ?: original.frozenMutationOrNull()
    private val next = after ?: before
    private val token = UUID.fromString(manifest.operationToken)
    private val nextHash = next?.signedEnvelopeSha256?.let(::digest)
    private val projectedBinding = nextHash?.let { hash ->
        acquired.copyOf().also {
            if (it[5] == 2L) {
                it[5] = 3L
                it[6] = hash.copyOf()
            }
        }
    }
    private val pendingBinding = projectedBinding?.let { arrayOf<Any?>(*it, token) }
    private val advancingHead = if (headBinding != null && nextHash != null) arrayOf(*headBinding, token, nextHash) else null
    private val overlap = overlapArguments(proof, inputs.chain.limits)
    private val beforeActivation = before?.let(::activationArguments)
    private val nextActivation = next?.let(::activationArguments)
    private val copies = when (kind) {
        CatalogSignerRotationActivationKindV1.INITIAL_PENDING_READ, CatalogSignerRotationActivationKindV1.INITIAL_PROJECTED_READ,
        CatalogSignerRotationActivationKindV1.PENDING_RECHECK, CatalogSignerRotationActivationKindV1.PROJECTED_RECHECK,
        CatalogSignerRotationActivationKindV1.COMPLETE,
        -> copyArguments(proof)

        CatalogSignerRotationActivationKindV1.PROJECT -> checkNotNull(expected).copyArguments()

        else -> null
    }
    private val initialHistory = when (kind) {
        CatalogSignerRotationActivationKindV1.INITIAL_HEAD_READ, CatalogSignerRotationActivationKindV1.HEAD_RECHECK,
        CatalogSignerRotationActivationKindV1.PREPARE,
        -> overlap

        CatalogSignerRotationActivationKindV1.INITIAL_PENDING_READ, CatalogSignerRotationActivationKindV1.INITIAL_PROJECTED_READ,
        CatalogSignerRotationActivationKindV1.PENDING_RECHECK, CatalogSignerRotationActivationKindV1.PROJECTED_RECHECK,
        CatalogSignerRotationActivationKindV1.PROJECT,
        -> arrayOf(*overlap, *checkNotNull(beforeActivation), *checkNotNull(copies))

        else -> arrayOf(*overlap, *checkNotNull(beforeActivation))
    }
    private val finalHistory = when (kind) {
        CatalogSignerRotationActivationKindV1.PREPARE, CatalogSignerRotationActivationKindV1.SIGNATURE -> arrayOf(*overlap, *checkNotNull(nextActivation))
        CatalogSignerRotationActivationKindV1.COMPLETE -> arrayOf(*overlap, *checkNotNull(nextActivation), *checkNotNull(copies))
        else -> initialHistory
    }
    private val insert = activationArguments(inputs.unsigned()).take(11).toTypedArray()
    private val signature = if (kind === CatalogSignerRotationActivationKindV1.SIGNATURE) {
        arrayOf(*checkNotNull(beforeActivation), *checkNotNull(nextActivation).drop(11).toTypedArray())
    } else {
        null
    }
    private val completion = copies?.let { arrayOf(*checkNotNull(nextActivation), *it) }
    private val projection = if (kind === CatalogSignerRotationActivationKindV1.PROJECT) {
        arrayOf(*checkNotNull(completion), Timestamp.from(checkNotNull(expected?.completedAt)))
    } else {
        null
    }

    init {
        requireConnectionFree()
        requireSignerRotation(acquired.size == 12 && acquired[5] in listOf(2L, 3L))
        inputs.requirePrefix(proof)
        before?.let(inputs::requireMutation)
        after?.let(inputs::requireMutation)
        if (acquired[5] == 3L) requireSignerRotation((acquired[6] as? ByteArray).contentEquals(nextHash))
        if (kind === CatalogSignerRotationActivationKindV1.PREPARE) requireSignerRotation(before == null && after?.signedEnvelopeBytes == null)
        if (kind === CatalogSignerRotationActivationKindV1.SIGNATURE) {
            requireSignerRotation(
                before?.signatureSlots?.single()?.signatureBytes == null &&
                    after?.signatureSlots?.single()?.signatureBytes != null,
            )
        }
    }

    internal fun requirePersistence(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) = original.requireActivationPersistence(this, ownership, jdbc)
    internal fun headBindingArguments(): Array<Any?> = checkNotNull(headBinding)
    internal fun pendingBindingArguments(): Array<Any?> = checkNotNull(pendingBinding)
    internal fun projectedBindingArguments(): Array<Any?> = checkNotNull(projectedBinding)
    internal fun initialHistoryArguments(): Array<Any?> = initialHistory
    internal fun finalHistoryArguments(): Array<Any?> = finalHistory
    internal fun insertArguments(): Array<Any?> = insert
    internal fun signatureArguments(): Array<Any?> = checkNotNull(signature)
    internal fun completionArguments(): Array<Any?> = checkNotNull(completion)
    internal fun projectionArguments(): Array<Any?> = checkNotNull(projection)
    internal fun headArguments(): Array<Any?> = checkNotNull(advancingHead)
    internal fun clearPendingArguments(): Array<Any?> = checkNotNull(pendingBinding)

    private fun activationArguments(value: CatalogFrozenMutation): Array<Any?> = arrayOf(
        token, UUID.fromString(manifest.catalogWriterGenerationId), inputs.approvalBytes(), digest(Sha256.hex(inputs.approvalBytes())),
        inputs.intentBytes(), digest(inputs.unsignedHash), manifest.requiredSignerPolicy.members.single().keyId,
        manifest.requiredSignerPolicy.members.single().algorithmId, CatalogReadbackProtocol.key(3),
        Timestamp.from(Instant.ofEpochSecond(manifest.creation.createdAtEpochSecond)), digest(manifest.previousEnvelopeSha256),
        value.signatureSlots.single().signatureBytes, value.signedEnvelopeBytes, value.signedEnvelopeSha256?.let(::digest),
    )

    override fun toString(): String = "CatalogSignerRotationActivationInputV1(fixed2-to3,detached,original-owner,redacted)"

    companion object {
        internal fun create(
            original: CatalogSignerRotationActivationV1,
            kind: CatalogSignerRotationActivationKindV1,
            expected: CatalogSignerRotationActivationObservationV1?,
            after: CatalogFrozenMutation? = null,
        ): CatalogSignerRotationActivationInputV1 {
            requireConnectionFree()
            original.requireInputConstruction(kind, expected, after)
            return CatalogSignerRotationActivationInputV1(original, kind, expected, after)
        }

        private fun overlapArguments(
            proof: CatalogDualLocationVerifier.Activation3Readback,
            limits: me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainLimits,
        ): Array<Any?> {
            val bytes = proof.overlapBytes()
            val frozen = CatalogFrozenManifestParser.signed(bytes, limits)
            val manifest = OfflineTrustBundleParser.parseRotation(bytes, limits.maximumManifestRecords).manifest
            val approvals = CanonicalJson.canonicalize(
                ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()),
                manifest.approvals,
            ).toByteArray(Charsets.UTF_8)
            return arrayOf(
                UUID.fromString(manifest.operationToken), UUID.fromString(manifest.catalogWriterGenerationId), approvals, digest(Sha256.hex(approvals)),
                frozen.manifestBytes,
                digest(
                    frozen.manifestSha256,
                ),
                manifest.requiredSignerPolicy.members[0].keyId, manifest.requiredSignerPolicy.members[0].algorithmId,
                manifest.requiredSignerPolicy.members[1].keyId, manifest.requiredSignerPolicy.members[1].algorithmId, CatalogReadbackProtocol.key(2),
                Timestamp.from(Instant.ofEpochSecond(manifest.creation.createdAtEpochSecond)), digest(manifest.previousEnvelopeSha256),
                Base64.getDecoder().decode(
                    frozen.signatures[0].signatureBase64,
                ),
                Base64.getDecoder().decode(frozen.signatures[1].signatureBase64), bytes, digest(Sha256.hex(bytes)),
            )
        }

        internal fun overlapCopies(proof: CatalogDualLocationVerifier.Activation3Readback): Array<Any?> =
            copies(proof.overlapObjectVersion, proof.overlapRetainUntilEpochSecond, proof.overlapPrimaryEvidenceBytes(), proof.overlapReplicaEvidenceBytes())
        private fun copyArguments(proof: CatalogDualLocationVerifier.Activation3Readback): Array<Any?> =
            copies(proof.objectVersion, proof.retainUntilEpochSecond, checkNotNull(proof.primaryEvidenceBytes()), checkNotNull(proof.replicaEvidenceBytes()))
        internal fun copies(version: String, retained: Long, primary: ByteArray, replica: ByteArray): Array<Any?> =
            arrayOf(version, Timestamp.from(Instant.ofEpochSecond(retained)), primary, digest(Sha256.hex(primary)), replica, digest(Sha256.hex(replica)))
        private fun digest(value: String): ByteArray = HexFormat.of().parseHex(value)
    }
}
