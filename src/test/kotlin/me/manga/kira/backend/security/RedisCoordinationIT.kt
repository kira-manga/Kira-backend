package me.manga.kira.backend.security

import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.completion.application.RedisCompletionAdmission
import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.config.KiraSecurityProperties
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID

class RedisCoordinationIT {
    @BeforeEach
    fun clearRedis() {
        template.connectionFactory?.connection?.serverCommands()?.flushDb()
    }

    @Test
    fun `authentication failures recorded by one instance block another instance`() {
        val properties = KiraSecurityProperties(
            throttle = KiraSecurityProperties.Throttle(
                backend = "redis",
                instanceCount = 2,
                loginFailureThreshold = 2,
                loginIpFailureThreshold = 100,
                loginInitialBlock = Duration.ofMinutes(1),
            ),
        )
        val first = RedisAuthThrottleService(template, properties)
        val second = RedisAuthThrottleService(template, properties)
        first.recordLoginFailure("reader@example.com", "192.0.2.1")
        first.recordLoginFailure("reader@example.com", "192.0.2.1")

        assertThrows<TooManyRequestsException> { second.checkLoginAllowed("reader@example.com", "192.0.2.1") }
    }

    @Test
    fun `completion quota and concurrency are shared by instances`() {
        val quotaProperties = KiraCompletionProperties(
            coordinationBackend = "redis",
            instanceCount = 2,
            perUserPerMinute = 1,
            globalPerMinute = 0,
            perUserDailyQuota = 0,
            globalConcurrency = 10,
        )
        val first = RedisCompletionAdmission(template, quotaProperties)
        val second = RedisCompletionAdmission(template, quotaProperties)
        val user = UUID.randomUUID()
        first.acquire(user).close()
        assertThrows<TooManyRequestsException> { second.acquire(user) }

        clearRedis()
        val concurrencyProperties = quotaProperties.copy(perUserPerMinute = 0, globalConcurrency = 1)
        val held = RedisCompletionAdmission(template, concurrencyProperties).acquire(UUID.randomUUID())
        assertThrows<TooManyRequestsException> {
            RedisCompletionAdmission(template, concurrencyProperties).acquire(UUID.randomUUID())
        }
        held.close()
    }

    companion object {
        private val redis = GenericContainer(DockerImageName.parse("redis:7.4.7-alpine")).withExposedPorts(6379).also { it.start() }
        private val factory = LettuceConnectionFactory(RedisStandaloneConfiguration(redis.host, redis.getMappedPort(6379))).also {
            it.afterPropertiesSet()
            it.start()
        }
        private val template = StringRedisTemplate(factory).also { it.afterPropertiesSet() }

        @JvmStatic
        @AfterAll
        fun shutdown() {
            factory.destroy()
            redis.stop()
        }
    }
}
