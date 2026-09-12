package me.manga.kira.backend.completion.application

import java.util.UUID

/** Quota/rate admission and pending reservations; execution requires explicit activation and owned cleanup. */
interface CompletionAdmission {
    fun acquire(userId: UUID): CompletionPermit
}

/**
 * Activation must acknowledge a live reservation before provider authorization. There is no default
 * success for an old or anonymous permit; duplicate activation cannot authorize another invocation.
 * Expected coordination failures during close are reported without replacing the caller's outcome.
 * Release is attempted at most once at the application level, not a wire-delivery guarantee.
 * This does not confirm remote release/worker termination or contain arbitrary programming/JVM errors.
 */
interface CompletionPermit : AutoCloseable {
    fun activate(): CompletionActivation

    override fun close()
}

enum class CompletionActivation {
    /** The exact pending reservation was acknowledged as pinned; startup authorization is still required. */
    ACTIVATED,

    /** No live pending reservation remains, including a permit already closed locally. */
    EXPIRED,

    /** Invalid, duplicate or indeterminate activation; never infer permission from an unknown reply. */
    UNAVAILABLE,
}
