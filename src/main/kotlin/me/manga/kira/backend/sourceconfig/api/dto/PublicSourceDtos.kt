package me.manga.kira.backend.sourceconfig.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import me.manga.kira.backend.sourceconfig.application.SourceSummary
import me.manga.kira.backend.sourceconfig.domain.PublishedDocument
import java.time.Instant

/**
 * Public app-facing API DTOs (PLAN §4.1). Jackson-serialized thin views mapped from application
 * results (the source-config MODEL itself uses kotlinx and is served as raw canonical bytes, never
 * through these). The single stanza (`GET /sources/{api}`) is NOT here — it is the stored canonical
 * bytes written verbatim by the controller, not a re-serialized DTO.
 */

/** `GET /source-config/document/meta` — the cheap poll body `{revision, schemaVersion, checksum, publishedAt}`. */
data class DocumentMetaResponse(
    val revision: Long,
    val schemaVersion: Int,
    val checksum: String,
    val publishedAt: Instant,
) {
    companion object {
        fun of(doc: PublishedDocument) =
            DocumentMetaResponse(
                revision = doc.documentRevision,
                schemaVersion = doc.schemaVersion,
                checksum = doc.checksum,
                publishedAt = doc.createdAt,
            )
    }
}

/**
 * `GET /sources` list item (PLAN §4.1). `iconRemoteUrl` is omitted when the stanza carries none
 * (`NON_NULL`); `lifecycle` is the app-vocabulary value (retired source → `"removed"`).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SourceSummaryResponse(
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
) {
    companion object {
        fun of(summary: SourceSummary) =
            SourceSummaryResponse(
                api = summary.api,
                displayName = summary.displayName,
                language = summary.language,
                engine = summary.engine,
                lifecycle = summary.lifecycle,
                siteState = summary.siteState,
                adult = summary.adult,
                baseUrl = summary.baseUrl,
                iconRemoteUrl = summary.iconRemoteUrl,
                revisionNumber = summary.revisionNumber,
                publishedAt = summary.publishedAt,
            )
    }
}
