package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundlePolicy
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback

/**
 * Historical empty-page observations only, not a simultaneous snapshot, continued absence, no-reset proof or permission to prepare/sign/PUT.
 * No future genesis-envelope pin is needed or invented. Caller retains the actual adapter/construction, original budget and cleanup custody.
 * This diagnostic object is neither durable release evidence nor a cleanup receipt; it is not accepted by any mutation or admission boundary.
 */
internal class CatalogGenesisNamespaceObservationV1 private constructor(
    val currentTrustBundleEnvelopeSha256: String,
    val primaryRequest: CatalogListRequest,
    val replicaRequest: CatalogListRequest,
) {
    override fun toString(): String = "CatalogGenesisNamespaceObservationV1(historical-empty-two-routes,redacted,no-authority-or-cleanup-proof)"

    companion object {
        fun observe(
            provider: CatalogReadbackPort,
            currentBundleBytes: ByteArray,
            policy: OfflineTrustBundlePolicy,
            originalBudget: PersistenceTimeBudget,
        ): CatalogGenesisNamespaceObservationV1 {
            requireAttempt(originalBudget)
            val trust = OfflineTrustBundleVerifier.verify(currentBundleBytes, policy)
            requireAttempt(originalBudget)
            val locations = trust.body.catalogLocations // Raw verifier enforces exactly PRIMARY then REPLICA, matching independent policy.
            val primary = CatalogListRequest(locations[0], CatalogReadbackProtocol.PREFIX, null, 1)
            val replica = CatalogListRequest(locations[1], CatalogReadbackProtocol.PREFIX, null, 1)
            listOf(primary, replica).forEach { request ->
                requireAttempt(originalBudget)
                val page = catalogProviderCall { provider.listVersions(request) }
                requireAttempt(originalBudget)
                requireCatalogReadback(
                    page.requestBinding == request && !page.isTruncated && page.nextCursor == null,
                    CatalogReadbackFailure.INVALID_LISTING,
                )
                // Inspect only counts: never enumerate, copy a provider list, paginate or read an alleged existing generation.
                val empty = catalogProviderCall { page.versions.size == 0 && page.deleteMarkers.size == 0 }
                requireAttempt(originalBudget)
                requireCatalogReadback(empty, CatalogReadbackFailure.INVALID_LISTING)
            }
            val observation = CatalogGenesisNamespaceObservationV1(trust.envelopeSha256, primary, replica)
            requireAttempt(originalBudget)
            return observation
        }

        private fun requireAttempt(originalBudget: PersistenceTimeBudget) {
            requireConnectionFree()
            requireCatalogReadback(!Thread.currentThread().isInterrupted, CatalogReadbackFailure.INTERRUPTED)
            originalBudget.remainingMillis(Long.MAX_VALUE)
        }
    }
}
