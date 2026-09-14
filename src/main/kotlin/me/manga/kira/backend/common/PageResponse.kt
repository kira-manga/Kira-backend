package me.manga.kira.backend.common

/**
 * Offset pagination envelope (PLAN §4 / §4.5): `?page=0&size=20`
 * (size max 100, enforced at the controller boundary in later phases). Serialized by Jackson as an
 * API DTO — it never crosses into persistence. Admin source/document histories instead retain their
 * array bodies and use bounded revision-keyset windows with a continuation header.
 */
data class PageResponse<T>(val items: List<T>, val page: Int, val size: Int, val total: Long)
