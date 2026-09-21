package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveCheckpointHistoryV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentCheckpointDocumentV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import me.manga.kira.backend.complaint.infrastructure.terminal.VersionBoundTestOrdinarySealV1
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Bounded physical comparison reader. Callers supply their own locked source rows and current
 * holder; this never revives the producer, a native observation, payment or a successful scan.
 * Legacy V26-only N=1 is represented by NO archive rows, not an invented charged Entry.
 */
internal class TestActiveRecurrentHistoryV1 private constructor(val records: List<Record>) : AutoCloseable {
    val commitment: TestActiveCheckpointHistoryV1? = records.takeIf { it.isNotEmpty() }?.let { TestActiveCheckpointHistoryV1.of(it.map(Record::entry)) }
    fun requireSame(other: TestActiveRecurrentHistoryV1) {
        requireRecurrent(records.size == other.records.size)
        records.zip(other.records).forEach { (a, b) -> a.requireSame(b) }
    }
    override fun close() = records.forEach(Record::close)
    override fun toString(): String = "RecurrentHistory(bounded-physical-comparisons,no-authority)"

    class Record private constructor(val entry: TestActiveCheckpointHistoryV1.Entry, val verification: TestActiveRecurrentVerificationV1,
        private val checkpoint: ByteArray?, val checkpointSha256: String?, val checkpointedAt: Instant?, val archivedAt: Instant) : AutoCloseable {
        fun checkpointBytes(): ByteArray? = checkpoint?.copyOf()
        fun verificationBytes(): ByteArray = verification.canonicalBytes()
        fun requireSame(other: Record) {
            requireRecurrent(entry.sha256 == other.entry.sha256 && checkpoint.contentEquals(other.checkpoint) && checkpointSha256 == other.checkpointSha256 &&
                checkpointedAt == other.checkpointedAt && archivedAt == other.archivedAt)
            verification.requireSame(other.verification)
        }
        fun insertArguments(): Array<Any?> {
            val initial = entry.source == TestActiveCheckpointHistoryV1.Source.V26_INITIAL
            return arrayOf(entry.identity.scope, entry.ordinal, entry.operationToken, entry.source.name,
                if (initial) entry.operationToken else null, if (initial) null else entry.operationToken, entry.canonicalBytes(), recurrentBytes(entry.sha256),
                entry.objectVersion, verification.canonicalBytes(), recurrentBytes(verification.sha256), checkpoint?.copyOf(), checkpointSha256?.let(::recurrentBytes),
                Timestamp.from(archivedAt), checkpointedAt?.let(Timestamp::from))
        }
        override fun close() { verification.close(); checkpoint?.fill(0) }
        override fun toString(): String = "RecurrentHistoryRecord(physical-checkpoint-included,root-checkpoint-excluded,redacted)"

        companion object {
            /** Comparison candidate only. The fixed writer independently checks native/current authority before INSERT. */
            fun fromCurrent(intent: TestActiveRecurrentIntentV1, current: TestActiveRecurrentCurrentV1,
                manifestFramedBytes: Long, acquisition: VersionBoundTestOrdinarySealV1): Record {
                val verification = current.proof(intent, acquisition)
                val checkpoint = if (intent.ordinal == 1) current.checkpointBytes() else null
                try {
                    if (intent.ordinal == 1) requireRecurrent(current.requireCheckpoint(intent, null) == manifestFramedBytes)
                    else requireRecurrent(current.checkpointSha256 == null)
                    return Record(projectEntry(intent, verification, manifestFramedBytes), verification, checkpoint,
                        if (intent.ordinal == 1) current.checkpointSha256 else null, if (intent.ordinal == 1) current.checkpointedAt else null, current.sampledAt)
                } catch (problem: Throwable) { verification.close(); checkpoint?.fill(0); throw problem }
            }

            internal fun read(row: ResultSet, intents: List<TestActiveRecurrentIntentV1>, sampledAt: Instant,
                acquisition: VersionBoundTestOrdinarySealV1): Record {
                requireRecurrent(row.getBoolean("valid") && !row.wasNull())
                val bytes = checkNotNull(row.getBytes("entry_bytes"))
                val entry = try { TestActiveCheckpointHistoryV1.Entry.parse(bytes).also { requireRecurrent(it.sha256 == recurrentHash(row, "entry_hash")) } }
                    finally { bytes.fill(0) }
                val source = intents.single { it.ordinal == entry.ordinal }
                requireRecurrent(source.source == entry.source && source.token.toString() == entry.operationToken &&
                    row.getObject("data_scope_id", UUID::class.java) == source.identity.scope && row.getObject("operation_token", UUID::class.java) == source.token &&
                    recurrentLong(row, "ordinal") == entry.ordinal.toLong() && row.getString("source") == entry.source.name &&
                    row.getObject("initial_seal_token", UUID::class.java) == (if (entry.ordinal == 1) source.token else null))
                requireRecurrent(row.getObject("recurrent_seal_token", UUID::class.java) == (if (entry.ordinal == 1) null else source.token))
                val proofBytes = checkNotNull(row.getBytes("verification_bytes"))
                val proof = try { TestActiveRecurrentVerificationV1.parse(proofBytes, recurrentHash(row, "verification_hash"), checkNotNull(source.payload),
                    checkNotNull(row.getString("object_version")), entry.retainUntil, entry.verifiedAt, sampledAt, acquisition) } finally { proofBytes.fill(0) }
                var checkpoint: ByteArray? = null
                try {
                    requireRecurrent(projectEntry(source, proof, entry.manifestFramedBytes).sha256 == entry.sha256 &&
                        recurrentLong(row, "charged_storage_bytes") == TestActiveRecurrentStorageV1.HISTORY_STORAGE_BYTES)
                    checkpoint = row.getBytes("checkpoint_bytes")
                    val checkpointHash = row.getBytes("checkpoint_hash")?.let(::recurrentHex)
                    val checkpointedAt = row.getTimestamp("checkpointed_at")?.toInstant()
                    val archivedAt = recurrentTime(row, "archived_at")
                    requireRecurrent(archivedAt >= entry.verifiedAt && archivedAt <= sampledAt)
                    if (checkpoint == null) requireRecurrent(checkpointHash == null && checkpointedAt == null && entry.ordinal > 1)
                    else requireRecurrent(Sha256.hex(checkpoint) == checkpointHash && checkNotNull(checkpointedAt) <= sampledAt)
                    return Record(entry, proof, checkpoint, checkpointHash, checkpointedAt, archivedAt)
                } catch (problem: Throwable) { proof.close(); checkpoint?.fill(0); throw problem }
            }

            private fun projectEntry(i: TestActiveRecurrentIntentV1, v: TestActiveRecurrentVerificationV1, framed: Long): TestActiveCheckpointHistoryV1.Entry {
                val row = checkNotNull(i.payload); val b = row.binding; val seal = i.seal()
                return TestActiveCheckpointHistoryV1.Entry(recurrentHistoryIdentity(i.identity), i.source, i.ordinal, i.token.toString(),
                    i.epochStart, i.epochEnd, checkNotNull(i.epochAfter), i.requestOwner.toString(), i.requestToken, i.requestedAt,
                    checkNotNull(i.captureOwner).toString(), checkNotNull(i.captureToken), checkNotNull(i.capturedAt), b.preparingFencingToken,
                    b.objectId, b.objectKey, b.routingKeyId, row.canonicalSha256, checkNotNull(row.wireSha256), checkNotNull(row.metadataSha256),
                    i.identity.sealEncodingSha256, b.retentionFloor, b.createdAt, checkNotNull(row.retainUntil), checkNotNull(row.frozenAt),
                    v.version, v.lastModified, v.retainUntil, v.verifiedAt, v.sha256, seal.precedingSealSha256, seal.eventCount,
                    seal.eventManifestSha256, framed, TestActiveRecurrentStorageV1.INTENT_STORAGE_BYTES, TestActiveRecurrentStorageV1.HISTORY_STORAGE_BYTES)
            }
        }
    }

