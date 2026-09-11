package me.manga.kira.backend.completion.application

import java.util.UUID

/** Quota/rate admission and logical concurrent permits; close does not prove physical provider termination. */
interface CompletionAdmission {
    fun acquire(userId: UUID): CompletionPermit
}

fun interface CompletionPermit : AutoCloseable {
    override fun close()
}
