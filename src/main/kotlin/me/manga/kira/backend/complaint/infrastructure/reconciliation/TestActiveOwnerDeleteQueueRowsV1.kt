package me.manga.kira.backend.complaint.infrastructure.reconciliation

import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** Detached bounded comparisons only. Neither row class can issue an original, lease, APPLY or ack. */
internal object TestActiveOwnerDeleteQueueRowsV1 {
    class Current(row: ResultSet) {
        val epoch = long(row, "publication_epoch")
        val token = long(row, "lease_token")
        val owner: UUID? = row.getObject("lease_owner", UUID::class.java)
        val expiresAt: Instant? = row.getTimestamp("lease_expires_at")?.toInstant()
        val sampledAt: Instant = checkNotNull(row.getTimestamp("sampled_at")).toInstant()
        private val global = digest(row, "global_fingerprint")
        private val run = digest(row, "run_fingerprint")
        private val control = digest(row, "control_fingerprint")
        init { requireQueue(boolean(row, "valid") && epoch in 1..2 && token >= 0 && (owner == null) == (expiresAt == null)) }
        fun requireSame(other: Current) = requireQueue(epoch == other.epoch && global.contentEquals(other.global) &&
            run.contentEquals(other.run) && control.contentEquals(other.control) && !other.sampledAt.isBefore(sampledAt))
        fun requireLease(original: TestActiveOwnerDeleteQueueV1) = requireQueue(owner == original.attemptId && token == original.leaseToken &&
            expiresAt == original.leaseExpiresAt && checkNotNull(expiresAt).isAfter(sampledAt))
        override fun toString() = "ActiveQueueCurrent(detached,redacted,no-authority)"
    }

    class Observation(row: ResultSet) {
        val owner: UUID = checkNotNull(row.getObject("lease_owner", UUID::class.java))
        val token = long(row, "fencing_token")
        val state: String = checkNotNull(row.getString("state"))
        val startedAt: Instant = checkNotNull(row.getTimestamp("started_at")).toInstant()
        val settledAt: Instant? = row.getTimestamp("settled_at")?.toInstant()
        val primaryAcked = long(row, "primary_acked")
        val dlqAcked = long(row, "dlq_acked")
        private val fingerprint = digest(row, "fingerprint")
        init { requireQueue(boolean(row, "valid")) }
        fun requireSame(other: Observation) = requireQueue(fingerprint.contentEquals(other.fingerprint))
        fun requirePolling(original: TestActiveOwnerDeleteQueueV1) = requireQueue(owner == original.attemptId && token == original.leaseToken &&
            state == "POLLING" && startedAt == original.pollingStartedAt && settledAt == null && primaryAcked == 0L && dlqAcked == 0L)
        override fun toString() = "ActiveQueueObservation(historical-or-pending,redacted,no-health-authority)"
    }

    fun boolean(row: ResultSet, name: String): Boolean = row.getBoolean(name).also { requireQueue(!row.wasNull()) }
    fun long(row: ResultSet, name: String): Long = row.getLong(name).also { requireQueue(!row.wasNull()) }
    private fun digest(row: ResultSet, name: String) = checkNotNull(row.getBytes(name)).also { requireQueue(it.size == 32) }.copyOf()
}
