package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.builtins.ListSerializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogObjectMetadata
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** Immutable bounded SQL observations, not a run/projection/provider capability. Issuance stays with the original operation. */
internal class CatalogTestRunActivationSnapshotV1(
    val control: CatalogTestRunActivationControlV1,
    val history: CatalogTestRunActivationHistoryV1,
    val signedTail: CatalogTestRunActivationSignedTailV1? = null,
    val completedTail: CatalogTestRunActivationCompletedTailV1? = null,
) {
    internal fun requireExpected(input: CatalogTestRunActivationFrozenV1, prepared: Boolean, signed: CatalogTestRunActivationSignedV1? = null) {
        check(completedTail == null)
        control.requirePredecessor(input)
        history.requireExpected(input.generation, prepared, signed != null)
        if (signed == null) check(signedTail == null) else {
            check(prepared && signed.frozen === input)
            checkNotNull(signedTail).requireExact(signed)
        }
        if (prepared) check(control.maintenanceClosed && control.creationClosed)
    }

    internal fun requireSame(other: CatalogTestRunActivationSnapshotV1, closed: Boolean) {
        control.requireSame(other.control, closed)
        history.requireSame(other.history)
        if (signedTail == null) check(other.signedTail == null) else signedTail.requireSame(checkNotNull(other.signedTail))
        if (completedTail == null) check(other.completedTail == null) else completedTail.requireSame(checkNotNull(other.completedTail))
    }

    internal fun requireDelivery(input: CatalogTestRunActivationFrozenV1, signed: CatalogTestRunActivationSignedV1) {
        check(signed.frozen === input)
        checkNotNull(signedTail).requireExact(signed)
        history.requireExpected(input.generation, prepared = true, signed = true, completed = completedTail != null)
        control.requireDelivery(input, signed, completedTail != null)
    }

    override fun toString(): String = "CatalogTestRunActivationSnapshotV1(detached,no-authority)"
}

internal class CatalogTestRunActivationControlV1 private constructor(
    private val core: String,
    val maintenanceClosed: Boolean,
    val creationClosed: Boolean,
    val head: CatalogLocalHead,
    private val databaseIdentity: UUID?,
    private val restoreIdentity: UUID?,
    private val eventWriter: UUID?,
    private val catalogWriter: UUID,
    private val trustHash: String,
    val leaseToken: Long,
    val pendingToken: UUID?,
) {
    fun requirePredecessor(input: CatalogTestRunActivationFrozenV1) {
        check(pendingToken == null)
        check(head.generation == input.generation - 1L && head.envelopeSha256 == input.predecessorHash && catalogWriter == input.catalogWriter)
        check(databaseIdentity == input.databaseIdentity && restoreIdentity == input.restoreIdentity && eventWriter == input.eventWriter)
        check(trustHash == input.currentTrustHash)
    }

    fun requireSame(other: CatalogTestRunActivationControlV1, closed: Boolean) {
        check(core == other.core && head == other.head && pendingToken == other.pendingToken)
        if (closed) check(maintenanceClosed && creationClosed) else {
            check(maintenanceClosed == other.maintenanceClosed && creationClosed == other.creationClosed)
        }
    }

    fun requireDelivery(input: CatalogTestRunActivationFrozenV1, signed: CatalogTestRunActivationSignedV1, completed: Boolean) {
        check(maintenanceClosed && creationClosed && catalogWriter == input.catalogWriter && trustHash == input.currentTrustHash)
        check(databaseIdentity == input.databaseIdentity && restoreIdentity == input.restoreIdentity && eventWriter == input.eventWriter)
        if (completed) {
            check(head.generation == input.generation && head.envelopeSha256 == signed.envelopeSha256 && pendingToken == input.token)
        } else requirePredecessor(input)
    }

    /** The delivery query normalizes ONLY head/hash/pending to the original predecessor for this byte comparison. */
    fun requireCompletionTransition(before: CatalogTestRunActivationControlV1, input: CatalogTestRunActivationFrozenV1, signed: CatalogTestRunActivationSignedV1) {
        check(core == before.core && before.maintenanceClosed && before.creationClosed)
        requireDelivery(input, signed, completed = true)
        before.requireDelivery(input, signed, completed = before.pendingToken != null)
    }

    /** Private exact global preimage only, deliberately distinct from the target full-D configuration. */
    internal fun custodyBytes(): ByteArray {
        requireConnectionFree()
        return core.toByteArray(Charsets.UTF_8).also { check(it.size in 1..524288) }
    }

    override fun toString(): String = "CatalogTestRunActivationControlV1(exact-global-preimage,redacted)"

    companion object {
        fun copy(row: ResultSet): CatalogTestRunActivationControlV1 {
            check(row.requiredTestActivationBoolean("valid"))
            val core = checkNotNull(row.getString("core_preimage"))
            check(core.length in 1..524288)
            return CatalogTestRunActivationControlV1(
                core, row.requiredTestActivationBoolean("maintenance_closed"), row.requiredTestActivationBoolean("creation_closed"),
                CatalogLocalHead(row.requiredTestActivationLong("accepted_catalog_generation"), HexFormat.of().formatHex(checkNotNull(row.getBytes("accepted_catalog_hash")))),
                row.getObject("database_identity", UUID::class.java), row.getObject("restore_identity", UUID::class.java),
                row.getObject("event_writer_generation", UUID::class.java), checkNotNull(row.getObject("catalog_writer_generation", UUID::class.java)),
                HexFormat.of().formatHex(checkNotNull(row.getBytes("trust_bundle_hash"))),
                row.requiredTestActivationLong("lease_token"),
                row.getObject("pending_projection_token", UUID::class.java),
            )
        }
    }
}

