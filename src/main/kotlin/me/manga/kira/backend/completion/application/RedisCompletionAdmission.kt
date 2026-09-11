package me.manga.kira.backend.completion.application

import me.manga.kira.backend.common.exception.ServiceUnavailableException
import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.observability.KiraMetrics
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.serializer.SerializationException
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared rate/quota admission and token-owned, unexpired/unreleased logical leases, not a physical
 * provider concurrency guarantee. Requires the stopped/drained Redis protocol cutover in SECURITY.md.
 */
@Component
@ConditionalOnProperty(prefix = "kira.completion", name = ["coordination-backend"], havingValue = "redis")
class RedisCompletionAdmission(
    private val redis: StringRedisTemplate,
    private val properties: KiraCompletionProperties,
    private val metrics: KiraMetrics? = null,
) : CompletionAdmission {
    init {
        require(properties.globalConcurrency in 1..MAX_LEASES) {
            "Redis kira.completion.global-concurrency must be between 1 and $MAX_LEASES"
        }
    }

    private val leaseMillis = concurrencyTtlMs()

    override fun acquire(userId: UUID): CompletionPermit {
        val token = UUID.randomUUID().toString()
        val result = execute(
            listOf(
                "$KEY_PREFIX:user-minute:$userId",
                "$KEY_PREFIX:global-minute",
                "$KEY_PREFIX:user-day:$userId",
                "$KEY_PREFIX:concurrency",
            ),
            "acquire", token,
            properties.perUserPerMinute.toString(),
            properties.globalPerMinute.toString(),
            properties.perUserDailyQuota.toString(),
            properties.globalConcurrency.toString(),
            leaseMillis.toString(),
        )
        if (result != 0L) reject(result)
        val released = AtomicBoolean(false)
        return CompletionPermit {
            if (released.compareAndSet(false, true)) {
                val reply = execute(
                    listOf("$KEY_PREFIX:concurrency"), "release", token, properties.globalConcurrency.toString(), releasing = true,
                )
                if (reply != 0L && reply != 1L) coordinationUnavailable(releasing = true)
            }
        }
    }

    private fun concurrencyTtlMs(): Long {
        require(listOf(properties.queueTimeout, properties.timeout).all { it.nano % 1_000_000 == 0 }) {
            "Redis completion queue/provider timeouts must be positive whole milliseconds"
        }
        val lease = try {
            Math.multiplyExact(Math.addExact(properties.queueTimeout.toMillis(), properties.timeout.toMillis()), 2L)
        } catch (ignored: ArithmeticException) {
            throw IllegalArgumentException("Redis completion lease exceeds exact millisecond arithmetic")
        }
        require(lease in 1..MAX_SCRIPT_INTEGER) { "Redis completion lease exceeds exact millisecond arithmetic" }
        return lease
    }

    private fun execute(keys: List<String>, vararg args: String, releasing: Boolean = false): Long = try {
        // Do not cast an unexpected serializer/transport reply into an admission acknowledgement.
        val reply: Any? = redis.execute(SCRIPT, keys, *args)
        if (reply !is Long) coordinationUnavailable(releasing)
        reply
    } catch (ignored: DataAccessException) {
        coordinationUnavailable(releasing)
    } catch (ignored: SerializationException) {
        coordinationUnavailable(releasing)
    } catch (ignored: ClassCastException) {
        coordinationUnavailable(releasing)
    }

    private fun reject(code: Long?): Nothing {
        val (machineCode, retry) = when (code) {
            USER_RATE -> "COMPLETION_USER_RATE_LIMIT" to MINUTE_SECONDS
            GLOBAL_RATE -> "COMPLETION_GLOBAL_RATE_LIMIT" to MINUTE_SECONDS
            DAILY_QUOTA -> "COMPLETION_DAILY_QUOTA" to DAY_SECONDS
            CONCURRENCY -> "COMPLETION_CONCURRENCY_LIMIT" to 1L
            else -> coordinationUnavailable()
        }
        metrics?.completionAdmission(machineCode.lowercase())
        if (code == CONCURRENCY) {
            throw ServiceUnavailableException("Completion limit exceeded. Try again later.", machineCode, retry)
        }
        throw TooManyRequestsException("Completion limit exceeded. Try again later.", machineCode, retry)
    }

    private fun coordinationUnavailable(releasing: Boolean = false): Nothing {
        log.error("Shared completion admission unavailable; denying request")
        metrics?.completionAdmission("coordination_unavailable")
        // Release failure remains visible with its existing response; Backend #15 is separate.
        if (releasing) {
            throw TooManyRequestsException(
                "Completion service is temporarily unavailable. Try again later.",
                "COMPLETION_COORDINATION_UNAVAILABLE",
                FAILURE_RETRY_SECONDS,
            )
        }
        throw ServiceUnavailableException(
            "Completion service is temporarily unavailable. Try again later.",
            "COMPLETION_COORDINATION_UNAVAILABLE",
            FAILURE_RETRY_SECONDS,
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(RedisCompletionAdmission::class.java)
        const val KEY_PREFIX = "kira:completion-admission"
        const val MAX_LEASES = 4_096
        const val MAX_SCRIPT_INTEGER = 9_007_199_254_740_991L
        const val USER_RATE = 1L
        const val GLOBAL_RATE = 2L
        const val DAILY_QUOTA = 3L
        const val CONCURRENCY = 4L
        const val MINUTE_SECONDS = 60L
        const val DAY_SECONDS = 86_400L
        const val FAILURE_RETRY_SECONDS = 5L

        val SCRIPT = DefaultRedisScript<Long>().apply {
            setLocation(ClassPathResource("redis/completion-admission.lua"))
            resultType = Long::class.java
        }
    }
}
