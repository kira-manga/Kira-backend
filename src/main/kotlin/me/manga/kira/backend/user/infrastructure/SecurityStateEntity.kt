package me.manga.kira.backend.user.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * JPA entity for the `security_state` singleton row (id = 1), seeded by V1 (PLAN §5). Its only
 * purpose is to be locked `FOR UPDATE` to serialize admin-account mutations (PLAN §4.4).
 */
@Entity
@Table(name = "security_state")
class SecurityStateEntity(
    @Id
    @Column(name = "id", nullable = false)
    var id: Int = 1,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
)
