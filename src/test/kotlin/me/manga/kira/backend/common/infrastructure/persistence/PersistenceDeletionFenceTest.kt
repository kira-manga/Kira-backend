package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PersistenceDeletionFenceTest {
    @Test
    fun `fence calls clip at seventy five without restarting either phase or fence budget`() {
        val clock = FenceBudgetClock()
        val work = PersistenceTimeBudget.start(2_000, clock)
        val fence = PersistenceDeletionFenceBudget(work, clock)
        assertEquals(75L, fence.remainingMillis())
        assertNotSame(work, fence.dispatchBudget())
        clock.now = 60_000_000
        assertEquals(40L, fence.remainingMillis())
        assertEquals(40L, fence.remainingMillis())
        clock.now = 99_000_000
        assertEquals(1L, fence.remainingMillis())
        assertEquals(1_901L, work.remainingMillis(2_000))
    }

    @Test
    fun `earlier phase deadline clips both read allowance and actual dispatch budget`() {
        val clock = FenceBudgetClock()
        val work = PersistenceTimeBudget.start(2_000, clock)
        clock.now = 1_990_000_000
        val fence = PersistenceDeletionFenceBudget(work, clock)
        assertEquals(10L, fence.remainingMillis())
        assertSame(work, fence.dispatchBudget())
        clock.now += 9_000_000
        assertEquals(1L, fence.remainingMillis())
        clock.now++
        assertEquals(
            PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED,
            assertThrows<PersistenceBoundaryException> { fence.remainingMillis() }.code,
        )
    }

    @ParameterizedTest
    @ValueSource(longs = [99_000_001, 99_999_999, 100_000_000, 100_000_001])
    fun `last fractional millisecond exact expiry and one over cannot become unlimited or restart`(elapsed: Long) {
        val clock = FenceBudgetClock()
        val fence = PersistenceDeletionFenceBudget(PersistenceTimeBudget.start(2_000, clock), clock)
        clock.now = elapsed
        assertEquals(
            PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED,
            assertThrows<PersistenceBoundaryException> { fence.remainingMillis() }.code,
        )
        assertEquals(
            PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED,
            assertThrows<PersistenceBoundaryException> { fence.dispatchBudget() }.code,
        )
    }

    private class FenceBudgetClock(var now: Long = 0) : PersistenceNanoClock {
        override fun nanoTime(): Long = now
    }
}
