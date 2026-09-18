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
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizeAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogReadbackRefreshCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationInitialAuthorV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationPreparedRecoveryV1
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

    internal fun loadFinalizing(
        initialBundleBytes: ByteArray,
        currentBundleBytes: ByteArray,
        policy: CatalogReadbackPolicy,
        attempt: CatalogGenesisFinalizeAttemptV1,
    ): LocalCatalogSnapshot {
        requireConnectionFree()
        attempt.requireRunning()
        val initial = copyBundle(initialBundleBytes)
        val current = copyBundle(currentBundleBytes)
        val trust = OfflineTrustBundleVerifier.verify(current, policy.chain.trustBundlePolicy)
        return capture(finalizer = attempt).validate(initial, trust, policy)
    }

    /** Same actual snapshot operation; only the concrete recovery owner supplies its original allowance and retained inputs. */
    internal fun loadPreparedRecovery(original: CatalogSignerRotationPreparedRecoveryV1, policy: CatalogReadbackPolicy): LocalCatalogSnapshot {
        requireConnectionFree()
        original.requireSnapshotSelection(ownership)
        val initial = copyBundle(original.inputs.initialBytes())
        val current = copyBundle(original.inputs.currentBytes())
        val trust = OfflineTrustBundleVerifier.verify(current, policy.chain.trustBundlePolicy)
        return capture(recovery = original).validate(initial, trust, policy).also { original.requireRunning() }
    }

    internal fun loadInitialAuthor(
        initialBundleBytes: ByteArray,
        currentBundleBytes: ByteArray,
        policy: CatalogReadbackPolicy,
        original: CatalogSignerRotationInitialAuthorV1,
    ): LocalCatalogSnapshot {
        requireConnectionFree()
        original.requireBootstrapRunning()
        val initial = copyBundle(initialBundleBytes)
        val current = copyBundle(currentBundleBytes)
        val trust = OfflineTrustBundleVerifier.verify(current, policy.chain.trustBundlePolicy)
        return capture(initialAuthor = original).validate(initial, trust, policy).also { original.requireBootstrapRunning() }
    }

    /** No future PSS-envelope pin is needed to observe PREPARED bytes. Their presence is not signing/publication authority. */
    fun loadGenesisPreparation(currentBundleBytes: ByteArray, policy: OfflineCatalogChainReaderPolicy): UnverifiedGenesisPreparation {
        requireConnectionFree()
        val trust = OfflineTrustBundleVerifier.verify(copyBundle(currentBundleBytes), policy.trustBundlePolicy)
        requireCatalogReadback(trust.body.minimumCatalogHeadGeneration == 1L, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        return capture().observeGenesisPreparation(policy.limits)
    }

    /** Separate author root accepts only this original freeze's inputs and remaining budget. */
    internal fun loadGenesisPreparation(attempt: CatalogGenesisFreezeAttemptV1): UnverifiedGenesisPreparation {
        requireConnectionFree()
        attempt.requireRunning()
        val trust = OfflineTrustBundleVerifier.verify(attempt.inputs.currentBytes(), attempt.inputs.chain.trustBundlePolicy)
        requireCatalogReadback(trust.body.minimumCatalogHeadGeneration == 1L, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        return capture(author = attempt).observeGenesisPreparation(attempt.inputs.chain.limits)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun capture(
        attempt: CatalogReadbackRefreshCustodyV1.Attempt? = null,
        author: CatalogGenesisFreezeAttemptV1? = null,
        finalizer: CatalogGenesisFinalizeAttemptV1? = null,
        recovery: CatalogSignerRotationPreparedRecoveryV1? = null,
        initialAuthor: CatalogSignerRotationInitialAuthorV1? = null,
    ): CatalogSnapshotRows {
        requireConnectionFree()
        val phase = initialAuthor?.let(ownership::enterComplaintCatalogSnapshot) ?: recovery?.let(ownership::enterComplaintCatalogSnapshot)
            ?: finalizer?.let(ownership::enterComplaintCatalogSnapshot) ?: author?.let(ownership::enterComplaintCatalogSnapshot)
            ?: attempt?.let(ownership::enterComplaintCatalogSnapshot) ?: ownership.enterComplaintCatalogSnapshot()
        var captured: CatalogSnapshotReadOperation? = null
        var closingFailure: Throwable? = null
        try {
            phase.begin()
            author?.let { reader.authenticateGenesisAuthor(it, ownership) }
            finalizer?.let { reader.authenticateGenesisFinalizer(it, ownership) }
            initialAuthor?.let { reader.authenticateInitialSignerRotationAuthor(it, ownership) }
            captured = reader.read()
            author?.requireRunning()
            finalizer?.requireRunning()
            recovery?.requireRunning()
            initialAuthor?.requireBootstrapRunning()
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            try {
                closingFailure = runCatching(phase::finish).exceptionOrNull()
                closingFailure?.let { recovery?.observeFailure(it) }
                closingFailure?.let { initialAuthor?.observeFailure(it) }
            } finally {
                recovery?.observePhaseCleanup(phase)
                initialAuthor?.observePhaseCleanup(phase)
            }
        }
        recovery?.throwIfSignalled()
        initialAuthor?.throwIfSignalled()
        closingFailure?.let { throw it }
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
