package me.manga.kira.backend.completion

import me.manga.kira.backend.common.GlobalExceptionHandler
import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.completion.application.CompletionAdmission
import me.manga.kira.backend.completion.application.InMemoryCompletionAdmission
import me.manga.kira.backend.completion.application.RedisCompletionAdmission
import me.manga.kira.backend.config.KiraCompletionProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
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
    fun `Redis zero alone admits and releases once`() {
        val redis = RedisFixture()
        Endpoint(redis.admission).accepts()
        assertEquals(listOf(4, 1), redis.keyCounts)
    }

    @Test
    fun `release failure keeps its separate current behavior and cannot retry on second close`() {
        val redis = RedisFixture(releaseFailure = true)
        val permit = redis.admission.acquire(USER)
        val failure = assertThrows<TooManyRequestsException> { permit.close() }
        assertEquals("COMPLETION_COORDINATION_UNAVAILABLE", failure.code)
        assertEquals(5L, failure.retryAfterSeconds)
        permit.close()
        assertEquals(listOf(4, 1), redis.keyCounts)
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
    fun `malformed release acknowledgements remain visible failures without retry`() {
        listOf(null, -1L, 2L, 0, "0", listOf(1L), SerializationException("private detail"), ClassCastException()).forEach { reply ->
            val redis = RedisFixture(releaseResult = reply)
            val permit = redis.admission.acquire(USER)
            val failure = assertThrows<TooManyRequestsException> { permit.close() }
            assertEquals(429, failure.status.value())
            assertEquals("COMPLETION_COORDINATION_UNAVAILABLE", failure.code)
            assertEquals(5L, failure.retryAfterSeconds)
            permit.close()
            assertEquals(listOf(4, 1), redis.keyCounts)
        }
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

    private class RedisFixture(result: Any? = 0L, acquireFailure: Boolean = false, releaseFailure: Boolean = false, releaseResult: Any? = 0L) {
        val keyCounts = mutableListOf<Int>()
        val tokens = mutableListOf<String>()
        private val redis = mock(StringRedisTemplate::class.java) { call ->
            if (call.method.name == "execute") {
                val count = call.getArgument<List<String>>(1).size
                keyCounts.add(count)
                tokens.add((call.rawArguments[2] as Array<*>)[1] as String)
                val shouldFail = when (count) {
                    4 -> acquireFailure
                    1 -> releaseFailure
                    else -> false
                }
                if (shouldFail) {
                    throw DataAccessResourceFailureException("private Redis connection detail")
                }
                val reply = if (count == 4) result else releaseResult
                if (reply is RuntimeException) throw reply
                reply
            } else {
                Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
        val admission = RedisCompletionAdmission(redis, properties())
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
