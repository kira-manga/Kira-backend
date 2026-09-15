package me.manga.kira.backend.completion

import me.manga.kira.backend.common.exception.ServiceUnavailableException
import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.completion.application.CompletionActivation
import me.manga.kira.backend.completion.application.RedisCompletionAdmission
import me.manga.kira.backend.config.KiraCompletionProperties
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Only this class's disposable Redis is seeded/aged; production admission always obtains server TIME. */
class RedisCompletionAdmissionPolicyIT : CompletionAdmissionPolicyContract() {
    @BeforeEach
    @AfterEach
    fun clearRedis() {
        requireNotNull(template.connectionFactory).connection.use { it.serverCommands().flushDb() }
    }

    override fun fixture(properties: KiraCompletionProperties): CompletionAdmissionPolicyFixture = RedisFixture(properties)

    @ParameterizedTest(name = "no mutation on {0} rejection")
    @ValueSource(strings = ["global", "daily", "capacity"])
    fun `a later rejection leaves every ledger expiry and reservation unchanged`(dimension: String) {
        val config = properties(
            user = 3,
            global = if (dimension == "global") 1 else 5,
            daily = if (dimension == "daily") 1 else 3,
            capacity = if (dimension == "capacity") 1 else 8,
        )
        fixture(config).use { test ->
            test.first.acquire(USER).use { held ->
                assertEquals(CompletionActivation.ACTIVATED, held.activate())
                val before = snapshot()
                val caller = if (dimension == "daily") USER else UUID.randomUUID()
                when (dimension) {
                    "capacity" -> assertEquals(
                        "COMPLETION_CONCURRENCY_LIMIT",
                        assertThrows<ServiceUnavailableException> { test.second.acquire(caller) }.code,
                    )

                    else -> assertEquals(
                        if (dimension == "daily") "COMPLETION_DAILY_QUOTA" else "COMPLETION_GLOBAL_RATE_LIMIT",
                        assertThrows<TooManyRequestsException> { test.second.acquire(caller) }.code,
                    )
                }
                assertEquals(before, snapshot(), "Rejection must not even prune or refresh earlier state")
            }
        }
    }

    @Test
    fun `server timestamps share UUID events and retention follows the latest event not the first`() {
        fixture(properties(user = 3, global = 3, daily = 3)).use { test ->
            val before = now()
            test.first.acquire(USER).close()
            val after = now()
            val first = ledger(keys()[0])
            assertEquals(1, first.size)
            assertTrue(first.values.single() in before..after, "No application clock is supplied to admission")
            assertEquals(first, ledger(keys()[1]))
            assertEquals(first, ledger(keys()[2]))
            assertEquals(4, UUID.fromString(first.keys.single()).version())

            test.advance(Duration.ofSeconds(30))
            val previousDeadline = expiry(keys()[0])
            test.second.acquire(USER).close()
            val events = ledger(keys()[0])
            assertEquals(2, events.size)
            assertEquals(events, ledger(keys()[1]))
            assertEquals(events, ledger(keys()[2]))
            keys().take(3).forEach { key ->
                assertEquals(events.values.max() + window(key) + 1, expiry(key))
            }
            assertTrue(expiry(keys()[0]) > previousDeadline)
            assertFalse(requireNotNull(template.hasKey(CONCURRENCY)), "Closing permits must not remove charged rate events")
        }
    }

    @ParameterizedTest(name = "exact Redis cutoff: {0}")
    @EnumSource(CompletionRateDimension::class)
    fun `the exact cutoff is retained and the next millisecond prunes it`(dimension: CompletionRateDimension) {
        // A real server TIME sample is held fixed only inside this atomic test wrapper. The unchanged
        // production resource otherwise executes against real Redis. A newer peer keeps TTL away from
        // the test boundary, so this tests score inclusion rather than scheduler-dependent key expiry.
        listOf(dimension.window.toMillis() to dimension.ordinal + 1L, dimension.window.toMillis() + 1 to 0L).forEach { (age, expected) ->
            clearRedis()
            val config = dimension.properties(2)
            val result = template.execute(
                EXACT_BOUNDARY,
                keys(),
                "acquire", UUID.randomUUID().toString(), config.perUserPerMinute.toString(), config.globalPerMinute.toString(),
                config.perUserDailyQuota.toString(), "8", "64000", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                (dimension.ordinal + 1).toString(), age.toString(), dimension.window.toMillis().toString(),
            )
            assertEquals(expected, result)
            assertEquals(2, ledger(keys()[dimension.ordinal]).size, "An accepted replacement prunes only the expired event")
        }
    }

