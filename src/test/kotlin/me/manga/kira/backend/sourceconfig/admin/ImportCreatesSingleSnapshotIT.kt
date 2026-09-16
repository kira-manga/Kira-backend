package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 23 — `ImportCreatesSingleSnapshotIT`: *one import = at most one snapshot*. Importing a
 * multi-source document materializes exactly ONE `published_documents` row, not one per source — the
 * per-source changes apply without intermediate whole-document snapshots and a single snapshot is
 * materialized after the batch (PLAN §12.2 point 5).
 */
class ImportCreatesSingleSnapshotIT : AbstractAdminSourceIT() {

    @Test
    fun `importing four sources creates exactly one published document row`() {
        assertEquals(0L, snapshotCount(), "clean baseline")

        importBundled(SourceConfigFixtures.loadFixture("bundled-trimmed.json")).andExpect { status { isOk() } }

        assertEquals(4L, sourceRowCount(), "all four stanzas created")
        assertEquals(
            1L,
            snapshotCount(),
            "exactly one snapshot for the whole import (NOT one per source)",
        )
    }
}
