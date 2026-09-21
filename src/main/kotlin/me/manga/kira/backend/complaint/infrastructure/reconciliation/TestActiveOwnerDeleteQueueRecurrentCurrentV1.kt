package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveCheckpointHistoryV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import me.manga.kira.backend.security.EpochSealFramesV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.security.MessageDigest
import java.time.Instant

/**
 * B's fixed recurrent comparison, under its existing typed holder. No producer, creation gate,
 * native acquisition, new lease or eligibility result is created. A held B lease is legal and
 * intact but old checkpoint history does not block exact privacy recovery on an age predicate.
 */
internal object TestActiveOwnerDeleteQueueRecurrentCurrentV1 {
    /** Sampled-at and minimum-retained-until comparisons only; the caller must freshly reread B current. */
    fun read(jdbc: JdbcTemplate, original: TestActiveOwnerDeleteQueueV1,
        before: TestActiveOwnerDeleteQueueRowsV1.Current): Pair<Instant, Instant> {
        requireQueue(before.sequence in 2..TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS)
        original.requireRecipe(original.recipe)
        val process = original.process
        val source = checkNotNull(process.activeRecurrent)
        val acquisition = checkNotNull(process.ordinarySeal)
        source.requireRetained(original.routing, process.pools, acquisition)
        return readControl(jdbc, original, before).use { current ->
            val intents = readIntents(jdbc, original, current)
            try {
                checkNotNull(jdbc.query(TestActiveOwnerDeleteQueueSqlV1.recurrentHistory, ResultSetExtractor { rows ->
                    TestActiveRecurrentHistoryV1.read(rows, intents, current.sampledAt, acquisition)
                }, original.scope)).use { history ->
                    requireQueue(history.records.size == intents.size && history.records.all { it.checkpointSha256 != null && it.checkpointedAt != null })
                    current.requireCheckpoint(intents.last(), checkNotNull(history.commitment))
                    val first = history.records.first().entry
                    val digest = MessageDigest.getInstance("SHA-256")
                    val framed = EpochSealFramesV1.update(digest, listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", original.identity.writer.toString(),
                        original.routing.journalConfiguration.ordinaryPrefix, "TEST", original.scope.toString(), "1", "1", "0"))
                    requireQueue(framed in 1..original.routing.journalConfiguration.declaration().limits.capacity.maximumScanStagingBytes &&
                        first.eventCount == 0L && first.manifestSha256 == recurrentHex(digest.digest()) && first.manifestFramedBytes == framed)
                    val last = history.records.last()
                    requireQueue(last.checkpointSha256 == current.checkpointSha256 && last.checkpointedAt == current.checkpointedAt)
                    val currentBytes = current.checkpointBytes()
                    val archivedBytes = checkNotNull(last.checkpointBytes())
                    try { requireQueue(currentBytes.contentEquals(archivedBytes)) } finally { currentBytes.fill(0); archivedBytes.fill(0) }
                    current.proof(intents.last(), acquisition).use { it.requireSame(last.verification) }
                    source.requireRetained(original.routing, process.pools, acquisition)
                    original.requireRecipe(original.recipe)
                    current.sampledAt to history.records.minOf { it.verification.retainUntil }
                }
            } finally { intents.forEach { it.close() } }
        }
    }

    private fun readControl(jdbc: JdbcTemplate, original: TestActiveOwnerDeleteQueueV1,
        before: TestActiveOwnerDeleteQueueRowsV1.Current): TestActiveRecurrentCurrentV1 {
        val args = original.identity.arguments()
        return try {
            checkNotNull(jdbc.query(TestActiveOwnerDeleteQueueSqlV1.recurrentCurrent, ResultSetExtractor { rows ->
                requireQueue(rows.next())
                before.requireRecurrentControl(rows)
                val current = TestActiveRecurrentCurrentV1.copy(rows)
                try {
                    requireQueue(!rows.next() && current.sequence in 2..TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS &&
                        current.state == "CAPTURED" && !current.scanRequested && current.sealState == "SEAL_VERIFIED" && current.checkpointSha256 != null)
                    current
                } catch (problem: Throwable) { current.close(); throw problem }
            }, *args))
        } finally { args.filterIsInstance<ByteArray>().forEach { it.fill(0) } }
    }

    private fun readIntents(jdbc: JdbcTemplate, original: TestActiveOwnerDeleteQueueV1,
        current: TestActiveRecurrentCurrentV1): List<TestActiveRecurrentIntentV1> {
        val intents = ArrayList<TestActiveRecurrentIntentV1>()
        try {
            fun headers(sql: String, source: TestActiveCheckpointHistoryV1.Source) {
                jdbc.query(sql, ResultSetExtractor { rows ->
                    while (rows.next()) {
                        requireQueue(intents.size < TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS)
                        intents.add(TestActiveRecurrentIntentV1(rows, source, original.identity))
                    }
                }, original.scope)
            }
            headers(TestActiveOwnerDeleteQueueSqlV1.recurrentInitialHeaders, TestActiveCheckpointHistoryV1.Source.V26_INITIAL)
            requireQueue(intents.size == 1)
            headers(TestActiveOwnerDeleteQueueSqlV1.recurrentHeaders, TestActiveCheckpointHistoryV1.Source.V31_RECURRENT)
            intents.forEachIndexed { index, intent ->
                requireQueue(intent.ordinal == index + 1 && intent.state == "WIRE_FROZEN")
                jdbc.query(if (index == 0) TestActiveOwnerDeleteQueueSqlV1.recurrentInitialPayload else TestActiveOwnerDeleteQueueSqlV1.recurrentPayload,
                    ResultSetExtractor { rows -> requireQueue(rows.next()); intent.bindPayload(rows); requireQueue(!rows.next()) }, intent.token, original.scope)
            }
            current.requireIntents(intents)
            return intents.toList()
        } catch (problem: Throwable) { intents.forEach { it.close() }; throw problem }
    }
}
