package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PersistenceComplaintMaintenanceFenceV1Test {
    @Test
    fun `every admitted phase has an explicit maintenance lock classification`() {
        val oldWriters = setOf(
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
        val testWriters = setOf(
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_LEASE_ACQUIRE,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARE,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PREPARED_RELOAD,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SIGNATURE,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SIGNED_RELOAD,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_DELIVERY_RELOAD,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_COMPLETE,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PENDING_RELOAD,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECT_RELOAD,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECT,
            PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_PROJECTED_RELOAD,
        )
        val adminWriters = setOf(
            PersistencePhasePath.COMPLAINT_ADMIN_EDIT,
            PersistencePhasePath.COMPLAINT_ADMIN_STATUS,
            PersistencePhasePath.COMPLAINT_ADMIN_BATCH_STATUS,
        )
        val adminDeletionWriters = setOf(
            PersistencePhasePath.COMPLAINT_ADMIN_DELETE_AUTHORIZE,
            PersistencePhasePath.COMPLAINT_ADMIN_DELETE_VERIFY,
            PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY,
        )
        val terminalWriters = setOf(
            PersistencePhasePath.COMPLAINT_TEST_ORDINARY_SEAL,
            PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PREPARE,
            PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_PUBLICATION,
            PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_PUBLICATION,
            PersistencePhasePath.COMPLAINT_TEST_ORDINARY_DRAIN,
        )
        val registration = setOf(
            PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_REGISTRATION,
            PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_RECOVERY_REGISTRATION,
        )
        val initialAdmission = setOf(
            PersistencePhasePath.COMPLAINT_TEST_INITIAL_ADMISSION_CAPTURE,
            PersistencePhasePath.COMPLAINT_TEST_INITIAL_ADMISSION_RELEASE,
        )
        val sealing = setOf(PersistencePhasePath.COMPLAINT_TEST_RUN_SEAL, PersistencePhasePath.COMPLAINT_TEST_RUN_SEALED_AUDIT)
        val writers = oldWriters + testWriters + adminWriters + adminDeletionWriters + terminalWriters + registration + initialAdmission + sealing
        assertEquals(113, PersistencePhasePath.entries.size)
        assertEquals(40, oldWriters.size)
        assertEquals(11, testWriters.size)
        assertEquals(3, adminWriters.size)
        assertEquals(3, adminDeletionWriters.size)
        assertEquals(5, terminalWriters.size)
        assertEquals(2, registration.size)
        assertEquals(2, initialAdmission.size)
        assertEquals(68, writers.size)
        assertEquals(writers, PersistencePhasePath.entries.filter { it.complaintMaintenanceWriter }.toSet())
        val source = setOf(
            PersistencePhasePath.SOURCE_GRANT_CLEANUP,
            PersistencePhasePath.SOURCE_STEP_UP_SNAPSHOT,
            PersistencePhasePath.SOURCE_STEP_UP_ISSUANCE,
        )
        assertEquals(source, PersistencePhasePath.entries.filter { it.source }.toSet())
        assertFalse(source.any { it.complaintMaintenanceWriter })
        val oldObservations = setOf(
            PersistencePhasePath.COMPLAINT_STEP_UP_SNAPSHOT,
            PersistencePhasePath.COMPLAINT_INSTALLATION_SESSION_PREFLIGHT,
            PersistencePhasePath.COMPLAINT_INSTALLATION_DELETION_PREFLIGHT,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_RELOAD,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_AUTHENTICATION,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_PREFLIGHT,
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_STATUS,
            PersistencePhasePath.COMPLAINT_INSTALLATION_CURRENT_STATE,
            PersistencePhasePath.COMPLAINT_OWNER_HISTORY_AUTHENTICATION,
            PersistencePhasePath.COMPLAINT_OWNER_HISTORY_PAGE,
            PersistencePhasePath.COMPLAINT_OWNER_DETAIL,
            PersistencePhasePath.COMPLAINT_OWNER_OPERATION_AUTHENTICATION,
            PersistencePhasePath.COMPLAINT_OWNER_CREATE_PREFLIGHT,
            PersistencePhasePath.COMPLAINT_OWNER_REPLY_PREFLIGHT,
            PersistencePhasePath.COMPLAINT_OWNER_OPERATION_STATUS,
            PersistencePhasePath.COMPLAINT_OWNER_EDIT_AUTHENTICATION,
            PersistencePhasePath.COMPLAINT_OWNER_EDIT_PREFLIGHT,
            PersistencePhasePath.COMPLAINT_OWNER_EDIT_STATUS,
            PersistencePhasePath.COMPLAINT_DELETION_FENCE_PREFIX,
            PersistencePhasePath.COMPLAINT_DELETION_CONTROL_SNAPSHOT,
            PersistencePhasePath.COMPLAINT_CATALOG_SNAPSHOT,
            PersistencePhasePath.COMPLAINT_CATALOG_PROJECTED_HEAD,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_FINAL_READ,
            PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_ACTIVATION_READ,
            PersistencePhasePath.COMPLAINT_EPOCH_ROTATION_RESUME,
            PersistencePhasePath.COMPLAINT_CUTOFF_CONTROL,
            PersistencePhasePath.COMPLAINT_CUTOFF_PAGE,
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PUBLISH_RECHECK,
        )
        val adminObservations = setOf(
            PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION,
            PersistencePhasePath.COMPLAINT_ADMIN_SEARCH,
            PersistencePhasePath.COMPLAINT_ADMIN_DETAIL,
            PersistencePhasePath.COMPLAINT_ADMIN_STATS,
            PersistencePhasePath.COMPLAINT_ADMIN_EDIT_PREFLIGHT,
            PersistencePhasePath.COMPLAINT_ADMIN_STATUS_PREFLIGHT,
            PersistencePhasePath.COMPLAINT_ADMIN_BATCH_STATUS_PREFLIGHT,
            PersistencePhasePath.COMPLAINT_ADMIN_DELETE_RELOAD,
            PersistencePhasePath.COMPLAINT_ADMIN_DELETE_PREFLIGHT,
        )
        val terminalObservations = setOf(PersistencePhasePath.COMPLAINT_TEST_INSTALLATION_MANIFEST_VERIFY, PersistencePhasePath.COMPLAINT_TEST_RUN_PURGE_VERIFY)
        val snapshot = PersistencePhasePath.COMPLAINT_CATALOG_TEST_RUN_ACTIVATION_SNAPSHOT
        assertEquals(30, oldObservations.size)
        assertEquals(9, adminObservations.size)
        assertEquals(2, terminalObservations.size)
        assertEquals(42, (oldObservations + adminObservations + terminalObservations + snapshot).size)
        assertEquals(oldObservations + adminObservations + terminalObservations + snapshot, PersistencePhasePath.entries.filter { !it.source && !it.complaintMaintenanceWriter }.toSet())
        assertEquals(testWriters + snapshot, PersistencePhasePath.entries.filter { it.catalogTestRunActivation }.toSet())
        assertTrue(snapshot.readOnly)
        assertFalse(testWriters.any { it.readOnly })
        assertFalse(adminWriters.any { it.readOnly })
        assertFalse(registration.any { it.readOnly }) // PostgreSQL SELECT FOR UPDATE requires a writable transaction, not DML permission.
        assertFalse(registration.any { it.catalogTestRunActivation }) // Separate normal-root ownership, never a named-projector route.
        assertEquals(initialAdmission, PersistencePhasePath.entries.filter { it.testInitialAdmission }.toSet())
        assertFalse(initialAdmission.any { it.readOnly || it.catalogTestRunActivation || it.testRunSealing })
        assertEquals(sealing, PersistencePhasePath.entries.filter { it.testRunSealing }.toSet())
        assertFalse(sealing.any { it.readOnly || it.catalogTestRunActivation })
        assertTrue(PersistencePhasePath.COMPLAINT_ADMIN_EDIT_PREFLIGHT.readOnly)
        assertTrue(PersistencePhasePath.COMPLAINT_ADMIN_STATUS_PREFLIGHT.readOnly)
        assertTrue(PersistencePhasePath.COMPLAINT_ADMIN_BATCH_STATUS_PREFLIGHT.readOnly)
    }

    @Test
    fun `settings M and fresh gate dispatch cap at seventy five without restarting the hundred millisecond prefix`() {
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
        val gateDispatch = fence.dispatchBudget()
        assertEquals(25L, gateDispatch.remainingMillis(2_000))
        clock.now = 99_000_000
        assertEquals(1L, gateDispatch.remainingMillis(2_000))
        clock.now = 100_000_000
        assertExpired { fence.remainingMillis() }
        assertExpired { secondDispatch.remainingMillis(2_000) }
        assertExpired { gateDispatch.remainingMillis(2_000) }
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