/** Only the one TEST tail can detach raw bytes; the historical stream still has six fixed-width columns. */
internal class CatalogTestRunActivationSignedTailV1 private constructor(
    private val signature: ByteArray,
    private val envelope: ByteArray,
    private val hash: ByteArray,
) {
    fun requireExact(expected: CatalogTestRunActivationSignedV1) = expected.requireExact(signature, envelope, hash)

    fun requireSame(other: CatalogTestRunActivationSignedTailV1) {
        check(signature.contentEquals(other.signature) && envelope.contentEquals(other.envelope) && hash.contentEquals(other.hash))
    }

    override fun toString(): String = "CatalogTestRunActivationSignedTailV1(bounded-exact-row-bytes,no-authority)"

    companion object {
        fun copy(row: ResultSet, expected: CatalogTestRunActivationSignedV1): CatalogTestRunActivationSignedTailV1? {
            check(row.requiredTestActivationBoolean("valid"))
            val signature = row.getBytes("signature_bytes")
            val envelope = row.getBytes("envelope_bytes")
            val hash = row.getBytes("envelope_hash")
            if (signature == null) {
                check(envelope == null && hash == null)
                return null
            }
            val signedEnvelope = checkNotNull(envelope)
            val envelopeHash = checkNotNull(hash)
            check(signature.size == 384 && signedEnvelope.size in 1..131072 && envelopeHash.size == 32)
            return CatalogTestRunActivationSignedTailV1(signature.copyOf(), signedEnvelope.copyOf(), envelopeHash.copyOf()).also { it.requireExact(expected) }
        }
    }
}

internal class CatalogTestRunActivationLeaseV1 private constructor(val owner: UUID, val token: Long, val expiresAt: Instant) {
    fun arguments(): Array<Any?> = arrayOf(owner, token, Timestamp.from(expiresAt))
    override fun toString(): String = "CatalogTestRunActivationLeaseV1(released-row-facts,no-independent-lease-authority)"

    companion object {
        fun copy(row: ResultSet): CatalogTestRunActivationLeaseV1 = CatalogTestRunActivationLeaseV1(
            checkNotNull(row.getObject("lease_owner", UUID::class.java)), row.requiredTestActivationLong("lease_token"),
            checkNotNull(row.getTimestamp("lease_expires_at")).toInstant(),
        ).also { check(it.token > 0 && it.expiresAt.epochSecond in 0..253402300799L) }
    }
}

/**
 * One bounded initial packed raw proof (40 bytes per predecessor), never a history of row strings or signatures.
 * Later reads retain only two SHA-256 values/counts. SQL returns fixed-size fingerprints and streams fetch128;
 * hashes are private comparison data, not canonical JSON, signed facts or portable continuation authority.
 */
