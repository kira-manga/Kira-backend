package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Four fixed actions only. No caller-supplied B, pending allowance, arbitrary generation or SQL path. */
internal enum class CatalogSignerRotationFinalizationKindV1 { INITIAL_READ, RECHECK, COMPLETE, PROJECT }

/**
 * Connection-free detached arguments from the original retained delivery owner and released history.
 * G1's entire lifecycle/copy preimage is carried forward; raw tail2 never substitutes for accepted B1.
 * Construction/data equality alone grants nothing: the actual owner and phase retain this exact input.
 */
internal class CatalogSignerRotationFinalizationInputV1 private constructor(
    internal val original: CatalogSignerRotationDeliveryV1,
    internal val kind: CatalogSignerRotationFinalizationKindV1,
    internal val expected: CatalogSignerRotationFinalizationObservationV1?,
    readback: CatalogDualLocationVerifier.Overlap2Readback?,
) {
    internal val path: PersistencePhasePath = when (kind) {
        CatalogSignerRotationFinalizationKindV1.INITIAL_READ, CatalogSignerRotationFinalizationKindV1.RECHECK ->
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_FINAL_READ

        CatalogSignerRotationFinalizationKindV1.COMPLETE -> PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_COMPLETE

        CatalogSignerRotationFinalizationKindV1.PROJECT -> PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PROJECT
    }
    private val inputs = original.inputs
    private val manifest = inputs.manifest
    private val frozen = original.frozenMutation()
    private val token = UUID.fromString(manifest.operationToken)
    private val envelopeHash = digest(checkNotNull(frozen.signedEnvelopeSha256))
    private val preparedBinding = original.finalizationBindingArguments()
    private val projectedBinding = preparedBinding.copyOf().also {
        it[5] = 2L
        it[6] = envelopeHash.copyOf() // These two and only these two positions change from B1 to B2.
    }
    private val pendingBinding = arrayOf<Any?>(*projectedBinding, token)
    private val rotation = arrayOf<Any?>(
        token,
        UUID.fromString(manifest.catalogWriterGenerationId),
        inputs.approvalBytes(),
        digest(Sha256.hex(inputs.approvalBytes())),
        inputs.intentBytes(),
        digest(inputs.unsignedHash),
        manifest.requiredSignerPolicy.members[0].keyId,
        manifest.requiredSignerPolicy.members[0].algorithmId,
        manifest.requiredSignerPolicy.members[1].keyId,
        manifest.requiredSignerPolicy.members[1].algorithmId,
        CatalogReadbackProtocol.key(2),
        Timestamp.from(Instant.ofEpochSecond(manifest.creation.createdAtEpochSecond)),
        digest(manifest.previousEnvelopeSha256),
        checkNotNull(frozen.signatureSlots[0].signatureBytes),
        checkNotNull(frozen.signatureSlots[1].signatureBytes),
        checkNotNull(frozen.signedEnvelopeBytes),
        envelopeHash,
    )
    private val genesis = expected?.genesisArguments()
    private val copies: Array<Any?>? = when (kind) {
        CatalogSignerRotationFinalizationKindV1.COMPLETE -> copyArguments(checkNotNull(readback))
        CatalogSignerRotationFinalizationKindV1.PROJECT -> checkNotNull(expected).copyArguments()
        else -> null
    }
    private val preparedHistory = genesis?.let { arrayOf<Any?>(*rotation, *it) }
    private val pendingHistory = copies?.let { arrayOf<Any?>(*rotation, *checkNotNull(genesis), *it) }
    private val completion = copies?.let { arrayOf<Any?>(*rotation, *it) }
    private val projection = if (kind === CatalogSignerRotationFinalizationKindV1.PROJECT) {
        arrayOf<Any?>(*checkNotNull(completion), Timestamp.from(checkNotNull(expected?.completedAt)))
    } else {
        null
    }
    private val head = arrayOf<Any?>(*preparedBinding, token, envelopeHash)

    init {
        requireConnectionFree()
        requireSignerRotation(preparedBinding.size == 12 && preparedBinding[5] == 1L)
        inputs.requireMutation(frozen)
        expected?.let {
            inputs.requireObservation(CatalogSignerRotationObservationV1(it.genesis, it.mutation))
            requireSignerRotation(sameSignerRotationMutation(it.mutation, frozen), CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED)
        }
        readback?.let {
            requireSignerRotation(
                kind === CatalogSignerRotationFinalizationKindV1.COMPLETE &&
                    it.state === CatalogDualLocationVerifier.Overlap2Readback.State.PREPARED_DUAL_COPY &&
                    it.operationToken == manifest.operationToken && it.frozenEnvelopeBytes().contentEquals(frozen.signedEnvelopeBytes),
                CatalogSignerRotationFreezeFailureV1.RECOVERY_REQUIRED,
            )
        }
    }

    internal fun requirePersistence(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) = original.requireFinalizationPersistence(this, ownership, jdbc)
    internal fun preparedBindingArguments(): Array<Any?> = preparedBinding
    internal fun pendingBindingArguments(): Array<Any?> = pendingBinding
    internal fun projectedBindingArguments(): Array<Any?> = projectedBinding
    internal fun initialHistoryArguments(): Array<Any?> = rotation
    internal fun preparedHistoryArguments(): Array<Any?> = checkNotNull(preparedHistory)
    internal fun pendingHistoryArguments(): Array<Any?> = checkNotNull(pendingHistory)
    internal fun completionArguments(): Array<Any?> = checkNotNull(completion)
    internal fun projectionArguments(): Array<Any?> = checkNotNull(projection)
    internal fun headArguments(): Array<Any?> = head
    internal fun clearPendingArguments(): Array<Any?> = pendingBinding

    override fun toString(): String = "CatalogSignerRotationFinalizationInputV1(fixed-B1-B2,original-owner,redacted)"

    companion object {
        internal fun initial(original: CatalogSignerRotationDeliveryV1): CatalogSignerRotationFinalizationInputV1 =
            create(original, CatalogSignerRotationFinalizationKindV1.INITIAL_READ, null, null)

        internal fun recheck(
            original: CatalogSignerRotationDeliveryV1,
            expected: CatalogSignerRotationFinalizationObservationV1,
        ): CatalogSignerRotationFinalizationInputV1 = create(original, CatalogSignerRotationFinalizationKindV1.RECHECK, expected, null)

        internal fun complete(
            original: CatalogSignerRotationDeliveryV1,
            expected: CatalogSignerRotationFinalizationObservationV1,
            readback: CatalogDualLocationVerifier.Overlap2Readback,
        ): CatalogSignerRotationFinalizationInputV1 = create(original, CatalogSignerRotationFinalizationKindV1.COMPLETE, expected, readback)

        internal fun project(
            original: CatalogSignerRotationDeliveryV1,
            expected: CatalogSignerRotationFinalizationObservationV1,
        ): CatalogSignerRotationFinalizationInputV1 = create(original, CatalogSignerRotationFinalizationKindV1.PROJECT, expected, null)

        private fun create(
            original: CatalogSignerRotationDeliveryV1,
            kind: CatalogSignerRotationFinalizationKindV1,
            expected: CatalogSignerRotationFinalizationObservationV1?,
            readback: CatalogDualLocationVerifier.Overlap2Readback?,
        ): CatalogSignerRotationFinalizationInputV1 {
            requireConnectionFree()
            original.requireInputConstruction(kind, expected, readback)
            return CatalogSignerRotationFinalizationInputV1(original, kind, expected, readback)
        }

        private fun copyArguments(readback: CatalogDualLocationVerifier.Overlap2Readback): Array<Any?> {
            val primary = checkNotNull(readback.primaryEvidenceBytes())
            val replica = checkNotNull(readback.replicaEvidenceBytes())
            return arrayOf(
                readback.objectVersion,
                Timestamp.from(Instant.ofEpochSecond(readback.retainUntilEpochSecond)),
                primary,
                digest(Sha256.hex(primary)),
                replica,
                digest(Sha256.hex(replica)),
            )
        }

        private fun digest(value: String): ByteArray = HexFormat.of().parseHex(value)
    }
}
