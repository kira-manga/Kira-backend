package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointDocumentV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentCheckpointDocumentV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1

/** Local, pre-D recipe. No new reader/native resources, cached assessment, lease or Completed authority. */
internal class VersionBoundTestInitialCheckpointCreateV1 private constructor(
    private val pools: VersionBoundPersistencePools,
    private val routing: TestOwnerDeleteJournalRoutingV1,
    private val checkpoint: VersionBoundTestActiveInitialCheckpointV1,
    private val profile: String,
    private val recurrent: VersionBoundTestActiveRecurrentV1?,
) {
    internal val recurrentCurrent: Boolean get() = profile == RECURRENT_PROFILE

    internal fun requireRetained(resources: VersionBoundPersistencePools, selected: TestOwnerDeleteJournalRoutingV1,
        scanner: VersionBoundTestActiveInitialCheckpointV1?, selectedRecurrent: VersionBoundTestActiveRecurrentV1? = null) {
        check(resources === pools && selected === routing && scanner === checkpoint)
        checkpoint.requireRetained(routing, pools, checkpoint.retention)
        if (recurrentCurrent) {
            check(selectedRecurrent != null && selectedRecurrent === recurrent)
            selectedRecurrent.requireRetained(routing, pools, checkpoint.retention)
        }
    }

    /** Pool-side pin comparison only; it cannot issue a registered request binding. */
    internal fun requirePool(resources: VersionBoundPersistencePools) = check(resources === pools)

    /** Separate born-with declaration; the old CREATE profile never acquires reply semantics. */
    internal fun requireReplies() = check(profile in setOf(REPLY_PROFILE, EDIT_PROFILE, RECURRENT_PROFILE))

    /** Explicit third born-with profile; neither older profile acquires EDIT. */
    internal fun requireEdits() = check(profile in setOf(EDIT_PROFILE, RECURRENT_PROFILE))

    internal fun inventory(): JsonObject {
        requireConnectionFree(); requireRetained(pools, routing, checkpoint, recurrent)
        return buildJsonObject {
            put("profile", profile); put("schemaVersion", 1)
            put("journalConfigurationSha256", routing.journalConfiguration.sha256)
            put("checkpointProfile", if (recurrentCurrent) TestActiveRecurrentCheckpointDocumentV1.PROFILE else TestActiveInitialCheckpointDocumentV1.PROFILE)
            if (recurrentCurrent) {
                put("initialCheckpointProfile", TestActiveInitialCheckpointDocumentV1.PROFILE)
                put("recurrentSourceProfile", TestActiveRecurrentStorageV1.PROFILE)
                put("checkpointSelection", "STRICT_INITIAL_OR_CURRENT_RECURRENT_IMMUTABLE_V26_V31_HISTORY")
            }
            put("checkpointMaximumAgeMillis", routing.journalConfiguration.declaration().limits.deadlines.checkpointMaxAgeMillis)
            put("provenance", "EXACT_REGISTERED_ASSEMBLY_ORDINARY_OWNER_TEMPLATE_INGRESS_AND_P")
            put("poolPolicy", when (profile) {
                RECURRENT_PROFILE -> "BORN_WITH_REGISTERED_CURRENT_RECURRENT_CREATE_REPLY_EDIT_NO_DESIRED_ONLY_BYPASS"
                EDIT_PROFILE -> "BORN_WITH_REGISTERED_CREATE_REPLY_EDIT_NO_DESIRED_ONLY_BYPASS"
                REPLY_PROFILE -> "BORN_WITH_REGISTERED_CREATE_REPLY_NO_DESIRED_ONLY_BYPASS"
                else -> "BORN_WITH_NO_DESIRED_ONLY_OR_REPLY_BYPASS"
            })
            put("newWork", when (profile) {
                RECURRENT_PROFILE -> "OWNER_CREATE_OWNER_REPLY_AND_OWNER_EDIT_STRICT_INITIAL_OR_RECURRENT_CAPTURED_FULL_CURRENT_CHECKPOINT"
                EDIT_PROFILE -> "OWNER_CREATE_OWNER_REPLY_AND_OWNER_EDIT_INITIAL_EPOCH2_CAPTURED_FULL_CURRENT_CHECKPOINT"
                REPLY_PROFILE -> "OWNER_CREATE_AND_OWNER_REPLY_INITIAL_EPOCH2_CAPTURED_FULL_CURRENT_CHECKPOINT"
                else -> "OWNER_CREATE_ONLY_INITIAL_EPOCH2_CAPTURED_FULL_CURRENT_CHECKPOINT"
            })
            put("locking", "ORIGINAL_M_SHARED_GLOBAL_SCOPE_BEFORE_COUNTERS_RUN_ACTOR")
            put("freshness", "DB_TIME_AFTER_WAITS_AND_BEFORE_COMPLETION_NO_RESTART")
            put("replay", "CURRENT_ACTOR_EXACT_TERMINAL_RECEIPT_BEFORE_CURRENT_CHECKPOINT_AND_CAPACITY")
            put("authority", "NO_HEALTH_ALIAS_NO_COMPLETED_NO_PROVIDER_NO_GATE_OPENING")
        }
    }

    override fun toString(): String = if (recurrentCurrent) "VersionBoundTestInitialCheckpointCreateV1(cold-recurrent-current,no-authority)"
        else "VersionBoundTestInitialCheckpointCreateV1(cold-initial-only,no-authority)"

    companion object {
        const val PROFILE = "TEST_REGISTERED_CURRENT_INITIAL_CHECKPOINT_OWNER_CREATE_V1"
        const val REPLY_PROFILE = "TEST_REGISTERED_CURRENT_INITIAL_CHECKPOINT_OWNER_CREATE_REPLY_V1"
        const val EDIT_PROFILE = "TEST_REGISTERED_CURRENT_INITIAL_CHECKPOINT_OWNER_CREATE_REPLY_EDIT_V1"
        const val RECURRENT_PROFILE = "TEST_REGISTERED_CURRENT_RECURRENT_CHECKPOINT_OWNER_CREATE_REPLY_EDIT_V1"

        internal fun requireInput(input: TestInitialCheckpointCreateInputV1) {
            check(input.schemaVersion == 1 && input.profile in setOf(PROFILE, REPLY_PROFILE, EDIT_PROFILE, RECURRENT_PROFILE))
        }

        fun fromIndependentInputs(input: TestInitialCheckpointCreateInputV1, pools: VersionBoundPersistencePools,
            routing: TestOwnerDeleteJournalRoutingV1, checkpoint: VersionBoundTestActiveInitialCheckpointV1,
            recurrent: VersionBoundTestActiveRecurrentV1? = null): VersionBoundTestInitialCheckpointCreateV1 {
            requireConnectionFree(); requireInput(input)
            val source = if (input.profile == RECURRENT_PROFILE) checkNotNull(recurrent) else null
            return VersionBoundTestInitialCheckpointCreateV1(pools, routing, checkpoint, input.profile, source)
                .also { it.requireRetained(pools, routing, checkpoint, recurrent) }
        }
    }
}
