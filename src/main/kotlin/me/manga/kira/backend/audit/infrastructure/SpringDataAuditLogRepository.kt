package me.manga.kira.backend.audit.infrastructure

import org.springframework.data.jpa.repository.JpaRepository

/**
 * Spring Data JPA repository for [AuditLogEntity] (PLAN §2 infrastructure). Wrapped by
 * [JpaAuditRepositoryAdapter], which exposes the pure-Kotlin domain port.
 */
interface SpringDataAuditLogRepository : JpaRepository<AuditLogEntity, Long>
