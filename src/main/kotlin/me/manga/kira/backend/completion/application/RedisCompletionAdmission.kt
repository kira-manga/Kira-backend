package me.manga.kira.backend.completion.application

import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.observability.KiraMetrics
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Redis-Lua completion admission: atomic distributed rate, quota, and concurrency enforcement. */
@Component
@ConditionalOnProperty(prefix = "kira.completion", name = ["coordination-backend"], havingValue = "redis")
class RedisCompletionAdmission(
    private val redis: StringRedisTemplate,
    private val properties: KiraCompletionProperties,
    private val metrics: KiraMetrics? = null,
) : CompletionAdmission {
    override fun acquire(userId: UUID): CompletionPermit {
        val result = safely {
            redis.execute(
                ACQUIRE_SCRIPT,
                listOf(
                    "$KEY_PREFIX:user-minute:$userId",
                    "$KEY_PREFIX:global-minute",
                    "$KEY_PREFIX:user-day:$userId",
                    "$KEY_PREFIX:concurrency",
                ),
                properties.perUserPerMinute.toString(),
                properties.globalPerMinute.toString(),
                properties.perUserDailyQuota.toString(),
                properties.globalConcurrency.toString(),
                concurrencyTtlMs().toString(),
            ) ?: 0L
        }
        if (result != 0L) reject(result)
        val released = AtomicBoolean(false)
        return CompletionPermit {
            if (released.compareAndSet(false, true)) {
                safely { redis.execute(RELEASE_SCRIPT, listOf("$KEY_PREFIX:concurrency")) }
            }
        }
    }

    private fun concurrencyTtlMs(): Long = (properties.queueTimeout + properties.timeout).multipliedBy(2).toMillis()

    private fun reject(code: Long): Nothing {
        val (machineCode, retry) = when (code) {
            USER_RATE -> "COMPLETION_USER_RATE_LIMIT" to MINUTE_SECONDS
            GLOBAL_RATE -> "COMPLETION_GLOBAL_RATE_LIMIT" to MINUTE_SECONDS
            DAILY_QUOTA -> "COMPLETION_DAILY_QUOTA" to DAY_SECONDS
            else -> "COMPLETION_CONCURRENCY_LIMIT" to 1L
        }
        metrics?.completionAdmission(machineCode.lowercase())
        throw TooManyRequestsException("Completion limit exceeded. Try again later.", machineCode, retry)
    }

    private fun <T> safely(block: () -> T): T = try {
        block()
    } catch (ignored: DataAccessException) {
        log.error("Shared completion admission unavailable; denying request")
        metrics?.completionAdmission("coordination_unavailable")
        throw TooManyRequestsException(
            "Completion service is temporarily unavailable. Try again later.",
            "COMPLETION_COORDINATION_UNAVAILABLE",
            FAILURE_RETRY_SECONDS,
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(RedisCompletionAdmission::class.java)
        const val KEY_PREFIX = "kira:completion-admission"
        const val USER_RATE = 1L
        const val GLOBAL_RATE = 2L
        const val DAILY_QUOTA = 3L
        const val CONCURRENCY = 4L
        const val MINUTE_SECONDS = 60L
        const val DAY_SECONDS = 86_400L
        const val FAILURE_RETRY_SECONDS = 5L

        val ACQUIRE_SCRIPT = DefaultRedisScript(
            """
            local limits = {tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3])}
            local ttl = {60000, 60000, 86400000}
            for i = 1, 3 do
              if limits[i] > 0 then
                local count = redis.call('INCR', KEYS[i])
                if count == 1 then redis.call('PEXPIRE', KEYS[i], ttl[i]) end
                if count > limits[i] then redis.call('DECR', KEYS[i]); return i end
              end
            end
            local active = redis.call('INCR', KEYS[4])
            if active == 1 then redis.call('PEXPIRE', KEYS[4], tonumber(ARGV[5])) end
            if active > tonumber(ARGV[4]) then redis.call('DECR', KEYS[4]); return 4 end
            return 0
            """.trimIndent(),
            Long::class.java,
        )

        val RELEASE_SCRIPT = DefaultRedisScript(
            """
            local active = tonumber(redis.call('GET', KEYS[1]) or '0')
            if active <= 1 then redis.call('DEL', KEYS[1]); return 0 end
            return redis.call('DECR', KEYS[1])
            """.trimIndent(),
            Long::class.java,
        )
    }
}
