package me.manga.kira.backend.complaint.domain.reconciliation

import kotlinx.serialization.Serializable

/** One cold recurrent owner declaration, selected before D. No checkpoint, lease or scan result. */
@Serializable
internal data class TestActiveRecurrentInputV1(
    val schemaVersion: Int,
    val profile: String,
    val recoverySessionName: String?,
)
