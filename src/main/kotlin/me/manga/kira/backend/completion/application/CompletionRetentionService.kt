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
        val cutoff = clock.instant().minus(properties.retention)
        val batch = properties.cleanupBatchSize
        jdbc.update(
            """
            INSERT INTO completion_results(request_id, result, error, error_code, latency_ms, created_at)
            SELECT id, NULL, ?, 'REQUEST_EXPIRED', NULL, ?
            FROM completion_requests
            WHERE created_at < ? AND status IN ('PENDING', 'RUNNING')
            ORDER BY created_at, id
            LIMIT ?
            ON CONFLICT (request_id) DO NOTHING
            """.trimIndent(),
            EXPIRED_MESSAGE,
            Timestamp.from(clock.instant()),
            Timestamp.from(cutoff),
            batch,
        )
        jdbc.update(
            """
            UPDATE completion_requests
            SET status = 'FAILED', updated_at = ?
            WHERE id IN (
              SELECT id FROM completion_requests
              WHERE created_at < ? AND status IN ('PENDING', 'RUNNING')
              ORDER BY created_at, id LIMIT ?
            )
            """.trimIndent(),
            Timestamp.from(clock.instant()),
            Timestamp.from(cutoff),
            batch,
        )
        jdbc.update(
            """
            DELETE FROM completion_results
            WHERE request_id IN (
              SELECT id FROM completion_requests
              WHERE created_at < ? AND status IN ('SUCCEEDED', 'FAILED')
              ORDER BY created_at, id LIMIT ?
            )
            """.trimIndent(),
            Timestamp.from(cutoff),
            batch,
        )
        val deleted = jdbc.update(
            """
            DELETE FROM completion_requests
            WHERE id IN (
              SELECT id FROM completion_requests
              WHERE created_at < ? AND status IN ('SUCCEEDED', 'FAILED')
                AND NOT EXISTS (SELECT 1 FROM completion_results r WHERE r.request_id = completion_requests.id)
              ORDER BY created_at, id LIMIT ?
            )
            """.trimIndent(),
            Timestamp.from(cutoff),
            batch,
        )
        if (deleted > 0) log.info("Completion retention deleted {} expired requests", deleted)
        metrics.retentionDeleted(deleted)
        return deleted
    }

    private companion object {
        val log = LoggerFactory.getLogger(CompletionRetentionService::class.java)
        const val EXPIRED_MESSAGE = "The completion request expired under the retention policy."
    }
}
