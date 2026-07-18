package me.manga.kira.backend.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A test [Clock] whose instant can be advanced, so time-dependent logic (auth-throttle window
 * expiry / block escalation) is exercised deterministically instead of with real sleeps.
 */
class MutableClock(private var current: Instant = Instant.parse("2026-01-01T00:00:00Z"), private val zone: ZoneId = ZoneOffset.UTC) : Clock() {

    override fun instant(): Instant = current

    override fun getZone(): ZoneId = zone

    override fun withZone(newZone: ZoneId): Clock = MutableClock(current, newZone)

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}
