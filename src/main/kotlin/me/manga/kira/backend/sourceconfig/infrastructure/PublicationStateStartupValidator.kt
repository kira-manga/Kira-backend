package me.manga.kira.backend.sourceconfig.infrastructure

import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Startup fail-fast validator for the `document_publication_state` pointer coherence (PLAN §5). Runs at
 * boot via [StartupConsistencyRunner]; any inconsistency throws (readiness stays red; never
 * auto-repaired — the message names the recovery runbook).
 *
 * Checks (PLAN §5):
 *  - (a) pointer NULL ⇒ zero snapshot rows exist (fresh install).
 *  - (b) pointer non-NULL ⇒ a snapshot exists AND the pointer equals `MAX(document_revision)` — no
 *        snapshot may sit above the pointer (the FK already guarantees the referenced row exists).
 *  - (c) the sequence's next value `>` the pointer (a future publish must not collide or rewind).
 */
@Component
class PublicationStateStartupValidator(private val publishedDocuments: PublishedDocumentRepository) {
    /** Throws [IllegalStateException] with the recovery-runbook message on any inconsistency (PLAN §5). */
    fun validate() {
        val pointer = publishedDocuments.latestPointer()
        val count = publishedDocuments.snapshotCount()
        val max = publishedDocuments.maxDocumentRevision()

        if (pointer == null) {
            // (a) A NULL pointer is only consistent with a fresh install (no snapshots).
            check(count == 0L) {
                runbook(
                    "the latest-document pointer is NULL but $count published snapshot(s) exist " +
                        "(max revision $max).",
                )
            }
            log.info("Publication-state startup check OK: fresh install (pointer NULL, no snapshots).")
            return
        }

        // (b) A non-NULL pointer must be the highest snapshot — nothing may sit above it.
        check(max != null && pointer == max) {
            runbook(
                "the latest-document pointer ($pointer) does not equal MAX(document_revision) ($max) — " +
                    "a snapshot sits above the pointer or the pointer is stale.",
            )
        }

        // (c) The sequence must never be poised to re-hand-out (or rewind below) the current latest.
        val sequenceNext = publishedDocuments.sequenceNextValue()
        check(sequenceNext > pointer) {
            runbook(
                "seq_document_revision next value ($sequenceNext) is not greater than the latest-document " +
                    "pointer ($pointer).",
            )
        }

        log.info(
            "Publication-state startup check OK: pointer={} snapshots={} sequenceNext={}",
            pointer,
            count,
            sequenceNext,
        )
    }

    private fun runbook(problem: String): String = "Publication-state consistency check FAILED: $problem " +
        "The backend refuses to start; this is never auto-repaired. See the recovery runbook in " +
        "docs/SOURCE_CONFIG_LIFECYCLE.md (inspect published_documents vs document_publication_state, " +
        "decide the true latest, repair with a single audited SQL UPDATE, record it in audit_log, restart)."

    private companion object {
        val log = LoggerFactory.getLogger(PublicationStateStartupValidator::class.java)
    }
}
