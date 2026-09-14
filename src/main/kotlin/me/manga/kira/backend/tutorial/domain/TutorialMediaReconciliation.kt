package me.manga.kira.backend.tutorial.domain

import java.util.UUID

data class MediaReconciliationFinding(val issue: MediaStorageIssue, val mediaId: UUID? = null, val filename: String? = null, val published: Boolean? = null)

/** A bounded observation, not an atomic online-health or installed-writer-exclusion assertion. */
data class MediaReconciliationReport(
    val rowsChecked: Int,
    val publishedRowsChecked: Int,
    /** True only if the entire published subset was covered AND its exact bytes verified. */
    val publishedComplete: Boolean,
    /** Complete diagnostic traversal; a known missing/corrupt draft still has a finding. */
    val complete: Boolean,
    val findings: List<MediaReconciliationFinding>,
    /** Originals preserved and removed during this operation, not preexisting quarantine copies. */
    val quarantinedFiles: Int = 0,
    val retainedQuarantineFiles: Int = 0,
) {
    val clean: Boolean get() = complete && publishedComplete && findings.isEmpty()
}
