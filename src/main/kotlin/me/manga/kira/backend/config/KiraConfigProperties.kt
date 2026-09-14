package me.manga.kira.backend.config

import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * `kira.config.*` — the document-revision two-floor model (PLAN §5 / §16.7, Open Q7). The startup
 * validators enforce these against the `seq_document_revision` sequence state and the publication
 * pointer at boot, fail-fast (PLAN §5 `StartupConsistencyIT`).
 *
 * **Two floors, exact comparisons (no ambiguous "exceeds"):**
 * - [bundledRevisionFloor] (default **6**) is the configured publication bound, aligned with the
 *   approved catalog-v2 App source bundle. Every *published* server revision must be **strictly `>`**
 *   it. The client requires `manifest.catalogRevision > bundled.revision` and enforces its durable
 *   accepted floor separately. The source default does not attest a shipped binary or installed config.
 * - [minimumServerRevision] (default **100**) = the smallest revision the backend may ever publish =
 *   the sequence seed. The sequence's next value must be **`>=`** it (inclusive — the very first
 *   generated value IS 100 and is legal).
 *
 * Startup asserts (PLAN §5): `minimumServerRevision > bundledRevisionFloor`; sequence-next `>=`
 * `minimumServerRevision`; and, when snapshots exist, sequence-next `>` the latest published revision.
 *
 * **Ops note (PLAN §5):** at production cutover — and at every app release that re-bundles — ops
 * re-verifies [bundledRevisionFloor] against the revision actually shipped in the live binary; never
 * treats the source default as installed-state evidence.
 */
@Validated
@ConfigurationProperties(prefix = "kira.config")
data class KiraConfigProperties(
    /** Configured bundled publication bound; verify against shipped app binaries. Server revisions must be `>` it. */
    @field:Positive
    val bundledRevisionFloor: Long = 6,
    /** Smallest revision the backend may publish (= the sequence seed); sequence-next must be `>=` it. */
    @field:Positive
    val minimumServerRevision: Long = 100,
)
