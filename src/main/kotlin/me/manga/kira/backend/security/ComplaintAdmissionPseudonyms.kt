package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteTuple
import me.manga.kira.backend.complaint.domain.ComplaintAdminContentTuple
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusTuple
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreationOperation
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditTuple
import me.manga.kira.backend.complaint.domain.ComplaintOwnerOperationTuple
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightTuple
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import java.nio.ByteBuffer
import java.util.UUID

/** A storage key contains only a key-generation name and one fixed-length HMAC output. */
internal class ComplaintAdmissionBucketKey(val generation: String, private val digest: String) {
    init {
        require(generation.length in 1..64 && digest.length == 64) { INVALID_ADMISSION_CONFIGURATION }
    }

    override fun equals(other: Any?): Boolean = other is ComplaintAdmissionBucketKey && generation == other.generation && digest == other.digest

    override fun hashCode(): Int = 31 * generation.hashCode() + digest.hashCode()

    override fun toString(): String = "ComplaintAdmissionBucketKey(redacted)"
}

internal object ComplaintAdmissionPseudonyms {
    /** ADMIN+scope is domain-separated from owner writes and Admin-read's minute store. */
    fun adminContentActor(keys: List<ComplaintAdmissionKey>, actor: UUID, scope: ComplaintDataScope): List<ComplaintAdmissionBucketKey> =
        derive(keys, listOf(domain(), ascii("ACTOR"), ascii("ADMIN"), uuid(actor), uuid(scope.id), ascii(ComplaintAdminContentTuple.OPERATION)))

    fun adminContentMember(keys: List<ComplaintAdmissionKey>, tuple: ComplaintAdminContentTuple): List<ComplaintAdmissionBucketKey> = derive(
        keys,
        listOf(
            domain(), ascii("MEMBER"), ascii("ADMIN"), uuid(tuple.actor), uuid(tuple.scope.id),
            ascii(ComplaintAdminContentTuple.OPERATION), uuid(tuple.key), uuid(tuple.targetId), tuple.fingerprintBytes(),
        ),
    )

    /** ADMIN+scope is domain-separated from owner writes and Admin-read's minute store. */
    fun adminDeleteActor(keys: List<ComplaintAdmissionKey>, actor: UUID, scope: ComplaintDataScope): List<ComplaintAdmissionBucketKey> =
        derive(keys, listOf(domain(), ascii("ACTOR"), ascii("ADMIN"), uuid(actor), uuid(scope.id), ascii(ComplaintAdminDeleteTuple.OPERATION)))

    fun adminDeleteMember(keys: List<ComplaintAdmissionKey>, tuple: ComplaintAdminDeleteTuple): List<ComplaintAdmissionBucketKey> = derive(
        keys,
        listOf(
            domain(), ascii("MEMBER"), ascii("ADMIN"), uuid(tuple.actor), uuid(tuple.scope.id),
            ascii(ComplaintAdminDeleteTuple.OPERATION), uuid(tuple.key), uuid(tuple.targetId), tuple.fingerprintBytes(),
        ),
    )

    /** Status and closure share ONE actor+scope allowance, distinct from the existing ADMIN_EDIT bucket. */
    fun adminStatusActor(keys: List<ComplaintAdmissionKey>, actor: UUID, scope: ComplaintDataScope): List<ComplaintAdmissionBucketKey> =
        derive(keys, listOf(domain(), ascii("ACTOR"), ascii("ADMIN"), uuid(actor), uuid(scope.id), ascii("ADMIN_STATUS_CLOSURE")))

    fun adminStatusMember(keys: List<ComplaintAdmissionKey>, tuple: ComplaintAdminStatusTuple): List<ComplaintAdmissionBucketKey> = derive(
        keys,
        listOf(
            domain(), ascii("MEMBER"), ascii("ADMIN"), uuid(tuple.actor), uuid(tuple.scope.id),
            ascii(tuple.operation.name), uuid(tuple.key), uuid(tuple.targetId), tuple.fingerprintBytes(),
        ),
    )

    /** One distinct ADMIN family combines search/detail without an installation-shaped surrogate. */
    fun adminReadActor(keys: List<ComplaintAdmissionKey>, actor: UUID, scope: ComplaintDataScope): List<ComplaintAdmissionBucketKey> =
        derive(keys, listOf(domain(), ascii("ACTOR"), ascii("ADMIN"), uuid(actor), uuid(scope.id), ascii("ADMIN_READ")))

    fun ingressIp(keys: List<ComplaintAdmissionKey>, canonicalIp: ByteArray): List<ComplaintAdmissionBucketKey> =
        derive(keys, listOf(domain(), ascii("IP"), ascii("INGRESS"), canonicalIp))

    fun sessionIp(keys: List<ComplaintAdmissionKey>, canonicalIp: ByteArray): List<ComplaintAdmissionBucketKey> =
        derive(keys, listOf(domain(), ascii("IP"), ascii("SESSION"), canonicalIp))

    fun sessionActor(keys: List<ComplaintAdmissionKey>, installation: ScopedInstallationId): List<ComplaintAdmissionBucketKey> =
        derive(keys, listOf(domain(), ascii("ACTOR"), ascii("INSTALLATION"), ascii("SESSION"), uuid(installation.id), uuid(installation.scope.id)))

    fun ownerReadActor(keys: List<ComplaintAdmissionKey>, installation: ScopedInstallationId): List<ComplaintAdmissionBucketKey> =
        derive(keys, listOf(domain(), ascii("ACTOR"), ascii("INSTALLATION"), ascii("OWNER_READ"), uuid(installation.id), uuid(installation.scope.id)))

