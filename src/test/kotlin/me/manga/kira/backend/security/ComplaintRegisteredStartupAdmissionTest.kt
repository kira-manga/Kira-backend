package me.manga.kira.backend.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockHttpServletRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** SOURCE ONLY / NOT_COMPILED / NOT_RUN. The original ingress owner, not a new shutdown permit. */
class ComplaintRegisteredStartupAdmissionTest {
    @Test
    fun `startup stop rejects new contexts but cannot assert held original requests released`() {
        val ingress = historyTestIngress()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        assertFalse(ingress.registeredStartupAdmissionReleased(), "An unstopped empty owner is not shutdown proof.")
        val worker = Thread.ofPlatform().unstarted {
            try {
                ingress.withIngress(MockHttpServletRequest().apply { remoteAddr = "127.0.0.1" }) { original ->
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    assertEquals(ComplaintAdmissionFailure.UNAVAILABLE,
                        assertThrows<ComplaintAdmissionRejected> { ingress.requireLiveContext(original) }.code)
                }
            } catch (problem: Throwable) { failure.set(problem) }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            ingress.stopRegisteredStartupAdmission()
            assertFalse(ingress.registeredStartupAdmissionReleased(), "The retained original context still has to execute finally.")
            assertEquals(ComplaintAdmissionFailure.UNAVAILABLE, assertThrows<ComplaintAdmissionRejected> {
                ingress.withIngress(MockHttpServletRequest().apply { remoteAddr = "127.0.0.1" }) { error("Stopped ingress admitted a new context") }
            }.code)
            ingress.stopRegisteredStartupAdmission()
            assertFalse(ingress.registeredStartupAdmissionReleased(), "Repeated stop is not an owner refund.")
        } finally {
            release.countDown()
            worker.join(5_000)
        }
        assertFalse(worker.isAlive)
        failure.get()?.let { throw it }
        assertTrue(ingress.registeredStartupAdmissionReleased())
        assertEquals(ComplaintAdmissionFailure.UNAVAILABLE, assertThrows<ComplaintAdmissionRejected> {
            ingress.withIngress(MockHttpServletRequest().apply { remoteAddr = "127.0.0.1" }) { error("Stopped owner restarted") }
        }.code)
        // No global switch or second registry: another already-independent owner is unaffected.
        historyTestIngress().withIngress(MockHttpServletRequest().apply { remoteAddr = "127.0.0.1" }) { }
    }
}
