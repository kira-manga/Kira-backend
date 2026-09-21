package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.infrastructure.persistence.EpochRotationPersistence
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveFirstCutSuccessorInputV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveFirstSealStorageV1
import me.manga.kira.backend.complaint.infrastructure.terminal.VersionBoundTestOrdinarySealV1

/** Cold closed successor graph, present before D/PROJECT. It grants no current-state or retry authority. */
internal class VersionBoundTestActiveFirstCutSuccessorV1 private constructor(
    private val pools: VersionBoundPersistencePools,
    private val journal: TestOwnerDeleteJournalConfigurationV1,
    private val firstCut: VersionBoundTestActiveFirstCutV1,
    private val seal: VersionBoundTestOrdinarySealV1,
) {
    internal val resource: EpochRotationPersistence = firstCut.resource
    val totalAttemptMillis = firstCut.totalAttemptMillis
    val scopedLeaseMillis = firstCut.scopedLeaseMillis

    init { requireRetained(pools, journal, firstCut, seal) }

    internal fun requireRetained(selectedPools: VersionBoundPersistencePools, selectedJournal: TestOwnerDeleteJournalConfigurationV1,
        selectedFirstCut: VersionBoundTestActiveFirstCutV1?, selectedSeal: VersionBoundTestOrdinarySealV1?) {
        requireFirstCutSuccessor(pools === selectedPools && journal === selectedJournal && firstCut === selectedFirstCut && seal === selectedSeal)
        firstCut.requireRetained(pools, journal, seal) // Every original C cold-role and independent-price check is preserved.
        requireFirstCutSuccessor(resource === firstCut.resource && totalAttemptMillis == firstCut.totalAttemptMillis &&
            scopedLeaseMillis == firstCut.scopedLeaseMillis)
    }

    internal fun inventory(): JsonObject = buildJsonObject {
        put("schemaVersion", 1)
        put("profile", PROFILE)
        put("origin", "DISTINCT_ORIGINAL_ONLY_NO_OLD_CAPTURED_RECONSTRUCTION")
        put("scope", "ACTIVE_TEST_ALREADY_PAID_FIRST_RANGE_RESERVED_ONLY")
        put("maximumSlotsPerRun", TestActiveFirstSealStorageV1.MAX_SLOTS_PER_RUN)
        put("rotationSequence", 1)
        put("epochStart", 1)
        put("epochEnd", 1)
        put("epochAfter", 2)
        put("currentRead", "FULL_D_J_BIRTH_HISTORY_V17_V26_P22_THEN_DUAL_RAW_AND_RECHECK")
        put("lease", "FRESH_ABOVE_CURRENT_REQUEST_CAPTURE_NULL_OR_DB_EXPIRED_ONLY")
        put("scopedLeaseMillis", scopedLeaseMillis)
        put("requested", "SAME_ROOT_NONPOOLED_EXCLUSIVE_E_CAPTURE_NO_REQUEST_OR_CHARGE")
        put("captured", "FENCED_RECHECK_AND_EXACT_OWN_RELEASE_NO_V26_OR_EPOCH_WRITE")
        put("handoff", "PRIVATE_ONE_USE_RECOVERED_REQUIRES_FRESH_SEALER_AUTHORITY")
        put("success", "THIS_ORIGINAL_KNOWN_COMMIT_AND_ACTUAL_CLEANUP_ONLY")
        put("totalAttemptMillis", totalAttemptMillis)
        put("chargedStorageBytes", TestActiveFirstSealStorageV1.STORAGE_BYTES)
        put("counterDelta", 0)
        put("reserveDelta", 0)
        put("laterStates", "CANONICAL_AND_WIRE_REFUSED")
    }

    override fun toString(): String = "VersionBoundTestActiveFirstCutSuccessorV1(cold-reserved-only,no-current-authority)"

    companion object {
        const val PROFILE = "TEST_ACTIVE_FIRST_CUT_RESERVED_SUCCESSOR_V1"
        internal fun requireInput(input: TestActiveFirstCutSuccessorInputV1) = requireFirstCutSuccessor(
            input.schemaVersion == 1 && input.profile == PROFILE,
        )
        internal fun fromRetained(input: TestActiveFirstCutSuccessorInputV1, pools: VersionBoundPersistencePools,
            journal: TestOwnerDeleteJournalConfigurationV1, firstCut: VersionBoundTestActiveFirstCutV1,
            seal: VersionBoundTestOrdinarySealV1): VersionBoundTestActiveFirstCutSuccessorV1 {
            requireConnectionFree()
            requireInput(input)
            return VersionBoundTestActiveFirstCutSuccessorV1(pools, journal, firstCut, seal)
        }
    }
}
