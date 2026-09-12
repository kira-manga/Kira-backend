package me.manga.kira.backend.completion

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.manga.kira.backend.common.GlobalExceptionHandler
import me.manga.kira.backend.completion.application.CompletionActivation
import me.manga.kira.backend.completion.application.CompletionAdmission
import me.manga.kira.backend.completion.application.InMemoryCompletionAdmission
import me.manga.kira.backend.completion.application.RedisCompletionAdmission
import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.observability.KiraMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.serializer.SerializationException
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Actual admission decisions and actual advice; only Redis transport is replaced, not its result mapping. */
class CompletionAdmissionResponseTest {
    @Test
    fun `memory capacity is service overload and becomes available after release`() {
        val admission = InMemoryCompletionAdmission(properties().copy(globalConcurrency = 1), Clock.systemUTC())
        val held = admission.acquire(USER)
        val endpoint = Endpoint(admission)
        try {
            endpoint.rejects(503, "COMPLETION_CONCURRENCY_LIMIT", 1)
        } finally {
            held.close()
        }
        endpoint.accepts()
    }

    @ParameterizedTest
    @CsvSource(
        "1, 0, 0, COMPLETION_USER_RATE_LIMIT, 60",
        "0, 1, 0, COMPLETION_GLOBAL_RATE_LIMIT, 60",
        "0, 0, 1, COMPLETION_DAILY_QUOTA, 86400",
    )
    fun `memory rate and quota rejections remain caller limits`(userRate: Int, globalRate: Int, dailyQuota: Int, code: String, retry: Long) {
        val admission = InMemoryCompletionAdmission(
            properties().copy(perUserPerMinute = userRate, globalPerMinute = globalRate, perUserDailyQuota = dailyQuota),
            Clock.systemUTC(),
        )
        admission.acquire(USER).close()
        Endpoint(admission).rejects(429, code, retry)
    }

    @ParameterizedTest
    @CsvSource(
        "1, 429, COMPLETION_USER_RATE_LIMIT, 60",
        "2, 429, COMPLETION_GLOBAL_RATE_LIMIT, 60",
        "3, 429, COMPLETION_DAILY_QUOTA, 86400",
        "4, 503, COMPLETION_CONCURRENCY_LIMIT, 1",
    )
    fun `Redis defined rejections preserve codes retries and their HTTP distinction`(result: Long, status: Int, code: String, retry: Long) {
        val redis = RedisFixture(result)
        Endpoint(redis.admission).rejects(status, code, retry)
        assertEquals(listOf(4), redis.keyCounts, "Rejected acquire cannot issue a release.")
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = [-1, 5, Long.MAX_VALUE])
    fun `indeterminate Redis acquisition denies work without speculative release`(result: Long?) {
        val redis = RedisFixture(result)
        Endpoint(redis.admission).rejects(503, "COMPLETION_COORDINATION_UNAVAILABLE", 5)
        assertEquals(listOf(4), redis.keyCounts)
    }

    @Test
    fun `Redis acquire transport failure is unavailable not a caller limit`() {
        val redis = RedisFixture(acquireFailure = true)
        Endpoint(redis.admission).rejects(503, "COMPLETION_COORDINATION_UNAVAILABLE", 5)
        assertEquals(listOf(4), redis.keyCounts)
    }

    @Test
    fun `malformed Redis acquisition replies deny without a speculative release`() {
        listOf(0, 0.0, true, "0", listOf(0L), SerializationException("private Redis connection detail"), ClassCastException()).forEach { reply ->
            val redis = RedisFixture(result = reply)
            Endpoint(redis.admission).rejects(503, "COMPLETION_COORDINATION_UNAVAILABLE", 5)
            assertEquals(listOf(4), redis.keyCounts)
        }
    }

