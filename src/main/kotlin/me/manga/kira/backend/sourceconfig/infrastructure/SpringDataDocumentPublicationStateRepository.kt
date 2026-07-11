package me.manga.kira.backend.sourceconfig.infrastructure

import org.springframework.data.jpa.repository.JpaRepository

/**
 * Spring Data JPA repository for the [DocumentPublicationStateEntity] singleton (id = 1), seeded by V3.
 * The pointer read (`findById(1)`) is inherited from [JpaRepository]. Phase 6 adds the `FOR UPDATE`
 * global publication lock here.
 */
interface SpringDataDocumentPublicationStateRepository : JpaRepository<DocumentPublicationStateEntity, Int>
