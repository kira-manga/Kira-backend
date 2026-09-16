package me.manga.kira.backend.complaint.domain.catalog

/** Verified supplied observations, not live AWS authority, a persisted accepted head, or admission permission. */
internal class CatalogCommonHeadEvidence(
    val chain: CheckedOfflineCatalogInventoryChain,
    val objectVersion: String,
    val retainUntilEpochSecond: Long,
    val evaluatedAtEpochSecond: Long,
    val primaryEncodedBytes: Long,
    val replicaEncodedBytes: Long,
) {
    override fun toString(): String = "CatalogCommonHeadEvidence(no-projection-or-admission-authority)"
}

internal sealed interface CatalogReadbackResult {
    data object EmptyClosed : CatalogReadbackResult

    /** Empty current listings plus an authenticated frozen candidate; no common head, no-reset proof or permission to PUT. */
    data class PreparedGenesisUnpublished(val operationToken: String, val signedEnvelopeSha256: String) : CatalogReadbackResult
    data class BootstrapObserved(val evidence: CatalogCommonHeadEvidence) : CatalogReadbackResult
    data class CurrentHeadObserved(val evidence: CatalogCommonHeadEvidence) : CatalogReadbackResult
    data class NeedsSignaturePersistence(val evidence: CatalogCommonHeadEvidence, val operationToken: String) : CatalogReadbackResult
    data class NeedsConditionalPublication(val evidence: CatalogCommonHeadEvidence, val operationToken: String) : CatalogReadbackResult
    data class PreparedCompletionEvidence(val evidence: CatalogCommonHeadEvidence, val operationToken: String) : CatalogReadbackResult
    data class ProjectionResumeEvidence(val evidence: CatalogCommonHeadEvidence, val operationToken: String) : CatalogReadbackResult

    /** The replica is empty. No predecessor, common-head evidence or implicit genesis acceptance is manufactured. */
    data class GenesisAwaitReplication(
        val candidate: CatalogTailEvidence,
        val operationToken: String,
        val objectVersion: String,
        val retainUntilEpochSecond: Long,
    ) : CatalogReadbackResult

    /** Intentionally contains no common-head evidence: the replica prefix never becomes an accepted head. */
    data class AwaitReplication(
        val predecessor: CatalogLocalHead,
        val candidate: CatalogTailEvidence,
        val operationToken: String,
        val objectVersion: String,
        val retainUntilEpochSecond: Long,
    ) : CatalogReadbackResult
}
