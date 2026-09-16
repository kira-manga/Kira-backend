package me.manga.kira.backend.audit.application

import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.audit.domain.ComplaintAuditActor
import me.manga.kira.backend.audit.domain.ComplaintAuditResourceSubject
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import java.time.Instant

/**
 * An immutable payload candidate produced by [AuditService], never a write permit or capacity
 * allocation. The future writer still owes subject/scope/Admin authorization, phase-bound single-use
 * allocation and atomic row/charge persistence. There is deliberately no conversion to NewAuditEntry.
 */
internal sealed interface PreparedComplaintAudit {
    val action: AuditAction
    val subject: ComplaintAuditResourceSubject
    val actor: ComplaintAuditActor
    val detailJson: String
    val createdAt: Instant
}

/** Byte-size check only, after the existing scalar encoder; this does not prepare or authorize an audit. */
internal fun requireComplaintAuditPayloadSize(detailJson: String) {
    require(detailJson.toByteArray(Charsets.UTF_8).size <= ComplaintCapacityCharges.MAX_AUDIT_PAYLOAD_BYTES) {
        "Complaint audit payload exceeds the byte limit."
    }
}
