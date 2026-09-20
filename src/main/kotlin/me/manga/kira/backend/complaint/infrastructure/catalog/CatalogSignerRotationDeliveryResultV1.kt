package me.manga.kira.backend.complaint.infrastructure.catalog

internal enum class CatalogSignerRotationDeliveryStateV1 { AWAIT_REPLICATION, PROJECTION_PENDING, PROJECTED }

/** Diagnostics only. Pending has no transferable token, lease, refresh slot, tuple or projection authority. */
internal class CatalogSignerRotationDeliveryResultV1 private constructor(val state: CatalogSignerRotationDeliveryStateV1) {
    override fun toString(): String = "CatalogSignerRotationDeliveryResultV1(diagnostic-only,redacted)"

    companion object {
        internal fun issuedBy(original: CatalogSignerRotationDeliveryV1, state: CatalogSignerRotationDeliveryStateV1): CatalogSignerRotationDeliveryResultV1 {
            original.requireResult(state)
            return CatalogSignerRotationDeliveryResultV1(state)
        }
    }
}
