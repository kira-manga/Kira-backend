package me.manga.kira.backend.user.infrastructure

import me.manga.kira.backend.user.domain.CredentialVersionExhaustedException
import me.manga.kira.backend.user.domain.PagedUsers
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import me.manga.kira.backend.user.domain.UserNotFoundException
import me.manga.kira.backend.user.domain.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Adapts [SpringDataUserRepository] to the pure-Kotlin [UserRepository] port (PLAN §2 —
 * infrastructure implements the domain port; entity↔domain mapping is explicit here so entities
 * never escape this layer).
 */
@Repository
class JpaUserRepositoryAdapter(private val jpa: SpringDataUserRepository) : UserRepository {

    override fun findById(id: UUID): User? = jpa.findById(id).map { it.toDomain() }.orElse(null)

    override fun findByEmail(normalizedEmail: String): User? = jpa.findByEmailIgnoreCase(normalizedEmail)?.toDomain()

    override fun existsByEmail(normalizedEmail: String): Boolean = jpa.existsByEmailIgnoreCase(normalizedEmail)

    override fun adminExists(): Boolean = jpa.existsByRole(Role.ADMIN)

    override fun countEnabledAdmins(): Long = jpa.countEnabledByRole(Role.ADMIN)

    override fun create(normalizedEmail: String, passwordHash: String, role: Role): User {
        val entity =
            UserEntity(
                email = normalizedEmail,
                passwordHash = passwordHash,
                role = role,
                enabled = true,
            )
        return jpa.save(entity).toDomain()
    }

    @Transactional
    override fun setEnabled(id: UUID, enabled: Boolean) {
        if (jpa.updateEnabled(id, enabled, Instant.now()) != 1) throw UserNotFoundException()
    }

    @Transactional
    override fun updatePasswordHash(id: UUID, passwordHash: String) {
        if (jpa.updatePasswordHashAndVersion(id, passwordHash, Instant.now(), Long.MAX_VALUE) != 1) {
            if (!jpa.existsById(id)) throw UserNotFoundException()
            throw CredentialVersionExhaustedException()
        }
    }

    @Transactional
    override fun updateRole(id: UUID, role: Role) {
        if (jpa.updateRole(id, role.name, Instant.now()) != 1) throw UserNotFoundException()
    }

    override fun findPage(page: Int, size: Int): PagedUsers {
        val result = jpa.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt")))
        return PagedUsers(items = result.content.map { it.toDomain() }, total = result.totalElements)
    }

    private fun UserEntity.toDomain(): User = User(
        id = requireNotNull(id) { "persisted UserEntity must have an id" },
        email = email,
        passwordHash = passwordHash,
        role = role,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
        credentialVersion = credentialVersion,
    )
}
