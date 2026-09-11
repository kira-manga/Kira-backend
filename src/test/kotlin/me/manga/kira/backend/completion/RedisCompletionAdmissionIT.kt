package me.manga.kira.backend.completion

import me.manga.kira.backend.common.exception.ServiceUnavailableException
import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.completion.application.RedisCompletionAdmission
import me.manga.kira.backend.config.KiraCompletionProperties
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real Redis logical leases only; no provider, cancellation or physical-work termination claim. */
@Timeout(20)
class RedisCompletionAdmissionIT {
    @BeforeEach
    @AfterEach
    fun clearRedis() {
        requireNotNull(template.connectionFactory).connection.use { it.serverCommands().flushDb() }
    }

    @Test
    fun `two instances sustain unexpired overlapping leases beyond the first key deadline`() {
        val first = RedisCompletionAdmission(template, properties())
        val second = RedisCompletionAdmission(template, properties())
        val original = first.acquire(USER)
        val firstDeadline = leases().values.single()
        assertEquals(firstDeadline, retainedUntil())
        awaitServerTime(firstDeadline - 2_000)
        val overlapping = second.acquire(USER)
        assertTrue(now() < firstDeadline - 750, "Original lease is still valid with scheduling margin")
        original.close()
        val replacement = first.acquire(USER)
        val held = leases()
        assertCapacityDenied(second, held)

        awaitServerTime(firstDeadline + 50)
        assertCapacityDenied(first, held) // Neither held lease is expired; unrelated rate caps are disabled.
        assertTrue(retainedUntil() >= held.values.max())
        overlapping.close()
        val next = second.acquire(USER)
        assertCapacityDenied(first, leases())
        replacement.close()
        next.close()
        assertFalse(requireNotNull(template.hasKey(KEY)))
    }

    @Test
    fun `expired and replayed releases cannot touch successor tokens or their deadlines`() {
        val stale = RedisCompletionAdmission(template, properties(250)).acquire(USER)
        val (oldToken, oldDeadline) = leases().entries.single().let { it.key to it.value }
        awaitServerTime(oldDeadline)
        assertFalse(requireNotNull(template.hasKey(KEY))) // Abandonment frees capacity without a close.

        val first = RedisCompletionAdmission(template, properties(5_000))
        val second = RedisCompletionAdmission(template, properties(5_000))
        val one = first.acquire(USER)
        val firstToken = leases().keys.single()
        val two = second.acquire(USER)
        val successors = leases()
        val secondToken = (successors.keys - firstToken).single()
        val expiry = retainedUntil()
        stale.close()
        stale.close()
        assertEquals(0L, releaseAgain(oldToken)) // Actual Redis replay, not merely the local AtomicBoolean.
        assertEquals(successors, leases())
        assertEquals(expiry, retainedUntil())
        assertCapacityDenied(first, successors)

        one.close()
        assertEquals(0L, releaseAgain(firstToken))
        assertEquals(successors - firstToken, leases())
        assertEquals(expiry, retainedUntil())
        two.close()
        assertEquals(0L, releaseAgain(secondToken))
        assertFalse(requireNotNull(template.hasKey(KEY)))
    }

    @Test
    fun `shorter instance leases and turnover never shorten a live longer lease`() {
        val long = RedisCompletionAdmission(template, properties(5_000)).acquire(USER)
        val (longToken, longDeadline) = leases().entries.single().let { it.key to it.value }
        val shortInstance = RedisCompletionAdmission(template, properties(250))
        val short = shortInstance.acquire(USER)
        val (shortToken, shortDeadline) = (leases() - longToken).entries.single().let { it.key to it.value }
        assertTrue(shortDeadline < longDeadline)
        assertEquals(longDeadline, retainedUntil())
        awaitServerTime(shortDeadline)
        val expired = snapshot()
        assertEquals(-1L, template.execute(SCRIPT, keys(), "acquire", shortToken, "0", "0", "0", "2", "4000"))
        assertEquals(expired, snapshot()) // Reusing even an expired token cannot create a new permit.

        val replacement = shortInstance.acquire(USER) // Prunes only the expired short member.
        val held = leases()
        assertFalse(shortToken in held)
        assertEquals(longDeadline, held[longToken])
        assertEquals(longDeadline, retainedUntil())
        short.close()
        assertEquals(held, leases())
        assertCapacityDenied(shortInstance, held)
        long.close()
        assertEquals(held - longToken, leases())
        assertEquals(longDeadline, retainedUntil()) // Token-only release may leave a harmless later key expiry.
        replacement.close()
        assertFalse(requireNotNull(template.hasKey(KEY)))
    }

