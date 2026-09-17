package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSession
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseSettings
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseCampaignV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Real independent PG row holders only. No replacement production JdbcTemplate, SQL callback, clock or completion signal. */
internal class CutoffResolverLockCases(private val cases: CutoffResolverCases) {
    private val f = cases.f
    private val publications = cases.publications
    private val wire = publications.wire

    fun run() {
        val event = publications.cutoffEvents.single()
        var leader = cases.capture()
        val captured = f.row()
        val outside = f.outsideRotationAndLease()
        val prepared = publications.row(event)
        for ((column, value) in bindingChanges()) {
            val exactBeforeFault = f.genesis.controlRow()
            try {
                controlRefusal(leader.campaign) { selected ->
                    assertEquals(
                        1,
                        selected.update("UPDATE complaint_journal_control SET $column = ? WHERE data_scope_id = ?", value, ComplaintDataScope.LIVE.id),
                    )
                }
                assertEquals(prepared, publications.row(event))
                assertEquals(captured, f.row())
            } finally {
                f.genesis.restoreControl(exactBeforeFault) // Exact fault restoration only, never a synthetic G1/seal/checkpoint installer.
            }
            cases.assertFailure(assertThrows<PersistencePhaseException> { cases.resolve(leader.campaign) })
            assertTrue(wire.requests.isEmpty() && wire.kms.requests.isEmpty(), "Restoration cannot revive the SAME failed campaign.")
            leader = f.successor(leader.campaign)
            assertEquals(3, f.pooledPids.size) // Reobserve the current original pools after failures, not an assumed old PID.
        }

        controlRefusal(leader.campaign, ::expire)
        assertEquals(prepared, publications.row(event))
        val afterExpiredDiscovery = f.acquire() // Genuine acquisition of the expired row; no supplied token or lease receipt.
        assertTrue(afterExpiredDiscovery.receipt.token > leader.receipt.token)
        assertNotEquals(afterExpiredDiscovery.receipt.owner, leader.receipt.owner)
        publicationOnlyEvidenceAfterLoss(afterExpiredDiscovery.campaign)
        assertEquals("VERIFIED", publications.state(event), "An already-running immutable publication-only proof CAS may finish after DB lease loss.")
        val firstProof = publications.immutableRow(event)
        val traffic = wire.requests.size to wire.kms.requests.size
        cases.assertFailure(assertThrows<PersistencePhaseException> { cases.resolve(afterExpiredDiscovery.campaign) })
        assertEquals(traffic, wire.requests.size to wire.kms.requests.size)

        val successor = f.acquire()
        assertTrue(successor.receipt.token > afterExpiredDiscovery.receipt.token)
        assertNotEquals(successor.receipt.owner, afterExpiredDiscovery.receipt.owner)
        val manifest = cases.resolve(successor.campaign)
        cases.assertManifest(manifest)
        assertEquals(firstProof, publications.immutableRow(event))
        assertEquals(captured, f.row())
        assertEquals(outside, f.outsideRotationAndLease())
        cases.assertReleased()
    }

    private fun controlRefusal(campaign: CatalogCoordinatorLeaseCampaignV1, change: (JdbcTemplate) -> Unit) = independentTransaction { blocker, selected ->
        assertEquals(
            ComplaintDataScope.LIVE.id,
            selected.queryForObject(
                "SELECT data_scope_id FROM complaint_journal_control WHERE data_scope_id = ? FOR UPDATE", UUID::class.java, ComplaintDataScope.LIVE.id,
            ),
        )
        val holder = checkNotNull(selected.queryForObject("SELECT pg_backend_pid()", Int::class.java))
        OwnedCallerTestScope().use { callers ->
            val worker = callers.launch { runCatching { cases.resolve(campaign) } }
            try {
                val waiting = awaiting(selected, holder, "complaint_journal_control", "complaint_journal_publications")
                assertWait(waiting)
                assertTrue(wire.requests.isEmpty() && wire.kms.requests.isEmpty())
                change(selected)
                assertPromptRelease(waiting, selected)
                blocker.commit()
            } finally {
                blocker.rollback()
            }
            cases.assertFailure(assertInstanceOf(PersistencePhaseException::class.java, worker.value().exceptionOrNull()))
            cases.assertReleased()
        }
    }

    private fun publicationOnlyEvidenceAfterLoss(campaign: CatalogCoordinatorLeaseCampaignV1) = independentTransaction { blocker, selected ->
        val event = publications.cutoffEvents.single()
        assertEquals(
            event.route.eventId,
            selected.queryForObject(
                "SELECT event_id FROM complaint_journal_publications WHERE event_id = ? FOR UPDATE", String::class.java, event.route.eventId,
            ),
        )
        val holder = checkNotNull(selected.queryForObject("SELECT pg_backend_pid()", Int::class.java))
        OwnedCallerTestScope().use { callers ->
            val worker = callers.launch { runCatching { cases.resolve(campaign) } }
            try {
                val waiting = awaiting(selected, holder, "complaint_journal_publications", "complaint_journal_control")
                assertWait(waiting)
                // This independent control UPDATE must not be blocked by a backward control lock in VERIFY.
                expire(selected)
                assertPromptRelease(waiting, selected)
                blocker.commit()
            } finally {
                blocker.rollback()
            }
            cases.assertFailure(assertInstanceOf(PersistencePhaseException::class.java, worker.value().exceptionOrNull()))
            assertTrue(wire.requests.any { it.kind == "GET" }, "The real readback preceded the publication-only CAS wait.")
            assertEquals(1, wire.generated())
            cases.assertReleased()
        }
    }

