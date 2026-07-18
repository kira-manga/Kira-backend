package me.manga.kira.backend.sourceconfig.infrastructure

import me.manga.kira.backend.config.KiraConfigProperties
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Startup fail-fast validator for the document-revision **two-floor model** (PLAN §5 / §16.7). Runs at
 * boot via [StartupConsistencyRunner]; on any violation it throws (the app refuses to start — readiness
 * stays red — and the message names the recovery runbook; PLAN §5 never silently auto-repairs).
 *
 * Exact comparisons (no ambiguous "exceeds"):
 *  1. `minimumServerRevision > bundledRevisionFloor` — else two documents could share a revision number.
 *  2. sequence-next `>=` `minimumServerRevision` (inclusive — the first generated value IS the seed).
 *  3. when snapshots exist, sequence-next `>` the latest published revision (`MAX(document_revision)`).
 *
 * Sequence state is read from `pg_sequences` (a NULL `last_value` ⇒ next = START value; PLAN §5).
 * Constructed with its [config] so tests can exercise misconfigured floors against a real DB.
 */
@Component
class RevisionFloorStartupValidator(private val config: KiraConfigProperties, private val publishedDocuments: PublishedDocumentRepository) {
    /** Throws [IllegalStateException] with the recovery-runbook message on any floor violation (PLAN §5). */
    fun validate() {
        // 1) Property invariant — independent of DB state; fails fast even on a fresh install.
        check(config.minimumServerRevision > config.bundledRevisionFloor) {
            runbook(
                "kira.config.minimum-server-revision (${config.minimumServerRevision}) must be strictly " +
                    "greater than kira.config.bundled-revision-floor (${config.bundledRevisionFloor}).",
            )
        }

        val sequenceNext = publishedDocuments.sequenceNextValue()

        // 2) The sequence may never hand out a value below the server minimum.
        check(sequenceNext >= config.minimumServerRevision) {
            runbook(
                "seq_document_revision next value ($sequenceNext) is below " +
                    "kira.config.minimum-server-revision (${config.minimumServerRevision}).",
            )
        }

        // 3) When snapshots exist, the next revision must be strictly above the latest published one.
        val latest = publishedDocuments.maxDocumentRevision()
        if (latest != null) {
            check(sequenceNext > latest) {
                runbook(
                    "seq_document_revision next value ($sequenceNext) is not greater than the latest " +
                        "published document revision ($latest) — a future publish would collide or rewind.",
                )
            }
        }

        log.info(
            "Revision-floor startup check OK: bundledFloor={} minServer={} sequenceNext={} latestPublished={}",
            config.bundledRevisionFloor,
            config.minimumServerRevision,
            sequenceNext,
            latest,
        )
    }

    private fun runbook(problem: String): String = "Revision-floor consistency check FAILED: $problem " +
        "The backend refuses to start; this is never auto-repaired. See the recovery runbook in " +
        "docs/SOURCE_CONFIG_LIFECYCLE.md (inspect published_documents / the publication pointer / the " +
        "sequence, repair with a single audited SQL statement, record it in audit_log, then restart)."

    private companion object {
        val log = LoggerFactory.getLogger(RevisionFloorStartupValidator::class.java)
    }
}
