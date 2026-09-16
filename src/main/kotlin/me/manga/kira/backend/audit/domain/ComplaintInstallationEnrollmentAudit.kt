package me.manga.kira.backend.audit.domain

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import java.time.Instant

/** The existing AuditService supplies this narrow entry; a returned call alone does not prove insertion. */
internal fun interface ComplaintInstallationEnrollmentAudit {
    fun record(scope: ComplaintDataScope, allocation: ComplaintAuditAllocation, at: Instant)
}

/** Scope-only, fixed enrolled event. No installation/owner/credential/network identifier is accepted. */
internal class CountedInstallationEnrollmentAuditEntry(val scope: ComplaintDataScope, val detailJson: String, val createdAt: Instant) {
    init {
        require(detailJson == "{\"version\":1}") // Exact initial credential version from the shared scalar encoder, not an arbitrary map.
    }

    override fun toString(): String = "CountedInstallationEnrollmentAuditEntry(redacted)"
}
