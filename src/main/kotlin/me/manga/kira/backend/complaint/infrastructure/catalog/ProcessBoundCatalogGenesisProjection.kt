package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration

/**
 * Historical, closed-epoch1 G1 projection receipt from the actual committed AND released operation.
 * Not a current capability, SDK-source proof, checkpoint or restore clearance. In particular a D-v1
 * process without catalog-readback settings cannot gain actual refresh authority from this receipt.
 * The actual SDK refresh owner must separately own this exact readback and its complete cleanup.
 */
internal class ProcessBoundCatalogGenesisProjection private constructor(operation: CatalogGenesisMutationOperation) {
    private val released = operation.requireReleasedProcessProjection()
    private val input = released.first
    private val readback = input.readback
    val process: VersionBoundComplaintProcessConfiguration = checkNotNull(input.binding.process)
    val state: CatalogGenesisFinalizationState = released.second
    val operationToken: String = readback.mutation().operationToken
    val envelopeSha256: String = readback.envelopeSha256
    val currentTrustBundleSha256: String = readback.currentTrustBundleSha256
    val catalogWriterGenerationId: String = readback.manifest().catalogWriterGenerationId

    /** Exact immutable provenance identities plus local recheck, never a caller-supplied success flag or current DB observation. */
    fun requireBinding(process: VersionBoundComplaintProcessConfiguration, readback: CatalogDualLocationVerifier.GenesisReadback) {
        requireCatalogReadback(this.process === process && this.readback === readback, CatalogReadbackFailure.INVALID_POLICY)
        input.binding.requireUnchangedConfiguration()
    }

    override fun toString(): String = "ProcessBoundCatalogGenesisProjection(historical-G1,no-current-or-SDK-authority)"

    companion object {
        /** No observation, Boolean, tuple or supplied descriptor can construct this receipt. */
        internal fun issuedBy(operation: CatalogGenesisMutationOperation): ProcessBoundCatalogGenesisProjection =
            ProcessBoundCatalogGenesisProjection(operation)
    }
}
