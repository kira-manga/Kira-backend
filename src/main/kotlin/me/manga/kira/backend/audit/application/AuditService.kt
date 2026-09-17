package me.manga.kira.backend.audit.application

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.audit.domain.AuditRepository
import me.manga.kira.backend.audit.domain.ComplaintAuditAllocation
import me.manga.kira.backend.audit.domain.ComplaintAuditMutation
import me.manga.kira.backend.audit.domain.CountedComplaintAuditEntry
import me.manga.kira.backend.audit.domain.CountedComplaintAuditRepository
import me.manga.kira.backend.audit.domain.CountedInstallationDeleteAuthorizationAuditEntry
import me.manga.kira.backend.audit.domain.CountedInstallationEnrollmentAuditEntry
import me.manga.kira.backend.audit.domain.CountedOwnerDeleteAllAuditEntry
import me.manga.kira.backend.audit.domain.NewAuditEntry
import me.manga.kira.backend.audit.domain.OwnerDeleteAllAuditOutcome
import me.manga.kira.backend.audit.domain.scalarDetails
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.security.CurrentUser
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Writes the `audit_log` (PLAN §5/§6). Called by every mutating admin/auth service. The acting user
 * defaults to the [CurrentUser] SecurityContext principal (null = system actor, e.g. the seeder), and
 * `created_at` is stamped from the injected [Clock] so a `DOCUMENT_PUBLISHED` row can carry the SAME
 * instant serialized as the document's `generatedAt` (PLAN §9 steps 7–8; [recordAt] takes that instant).
 *
 * **Log-hygiene (PLAN §6, non-negotiable):** [detail] must contain identifiers, revision numbers, and
 * checksums ONLY — never full config bodies, header values, completion prompts/results, or passwords.
 * Only scalar values are encodable here, which keeps a caller from accidentally stuffing an object body
 * into the trail. Callers pass small maps of stable keys → scalar identifiers.
 */
@Service
class AuditService(private val audit: AuditRepository, private val currentUser: CurrentUser, private val clock: Clock) {
    /** Record an audit row, stamping `created_at` from the injected clock. */
    fun record(action: AuditAction, entityType: String, entityId: String, detail: Map<String, Any?> = emptyMap(), actorUserId: UUID? = currentActor()) =
        recordAt(action, entityType, entityId, clock.instant(), detail, actorUserId)

    /**
     * Record an audit row at a SPECIFIC instant (PLAN §9 steps 7–8 — the publication audit detail must
     * carry the exact `generatedAt`/`created_at` instant of the snapshot, not a fresh `clock.instant()`).
     */
    fun recordAt(
        action: AuditAction,
        entityType: String,
        entityId: String,
        at: java.time.Instant,
        detail: Map<String, Any?> = emptyMap(),
        actorUserId: UUID? = currentActor(),
    ) {
        require(!action.wire.startsWith("COMPLAINT_")) {
            "Complaint audit writes are not available through the ordinary route."
        }
        audit.record(
            NewAuditEntry(
                actorUserId = actorUserId,
                action = action.wire,
                entityType = entityType,
                entityId = entityId,
                detailJson = encode(detail),
                createdAt = at,
            ),
        )
    }

    /** Dormant, I/O-free preparation with explicit time; it cannot write or allocate audit capacity. */
    internal fun prepareComplaintMutation(mutation: ComplaintAuditMutation, at: Instant): PreparedComplaintAudit {
        val detailJson = encode(mutation.scalarDetails())
        requireComplaintAuditPayloadSize(detailJson)
        return PreparedMutationAudit(mutation, detailJson, at)
    }

    /** Existing-phase write only. The adapter must consume a real allocation for this exact mutation. */
    internal fun recordComplaintMutation(mutation: ComplaintAuditMutation, allocation: ComplaintAuditAllocation, at: Instant) {
        val prepared = prepareComplaintMutation(mutation, at)
        val counted = checkNotNull(audit as? CountedComplaintAuditRepository)
        counted.recordComplaint(CountedComplaintAuditEntry(mutation, prepared.detailJson, prepared.createdAt), allocation)
    }

    /** Scope-only enrollment event; its existing-phase allocation must prove both real paired INSERTs. */
    internal fun recordInstallationEnrollment(scope: ComplaintDataScope, allocation: ComplaintAuditAllocation, at: Instant) {
        val detailJson = encode(mapOf("version" to 1))
        requireComplaintAuditPayloadSize(detailJson)
        val counted = checkNotNull(audit as? CountedComplaintAuditRepository)
        counted.recordInstallationEnrollment(CountedInstallationEnrollmentAuditEntry(scope, detailJson, at), allocation)
    }

    /** Scope-only fixed authorization event. Actual receipt/pending writes and prepaid custody are checked by the shared adapter. */
    internal fun recordInstallationDeleteAuthorization(submittedVersion: Long, allocation: ComplaintAuditAllocation, at: Instant) {
        val detailJson = encode(mapOf("version" to submittedVersion))
        requireComplaintAuditPayloadSize(detailJson)
        val counted = checkNotNull(audit as? CountedComplaintAuditRepository)
        counted.recordInstallationDeleteAuthorization(CountedInstallationDeleteAuthorizationAuditEntry(submittedVersion, detailJson, at), allocation)
    }

    /** Actual removed resources only, then one aggregate installation completion; allocation binds the locked outcome. */
    internal fun recordOwnerDeleteAll(outcome: OwnerDeleteAllAuditOutcome, allocation: ComplaintAuditAllocation, at: Instant) {
        val detailJson = encode(outcome.scalarDetails())
        requireComplaintAuditPayloadSize(detailJson)
        val counted = checkNotNull(audit as? CountedComplaintAuditRepository)
        counted.recordOwnerDeleteAll(CountedOwnerDeleteAllAuditEntry(outcome, detailJson, at), allocation)
    }

    private class PreparedMutationAudit(mutation: ComplaintAuditMutation, override val detailJson: String, override val createdAt: Instant) :
        PreparedComplaintAudit {
        override val action = mutation.action
        override val subject = mutation.subject
        override val actor = mutation.actor

        override fun toString(): String = "PreparedComplaintAudit(action=${action.wire}, redacted)"
    }

    private fun currentActor(): UUID? = currentUser.getOrNull()?.id

    /** Encode a scalars-only detail map into a canonical JSON object string (PLAN §6 hygiene). */
    private fun encode(detail: Map<String, Any?>): String = JsonObject(
        detail.mapValues { (key, value) ->
            when (value) {
                null -> JsonNull

                is String -> JsonPrimitive(value)

                is Boolean -> JsonPrimitive(value)

                is Int -> JsonPrimitive(value)

                is Long -> JsonPrimitive(value)

                else -> error(
                    "audit detail '$key' must be a scalar identifier/number/checksum " +
                        "(String/Int/Long/Boolean/null) — never an object body (PLAN §6 log-hygiene)",
                )
            }
        },
    ).toString()

    companion object {
        const val ENTITY_SOURCE = "source"
        const val ENTITY_REVISION = "revision"
        const val ENTITY_DOCUMENT = "document"
        const val ENTITY_SOURCE_DRAFT = "source_draft"
        const val ENTITY_SOURCE_CHANGESET = "source_changeset"
        const val ENTITY_USER = "user"
        const val ENTITY_LOGIN_IDENTIFIER = "login_identifier"
        const val ENTITY_TUTORIAL = "tutorial"
        const val ENTITY_TUTORIAL_CATEGORY = "tutorial_category"
        const val ENTITY_TUTORIAL_MEDIA = "tutorial_media"
    }
}
