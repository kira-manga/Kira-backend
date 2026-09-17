package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.UnverifiedGenesisPreparation
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogReadbackRefreshCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSnapshotReadOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSnapshotRows
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogSnapshotReader
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineTrustBundleVerifier

/** Dormant read-only load. Neither database observations nor a successful phase confer catalog/readback/admission authority. */
internal class ComplaintCatalogSnapshotPhaseExecutor(private val ownership: PersistencePhaseOwnership, private val reader: JdbcCatalogSnapshotReader) {
    fun load(initialBundleBytes: ByteArray, currentBundleBytes: ByteArray, policy: CatalogReadbackPolicy): LocalCatalogSnapshot {
        requireConnectionFree()
        val initial = copyBundle(initialBundleBytes)
        val current = copyBundle(currentBundleBytes)
        val trust = OfflineTrustBundleVerifier.verify(current, policy.chain.trustBundlePolicy)
        return capture().validate(initial, trust, policy)
    }

    /** Same snapshot operation/resource, bounded by the already-running projected refresh rather than a restarted attempt. */
    internal fun loadProjected(
        initialBundleBytes: ByteArray,
        currentBundleBytes: ByteArray,
        policy: CatalogReadbackPolicy,
        attempt: CatalogReadbackRefreshCustodyV1.Attempt,
    ): LocalCatalogSnapshot {
        requireConnectionFree()
        attempt.requireProjectedPersistence(ownership)
        val initial = copyBundle(initialBundleBytes)
        val current = copyBundle(currentBundleBytes)
        val trust = OfflineTrustBundleVerifier.verify(current, policy.chain.trustBundlePolicy)
        return capture(attempt).validate(initial, trust, policy)
    }

    /** No future PSS-envelope pin is needed to observe PREPARED bytes. Their presence is not signing/publication authority. */
    fun loadGenesisPreparation(currentBundleBytes: ByteArray, policy: OfflineCatalogChainReaderPolicy): UnverifiedGenesisPreparation {
        requireConnectionFree()
        val trust = OfflineTrustBundleVerifier.verify(copyBundle(currentBundleBytes), policy.trustBundlePolicy)
        requireCatalogReadback(trust.body.minimumCatalogHeadGeneration == 1L, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        return capture().observeGenesisPreparation(policy.limits)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun capture(attempt: CatalogReadbackRefreshCustodyV1.Attempt? = null): CatalogSnapshotRows {
        requireConnectionFree()
        val phase = attempt?.let(ownership::enterComplaintCatalogSnapshot) ?: ownership.enterComplaintCatalogSnapshot()
        var captured: CatalogSnapshotReadOperation? = null
        try {
            phase.begin()
            captured = reader.read()
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        val operation = captured ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        // The getter checks known commit + completed resource release before any local JSON/hash/signature work.
        return operation.rows
    }

    private fun copyBundle(bytes: ByteArray): ByteArray {
        requireCatalogReadback(bytes.size in 1..OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES, CatalogReadbackFailure.LIMIT_EXCEEDED)
        return bytes.copyOf()
    }

    override fun toString(): String = "ComplaintCatalogSnapshotPhaseExecutor(read-only,no-authority)"
}
