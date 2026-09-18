package me.manga.kira.backend.complaint.infrastructure.transaction

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.UnverifiedGenesisPreparation
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizationObservation
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeAttemptV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisInitialLiveBinding
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationInput
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.GenesisResume
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogGenesisMutationStore
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogSnapshotReader
import me.manga.kira.backend.complaint.infrastructure.catalog.ProcessBoundCatalogGenesisProjection
import org.springframework.jdbc.core.JdbcTemplate

/** Dormant G1-only composition. No publication, pin production, credential issuance, runtime opening or restore clearance. */
internal class ComplaintCatalogGenesisPersistencePhaseExecutor(private val ownership: PersistencePhaseOwnership, private val jdbc: JdbcTemplate) {
    // Same concrete tuple, same ownership and sole slot; never another pool or an ordinary/deletion fallback.
    private val snapshot = ComplaintCatalogSnapshotPhaseExecutor(ownership, JdbcCatalogSnapshotReader(jdbc))

    fun resumeGenesis(
        provider: CatalogReadbackPort,
        initialBundleBytes: ByteArray,
        currentBundleBytes: ByteArray,
        policy: CatalogReadbackPolicy,
        expected: CatalogGenesisInitialLiveBinding,
    ): CatalogGenesisFinalizationObservation {
        requireConnectionFree()
        expected.requirePersistence(ownership, jdbc)
        val initial = copyBundle(initialBundleBytes)
        val current = copyBundle(currentBundleBytes)
        val local = snapshot.load(initial, current, policy) // A committed AND released observation, not authority.
        val readback = CatalogDualLocationVerifier.GenesisReadback.verify(provider, initial, current, policy, local)
        if (readback.resume == GenesisResume.PREPARED) completeGenesis(readback, expected)
        return projectGenesis(readback, expected)
    }

    fun completeGenesis(
        readback: CatalogDualLocationVerifier.GenesisReadback,
        expected: CatalogGenesisInitialLiveBinding,
    ): CatalogGenesisFinalizationObservation {
        requireConnectionFree()
        return persist(CatalogGenesisMutationInput.complete(readback, expected), expected.capacityPolicyDigestBytes()).finalizationObservation
    }

    fun projectGenesis(
        readback: CatalogDualLocationVerifier.GenesisReadback,
        expected: CatalogGenesisInitialLiveBinding,
    ): CatalogGenesisFinalizationObservation {
        requireConnectionFree()
        return persist(CatalogGenesisMutationInput.project(readback, expected), expected.capacityPolicyDigestBytes()).finalizationObservation
    }

    /** Same closed PROJECT/no-op phase, but only its actual committed+released process binding can produce this partial receipt. */
    fun projectGenesisForProcess(
        readback: CatalogDualLocationVerifier.GenesisReadback,
        expected: CatalogGenesisInitialLiveBinding,
    ): ProcessBoundCatalogGenesisProjection {
        requireConnectionFree()
        requireCatalogReadback(expected.process != null, CatalogReadbackFailure.INVALID_POLICY)
        return persist(CatalogGenesisMutationInput.project(readback, expected), expected.capacityPolicyDigestBytes()).processBoundProjection
    }

    fun prepareGenesis(
        offlineIntentManifestBytes: ByteArray,
        initialBundleBytes: ByteArray,
        currentBundleBytes: ByteArray,
        policy: OfflineCatalogChainReaderPolicy,
        expectedCapacityPolicyDigest: ByteArray,
    ): UnverifiedGenesisPreparation {
        requireConnectionFree()
        val input = CatalogGenesisMutationInput.prepare(offlineIntentManifestBytes, initialBundleBytes, currentBundleBytes, policy)
        return persist(input, expectedCapacityPolicyDigest).observation
    }

    fun persistGenesisSignature(
        local: UnverifiedGenesisPreparation,
        offlineIntentManifestBytes: ByteArray,
        initialBundleBytes: ByteArray,
        currentBundleBytes: ByteArray,
        policy: OfflineCatalogChainReaderPolicy,
        expectedCapacityPolicyDigest: ByteArray,
        signatureBytes: ByteArray?,
    ): UnverifiedGenesisPreparation {
        requireConnectionFree()
        val input = CatalogGenesisMutationInput.signature(local, offlineIntentManifestBytes, initialBundleBytes, currentBundleBytes, policy, signatureBytes)
        return persist(input, expectedCapacityPolicyDigest).observation
    }

    internal fun prepareGenesis(attempt: CatalogGenesisFreezeAttemptV1): UnverifiedGenesisPreparation {
        requireConnectionFree()
        attempt.requirePersistence(ownership, jdbc)
        return persist(attempt.inputs.preparationInput(), attempt.inputs.capacityDigest(), attempt).observation
    }

    internal fun persistGenesisSignature(
        attempt: CatalogGenesisFreezeAttemptV1,
        local: UnverifiedGenesisPreparation,
        signatureBytes: ByteArray,
    ): UnverifiedGenesisPreparation {
        requireConnectionFree()
        attempt.requirePersistence(ownership, jdbc)
        return persist(attempt.inputs.signatureInput(local, signatureBytes), attempt.inputs.capacityDigest(), attempt).observation
    }

    @Suppress("TooGenericExceptionCaught")
    private fun persist(
        input: CatalogGenesisMutationInput,
        expectedCapacityPolicyDigest: ByteArray,
        author: CatalogGenesisFreezeAttemptV1? = null,
    ): CatalogGenesisMutationOperation {
        requireConnectionFree()
        requireCatalogReadback(expectedCapacityPolicyDigest.size == 32, CatalogReadbackFailure.INVALID_POLICY)
        input.finalization?.binding?.requirePersistence(ownership, jdbc)
        val capacity = JdbcComplaintCapacityStore(jdbc, expectedCapacityPolicyDigest)
        val store = JdbcCatalogGenesisMutationStore(jdbc)
        val phase = when (input.path) {
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE ->
                author?.let(ownership::enterComplaintCatalogGenesisPrepare) ?: ownership.enterComplaintCatalogGenesisPrepare()

            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE ->
                author?.let(ownership::enterComplaintCatalogGenesisSignature) ?: ownership.enterComplaintCatalogGenesisSignature()

            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE -> ownership.enterComplaintCatalogGenesisComplete()

            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT -> ownership.enterComplaintCatalogGenesisProject()

            else -> error("Unsupported G1 phase.")
        }
        var completed: CatalogGenesisMutationOperation? = null
        try {
            phase.begin() // Fixed shared epoch fence precedes every control, advisory/mutation and counter lock.
            author?.authenticate(ownership, jdbc)
            completed = when (input.path) {
                PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE -> store.prepare(input, capacity)
                PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE -> store.persistSignature(input, capacity)
                PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE -> store.complete(input, capacity)
                PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT -> store.project(input, capacity)
                else -> error("Unsupported G1 phase.")
            }
            input.finalization?.binding?.requirePersistence(ownership, jdbc)
            author?.requirePersistence(ownership, jdbc)
            phase.commit()
        } catch (problem: Throwable) {
            phase.recordFailure(problem)
        } finally {
            phase.finish()
        }
        return completed ?: throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }

    private fun copyBundle(bytes: ByteArray): ByteArray {
        requireCatalogReadback(bytes.size in 1..OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES, CatalogReadbackFailure.LIMIT_EXCEEDED)
        return bytes.copyOf()
    }

    override fun toString(): String = "ComplaintCatalogGenesisPersistencePhaseExecutor(G1-only,no-publication-authority)"
}
