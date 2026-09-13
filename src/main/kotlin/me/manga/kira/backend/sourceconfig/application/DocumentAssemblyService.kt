package me.manga.kira.backend.sourceconfig.application

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.sourceconfig.domain.AssemblySource
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPhase
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPolicy
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPolicyRejected
import me.manga.kira.backend.sourceconfig.domain.NewPublishedDocument
import me.manga.kira.backend.sourceconfig.domain.NewPublishedSourceCatalog
import me.manga.kira.backend.sourceconfig.domain.PublishedCatalogEntry
import me.manga.kira.backend.sourceconfig.domain.PublishedDocument
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceCatalog
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceCatalogRepository
import me.manga.kira.backend.sourceconfig.domain.SourceConfigRepository
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus
import me.manga.kira.backend.sourceconfig.domain.model.RemovedSourceEntry
import me.manga.kira.backend.sourceconfig.domain.model.SourceCatalogEntry
import me.manga.kira.backend.sourceconfig.domain.model.SourceCatalogManifest
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import me.manga.kira.backend.sourceconfig.signing.DocumentSigner
import me.manga.kira.backend.sourceconfig.signing.DocumentSigningInput
import me.manga.kira.backend.sourceconfig.signing.SourceCatalogSignatureCodec
import me.manga.kira.backend.sourceconfig.validation.SourceConfigValidator
import me.manga.kira.backend.sourceconfig.validation.ValidationResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
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
 * The ONE Clock instant (sampled here for ordinary publication, supplied by the initial orchestrator
 * for bootstrap, and truncated to ISO-8601 UTC seconds, PLAN §5) is the document's
 * `generatedAt`, the snapshot row's `created_at`, AND the value the caller stamps into the publication
 * audit detail (PLAN §9 steps 7–8) — application time and DB time can never diverge.
 */
