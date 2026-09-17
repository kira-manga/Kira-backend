package me.manga.kira.backend.complaint.infrastructure.catalog

import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** A detached bounded SQL observation, NEVER a current-authority, commit, release or cutoff receipt. */
internal class CatalogEpochRotationRowV1 private constructor(
    private val binding: CatalogEpochRotationBindingRowV1,
    private val local: Local,
    val publicationEpoch: Long,
    val scanRequested: Boolean,
    val sequence: Long,
    val slot: CatalogEpochRotationSlotV1?,
    val updatedAt: Instant,
    val sampledAt: Instant?,
) {
    internal val historyEmpty: Boolean get() = local.historyEmpty

    internal fun requireCurrent(attempt: CatalogEpochRotationAttemptV1) {
        attempt.requireRunning()
        check(local.scope == LIVE && !local.testOnly && local.projection == null)
        check(attempt.matchesBinding(binding) && local.owner == attempt.owner && local.token == attempt.token)
        val time = checkNotNull(sampledAt) // A locking projection alone can never establish unexpired authority.
        check(checkNotNull(local.expiry).isAfter(time))
        slot?.let { check(it.hasBinding(binding)) }
    }

    internal fun sameState(other: CatalogEpochRotationRowV1): Boolean =
        binding.sameAs(other.binding) && local == other.local && publicationEpoch == other.publicationEpoch &&
            scanRequested == other.scanRequested && sequence == other.sequence && updatedAt == other.updatedAt &&
            ((slot == null && other.slot == null) || (slot != null && other.slot != null && slot.sameState(other.slot)))

    internal fun sameAuthorityAndGates(other: CatalogEpochRotationRowV1): Boolean = binding.sameAs(other.binding) && local == other.local

    override fun toString(): String = "CatalogEpochRotationRowV1(detached-observation,no-authority)"

    private data class Local(
        val scope: UUID,
        val testOnly: Boolean,
        val projection: UUID?,
        val owner: UUID?,
        val token: Long,
        val expiry: Instant?,
        val maintenanceClosed: Boolean,
        val creationClosed: Boolean,
        val historyEmpty: Boolean,
    )

    companion object {
        private val LIVE = UUID(0L, 0L)

        /** Only the retained concrete operation may turn this observation into a sealed result. */
        internal fun copy(row: ResultSet): CatalogEpochRotationRowV1 {
            check(requiredBoolean(row, "rotation_finite_times") && requiredBoolean(row, "rotation_sample_finite"))
            val sequence = requiredLong(row, "rotation_sequence").also { check(it >= 0) }
            return CatalogEpochRotationRowV1(
                CatalogEpochRotationBindingRowV1.copy(row, ""),
                Local(
                    checkNotNull(row.getObject("data_scope_id", UUID::class.java)),
                    requiredBoolean(row, "test_only"),
                    row.getObject("pending_projection_token", UUID::class.java),
                    row.getObject("lease_owner", UUID::class.java),
                    requiredLong(row, "lease_token").also { check(it >= 0) },
                    row.getTimestamp("lease_expires_at")?.toInstant(),
                    requiredBoolean(row, "maintenance_closed"),
                    requiredBoolean(row, "creation_closed"),
                    requiredBoolean(row, "rotation_history_empty"),
                ),
                requiredLong(row, "publication_epoch").also { check(it > 0) },
                requiredBoolean(row, "scan_requested"),
                sequence,
                CatalogEpochRotationSlotV1.copy(row, sequence),
                requiredInstant(row, "updated_at"),
                row.getTimestamp("rotation_sampled_at")?.toInstant(),
            )
        }
    }
}

/** Private detached digest buffers; equality is byte-content equality, not JDBC array identity. */
internal class CatalogEpochRotationBindingRowV1 private constructor(private val values: List<Any>) {
    internal val writer: UUID get() = values[5] as UUID

    internal fun matchesLeaseArguments(arguments: Array<Any?>): Boolean = arguments.size == 8 && values[0] == 1 && values[6] == 1L &&
        intArrayOf(1, 2, 3, 4, 5, 7, 8, 9).withIndex().all { (argument, field) -> sameValue(values[field], arguments[argument]) }

    internal fun sameAs(other: CatalogEpochRotationBindingRowV1): Boolean = values.indices.all { sameValue(values[it], other.values[it]) }

    override fun toString(): String = "CatalogEpochRotationBindingRowV1(detached,redacted,no-authority)"

    companion object {
        internal fun copy(row: ResultSet, prefix: String): CatalogEpochRotationBindingRowV1 = CatalogEpochRotationBindingRowV1(
            listOf(
                row.getInt(prefix + "implementation_schema").also { check(!row.wasNull() && it == 1) },
                requiredLong(row, prefix + "desired_generation").also { check(it > 0) },
                requiredDigest(row, prefix + "desired_configuration_hash"),
                requiredUuid(row, prefix + "database_identity"),
                requiredUuid(row, prefix + "restore_identity"),
                requiredUuid(row, prefix + "event_writer_generation"),
                requiredLong(row, prefix + "accepted_catalog_generation").also { check(it in 1L..65_536L) },
                requiredDigest(row, prefix + "accepted_catalog_hash"),
                requiredDigest(row, prefix + "trust_bundle_hash"),
                requiredUuid(row, prefix + "catalog_writer_generation"),
            ),
        )

        private fun sameValue(left: Any, right: Any?): Boolean = if (left is ByteArray) right is ByteArray && left.contentEquals(right) else left == right
    }
}

