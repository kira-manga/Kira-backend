package me.manga.kira.backend.tutorial.domain

/** Filesystem mechanics stay behind this port; the application owns database transactions. */
interface TutorialMediaStorage {
    /** The caller owns the media transaction lock. Existing names must never be overwritten. */
    fun installNew(media: StoredMedia, bytes: ByteArray)

    /** A successful result contains the very bytes checked, not a pathname to reopen. */
    fun readVerified(media: StoredMedia): MediaReadResult

    /** Only authentic bytes matching all recorded content metadata may repair an existing object. */
    fun repair(media: StoredMedia, bytes: ByteArray, beforeMutation: () -> Unit)

    /** An idempotent, physical-only fast path after known successful outer database commit. */
    fun deleteCommitted(media: StoredMedia)

    /** A bounded, fixed inventory. An incomplete inventory is never authority for a sweep. */
    fun inventory(limit: Int): MediaInventory

    /** Preserve an original candidate before removing it; never discover another filename here. */
    fun quarantine(candidate: MediaFileSnapshot, beforeMutation: () -> Unit): Boolean
}

sealed interface MediaReadResult {
    data class Verified(val bytes: ByteArray) : MediaReadResult
    data class Unavailable(val issue: MediaStorageIssue) : MediaReadResult
}

enum class MediaStorageIssue {
    MISSING,
    INVALID_METADATA,
    UNSAFE_FILE,
    SIZE_MISMATCH,
    CHECKSUM_MISMATCH,
    IO_FAILURE,
    IDENTITY_CHANGED,
    INVENTORY_LIMIT,
    ROW_LIMIT,
    ORPHAN,
    STAGING,
    UNKNOWN_ENTRY,
    INCOMPLETE_QUARANTINE,
}

enum class MediaFileKind { FINAL, STAGING }

/** Opaque provider identity plus immutable observation facts, not merely a candidate pathname. */
data class MediaFileSnapshot(val filename: String, val kind: MediaFileKind, val fileKey: String, val byteSize: Long, val modifiedAt: String)

data class MediaInventory(
    val candidates: List<MediaFileSnapshot>,
    val issues: List<MediaStorageIssue> = emptyList(),
    val quarantinedFiles: Int = 0,
    val complete: Boolean = true,
)

class TutorialMediaStorageException(val issue: MediaStorageIssue, cause: Throwable? = null) :
    RuntimeException("tutorial media storage operation failed: ${issue.name}", cause)

/** Both values must still identify the same physical PostgreSQL transaction on revalidation. */
data class MediaTransaction(val backendPid: Int, val transactionId: Long)

object TutorialMediaStorageLimits {
    const val MAX_BYTES = 4 * 1024 * 1024
}
