package me.manga.kira.backend.complaint.domain.reconciliation

import kotlinx.serialization.Serializable
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector

/** Optional before full D; no scheduler, credentials, current queue status or activation is declared. */
@Serializable
internal data class TestActiveOwnerDeleteQueueInputV1(
    val schemaVersion: Int,
    val profile: String,
    val totalAttemptMillis: Long,
    val recoverySessionName: String?,
)

/** V29's one permanent observation per existing TEST run, not a new counter or terminal-reserve charge. */
internal object TestActiveOwnerDeleteQueueStorageV1 {
    const val PROFILE = "TEST_ACTIVE_OWNER_DELETE_QUEUE_V1"
    const val STORAGE_PROFILE = "TEST_ACTIVE_QUEUE_OBSERVATION_STORAGE_V1"
    const val MAX_ROWS_PER_SCOPE = 1L
    // Conservative logical heap/index envelopes. This is not a disk, TOAST or WAL qualification.
    const val MAX_HEAP_BYTES = 768L
    const val MAX_INDEX_BYTES = 256L // The primary key also serves the run FK; no unpriced extra index.
    const val SAFETY_MULTIPLIER = 8L
    const val STORAGE_BYTES = SAFETY_MULTIPLIER * (MAX_HEAP_BYTES + MAX_INDEX_BYTES)
    val ROW = ComplaintCapacityVector.units(ComplaintCapacityCounter.STORAGE_BYTES, STORAGE_BYTES)
}
