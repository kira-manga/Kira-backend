package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.complaint.infrastructure.journal.aws.requireJournalVersion
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.util.UUID

/**
 * Fixed successor read of the completed ordinary cut. The drain's own Applied.read remains
 * running-only: completion does not reopen that original or lend its expired scan budget.
 * Only the exact retained PREPARE/publication phase can use this reader, including empty pages.
 */
internal object TestInstallationManifestAppliedPageV1 {
    fun page(jdbc: JdbcTemplate, operation: TestInstallationManifestOperationV1,
        after: Pair<String, String>?): List<TestOrdinaryDrainPersistenceV1.Applied> {
        operation.requireAppliedPage(jdbc)
        val drain = operation.original.drain
        return jdbc.query(TestOrdinaryDrainSqlV1.appliedPage, { row, _ ->
            operation.requireAppliedPage(jdbc)
            read(row, drain)
        }, drain.scope, drain.routing.journalConfiguration.ordinaryPrefix + "%", drain.routing.journalConfiguration.sealTerminalPrefix + "%",
            after?.first, after?.first, after?.second).also { operation.requireAppliedPage(jdbc) }
    }

    fun page(jdbc: JdbcTemplate, operation: TestInstallationManifestPublicationOperationV1,
        after: Pair<String, String>?): List<TestOrdinaryDrainPersistenceV1.Applied> {
        operation.requireAppliedPage(jdbc)
        val drain = operation.original.drain
        return jdbc.query(TestOrdinaryDrainSqlV1.appliedPage, { row, _ ->
            operation.requireAppliedPage(jdbc)
            read(row, drain)
        }, drain.scope, drain.routing.journalConfiguration.ordinaryPrefix + "%", drain.routing.journalConfiguration.sealTerminalPrefix + "%",
            after?.first, after?.first, after?.second).also { operation.requireAppliedPage(jdbc) }
    }

    // Private shape/classification only, after the typed active-child check on every row.
    private fun read(row: ResultSet, drain: TestRunOrdinaryDrainV1): TestOrdinaryDrainPersistenceV1.Applied {
        drain.requireManifestPredecessor()
        val kind = checkNotNull(row.getString("event_kind"))
        requireManifest(kind == "OWNER_DELETE" || kind == "OWNER_DELETE_ALL" && drain.routing.journalConfiguration.ownerDeleteAll ||
            kind == "ADMIN_DELETE" && drain.routing.journalConfiguration.registeredAdminDelete ||
            kind == "ADMIN_BATCH_DELETE" && drain.routing.journalConfiguration.registeredAdminBatchDelete)
        val targets = row.getInt("target_count").also { requireManifest(!row.wasNull()) }
        requireManifest(row.getObject("data_scope_id", UUID::class.java) == drain.scope && TestOrdinaryDrainRowsV1.boolean(row, "test_only") &&
            row.getObject("writer_generation", UUID::class.java).toString() == drain.writer &&
            (when (kind) { "OWNER_DELETE_ALL" -> targets in 0..100; "ADMIN_BATCH_DELETE" -> targets in 1..50; else -> targets == 1 }) &&
            TestOrdinaryDrainRowsV1.boolean(row, "finite") && row.getLong("journal_epoch") in 1..drain.cutoff)
        return TestOrdinaryDrainPersistenceV1.Applied(checkNotNull(row.getString("event_id")), checkNotNull(row.getString("object_key")),
            requireJournalVersion(row.getString("object_version")), TestOrdinaryDrainRowsV1.hash(row, "ciphertext_hash"),
            row.getLong("journal_epoch"), kind, targets, checkNotNull(row.getTimestamp("applied_at")).toInstant(), row.getString("stamp"))
    }
}
