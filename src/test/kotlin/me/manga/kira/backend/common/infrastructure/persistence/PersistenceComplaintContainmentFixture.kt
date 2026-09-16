package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Real PG/Spring cleanup and complaint admission containment, NOT a residual-native interval or full D07 qualification. */
internal fun assertCommittedDeletionContainment(f: DeletionComplaintAuditFixture) {
    val original = f.ownership.enterComplaintDeletionFencePrefix()
    val permit = ownedCutField(original, "permit") as LocalPersistencePermit
    val sentinelKey = Any()
    val sentinel = Any()
    var sentinelBound = false
    try {
        try {
            original.begin()
            f.checkpoint(DeletionAuditStep.BEGIN)
            original.commit()
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, original.databaseOutcome())
            assertTrue(PersistencePhaseOwnership.springConnectionFree())
            TransactionSynchronizationManager.bindResource(sentinelKey, sentinel)
            sentinelBound = true
        } finally {
            original.finish() // Real finalizer observes the real Spring resource map; no stage/clock/native mutation.
        }
        assertTrue(original.quarantined())
        assertSame(original, PersistencePhaseOwnership.current())
        assertEquals(1, f.admission.activeOwners().totalOwners)
        assertTrue(f.observations.single().second.lease.completion.quiescent())
        assertFalse(permit.releaseCompleted())
        val failure = original.failureException(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED)
        assertEquals(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED, failure.code)
        assertEquals(PersistenceDatabaseOutcome.COMMITTED, failure.databaseOutcome)
        assertFalse(failure.cleanupProven)

        OwnedCallerTestScope().use { callers ->
            callers.launch {
                requireConnectionFree() // This thread's empty Spring state is not the original caller's proof.
                val foreignFailure = assertThrows<PersistencePhaseException> { original.reconcileQuarantine() }
                // Caller refusal cannot replace the original phase's sticky unresolved-cleanup failure or committed outcome.
                assertEquals(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED, foreignFailure.code)
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, foreignFailure.databaseOutcome)
                assertFalse(foreignFailure.cleanupProven)
                assertComplaintEntryRefused(f.base.ordinary.ownership::enterComplaintAdminAudit)
                assertComplaintEntryRefused(f.ownership::enterComplaintDeletionFencePrefix)
                assertEquals(0, f.base.ordinary.newExecutor().cleanupSourceGrants())
                assertEquals(1, f.base.ordinary.jdbc.queryForObject("SELECT 1", Int::class.java)) // Unscoped compatibility stays open.
                requireConnectionFree()
                assertComplaintEntryRefused(f.base.ordinary.ownership::enterComplaintAdminAudit)
                assertComplaintEntryRefused(f.ownership::enterComplaintDeletionFencePrefix)
            }.value()
        }
        assertSame(sentinel, TransactionSynchronizationManager.getResource(sentinelKey))
        assertSame(original, PersistencePhaseOwnership.current())
        assertFalse(permit.releaseCompleted())
        assertEquals(1, f.admission.activeOwners().totalOwners)
        assertEquals(0, f.base.ordinary.admission.activeOwners())
        assertFalse(f.base.ordinary.ownedPool.scope.owner.snapshot().shutdownRequested)
    } finally {
        if (sentinelBound) {
            assertSame(sentinel, TransactionSynchronizationManager.getResource(sentinelKey))
            assertSame(sentinel, TransactionSynchronizationManager.unbindResource(sentinelKey))
        }
        requireConnectionFree() // Only this original caller can now prove cleanup and complete the exact release/clear.
    }
    assertTrue(permit.releaseCompleted())
    assertTrue(original.failureException(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED).cleanupProven)
    assertFalse(original.quarantined())
    assertNull(PersistencePhaseOwnership.current())
    f.assertReleased()
    OwnedCallerTestScope().use { callers ->
        callers.launch {
            assertUnusedEntryCompletes(f.base.ordinary.ownership::enterComplaintAdminAudit)
            assertUnusedEntryCompletes(f.ownership::enterComplaintDeletionFencePrefix)
        }.value()
    }
    f.assertReleased()
}

/** Real spare ordinary capacity distinguishes complaint containment from existing source-origin local health refusal. */
internal fun assertOrdinaryComplaintSourceCompatibility(f: OrdinarySourceGrantCleanupFixture) {
    assertEquals(2, f.admission.ownerLimit)
    for (sourceIncident in listOf(false, true)) {
        // Prepared-only control: no invented transaction outcome. The first test covers a real committed transaction.
        val original = if (sourceIncident) f.ownership.enterSourceGrantCleanup() else f.ownership.enterComplaintAdminAudit()
        val permit = ownedCutField(original, "permit") as LocalPersistencePermit
        val sentinelKey = Any()
        val sentinel = Any()
        var sentinelBound = false
        try {
            try {
                TransactionSynchronizationManager.bindResource(sentinelKey, sentinel)
                sentinelBound = true
            } finally {
                original.finish()
            }
            assertTrue(original.quarantined())
            assertFalse(permit.releaseCompleted())
            assertEquals(1, f.admission.activeOwners())
            OwnedCallerTestScope().use { callers ->
                callers.launch {
                    if (sourceIncident) {
                        assertComplaintEntryRefused(f.ownership::enterSourceGrantCleanup)
                    } else {
                        assertEquals(0, f.newExecutor().cleanupSourceGrants()) // Same actual ordinary pool and owner, P=3.
                    }
                    assertComplaintEntryRefused(f.ownership::enterComplaintAdminAudit)
                }.value()
            }
            assertEquals(1, f.admission.activeOwners())
            assertSame(original, PersistencePhaseOwnership.current())
            assertFalse(permit.releaseCompleted())
        } finally {
            if (sentinelBound) {
                assertSame(sentinel, TransactionSynchronizationManager.getResource(sentinelKey))
                assertSame(sentinel, TransactionSynchronizationManager.unbindResource(sentinelKey))
            }
            requireConnectionFree()
        }
        assertTrue(permit.releaseCompleted())
        assertEquals(0, f.admission.activeOwners())
        assertUnusedEntryCompletes(f.ownership::enterComplaintAdminAudit)
    }
}

private fun assertComplaintEntryRefused(entry: () -> PersistencePhaseContext) {
    var unexpected: PersistencePhaseContext? = null
    try {
        val failure = assertThrows<PersistencePhaseException> { unexpected = entry() }
        assertEquals(PersistencePhaseFailureCode.CLEANUP_UNRESOLVED, failure.code)
    } finally {
        unexpected?.finish() // Even an unexpected admission is retained for exact cleanup before reporting the failure.
    }
    assertNull(PersistencePhaseOwnership.current())
    assertFalse(LocalPersistencePermit.callerHasOutstandingPermit())
    requireConnectionFree()
}

private fun assertUnusedEntryCompletes(entry: () -> PersistencePhaseContext) {
    val phase = entry()
    try {
        assertSame(phase, PersistencePhaseOwnership.current())
    } finally {
        phase.finish()
    }
    assertTrue(phase.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
    requireConnectionFree()
}
