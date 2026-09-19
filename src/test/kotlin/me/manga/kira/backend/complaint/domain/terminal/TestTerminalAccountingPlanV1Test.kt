package me.manga.kira.backend.complaint.domain.terminal

import me.manga.kira.backend.complaint.domain.ComplaintCapacityBalance
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityConfiguration
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter.APP_INSTALLATIONS
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter.AUDIT_ROWS
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter.CATALOG_MUTATIONS
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter.COMPLAINT_ROWS
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter.INSTALLATION_IDS
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter.JOURNAL_CONTROL
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter.JOURNAL_PUBLICATIONS
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter.RECOVERY_RESERVATIONS
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter.RESOURCE_IDS
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter.SCAN_ENTRIES
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter.SCAN_RUNS
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter.STORAGE_BYTES
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter.TEST_RUNS
import me.manga.kira.backend.complaint.domain.ComplaintCapacityException
import me.manga.kira.backend.complaint.domain.ComplaintCapacityFailureCode
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Logical accounting only: no row sizing, accepted activation, B-limit, ownership or replay proof. */
class TestTerminalAccountingPlanV1Test {
    @Test
    fun promotedChargesKeepCounterAndStorageObligationsSeparate() {
        val charges = TestTerminalCapacityChargesV1
        val expected = listOf(
            charges.INSTALLATION_SHARE to vector(32_768, INSTALLATION_IDS to 1L, APP_INSTALLATIONS to 1L),
            charges.AUDIT to vector(65_536, AUDIT_ROWS to 1L),
            charges.PUBLICATION_WITH_RESERVATION to vector(278_528, JOURNAL_PUBLICATIONS to 1L, RECOVERY_RESERVATIONS to 1L),
            charges.SCOPED_CATALOG to vector(3_219_968, CATALOG_MUTATIONS to 1L),
            charges.ACTIVE_RUN to vector(5632, TEST_RUNS to 1L),
            charges.TERMINAL_RUN_DELTA to vector(1_068_608),
            charges.CONTROL to vector(1_599_808, JOURNAL_CONTROL to 1L),
            charges.SYSTEM_NOTICE to vector(16_384, COMPLAINT_ROWS to 1L),
            charges.NOTICE_WITH_RESOURCE to vector(32_768, COMPLAINT_ROWS to 1L, RESOURCE_IDS to 1L),
            charges.SIDECAR to vector(1_340_736),
            charges.SCAN_RUN to vector(3776, SCAN_RUNS to 1L),
            charges.SCAN_ENTRY to vector(37_696, SCAN_ENTRIES to 1L),
        )
        expected.forEach { (actual, oracle) -> assertEquals(oracle, actual) }
        assertEquals(ComplaintCapacityCharges.INSTALLATION_ID + ComplaintCapacityCharges.INSTALLATION_CREDENTIAL, charges.INSTALLATION_SHARE)
        assertEquals(ComplaintCapacityCharges.INSTALLATION_ENROLLMENT, charges.INSTALLATION_SHARE + charges.AUDIT)
        assertEquals(ComplaintCapacityCharges.AUDIT, charges.AUDIT)
        assertEquals(OwnerDeleteAllCapacityCharges.PUBLICATION + OwnerDeleteAllCapacityCharges.RESERVATION, charges.PUBLICATION_WITH_RESERVATION)
        assertEquals(TestTerminalDurableStorageProfileV1.ROW, charges.SIDECAR)
        // Growth retains the one already-paid run slot; a sidecar is not another publication slot.
        assertEquals(vector(1_074_240, TEST_RUNS to 1L), charges.ACTIVE_RUN + charges.TERMINAL_RUN_DELTA)
    }

