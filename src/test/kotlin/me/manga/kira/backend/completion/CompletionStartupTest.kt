package me.manga.kira.backend.completion

import me.manga.kira.backend.completion.application.CompletionStartup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

@Timeout(5)
class CompletionStartupTest {
    @ParameterizedTest
    @ValueSource(longs = [110, 111])
    fun `authorization rejects the original startup deadline including equality`(at: Long) {
        val now = AtomicLong(100)
        val startup = CompletionStartup(Duration.ofNanos(10), now::get)
        assertTrue(startup.canClaim())
        now.set(at)

        assertFalse(startup.authorize())
        assertEquals(CompletionStartup.Decision.Expired, startup.await())
    }

    @Test
    fun `timely authorization survives a descheduled caller and starts its own provider budget`() {
        val now = AtomicLong(100)
        val startup = CompletionStartup(Duration.ofNanos(10), now::get)
        assertTrue(startup.canClaim())
        now.set(109)
        assertTrue(startup.authorize())
        now.set(120)

        val start = startup.await() as CompletionStartup.Decision.Authorized
        assertEquals(109L, start.atNanos)
        // The provider deadline is119, not130 derived from this late caller's wakeup.
        assertThrows<TimeoutException> {
            startup.awaitProvider(FutureTask { "not started" }, start, Duration.ofNanos(10))
        }
    }

    @Test
    fun `an observable completed Future can win at the provider wait deadline`() {
        val now = AtomicLong(100)
        val startup = CompletionStartup(Duration.ofNanos(10), now::get)
        assertTrue(startup.authorize())
        val start = startup.await() as CompletionStartup.Decision.Authorized
        val completed = FutureTask { "completed" }.apply { run() }
        now.set(110)

        assertEquals("completed", startup.awaitProvider(completed, start, Duration.ofNanos(10)))
        assertThrows<TimeoutException> {
            startup.awaitProvider(FutureTask { "not started" }, start, Duration.ofNanos(10))
        }
    }

    @Test
    fun `startup delay does not consume the provider budget`() {
        val now = AtomicLong(100)
        val startup = CompletionStartup(Duration.ofNanos(100), now::get)
        assertTrue(startup.canClaim())
        now.set(180) // Eight provider durations elapsed while the RUNNING transaction was starting.
        assertTrue(startup.authorize())
        val start = startup.await() as CompletionStartup.Decision.Authorized
        assertEquals(180L, start.atNanos)
        val observedWait = AtomicLong()
        val completed = object : FutureTask<String>(Callable { "result" }) {
            override fun get(timeout: Long, unit: TimeUnit): String {
                observedWait.set(unit.toNanos(timeout))
                return "result"
            }
        }

        assertEquals("result", startup.awaitProvider(completed, start, Duration.ofNanos(10)))
        assertEquals(10L, observedWait.get())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `cancellation independently forbids both pending and post-claim authorization`(claimAlreadyStarted: Boolean) {
        val startup = CompletionStartup(Duration.ofSeconds(1))
        if (claimAlreadyStarted) assertTrue(startup.canClaim())
        startup.cancel()

        assertFalse(startup.canClaim())
        assertFalse(startup.authorize())
        assertEquals(CompletionStartup.Decision.Cancelled, startup.await())
    }

    @Test
    fun `failure and rejected claims wake startup without waiting for its deadline`() {
        val failure = IllegalStateException("synthetic startup failure")
        val failed = CompletionStartup(Duration.ofDays(1))
        failed.failed(failure)
        assertSame(failure, (failed.await() as CompletionStartup.Decision.Failed).cause)
        assertFalse(failed.authorize())

        val rejected = CompletionStartup(Duration.ofDays(1))
        rejected.rejected()
        assertEquals(CompletionStartup.Decision.Rejected, rejected.await())
        assertFalse(rejected.authorize())
    }

    @Test
    fun `expired pending work cannot start after the caller timeout decision`() {
        val now = AtomicLong(100)
        val startup = CompletionStartup(Duration.ofNanos(10), now::get)
        now.set(110)

        assertEquals(CompletionStartup.Decision.Expired, startup.await())
        assertFalse(startup.canClaim())
        assertFalse(startup.authorize())
    }

    @Test
    fun `monotonic deadline subtraction supports nanoTime wraparound`() {
        val now = AtomicLong(Long.MAX_VALUE - 4)
        val startup = CompletionStartup(Duration.ofNanos(10), now::get)
        now.addAndGet(9)
        assertTrue(startup.authorize())
        assertEquals(now.get(), (startup.await() as CompletionStartup.Decision.Authorized).atNanos)
    }
}
