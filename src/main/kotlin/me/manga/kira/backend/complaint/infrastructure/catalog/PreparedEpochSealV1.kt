package me.manga.kira.backend.complaint.infrastructure.catalog

/** Historical known-committed AND released canonical intent only. Not current fencing, wire-ready work, provider evidence or a verified seal. */
internal class PreparedEpochSealV1 private constructor(row: CatalogSealCanonicalRowV1, val eventCount: Long, val eventManifestSha256: String) {
    val rotationId = row.rotationId
    val sequence = row.sequence
    val writer = row.writer
    val epochStartInclusive = row.epochStart
    val epochEndInclusive = row.epochEnd
    val preparingFencingToken = row.preparingFencingToken
    val selectedRoutingKeyId = row.routingKeyId
    val objectKey = row.objectKey
    val semanticSha256 = row.semanticSha256()
    val operationToken = row.operationToken

    override fun toString(): String = "PreparedEpochSealV1(historical,redacted,canonical-only-NOT-wire-ready-or-verified)"

    companion object {
        internal fun fromReleased(operation: CatalogCutoffPersistenceOperationV1): PreparedEpochSealV1 {
            val (row, manifest) = operation.attempt.preparedResult(operation)
            return PreparedEpochSealV1(row, manifest.eventCount, manifest.eventManifestSha256)
        }
    }
}
