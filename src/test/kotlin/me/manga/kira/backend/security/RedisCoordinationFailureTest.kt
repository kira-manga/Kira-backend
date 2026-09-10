package me.manga.kira.backend.security

import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.completion.application.RedisCompletionAdmission
import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.config.KiraSecurityProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import java.util.UUID

class RedisCoordinationFailureTest {
    private fun unavailableRedis(): StringRedisTemplate = mock(StringRedisTemplate::class.java) {
        throw DataAccessResourceFailureException("injected unavailable Redis")
    }

    @Test
    fun `authentication fails closed when shared Redis is unavailable`() {
        val service =
            RedisAuthThrottleService(
                unavailableRedis(),
                KiraSecurityProperties(throttle = KiraSecurityProperties.Throttle(backend = "redis", instanceCount = 2)),
            )

        val error = assertThrows<TooManyRequestsException> { service.beginLoginAttempt("reader@example.com", "192.0.2.1") }

        assertUnavailable(error)
    }

    @Test
    fun `null malformed and unavailable admission replies never grant a login or registration`() {
        listOf(null, -1L, Long.MAX_VALUE, "malformed", listOf(0L), DataAccessResourceFailureException("injected Redis error")).forEach { reply ->
            val service = service(scriptedRedis { if (reply is RuntimeException) throw reply else reply })
            assertUnavailable(assertThrows<TooManyRequestsException> { service.beginLoginAttempt("reader@example.com", "192.0.2.1") })
            assertUnavailable(assertThrows<TooManyRequestsException> { service.checkRegistrationAllowed("192.0.2.1") })
        }
    }

    @Test
    fun `a missing capacity shortlist denies rather than silently omitting shared eviction checks`() {
        var scripts = 0
        val service = service(scriptedRedis(shortlist = null) {
            scripts += 1
            0L
        })
        assertUnavailable(assertThrows<TooManyRequestsException> { service.beginLoginAttempt("reader@example.com", "192.0.2.1") })
        assertEquals(0, scripts)
    }

    @Test
    fun `null malformed and unavailable completion replies deny both outcomes with no retry on close`() {
        listOf(null, -1L, 2L, "malformed", DataAccessResourceFailureException("injected Redis error")).forEach { reply ->
            listOf(false, true).forEach { success ->
                var scripts = 0
                val service = service(scriptedRedis {
                    scripts += 1
                    if (scripts == 1) 0L else if (reply is RuntimeException) throw reply else reply
                })
                val attempt = service.beginLoginAttempt("reader@example.com", "192.0.2.1")
                assertUnavailable(assertThrows<TooManyRequestsException> { attempt.complete(success) })
                attempt.close()
                assertThrows<TooManyRequestsException> { attempt.complete(true) }
                assertEquals(2, scripts)
            }
        }
    }

    @Test
    fun `unknown or expired completion acknowledgement can never authorize success`() {
        val service = service(scriptedRedis { 0L })
        val expired = service.beginLoginAttempt("reader@example.com", "192.0.2.1")
        assertThrows<TooManyRequestsException> { expired.complete(true) }
        val failed = service.beginLoginAttempt("reader@example.com", "192.0.2.1")
        failed.complete(false) // Expired failure is explicitly harmless, not permission to reuse the handle.
        assertThrows<TooManyRequestsException> { failed.complete(true) }
    }

    private fun service(redis: StringRedisTemplate) = RedisAuthThrottleService(
        redis,
        KiraSecurityProperties(throttle = KiraSecurityProperties.Throttle(backend = "redis", instanceCount = 2)),
    )

    private fun scriptedRedis(
        shortlist: Set<ZSetOperations.TypedTuple<String>>? = emptySet(),
        reply: () -> Any?,
    ): StringRedisTemplate {
        val sortedSets = mock(ZSetOperations::class.java) { invocation ->
            if (invocation.method.name == "rangeWithScores") shortlist else Answers.RETURNS_DEFAULTS.answer(invocation)
        }
        return mock(StringRedisTemplate::class.java) { invocation ->
            when (invocation.method.name) {
                "opsForZSet" -> sortedSets
                "execute" -> reply()
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
    }

    private fun assertUnavailable(error: TooManyRequestsException) {
        assertEquals("AUTH_THROTTLE_UNAVAILABLE", error.code)
        assertEquals(429, error.status.value())
        assertEquals(5L, error.retryAfterSeconds)
    }

    @Test
    fun `completion admission fails closed when shared Redis is unavailable`() {
        val admission =
            RedisCompletionAdmission(
                unavailableRedis(),
                KiraCompletionProperties(coordinationBackend = "redis", instanceCount = 2),
            )

        val error = assertThrows<TooManyRequestsException> { admission.acquire(UUID.randomUUID()) }

        assertEquals("COMPLETION_COORDINATION_UNAVAILABLE", error.code)
    }
}
