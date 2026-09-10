package me.manga.kira.backend.security

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.observability.KiraMetrics
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Single-JVM auth admission. One lock covers BOTH login dimensions and the global registration/login
 * capacity bound, never credential work. Completed failures + live attempts cannot exceed a dimension's
 * threshold. Safe eviction cannot discard a live attempt, login block or exhausted registration window.
 */
@Service
@ConditionalOnProperty(prefix = "kira.security.throttle", name = ["backend"], havingValue = "memory", matchIfMissing = true)
class AuthThrottleService(private val properties: KiraSecurityProperties, private val clock: Clock, private val metrics: KiraMetrics? = null) : AuthThrottle {
    private val throttle get() = properties.throttle
    private val lock = Any()
    private val buckets = HashMap<String, Bucket>()

    override fun beginLoginAttempt(normalizedEmail: String, clientIp: String): AuthLoginAttempt {
        val keys = listOf(loginIdentityKey(normalizedEmail, clientIp), loginIpKey(clientIp))
        val token = UUID.randomUUID().toString()
        synchronized(lock) {
            val now = clock.instant()
            val targets = keys.map { (buckets[it] as LoginBucket?) ?: LoginBucket(now, throttle.loginInitialBlock) }
            targets.forEach { it.expire(now) }
            val retry = targets.zip(thresholds()).maxOf { (bucket, threshold) -> bucket.retryMillis(now, threshold) }
            if (retry > 0) throttled(retry)
            if (targets.any { token in it.attempts }) invalidLoginAttempt()
            val deadline = now.plus(throttle.loginAttemptTtl)
            makeRoom(keys.toSet(), now)
            keys.zip(targets).forEach { (key, bucket) ->
                bucket.attempts[token] = deadline
                bucket.lastUpdate = now
                buckets[key] = bucket
            }
        }
        return AuthLoginAttempt { success -> complete(keys, token, success) }
    }

    private fun complete(keys: List<String>, token: String, success: Boolean) {
        val engaged = mutableListOf<String>()
        synchronized(lock) {
            // Sample inside the lock: a waiter must not use a timestamp from before lease expiry.
            val now = clock.instant()
            val targets = keys.map { buckets[it] as LoginBucket? }
            targets.forEach { it?.expire(now) }
            val deadlines = targets.map { it?.attempts?.get(token) }
            if (deadlines.any { it == null || !now.isBefore(it) } || deadlines[0] != deadlines[1]) {
                if (success) invalidLoginAttempt()
                return // Expired/unknown failure cannot recreate a bucket or touch a successor.
            }
            val live = targets.filterNotNull()
            // Compute both potentially throwing deadline transitions before consuming either token.
            val blocks = live.mapIndexed { index, bucket ->
                if (!success && bucket.failures + 1 >= thresholds()[index]) now.plus(bucket.nextBlock) else null
            }
            live.forEachIndexed { index, bucket ->
                bucket.attempts.remove(token)
                bucket.lastUpdate = now
                if (success) {
                    if (index == 0) bucket.resetHistory() // Never erase another attempt or aggregate IP history.
                } else if (bucket.fail(now, thresholds()[index], blocks[index])) {
                    engaged += if (index == 0) "identity" else "client-ip"
                }
            }
        }
        engaged.forEach { dimension ->
            log.warn("Auth throttle engaged for login dimension={}", dimension)
            metrics?.authenticationThrottle(dimension, "blocked")
        }
    }

    override fun checkRegistrationAllowed(clientIp: String) {
        synchronized(lock) {
            val now = clock.instant()
            val key = "registration|$clientIp"
            val bucket = (buckets[key] as RegistrationBucket?) ?: RegistrationBucket(now)
            bucket.expire(now)
            if (bucket.isProtected(now)) throttled(Duration.between(now, bucket.windowStart.plus(throttle.registrationWindow)).toMillis())
            makeRoom(setOf(key), now)
            if (bucket.dead(now)) bucket.windowStart = now
            bucket.count += 1
            bucket.lastUpdate = now
            buckets[key] = bucket
        }
    }

    /** Deliberate operational/test reset; all outstanding handles become invalid. Never an automatic fallback. */
    fun clearAll() {
        synchronized(lock) { buckets.clear() }
    }

