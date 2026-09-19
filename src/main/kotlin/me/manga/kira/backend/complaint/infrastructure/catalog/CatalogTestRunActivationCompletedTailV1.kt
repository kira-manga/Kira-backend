package me.manga.kira.backend.complaint.infrastructure.catalog

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/** One bounded detached TEST completion row. Raw facts only; neither bytes nor timestamp grant COMPLETE or PROJECT. */
internal class CatalogTestRunActivationCompletedTailV1 private constructor(
    val objectVersion: String,
    val retainUntil: Instant,
    private val primary: ByteArray,
    private val primaryHash: ByteArray,
    private val replica: ByteArray,
    private val replicaHash: ByteArray,
    val completedAt: Instant,
) {
    fun arguments(): Array<Any?> = arrayOf(
        objectVersion, Timestamp.from(retainUntil), primary.copyOf(), primaryHash.copyOf(), replica.copyOf(), replicaHash.copyOf(), Timestamp.from(completedAt),
    )

    fun requireExact(proof: CatalogTestRunActivationDeliveryReadbackV1) {
        check(proof.state === CatalogTestRunActivationDeliveryReadbackV1.State.DUAL_COPY)
        check(objectVersion == proof.objectVersion && retainUntil == Instant.ofEpochSecond(checkNotNull(proof.retainUntilEpochSecond)))
        check(primary.contentEquals(proof.primaryEvidenceBytes()) && primaryHash.contentEquals(proof.primaryEvidenceHash()))
        check(replica.contentEquals(proof.replicaEvidenceBytes()) && replicaHash.contentEquals(proof.replicaEvidenceHash()))
    }

    fun requireSame(other: CatalogTestRunActivationCompletedTailV1) {
        check(objectVersion == other.objectVersion && retainUntil == other.retainUntil && completedAt == other.completedAt)
        check(primary.contentEquals(other.primary) && primaryHash.contentEquals(other.primaryHash) &&
            replica.contentEquals(other.replica) && replicaHash.contentEquals(other.replicaHash))
    }

    fun requireCustody(version: String, retainUntilEpochSecond: Long, primaryBytes: ByteArray, replicaBytes: ByteArray) {
        check(objectVersion == version && retainUntil == Instant.ofEpochSecond(retainUntilEpochSecond) &&
            primary.contentEquals(primaryBytes) && replica.contentEquals(replicaBytes))
    }

    override fun toString(): String = "CatalogTestRunActivationCompletedTailV1(bounded-exact-row,no-projection-or-run-authority)"

    companion object {
        fun copy(row: ResultSet): CatalogTestRunActivationCompletedTailV1? {
            check(row.requiredTestActivationBoolean("valid"))
            val completed = row.requiredTestActivationBoolean("completed")
            val version = row.getString("object_version")
            val retainUntil = row.getTimestamp("retain_until")?.toInstant()
            val primary = row.getBytes("primary_evidence_bytes")
            val primaryHash = row.getBytes("primary_evidence_hash")
            val replica = row.getBytes("replica_evidence_bytes")
            val replicaHash = row.getBytes("replica_evidence_hash")
            val completedAt = row.getTimestamp("completed_at")?.toInstant()
            if (!completed) {
                check(version == null && retainUntil == null && primary == null && primaryHash == null && replica == null && replicaHash == null && completedAt == null)
                return null
            }
            check(primary != null && primary.size in 1..65536 && primaryHash != null && primaryHash.size == 32)
            check(replica != null && replica.size in 1..65536 && replicaHash != null && replicaHash.size == 32)
            return CatalogTestRunActivationCompletedTailV1(
                checkNotNull(version), checkNotNull(retainUntil), primary.copyOf(), primaryHash.copyOf(), replica.copyOf(), replicaHash.copyOf(), checkNotNull(completedAt),
            )
        }
    }
}

/** The one TEST tail only. Historical raw rows never escape the six-column fingerprint stream. */
internal class CatalogTestRunActivationDeliveryTailV1 private constructor(
    val signed: CatalogTestRunActivationSignedTailV1,
    val completed: CatalogTestRunActivationCompletedTailV1?,
) {
    companion object {
        fun copy(row: ResultSet, expected: CatalogTestRunActivationSignedV1): CatalogTestRunActivationDeliveryTailV1 =
            CatalogTestRunActivationDeliveryTailV1(checkNotNull(CatalogTestRunActivationSignedTailV1.copy(row, expected)), CatalogTestRunActivationCompletedTailV1.copy(row))
    }
}
