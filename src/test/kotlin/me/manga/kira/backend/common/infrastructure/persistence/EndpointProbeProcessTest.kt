package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EndpointProbeProcessTest {
    @Test
    fun `a deliberate child assertion failure is observed and its actual exit is retained`() {
        val child = EndpointProbeProcess.start(EndpointProbeMode.ASSERTION_FAILURE)
        val failure = assertThrows(IllegalStateException::class.java) { child.use { it.awaitVerified() } }
        assertEquals("Endpoint probe rejected: exit 1.", failure.message)
        assertTerminated(child, "failed-assertion")
    }

    @Test
    fun `an absent provider service fixture cannot pass the positive discovery control`() {
        val child = EndpointProbeProcess.start(EndpointProbeMode.STOCK_PROVIDER_SENTINEL)
        val failure = assertThrows(IllegalStateException::class.java) { child.use { it.awaitVerified() } }
        assertEquals("Endpoint probe rejected: exit 1.", failure.message)
        assertTerminated(child, "missing-provider-control")
    }

    @Test
    fun `timed out observation still terminates and reaps only its own child`() {
        val child = EndpointProbeProcess.start(EndpointProbeMode.WAIT_FOR_TERMINATION)
        val failure = assertThrows(IllegalStateException::class.java) { child.use { it.awaitVerified(50) } }
        assertEquals("Endpoint probe observation timed out.", failure.message)
        assertTerminated(child, "timed-out")
    }

    @Test
    fun `interrupted observation restores the flag after bounded cleanup and actual exit`() {
        withEndpointInterruptIsolation {
            val child = EndpointProbeProcess.start(EndpointProbeMode.WAIT_FOR_TERMINATION)
            assertThrows(InterruptedException::class.java) {
                child.use {
                    Thread.currentThread().interrupt()
                    it.awaitVerified()
                }
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertTerminated(child, "interrupted")
        }
    }

    private fun assertTerminated(child: EndpointProbeProcess, scenario: String) {
        assertTrue(child.pid > 0)
        assertTrue(child.exitObserved)
        assertFalse(child.isAlive)
        println("ENDPOINT_PROBE_CLEANUP scenario=$scenario pid=${child.pid} exit_observed=true alive=false")
    }
}
