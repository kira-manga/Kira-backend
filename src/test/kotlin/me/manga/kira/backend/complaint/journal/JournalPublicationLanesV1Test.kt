package me.manga.kira.backend.complaint.journal

import me.manga.kira.backend.common.infrastructure.persistence.racePersistenceAdmissions
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.InitialLiveJournalTestFixture
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLaneSnapshotV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Accounting only; real privacy factories and cleanup owners are exercised by the separate PG fixture. */
class JournalPublicationLanesV1Test {
    @Test
    fun `large validated limits allocate only actual owners and snapshot addition widens before summing`() {
        val declared = InitialLiveJournalTestFixture.declaration()
        val journal = ComplaintJournalConfigurationV1.of(
            declared.copy(limits = declared.limits.copy(
                capacity = declared.limits.capacity.copy(maximumPublicationLanes = Int.MAX_VALUE, routinePublicationLanes = Int.MAX_VALUE - 1),
            )),
        )
        JournalPublicationLanesV1(journal).use { lanes ->
            assertEquals(0L, lanes.activeOwners().totalOwners)
            val actual = List(3) { checkNotNull(lanes.tryRoutinePublication()) }
            assertEquals(JournalPublicationLaneSnapshotV1(3, 0), lanes.activeOwners())
            actual.forEach { it.close() }
            assertEquals(0L, lanes.activeOwners().totalOwners)
        }
        // Synthetic diagnostic values, not an assertion that this impossible occupancy was admitted.
        assertEquals(4_294_967_294L, JournalPublicationLaneSnapshotV1(Int.MAX_VALUE, Int.MAX_VALUE).totalOwners)
    }

    @Test
    fun `routine contention stale close and shutdown never admit beyond the configured unstarted owners`() {
        val journal = ComplaintJournalConfigurationV1.of(InitialLiveJournalTestFixture.declaration())
        val lanes = JournalPublicationLanesV1(journal)
        val admitted = racePersistenceAdmissions { lanes.tryRoutinePublication() }.filterNotNull()
        assertTrue(admitted.size in 1..journal.declaration().limits.capacity.routinePublicationLanes)
        assertEquals(admitted.size.toLong(), lanes.activeOwners().totalOwners)
        admitted.forEach { old -> racePersistenceAdmissions { old.close() } }
        assertEquals(0L, lanes.activeOwners().totalOwners)
        val replacement = checkNotNull(lanes.tryRoutinePublication())
        admitted.forEach { it.close() }
        assertEquals(JournalPublicationLaneSnapshotV1(1, 0), lanes.activeOwners())
        lanes.close()
        replacement.close()
        lanes.close()
        assertEquals(0L, lanes.activeOwners().totalOwners)
        assertNull(lanes.tryRoutinePublication())
    }
}
