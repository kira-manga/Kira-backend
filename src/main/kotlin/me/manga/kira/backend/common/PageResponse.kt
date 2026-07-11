package me.manga.kira.backend.common

/**
 * Uniform pagination envelope for every paginated endpoint (PLAN §4 / §4.5): `?page=0&size=20`
 * (size max 100, enforced at the controller boundary in later phases). Serialized by Jackson as an
 * API DTO — it never crosses into persistence.
 */
data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val total: Long,
)
