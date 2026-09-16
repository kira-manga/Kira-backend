package me.manga.kira.backend.security

import java.time.Instant

/**
 * One fixed complaint-grant deletion/refund batch under the selected ordinary owner. Only the named
 * executor supplies the once-sampled trusted Clock cutoff. No caller scope, UUIDs, vector or count
 * can authorize a refund, and used-at changes without physical deletion never release capacity.
 */
internal interface ComplaintGrantCleanup {
    fun deleteEligibleComplaintGrantsAndRefund(cutoff: Instant): Int
}
