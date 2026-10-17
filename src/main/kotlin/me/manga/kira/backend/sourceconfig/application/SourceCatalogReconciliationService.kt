package me.manga.kira.backend.sourceconfig.application

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.serialization.Serializable
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.common.exception.ConflictException
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPhase
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.SourceCatalogReconciliationReceipt
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

/** Explicit, request-bound new approval of existing history. The caller authenticates ADMIN and one-use step-up. */
@Service
class SourceCatalogReconciliationService(
    private val documents: PublishedDocumentRepository,
    private val proof: SourceCatalogReconciliationProof,
    private val audit: AuditService,
    private val clock: Clock,
) {
    @Transactional(timeout = 30)
    fun reconcile(rawBody: ByteArray, actorId: UUID): SourceCatalogReconciliationReceipt = try {
        check(rawBody.size in 1..MAX_REQUEST_BYTES)
        val raw = rawBody.copyOf()
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw))
        documents.lockPublicationState()
        val state = documents.initialSourceCatalogState()
        if (state.phase == InitialSourceCatalogPhase.RECONCILED) {
            val receipt = checkNotNull(state.reconciliationReceipt)
            check(receipt.requestSha256 == hash)
            receipt // Replay precedes current-pointer, parser and policy checks; never re-adopt evolved state.
        } else {
            check(state.phase == InitialSourceCatalogPhase.RECONCILIATION_REQUIRED)
            val intent = parse(raw)
            check(state.latestDocumentRevision == intent.expectedLatestRevision)
            // Highest stored revision is a consistency comparison, never authority for selecting latest.
            check(documents.findSummaryWindow(null, 1).single().documentRevision == state.latestDocumentRevision)
            check(documents.sequenceNextValue() > intent.expectedLatestRevision)
            proof.verify(intent)
            val receipt = SourceCatalogReconciliationReceipt(
                intent.policyId, hash, intent.anchorRevision, intent.anchorDocumentChecksum, intent.anchorCatalogChecksum,
                intent.expectedLatestRevision, intent.documentChecksum, intent.catalogChecksum,
                clock.instant().truncatedTo(ChronoUnit.MICROS), actorId,
            )
            documents.reconcileSourceCatalog(receipt)
            audit.recordAt(
                AuditAction.SOURCE_CATALOG_RECONCILED, AuditService.ENTITY_DOCUMENT,
                receipt.admittedRevision.toString(), receipt.approvedAt,
                mapOf("policyId" to receipt.policyId, "requestSha256" to receipt.requestSha256,
                    "anchorRevision" to receipt.anchorRevision, "documentRevision" to receipt.admittedRevision,
                    "checksum" to receipt.documentChecksum, "catalogChecksum" to receipt.catalogChecksum),
                actorId,
            )
            receipt
        }
    } catch (_: Exception) {
        // No submitted bytes, parser/provider/DB exception details or signature material crosses HTTP/logging.
        throw SourceCatalogReconciliationRejected()
    }

    private fun parse(raw: ByteArray): ReconciliationIntent {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
        STRUCTURE.readValue(text, Any::class.java)
        return SourceConfigParser.strictJson.decodeFromString(ReconciliationIntent.serializer(), text).also {
            check(it.policyId == POLICY_ID && it.anchorRevision > 0 && it.expectedLatestRevision >= it.anchorRevision)
            check(listOf(it.anchorDocumentChecksum, it.anchorCatalogChecksum, it.documentChecksum, it.catalogChecksum).all(HASH::matches))
        }
    }

    companion object {
        const val POLICY_ID = "signed-catalog-history-reconciliation-v1"
        const val CONFIRMATION = "ADMIT_EXISTING_SIGNED_CATALOG"
        const val MAX_REQUEST_BYTES = 4096
        private val HASH = Regex("[0-9a-f]{64}")
        private val STRUCTURE = ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).build()).build(),
        ).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    }
}

@Serializable
internal data class ReconciliationIntent(
    val policyId: String,
    val anchorRevision: Long,
    val anchorDocumentChecksum: String,
    val anchorCatalogChecksum: String,
    val expectedLatestRevision: Long,
    val documentChecksum: String,
    val catalogChecksum: String,
)

class SourceCatalogReconciliationRejected : ConflictException(
    "source-catalog reconciliation refused; review the selected signed history and unchanged held state.",
    code = "SOURCE_CATALOG_RECONCILIATION_REJECTED",
)
