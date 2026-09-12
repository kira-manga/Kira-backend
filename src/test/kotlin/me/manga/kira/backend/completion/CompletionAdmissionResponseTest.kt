package me.manga.kira.backend.completion

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.manga.kira.backend.common.GlobalExceptionHandler
import me.manga.kira.backend.completion.application.CompletionAdmission
import me.manga.kira.backend.completion.application.InMemoryCompletionAdmission
import me.manga.kira.backend.completion.application.RedisCompletionAdmission
import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.observability.KiraMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
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
    fun `Redis zero alone admits and releases once`() {
        val redis = RedisFixture()
        Endpoint(redis.admission).accepts()
        assertEquals(listOf(4, 1), redis.keyCounts)
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
            val expected = Any()
            assertSame(expected, permit.use { expected })
            permit.close()
            assertEquals(listOf(4, 1), redis.keyCounts)

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
        result: Long? = 0L,
        acquireFailure: Boolean = false,
        releaseFailure: Boolean = false,
        releaseResult: Long? = 0L,
        metrics: KiraMetrics? = null,
    ) {
        val keyCounts = mutableListOf<Int>()
        private val redis = mock(StringRedisTemplate::class.java) { call ->
            if (call.method.name == "execute") {
                val count = call.getArgument<List<String>>(1).size
                keyCounts.add(count)
                val shouldFail = when (count) {
                    4 -> acquireFailure
                    1 -> releaseFailure
                    else -> false
                }
                if (shouldFail) {
                    throw DataAccessResourceFailureException("private Redis connection detail")
                }
                if (count == 4) result else releaseResult
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
        fun acquire(): Map<String, Boolean> = admission.acquire(USER).use {
            admitted += 1
            mapOf("admitted" to true)
        }
    }

    private companion object {
        val USER: UUID = UUID.fromString("9704d18b-978d-4d0b-898b-63d81e8c8a69")

        fun properties() = KiraCompletionProperties(perUserPerMinute = 0, globalPerMinute = 0, perUserDailyQuota = 0)
    }
}
