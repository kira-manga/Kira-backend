package me.manga.kira.backend.complaint.domain.terminal

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityFailureCode
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.rejectCapacity

/** Semantic cardinalities only; no retry, provider-call, chunk, seal, scan-pass or purge-batch audit stage. */
internal enum class TestTerminalAuditStageV1 {
    NOTICE_CREATED,
    RUN_ACTIVATED,
    ACTIVATION_CATALOG_PROJECTED,
    RUN_SEALED,
    TERMINAL_CATALOG_PROJECTED,
    RUN_PURGED,
    INSTALLATION_DISPOSITION,
}

/**
 * Fixed finite policy and checked declarations, NOT an admitted run, authenticated J, or complete
 * qualification. N=0 is useful to check boundary arithmetic; a genuine activation requires N>0.
 * R is independently supplied TEST-J retained-version capacity, never inferred from N. J's LP32
 * framed-byte ceiling, terminal catalog fit and durable scan-progress proof are separate gates.
 */
internal class TestTerminalAccountingPlanV1(val installationLimit: Long, val maximumRetainedVersions: Long) {
    val manifestChunkCount: Long = TestTerminalSyntaxV1.chunkCount(installationLimit).toLong()
    val maximumSealCount: Long = TestTerminalProfileV1.MAX_SEALS.toLong()
    val terminalPublicationCount: Long = TestTerminalSyntaxV1.add(manifestChunkCount, 1L)
    val maximumSidecarCount: Long = TestTerminalDurableStorageProfileV1.maximumIntentCount(installationLimit)
    val noticeSeedCount: Long = 2L
    val projectionAuditCount: Long = 4L
    val maximumTerminalAuditCount: Long = TestTerminalSyntaxV1.add(installationLimit, 3L)
    val maximumLifecycleAuditCount: Long = TestTerminalSyntaxV1.add(projectionAuditCount, maximumTerminalAuditCount)
    val scanPool: TestTerminalScanPoolV1 = TestTerminalScanPoolV1(maximumRetainedVersions)

    // Catalog prepare is already actual before projection, and remains separately charged history.
    val activationCatalogPrepareActual: ComplaintCapacityVector = TestTerminalCapacityChargesV1.SCOPED_CATALOG
    val activationProjectionActual: ComplaintCapacityVector = TestTerminalCapacityChargesV1.ACTIVE_RUN +
        TestTerminalCapacityChargesV1.CONTROL + TestTerminalCapacityChargesV1.NOTICE_WITH_RESOURCE.scaled(noticeSeedCount) +
        TestTerminalCapacityChargesV1.AUDIT.scaled(projectionAuditCount)

    // Eight disjoint slices of original unused reserve. Future physical rows are NOT actual yet.
    val enrollmentReserve: ComplaintCapacityVector = TestTerminalCapacityChargesV1.INSTALLATION_SHARE.scaled(installationLimit)
    val manifestPublicationsActualReserve: ComplaintCapacityVector =
        TestTerminalCapacityChargesV1.PUBLICATION_WITH_RESERVATION.scaled(manifestChunkCount)
    val purgePublicationActualReserve: ComplaintCapacityVector = TestTerminalCapacityChargesV1.PUBLICATION_WITH_RESERVATION
    val sidecarActualReserve: ComplaintCapacityVector = TestTerminalCapacityChargesV1.SIDECAR.scaled(maximumSidecarCount)
    val terminalCatalogActualReserve: ComplaintCapacityVector = TestTerminalCapacityChargesV1.SCOPED_CATALOG
    val terminalNonPurgeAuditReserve: ComplaintCapacityVector = TestTerminalCapacityChargesV1.AUDIT.scaled(maximumTerminalAuditCount - 1L)
    val scanPoolReserve: ComplaintCapacityVector = scanPool.ceiling
    val purgeFuturePromise: ComplaintCapacityVector = TestTerminalCapacityChargesV1.TERMINAL_RUN_DELTA + TestTerminalCapacityChargesV1.AUDIT

    // Deliberate policy, not an omitted price: each chunk's complete publication/recovery-row/sidecar
    // footprint is prepaid actual at insertion, and this policy creates no later per-chunk row.
    val manifestFuturePromise: ComplaintCapacityVector = ComplaintCapacityVector.ZERO

    // Eager checked addition rejects a plan whose other slices overflow even if R alone fits its pool.
    val originalUnusedReserve: ComplaintCapacityVector = enrollmentReserve + manifestPublicationsActualReserve +
        purgePublicationActualReserve + sidecarActualReserve + terminalCatalogActualReserve +
        terminalNonPurgeAuditReserve + scanPoolReserve + purgeFuturePromise

