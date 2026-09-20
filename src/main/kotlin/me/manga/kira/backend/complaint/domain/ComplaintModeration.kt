package me.manga.kira.backend.complaint.domain

import java.time.Instant
import java.util.UUID

sealed interface ComplaintClosure {
    val reason: String?

    data class Admin(override val reason: String, val actorId: UUID, val closedAt: Instant) : ComplaintClosure {
        init {
            if (ComplaintTextRules.closureReason(reason) != reason) invalidModerationState()
        }

        override fun toString(): String = "ComplaintClosure.Admin(redacted)"
    }

    /** Only an imported LEGACY_UNCLAIMED row may lack its historical reason or time. */
    data class Legacy(override val reason: String?, val closedAt: Instant?) : ComplaintClosure {
        init {
            if (reason != null && ComplaintTextRules.closureReason(reason) != reason) invalidModerationState()
        }

        override fun toString(): String = "ComplaintClosure.Legacy(redacted)"
    }
}

/** The content, ownership lookup, authorization and locked update are separate application concerns. */
data class ComplaintModerationState(
    val kind: ComplaintKind,
    val ownership: ComplaintOwnership,
    val status: ComplaintStatus,
    val version: Long,
    val closure: ComplaintClosure? = null,
) {
    init {
        if (version <= 0 || (status == ComplaintStatus.CLOSED) != (closure != null)) invalidModerationState()
        if (closure is ComplaintClosure.Legacy && ownership != ComplaintOwnership.LEGACY_UNCLAIMED) invalidModerationState()
        if (status == ComplaintStatus.UNKNOWN && ownership != ComplaintOwnership.LEGACY_UNCLAIMED) invalidModerationState()
        if ((kind == ComplaintKind.NOTICE) != (ownership == ComplaintOwnership.SYSTEM)) invalidModerationState()
        if (kind == ComplaintKind.NOTICE && status != ComplaintStatus.PINNED) invalidModerationState()
    }
}

private fun invalidModerationState(): Nothing = throw ComplaintRuleException(ComplaintRuleCode.INVALID_MODERATION_STATE)
