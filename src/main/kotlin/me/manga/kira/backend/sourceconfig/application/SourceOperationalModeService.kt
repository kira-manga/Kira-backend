package me.manga.kira.backend.sourceconfig.application

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.common.ApiFieldError
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.exception.ValidationFailedException
import me.manga.kira.backend.observability.KiraMetrics
import me.manga.kira.backend.sourceconfig.domain.NewRevision
import me.manga.kira.backend.sourceconfig.domain.NewValidationResult
import me.manga.kira.backend.sourceconfig.domain.PublishedDocument
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.RevisionRepository
import me.manga.kira.backend.sourceconfig.domain.RevisionStatus
import me.manga.kira.backend.sourceconfig.domain.SourceConfigHead
import me.manga.kira.backend.sourceconfig.domain.SourceConfigRepository
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus
import me.manga.kira.backend.sourceconfig.domain.SourceOperationalMode
import me.manga.kira.backend.sourceconfig.domain.SourceRevision
import me.manga.kira.backend.sourceconfig.domain.ValidationResultRepository
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import me.manga.kira.backend.sourceconfig.validation.SourceConfigValidator
import me.manga.kira.backend.sourceconfig.validation.ValidationResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Owns the Admin Studio's idempotent three-state source switch. Operational state is represented
 * by the existing lifecycle head plus immutable source-revision content, so no second mutable
 * column can drift from the signed catalog. Each real transition runs under the global publication
 * lock and materializes exactly one document and v2 catalog revision.
 */
