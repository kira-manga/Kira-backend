package me.manga.kira.backend.security.aws

import me.manga.kira.backend.security.EpochSealAttemptV1
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** Sampled BEFORE AssumeRole dispatch. Neither an AWS expiration nor cleanup renews/revokes a DB lease. */
internal class EpochSealStsSessionTiming(
    private val original: EpochSealAttemptV1,
    private val limits: AwsEpochSealStsLimits,
    private val nanoTime: () -> Long,
    private val wallClock: () -> Instant,
) {
    private val started = nanoTime()
    private val wallStarted = wallClock()
    private var lastElapsed = 0L
    private var lastWall = wallStarted
    private var expired = false

    @Synchronized
    fun requireUsable(expiration: Instant) {
        val remainingSeal = original.remainingMillis(Int.MAX_VALUE)
        val elapsed = nanoTime() - started
        val wall = wallClock()
        val uncertainty = limits.clockUncertaintyMillis
        // Bound before arithmetic on an untrusted provider expiration or fixture clock.
        if (elapsedOutOfBounds(elapsed) || wallOutOfBounds(wall) || expirationOutOfBounds(expiration, wall, uncertainty)) {
            expired = true
        }
        requireEpochSealSts(!expired, EpochSealStsFailure.SESSION_EXPIRED)
        val projected = wallStarted.plusNanos(elapsed)
        if (abs(Duration.between(projected, wall).toMillis()) > uncertainty) expired = true
        // Use the later clock estimate, then subtract the independently configured uncertainty.
        val conservativeNow = maxOf(projected, wall).plusMillis(uncertainty)
        if (Duration.between(conservativeNow, expiration).toMillis() <= remainingSeal) expired = true
        requireEpochSealSts(!expired, EpochSealStsFailure.SESSION_EXPIRED)
        lastElapsed = elapsed
        lastWall = wall
    }

    private fun elapsedOutOfBounds(elapsed: Long): Boolean = elapsed < 0 || elapsed < lastElapsed || elapsed > 600_000_000_000L

    private fun wallOutOfBounds(wall: Instant): Boolean = wall < lastWall || wall < Instant.EPOCH || wall > wallStarted.plusSeconds(660)

    private fun expirationOutOfBounds(expiration: Instant, wall: Instant, uncertainty: Long): Boolean =
        expiration <= wall || expiration > wall.plusSeconds(EpochSealStsPolicy.SESSION_SECONDS.toLong()).plusMillis(uncertainty)

    override fun toString(): String = "EpochSealStsSessionTiming(original-budget,conservative-expiry,no-lease-authority)"
}