    @Test
    fun installationBoundsRetainZeroAndChunkEdgeObligations() {
        for ((installations, chunks) in listOf(0L to 0L, 1L to 1L, 499L to 1L, 500L to 1L, 501L to 2L, 2_048_000L to 4096L)) {
            val plan = TestTerminalAccountingPlanV1(installations, 1)
            assertEquals(chunks, plan.manifestChunkCount)
            assertEquals(chunks + 1, plan.terminalPublicationCount)
            assertEquals(16L, plan.maximumSealCount)
            assertEquals(chunks + 17, plan.maximumSidecarCount)
            assertEquals(2L, plan.noticeSeedCount)
            assertEquals(4L, plan.projectionAuditCount)
            assertEquals(installations + 3, plan.maximumTerminalAuditCount)
            assertEquals(installations + 7, plan.maximumLifecycleAuditCount)
            val stages = TestTerminalAuditStageV1.entries.associateWith { plan.maximumAuditCount(it) }
            assertEquals(
                mapOf(
                    TestTerminalAuditStageV1.NOTICE_CREATED to 2L,
                    TestTerminalAuditStageV1.RUN_ACTIVATED to 1L,
                    TestTerminalAuditStageV1.ACTIVATION_CATALOG_PROJECTED to 1L,
                    TestTerminalAuditStageV1.RUN_SEALED to 1L,
                    TestTerminalAuditStageV1.TERMINAL_CATALOG_PROJECTED to 1L,
                    TestTerminalAuditStageV1.RUN_PURGED to 1L,
                    TestTerminalAuditStageV1.INSTALLATION_DISPOSITION to installations,
                ),
                stages,
            )
            assertEquals(plan.maximumLifecycleAuditCount, stages.values.sum())
        }
        for (invalid in listOf(-1L, 2_048_001L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertEquals(
                TestTerminalFailureV1.LIMIT_EXCEEDED,
                assertThrows(TestTerminalExceptionV1::class.java) { TestTerminalAccountingPlanV1(invalid, 1) }.code,
            )
        }
    }

    @Test
    fun stagePartitionsAndPurgePromisePayEachObligationExactlyOnce() {
        val plan = TestTerminalAccountingPlanV1(501, 37)
        assertEquals(vector(3_219_968, CATALOG_MUTATIONS to 1L), plan.activationCatalogPrepareActual)
        assertEquals(
            vector(1_933_120, TEST_RUNS to 1L, JOURNAL_CONTROL to 1L, COMPLAINT_ROWS to 2L, RESOURCE_IDS to 2L, AUDIT_ROWS to 4L),
            plan.activationProjectionActual,
        )
        assertEquals(vector(16_416_768, INSTALLATION_IDS to 501L, APP_INSTALLATIONS to 501L), plan.enrollmentReserve)
        assertEquals(vector(557_056, JOURNAL_PUBLICATIONS to 2L, RECOVERY_RESERVATIONS to 2L), plan.manifestPublicationsActualReserve)
        assertEquals(vector(278_528, JOURNAL_PUBLICATIONS to 1L, RECOVERY_RESERVATIONS to 1L), plan.purgePublicationActualReserve)
        assertEquals(vector(25_473_984), plan.sidecarActualReserve)
        assertEquals(vector(3_219_968, CATALOG_MUTATIONS to 1L), plan.terminalCatalogActualReserve)
        assertEquals(vector(32_964_608, AUDIT_ROWS to 503L), plan.terminalNonPurgeAuditReserve)
        assertEquals(vector(2_797_056, SCAN_RUNS to 2L, SCAN_ENTRIES to 74L), plan.scanPoolReserve)
        assertEquals(vector(1_134_144, AUDIT_ROWS to 1L), plan.purgeFuturePromise)
        assertEquals(ComplaintCapacityVector.ZERO, plan.manifestFuturePromise)
        val disjointSlices = listOf(
            plan.enrollmentReserve, plan.manifestPublicationsActualReserve, plan.purgePublicationActualReserve,
            plan.sidecarActualReserve, plan.terminalCatalogActualReserve, plan.terminalNonPurgeAuditReserve,
            plan.scanPoolReserve, plan.purgeFuturePromise,
        )
        assertEquals(disjointSlices.fold(ComplaintCapacityVector.ZERO) { sum, slice -> sum + slice }, plan.originalUnusedReserve)
        assertEquals(
            vector(
                82_842_112, INSTALLATION_IDS to 501L, APP_INSTALLATIONS to 501L, AUDIT_ROWS to 504L,
                CATALOG_MUTATIONS to 1L, JOURNAL_PUBLICATIONS to 3L, RECOVERY_RESERVATIONS to 3L,
                SCAN_RUNS to 2L, SCAN_ENTRIES to 74L,
            ),
            plan.originalUnusedReserve,
        )
        val zero = TestTerminalAccountingPlanV1(0, 1)
        assertEquals(
            vector(27_639_168, AUDIT_ROWS to 3L, CATALOG_MUTATIONS to 1L, JOURNAL_PUBLICATIONS to 1L, RECOVERY_RESERVATIONS to 1L, SCAN_RUNS to 2L, SCAN_ENTRIES to 2L),
            zero.originalUnusedReserve,
        )

        // Aggregate arithmetic fixture, not an activation/publication or future settlement writer.
        val digest = ByteArray(32) { 7 }
        val initialActual = plan.activationCatalogPrepareActual + plan.activationProjectionActual
        val hard = initialActual + plan.originalUnusedReserve
        val closed = ComplaintCapacityLedger(
            ComplaintCapacityConfiguration.of(digest, true),
            ComplaintCapacityBalance(hard, ComplaintCapacityVector.ZERO, ComplaintCapacityVector.ZERO, initialActual, testReserved = plan.originalUnusedReserve),
        )
        val publication = plan.purgePublicationActualReserve + TestTerminalCapacityChargesV1.SIDECAR
        val moved = closed.spendTestReserve(digest, publication, plan.purgeFuturePromise)
        assertEquals(ComplaintCapacityVector.ZERO, moved.balance.free)
        assertEquals(initialActual + publication, moved.balance.actual)
        assertEquals(plan.purgeFuturePromise, moved.balance.recoveryReserved)
        assertEquals(plan.originalUnusedReserve - publication - plan.purgeFuturePromise, moved.balance.testReserved)
        assertEquals(hard, moved.balance.free + moved.balance.actual + moved.balance.recoveryReserved + moved.balance.testReserved)
        val converted = moved.convertRecovery(digest, plan.purgeFuturePromise, plan.purgeFuturePromise)
        assertEquals(initialActual + publication + plan.purgeFuturePromise, converted.balance.actual)
        assertEquals(ComplaintCapacityVector.ZERO, converted.balance.recoveryReserved)
        assertEquals(moved.balance.testReserved, converted.balance.testReserved)
        assertEquals(1L, converted.balance.actual[TEST_RUNS])
        assertEquals(plan.originalUnusedReserve, closed.balance.testReserved)
    }

    @Test
    fun retainedVersionsAloneSizeTheTwoPassPoolWithoutBorrowingInstallationCounts() {
        val small = TestTerminalAccountingPlanV1(1, 1)
        val largeInstallationLimit = TestTerminalAccountingPlanV1(2_048_000, 1)
        val manyVersions = TestTerminalAccountingPlanV1(1, 37)
        assertEquals(vector(82_944, SCAN_RUNS to 2L, SCAN_ENTRIES to 2L), small.scanPoolReserve)
        assertEquals(small.scanPoolReserve, largeInstallationLimit.scanPoolReserve)
        assertEquals(vector(2_797_056, SCAN_RUNS to 2L, SCAN_ENTRIES to 74L), manyVersions.scanPoolReserve)
        assertEquals(manyVersions.scanPool.ceiling, manyVersions.scanPoolReserve)
        assertEquals(2L, manyVersions.scanPool.maximumRunRows)
        assertEquals(74L, manyVersions.scanPool.maximumEntryRows)
        assertEquals(small.originalUnusedReserve - small.scanPoolReserve, manyVersions.originalUnusedReserve - manyVersions.scanPoolReserve)
        for (invalid in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertFailure(ComplaintCapacityFailureCode.INVALID_CONFIGURATION) { TestTerminalScanPoolV1(invalid) }
            assertFailure(ComplaintCapacityFailureCode.INVALID_CONFIGURATION) { TestTerminalAccountingPlanV1(1, invalid) }
        }
        // B limits LP32 framed-fold bytes separately; these row charges do not assert B or object-headroom admission.
    }

    @Test
    fun retainedVersionArithmeticRejectsMultiplicationAndAggregateOverflow() {
        val maximumPoolVersions = (Long.MAX_VALUE - 7552L) / 75_392L
        assertEquals(maximumPoolVersions, TestTerminalScanPoolV1.MAX_RETAINED_VERSIONS_BY_STORAGE_ARITHMETIC)
        val maximumPool = TestTerminalScanPoolV1(maximumPoolVersions)
        assertEquals(
            vector(7552L + maximumPoolVersions * 75_392L, SCAN_RUNS to 2L, SCAN_ENTRIES to maximumPoolVersions * 2),
            maximumPool.ceiling,
        )
        for (invalid in listOf(maximumPoolVersions + 1, Long.MAX_VALUE)) {
            assertFailure(ComplaintCapacityFailureCode.AMOUNT_OVERFLOW) { TestTerminalScanPoolV1(invalid) }
            assertFailure(ComplaintCapacityFailureCode.AMOUNT_OVERFLOW) { TestTerminalAccountingPlanV1(0, invalid) }
        }
        // Independent N=0 oracle: reserve = 27,563,776 + 75,392*R bytes, not merely T.
        val maximumReserveVersions = (Long.MAX_VALUE - 27_563_776L) / 75_392L
        assertTrue(maximumReserveVersions < maximumPoolVersions)
        val maximumPlan = TestTerminalAccountingPlanV1(0, maximumReserveVersions)
        assertEquals(27_563_776L + maximumReserveVersions * 75_392L, maximumPlan.originalUnusedReserve[STORAGE_BYTES])
        assertEquals(maximumReserveVersions * 2, maximumPlan.originalUnusedReserve[SCAN_ENTRIES])
        assertTrue(TestTerminalScanPoolV1(maximumReserveVersions + 1).ceiling[STORAGE_BYTES] > 0)
        assertFailure(ComplaintCapacityFailureCode.AMOUNT_OVERFLOW) { TestTerminalAccountingPlanV1(0, maximumReserveVersions + 1) }
        assertFailure(ComplaintCapacityFailureCode.AMOUNT_OVERFLOW) { TestTerminalAccountingPlanV1(0, maximumPoolVersions) }
        // Dedicated credit rejection precedes addition even when adding two entries would overflow Long.
        assertFailure(ComplaintCapacityFailureCode.RESERVATION_EXCEEDED) {
            maximumPool.unusedAfterRecycle(maximumPool.ceiling, maximumPool.chargeFor(0, 2))
        }
    }

    @Test
    fun dedicatedPoolRequiresExactScanChargesAndNeverExceedsItsOwnCeiling() {
        val pool = TestTerminalScanPoolV1(3)
        val half = pool.chargeFor(1, 3)
        assertEquals(vector(233_728, SCAN_RUNS to 2L, SCAN_ENTRIES to 6L), pool.ceiling)
        assertEquals(vector(116_864, SCAN_RUNS to 1L, SCAN_ENTRIES to 3L), half)
        assertEquals(ComplaintCapacityVector.ZERO, pool.chargeFor(0, 0))
        assertEquals(pool.ceiling, pool.chargeFor(2, 6))
        assertEquals(pool.ceiling, pool.unusedAfterRecycle(half, half))
        assertEquals(pool.ceiling, pool.unusedAfterRecycle(ComplaintCapacityVector.ZERO, pool.ceiling))
        assertEquals(pool.ceiling, pool.unusedAfterRecycle(pool.ceiling, ComplaintCapacityVector.ZERO))
        for ((runs, entries) in listOf(-1L to 0L, 0L to -1L, Long.MIN_VALUE to 0L)) {
            assertFailure(ComplaintCapacityFailureCode.NEGATIVE_AMOUNT) { pool.chargeFor(runs, entries) }
        }
        for ((runs, entries) in listOf(3L to 6L, 2L to 7L, Long.MAX_VALUE to 0L, 0L to Long.MAX_VALUE)) {
            assertFailure(ComplaintCapacityFailureCode.RESERVATION_EXCEEDED) { pool.chargeFor(runs, entries) }
        }
        for (removed in listOf(pool.chargeFor(2, 3), pool.chargeFor(1, 4))) {
            assertFailure(ComplaintCapacityFailureCode.RESERVATION_EXCEEDED) { pool.unusedAfterRecycle(half, removed) }
        }
        val invalidPrices = listOf(
            half.with(STORAGE_BYTES, half[STORAGE_BYTES] - 1),
            half.with(STORAGE_BYTES, half[STORAGE_BYTES] + 1),
            vector(1),
        )
        for (unpriced in invalidPrices) {
            assertFailure(ComplaintCapacityFailureCode.INVALID_CONFIGURATION) { pool.unusedAfterRecycle(unpriced, ComplaintCapacityVector.ZERO) }
            assertFailure(ComplaintCapacityFailureCode.INVALID_CONFIGURATION) { pool.unusedAfterRecycle(ComplaintCapacityVector.ZERO, unpriced) }
        }
        for (counter in ComplaintCapacityCounter.entries.filterNot { it in setOf(SCAN_RUNS, SCAN_ENTRIES, STORAGE_BYTES) }) {
            val foreign = ComplaintCapacityVector.units(counter, 1)
            assertFailure(ComplaintCapacityFailureCode.INVALID_CONFIGURATION) { pool.unusedAfterRecycle(foreign, ComplaintCapacityVector.ZERO) }
            assertFailure(ComplaintCapacityFailureCode.INVALID_CONFIGURATION) { pool.unusedAfterRecycle(ComplaintCapacityVector.ZERO, foreign) }
        }
        assertEquals(vector(116_864, SCAN_RUNS to 1L, SCAN_ENTRIES to 3L), half)
    }

    private fun vector(storageBytes: Long, vararg units: Pair<ComplaintCapacityCounter, Long>): ComplaintCapacityVector =
        ComplaintCapacityVector.of(LongArray(22).also { values ->
            values[STORAGE_BYTES.storedOrdinal - 1] = storageBytes
            units.forEach { (counter, amount) -> values[counter.storedOrdinal - 1] = amount }
        })

    private fun assertFailure(code: ComplaintCapacityFailureCode, action: () -> Unit) {
        assertEquals(code, assertThrows(ComplaintCapacityException::class.java, action).code)
    }
}
