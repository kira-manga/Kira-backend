package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.catalog.CatalogTestRunTerminalFixtureV1
import me.manga.kira.backend.complaint.catalog.TerminalCatalogQueueHistoryV1
import me.manga.kira.backend.complaint.catalog.withNonemptyActiveHistoryTerminalCatalogRun
import me.manga.kira.backend.complaint.catalog.withTerminalCatalogRun
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureExceptionV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureSqlV1
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Real SQL completion cuts, never an injected COMMITTED/UNKNOWN or synthetic successful E. NOT_RUN. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestRunErasureRecoveryIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestRunErasureRecoveryIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun lostCommittedFirstBatchCannotRepeatRefundAndFreshOriginFinishesExactRemainingRows() = withFixture { tls ->
        withNonemptyActiveHistoryTerminalCatalogRun(tls, TerminalCatalogQueueHistoryV1.SETTLED) { active ->
            val f = active.catalog
            TestRunErasureFixtureV1(f).use { h ->
                val e = h.projectE(); val before = h.baseline(); val beforeImage = h.image(); val beforeNative = h.nativeImage()
                var selected: PersistencePhaseContext? = null; var committed = false
                h.jdbc.after = { call -> if (selected == null && call.path === PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_BATCH &&
                    call.sql == TestRunErasureSqlV1.releaseLease) {
                    selected = call.phase
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() { committed = true; error("Synthetic erasure first-batch acknowledgement lost.") }
                    })
                } }
                val original = h.child(e)
                try { assertThrows<TestRunErasureExceptionV1> { original.erase(h.request()) } } finally { h.jdbc.after = {} }
                assertTrue(committed); assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(selected).databaseOutcome())
                h.assertReleased(false); assertEquals("PURGING", h.state()); assertNotEquals(beforeImage, h.image())
                assertTrue(h.removablePrice().fitsWithin(before.removable)); assertNotEquals(before.removable, h.removablePrice())
                assertEquals(before.queue, h.rows("complaint_test_active_queue_observations"))
                val partial = h.image(); val partialNative = h.nativeImage()
                assertThrows<RuntimeException> { original.erase(h.request()) }; assertThrows<RuntimeException> { e.beginErasure(h.ownership, h.jdbc) }
                assertEquals(partial, h.image()); assertEquals(partialNative, h.nativeImage())
                withFreshTestRunErasure(f) { fresh ->
                    val resumed = fresh.fresh()
                    assertEquals(TestRunErasureResultV1.PURGED, resumed.erase(fresh.request()))
                    fresh.assertReleased(); fresh.assertPurged(before); fresh.jdbc.assertOrder()
                    assertThrows<RuntimeException> { fresh.fresh() } // Same new-process claim is not renewable.
                }
                assertEquals(beforeNative.signs, h.nativeImage().signs); assertEquals(beforeNative.puts, h.nativeImage().puts)
                assertEquals(beforeNative.journalWrites, h.nativeImage().journalWrites)
                assertThrows<RuntimeException> { original.erase(h.request()) }
            }
        }
    }

    @Test fun actualDeferredCommitUnknownRollsBackItsBatchAndFreshOriginNeverSpendsAnUncommittedRefund() = withFixture { tls ->
        withNonemptyActiveHistoryTerminalCatalogRun(tls) { active ->
            val f = active.catalog
            TestRunErasureFixtureV1(f).use { h ->
                val e = h.projectE(); val before = h.baseline(); val image = h.image()
                var selected: PersistencePhaseContext? = null
                h.jdbc.after = { call -> if (selected == null && call.path === PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_BATCH &&
                    call.sql == TestRunErasureSqlV1.releaseLease) {
                    selected = call.phase
                    // This participates in the ACTUAL already held deletion transaction. PostgreSQL
                    // itself rejects commit; neither the original nor its outcome is replaced.
                    val actual = JdbcTemplate(h.runtime.pools.deletion)
                    actual.execute("CREATE TEMP TABLE kira_erasure_commit_cut (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                    assertEquals(2, actual.update("INSERT INTO kira_erasure_commit_cut VALUES (1),(1)"))
                } }
                val original = h.child(e)
                try { assertThrows<TestRunErasureExceptionV1> { original.erase(h.request()) } } finally { h.jdbc.after = {} }
                assertEquals(PersistenceDatabaseOutcome.UNKNOWN, checkNotNull(selected).databaseOutcome()); h.assertReleased(false)
                assertEquals(image, h.image(), "Rolled-back physical rows, run, scoped lease and all counters must remain exactly unchanged.")
                withFreshTestRunErasure(f) { fresh ->
                    assertEquals(TestRunErasureResultV1.PURGED, fresh.fresh().erase(fresh.request()))
                    fresh.assertReleased(); fresh.assertPurged(before)
                }
                assertThrows<RuntimeException> { original.erase(h.request()) }
            }
        }
    }

    @Test fun lostCommittedFinalIsAlreadyPurgedAndFreshOriginDoesOnlyNativeVerificationWithoutMvccChurn() = withFixture { tls ->
        withNonemptyActiveHistoryTerminalCatalogRun(tls, TerminalCatalogQueueHistoryV1.SETTLED) { active ->
            val f = active.catalog
            TestRunErasureFixtureV1(f).use { h ->
                val e = h.projectE(); val before = h.baseline()
                var selected: PersistencePhaseContext? = null; var committed = false
                h.jdbc.after = { call -> if (call.sql == TestRunErasureSqlV1.purgeRun) {
                    assertEquals(null, selected); selected = call.phase
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() { committed = true; error("Synthetic erasure final acknowledgement lost.") }
                    })
                } }
                val original = h.child(e)
                try { assertThrows<TestRunErasureExceptionV1> { original.erase(h.request()) } } finally { h.jdbc.after = {} }
                assertTrue(committed); assertEquals(PersistenceDatabaseOutcome.COMMITTED, checkNotNull(selected).databaseOutcome())
                h.assertReleased(false); h.assertPurged(before)
                val purged = h.image(); val files = f.files(); val native = h.nativeImage()
                withFreshTestRunErasure(f) { fresh ->
                    assertEquals(TestRunErasureResultV1.PURGED_HISTORY_VERIFIED_ONLY, fresh.fresh().erase(fresh.request()))
                    fresh.assertReleased(); assertReadOnlyErasure(fresh)
                    assertEquals(purged, fresh.image()); assertEquals(files, f.files())
                    assertEquals(native.terminalGets + 2 * f.d.targets.size, fresh.nativeImage().terminalGets)
                    assertEquals(native.ordinaryGets + 2, fresh.nativeImage().ordinaryGets)
                    assertEquals(native.signs, fresh.nativeImage().signs); assertEquals(native.puts, fresh.nativeImage().puts)
                    assertEquals(native.journalWrites, fresh.nativeImage().journalWrites)
                }
                assertThrows<RuntimeException> { original.erase(h.request()) }; assertEquals(purged, h.image())
            }
        }
    }

    @Test fun purgedTwoSealHistoryFreshlyReadsBothNativePairsWithoutRecreatingControlSidecarsOrAudit() = withFixture { tls ->
        withTerminalCatalogRun(tls, enrolled = true) { f -> TestRunErasureFixtureV1(f).use { h ->
            h.successful(active = false, queue = false, nonempty = false)
            val before = h.image(); val native = h.nativeImage()
            withFreshTestRunErasure(f) { fresh ->
                assertEquals(TestRunErasureResultV1.PURGED_HISTORY_VERIFIED_ONLY, fresh.fresh().erase(fresh.request()))
                fresh.assertReleased(); assertReadOnlyErasure(fresh); assertEquals(before, fresh.image())
                assertEquals(native.terminalGets + 2 * f.d.targets.size, fresh.nativeImage().terminalGets)
                assertTrue(fresh.nativeImage().catalogRequests > native.catalogRequests)
            }
        } }
    }

    @Test fun purgedHistoryStillRejectsMissingOriginalAObjectWithoutRecreatingItsDeletedSqlSidecar() = withFixture { tls ->
        withNonemptyActiveHistoryTerminalCatalogRun(tls) { active ->
            val f = active.catalog
            TestRunErasureFixtureV1(f).use { h ->
                h.successful(active = true, queue = false, nonempty = true)
                val before = h.image(); val old = f.f.sealHttp.terminalInventoryListing
                f.f.sealHttp.terminalInventoryListing = { pass, objects -> old(pass, objects).filter { it.key != active.initialSeal.objectRef.objectKey } }
                try { withFreshTestRunErasure(f) { fresh ->
                    val original = fresh.fresh()
                    assertThrows<TestRunErasureExceptionV1> { original.erase(fresh.request()) }
                    fresh.assertReleased(false); assertReadOnlyErasure(fresh); assertEquals(before, fresh.image())
                } } finally { f.f.sealHttp.terminalInventoryListing = old }
            }
        }
    }

    @Test fun olderRestoreMissingRetainedRunIsRefusedRatherThanInventingRunProjectionOrCredentials() = withFixture { tls ->
        withTerminalCatalogRun(tls) { f -> TestRunErasureFixtureV1(f).use { h ->
            h.successful(active = false, queue = false, nonempty = false)
            // Negative-only older-restore specimen AFTER genuine PURGED. No successful recovery
            // is fabricated, no guard is disabled and this missing-row subset is not implemented.
            assertEquals(1, h.observer.update("DELETE FROM complaint_test_runs WHERE data_scope_id = ?", h.scope))
            val before = h.image(); val native = h.nativeImage()
            withFreshTestRunErasure(f) { fresh ->
                assertThrows<TestRunErasureExceptionV1> { fresh.fresh().erase(fresh.request()) }
                fresh.assertReleased(false); assertReadOnlyErasure(fresh); assertEquals(before, fresh.image()); assertEquals(native, fresh.nativeImage())
            }
        } }
    }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true).use { it.bind(); action(it) }
}

