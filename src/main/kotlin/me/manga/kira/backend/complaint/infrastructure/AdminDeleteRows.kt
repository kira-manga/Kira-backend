package me.manga.kira.backend.complaint.infrastructure

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
        private val fingerprint: ByteArray? = row.getBytes("fingerprint")
        val consumedGrantId: UUID? = row.getObject("consumed_grant_id", UUID::class.java)
        val state: String = checkNotNull(row.getString("state"))
        val comparable: Boolean = OwnerDeleteRows.bool(row, "comparable")
        val visible: Boolean = OwnerDeleteRows.bool(row, "visible")
        val valid: Boolean = OwnerDeleteRows.bool(row, "valid_shape")
        val publication: String? = row.getString("publication_ref")
        val authorizedAt: Instant? = row.getTimestamp("authorized_at")?.toInstant()
        private val outcome = row.getString("outcome")
        private val status = row.getInt("response_status")
        private val problem = row.getString("problem_code")
        val externalEvent: String? = row.getString("external_event_id")
        val externalEpoch: Long = row.getLong("external_epoch")
        val externalVersion: String? = row.getString("external_object_version")
        val externalHash: ByteArray? = row.getBytes("external_ciphertext_hash")

        fun matches(tuple: ComplaintAdminDeleteTuple): Boolean = actor == tuple.actor && key == tuple.key && operation == "ADMIN_DELETE" &&
            scope == tuple.scope.id && test && target == tuple.targetId && fingerprint?.let { MessageDigest.isEqual(it, tuple.fingerprintBytes()) } == true

        fun completed(): ComplaintAdminDeleteReceipt {
            check(valid && state == "COMPLETED")
            return when (outcome) {
                "APPLIED" -> ComplaintAdminDeleteReceipt.Applied(checkNotNull(consumedGrantId)).also { check(status == 204) }
                "REJECTED" -> ComplaintAdminDeleteReceipt.Rejected(ComplaintAdminDeleteRejection.entries.single { it.name == problem }, consumedGrantId).also { check(it.status == status) }
                else -> error("Stored deletion outcome refused")
            }
        }
    }

}
