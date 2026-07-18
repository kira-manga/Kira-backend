package me.manga.kira.backend.user.infrastructure

import me.manga.kira.backend.user.domain.SecurityStateRepository
import org.springframework.stereotype.Repository

/**
 * Adapts [SpringDataSecurityStateRepository] to the domain [SecurityStateRepository] port. The
 * singleton row is seeded by V1, so [lockForAdminMutation] always finds it; a missing row is a
 * schema/migration defect worth failing loudly on (PLAN §5).
 */
@Repository
class JpaSecurityStateRepositoryAdapter(private val jpa: SpringDataSecurityStateRepository) : SecurityStateRepository {

    override fun lockForAdminMutation() {
        checkNotNull(jpa.lockSingletonRow()) {
            "security_state singleton row (id=1) is missing — V1 seed did not run"
        }
    }
}
