package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityBalance
import me.manga.kira.backend.complaint.domain.ComplaintCapacityConfiguration
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDailyAdmission
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.config.KiraSecurityProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockHttpServletRequest
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Counter/configuration tests only. Real locked-row binding and the closed phase are separately exercised on PG. */
class ComplaintBootstrapEnrollmentAdmissionTest {
    @Test
    fun `enrollment global quota is explicit bounded below both durable declarations and disabled cannot activate`() {
        listOf(1, 120).forEach { limit -> ComplaintEnrollmentAdmissionPolicy.Bounded(limit, digest(), 121, 121) }
        listOf(-1, 0, 121, Int.MAX_VALUE).forEach { limit -> invalid { ComplaintEnrollmentAdmissionPolicy.Bounded(limit, digest(), 1000, 1000) } }
        listOf(-1L, 0L, 119L, 120L).forEach { bound ->
            invalid { ComplaintEnrollmentAdmissionPolicy.Bounded(120, digest(), bound, 121) }
            invalid { ComplaintEnrollmentAdmissionPolicy.Bounded(120, digest(), 121, bound) }
        }
        listOf(0, 31, 33).forEach { size -> invalid { ComplaintEnrollmentAdmissionPolicy.Bounded(1, ByteArray(size), 2, 2) } }
        val bytes = digest()
        val policy = ComplaintEnrollmentAdmissionPolicy.Bounded(120, bytes, 121, 121)
        bytes.fill(99)
        val limits = ComplaintCapacityVector.ZERO.with(ComplaintCapacityCounter.INSTALLATION_IDS, 121)
        val ledger = ComplaintCapacityLedger(ComplaintCapacityConfiguration.of(digest(), true), ComplaintCapacityBalance(limits, limits, limits))
        assertTrue(policy.matchesLocked(ledger, ComplaintDailyAdmission(null, 0, 121)))
        assertFalse(policy.matchesLocked(ledger, ComplaintDailyAdmission(null, 0, 122)))
        assertEquals("ComplaintEnrollmentAdmissionPolicy.Bounded(redacted)", policy.toString())
        val disabled = newGuard(MutableAdmissionTestClock(), policy = admissionTestPolicy())
        disabled.withIngress(request()) { context ->
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { disabled.chargeEnrollment(context, Any()) }
        }
        disabled.withIngress(request()) { disabled.chargeBootstrap(it) }
    }