/** Separately retained runtime/process, not E's pre-erasure PREPARED recovery or a copied result. */
internal fun withFreshTestRunErasure(catalog: CatalogTestRunTerminalFixtureV1, action: (TestRunErasureFixtureV1) -> Unit) {
    val runtime = VersionBoundPersistenceConnectedFixture(catalog.f.runtime.database, endpointPort = catalog.f.runtime.endpointPort,
        testIntake = catalog.evidence.intakeAssembly, testRegistrationPredecessor = catalog.f.runtime)
    var failed: Throwable? = null
    try {
        runtime.bind(); runtime.start()
        assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.catalogCoordinator.prepare())
        TestRunErasureFixtureV1(catalog, runtime).use(action)
    } catch (problem: Throwable) { failed = problem; throw problem }
    finally {
        try { runtime.closeWith(catalog.f.runtime) }
        catch (cleanup: Throwable) { val body = failed; if (body == null) throw cleanup; if (body !== cleanup) body.addSuppressed(cleanup) }
    }
}

internal fun assertReadOnlyErasure(f: TestRunErasureFixtureV1) {
    val mutation = Regex("(?m)^\\s*(?:UPDATE|DELETE|INSERT)\\b")
    assertFalse(f.jdbc.calls.any { mutation.containsMatchIn(it.sql) })
    assertTrue(f.jdbc.calls.all { it.path === PersistencePhasePath.COMPLAINT_TEST_RUN_ERASURE_READ })
}
