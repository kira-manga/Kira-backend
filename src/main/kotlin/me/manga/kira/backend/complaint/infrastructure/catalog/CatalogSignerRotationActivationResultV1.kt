package me.manga.kira.backend.complaint.infrastructure.catalog

internal enum class CatalogSignerRotationActivationStateV1 { PREPARED_UNSIGNED, SIGNED_UNPUBLISHED, AWAIT_REPLICATION, PROJECTION_PENDING, PROJECTED }

/** Diagnostics only. Pending has no transferable token, lease, refresh slot, tuple or projection authority. */
internal class CatalogSignerRotationActivationResultV1 private constructor(val state: CatalogSignerRotationActivationStateV1) {
    override fun toString(): String = "CatalogSignerRotationActivationResultV1(diagnostic-only,redacted)"

    companion object {
        internal fun issuedBy(original: CatalogSignerRotationActivationV1, state: CatalogSignerRotationActivationStateV1): CatalogSignerRotationActivationResultV1 {
            original.requireResult(state)
            return CatalogSignerRotationActivationResultV1(state)
        }
    }
}
