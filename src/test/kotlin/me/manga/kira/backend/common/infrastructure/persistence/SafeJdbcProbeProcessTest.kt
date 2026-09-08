package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SafeJdbcProbeProcessTest {
    @Test
    fun `a child assertion failure is observed as failure and its actual exit is retained`() {
        // Deliberately supply the real driver to the probe that requires it to be missing.
        val child = SafeJdbcProbeProcess.start(JdbcFailureProbeMode.MISSING_DRIVER)
        val failure = assertThrows(IllegalStateException::class.java) { child.use { it.awaitVerified() } }
        assertEquals("JDBC profile probe rejected: exit 1.", failure.message)
        assertTerminated(child, "failed-assertion")
    }

    @Test
    fun `an observation timeout still terminates and reaps only the owned child`() {
        val child = SafeJdbcProbeProcess.start(JdbcFailureProbeMode.WAIT_FOR_TERMINATION)
        val failure = assertThrows(IllegalStateException::class.java) { child.use { it.awaitVerified(50) } }
        assertEquals("JDBC profile probe observation timed out.", failure.message)
        assertTerminated(child, "timed-out")
    }

    @Test
    fun `caller interruption aborts observation but cleanup observes exit and restores interruption`() {
        withJdbcTestInterruptIsolation {
            val child = SafeJdbcProbeProcess.start(JdbcFailureProbeMode.WAIT_FOR_TERMINATION)
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

    private fun assertTerminated(child: SafeJdbcProbeProcess, scenario: String) {
        assertTrue(child.pid > 0)
        assertTrue(child.exitObserved)
        assertFalse(child.isAlive)
        println("JDBC_PROBE_CLEANUP scenario=$scenario pid=${child.pid} exit_observed=true alive=false")
    }
}
