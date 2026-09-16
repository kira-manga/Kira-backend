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
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationInput
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationOperation
import me.manga.kira.backend.complaint.infrastructure.catalog.GenesisResume
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogGenesisMutationStore
import me.manga.kira.backend.complaint.infrastructure.catalog.JdbcCatalogSnapshotReader
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
        expectedCapacityPolicyDigest: ByteArray,
    ): CatalogGenesisFinalizationObservation {
        requireConnectionFree()
        requireCatalogReadback(expectedCapacityPolicyDigest.size == 32, CatalogReadbackFailure.INVALID_POLICY)
        val digest = expectedCapacityPolicyDigest.copyOf()
        val initial = copyBundle(initialBundleBytes)
        val current = copyBundle(currentBundleBytes)
        val local = snapshot.load(initial, current, policy) // A committed AND released observation, not authority.
        val readback = CatalogDualLocationVerifier.GenesisReadback.verify(provider, initial, current, policy, local)
        if (readback.resume == GenesisResume.PREPARED) completeGenesis(readback, digest)
        return projectGenesis(readback, digest)
    }

    fun completeGenesis(
        readback: CatalogDualLocationVerifier.GenesisReadback,
        expectedCapacityPolicyDigest: ByteArray,
    ): CatalogGenesisFinalizationObservation {
        requireConnectionFree()
        return persist(CatalogGenesisMutationInput.complete(readback), expectedCapacityPolicyDigest).finalizationObservation
    }

    fun projectGenesis(readback: CatalogDualLocationVerifier.GenesisReadback, expectedCapacityPolicyDigest: ByteArray): CatalogGenesisFinalizationObservation {
        requireConnectionFree()
        return persist(CatalogGenesisMutationInput.project(readback), expectedCapacityPolicyDigest).finalizationObservation
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

    @Suppress("TooGenericExceptionCaught")
    private fun persist(input: CatalogGenesisMutationInput, expectedCapacityPolicyDigest: ByteArray): CatalogGenesisMutationOperation {
        requireConnectionFree()
        requireCatalogReadback(expectedCapacityPolicyDigest.size == 32, CatalogReadbackFailure.INVALID_POLICY)
        val capacity = JdbcComplaintCapacityStore(jdbc, expectedCapacityPolicyDigest)
        val store = JdbcCatalogGenesisMutationStore(jdbc)
        val phase = when (input.path) {
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE -> ownership.enterComplaintCatalogGenesisPrepare()
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE -> ownership.enterComplaintCatalogGenesisSignature()
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE -> ownership.enterComplaintCatalogGenesisComplete()
            PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT -> ownership.enterComplaintCatalogGenesisProject()
            else -> error("Unsupported G1 phase.")
        }
        var completed: CatalogGenesisMutationOperation? = null
        try {
            phase.begin() // Fixed shared epoch fence precedes every control, advisory/mutation and counter lock.
            completed = when (input.path) {
                PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PREPARE -> store.prepare(input, capacity)
                PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_SIGNATURE -> store.persistSignature(input, capacity)
                PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_COMPLETE -> store.complete(input, capacity)
                PersistencePhasePath.COMPLAINT_CATALOG_GENESIS_PROJECT -> store.project(input, capacity)
                else -> error("Unsupported G1 phase.")
            }
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
