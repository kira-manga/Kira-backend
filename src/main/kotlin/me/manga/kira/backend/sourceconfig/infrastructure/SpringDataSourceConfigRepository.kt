package me.manga.kira.backend.sourceconfig.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Spring Data JPA repository for [SourceConfigEntity] (PLAN §2 infrastructure). Wrapped by
 * [JpaSourceConfigRepositoryAdapter], which exposes the pure-Kotlin domain port.
 */
interface SpringDataSourceConfigRepository : JpaRepository<SourceConfigEntity, UUID> {

    fun findByApi(api: String): SourceConfigEntity?

    fun existsByApi(api: String): Boolean
}