    companion object {
        fun read(rows: ResultSet, intents: List<TestActiveRecurrentIntentV1>, sampledAt: Instant,
            acquisition: VersionBoundTestOrdinarySealV1): TestActiveRecurrentHistoryV1 {
            val records = ArrayList<Record>()
            try {
                while (rows.next()) {
                    requireRecurrent(records.size < TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS)
                    val record = Record.read(rows, intents, sampledAt, acquisition)
                    records.add(record)
                    requireRecurrent(record.entry.ordinal == records.size)
                    requireArchivedCheckpoint(record, records, intents[records.size - 1])
                }
                val value = TestActiveRecurrentHistoryV1(records.toList())
                requireRecurrent(records.size in maxOf(0, intents.size - 1)..intents.size)
                if (records.isEmpty()) requireRecurrent(intents.size == 1)
                records.dropLast(1).forEach { requireRecurrent(it.checkpointSha256 != null) }
                intents.drop(1).forEachIndexed { index, intent ->
                    val predecessor = records[index]
                    requireRecurrent(intent.predecessorToken.toString() == predecessor.entry.operationToken && intent.predecessorCheckpointSha256 == predecessor.checkpointSha256 &&
                        intent.predecessorHistorySha256 == TestActiveCheckpointHistoryV1.of(records.take(index + 1).map(Record::entry)).rootSha256 &&
                        intent.epochStart == predecessor.entry.epochEnd + 1)
                }
                return value
            } catch (problem: Throwable) { records.forEach(Record::close); throw problem }
        }

        private fun requireArchivedCheckpoint(record: Record, prefix: List<Record>, intent: TestActiveRecurrentIntentV1) {
            val bytes = record.checkpointBytes() ?: return
            try {
                val e = record.entry; val i = e.identity
                if (e.ordinal == 1) {
                    val doc = TestInitialCheckpointCurrentCodecV1.checkpoint(bytes)
                    requireRecurrent(doc.scope == i.scope && doc.desiredGeneration == i.desiredGeneration && doc.configurationSha256 == i.configurationSha256 &&
                        doc.journalConfigurationSha256 == i.journalConfigurationSha256 && doc.databaseIdentity == i.databaseIdentity && doc.restoreIdentity == i.restoreIdentity &&
                        doc.writerGeneration == i.writerGeneration && doc.catalogGeneration == i.catalogGeneration && doc.catalogSha256 == i.catalogSha256 &&
                        doc.trustBundleSha256 == i.trustBundleSha256 && doc.catalogWriterGeneration == i.catalogWriterGeneration && doc.sealOperationToken == e.operationToken &&
                        doc.sealObjectKey == e.objectKey && doc.sealObjectVersion == e.objectVersion && doc.sealCanonicalSha256 == e.canonicalSha256 &&
                        doc.sealCiphertextSha256 == e.wireSha256 && doc.manifestSha256 == e.manifestSha256 && doc.manifestFramedBytes == e.manifestFramedBytes &&
                        e.eventCount == 0L && doc.fencingToken > e.preparingFencingToken && doc.startedAt >= e.verifiedAt && doc.completedAt == record.checkpointedAt)
                } else {
                    val doc = TestActiveRecurrentCheckpointDocumentV1.parse(bytes)
                    doc.requireHistory(TestActiveCheckpointHistoryV1.of(prefix.map(Record::entry)))
                    requireRecurrent(doc.predecessorCheckpointSha256 == intent.predecessorCheckpointSha256 && doc.completedAt == record.checkpointedAt)
                }
            } finally { bytes.fill(0) }
        }
    }
}