    fun maximumAuditCount(stage: TestTerminalAuditStageV1): Long = when (stage) {
        TestTerminalAuditStageV1.NOTICE_CREATED -> noticeSeedCount
        TestTerminalAuditStageV1.INSTALLATION_DISPOSITION -> installationLimit
        TestTerminalAuditStageV1.RUN_ACTIVATED,
        TestTerminalAuditStageV1.ACTIVATION_CATALOG_PROJECTED,
        TestTerminalAuditStageV1.RUN_SEALED,
        TestTerminalAuditStageV1.TERMINAL_CATALOG_PROJECTED,
        TestTerminalAuditStageV1.RUN_PURGED -> 1L
    }

    override fun toString(): String = "TestTerminalAccountingPlanV1(declaration, redacted)"

    companion object {
        const val PROFILE = "TEST_TERMINAL_ACCOUNTING_V1"
        const val MAXIMUM_DENIAL_CUTS = 2 * TestTerminalProfileV1.MAX_DENIAL_RANGES
        const val MAXIMUM_INVENTORY_SUMMARIES = 2 * MAXIMUM_DENIAL_CUTS
        const val MAXIMUM_EVIDENCE_REFERENCES = 2 * MAXIMUM_DENIAL_CUTS
    }
}

/**
 * One serial two-pass paid staging pool. Counts/charges below are pure declarations: neither this
 * type nor a successful recycle comparison attests to deleted rows, a retained witness or a fence.
 */
internal class TestTerminalScanPoolV1(val maximumRetainedVersions: Long) {
    init {
        if (maximumRetainedVersions <= 0L) rejectCapacity(ComplaintCapacityFailureCode.INVALID_CONFIGURATION)
        if (maximumRetainedVersions > MAX_RETAINED_VERSIONS_BY_STORAGE_ARITHMETIC) rejectCapacity(ComplaintCapacityFailureCode.AMOUNT_OVERFLOW)
    }

    val maximumRunRows: Long = PASS_COUNT
    val maximumEntryRows: Long = maximumRetainedVersions * PASS_COUNT
    val ceiling: ComplaintCapacityVector = TestTerminalCapacityChargesV1.SCAN_RUN.scaled(maximumRunRows) +
        TestTerminalCapacityChargesV1.SCAN_ENTRY.scaled(maximumEntryRows)

    fun chargeFor(runRows: Long, entryRows: Long): ComplaintCapacityVector {
        if (runRows < 0L || entryRows < 0L) rejectCapacity(ComplaintCapacityFailureCode.NEGATIVE_AMOUNT)
        if (runRows > maximumRunRows || entryRows > maximumEntryRows) rejectCapacity(ComplaintCapacityFailureCode.RESERVATION_EXCEEDED)
        return TestTerminalCapacityChargesV1.SCAN_RUN.scaled(runRows) + TestTerminalCapacityChargesV1.SCAN_ENTRY.scaled(entryRows)
    }

    /** Exact priced scan-only vectors; no re-credit beyond this declared dedicated pool, even if global actual is larger. */
    fun unusedAfterRecycle(unused: ComplaintCapacityVector, removed: ComplaintCapacityVector): ComplaintCapacityVector {
        requirePricedPoolCharge(unused)
        requirePricedPoolCharge(removed)
        if (!removed.fitsWithin(ceiling - unused)) rejectCapacity(ComplaintCapacityFailureCode.RESERVATION_EXCEEDED)
        return unused + removed
    }

    private fun requirePricedPoolCharge(charge: ComplaintCapacityVector) {
        val expected = chargeFor(charge[ComplaintCapacityCounter.SCAN_RUNS], charge[ComplaintCapacityCounter.SCAN_ENTRIES])
        if (charge != expected) rejectCapacity(ComplaintCapacityFailureCode.INVALID_CONFIGURATION)
    }

    override fun toString(): String = "TestTerminalScanPoolV1(declaration, redacted)"

    companion object {
        const val PASS_COUNT = 2L

        // Long-storage arithmetic ceiling ONLY. It is not a reviewed deployment/provider/heap limit.
        const val MAX_RETAINED_VERSIONS_BY_STORAGE_ARITHMETIC =
            (Long.MAX_VALUE - PASS_COUNT * TestTerminalCapacityChargesV1.SCAN_RUN_STORAGE_BYTES) /
                (PASS_COUNT * TestTerminalCapacityChargesV1.SCAN_ENTRY_STORAGE_BYTES)
    }
}
