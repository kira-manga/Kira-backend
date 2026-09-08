package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PersistenceTimeBudgetTest {
    @Test
    fun `one start fixes the entire budget and repeated reads cannot reset it`() {
        val clock = TestNanoClock(25_000_000)
        val budget = PersistenceTimeBudget.start(2000, clock)
        assertEquals(2000L, budget.remainingMillis(3000))
        clock.now += 500_000_000
        assertEquals(1500L, budget.remainingMillis(3000))
        assertEquals(1500L, budget.remainingMillis(3000))
        clock.now += 1_499_000_000
        assertEquals(1L, budget.remainingMillis(3000))
        clock.now += 1_000_000
        assertFailure(PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED) { budget.remainingMillis(3000) }
        clock.now += 1
        assertFailure(PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED) { budget.remainingMillis(3000) }
    }

    @Test
    fun `remaining fractional milliseconds floor down and the final fraction never becomes unlimited zero`() {
        val clock = TestNanoClock(0)
        val budget = PersistenceTimeBudget.start(2, clock)
        assertEquals(2L, budget.remainingMillis(10))
        clock.now = 1
        assertEquals(1L, budget.remainingMillis(10))
        clock.now = 1_000_000
        assertEquals(1L, budget.remainingMillis(10))
        for (instant in listOf(1_000_001L, 1_999_999L, 2_000_000L, 2_000_001L)) {
            clock.now = instant
            assertFailure(PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED) { budget.remainingMillis(10) }
        }
    }

    @Test
    fun `positive API caps clip the same remaining budget without changing it`() {
        val clock = TestNanoClock(0)
        val budget = PersistenceTimeBudget.start(2000, clock)
        assertEquals(75L, budget.remainingMillis(75))
        assertEquals(250L, budget.remainingMillis(250))
        assertEquals(1000L, budget.remainingMillis(1000))
        assertEquals(2000L, budget.remainingMillis(Long.MAX_VALUE))
        clock.now = 1_950_000_000
        assertEquals(50L, budget.remainingMillis(75))
        assertEquals(50L, budget.remainingMillis(1000))
    }

    @ParameterizedTest
    @ValueSource(longs = [Long.MIN_VALUE, -1, 0, 9223372036855, Long.MAX_VALUE])
    fun `invalid allowance fails before reading the clock or overflowing its conversion`(allowance: Long) {
        val clock = PersistenceNanoClock { error("Invalid input must not sample the clock") }
        assertFailure(PersistenceBoundaryFailureCode.INVALID_TIME_BUDGET) { PersistenceTimeBudget.start(allowance, clock) }
    }

    @Test
    fun `maximum representable allowance remains finite and exact at conversion boundary`() {
        val clock = TestNanoClock(0)
        val budget = PersistenceTimeBudget.start(9_223_372_036_854, clock)
        assertEquals(9_223_372_036_854L, budget.remainingMillis(Long.MAX_VALUE))
        clock.now = 9_223_372_036_853_000_000
        assertEquals(1L, budget.remainingMillis(Long.MAX_VALUE))
        clock.now = 9_223_372_036_854_000_000
        assertFailure(PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED) { budget.remainingMillis(Long.MAX_VALUE) }
    }

    @ParameterizedTest
    @ValueSource(longs = [Long.MIN_VALUE, -1, 0])
    fun `zero or negative API caps are not passed through as unlimited`(cap: Long) {
        val budget = PersistenceTimeBudget.start(2000, TestNanoClock(0))
        assertFailure(PersistenceBoundaryFailureCode.INVALID_TIME_BUDGET) { budget.remainingMillis(cap) }
    }

    @Test
    fun `negative nanoTime origins are valid and unrelated to epoch dates`() {
        val clock = TestNanoClock(-2_000_000)
        val budget = PersistenceTimeBudget.start(3, clock)
        assertEquals(3L, budget.remainingMillis(10))
        clock.now = -1_000_000
        assertEquals(2L, budget.remainingMillis(10))
        clock.now = 0
        assertEquals(1L, budget.remainingMillis(10))
        clock.now = 1_000_000
        assertFailure(PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED) { budget.remainingMillis(10) }
    }

    @Test
    fun `elapsed subtraction supports signed nanoTime wrap without deadline addition overflow`() {
        val clock = TestNanoClock(Long.MAX_VALUE - 499_999)
        val budget = PersistenceTimeBudget.start(2, clock)
        assertEquals(2L, budget.remainingMillis(10))
        clock.now = Long.MIN_VALUE + 500_000
        assertEquals(1L, budget.remainingMillis(10))
        clock.now = Long.MIN_VALUE + 1_500_000
        assertFailure(PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED) { budget.remainingMillis(10) }
    }

    @Test
    fun `unsupported negative elapsed time cannot extend an admitted budget`() {
        val clock = TestNanoClock(500_000)
        val budget = PersistenceTimeBudget.start(100, clock)
        clock.now = 499_999
        assertFailure(PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED) { budget.remainingMillis(100) }
    }

    @Test
    fun `failure diagnostics are value free and time checks do not clear interrupt status`() {
        val interruptedOnEntry = Thread.currentThread().isInterrupted
        val clock = TestNanoClock(123456789)
        val budget = PersistenceTimeBudget.start(1, clock)
        try {
            Thread.currentThread().interrupt()
            clock.now += 1_000_000
            val failure = assertThrows(PersistenceBoundaryException::class.java) { budget.remainingMillis(1) }
            assertEquals("Persistence boundary rejected: TIME_BUDGET_EXHAUSTED.", failure.message)
            assertNull(failure.cause)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals("PersistenceTimeBudget(redacted)", budget.toString())
        } finally {
            // Remove this test's own interrupt only; preserve the entry state for the caller.
            Thread.interrupted()
            if (interruptedOnEntry) Thread.currentThread().interrupt()
        }
    }

    private fun assertFailure(code: PersistenceBoundaryFailureCode, action: () -> Unit) {
        assertEquals(code, assertThrows(PersistenceBoundaryException::class.java, action).code)
    }

    private class TestNanoClock(var now: Long) : PersistenceNanoClock {
        override fun nanoTime(): Long = now
    }
}