    fun size(): Int = synchronized(lock) { buckets.size }

    /** Preflight the whole request: no victim is removed unless BOTH targets can fit. */
    private fun makeRoom(targets: Set<String>, now: Instant) {
        val needed = buckets.size + targets.count { it !in buckets } - throttle.maxEntries
        if (needed <= 0) return
        val candidates = buckets.entries.filter { (key, bucket) ->
            bucket.expire(now) // Safe expired-state pruning, not admission or a retention refresh.
            key !in targets && !bucket.isProtected(now)
        }.sortedWith(compareBy({ !it.value.dead(now) }, { it.value.lastUpdate }, { it.key }))
        if (candidates.size < needed) throttled(CAPACITY_RETRY_MILLIS)
        candidates.take(needed).forEach { buckets.remove(it.key) }
    }

    private fun thresholds(): List<Int> = listOf(throttle.loginFailureThreshold, throttle.loginIpFailureThreshold)

    private fun loginIdentityKey(email: String, clientIp: String): String = "identity|${Sha256.hexUtf8(email.take(EMAIL_KEY_CAP))}|$clientIp"

    private fun loginIpKey(clientIp: String): String = "client-ip|$clientIp"

    private fun throttled(retryMillis: Long): Nothing = throw TooManyRequestsException(
        "Too many attempts. Try again later.",
        retryAfterSeconds = (retryMillis / 1_000 + if (retryMillis % 1_000 > 0) 1 else 0).coerceAtLeast(1),
    )

    private interface Bucket {
        val lastUpdate: Instant

        fun expire(now: Instant)

        fun isProtected(now: Instant): Boolean

        fun dead(now: Instant): Boolean
    }

    private inner class LoginBucket(override var lastUpdate: Instant, var nextBlock: Duration) : Bucket {
        var failures = 0
        var lastFailure: Instant? = null
        var blockedUntil: Instant? = null
        val attempts = HashMap<String, Instant>()

        override fun expire(now: Instant) {
            attempts.entries.removeIf { !now.isBefore(it.value) }
            if (lastFailure?.plus(throttle.loginFailureWindow)?.let { !now.isBefore(it) } == true && !blocked(now)) {
                resetHistory()
            }
        }

        override fun isProtected(now: Instant): Boolean = attempts.isNotEmpty() || blocked(now)

        override fun dead(now: Instant): Boolean = !isProtected(now) && !now.isBefore(lastUpdate.plus(throttle.loginFailureWindow))

        private fun blocked(now: Instant): Boolean = blockedUntil?.let { now.isBefore(it) } == true

        fun retryMillis(now: Instant, threshold: Int): Long = when {
            blocked(now) -> Duration.between(now, blockedUntil).toMillis().coerceAtLeast(1)
            failures.toLong() + attempts.size >= threshold ->
                attempts.values.minOrNull()?.let { Duration.between(now, it).toMillis().coerceAtLeast(1) } ?: CAPACITY_RETRY_MILLIS
            else -> 0
        }

        fun resetHistory() {
            failures = 0
            lastFailure = null
            blockedUntil = null
            nextBlock = throttle.loginInitialBlock
        }

        fun fail(now: Instant, threshold: Int, block: Instant?): Boolean {
            failures += 1
            lastFailure = now
            if (failures < threshold) return false
            blockedUntil = block
            failures = 0
            nextBlock = if (nextBlock > throttle.loginMaxBlock.dividedBy(2)) throttle.loginMaxBlock else nextBlock.multipliedBy(2)
            return true
        }
    }

    private inner class RegistrationBucket(override var lastUpdate: Instant) : Bucket {
        var windowStart: Instant = lastUpdate
        var count = 0

        override fun expire(now: Instant) {
            if (!now.isBefore(windowStart.plus(throttle.registrationWindow))) {
                count = 0
            }
        }

        override fun isProtected(now: Instant): Boolean = count >= throttle.registrationMaxPerWindow && !dead(now)

        override fun dead(now: Instant): Boolean = !now.isBefore(windowStart.plus(throttle.registrationWindow))
    }

    private companion object {
        val log = LoggerFactory.getLogger(AuthThrottleService::class.java)
        const val EMAIL_KEY_CAP = 320
        const val CAPACITY_RETRY_MILLIS = 5_000L
    }
}
