package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.infrastructure.persistence.EpochRotationPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceComplaintMaintenanceFenceV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipantRole
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveFirstCutInputV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveFirstSealStorageV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintEffectiveConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.VersionBoundTestOrdinarySealV1

/** The real same-root native resource and separate seal acquisition retained before D. No current-use authority. */
internal class VersionBoundTestActiveFirstCutV1 private constructor(
    private val pools: VersionBoundPersistencePools,
    private val journal: TestOwnerDeleteJournalConfigurationV1,
    private val seal: VersionBoundTestOrdinarySealV1,
) {
    internal val resource: EpochRotationPersistence = checkNotNull(pools.epochRotation)
    private val descriptor = resource.descriptor()
    val totalAttemptMillis = journal.declaration().limits.deadlines.epochRotationMillis.toLong()
    val profile = TestActiveFirstSealStorageV1.PROFILE
    val schemaVersion = TestActiveFirstSealStorageV1.SCHEMA_VERSION
    val scopedLeaseMillis = 30_000L

    init {
        requireRetained(pools, journal, seal)
        val ordinary = pools.descriptors().first()
        requireFirstCut(descriptor.authenticationPassword === ordinary.authenticationPassword &&
            descriptor.publicTrustSha256 == ordinary.publicTrustSha256 && descriptor.publicTrustByteCount == ordinary.publicTrustByteCount &&
            descriptor.publicTrustCertificateCount == ordinary.publicTrustCertificateCount)
    }

    internal fun requireRetained(selectedPools: VersionBoundPersistencePools, selectedJournal: TestOwnerDeleteJournalConfigurationV1,
        selectedSeal: VersionBoundTestOrdinarySealV1?) {
        requireFirstCut(pools === selectedPools && journal === selectedJournal && seal === selectedSeal &&
            journal.registeredAdminBatchDelete && journal.ownerDeleteAll && journal.registeredAdminDelete &&
            pools.epochRotation === resource && resource.belongsTo(pools) && resource.descriptor() === descriptor &&
            descriptor.role === PersistenceJdbcParticipantRole.EPOCH_ROTATION && descriptor.capacity == 1 && !descriptor.pooled &&
            totalAttemptMillis in 1..descriptor.maximumRotationMillis)
        resource.requireUnchangedConfiguration()
    }

    internal fun inventory(): JsonObject = buildJsonObject {
        put("schemaVersion", schemaVersion)
        put("profile", profile)
        put("ordinarySealOwner", "RETAINED_INDEPENDENT_NATIVE_TEST_SEAL")
        put("scope", "ACTIVE_TEST_FIRST_RANGE_ONLY")
        put("maximumSlotsPerRun", TestActiveFirstSealStorageV1.MAX_SLOTS_PER_RUN)
        put("rotationSequence", 1)
        put("epochStart", 1)
        put("epochEnd", 1)
        put("epochAfter", 2)
        put("currentRawRead", "DUAL_SCHEMA3_THEN_FULL_D_RUN_HISTORY_RECHECK")
        put("scopedLeaseMillis", scopedLeaseMillis)
        put("captureLeaseHandoff", "EXACT_CURRENT_OWNER_EXPIRY_CLEAR_TOKEN_PRESERVED")
        put("success", "ORIGINAL_KNOWN_COMMIT_AND_ACTUAL_NATIVE_CLEANUP")
        put("sealEncodingSha256", TestActiveFirstSealStorageV1.sealEncodingSha256)
        put("storage", buildJsonObject {
            put("counter", "STORAGE_BYTES")
            put("chargedBytes", TestActiveFirstSealStorageV1.STORAGE_BYTES)
            put("source", "ORDINARY_FREE_TO_ACTUAL")
            put("reserveDelta", 0)
            put("maximumCanonicalBytes", TestActiveFirstSealStorageV1.MAX_CANONICAL_BYTES)
            put("maximumWireBytes", TestActiveFirstSealStorageV1.MAX_WIRE_BYTES)
            put("maximumMetadataBytes", TestActiveFirstSealStorageV1.MAX_METADATA_BYTES)
            put("maximumObjectKeyBytes", TestActiveFirstSealStorageV1.MAX_OBJECT_KEY_BYTES)
            put("maximumRoutingKeyIdBytes", TestActiveFirstSealStorageV1.MAX_ROUTING_KEY_ID_BYTES)
            put("objectIdBytes", TestActiveFirstSealStorageV1.OBJECT_ID_BYTES)
            put("logicalStorageEnvelopeBytes", TestActiveFirstSealStorageV1.LOGICAL_ENVELOPE_BYTES)
        })
        put("nonpooledCapture", buildJsonObject {
            put("role", descriptor.role.name)
            put("capacity", descriptor.capacity)
            put("pooled", descriptor.pooled)
            put("sessionPolicy", descriptor.sessionPolicy)
            put("protocolVersion", descriptor.protocolVersion)
            put("maximumRotationMillis", descriptor.maximumRotationMillis)
            put("effectiveRotationMillis", totalAttemptMillis)
            put("requestPhaseMillis", descriptor.requestPhaseMillis)
            put("statementMillis", descriptor.statementMillis)
            put("controlLockMillis", descriptor.controlLockMillis)
            put("maintenancePrefixMillis", PersistenceComplaintMaintenanceFenceV1.PREFIX_MILLIS)
            put("maintenanceDispatchMillis", PersistenceComplaintMaintenanceFenceV1.DISPATCH_MILLIS)
            put("authenticationPassword", ComplaintEffectiveConfigurationV1.secret(descriptor.authenticationPassword))
            put("publicTrust", buildJsonObject {
                put("sha256", descriptor.publicTrustSha256)
                put("byteCount", descriptor.publicTrustByteCount)
                put("certificateCount", descriptor.publicTrustCertificateCount)
            })
            val opening = descriptor.opening
            put("opening", buildJsonObject {
                put("recipe", opening.policy.recipe.name)
                put("evidencePolicy", opening.policy.evidence.name)
                put("transportRoute", opening.policy.route.name)
                put("driverUrl", opening.driverUrl)
                put("loginBudgetMillis", opening.loginBudgetMillis)
                put("publicDriverProperties", JsonObject(opening.publicDriverProperties().mapValues { JsonPrimitive(it.value) }))
            })
        })
    }

    override fun toString(): String = "VersionBoundTestActiveFirstCutV1(cold-first-cut-and-paid-slot,no-authority)"

    companion object {
        internal fun requireInput(input: TestActiveFirstCutInputV1) = requireFirstCut(
            input.schemaVersion == TestActiveFirstSealStorageV1.SCHEMA_VERSION && input.profile == TestActiveFirstSealStorageV1.PROFILE,
        )
        internal fun fromRetained(input: TestActiveFirstCutInputV1, pools: VersionBoundPersistencePools,
            journal: TestOwnerDeleteJournalConfigurationV1, seal: VersionBoundTestOrdinarySealV1): VersionBoundTestActiveFirstCutV1 {
            requireConnectionFree()
            requireInput(input)
            return VersionBoundTestActiveFirstCutV1(pools, journal, seal)
        }
    }
}
