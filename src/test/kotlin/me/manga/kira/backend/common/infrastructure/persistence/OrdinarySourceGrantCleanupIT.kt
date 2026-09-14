package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.security.AdminStepUpService.Companion.SOURCE_ADMIN_MUTATION_SCOPE
import me.manga.kira.backend.security.SourceGrantCleanup
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real ordinary JPA and independent-reader row oracles; the shared fixture owns all database/pool/EMF setup. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OrdinarySourceGrantCleanupIT {
    private val database = lazy { PgLifecycleDatabaseFixture().also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `source expiry includes the cutoff and used unexpired grants without deleting the next microsecond`() =
        withOrdinarySourceGrantCleanup(database.value) { f ->
            f.seedGrant(grantId(1), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff.minusNanos(1_000))
            f.seedGrant(grantId(2), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff)
            f.seedGrant(grantId(3), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff.plusSeconds(60), f.cutoff.minusSeconds(1))
            f.seedGrant(grantId(4), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff.plusNanos(1_000))
            assertEquals((1L..4L).map(::grantId).toSet(), f.grantIds())

            assertEquals(3, f.newExecutor().cleanupSourceGrants())
            assertEquals(setOf(grantId(4)), f.grantIds())
            assertEquals(0, f.newExecutor().cleanupSourceGrants())
            assertEquals(setOf(grantId(4)), f.grantIds())
        }

    @Test
    fun `mixed scope cleanup preserves active source grants and every complaint lifecycle`() = withOrdinarySourceGrantCleanup(database.value) { f ->
        f.seedGrant(grantId(1), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff.plusSeconds(60))
        f.seedGrant(grantId(2), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff.minusSeconds(60))
        f.seedGrant(grantId(3), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff.plusSeconds(60), f.cutoff.minusSeconds(1))
        f.seedGrant(grantId(4), COMPLAINT_SCOPE, f.cutoff.minusSeconds(60))
        f.seedGrant(grantId(5), COMPLAINT_SCOPE, f.cutoff.plusSeconds(60), f.cutoff.minusSeconds(1))
        f.seedGrant(grantId(6), COMPLAINT_SCOPE, f.cutoff.minusSeconds(60), f.cutoff.minusSeconds(61))
        f.seedGrant(grantId(7), COMPLAINT_SCOPE, f.cutoff.plusSeconds(60))
        f.seedGrant(grantId(8), COMPLAINT_SCOPE, f.cutoff)
        assertEquals((1L..8L).map(::grantId).toSet(), f.grantIds())

        assertEquals(2, f.newExecutor().cleanupSourceGrants())
        assertEquals(setOf(1L, 4L, 5L, 6L, 7L, 8L).map(::grantId).toSet(), f.grantIds())
    }

    @Test
    fun `one invocation commits only the first fifty eligible UUIDs and leaves the remaining batch`() = withOrdinarySourceGrantCleanup(database.value) { f ->
        val eligible = (101L..170L).map(::grantId)
        // Reverse insertion order so the row-set oracle is UUID order, not fixture insertion order.
        eligible.reversed().forEach { f.seedGrant(it, SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff) }
        f.seedGrant(grantId(1), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff.plusSeconds(60))
        f.seedGrant(grantId(2), COMPLAINT_SCOPE, f.cutoff.minusSeconds(60))
        val protected = setOf(grantId(1), grantId(2))
        assertEquals(eligible.toSet() + protected, f.grantIds())

        assertEquals(50, f.newExecutor().cleanupSourceGrants())
        assertEquals(eligible.drop(50).toSet() + protected, f.grantIds())
    }

    @Test
    fun `an independently locked earliest eligible grant is skipped while fifty other rows commit`() = withOrdinarySourceGrantCleanup(database.value) { f ->
        val eligible = (1L..52L).map(::grantId)
        eligible.forEach { f.seedGrant(it, SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff) }
        assertEquals(eligible.toSet(), f.grantIds())
        val survivors = setOf(eligible.first(), eligible.last())

        // The independent transaction keeps this actual row lock until cleanup has returned.
        // No worker, Future timeout, sleep or lock release makes the successful call possible.
        f.lockGrant(eligible.first()).use {
            assertEquals(50, f.newExecutor().cleanupSourceGrants())
            assertEquals(survivors, f.grantIds())
        }
        assertEquals(survivors, f.grantIds())
        assertEquals(2, f.newExecutor().cleanupSourceGrants())
        assertTrue(f.grantIds().isEmpty())
    }

    @Test
    fun `the trusted Clock is sampled once and its committed cutoff is durable to an independent reader`() =
        withOrdinarySourceGrantCleanup(database.value) { f ->
            f.seedGrant(grantId(1), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff)
            f.seedGrant(grantId(2), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff.plusSeconds(60))
            assertEquals(setOf(grantId(1), grantId(2)), f.grantIds())
            val clock = AdvancingCountingClock(f.cutoff, f.cutoff.plusSeconds(3_600))
            val observedCutoffs = mutableListOf<Instant>()
            val observingStore = object : SourceGrantCleanup {
                override fun deleteEligibleSourceGrants(cutoff: Instant): Int {
                    observedCutoffs.add(cutoff)
                    return f.sourceStore.deleteEligibleSourceGrants(cutoff)
                }
            }

            assertEquals(1, f.newExecutor(store = observingStore, clock = clock).cleanupSourceGrants())
            assertEquals(1, clock.sampleCount)
            assertEquals(listOf(f.cutoff), observedCutoffs)
            assertEquals(setOf(grantId(2)), f.grantIds())
        }

    @Test
    fun `a decorator failure after the real delete rolls back and preserves independently readable rows`() =
        withOrdinarySourceGrantCleanup(database.value) { f ->
            f.seedGrant(grantId(1), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff)
            f.seedGrant(grantId(2), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff.plusSeconds(60), f.cutoff.minusSeconds(1))
            f.seedGrant(grantId(3), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff.plusSeconds(60))
            f.seedGrant(grantId(4), COMPLAINT_SCOPE, f.cutoff.minusSeconds(60))
            val before = (1L..4L).map(::grantId).toSet()
            assertEquals(before, f.grantIds())
            var deletedBeforeAbort = -1
            var visibleBeforeAbort = emptySet<UUID>()
            var explicitAbortReached = false
            val failingStore = object : SourceGrantCleanup {
                override fun deleteEligibleSourceGrants(cutoff: Instant): Int {
                    deletedBeforeAbort = f.sourceStore.deleteEligibleSourceGrants(cutoff)
                    visibleBeforeAbort = f.grantIds()
                    explicitAbortReached = true
                    throw AfterDeleteFailure()
                }
            }

            // The phase deliberately exposes a value-free failure, not the arbitrary decorator cause.
            assertThrows<RuntimeException> { f.newExecutor(store = failingStore).cleanupSourceGrants() }
            assertTrue(explicitAbortReached, "The real delete and independent read must precede the deliberate failure.")
            assertEquals(2, deletedBeforeAbort)
            assertEquals(before, visibleBeforeAbort, "An independent reader cannot observe the uncommitted deletion.")
            assertEquals(before, f.grantIds(), "Independent post-return readback must witness rollback, not a fabricated count.")

            // Positive continuation proves that rollback did not merely leave the selected path unusable.
            assertEquals(2, f.newExecutor().cleanupSourceGrants())
            assertEquals(setOf(grantId(3), grantId(4)), f.grantIds())
        }

    private fun grantId(value: Long): UUID = UUID(0, value)

    private class AfterDeleteFailure : RuntimeException("Test-only failure after a real source-grant delete.")

    private class AdvancingCountingClock(
        private val first: Instant,
        private val later: Instant,
        private val samples: AtomicInteger = AtomicInteger(),
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        val sampleCount: Int get() = samples.get()

        override fun instant(): Instant = if (samples.getAndIncrement() == 0) first else later

        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = AdvancingCountingClock(first, later, samples, zone)
    }

    private companion object {
        const val COMPLAINT_SCOPE = "complaint-moderation-mutation"
    }
}