internal enum class CatalogEpochRotationStateV1 { REQUESTED, CAPTURED }

/** Exact stored slot including original requester/capturer provenance; none of these fields authorizes a successor. */
internal class CatalogEpochRotationSlotV1 private constructor(
    val sequence: Long,
    val id: UUID,
    val state: CatalogEpochRotationStateV1,
    val epochBefore: Long,
    private val binding: CatalogEpochRotationBindingRowV1,
    val request: CatalogEpochRotationProvenanceV1,
    val capture: CatalogEpochRotationProvenanceV1?,
    val epochAfter: Long?,
) {
    val writer: UUID get() = binding.writer

    internal fun hasBinding(current: CatalogEpochRotationBindingRowV1): Boolean = binding.sameAs(current)

    internal fun sameRequest(other: CatalogEpochRotationSlotV1): Boolean = sequence == other.sequence && id == other.id &&
        epochBefore == other.epochBefore && binding.sameAs(other.binding) && request.sameAs(other.request)

    internal fun sameState(other: CatalogEpochRotationSlotV1): Boolean = sameRequest(other) && state === other.state && epochAfter == other.epochAfter &&
        ((capture == null && other.capture == null) || (capture != null && other.capture != null && capture.sameAs(other.capture)))

    override fun toString(): String = "CatalogEpochRotationSlotV1(stored-provenance,no-authority)"

    companion object {
        private val NULLABLE_FIELDS = listOf(
            "rotation_id", "rotation_state", "rotation_epoch_before", "rotation_implementation_schema", "rotation_desired_generation",
            "rotation_desired_configuration_hash", "rotation_database_identity", "rotation_restore_identity", "rotation_event_writer_generation",
            "rotation_accepted_catalog_generation", "rotation_accepted_catalog_hash", "rotation_trust_bundle_hash", "rotation_catalog_writer_generation",
            "rotation_request_owner", "rotation_request_token", "rotation_requested_at", "rotation_capture_owner", "rotation_capture_token",
            "rotation_captured_at", "rotation_epoch_after",
        )

        internal fun copy(row: ResultSet, sequence: Long): CatalogEpochRotationSlotV1? {
            if (sequence == 0L) {
                check(NULLABLE_FIELDS.all { row.getObject(it) == null })
                return null
            }
            check(sequence > 0)
            val state = when (row.getString("rotation_state")) {
                "REQUESTED" -> CatalogEpochRotationStateV1.REQUESTED
                "CAPTURED" -> CatalogEpochRotationStateV1.CAPTURED
                else -> error("Invalid rotation state.")
            }
            val before = requiredLong(row, "rotation_epoch_before").also { check(it in 1 until Long.MAX_VALUE) }
            val captured = if (state === CatalogEpochRotationStateV1.CAPTURED) {
                CatalogEpochRotationProvenanceV1.copy(row, "rotation_capture_", "rotation_captured_at")
            } else {
                check(
                    listOf("rotation_capture_owner", "rotation_capture_token", "rotation_captured_at", "rotation_epoch_after").all {
                        row.getObject(it) == null
                    },
                )
                null
            }
            val after = if (captured == null) null else requiredLong(row, "rotation_epoch_after").also { check(it == Math.addExact(before, 1L)) }
            if (captured == null) {
                check(requiredBoolean(row, "scan_requested") && requiredLong(row, "publication_epoch") == before)
            } else {
                check(requiredLong(row, "publication_epoch") >= checkNotNull(after))
            }
            return CatalogEpochRotationSlotV1(
                sequence,
                requiredUuid(row, "rotation_id"),
                state,
                before,
                CatalogEpochRotationBindingRowV1.copy(row, "rotation_"),
                CatalogEpochRotationProvenanceV1.copy(row, "rotation_request_", "rotation_requested_at"),
                captured,
                after,
            )
        }
    }
}

internal class CatalogEpochRotationProvenanceV1 private constructor(val owner: UUID, val token: Long, val at: Instant) {
    internal fun sameAs(other: CatalogEpochRotationProvenanceV1): Boolean = owner == other.owner && token == other.token && at == other.at

    override fun toString(): String = "CatalogEpochRotationProvenanceV1(historical,redacted)"

    companion object {
        internal fun copy(row: ResultSet, prefix: String, timeColumn: String): CatalogEpochRotationProvenanceV1 = CatalogEpochRotationProvenanceV1(
            requiredUuid(row, prefix + "owner"),
            requiredLong(row, prefix + "token").also { check(it > 0) },
            requiredInstant(row, timeColumn),
        )
    }
}

private fun requiredBoolean(row: ResultSet, name: String): Boolean = row.getBoolean(name).also { check(!row.wasNull()) }

private fun requiredLong(row: ResultSet, name: String): Long = row.getLong(name).also { check(!row.wasNull()) }

private fun requiredInstant(row: ResultSet, name: String): Instant = checkNotNull(row.getTimestamp(name)).toInstant()

private fun requiredUuid(row: ResultSet, name: String): UUID = checkNotNull(row.getObject(name, UUID::class.java)).also {
    check(it.version() == 4 && it.variant() == 2)
}

private fun requiredDigest(row: ResultSet, name: String): ByteArray = checkNotNull(row.getBytes(name)).also { check(it.size == 32) }.copyOf()
