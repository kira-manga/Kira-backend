package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.security.EpochSealAttemptV1
import me.manga.kira.backend.security.EpochSealCanonicalV1
import me.manga.kira.backend.security.EpochSealContentV1
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import java.sql.ResultSet
import java.util.HexFormat
import java.util.UUID

/** Bounded detached canonical-only format1 intent. Neither a row copy nor local canonical content authorizes persistence or dispatch. */
internal class CatalogSealCanonicalRowV1 private constructor(
    val rotationId: UUID,
    val sequence: Long,
    val preparingFencingToken: Long,
    val routingKeyId: String,
    val epochStart: Long,
    private val predecessor: ByteArray,
    val epochEnd: Long,
    val writer: UUID,
    val operationToken: UUID,
    val objectKey: String,
    private val bytes: ByteArray,
    private val hash: ByteArray,
) {
    internal fun restore(routing: VersionBoundComplaintJournalRouting, attempt: EpochSealAttemptV1): EpochSealContentV1 {
        requireConnectionFree()
        val content = EpochSealCanonicalV1(routing).restoreCanonical(bytes, routingKeyId, objectKey, HexFormat.of().formatHex(hash), attempt)
        val payload = content.payload
        check(payload.writerGeneration == writer.toString() && payload.preparingFencingToken == preparingFencingToken)
        check(payload.epochStartInclusive == epochStart && payload.epochEndInclusive == epochEnd)
        check(payload.precedingSealSha256.isEmpty() && predecessor.isEmpty())
        return content
    }

    internal fun sameIntent(other: CatalogSealCanonicalRowV1): Boolean = rotationId == other.rotationId && sequence == other.sequence &&
        preparingFencingToken == other.preparingFencingToken && routingKeyId == other.routingKeyId && epochStart == other.epochStart &&
        predecessor.contentEquals(other.predecessor) && epochEnd == other.epochEnd && writer == other.writer && operationToken == other.operationToken &&
        objectKey == other.objectKey && bytes.contentEquals(other.bytes) && hash.contentEquals(other.hash)

    internal fun requireSlot(slot: CatalogEpochRotationSlotV1) {
        check(rotationId == slot.id && sequence == slot.sequence && writer == slot.writer && epochEnd == slot.epochBefore)
        check(sequence == 1L && epochStart == 1L && predecessor.isEmpty() && preparingFencingToken > 0)
    }

    /** Only immutable bounded copies/comparisons run under the later actual control lock. */
    internal fun insertArguments(): Array<Any?> = arrayOf(
        epochEnd, writer, operationToken, objectKey, bytes.copyOf(), hash.copyOf(), rotationId, sequence,
        preparingFencingToken, routingKeyId, epochStart, predecessor.copyOf(),
    )

    internal fun semanticSha256(): String = HexFormat.of().formatHex(hash)

    override fun toString(): String = "CatalogSealCanonicalRowV1(detached,redacted,NOT-wire-ready)"

    companion object {
        /** Called only after the complete original attempt's released manifest; nonce generation remains connection-free. */
        internal fun fresh(content: EpochSealContentV1, slot: CatalogEpochRotationSlotV1): CatalogSealCanonicalRowV1 {
            requireConnectionFree()
            val payload = content.payload
            check(payload.epochStartInclusive == 1L && payload.precedingSealSha256.isEmpty() && payload.preparingFencingToken > 0)
            check(payload.epochEndInclusive == slot.epochBefore && payload.writerGeneration == slot.writer.toString() && slot.sequence == 1L)
            return CatalogSealCanonicalRowV1(
                slot.id, slot.sequence, payload.preparingFencingToken, content.route.routingKeyId, payload.epochStartInclusive,
                byteArrayOf(), payload.epochEndInclusive, slot.writer, UUID.randomUUID(), content.route.objectKey,
                content.canonicalBytes(), HexFormat.of().parseHex(content.semanticSha256),
            )
        }

        internal fun copy(row: ResultSet): CatalogSealCanonicalRowV1? {
            val state = row.getString("seal_state") ?: return null // The enclosing projection checks the ENTIRE absent cohort first.
            check(state == "SEAL_PREPARED" && row.getInt("seal_format") == 1 && !row.wasNull())
            return CatalogSealCanonicalRowV1(
                requiredUuid(row, "seal_rotation_id"), requiredLong(row, "seal_rotation_sequence").also { check(it == 1L) },
                requiredLong(row, "seal_preparing_fencing_token").also { check(it > 0) }, checkNotNull(row.getString("seal_routing_key_id")),
                requiredLong(row, "seal_epoch_start").also { check(it == 1L) }, checkNotNull(row.getBytes("seal_preceding_hash")).copyOf(),
                requiredLong(row, "seal_epoch").also { check(it > 0) }, requiredUuid(row, "seal_writer_generation"),
                requiredUuid(row, "seal_operation_token"), checkNotNull(row.getString("seal_object_key")),
                checkNotNull(row.getBytes("seal_bytes")).copyOf(), checkNotNull(row.getBytes("seal_hash")).copyOf(),
            )
        }

        private fun requiredLong(row: ResultSet, name: String): Long = row.getLong(name).also { check(!row.wasNull()) }

        private fun requiredUuid(row: ResultSet, name: String): UUID = checkNotNull(row.getObject(name, UUID::class.java)).also {
            check(it.version() == 4 && it.variant() == 2)
        }
    }
}

/** Bounded full control observation. Checkpoint and legacy/unlinked seal history is refused, not treated as an empty row. */
internal class CatalogCutoffControlRowV1 private constructor(
    val rotation: CatalogEpochRotationRowV1,
    val seal: CatalogSealCanonicalRowV1?,
    private val preimage: CatalogCutoffControlPreimageV1,
) {
    internal fun sameState(other: CatalogCutoffControlRowV1): Boolean = rotation.sameState(other.rotation) && preimage.sameState(other.preimage) &&
        sameSeal(other.seal)

    internal fun sameSeal(expected: CatalogSealCanonicalRowV1?): Boolean = if (seal == null) expected == null else expected != null && seal.sameIntent(expected)

    internal fun sameExceptCanonicalAndUpdatedAt(other: CatalogCutoffControlRowV1): Boolean = preimage.sameExceptUpdatedAt(other.preimage)

    internal fun preimageArguments(): Array<Any?> = preimage.arguments()

    override fun toString(): String = "CatalogCutoffControlRowV1(detached,bounded,no-authority)"

    companion object {
        internal fun copy(row: ResultSet): CatalogCutoffControlRowV1 {
            check(row.getBoolean("cutoff_seal_valid") && !row.wasNull())
            check(row.getBoolean("cutoff_checkpoint_empty") && !row.wasNull())
            check(row.getBoolean("cutoff_finite_times") && !row.wasNull())
            return CatalogCutoffControlRowV1(
                CatalogEpochRotationRowV1.copy(row),
                CatalogSealCanonicalRowV1.copy(row),
                CatalogCutoffControlPreimageV1.copy(row),
            )
        }
    }
}
