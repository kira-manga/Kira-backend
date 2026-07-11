package me.manga.kira.backend.sourceconfig.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * Spring Data JPA repository for the [DocumentPublicationStateEntity] singleton (id = 1), seeded by V3.
 * The pointer read (`findById(1)`) is inherited from [JpaRepository]. [updatePointer] is a native
 * `@Modifying` update (PLAN §9 step 9) — executed after the snapshot row is inserted so the pointer FK
 * always holds. The GLOBAL publication `FOR UPDATE` lock (step 1) is issued via `JdbcTemplate` in the
 * adapter (a native `SELECT … FOR UPDATE`, no managed entity to keep in sync with the native pointer update).
 */
interface SpringDataDocumentPublicationStateRepository : JpaRepository<DocumentPublicationStateEntity, Int> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value =
            "UPDATE document_publication_state SET latest_document_revision = :revision, " +
                "updated_at = :at WHERE id = 1",
        nativeQuery = true,
    )
    fun updatePointer(
        @Param("revision") revision: Long,
        @Param("at") at: Instant,
    )
}
