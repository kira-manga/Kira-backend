package me.manga.kira.backend.user.infrastructure

import me.manga.kira.backend.user.domain.Role
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * Spring Data JPA repository for [UserEntity] (PLAN §2 infrastructure). Wrapped by
 * [JpaUserRepositoryAdapter], which exposes the pure-Kotlin domain port. Email predicates use
 * `lower(email)` to match the case-insensitive `uq_users_email_lower` index (PLAN §5). Pagination
 * uses the inherited `findAll(Pageable)` with an explicit sort, so no fragile derived-name query is
 * needed. Mutations join the adapter's transaction. Flush pending work before the bulk update,
 * then clear managed snapshots so neither subsequent reads nor a later flush restore stale fields.
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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = "UPDATE users SET enabled = :enabled, updated_at = :at WHERE id = :id",
        nativeQuery = true,
    )
    fun updateEnabled(@Param("id") id: UUID, @Param("enabled") enabled: Boolean, @Param("at") at: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value =
        "UPDATE users SET password_hash = :passwordHash, credential_version = credential_version + 1, " +
            "updated_at = :at WHERE id = :id AND credential_version < :maxVersion",
        nativeQuery = true,
    )
    fun updatePasswordHashAndVersion(
        @Param("id") id: UUID,
        @Param("passwordHash") passwordHash: String,
        @Param("at") at: Instant,
        @Param("maxVersion") maxVersion: Long,
    ): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = "UPDATE users SET role = :role, updated_at = :at WHERE id = :id",
        nativeQuery = true,
    )
    fun updateRole(@Param("id") id: UUID, @Param("role") role: String, @Param("at") at: Instant): Int
}
