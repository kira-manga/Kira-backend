package me.manga.kira.backend.user.domain

import java.util.UUID

/**
 * User persistence **port** (PLAN §2 — domain declares the interface; infrastructure implements it
 * with a Spring Data adapter). Pure Kotlin, no framework types. Methods are narrow and
 * single-purpose (ISP): the application services depend only on what they use.
 *
 * Email matching is **case-insensitive** everywhere (the DB enforces it via `uq_users_email_lower`;
 * the application also normalizes to trim+lowercase before store — PLAN §5). Callers pass an
 * already-normalized email.
 */
interface UserRepository {

    /** Find by primary key, or null. Used by the per-request JWT auth converter (PLAN §6). */
    fun findById(id: UUID): User?

    /** Find by case-insensitive email, or null. */
    fun findByEmail(normalizedEmail: String): User?

    /** True when a user with this case-insensitive email already exists. */
    fun existsByEmail(normalizedEmail: String): Boolean

    /** True when at least one `ADMIN` row exists (enabled or not) — the seeder's guard (PLAN §6). */
    fun adminExists(): Boolean

    /** Count of currently-enabled `ADMIN`s — the last-admin guard's count (PLAN §4.4). */
    fun countEnabledAdmins(): Long

    /**
     * Insert a new user (id + timestamps assigned by the adapter). The caller supplies an already
     * policy-checked password **hash** (never a plaintext password). Returns the stored model.
     */
    fun create(
        normalizedEmail: String,
        passwordHash: String,
        role: Role,
    ): User

    /** Set the enabled flag. Idempotent. */
    fun setEnabled(
        id: UUID,
        enabled: Boolean,
    )

    /** Replace the password hash (explicit admin reset — PLAN §4.4). */
    fun updatePasswordHash(
        id: UUID,
        passwordHash: String,
    )

    /** Replace the role (used by tests and any future role-mutation surface). */
    fun updateRole(
        id: UUID,
        role: Role,
    )

    /** One page of users ordered by `created_at` ascending, plus the total count (PLAN §4.4). */
    fun findPage(
        page: Int,
        size: Int,
    ): PagedUsers
}

/** A single page of [User]s plus the total row count (PLAN §4.5 pagination). */
data class PagedUsers(
    val items: List<User>,
    val total: Long,
)
