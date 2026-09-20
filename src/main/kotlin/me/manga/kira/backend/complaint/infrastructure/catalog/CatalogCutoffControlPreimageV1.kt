package me.manga.kira.backend.complaint.infrastructure.catalog

import java.sql.ResultSet
import java.sql.Timestamp

/** Exact detached non-seal control/slot preimage. Every seal/checkpoint field is separately required NULL by the new-write CAS. */
internal class CatalogCutoffControlPreimageV1 private constructor(private val values: List<Any?>) {
    internal fun arguments(): Array<Any?> = values.map(::copyValue).toTypedArray()

    internal fun sameState(other: CatalogCutoffControlPreimageV1): Boolean = values.indices.all { sameValue(values[it], other.values[it]) }

    internal fun sameExceptUpdatedAt(other: CatalogCutoffControlPreimageV1): Boolean =
        (0 until values.lastIndex).all { sameValue(values[it], other.values[it]) }

    override fun toString(): String = "CatalogCutoffControlPreimageV1(detached,redacted,no-authority)"

    companion object {
        // Closed compile-time column vocabulary, never caller SQL. Include the independent retention lease and EVERY slot/full-B field.
        // updated_at MUST remain last: canonical PREPARED may change only its own fields and that timestamp.
        private val COLUMNS = listOf(
            "data_scope_id" to "uuid", "test_only" to "boolean", "implementation_schema" to "integer", "desired_generation" to "bigint",
            "desired_configuration_hash" to "bytea", "database_identity" to "uuid", "restore_identity" to "uuid",
            "event_writer_generation" to "uuid", "accepted_catalog_generation" to "bigint", "accepted_catalog_hash" to "bytea",
            "trust_bundle_hash" to "bytea", "catalog_writer_generation" to "uuid", "pending_projection_token" to "uuid",
            "publication_epoch" to "bigint", "scan_requested" to "boolean", "maintenance_closed" to "boolean", "creation_closed" to "boolean",
            "lease_owner" to "uuid", "lease_token" to "bigint", "lease_expires_at" to "timestamptz",
            "retention_lease_owner" to "uuid", "retention_lease_token" to "bigint", "retention_lease_expires_at" to "timestamptz",
            "rotation_sequence" to "bigint", "rotation_id" to "uuid", "rotation_state" to "text", "rotation_epoch_before" to "bigint",
            "rotation_implementation_schema" to "integer", "rotation_desired_generation" to "bigint",
            "rotation_desired_configuration_hash" to "bytea", "rotation_database_identity" to "uuid", "rotation_restore_identity" to "uuid",
            "rotation_event_writer_generation" to "uuid", "rotation_accepted_catalog_generation" to "bigint",
            "rotation_accepted_catalog_hash" to "bytea", "rotation_trust_bundle_hash" to "bytea", "rotation_catalog_writer_generation" to "uuid",
            "rotation_request_owner" to "uuid", "rotation_request_token" to "bigint", "rotation_requested_at" to "timestamptz",
            "rotation_capture_owner" to "uuid", "rotation_capture_token" to "bigint", "rotation_captured_at" to "timestamptz",
            "rotation_epoch_after" to "bigint", "updated_at" to "timestamptz",
        )

        internal val PREDICATE = COLUMNS.joinToString(" AND ") { (column, type) -> "c.$column IS NOT DISTINCT FROM ?::$type" }

        internal fun copy(row: ResultSet): CatalogCutoffControlPreimageV1 =
            CatalogCutoffControlPreimageV1(COLUMNS.map { (column, _) -> copyValue(row.getObject(column)) })

        private fun sameValue(left: Any?, right: Any?): Boolean = if (left is ByteArray) right is ByteArray && left.contentEquals(right) else left == right

        private fun copyValue(value: Any?): Any? = when (value) {
            is ByteArray -> value.copyOf()
            is Timestamp -> Timestamp.from(value.toInstant())
            else -> value
        }
    }
}
