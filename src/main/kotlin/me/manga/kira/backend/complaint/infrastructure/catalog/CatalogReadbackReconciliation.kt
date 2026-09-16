package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.complaint.domain.catalog.CatalogCommonHeadEvidence
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackResult
import me.manga.kira.backend.complaint.domain.catalog.CheckedOfflineCatalogInventoryChain
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback

/** Exact reconciliation only: unexplained forward heads and restored-row rebases never become ordinary-runtime authority. */
internal object CatalogReadbackReconciliation {
    fun reconcile(
        local: ValidatedLocalCatalog,
        stream: CatalogReadbackStream,
        chain: CheckedOfflineCatalogInventoryChain,
        policy: CatalogReadbackPolicy,
    ): CatalogReadbackResult {
        val observed = stream.lastRead ?: conflict()
        val retainUntil = observed.metadata.retainUntilEpochSecond ?: conflict()
        val version = observed.metadata.requestBinding.versionId
        val head = CatalogLocalHead(chain.tail.generation, chain.tail.envelopeSha256)
        if (stream.waitingForReplica) {
            requireFrozenTail(local, observed, head)
            return when (val supplied = local.supplied) {
                is LocalCatalogSnapshot.Prepared ->
                    CatalogReadbackResult.AwaitReplication(supplied.head, chain.tail, supplied.mutation.operationToken, version, retainUntil)

                is LocalCatalogSnapshot.PreparedGenesis ->
                    CatalogReadbackResult.GenesisAwaitReplication(chain.tail, supplied.mutation.operationToken, version, retainUntil)

                else -> conflict()
            }
        }
        val evidence = CatalogCommonHeadEvidence(
            chain,
            version,
            retainUntil,
            policy.evaluatedAtEpochSecond,
            stream.primaryEncodedBytes,
            stream.replicaEncodedBytes,
        )
        return when (val supplied = local.supplied) {
            LocalCatalogSnapshot.NeverAccepted -> {
                requireCatalogReadback(head.generation == 1L, CatalogReadbackFailure.HEAD_CONFLICT)
                CatalogReadbackResult.BootstrapObserved(evidence)
            }

            is LocalCatalogSnapshot.PreparedGenesis -> {
                requireFrozenTail(local, observed, head)
                CatalogReadbackResult.PreparedCompletionEvidence(evidence, supplied.mutation.operationToken)
            }

            is LocalCatalogSnapshot.Accepted -> {
                requireCatalogReadback(head == supplied.head, CatalogReadbackFailure.HEAD_CONFLICT)
                CatalogReadbackResult.CurrentHeadObserved(evidence)
            }

            is LocalCatalogSnapshot.Prepared -> {
                if (head == supplied.head) {
                    if (local.frozen?.envelopeBytes == null) {
                        CatalogReadbackResult.NeedsSignaturePersistence(evidence, supplied.mutation.operationToken)
                    } else {
                        CatalogReadbackResult.NeedsConditionalPublication(evidence, supplied.mutation.operationToken)
                    }
                } else {
                    requireFrozenTail(local, observed, head)
                    CatalogReadbackResult.PreparedCompletionEvidence(evidence, supplied.mutation.operationToken)
                }
            }

            is LocalCatalogSnapshot.ProjectionPending -> {
                requireCatalogReadback(head == supplied.head, CatalogReadbackFailure.HEAD_CONFLICT)
                requireFrozenTail(local, observed, head)
                CatalogReadbackResult.ProjectionResumeEvidence(evidence, supplied.projection.operationToken)
            }
        }
    }

    private fun requireFrozenTail(local: ValidatedLocalCatalog, observed: ReadCatalogVersion, head: CatalogLocalHead) {
        val frozen = local.frozen ?: conflict()
        val envelope = frozen.envelopeBytes ?: conflict()
        requireCatalogReadback(
            head.generation == frozen.claims.generation && head.envelopeSha256 == frozen.envelopeSha256 && observed.bytes.contentEquals(envelope),
            CatalogReadbackFailure.HEAD_CONFLICT,
        )
    }

    private fun conflict(): Nothing = throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)
}
