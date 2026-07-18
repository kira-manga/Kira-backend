package me.manga.kira.backend.security

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.config.KiraSecurityProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Bounded in-memory auth throttle (PLAN §6, Appendix C #4). **Single-instance only** — the store is
 * correct for one JVM; a multi-instance deployment MUST move to a shared backend (Redis) first.
 * This class is the seam for that.
 *
 *  - **Login identity bucket**: keyed by normalized-email AND client IP. Repeated failures block that
 *    pair without globally locking the account.
 *  - **Login IP bucket**: aggregates failures across emails from one client address, preventing one
 *    origin from bypassing throttling by spraying many identities.
 *    Blocks double per breach, cap at `loginMaxBlock`, and expire after inactivity. There is **no
 *    permanent lockout** an attacker could weaponize against a victim (everything is TTL-bounded).
 *  - **Registration**: a per-IP rate limit within `registrationWindow`.
 *  - Throttled → the caller raises **429** with the same generic body as an auth failure (no oracle).
 *
 * **Bounded store**: at most `throttle.max-entries`; each entry stores only counters/timestamps
 * (never credentials); keys hash the (capped) email so nothing sensitive is retained; when full,
 * eviction removes dead entries first, then the oldest by last-update.
 */
@Service
class AuthThrottleService(private val properties: KiraSecurityProperties, private val clock: Clock) {
    private val throttle get() = properties.throttle

    private val lock = Any()
    private val loginBuckets = HashMap<String, LoginBucket>()
    private val registrationBuckets = HashMap<String, RegistrationBucket>()

    /** Throws 429 if either the (email, IP) identity or aggregate IP bucket is blocked. */
    fun checkLoginAllowed(normalizedEmail: String, clientIp: String) {
        val now = clock.instant()
        synchronized(lock) {
            for (key in listOf(loginIdentityKey(normalizedEmail, clientIp), loginIpKey(clientIp))) {
                val until = loginBuckets[key]?.blockedUntil
                if (until != null && now.isBefore(until)) {
                    throw TooManyRequestsException("Too many attempts. Try again later.")
                }
            }
        }
    }

    /** Record a failed login; may arm the temporary block. */
    fun recordLoginFailure(normalizedEmail: String, clientIp: String) {
        val now = clock.instant()
        synchronized(lock) {
            recordFailure(
                key = loginIdentityKey(normalizedEmail, clientIp),
                threshold = throttle.loginFailureThreshold,
                now = now,
                dimension = "identity",
            )
            recordFailure(
                key = loginIpKey(clientIp),
                threshold = throttle.loginIpFailureThreshold,
                now = now,
                dimension = "client-ip",
            )
        }
    }

    /** Successful login clears the identity bucket; it cannot erase aggregate IP spray history. */
    fun recordLoginSuccess(normalizedEmail: String, clientIp: String) {
        synchronized(lock) { loginBuckets.remove(loginIdentityKey(normalizedEmail, clientIp)) }
    }

    /** Count a registration attempt for [clientIp]; throws 429 when the per-IP window cap is exceeded. */
    fun checkRegistrationAllowed(clientIp: String) {
        val now = clock.instant()
        synchronized(lock) {
            val bucket =
                registrationBuckets.getOrPut(clientIp) {
                    evictIfNeeded(registrationBuckets, now) { it.isDead(now, throttle) }
                    RegistrationBucket(windowStart = now, lastUpdate = now)
                }
            if (Duration.between(bucket.windowStart, now) > throttle.registrationWindow) {
                bucket.windowStart = now
                bucket.count = 0
            }
            if (bucket.count >= throttle.registrationMaxPerWindow) {
                throw TooManyRequestsException("Too many attempts. Try again later.")
            }
            bucket.count += 1
            bucket.lastUpdate = now
        }
    }

    /** Reset ALL throttle state (operational reset; also used to isolate tests). */
    fun clearAll() {
        synchronized(lock) {
            loginBuckets.clear()
            registrationBuckets.clear()
        }
    }

    /** Total tracked entries across both stores (for tests / diagnostics). */
    fun size(): Int = synchronized(lock) { loginBuckets.size + registrationBuckets.size }

    private fun recordFailure(key: String, threshold: Int, now: Instant, dimension: String) {
        val bucket =
            loginBuckets.getOrPut(key) {
                evictIfNeeded(loginBuckets, now) { it.isDead(now, throttle) }
                LoginBucket(nextBlock = throttle.loginInitialBlock, lastUpdate = now)
            }
        if (Duration.between(bucket.lastUpdate, now) > throttle.loginFailureWindow) {
            bucket.failures = 0
            bucket.nextBlock = throttle.loginInitialBlock
            bucket.blockedUntil = null
        }
        bucket.failures += 1
        bucket.lastUpdate = now
        if (bucket.failures >= threshold) {
            bucket.blockedUntil = now.plus(bucket.nextBlock)
            bucket.failures = 0
            bucket.nextBlock = minOf(bucket.nextBlock.multipliedBy(2), throttle.loginMaxBlock)
            log.warn("Auth throttle engaged for login dimension={}", dimension)
        }
    }

    /** Key = hash(capped email) + IP — bounded and credential-free (PLAN §6 bounded keys). */
    private fun loginIdentityKey(normalizedEmail: String, clientIp: String): String {
        val capped = normalizedEmail.take(EMAIL_KEY_CAP)
        return "identity|" + Sha256.hexUtf8(capped) + "|" + clientIp
    }

    private fun loginIpKey(clientIp: String): String = "client-ip|$clientIp"

    /**
     * Ensure there is room for one more key: drop dead entries first, then the oldest by last-update,
     * until under the cap (PLAN §6 deterministic eviction).
     */
    private fun <B : TrackedBucket> evictIfNeeded(map: MutableMap<String, B>, now: Instant, isDead: (B) -> Boolean) {
        if (map.size < throttle.maxEntries) return
        map.entries.removeIf { isDead(it.value) }
        while (map.size >= throttle.maxEntries) {
            val oldest = map.entries.minByOrNull { it.value.lastUpdate } ?: break
            map.remove(oldest.key)
        }
    }

    private interface TrackedBucket {
        val lastUpdate: Instant
    }

    private class LoginBucket(var failures: Int = 0, var blockedUntil: Instant? = null, var nextBlock: Duration, override var lastUpdate: Instant) :
        TrackedBucket {
        fun isDead(now: Instant, throttle: KiraSecurityProperties.Throttle): Boolean {
            val blockOver = blockedUntil?.let { !now.isBefore(it) } ?: true
            val windowOver = Duration.between(lastUpdate, now) > throttle.loginFailureWindow
            return blockOver && windowOver
        }
    }

    private class RegistrationBucket(var windowStart: Instant, var count: Int = 0, override var lastUpdate: Instant) : TrackedBucket {
        fun isDead(now: Instant, throttle: KiraSecurityProperties.Throttle): Boolean = Duration.between(windowStart, now) > throttle.registrationWindow
    }

    private companion object {
        val log = LoggerFactory.getLogger(AuthThrottleService::class.java)
        const val EMAIL_KEY_CAP = 320
    }
}
