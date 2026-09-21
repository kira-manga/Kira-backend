package me.manga.kira.backend.complaint.parsing

import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchStatusInput
import me.manga.kira.backend.complaint.domain.ComplaintDataScope

/** Strict legacy STATUS entry; DELETE cannot fall through this wrapper, even at one target. */
internal object ComplaintAdminBatchStatusParser {
    const val MAX_BODY_BYTES = ComplaintAdminBatchParser.MAX_BODY_BYTES
    fun parse(bytes: ByteArray, scope: ComplaintDataScope, key: String): ComplaintAdminBatchStatusInput =
        ComplaintAdminBatchParser.parseStatus(bytes, scope, key)
}
