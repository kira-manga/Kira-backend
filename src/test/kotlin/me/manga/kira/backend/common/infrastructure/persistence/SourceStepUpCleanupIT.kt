package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.common.exception.ServiceUnavailableException
import me.manga.kira.backend.security.AdminStepUpService.Companion.SOURCE_ADMIN_MUTATION_SCOPE
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.util.UUID

/** Four actual live-source service/transaction tests; no Boot/HTTP, complaint-authority or native-pool qualification. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class SourceStepUpCleanupIT {
    private val database = lazy { PgLifecycleDatabaseFixture(SourceStepUpCleanupIT::class.java).also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `live source issuance preserves every complaint grant lifecycle and all counters`() = withSourceStepUpCleanup(database.value) { f ->
        f.seedGrant(id(1), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff.minusSeconds(1))
        f.seedGrant(id(2), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff.plusSeconds(60), f.cutoff.minusSeconds(1))
        f.seedGrant(id(3), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff.plusSeconds(60))
        f.seedGrant(id(4), COMPLAINT_SCOPE, f.cutoff.minusSeconds(1))
        f.seedGrant(id(5), COMPLAINT_SCOPE, f.cutoff.plusSeconds(60), f.cutoff.minusSeconds(1))
        f.seedGrant(id(6), COMPLAINT_SCOPE, f.cutoff.plusSeconds(60))
        f.seedComplaintCharges(3)
        val beforeComplaintRows = f.grantSnapshot(COMPLAINT_SCOPE)
        val beforeCounters = f.counterSnapshot()

        val issuedId = f.issuedId(f.issue())

        assertEquals(listOf(2), f.deletedCounts)
        assertEquals(listOf(issuedId), f.insertedIds)
        assertEquals(setOf(id(3), id(4), id(5), id(6), issuedId), f.grantIds())
        assertEquals(beforeComplaintRows, f.grantSnapshot(COMPLAINT_SCOPE))
        assertEquals(beforeCounters, f.counterSnapshot())
        f.assertSplitTransactions()
    }

    @Test
    fun `one live issuance deletes only fifty ordered eligible grants and respects the exact cutoff`() = withSourceStepUpCleanup(database.value) { f ->
        val eligible = (1L..51L).map(::id)
        eligible.reversed().forEach { f.seedGrant(it, SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff) }
        // An unused proof one PostgreSQL microsecond after the cutoff stays active, even before every eligible UUID.
        f.seedGrant(id(0), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff.plusNanos(1_000))
        f.seedGrant(id(52), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff.plusSeconds(60))
        val beforeCounters = f.counterSnapshot()

        val issuedId = f.issuedId(f.issue())

        assertEquals(listOf(50), f.deletedCounts, "Issuance performs one bounded cleanup, never a drain loop.")
        assertEquals(listOf(issuedId), f.insertedIds)
        assertEquals(setOf(id(0), eligible.last(), id(52), issuedId), f.grantIds())
        assertEquals(beforeCounters, f.counterSnapshot())
        f.assertSplitTransactions()
    }

    @Test
    fun `live issuance skips an independently held first eligible row while that lock remains held`() = withSourceStepUpCleanup(database.value) { f ->
        val eligible = (1L..51L).map(::id)
        eligible.forEach { f.seedGrant(it, SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff) }
        val beforeCounters = f.counterSnapshot()

        f.lockGrant(eligible.first()).use {
            val issuedId = f.issuedId(f.issue())

            assertEquals(listOf(50), f.deletedCounts)
            assertEquals(setOf(eligible.first(), issuedId), f.grantIds())
            assertEquals(beforeCounters, f.counterSnapshot())
            f.assertSplitTransactions()
        }
    }

    @Test
    fun `a failure after the actual live grant insert rolls issuance back but preserves completed cleanup`() = withSourceStepUpCleanup(database.value) { f ->
        f.seedGrant(id(1), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff)
        f.seedGrant(id(2), SOURCE_ADMIN_MUTATION_SCOPE, f.cutoff.plusSeconds(60), f.cutoff.minusSeconds(1))
        f.seedGrant(id(3), COMPLAINT_SCOPE, f.cutoff)
        f.seedComplaintCharges(1)
        val beforeComplaintRows = f.grantSnapshot(COMPLAINT_SCOPE)
        val beforeCounters = f.counterSnapshot()
        val fault = SourceStepUpAfterInsertFailure()
        var insertedBeforeFailure = false
        f.afterInsert = { insertedId ->
            val inTransactionIds = f.jdbc.queryForList(
                "SELECT id FROM admin_step_up_grants WHERE user_id = ? ORDER BY id",
                UUID::class.java,
                f.userId,
            ).toSet()
            assertEquals(setOf(id(3), insertedId), inTransactionIds, "Actual cleanup and insertion already executed on the selected connection.")
            assertEquals(setOf(id(3)), f.grantIds(), "An independent reader sees committed cleanup but not uncommitted issuance.")
            f.assertSplitTransactions()
            insertedBeforeFailure = true
            throw fault
        }

        val failure = assertThrows<ServiceUnavailableException> { f.issue() }
        assertEquals("ADMIN_STEP_UP_UNAVAILABLE", failure.code)
        requireConnectionFree()

        assertTrue(insertedBeforeFailure)
        assertEquals(listOf(2), f.deletedCounts)
        assertEquals(1, f.insertedIds.size)
        assertEquals(beforeComplaintRows, f.grantSnapshot())
        assertEquals(beforeCounters, f.counterSnapshot())
    }

    private fun id(value: Long): UUID = UUID(0, value)

    private companion object {
        const val COMPLAINT_SCOPE = "complaint-moderation-mutation"
    }
}

private class SourceStepUpAfterInsertFailure : RuntimeException("Synthetic source-step-up post-insert fault.")