    @Test
    fun `idle daily history expires without another admission or an orphan counter`() {
        fixture(CompletionRateDimension.USER_DAY.properties(1)).use { test ->
            test.first.acquire(USER).close()
            test.advance(Duration.ofDays(1).minusSeconds(2))
            val key = keys()[2]
            val deadline = expiry(key)
            assertEquals(ledger(key).values.single() + DAY + 1, deadline)
            assertTrue(deadline > now(), "The actual server expiry is still ahead of this readback")
            val stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (now() < deadline && System.nanoTime() < stop) Thread.sleep(10)
            assertTrue(now() >= deadline)
            assertFalse(requireNotNull(template.hasKey(key)))
            assertTrue(requireNotNull(template.keys("$PREFIX:*")).isEmpty())
        }
    }

    @Test
    fun `disabled dimensions neither inspect nor mutate even incompatible retained rate state`() {
        keys().take(3).forEach { template.opsForList().rightPush(it, "synthetic-disabled-state") }
        val before = snapshot()
        fixture(properties(capacity = 1)).use { test ->
            repeat(3) {
                test.first.acquire(USER).use { permit ->
                    assertEquals(CompletionActivation.ACTIVATED, permit.activate())
                    assertEquals("COMPLETION_CONCURRENCY_LIMIT", assertThrows<ServiceUnavailableException> { test.second.acquire(USER) }.code)
                }
            }
        }
        assertEquals(before, snapshot())
    }

