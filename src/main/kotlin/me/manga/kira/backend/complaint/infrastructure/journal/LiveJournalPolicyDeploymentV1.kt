package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.JournalAuthoritiesV1
import me.manga.kira.backend.complaint.domain.JournalKmsKeyV1
import me.manga.kira.backend.complaint.domain.JournalRoutingKeyV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogS3ObjectVersionV1
import me.manga.kira.backend.complaint.domain.catalog.InitialJournalLocationV1
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1

/**
 * Independently supplied cold declarations, not installation, all-copy discovery or backup acceptance
 * evidence. This closed profile is logical bundles only: EVERY copy ages from its immutable SOURCE
 * restore point, including copies added later. No flag can assert completeness or effective policies.
 */
internal class LiveJournalPolicyDeploymentV1(
    val environment: String,
    copyPolicies: List<LiveJournalCopyPolicyV1>,
    val journalLock: LiveJournalLockPolicyV1,
    hmacKeys: List<LiveJournalHmacRetentionV1>,
    kmsKeys: List<LiveJournalKmsRetentionV1>,
    val acceptedRequestLateArrival: LiveJournalTimeBoundV1,
    val utcUncertainty: LiveJournalTimeBoundV1,
) {
    private val storedCopies = boundedCopy(copyPolicies, OfflineCatalogChainProtocol.MAX_MANIFEST_RECORDS).sortedWith(
        compareBy({ it.sourceKind }, { it.locationClass }, { it.accountId }, { it.region }, { it.bucket }, { it.prefix }),
    )
    private val storedHmac = boundedCopy(hmacKeys, 4).sortedBy { it.key.keyId }
    private val storedKms = boundedCopy(kmsKeys, 3).sortedBy { it.key.keyId }

    val copyPolicies: List<LiveJournalCopyPolicyV1> get() = storedCopies.toList()
    val hmacKeys: List<LiveJournalHmacRetentionV1> get() = storedHmac.toList()
    val kmsKeys: List<LiveJournalKmsRetentionV1> get() = storedKms.toList()

    init {
        validLivePolicy(OfflineBootstrapGrammar.referenceId(environment))
        storedCopies.forEach(::validateCopyPolicy)
        storedCopies.groupBy { listOf(it.sourceKind, it.locationClass, it.accountId, it.region, it.bucket) }.values.forEach { selectors ->
            // Sorting makes a nested or duplicate prefix adjacent to its earlier enclosing selector.
            selectors.zipWithNext().forEach { (left, right) -> validLivePolicy(!right.prefix.startsWith(left.prefix)) }
        }
        validLivePolicy(storedHmac.map { it.key.keyId }.distinct().size == storedHmac.size)
        validLivePolicy(storedKms.map { it.key.keyId }.distinct().size == storedKms.size)
        val references = storedCopies.map { it.policy } + storedHmac.map { it.policy } + storedKms.map { it.policy } +
            journalLock.policy + acceptedRequestLateArrival.policy + utcUncertainty.policy
        references.forEach(::validatePolicyReference)
        validLivePolicy(references.groupBy { it.policyId }.values.all { it.distinct().size == 1 })
        listOf(acceptedRequestLateArrival, utcUncertainty).forEach {
            validLivePolicy(OfflineBootstrapGrammar.referenceId(it.profileId) && it.maximumMillis in 0..MAX_BOUND_MILLIS)
        }
    }

    /** Called once before D is computed. Mutable facts, clocks, credentials and installed-policy claims are absent. */
    internal fun requireJournal(journal: ComplaintJournalConfigurationV1, reader: VersionBoundCatalogReadbackConfigurationV1) {
        val input = journal.declaration()
        validLivePolicy(environment == reader.chainPolicy.trustBundlePolicy.expectedEnvironment)
        validLivePolicy(storedCopies.size <= reader.chainPolicy.limits.maximumManifestRecords)
        validLivePolicy(
            journalLock.location == input.journalLocation && journalLock.authorities == input.authorities &&
                journalLock.ordinaryPrefix == OfflineBootstrapGrammar.ordinaryPrefix(input.writer.generationId) &&
                journalLock.sealTerminalPrefix == OfflineBootstrapGrammar.sealTerminalPrefix(input.writer.generationId),
        )
        validLivePolicy(storedHmac.map { it.key } == input.routing.keys.sortedBy { it.keyId })
        val requiredKms = listOf(input.encryption, input.recovery.queue.encryption, input.recovery.deadLetterQueue.encryption).distinct().sortedBy { it.keyId }
        validLivePolicy(storedKms.map { it.key } == requiredKms)
        val maximumAge = input.limits.retention.maximumRestoreAgeSeconds
        validLivePolicy(storedCopies.all { it.maximumAgeSeconds in 0..maximumAge })
        // The journal is in its independently administered account, outside the backup failure domain.
        validLivePolicy(storedCopies.none { it.accountId == input.journalLocation.accountId })
    }

    internal fun copyPolicyFor(sourceKind: String, locationClass: String, artifact: CatalogS3ObjectVersionV1): LiveJournalCopyPolicyV1? =
        storedCopies.singleOrNull {
            it.sourceKind == sourceKind && it.locationClass == locationClass && it.accountId == artifact.accountId &&
                it.region == artifact.region && it.bucket == artifact.bucket && artifact.key.startsWith(it.prefix)
        }

    override fun toString(): String = "LiveJournalPolicyDeploymentV1(cold-logical-only,redacted,no-installation-or-completeness-proof)"

    companion object {
        const val PROFILE = "ALL_LOGICAL_COPIES_SOURCE_RELATIVE_MAXIMUM_AGE_V1"
        const val KEY_AVAILABILITY = "WHILE_ANY_RETAINED_JOURNAL_VERSION_REQUIRES_KEY"
        private const val MAX_BOUND_MILLIS = CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND * 1000
        private val PREFIX = Regex("[A-Za-z0-9._/-]+/")
        private val CLASSES = setOf("PRIMARY", "REPLICA", "OPERATOR", "OFFSITE")

        private fun validateCopyPolicy(value: LiveJournalCopyPolicyV1) {
            validLivePolicy(value.sourceKind == CatalogLogicalInventoryProtocol.SOURCE_KIND && value.locationClass in CLASSES)
            validLivePolicy(
                OfflineBootstrapGrammar.account(
                    value.accountId,
                ) && OfflineBootstrapGrammar.region(value.region) && OfflineBootstrapGrammar.bucket(value.bucket),
            )
            validLivePolicy(value.prefix.length in 2..CatalogLogicalInventoryProtocol.MAX_S3_KEY_BYTES && PREFIX.matches(value.prefix))
            validLivePolicy(value.prefix.dropLast(1).split('/').none { it.isEmpty() || it == "." || it == ".." })
            validLivePolicy(value.maximumAgeSeconds in 0..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND)
        }

        private fun validatePolicyReference(value: InitialPolicyReferenceV1) {
            validLivePolicy(OfflineBootstrapGrammar.referenceId(value.policyId) && value.version > 0 && OfflineBootstrapGrammar.sha256(value.sha256))
        }

        private fun <T> boundedCopy(values: List<T>, maximum: Int): List<T> {
            validLivePolicy(values.size in 1..maximum)
            return values.toList().also { validLivePolicy(it.size in 1..maximum) }
        }
    }
}

