package me.manga.kira.backend.completion.application

import java.util.UUID

/** Atomic quota, rate, and global-concurrency admission shared by completion orchestration. */
interface CompletionAdmission {
    fun acquire(userId: UUID): CompletionPermit
}

fun interface CompletionPermit : AutoCloseable {
    override fun close()
}
