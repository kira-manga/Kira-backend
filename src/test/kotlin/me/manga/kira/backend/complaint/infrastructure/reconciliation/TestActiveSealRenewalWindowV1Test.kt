package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Pure elapsed-budget tests only: these do not issue a lease, COMMIT or physical-release proof. */
class TestActiveSealRenewalWindowV1Test {
    @Test fun `dispatch checkout commit and cleanup consume the retained ten second window`() {
        val f = Fixture()
        f.window.beginDispatch(first = true)
        f.millis = 1_750
        assertEquals(2_000L, f.window.phaseBudget().remainingMillis(2_000))
        f.window.committedAndReleased()
        assertEquals(8_250, f.window.remainingMillis(10_000))
        val retained = f.window.providerBudget()
        f.millis = 9_250
        assertSame(retained, f.window.providerBudget())
        assertEquals(750, f.window.remainingMillis(10_000))
    }

    @Test fun `renewal never lends its pending future window to the preceding commit tail`() {
        val f = Fixture()
        f.window.beginDispatch(first = true); f.window.committedAndReleased()
        f.millis = 9_250
        f.window.beginDispatch(first = false)
        assertEquals(750L, f.window.phaseBudget().remainingMillis(2_000))
        f.millis = 10_001
        assertThrows<RuntimeException> { f.window.committedAndReleased() }
        f.millis = 9_500 // Even a bad replacement clock cannot rehabilitate the failed original.
        assertThrows<TestActiveOrdinarySealExceptionV1> { f.window.remainingMillis(1) }
    }

    @Test fun `successful renewal retains its before dispatch start and clips providers`() {
        val f = Fixture()
        f.window.beginDispatch(first = true); f.window.committedAndReleased()
        f.millis = 8_000; f.window.beginDispatch(first = false)
        f.millis = 9_000; f.window.committedAndReleased()
        assertEquals(9_000, f.window.remainingMillis(30_000))
        f.millis = 17_750
        assertEquals(250, f.window.remainingMillis(5_000))
        assertEquals(250L, f.window.providerBudget().capped(5_000).remainingMillis(5_000))
    }

    @Test fun `original journal deadline cannot be renewed`() {
        val f = Fixture(total = 12_000)
        f.window.beginDispatch(first = true); f.window.committedAndReleased()
        f.millis = 8_000; f.window.beginDispatch(first = false)
        f.millis = 9_000; f.window.committedAndReleased()
        assertEquals(3_000, f.window.remainingMillis(10_000))
        f.millis = 12_000
        assertThrows<RuntimeException> { f.window.remainingMillis(1) }
        assertThrows<TestActiveOrdinarySealExceptionV1> { f.window.beginDispatch(first = false) }
    }

    @Test fun `provider work is unavailable before original acquire commit and release`() {
        val f = Fixture()
        assertThrows<IllegalStateException> { f.window.providerBudget() }
        assertThrows<TestActiveOrdinarySealExceptionV1> { f.window.beginDispatch(first = true) }
        val pending = Fixture()
        pending.window.beginDispatch(first = true)
        assertThrows<IllegalStateException> { pending.window.providerBudget() }
        assertThrows<TestActiveOrdinarySealExceptionV1> { pending.window.committedAndReleased() }
    }

    @Test fun `closed original cannot restart a renewal`() {
        val f = Fixture()
        f.window.beginDispatch(first = true); f.window.committedAndReleased(); f.window.poison()
        assertThrows<TestActiveOrdinarySealExceptionV1> { f.window.remainingMillis(1) }
        assertThrows<TestActiveOrdinarySealExceptionV1> { f.window.beginDispatch(first = false) }
        assertThrows<TestActiveOrdinarySealExceptionV1> { f.window.committedAndReleased() }
    }

    @Test fun `backward positive elapsed sample stays poisoned instead of extending SQL time`() {
        val f = Fixture()
        f.window.beginDispatch(first = true); f.window.committedAndReleased()
        f.millis = 4_000; assertEquals(6_000, f.window.remainingMillis(10_000))
        f.millis = 3_999
        assertThrows<TestActiveOrdinarySealExceptionV1> { f.window.remainingMillis(1) }
        f.millis = 5_000
        assertThrows<TestActiveOrdinarySealExceptionV1> { f.clock.nanoTime() }
        assertThrows<TestActiveOrdinarySealExceptionV1> { f.window.remainingMillis(1) }
    }

    @Test fun `signed nanoTime wrap is forward elapsed rather than wall clock failure`() {
        var raw = Long.MAX_VALUE - 2_000_000L
        val clock = TestActiveSealNanoClockV1(PersistenceNanoClock { raw })
        val budget = PersistenceTimeBudget.start(100, clock)
        raw += 3_000_000L
        assertEquals(97L, budget.remainingMillis(100))
    }

    private class Fixture(total: Long = 60_000) {
        var millis = 0L
        val clock = TestActiveSealNanoClockV1(PersistenceNanoClock { millis * 1_000_000 })
        val window = TestActiveSealRenewalWindowV1(PersistenceTimeBudget.start(total, clock))
    }
}
