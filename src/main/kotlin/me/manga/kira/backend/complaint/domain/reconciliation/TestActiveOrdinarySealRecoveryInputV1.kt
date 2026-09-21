package me.manga.kira.backend.complaint.domain.reconciliation

import kotlinx.serialization.Serializable

/** Born-with cold selector only, never a durable-state assertion, deadline override or recovery proof. */
@Serializable
internal data class TestActiveOrdinarySealRecoveryInputV1(val schemaVersion: Int, val profile: String)
