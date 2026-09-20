package me.manga.kira.backend.complaint.domain.catalog

/** Independent requirements, never values nominated by a provider response or a restored database. */
internal class CatalogReadbackPolicy(
    val chain: OfflineCatalogChainReaderPolicy,
    val expectedGenesisEnvelopeSha256: String,
    val evaluatedAtEpochSecond: Long,
    val requiredRetainUntilEpochSecond: Long,
    val pageSize: Int,
    val maximumPagesPerLocation: Int,
) {
    init {
        requireCatalogReadback(OfflineBootstrapGrammar.sha256(expectedGenesisEnvelopeSha256), CatalogReadbackFailure.INVALID_POLICY)
        requireCatalogReadback(
            evaluatedAtEpochSecond in 0..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND &&
                requiredRetainUntilEpochSecond in 0..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND &&
                requiredRetainUntilEpochSecond > evaluatedAtEpochSecond,
            CatalogReadbackFailure.INVALID_POLICY,
        )
        requireCatalogReadback(pageSize in 1..CatalogReadbackProtocol.MAX_PAGE_ENTRIES, CatalogReadbackFailure.INVALID_POLICY)
        requireCatalogReadback(maximumPagesPerLocation in 1..OfflineCatalogChainProtocol.MAX_GENERATIONS, CatalogReadbackFailure.INVALID_POLICY)
    }
}

internal object CatalogReadbackProtocol {
    const val PREFIX = "complaints/catalog/v1/"
    const val GENERATION_DIGITS = 20
    const val MAX_PAGE_ENTRIES = 1000
    const val MAX_VERSION_CHARACTERS = 1024

    fun key(generation: Long): String {
        requireCatalogReadback(generation in 1..OfflineCatalogChainProtocol.MAX_GENERATIONS, CatalogReadbackFailure.INVALID_LOCAL_STATE)
        return PREFIX + generation.toString().padStart(GENERATION_DIGITS, '0') + ".json"
    }

    fun validVersion(value: String?): Boolean = value != null && value.length in 1..MAX_VERSION_CHARACTERS && value != "null" && value.all { it in '!'..'~' }
}

internal enum class CatalogReadbackFailure {
    INVALID_POLICY,
    INVALID_LOCAL_STATE,
    LIMIT_EXCEEDED,
    INVALID_LISTING,
    INVALID_READBACK,
    RETENTION_MISMATCH,
    REPLICATION_MISMATCH,
    HEAD_CONFLICT,
    PROVIDER_FAILURE,
    CLOSE_FAILURE,
    INTERRUPTED,
}

/** No raw provider message, object contents, request tokens, causes or suppressed provider exceptions. */
internal class CatalogReadbackException(val code: CatalogReadbackFailure) : RuntimeException("Catalog readback rejected: ${code.name}.")

internal fun requireCatalogReadback(condition: Boolean, failure: CatalogReadbackFailure) {
    if (!condition) throw CatalogReadbackException(failure)
}
