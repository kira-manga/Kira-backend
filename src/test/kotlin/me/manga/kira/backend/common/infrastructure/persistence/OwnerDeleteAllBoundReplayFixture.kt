package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightResult
import me.manga.kira.backend.complaint.infrastructure.BoundOwnerDeleteAllReplayV1
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllApplyV1
import me.manga.kira.backend.complaint.infrastructure.InstallationDeletionPreflightSnapshot
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintInstallationDeletionPreflightStore
import me.manga.kira.backend.complaint.infrastructure.journal.OwnerDeleteAllVerificationCodecV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationDeletionPreflightPhaseExecutor
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import me.manga.kira.backend.security.OwnerDeleteAllJournalEventV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator

/** Actual completed pipeline plus a separately owned ordinary reader; no fabricated Completed or replay. */
internal class OwnerDeleteAllBoundReplayFixture(
    val connected: OwnerDeleteAllContinuationFixture,
    val committed: CommittedOwnerDeleteAllApplyV1,
    val event: OwnerDeleteAllJournalEventV1 = connected.publisher.event,
    private val publisher: OwnerDeleteAllJournalPublisherFixture = connected.publisher,
) {
    val auth get() = connected.auth
    val candidate get() = connected.candidate
    val jdbc = BoundReplayPreflightJdbc(this)
    val store = JdbcComplaintInstallationDeletionPreflightStore(jdbc)
    val preflights = ComplaintInstallationDeletionPreflightPhaseExecutor(auth.base.ordinary.ownership, store)
    val proofCodec = OwnerDeleteAllVerificationCodecV1(auth.routing)

    fun comparison(): InstallationDeletionPreflightResult.Completed = assertInstanceOf(
        InstallationDeletionPreflightResult.Completed::class.java,
        preflights.preflight(candidate),
    )

    fun bind(comparison: InstallationDeletionPreflightResult.Completed): BoundOwnerDeleteAllReplayV1 =
        preflights.bindReplay(comparison, auth.routing, auth.codec)

    fun assertBound(comparison: InstallationDeletionPreflightResult.Completed): BoundOwnerDeleteAllReplayV1 {
        val reads = jdbc.queries
        val state = auth.state()
        val statements = connected.statements.toList()
        val providerCalls = publisher.requests.size to publisher.kms.requests.size
        val result = bind(comparison)
        assertEquals(committed.completedAt, result.completedAt)
        assertEquals(committed.expiresAt, result.expiresAt)
        assertEquals(reads, jdbc.queries)
        assertEquals(state, auth.state())
        assertEquals(statements, connected.statements.toList())
        assertEquals(providerCalls, publisher.requests.size to publisher.kms.requests.size)
        assertReleased()
        return result
    }

    fun eventBytes(): ByteArray = bytes("event_bytes")
    fun proofBytes(): ByteArray = bytes("verification_bytes")

    private fun bytes(column: String): ByteArray = checkNotNull(
        auth.observer.queryForObject(
            "SELECT $column FROM complaint_journal_publications WHERE event_id = ?",
            ByteArray::class.java,
            event.route.eventId,
        ),
    )

    fun replaceEvent(bytes: ByteArray) = replace("event_bytes", "semantic_hash", bytes)
    fun replaceProof(bytes: ByteArray) = replace("verification_bytes", "verification_hash", bytes)

    private fun replace(column: String, hash: String, bytes: ByteArray) {
        assertEquals(
            1,
            auth.observer.update(
                "UPDATE complaint_journal_publications SET $column = ?, $hash = ? WHERE event_id = ?",
                bytes,
                ownerDeleteAllTestDigest(bytes),
                event.route.eventId,
            ),
        )
    }

    fun assertReleased() {
        assertTrue(jdbc.observations.all { it.lease.completion.quiescent() })
        assertEquals(0, jdbc.updates)
        assertEquals(publisher.s3ClientsCreated, publisher.s3ClientsClosed)
        assertEquals(publisher.kms.createdClients, publisher.kms.closedClients)
        publisher.assertClosedExchanges()
        connected.assertReleased()
    }
}

/** Wraps only the actual fixed cursor. Fault actions alter real independent rows, never its result. */
internal class BoundReplayPreflightJdbc(private val fixture: OwnerDeleteAllBoundReplayFixture) : JdbcTemplate(fixture.auth.base.ordinary.pool) {
    val observations = mutableListOf<StepUpPhaseObservation>()
    var queries = 0
        private set
    var updates = 0
        private set
    var beforeMap: () -> Unit = {}

    init {
        exceptionTranslator = SQLExceptionSubclassTranslator()
    }

    override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> {
        queries++
        return super.query(
            sql,
            RowMapper<T> { row, index ->
                fixture.auth.preserveAssertions {
                    assertEquals(InstallationDeletionPreflightSnapshot.SQL, sql)
                    if (index == 0) {
                        observations.add(observeStepUpPhase(fixture.auth.base.ordinary))
                        beforeMap()
                    }
                }
                rowMapper.mapRow(row, index)
            },
            *args,
        )
    }

    override fun update(sql: String, vararg args: Any?): Int {
        updates++
        return super.update(sql, *args)
    }
}
