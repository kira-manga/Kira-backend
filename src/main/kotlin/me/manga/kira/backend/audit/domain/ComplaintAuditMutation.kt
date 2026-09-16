package me.manga.kira.backend.audit.domain

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import java.util.UUID

enum class ComplaintAuditActorKind { ADMIN, INSTALLATION, SYSTEM }

/** Structural FK pairing only; a future writer must establish the actual Admin's identity/authority. */
internal class ComplaintAuditActor private constructor(val kind: ComplaintAuditActorKind, val adminUserId: UUID?) {
    override fun toString(): String = "ComplaintAuditActor(kind=$kind, redacted)"

    companion object {
        fun of(kind: ComplaintAuditActorKind, adminUserId: UUID? = null): ComplaintAuditActor {
            require((kind == ComplaintAuditActorKind.ADMIN) == (adminUserId != null)) {
                "Complaint audit Admin identity must be present exactly for ADMIN."
            }
            return ComplaintAuditActor(kind, adminUserId)
        }
    }
}

/**
 * A syntactically checked resource reference, not proof of existence, ownership or scope membership.
 * There is no scope-only/installation subject variant. A future writer must still prove that the
 * supplied UUID names a complaint resource rather than another kind of identifier.
 */
internal class ComplaintAuditResourceSubject private constructor(val scope: ComplaintDataScope, val resourceId: UUID) {
    override fun toString(): String = "ComplaintAuditResourceSubject(redacted)"

    companion object {
        fun of(scope: ComplaintDataScope, resourceId: String): ComplaintAuditResourceSubject =
            ComplaintAuditResourceSubject(scope, ComplaintIdentifiers.resourceId(resourceId))
    }
}

/**
 * Privacy-limited descriptions of five normal mutations, not authorization to perform or audit them.
 * No caller-supplied action, entity string, detail map, prose, installation identity or recovery
 * original-actor field is accepted. Actual state transitions remain the mutation writer's concern.
 */
internal sealed class ComplaintAuditMutation private constructor(
    val action: AuditAction,
    val subject: ComplaintAuditResourceSubject,
    val actor: ComplaintAuditActor,
    val version: Long,
) {
    init {
        require(version > 0) { "Complaint audit version must be positive." }
    }

    final override fun toString(): String = "ComplaintAuditMutation(action=${action.wire}, redacted)"

    class Created(subject: ComplaintAuditResourceSubject, actor: ComplaintAuditActor, version: Long) :
        ComplaintAuditMutation(AuditAction.COMPLAINT_CREATED, subject, actor, version)

    class Replied(subject: ComplaintAuditResourceSubject, actor: ComplaintAuditActor, version: Long) :
        ComplaintAuditMutation(AuditAction.COMPLAINT_REPLIED, subject, actor, version)

    class ContentEdited(subject: ComplaintAuditResourceSubject, actor: ComplaintAuditActor, version: Long) :
        ComplaintAuditMutation(AuditAction.COMPLAINT_CONTENT_EDITED, subject, actor, version)

    class StatusChanged(
        subject: ComplaintAuditResourceSubject,
        actor: ComplaintAuditActor,
        version: Long,
        val fromStatus: ComplaintStatus,
        val toStatus: ComplaintStatus,
    ) : ComplaintAuditMutation(AuditAction.COMPLAINT_STATUS_CHANGED, subject, actor, version) {
        init {
            require(fromStatus != toStatus) { "Complaint audit status transition must change status." }
            // Match the existing moderation target vocabulary, without accepting moderation prose.
            require(
                toStatus == ComplaintStatus.OPEN || toStatus == ComplaintStatus.IN_PROGRESS ||
                    toStatus == ComplaintStatus.PLANNED || toStatus == ComplaintStatus.RESOLVED ||
                    toStatus == ComplaintStatus.NOT_PLANNED,
            ) { "Complaint audit status target must be an ordinary moderation target." }
        }
    }

    class Closed(subject: ComplaintAuditResourceSubject, actor: ComplaintAuditActor, version: Long, val fromStatus: ComplaintStatus) :
        ComplaintAuditMutation(AuditAction.COMPLAINT_CLOSED, subject, actor, version) {
        // CLOSED -> CLOSED can describe a changed/normalized closure; its reason never enters audit.
        val toStatus: ComplaintStatus = ComplaintStatus.CLOSED
    }
}
