package me.manga.kira.backend.sourceconfig.application

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.common.exception.PayloadTooLargeException
import me.manga.kira.backend.sourceconfig.domain.NewSourceEditorDraft
import me.manga.kira.backend.sourceconfig.domain.RevisionRepository
import me.manga.kira.backend.sourceconfig.domain.SourceConfigRepository
import me.manga.kira.backend.sourceconfig.domain.SourceEditorDraft
import me.manga.kira.backend.sourceconfig.domain.SourceEditorDraftRepository
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import me.manga.kira.backend.sourceconfig.validation.SourceConfigValidator
import me.manga.kira.backend.sourceconfig.validation.ValidationResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Collaborative server-side source editor workspace.
 *
 * Autosave only changes [SourceEditorDraft]; immutable revisions and public catalog state are
 * untouched. Finalization reuses the strict authoring path and is atomic with advancing the draft
 * baseline. A stale optimistic version rolls the entire finalize transaction back.
 */
@Service
class SourceEditorDraftService(
    private val sources: SourceConfigRepository,
    private val revisions: RevisionRepository,
    private val drafts: SourceEditorDraftRepository,
    private val sourceAdmin: SourceAdminService,
    private val validator: SourceConfigValidator,
    private val audit: AuditService,
    private val clock: Clock,
) {
    @Transactional
    fun open(api: String, fromRevision: Int?, actorId: UUID): SourceEditorDraft {
        val head = sources.lockByApiForUpdate(api) ?: throw SourceNotFoundException(api)
        drafts.findBySourceConfigId(head.id)?.let { return it }
        val revisionNumber = fromRevision ?: revisions.latestRevisionNumber(head.id)
            ?: throw RevisionNotFoundException(api, 0)
        val revision =
            revisions.findBySourceAndNumber(head.id, revisionNumber)
                ?: throw RevisionNotFoundException(api, revisionNumber)
        // The source-row lock serializes concurrent opens for this api, so the one-draft unique
        // constraint is deterministic without catching an exception that would mark this transaction
        // rollback-only.
        val created =
            drafts.create(
                NewSourceEditorDraft(
                    sourceConfigId = head.id,
                    basedOnRevisionNumber = revisionNumber,
                    contentJson = revision.configCanonicalJson,
                    actorId = actorId,
                    at = clock.instant(),
                ),
            )
        audit.record(
            AuditAction.SOURCE_DRAFT_OPENED,
            AuditService.ENTITY_SOURCE_DRAFT,
            created.id.toString(),
            mapOf("api" to api, "basedOnRevisionNumber" to revisionNumber),
            actorId,
        )
        return created
    }

    @Transactional(readOnly = true)
    fun get(api: String): SourceEditorDraft {
        val head = sources.findByApi(api) ?: throw SourceNotFoundException(api)
        return drafts.findBySourceConfigId(head.id) ?: throw SourceDraftNotFoundException()
    }

    @Transactional
    fun save(api: String, expectedVersion: Long, contentJson: String, actorId: UUID): SourceEditorDraft {
        requireSafeContentSize(contentJson)
        val current = get(api)
        return drafts.updateContent(current.id, expectedVersion, contentJson, actorId, clock.instant())
            ?: throw SourceDraftVersionConflictException()
    }

    @Transactional(readOnly = true)
    fun validate(api: String, expectedVersion: Long): ValidationResult {
        val draft = get(api)
        if (draft.version != expectedVersion) throw SourceDraftVersionConflictException()
        val model = SourceConfigParser.parseStrictSource(draft.contentJson)
        StructuralAuthoringGate.check(model, pathApi = api)
        return validator.validate(
            me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument(
                schemaVersion = DocumentAssemblyService.SCHEMA_VERSION,
                sources = listOf(model),
            ),
        )
    }

    @Transactional
    fun finalizeDraft(api: String, expectedVersion: Long, actorId: UUID): FinalizedSourceDraft {
        val draft = get(api)
        if (draft.version != expectedVersion) throw SourceDraftVersionConflictException()
        val created = sourceAdmin.createRevision(api, draft.contentJson, actorId)
        val revision = sourceAdmin.getRevision(api, created.revisionNumber).revision
        val updated =
            drafts.updateBaseline(
                id = draft.id,
                expectedVersion = expectedVersion,
                basedOnRevisionNumber = created.revisionNumber,
                contentJson = revision.configCanonicalJson,
                actorId = actorId,
                updatedAt = clock.instant(),
            ) ?: throw SourceDraftVersionConflictException()
        audit.record(
            AuditAction.SOURCE_DRAFT_FINALIZED,
            AuditService.ENTITY_SOURCE_DRAFT,
            draft.id.toString(),
            mapOf("api" to api, "revisionNumber" to created.revisionNumber),
            actorId,
        )
        return FinalizedSourceDraft(updated, created)
    }

    @Transactional
    fun publish(api: String, expectedVersion: Long, actorId: UUID): PublishedSourceDraft {
        val draft = get(api)
        if (draft.version != expectedVersion) throw SourceDraftVersionConflictException()
        val published = sourceAdmin.publishEditorContent(api, draft.contentJson, actorId)
        val revision = sourceAdmin.getRevision(api, published.revision.revisionNumber).revision
        val updated =
            drafts.updateBaseline(
                id = draft.id,
                expectedVersion = expectedVersion,
                basedOnRevisionNumber = published.revision.revisionNumber,
                contentJson = revision.configCanonicalJson,
                actorId = actorId,
                updatedAt = clock.instant(),
            ) ?: throw SourceDraftVersionConflictException()
        audit.record(
            AuditAction.SOURCE_DRAFT_FINALIZED,
            AuditService.ENTITY_SOURCE_DRAFT,
            draft.id.toString(),
            mapOf(
                "api" to api,
                "revisionNumber" to published.revision.revisionNumber,
                "documentRevision" to published.publication.documentRevision,
            ),
            actorId,
        )
        return PublishedSourceDraft(updated, published)
    }

    @Transactional
    fun discard(api: String, expectedVersion: Long, actorId: UUID) {
        val draft = get(api)
        if (!drafts.delete(draft.id, expectedVersion)) throw SourceDraftVersionConflictException()
        audit.record(
            AuditAction.SOURCE_DRAFT_DISCARDED,
            AuditService.ENTITY_SOURCE_DRAFT,
            draft.id.toString(),
            mapOf("api" to api, "version" to expectedVersion),
            actorId,
        )
    }

    private fun requireSafeContentSize(content: String) {
        if (content.toByteArray(Charsets.UTF_8).size > MAX_DRAFT_BYTES) {
            throw PayloadTooLargeException("source editor draft must not exceed 512 KiB.")
        }
    }

    companion object {
        const val MAX_DRAFT_BYTES = 512 * 1024
    }
}

data class FinalizedSourceDraft(val draft: SourceEditorDraft, val revision: SourceMutationResult)

data class PublishedSourceDraft(val draft: SourceEditorDraft, val publication: EditorContentPublishOutcome)
