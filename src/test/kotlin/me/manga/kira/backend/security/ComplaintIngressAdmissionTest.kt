package me.manga.kira.backend.security

import jakarta.servlet.DispatcherType
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.config.KiraSecurityProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.security.ProviderException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Direct counter calls below test enforcement only; the real coordinator is exercised by the PG probe. */
class ComplaintIngressAdmissionTest {
    @Test
    fun `HTTP bridge holds one ingress through wrappers semantic work and response delivery`() {
        val guard = newGuard(MutableAdmissionTestClock(), admissionTestPolicy(concurrent = 1, ingressRate = 1))
        val bridge = ComplaintHttpIngressBridge(guard)
        val input = request().apply { method = "GET"; requestURI = ComplaintInstallationRoutes.ME }
        lateinit var context: ComplaintIngressContext
        val response = object : MockHttpServletResponse() {
            override fun getOutputStream(): ServletOutputStream {
                guard.requireLiveContext(context)
                return super.getOutputStream()
            }
        }
        bridge.doFilter(input, response) { admitted, output ->
            val wrapped = HttpServletRequestWrapper(admitted as HttpServletRequest)
            context = bridge.authenticationContext(wrapped)
            assertSame(context, bridge.claimHandler(wrapped))
            guard.startOwnerHistory(context)
            val identity = Any()
            guard.chargeOwnerHistory(context, admissionTestActor(1), identity)
            guard.consumeOwnerHistory(context, identity)
            output.outputStream.write(byteArrayOf(1, 2, 3))
        }
        assertEquals(listOf<Byte>(1, 2, 3), response.contentAsByteArray.toList())
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.requireLiveContext(context) }
        val limited = MockHttpServletResponse()
        bridge.doFilter(input, limited) { _, _ -> error("A second request must pay ingress again") }
        assertEquals(429, limited.status)
        guard.withIngress(request("192.0.2.2")) {} // Response completion released the only reservation.
    }

    @Test
    fun `HTTP bridge refuses foreign rewritten redispatched threaded and escaped frame reuse`() {
        val guard = newGuard(MutableAdmissionTestClock())
        val bridge = ComplaintHttpIngressBridge(guard)
        val other = ComplaintHttpIngressBridge(guard)
        val input = request().apply { method = "GET"; requestURI = ComplaintInstallationRoutes.ME }
        val executor = Executors.newSingleThreadExecutor()
        try {
            bridge.doFilter(input, MockHttpServletResponse()) { admitted, _ ->
                val http = admitted as HttpServletRequest
                assertThrows(ComplaintSecurityRejected::class.java) { other.authenticationContext(http) }
                assertThrows(ComplaintSecurityRejected::class.java) { bridge.authenticationContext(request()) }
                val rewritten = object : HttpServletRequestWrapper(http) {
                    override fun getRequestURI(): String = "/api/v1/auth/me"
                }
                assertThrows(ComplaintSecurityRejected::class.java) { bridge.authenticationContext(rewritten) }
                executor.submit { assertThrows(ComplaintSecurityRejected::class.java) { bridge.authenticationContext(http) } }.get(5, TimeUnit.SECONDS)
                assertThrows(ComplaintSecurityRejected::class.java) { http.startAsync() }
                input.dispatcherType = DispatcherType.FORWARD
                assertThrows(ComplaintSecurityRejected::class.java) { bridge.claimHandler(http) }
                input.dispatcherType = DispatcherType.REQUEST
                bridge.claimHandler(http)
                assertThrows(ComplaintSecurityRejected::class.java) { bridge.claimHandler(http) }
                assertThrows(ComplaintSecurityRejected::class.java) { bridge.authenticationContext(http) }
                assertThrows(ComplaintSecurityRejected::class.java) { bridge.doFilter(input, MockHttpServletResponse()) { _, _ -> } }
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertThrows(ComplaintSecurityRejected::class.java) { bridge.authenticationContext(input) }
    }

    @Test
    fun `HTTP bridge never appends a problem after buffered delivery or leaks its reservation on failure`() {
        for (failure in listOf(IOException("synthetic"), ComplaintSecurityRejected(ComplaintSecurityFailure.UNAVAILABLE))) {
            val guard = newGuard(MutableAdmissionTestClock(), admissionTestPolicy(concurrent = 1))
            val bridge = ComplaintHttpIngressBridge(guard)
            val input = request().apply { method = "GET"; requestURI = ComplaintInstallationRoutes.ME }
            val response = MockHttpServletResponse()
            assertThrows(IOException::class.java) {
                bridge.doFilter(input, response) { _, output ->
                    output.outputStream.write("prefix".toByteArray())
                    throw failure
                }
            }
            assertEquals("prefix", response.contentAsString)
            guard.withIngress(input) {}
            assertThrows(ComplaintSecurityRejected::class.java) { bridge.authenticationContext(input) }
        }
    }

    @Test
    fun `ingress enforces exact IP rate and 2048 physical bucket ceiling without live eviction`() {
        val clock = MutableAdmissionTestClock()
        val guard = newGuard(clock)
        repeat(120) { guard.withIngress(request()) {} }
        val denied = admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { guard.withIngress(request()) { error("must not execute") } }
        assertEquals(60L, denied.retryAfterSeconds)
        clock.value = ComplaintAdmissionPolicy.INGRESS_WINDOW_NANOS
        guard.withIngress(request()) {}
        val cardinality = newGuard(MutableAdmissionTestClock())
        repeat(2048) { index -> cardinality.withIngress(request("10.${index / 256}.${index % 256}.1")) {} }
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { cardinality.withIngress(request("11.0.0.1")) { error("must not execute") } }
        cardinality.withIngress(request("10.0.0.1")) {}
    }

    @Test
    fun `ingress idle expiry and bounded backlog fail closed until the exact batch has drained`() {
        val clock = MutableAdmissionTestClock()
        val guard = newGuard(clock, admissionTestPolicy(ingressBuckets = 3, prune = 1))
        (1..3).forEach { guard.withIngress(request("192.0.2.$it")) {} }
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { guard.withIngress(request("192.0.2.4")) {} }
        clock.value = ComplaintAdmissionPolicy.INGRESS_IDLE_NANOS
        repeat(2) { admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { guard.withIngress(request("192.0.2.4")) {} } }
        guard.withIngress(request("192.0.2.4")) {}
    }

    @Test
    fun `64 concurrent ingress operations exclude the 65th and release every reserved slot`() {
        val guard = newGuard(MutableAdmissionTestClock())
        val entered = CountDownLatch(64)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(64)
        try {
            val futures = (1..64).map { index ->
                executor.submit {
                    guard.withIngress(request("192.0.2.$index")) {
                        entered.countDown()
                        assertTrue(release.await(15, TimeUnit.SECONDS))
                    }
                }
            }
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { guard.withIngress(request("192.0.2.65")) { error("must not execute") } }
            release.countDown()
            futures.forEach { it.get(15, TimeUnit.SECONDS) }
            guard.withIngress(request("192.0.2.65")) {}
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `contexts cannot escape cross owner thread nest or survive exceptional return`() {
        val clock = MutableAdmissionTestClock()
        val guard = newGuard(clock, admissionTestPolicy(concurrent = 1))
        val other = newGuard(clock)
        var captured: ComplaintIngressContext? = null
        val executor = Executors.newSingleThreadExecutor()
        try {
            guard.withIngress(request()) { context ->
                captured = context
                admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.startSession(ComplaintIngressContext()) }
                admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { other.startSession(context) }
                admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.withIngress(request()) {} }
                admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { other.withIngress(request()) {} }
                executor.submit {
                    admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.startSession(context) }
                }.get(5, TimeUnit.SECONDS)
                guard.startSession(context)
                admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.startSession(context) }
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.startSession(checkNotNull(captured)) }
        assertThrows(UnsupportedOperationException::class.java) { guard.withIngress(request()) { throw UnsupportedOperationException("synthetic") } }
        guard.withIngress(request()) {}
    }

    @Test
    fun `admission identity is one use same context and strictly younger than five seconds`() {
        val clock = MutableAdmissionTestClock()
        val guard = newGuard(clock)
        guard.withIngress(request()) { context ->
            guard.startSession(context)
            val identity = Any()
            guard.chargeSession(context, admissionTestActor(1), identity)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.consumeSession(context, Any()) }
            clock.value = ComplaintAdmissionPolicy.ADMISSION_LIFETIME_NANOS - 1
            guard.consumeSession(context, identity)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.consumeSession(context, identity) }
        }
        guard.withIngress(request()) { context ->
            guard.startSession(context)
            val identity = Any()
            guard.chargeSession(context, admissionTestActor(2), identity)
            clock.value += ComplaintAdmissionPolicy.ADMISSION_LIFETIME_NANOS
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.consumeSession(context, identity) }
        }
    }

    @Test
    fun `concurrent session charges are atomic and never admit the 31st actor attempt`() {
        val guard = newGuard(MutableAdmissionTestClock())
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val admitted = AtomicInteger()
        val refused = AtomicInteger()
        try {
            val futures = (1..60).map {
                executor.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    try {
                        counterAttempt(guard, 1)
                        admitted.incrementAndGet()
                    } catch (failure: ComplaintAdmissionRejected) {
                        assertEquals(ComplaintAdmissionFailure.RATE_LIMITED, failure.code)
                        refused.incrementAndGet()
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(15, TimeUnit.SECONDS) }
            assertEquals(30, admitted.get())
            assertEquals(30, refused.get())
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `runtime rotation drains ingress preserves old limits and refuses early previous retirement`() {
        val clock = MutableAdmissionTestClock()
        val guard = newGuard(clock)
        repeat(30) { counterAttempt(guard, 1) }
        guard.withIngress(request()) {
            admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { guard.rotate(admissionTestKey(2)) }
        }
        guard.rotate(admissionTestKey(2))
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { counterAttempt(guard, 1) }
        repeat(30) { counterAttempt(guard, 2) }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { counterAttempt(guard, 2) }
        admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { guard.rotate(admissionTestKey(3)) }
        clock.value = ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS - 1
        admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { guard.retirePrevious() }
        clock.value += 1
        guard.retirePrevious()
        guard.rotate(admissionTestKey(3))
        counterAttempt(guard, 1)
        val restartedClock = MutableAdmissionTestClock(Long.MAX_VALUE / 4)
        val restarted = newGuard(restartedClock, keys = admissionTestKeys(previous = admissionTestKey(2)))
        admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { restarted.retirePrevious() }
        restartedClock.value += ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS
        restarted.retirePrevious()
    }

    @Test
    fun `trusted resolver canonical identity is used and spoofed forwarding cannot reset ingress`() {
        val guard = newGuard(MutableAdmissionTestClock(), admissionTestPolicy(ingressRate = 2))
        repeat(2) { index -> guard.withIngress(request().apply { addHeader("X-Forwarded-For", "192.0.2.${index + 10}") }) {} }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) {
            guard.withIngress(request().apply { addHeader("X-Forwarded-For", "192.0.2.99") }) {}
        }
        val properties = KiraSecurityProperties(trustForwardedHeaders = true, trustedProxies = listOf("10.0.0.0/8"))
        val trusted = newGuard(MutableAdmissionTestClock(), admissionTestPolicy(ingressRate = 1), resolver = ClientIpResolver(properties))
        trusted.withIngress(request("10.0.0.1").apply { addHeader("X-Forwarded-For", "2001:db8::1") }) {}
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) {
            trusted.withIngress(request("10.0.0.1").apply { addHeader("X-Forwarded-For", "2001:0DB8:0:0:0:0:0:1") }) {}
        }
    }

    @Test
    fun `ambient Spring state is refused before ingress or semantic work`() {
        val guard = newGuard(MutableAdmissionTestClock())
        TransactionSynchronizationManager.initSynchronization()
        try {
            assertThrows(PersistencePhaseException::class.java) { guard.withIngress(request()) { error("must not execute") } }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
        guard.withIngress(request()) { context ->
            val resource = Any()
            TransactionSynchronizationManager.bindResource(resource, Any())
            try {
                assertThrows(PersistencePhaseException::class.java) { guard.startSession(context) }
            } finally {
                TransactionSynchronizationManager.unbindResource(resource)
            }
            guard.startSession(context)
        }
    }

    @Test
    fun `clock regression and arithmetic exhaustion latch closed while ordinary nano wrap remains valid`() {
        val clock = MutableAdmissionTestClock()
        val guard = newGuard(clock)
        guard.withIngress(request()) {}
        clock.value = -1
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { guard.withIngress(request()) {} }
        clock.value = 1
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { guard.withIngress(request()) {} }
        val exhaustedClock = MutableAdmissionTestClock()
        val exhausted = newGuard(exhaustedClock)
        exhaustedClock.value = Long.MAX_VALUE - 1
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { exhausted.withIngress(request()) {} }
        val wrappingClock = MutableAdmissionTestClock(Long.MAX_VALUE - 10)
        val wrapping = newGuard(wrappingClock)
        wrappingClock.value = Long.MIN_VALUE + 10
        wrapping.withIngress(request()) {}
        // Synthetic thrown failures exercise closed-state custody without exhausting real heap/provider resources.
        listOf(ProviderException("synthetic"), OutOfMemoryError("synthetic")).forEach { failure ->
            var inject = false
            val failed = newGuard(ComplaintAdmissionNanoClock { if (inject) throw failure else 0L })
            inject = true
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { failed.withIngress(request()) {} }
            inject = false
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { failed.withIngress(request()) {} }
        }
    }

    private fun counterAttempt(guard: ComplaintIngressAdmission, actor: Int) {
        guard.withIngress(request()) { context ->
            guard.startSession(context)
            val identity = Any()
            guard.chargeSession(context, admissionTestActor(actor), identity)
            guard.consumeSession(context, identity)
        }
    }

    private fun request(address: String = "192.0.2.1"): MockHttpServletRequest = MockHttpServletRequest().apply { remoteAddr = address }

    private fun newGuard(
        clock: ComplaintAdmissionNanoClock,
        policy: ComplaintAdmissionPolicy = admissionTestPolicy(),
        keys: ComplaintAdmissionKeyConfiguration = admissionTestKeys(),
        resolver: ClientIpResolver = ClientIpResolver(KiraSecurityProperties()),
    ): ComplaintIngressAdmission = ComplaintIngressAdmission(resolver, policy, keys, clock)
}