    @Test
    fun `two instances racing at one cap record one unique event per admitted attempt in every dimension`() {
        fixture(properties(user = 8, global = 8, daily = 8, capacity = 32)).use { test ->
            val pool = Executors.newFixedThreadPool(8)
            val ready = CountDownLatch(8)
            val start = CountDownLatch(1)
            try {
                val attempts = (0 until 16).map { index ->
                    pool.submit(Callable {
                        ready.countDown()
                        assertTrue(start.await(3, TimeUnit.SECONDS))
                        val admission = if (index % 2 == 0) test.first else test.second
                        try {
                            admission.acquire(USER).close()
                            true
                        } catch (failure: TooManyRequestsException) {
                            assertEquals("COMPLETION_USER_RATE_LIMIT", failure.code)
                            false
                        }
                    })
                }
                assertTrue(ready.await(3, TimeUnit.SECONDS))
                start.countDown()
                assertEquals(8, attempts.count { it.get(3, TimeUnit.SECONDS) })
                val events = ledger(keys()[0])
                assertEquals(8, events.size)
                events.keys.forEach { assertEquals(4, UUID.fromString(it).version()) }
                assertEquals(events, ledger(keys()[1]))
                assertEquals(events, ledger(keys()[2]))
                assertFalse(requireNotNull(template.hasKey(CONCURRENCY)))
            } finally {
                start.countDown()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS), "All fixture-owned request tasks must settle")
            }
        }
    }

    @Test
    fun `rate expiry never expires a pin or charges a capacity-rejected replacement`() {
        fixture(properties(user = 2, global = 3, capacity = 1)).use { test ->
            test.first.acquire(USER).use { held ->
                assertEquals(CompletionActivation.ACTIVATED, held.activate())
                test.advance(Duration.ofSeconds(61))
                val pinned = snapshot()
                assertEquals(setOf(CONCURRENCY), pinned.keys)
                assertEquals(-1L, expiry(CONCURRENCY))
                assertEquals("COMPLETION_CONCURRENCY_LIMIT", assertThrows<ServiceUnavailableException> { test.second.acquire(USER) }.code)
                assertEquals(pinned, snapshot())
            }
            test.second.acquire(USER).close()
            assertEquals(1, ledger(keys()[0]).size)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["legacy-counter", "persistent", "short-expiry", "future-score"])
    fun `unsupported rate state fails closed without repairing it or charging any earlier dimension`(fault: String) {
        fixture(properties(user = 10, global = 10, daily = 10)).use { test ->
            test.first.acquire(USER).close()
            val key = keys()[2]
            when (fault) {
                "legacy-counter" -> template.opsForValue().set(key, "1", Duration.ofDays(1))

                "persistent" -> template.persist(key)

                "short-expiry" -> template.expireAt(key, Instant.ofEpochMilli(expiry(key) - 1))

                "future-score" -> {
                    val score = now() + 30_000
                    template.opsForZSet().add(key, ledger(key).keys.single(), score.toDouble())
                    template.expireAt(key, Instant.ofEpochMilli(score + DAY + 1))
                }
            }
            val before = snapshot()
            val failure = assertThrows<ServiceUnavailableException> { test.second.acquire(USER) }
            assertEquals("COMPLETION_COORDINATION_UNAVAILABLE", failure.code)
            assertEquals(5L, failure.retryAfterSeconds)
            assertEquals(before, snapshot())
        }
    }

    @Test
    fun `a retained event UUID cannot be replayed after its permit was released`() {
        fixture(properties(user = 3, global = 3, daily = 3)).use { test ->
            test.first.acquire(USER).close()
            val token = ledger(keys()[0]).keys.single()
            val before = snapshot()
            assertEquals(-1L, template.execute(SCRIPT, keys(), "acquire", token, "3", "3", "3", "8", "64000"))
            assertEquals(before, snapshot())
            test.second.acquire(USER).close()
            assertEquals(2, ledger(keys()[0]).size)
        }
    }

    private class RedisFixture(properties: KiraCompletionProperties) : CompletionAdmissionPolicyFixture {
        private val shared = properties.copy(coordinationBackend = "redis", instanceCount = 2)
        override val first = RedisCompletionAdmission(template, shared)
        override val second = RedisCompletionAdmission(template, shared)

        override fun advance(duration: Duration) {
            // Fixture-only time travel: age already-written events, not production TIME or windows.
            // Permits are deliberately untouched, preserving the independent pending/pin protocol.
            val rateKeys = requireNotNull(template.keys("$PREFIX:*")).filter { it != CONCURRENCY }.sorted()
            if (rateKeys.isNotEmpty()) {
                assertEquals(1L, template.execute(AGE, rateKeys, duration.toMillis().toString(), *rateKeys.map { window(it).toString() }.toTypedArray()))
            }
        }

        override fun close() = Unit
    }

    private fun snapshot() = requireNotNull(template.keys("$PREFIX:*")).sorted().associateWith {
        requireNotNull(template.dump(it)).toList() to expiry(it)
    }

    private fun ledger(key: String): Map<String, Long> = requireNotNull(template.opsForZSet().rangeWithScores(key, 0, -1)).associate {
        val score = requireNotNull(it.score)
        assertEquals(score.toLong().toDouble(), score)
        requireNotNull(it.value) to score.toLong()
    }

    private fun keys() = listOf("$PREFIX:user-minute:$USER", "$PREFIX:global-minute", "$PREFIX:user-day:$USER", CONCURRENCY)

    private fun expiry(key: String): Long = requireNotNull(template.execute(EXPIRY, listOf(key)))

    private fun now(): Long = requireNotNull(template.execute(NOW, emptyList()))

    companion object {
        private const val PREFIX = "kira:completion-admission"
        private const val CONCURRENCY = "$PREFIX:concurrency"
        private const val MINUTE = 60_000L
        private const val DAY = 86_400_000L
        private val USER = UUID.randomUUID()

        private fun window(key: String) = if (key.startsWith("$PREFIX:user-day:")) DAY else MINUTE

        private val SCRIPT = DefaultRedisScript<Long>().apply {
            setLocation(ClassPathResource("redis/completion-admission.lua"))
            resultType = Long::class.java
        }
        private val EXPIRY = DefaultRedisScript("return redis.call('PEXPIRETIME', KEYS[1])", Long::class.java)
        private val NOW = DefaultRedisScript(
            "local t = redis.call('TIME'); return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)",
            Long::class.java,
        )
        private val AGE = DefaultRedisScript(
            """
            local delta = tonumber(ARGV[1])
            for i, key in ipairs(KEYS) do
              local entries = redis.call('ZRANGE', key, 0, -1, 'WITHSCORES')
              for j = 1, #entries, 2 do
                redis.call('ZADD', key, string.format('%.0f', tonumber(entries[j + 1]) - delta), entries[j])
              end
              if #entries > 0 then
                local newest = tonumber(entries[#entries]) - delta
                redis.call('PEXPIREAT', key, string.format('%.0f', newest + tonumber(ARGV[i + 1]) + 1))
              end
            end
            return 1
            """.trimIndent(),
            Long::class.java,
        )
        private val EXACT_BOUNDARY = DefaultRedisScript(
            """
            local authoritative = redis
            local sampled = redis.call('TIME')
            local now = tonumber(sampled[1]) * 1000 + math.floor(tonumber(sampled[2]) / 1000)
            local selected = KEYS[tonumber(ARGV[10])]
            redis.call('ZADD', selected, string.format('%.0f', now - tonumber(ARGV[11])), ARGV[8], string.format('%.0f', now), ARGV[9])
            redis.call('PEXPIREAT', selected, string.format('%.0f', now + tonumber(ARGV[12]) + 1))
            local ARGV = {unpack(ARGV, 1, 7)}
            local redis = {call = function(command, ...)
              if command == 'TIME' then return sampled end
              return authoritative.call(command, ...)
            end}
            local function production()
            """.trimIndent() + "\n" + SCRIPT.scriptAsString + "\nend\nreturn production()",
            Long::class.java,
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
