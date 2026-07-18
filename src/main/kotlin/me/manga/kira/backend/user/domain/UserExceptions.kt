package me.manga.kira.backend.user.domain

import me.manga.kira.backend.common.exception.ConflictException
import me.manga.kira.backend.common.exception.NotFoundException

/*
 * User-domain typed exceptions (PLAN §2 — domain throws typed errors; the common
 * `GlobalExceptionHandler` maps them to the problem envelope). Stable `code`s let clients/tests
 * key on the machine identifier rather than parse prose.
 */

/** 409 — email already registered (case-insensitive). Never echoes the submitted email (§6). */
class DuplicateEmailException : ConflictException("Email is already registered.", code = "EMAIL_ALREADY_EXISTS")

/** 404 — no user with the given id. */
class UserNotFoundException : NotFoundException("User not found.", code = "USER_NOT_FOUND")

/**
 * 409 — the requested change would disable the last enabled ADMIN (PLAN §4.4 last-admin guard).
 * Enforced under the `security_state` row lock so it holds under concurrency.
 */
class LastAdminException : ConflictException("Cannot disable the last enabled admin.", code = "LAST_ADMIN")
