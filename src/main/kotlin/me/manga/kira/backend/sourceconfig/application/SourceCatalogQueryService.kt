package me.manga.kira.backend.sourceconfig.application

import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceArtifact
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceCatalog
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceCatalogRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SourceCatalogQueryService(private val documents: PublishedDocumentRepository, private val catalogs: PublishedSourceCatalogRepository) {
    @Transactional(readOnly = true)
    fun latestManifest(): PublishedSourceCatalog? {
        val pointer = documents.latestPointer() ?: return null
        return catalogs.findByRevision(pointer)
    }

    @Transactional(readOnly = true)
    fun publishedArtifact(api: String, revision: Int): PublishedSourceArtifact? = if (revision > 0) {
        catalogs.findPublishedArtifact(api, revision)
    } else {
        null
    }
}
