package me.manga.kira.backend.user.infrastructure

import me.manga.kira.backend.user.domain.Role
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

/**
 * Spring Data JPA repository for [UserEntity] (PLAN §2 infrastructure). Wrapped by
 * [JpaUserRepositoryAdapter], which exposes the pure-Kotlin domain port. Email predicates use
 * `lower(email)` to match the case-insensitive `uq_users_email_lower` index (PLAN §5). Pagination
 * uses the inherited `findAll(Pageable)` with an explicit sort, so no fragile derived-name query is
 * needed.
 */
interface SpringDataUserRepository : JpaRepository<UserEntity, UUID> {

    @Query("SELECT u FROM UserEntity u WHERE lower(u.email) = lower(:email)")
    fun findByEmailIgnoreCase(@Param("email") email: String): UserEntity?

    @Query(
        "SELECT CASE WHEN count(u) > 0 THEN true ELSE false END " +
            "FROM UserEntity u WHERE lower(u.email) = lower(:email)",
    )
    fun existsByEmailIgnoreCase(@Param("email") email: String): Boolean

    @Query("SELECT CASE WHEN count(u) > 0 THEN true ELSE false END FROM UserEntity u WHERE u.role = :role")
    fun existsByRole(@Param("role") role: Role): Boolean

    @Query("SELECT count(u) FROM UserEntity u WHERE u.role = :role AND u.enabled = true")
    fun countEnabledByRole(@Param("role") role: Role): Long
}