/** The age rule is fixed by PROFILE; copy creation/registration never restarts it. */
internal data class LiveJournalCopyPolicyV1(
    val sourceKind: String,
    val locationClass: String,
    val accountId: String,
    val region: String,
    val bucket: String,
    val prefix: String,
    val policy: InitialPolicyReferenceV1,
    val maximumAgeSeconds: Long,
)

/** Exact independent J mapping: COMPLIANCE, all versions, no native expiration/deletion or role-policy widening. */
internal data class LiveJournalLockPolicyV1(
    val location: InitialJournalLocationV1,
    val ordinaryPrefix: String,
    val sealTerminalPrefix: String,
    val authorities: JournalAuthoritiesV1,
    val policy: InitialPolicyReferenceV1,
)

/** Required immutable secret versions stay available for every retained version, not just future writes. */
internal data class LiveJournalHmacRetentionV1(val key: JournalRoutingKeyV1, val policy: InitialPolicyReferenceV1)

/** Nonexportable KMS key identity, including its J policy; no backing-key-material version is invented. */
internal data class LiveJournalKmsRetentionV1(val key: JournalKmsKeyV1, val policy: InitialPolicyReferenceV1)

/** Independent native/UTC qualification references; an SDK timeout is not either of these guarantees. */
internal data class LiveJournalTimeBoundV1(val profileId: String, val policy: InitialPolicyReferenceV1, val maximumMillis: Long)

internal const val INVALID_LIVE_JOURNAL_POLICY = "Invalid retained LIVE journal policy configuration"

private fun validLivePolicy(condition: Boolean) = require(condition) { INVALID_LIVE_JOURNAL_POLICY }
