package me.manga.kira.backend.complaint.domain.reconciliation

import kotlinx.serialization.Serializable

/** Explicit cold profile selector, not a deadline override, current-state assertion or enable flag. */
@Serializable
internal data class TestActiveFirstCutInputV1(val schemaVersion: Int, val profile: String)

/** Explicit cold ordinary caller spelling only; credentials are separately supplied and never serialized. */
@Serializable
internal data class TestActiveOrdinaryPublicationInputV1(val sessionName: String?)
