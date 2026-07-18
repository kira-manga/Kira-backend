package me.manga.kira.backend.sourceconfig.application

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.common.ApiFieldError
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.common.exception.ValidationFailedException
import me.manga.kira.backend.sourceconfig.domain.LifecycleStateMachine
import me.manga.kira.backend.sourceconfig.domain.NewRevision
import me.manga.kira.backend.sourceconfig.domain.NewSourceConfig
import me.manga.kira.backend.sourceconfig.domain.NewValidationResult
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.RevisionRepository
import me.manga.kira.backend.sourceconfig.domain.RevisionStatus
import me.manga.kira.backend.sourceconfig.domain.SourceConfigHead
import me.manga.kira.backend.sourceconfig.domain.SourceConfigRepository
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus
import me.manga.kira.backend.sourceconfig.domain.ValidationResultRepository
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import me.manga.kira.backend.sourceconfig.validation.SourceConfigValidator
import me.manga.kira.backend.sourceconfig.validation.ValidationWarning
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * The bundled-import on-ramp (PLAN §4.3 `import-bundled` row / §12.2). Seeds (and later re-syncs) the
 * backend from the app's bundled document JSON (`CONFIG_BACKED_SOURCES_JSON`) — the migration on-ramp
 * that makes the backend testable against the real production data (PLAN §12).
 *
 * The whole import is a SINGLE all-or-nothing transaction (PLAN §12.2 point 6). Exact normative flow:
 *
 * 1. Parse the full document with the **COMPATIBILITY parser** (PLAN §7) — leniently, exactly as the
 *    app reads it (malformed JSON → the parser's 400).
 * 2. Validate the WHOLE document (§8 validator + the per-stanza Tier-1 structural checks that guard the
 *    DB column limits — [StructuralAuthoringGate.importStructuralErrors]); ANY error → **422**, nothing
 *    persisted. (Import subsumes both tiers — §8 "Import" note.)
 * 3. **Ignore the incoming `revision`/`generatedAt`** — the server exclusively allocates document
 *    revisions; the payload's values are recorded in the audit detail for provenance only.
 * 4. **Read each stanza's `lifecycle` separately, then normalize the content to lifecycle-NEUTRAL**
 *    (`lifecycle = "active"`, rendered as an absent key by kcj-1) before any canonical comparison,
 *    checksum, or storage — the incoming lifecycle NEVER enters stored content (PLAN §9).
 * 5. Per source, by `api` (see [importStanza]): absent → create (position = payload order); present →
 *    draft-only → `skippedDraft`; otherwise compare lifecycle-neutral canonical content vs the
 *    currently published revision (identical → `unchanged`; different → publish exactly ONE new
 *    revision), with the retired/removed exceptions and the "payload lifecycle never overrides server
 *    lifecycle" conflict reporting.
 * 6. All per-source changes apply WITHOUT intermediate whole-document snapshots; after the batch,
 *    materialize **exactly ONE** snapshot via the §9 sequence ([DocumentAssemblyService.materialize]).
 *    Nothing changed at all → no-op: no new snapshot, `documentRevision = null`.
 *
 * Lock order (PLAN §9 / orchestrator pin): the **global publication lock is taken FIRST**, then each
 * affected source-row lock — the same order publishes use, so imports serialize with publishes and no
 * deadlock is possible. One `BUNDLED_IMPORTED` audit row records the counts + payload provenance, never
 * config bodies or header values (§6 log-hygiene).
 */
@Service
class BundledImportService(
    private val sources: SourceConfigRepository,
    private val revisions: RevisionRepository,
    private val validationResults: ValidationResultRepository,
    private val publishedDocuments: PublishedDocumentRepository,
    private val assembly: DocumentAssemblyService,
    private val validator: SourceConfigValidator,
    private val audit: AuditService,
    private val clock: Clock,
) {

    /** `POST /admin/sources/import-bundled` (PLAN §4.3 / §12.2). See the class KDoc for the flow. */
    @Transactional
    fun import(rawJson: String, actorId: UUID): BundledImportResult {
        // 1) COMPATIBILITY parse — malformed JSON surfaces as the parser's 400 (PLAN §7).
        val document = SourceConfigParser.parseCompatibleDocument(rawJson)

        // 2) Whole-document validation (§8) + per-stanza Tier-1 import structural checks. Any error → 422.
        val validation = validator.validate(document)
        val structuralErrors =
            document.sources.flatMapIndexed { index, stanza ->
                StructuralAuthoringGate.importStructuralErrors(stanza, path = "sources[$index]")
            }
        val errors =
            validation.errors.map { ApiFieldError(code = it.code, path = it.path, message = it.message) } +
                structuralErrors
        if (errors.isNotEmpty()) throw ValidationFailedException(errors)

        // 3) Warnings surfaced to the caller: validator warnings + a non-schema/duplicate-key diff (§7).
        val warnings = collectWarnings(rawJson, validation.warnings)

        // §9 step 1 — global publication lock FIRST (serialize with publishes; no deadlock, PLAN §9).
        publishedDocuments.lockPublicationState()

        val now = clock.instant()
        val acc = Accumulator()
        // 4/5) Per-source, in payload order (created positions follow it — PLAN §5 source ordering).
        document.sources.forEachIndexed { index, stanza ->
            importStanza(stanza, index, actorId, now, acc)
        }

        // 6) Exactly ONE snapshot when anything changed; otherwise a no-op with no new revision (PLAN §12.2).
        val changed = acc.created.isNotEmpty() || acc.updated.isNotEmpty()
        val documentRevision =
            if (changed) {
                val snapshot = assembly.materialize(actorId)
                recordImportAudit(document, acc, snapshot.documentRevision, snapshot.createdAt, actorId)
                snapshot.documentRevision
            } else {
                recordImportAudit(document, acc, documentRevision = null, at = now, actorId = actorId)
                null
            }

        return acc.toResult(warnings, documentRevision)
    }

    /** Apply one payload stanza (PLAN §12.2 point 4/5); mutates [acc]. */
    private fun importStanza(stanza: SourceConfig, payloadIndex: Int, actorId: UUID, now: Instant, acc: Accumulator) {
        val payloadLifecycle = stanza.lifecycle
        // Normalize content to lifecycle-NEUTRAL BEFORE any comparison/checksum/storage (PLAN §9/§12.2).
        val neutral = stanza.copy(lifecycle = NEUTRAL_LIFECYCLE)
        val canonical = SourceConfigParser.canonicalSource(neutral)
        val checksum = CanonicalJson.checksum(canonical)

        val head = sources.lockByApiForUpdate(stanza.api) // §9 step 2 — null when the source is absent.
        if (head == null) {
            // ABSENT → create (unless the payload marks it removed — no terminal husk, PLAN §12.2).
            when (payloadLifecycle) {
                APP_REMOVED -> acc.skippedRemoved += stanza.api

                else -> {
                    val initialStatus =
                        if (payloadLifecycle == APP_DISABLED) SourceLifecycleStatus.DISABLED else SourceLifecycleStatus.ACTIVE
                    createAndPublish(stanza, neutral, canonical, checksum, payloadIndex, initialStatus, actorId, now)
                    acc.created += stanza.api
                }
            }
            return
        }

        // PRESENT. Payload lifecycle NEVER overrides server lifecycle; a difference is reported only
        // (informational) — compared in the app's 3-value vocabulary the payload speaks (PLAN §12.2).
        if (payloadLifecycle != head.status.toAppLifecycle()) {
            acc.lifecycleConflicts += LifecycleConflict(stanza.api, payloadLifecycle, head.status.wire)
        }

        val publishedChecksum = head.currentPublishedRevisionId?.let { revisions.findById(it)?.checksum }
        val identical = publishedChecksum == checksum
        when (head.status) {
            // Terminal: never revived by import (PLAN §12.2). Nothing stored.
            SourceLifecycleStatus.REMOVED -> acc.skippedRemoved += stanza.api

            // Retired: content is never imported (publish-on-retired is 409); a difference is skipped.
            SourceLifecycleStatus.RETIRED ->
                if (identical) acc.unchanged += stanza.api else acc.skippedRetired += stanza.api

            // Import is a migration/re-sync path, never an implicit approval path for admin WIP.
            SourceLifecycleStatus.DRAFT -> acc.skippedDraft += stanza.api

            // Active/disabled content can be updated; publishing on disabled preserves that lifecycle.
            SourceLifecycleStatus.ACTIVE, SourceLifecycleStatus.DISABLED ->
                if (identical) {
                    acc.unchanged += stanza.api
                } else {
                    publishNewRevision(head, neutral, canonical, checksum, actorId, now)
                    acc.updated += stanza.api
                }
        }
    }

    /**
     * Create an absent source and publish revision 1 (so it appears in the served document). The head is
     * created `draft` (a null published-revision pointer is always valid) and flipped to [initialStatus]
     * + pointer in the SAME [SourceConfigRepository.applyPublishedRevision] statement, so no transient
     * "non-draft with no published revision" row is ever visible.
     */
    @Suppress("LongParameterList")
    private fun createAndPublish(
        stanza: SourceConfig,
        neutral: SourceConfig,
        canonical: String,
        checksum: String,
        position: Int,
        initialStatus: SourceLifecycleStatus,
        actorId: UUID,
        now: Instant,
    ) {
        val head =
            sources.create(
                NewSourceConfig(
                    api = stanza.api,
                    displayName = stanza.displayName,
                    language = stanza.language,
                    engine = stanza.engine,
                    status = SourceLifecycleStatus.DRAFT,
                    position = position,
                    baseUrl = stanza.baseUrl,
                    adult = stanza.isAdult(),
                ),
            )
        val revision = insertNeutralRevision(head.id, number = 1, canonical = canonical, checksum = checksum, actorId = actorId)
        storeValidation(revision.id, neutral)
        revisions.markPublished(revision.id, now)
        sources.applyPublishedRevision(
            id = head.id,
            currentPublishedRevisionId = revision.id,
            status = initialStatus,
            publishedAt = now,
            displayName = stanza.displayName,
            language = stanza.language,
            engine = stanza.engine,
            baseUrl = stanza.baseUrl,
            adult = stanza.isAdult(),
            updatedAt = now,
        )
    }

    /**
     * Publish exactly ONE new revision for an existing active/disabled source whose content
     * changed (PLAN §12.2) — supersede-then-publish ordering keeps `uq_one_published_per_source` valid
     * at every statement (PLAN §9). Publishing never re-enables ([LifecycleStateMachine.statusAfterPublish]:
     * active→active, disabled→disabled, first-publish draft→active). NO per-source snapshot here — the
     * single snapshot is materialized once after the whole batch.
     */
    private fun publishNewRevision(head: SourceConfigHead, neutral: SourceConfig, canonical: String, checksum: String, actorId: UUID, now: Instant) {
        val number = revisions.nextRevisionNumber(head.id)
        val revision = insertNeutralRevision(head.id, number, canonical, checksum, actorId)
        storeValidation(revision.id, neutral)
        val newStatus = LifecycleStateMachine.statusAfterPublish(head.status)
        head.currentPublishedRevisionId?.let { revisions.markSuperseded(it) }
        revisions.markPublished(revision.id, now)
        sources.applyPublishedRevision(
            id = head.id,
            currentPublishedRevisionId = revision.id,
            status = newStatus,
            publishedAt = now,
            displayName = neutral.displayName,
            language = neutral.language,
            engine = neutral.engine,
            baseUrl = neutral.baseUrl,
            adult = neutral.isAdult(),
            updatedAt = now,
        )
    }

    private fun insertNeutralRevision(sourceConfigId: UUID, number: Int, canonical: String, checksum: String, actorId: UUID) = revisions.create(
        NewRevision(
            sourceConfigId = sourceConfigId,
            revisionNumber = number,
            configCanonicalJson = canonical,
            checksum = checksum,
            canonVersion = CanonicalJson.CANON_VERSION,
            status = RevisionStatus.DRAFT,
            createdBy = actorId,
            notes = IMPORT_NOTE,
        ),
    )

    /**
     * Validate the imported stanza in a single-source candidate document and store the result, so every
     * revision keeps a stored validation result (the invariant the admin read paths rely on). The whole
     * document already validated clean above, so a single stanza validates clean too.
     */
    private fun storeValidation(revisionId: UUID, neutral: SourceConfig) {
        val result =
            validator.validate(
                SourceConfigDocument(schemaVersion = DocumentAssemblyService.SCHEMA_VERSION, sources = listOf(neutral)),
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
    }

    /**
     * Validator warnings plus a single advisory when the document carries non-schema or duplicate keys
     * (detected by diffing the lenient parse against a STRICT re-parse attempt, PLAN §7) — so suspicious
     * structures are visible, not silent. The strict-parse message names the offending key, never a value.
     */
    private fun collectWarnings(rawJson: String, validatorWarnings: List<ValidationWarning>): List<ValidationWarning> {
        val warnings = validatorWarnings.toMutableList()
        try {
            SourceConfigParser.parseStrictDocument(rawJson)
        } catch (ex: BadRequestException) {
            warnings +=
                ValidationWarning(
                    code = NON_SCHEMA_KEYS_WARNING,
                    path = "document",
                    message =
                    "the document contains fields not in the current schema or duplicate keys and was " +
                        "imported leniently (${ex.detail})",
                )
        }
        return warnings
    }

    private fun recordImportAudit(document: SourceConfigDocument, acc: Accumulator, documentRevision: Long?, at: Instant, actorId: UUID) {
        // Detail carries COUNTS + payload provenance only — never config bodies, apis, or header values (§6).
        audit.recordAt(
            AuditAction.BUNDLED_IMPORTED,
            AuditService.ENTITY_DOCUMENT,
            documentRevision?.toString() ?: "none",
            at,
            mapOf(
                "created" to acc.created.size,
                "updated" to acc.updated.size,
                "unchanged" to acc.unchanged.size,
                "skippedRemoved" to acc.skippedRemoved.size,
                "skippedRetired" to acc.skippedRetired.size,
                "skippedDraft" to acc.skippedDraft.size,
                "lifecycleConflicts" to acc.lifecycleConflicts.size,
                "sourceCount" to document.sources.size,
                "payloadRevision" to document.revision,
                "payloadGeneratedAt" to document.generatedAt,
                "documentRevision" to documentRevision,
            ),
            actorId,
        )
    }

    private fun SourceConfig.isAdult(): Boolean = siteState == ADULT_SITE_STATE

    /** The app's 3-value lifecycle for a server status (PLAN §9 mapping) — the vocabulary the payload speaks. */
    private fun SourceLifecycleStatus.toAppLifecycle(): String = when (this) {
        SourceLifecycleStatus.ACTIVE, SourceLifecycleStatus.DRAFT -> APP_ACTIVE
        SourceLifecycleStatus.DISABLED -> APP_DISABLED
        SourceLifecycleStatus.RETIRED, SourceLifecycleStatus.REMOVED -> APP_REMOVED
    }

    /** Mutable per-import accumulator; api strings collected in payload order. */
    private class Accumulator {
        val created = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        val skippedRemoved = mutableListOf<String>()
        val skippedRetired = mutableListOf<String>()
        val skippedDraft = mutableListOf<String>()
        val lifecycleConflicts = mutableListOf<LifecycleConflict>()

        fun toResult(warnings: List<ValidationWarning>, documentRevision: Long?) = BundledImportResult(
            created = created.toList(),
            updated = updated.toList(),
            unchanged = unchanged.toList(),
            skippedRemoved = skippedRemoved.toList(),
            skippedRetired = skippedRetired.toList(),
            skippedDraft = skippedDraft.toList(),
            lifecycleConflicts = lifecycleConflicts.toList(),
            warnings = warnings,
            documentRevision = documentRevision,
        )
    }

    private companion object {
        const val NEUTRAL_LIFECYCLE = "active"
        const val APP_ACTIVE = "active"
        const val APP_DISABLED = "disabled"
        const val APP_REMOVED = "removed"
        const val ADULT_SITE_STATE = "ADULT_18_PLUS"
        const val IMPORT_NOTE = "bundled import"
        const val NON_SCHEMA_KEYS_WARNING = "NON_SCHEMA_KEYS"
    }
}

/**
 * A payload-lifecycle-vs-server-lifecycle mismatch on an existing source (PLAN §12.2). Informational:
 * the payload lifecycle NEVER changes the server lifecycle; content still imports (subject to the
 * retired/removed exception). [payloadLifecycle] is the incoming app value; [serverLifecycle] is the
 * source's current server status wire value.
 */
data class LifecycleConflict(val api: String, val payloadLifecycle: String, val serverLifecycle: String)

/**
 * The structured result of a bundled import (PLAN §4.3 / §12.2). Each list holds api strings (in payload
 * order); [documentRevision] is null on the no-op case (nothing changed → no new snapshot).
 */
data class BundledImportResult(
    val created: List<String>,
    val updated: List<String>,
    val unchanged: List<String>,
    val skippedRemoved: List<String>,
    val skippedRetired: List<String>,
    val skippedDraft: List<String>,
    val lifecycleConflicts: List<LifecycleConflict>,
    val warnings: List<ValidationWarning>,
    val documentRevision: Long?,
)
