package me.manga.kira.backend.sourceconfig.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

/**
 * Spring Data JPA repository for [PublishedDocumentEntity] (PLAN §2 infrastructure). Wrapped by
 * [JpaPublishedDocumentRepositoryAdapter]. [maxDocumentRevision] backs the **startup consistency check
 * only** (PLAN §5) — never a runtime read path; the authoritative "latest" is the pointer.
 */
interface SpringDataPublishedDocumentRepository : JpaRepository<PublishedDocumentEntity, UUID> {

    @Query("SELECT max(p.documentRevision) FROM PublishedDocumentEntity p")
    fun maxDocumentRevision(): Long?

    fun findByDocumentRevision(documentRevision: Long): PublishedDocumentEntity?

    fun findAllByOrderByDocumentRevisionAsc(): List<PublishedDocumentEntity>
}
