package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointDocumentV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1

/** Local, pre-D recipe. No new reader/native resources, cached assessment, lease or Completed authority. */
internal class VersionBoundTestInitialCheckpointCreateV1 private constructor(
    private val pools: VersionBoundPersistencePools,
    private val routing: TestOwnerDeleteJournalRoutingV1,
    private val checkpoint: VersionBoundTestActiveInitialCheckpointV1,
    private val profile: String,
) {
    internal fun requireRetained(resources: VersionBoundPersistencePools, selected: TestOwnerDeleteJournalRoutingV1,
        scanner: VersionBoundTestActiveInitialCheckpointV1?) {
        check(resources === pools && selected === routing && scanner === checkpoint)
        checkpoint.requireRetained(routing, pools, checkpoint.retention)
    }

    /** Pool-side pin comparison only; it cannot issue a registered request binding. */
    internal fun requirePool(resources: VersionBoundPersistencePools) = check(resources === pools)

    /** Separate born-with declaration; the old CREATE profile never acquires reply semantics. */
    internal fun requireReplies() = check(profile == REPLY_PROFILE)

    internal fun inventory(): JsonObject {
        requireConnectionFree(); requireRetained(pools, routing, checkpoint)
        return buildJsonObject {
            put("profile", profile); put("schemaVersion", 1)
            put("journalConfigurationSha256", routing.journalConfiguration.sha256)
            put("checkpointProfile", TestActiveInitialCheckpointDocumentV1.PROFILE)
            put("checkpointMaximumAgeMillis", routing.journalConfiguration.declaration().limits.deadlines.checkpointMaxAgeMillis)
            put("provenance", "EXACT_REGISTERED_ASSEMBLY_ORDINARY_OWNER_TEMPLATE_INGRESS_AND_P")
            put("poolPolicy", if (profile == REPLY_PROFILE) "BORN_WITH_REGISTERED_CREATE_REPLY_NO_DESIRED_ONLY_BYPASS" else "BORN_WITH_NO_DESIRED_ONLY_OR_REPLY_BYPASS")
            put("newWork", if (profile == REPLY_PROFILE) "OWNER_CREATE_AND_OWNER_REPLY_INITIAL_EPOCH2_CAPTURED_FULL_CURRENT_CHECKPOINT" else "OWNER_CREATE_ONLY_INITIAL_EPOCH2_CAPTURED_FULL_CURRENT_CHECKPOINT")
            put("locking", "ORIGINAL_M_SHARED_GLOBAL_SCOPE_BEFORE_COUNTERS_RUN_ACTOR")
            put("freshness", "DB_TIME_AFTER_WAITS_AND_BEFORE_COMPLETION_NO_RESTART")
            put("replay", "CURRENT_ACTOR_EXACT_TERMINAL_RECEIPT_BEFORE_CURRENT_CHECKPOINT_AND_CAPACITY")
            put("authority", "NO_HEALTH_ALIAS_NO_COMPLETED_NO_PROVIDER_NO_GATE_OPENING")
        }
    }

    override fun toString(): String = "VersionBoundTestInitialCheckpointCreateV1(cold-initial-only,no-authority)"

    companion object {
        const val PROFILE = "TEST_REGISTERED_CURRENT_INITIAL_CHECKPOINT_OWNER_CREATE_V1"
        const val REPLY_PROFILE = "TEST_REGISTERED_CURRENT_INITIAL_CHECKPOINT_OWNER_CREATE_REPLY_V1"

        internal fun requireInput(input: TestInitialCheckpointCreateInputV1) {
            check(input.schemaVersion == 1 && input.profile in setOf(PROFILE, REPLY_PROFILE))
        }

        fun fromIndependentInputs(input: TestInitialCheckpointCreateInputV1, pools: VersionBoundPersistencePools,
            routing: TestOwnerDeleteJournalRoutingV1, checkpoint: VersionBoundTestActiveInitialCheckpointV1): VersionBoundTestInitialCheckpointCreateV1 {
            requireConnectionFree(); requireInput(input)
            return VersionBoundTestInitialCheckpointCreateV1(pools, routing, checkpoint, input.profile).also { it.requireRetained(pools, routing, checkpoint) }
        }
    }
}