    @Test
    fun `Redis acquire zero and activation one alone admit and release once`() {
        val redis = RedisFixture()
        Endpoint(redis.admission).accepts()
        assertEquals(listOf(4, 1, 1), redis.keyCounts)
        assertEquals(listOf("acquire", "activate", "release"), redis.operations)
        assertEquals(1, redis.tokens.toSet().size)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `unconfirmed Redis release preserves the result and reports once per application attempt`(releaseFailure: Boolean) {
        val registry = SimpleMeterRegistry()
        val logger = LoggerFactory.getLogger(RedisCompletionAdmission::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply {
            context = logger.loggerContext
            start()
        }
        logger.addAppender(appender)
        try {
            val redis = RedisFixture(releaseFailure = releaseFailure, releaseResult = null, metrics = KiraMetrics(registry))
            val permit = redis.admission.acquire(USER)
            assertEquals(CompletionActivation.ACTIVATED, permit.activate())
            val expected = Any()
            assertSame(expected, permit.use { expected })
            permit.close()
            assertEquals(listOf(4, 1, 1), redis.keyCounts)
            assertEquals(listOf("acquire", "activate", "release"), redis.operations)
            assertEquals(1, redis.tokens.toSet().size)

            val event = appender.list.single()
            assertEquals(logger.name, event.loggerName)
            assertEquals(Level.WARN, event.level)
            assertEquals("Shared completion admission release unconfirmed", event.formattedMessage)
            assertNull(event.argumentArray)
            assertNull(event.throwableProxy)

            val counter = registry.get("kira.completion.admission.events").tag("outcome", "release_unconfirmed").counter()
            assertEquals(1.0, counter.count())
            assertEquals(listOf(Tag.of("outcome", "release_unconfirmed")), counter.id.tags)
            assertNull(registry.find("kira.completion.admission.events").tag("outcome", "coordination_unavailable").counter())
            assertEquals(1, registry.meters.size)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
            registry.close()
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [0, 1])
    fun `each permit releases only its own UUID once for absent or removed acknowledgements`(reply: Long) {
        val redis = RedisFixture(releaseResult = reply)
        val first = redis.admission.acquire(USER)
        val second = redis.admission.acquire(USER)
        first.close()
        first.close()
        second.close()
        second.close()
        assertEquals(listOf(4, 4, 1, 1), redis.keyCounts)
        assertNotEquals(redis.tokens[0], redis.tokens[1])
        assertEquals(redis.tokens.take(2), redis.tokens.drop(2))
        redis.tokens.forEach { assertEquals(4, UUID.fromString(it).version()) }
    }

    @Test
    fun `invalid release acknowledgements and known decode failures preserve the outcome without retry`() {
        listOf(null, -1L, 2L, 0, "0", listOf(1L), SerializationException("private detail"), ClassCastException()).forEach { reply ->
            val registry = SimpleMeterRegistry()
            try {
                val redis = RedisFixture(releaseResult = reply, metrics = KiraMetrics(registry))
                val permit = redis.admission.acquire(USER)
                assertEquals(CompletionActivation.ACTIVATED, permit.activate())
                val expected = Any()
                assertSame(expected, permit.use { expected })
                permit.close()
                assertEquals(listOf("acquire", "activate", "release"), redis.operations)
                assertEquals(1, redis.tokens.toSet().size)
                assertEquals(1.0, registry.get("kira.completion.admission.events").tag("outcome", "release_unconfirmed").counter().count())
                assertNull(registry.find("kira.completion.admission.events").tag("outcome", "coordination_unavailable").counter())
            } finally {
                registry.close()
            }
        }
    }

    @Test
    fun `activation denies expired or indeterminate replies without speculative cleanup`() {
        val replies = listOf(
            0L, null, -1L, 2L, Long.MAX_VALUE, 1, "1", listOf(1L),
            DataAccessResourceFailureException("private Redis connection detail"),
            SerializationException("private Redis connection detail"), ClassCastException(),
        )
        replies.forEach { reply ->
            val redis = RedisFixture(activationResult = reply)
            val permit = redis.admission.acquire(USER)
            assertEquals(if (reply == 0L) CompletionActivation.EXPIRED else CompletionActivation.UNAVAILABLE, permit.activate())
            assertEquals(listOf("acquire", "activate"), redis.operations)
            assertEquals(1, redis.tokens.toSet().size)
            permit.close() // Owed owner cleanup, not compensation during a failed activation.
            permit.close()
            assertEquals(listOf("acquire", "activate", "release"), redis.operations)
        }
    }

    @Test
    fun `unrelated Redis programming failure is not swallowed as unconfirmed cleanup`() {
        val sentinel = UnsupportedOperationException("synthetic programming failure")
        val redis = RedisFixture(releaseResult = sentinel)
        val permit = redis.admission.acquire(USER)
        assertSame(sentinel, assertThrows<UnsupportedOperationException> { permit.close() })
        permit.close()
        assertEquals(listOf("acquire", "release"), redis.operations)
    }

    @Test
    fun `Redis duration and capacity guards run before any Redis traffic`() {
        val redis = mock(StringRedisTemplate::class.java)
        val one = Duration.ofMillis(1)
        listOf(
            Duration.ofNanos(1) to one,
            one to Duration.ofNanos(1),
            Duration.ofNanos(1_500_000) to one,
            one to Duration.ofNanos(1_500_000),
            Duration.ofSeconds(Long.MAX_VALUE) to one,
            Duration.ofMillis(Long.MAX_VALUE) to one,
            Duration.ofMillis(Long.MAX_VALUE / 2) to one,
            Duration.ofMillis(9_007_199_254_740_991L / 2) to one,
        ).forEach { (queue, provider) ->
            assertThrows<IllegalArgumentException> {
                RedisCompletionAdmission(redis, properties().copy(queueTimeout = queue, timeout = provider))
            }
        }
        listOf(0, 4_097).forEach { capacity ->
            assertThrows<IllegalArgumentException> { RedisCompletionAdmission(redis, properties().copy(globalConcurrency = capacity)) }
        }
        RedisCompletionAdmission(redis, properties().copy(globalConcurrency = 4_096))
        InMemoryCompletionAdmission(properties().copy(globalConcurrency = 4_097), Clock.systemUTC()) // Redis-only bound; no clamp.
        verifyNoInteractions(redis)
    }

    private class Endpoint(admission: CompletionAdmission) {
        private val controller = AdmissionController(admission)
        private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(GlobalExceptionHandler()).build()

        fun rejects(expectedStatus: Int, code: String, retry: Long) {
            val response = mvc.post("/test/completion-admission").andExpect {
                status { isEqualTo(expectedStatus) }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.status") { value(expectedStatus) }
                jsonPath("$.errors[0].code") { value(code) }
                header { string("Retry-After", retry.toString()) }
            }.andReturn().response
            assertEquals(0, controller.admitted)
            assertFalse(response.contentAsString.contains("private Redis connection detail"))
        }

        fun accepts() {
            mvc.post("/test/completion-admission").andExpect {
                status { isOk() }
                jsonPath("$.admitted") { value(true) }
                header { doesNotExist("Retry-After") }
            }
            assertEquals(1, controller.admitted)
        }
    }

    private class RedisFixture(
        result: Any? = 0L,
        acquireFailure: Boolean = false,
        releaseFailure: Boolean = false,
        releaseResult: Any? = 0L,
        activationResult: Any? = 1L,
        metrics: KiraMetrics? = null,
    ) {
        val keyCounts = mutableListOf<Int>()
        val operations = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        private val acquired = mutableSetOf<String>()
        private val redis = mock(StringRedisTemplate::class.java) { call ->
            if (call.method.name == "execute") {
                val keys = call.getArgument<List<String>>(1)
                val args = call.rawArguments[2] as Array<*>
                val operation = args[0] as String
                val token = args[1] as String
                keyCounts += keys.size
                operations += operation
                tokens += token
                val reply = when (operation) {
                    "acquire" -> {
                        assertEquals(4, keys.size)
                        assertTrue(acquired.add(token))
                        if (acquireFailure) throw DataAccessResourceFailureException("private Redis connection detail")
                        result
                    }

                    "activate" -> {
                        assertEquals(1, keys.size)
                        assertTrue(token in acquired)
                        activationResult
                    }

                    "release" -> {
                        assertEquals(1, keys.size)
                        assertTrue(token in acquired)
                        if (releaseFailure) throw DataAccessResourceFailureException("private Redis connection detail")
                        releaseResult
                    }

                    else -> error("Unexpected Redis admission operation")
                }
                if (reply is RuntimeException) throw reply
                reply
            } else {
                Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        val admission = RedisCompletionAdmission(redis, properties(), metrics)
    }

    @RestController
    private class AdmissionController(private val admission: CompletionAdmission) {
        var admitted = 0

        @PostMapping("/test/completion-admission")
        fun acquire(): Map<String, Boolean> = admission.acquire(USER).use { permit ->
            check(permit.activate() == CompletionActivation.ACTIVATED)
            admitted += 1
            mapOf("admitted" to true)
        }
    }

    private companion object {
        val USER: UUID = UUID.fromString("9704d18b-978d-4d0b-898b-63d81e8c8a69")

        fun properties() = KiraCompletionProperties(perUserPerMinute = 0, globalPerMinute = 0, perUserDailyQuota = 0)
    }
}