@Service
class SourceOperationalModeService(
    private val sources: SourceConfigRepository,
    private val revisions: RevisionRepository,
    private val validationResults: ValidationResultRepository,
    private val publishedDocuments: PublishedDocumentRepository,
    private val assembly: DocumentAssemblyService,
    private val validator: SourceConfigValidator,
    private val audit: AuditService,
    private val clock: Clock,
    private val metrics: KiraMetrics,
) {
    @Transactional
    fun set(api: String, requested: SourceOperationalMode, actorId: UUID): SourceOperationalModeOutcome {
        publishedDocuments.lockPublicationState()
        val head = sources.lockByApiForUpdate(api) ?: throw SourceNotFoundException(api)
        val current = requireOperationalSource(head)
        if (current.mode == requested) return noOp(api, requested, current.revision)

        val publishedRevision = applyTarget(head, current, requested, actorId)
        val snapshot = assembly.materialize(actorId)
        auditChange(api, current, requested, publishedRevision, snapshot, actorId)
        auditDocumentPublished(snapshot, actorId)
        metrics.publication("operational-mode")
        return SourceOperationalModeOutcome(
            api = api,
            mode = requested,
            sourceRevisionNumber = publishedRevision.revisionNumber,
            documentRevision = snapshot.documentRevision,
            checksum = snapshot.checksum,
            noOp = false,
        )
    }

    private fun requireOperationalSource(head: SourceConfigHead): CurrentOperationalSource {
        if (head.engine != GENERIC_ENGINE || head.status !in OPERATIONAL_STATUSES) {
            throw SourceOperationalModeUnavailableException()
        }
        val revisionId = head.currentPublishedRevisionId ?: throw SourceOperationalModeUnavailableException()
        val revision = revisions.findById(revisionId) ?: error("source '${head.api}' points to a missing published revision")
        check(revision.status == RevisionStatus.PUBLISHED) {
            "source '${head.api}' points to a non-published current revision"
        }
        val model = decode(revision)
        if (model.engine != GENERIC_ENGINE) throw SourceOperationalModeUnavailableException()
        val mode =
            SourceOperationalMode.current(head.status, model.siteState)
                ?: throw SourceOperationalModeUnavailableException()
        return CurrentOperationalSource(revision, model, mode)
    }

    private fun noOp(api: String, requested: SourceOperationalMode, revision: SourceRevision): SourceOperationalModeOutcome {
        val pointer = checkNotNull(publishedDocuments.latestPointer()) {
            "a published source exists but the latest-document pointer is NULL"
        }
        val snapshot = checkNotNull(publishedDocuments.findByRevision(pointer)) {
            "latest-document pointer $pointer references a missing snapshot"
        }
        metrics.publication("noop")
        return SourceOperationalModeOutcome(
            api = api,
            mode = requested,
            sourceRevisionNumber = revision.revisionNumber,
            documentRevision = snapshot.documentRevision,
            checksum = snapshot.checksum,
            noOp = true,
        )
    }

    private fun applyTarget(head: SourceConfigHead, current: CurrentOperationalSource, requested: SourceOperationalMode, actorId: UUID): SourceRevision {
        val now = clock.instant()
        if (current.model.siteState == requested.targetSiteState) {
            sources.updateStatus(head.id, requested.targetStatus, now)
            return current.revision
        }

        val model = current.model.copy(siteState = requested.targetSiteState)
        val revision = createRevision(head, model, requested, actorId)
        revisions.markSuperseded(current.revision.id)
        revisions.markPublished(revision.id, now)
        sources.applyPublishedRevision(
            id = head.id,
            currentPublishedRevisionId = revision.id,
            status = requested.targetStatus,
            publishedAt = now,
            displayName = model.displayName,
            language = model.language,
            engine = model.engine,
            baseUrl = model.baseUrl,
            adult = model.siteState == ADULT_SITE_STATE,
            updatedAt = now,
        )
        return revision
    }

    private fun createRevision(head: SourceConfigHead, model: SourceConfig, requested: SourceOperationalMode, actorId: UUID): SourceRevision {
        val canonical = SourceConfigParser.canonicalSource(model.copy(lifecycle = "active"))
        val revision =
            revisions.create(
                NewRevision(
                    sourceConfigId = head.id,
                    revisionNumber = revisions.nextRevisionNumber(head.id),
                    configCanonicalJson = canonical,
                    checksum = CanonicalJson.checksum(canonical),
                    canonVersion = CanonicalJson.CANON_VERSION,
                    status = RevisionStatus.DRAFT,
                    createdBy = actorId,
                    notes = "operational mode: ${requested.wire}",
                ),
            )
        val validation = validateAndStore(revision.id, model)
        if (!validation.isValid) {
            throw ValidationFailedException(
                validation.errors.map { ApiFieldError(code = it.code, path = it.path, message = it.message) },
            )
        }
        audit.record(
            AuditAction.REVISION_CREATED,
            AuditService.ENTITY_REVISION,
            revision.id.toString(),
            mapOf(
                "api" to head.api,
                "revisionNumber" to revision.revisionNumber,
                "valid" to true,
                "checksum" to revision.checksum,
            ),
            actorId,
        )
        return revision
    }

    private fun validateAndStore(revisionId: UUID, model: SourceConfig): ValidationResult {
        val result =
            validator.validate(
                SourceConfigDocument(
                    schemaVersion = DocumentAssemblyService.SCHEMA_VERSION,
                    sources = listOf(model),
                ),
            )
        validationResults.save(
            NewValidationResult(
                revisionId = revisionId,
                valid = result.isValid,
                errors = result.errors,
                warnings = result.warnings,
                rulesVersion = SourceConfigValidationConfig.RULES_VERSION,
            ),
        )
        return result
    }

    private fun auditChange(
        api: String,
        previous: CurrentOperationalSource,
        requested: SourceOperationalMode,
        revision: SourceRevision,
        snapshot: PublishedDocument,
        actorId: UUID,
    ) {
        if (revision.id != previous.revision.id) {
            audit.recordAt(
                AuditAction.REVISION_PUBLISHED,
                AuditService.ENTITY_REVISION,
                revision.id.toString(),
                snapshot.createdAt,
                mapOf(
                    "api" to api,
                    "revisionNumber" to revision.revisionNumber,
                    "checksum" to revision.checksum,
                    "documentRevision" to snapshot.documentRevision,
                ),
                actorId,
            )
        }
        audit.recordAt(
            AuditAction.SOURCE_OPERATIONAL_MODE_CHANGED,
            AuditService.ENTITY_SOURCE,
            api,
            snapshot.createdAt,
            mapOf(
                "from" to previous.mode.wire,
                "to" to requested.wire,
                "sourceRevisionNumber" to revision.revisionNumber,
                "documentRevision" to snapshot.documentRevision,
            ),
            actorId,
        )
    }

    private fun auditDocumentPublished(snapshot: PublishedDocument, actorId: UUID) {
        audit.recordAt(
            AuditAction.DOCUMENT_PUBLISHED,
            AuditService.ENTITY_DOCUMENT,
            snapshot.documentRevision.toString(),
            snapshot.createdAt,
            mapOf(
                "documentRevision" to snapshot.documentRevision,
                "checksum" to snapshot.checksum,
                "sourceCount" to snapshot.sourceCount,
                "generatedAt" to DateTimeFormatter.ISO_INSTANT.format(snapshot.createdAt),
            ),
            actorId,
        )
    }

    private fun decode(revision: SourceRevision): SourceConfig = SourceConfigParser.parseCompatibleSource(revision.configCanonicalJson)

    private companion object {
        const val ADULT_SITE_STATE = "ADULT_18_PLUS"
        const val GENERIC_ENGINE = "generic"
        private val OPERATIONAL_STATUSES = setOf(SourceLifecycleStatus.ACTIVE, SourceLifecycleStatus.DISABLED)
    }
}

data class SourceOperationalModeOutcome(
    val api: String,
    val mode: SourceOperationalMode,
    val sourceRevisionNumber: Int,
    val documentRevision: Long,
    val checksum: String,
    val noOp: Boolean,
)

private data class CurrentOperationalSource(val revision: SourceRevision, val model: SourceConfig, val mode: SourceOperationalMode)
