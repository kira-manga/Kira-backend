package me.manga.kira.backend.security

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import java.time.Clock

/** TEST acquisitions only. The retained routing consumer, not a second material list, binds the actual TEST J. */
internal class VersionBoundTestComplaintConsumerInputsV1(
    val admissionCurrent: AcquiredVersionedSecret,
    val admissionPrevious: AcquiredVersionedSecret?,
    val cursorActiveKeyId: String,
    cursorSecrets: List<AcquiredVersionedSecret>,
    val journalRouting: TestOwnerDeleteJournalRoutingV1,
) {
    private val cursors = snapshotCursors(cursorSecrets)

    init {
        val admissions = admissionSecrets()
        admissions.forEach { requireFamily(it, SecretMaterialFamily.COMPLAINT_ADMISSION) }
        require(admissions.map { it.descriptor.logicalKeyId }.distinct().size == admissions.size) { INVALID_BOUND_TEST_CONSUMERS }
        require(cursors.map { it.descriptor.logicalKeyId }.distinct().size == cursors.size) { INVALID_BOUND_TEST_CONSUMERS }
        require(cursors.any { it.descriptor.logicalKeyId == cursorActiveKeyId }) { INVALID_BOUND_TEST_CONSUMERS }
    }

    internal fun admissionSecrets(): List<AcquiredVersionedSecret> = listOfNotNull(admissionCurrent, admissionPrevious)

    internal fun cursorSecrets(): List<AcquiredVersionedSecret> = cursors.toList()

    override fun toString(): String = "VersionBoundTestComplaintConsumerInputsV1(redacted,no-authority)"

    private companion object {
        fun snapshotCursors(secrets: List<AcquiredVersionedSecret>): List<AcquiredVersionedSecret> {
            val count = secrets.size
            require(count in 1..8) { INVALID_BOUND_TEST_CONSUMERS }
            val iterator = secrets.iterator()
            val result = ArrayList<AcquiredVersionedSecret>(count)
            repeat(count) {
                require(iterator.hasNext()) { INVALID_BOUND_TEST_CONSUMERS }
                val acquired = iterator.next()
                requireFamily(acquired, SecretMaterialFamily.COMPLAINT_CURSOR)
                result.add(acquired)
            }
            require(!iterator.hasNext()) { INVALID_BOUND_TEST_CONSUMERS }
            return result.sortedBy { it.descriptor.logicalKeyId }
        }

        fun requireFamily(acquired: AcquiredVersionedSecret, family: SecretMaterialFamily) {
            require(acquired.descriptor.family == family && acquired.descriptor.purpose == SecretMaterialPurpose.HMAC_SHA256) {
                INVALID_BOUND_TEST_CONSUMERS
            }
        }
    }
}

/**
 * Cold actual PRE_CUTOVER_TEST/memory/one-declared-instance consumer. Builds its own bounded ingress
 * with enrollment, owner operations and explicitly selected Admin policies; never accepts ingress
 * plus claimed settings. The optional Admin read policy and cursor are born together before full D.
 * Fixed admission keys cannot rotate or retire behind D. Rebuilding loses counters and is not rollout.
 * No bean, namespace registration, process binding, current capability or provider operation.
 */
