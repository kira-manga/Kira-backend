package me.manga.kira.backend.user.domain

/**
 * Port over the `security_state` singleton row (PLAN §5, §4.4). Locking this row `FOR UPDATE`
 * **before** counting enabled admins serializes concurrent admin-account mutations, so the
 * last-admin guard cannot be defeated by a race under READ COMMITTED (two transactions each
 * observing "2 enabled admins" and disabling different ones → zero remain).
 *
 * Must be called inside an active transaction — the lock is held until that transaction commits.
 */
interface SecurityStateRepository {

    /** `SELECT … FROM security_state WHERE id = 1 FOR UPDATE` — serializes admin mutations (PLAN §4.4). */
    fun lockForAdminMutation()
}
