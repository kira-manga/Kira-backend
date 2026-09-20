package me.manga.kira.backend.audit.domain

import java.time.Instant

/** Fixed scope-only authorization detail. The private allocation selects scope, not this entry.
 * No installation, owner, key, event ID or arbitrary map. */
internal class CountedInstallationDeleteAuthorizationAuditEntry(val submittedVersion: Long, val detailJson: String, val createdAt: Instant) {
    init {
        require(submittedVersion > 0 && detailJson == "{\"version\":$submittedVersion}")
    }

    override fun toString(): String = "CountedInstallationDeleteAuthorizationAuditEntry(redacted)"
}
