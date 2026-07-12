package me.manga.kira.backend.sourceconfig.application

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.sourceconfig.domain.PublishedDocument
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.SourceConfigRepository
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Read side of the public app-facing surface (PLAN §4.1) — the two public controllers call it and stay
 * thin. Every "latest" read resolves through the single authoritative
 * [PublishedDocumentRepository.latestPointer] (PLAN §5 — never `MAX(document_revision)`).
 *
 * Consistency with the served bytes is achieved by reading the **latest snapshot's stored canonical
 * bytes** as the source of truth: the summary list parses that document (its stanzas are already in the
 * normative `(position ASC, api ASC)` order with the served lifecycle injected), and the single by-api
 * view extracts the stanza JsonObject verbatim from those same bytes (kotlinx preserves member order),
 * so what `/sources` and `/sources/{api}` report can never drift from what `/source-config/document`
 * serves. `revisionNumber`/`publishedAt` (which the document does not carry) come from each source's
 * currently-published revision row.
 */
@Service
class SourceQueryService(
    private val publishedDocuments: PublishedDocumentRepository,
    private val sources: SourceConfigRepository,
) {

    /** The latest published snapshot resolved through the authoritative pointer, or null before any publish. */
    @Transactional(readOnly = true)
    fun latestDocument(): PublishedDocument? {
        val pointer = publishedDocuments.latestPointer() ?: return null
        return publishedDocuments.findByRevision(pointer)
    }

    /**
     * Summaries of the sources in the CURRENT document (PLAN §4.1), in document order, filtered by the
     * optional app-vocabulary [lifecycles] ({active, disabled, removed}) and [engines] ({generic,
     * legacy}) sets (null = no filter). Draft-only and removed sources never appear (they are not in the
     * document). No document ever published → empty list.
     */
    @Transactional(readOnly = true)
    fun listSummaries(
        lifecycles: Set<String>?,
        engines: Set<String>?,
    ): List<SourceSummary> {
        val document = latestDocument() ?: return emptyList()
        val parsed = SourceConfigParser.parseCompatibleDocument(document.documentJson)
        val metadata = sources.findPublishedRevisionMetadata().associateBy { it.api }
        return parsed.sources
            .filter { lifecycles == null || it.lifecycle in lifecycles }
            .filter { engines == null || it.engine in engines }
            .map { stanza ->
                // Invariant: every source in the served document has a currently-published revision.
                val meta =
                    checkNotNull(metadata[stanza.api]) {
                        "served source '${stanza.api}' has no published-revision metadata (inconsistent state)"
                    }
                SourceSummary(
                    api = stanza.api,
                    displayName = stanza.displayName,
                    language = stanza.language,
                    engine = stanza.engine,
                    lifecycle = stanza.lifecycle,
                    siteState = stanza.siteState,
                    adult = stanza.siteState == ADULT_SITE_STATE,
                    baseUrl = stanza.baseUrl,
                    iconRemoteUrl = stanza.icon?.remoteUrl?.takeIf { it.isNotBlank() },
                    revisionNumber = meta.revisionNumber,
                    publishedAt = meta.publishedAt,
                )
            }
    }

    /**
     * The single published stanza for [api], served CONSISTENT with the document content (PLAN §4.1):
     * the stanza's JsonObject is extracted verbatim from the latest snapshot and re-serialized compactly
     * (so an active stanza keeps its shape — NO injected `lifecycle:"active"`). Absent from the document:
     * a terminally `removed` source → [SourceStanzaResult.Gone] (410); an unknown or draft-only (never
     * published) source → [SourceStanzaResult.NotFound] (404), decided by the source head row's status.
     */
    @Transactional(readOnly = true)
    fun sourceStanza(api: String): SourceStanzaResult {
        val document = latestDocument()
        if (document != null) {
            val stanza = findStanza(document.documentJson, api)
            if (stanza != null) {
                return SourceStanzaResult.Found(CanonicalJson.json.encodeToString(JsonElement.serializer(), stanza))
            }
        }
        return when (sources.findByApi(api)?.status) {
            SourceLifecycleStatus.REMOVED -> SourceStanzaResult.Gone
            else -> SourceStanzaResult.NotFound
        }
    }

    /** Extract the `sources[]` element whose `api` equals [api] from the stored document bytes, or null. */
    private fun findStanza(
        documentJson: String,
        api: String,
    ): JsonObject? {
        val root = CanonicalJson.json.parseToJsonElement(documentJson) as? JsonObject ?: return null
        val array = root["sources"] as? JsonArray ?: return null
        return array
            .asSequence()
            .filterIsInstance<JsonObject>()
            .firstOrNull { (it["api"] as? JsonPrimitive)?.content == api }
    }

    private companion object {
        const val ADULT_SITE_STATE = "ADULT_18_PLUS"
    }
}

/**
 * One `GET /sources` summary row (PLAN §4.1). [lifecycle] is the app-vocabulary value already present in
 * the served stanza (`active`/`disabled`/retired-as-`removed`); [iconRemoteUrl] is null when the stanza
 * carries no `icon.remoteUrl`; [revisionNumber]/[publishedAt] are the source's currently-published
 * revision and its publish time.
 */
data class SourceSummary(
    val api: String,
    val displayName: String,
    val language: String,
    val engine: String,
    val lifecycle: String,
    val siteState: String,
    val adult: Boolean,
    val baseUrl: String,
    val iconRemoteUrl: String?,
    val revisionNumber: Int,
    val publishedAt: Instant,
)

/** Outcome of a by-api stanza lookup (PLAN §4.1 status mapping): 200 / 410 / 404. */
sealed interface SourceStanzaResult {
    /** 200 — the compact stanza JSON, exactly as it appears in the served document. */
    data class Found(val json: String) : SourceStanzaResult

    /** 410 — the source is terminally removed (dropped from the document). */
    data object Gone : SourceStanzaResult

    /** 404 — unknown api or a draft-only source that has never been published. */
    data object NotFound : SourceStanzaResult
}