    @Test
    fun `bootstrap admits exactly 120 trusted IP attempts per sliding hour and returns no scope`() {
        val clock = MutableAdmissionTestClock()
        val guard = newGuard(clock)
        repeat(120) { guard.withIngress(request()) { assertEquals(Unit, guard.chargeBootstrap(it)) } }
        clock.value = seconds(61) // Separate ingress minute expires, but the semantic hour has not.
        val failure = admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) {
            guard.withIngress(request().apply { addHeader("X-Forwarded-For", "198.51.100.9") }) { guard.chargeBootstrap(it) }
        }
        assertEquals(3539L, failure.retryAfterSeconds)
        guard.withIngress(request("192.0.2.2")) { guard.chargeBootstrap(it) }
        clock.value = seconds(3600)
        guard.withIngress(request()) { guard.chargeBootstrap(it) }
    }

    @Test
    fun `enrollment IP and global dimensions charge atomically and return the maximum applicable retry`() {
        val clock = MutableAdmissionTestClock()
        val guard = newGuard(clock, global = 12)
        repeat(10) { enroll(guard, "192.0.2.1") }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { enroll(guard, "192.0.2.1") }
        clock.value = seconds(100)
        repeat(2) { enroll(guard, "192.0.2.2") } // The failed IP attempt did not consume a global event.
        assertEquals(3500L, admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { enroll(guard, "192.0.2.3") }.retryAfterSeconds)
        clock.value = seconds(3600)
        repeat(10) { enroll(guard, "192.0.2.3") } // Global refusal did not partially charge this IP.
        assertEquals(3600L, admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { enroll(guard, "192.0.2.3") }.retryAfterSeconds)
        assertEquals(100L, admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { enroll(guard, "192.0.2.4") }.retryAfterSeconds)
    }

    @Test
    fun `all three operations share one physical bucket event and bounded expiry budget`() {
        val events = newGuard(MutableAdmissionTestClock(), policy = quotaPolicy(events = 4))
        events.withIngress(request()) { events.chargeBootstrap(it) } // One event.
        enroll(events) // Two more; IP and global are independent dimensions.
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { session(events) }
        events.withIngress(request()) { events.chargeBootstrap(it) } // Exactly four, no partial failed-session charge.
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { enroll(events) }

        val clock = MutableAdmissionTestClock()
        val buckets = newGuard(clock, policy = quotaPolicy(buckets = 4, prune = 1))
        buckets.withIngress(request()) { buckets.chargeBootstrap(it) }
        enroll(buckets)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { session(buckets) }
        // Only the three semantic buckets expire; the existing ingress has one IP registration.
        clock.value = seconds(3600)
        repeat(2) {
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { buckets.withIngress(request()) { buckets.chargeBootstrap(it) } }
        }
        buckets.withIngress(request()) { buckets.chargeBootstrap(it) }
        enroll(buckets)
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { session(buckets) }
    }

    @Test
    fun `bootstrap enrollment IP and global HMAC frames are distinct fixed vectors without UUID actor input`() {
        val rawKey = admissionTestBytes(1)
        val keys = listOf(ComplaintAdmissionKey("key-1", rawKey))
        val ip = "192.0.2.1".toByteArray(Charsets.US_ASCII)
        val domain = "0000001b6b6972612d636f6d706c61696e742d61646d697373696f6e2d7631"
        val encodedIp = "000000093139322e302e322e31"
        val expectedFrames = listOf(
            domain + "00000002495000000009424f4f545354524150" + encodedIp,
            domain + "0000000249500000000a454e524f4c4c4d454e54" + encodedIp,
            domain + "00000006474c4f42414c0000000a454e524f4c4c4d454e54",
        )
        val actual = listOf(
            ComplaintAdmissionPseudonyms.bootstrapIp(keys, ip).single(),
            ComplaintAdmissionPseudonyms.enrollmentIp(keys, ip).single(),
            ComplaintAdmissionPseudonyms.enrollmentGlobal(keys).single(),
        )
        expectedFrames.forEachIndexed { index, hex ->
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(rawKey, "HmacSHA256")) }
            assertEquals(ComplaintAdmissionBucketKey("key-1", HexFormat.of().formatHex(mac.doFinal(HexFormat.of().parseHex(hex)))), actual[index])
        }
        val existing = listOf(
            ComplaintAdmissionPseudonyms.ingressIp(keys, ip).single(),
            ComplaintAdmissionPseudonyms.sessionIp(keys, ip).single(),
            ComplaintAdmissionPseudonyms.sessionActor(keys, admissionTestActor(1)).single(),
        )
        assertEquals(6, (actual + existing).distinct().size)
    }

    @Test
    fun `rotation preserves bootstrap and global enrollment limits and retains the shared physical overlap cap`() {
        val clock = MutableAdmissionTestClock()
        val guard = newGuard(clock, global = 4)
        repeat(3) { enroll(guard) }
        repeat(120) { guard.withIngress(request("192.0.2.9")) { guard.chargeBootstrap(it) } }
        clock.value = seconds(61)
        guard.rotate(admissionTestKey(2))
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { guard.withIngress(request("192.0.2.9")) { guard.chargeBootstrap(it) } }
        enroll(guard, "192.0.2.2")
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { enroll(guard, "192.0.2.3") }
        admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { guard.retirePrevious() }
        clock.value += ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS
        guard.retirePrevious()
        enroll(guard)
        guard.withIngress(request("192.0.2.9")) { guard.chargeBootstrap(it) }

        val capped = newGuard(MutableAdmissionTestClock(), policy = quotaPolicy(events = 3))
        capped.rotate(admissionTestKey(2))
        admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { enroll(capped) } // Four physical IP/global events, not two.
        capped.withIngress(request()) { capped.chargeBootstrap(it) } // Refusal did not publish half of the enrollment.
    }

    @Test
    fun `one context cannot switch operation escape owner or convert an injected clock into a database handoff`() {
        var reads = 0
        val guard = newGuard(
            ComplaintAdmissionNanoClock {
                reads += 1
                0L
            },
        )
        val other = newGuard(MutableAdmissionTestClock())
        var captured: ComplaintIngressContext? = null
        guard.withIngress(request()) { context ->
            captured = context
            guard.chargeBootstrap(context)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.chargeBootstrap(context) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.chargeEnrollment(context, Any()) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.startSession(context) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { other.chargeBootstrap(context) }
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { ComplaintIngressAdmission.requireRawEnrollmentContext() }
        }
        admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.chargeEnrollment(checkNotNull(captured), Any()) }
        guard.withIngress(request()) { context ->
            val identity = Any()
            guard.chargeEnrollment(context, identity)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.consumeSession(context, identity) }
            val candidate = InstallationEnrollmentCredentials.prepare(admissionTestActor(1), ComplaintPlatform.ANDROID, ByteArray(32))
            val before = reads
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.prepareEnrollment(context, identity, candidate) }
            assertEquals(before, reads)
            admissionTestRefused(ComplaintAdmissionFailure.INVALID_CONTEXT) { guard.chargeEnrollment(context, Any()) }
        }
        ComplaintIngressAdmission.requireRawEnrollmentContext()
    }

    @Test
    fun `concurrent enrollment respects one global ceiling and downstream failures never refund admission`() {
        val guard = newGuard(MutableAdmissionTestClock(), global = 7)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val admitted = AtomicInteger()
        val denied = AtomicInteger()
        try {
            val futures = (1..24).map { index ->
                executor.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    try {
                        enroll(guard, "192.0.2.$index")
                        admitted.incrementAndGet()
                    } catch (failure: ComplaintAdmissionRejected) {
                        assertEquals(ComplaintAdmissionFailure.RATE_LIMITED, failure.code)
                        assertEquals(3600L, failure.retryAfterSeconds)
                        denied.incrementAndGet()
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(7, admitted.get())
            assertEquals(17, denied.get())
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
        val failed = newGuard(MutableAdmissionTestClock())
        repeat(10) {
            assertThrows<UnsupportedOperationException> {
                failed.withIngress(request()) { context ->
                    failed.chargeEnrollment(context, Any())
                    throw UnsupportedOperationException("synthetic")
                }
            }
        }
        admissionTestRefused(ComplaintAdmissionFailure.RATE_LIMITED) { enroll(failed) }
    }

    private fun enroll(guard: ComplaintIngressAdmission, ip: String = "192.0.2.1") = guard.withIngress(request(ip)) { guard.chargeEnrollment(it, Any()) }

    private fun session(guard: ComplaintIngressAdmission) = guard.withIngress(request()) { context ->
        guard.startSession(context)
        guard.chargeSession(context, admissionTestActor(1), Any())
    }

    private fun quotaPolicy(global: Int = 120, buckets: Int = 4096, events: Int = 131072, prune: Int = 128): ComplaintAdmissionPolicy = admissionTestPolicy(
        semanticBuckets = buckets,
        semanticEvents = events,
        prune = prune,
        enrollment = ComplaintEnrollmentAdmissionPolicy.Bounded(global, digest(), 200, 200),
    )

    private fun newGuard(
        clock: ComplaintAdmissionNanoClock,
        global: Int = 120,
        policy: ComplaintAdmissionPolicy = quotaPolicy(global),
    ): ComplaintIngressAdmission = ComplaintIngressAdmission(ClientIpResolver(KiraSecurityProperties()), policy, admissionTestKeys(), clock)

    private fun request(ip: String = "192.0.2.1"): MockHttpServletRequest = MockHttpServletRequest().apply { remoteAddr = ip }

    private fun seconds(value: Long): Long = value * ComplaintAdmissionPolicy.SECOND_NANOS

    private fun digest(): ByteArray = ByteArray(32) { 17 }

    private fun invalid(operation: () -> Unit) {
        val failure = assertThrows<IllegalArgumentException> { operation() }
        assertEquals(INVALID_ADMISSION_CONFIGURATION, failure.message)
        assertNull(failure.cause)
    }
}
