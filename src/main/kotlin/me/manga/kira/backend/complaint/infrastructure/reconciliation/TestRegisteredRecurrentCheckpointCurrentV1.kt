package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveCheckpointHistoryV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentJsonV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.security.EpochSealFramesV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.security.MessageDigest
import java.time.Instant

/**
 * One fixed recurrent-current comparison reader. The CREATE/REPLY/EDIT owners separately prove
 * their typed operation and original ordinary holder before/after each use. No supplied proof,
 * producer operation, native acquisition, lock, write, cached eligibility or lease is issued here.
 */
internal class TestRegisteredRecurrentCheckpointCurrentV1(
    registration: ComplaintTestNamespaceRegistrationV1,
    private val jdbc: JdbcTemplate,
) {
    private val process = registration.process
    private val policy = checkNotNull(process.initialCheckpointCreate)
    private val source = checkNotNull(process.activeRecurrent)
    private val identity = TestActiveFirstCutIdentityV1.fromRegistration(registration)
    private val journal = process.consumers.journalConfiguration
    private val initialManifest = MessageDigest.getInstance("SHA-256").let { digest ->
        val framed = EpochSealFramesV1.update(digest, listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", identity.writer.toString(),
            journal.ordinaryPrefix, "TEST", identity.scope.toString(), "1", "1", "0"))
        requireRecurrent(framed in 1..journal.declaration().limits.capacity.maximumScanStagingBytes)
        recurrentHex(digest.digest()) to framed
    }

    private fun retained() {
        requireRecurrent(policy.recurrentCurrent && process.initialCheckpointCreate === policy && process.activeRecurrent === source)
        policy.requireRetained(process.pools, process.consumers.journalRouting, process.initialCheckpoint, source)
    }

    /** A missing/malformed/unsupported selector refuses; only untouched sequence1 uses the old reader. */
    internal fun selectInitial(): Boolean {
        retained()
        val sequence = jdbc.query(TestRegisteredRecurrentCheckpointCurrentSqlV1.branch, { row, _ ->
            requireRecurrent(row.getBoolean("valid") && !row.wasNull())
            recurrentLong(row, "rotation_sequence").also { requireRecurrent(it in 1..14) }
        }, identity.scope).single()
        return sequence == 1L
    }

    internal fun readCurrent(): Instant {
        retained()
        return readControl().use { current ->
            val intents = readIntents(current)
            try {
                checkNotNull(jdbc.query(TestRegisteredRecurrentCheckpointCurrentSqlV1.history, ResultSetExtractor { rows ->
                    TestActiveRecurrentHistoryV1.read(rows, intents, current.sampledAt, source.retention)
                }, identity.scope)).use { history ->
                    requireRecurrent(history.records.size == intents.size && history.records.all { it.checkpointSha256 != null && it.checkpointedAt != null })
                    current.requireCheckpoint(intents.last(), checkNotNull(history.commitment))
                    val first = history.records.first().entry
                    requireRecurrent(first.eventCount == 0L && first.manifestSha256 == initialManifest.first && first.manifestFramedBytes == initialManifest.second)
                    val last = history.records.last()
                    requireRecurrent(last.checkpointSha256 == current.checkpointSha256 && last.checkpointedAt == current.checkpointedAt)
                    val bytes = current.checkpointBytes()
                    val archived = checkNotNull(last.checkpointBytes())
                    try { requireRecurrent(bytes.contentEquals(archived)) } finally { bytes.fill(0); archived.fill(0) }
                    current.proof(intents.last(), source.retention).use { it.requireSame(last.verification) }

                    // No locking SELECT occurs here or above. Fresh DB time follows every bounded
                    // read/parse and every earlier ordinary lock wait, including the completion check.
                    val now = checkNotNull(jdbc.queryForObject("SELECT clock_timestamp()", { row, _ -> row.getTimestamp(1).toInstant() }))
                    val completed = checkNotNull(current.checkpointedAt)
                    val retentionFloor = now.plusMillis(source.retention.retention.utcUncertainty.maximumMillis)
                    requireRecurrent(TestActiveRecurrentJsonV1.time(now) && now >= current.sampledAt && completed <= now &&
                        completed.plusMillis(journal.declaration().limits.deadlines.checkpointMaxAgeMillis.toLong()) >= now &&
                        history.records.all { it.verification.retainUntil > retentionFloor })
                    retained()
                    now
                }
            } finally { intents.forEach { it.close() } }
        }
    }

    private fun readControl(): TestActiveRecurrentCurrentV1 {
        val args = identity.arguments()
        return try {
            checkNotNull(jdbc.query(TestRegisteredRecurrentCheckpointCurrentSqlV1.current, ResultSetExtractor { rows ->
                requireRecurrent(rows.next() && rows.getBoolean("ordinary_valid") && !rows.wasNull())
                val current = TestActiveRecurrentCurrentV1.copy(rows)
                try {
                    requireRecurrent(!rows.next() && current.sequence in 2..14 && current.lease == null && current.checkpointSha256 != null)
                    current
                } catch (problem: Throwable) { current.close(); throw problem }
            }, *args, identity.scope))
        } finally { args.forEach { if (it is ByteArray) it.fill(0) } }
    }

    private fun readIntents(current: TestActiveRecurrentCurrentV1): List<TestActiveRecurrentIntentV1> {
        val intents = ArrayList<TestActiveRecurrentIntentV1>()
        try {
            fun headers(sql: String, source: TestActiveCheckpointHistoryV1.Source) {
                jdbc.query(sql, ResultSetExtractor { rows ->
                    while (rows.next()) {
                        requireRecurrent(intents.size < TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS)
                        intents.add(TestActiveRecurrentIntentV1(rows, source, identity))
                    }
                }, identity.scope)
            }
            headers(TestRegisteredRecurrentCheckpointCurrentSqlV1.initialHeaders, TestActiveCheckpointHistoryV1.Source.V26_INITIAL)
            requireRecurrent(intents.size == 1)
            headers(TestRegisteredRecurrentCheckpointCurrentSqlV1.recurrentHeaders, TestActiveCheckpointHistoryV1.Source.V31_RECURRENT)
            intents.forEachIndexed { index, intent ->
                requireRecurrent(intent.ordinal == index + 1 && intent.state == "WIRE_FROZEN")
                jdbc.query(if (index == 0) TestRegisteredRecurrentCheckpointCurrentSqlV1.initialPayload else TestRegisteredRecurrentCheckpointCurrentSqlV1.recurrentPayload,
                    ResultSetExtractor { rows -> requireRecurrent(rows.next()); intent.bindPayload(rows); requireRecurrent(!rows.next()) }, intent.token, identity.scope)
            }
            current.requireIntents(intents)
            return intents.toList()
        } catch (problem: Throwable) { intents.forEach { it.close() }; throw problem }
    }

    override fun toString(): String = "TestRegisteredRecurrentCheckpointCurrentV1(fixed-current-comparisons,no-authority)"
}