    @Test
    fun `legacy malformed and oversized lease state is refused without repair on acquire or release`() {
        val admission = RedisCompletionAdmission(template, properties(15_000).copy(perUserPerMinute = 10, globalPerMinute = 10, perUserDailyQuota = 10))
        listOf("legacy", "hash", "fraction", "infinite", "negative", "range", "token", "capacity", "persistent", "early-expiry").forEach { fault ->
            clearRedis()
            val permit = admission.acquire(USER)
            val (token, deadline) = leases().entries.single().let { it.key to it.value }
            when (fault) {
                "legacy" -> {
                    template.delete(KEY)
                    template.opsForValue().set(KEY, "1", Duration.ofMinutes(1))
                }
                "hash" -> {
                    template.delete(KEY)
                    template.opsForHash<String, String>().put(KEY, "legacy", "1")
                }
                "fraction" -> template.opsForZSet().add(KEY, token, deadline - 0.5)
                "infinite" -> template.opsForZSet().add(KEY, token, Double.POSITIVE_INFINITY)
                "negative" -> template.opsForZSet().add(KEY, token, -1.0)
                "range" -> template.opsForZSet().add(KEY, token, MAX_INTEGER.toDouble() + 1)
                "token" -> template.opsForZSet().add(KEY, "not-a-uuid", deadline.toDouble())
                "capacity" -> repeat(2) { template.opsForZSet().add(KEY, UUID.randomUUID().toString(), deadline.toDouble()) }
                "persistent" -> template.persist(KEY)
                "early-expiry" -> template.expireAt(KEY, Instant.ofEpochMilli(deadline - 1_000))
            }
            val before = snapshot()
            assertUnavailable(assertThrows<ServiceUnavailableException> { admission.acquire(USER) })
            assertEquals(before, snapshot(), fault)
            val error = assertThrows<TooManyRequestsException> { permit.close() }
            assertEquals("COMPLETION_COORDINATION_UNAVAILABLE", error.code)
            assertEquals(429, error.status.value())
            assertEquals(5L, error.retryAfterSeconds)
            assertEquals(before, snapshot(), fault)
        }
    }

    @Test
    fun `enabled rate state is preflighted before any earlier counter or lease mutation`() {
        val admission = RedisCompletionAdmission(template, properties(15_000).copy(perUserPerMinute = 10, globalPerMinute = 10, perUserDailyQuota = 10))
        listOf("1.5", "01", MAX_INTEGER.toString(), "wrong-type").forEach { fault ->
            clearRedis()
            val permit = admission.acquire(USER)
            val rateKey = keys()[2]
            if (fault == "wrong-type") {
                template.delete(rateKey)
                template.opsForList().rightPush(rateKey, "value")
            } else {
                template.opsForValue().set(rateKey, fault)
            }
            val before = snapshot()
            assertUnavailable(assertThrows<ServiceUnavailableException> { admission.acquire(USER) })
            assertEquals(before, snapshot(), fault)
            permit.close() // Release neither reads nor changes rate keys.
            assertEquals(before - KEY, snapshot() - KEY, fault)
        }
    }

    @Test
    fun `token collision and invalid protocol arguments refuse before writes`() {
        val admission = RedisCompletionAdmission(template, properties(5_000))
        val permit = admission.acquire(USER)
        val token = leases().keys.single()
        val before = snapshot()
        val arguments = listOf("acquire", UUID.randomUUID().toString(), "10", "10", "10", "2", "4000")
        listOf(1 to token, 2 to "1.5", 5 to "4097", 6 to "0", 6 to MAX_INTEGER.toString()).forEach { (index, invalid) ->
            val args = arguments.toMutableList().also { it[index] = invalid }
            assertEquals(-1L, template.execute(SCRIPT, keys(), *args.toTypedArray()))
            assertEquals(before, snapshot())
        }
        val aliased = keys().toMutableList().also { it[1] = it[0] }
        assertEquals(-1L, template.execute(SCRIPT, aliased, *arguments.toTypedArray()))
        assertEquals(-1L, releaseAgain("invalid-token"))
        assertEquals(before, snapshot())
        permit.close()
    }