@Service
class DocumentAssemblyService(
    private val sources: SourceConfigRepository,
    private val publishedDocuments: PublishedDocumentRepository,
    private val publishedCatalogs: PublishedSourceCatalogRepository,
    private val validator: SourceConfigValidator,
    private val documentSigner: DocumentSigner,
    private val clock: Clock,
    private val initialCatalogPolicy: InitialSourceCatalogPolicy,
) {

    /**
     * Materialize + persist a new snapshot from the current published state (PLAN §9 steps 4–9). MUST
     * run inside the caller's transaction (which holds the global lock) — [Propagation.MANDATORY]
     * asserts it. Returns the stored snapshot ([PublishedDocument.createdAt] is the shared instant).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun materialize(actorId: UUID): PublishedDocument {
        // MANDATORY proves a transaction, not G ownership. Reassert the exact-one singleton lock.
        publishedDocuments.lockPublicationState()
        if (publishedDocuments.initialSourceCatalogState().phase != InitialSourceCatalogPhase.COMPLETE) {
            throw GenericV2CutoverRejected("initial catalog bootstrap must be complete before ordinary publication")
        }
        val assemblySources = sources.findSourcesForAssembly()
        val publication = materializeLocked(assemblySources, actorId, clock.instant().truncatedTo(ChronoUnit.SECONDS))
        publishedDocuments.updatePointer(publication.document.documentRevision, publication.document.createdAt)
        return publication.document
    }

    /**
     * Only the initial transaction may use this entry. Revalidate actual effective state under G,
     * insert both artifacts, but leave the PENDING/null pointer untouched for the final receipt CAS.
     * [instant] is the initial orchestrator's one shared publication/audit/receipt clock sample.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun materializeInitialBootstrap(actorId: UUID, instant: Instant): MaterializedPublication {
        publishedDocuments.lockPublicationState()
        if (publishedDocuments.initialSourceCatalogState().phase != InitialSourceCatalogPhase.PENDING) {
            throw GenericV2CutoverRejected("initial materialization requires pending bootstrap state")
        }
        check(publishedDocuments.snapshotCount() == 0L) { "pending bootstrap has unexpected publication history" }
        val assemblySources = sources.findSourcesForAssembly()
        try {
            initialCatalogPolicy.requirePublicationInventory(sources.findAll(null), assemblySources)
        } catch (ex: InitialSourceCatalogPolicyRejected) {
            throw GenericV2CutoverRejected(ex.message ?: "initial publication inventory was rejected", ex)
        }
        check(instant.nano == 0) { "initial publication instant must use whole UTC seconds" }
        return materializeLocked(assemblySources, actorId, instant)
    }

    private fun materializeLocked(assemblySources: List<AssemblySource>, actorId: UUID, instant: Instant): MaterializedPublication {
        // ONE instant for generatedAt / both created_at values / audit / receipt.
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
        val previousRevision = publishedDocuments.latestPointer()
        val previousChecksum = previousRevision?.let { publishedDocuments.findByRevision(it)?.checksum }
        check(previousRevision == null || previousChecksum != null) { "latest document pointer has no snapshot" }
        val signature =
            documentSigner.sign(
                DocumentSigningInput(
                    revision = revision,
                    checksum = checksum,
                    createdAt = instant,
                    previousRevision = previousRevision,
                    previousChecksum = previousChecksum,
                    documentJson = canonical,
                ),
            )

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
                    signatureFormat = signature?.format,
                    signatureAlgorithm = signature?.algorithm,
                    signingKeyId = signature?.keyId,
                    signatureBase64 = signature?.signatureBase64,
                    previousDocumentRevision = signature?.previousRevision,
                    previousDocumentChecksum = signature?.previousChecksum,
                ),
            )

        // Source-catalog v2 is materialized in the SAME transaction and shares this revision. The
        // latest pointer cannot expose the document until its corresponding manifest is durable.
        val catalog =
            materializeCatalog(
                assemblySources = assemblySources,
                catalogRevision = revision,
                generatedAt = generatedAt,
                instant = instant,
                actorId = actorId,
                previousDocumentRevision = previousRevision,
            )

        // The normal entry moves the pointer; initial bootstrap finalizes pointer/receipt/phase in
        // one CAS after both artifacts exist. Neither path can expose only one artifact family.
        return MaterializedPublication(snapshot, catalog)
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
    fun buildDocument(assemblySources: List<AssemblySource>, generatedAt: String, revision: Long): SourceConfigDocument {
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
    private fun servedLifecycle(status: SourceLifecycleStatus): String = when (status) {
        SourceLifecycleStatus.ACTIVE -> "active"

        SourceLifecycleStatus.DISABLED -> "disabled"

        SourceLifecycleStatus.RETIRED -> "removed"

        SourceLifecycleStatus.DRAFT, SourceLifecycleStatus.WITHHELD, SourceLifecycleStatus.REMOVED ->
            error("status $status must never reach document assembly (PLAN §9)")
    }

    private fun materializeCatalog(
        assemblySources: List<AssemblySource>,
        catalogRevision: Long,
        generatedAt: String,
        instant: Instant,
        actorId: UUID,
        previousDocumentRevision: Long?,
    ): PublishedSourceCatalog {
        val previousCatalog = previousDocumentRevision?.let(publishedCatalogs::findByRevision)
        val currentApis = assemblySources.mapTo(linkedSetOf()) { it.api }
        val removedApis =
            (
                previousCatalog?.let { publishedCatalogs.removedApis(it.catalogRevision) }.orEmpty() +
                    (publishedCatalogs.previouslyPublishedApis() - currentApis)
                ).toSortedSet()

        val persistedEntries =
            assemblySources
                .sortedWith(compareBy({ it.position }, { it.api }))
                .mapIndexed { order, source ->
                    require(source.engine == GENERIC_ENGINE) {
                        "non-generic source '${source.api}' must never enter public catalog assembly"
                    }
                    val sourceSignature =
                        requireNotNull(
                            documentSigner.signDetached(
                                SourceCatalogSignatureCodec.sourcePayload(
                                    api = source.api,
                                    sourceRevision = source.revisionNumber,
                                    checksum = source.checksum,
                                    canonicalJson = source.canonicalContent,
                                ),
                            ),
                        ) { "source-catalog v2 requires configured Ed25519 signing" }
                    PublishedCatalogEntry(
                        sourceConfigId = source.sourceConfigId,
                        sourceRevisionId = source.sourceRevisionId,
                        api = source.api,
                        sourceRevision = source.revisionNumber,
                        checksum = source.checksum,
                        order = order,
                        lifecycle = catalogLifecycle(source.status),
                        engine = source.engine,
                        sourceSigningKeyId = sourceSignature.keyId,
                        sourceSignature = sourceSignature.signatureBase64,
                    )
                }
        val manifest =
            SourceCatalogManifest(
                schemaVersion = CATALOG_SCHEMA_VERSION,
                sourceSchemaVersion = SCHEMA_VERSION,
                catalogRevision = catalogRevision,
                generatedAt = generatedAt,
                sources =
                persistedEntries.map { entry ->
                    SourceCatalogEntry(
                        api = entry.api,
                        sourceRevision = entry.sourceRevision,
                        checksum = entry.checksum,
                        order = entry.order,
                        lifecycle = entry.lifecycle,
                        engine = entry.engine,
                        sourceSigningKeyId = entry.sourceSigningKeyId,
                        sourceSignature = entry.sourceSignature,
                    )
                },
                removedSources = removedApis.map(::RemovedSourceEntry),
            )
        val canonical = CanonicalJson.canonicalize(SourceCatalogManifest.serializer(), manifest)
        val checksum = CanonicalJson.checksum(canonical)
        val manifestSignature =
            requireNotNull(
                documentSigner.signDetached(
                    SourceCatalogSignatureCodec.manifestPayload(
                        catalogRevision = catalogRevision,
                        previousCatalogRevision = previousCatalog?.catalogRevision,
                        previousCatalogChecksum = previousCatalog?.checksum,
                        checksum = checksum,
                        createdAt = instant,
                        manifestJson = canonical,
                    ),
                ),
            ) { "source-catalog v2 requires configured Ed25519 signing" }
        return publishedCatalogs.insert(
            NewPublishedSourceCatalog(
                catalogRevision = catalogRevision,
                schemaVersion = CATALOG_SCHEMA_VERSION,
                sourceSchemaVersion = SCHEMA_VERSION,
                manifestJson = canonical,
                checksum = checksum,
                canonVersion = CanonicalJson.CANON_VERSION,
                createdBy = actorId,
                createdAt = instant,
                signatureFormat = SourceCatalogSignatureCodec.MANIFEST_FORMAT,
                signatureAlgorithm = manifestSignature.algorithm,
                signingKeyId = manifestSignature.keyId,
                signatureBase64 = manifestSignature.signatureBase64,
                previousCatalogRevision = previousCatalog?.catalogRevision,
                previousCatalogChecksum = previousCatalog?.checksum,
                entries = persistedEntries,
                removedApis = removedApis.toList(),
            ),
        )
    }

    /** V2 keeps server lifecycle explicit; only tombstones use `removed`. */
    private fun catalogLifecycle(status: SourceLifecycleStatus): String = when (status) {
        SourceLifecycleStatus.ACTIVE -> "active"

        SourceLifecycleStatus.DISABLED -> "disabled"

        SourceLifecycleStatus.RETIRED -> "retired"

        SourceLifecycleStatus.DRAFT, SourceLifecycleStatus.WITHHELD, SourceLifecycleStatus.REMOVED ->
            error("status $status must never reach source-catalog assembly")
    }

    companion object {
        /** The document schema version (PLAN §7 / §8 rule 1 — `SUPPORTED_SCHEMA_VERSION`). */
        const val SCHEMA_VERSION = 1
        const val CATALOG_SCHEMA_VERSION = 1
        private const val GENERIC_ENGINE = "generic"
        private const val PLACEHOLDER_GENERATED_AT = "1970-01-01T00:00:00Z"
    }
}

data class MaterializedPublication(val document: PublishedDocument, val catalog: PublishedSourceCatalog)
