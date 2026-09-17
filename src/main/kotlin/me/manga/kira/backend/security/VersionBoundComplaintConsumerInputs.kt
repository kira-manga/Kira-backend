package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.config.KiraSecurityProperties

/** Acquisition inputs only. The concrete journal owner is mandatory; no substitute routing-key list. */
internal class VersionBoundComplaintConsumerInputs(
    val admissionCurrent: AcquiredVersionedSecret,
    val admissionPrevious: AcquiredVersionedSecret?,
    val cursorActiveKeyId: String,
    cursorSecrets: List<AcquiredVersionedSecret>,
    val journalRouting: VersionBoundComplaintJournalRouting,
) {
    private val cursors = snapshotCursors(cursorSecrets)

    init {
        val admissions = admissionSecrets()
        admissions.forEach { requireFamily(it, SecretMaterialFamily.COMPLAINT_ADMISSION) }
        require(admissions.map { it.descriptor.logicalKeyId }.distinct().size == admissions.size) { INVALID_BOUND_COMPLAINT_CONSUMERS }
        require(cursors.map { it.descriptor.logicalKeyId }.distinct().size == cursors.size) { INVALID_BOUND_COMPLAINT_CONSUMERS }
        require(cursors.any { it.descriptor.logicalKeyId == cursorActiveKeyId }) { INVALID_BOUND_COMPLAINT_CONSUMERS }
    }

    internal fun admissionSecrets(): List<AcquiredVersionedSecret> = listOfNotNull(admissionCurrent, admissionPrevious)

    internal fun cursorSecrets(): List<AcquiredVersionedSecret> = cursors.toList()

    override fun toString(): String = "VersionBoundComplaintConsumerInputs(redacted,no-authority)"

    private companion object {
        fun snapshotCursors(secrets: List<AcquiredVersionedSecret>): List<AcquiredVersionedSecret> {
            val count = secrets.size
            require(count in 1..8) { INVALID_BOUND_COMPLAINT_CONSUMERS }
            val iterator = secrets.iterator()
            val result = ArrayList<AcquiredVersionedSecret>(count)
            repeat(count) {
                require(iterator.hasNext()) { INVALID_BOUND_COMPLAINT_CONSUMERS }
                val acquired = iterator.next()
                requireFamily(acquired, SecretMaterialFamily.COMPLAINT_CURSOR)
                result.add(acquired)
            }
            require(!iterator.hasNext()) { INVALID_BOUND_COMPLAINT_CONSUMERS }
            return result.sortedBy { it.descriptor.logicalKeyId }
        }

        fun requireFamily(acquired: AcquiredVersionedSecret, family: SecretMaterialFamily) {
            require(acquired.descriptor.family == family && acquired.descriptor.purpose == SecretMaterialPurpose.HMAC_SHA256) {
                INVALID_BOUND_COMPLAINT_CONSUMERS
            }
        }
    }
}

/** Explicit finite consumer settings, not P, J or a serialized fragment of D. No quota or key defaults. */
internal class VersionBoundComplaintConsumerSettings(
    val coordinationMode: String,
    val declaredInstances: Int,
    val concurrentLimit: Int,
    val ingressBucketLimit: Int,
    val ingressPerMinute: Int,
    val semanticBucketLimit: Int,
    val semanticEventLimit: Int,
    val pruneBatch: Int,
    val enrollmentGlobalPerHour: Int,
    val ownerCreateGlobalPerHour: Int,
    val ownerCreateMemberLimit: Int,
    val ownerCreatePruneBatch: Int,
    val trustForwardedHeaders: Boolean,
    trustedProxies: List<String>,
) {
    private val proxies = trustedProxies.toList()

    fun trustedProxies(): List<String> = proxies.toList()

    internal fun clientIpResolver(): ClientIpResolver = ClientIpResolver(
        KiraSecurityProperties(trustForwardedHeaders = trustForwardedHeaders, trustedProxies = proxies.toList()),
    )

    internal fun admissionPolicy(capacityPolicy: ComplaintCapacityPolicyV1): ComplaintAdmissionPolicy = ComplaintAdmissionPolicy(
        coordinationMode,
        declaredInstances,
        concurrentLimit,
        ingressBucketLimit,
        ingressPerMinute,
        semanticBucketLimit,
        semanticEventLimit,
        pruneBatch,
        ComplaintEnrollmentAdmissionPolicyFactory.fromCapacityPolicy(capacityPolicy, enrollmentGlobalPerHour),
    )

    internal fun ownerCreatePolicy(capacityPolicy: ComplaintCapacityPolicyV1): ComplaintOwnerCreateAdmissionPolicy.Bounded =
        ComplaintOwnerCreateAdmissionPolicy.Bounded(capacityPolicy, ownerCreateGlobalPerHour, ownerCreateMemberLimit, ownerCreatePruneBatch)

    override fun toString(): String = "VersionBoundComplaintConsumerSettings(redacted,no-authority)"
}

internal const val INVALID_BOUND_COMPLAINT_CONSUMERS = "Invalid version-bound complaint consumer configuration"
