package me.manga.kira.backend.complaint.infrastructure.reconciliation

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.DeleteAllCounter
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJdbcTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteLiteralCharges
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.S3CatalogReadbackFixture
import me.manga.kira.backend.complaint.catalog.SignedActivationObservation
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutFixtureV1
import me.manga.kira.backend.complaint.catalog.withTestActiveFirstCut
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredHttpStartupCasesV1.StartedHttpView
import me.manga.kira.backend.complaint.infrastructure.transaction.DeletionPersistenceAdmission
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import me.manga.kira.backend.security.InstallationJwtCodec
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** HTTP request/expected targets only, never an admitted deletion or a published work capability. */
internal class RegisteredOwnerDeleteAllHttpRequestV1(val installation: ScopedInstallationId, val targets: List<UUID>,
    val key: UUID = UUID.randomUUID(), val credentialVersion: Long = 1)

/** Startup, not a lower identity/deletion wrapper, creates and retains the original owner cohort. */
internal fun withRegisteredOwnerDeleteAllHttp(tls: VersionBoundPersistenceConnectedFixture, shortFreshness: Boolean = false,
    action: (TestRegisteredOwnerDeleteAllHttpFixtureV1) -> Unit) {
    val raw = TestRegisteredOwnerDeleteAllHttpRawFixtureV1(shortFreshness)
    withTestActiveFirstCut(tls, ordinaryRawHttp = raw.factories, globalScanBeforeActivation = true) { first ->
        val configuration = first.process.canonicalBytes()
        val startup = first.assembly.beginRegisteredOwnerDeleteAllHttpStartup(first.registration)
        startup.use {
            assertSame(startup, poolTestField<Any>(first.assembly, "httpStartup"))
            startup.start()
            StartedHttpView(first, startup, TestRegisteredOwnerDeleteAllHttpFixtureV1.PATHS).use { web ->
                val f = TestRegisteredOwnerDeleteAllHttpFixtureV1(first, web, raw); raw.fixture = f
                assertArrayEquals(configuration, first.process.canonicalBytes())
                AutoCloseable {
                    f.assertReleased()
                    // Disposable fixture teardown only, after all requests/B and assertions. No history,
                    // quota, verifier or proof is reset to make another product attempt eligible.
                    for (table in TestRegisteredOwnerDeleteAllHttpFixtureV1.CLEANUP) f.jdbc.update("DELETE FROM $table WHERE data_scope_id = ?", f.scope)
                    f.jdbc.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND action IN " +
                        "('COMPLAINT_CREATED','COMPLAINT_INSTALLATION_DELETE_AUTHORIZED','COMPLAINT_DELETED','COMPLAINT_RECOVERY_APPLIED')", f.scope)
                }.use { action(f) }
                startup.close(); web.assertDisposed(nativeStillActive = true)
                raw.fixture = null
            }
        }
        startup.requireCleanupProven()
    }
}