    private fun expire(selected: JdbcTemplate) {
        assertEquals(
            1,
            selected.update(
                "UPDATE complaint_journal_control SET lease_expires_at = clock_timestamp() - interval '1 second' " +
                    "WHERE data_scope_id = ? AND lease_owner IS NOT NULL",
                ComplaintDataScope.LIVE.id,
            ),
        )
    }

    private fun awaiting(selected: JdbcTemplate, holder: Int, table: String, forbidden: String): LockObservation {
        var observation: LockObservation? = null
        awaitLifecycleFact(2_000) {
            selected.execute("SELECT pg_stat_clear_snapshot()") // This independent holder is a transaction; never reuse its first activity snapshot.
            observation = selected.query(
                "SELECT a.pid, a.backend_start, a.query_start, s.ssl, s.version, s.bits, " +
                    "NOT EXISTS (SELECT 1 FROM pg_locks l LEFT JOIN pg_class c ON c.oid = l.relation WHERE l.pid = a.pid AND " +
                    "(l.locktype = 'advisory' OR c.relname IN (?, 'installation_deletion_receipts','complaint_idempotency_receipts'," +
                    "'complaint_capacity_counters','complaint_catalog_mutations','complaint_recovery_capacity_reservations'," +
                    "'complaint_test_runs','complaint_installation_ids','app_installations','complaint_resource_ids','complaints','audit_log'," +
                    "'complaint_deletion_journal_applied'))) AS only_selected " +
                    "FROM pg_stat_activity a JOIN pg_stat_ssl s USING (pid) WHERE a.datname = current_database() AND a.usename = ? " +
                    "AND ? = ANY(pg_blocking_pids(a.pid)) AND position(? IN a.query) > 0 AND position('FOR UPDATE' IN a.query) > 0 " +
                    "AND EXISTS (SELECT 1 FROM pg_locks l WHERE l.pid = a.pid AND NOT l.granted AND l.locktype = 'transactionid')",
                { row, _ ->
                    LockObservation(
                        PgLifecycleDatabaseSession(row.getInt("pid"), row.getTimestamp("backend_start").toInstant()),
                        row.getTimestamp("query_start").toInstant(), row.getBoolean("only_selected"),
                        row.getBoolean("ssl") && row.getString("version") in setOf("TLSv1.2", "TLSv1.3") && row.getInt("bits") >= 128,
                    )
                },
                forbidden, PgLifecycleDatabaseSettings.CANDIDATE, holder, table,
            ).singleOrNull()
            observation != null
        }
        return checkNotNull(observation)
    }

    private fun assertWait(waiting: LockObservation) {
        assertTrue(waiting.session.pid > 0 && waiting.session.backendStart != Instant.EPOCH)
        assertTrue(waiting.secure)
        assertTrue(waiting.onlySelected, "Actual waiter holds neither the opposite control/publication class nor receipt/counter/domain/advisory locks.")
    }

    private fun assertPromptRelease(waiting: LockObservation, selected: JdbcTemplate) {
        val now = checkNotNull(selected.queryForObject("SELECT clock_timestamp()", { row, _ -> row.getTimestamp(1).toInstant() }))
        assertTrue(
            Duration.between(waiting.queryStarted, now).toMillis() < 75,
            "Release within the actual 100ms lock budget; a lock timeout is not a binding/lease refusal proof.",
        )
    }

    private fun <T> independentTransaction(action: (Connection, JdbcTemplate) -> T): T = checkNotNull(f.observer.dataSource).connection.use { connection ->
        connection.autoCommit = false
        val selected = JdbcTemplate(SingleConnectionDataSource(connection, true)).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
        try {
            action(connection, selected)
        } finally {
            connection.rollback()
        }
    }

    private fun bindingChanges(): List<Pair<String, Any>> = listOf(
        "desired_generation" to 8L,
        "desired_configuration_hash" to ByteArray(32) { 91 },
        "database_identity" to UUID.randomUUID(),
        "restore_identity" to UUID.randomUUID(),
        "event_writer_generation" to UUID.randomUUID(),
        "accepted_catalog_generation" to 2L,
        "accepted_catalog_hash" to ByteArray(32) { 92 },
        "trust_bundle_hash" to ByteArray(32) { 93 },
        "catalog_writer_generation" to UUID.randomUUID(),
    ) // Schema=1 and exact LIVE scope are fixed by the existing schema/producer, not a caller-selectable codec mode.

    private data class LockObservation(val session: PgLifecycleDatabaseSession, val queryStarted: Instant, val onlySelected: Boolean, val secure: Boolean)
}
