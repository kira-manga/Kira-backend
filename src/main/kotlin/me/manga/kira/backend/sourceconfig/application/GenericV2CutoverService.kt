package me.manga.kira.backend.sourceconfig.application

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.common.exception.ConflictException
import me.manga.kira.backend.common.exception.PayloadTooLargeException
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPhase
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPolicy
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPolicyRejected
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogReceipt
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.SourceConfigRepository
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

/**
 * One raw, payload-bound initial transaction. Receipt replay is independent of the current parser,
 * reference policy and evolved inventory; an old confirmation-only apply never publishes.
 */
@Service
class GenericV2CutoverService(
    private val sources: SourceConfigRepository,
    private val documents: PublishedDocumentRepository,
    private val assembly: DocumentAssemblyService,
    private val importer: BundledImportService,
    private val initialCatalogPolicy: InitialSourceCatalogPolicy,
    private val audit: AuditService,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun dryRun(): GenericV2CutoverResult {
        val state = documents.initialSourceCatalogState()
        val heads = sources.findAll(null)
        val problems =
            when (state.phase) {
                InitialSourceCatalogPhase.PENDING -> if (heads.isEmpty()) emptyList() else listOf("pending catalog contains source heads")

                InitialSourceCatalogPhase.COMPLETE -> emptyList()

                InitialSourceCatalogPhase.RECONCILIATION_REQUIRED -> listOf("existing catalog requires separately authorized reconciliation")
            }
        val withheld = heads.filter { it.status == SourceLifecycleStatus.WITHHELD }.mapTo(hashSetOf()) { it.api }
        return GenericV2CutoverResult(
            ready = problems.isEmpty(),
            applied = false,
            approvedActiveSources = APPROVED_GENERIC_APIS,
            legacySourcesToWithhold = LEGACY_APIS.filterNot { it in withheld },
            alreadyWithheldSources = LEGACY_APIS.filter { it in withheld },
            problems = problems,
            documentRevision = state.receipt?.documentRevision ?: state.latestDocumentRevision,
            phase = state.phase,
            receipt = state.receipt,
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun apply(confirmation: String, actorId: UUID): GenericV2CutoverResult =
        throw GenericV2CutoverRejected("use POST /api/v1/admin/source-catalog-v2/cutover/import-bundled")

    /** ADMIN is enforced at the HTTP boundary; all input and state checks also protect direct callers. */
    @Transactional
    fun importBundled(rawBody: ByteArray, confirmation: String, actorId: UUID): InitialSourceCatalogReceipt {
        if (rawBody.size > MAX_BOOTSTRAP_BYTES) throw PayloadTooLargeException("bootstrap document exceeds the 5 MiB limit")
        if (confirmation != CONFIRMATION) {
            throw GenericV2CutoverRejected("exact confirmation is required")
        }
        val payload = rawBody.copyOf()
        val payloadSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload))
        documents.lockPublicationState()
        val state = documents.initialSourceCatalogState()
        when (state.phase) {
            InitialSourceCatalogPhase.COMPLETE -> {
                val receipt = checkNotNull(state.receipt)
                if (receipt.payloadSha256 != payloadSha256) throw GenericV2CutoverRejected("initial bootstrap already completed with different bytes")
                return receipt
            }

            InitialSourceCatalogPhase.RECONCILIATION_REQUIRED ->
                throw GenericV2CutoverRejected("existing catalog requires separately authorized reconciliation")

            InitialSourceCatalogPhase.PENDING -> Unit
        }
        check(documents.snapshotCount() == 0L) { "pending bootstrap has unexpected publication history" }
        return importPending(decodeUtf8(payload), payloadSha256, actorId)
    }

    private fun importPending(rawJson: String, payloadSha256: String, actorId: UUID): InitialSourceCatalogReceipt {
        try {
            val admission = initialCatalogPolicy.admitPayload(rawJson)
            val instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)
            val staged = importer.stageInitialBootstrap(admission.document, actorId, instant)
            initialCatalogPolicy.requireStagedInventory(sources.findAll(null), sources.findSourcesForAssembly())
            LEGACY_APIS.sorted().forEach { api ->
                val head = sources.lockByApiForUpdate(api) ?: throw GenericV2CutoverRejected("reviewed legacy source is missing")
                if (head.status != SourceLifecycleStatus.WITHHELD) sources.updateStatus(head.id, SourceLifecycleStatus.WITHHELD, instant)
            }
            val publication = assembly.materializeInitialBootstrap(actorId, instant)
            val receipt =
                InitialSourceCatalogReceipt(
                    policyId = admission.policyId,
                    referenceSha256 = admission.referenceSha256,
                    payloadSha256 = payloadSha256,
                    documentRevision = publication.document.documentRevision,
                    documentChecksum = publication.document.checksum,
                    catalogRevision = publication.catalog.catalogRevision,
                    catalogChecksum = publication.catalog.checksum,
                    completedAt = publication.document.createdAt,
                    actorId = actorId,
                )
            importer.recordInitialBootstrapAudit(admission.document, staged, publication.document, actorId)
            recordCompletionAudit(receipt)
            documents.completeInitialSourceCatalog(receipt)
            return receipt
        } catch (ex: InitialSourceCatalogPolicyRejected) {
            throw GenericV2CutoverRejected(ex.message ?: "initial catalog policy rejected the candidate")
        }
    }

    private fun recordCompletionAudit(receipt: InitialSourceCatalogReceipt) {
        audit.recordAt(
            AuditAction.SOURCE_CATALOG_V2_CUTOVER,
            AuditService.ENTITY_DOCUMENT,
            receipt.documentRevision.toString(),
            receipt.completedAt,
            mapOf(
                "activeGenericCount" to APPROVED_GENERIC_APIS.size,
                "withheldLegacyCount" to LEGACY_APIS.size,
                "policyId" to receipt.policyId,
                "referenceSha256" to receipt.referenceSha256,
                "payloadSha256" to receipt.payloadSha256,
                "documentRevision" to receipt.documentRevision,
                "checksum" to receipt.documentChecksum,
                "catalogRevision" to receipt.catalogRevision,
                "catalogChecksum" to receipt.catalogChecksum,
            ),
            receipt.actorId,
        )
    }

    private fun decodeUtf8(payload: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload))
            .toString()
    } catch (_: CharacterCodingException) {
        throw BadRequestException("bootstrap document must be valid UTF-8", code = "BOOTSTRAP_INVALID_UTF8")
    }

    companion object {
        const val CONFIRMATION = "WITHHOLD_33_LEGACY_SOURCES"
        const val MAX_BOOTSTRAP_BYTES = 5 * 1024 * 1024
        const val GENERIC_ENGINE = "generic"

        val APPROVED_GENERIC_APIS = InitialSourceCatalogPolicy.APPROVED_GENERIC_APIS
        val LEGACY_APIS = InitialSourceCatalogPolicy.LEGACY_APIS
        val EXPECTED_ALL = InitialSourceCatalogPolicy.EXPECTED_ALL
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
    val phase: InitialSourceCatalogPhase,
    val receipt: InitialSourceCatalogReceipt?,
)
