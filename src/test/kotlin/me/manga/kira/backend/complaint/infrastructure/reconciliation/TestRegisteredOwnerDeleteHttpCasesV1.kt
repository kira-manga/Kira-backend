package me.manga.kira.backend.complaint.infrastructure.reconciliation

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletInputStream
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.DeleteAllCounter
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJdbcTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteLiteralCharges
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.S3CatalogReadbackFixture
import me.manga.kira.backend.complaint.catalog.SignedActivationObservation
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutFixtureV1
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutInputFixtureV1
import me.manga.kira.backend.complaint.catalog.TestActiveOrdinaryRawHttpV1
import me.manga.kira.backend.complaint.catalog.withTestActiveFirstCut
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeletePrecondition
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteRequest
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointDeletionInputV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredHttpStartupCasesV1.StartedHttpView
import me.manga.kira.backend.complaint.infrastructure.transaction.DeletionPersistenceAdmission
import me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherFixture
import me.manga.kira.backend.complaint.journal.TestOwnerDeleteJournalPublisherFixture
import me.manga.kira.backend.complaint.journal.journalPublisherRawAssertSigned
import me.manga.kira.backend.config.ComplaintTestBootstrapHttpCompositionV1
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import me.manga.kira.backend.security.InstallationJwtCodec
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.JournalKmsHttpReply
import me.manga.kira.backend.security.aws.JournalKmsHttpRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.transaction.support.TransactionSynchronizationManager
import software.amazon.awssdk.http.ExecutableHttpRequest
import software.amazon.awssdk.http.HttpExecuteRequest
import software.amazon.awssdk.http.SdkHttpClient
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * Real enrollment/CREATE/checkpoint and HTTP A/VERIFY; real separately invoked B consumes A's actual
 * native object. No prebound identity/deletion fixture, seeded completion or request-time APPLY.
 * SOURCE ONLY / NOT_COMPILED / NOT_RUN. G1: Delegated model safety refusal; no implementation retry or workaround.
 */
