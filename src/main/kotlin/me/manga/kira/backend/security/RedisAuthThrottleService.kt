package me.manga.kira.backend.security

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.observability.KiraMetrics
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.serializer.SerializationException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Fenced two-dimension admission using Redis TIME. All script keys are explicitly declared; an
 * outside-Lua shortlist is only a hint, atomically revalidated before safe eviction. No local fallback.
 * Requires no independent Redis key eviction and a coordinated v2 auth-state cutover (SECURITY.md).
 */
@Service
@ConditionalOnProperty(prefix = "kira.security.throttle", name = ["backend"], havingValue = "redis")
class RedisAuthThrottleService(
    private val redis: StringRedisTemplate,
    private val properties: KiraSecurityProperties,
    private val metrics: KiraMetrics? = null,
) : AuthThrottle {
    private val config get() = properties.throttle

    override fun beginLoginAttempt(normalizedEmail: String, clientIp: String): AuthLoginAttempt {
        val targets = listOf(loginIdentityKey(normalizedEmail, clientIp), key("login:ip", clientIp))
        val token = UUID.randomUUID().toString()
        admission("begin", targets, token)
        return AuthLoginAttempt { success ->
            val reply = execute(if (success) "success" else "failure", targets, token)
            when (reply) {
                0L -> if (success) invalidLoginAttempt() // Missing/expired failure is a harmless no-op.
                1L -> Unit
                else -> unavailable()
            }
        }
    }

    override fun checkRegistrationAllowed(clientIp: String) {
        admission("register", listOf(key("registration:ip", clientIp)), "")
    }

    private fun admission(mode: String, targets: List<String>, token: String) {
        val reply = execute(mode, targets, token, candidates(targets))
        if (reply < 0 || reply >= MAX_SCRIPT_INTEGER) unavailable()
        if (reply > 0) {
            metrics?.authenticationThrottle(if (mode == "register") "registration" else "login", "blocked")
            throw TooManyRequestsException(
                "Too many attempts. Try again later.",
                retryAfterSeconds = (reply / 1_000 + if (reply % 1_000 > 0) 1 else 0).coerceAtLeast(1),
            )
        }
    }

    /** At most 64 oldest records, score then lexical key order. No retry or database-wide scan. */
    private fun candidates(targets: List<String>): List<Candidate> = safely {
        val entries = redis.opsForZSet().rangeWithScores(INDEX_KEY, 0, MAX_CANDIDATES - 1) ?: unavailable()
        if (entries.size > MAX_CANDIDATES) unavailable()
        entries.map { entry ->
            val key = entry.value ?: unavailable()
            val score = entry.score ?: unavailable()
            if (!BUCKET_KEY.matches(key) || !score.isFinite() || score < 0 || score != score.toLong().toDouble()) unavailable()
            Candidate(key, score.toLong())
        }.filter { it.key !in targets }.sortedWith(compareBy({ it.score }, { it.key }))
    }

    private fun execute(mode: String, targets: List<String>, token: String, candidates: List<Candidate> = emptyList()): Long = safely {
        val keys = listOf(INDEX_KEY) + (targets + candidates.map { it.key }).flatMap { listOf(it, "$it:attempts") }
        val args = listOf(
            mode, token,
            config.loginFailureThreshold.toString(), config.loginIpFailureThreshold.toString(),
            config.loginInitialBlock.toMillis().toString(), config.loginMaxBlock.toMillis().toString(),
            config.loginFailureWindow.toMillis().toString(), config.loginAttemptTtl.toMillis().toString(),
            config.registrationMaxPerWindow.toString(), config.registrationWindow.toMillis().toString(), config.maxEntries.toString(),
        ) + candidates.map { it.score.toString() }
        // Read as Any so unexpected serializer/mock result types are denied, not cast or defaulted to success.
        val reply: Any? = redis.execute(SCRIPT, keys, *args.toTypedArray())
        if (reply !is Long) unavailable()
        reply
    }

    private fun <T> safely(block: () -> T): T = try {
        block()
    } catch (ignored: DataAccessException) {
        unavailable()
    } catch (ignored: SerializationException) {
        unavailable()
    } catch (ignored: ClassCastException) {
        unavailable()
    }

    private fun unavailable(): Nothing {
        log.error("Shared authentication throttle unavailable; denying request")
        metrics?.authenticationThrottle("shared", "unavailable")
        throw TooManyRequestsException(
            detail = "Authentication is temporarily unavailable. Try again later.",
            code = "AUTH_THROTTLE_UNAVAILABLE",
            retryAfterSeconds = FAILURE_RETRY_SECONDS,
        )
    }

    private fun loginIdentityKey(email: String, clientIp: String): String = key("login:identity", email.take(EMAIL_KEY_CAP) + "|" + clientIp)

    private fun key(dimension: String, value: String): String = "$KEY_PREFIX:$dimension:${Sha256.hexUtf8(value)}"

    private data class Candidate(val key: String, val score: Long)

    private companion object {
        val log = LoggerFactory.getLogger(RedisAuthThrottleService::class.java)
        const val KEY_PREFIX = "kira:auth-throttle:v2"
        const val INDEX_KEY = "$KEY_PREFIX:index"
        const val EMAIL_KEY_CAP = 320
        const val MAX_CANDIDATES = 64L
        const val MAX_SCRIPT_INTEGER = 9_007_199_254_740_991L
        const val FAILURE_RETRY_SECONDS = 5L
        val BUCKET_KEY = Regex("$KEY_PREFIX:(login:identity|login:ip|registration:ip):[a-f0-9]{64}")
        val SCRIPT = DefaultRedisScript<Long>().apply {
            setLocation(ClassPathResource("redis/auth-throttle.lua"))
            resultType = Long::class.java
        }
    }
}