internal class TestRegisteredOwnerDeleteAllHttpFixtureV1(val first: TestActiveFirstCutFixtureV1, val web: StartedHttpView,
    val raw: TestRegisteredOwnerDeleteAllHttpRawFixtureV1) {
    val jdbc = first.observer
    val scope = first.scope
    val deletionOwner = poolTestField<PersistencePhaseOwnership>(web.startup, "deletionOwnership")
    val deletionJdbc = poolTestField<JdbcTemplate>(web.startup, "deletionJdbc")
    val deletionAdmission = poolTestField<DeletionPersistenceAdmission>(web.startup, "deletionAdmission")
    val audit = web.context.getBean(AuditService::class.java)
    private var checkpoint: TestActiveInitialCheckpointFixtureV1? = null

    init {
        assertNotSame(deletionOwner, poolTestField<PersistencePhaseOwnership>(web.startup, "ownership"))
        assertSame(first.process.pools.deletion, deletionJdbc.dataSource)
        assertSame(first.process.pools.deletion, (deletionOwner.manager as GuardedJdbcTransactionManager).dataSource)
        first.registration.requireInitialDeletionPhaseResources(deletionOwner, deletionJdbc)
        assertEquals(PersistenceLifecycleObservation.READY, first.process.pools.deletion.observePreparation())
    }

    fun enroll(actor: ScopedInstallationId): String {
        val body = mapper.writeValueAsBytes(mapOf("installationId" to actor.id.toString(), "expectedDataScopeId" to scope.toString(),
            "platform" to "ANDROID", "secret" to SECRET))
        val response = checked(web.post(ComplaintInstallationRoutes.ENROLLMENT, body), 201)
        assertEquals(ComplaintInstallationRoutes.ME, header(response, "Location"))
        return mapper.readTree(response.body())["accessToken"].textValue().also {
            assertEquals(actor, InstallationJwtCodec(first.process.consumers.jwt.installationKeyRing, Clock.systemUTC()).verify(it).installation)
            web.assertRequestsReleased()
        }
    }
    fun withCheckpoint(action: () -> Unit) {
        val captured = first.capture(); first.awaitNativeReclaimed()
        TestActiveOrdinarySealFixtureV1(first, captured, raw.ordinary).use { sealer ->
            val verified = sealer.seal(); sealer.assertReleased()
            awaitInitialCheckpointLeaseExpiry(sealer.observer, sealer.scope)
            TestActiveInitialCheckpointFixtureV1(sealer, verified, raw.checkpoint).use { current ->
                current.checkpoint(); current.assertReleased() // Completed is discarded, never supplied to HTTP.
                checkpoint = current
                try { action() } finally { checkpoint = null }
            }
        }
    }
    fun create(id: UUID, token: String) {
        val before = counters()
        val body = mapper.writeValueAsBytes(mapOf("id" to id.toString(), "type" to "TECHNICAL", "subject" to "Registered ALL subject",
            "body" to "Registered ALL body", "metadata" to mapOf("appVersion" to null, "osVersion" to "fixture-os", "manufacturer" to "", "deviceModel" to "")))
        val response = checked(web.post(ComplaintInstallationRoutes.HISTORY, body, token, UUID.randomUUID()), 201)
        assertEquals("""{"id":"$id","version":1}""", response.body().decodeToString())
        assertEquals("${ComplaintInstallationRoutes.HISTORY}/$id", header(response, "Location"))
        assertEquals("\"complaint-$id-v1\"", header(response, "ETag"))
        assertCharge(before, counters(), ComplaintCapacityCharges.OWNER_CREATE)
    }
    fun delete(input: RegisteredOwnerDeleteAllHttpRequestV1, secret: String = SECRET, version: Long = input.credentialVersion,
        key: UUID? = input.key, bearer: String? = null, path: String = ComplaintInstallationRoutes.DELETE_ALL): HttpResponse<ByteArray> =
        web.post(path, mapper.writeValueAsBytes(mapOf("installationId" to input.installation.id.toString(), "dataScopeId" to scope.toString(),
            "secret" to secret, "credentialVersion" to version)), bearer, key)

    fun publication(input: RegisteredOwnerDeleteAllHttpRequestV1) = jdbc.queryForMap("SELECT p.* FROM complaint_journal_publications p JOIN installation_deletion_receipts r " +
        "ON r.publication_ref = p.event_id WHERE p.data_scope_id = ? AND r.installation_id = ? AND r.deletion_key = ?", scope, input.installation.id, input.key)
    fun receipt(input: RegisteredOwnerDeleteAllHttpRequestV1) = jdbc.queryForMap("SELECT * FROM installation_deletion_receipts WHERE data_scope_id = ? AND installation_id = ?", scope, input.installation.id)
    fun credential(input: RegisteredOwnerDeleteAllHttpRequestV1) = jdbc.queryForMap("SELECT * FROM app_installations WHERE id = ? AND data_scope_id = ?", input.installation.id, scope)

    fun assertPending(input: RegisteredOwnerDeleteAllHttpRequestV1, verified: Boolean) {
        val receipt = receipt(input); val publication = publication(input)
        assertEquals("AUTHORIZED_DELETE", receipt["state"]); assertNull(receipt["response_status"]); assertNull(receipt["outcome"])
        assertNull(receipt["completed_at"]); assertNull(receipt["expires_at"])
        assertEquals(if (verified) "VERIFIED" else "PREPARED", publication["state"])
        assertEquals(verified, publication["verification_bytes"] != null); assertNull(publication["applied_at"])
        assertEquals("OWNER_DELETE_ALL", publication["event_kind"]); assertEquals(input.targets.size, (publication["target_count"] as Number).toInt())
        for (table in listOf("complaint_installation_ids", "app_installations")) assertEquals("DELETION_PENDING",
            jdbc.queryForObject("SELECT state FROM $table WHERE id = ? AND data_scope_id = ?", String::class.java, input.installation.id, scope))
        assertEquals(input.credentialVersion, credential(input)["credential_version"])
        assertEquals(input.targets.size.toLong(), jdbc.queryForObject("SELECT count(*) FROM complaints WHERE owner_id = ? AND data_scope_id = ?", Long::class.java, input.installation.id, scope))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", Long::class.java, scope))
        assertEquals(mapOf("COMPLAINT_INSTALLATION_DELETE_AUTHORIZED" to 1L), deletionAudits())
    }

    fun assertApplied(input: RegisteredOwnerDeleteAllHttpRequestV1, record: TestRegisteredInitialDeletionNativeRecordV1) {
        val receipt = receipt(input); val publication = publication(input); val credential = credential(input)
        assertEquals("APPLIED", publication["state"]); assertEquals("COMPLETED", receipt["state"])
        assertEquals("APPLIED", receipt["outcome"]); assertEquals(204, (receipt["response_status"] as Number).toInt())
        assertEquals(record.stored.version, receipt["external_object_version"]); assertEquals(record.event.route.eventId, receipt["external_event_id"])
        assertEquals(record.stored.version, publication["object_version"])
        val hash = HexFormat.of().parseHex(Sha256.hex(record.stored.bytes))
        assertArrayEquals(hash, publication["ciphertext_hash"] as ByteArray); assertArrayEquals(hash, receipt["external_ciphertext_hash"] as ByteArray)
        assertEquals("DELETED", credential["state"]); assertEquals(input.credentialVersion + 1, credential["credential_version"])
        listOf("platform", "owner_reference", "last_authenticated_at").forEach { assertNull(credential[it], it) }
        assertEquals("DELETED", jdbc.queryForObject("SELECT state FROM complaint_installation_ids WHERE id = ? AND data_scope_id = ?", String::class.java, input.installation.id, scope))
        assertEquals(input.targets.sortedBy(UUID::toString), jdbc.queryForList("SELECT id FROM complaint_resource_ids WHERE data_scope_id = ? AND state = 'DELETED' ORDER BY id::text", UUID::class.java, scope))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM complaints WHERE owner_id = ? AND data_scope_id = ?", Long::class.java, input.installation.id, scope))
        val applied = jdbc.queryForList("SELECT * FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", scope).single()
        assertEquals(record.stored.key, applied["object_key"]); assertEquals(record.stored.version, applied["object_version"])
        assertEquals(record.event.route.eventId, applied["event_id"]); assertArrayEquals(hash, applied["ciphertext_hash"] as ByteArray)
        val completed = (receipt["completed_at"] as Timestamp).toInstant()
        val expires = completed.plus(Duration.ofHours(192))
        assertEquals(publication["created_at"], receipt["authorized_at"])
        assertEquals(receipt["completed_at"], publication["applied_at"]); assertEquals(receipt["completed_at"], applied["applied_at"])
        assertEquals(receipt["completed_at"], credential["deleted_at"])
        assertEquals(receipt["completed_at"], jdbc.queryForObject("SELECT terminal_at FROM complaint_installation_ids WHERE id = ? AND data_scope_id = ?", Timestamp::class.java, input.installation.id, scope))
        assertEquals(expires, (receipt["expires_at"] as Timestamp).toInstant()); assertEquals(expires, (credential["verifier_expires_at"] as Timestamp).toInstant())
    }

    /** Genuine B is independently owned and invoked only after the HTTP exchange has ended. */
    fun completeThroughQueue(input: RegisteredOwnerDeleteAllHttpRequestV1, record: TestRegisteredInitialDeletionNativeRecordV1) {
        assertReleased()
        val before = counters(); val proof = publicationIdentity(input); val tuple = receiptIdentity(input)
        val verifier = credential(input)["secret_verifier"] as ByteArray
        val untouched = jdbc.queryForList("SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM complaints c WHERE data_scope_id = ? AND owner_id IS DISTINCT FROM ? ORDER BY id", String::class.java, scope, input.installation.id)
        var original: TestActiveOwnerDeleteQueueV1? = null
        raw.queue.attachRegisteredHttp(first.process, record, ::assertSqlReleased, ::assertSqlReleased) { deadLetter ->
            assertFalse(deadLetter); assertEquals(TestActiveOwnerDeleteQueueStepV1.ACK, checkNotNull(original).step)
            assertSqlReleased(); assertApplied(input, record)
        }.use {
            val selected = TestActiveOwnerDeleteQueueV1.withHttpFixture(first.registration, first.assembly, deletionOwner, deletionJdbc, audit,
                first.p.f.http::readClient, SignedActivationObservation.WALL_CLOCK).also { original = it }
            assertSame(deletionOwner, poolTestField<PersistencePhaseOwnership>(selected, "deletionOwner"))
            assertSame(deletionJdbc, poolTestField<JdbcTemplate>(selected, "deletionJdbc"))
            val completed = selected.poll(S3CatalogReadbackFixture.credentials, S3CatalogReadbackFixture.credentials)
            assertEquals(scope, completed.scope); assertEquals(1, completed.primaryAcknowledged); assertEquals(0, completed.dlqAcknowledged)
            raw.queue.assertDisposed(); assertReleased()
            assertEquals(listOf("synthetic-primary-receipt-1"), raw.queue.ackRequests)
            assertEquals(listOf("GET"), raw.queue.requests.map { it.kind }); assertEquals(1, raw.queue.kms.requests.size)
            assertEquals(proof, publicationIdentity(input), "B preserves the original ALL canonical event and native VERIFY proof.")
            assertEquals(tuple, receiptIdentity(input), "B preserves the exact original installation/version/key/fingerprint tuple.")
            assertArrayEquals(verifier, credential(input)["secret_verifier"] as ByteArray)
            assertEquals(untouched, jdbc.queryForList("SELECT jsonb_build_array(to_jsonb(c), c.xmin::text)::text FROM complaints c WHERE data_scope_id = ? AND owner_id IS DISTINCT FROM ? ORDER BY id", String::class.java, scope, input.installation.id))
            assertEquals(mapOf("COMPLAINT_INSTALLATION_DELETE_AUTHORIZED" to 1L, "COMPLAINT_DELETED" to input.targets.size.toLong(), "COMPLAINT_RECOVERY_APPLIED" to 1L), deletionAudits())
            val row = jdbc.queryForMap("SELECT state, primary_acked, dlq_acked FROM complaint_test_active_queue_observations WHERE data_scope_id = ?", scope)
            assertEquals("SETTLED", row["state"]); assertEquals(1, (row["primary_acked"] as Number).toInt()); assertEquals(0, (row["dlq_acked"] as Number).toInt())
            assertNull(first.control()["lease_owner"]); assertNull(first.control()["lease_expires_at"])
            val used = OwnerDeleteLiteralCharges.appliedOnly + OwnerDeleteLiteralCharges.audit.scaled(input.targets.size.toLong() + 1)
            val reservation = jdbc.queryForMap("SELECT state, reserved_amounts::text, converted_amounts::text FROM complaint_recovery_capacity_reservations WHERE data_scope_id = ?", scope)
            assertEquals("PARTIAL", reservation["state"]); assertEquals(vectorText(PROMISE), reservation["reserved_amounts"])
            assertEquals(vectorText(used), reservation["converted_amounts"])
            val after = counters()
            before.forEach { (counter, old) ->
                val observation = if (counter === ComplaintCapacityCounter.STORAGE_BYTES) 8192L else 0L
                val refund = OwnerDeleteLiteralCharges.content[counter] * input.targets.size
                assertEquals(old.copy(free = old.free + refund - observation, actual = old.actual + used[counter] - refund + observation,
                    recovery = old.recovery - used[counter]), after.getValue(counter), counter.storedName)
            }
        }
    }

    private fun publicationIdentity(input: RegisteredOwnerDeleteAllHttpRequestV1): String = checkNotNull(jdbc.queryForObject(
        "SELECT (to_jsonb(p) - ARRAY['state','applied_at'])::text FROM complaint_journal_publications p WHERE event_id = ? AND data_scope_id = ?",
        String::class.java, publication(input)["event_id"], scope))
    private fun receiptIdentity(input: RegisteredOwnerDeleteAllHttpRequestV1): String = checkNotNull(jdbc.queryForObject(
        "SELECT (to_jsonb(n) - ARRAY['state','outcome','response_status','external_event_id','external_epoch','external_object_version','external_ciphertext_hash','completed_at','expires_at'])::text " +
            "FROM installation_deletion_receipts n WHERE data_scope_id = ? AND installation_id = ?", String::class.java, scope, input.installation.id))
    private fun deletionAudits() = jdbc.query("SELECT action, count(*) FROM audit_log WHERE complaint_data_scope_id = ? " +
        "AND action IN ('COMPLAINT_INSTALLATION_DELETE_AUTHORIZED','COMPLAINT_DELETED','COMPLAINT_RECOVERY_APPLIED') GROUP BY action",
        { row, _ -> row.getString(1) to row.getLong(2) }, scope).toMap()
    fun counters(): Map<ComplaintCapacityCounter, DeleteAllCounter> = jdbc.query("SELECT name, free_units, actual_units, recovery_reserved_units, test_reserved_units, " +
        "(to_jsonb(c) - ARRAY['free_units','actual_units','recovery_reserved_units','updated_at'])::text AS preserved FROM complaint_capacity_counters c ORDER BY ordinal",
        { row, _ -> ComplaintCapacityCounter.entries.single { it.storedName == row.getString(1) } to DeleteAllCounter(row.getLong(2), row.getLong(3), row.getLong(4), row.getLong(5), row.getString(6)) }).toMap()
    fun image() = (CLEANUP + listOf("app_installations", "complaint_installation_ids", "complaint_journal_control")).associateWith { table ->
        jdbc.queryForList("SELECT jsonb_build_array(to_jsonb(r), r.xmin::text)::text FROM $table r WHERE data_scope_id = ? ORDER BY to_jsonb(r)::text", String::class.java, scope)
    } + ("audit" to jdbc.queryForList("SELECT to_jsonb(a)::text FROM audit_log a WHERE complaint_data_scope_id = ? ORDER BY id", String::class.java, scope))
    fun assertSqlReleased() {
        requireConnectionFree(); assertNull(PersistencePhaseOwnership.current()); assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, web.admission.activeOwners()); assertEquals(0, deletionAdmission.activeOwners().totalOwners)
        assertEquals(0, first.process.pools.catalogCoordinator.activeSnapshotOwners()); checkpoint?.assertSqlReleased()
    }
    fun assertReleased() {
        web.assertRequestsReleased(); assertSqlReleased(); raw.assertDisposed()
        assertEquals(0L, first.process.publicationLanes.activeOwners().totalOwners)
    }

    companion object {
        val mapper = ObjectMapper()
        val SECRET: String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val READ_CREATE = setOf(ComplaintInstallationRoutes.BOOTSTRAP, ComplaintInstallationRoutes.ENROLLMENT, ComplaintInstallationRoutes.SESSION,
            ComplaintInstallationRoutes.HISTORY, "${ComplaintInstallationRoutes.HISTORY}/{id}", ComplaintInstallationRoutes.STATUS)
        val PATHS = READ_CREATE + ComplaintInstallationRoutes.DELETE_ALL
        val CLEANUP = listOf("complaint_test_active_queue_observations", "installation_deletion_receipts", "complaint_idempotency_receipts", "complaint_recovery_capacity_reservations",
            "complaint_deletion_journal_retirements", "complaint_deletion_journal_applied", "complaint_journal_publications", "complaints", "complaint_resource_ids")
        // Existing independently frozen ALL lifecycle vectors; not copied from the product under test.
        val AUTHORIZATION = ComplaintCapacityVector.of(longArrayOf(0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 376832, 0))
        val PROMISE = ComplaintCapacityVector.of(longArrayOf(0, 113, 0, 0, 0, 0, 0, 1, 0, 4, 0, 0, 4, 0, 0, 0, 0, 100, 0, 0, 10240000, 0))
        private fun vectorText(vector: ComplaintCapacityVector) = vector.toLongArray().joinToString(",", "{", "}")
        fun assertCharge(before: Map<ComplaintCapacityCounter, DeleteAllCounter>, after: Map<ComplaintCapacityCounter, DeleteAllCounter>,
            actual: ComplaintCapacityVector, reserve: ComplaintCapacityVector = ComplaintCapacityVector.ZERO) {
            assertEquals(before.keys, after.keys)
            before.forEach { (counter, old) -> assertEquals(old.copy(free = old.free - actual[counter] - reserve[counter],
                actual = old.actual + actual[counter], recovery = old.recovery + reserve[counter]), after.getValue(counter), counter.storedName) }
        }
        fun checked(response: HttpResponse<ByteArray>, expected: Int): HttpResponse<ByteArray> {
            assertEquals(expected, response.statusCode()); assertEquals("1", header(response, "X-Kira-Complaint-Contract"))
            assertEquals("no-store, no-transform", header(response, "Cache-Control"))
            assertTrue(if (expected == 204) response.body().isEmpty() else response.body().size in 1..16 * 1024)
            assertNull(header(response, "Set-Cookie")); assertNull(header(response, "Content-Encoding"))
            return response
        }
        fun problem(response: HttpResponse<ByteArray>, expected: Int, code: String) {
            checked(response, expected); assertEquals(code, mapper.readTree(response.body())["errors"][0]["code"].textValue())
        }
        fun header(response: HttpResponse<ByteArray>, name: String): String? = response.headers().firstValue(name).orElse(null)
    }
}
