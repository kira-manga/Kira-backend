package me.manga.kira.backend.complaint.domain

import java.time.Instant
import java.util.UUID

/** One transition policy for single-item and batch moderation; it performs no persistence or audit I/O. */
object ComplaintStateMachine {
    private val statusTargets = setOf(
        ComplaintStatus.OPEN,
        ComplaintStatus.IN_PROGRESS,
        ComplaintStatus.PLANNED,
        ComplaintStatus.RESOLVED,
        ComplaintStatus.NOT_PLANNED,
    )

    fun transition(current: ComplaintModerationState, target: ComplaintStatus): ComplaintModerationState {
        requireMutable(current)
        if (target !in statusTargets) throw ComplaintRuleException(ComplaintRuleCode.INVALID_STATUS_TARGET)
        if (current.status == target) throw ComplaintRuleException(ComplaintRuleCode.NO_CHANGE)
        return current.copy(status = target, version = nextVersion(current), closure = null)
    }

    fun close(current: ComplaintModerationState, reason: String, actorId: UUID, closedAt: Instant): ComplaintModerationState {
        requireMutable(current)
        val normalized = ComplaintTextRules.closureReason(reason)
        // An explicit Admin closure must also normalize an incomplete legacy tuple, even when its
        // old reason happens to match. A true no-op never changes the actor/time or consumes a version.
        if (current.closure is ComplaintClosure.Admin && current.closure.reason == normalized) {
            throw ComplaintRuleException(ComplaintRuleCode.NO_CHANGE)
        }
        return current.copy(
            status = ComplaintStatus.CLOSED,
            version = nextVersion(current),
            closure = ComplaintClosure.Admin(normalized, actorId, closedAt),
        )
    }

    /** Called after a validated content edit, retaining closure provenance rather than rewriting it. */
    fun contentEdited(current: ComplaintModerationState): ComplaintModerationState {
        requireMutable(current)
        return current.copy(version = nextVersion(current))
    }

    private fun requireMutable(current: ComplaintModerationState) {
        if (current.kind == ComplaintKind.NOTICE) throw ComplaintRuleException(ComplaintRuleCode.IMMUTABLE_NOTICE)
    }

    private fun nextVersion(current: ComplaintModerationState): Long {
        if (current.version == Long.MAX_VALUE) throw ComplaintRuleException(ComplaintRuleCode.VERSION_EXHAUSTED)
        return current.version + 1
    }
}
