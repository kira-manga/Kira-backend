package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.DeleteAllCounter
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJdbcTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteLiteralCharges
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.S3CatalogReadbackFixture
import me.manga.kira.backend.complaint.catalog.SignedActivationObservation
import me.manga.kira.backend.complaint.catalog.withTestActiveFirstCut
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.infrastructure.journal.TestAdminDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteAllJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.ADMIN
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.mapper
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredHttpStartupCasesV1.StartedHttpView
import me.manga.kira.backend.complaint.infrastructure.transaction.DeletionPersistenceAdmission
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Duration
import java.util.HexFormat
import java.util.UUID

/** Request and comparison bytes only, never an authenticated actor, grant or deletion work. */
internal data class RegisteredAdminDeleteHttpAttemptV1(val targets: List<Pair<UUID, Long>>, val batch: Boolean = targets.size > 1,
    val key: UUID = UUID.randomUUID()) {
    val ids: List<UUID> get() = targets.map { it.first }.sortedBy(UUID::toString)
    val operation: String get() = if (batch) "ADMIN_BATCH_DELETE" else "ADMIN_DELETE"
    fun tag(target: Pair<UUID, Long>): String = "\"complaint-${target.first}-v${target.second}\""
}

/** Reuses the existing genuine Admin/password/current/checkpoint view; startup alone creates SQL owners. */
internal fun withRegisteredCompleteHttp(tls: VersionBoundPersistenceConnectedFixture, oldStatusOnly: Boolean = false,
    action: (TestRegisteredCompleteHttpFixtureV1) -> Unit) {
    val raw = TestRegisteredCompleteHttpRawFixtureV1()
    withTestActiveFirstCut(tls, ordinaryRawHttp = raw.factories, globalScanBeforeActivation = true) { first ->
        val configuration = first.process.canonicalBytes()
        val startup = if (oldStatusOnly) first.assembly.beginRegisteredAdminContentStatusBatchStatusHttpStartup(first.registration)
            else first.assembly.beginRegisteredCompleteHttpStartup(first.registration)
        startup.use {
            startup.start()
            StartedHttpView(first, startup, if (oldStatusOnly) TestRegisteredCompleteHttpFixtureV1.OLD_PATHS else TestRegisteredCompleteHttpFixtureV1.PATHS).use { web ->
                withRegisteredAdminContentFixtureV1(first, raw.ordinary, raw.checkpoint, web) { admin ->
                    val f = TestRegisteredCompleteHttpFixtureV1(admin, raw, oldStatusOnly); raw.fixture = f
                    assertArrayEquals(configuration, first.process.canonicalBytes())
                    try { action(f) } finally {
                        f.assertReleased()
                        // Disposable teardown AFTER assertions/B. No new request follows this cleanup.
                        for (table in TestRegisteredCompleteHttpFixtureV1.DELETION_TABLES) f.jdbc.update("DELETE FROM $table WHERE data_scope_id = ?", f.scope)
                        f.jdbc.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND action IN " +
                            "('COMPLAINT_REPLIED','COMPLAINT_DELETE_AUTHORIZED','COMPLAINT_DELETED','COMPLAINT_RECOVERY_APPLIED')", f.scope)
                    }
                }
                startup.close(); web.assertDisposed(nativeStillActive = true)
                if (!oldStatusOnly) {
                    for (field in listOf("ownerDeletePublisherCloseReturned", "ownerDeleteAllPublisherCloseReturned", "adminDeletePublisherCloseReturned")) {
                        assertTrue(poolTestField<Boolean>(startup, field), "Original $field must return before cleanup is proven.")
                    }
                    assertTrue(poolTestField<TestOwnerDeleteJournalPublisherFactoryV1>(startup, "ownerDeletePublisher").isClosed())
                    assertTrue(poolTestField<TestOwnerDeleteAllJournalPublisherFactoryV1>(startup, "ownerDeleteAllPublisher").isClosed())
                    assertTrue(poolTestField<TestAdminDeleteJournalPublisherFactoryV1>(startup, "adminDeletePublisher").isClosed())
                }
                raw.fixture = null
            }
        }
        startup.requireCleanupProven()
    }
}

