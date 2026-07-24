package me.manga.kira.backend.sourceconfig.application

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.common.ApiFieldError
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.exception.InvalidLifecycleTransitionException
import me.manga.kira.backend.common.exception.ValidationFailedException
import me.manga.kira.backend.observability.KiraMetrics
import me.manga.kira.backend.sourceconfig.domain.IllegalLifecycleTransitionException
import me.manga.kira.backend.sourceconfig.domain.LifecycleAction
import me.manga.kira.backend.sourceconfig.domain.LifecycleStateMachine
import me.manga.kira.backend.sourceconfig.domain.NewRevision
import me.manga.kira.backend.sourceconfig.domain.NewSourceConfig
import me.manga.kira.backend.sourceconfig.domain.NewValidationResult
import me.manga.kira.backend.sourceconfig.domain.PublishedDocument
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.RevisionRepository
import me.manga.kira.backend.sourceconfig.domain.RevisionStatus
import me.manga.kira.backend.sourceconfig.domain.SourceConfigHead
import me.manga.kira.backend.sourceconfig.domain.SourceConfigRepository
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus
import me.manga.kira.backend.sourceconfig.domain.SourceRevision
import me.manga.kira.backend.sourceconfig.domain.UnretireNotAllowedForEngineException
import me.manga.kira.backend.sourceconfig.domain.ValidationResultRepository
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import me.manga.kira.backend.sourceconfig.validation.SourceConfigValidator
import me.manga.kira.backend.sourceconfig.validation.ValidationResult
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Admin source-management orchestration (PLAN §4.3/§8/§9). Owns: authoring (STRICT parse + the Tier-1
 * structural gate + stored Tier-2 validation), the publish transaction (publishable-revision-states
 * rules + supersede-then-publish ordering), the lifecycle transitions, and rollback — each state-visible
 * mutation running the §9 sequence: lock the global publication row FIRST, then the source row, apply
 * the per-source mutation, then hand off to [DocumentAssemblyService.materialize] (steps 4–9). Every
 * mutation writes `audit_log` via [AuditService].
 *
 * Actors are passed in ([actorId]) rather than read from the SecurityContext so the concurrency ITs can
 * drive the service directly off request threads (PLAN §11 tests 21/22/48); the controller supplies the
 * authenticated admin's id.
 */
