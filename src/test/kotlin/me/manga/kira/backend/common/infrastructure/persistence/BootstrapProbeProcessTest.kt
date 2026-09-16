package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class BootstrapProbeProcessTest {
    @Test
    fun `a deliberate assertion failure cannot be reported as success`(@TempDir root: Path) {
        val child = BootstrapProbeProcess.start(root, BootstrapProbeCase.ASSERTION_FAILURE)
        val failure = assertThrows(IllegalStateException::class.java) { child.use { it.awaitVerified() } }
        assertEquals("Bootstrap probe rejected: exit 1.", failure.message)
        child.requireAssertionFailureWitness()
        assertTerminated(child)
    }

    @Test
    fun `a zero-exit no-op cannot satisfy the special success receipt`(@TempDir root: Path) {
        val child = BootstrapProbeProcess.start(root, BootstrapProbeCase.NOOP_EXIT)
        val failure = assertThrows(IllegalStateException::class.java) { child.use { it.awaitVerified() } }
        assertEquals("Bootstrap probe rejected: exit 0.", failure.message)
        assertTerminated(child)
    }

    @Test
    fun `a success exit without the matching identity is rejected`(@TempDir root: Path) {
        val child = BootstrapProbeProcess.start(root, BootstrapProbeCase.WRONG_RECEIPT)
        val failure = assertThrows(IllegalStateException::class.java) { child.use { it.awaitVerified() } }
        assertEquals("Bootstrap probe success identity missing.", failure.message)
        assertTerminated(child)
    }

    @Test
    fun `timeout still kills and observes only the owned child exit`(@TempDir root: Path) {
        val child = BootstrapProbeProcess.start(root, BootstrapProbeCase.WAIT_FOR_TERMINATION)
        val failure = assertThrows(IllegalStateException::class.java) {
            child.use {
                it.awaitReady()
                it.awaitVerified(25)
            }
        }
        assertEquals("Bootstrap probe observation timed out.", failure.message)
        assertTrue(child.readyObserved)
        assertTerminated(child)
    }

    @Test
    fun `interrupted observation preserves interruption after cleanup`(@TempDir root: Path) = withBootstrapInterruptIsolation {
        val child = BootstrapProbeProcess.start(root, BootstrapProbeCase.WAIT_FOR_TERMINATION)
        assertThrows(InterruptedException::class.java) {
            child.use {
                it.awaitReady()
                Thread.currentThread().interrupt()
                it.awaitVerified()
            }
        }
        assertTrue(Thread.currentThread().isInterrupted)
        assertTrue(child.readyObserved)
        assertTerminated(child)
    }

    @Test
    fun `repeated cleanup interruptions keep one deadline and the original failure`(@TempDir root: Path) = withBootstrapInterruptIsolation {
        val budgets = mutableListOf<Long>()
        val child = BootstrapProbeProcess.start(root, BootstrapProbeCase.WAIT_FOR_TERMINATION, cleanupWait = { process, remaining ->
            budgets += remaining
            if (budgets.size <= 3) throw InterruptedException("synthetic cleanup interruption")
            process.waitFor(remaining, TimeUnit.NANOSECONDS)
            Unit
        })
        val original = IllegalStateException("original synthetic failure")
        val observed = assertThrows(IllegalStateException::class.java) {
            child.use {
                it.awaitReady()
                throw original
            }
        }
        assertSame(original, observed)
        assertTrue(budgets.size >= 4)
        assertTrue(budgets.zipWithNext().all { (before, after) -> after <= before })
        assertTrue(budgets.all { it in 1..TimeUnit.SECONDS.toNanos(5) })
        assertTrue(Thread.currentThread().isInterrupted)
        assertTrue(child.readyObserved)
        assertTerminated(child)
    }

    @Test
    fun `an extra synthetic environment entry is rejected before dispatch`(@TempDir root: Path) {
        val child = BootstrapProbeProcess.start(root, BootstrapProbeCase.EXTRA_ENVIRONMENT)
        val failure = assertThrows(IllegalStateException::class.java) { child.use { it.awaitVerified() } }
        assertEquals("Bootstrap probe rejected: exit 1.", failure.message)
        child.requireEnvironmentRejectionWitness()
        assertFalse(child.readyObserved)
        assertTerminated(child)
    }

    @Test
    fun `a ready file with a different nonce is rejected and its child is reaped`(@TempDir root: Path) {
        val child = BootstrapProbeProcess.start(root, BootstrapProbeCase.WRONG_READY_IDENTITY)
        val failure = assertThrows(IllegalStateException::class.java) { child.use { it.awaitReady() } }
        assertEquals("Bootstrap probe readiness identity mismatch.", failure.message)
        assertFalse(child.readyObserved)
        assertTerminated(child)
    }

    private fun assertTerminated(child: BootstrapProbeProcess) {
        assertTrue(child.pid > 0)
        assertTrue(child.exitObserved)
        assertFalse(child.isAlive)
    }
}
