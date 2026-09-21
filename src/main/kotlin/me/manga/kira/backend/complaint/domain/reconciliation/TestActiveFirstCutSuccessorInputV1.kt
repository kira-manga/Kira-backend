package me.manga.kira.backend.complaint.domain.reconciliation

import kotlinx.serialization.Serializable

/** Separate pre-D selector. No stored slot, old owner/token, proof, lease or deadline is an input. */
@Serializable
internal data class TestActiveFirstCutSuccessorInputV1(val schemaVersion: Int, val profile: String)
