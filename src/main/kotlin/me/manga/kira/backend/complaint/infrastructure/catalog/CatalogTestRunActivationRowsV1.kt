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
) {
    internal fun requireExpected(input: CatalogTestRunActivationFrozenV1, prepared: Boolean) {
        control.requirePredecessor(input)
        history.requireExpected(input.generation, prepared)
        if (prepared) check(control.maintenanceClosed && control.creationClosed)
    }

    internal fun requireSame(other: CatalogTestRunActivationSnapshotV1, closed: Boolean) {
        control.requireSame(other.control, closed)
        history.requireSame(other.history)
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
) {
    fun requirePredecessor(input: CatalogTestRunActivationFrozenV1) {
        check(head.generation == input.generation - 1L && head.envelopeSha256 == input.predecessorHash && catalogWriter == input.catalogWriter)
        check(databaseIdentity == input.databaseIdentity && restoreIdentity == input.restoreIdentity && eventWriter == input.eventWriter)
        check(trustHash == input.currentTrustHash)
    }

    fun requireSame(other: CatalogTestRunActivationControlV1, closed: Boolean) {
        check(core == other.core)
        if (closed) check(maintenanceClosed && creationClosed) else {
            check(maintenanceClosed == other.maintenanceClosed && creationClosed == other.creationClosed)
        }
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
            )
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
    private val digest: ByteArray,
    private val predecessorDigest: ByteArray,
    private val rawBindings: ByteArray?,
    private val retainedUntilMicros: LongArray?,
) {
    fun requireExpected(generation: Long, prepared: Boolean) {
        check(this.prepared == prepared && size.toLong() == generation - 1L + (if (prepared) 1L else 0L))
    }

    fun requireSame(other: CatalogTestRunActivationHistoryV1) {
        check(size == other.size && prepared == other.prepared && digest.contentEquals(other.digest))
    }

    fun requireUnchangedPrefix(before: CatalogTestRunActivationHistoryV1, appended: Boolean) {
        if (appended) {
            check(prepared && !before.prepared && size == before.size + 1 && predecessorDigest.contentEquals(before.digest))
        } else {
            requireSame(before)
        }
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
        fun read(rows: ResultSet, input: CatalogTestRunActivationFrozenV1, retainRaw: Boolean): CatalogTestRunActivationHistoryV1 {
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
            all.update(long(count.toLong()))
            prefix.update(long(predecessorCount.toLong()))
            return CatalogTestRunActivationHistoryV1(count, prepared, all.digest(), prefix.digest(), bindings, retention)
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
