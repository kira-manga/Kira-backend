package me.manga.kira.backend.common

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * The uniform problem envelope (PLAN §2 / §4) — RFC-9457 (`application/problem+json`) style:
 * `type`, `title`, `status`, `detail`, plus the `errors[]` extension member for field-level
 * pinpoints. Produced by [GlobalExceptionHandler] and pre-MVC request filters; serialized by Jackson.
 *
 * `NON_EMPTY` inclusion drops `null`/empty members so an error with no field details is a clean
 * `{type,title,status,detail}` and one with details adds `errors[]`. Error responses **never echo
 * submitted config/header/password values verbatim** (PLAN §4.5 / §6 log-hygiene).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class ApiError(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String? = null,
    val errors: List<ApiFieldError> = emptyList(),
)

/**
 * One field-level pinpoint inside [ApiError.errors]. `code` is a stable machine identifier (e.g.
 * `NOT_FOUND`, `VALIDATION_FAILED`, `INVALID_LIFECYCLE_TRANSITION`, or a §8 validation code); `path`
 * is the offending location when applicable (e.g. `sources[Azora].filters[genres].request.encode`).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class ApiFieldError(
    val code: String,
    val path: String? = null,
    val message: String,
)
