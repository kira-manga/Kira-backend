package me.manga.kira.backend.completion.application

import java.util.UUID

/** Atomic quota, rate, and global-concurrency admission shared by completion orchestration. */
interface CompletionAdmission {
    fun acquire(userId: UUID): CompletionPermit
}

/**
 * Expected coordination failures during close are reported without replacing the caller's outcome.
 * Release is attempted at most once at the application level, not a wire-delivery guarantee.
 * This does not confirm remote release/worker termination or contain arbitrary programming/JVM errors.
 */
fun interface CompletionPermit : AutoCloseable {
    override fun close()
}
