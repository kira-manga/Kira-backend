package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveOrdinarySealRecoveryInputV1
import me.manga.kira.backend.complaint.infrastructure.terminal.VersionBoundTestOrdinarySealV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1

/** Independently optional pre-D recipe. Owns no new provider material, capacity or persistence resource. */
internal class VersionBoundTestActiveOrdinarySealRecoveryV1 private constructor(
    private val pools: VersionBoundPersistencePools, private val routing: TestOwnerDeleteJournalRoutingV1,
    private val firstCut: VersionBoundTestActiveFirstCutV1, private val seal: VersionBoundTestOrdinarySealV1,
) {
    init { requireRetained(pools, routing, firstCut, seal) }
    internal fun requireRetained(selectedPools: VersionBoundPersistencePools, selectedRouting: TestOwnerDeleteJournalRoutingV1,
        selectedFirstCut: VersionBoundTestActiveFirstCutV1?, selectedSeal: VersionBoundTestOrdinarySealV1?) {
        requireActiveSealRecovery(pools === selectedPools && routing === selectedRouting && firstCut === selectedFirstCut && seal === selectedSeal &&
            routing.journalConfiguration.registeredAdminBatchDelete)
        firstCut.requireRetained(pools, routing.journalConfiguration, seal)
    }
    internal fun inventory() = buildJsonObject {
        requireConnectionFree(); requireRetained(pools, routing, firstCut, seal)
        put("schemaVersion", 1); put("profile", PROFILE)
        put("journalConfigurationSha256", routing.journalConfiguration.sha256)
        put("states", "CAPTURED_FIRST_RANGE_SEAL_PREPARED_CANONICAL_OR_WIRE_FROZEN")
        put("closure", "EMPTY_ONLY_UNFILTERED_LOCAL_PASSES_NO_RESOLVER")
        put("current", "FULL_D_P22_RUN_HISTORY_DUAL_RAW_NONWAITING_CURRENT_PLUS_ONE_LEASE")
        put("historical", "IMMUTABLE_PREPARING_TOKEN_CANONICAL_RETAINED_ROUTE_AND_FROZEN_WINNER")
        put("wire", "GENERATE_ONLY_IF_CANONICAL_THEN_CAS_RELOAD_KNOWN_RELEASE")
        put("native", "EXISTING_EXACT_LIST_OPTIONAL_ONE_PUT_GET_AEAD_ACTUAL_CLOSE_BEFORE_VERIFY")
        put("retention", "IMMUTABLE_FROZEN_REQUEST_MISSING_OBJECT_MAY_REFUSE_NO_REPAIR")
        put("totalAttemptMillis", routing.journalConfiguration.declaration().limits.deadlines.epochSealMillis)
        put("phaseMillis", 2_000); put("leaseMillis", 30_000); put("renewalWindowMillis", 10_000)
        put("newCharge", 0); put("success", "HISTORICAL_ONLY_NO_A_RESULT_CHECKPOINT_HEALTH_OR_TERMINAL")
    }
    override fun toString(): String = "VersionBoundTestActiveOrdinarySealRecoveryV1(pre-D-empty-only,redacted)"
    companion object {
        const val PROFILE = "TEST_ACTIVE_INITIAL_EMPTY_PREVERIFY_SEAL_RECOVERY_V1"
        internal fun requireInput(input: TestActiveOrdinarySealRecoveryInputV1) =
            requireActiveSealRecovery(input.schemaVersion == 1 && input.profile == PROFILE)
        internal fun fromRetained(input: TestActiveOrdinarySealRecoveryInputV1, pools: VersionBoundPersistencePools,
            routing: TestOwnerDeleteJournalRoutingV1, firstCut: VersionBoundTestActiveFirstCutV1, seal: VersionBoundTestOrdinarySealV1): VersionBoundTestActiveOrdinarySealRecoveryV1 {
            requireConnectionFree(); requireInput(input)
            return VersionBoundTestActiveOrdinarySealRecoveryV1(pools, routing, firstCut, seal)
        }
    }
}
