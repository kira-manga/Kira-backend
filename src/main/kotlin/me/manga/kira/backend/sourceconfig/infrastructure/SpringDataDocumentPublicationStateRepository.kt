package me.manga.kira.backend.sourceconfig.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * Spring Data JPA repository for the [DocumentPublicationStateEntity] singleton (id = 1), seeded by V3.
 * The adapter reads/locks phase and receipt via transaction-bound JDBC, never a managed singleton
 * that could shadow native updates. [updatePointer] is the ordinary COMPLETE-only native update
 * (PLAN §9 step 9), after both artifacts exist; its count must be exactly one. Initial completion uses
 * the adapter's separate pending-state CAS to finalize phase, receipt and pointer together.
 */
interface SpringDataDocumentPublicationStateRepository : JpaRepository<DocumentPublicationStateEntity, Int> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value =
        "UPDATE document_publication_state SET latest_document_revision = :revision, " +
            "updated_at = :at WHERE id = 1 AND bootstrap_phase = 'complete'",
        nativeQuery = true,
    )
    fun updatePointer(@Param("revision") revision: Long, @Param("at") at: Instant): Int
}