internal object TestRegisteredOwnerDeleteHttpCasesV1 {
    fun authorizationQueueAndReceipts(tls: VersionBoundPersistenceConnectedFixture) = withWeb(tls) { f ->
        f.originalResourcesAndOlderSelector()
        val actor = f.first.initial.candidate().installation
        val foreign = f.first.initial.candidate().installation
        val token = f.enroll(actor); val foreignToken = f.enroll(foreign)
        val target = UUID.randomUUID(); val input = deletion(target)
        val beforeCheckpoint = f.image(); val countersBeforeCheckpoint = f.counters()
        problem(f.delete(input, token), 503, "SERVICE_UNAVAILABLE")
        f.assertReleased()
        assertEquals(beforeCheckpoint, f.image()); assertEquals(countersBeforeCheckpoint, f.counters())
        assertNull(f.raw.publisher, "Registration/enrollment cannot substitute for the current checkpoint.")

        f.withCheckpoint {
            f.create(target, token)
            checked(f.web.get(path(target), foreignToken), 404)
            val before = f.image(); val counters = f.counters()
            problem(f.delete(input, token, body = "{}".toByteArray()), 400, "VALIDATION_FAILED")
            problem(f.delete(input, token, etag = null), 428, "PRECONDITION_REQUIRED")
            problem(f.delete(input, token, requestPath = "${path(target)}?unexpected=1"), 400, "VALIDATION_FAILED")
            f.web.refusedBeforeBody("DELETE", "${path(target)}/", token, 404)
            f.web.refusedBeforeBody("POST", ComplaintInstallationRoutes.DELETE_ALL, token, 404)
            f.web.refusedBeforeBody("GET", ComplaintInstallationRoutes.ME, token, 404)
            f.web.refusedBeforeBody("POST", "${path(target)}/replies", token, 404)
            f.web.refusedBeforeBody("PATCH", "${path(target)}/content", token, 404)
            f.web.refusedBeforeBody("DELETE", "/api/v1/admin/complaints/$target", token, 404)
            f.assertReleased()
            assertEquals(before, f.image()); assertEquals(counters, f.counters()); assertNull(f.raw.publisher)

            f.raw.expected = input
            problem(f.delete(input, token), 503, "SERVICE_UNAVAILABLE") // Genuine A/VERIFY is not completion.
            f.assertReleased(); f.assertPending(input, verified = true)
            assertEquals(actor.id, f.receipt(input)["actor_id"])
            assertCharge(counters, f.counters(), OwnerDeleteLiteralCharges.authorization, OwnerDeleteLiteralCharges.promise)
            val record = f.raw.record()
            assertArrayEquals(record.stored.bytes, checkNotNull(f.raw.publisher).requests.single { it.kind == "PUT" }.body)
            val verifiedRows = f.image(); val authorizedCounters = f.counters(); val providerCounts = f.raw.counts()
            problem(f.status(input, token), 404, "OPERATION_NOT_FOUND")
            problem(f.status(input, foreignToken), 404, "OPERATION_NOT_FOUND")
            problem(f.status(input, token, target = UUID.randomUUID()), 409, "IDEMPOTENCY_KEY_REUSED")
            problem(f.delete(deletion(target, key = input.key, version = 2), token), 409, "IDEMPOTENCY_KEY_REUSED")
            problem(f.delete(input, token), 503, "SERVICE_UNAVAILABLE")
            f.assertReleased()
            assertEquals(verifiedRows, f.image()); assertEquals(authorizedCounters, f.counters())
            assertEquals(providerCounts, f.raw.counts(), "A-only replay neither republishes nor invokes B.")
            assertTrue(f.raw.queue.order.isEmpty()); assertTrue(f.raw.queue.ackRequests.isEmpty())

            // B is explicitly started only here, after the HTTP response and all request ownership released.
            f.completeThroughQueue(record)
            f.assertApplied(input, record)
            val completedRows = f.image(); val completedCounters = f.counters(); val completedProviders = f.raw.counts()
            checked(f.status(input, token), 200).also {
                assertEquals("""{"outcome":"APPLIED","originalStatus":204}""", it.body().decodeToString())
            }
            val replay = checked(f.delete(input, token), 204)
            assertArrayEquals(ByteArray(0), replay.body())
            for (name in listOf("Content-Type", "Content-Length", "ETag", "Location", "Retry-After")) assertNull(header(replay, name))
            problem(f.status(input, foreignToken), 404, "OPERATION_NOT_FOUND")
            problem(f.status(input, token, fingerprint = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))), 409, "IDEMPOTENCY_KEY_REUSED")
            // A completed receipt is replayable with closed creation/current-work gates, without another charge.
            assertEquals(1, f.jdbc.update("UPDATE complaint_journal_control SET maintenance_closed = true, creation_closed = true WHERE data_scope_id = ?", f.scope))
            val closedRows = f.image()
            val repeated = checked(f.delete(input, token), 204)
            assertArrayEquals(replay.body(), repeated.body())
            checked(f.status(input, token), 200)
            f.assertReleased()
            assertEquals(closedRows, f.image()); assertEquals(completedCounters, f.counters()); assertEquals(completedProviders, f.raw.counts())
            assertEquals(completedRows - "complaint_journal_control", closedRows - "complaint_journal_control")
            // Current authentication, not cached receipt delivery: invalidate the actually enrolled credential.
            assertEquals(1, f.jdbc.update("UPDATE app_installations SET credential_version = credential_version + 1 WHERE id = ? AND data_scope_id = ?", actor.id, f.scope))
            problem(f.delete(input, token), 401, "UNAUTHORIZED")
            problem(f.status(input, token), 401, "UNAUTHORIZED")
            f.first.registration.close()
            checked(f.delete(input, token), 503); checked(f.status(input, token), 503)
            f.assertReleased()
            assertEquals(completedCounters, f.counters()); assertEquals(completedProviders, f.raw.counts())
        }
    }

    fun expiredCurrent(tls: VersionBoundPersistenceConnectedFixture) = withWeb(tls, shortFreshness = true) { f ->
        val token = f.enroll(f.first.initial.candidate().installation)
        f.withCheckpoint {
            val target = UUID.randomUUID(); f.create(target, token)
            val input = deletion(target); f.raw.expected = input
            assertEquals(30_000, f.first.process.consumers.journalConfiguration.declaration().limits.deadlines.checkpointMaxAgeMillis)
            val expires = (f.first.control().getValue("checkpoint_completed_at") as Timestamp).toInstant().plusMillis(30_000)
            val rows = f.image(); val counters = f.counters(); val providers = f.raw.counts()
            awaitLifecycleFact(31_000) { f.jdbc.queryForObject("SELECT clock_timestamp() > ?::timestamptz", Boolean::class.java, Timestamp.from(expires)) == true }
            problem(f.delete(input, token), 503, "SERVICE_UNAVAILABLE")
            problem(f.status(input, token), 404, "OPERATION_NOT_FOUND")
            f.assertReleased()
            assertEquals(rows, f.image()); assertEquals(counters, f.counters()); assertEquals(providers, f.raw.counts())
            assertNull(f.raw.publisher); assertTrue(f.raw.queue.order.isEmpty())
        }
    }

    fun nativeFailure(tls: VersionBoundPersistenceConnectedFixture) = withWeb(tls) { f ->
        val token = f.enroll(f.first.initial.candidate().installation)
        f.withCheckpoint {
            val target = UUID.randomUUID(); f.create(target, token)
            val input = deletion(target); f.raw.expected = input; f.raw.failReadback = true
            val before = f.counters()
            problem(f.delete(input, token), 503, "SERVICE_UNAVAILABLE")
            f.assertReleased(); f.assertPending(input, verified = false)
            assertCharge(before, f.counters(), OwnerDeleteLiteralCharges.authorization, OwnerDeleteLiteralCharges.promise)
            val publisher = checkNotNull(f.raw.publisher)
            assertEquals(1, publisher.requests.count { it.kind == "PUT" })
            assertTrue(publisher.requests.any { it.kind == "GET" }); assertEquals(1, publisher.objects.size)
            assertEquals(1, publisher.generated()); assertEquals(0, publisher.decrypted())
            problem(f.status(input, token), 404, "OPERATION_NOT_FOUND")
            assertTrue(f.raw.queue.order.isEmpty(), "A native failure cannot call B or fabricate an applied receipt.")
        }
    }

    fun originalShutdown(tls: VersionBoundPersistenceConnectedFixture, nativeOnly: Boolean) = withWeb(tls) { f ->
        val before = f.counters(); val providers = f.raw.counts()
        val lane = if (nativeOnly) poolTestField<TestOwnerDeleteJournalPublisherFactoryV1>(f.web.startup, "ownerDeletePublisher").reserve() else null
        try {
            OwnedCallerTestScope().use { callers ->
                callers.beforeClose { lane?.close() }
                val holdingDeletion = if (nativeOnly) null else callers.gate()
                val deleting = if (nativeOnly) null else callers.launch {
                    val permit = checkNotNull(f.deletionAdmission.tryPrivacyDeletion())
                    // Accounting-only reservation: this caller never borrows a SQL holder. Its
                    // genuine release must return; no caller-supplied physical-cleanup verdict.
                    try { checkNotNull(holdingDeletion).hold() } finally { assertTrue(permit.releaseAfterQuiescence()) }
                }
                holdingDeletion?.awaitEntered()
                val release = callers.launch {
                    awaitLifecycleFact(5_000) { f.web.ingressSnapshot().stopped &&
                        runCatching { f.first.registration.requireUsable() }.exceptionOrNull() is ComplaintTestNamespaceRegistrationExceptionV1 }
                    assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { f.first.registration.requireUsable() }
                    assertEquals(0, f.web.ingressSnapshot().reservations); assertEquals(0, f.web.admission.activeOwners())
                    assertEquals(if (nativeOnly) 0 else 1, f.deletionAdmission.activeOwners().totalOwners)
                    assertEquals(if (nativeOnly) 1L else 0L, f.first.process.publicationLanes.activeOwners().totalOwners)
                    fun retained() {
                        assertTrue(f.web.emf.isOpen); assertTrue(f.web.context.isActive)
                        assertFalse(f.first.process.pools.shutdownRequested())
                        assertFalse(poolTestField<Boolean>(f.web.startup, "cleanupProven"))
                    }
                    // Separate fresh-startup subcases hold each original owner ALONE; a missing
                    // deletion predicate cannot hide behind a native owner (or vice versa).
                    val heldUntil = System.nanoTime() + 100_000_000
                    while (System.nanoTime() < heldUntil) { retained(); LockSupport.parkNanos(1_000_000) }
                    holdingDeletion?.release(); deleting?.value(); lane?.close()
                    Unit
                }
                f.web.startup.close(); release.value()
            }
            f.web.assertDisposed(nativeStillActive = true)
            assertThrows<ComplaintTestDeploymentExceptionV1> { f.web.startup.start() }
            assertEquals(before, f.counters()); assertEquals(providers, f.raw.counts())
        } finally { lane?.close() }
    }

    private fun withWeb(tls: VersionBoundPersistenceConnectedFixture, shortFreshness: Boolean = false, action: (Fixture) -> Unit) {
        val raw = Raw(shortFreshness)
        withTestActiveFirstCut(tls, ordinaryRawHttp = raw.factories, globalScanBeforeActivation = true) { first ->
            val configuration = first.process.canonicalBytes()
            val startup = first.assembly.beginRegisteredOwnerDeleteHttpStartup(first.registration)
            startup.use {
                assertSame(startup, poolTestField<Any>(first.assembly, "httpStartup"))
                startup.start()
                StartedHttpView(first, startup, READ_CREATE).use { web ->
                    val f = Fixture(first, web, raw); raw.fixture = f
                    assertArrayEquals(configuration, first.process.canonicalBytes())
                    try { action(f) } finally {
                        f.assertReleased()
                        // Owned disposable-scope teardown AFTER assertions. Never refund/reopen/repair a gate,
                        // mint a checkpoint/receipt, or claim product erasure from these fixture DELETEs.
                        for (table in CLEANUP) f.jdbc.update("DELETE FROM $table WHERE data_scope_id = ?", f.scope)
                        f.jdbc.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND action IN " +
                            "('COMPLAINT_CREATED','COMPLAINT_DELETE_AUTHORIZED','COMPLAINT_DELETED','COMPLAINT_RECOVERY_APPLIED')", f.scope)
                    }
                    startup.close(); web.assertDisposed(nativeStillActive = true)
                    raw.fixture = null
                }
            }
            startup.requireCleanupProven()
        }
    }

    private class Fixture(val first: TestActiveFirstCutFixtureV1, val web: StartedHttpView, val raw: Raw) {
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
        fun originalResourcesAndOlderSelector() {
            val owner = poolTestField<PersistencePhaseOwnership>(web.startup, "ownership")
            val template = poolTestField<JdbcTemplate>(web.startup, "jdbc")
            first.registration.requireIdentityAdmissionPhaseResources(owner, template)
            val old = ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreate(first.registration, first.assembly, owner, template, audit)
            assertEquals(READ_CREATE, old.mappedPaths) // Same path, strictly different admitted method cohort.
            val request = object : MockHttpServletRequest("DELETE", path(UUID.randomUUID())) {
                override fun getInputStream(): ServletInputStream = error("Older selector read DELETE input.")
            }
            val response = MockHttpServletResponse()
            old.ingressFilter.doFilter(request, response, FilterChain { _, _ -> error("Older selector widened to DELETE.") })
            assertEquals(404, response.status)
        }
        fun enroll(actor: ScopedInstallationId): String {
            val body = mapper.writeValueAsBytes(mapOf("installationId" to actor.id.toString(), "expectedDataScopeId" to scope.toString(),
                "platform" to "ANDROID", "secret" to Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })))
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
            val body = mapper.writeValueAsBytes(mapOf("id" to id.toString(), "type" to "TECHNICAL", "subject" to "Registered delete subject",
                "body" to "Registered delete body", "metadata" to mapOf("appVersion" to null, "osVersion" to "fixture-os", "manufacturer" to "", "deviceModel" to "")))
            val response = checked(web.post(ComplaintInstallationRoutes.HISTORY, body, token, UUID.randomUUID()), 201)
            assertEquals("""{"id":"$id","version":1}""", response.body().decodeToString())
            assertEquals(path(id), header(response, "Location")); assertEquals("\"complaint-$id-v1\"", header(response, "ETag"))
            assertCharge(before, counters(), ComplaintCapacityCharges.OWNER_CREATE)
        }
        fun delete(input: ComplaintOwnerDeleteInput, token: String, body: ByteArray? = null,
            etag: String? = input.precondition.canonical, requestPath: String = path(input.targetId)): HttpResponse<ByteArray> {
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${web.port}$requestPath"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer $token").header("X-Kira-Idempotency-Key", input.key.toString())
                .apply { etag?.let { header("If-Match", it) }; if (body != null) header("Content-Type", "application/json") }
                .method("DELETE", body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody()).build()
            return poolTestField<HttpClient>(web, "client").send(request, HttpResponse.BodyHandlers.ofByteArray())
        }
        fun status(input: ComplaintOwnerDeleteInput, token: String, target: UUID = input.targetId,
            fingerprint: String = ComplaintOwnerDeleteFingerprint.of(ComplaintOwnerDeleteRequest.normalize(first.process.consumers.journalConfiguration.scope, input)).encoded) =
            web.post(ComplaintInstallationRoutes.STATUS, mapper.writeValueAsBytes(mapOf("operation" to "OWNER_DELETE", "key" to input.key.toString(),
                "targetIds" to listOf(target.toString()), "fingerprint" to fingerprint)), token)
        fun publication(input: ComplaintOwnerDeleteInput) = jdbc.queryForMap("SELECT p.* FROM complaint_journal_publications p JOIN complaint_idempotency_receipts r " +
            "ON r.publication_ref = p.event_id AND r.data_scope_id = p.data_scope_id WHERE p.data_scope_id = ? AND r.idempotency_key = ? AND r.operation = 'OWNER_DELETE'", scope, input.key)
        fun receipt(input: ComplaintOwnerDeleteInput) = jdbc.queryForMap("SELECT * FROM complaint_idempotency_receipts WHERE data_scope_id = ? AND idempotency_key = ? AND operation = 'OWNER_DELETE'", scope, input.key)
        fun assertPending(input: ComplaintOwnerDeleteInput, verified: Boolean) {
            val receipt = receipt(input); val publication = publication(input)
            assertEquals("AUTHORIZED_DELETE", receipt["state"]); assertNull(receipt["response_status"]); assertNull(receipt["outcome"])
            assertEquals(if (verified) "VERIFIED" else "PREPARED", publication["state"])
            assertEquals(verified, publication["verification_bytes"] != null); assertNull(publication["applied_at"])
            assertEquals("DELETION_PENDING", jdbc.queryForObject("SELECT state FROM complaint_resource_ids WHERE id = ? AND data_scope_id = ?", String::class.java, input.targetId, scope))
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM complaints WHERE id = ? AND data_scope_id = ?", Long::class.java, input.targetId, scope))
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", Long::class.java, scope))
            assertEquals(mapOf("COMPLAINT_DELETE_AUTHORIZED" to 1L), deletionAudits())
        }
        fun assertApplied(input: ComplaintOwnerDeleteInput, record: TestRegisteredInitialDeletionNativeRecordV1) {
            val receipt = receipt(input); val publication = publication(input)
            assertEquals("APPLIED", publication["state"]); assertEquals("COMPLETED", receipt["state"])
            assertEquals("APPLIED", receipt["outcome"]); assertEquals(204, (receipt["response_status"] as Number).toInt())
            assertEquals(record.stored.version, receipt["external_object_version"]); assertEquals(record.event.route.eventId, receipt["external_event_id"])
            assertEquals(record.stored.version, publication["object_version"])
            assertArrayEquals(java.util.HexFormat.of().parseHex(Sha256.hex(record.stored.bytes)), publication["ciphertext_hash"] as ByteArray)
            assertEquals("DELETED", jdbc.queryForObject("SELECT state FROM complaint_resource_ids WHERE id = ? AND data_scope_id = ?", String::class.java, input.targetId, scope))
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM complaints WHERE id = ? AND data_scope_id = ?", Long::class.java, input.targetId, scope))
            val applied = jdbc.queryForList("SELECT object_key, object_version, event_id, encode(ciphertext_hash, 'hex') AS hash FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", scope).single()
            assertEquals(record.stored.key, applied["object_key"]); assertEquals(record.stored.version, applied["object_version"])
            assertEquals(record.event.route.eventId, applied["event_id"]); assertEquals(Sha256.hex(record.stored.bytes), applied["hash"])
        }
        fun completeThroughQueue(record: TestRegisteredInitialDeletionNativeRecordV1) {
            assertReleased()
            val input = checkNotNull(raw.expected); val before = counters()
            val proof = publicationIdentity(input); val tuple = receiptIdentity(input)
            val identities = image().filterKeys { it in IDENTITIES }
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
                assertEquals(proof, publicationIdentity(input), "B cannot replace the original canonical event or native VERIFY evidence.")
                assertEquals(tuple, receiptIdentity(input), "The original actor/key/targets/fingerprint and consumed-grant field stay unchanged.")
                assertEquals(identities, image().filterKeys { it in IDENTITIES }, "Resource deletion cannot rewrite or reconstruct credential pairs.")
                assertEquals(mapOf("COMPLAINT_DELETE_AUTHORIZED" to 1L, "COMPLAINT_DELETED" to 1L, "COMPLAINT_RECOVERY_APPLIED" to 1L), deletionAudits())
                val row = jdbc.queryForMap("SELECT state, primary_acked, dlq_acked FROM complaint_test_active_queue_observations WHERE data_scope_id = ?", scope)
                assertEquals("SETTLED", row["state"]); assertEquals(1, (row["primary_acked"] as Number).toInt()); assertEquals(0, (row["dlq_acked"] as Number).toInt())
                assertNull(first.control()["lease_owner"]); assertNull(first.control()["lease_expires_at"])
                val used = OwnerDeleteLiteralCharges.appliedOnly + OwnerDeleteLiteralCharges.audit.scaled(2)
                val reservation = jdbc.queryForMap("SELECT state, reserved_amounts::text, converted_amounts::text FROM complaint_recovery_capacity_reservations WHERE data_scope_id = ?", scope)
                assertEquals("PARTIAL", reservation["state"])
                assertEquals(vectorText(OwnerDeleteLiteralCharges.promise), reservation["reserved_amounts"])
                assertEquals(vectorText(used), reservation["converted_amounts"], "Only actual B rows convert the original reserved recovery share.")
                val after = counters()
                before.forEach { (counter, old) ->
                    val observation = if (counter === ComplaintCapacityCounter.STORAGE_BYTES) 8192L else 0L
                    val refund = OwnerDeleteLiteralCharges.content[counter]
                    assertEquals(old.copy(free = old.free + refund - observation, actual = old.actual + used[counter] - refund + observation,
                        recovery = old.recovery - used[counter]), after.getValue(counter), counter.storedName)
                }
            }
        }
        private fun publicationIdentity(input: ComplaintOwnerDeleteInput): String = checkNotNull(jdbc.queryForObject(
            "SELECT (to_jsonb(p) - ARRAY['state','applied_at'])::text FROM complaint_journal_publications p WHERE event_id = ? AND data_scope_id = ?",
            String::class.java, publication(input)["event_id"], scope))
        private fun receiptIdentity(input: ComplaintOwnerDeleteInput): String = checkNotNull(jdbc.queryForObject(
            "SELECT (to_jsonb(n) - ARRAY['state','outcome','response_status','ack_ids','ack_versions','external_event_id','external_epoch'," +
                "'external_object_version','external_ciphertext_hash','completed_at','expires_at'])::text FROM complaint_idempotency_receipts n WHERE data_scope_id = ? AND idempotency_key = ?",
            String::class.java, scope, input.key))
        private fun deletionAudits() = jdbc.query("SELECT action, count(*) FROM audit_log WHERE complaint_data_scope_id = ? " +
            "AND action IN ('COMPLAINT_DELETE_AUTHORIZED','COMPLAINT_DELETED','COMPLAINT_RECOVERY_APPLIED') GROUP BY action",
            { row, _ -> row.getString(1) to row.getLong(2) }, scope).toMap()
        fun counters(): Map<ComplaintCapacityCounter, DeleteAllCounter> = jdbc.query("SELECT name, free_units, actual_units, recovery_reserved_units, test_reserved_units, " +
            "(to_jsonb(c) - ARRAY['free_units','actual_units','recovery_reserved_units','updated_at'])::text AS preserved FROM complaint_capacity_counters c ORDER BY ordinal",
            { row, _ -> ComplaintCapacityCounter.entries.single { it.storedName == row.getString(1) } to
                DeleteAllCounter(row.getLong(2), row.getLong(3), row.getLong(4), row.getLong(5), row.getString(6)) }).toMap()
        fun image() = (CLEANUP + IDENTITIES + "complaint_journal_control").associateWith { table ->
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
    }

    /** Only raw native HTTP is replaced. All expected semantics come from the actually committed AUTH row. */
    private class Raw(shortFreshness: Boolean) {
        val ordinary = TestActiveOrdinaryRawFixtureV1(); val checkpoint = TestActiveInitialCheckpointRawFixtureV1()
        val queue = TestActiveOwnerDeleteQueueRawFixtureV1()
        @Volatile var fixture: Fixture? = null
        @Volatile var expected: ComplaintOwnerDeleteInput? = null
        @Volatile var publisher: TestOwnerDeleteJournalPublisherFixture? = null
            private set
        var failReadback = false
        private var keyReply: ((JournalKmsHttpRequest) -> JournalKmsHttpReply)? = null
        private var keyContext: Map<String, String>? = null
        private val assertion = AtomicReference<AssertionError?>()
        val factories = TestActiveOrdinaryRawHttpV1(ordinary.factories.sts,
            { remaining -> native(remaining) { selected().kms.httpClient() } },
            { remaining -> native(remaining) { selected().httpClient() } }, checkpoint.input,
            initialCheckpointCreate = TestInitialCheckpointCreateInputV1(1, VersionBoundTestInitialCheckpointCreateV1.PROFILE),
            shortInitialCheckpointFreshness = shortFreshness, activeOwnerDeleteQueue = queue.input,
            initialCheckpointDeletion = TestInitialCheckpointDeletionInputV1(1, VersionBoundTestInitialCheckpointDeletionV1.PROFILE))

        private fun selected(): TestOwnerDeleteJournalPublisherFixture {
            publisher?.let { return it }
            val f = checkNotNull(fixture); val input = checkNotNull(expected)
            f.assertSqlReleased(); f.assertPending(input, verified = false)
            val row = f.publication(input)
            val event = TestOwnerDeleteJournalCodecV1.restoreCanonical(f.first.process.consumers.journalRouting,
                row.getValue("event_bytes") as ByteArray, row.getValue("routing_key_id") as String)
            // This restored value is raw-server comparison only, never returned as MAIN work or proof.
            assertEquals(listOf(input.targetId), event.complaintIds()); assertEquals(input.key, event.tuple.operationKey)
            assertArrayEquals(row.getValue("event_bytes") as ByteArray, event.canonicalBytes())
            val p = TestOwnerDeleteJournalPublisherFixture(f.first.process.consumers.journalRouting, event)
            publisher = p
            p.beforePrepare = { checked { f.assertSqlReleased(); p.wall = f.first.native.now() } }
            p.onClientClose = { checked { f.assertSqlReleased() } }; p.kms.onClientClose = { checked { f.assertSqlReleased() } }
            val reply = p.kms.respond.also { keyReply = it }
            p.kms.respond = { request -> checked {
                f.assertSqlReleased(); p.assertKmsContext(request, event)
                val credentials = TestActiveFirstCutInputFixtureV1.ordinaryCredentials
                assertEquals(credentials.sessionToken(), request.http.firstMatchingHeader("x-amz-security-token").orElseThrow())
                assertTrue(request.http.firstMatchingHeader("Authorization").orElseThrow().startsWith("AWS4-HMAC-SHA256 Credential=${credentials.accessKeyId()}/"))
                if (request.target() == AwsJournalKmsFixture.GENERATE_TARGET) {
                    assertNull(keyContext)
                    keyContext = request.fields()["EncryptionContext"].fields().asSequence().associate { it.key to it.value.textValue() }
                }
                reply(request)
            } }
            p.respond = { request -> checked {
                f.assertSqlReleased()
                val location = p.journal.declaration().journalLocation
                journalPublisherRawAssertSigned(request, location.region, location.accountId, TestActiveFirstCutInputFixtureV1.ordinaryCredentials)
                if (failReadback && request.kind == "GET") OwnerDeleteAllJournalPublisherFixture.errorReply(503) else p.statefulReply(request)
            } }
            return p
        }
        private fun native(remaining: () -> Int, create: () -> SdkHttpClient): SdkHttpClient = checked {
            checkNotNull(fixture).assertSqlReleased(); val client = create()
            object : SdkHttpClient {
                override fun prepareRequest(request: HttpExecuteRequest): ExecutableHttpRequest = checked {
                    checkNotNull(fixture).assertSqlReleased(); assertTrue(remaining() in 1..5_000); client.prepareRequest(request)
                }
                override fun close() = client.close()
                override fun clientName() = "SyntheticRegisteredOwnerDeleteHttp"
            }
        }
        fun record(): TestRegisteredInitialDeletionNativeRecordV1 {
            assertDisposed(); val p = checkNotNull(publisher); val stored = p.objects.single()
            val publication = checkNotNull(fixture).publication(checkNotNull(expected))
            assertEquals("VERIFIED", publication["state"]); assertEquals(stored.version, publication["object_version"])
            assertEquals(Sha256.hex(stored.bytes), java.util.HexFormat.of().formatHex(publication["ciphertext_hash"] as ByteArray))
            assertEquals(1, p.generated()); assertEquals(1, p.decrypted())
            return TestRegisteredInitialDeletionNativeRecordV1(p.event, stored, checkNotNull(keyContext), p, checkNotNull(keyReply))
        }
        fun counts(): List<Int> {
            val first = checkNotNull(fixture).first
            return listOf(first.native.sts.requests.size, first.native.kms.requests.size, first.native.requests.size, first.p.f.http.read.requests.size,
                ordinary.sts.requests.size, publisher?.kms?.requests?.size ?: 0, publisher?.requests?.size ?: 0,
                checkpoint.sts.requests.size, checkpoint.kms.requests.size, checkpoint.requests.size,
                queue.sts.requests.size, queue.kms.requests.size, queue.requests.size, queue.sqs.requests.size)
        }
        fun assertDisposed() { ordinary.assertDisposed(); publisher?.assertClientsClosed(); checkpoint.assertDisposed(); queue.assertDisposed(); assertion.get()?.let { throw it } }
        private fun <T> checked(action: () -> T): T = try { action() } catch (failure: AssertionError) { assertion.compareAndSet(null, failure); throw failure }
    }

    private fun deletion(id: UUID, key: UUID = UUID.randomUUID(), version: Long = 1) =
        ComplaintOwnerDeleteInput(id, key, ComplaintOwnerDeletePrecondition.parse(id, "\"complaint-$id-v$version\""))
    private fun path(id: UUID) = "${ComplaintInstallationRoutes.HISTORY}/$id"
    private fun vectorText(vector: ComplaintCapacityVector) = vector.toLongArray().joinToString(",", "{", "}")
    private fun assertCharge(before: Map<ComplaintCapacityCounter, DeleteAllCounter>, after: Map<ComplaintCapacityCounter, DeleteAllCounter>,
        actual: ComplaintCapacityVector, reserve: ComplaintCapacityVector = ComplaintCapacityVector.ZERO) {
        assertEquals(before.keys, after.keys)
        before.forEach { (counter, old) -> assertEquals(old.copy(free = old.free - actual[counter] - reserve[counter],
            actual = old.actual + actual[counter], recovery = old.recovery + reserve[counter]), after.getValue(counter), counter.storedName) }
    }
    private fun checked(response: HttpResponse<ByteArray>, expected: Int): HttpResponse<ByteArray> {
        assertEquals(expected, response.statusCode()); assertEquals("1", header(response, "X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", header(response, "Cache-Control"))
        assertTrue(if (expected == 204) response.body().isEmpty() else response.body().size in 1..16 * 1024)
        assertNull(header(response, "Set-Cookie")); assertNull(header(response, "Content-Encoding"))
        return response
    }
    private fun problem(response: HttpResponse<ByteArray>, expected: Int, code: String) {
        checked(response, expected); assertEquals(code, mapper.readTree(response.body())["errors"][0]["code"].textValue())
    }
    private fun header(response: HttpResponse<ByteArray>, name: String): String? = response.headers().firstValue(name).orElse(null)
    private val mapper = ObjectMapper()
    private val READ_CREATE = setOf(ComplaintInstallationRoutes.BOOTSTRAP, ComplaintInstallationRoutes.ENROLLMENT, ComplaintInstallationRoutes.SESSION,
        ComplaintInstallationRoutes.HISTORY, "${ComplaintInstallationRoutes.HISTORY}/{id}", ComplaintInstallationRoutes.STATUS)
    private val IDENTITIES = listOf("app_installations", "complaint_installation_ids", "installation_deletion_receipts")
    private val CLEANUP = listOf("complaint_test_active_queue_observations", "complaint_idempotency_receipts", "complaint_recovery_capacity_reservations",
        "complaint_deletion_journal_retirements", "complaint_deletion_journal_applied", "complaint_journal_publications", "complaints", "complaint_resource_ids")
}
