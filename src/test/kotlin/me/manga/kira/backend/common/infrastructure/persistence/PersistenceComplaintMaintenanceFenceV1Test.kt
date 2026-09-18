package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PersistenceComplaintMaintenanceFenceV1Test {
    @Test
    fun `exact forty potential writers participate while thirty observations and three SOURCE paths do not`() {
        val writers = setOf(
            PersistencePhasePath.COMPLAINT_GRANT_CLEANUP,
            PersistencePhasePath.COMPLAINT_STEP_UP_ISSUANCE,
            PersistencePhasePath.COMPLAINT_ADMIN_AUDIT,
            PersistencePhasePath.COMPLAINT_DELETION_ADMIN_AUDIT,
            PersistencePhasePath.COMPLAINT_RECOVERY_SETTLEMENT,
            PersistencePhasePath.COMPLAINT_TEST_RESERVE_SPEND,
            PersistencePhasePath.COMPLAINT_INSTALLATION_ENROLLMENT,
            PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_REFRESH,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_AUTHORIZE,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_VERIFY,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHORIZE,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY,
            PersistencePhasePath.COMPLAINT_OWNER_CREATE,
            PersistencePhasePath.COMPLAINT_OWNER_REPLY,
            PersistencePhasePath.COMPLAINT_OWNER_EDIT,
            PersistencePhasePath.COMPLAINT_DELETION_MUTATION,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_COMPLETE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PROJECT,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PREPARE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_SIGNATURE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_COMPLETE,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_PROJECT,
            PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_ACQUIRE,
            PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RENEW,
            PersistencePhasePath.COMPLAINT_COORDINATOR_LEASE_RELINQUISH,
            PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_REQUEST,
            PersistencePhasePath.COMPLAINT_CUTOFF_VERIFY,
            PersistencePhasePath.COMPLAINT_SEAL_PREPARE,
            PersistencePhasePath.COMPLAINT_DESIRED_BOOTSTRAP,
            PersistencePhasePath.COMPLAINT_DESIRED_CLOSE,
            PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE,
            PersistencePhasePath.COMPLAINT_DESIRED_SIGNED_GENESIS_FIRST,
        )
        assertEquals(73, PersistencePhasePath.entries.size)
        assertEquals(40, writers.size)
        assertEquals(writers, PersistencePhasePath.entries.filter { it.complaintMaintenanceWriter }.toSet())
        val source = setOf(
            PersistencePhasePath.SOURCE_GRANT_CLEANUP,
            PersistencePhasePath.SOURCE_STEP_UP_SNAPSHOT,
            PersistencePhasePath.SOURCE_STEP_UP_ISSUANCE,
        )
        assertEquals(source, PersistencePhasePath.entries.filter { it.source }.toSet())
        assertFalse(source.any { it.complaintMaintenanceWriter })
        assertEquals(30, PersistencePhasePath.entries.count { !it.source && !it.complaintMaintenanceWriter })
    }

    @Test
    fun `guard dispatch caps at seventy five and later dispatch cannot restart the hundred millisecond prefix`() {
        val clock = MaintenanceBudgetClock()
        val work = PersistenceTimeBudget.start(2_000, clock)
        val fence = PersistenceComplaintMaintenanceFenceBudgetV1(work)
        val firstDispatch = fence.dispatchBudget()
        assertEquals(75L, fence.remainingMillis())
        assertEquals(75L, firstDispatch.remainingMillis(2_000))
        clock.now = 60_000_000
        val secondDispatch = fence.dispatchBudget()
        assertEquals(15L, firstDispatch.remainingMillis(2_000))
        assertEquals(40L, secondDispatch.remainingMillis(2_000))
        clock.now = 75_000_000
        assertExpired { firstDispatch.remainingMillis(2_000) }
        assertEquals(25L, fence.remainingMillis())
        assertEquals(25L, secondDispatch.remainingMillis(2_000))
        clock.now = 100_000_000
        assertExpired { fence.remainingMillis() }
        assertExpired { secondDispatch.remainingMillis(2_000) }
        assertExpired { fence.dispatchBudget() }
        assertEquals(1_900L, work.remainingMillis(2_000))
    }

    @Test
    fun `earlier original work deadline clips settings and native dispatch without a new phase allowance`() {
        val clock = MaintenanceBudgetClock()
        val work = PersistenceTimeBudget.start(2_000, clock)
        clock.now = 1_990_000_000
        val fence = PersistenceComplaintMaintenanceFenceBudgetV1(work)
        val dispatch = fence.dispatchBudget()
        assertEquals(10L, fence.remainingMillis())
        assertEquals(10L, dispatch.remainingMillis(2_000))
        clock.now += 9_000_000
        assertEquals(1L, fence.remainingMillis())
        assertEquals(1L, dispatch.remainingMillis(2_000))
        clock.now++
        assertExpired { fence.remainingMillis() }
        assertExpired { dispatch.remainingMillis(2_000) }
        assertExpired { fence.dispatchBudget() }
    }

    @Test
    fun `fractional last millisecond exact expiry and overrun cannot become zero unlimited timeouts`() {
        listOf(99_000_001L, 99_999_999L, 100_000_000L, 100_000_001L).forEach { elapsed ->
            val clock = MaintenanceBudgetClock()
            val fence = PersistenceComplaintMaintenanceFenceBudgetV1(PersistenceTimeBudget.start(2_000, clock))
            clock.now = elapsed
            assertExpired { fence.remainingMillis() }
            assertExpired { fence.dispatchBudget() }
        }
    }

    private fun assertExpired(work: () -> Any) {
        assertEquals(PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED, assertThrows<PersistenceBoundaryException> { work() }.code)
    }

    private class MaintenanceBudgetClock(var now: Long = 0) : PersistenceNanoClock {
        override fun nanoTime(): Long = now
    }
}
