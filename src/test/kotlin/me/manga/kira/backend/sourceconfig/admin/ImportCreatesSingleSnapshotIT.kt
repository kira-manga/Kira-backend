package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.InitialSourceCatalogFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 23 — `ImportCreatesSingleSnapshotIT`: *one import = at most one snapshot*. Importing a
 * multi-source document materializes exactly ONE `published_documents` row, not one per source — the
 * per-source changes apply without intermediate whole-document snapshots and a single snapshot is
 * materialized after the batch (PLAN §12.2 point 5).
 */
class ImportCreatesSingleSnapshotIT : AbstractAdminSourceIT() {
    override val bootstrapCatalogBeforeEach: Boolean = true

    @Test
    fun `importing four sources creates exactly one published document row`() {
        val snapshotsBefore = snapshotCount()
        val rowsBefore = sourceRowCount()
        assertEquals(1L, snapshotsBefore, "real bootstrap baseline")

        importBundled(InitialSourceCatalogFixtures.postBootstrapTrimmedDocument()).andExpect { status { isOk() } }

        assertEquals(rowsBefore + 4, sourceRowCount(), "all four additional stanzas created")
        assertEquals(
            snapshotsBefore + 1,
            snapshotCount(),
            "exactly one snapshot for the whole import (NOT one per source)",
        )
    }
}
