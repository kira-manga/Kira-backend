package me.manga.kira.backend.complaint.domain

/** A digest binding, not a signature/authority check. The adapter must validate every locked row against trusted configuration. */
class ComplaintCapacityConfiguration private constructor(private val digest: ByteArray?, val creationClosed: Boolean) {
    fun digestBytes(): ByteArray? = digest?.copyOf()

    fun requireMatching(expectedDigest: ByteArray) {
        if (expectedDigest.size != DIGEST_BYTES) rejectCapacity(ComplaintCapacityFailureCode.INVALID_CONFIGURATION)
        if (digest == null || !digest.contentEquals(expectedDigest)) rejectCapacity(ComplaintCapacityFailureCode.CONFIGURATION_MISMATCH)
    }

    fun requireCreationAllowed(expectedDigest: ByteArray) {
        requireMatching(expectedDigest)
        if (creationClosed) rejectCapacity(ComplaintCapacityFailureCode.CREATION_CLOSED)
    }

    override fun equals(other: Any?): Boolean = other is ComplaintCapacityConfiguration &&
        creationClosed == other.creationClosed && digest.contentEquals(other.digest)

    override fun hashCode(): Int = 31 * digest.contentHashCode() + creationClosed.hashCode()

    override fun toString(): String = "ComplaintCapacityConfiguration(redacted)"

    companion object {
        private const val DIGEST_BYTES = 32

        fun of(digest: ByteArray?, creationClosed: Boolean): ComplaintCapacityConfiguration {
            if (digest == null && !creationClosed) rejectCapacity(ComplaintCapacityFailureCode.INVALID_CONFIGURATION)
            if (digest != null && digest.size != DIGEST_BYTES) rejectCapacity(ComplaintCapacityFailureCode.INVALID_CONFIGURATION)
            return ComplaintCapacityConfiguration(digest?.copyOf(), creationClosed)
        }
    }
}
