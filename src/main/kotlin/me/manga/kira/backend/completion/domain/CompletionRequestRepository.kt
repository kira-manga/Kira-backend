package me.manga.kira.backend.completion.domain

import java.time.Instant
import java.util.UUID

/**
 * Port for the `completion_requests` table (PLAN §2 DIP / §10). The application-layer persistence
 * component drives these inside its short transactions; timestamps are supplied by the caller (from
 * the injected Clock) so application and DB time never diverge.
 */
interface CompletionRequestRepository {
    /** Insert a new `PENDING` row and return its generated id. */
    fun insertPending(userId: UUID, provider: String, model: String, prompt: String, now: Instant): UUID

    /** Claim PENDING → RUNNING; a lost claim changes nothing. */
    fun tryMarkRunning(id: UUID, now: Instant): Boolean

    /** RUNNING → SUCCEEDED or PENDING/RUNNING → FAILED. Terminal rows never change. */
    fun tryFinish(id: UUID, status: CompletionStatus, now: Instant): Boolean

    fun findById(id: UUID): CompletionRequestRecord?

    /** Caller's own requests, newest first (the `idx_completions_user` order, PLAN §4.6). */
    fun findPageByUser(userId: UUID, page: Int, size: Int): CompletionRequestPage
}
