package me.manga.kira.backend.complaint.domain.reconciliation

import kotlinx.serialization.Serializable

/** Cold finite read-owner declaration only; never a current seal, scan, lease or SUCCESS assertion. */
@Serializable
internal data class TestActiveInitialCheckpointInputV1(
    val schemaVersion: Int,
    val profile: String,
    val recoverySessionName: String?,
)
