package me.manga.kira.backend.completion.application

import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.observability.KiraMetrics
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Single-instance admission controller used by tests, development, and explicitly validated one-pod deployments. */
@Component
@ConditionalOnProperty(prefix = "kira.completion", name = ["coordination-backend"], havingValue = "memory", matchIfMissing = true)
class InMemoryCompletionAdmission(private val properties: KiraCompletionProperties, private val clock: Clock, private val metrics: KiraMetrics? = null) :
    CompletionAdmission {
    private val lock = Any()
    private val perUserMinute = HashMap<UUID, ArrayDeque<Instant>>()
    private val perUserDaily = HashMap<UUID, ArrayDeque<Instant>>()
    private val globalMinute = ArrayDeque<Instant>()
    private var active = 0

    override fun acquire(userId: UUID): CompletionPermit {
        synchronized(lock) {
            val now = clock.instant()
            val minute = perUserMinute.getOrPut(userId, ::ArrayDeque)
            val daily = perUserDaily.getOrPut(userId, ::ArrayDeque)
            prune(minute, now.minus(MINUTE))
            prune(globalMinute, now.minus(MINUTE))
            prune(daily, now.minus(DAY))
            checkLimit(minute, properties.perUserPerMinute, "COMPLETION_USER_RATE_LIMIT", MINUTE.seconds)
            checkLimit(globalMinute, properties.globalPerMinute, "COMPLETION_GLOBAL_RATE_LIMIT", MINUTE.seconds)
            checkLimit(daily, properties.perUserDailyQuota, "COMPLETION_DAILY_QUOTA", DAY.seconds)
            if (active >= properties.globalConcurrency) {
                metrics?.completionAdmission("concurrency_rejected")
                throw TooManyRequestsException(
                    "Completion capacity is currently exhausted. Try again later.",
                    code = "COMPLETION_CONCURRENCY_LIMIT",
                    retryAfterSeconds = 1,
                )
            }
            minute.addLast(now)
            globalMinute.addLast(now)
            daily.addLast(now)
            active += 1
        }
        val released = AtomicBoolean(false)
        return CompletionPermit {
            if (released.compareAndSet(false, true)) synchronized(lock) { active = (active - 1).coerceAtLeast(0) }
        }
    }

    private fun checkLimit(values: ArrayDeque<Instant>, limit: Int, code: String, retrySeconds: Long) {
        if (limit > 0 && values.size >= limit) {
            metrics?.completionAdmission(code.lowercase())
            throw TooManyRequestsException("Completion limit exceeded. Try again later.", code, retrySeconds)
        }
    }

    private fun prune(values: ArrayDeque<Instant>, cutoff: Instant) {
        while (values.firstOrNull()?.isBefore(cutoff) == true) values.removeFirst()
    }

    private companion object {
        val MINUTE: Duration = Duration.ofMinutes(1)
        val DAY: Duration = Duration.ofDays(1)
    }
}
