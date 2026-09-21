package me.manga.kira.backend.complaint.domain.reconciliation

import kotlinx.serialization.Serializable

/** Born-with declaration only. No current checkpoint, registration or content authority is supplied. */
@Serializable
internal data class TestInitialCheckpointCreateInputV1(val schemaVersion: Int, val profile: String)
