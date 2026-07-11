package me.manga.kira.backend.sourceconfig.application

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.sourceconfig.domain.AssemblySource
import me.manga.kira.backend.sourceconfig.domain.NewPublishedDocument
import me.manga.kira.backend.sourceconfig.domain.PublishedDocument
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.SourceConfigRepository
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import me.manga.kira.backend.sourceconfig.validation.SourceConfigValidator
import me.manga.kira.backend.sourceconfig.validation.ValidationResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Materializes the served whole-document snapshot (PLAN §9 steps 4–9). Called by [SourceAdminService]
 * from inside the single publish/lifecycle transaction, AFTER the caller has taken the global
 * publication lock (step 1), the affected source-row lock (step 2), and applied the per-source mutation
 * (step 3). Split out from [SourceAdminService] per PLAN §3/§15.6 so the assembly (ordering, lifecycle
 * injection, checksum, single-Clock instant) is a cohesive, testable unit reused by import (Phase 8).
 *
 * The ONE Clock instant taken here (truncated to ISO-8601 UTC seconds, PLAN §5) is the document's
 * `generatedAt`, the snapshot row's `created_at`, AND the value the caller stamps into the publication
 * audit detail (PLAN §9 steps 7–8) — application time and DB time can never diverge.
 */
@Service
class DocumentAssemblyService(
    private val sources: SourceConfigRepository,
    private val publishedDocuments: PublishedDocumentRepository,
    private val validator: SourceConfigValidator,
    private val clock: Clock,
) {

    /**
     * Materialize + persist a new snapshot from the current published state (PLAN §9 steps 4–9). MUST
     * run inside the caller's transaction (which holds the global lock) — [Propagation.MANDATORY]
     * asserts it. Returns the stored snapshot ([PublishedDocument.createdAt] is the shared instant).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun materialize(actorId: UUID): PublishedDocument {
        // Step 4 — authoritative current state under the lock (cannot be stale; every writer queues on step 1).
        val assemblySources = sources.findSourcesForAssembly()

        // Steps 7 (part) — ONE instant for generatedAt / created_at / audit detail.
        val instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val generatedAt = DateTimeFormatter.ISO_INSTANT.format(instant)

        // Step 8 (part) — consume the monotonic revision (before building, since it is part of the bytes).
        val revision = publishedDocuments.nextDocumentRevision()

        // Step 5 — assemble the candidate (ordered, lifecycle injected).
        val document = buildDocument(assemblySources, generatedAt, revision)

        // Step 6 — validate the whole candidate (PLAN §8 rule 29 — defense in depth over the per-source gate).
        val validation = validator.validate(document)
        check(validation.isValid) {
            "assembled candidate document failed whole-document validation (PLAN §8 rule 29): " +
                validation.errors.joinToString { "${it.code}@${it.path}" }
        }

        // Step 7 — canonicalize + checksum the exact served bytes.
        val canonical = SourceConfigParser.canonicalDocument(document)
        val checksum = CanonicalJson.checksum(canonical)

        // Step 8 — insert the snapshot with created_at = the shared instant (no DB default).
        val snapshot =
            publishedDocuments.insertSnapshot(
                NewPublishedDocument(
                    documentRevision = revision,
                    schemaVersion = SCHEMA_VERSION,
                    documentJson = canonical,
                    checksum = checksum,
                    canonVersion = CanonicalJson.CANON_VERSION,
                    sourceCount = document.sources.size,
                    createdBy = actorId,
                    createdAt = instant,
                    notes = null,
                ),
            )

        // Step 9 — move the authoritative latest pointer (FK holds: the snapshot row exists).
        publishedDocuments.updatePointer(revision, instant)
        return snapshot
    }

    /**
     * Validate the CANDIDATE document assembled from current published state WITHOUT publishing (admin
     * `POST /documents/validate`, PLAN §4.3). Read-only preview; `generatedAt`/`revision` are placeholders
     * because validation is independent of them.
     */
    @Transactional(readOnly = true)
    fun validateCandidate(): ValidationResult {
        val document = buildDocument(sources.findSourcesForAssembly(), PLACEHOLDER_GENERATED_AT, revision = 0)
        return validator.validate(document)
    }

    /**
     * Build the document model from [assemblySources] (PLAN §9 step 5). Re-sorts by `(position ASC,
     * api ASC)` so the canonical bytes are deterministic regardless of the order the repository returned
     * (PLAN §5 source ordering — key sorting never reorders the sources array). Each stanza is parsed
     * from its stored lifecycle-neutral content and has the SERVED lifecycle injected. Public for the
     * order-determinism test (PLAN §11 test 40).
     */
    fun buildDocument(
        assemblySources: List<AssemblySource>,
        generatedAt: String,
        revision: Long,
    ): SourceConfigDocument {
        val stanzas =
            assemblySources
                .sortedWith(compareBy({ it.position }, { it.api }))
                .map { it.toStanza() }
        return SourceConfigDocument(
            schemaVersion = SCHEMA_VERSION,
            generatedAt = generatedAt,
            revision = revision,
            sources = stanzas,
        )
    }

    private fun AssemblySource.toStanza(): SourceConfig {
        val neutral = SourceConfigParser.parseCompatibleSource(canonicalContent)
        return neutral.copy(lifecycle = servedLifecycle(status))
    }

    /**
     * The served app-vocabulary lifecycle for a server status (PLAN §9 mapping): `active`→`"active"`
     * (rendered as an ABSENT key by kcj-1 default-omission), `disabled`→`"disabled"`, retired→`"removed"`.
     * `draft`/`removed` never reach assembly (they are excluded by [SourceConfigRepository.findSourcesForAssembly]).
     */
    private fun servedLifecycle(status: SourceLifecycleStatus): String =
        when (status) {
            SourceLifecycleStatus.ACTIVE -> "active"
            SourceLifecycleStatus.DISABLED -> "disabled"
            SourceLifecycleStatus.RETIRED -> "removed"
            SourceLifecycleStatus.DRAFT, SourceLifecycleStatus.REMOVED ->
                error("status $status must never reach document assembly (PLAN §9)")
        }

    companion object {
        /** The document schema version (PLAN §7 / §8 rule 1 — `SUPPORTED_SCHEMA_VERSION`). */
        const val SCHEMA_VERSION = 1
        private const val PLACEHOLDER_GENERATED_AT = "1970-01-01T00:00:00Z"
    }
}
