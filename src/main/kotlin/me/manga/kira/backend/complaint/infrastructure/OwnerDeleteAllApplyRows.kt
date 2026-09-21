package me.manga.kira.backend.complaint.infrastructure

import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.util.UUID

/** Bounded SQL comparison rows only. No row or constructible scalar supplies APPLY/provider authority. */
internal object OwnerDeleteAllApplyRows {
    /** Necessary comparison only, not replay authority. Registered RELOAD/APPLY supply their
     * actual sampled database time; exact T/D + 192h shape and all family guards remain separate. */
    fun completedReplayWindowsLive(receiptExpiry: Instant?, verifierExpiry: Instant?, comparisonAt: Instant): Boolean =
        receiptExpiry?.isAfter(comparisonAt) == true && verifierExpiry?.isAfter(comparisonAt) == true

    class Receipt(
        val key: UUID,
        val version: Long,
        val fingerprint: ByteArray,
        val reference: String,
        val authorizedAt: Instant,
        val createdAt: Instant,
        val state: String,
        val completedAt: Instant?,
        val expiresAt: Instant?,
        val external: External?,
    )

    class External(val eventId: String, val epoch: Long, val version: String, val hash: ByteArray)

    class Publication(
        val eventId: String,
        val writer: UUID,
        val epoch: Long,
        val routingKeyId: String,
        val objectKey: String,
        val targetCount: Int,
        val bytes: ByteArray,
        val hash: ByteArray,
        val state: String,
        val createdAt: Instant,
        val appliedAt: Instant?,
        val verification: Verification,
    )

    class Verification(
        val version: String,
        val hash: ByteArray,
        val createdAt: Instant,
        val retainUntil: Instant,
        val verifiedAt: Instant,
        val bytes: ByteArray,
        val verificationHash: ByteArray,
    )

    class Recovery(
        val eventId: String,
        val state: String,
        val promise: ComplaintCapacityVector,
        val used: ComplaintCapacityVector,
        val convertedAt: Instant?,
    ) {
        val remaining: ComplaintCapacityVector get() = promise - used
    }

    class Installation(val state: String, val terminalAt: Instant?)

    class Credential(
        val state: String,
        val credentialVersion: Long,
        val rowVersion: Long,
        val verifier: ByteArray,
        val deletedAt: Instant?,
        val expiresAt: Instant?,
    )

    class Resource(val id: UUID, val state: String, val deletedAt: Instant?)

    class Content(val id: UUID, val owner: UUID, val kind: String, val version: Long)

    fun receipt(row: ResultSet): Receipt {
        valid(row)
        val state = string(row, "state")
        return Receipt(
            row.getObject("deletion_key", UUID::class.java), long(row, "submitted_credential_version"), bytes(row, "fingerprint"),
            string(row, "publication_ref"), instant(row, "authorized_at"), instant(row, "created_at"), state, time(row, "completed_at"), time(row, "expires_at"),
            if (state == "COMPLETED") {
                External(
                    string(row, "external_event_id"),
                    long(row, "external_epoch"),
                    string(row, "external_object_version"),
                    bytes(row, "external_ciphertext_hash"),
                )
            } else {
                null
            },
        )
    }

    fun publication(row: ResultSet): Publication {
        valid(row)
        return Publication(
            string(row, "event_id"), row.getObject("writer_generation", UUID::class.java), long(row, "journal_epoch"),
            string(row, "routing_key_id"), string(row, "object_key"), row.getInt("target_count").also { check(!row.wasNull() && it in 0..100) },
            bytes(row, "event_bytes"), bytes(row, "semantic_hash"), string(row, "state"), instant(row, "created_at"), time(row, "applied_at"),
            Verification(
                string(row, "object_version"),
                bytes(row, "ciphertext_hash"),
                instant(row, "object_created_at"),
                instant(row, "retain_until"),
                instant(row, "verified_at"),
                bytes(row, "verification_bytes"),
                bytes(row, "verification_hash"),
            ),
        )
    }

    fun recovery(row: ResultSet): Recovery {
        valid(row)
        val state = string(row, "state")
        val event = string(row, "event_id")
        check(string(row, "publication_ref") == event)
        return Recovery(
            event,
            state,
            vector(row, "reserved_amounts"),
            if (state == "PARTIAL") vector(row, "converted_amounts") else ComplaintCapacityVector.ZERO,
            time(row, "converted_at"),
        )
    }

    fun installation(row: ResultSet): Installation {
        valid(row)
        return Installation(string(row, "state"), time(row, "terminal_at"))
    }

    fun credential(row: ResultSet): Credential {
        valid(row)
        return Credential(
            string(row, "state"),
            long(row, "credential_version"),
            long(row, "version"),
            bytes(row, "secret_verifier"),
            time(row, "deleted_at"),
            time(row, "verifier_expires_at"),
        )
    }

    fun resource(row: ResultSet): Resource {
        valid(row)
        return Resource(row.getObject("id", UUID::class.java), string(row, "state"), time(row, "deleted_at"))
    }

    fun content(row: ResultSet): Content {
        valid(row)
        return Content(row.getObject("id", UUID::class.java), row.getObject("owner_id", UUID::class.java), string(row, "kind"), long(row, "version"))
    }

    fun valid(row: ResultSet) {
        check(row.getBoolean("live") && !row.wasNull())
        check(row.getBoolean("valid") && !row.wasNull())
    }

    fun long(row: ResultSet, name: String): Long = row.getLong(name).also { check(!row.wasNull()) }
    fun string(row: ResultSet, name: String): String = checkNotNull(row.getString(name))
    fun bytes(row: ResultSet, name: String): ByteArray = checkNotNull(row.getBytes(name))
    fun time(row: ResultSet, name: String): Instant? = row.getTimestamp(name)?.toInstant()
    fun instant(row: ResultSet, name: String): Instant = checkNotNull(time(row, name))

    private fun vector(row: ResultSet, name: String): ComplaintCapacityVector {
        // SQL's bounded CASE/valid flag already checked dimensions, lower bound, width and nonnegative values.
        val array = checkNotNull(row.getArray(name))
        try {
            check(array.baseType == Types.BIGINT)
            val values = array.array as? Array<*> ?: error("Invalid bounded recovery vector")
            check(values.size == 22)
            return ComplaintCapacityVector.of(LongArray(values.size) { checkNotNull(values[it] as? Long) })
        } finally {
            array.free()
        }
    }
}