    fun ownerCreateActor(keys: List<ComplaintAdmissionKey>, installation: ScopedInstallationId): List<ComplaintAdmissionBucketKey> =
        derive(keys, listOf(domain(), ascii("ACTOR"), ascii("INSTALLATION"), uuid(installation.id), uuid(installation.scope.id), ascii("OWNER_CREATE")))

    fun ownerCreateGlobal(keys: List<ComplaintAdmissionKey>): List<ComplaintAdmissionBucketKey> =
        derive(keys, listOf(domain(), ascii("GLOBAL"), ascii("OWNER_CREATE")))

    /** Single-item edit/delete share this 60/hour family; delete-all and creation deliberately do not. */
    fun ownerEditDeleteActor(keys: List<ComplaintAdmissionKey>, installation: ScopedInstallationId): List<ComplaintAdmissionBucketKey> = derive(
        keys,
        listOf(domain(), ascii("ACTOR"), ascii("INSTALLATION"), uuid(installation.id), uuid(installation.scope.id), ascii("OWNER_EDIT_DELETE")),
    )

    fun ownerEditMember(keys: List<ComplaintAdmissionKey>, tuple: ComplaintOwnerEditTuple): List<ComplaintAdmissionBucketKey> = derive(
        keys,
        listOf(
            domain(), ascii("MEMBER"), ascii("INSTALLATION"), uuid(tuple.installation.id), uuid(tuple.installation.scope.id),
            ascii(ComplaintOwnerEditTuple.OPERATION), uuid(tuple.key), uuid(tuple.targetId), tuple.fingerprintBytes(),
        ),
    )

    fun ownerCreateMember(keys: List<ComplaintAdmissionKey>, tuple: ComplaintOwnerOperationTuple): List<ComplaintAdmissionBucketKey> = derive(
        keys,
        listOf(
            domain(),
            ascii("MEMBER"),
            ascii("INSTALLATION"),
            uuid(tuple.installation.id),
            uuid(tuple.installation.scope.id),
            ascii(tuple.operation.name),
            uuid(tuple.key),
        ) + (if (tuple.operation === ComplaintOwnerCreationOperation.OWNER_REPLY) tuple.targetIds().map(::uuid) else emptyList()) +
            listOf(tuple.fingerprintBytes()),
    )

    fun ownerDeleteAllIp(keys: List<ComplaintAdmissionKey>, canonicalIp: ByteArray): List<ComplaintAdmissionBucketKey> =
        derive(keys, listOf(domain(), ascii("IP"), ascii("OWNER_DELETE_ALL"), canonicalIp))

    fun ownerDeleteAllActor(keys: List<ComplaintAdmissionKey>, installation: ScopedInstallationId): List<ComplaintAdmissionBucketKey> =
        derive(keys, listOf(domain(), ascii("ACTOR"), ascii("INSTALLATION"), uuid(installation.id), uuid(installation.scope.id), ascii("OWNER_DELETE_ALL")))

    fun ownerDeleteAllMember(keys: List<ComplaintAdmissionKey>, tuple: InstallationDeletionPreflightTuple): List<ComplaintAdmissionBucketKey> = derive(
        keys,
        listOf(
            domain(),
            ascii("MEMBER"),
            ascii("INSTALLATION"),
            uuid(tuple.installation.id),
            uuid(tuple.installation.scope.id),
            ascii("OWNER_DELETE_ALL"),
            uuid(tuple.operationKey),
            ByteBuffer.allocate(40).putLong(tuple.submittedCredentialVersion).put(tuple.fingerprint.bytes()).array(),
        ),
    )

    fun bootstrapIp(keys: List<ComplaintAdmissionKey>, canonicalIp: ByteArray): List<ComplaintAdmissionBucketKey> =
        derive(keys, listOf(domain(), ascii("IP"), ascii("BOOTSTRAP"), canonicalIp))

    fun enrollmentIp(keys: List<ComplaintAdmissionKey>, canonicalIp: ByteArray): List<ComplaintAdmissionBucketKey> =
        derive(keys, listOf(domain(), ascii("IP"), ascii("ENROLLMENT"), canonicalIp))

    fun enrollmentGlobal(keys: List<ComplaintAdmissionKey>): List<ComplaintAdmissionBucketKey> =
        derive(keys, listOf(domain(), ascii("GLOBAL"), ascii("ENROLLMENT")))

    private fun derive(keys: List<ComplaintAdmissionKey>, parts: List<ByteArray>): List<ComplaintAdmissionBucketKey> {
        // The fixed reply member adds its two ordered IDs; existing create frames stay byte-identical.
        require(keys.size in 1..2 && parts.size in 1..10 && parts.all { it.size in 1..64 }) { INVALID_ADMISSION_CONFIGURATION }
        val frame = ByteBuffer.allocate(parts.sumOf { 4 + it.size }).apply {
            parts.forEach { putInt(it.size).put(it) }
        }.array()
        return try {
            keys.map { ComplaintAdmissionBucketKey(it.id, it.digest(frame)) }
        } finally {
            frame.fill(0)
        }
    }

    private fun domain(): ByteArray = ascii("kira-complaint-admission-v1")

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

    private fun uuid(value: UUID): ByteArray = ByteBuffer.allocate(16).putLong(value.mostSignificantBits).putLong(value.leastSignificantBits).array()
}
