package me.manga.kira.backend.security

import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.completion.application.RedisCompletionAdmission
import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.config.KiraSecurityProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.core.StringRedisTemplate
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

        val error = assertThrows<TooManyRequestsException> { service.checkLoginAllowed("reader@example.com", "192.0.2.1") }

        assertEquals("AUTH_THROTTLE_UNAVAILABLE", error.code)
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
