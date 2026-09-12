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
 * Shared rate/quota admission with expiring pending reservations and acknowledged, nonexpiring pins.
 * Physical ownership additionally requires the service owner and audited provider lifetime contract.
 * Requires the stopped/drained Redis protocol cutover and retained authority in SECURITY.md.
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

    private val pendingMillis = pendingTtlMs()

    override fun acquire(userId: UUID): CompletionPermit {
        val token = UUID.randomUUID().toString()
        val result = execute(
            listOf(
                "$KEY_PREFIX:user-minute:$userId",
                "$KEY_PREFIX:global-minute",
                "$KEY_PREFIX:user-day:$userId",
                "$KEY_PREFIX:concurrency",
            ),
            "acquire",
            token,
            properties.perUserPerMinute.toString(),
            properties.globalPerMinute.toString(),
            properties.perUserDailyQuota.toString(),
            properties.globalConcurrency.toString(),
            pendingMillis.toString(),
        )
        if (result != 0L) reject(result)
        return object : CompletionPermit {
            private val activationAttempted = AtomicBoolean(false)

            // Guard the application attempt only; the Redis client may replay an in-flight command.
            private val releaseAttempted = AtomicBoolean(false)

            override fun activate(): CompletionActivation {
                if (releaseAttempted.get()) return CompletionActivation.EXPIRED
                if (!activationAttempted.compareAndSet(false, true)) return activationUnavailable()
                return this@RedisCompletionAdmission.activate(token)
            }

            override fun close() {
                if (releaseAttempted.compareAndSet(false, true)) release(token)
            }
        }
    }

    private fun activate(token: String): CompletionActivation {
        val result = execute(listOf("$KEY_PREFIX:concurrency"), "activate", token, properties.globalConcurrency.toString())
        return when (result) {
            1L -> CompletionActivation.ACTIVATED

            0L -> {
                metrics?.completionAdmission("completion_concurrency_limit")
                CompletionActivation.EXPIRED
            }

            else -> activationUnavailable()
        }
    }

    private fun activationUnavailable(): CompletionActivation {
        recordCoordinationUnavailable()
        return CompletionActivation.UNAVAILABLE
    }

    private fun pendingTtlMs(): Long {
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

    private fun execute(keys: List<String>, vararg args: String): Long? = try {
        // Do not cast an unexpected serializer/transport reply into an admission acknowledgement.
        val reply: Any? = redis.execute(SCRIPT, keys, *args)
        reply as? Long
    } catch (ignored: DataAccessException) {
        null
    } catch (ignored: SerializationException) {
        null
    } catch (ignored: ClassCastException) {
        null
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

    private fun coordinationUnavailable(): Nothing {
        recordCoordinationUnavailable()
        throw ServiceUnavailableException(
            "Completion service is temporarily unavailable. Try again later.",
            "COMPLETION_COORDINATION_UNAVAILABLE",
            FAILURE_RETRY_SECONDS,
        )
    }

    private fun recordCoordinationUnavailable() {
        log.error("Shared completion admission unavailable; denying request")
        metrics?.completionAdmission("coordination_unavailable")
    }

    private fun release(token: String) {
        val result = execute(listOf("$KEY_PREFIX:concurrency"), "release", token, properties.globalConcurrency.toString())
        if (result != 0L && result != 1L) {
            log.warn("Shared completion admission release unconfirmed")
            metrics?.completionAdmission("release_unconfirmed")
        }
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