    @Test
    fun `exact deadline arithmetic rejects overflow and retains the greatest integral score`() {
        val peer = UUID.randomUUID().toString()
        template.opsForZSet().add(KEY, peer, MAX_INTEGER.toDouble())
        assertTrue(requireNotNull(template.expireAt(KEY, Instant.ofEpochMilli(MAX_INTEGER))))
        val before = snapshot()
        val overflowing = properties().copy(queueTimeout = Duration.ofMillis(1), timeout = Duration.ofMillis(MAX_INTEGER / 2 - 1))
        assertUnavailable(assertThrows<ServiceUnavailableException> { RedisCompletionAdmission(template, overflowing).acquire(USER) })
        assertEquals(before, snapshot())
        val permit = RedisCompletionAdmission(template, properties()).acquire(USER)
        assertEquals(MAX_INTEGER, leases()[peer])
        assertEquals(MAX_INTEGER, retainedUntil()) // Decimal integer formatting, not scientific/rounded tostring.
        permit.close()
        assertEquals(before, snapshot())
    }

    private fun assertCapacityDenied(admission: RedisCompletionAdmission, expected: Map<String, Long>) {
        assertEquals(2, expected.size)
        assertTrue(expected.values.all { it > now() }, "Both logical leases must still be unexpired before rejection")
        val error = assertThrows<ServiceUnavailableException> { admission.acquire(USER) }
        assertEquals("COMPLETION_CONCURRENCY_LIMIT", error.code)
        assertEquals(503, error.status.value())
        assertEquals(1L, error.retryAfterSeconds)
        assertEquals(expected, leases())
        assertTrue(expected.values.all { it > now() }, "A scheduling stall must not count an expired token as protected")
    }

    private fun assertUnavailable(error: ServiceUnavailableException) {
        assertEquals("COMPLETION_COORDINATION_UNAVAILABLE", error.code)
        assertEquals(503, error.status.value())
        assertEquals(5L, error.retryAfterSeconds)
    }

    private fun leases(): Map<String, Long> = requireNotNull(template.opsForZSet().rangeWithScores(KEY, 0, -1)).associate {
        val score = requireNotNull(it.score)
        assertTrue(score.isFinite() && score == score.toLong().toDouble())
        requireNotNull(it.value) to score.toLong()
    }

    private fun snapshot() = keys().associateWith { template.dump(it)?.toList() to requireNotNull(template.execute(EXPIRY, listOf(it))) }

    private fun retainedUntil(): Long = requireNotNull(template.execute(EXPIRY, listOf(KEY)))

    private fun now(): Long = requireNotNull(template.execute(NOW, emptyList()))

    private fun releaseAgain(token: String): Long = requireNotNull(template.execute(SCRIPT, listOf(KEY), "release", token, "2"))

    private fun awaitServerTime(deadline: Long) {
        val stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (now() < deadline && System.nanoTime() < stop) Thread.sleep(10)
        assertTrue(now() >= deadline, "Redis TIME must reach the actual member deadline within the bounded wait")
    }

    private fun keys() = listOf("$PREFIX:user-minute:$USER", "$PREFIX:global-minute", "$PREFIX:user-day:$USER", KEY)

    private fun properties(timeoutMs: Long = 1_000) = KiraCompletionProperties(
        coordinationBackend = "redis", instanceCount = 2, globalConcurrency = 2,
        perUserPerMinute = 0, globalPerMinute = 0, perUserDailyQuota = 0,
        queueTimeout = Duration.ofMillis(timeoutMs), timeout = Duration.ofMillis(timeoutMs),
    )

    companion object {
        private const val PREFIX = "kira:completion-admission"
        private const val KEY = "$PREFIX:concurrency"
        private const val MAX_INTEGER = 9_007_199_254_740_991L
        private val USER = UUID.randomUUID()
        // The same production resource, not a transcribed/simulated release protocol.
        private val SCRIPT = DefaultRedisScript<Long>().apply {
            setLocation(ClassPathResource("redis/completion-admission.lua"))
            resultType = Long::class.java
        }
        private val EXPIRY = DefaultRedisScript("return redis.call('PEXPIRETIME', KEYS[1])", Long::class.java)
        private val NOW = DefaultRedisScript(
            "local t = redis.call('TIME'); return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)", Long::class.java,
        )
        private val redis = GenericContainer(DockerImageName.parse("redis:7.4.7-alpine")).withExposedPorts(6379).also { it.start() }
        private val factory = LettuceConnectionFactory(
            RedisStandaloneConfiguration(redis.host, redis.getMappedPort(6379)),
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ofSeconds(2)).build(),
        ).also {
            it.afterPropertiesSet()
            it.start()
        }
        private val template = StringRedisTemplate(factory).also { it.afterPropertiesSet() }

        @JvmStatic
        @AfterAll
        fun shutdown() {
            try {
                factory.destroy()
            } finally {
                redis.stop()
            }
        }
    }
}
