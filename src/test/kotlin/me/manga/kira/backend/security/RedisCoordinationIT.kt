package me.manga.kira.backend.security

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.exception.ServiceUnavailableException
import me.manga.kira.backend.common.exception.TooManyRequestsException
import me.manga.kira.backend.completion.application.RedisCompletionAdmission
import me.manga.kira.backend.config.KiraCompletionProperties
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.observability.KiraMetrics
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

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
        first.beginLoginAttempt("reader@example.com", "192.0.2.1").complete(false)
        first.beginLoginAttempt("reader@example.com", "192.0.2.1").complete(false)

        assertThrows<TooManyRequestsException> { second.beginLoginAttempt("reader@example.com", "192.0.2.1") }
    }

    @Test
    fun `two instances bound the real same-identity login wave before releasing hashes`() = wave(AuthWavePath.LOGIN)

    @Test
    fun `two instances bound the real decoy IP spray wave before releasing hashes`() = wave(AuthWavePath.SPRAY)

    @Test
    fun `two instances bound the real admin step-up wave before releasing hashes`() = wave(AuthWavePath.STEP_UP)

    @Test
    fun `success cannot erase other live attempts or later failures across instances`() {
        val (first, second) = services(KiraSecurityProperties.Throttle(loginFailureThreshold = 2, loginIpFailureThreshold = 100))
        val success = first.beginLoginAttempt(EMAIL, IP)
        val failure = second.beginLoginAttempt(EMAIL, IP)
        success.complete(true)
        first.beginLoginAttempt(EMAIL, IP).use { held ->
            assertThrows<TooManyRequestsException> { second.beginLoginAttempt(EMAIL, IP) }
            failure.complete(false)
            assertThrows<TooManyRequestsException> { second.beginLoginAttempt(EMAIL, IP) } // One completed failure + held slot.
            held.complete(true)
        }
        second.beginLoginAttempt(EMAIL, IP).complete(true)
    }

    @Test
    fun `earlier failure resets only for identity and IP history is not refreshed by success`() {
        val (first, second) = services(KiraSecurityProperties.Throttle(loginFailureThreshold = 2, loginIpFailureThreshold = 3))
        val failure = first.beginLoginAttempt(EMAIL, IP)
        val success = second.beginLoginAttempt(EMAIL, IP)
        failure.complete(false)
        val lastFailure = field(ipKey(), "lastFailure")
        success.complete(true)
        assertEquals(lastFailure, field(ipKey(), "lastFailure"))
        assertEquals("1", field(ipKey(), "failures"))
        assertEquals("0", field(identityKey(), "failures"))
        val held = first.beginLoginAttempt(EMAIL, IP)
        second.beginLoginAttempt(EMAIL, IP).use { other ->
            assertThrows<TooManyRequestsException> { first.beginLoginAttempt("other@example.com", IP) }
            held.complete(true)
            other.complete(true)
        }
    }

    @Test
    fun `Redis history expiry preserves a still-live reservation`() {
        val (first, second) = services(
            KiraSecurityProperties.Throttle(loginIpFailureThreshold = 2, loginFailureWindow = Duration.ofMillis(300)),
        )
        first.beginLoginAttempt(EMAIL, IP).complete(false)
        val held = second.beginLoginAttempt("held@example.com", IP)
        assertThrows<TooManyRequestsException> { first.beginLoginAttempt("before-expiry@example.com", IP) }
        Thread.sleep(400) // Real Redis TIME, not an application Clock; held uses the default 30s lease.
        first.beginLoginAttempt("after-expiry@example.com", IP).use { fresh ->
            assertThrows<TooManyRequestsException> { second.beginLoginAttempt("third@example.com", IP) }
            held.complete(true)
            fresh.complete(true)
        }
    }

    @Test
    fun `Redis breaches reset counts and double block duration until idle history expires`() {
        val (first, second) = services(
            KiraSecurityProperties.Throttle(
                loginFailureThreshold = 1,
                loginIpFailureThreshold = 100,
                loginInitialBlock = Duration.ofMillis(300),
                loginMaxBlock = Duration.ofMillis(600),
                loginFailureWindow = Duration.ofSeconds(2),
                loginAttemptTtl = Duration.ofMillis(200),
            ),
        )
        listOf(300L, 600L, 600L).forEach { duration ->
            first.beginLoginAttempt(EMAIL, IP).complete(false)
            assertEquals("0", field(identityKey(), "failures"))
            assertEquals(duration, field(identityKey(), "blockedUntil")!!.toLong() - field(identityKey(), "lastFailure")!!.toLong())
            assertThrows<TooManyRequestsException> { second.beginLoginAttempt(EMAIL, IP) }
            Thread.sleep(duration + 30)
        }
        awaitExpired(identityKey())
        second.beginLoginAttempt(EMAIL, IP).complete(false)
        assertEquals(300L, field(identityKey(), "blockedUntil")!!.toLong() - field(identityKey(), "lastFailure")!!.toLong())
    }

    @Test
    fun `expired and duplicate completions cannot touch a successor reservation`() {
        val (first, second) = services(
            KiraSecurityProperties.Throttle(loginFailureThreshold = 1, loginAttemptTtl = Duration.ofSeconds(1)),
        )
        val stale = first.beginLoginAttempt(EMAIL, IP)
        assertTrue(ttl("${identityKey()}:attempts") in 1L..1_000L)
        awaitExpired("${identityKey()}:attempts", "${ipKey()}:attempts")
        val successor = second.beginLoginAttempt(EMAIL, IP)
        assertThrows<TooManyRequestsException> { stale.complete(true) }
        assertThrows<TooManyRequestsException> { first.beginLoginAttempt(EMAIL, IP) }
        successor.complete(false)
        stale.close()
        assertThrows<TooManyRequestsException> { successor.complete(true) }
        assertEquals("0", field(identityKey(), "failures"))
        assertThrows<TooManyRequestsException> { first.beginLoginAttempt(EMAIL, IP) }
    }

    @Test
    fun `completion fences both token sets before mutating either dimension`() {
        val (first, second) = services(KiraSecurityProperties.Throttle(loginFailureThreshold = 1))
        val held = first.beginLoginAttempt(EMAIL, IP)
        val identityTokens = "${identityKey()}:attempts"
        val token = template.opsForZSet().range(identityTokens, 0, 0)!!.single()
        template.opsForZSet().remove("${ipKey()}:attempts", token) // Simulated partial transient-state loss, fixture only.
        assertThrows<TooManyRequestsException> { held.complete(true) }
        assertEquals(setOf(token), template.opsForZSet().range(identityTokens, 0, -1))
        assertEquals("0", field(identityKey(), "failures"))
        assertThrows<TooManyRequestsException> { second.beginLoginAttempt(EMAIL, IP) }
    }

    @Test
    fun `malformed second dimension denies admission before reserving the first`() {
        val (first) = services(KiraSecurityProperties.Throttle())
        template.opsForValue().set(ipKey(), "wrong-type") // Fixture-only corruption; no first-dimension write is permitted.
        val error = assertThrows<TooManyRequestsException> { first.beginLoginAttempt(EMAIL, IP) }
        assertEquals("AUTH_THROTTLE_UNAVAILABLE", error.code)
        assertFalse(exists(identityKey()))
        assertFalse(exists("${identityKey()}:attempts"))
        assertFalse(exists(INDEX))
    }

    @Test
    fun `capacity preflight neither partially reserves nor evicts an insufficient eligible victim`() {
        val (first, second) = services(
            KiraSecurityProperties.Throttle(maxEntries = 3, registrationMaxPerWindow = 2, loginIpFailureThreshold = 2),
        )
        first.checkRegistrationAllowed("192.0.2.2")
        val held = first.beginLoginAttempt(EMAIL, IP)
        assertThrows<TooManyRequestsException> { second.beginLoginAttempt("other@example.com", "192.0.2.3") }
        assertFalse(exists(identityKey("other@example.com", "192.0.2.3")))
        second.checkRegistrationAllowed("192.0.2.2")
        assertThrows<TooManyRequestsException> { first.checkRegistrationAllowed("192.0.2.2") }
        assertThrows<TooManyRequestsException> { second.beginLoginAttempt("other@example.com", IP) }
        held.complete(true)
        assertEquals(3L, template.opsForZSet().zCard(INDEX))
    }

    @Test
    fun `short registration horizons never expire index or evict live login and longer block protection`() {
        val (first, second) = services(
            KiraSecurityProperties.Throttle(
                maxEntries = 3, loginFailureThreshold = 1, loginIpFailureThreshold = 1,
                loginAttemptTtl = Duration.ofSeconds(2), loginFailureWindow = Duration.ofMillis(100),
                loginInitialBlock = Duration.ofSeconds(2), loginMaxBlock = Duration.ofSeconds(4),
                registrationMaxPerWindow = 1, registrationWindow = Duration.ofMillis(150),
            ),
        )
        val held = first.beginLoginAttempt(EMAIL, IP)
        first.checkRegistrationAllowed("192.0.2.2")
        assertTrue(ttl(INDEX) > 150)
        assertTrue(ttl(identityKey()) > 150)
        assertThrows<TooManyRequestsException> { second.checkRegistrationAllowed("192.0.2.3") }
        awaitExpired(registrationKey("192.0.2.2"))
        assertTrue(exists(INDEX))
        assertTrue(exists("${identityKey()}:attempts"))
        held.complete(false)
        assertFalse(exists("${identityKey()}:attempts"))
        second.checkRegistrationAllowed("192.0.2.3")
        awaitExpired(registrationKey("192.0.2.3"))
        assertTrue(exists(INDEX))
        assertTrue(exists(identityKey())) // Block outlives the 100ms failure window.
        first.checkRegistrationAllowed("192.0.2.4")
        assertThrows<TooManyRequestsException> { second.beginLoginAttempt(EMAIL, IP) }
        assertEquals(3L, template.opsForZSet().zCard(INDEX))
        assertEquals("0", field(identityKey(), "failures"))
    }

    private fun wave(path: AuthWavePath) {
        val config = KiraSecurityProperties.Throttle(
            loginFailureThreshold = if (path == AuthWavePath.SPRAY) 5 else 2,
            loginIpFailureThreshold = if (path == AuthWavePath.SPRAY) 2 else 100,
        )
        assertAuthAdmissionWave(services(config), path)
    }

    private fun services(config: KiraSecurityProperties.Throttle): List<RedisAuthThrottleService> {
        val properties = KiraSecurityProperties(throttle = config.copy(backend = "redis", instanceCount = 2))
        return listOf(RedisAuthThrottleService(template, properties), RedisAuthThrottleService(template, properties))
    }

    private fun exists(key: String): Boolean = requireNotNull(template.hasKey(key))

    private fun ttl(key: String): Long = requireNotNull(template.getExpire(key, TimeUnit.MILLISECONDS))

    private fun field(key: String, name: String): String? = template.opsForHash<String, String>().get(key, name)

    private fun identityKey(email: String = EMAIL, ip: String = IP): String = "$PREFIX:login:identity:${Sha256.hexUtf8("$email|$ip")}"

    private fun ipKey(): String = "$PREFIX:login:ip:${Sha256.hexUtf8(IP)}"

    private fun registrationKey(ip: String): String = "$PREFIX:registration:ip:${Sha256.hexUtf8(ip)}"

    private fun awaitExpired(vararg keys: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (keys.any { exists(it) } && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(keys.none { exists(it) }, "Redis TTL expiry completed within the bounded wait")
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
        assertThrows<ServiceUnavailableException> {
            RedisCompletionAdmission(template, concurrencyProperties).acquire(UUID.randomUUID())
        }
        held.close()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `later completion closes do not repeat an unconfirmed application release`(applyBeforeFailure: Boolean) {
        val properties = KiraCompletionProperties(
            coordinationBackend = "redis",
            instanceCount = 2,
            perUserPerMinute = 0,
            globalPerMinute = 0,
            perUserDailyQuota = 0,
            globalConcurrency = 1,
            queueTimeout = Duration.ofMillis(500),
            timeout = Duration.ofMillis(500),
        )
        val maximumTtl = (properties.queueTimeout + properties.timeout).multipliedBy(2).toMillis()
        assertEquals(2_000L, maximumTtl)
        val healthy = RedisCompletionAdmission(template, properties)
        val fault = CompletionReleaseFault(applyBeforeFailure)
        val registry = SimpleMeterRegistry()
        try {
            val first = RedisCompletionAdmission(fault.redis, properties, KiraMetrics(registry)).acquire(UUID.randomUUID())
            val outcome = first.use {
                assertEquals("1", template.opsForValue().get(COMPLETION_KEY))
                assertTrue(ttl(COMPLETION_KEY) in 1L..maximumTtl)
                "normal outcome"
            }
            assertEquals("normal outcome", outcome)
            assertEquals(listOf(4, 1), fault.keyCounts)
            assertEquals(if (applyBeforeFailure) 1 else 0, fault.forwardedReleases)
            assertEquals(1.0, registry.get("kira.completion.admission.events").tag("outcome", "release_unconfirmed").counter().count())

            if (applyBeforeFailure) {
                assertFalse(exists(COMPLETION_KEY), "The actual release Lua freed the counter before the injected failure")
            } else {
                assertEquals("1", template.opsForValue().get(COMPLETION_KEY))
                assertTrue(ttl(COMPLETION_KEY) in 1L..maximumTtl)
                assertCompletionCapacityDenied(healthy)
                awaitExpired(COMPLETION_KEY) // Observe production TTL expiry; never force EXPIRE/DEL.
                assertEquals(-2L, ttl(COMPLETION_KEY))
            }

            healthy.acquire(UUID.randomUUID()).use {
                assertEquals("1", template.opsForValue().get(COMPLETION_KEY))
                assertTrue(ttl(COMPLETION_KEY) in 1L..maximumTtl)
                // Probe repeated application closes only while healthy successor B demonstrably owns capacity.
                repeat(2) { first.close() }
                assertEquals(listOf(4, 1), fault.keyCounts)
                assertEquals(if (applyBeforeFailure) 1 else 0, fault.forwardedReleases)
                assertEquals("1", template.opsForValue().get(COMPLETION_KEY))
                assertTrue(ttl(COMPLETION_KEY) in 1L..maximumTtl)
                assertCompletionCapacityDenied(healthy)
                assertEquals("1", template.opsForValue().get(COMPLETION_KEY))
                assertEquals(1.0, registry.get("kira.completion.admission.events").tag("outcome", "release_unconfirmed").counter().count())
            }
            assertFalse(exists(COMPLETION_KEY)) // B's healthy close performs normal owned cleanup.
        } finally {
            registry.close()
        }
    }

    private fun assertCompletionCapacityDenied(admission: RedisCompletionAdmission) {
        val failure = assertThrows<ServiceUnavailableException> { admission.acquire(UUID.randomUUID()) }
        assertEquals(503, failure.status.value())
        assertEquals("COMPLETION_CONCURRENCY_LIMIT", failure.code)
        assertEquals(1L, failure.retryAfterSeconds)
    }

    /** Models two transport outcomes, not Lettuce reconnect/replay or delayed wire execution. */
    private class CompletionReleaseFault(private val applyBeforeFailure: Boolean) {
        val keyCounts = mutableListOf<Int>()
        var forwardedReleases = 0
            private set
        val redis: StringRedisTemplate = mock(StringRedisTemplate::class.java) { call ->
            if (call.method.name == "execute") {
                val script = call.getArgument<RedisScript<Long>>(0)
                val keys = call.getArgument<List<String>>(1)
                val args = call.arguments.drop(2).toTypedArray()
                keyCounts.add(keys.size)
                when (keys.size) {
                    4 -> template.execute(script, keys, *args)

                    1 -> {
                        if (applyBeforeFailure) {
                            forwardedReleases += 1
                            assertEquals(0L, template.execute(script, keys, *args))
                        }
                        throw DataAccessResourceFailureException("private Redis release detail")
                    }

                    else -> error("Unexpected Redis admission key count")
                }
            } else {
                Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
    }

    companion object {
        private const val COMPLETION_KEY = "kira:completion-admission:concurrency"
        private const val PREFIX = "kira:auth-throttle:v2"
        private const val INDEX = "$PREFIX:index"
        private const val EMAIL = "reader@example.com"
        private const val IP = "192.0.2.1"
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
