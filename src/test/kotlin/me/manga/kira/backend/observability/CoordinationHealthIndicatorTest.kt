package me.manga.kira.backend.observability

import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.config.KiraSecurityProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.actuate.health.Status
import org.springframework.data.redis.core.StringRedisTemplate

class CoordinationHealthIndicatorTest {
    private val redis = mock(StringRedisTemplate::class.java)

    @Test
    fun `single-instance memory topology is ready without Redis`() {
        val indicator =
            CoordinationHealthIndicator(
                KiraSecurityProperties(throttle = KiraSecurityProperties.Throttle(backend = "memory", instanceCount = 1)),
                KiraCompletionProperties(enabled = false),
                redis,
            )

        assertEquals(Status.UP, indicator.health().status)
    }

    @Test
    fun `Redis topology fails readiness closed when Redis is unavailable`() {
        val indicator =
            CoordinationHealthIndicator(
                KiraSecurityProperties(throttle = KiraSecurityProperties.Throttle(backend = "redis", instanceCount = 2)),
                KiraCompletionProperties(enabled = false),
                redis,
            )

        assertEquals(Status.DOWN, indicator.health().status)
    }
}
