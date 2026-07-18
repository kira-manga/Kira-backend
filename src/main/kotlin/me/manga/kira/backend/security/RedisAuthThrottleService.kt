package me.manga.kira.backend.security

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.observability.KiraMetrics
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service

/**
 * Atomic, TTL-bounded Redis authentication throttle for horizontally scaled production deployments.
 * Redis server time is authoritative, keys contain only hashes/counters, and backend failure denies
 * authentication attempts instead of silently removing throttling.
 */
@Service
@ConditionalOnProperty(prefix = "kira.security.throttle", name = ["backend"], havingValue = "redis")
class RedisAuthThrottleService(
    private val redis: StringRedisTemplate,
    private val properties: KiraSecurityProperties,
    private val metrics: KiraMetrics? = null,
) : AuthThrottle {
    private val config get() = properties.throttle

    override fun checkLoginAllowed(normalizedEmail: String, clientIp: String) {
        val retryMs = execute(
            CHECK_SCRIPT,
            listOf(loginIdentityKey(normalizedEmail, clientIp), loginIpKey(clientIp)),
        )
        if (retryMs > 0) {
            metrics?.authenticationThrottle("login", "blocked")
            throttled(retryMs)
        }
    }

    override fun recordLoginFailure(normalizedEmail: String, clientIp: String) {
        recordFailure(loginIdentityKey(normalizedEmail, clientIp), config.loginFailureThreshold)
        recordFailure(loginIpKey(clientIp), config.loginIpFailureThreshold)
    }

    override fun recordLoginSuccess(normalizedEmail: String, clientIp: String) {
        safely { redis.delete(loginIdentityKey(normalizedEmail, clientIp)) }
    }

    override fun checkRegistrationAllowed(clientIp: String) {
        val retryMs = execute(
            REGISTRATION_SCRIPT,
            listOf(registrationKey(clientIp), INDEX_KEY),
            config.registrationMaxPerWindow.toString(),
            config.registrationWindow.toMillis().toString(),
            config.maxEntries.toString(),
        )
        if (retryMs > 0) {
            metrics?.authenticationThrottle("registration", "blocked")
            throttled(retryMs)
        }
    }

    private fun recordFailure(key: String, threshold: Int) {
        execute(
            FAILURE_SCRIPT,
            listOf(key, INDEX_KEY),
            threshold.toString(),
            config.loginFailureWindow.toMillis().toString(),
            config.loginInitialBlock.toMillis().toString(),
            config.loginMaxBlock.toMillis().toString(),
            config.maxEntries.toString(),
        )
    }

    private fun execute(script: DefaultRedisScript<Long>, keys: List<String>, vararg args: String): Long = safely {
        redis.execute(script, keys, *args) ?: 0L
    }

    private fun <T> safely(block: () -> T): T = try {
        block()
    } catch (ignored: DataAccessException) {
        log.error("Shared authentication throttle unavailable; denying request")
        metrics?.authenticationThrottle("shared", "unavailable")
        throw TooManyRequestsException(
            detail = "Authentication is temporarily unavailable. Try again later.",
            code = "AUTH_THROTTLE_UNAVAILABLE",
            retryAfterSeconds = FAILURE_RETRY_SECONDS,
        )
    }

    private fun throttled(retryMs: Long): Nothing = throw TooManyRequestsException(
        detail = "Too many attempts. Try again later.",
        retryAfterSeconds = ((retryMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceAtLeast(1),
    )

    private fun loginIdentityKey(email: String, clientIp: String): String = key("login:identity", email.take(EMAIL_KEY_CAP) + "|" + clientIp)

    private fun loginIpKey(clientIp: String): String = key("login:ip", clientIp)

    private fun registrationKey(clientIp: String): String = key("registration:ip", clientIp)

    private fun key(dimension: String, value: String): String = "$KEY_PREFIX:$dimension:${Sha256.hexUtf8(value)}"

    private companion object {
        val log = LoggerFactory.getLogger(RedisAuthThrottleService::class.java)
        const val KEY_PREFIX = "kira:auth-throttle"
        const val INDEX_KEY = "$KEY_PREFIX:index"
        const val EMAIL_KEY_CAP = 320
        const val MILLIS_PER_SECOND = 1_000L
        const val FAILURE_RETRY_SECONDS = 5L

        val CHECK_SCRIPT = DefaultRedisScript(
            """
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local retry = 0
            for _, key in ipairs(KEYS) do
              local blocked = tonumber(redis.call('HGET', key, 'blockedUntil') or '0')
              if blocked > now then retry = math.max(retry, blocked - now) end
            end
            return retry
            """.trimIndent(),
            Long::class.java,
        )

        val FAILURE_SCRIPT = DefaultRedisScript(
            """
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local threshold = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local initialBlock = tonumber(ARGV[3])
            local maxBlock = tonumber(ARGV[4])
            local maxEntries = tonumber(ARGV[5])
            local failures = tonumber(redis.call('HGET', KEYS[1], 'failures') or '0')
            local last = tonumber(redis.call('HGET', KEYS[1], 'lastUpdate') or '0')
            local nextBlock = tonumber(redis.call('HGET', KEYS[1], 'nextBlock') or tostring(initialBlock))
            if now - last > window then failures = 0; nextBlock = initialBlock end
            failures = failures + 1
            local blockedUntil = tonumber(redis.call('HGET', KEYS[1], 'blockedUntil') or '0')
            if failures >= threshold then
              blockedUntil = now + nextBlock
              failures = 0
              nextBlock = math.min(nextBlock * 2, maxBlock)
            end
            redis.call('HSET', KEYS[1], 'failures', failures, 'lastUpdate', now, 'nextBlock', nextBlock, 'blockedUntil', blockedUntil)
            redis.call('PEXPIRE', KEYS[1], math.max(window, maxBlock * 2))
            redis.call('ZADD', KEYS[2], now, KEYS[1])
            local excess = redis.call('ZCARD', KEYS[2]) - maxEntries
            if excess > 0 then
              local victims = redis.call('ZRANGE', KEYS[2], 0, excess - 1)
              for _, victim in ipairs(victims) do redis.call('DEL', victim) end
              redis.call('ZREM', KEYS[2], unpack(victims))
            end
            return math.max(0, blockedUntil - now)
            """.trimIndent(),
            Long::class.java,
        )

        val REGISTRATION_SCRIPT = DefaultRedisScript(
            """
            local maxCount = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local maxEntries = tonumber(ARGV[3])
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], window) end
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            redis.call('ZADD', KEYS[2], now, KEYS[1])
            local excess = redis.call('ZCARD', KEYS[2]) - maxEntries
            if excess > 0 then
              local victims = redis.call('ZRANGE', KEYS[2], 0, excess - 1)
              for _, victim in ipairs(victims) do redis.call('DEL', victim) end
              redis.call('ZREM', KEYS[2], unpack(victims))
            end
            if count > maxCount then return math.max(redis.call('PTTL', KEYS[1]), 1) end
            return 0
            """.trimIndent(),
            Long::class.java,
        )
    }
}
