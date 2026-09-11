package me.manga.kira.backend.completion.application

import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.observability.KiraMetrics
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/** Bounded scheduled deletion of expired prompts and results, including crash-left nonterminal rows. */
@Component
class CompletionRetentionService(
    private val jdbc: JdbcTemplate,
    private val properties: KiraCompletionProperties,
    private val clock: Clock,
    private val metrics: KiraMetrics,
) {
    @Scheduled(fixedDelayString = "\${kira.completion.cleanup-interval:PT1H}")
    @Transactional
    fun cleanExpired(): Int {
        val now = clock.instant()
        val cutoff = now.minus(properties.retention)
        // Every writer owns the request before touching its outcome. Select and lock one stable batch;
        // a concurrent updater's state is rechecked by PostgreSQL when its row lock becomes available.
        val expired = jdbc.query(
            """
            SELECT id, status FROM completion_requests
            WHERE created_at < ? ORDER BY created_at, id LIMIT ? FOR UPDATE
            """.trimIndent(),
            { row, _ -> row.getObject("id", UUID::class.java) to row.getString("status") },
            Timestamp.from(cutoff),
            properties.cleanupBatchSize,
        )
        if (expired.isEmpty()) {
            metrics.retentionDeleted(0)
            return 0
        }
        val inFlight = expired.filter { it.second == "PENDING" || it.second == "RUNNING" }.map { it.first }
        if (inFlight.isNotEmpty()) {
            val slots = inFlight.joinToString(",") { "?" }
            val changed = jdbc.update(
                "UPDATE completion_requests SET status = 'FAILED', updated_at = ? " +
                    "WHERE id IN ($slots) AND status IN ('PENDING', 'RUNNING')",
                *(listOf<Any>(Timestamp.from(now)) + inFlight).toTypedArray(),
            )
            check(changed == inFlight.size) { "completion expiry lost request ownership" }
            val inserted = jdbc.update(
                "INSERT INTO completion_results(request_id, result, error, error_code, latency_ms, created_at) " +
                    "SELECT id, NULL, ?, 'REQUEST_EXPIRED', NULL, ? FROM completion_requests " +
                    "WHERE id IN ($slots) AND status = 'FAILED'",
                *(listOf<Any>(EXPIRED_MESSAGE, Timestamp.from(now)) + inFlight).toTypedArray(),
            )
            check(inserted == inFlight.size) { "completion expiry did not publish its outcomes" }
        }
        val ids = expired.map { it.first }.toTypedArray()
        val slots = ids.joinToString(",") { "?" }
        jdbc.update("DELETE FROM completion_results WHERE request_id IN ($slots)", *ids)
        val deleted = jdbc.update(
            "DELETE FROM completion_requests WHERE id IN ($slots) AND status IN ('SUCCEEDED', 'FAILED')",
            *ids,
        )
        check(deleted == ids.size) { "completion expiry did not delete its owned requests" }
        if (deleted > 0) log.info("Completion retention deleted {} expired requests", deleted)
        metrics.retentionDeleted(deleted)
        return deleted
    }

    private companion object {
        val log = LoggerFactory.getLogger(CompletionRetentionService::class.java)
        const val EXPIRED_MESSAGE = "The completion request expired under the retention policy."
    }
}
