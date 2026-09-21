package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointDeletionInputV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1

/** Pre-D declaration and resource pins. Only the private registered process binding issues requests. */
internal class VersionBoundTestInitialCheckpointDeletionV1 private constructor(
    private val pools: VersionBoundPersistencePools,
    private val routing: TestOwnerDeleteJournalRoutingV1,
    private val checkpoint: VersionBoundTestActiveInitialCheckpointV1,
    private val publication: VersionBoundTestActiveCutoffPublicationV1,
) {
    internal fun requireRetained(resources: VersionBoundPersistencePools, selected: TestOwnerDeleteJournalRoutingV1,
        scanner: VersionBoundTestActiveInitialCheckpointV1?, ordinary: VersionBoundTestActiveCutoffPublicationV1?) {
        check(resources === pools && selected === routing && scanner === checkpoint && ordinary === publication)
        check(routing.journalConfiguration.registeredAdminBatchDelete && routing.journalConfiguration.ownerDeleteAll)
        checkpoint.requireRetained(routing, pools, checkpoint.retention)
    }

    internal fun requirePool(resources: VersionBoundPersistencePools) = check(resources === pools)

    internal fun inventory(): JsonObject {
        requireConnectionFree(); requireRetained(pools, routing, checkpoint, publication)
        return buildJsonObject {
            put("profile", PROFILE); put("schemaVersion", 1)
            put("journalConfigurationSha256", routing.journalConfiguration.sha256)
            put("checkpointMaximumAgeMillis", routing.journalConfiguration.declaration().limits.deadlines.checkpointMaxAgeMillis)
            put("origin", "EXACT_RELEASED_INITIAL_REGISTRATION_ASSEMBLY_OWNERS_TEMPLATES_INGRESS_AND_P")
            put("newWork", "ONE_FIRST_EPOCH2_OWNER_DELETE_OWNER_DELETE_ALL_ADMIN_DELETE_OR_ADMIN_BATCH_DELETE")
            put("poolPolicy", "BORN_WITH_PRESENCE_AND_ABSENCE_NO_LOWER_OR_RECOVERY_AUTH")
            put("locking", "ORIGINAL_M_SHARED_E_GLOBAL_SCOPE_RECEIPT_GRANT_COUNTER_RUN_ACTOR_TARGET")
            put("checkpoint", "CURRENT_FULL_INITIAL_CHECKPOINT_GLOBAL_AND_SCOPE_SCAN_GATES_NO_SYNTHETIC_PROOF")
            put("replay", "CURRENT_REGISTERED_IDENTITY_AND_ACTOR_BEFORE_EXACT_RECEIPT_NO_FRESHNESS_OR_NEW_CHARGE")
            put("publication", "EXACT_PRE_D_ORDINARY_RECIPE_RESERVED_SHARED_NATIVE_LANE_AND_REAL_STS")
            put("verification", "EXACT_RELEASED_PREPARED_WORK_AND_NATIVE_READBACK_NO_DIRECT_REQUEST_APPLY")
        }
    }

    override fun toString(): String = "VersionBoundTestInitialCheckpointDeletionV1(cold-initial-only,no-authority)"

    companion object {
        const val PROFILE = "TEST_REGISTERED_CURRENT_INITIAL_CHECKPOINT_FIRST_DELETION_V1"
        internal fun requireInput(input: TestInitialCheckpointDeletionInputV1) {
            check(input.schemaVersion == 1 && input.profile == PROFILE)
        }
        fun fromIndependentInputs(input: TestInitialCheckpointDeletionInputV1, pools: VersionBoundPersistencePools,
            routing: TestOwnerDeleteJournalRoutingV1, checkpoint: VersionBoundTestActiveInitialCheckpointV1,
            publication: VersionBoundTestActiveCutoffPublicationV1): VersionBoundTestInitialCheckpointDeletionV1 {
            requireConnectionFree(); requireInput(input)
            return VersionBoundTestInitialCheckpointDeletionV1(pools, routing, checkpoint, publication).also {
                it.requireRetained(pools, routing, checkpoint, publication)
            }
        }
    }
}