internal class CatalogTestRunActivationHistoryV1 private constructor(
    val size: Int,
    private val prepared: Boolean,
    private val signed: Boolean,
    private val completed: Boolean,
    private val digest: ByteArray,
    private val predecessorDigest: ByteArray,
    private val rawBindings: ByteArray?,
    private val retainedUntilMicros: LongArray?,
) {
    fun requireExpected(generation: Long, prepared: Boolean, signed: Boolean = false, completed: Boolean = false) {
        check(this.prepared == prepared && this.signed == signed && this.completed == completed &&
            size.toLong() == generation - 1L + (if (prepared) 1L else 0L))
    }

    fun requireSame(other: CatalogTestRunActivationHistoryV1) {
        check(size == other.size && prepared == other.prepared && signed == other.signed && completed == other.completed && digest.contentEquals(other.digest))
    }

    fun requireUnchangedPrefix(before: CatalogTestRunActivationHistoryV1, appended: Boolean) {
        if (appended) {
            check(prepared && !signed && !before.prepared && size == before.size + 1 && predecessorDigest.contentEquals(before.digest))
        } else {
            requireSame(before)
        }
    }

    fun requireSignatureTransition(before: CatalogTestRunActivationHistoryV1) {
        check(prepared && signed && !completed && !before.completed && before.prepared && size == before.size && predecessorDigest.contentEquals(before.predecessorDigest))
        if (before.signed) requireSame(before) // Exact signed replay changes neither tail nor any historical column.
    }

    fun requireCompletionTransition(before: CatalogTestRunActivationHistoryV1) {
        check(prepared && signed && completed && before.prepared && before.signed && size == before.size &&
            predecessorDigest.contentEquals(before.predecessorDigest))
        if (before.completed) requireSame(before)
    }

    internal fun custodyPrefixHash(): String {
        requireConnectionFree()
        return HexFormat.of().formatHex(predecessorDigest)
    }

    /** Connection-free raw binding. The caller must additionally fold the real full dual-copy chain and its Stable signer. */
    fun requireRaw(index: Int, raw: FrozenCatalogGeneration, primary: CatalogObjectMetadata) {
        requireConnectionFree()
        val bindings = checkNotNull(rawBindings)
        val retention = checkNotNull(retainedUntilMicros)
        check(index in retention.indices)
        val claims = raw.claims
        val expectedType = when (claims.operation) {
            "GENESIS" -> "GENESIS"
            "ROTATION_OVERLAP" -> "SIGNER_ROTATION_OVERLAP"
            "ROTATION_ACTIVATE" -> "SIGNER_ROTATION_ACTIVATION"
            "REGISTER_SOURCE", "ADD_COPY" -> "RESTORE_SOURCE_ACCEPTANCE"
            else -> error("Unsupported TEST activation predecessor.")
        }
        check(claims.generation == index + 1L)
        check(postgresMicros(checkNotNull(primary.retainUntilEpochSecond)) >= retention[index])
        val approvals = CanonicalJson.canonicalize(ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), claims.approvals).toByteArray(Charsets.UTF_8)
        val members = claims.requiredSignerPolicy.members
        check(members.size in 1..2 && raw.signatures.size == members.size)
        val first = members.first()
        val second = members.getOrNull(1)
        val fields = listOf(
            RAW_DOMAIN.toByteArray(Charsets.UTF_8), expectedType.toByteArray(Charsets.UTF_8), uuid(claims.operationToken), long(claims.generation),
            HexFormat.of().parseHex(claims.previousEnvelopeSha256), uuid(claims.catalogWriterGenerationId), HexFormat.of().parseHex(Sha256.hex(approvals)),
            HexFormat.of().parseHex(raw.manifestSha256), HexFormat.of().parseHex(checkNotNull(raw.envelopeSha256)),
            claims.requiredSignerPolicy.mode.toByteArray(Charsets.UTF_8), first.keyId.toByteArray(Charsets.UTF_8), first.algorithmId.toByteArray(Charsets.UTF_8),
            Base64.getDecoder().decode(raw.signatures.first().signatureBase64), second?.keyId?.toByteArray(Charsets.UTF_8),
            second?.algorithmId?.toByteArray(Charsets.UTF_8), raw.signatures.getOrNull(1)?.let { Base64.getDecoder().decode(it.signatureBase64) },
            CatalogReadbackProtocol.key(claims.generation).toByteArray(Charsets.UTF_8), primary.requestBinding.versionId.toByteArray(Charsets.UTF_8),
            long(postgresMicros(claims.creation.createdAtEpochSecond)), long(raw.manifestBytes.size.toLong()), long(checkNotNull(raw.envelopeBytes).size.toLong()),
        )
        val actual = MessageDigest.getInstance("SHA-256").apply {
            fields.forEach { field ->
                update(ByteBuffer.allocate(4).putInt(field?.size ?: -1).array())
                if (field != null) update(field)
            }
        }.digest()
        check(actual.indices.all { actual[it] == bindings[index * HASH_BYTES + it] })
    }

    override fun toString(): String = "CatalogTestRunActivationHistoryV1(packed-bounded-private-comparison,redacted)"

    companion object {
        internal const val RAW_DOMAIN = "kira-test-activation-sql-raw-v1"
        internal const val FETCH_ROWS = 128
        private const val HASH_BYTES = 32
        private const val HISTORY_DOMAIN = "kira-test-activation-sql-history-v1"

        /** JDBC's original ResultSet is consumed to exhaustion; no List<Row>, signature or textual preimage escapes. */
        fun read(rows: ResultSet, input: CatalogTestRunActivationFrozenV1, retainRaw: Boolean, signed: Boolean = false, completed: Boolean = false): CatalogTestRunActivationHistoryV1 {
            val predecessorCount = Math.toIntExact(input.generation - 1L)
            val bindings = if (retainRaw) ByteArray(Math.multiplyExact(predecessorCount, HASH_BYTES)) else null
            val retention = if (retainRaw) LongArray(predecessorCount) else null
            val all = historyDigest()
            val prefix = historyDigest()
            var count = 0
            var prepared = false
            while (rows.next()) {
                check(count < input.maximumGenerations && rows.requiredTestActivationBoolean("valid"))
                val generation = rows.requiredTestActivationLong("successor_generation")
                check(generation == count + 1L && generation <= input.generation)
                val rowHash = checkNotNull(rows.getBytes("row_digest")).also { check(it.size == HASH_BYTES) }
                val preparedMatches = rows.requiredTestActivationBoolean("prepared_matches")
                all.update(long(generation))
                all.update(rowHash)
                if (generation < input.generation) {
                    check(!preparedMatches)
                    prefix.update(long(generation))
                    prefix.update(rowHash)
                    if (retainRaw) {
                        val raw = checkNotNull(rows.getBytes("raw_binding")).also { check(it.size == HASH_BYTES) }
                        val time = checkNotNull(rows.getBytes("retain_until_wire")).also { check(it.size == java.lang.Long.BYTES) }
                        raw.copyInto(checkNotNull(bindings), count * HASH_BYTES)
                        checkNotNull(retention)[count] = ByteBuffer.wrap(time).long
                    }
                } else {
                    check(preparedMatches && !prepared)
                    prepared = true
                }
                count++
            }
            check(count in predecessorCount..input.maximumGenerations)
            check(!signed || prepared)
            check(!completed || signed)
            all.update(long(count.toLong()))
            prefix.update(long(predecessorCount.toLong()))
            return CatalogTestRunActivationHistoryV1(count, prepared, signed, completed, all.digest(), prefix.digest(), bindings, retention)
        }

        private fun historyDigest(): MessageDigest = MessageDigest.getInstance("SHA-256").apply { update(HISTORY_DOMAIN.toByteArray(Charsets.UTF_8)) }
        private fun long(value: Long): ByteArray = ByteBuffer.allocate(java.lang.Long.BYTES).putLong(value).array()
        private fun uuid(value: String): ByteArray = UUID.fromString(value).let {
            ByteBuffer.allocate(16).putLong(it.mostSignificantBits).putLong(it.leastSignificantBits).array()
        }
        private fun postgresMicros(epochSecond: Long): Long = Math.multiplyExact(Math.subtractExact(epochSecond, 946684800L), 1_000_000L)
    }
}

internal fun ResultSet.requiredTestActivationBoolean(column: String): Boolean = getBoolean(column).also { check(!wasNull()) }
internal fun ResultSet.requiredTestActivationLong(column: String): Long = getLong(column).also { check(!wasNull()) }
