package me.manga.kira.backend.sourceconfig.application

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.common.exception.ConflictException
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.RevisionRepository
import me.manga.kira.backend.sourceconfig.domain.SourceConfigHead
import me.manga.kira.backend.sourceconfig.domain.SourceConfigRepository
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * One-time, fail-closed 12/33 rollout operation. Dry-run is the default; apply is a single globally
 * serialized transaction and refuses any inventory drift.
 */
@Service
class GenericV2CutoverService(
    private val sources: SourceConfigRepository,
    private val revisions: RevisionRepository,
    private val documents: PublishedDocumentRepository,
    private val assembly: DocumentAssemblyService,
    private val audit: AuditService,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun dryRun(): GenericV2CutoverResult = inspect(sources.findAll(null), applied = false, documentRevision = null)

    @Transactional
    fun apply(confirmation: String, actorId: UUID): GenericV2CutoverResult {
        if (confirmation != CONFIRMATION) {
            throw GenericV2CutoverRejected("exact confirmation is required")
        }
        documents.lockPublicationState()
        val locked = EXPECTED_ALL.sorted().map { api -> sources.lockByApiForUpdate(api) ?: throw drift("missing source") }
        // Re-read the complete inventory after acquiring the global writer lock. Inspecting only
        // [locked] would silently ignore an unexpected 46th source and defeat the exact-inventory
        // cutover guard.
        val preflight = inspect(sources.findAll(null), applied = false, documentRevision = null)
        if (!preflight.ready) throw GenericV2CutoverRejected("catalog inventory does not match the reviewed 12/33 rollout")
        if (preflight.legacySourcesToWithhold.isEmpty()) {
            return preflight.copy(documentRevision = documents.latestPointer())
        }

        val now = clock.instant()
        locked
            .filter { it.api in LEGACY_APIS && it.status != SourceLifecycleStatus.WITHHELD }
            .forEach { sources.updateStatus(it.id, SourceLifecycleStatus.WITHHELD, now) }

        val snapshot = assembly.materialize(actorId)
        audit.recordAt(
            AuditAction.SOURCE_CATALOG_V2_CUTOVER,
            AuditService.ENTITY_DOCUMENT,
            snapshot.documentRevision.toString(),
            snapshot.createdAt,
            mapOf(
                "activeGenericCount" to APPROVED_GENERIC_APIS.size,
                "withheldLegacyCount" to LEGACY_APIS.size,
                "documentRevision" to snapshot.documentRevision,
                "checksum" to snapshot.checksum,
            ),
            actorId,
        )
        return inspect(sources.findAll(null), applied = true, documentRevision = snapshot.documentRevision)
    }

    private fun inspect(heads: List<SourceConfigHead>, applied: Boolean, documentRevision: Long?): GenericV2CutoverResult {
        val byApi = heads.associateBy { it.api }
        val problems = mutableListOf<String>()
        val actual = byApi.keys
        if (actual != EXPECTED_ALL) problems += "source inventory must contain exactly the reviewed 45 APIs"

        APPROVED_GENERIC_APIS.forEach { api ->
            val head = byApi[api]
            when {
                head == null -> problems += "approved generic source is missing"

                head.engine != GENERIC_ENGINE -> problems += "approved source does not have the generic engine"

                head.status != SourceLifecycleStatus.ACTIVE -> problems += "approved source is not active"

                head.currentPublishedRevisionId == null -> problems += "approved source has no published revision"

                else -> {
                    val revision = revisions.findById(head.currentPublishedRevisionId)
                    if (revision == null) problems += "approved source published revision is missing"
                }
            }
        }
        LEGACY_APIS.forEach { api ->
            val head = byApi[api]
            when {
                head == null -> problems += "reviewed legacy source is missing"

                head.engine == GENERIC_ENGINE -> problems += "reviewed legacy source unexpectedly has generic engine"

                head.status !in setOf(SourceLifecycleStatus.ACTIVE, SourceLifecycleStatus.WITHHELD) ->
                    problems += "reviewed legacy source is in an unsupported lifecycle"

                head.currentPublishedRevisionId == null -> problems += "reviewed legacy source has no published revision"
            }
        }
        return GenericV2CutoverResult(
            ready = problems.isEmpty(),
            applied = applied,
            approvedActiveSources = APPROVED_GENERIC_APIS,
            legacySourcesToWithhold =
            LEGACY_APIS.filter { byApi[it]?.status != SourceLifecycleStatus.WITHHELD },
            alreadyWithheldSources =
            LEGACY_APIS.filter { byApi[it]?.status == SourceLifecycleStatus.WITHHELD },
            problems = problems.distinct(),
            documentRevision = documentRevision,
        )
    }

    private fun drift(message: String) = GenericV2CutoverRejected(message)

    companion object {
        const val CONFIRMATION = "WITHHOLD_33_LEGACY_SOURCES"
        const val GENERIC_ENGINE = "generic"

        val APPROVED_GENERIC_APIS =
            listOf(
                "Azora",
                "Mangamello",
                "Mangamello Plus",
                "SwatManga",
                "Lekmanga",
                "Team X",
                "DilarV2",
                "3asq",
                "Demonicscans",
                "Mangabuddy",
                "Zazamanga",
                "Tapas",
            )
        val LEGACY_APIS =
            listOf(
                "Lavatoons",
                "Mangatuk",
                "Dilar",
                "Promanga",
                "Prochan",
                "Batoto",
                "Manhwatop",
                "Comick",
                "Mangapark",
                "مانجا بارك",
                "Mangapark-It",
                "Mangapark-Es",
                "Mangapark-Es-La",
                "Olympusbiblioteca",
                "Manhwaweb",
                "Taurus Fansub",
                "Inmanga",
                "Komik Cast",
                "Komiku",
                "Manga Origine",
                "Raijinscan",
                "Manhastro",
                "Flowermanga",
                "Mediocretoons",
                "Desu",
                "Mangahub",
                "Batcave",
                "Timenaight",
                "Webtoontr",
                "Webtoonhatti",
                "Mangaworld",
                "Senkuro",
                "Sussytoons",
            )
        val EXPECTED_ALL = (APPROVED_GENERIC_APIS + LEGACY_APIS).toSet()
    }
}

class GenericV2CutoverRejected(detail: String) :
    ConflictException("source-catalog v2 cutover rejected: $detail.", code = "SOURCE_CATALOG_V2_CUTOVER_REJECTED")

data class GenericV2CutoverResult(
    val ready: Boolean,
    val applied: Boolean,
    val approvedActiveSources: List<String>,
    val legacySourcesToWithhold: List<String>,
    val alreadyWithheldSources: List<String>,
    val problems: List<String>,
    val documentRevision: Long?,
)
