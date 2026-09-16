package me.manga.kira.backend.complaint.domain.catalog

/** Explicit independent reader inputs; checking syntax neither proves provenance nor consults a restored database. */
internal class OfflineCatalogChainReaderPolicy(
    val trustBundlePolicy: OfflineTrustBundlePolicy,
    currentWriterGenerationIds: List<String>,
    currentApproverIds: List<String>,
    val limits: OfflineCatalogChainLimits,
) {
    private val storedWriters = canonicalIds(currentWriterGenerationIds, 1, OfflineBootstrapGrammar::uuidV4)
    private val storedApprovers = canonicalIds(currentApproverIds, 2, OfflineBootstrapGrammar::approverId)

    val currentWriterGenerationIds: List<String> get() = storedWriters.toList()
    val currentApproverIds: List<String> get() = storedApprovers.toList()

    override fun toString(): String = "OfflineCatalogChainReaderPolicy(out-of-band,no-database-authority)"

    private fun canonicalIds(values: List<String>, minimum: Int, valid: (String) -> Boolean): List<String> {
        requireOfflineTrustBundle(values.size in minimum..16, OfflineTrustBundleFailure.INVALID_POLICY)
        val snapshot = values.toList()
        requireOfflineTrustBundle(
            snapshot.size in minimum..16 && snapshot.distinct().size == snapshot.size && snapshot == snapshot.sorted() && snapshot.all(valid),
            OfflineTrustBundleFailure.INVALID_POLICY,
        )
        return snapshot
    }
}

/** Lower operational/test budgets are permitted, never ceilings above the V6 hard limits. */
internal data class OfflineCatalogChainLimits(
    val maximumEnvelopeBytes: Int,
    val maximumManifestRecords: Int,
    val maximumGenerations: Int,
    val maximumEncodedBytes: Long,
) {
    init {
        requireOfflineTrustBundle(
            maximumEnvelopeBytes in 1..OfflineCatalogChainProtocol.MAX_ENVELOPE_BYTES &&
                maximumManifestRecords in 1..OfflineCatalogChainProtocol.MAX_MANIFEST_RECORDS &&
                maximumGenerations in 1..OfflineCatalogChainProtocol.MAX_GENERATIONS &&
                maximumEncodedBytes in 1..OfflineCatalogChainProtocol.MAX_ENCODED_BYTES,
            OfflineTrustBundleFailure.INVALID_POLICY,
        )
    }
}

internal object OfflineCatalogChainProtocol {
    const val MAX_ENVELOPE_BYTES = 8 * 1024 * 1024
    const val MAX_MANIFEST_RECORDS = 4096
    const val MAX_GENERATIONS = 65536
    const val MAX_ENCODED_BYTES = 2L * 1024 * 1024 * 1024
    const val ROTATION_OVERLAP = "ROTATION_OVERLAP"
    const val ROTATION_ACTIVATE = "ROTATION_ACTIVATE"
}