@Service
class SourceAdminService(
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

    // --- Authoring (create + revisions) -------------------------------------------------------------

    /**
     * `POST /admin/sources` — STRICT parse → Tier-1 gate → create draft source + revision 1 → store
     * Tier-2 validation (invalid drafts ARE stored) → 201 with the inline result (PLAN §4.3/§8).
     */
    @Transactional
    fun createSource(rawJson: String, actorId: UUID): SourceMutationResult {
        val model = SourceConfigParser.parseStrictSource(rawJson)
        StructuralAuthoringGate.check(model, pathApi = null)
        if (sources.existsByApi(model.api)) throw SourceAlreadyExistsException(model.api)

        val head =
            try {
                sources.create(
                    NewSourceConfig(
                        api = model.api,
                        displayName = model.displayName,
                        language = model.language,
                        engine = model.engine,
                        status = SourceLifecycleStatus.DRAFT,
                        position = sources.nextPosition(),
                        baseUrl = model.baseUrl,
                        adult = model.isAdult(),
                    ),
                )
            } catch (_: DataIntegrityViolationException) {
                throw SourceAlreadyExistsException(model.api)
            }
        val revision = insertDraftRevision(head.id, 1, model, actorId, notes = null)
        val validation = validateAndStore(revision.id, model)

        audit.record(
            AuditAction.SOURCE_CREATED,
            AuditService.ENTITY_SOURCE,
            model.api,
            mapOf("engine" to model.engine, "position" to head.position),
            actorId,
        )
        auditRevisionCreated(model.api, revision, validation, actorId)
        return SourceMutationResult(model.api, SourceLifecycleStatus.DRAFT, revision.revisionNumber, validation)
    }

    /**
     * `POST /admin/sources/{api}/revisions` — new draft revision. STRICT parse → Tier-1 gate (incl.
     * `API_ID_MISMATCH`) → allocate the number under the source-row lock → store Tier-2 validation
     * (PLAN §4.3/§5). No global publication lock: draft creation is not state-visible (PLAN §17).
     */
    @Transactional
    fun createRevision(api: String, rawJson: String, actorId: UUID): SourceMutationResult {
        val model = SourceConfigParser.parseStrictSource(rawJson)
        StructuralAuthoringGate.check(model, pathApi = api)
        // Lock the head FOR UPDATE so concurrent revision creations for this source serialize (PLAN §5).
        val head = sources.lockByApiForUpdate(api) ?: throw SourceNotFoundException(api)
        val number = revisions.nextRevisionNumber(head.id)
        val revision = insertDraftRevision(head.id, number, model, actorId, notes = null)
        val validation = validateAndStore(revision.id, model)

        auditRevisionCreated(api, revision, validation, actorId)
        return SourceMutationResult(api, head.status, number, validation)
    }

    // --- Reads --------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    fun getSource(api: String): SourceAdminView {
        val head = sources.findByApi(api) ?: throw SourceNotFoundException(api)
        val currentPublishedNumber =
            head.currentPublishedRevisionId?.let { revisions.findById(it)?.revisionNumber }
        return SourceAdminView(head, currentPublishedNumber, revisions.latestRevisionNumber(head.id))
    }

    @Transactional(readOnly = true)
    fun listSources(status: SourceLifecycleStatus?): List<SourceAdminView> = sources.findAllWithRevisionNumbers(status).map { listing ->
        SourceAdminView(
            listing.head,
            listing.currentPublishedRevisionNumber,
            listing.latestRevisionNumber,
        )
    }

    @Transactional(readOnly = true)
    fun listRevisions(api: String): List<RevisionView> {
        val head = sources.findByApi(api) ?: throw SourceNotFoundException(api)
        return revisions.findAllForSource(head.id).map { RevisionView(it, latestValid(it.id)) }
    }

    @Transactional(readOnly = true)
    fun getRevision(api: String, number: Int): RevisionView {
        val revision = findRevisionOrThrow(api, number)
        return RevisionView(revision, latestValid(revision.id))
    }

    @Transactional(readOnly = true)
    fun getLatestValidation(api: String, number: Int): ValidationResult {
        val revision = findRevisionOrThrow(api, number)
        val stored = validationResults.findLatestForRevision(revision.id)
        return if (stored != null) {
            ValidationResult(stored.valid, stored.errors, stored.warnings)
        } else {
            // Every revision is validated + stored at creation, so this is purely defensive. Compute
            // (do NOT store) so this stays a genuine read (the tx is readOnly).
            validateStanza(decodeStored(revision))
        }
    }

    /**
     * `POST /admin/sources/{api}/revisions/{n}/validate` — re-run validation (preview; the only state
     * change is storing the fresh result) and return it, even when invalid (PLAN §4.3).
     */
    @Transactional
    fun validateRevision(api: String, number: Int): ValidationResult {
        val revision = findRevisionOrThrow(api, number)
        return validateAndStore(revision.id, decodeStored(revision))
    }

    // --- Publish ------------------------------------------------------------------------------------

    /**
     * `POST /admin/sources/{api}/revisions/{n}/publish` (PLAN §4.3/§9). Applies the publishable-revision
     * states rules (200 no-op / `REVISION_SUPERSEDED` / `REVISION_OLDER_THAN_PUBLISHED`), re-validates
     * live (422 if invalid), and publishes with supersede-then-publish ordering + a new snapshot.
     */
    @Transactional
    fun publish(api: String, number: Int, actorId: UUID): PublishOutcome {
        publishedDocuments.lockPublicationState() // §9 step 1 — global lock FIRST
        val head = sources.lockByApiForUpdate(api) ?: throw SourceNotFoundException(api) // step 2
        val revision = revisions.findBySourceAndNumber(head.id, number) ?: throw RevisionNotFoundException(api, number)

        // Status × publish FIRST (PLAN §9): retired/removed → 409 takes precedence over the no-op/superseded
        // rules (otherwise publishing a removed source's still-"published" revision would 200-no-op).
        mappingLifecycleErrors { LifecycleStateMachine.statusAfterPublish(head.status) }

        when (revision.status) {
            RevisionStatus.PUBLISHED -> return currentPublishedNoOp()

            // idempotent 200, no new snapshot
            RevisionStatus.SUPERSEDED -> throw RevisionSupersededException(api, number)

            RevisionStatus.DRAFT -> {
                val publishedNumber = head.currentPublishedRevisionId?.let { revisions.findById(it)?.revisionNumber }
                if (publishedNumber != null && number <= publishedNumber) {
                    throw RevisionOlderThanPublishedException(api, number, publishedNumber)
                }
            }
        }

        // Re-validate live (a stale stored "valid" is not trusted, PLAN §4.3).
        val model = decodeStored(revision)
        val validation = validateAndStore(revision.id, model)
        if (!validation.isValid) throw ValidationFailedException(validation.errors.map { it.toFieldError() })

        val snapshot = mappingLifecycleErrors { doPublishDraft(head, revision, model, actorId) }
        metrics.publication("published")
        return PublishOutcome(snapshot.documentRevision, snapshot.checksum, noOp = false)
    }

    /**
     * Atomic editor fast path: strict-parse the current autosaved content, take the global lock before
     * the source lock, create one immutable revision, validate it, and publish it in the same
     * transaction. Any failure rolls back the revision, source head, snapshots, catalog, and audits.
     */
    @Transactional
    fun publishEditorContent(api: String, rawJson: String, actorId: UUID): EditorContentPublishOutcome {
        val model = SourceConfigParser.parseStrictSource(rawJson)
        StructuralAuthoringGate.check(model, pathApi = api)
        publishedDocuments.lockPublicationState()
        val head = sources.lockByApiForUpdate(api) ?: throw SourceNotFoundException(api)
        mappingLifecycleErrors { LifecycleStateMachine.statusAfterPublish(head.status) }
        val number = revisions.nextRevisionNumber(head.id)
        val revision = insertDraftRevision(head.id, number, model, actorId, notes = "published from source editor draft")
        val validation = validateAndStore(revision.id, model)
        if (!validation.isValid) throw ValidationFailedException(validation.errors.map { it.toFieldError() })
        auditRevisionCreated(api, revision, validation, actorId)
        val snapshot = mappingLifecycleErrors { doPublishDraft(head, revision, model, actorId) }
        metrics.publication("editor")
        return EditorContentPublishOutcome(
            revision =
            SourceMutationResult(
                api = api,
                status = LifecycleStateMachine.statusAfterPublish(head.status),
                revisionNumber = number,
                validation = validation,
            ),
            publication = PublishOutcome(snapshot.documentRevision, snapshot.checksum, noOp = false),
        )
    }

    @Transactional(readOnly = true)
    fun validateChanges(operations: List<CatalogChange>): ChangesetValidation {
        val prepared = prepareChanges(operations, lock = false)
        return ChangesetValidation(
            valid = true,
            operationCount = prepared.mutations.size + if (prepared.order != null) 1 else 0,
            affectedApis = prepared.affectedApis,
        )
    }

    /**
     * Apply a complete changeset in one transaction and materialize exactly one catalog snapshot.
     * Every operation is checked before the first write, so a failed changeset makes no partial change.
     */
    @Transactional
    fun applyChanges(operations: List<CatalogChange>, actorId: UUID, changesetId: UUID): ChangesetApplyOutcome {
        publishedDocuments.lockPublicationState()
        val prepared = prepareChanges(operations, lock = true)
        val now = clock.instant()
        prepared.mutations.forEach { mutation ->
            when (mutation) {
                is PreparedPublish -> {
                    mutation.head.currentPublishedRevisionId?.let { revisions.markSuperseded(it) }
                    revisions.markPublished(mutation.revision.id, now)
                    sources.applyPublishedRevision(
                        id = mutation.head.id,
                        currentPublishedRevisionId = mutation.revision.id,
                        status = mutation.resultingStatus,
                        publishedAt = now,
                        displayName = mutation.model.displayName,
                        language = mutation.model.language,
                        engine = mutation.model.engine,
                        baseUrl = mutation.model.baseUrl,
                        adult = mutation.model.isAdult(),
                        updatedAt = now,
                    )
                }

                is PreparedLifecycle -> sources.updateStatus(mutation.head.id, mutation.resultingStatus, now)
            }
        }
        prepared.order?.forEachIndexed { position, head ->
            if (head.position != position) sources.updatePosition(head.id, position, now)
        }
        val snapshot = assembly.materialize(actorId)
        audit.recordAt(
            AuditAction.SOURCE_CHANGESET_APPLIED,
            AuditService.ENTITY_SOURCE_CHANGESET,
            changesetId.toString(),
            snapshot.createdAt,
            mapOf(
                "operationCount" to operations.size,
                "affectedSourceCount" to prepared.affectedApis.size,
                "documentRevision" to snapshot.documentRevision,
                "checksum" to snapshot.checksum,
            ),
            actorId,
        )
        auditDocumentPublished(snapshot, actorId)
        metrics.publication("changeset")
        return ChangesetApplyOutcome(snapshot.documentRevision, snapshot.checksum, prepared.affectedApis)
    }

    // --- Lifecycle transitions ----------------------------------------------------------------------

    @Transactional
    fun disable(api: String, actorId: UUID): PublishOutcome = transition(api, LifecycleAction.DISABLE, AuditAction.SOURCE_DISABLED, actorId)

    @Transactional
    fun enable(api: String, actorId: UUID): PublishOutcome = transition(api, LifecycleAction.ENABLE, AuditAction.SOURCE_ENABLED, actorId)

    @Transactional
    fun retire(api: String, actorId: UUID): PublishOutcome = transition(api, LifecycleAction.RETIRE, AuditAction.SOURCE_RETIRED, actorId)

    @Transactional
    fun remove(api: String, confirm: String?, actorId: UUID): PublishOutcome {
        if (confirm != api) {
            throw me.manga.kira.backend.common.exception.BadRequestException(
                "remove requires a matching confirmation of the source api.",
                code = CONFIRMATION_REQUIRED_CODE,
            )
        }
        return transition(api, LifecycleAction.REMOVE, AuditAction.SOURCE_REMOVED, actorId)
    }

    // --- Rollback -----------------------------------------------------------------------------------

    /**
     * `POST /admin/sources/{api}/rollback` (PLAN §4.3/§9 forward-roll): copy revision {toRevision}'s
     * content into a NEW highest revision, re-validate (422 if rules tightened), and publish it. History
     * is never mutated; the server lifecycle is NOT restored (it follows the publish rules).
     */
    @Transactional
    fun rollback(api: String, toRevision: Int, actorId: UUID): RollbackOutcome {
        publishedDocuments.lockPublicationState()
        val head = sources.lockByApiForUpdate(api) ?: throw SourceNotFoundException(api)
        if (head.status == SourceLifecycleStatus.RETIRED || head.status == SourceLifecycleStatus.REMOVED) {
            throw InvalidLifecycleTransitionException("cannot rollback a ${head.status.wire} source (PLAN §9).")
        }
        if (head.currentPublishedRevisionId == null) {
            throw RollbackRequiresPublishedBaselineException()
        }
        val source = revisions.findBySourceAndNumber(head.id, toRevision) ?: throw RevisionNotFoundException(api, toRevision)

        val newNumber = revisions.nextRevisionNumber(head.id)
        val copied =
            revisions.create(
                NewRevision(
                    sourceConfigId = head.id,
                    revisionNumber = newNumber,
                    configCanonicalJson = source.configCanonicalJson,
                    checksum = source.checksum,
                    canonVersion = source.canonVersion,
                    status = RevisionStatus.DRAFT,
                    createdBy = actorId,
                    notes = "rollback to r$toRevision (new r$newNumber)",
                ),
            )
        val model = decodeStored(copied)
        val validation = validateAndStore(copied.id, model)
        if (!validation.isValid) throw ValidationFailedException(validation.errors.map { it.toFieldError() })

        val snapshot = mappingLifecycleErrors { doPublishDraft(head, copied, model, actorId) }
        audit.recordAt(
            AuditAction.SOURCE_ROLLBACK,
            AuditService.ENTITY_SOURCE,
            api,
            snapshot.createdAt,
            mapOf("fromRevision" to toRevision, "newRevisionNumber" to newNumber, "documentRevision" to snapshot.documentRevision),
            actorId,
        )
        return RollbackOutcome(newNumber, snapshot.documentRevision, snapshot.checksum)
    }

    // --- Documents ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    fun listDocuments(): List<PublishedDocument> = publishedDocuments.findAllOrderedByRevision()

    @Transactional(readOnly = true)
    fun getDocument(revision: Long): PublishedDocument = publishedDocuments.findByRevision(revision) ?: throw DocumentNotFoundException(revision)

    @Transactional(readOnly = true)
    fun validateCandidateDocument(): ValidationResult = assembly.validateCandidate()

    /**
     * `POST /admin/documents/republish` — force a new snapshot from current state, ALWAYS a new document
     * revision even when content is unchanged (recovery tool; PLAN §4.3).
     */
    @Transactional
    fun republish(actorId: UUID): PublishOutcome {
        publishedDocuments.lockPublicationState()
        val snapshot = assembly.materialize(actorId)
        auditDocumentPublished(snapshot, actorId)
        metrics.publication("rollback")
        return PublishOutcome(snapshot.documentRevision, snapshot.checksum, noOp = false)
    }

    // --- internals ----------------------------------------------------------------------------------

    /** Supersede the current published revision (if any), publish [draft], update the head, materialize. */
    private fun doPublishDraft(head: SourceConfigHead, draft: SourceRevision, model: SourceConfig, actorId: UUID): PublishedDocument {
        if (model.engine != GENERIC_ENGINE) throw NonGenericPublicationForbiddenException()
        // Compute the resulting status FIRST (throws for retired/removed → mapped to 409) before any write.
        val newStatus = LifecycleStateMachine.statusAfterPublish(head.status)
        val now = clock.instant()
        // Supersede-then-publish ordering keeps uq_one_published_per_source valid at every statement (§9).
        head.currentPublishedRevisionId?.let { revisions.markSuperseded(it) }
        revisions.markPublished(draft.id, now)
        sources.applyPublishedRevision(
            id = head.id,
            currentPublishedRevisionId = draft.id,
            status = newStatus,
            publishedAt = now,
            displayName = model.displayName,
            language = model.language,
            engine = model.engine,
            baseUrl = model.baseUrl,
            adult = model.isAdult(),
            updatedAt = now,
        )
        val snapshot = assembly.materialize(actorId)
        audit.recordAt(
            AuditAction.REVISION_PUBLISHED,
            AuditService.ENTITY_REVISION,
            draft.id.toString(),
            snapshot.createdAt,
            mapOf(
                "api" to head.api,
                "revisionNumber" to draft.revisionNumber,
                "checksum" to draft.checksum,
                "documentRevision" to snapshot.documentRevision,
            ),
            actorId,
        )
        auditDocumentPublished(snapshot, actorId)
        return snapshot
    }

    private fun transition(api: String, action: LifecycleAction, auditAction: AuditAction, actorId: UUID): PublishOutcome {
        publishedDocuments.lockPublicationState()
        val head = sources.lockByApiForUpdate(api) ?: throw SourceNotFoundException(api)
        val newStatus = mappingLifecycleErrors { LifecycleStateMachine.transition(head.status, action, head.engine) }
        val now = clock.instant()
        sources.updateStatus(head.id, newStatus, now)
        val snapshot = assembly.materialize(actorId)
        audit.recordAt(
            auditAction,
            AuditService.ENTITY_SOURCE,
            api,
            snapshot.createdAt,
            mapOf("from" to head.status.wire, "to" to newStatus.wire, "documentRevision" to snapshot.documentRevision),
            actorId,
        )
        auditDocumentPublished(snapshot, actorId)
        metrics.publication("lifecycle")
        return PublishOutcome(snapshot.documentRevision, snapshot.checksum, noOp = false)
    }

    /** 200 idempotent no-op publish: return the current served snapshot, NO new snapshot (PLAN §9). */
    private fun currentPublishedNoOp(): PublishOutcome {
        val pointer = checkNotNull(publishedDocuments.latestPointer()) {
            "a published revision exists but the latest-document pointer is NULL (inconsistent state)"
        }
        val current = checkNotNull(publishedDocuments.findByRevision(pointer)) {
            "latest-document pointer $pointer references a missing snapshot (inconsistent state)"
        }
        metrics.publication("noop")
        return PublishOutcome(current.documentRevision, current.checksum, noOp = true)
    }

    private fun insertDraftRevision(sourceConfigId: UUID, number: Int, model: SourceConfig, actorId: UUID, notes: String?): SourceRevision {
        // Content is stored lifecycle-NEUTRAL (PLAN §9): the gate guarantees lifecycle == "active", which
        // kcj-1 default-omission renders as an ABSENT key; normalize defensively.
        val neutral = model.copy(lifecycle = "active")
        val canonical = SourceConfigParser.canonicalSource(neutral)
        return revisions.create(
            NewRevision(
                sourceConfigId = sourceConfigId,
                revisionNumber = number,
                configCanonicalJson = canonical,
                checksum = CanonicalJson.checksum(canonical),
                canonVersion = CanonicalJson.CANON_VERSION,
                status = RevisionStatus.DRAFT,
                createdBy = actorId,
                notes = notes,
            ),
        )
    }

    /** Validate a stanza (errors + warnings) via a single-source candidate document, and store it. */
    private fun validateAndStore(revisionId: UUID, model: SourceConfig): ValidationResult {
        val result = validateStanza(model)
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

    /**
     * Validate ONE stanza (errors + warnings) by wrapping it in a single-source candidate document.
     * Cross-source `api` uniqueness (rule 2) is structurally guaranteed by `uq_source_configs_api`, so a
     * single-source doc is faithful; whole-document uniqueness is exercised by [validateCandidateDocument].
     */
    private fun validateStanza(model: SourceConfig): ValidationResult = validator.validate(
        SourceConfigDocument(schemaVersion = DocumentAssemblyService.SCHEMA_VERSION, sources = listOf(model)),
    )

    private fun decodeStored(revision: SourceRevision): SourceConfig = SourceConfigParser.parseCompatibleSource(revision.configCanonicalJson)

    private fun findRevisionOrThrow(api: String, number: Int): SourceRevision {
        val head = sources.findByApi(api) ?: throw SourceNotFoundException(api)
        return revisions.findBySourceAndNumber(head.id, number) ?: throw RevisionNotFoundException(api, number)
    }

    private fun latestValid(revisionId: UUID): Boolean? = validationResults.findLatestForRevision(revisionId)?.valid

    @Suppress("ThrowsCount")
    private fun prepareChanges(operations: List<CatalogChange>, lock: Boolean): PreparedChanges {
        if (operations.isEmpty()) throw EmptyChangesetException()
        if (operations.size > MAX_CHANGESET_OPERATIONS) throw ChangesetTooLargeException()
        val mutations = operations.filterNot { it.type == CatalogChangeType.REORDER }
        val reorder = operations.filter { it.type == CatalogChangeType.REORDER }
        if (reorder.size > 1) throw InvalidChangesetException("a changeset may contain at most one reorder operation.")
        val duplicateApi = mutations.groupingBy { it.api }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        if (duplicateApi != null) throw InvalidChangesetException("source '$duplicateApi' has more than one operation.")

        val preparedMutations =
            mutations.sortedBy { it.api }.map { operation ->
                val api = operation.api ?: throw InvalidChangesetException("${operation.type.wire} requires an api.")
                val head =
                    (if (lock) sources.lockByApiForUpdate(api) else sources.findByApi(api))
                        ?: throw SourceNotFoundException(api)
                when (operation.type) {
                    CatalogChangeType.PUBLISH -> preparePublish(operation, head)

                    CatalogChangeType.DISABLE -> prepareLifecycle(head, LifecycleAction.DISABLE)

                    CatalogChangeType.ENABLE -> prepareLifecycle(head, LifecycleAction.ENABLE)

                    CatalogChangeType.RETIRE -> prepareLifecycle(head, LifecycleAction.RETIRE)

                    CatalogChangeType.REMOVE -> {
                        if (operation.confirm != api) {
                            throw InvalidChangesetException("remove for '$api' requires an exact confirmation.")
                        }
                        prepareLifecycle(head, LifecycleAction.REMOVE)
                    }

                    CatalogChangeType.REORDER -> error("reorder is handled separately")
                }
            }
        val orderedHeads =
            reorder.singleOrNull()?.let { operation ->
                val requested = operation.orderedApis ?: throw InvalidChangesetException("reorder requires orderedApis.")
                if (requested.size != requested.distinct().size) {
                    throw InvalidChangesetException("reorder contains duplicate source ids.")
                }
                val current = sources.findAll(null)
                if (requested.size != current.size || requested.toSet() != current.map { it.api }.toSet()) {
                    throw InvalidChangesetException("reorder must contain every source exactly once.")
                }
                val byApi = current.associateBy { it.api }
                requested.map { requireNotNull(byApi[it]) }
            }
        return PreparedChanges(
            mutations = preparedMutations,
            order = orderedHeads,
            affectedApis = (preparedMutations.map { it.head.api } + orderedHeads?.map { it.api }.orEmpty()).distinct(),
        )
    }

    private fun preparePublish(operation: CatalogChange, head: SourceConfigHead): PreparedPublish {
        val number = operation.revisionNumber
            ?: throw InvalidChangesetException("publish for '${head.api}' requires revisionNumber.")
        val revision =
            revisions.findBySourceAndNumber(head.id, number)
                ?: throw RevisionNotFoundException(head.api, number)
        if (revision.status != RevisionStatus.DRAFT) {
            throw InvalidChangesetException("publish revision for '${head.api}' must be a draft.")
        }
        val publishedNumber = head.currentPublishedRevisionId?.let { revisions.findById(it)?.revisionNumber }
        if (publishedNumber != null && number <= publishedNumber) {
            throw RevisionOlderThanPublishedException(head.api, number, publishedNumber)
        }
        val resultingStatus = mappingLifecycleErrors { LifecycleStateMachine.statusAfterPublish(head.status) }
        val model = decodeStored(revision)
        if (model.engine != GENERIC_ENGINE) throw NonGenericPublicationForbiddenException()
        val validation = validateStanza(model)
        if (!validation.isValid) throw ValidationFailedException(validation.errors.map { it.toFieldError() })
        return PreparedPublish(head, revision, model, resultingStatus)
    }

    private fun prepareLifecycle(head: SourceConfigHead, action: LifecycleAction): PreparedLifecycle = PreparedLifecycle(
        head,
        mappingLifecycleErrors { LifecycleStateMachine.transition(head.status, action, head.engine) },
    )

    private fun auditRevisionCreated(api: String, revision: SourceRevision, validation: ValidationResult, actorId: UUID) = audit.record(
        AuditAction.REVISION_CREATED,
        AuditService.ENTITY_REVISION,
        revision.id.toString(),
        mapOf("api" to api, "revisionNumber" to revision.revisionNumber, "valid" to validation.isValid, "checksum" to revision.checksum),
        actorId,
    )

    private fun auditDocumentPublished(snapshot: PublishedDocument, actorId: UUID) = audit.recordAt(
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

    /** Translate the pure domain lifecycle exceptions into the boundary 409s (PLAN §9). */
    private inline fun <T> mappingLifecycleErrors(block: () -> T): T = try {
        block()
    } catch (e: IllegalLifecycleTransitionException) {
        throw InvalidLifecycleTransitionException(e.message ?: "invalid lifecycle transition.")
    } catch (e: UnretireNotAllowedForEngineException) {
        throw UnretireUnsupportedForEngineException(e.message ?: "un-retire is unsupported for this engine.")
    }

    private fun SourceConfig.isAdult(): Boolean = siteState == ADULT_SITE_STATE

    private companion object {
        const val ADULT_SITE_STATE = "ADULT_18_PLUS"
        const val GENERIC_ENGINE = "generic"
        const val MAX_CHANGESET_OPERATIONS = 500
    }
}

/** Errors→field-error mapping for the 422 problem envelope (PLAN §4.3). */
private fun me.manga.kira.backend.sourceconfig.validation.ValidationError.toFieldError(): ApiFieldError =
    ApiFieldError(code = code, path = path, message = message)

/** Result of a create/revision authoring call (PLAN §4.3 — 201 even when Tier-2-invalid). */
data class SourceMutationResult(val api: String, val status: SourceLifecycleStatus, val revisionNumber: Int, val validation: ValidationResult)

/** Result of a publish / lifecycle mutation (PLAN §4.3). [noOp] = the idempotent re-publish case. */
data class PublishOutcome(val documentRevision: Long, val checksum: String, val noOp: Boolean)

/** Result of a rollback (PLAN §4.3). */
data class RollbackOutcome(val newRevisionNumber: Int, val documentRevision: Long, val checksum: String)

/** The admin view of a source head plus its published/latest revision numbers (PLAN §4.3). */
data class SourceAdminView(val head: SourceConfigHead, val currentPublishedRevisionNumber: Int?, val latestRevisionNumber: Int?)

/** A revision plus its latest stored validity flag (PLAN §4.3 revision list). */
data class RevisionView(val revision: SourceRevision, val valid: Boolean?)

data class EditorContentPublishOutcome(val revision: SourceMutationResult, val publication: PublishOutcome)

enum class CatalogChangeType(val wire: String) {
    PUBLISH("publish"),
    DISABLE("disable"),
    ENABLE("enable"),
    RETIRE("retire"),
    REMOVE("remove"),
    REORDER("reorder"),
}

data class CatalogChange(
    val type: CatalogChangeType,
    val api: String? = null,
    val revisionNumber: Int? = null,
    val confirm: String? = null,
    val orderedApis: List<String>? = null,
)

data class ChangesetValidation(val valid: Boolean, val operationCount: Int, val affectedApis: List<String>)

data class ChangesetApplyOutcome(val documentRevision: Long, val checksum: String, val affectedApis: List<String>)

private data class PreparedChanges(val mutations: List<PreparedMutation>, val order: List<SourceConfigHead>?, val affectedApis: List<String>)

private sealed interface PreparedMutation {
    val head: SourceConfigHead
}

private data class PreparedPublish(
    override val head: SourceConfigHead,
    val revision: SourceRevision,
    val model: SourceConfig,
    val resultingStatus: SourceLifecycleStatus,
) : PreparedMutation

private data class PreparedLifecycle(override val head: SourceConfigHead, val resultingStatus: SourceLifecycleStatus) : PreparedMutation