internal class TestRegisteredCompleteHttpFixtureV1(val admin: TestRegisteredAdminContentHttpFixtureV1,
    val raw: TestRegisteredCompleteHttpRawFixtureV1, oldStatusOnly: Boolean) {
    val first get() = admin.first
    val web get() = admin.web
    val jdbc get() = admin.observer
    val scope get() = first.scope
    private val deletionOwner get() = poolTestField<PersistencePhaseOwnership>(web.startup, "deletionOwnership")
    private val deletionJdbc get() = poolTestField<JdbcTemplate>(web.startup, "deletionJdbc")
    init {
        if (oldStatusOnly) assertNull(poolTestField<Any?>(web.startup, "deletionOwnership")) else {
            assertNotSame(deletionOwner, poolTestField<PersistencePhaseOwnership>(web.startup, "ownership"))
            assertSame(first.process.pools.deletion, deletionJdbc.dataSource)
            assertSame(first.process.pools.deletion, (deletionOwner.manager as GuardedJdbcTransactionManager).dataSource)
            first.registration.requireInitialDeletionPhaseResources(deletionOwner, deletionJdbc)
            for (field in listOf("ownerDeleteResources", "ownerDeleteAllResources", "adminDeleteResources", "adminDeletePublisher")) {
                checkNotNull(poolTestField<Any?>(web.startup, field))
            }
        }
    }

    fun delete(attempt: RegisteredAdminDeleteHttpAttemptV1, proof: String? = null, bearer: String? = admin.admin()): HttpResponse<ByteArray> {
        if (attempt.batch) return web.post("$ADMIN/batch?dataScopeId=$scope", mapper.writeValueAsBytes(mapOf(
            "action" to "DELETE", "targets" to attempt.targets.map { mapOf("id" to it.first.toString(), "actionTag" to attempt.tag(it)) })), bearer, attempt.key, proof)
        val target = attempt.targets.single()
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${web.port}$ADMIN/${target.first}?dataScopeId=$scope"))
            .timeout(Duration.ofSeconds(5)).header("If-Match", attempt.tag(target)).header("X-Kira-Idempotency-Key", attempt.key.toString()).apply {
                bearer?.let { header("Authorization", "Bearer $it") }; proof?.let { header("X-Kira-Admin-Step-Up", it) }
            }.DELETE().build()
        return poolTestField<HttpClient>(web, "client").send(request, HttpResponse.BodyHandlers.ofByteArray()) // Original transport owner.
    }
    fun receipt(attempt: RegisteredAdminDeleteHttpAttemptV1) = jdbc.queryForMap("SELECT * FROM complaint_idempotency_receipts " +
        "WHERE data_scope_id = ? AND actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ?", scope, admin.users[0].id, attempt.key)
    fun publication(attempt: RegisteredAdminDeleteHttpAttemptV1) = jdbc.queryForMap("SELECT p.* FROM complaint_journal_publications p " +
        "JOIN complaint_idempotency_receipts n ON n.publication_ref = p.event_id WHERE p.data_scope_id = ? AND n.actor_kind = 'ADMIN' " +
        "AND n.actor_id = ? AND n.idempotency_key = ?", scope, admin.users[0].id, attempt.key)

    fun assertPending(attempt: RegisteredAdminDeleteHttpAttemptV1, grant: UUID, verified: Boolean) {
        val n = receipt(attempt); val p = publication(attempt)
        assertEquals("AUTHORIZED_DELETE", n["state"]); assertEquals(grant, n["consumed_grant_id"]); assertEquals(attempt.operation, n["operation"])
        for (field in listOf("outcome", "response_status", "ack_ids", "completed_at", "expires_at", "external_event_id")) assertNull(n[field], field)
        assertEquals(if (verified) "VERIFIED" else "PREPARED", p["state"])
        assertEquals(verified, p["verification_bytes"] != null); assertNull(p["applied_at"])
        assertEquals(attempt.operation, p["event_kind"]); assertEquals(attempt.ids.size, (p["target_count"] as Number).toInt())
        assertEquals(attempt.ids, jdbc.queryForList("SELECT id FROM complaints WHERE data_scope_id = ? AND id IN (${idSql(attempt)}) ORDER BY id::text", UUID::class.java, scope))
        assertEquals(attempt.ids, jdbc.queryForList("SELECT id FROM complaint_resource_ids WHERE data_scope_id = ? AND state = 'DELETION_PENDING' ORDER BY id::text", UUID::class.java, scope))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", Long::class.java, scope))
        assertEquals(mapOf("COMPLAINT_DELETE_AUTHORIZED" to attempt.ids.size.toLong()), deletionAudits()); admin.used(grant)
    }

    /** Distinct owner invocation AFTER HTTP release; serves only A's actual PUT and original KMS map. */
    fun completeThroughQueue(attempt: RegisteredAdminDeleteHttpAttemptV1, grant: UUID, record: TestRegisteredInitialDeletionNativeRecordV1) {
        assertReleased()
        val before = counters(); val n = receiptIdentity(attempt); val p = publicationIdentity(attempt); val consumed = admin.grantRow(grant)
        val identities = listOf("app_installations", "complaint_installation_ids").associateWith(admin::rows)
        val untouchedSql = "SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM complaints c WHERE data_scope_id = ? AND id NOT IN (${idSql(attempt)}) ORDER BY id"
        val untouched = jdbc.queryForList(untouchedSql, String::class.java, scope)
        val native = checkNotNull(raw.publisher); val requests = native.requests.size
        var original: TestActiveOwnerDeleteQueueV1? = null
        raw.queue.attachRegisteredHttp(first.process, record, ::assertSqlReleased, ::assertSqlReleased) { deadLetter ->
            assertFalse(deadLetter); assertEquals(TestActiveOwnerDeleteQueueStepV1.ACK, checkNotNull(original).step)
            assertSqlReleased(); assertApplied(attempt, grant, record)
        }.use {
            val b = TestActiveOwnerDeleteQueueV1.withHttpFixture(first.registration, first.assembly, deletionOwner, deletionJdbc,
                web.context.getBean(AuditService::class.java), first.p.f.http::readClient, SignedActivationObservation.WALL_CLOCK).also { original = it }
            assertSame(deletionOwner, poolTestField<PersistencePhaseOwnership>(b, "deletionOwner"))
            assertSame(deletionJdbc, poolTestField<JdbcTemplate>(b, "deletionJdbc"))
            val result = b.poll(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
            assertEquals(scope, result.scope); assertEquals(1, result.primaryAcknowledged); assertEquals(0, result.dlqAcknowledged)
            assertReleased(); assertApplied(attempt, grant, record)
            assertEquals(listOf("synthetic-primary-receipt-1"), raw.queue.ackRequests)
            assertEquals(listOf("GET"), raw.queue.requests.map { it.kind }); assertEquals(1, raw.queue.kms.requests.size)
            assertEquals(requests, native.requests.size, "B must not publish another object.")
            assertEquals(n, receiptIdentity(attempt)); assertEquals(p, publicationIdentity(attempt)); assertEquals(consumed, admin.grantRow(grant))
            assertEquals(identities, identities.keys.associateWith(admin::rows))
            assertEquals(untouched, jdbc.queryForList(untouchedSql, String::class.java, scope))
            assertEquals(true, jdbc.queryForObject("SELECT state = 'SETTLED' AND primary_acked = 1 AND dlq_acked = 0 " +
                "FROM complaint_test_active_queue_observations WHERE data_scope_id = ?", Boolean::class.java, scope))
            assertNull(first.control()["lease_owner"]); assertNull(first.control()["lease_expires_at"])
            assertEquals(mapOf("COMPLAINT_DELETE_AUTHORIZED" to attempt.ids.size.toLong(), "COMPLAINT_DELETED" to attempt.ids.size.toLong(),
                "COMPLAINT_RECOVERY_APPLIED" to 1L), deletionAudits())
            val used = OwnerDeleteLiteralCharges.appliedOnly + OwnerDeleteLiteralCharges.audit.scaled(attempt.ids.size.toLong() + 1)
            val reservation = jdbc.queryForMap("SELECT state, reserved_amounts::text, converted_amounts::text FROM complaint_recovery_capacity_reservations WHERE data_scope_id = ?", scope)
            assertEquals("PARTIAL", reservation["state"]); assertEquals(vectorText(promise(attempt)), reservation["reserved_amounts"])
            assertEquals(vectorText(used), reservation["converted_amounts"])
            val after = counters()
            before.forEach { (counter, old) ->
                val observed = if (counter === ComplaintCapacityCounter.STORAGE_BYTES) 8192L else 0L
                val refund = OwnerDeleteLiteralCharges.content[counter] * attempt.ids.size
                assertEquals(old.copy(free = old.free + refund - observed, actual = old.actual + used[counter] - refund + observed,
                    recovery = old.recovery - used[counter]), after.getValue(counter), counter.storedName)
            }
        }
    }

    private fun assertApplied(attempt: RegisteredAdminDeleteHttpAttemptV1, grant: UUID, record: TestRegisteredInitialDeletionNativeRecordV1) {
        val n = receipt(attempt); val p = publication(attempt)
        assertEquals("COMPLETED", n["state"]); assertEquals("APPLIED", n["outcome"]); assertEquals(grant, n["consumed_grant_id"])
        assertEquals(if (attempt.batch) 200 else 204, (n["response_status"] as Number).toInt()); assertEquals("APPLIED", p["state"])
        assertEquals(record.stored.version, n["external_object_version"]); assertEquals(record.event.route.eventId, n["external_event_id"])
        assertEquals(2L, n["external_epoch"]); assertEquals(record.stored.version, p["object_version"])
        val hash = HexFormat.of().parseHex(Sha256.hex(record.stored.bytes))
        assertArrayEquals(hash, n["external_ciphertext_hash"] as ByteArray); assertArrayEquals(hash, p["ciphertext_hash"] as ByteArray)
        assertEquals(true, jdbc.queryForObject("SELECT ${if (attempt.batch) "ack_ids = target_ids" else "ack_ids IS NULL"} AND ack_versions IS NULL " +
            "AND expires_at = completed_at + interval '192 hours' FROM complaint_idempotency_receipts WHERE actor_id = ? AND idempotency_key = ?",
            Boolean::class.java, admin.users[0].id, attempt.key))
        assertEquals(p["created_at"], n["authorized_at"])
        assertEquals((n["completed_at"] as Timestamp).toInstant().plus(Duration.ofHours(192)), (n["expires_at"] as Timestamp).toInstant())
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM complaints WHERE data_scope_id = ? AND id IN (${idSql(attempt)})", Long::class.java, scope))
        assertEquals(attempt.ids, jdbc.queryForList("SELECT id FROM complaint_resource_ids WHERE data_scope_id = ? AND state = 'DELETED' ORDER BY id::text", UUID::class.java, scope))
        val applied = jdbc.queryForList("SELECT * FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", scope).single()
        assertEquals(record.stored.key, applied["object_key"]); assertEquals(record.stored.version, applied["object_version"])
        assertEquals(record.event.route.eventId, applied["event_id"]); assertArrayEquals(hash, applied["ciphertext_hash"] as ByteArray)
    }
    private fun receiptIdentity(attempt: RegisteredAdminDeleteHttpAttemptV1) = jdbc.queryForObject(
        "SELECT (to_jsonb(n) - ARRAY['state','outcome','response_status','ack_ids','external_event_id','external_epoch','external_object_version','external_ciphertext_hash','completed_at','expires_at'])::text " +
            "FROM complaint_idempotency_receipts n WHERE actor_id = ? AND idempotency_key = ?", String::class.java, admin.users[0].id, attempt.key)
    private fun publicationIdentity(attempt: RegisteredAdminDeleteHttpAttemptV1) = jdbc.queryForObject(
        "SELECT (to_jsonb(p) - ARRAY['state','applied_at'])::text FROM complaint_journal_publications p WHERE event_id = ? AND data_scope_id = ?",
        String::class.java, publication(attempt)["event_id"], scope)
    private fun deletionAudits() = jdbc.query("SELECT action, count(*) FROM audit_log WHERE complaint_data_scope_id = ? " +
        "AND action IN ('COMPLAINT_DELETE_AUTHORIZED','COMPLAINT_DELETED','COMPLAINT_RECOVERY_APPLIED') GROUP BY action",
        { row, _ -> row.getString(1) to row.getLong(2) }, scope).toMap()
    fun counters(): Map<ComplaintCapacityCounter, DeleteAllCounter> = jdbc.query("SELECT name, free_units, actual_units, recovery_reserved_units, test_reserved_units, " +
        "(to_jsonb(c) - ARRAY['free_units','actual_units','recovery_reserved_units','updated_at'])::text FROM complaint_capacity_counters c ORDER BY ordinal",
        { row, _ -> ComplaintCapacityCounter.entries.single { it.storedName == row.getString(1) } to DeleteAllCounter(row.getLong(2), row.getLong(3), row.getLong(4), row.getLong(5), row.getString(6)) }).toMap()
    fun image() = admin.image() + DELETION_TABLES.associateWith { table ->
        jdbc.queryForList("SELECT jsonb_build_array(to_jsonb(r), r.xmin::text)::text FROM $table r WHERE data_scope_id = ? ORDER BY to_jsonb(r)::text", String::class.java, scope)
    }
    fun assertSqlReleased() {
        requireConnectionFree(); assertNull(PersistencePhaseOwnership.current()); assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, web.admission.activeOwners())
        assertEquals(0, poolTestField<DeletionPersistenceAdmission?>(web.startup, "deletionAdmission")?.activeOwners()?.totalOwners ?: 0)
        assertEquals(0, first.process.pools.catalogCoordinator.activeSnapshotOwners())
    }
    fun assertReleased() { web.assertRequestsReleased(); assertSqlReleased(); raw.assertDisposed(); assertEquals(0L, first.process.publicationLanes.activeOwners().totalOwners) }
    companion object {
        val OLD_PATHS = TestRegisteredAdminContentHttpFixtureV1.PATHS + setOf("$ADMIN/{id}/status", "$ADMIN/{id}/closure", "$ADMIN/batch")
        val PATHS = OLD_PATHS + setOf(ComplaintInstallationRoutes.ME, ComplaintInstallationRoutes.DELETE_ALL,
            "${ComplaintInstallationRoutes.HISTORY}/{id}/replies", "${ComplaintInstallationRoutes.HISTORY}/{id}/content")
        val DELETION_TABLES = listOf("complaint_test_active_queue_observations", "complaint_idempotency_receipts", "complaint_recovery_capacity_reservations",
            "complaint_deletion_journal_retirements", "complaint_deletion_journal_applied", "complaint_journal_publications")
        fun authorization(attempt: RegisteredAdminDeleteHttpAttemptV1) = OwnerDeleteLiteralCharges.authorization + OwnerDeleteLiteralCharges.audit.scaled(attempt.ids.size.toLong() - 1)
        fun promise(attempt: RegisteredAdminDeleteHttpAttemptV1): ComplaintCapacityVector {
            require(attempt.ids.size in 1..2) // Representative genuine distinct-owner inputs; max50 is carried, not reimplemented.
            return OwnerDeleteLiteralCharges.promise + (OwnerDeleteLiteralCharges.installation + OwnerDeleteLiteralCharges.resource + OwnerDeleteLiteralCharges.audit).scaled(attempt.ids.size.toLong() - 1)
        }
        private fun idSql(attempt: RegisteredAdminDeleteHttpAttemptV1) = attempt.ids.joinToString(",") { "'$it'::uuid" }
        private fun vectorText(vector: ComplaintCapacityVector) = vector.toLongArray().joinToString(",", "{", "}")
    }
}
