package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveFirstCutSuccessorInputV1

/** Selected before the genuine signed full-D activation; never retrofits recovery onto an old run. */
internal object TestActiveFirstCutSuccessorInputFixtureV1 {
    fun input() = TestActiveFirstCutSuccessorInputV1(1, "TEST_ACTIVE_FIRST_CUT_RESERVED_SUCCESSOR_V1")
}
