package me.manga.kira.backend.complaint.domain.reconciliation

import kotlinx.serialization.Serializable

/** Cold opt-in only; neither an observed checkpoint nor a registered mutation grant. */
@Serializable
internal data class TestInitialCheckpointDeletionInputV1(val schemaVersion: Int, val profile: String)
