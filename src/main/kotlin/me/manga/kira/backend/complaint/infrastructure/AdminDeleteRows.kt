package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.complaint.domain.AdminBatchDeleteCapacityCharges
import me.manga.kira.backend.complaint.domain.OwnerDeleteCapacityCharges
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.TestAdminDeleteJournalTupleV1
import me.manga.kira.backend.security.TestAdminBatchDeleteJournalTupleV1
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteReceipt
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRejection
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteTuple
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** ADMIN-only receipt comparison, including historical grant association. */
internal object AdminDeleteRows {
    class Receipt(row: ResultSet) {
        val actor: UUID = checkNotNull(row.getObject("actor_id", UUID::class.java))
        val key: UUID = checkNotNull(row.getObject("idempotency_key", UUID::class.java))
        val operation: String = checkNotNull(row.getString("operation"))
        val scope: UUID = checkNotNull(row.getObject("data_scope_id", UUID::class.java))
        val test: Boolean = OwnerDeleteRows.bool(row, "test_only")
        val target: UUID? = row.getObject("target_id", UUID::class.java)
        val targets: List<UUID>? = ids(row, "target_ids")
        private val acknowledgments: List<UUID>? = ids(row, "ack_ids")
        private val fingerprint: ByteArray? = row.getBytes("fingerprint")
        val consumedGrantId: UUID? = row.getObject("consumed_grant_id", UUID::class.java)
        val state: String = checkNotNull(row.getString("state"))
        val comparable: Boolean = OwnerDeleteRows.bool(row, "comparable")
        val visible: Boolean = OwnerDeleteRows.bool(row, "visible")
        val valid: Boolean = OwnerDeleteRows.bool(row, "valid_shape") && targets != null &&
            targets == targets.sortedBy(UUID::toString) && targets.distinct().size == targets.size
        val publication: String? = row.getString("publication_ref")
        val authorizedAt: Instant? = row.getTimestamp("authorized_at")?.toInstant()
        private val outcome = row.getString("outcome")
        private val status = row.getInt("response_status")
        private val problem = row.getString("problem_code")
        val externalEvent: String? = row.getString("external_event_id")
        val externalEpoch: Long = row.getLong("external_epoch")
        val externalVersion: String? = row.getString("external_object_version")
        val externalHash: ByteArray? = row.getBytes("external_ciphertext_hash")

        fun matches(tuple: ComplaintAdminDeleteTuple): Boolean = actor == tuple.actor && key == tuple.key && operation == tuple.operation &&
            scope == tuple.scope.id && test && targets == tuple.targetIds() && fingerprint?.let { MessageDigest.isEqual(it, tuple.fingerprintBytes()) } == true

        fun completed(): ComplaintAdminDeleteReceipt {
            check(valid && state == "COMPLETED")
            return when (outcome) {
                "APPLIED" -> when (operation) {
                    "ADMIN_DELETE" -> ComplaintAdminDeleteReceipt.Applied(checkNotNull(consumedGrantId)).also { check(status == 204 && acknowledgments == null && targets?.size == 1) }
                    "ADMIN_BATCH_DELETE" -> ComplaintAdminDeleteReceipt.BatchApplied(checkNotNull(targets), checkNotNull(consumedGrantId)).also { check(status == 200 && acknowledgments == targets) }
                    else -> error("Stored deletion family refused")
                }
                "REJECTED" -> ComplaintAdminDeleteReceipt.Rejected(ComplaintAdminDeleteRejection.entries.single { it.name == problem }, consumedGrantId).also { check(it.status == status) }
                else -> error("Stored deletion outcome refused")
            }
        }
    }

    fun tuple(event: TestOwnerDeleteJournalEventV1): ComplaintAdminDeleteTuple = when (val value = event.adminComparison) {
        is TestAdminDeleteJournalTupleV1 -> ComplaintAdminDeleteTuple(value.actorId, value.scope, value.operationKey, event.complaintIds().single(), value.fingerprintBytes())
        is TestAdminBatchDeleteJournalTupleV1 -> ComplaintAdminDeleteTuple.batch(value.actorId, value.scope, value.operationKey, event.complaintIds(), value.fingerprintBytes())
    }
    fun recovery(event: TestOwnerDeleteJournalEventV1) = when (val value = event.adminComparison) {
        is TestAdminDeleteJournalTupleV1 -> OwnerDeleteCapacityCharges.RECOVERY.also { check(event.complaintIds().size == 1) }
        is TestAdminBatchDeleteJournalTupleV1 -> AdminBatchDeleteCapacityCharges.recovery(value.ownerInstallationIds().size, event.complaintIds().size)
    }
    fun completed(event: TestOwnerDeleteJournalEventV1): ComplaintAdminDeleteReceipt = when (val value = event.adminComparison) {
        is TestAdminDeleteJournalTupleV1 -> ComplaintAdminDeleteReceipt.Applied(value.consumedGrantId)
        is TestAdminBatchDeleteJournalTupleV1 -> ComplaintAdminDeleteReceipt.BatchApplied(event.complaintIds(), value.consumedGrantId)
    }
    fun requireApplied(receipt: ComplaintAdminDeleteReceipt, event: TestOwnerDeleteJournalEventV1) {
        check(receipt.consumedGrantId == event.adminComparison.consumedGrantId)
        when (event.adminComparison) {
            is TestAdminDeleteJournalTupleV1 -> check(receipt is ComplaintAdminDeleteReceipt.Applied)
            is TestAdminBatchDeleteJournalTupleV1 -> check(receipt is ComplaintAdminDeleteReceipt.BatchApplied && receipt.ids == event.complaintIds())
        }
    }
    fun array(ids: List<UUID>): String = ids.joinToString(",", "{", "}")
    private fun ids(row: ResultSet, name: String): List<UUID>? {
        val sql = row.getArray(name) ?: return null
        return try {
            val values = sql.array as Array<*>
            check(values.size in 1..50)
            values.map { it as UUID }
        } finally { sql.free() }
    }

}
