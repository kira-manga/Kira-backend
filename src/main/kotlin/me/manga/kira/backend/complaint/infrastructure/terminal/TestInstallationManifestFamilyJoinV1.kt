package me.manga.kira.backend.complaint.infrastructure.terminal

import org.springframework.jdbc.core.JdbcTemplate

/**
 * Read-only ordinary-family closure for the two actual manifest successors. The complete SQL
 * relation separately validates every terminal publication; this page excludes only that named
 * family, never unsupported ordinary kinds or foreign-scope rows under either protected prefix.
 * Scalar comparison helpers issue no original, receipt, recovery permission or capacity transfer.
 */
internal object TestInstallationManifestFamilyJoinV1 {
    fun requireClosed(jdbc: JdbcTemplate, operation: TestInstallationManifestOperationV1, run: TestOrdinaryDrainRowsV1.Run) {
        operation.requireAppliedPage(jdbc)
        val original = operation.original
        val facts = TestOrdinaryDrainPersistenceV1.FamilyFacts(original.routing, original.control.cutoff)
        val fold = Fold(original.drain.maximumVersions, original.ordinaryCut.denial.firstInventory.versionCount)
        while (true) {
            operation.requireAppliedPage(jdbc)
            val page = primaryPage(jdbc, facts, fold.after)
            operation.requireAppliedPage(jdbc)
            if (page.isEmpty()) break
            page.forEach { id ->
                operation.requireAppliedPage(jdbc)
                fold.requireNext(id)
                fold.add(id, requirePrimary(jdbc, facts, run, id))
                operation.requireAppliedPage(jdbc)
            }
        }
        fold.finish(appliedCount(jdbc, facts))
        operation.requireAppliedPage(jdbc)
    }

    fun requireClosed(jdbc: JdbcTemplate, operation: TestInstallationManifestPublicationOperationV1, run: TestOrdinaryDrainRowsV1.Run) {
        operation.requireAppliedPage(jdbc)
        val original = operation.original
        val facts = TestOrdinaryDrainPersistenceV1.FamilyFacts(original.routing, original.control.cutoff)
        val fold = Fold(original.drain.maximumVersions, original.ordinaryCut.denial.firstInventory.versionCount)
        while (true) {
            operation.requireAppliedPage(jdbc)
            val page = primaryPage(jdbc, facts, fold.after)
            operation.requireAppliedPage(jdbc)
            if (page.isEmpty()) break
            page.forEach { id ->
                operation.requireAppliedPage(jdbc)
                fold.requireNext(id)
                fold.add(id, requirePrimary(jdbc, facts, run, id))
                operation.requireAppliedPage(jdbc)
            }
        }
        fold.finish(appliedCount(jdbc, facts))
        operation.requireAppliedPage(jdbc)
    }

    private fun requirePrimary(jdbc: JdbcTemplate, facts: TestOrdinaryDrainPersistenceV1.FamilyFacts,
        run: TestOrdinaryDrainRowsV1.Run, id: String): Long {
        val value = TestOrdinaryDrainPersistenceV1.closedPrimary(jdbc, facts, id)
        val family = TestOrdinaryDrainPersistenceV1.readFamilyFacts(jdbc, facts, value, checkNotNull(value.publication.verifiedAt))
        // Existing N/P/L only. No staging, reconstruction, lock upgrade, or second P−U conversion.
        TestOrdinaryDrainPersistenceV1.requirePrimaryFacts(jdbc, facts, run, value, family, converted = true, lockDomain = false)
        return family.size.toLong()
    }

    private fun primaryPage(jdbc: JdbcTemplate, facts: TestOrdinaryDrainPersistenceV1.FamilyFacts, after: String?): List<String> =
        jdbc.query(primaryPage, { row, _ -> checkNotNull(row.getString("event_id")) }, facts.scope,
            facts.routing.journalConfiguration.ordinaryPrefix + "%", facts.routing.journalConfiguration.sealTerminalPrefix + "%", after, after)

    private fun appliedCount(jdbc: JdbcTemplate, facts: TestOrdinaryDrainPersistenceV1.FamilyFacts): Long =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", Long::class.java, facts.scope))

    private class Fold(private val maximum: Long, private val expected: Long) {
        var after: String? = null
            private set
        private var primaries = 0L
        private var applied = 0L
        init { requireManifest(expected in 0..maximum) }
        fun requireNext(id: String) {
            requireManifest(after?.let { it < id } != false && primaries < maximum && applied < expected)
        }
        fun add(id: String, versions: Long) {
            requireNext(id)
            requireManifest(versions in 1..4)
            applied = Math.addExact(applied, versions)
            requireManifest(applied <= expected)
            primaries++; after = id
        }
        fun finish(actual: Long) { requireManifest(applied == expected && actual == applied) }
    }

    private val primaryPage = """
        SELECT CASE WHEN octet_length(event_id) BETWEEN 1 AND 128 THEN event_id END AS event_id
        FROM complaint_journal_publications
        WHERE (data_scope_id = ?::uuid OR object_key LIKE ?::text OR object_key LIKE ?::text)
            AND event_kind IS DISTINCT FROM 'INSTALLATION_MANIFEST'
            AND (?::text IS NULL OR event_id > ?::text COLLATE "C")
        ORDER BY event_id COLLATE "C" LIMIT ${TestOrdinaryDrainSqlV1.PAGE}
    """.trimIndent()
}
