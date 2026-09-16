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
 * - [bundledRevisionFloor] (default **5**) = the highest document revision shipped in the catalog-v2 app
 *   binary. Every *published* server revision must be **strictly `>`** it (equal would let two
 *   different documents share a revision number). The app's own acceptance rule is the *inclusive*
 *   `revision >= bundledDocument.revision`, so strictly-greater composes safely with it.
 * - [minimumServerRevision] (default **100**) = the smallest revision the backend may ever publish =
 *   the sequence seed. The sequence's next value must be **`>=`** it (inclusive — the very first
 *   generated value IS 100 and is legal).
 *
 * Startup asserts (PLAN §5): `minimumServerRevision > bundledRevisionFloor`; sequence-next `>=`
 * `minimumServerRevision`; and, when snapshots exist, sequence-next `>` the latest published revision.
 *
 * **Ops note (PLAN §5):** at production cutover — and at every app release that re-bundles — ops
 * re-verifies [bundledRevisionFloor] against the revision actually shipped in the live binary; never
 * relies forever on "bundled == 4".
 */
@Validated
@ConfigurationProperties(prefix = "kira.config")
data class KiraConfigProperties(
    /** Highest document revision shipped in a released app binary; published revisions must be `>` it. */
    @field:Positive
    val bundledRevisionFloor: Long = 5,
    /** Smallest revision the backend may publish (= the sequence seed); sequence-next must be `>=` it. */
    @field:Positive
    val minimumServerRevision: Long = 100,
)