internal class VersionBoundTestComplaintConsumerConfigurationV1 private constructor(
    val jwt: VersionBoundInstallationJwtConfiguration,
    val capacityPolicy: ComplaintCapacityPolicyV1,
    val journalConfiguration: TestOwnerDeleteJournalConfigurationV1,
    val journalRouting: TestOwnerDeleteJournalRoutingV1,
    private val settings: VersionBoundComplaintConsumerSettings,
    val admissionPolicy: ComplaintAdmissionPolicy,
    val ownerCreatePolicy: ComplaintOwnerCreateAdmissionPolicy.Bounded,
    val ownerEditPolicy: ComplaintOwnerEditAdmissionPolicy.Bounded,
    val ownerDeletePolicy: ComplaintOwnerDeleteAdmissionPolicy.Bounded,
    val ownerDeleteAllPolicy: ComplaintOwnerDeleteAllAdmissionPolicy,
    val adminDeletePolicy: ComplaintAdminDeleteAdmissionPolicy,
    val adminBatchDeletePolicy: ComplaintAdminBatchDeleteAdmissionPolicy,
    private val admissionKeys: ComplaintAdmissionKeyConfiguration,
    val ownerCursorCodec: ComplaintOwnerCursorCodec,
    val adminReadPolicy: ComplaintAdminReadAdmissionPolicy,
    val adminCursorCodec: ComplaintAdminCursorCodec?,
    val ingressAdmission: ComplaintIngressAdmission,
    descriptors: List<VersionedSecretBinding>,
) {
    private val bindings = descriptors.toList()

    init {
        require((adminReadPolicy is ComplaintAdminReadAdmissionPolicy.Bounded) == (adminCursorCodec != null)) { INVALID_BOUND_TEST_CONSUMERS }
    }

    val admissionCurrentKeyId: String get() = admissionKeys.currentKeyId
    val admissionPreviousKeyId: String? get() = admissionKeys.previousKeyId
    val coordinationMode: String get() = settings.coordinationMode
    val declaredInstances: Int get() = settings.declaredInstances
    val trustForwardedHeaders: Boolean get() = settings.trustForwardedHeaders
    val enrollmentPolicy: ComplaintEnrollmentAdmissionPolicy.Bounded
        get() = admissionPolicy.enrollment as ComplaintEnrollmentAdmissionPolicy.Bounded

    fun trustedProxies(): List<String> = settings.trustedProxies()

    fun descriptors(): List<VersionedSecretBinding> = bindings.toList()

    override fun toString(): String = "VersionBoundTestComplaintConsumerConfigurationV1(PRE_CUTOVER_TEST,redacted,no-authority)"

    companion object {
        fun fromAcquired(
            jwt: VersionBoundInstallationJwtConfiguration,
            capacityPolicy: ComplaintCapacityPolicyV1,
            journal: TestOwnerDeleteJournalConfigurationV1,
            keys: VersionBoundTestComplaintConsumerInputsV1,
            settings: VersionBoundComplaintConsumerSettings,
            adminBatchDeletePerHour: Int = 60,
            adminReadPerMinute: Int? = null,
        ): VersionBoundTestComplaintConsumerConfigurationV1 {
            requireConnectionFree()
            val user = requireNotNull(jwt.boundUserKeyProvider) { INVALID_BOUND_TEST_CONSUMERS }
            require(keys.journalRouting.journalConfiguration === journal) { INVALID_BOUND_TEST_CONSUMERS }
            require(!journal.adminDelete || journal.registeredAdminDelete) { INVALID_BOUND_TEST_CONSUMERS }
            val admissions = keys.admissionSecrets()
            val cursors = keys.cursorSecrets()
            val descriptors = jwt.descriptors() + admissions.map { it.descriptor }.sortedBy { it.logicalKeyId } +
                cursors.map { it.descriptor } + keys.journalRouting.descriptors()
            require(descriptors.map { it.version }.distinct().size == descriptors.size) { INVALID_BOUND_TEST_CONSUMERS }

            val policy = settings.admissionPolicy(capacityPolicy)
            val create = settings.ownerCreatePolicy(capacityPolicy)
            val edit = ComplaintOwnerEditAdmissionPolicy.Bounded(capacityPolicy, create.memberLimit, create.pruneBatch)
            val delete = ComplaintOwnerDeleteAdmissionPolicy.Bounded(capacityPolicy, create.memberLimit, create.pruneBatch)
            val deleteAll = if (journal.ownerDeleteAll) ComplaintOwnerDeleteAllAdmissionPolicy.Bounded(capacityPolicy, create.memberLimit, create.pruneBatch, journal.scope)
                else ComplaintOwnerDeleteAllAdmissionPolicy.Disabled
            val adminDelete = if (journal.registeredAdminDelete) ComplaintAdminDeleteAdmissionPolicy.Bounded(capacityPolicy, create.memberLimit, create.pruneBatch)
                else ComplaintAdminDeleteAdmissionPolicy.Disabled
            require(adminBatchDeletePerHour in 1..60 && (journal.registeredAdminBatchDelete || adminBatchDeletePerHour == 60)) { INVALID_BOUND_TEST_CONSUMERS }
            val adminBatchDelete = if (journal.registeredAdminBatchDelete)
                ComplaintAdminBatchDeleteAdmissionPolicy.Bounded(capacityPolicy, create.memberLimit, create.pruneBatch, adminBatchDeletePerHour)
                else ComplaintAdminBatchDeleteAdmissionPolicy.Disabled
            val adminRead = if (adminReadPerMinute == null) ComplaintAdminReadAdmissionPolicy.Disabled
                else ComplaintAdminReadAdmissionPolicy.Bounded(adminReadPerMinute)
            val resolver = settings.clientIpResolver()
            val copies = ArrayList<ByteArray>(descriptors.size)
            val admissionCopies = ArrayList<ComplaintAdmissionKey>(admissions.size)
            try {
                val jwtMaterials = jwt.descriptors().map { binding ->
                    val key = if (binding.family == SecretMaterialFamily.USER_ADMIN_JWT) {
                        user.secretKey
                    } else {
                        requireNotNull(jwt.installationKeyRing.key(binding.logicalKeyId)) { INVALID_BOUND_TEST_CONSUMERS }
                    }
                    KeyMaterial(binding, key.encoded.also { copies.add(it) })
                }
                val admissionMaterials = admissions.map { copyMaterial(it, copies) }
                val cursorMaterials = cursors.map { copyMaterial(it, copies) }
                val journalFamily = keys.journalRouting.admissionForbiddenFamily()
                (jwtMaterials + admissionMaterials + cursorMaterials).forEach { requireJournalSeparation(it, journalFamily) }
                admissionMaterials.forEach { admissionCopies.add(ComplaintAdmissionKey(it.binding.logicalKeyId, it.bytes)) }
                val fixedKeys = ComplaintAdmissionKeyConfiguration.fixed(
                    admissionCopies.first(),
                    admissionCopies.getOrNull(1),
                    listOf(
                        jwtFamily("user-admin-jwt", SecretMaterialFamily.USER_ADMIN_JWT, jwtMaterials),
                        jwtFamily("installation-jwt", SecretMaterialFamily.INSTALLATION_JWT, jwtMaterials),
                        ComplaintAdmissionForbiddenFamily("owner-cursor", cursorMaterials.map { it.bytes }),
                        journalFamily,
                    ),
                )
                val codec = ComplaintOwnerCursorCodec(
                    keys.cursorActiveKeyId,
                    cursorMaterials.associate { it.binding.logicalKeyId to it.bytes },
                    (jwtMaterials + admissionMaterials).map { it.bytes },
                    Clock.systemUTC(),
                )
                // Same acquired cursor family, but the fixed Admin codec owns its distinct actor/route/MAC frame.
                // Neither a caller codec nor an unversioned material list can be substituted after full D.
                val adminCodec = if (adminRead is ComplaintAdminReadAdmissionPolicy.Bounded) ComplaintAdminCursorCodec(
                    keys.cursorActiveKeyId,
                    cursorMaterials.associate { it.binding.logicalKeyId to it.bytes },
                    (jwtMaterials + admissionMaterials).map { it.bytes },
                    Clock.systemUTC(),
                ) else null
                val ingress = ComplaintIngressAdmission(
                    resolver, policy, fixedKeys, SystemComplaintAdmissionNanoClock,
                    createPolicy = create,
                    deleteAllPolicy = deleteAll,
                    editPolicy = edit,
                    ownerDeletePolicy = delete,
                    adminReadPolicy = adminRead,
                    adminDeletePolicy = adminDelete,
                    adminBatchDeletePolicy = adminBatchDelete,
                )
                return VersionBoundTestComplaintConsumerConfigurationV1(
                    jwt, capacityPolicy, journal, keys.journalRouting, settings, policy, create, edit, delete, deleteAll, adminDelete, adminBatchDelete,
                    fixedKeys, codec, adminRead, adminCodec, ingress, descriptors,
                )
            } finally {
                admissionCopies.forEach { it.destroy() }
                copies.forEach { it.fill(0) }
            }
        }

        private fun copyMaterial(acquired: AcquiredVersionedSecret, copies: MutableList<ByteArray>): KeyMaterial = acquired.useMaterial { material ->
            require(material.size in 32..128) { INVALID_BOUND_TEST_CONSUMERS }
            KeyMaterial(acquired.descriptor, material.copyOf().also { copies.add(it) })
        }

        private fun requireJournalSeparation(material: KeyMaterial, journalFamily: ComplaintAdmissionForbiddenFamily) {
            val candidate = ComplaintAdmissionKey(material.binding.logicalKeyId, material.bytes)
            try {
                require(!journalFamily.forbids(candidate)) { INVALID_BOUND_TEST_CONSUMERS }
            } finally {
                candidate.destroy()
            }
        }

        private fun jwtFamily(name: String, family: SecretMaterialFamily, materials: List<KeyMaterial>): ComplaintAdmissionForbiddenFamily =
            ComplaintAdmissionForbiddenFamily(name, materials.filter { it.binding.family == family }.map { it.bytes })
    }

    private class KeyMaterial(val binding: VersionedSecretBinding, val bytes: ByteArray)
}

private const val INVALID_BOUND_TEST_CONSUMERS = "Invalid version-bound TEST complaint consumer configuration"
