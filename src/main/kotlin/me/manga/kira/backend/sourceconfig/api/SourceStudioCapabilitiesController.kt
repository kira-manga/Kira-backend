package me.manga.kira.backend.sourceconfig.api

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.sourceconfig.application.DocumentAssemblyService
import me.manga.kira.backend.sourceconfig.application.SourceEditorDraftService
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus
import me.manga.kira.backend.sourceconfig.validation.ServerStrategyCatalog
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Machine-readable editor contract. The web UI uses this instead of duplicating strategy and
 * lifecycle vocabularies that could silently drift from backend validation.
 */
@RestController
@RequestMapping("/api/v1/admin/source-studio/capabilities")
class SourceStudioCapabilitiesController {
    @GetMapping
    fun get(): SourceStudioCapabilitiesResponse = SourceStudioCapabilitiesResponse(
        sourceSchemaVersion = DocumentAssemblyService.SCHEMA_VERSION,
        catalogSchemaVersion = DocumentAssemblyService.CATALOG_SCHEMA_VERSION,
        canonicalization = CanonicalJson.CANON_VERSION,
        authorableEngines = listOf("generic"),
        serverLifecycleStates = SourceLifecycleStatus.entries.map { it.wire },
        transforms = ServerStrategyCatalog.TRANSFORMS.sorted(),
        dateStrategies = ServerStrategyCatalog.DATE_STRATEGIES.sorted(),
        imageStrategies = ServerStrategyCatalog.IMAGE_STRATEGIES.sorted(),
        paginationStrategies = ServerStrategyCatalog.PAGINATION_TYPES.sorted(),
        endpointMethods = listOf("get", "post-form", "post-json"),
        endpointFormats = listOf("html", "json", "script-json"),
        editorDraftMaxBytes = SourceEditorDraftService.MAX_DRAFT_BYTES,
        optimisticLocking = "strong-etag-if-match",
        publicEnginePolicy = "generic-only",
    )
}

data class SourceStudioCapabilitiesResponse(
    val sourceSchemaVersion: Int,
    val catalogSchemaVersion: Int,
    val canonicalization: String,
    val authorableEngines: List<String>,
    val serverLifecycleStates: List<String>,
    val transforms: List<String>,
    val dateStrategies: List<String>,
    val imageStrategies: List<String>,
    val paginationStrategies: List<String>,
    val endpointMethods: List<String>,
    val endpointFormats: List<String>,
    val editorDraftMaxBytes: Int,
    val optimisticLocking: String,
    val publicEnginePolicy: String,
)
