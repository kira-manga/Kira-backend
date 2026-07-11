package me.manga.kira.backend.user.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import me.manga.kira.backend.user.domain.Role
import java.time.Instant
import java.util.UUID

/**
 * JPA entity for the `users` table (PLAN §5 V1). Kept in `infrastructure` — it never leaks into
 * controllers or the domain (PLAN §2). `kotlin-jpa` supplies the no-arg constructor; `allOpen`
 * makes it `open` for Hibernate.
 *
 * The id is generated **client-side** by Hibernate ([GenerationType.UUID]) so a `save` is a plain
 * INSERT (no pre-select) and the generated id is available immediately; the column's
 * `DEFAULT gen_random_uuid()` is a belt-and-braces fallback for manual inserts. `created_at` /
 * `updated_at` are stamped by [PrePersist]/[PreUpdate] so application and DB time cannot diverge.
 */
@Entity
@Table(name = "users")
class UserEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null,
    @Column(name = "email", nullable = false)
    var email: String = "",
    @Column(name = "password_hash", nullable = false)
    var passwordHash: String = "",
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    var role: Role = Role.USER,
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
) {
    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }
}
