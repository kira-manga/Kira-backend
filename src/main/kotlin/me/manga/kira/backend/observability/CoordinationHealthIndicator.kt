package me.manga.kira.backend.observability

import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.config.KiraSecurityProperties
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/** Readiness dependency that follows the configured topology instead of probing unused Redis. */
@Component("coordinationHealthIndicator")
class CoordinationHealthIndicator(
    private val security: KiraSecurityProperties,
    private val completion: KiraCompletionProperties,
    private val redis: StringRedisTemplate,
) : HealthIndicator {
    override fun health(): Health {
        if (!requiresRedis()) return Health.up().withDetail("mode", "memory").build()
        val available =
            runCatching {
                val connectionFactory = requireNotNull(redis.connectionFactory)
                connectionFactory.connection.use { connection -> connection.ping() == "PONG" }
            }.getOrDefault(false)
        return if (available) {
            Health.up().withDetail("mode", "redis").build()
        } else {
            Health.down().withDetail("mode", "redis").build()
        }
    }

    private fun requiresRedis(): Boolean = security.throttle.backend == "redis" ||
        (completion.enabled && completion.coordinationBackend == "redis")
}
