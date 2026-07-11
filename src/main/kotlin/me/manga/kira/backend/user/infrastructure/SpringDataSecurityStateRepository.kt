package me.manga.kira.backend.user.infrastructure

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

/**
 * Spring Data repository for the `security_state` singleton. [lockSingletonRow] issues
 * `SELECT … WHERE id = 1 FOR UPDATE` ([LockModeType.PESSIMISTIC_WRITE]) so concurrent admin
 * mutations serialize (PLAN §4.4 / §5). Must be called inside a transaction.
 */
interface SpringDataSecurityStateRepository : JpaRepository<SecurityStateEntity, Int> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SecurityStateEntity s WHERE s.id = 1")
    fun lockSingletonRow(): SecurityStateEntity?
}
