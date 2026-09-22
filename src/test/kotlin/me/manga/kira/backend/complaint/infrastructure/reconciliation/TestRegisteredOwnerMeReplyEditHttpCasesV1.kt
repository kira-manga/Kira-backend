package me.manga.kira.backend.complaint.infrastructure.reconciliation

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletInputStream
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.infrastructure.persistence.CounterSnapshot
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJpaTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutFixtureV1
import me.manga.kira.backend.complaint.catalog.TestActiveOrdinaryRawHttpV1
import me.manga.kira.backend.complaint.catalog.withTestActiveFirstCut
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreateInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintReplyFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintReportFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintReportIdentity
import me.manga.kira.backend.complaint.domain.ComplaintReportMetadataInput
import me.manga.kira.backend.complaint.domain.ComplaintReportRequest
import me.manga.kira.backend.complaint.domain.ComplaintReportRequestResult
import me.manga.kira.backend.complaint.domain.ComplaintType
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateCandidate
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentFailureV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredHttpStartupCasesV1.StartedHttpView
import me.manga.kira.backend.config.ComplaintTestBootstrapHttpCompositionV1
import me.manga.kira.backend.config.ComplaintTestRegisteredHttpStartupV1
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import me.manga.kira.backend.security.InstallationJwtCodec
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.net.http.HttpResponse
import java.time.Clock
import java.util.Base64
import java.util.UUID

/**
 * Genuine PG/TLS -> PROJECT/registration/identity RELEASE -> native seal/checkpoint, using the
 * existing original-JPA loopback view. No row/result seed, quota reset, substitute owner or cleanup.
 * SOURCE ONLY / NOT_COMPILED / NOT_RUN. G1: Delegated model safety refusal; no implementation retry or workaround.
 */
internal object TestRegisteredOwnerMeReplyEditHttpCasesV1 {
    fun oneListenerAndExactReceipts(tls: VersionBoundPersistenceConnectedFixture) =
        TestRegisteredHttpStartupCasesV1.withPrepared(tls, globalScanBeforeActivation = true,
            initialCheckpointCreate = input(VersionBoundTestInitialCheckpointCreateV1.EDIT_PROFILE)) { first, ordinary, raw ->
        val configuration = first.process.canonicalBytes()
        val beforeStartup = providerCounts(first, ordinary, raw)
        val startup = first.assembly.beginRegisteredMeReplyEditHttpStartup(first.registration)
        startup.use {
            assertSame(startup, poolTestField<ComplaintTestRegisteredHttpStartupV1>(first.assembly, "httpStartup"))
            assertRefused { first.assembly.beginRegisteredMeReplyEditHttpStartup(first.registration) }
            OwnedCallerTestScope().use { callers -> callers.launch {
                assertRefused { startup.start() }; assertRefused { startup.close() }
                assertRefused { first.assembly.beginRegisteredMeReplyEditHttpStartup(first.registration) }
            }.value() }
            assertNull(poolTestField<Any?>(startup, "factory"), "Foreign callers must not initialize the original JPA graph.")
            startup.start()
            StartedHttpView(first, startup, COMBINED).use { web ->
                olderSubsetsAndOriginalResources(first, web)
                assertArrayEquals(configuration, first.process.canonicalBytes())
                assertEquals(beforeStartup, providerCounts(first, ordinary, raw))
                val actor = first.initial.candidate().installation // The existing fixture owns exact enrolled-identity cleanup.
                try {
                    val enrolled = web.post(ComplaintInstallationRoutes.ENROLLMENT, identityBody(actor))
                    checked(enrolled, 201)
                    val location = checkNotNull(header(enrolled, "Location"))
                    assertEquals(ComplaintInstallationRoutes.ME, location)
                    val token = bearer(enrolled, actor, first)
                    val beforeMe = first.counters(); val beforeContent = mutationImage(first)
                    projection(web.get(location, token), actor) // Follow the actual enrollment Location on this same listener.
                    web.assertRequestsReleased()
                    assertEquals(beforeMe, first.counters()); assertEquals(beforeContent, mutationImage(first))

                    val report = reportAttempt(actor)
                    checked(web.post(ComplaintInstallationRoutes.HISTORY, reportBody(report), token, report.input.key), 503)
                    web.assertRequestsReleased()
                    assertEquals(beforeMe, first.counters()); assertEquals(beforeContent, mutationImage(first))
                    // Registration and a successful /me cannot replace the current checkpoint. Reuse this
                    // exact report tuple after the genuine checkpoint; never reset its admission member.
                    val captured = first.capture(); first.awaitNativeReclaimed()
                    TestActiveOrdinarySealFixtureV1(first, captured, ordinary).use { sealer ->
                        val verified = sealer.seal(); sealer.assertReleased()
                        awaitInitialCheckpointLeaseExpiry(sealer.observer, sealer.scope)
                        TestActiveInitialCheckpointFixtureV1(sealer, verified, raw).use { checkpoint ->
                            checkpoint.checkpoint() // Completed is discarded, never supplied as HTTP authority.
                            checkpoint.assertReleased()
                            first.p.f.rows.globalPredecessor?.assertPreserved(first.observer)
                            val providers = providerCounts(first, ordinary, raw)
                            var before = first.counters()
                            val created = web.post(ComplaintInstallationRoutes.HISTORY, reportBody(report), token, report.input.key)
                            applied(created, report.input.id, 1, 201); charge(before, first.counters(), ComplaintCapacityCharges.OWNER_CREATE)
                            val reply = registeredReplyAttempt(actor, report.input.id)
                            before = first.counters()
                            val replied = web.post(replyPath(reply), replyBody(reply), token, reply.input.key)
                            applied(replied, reply.input.id, 1, 201); charge(before, first.counters(), ComplaintCapacityCharges.OWNER_CREATE)
                            // One report + one reply spend the unchanged shared createGlobal2; EDIT has its existing separate family.
                            val edit = registeredEditAttempt(actor, reply.input.id, subject = null)
                            before = first.counters()
                            val edited = web.patch(edit, token)
                            applied(edited, reply.input.id, 2, 200); charge(before, first.counters(), ComplaintCapacityCharges.OWNER_EDIT)
                            val image = mutationImage(first); val counters = first.counters()
                            val detail = web.get(checkNotNull(header(replied, "Location")), token)
                            checked(detail, 200)
                            val item = mapper.readTree(detail.body())
                            assertEquals("REPLY", item["kind"].textValue()); assertEquals(report.input.id.toString(), item["replyToId"].textValue())
                            assertEquals(2L, item["version"].longValue()); assertEquals(edit.candidate.request.body, item["body"].textValue())
                            assertEquals(header(edited, "ETag"), header(detail, "ETag"))
                            val history = web.get(ComplaintInstallationRoutes.HISTORY, token); checked(history, 200)
                            assertEquals(setOf(report.input.id.toString(), reply.input.id.toString()),
                                mapper.readTree(history.body())["items"].map { it["id"].textValue() }.toSet())
                            assertEquals(actor.id, first.observer.queryForObject("SELECT owner_id FROM complaints WHERE id = ? AND data_scope_id = ?",
                                UUID::class.java, reply.input.id, first.scope))
                            exclusions(web, reply.input.id, token)
                            val createStatus = statusBody("OWNER_CREATE", report.input.key, listOf(report.input.id), ComplaintReportFingerprint.of(report.candidate.request).encoded)
                            val replyStatus = statusBody("OWNER_REPLY", reply.input.key, listOf(reply.input.parentId, reply.input.id), ComplaintReplyFingerprint.of(reply.candidate.request).encoded)
                            val editStatus = statusBody("OWNER_EDIT", edit.input.key, listOf(edit.input.targetId), ComplaintOwnerEditFingerprint.of(edit.candidate.request).encoded)
                            val deleteStatus = web.post(ComplaintInstallationRoutes.STATUS, statusBody("OWNER_DELETE", report.input.key,
                                listOf(report.input.id), ComplaintReportFingerprint.of(report.candidate.request).encoded), token)
                            checked(deleteStatus, 400); assertEquals("VALIDATION_FAILED", mapper.readTree(deleteStatus.body())["errors"][0]["code"].textValue())
                            val reversedReply = web.post(ComplaintInstallationRoutes.STATUS, statusBody("OWNER_REPLY", reply.input.key,
                                listOf(reply.input.id, reply.input.parentId), ComplaintReplyFingerprint.of(reply.candidate.request).encoded), token)
                            checked(reversedReply, 409); assertEquals("IDEMPOTENCY_KEY_REUSED", mapper.readTree(reversedReply.body())["errors"][0]["code"].textValue())
                            assertEquals(1, first.observer.update("UPDATE complaint_journal_control SET maintenance_closed = true, creation_closed = true WHERE data_scope_id = ?", first.scope))
                            projection(web.get(location, token), actor)
                            replay(created, web.post(ComplaintInstallationRoutes.HISTORY, reportBody(report), token, report.input.key))
                            replay(replied, web.post(replyPath(reply), replyBody(reply), token, reply.input.key)) // Historical version1, not current version2.
                            replay(edited, web.patch(edit, token)) // Original If-Match v1 still replays its exact v2 receipt.
                            status(web, token, createStatus, created); status(web, token, replyStatus, replied); status(web, token, editStatus, edited)
                            checked(web.patch(registeredEditAttempt(actor, reply.input.id, version = 2, subject = null, body = "Closed new edit"), token), 503)
                            first.registration.close() // No receipt or retained listener can revive the original registration.
                            checked(web.get(location, token), 503)
                            checked(web.post(ComplaintInstallationRoutes.STATUS, editStatus, token), 503)
                            checked(web.patch(edit, token), 503)
                            web.assertRequestsReleased(); checkpoint.assertReleased()
                            assertEquals(counters, first.counters()); assertEquals(image, mutationImage(first))
                            assertEquals(providers, providerCounts(first, ordinary, raw))
                        }
                    }
                } finally {
                    web.assertRequestsReleased()
                    // Disposable-scope teardown only, not product erasure/refund, gate repair or checkpoint authority.
                    first.observer.update("DELETE FROM complaints WHERE data_scope_id = ?", first.scope)
                    first.observer.update("DELETE FROM complaint_resource_ids WHERE data_scope_id = ?", first.scope)
                    first.observer.update("DELETE FROM complaint_idempotency_receipts WHERE data_scope_id = ? AND actor_id = ?", first.scope, actor.id)
                    first.observer.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND action IN ('COMPLAINT_CREATED', 'COMPLAINT_CONTENT_EDITED')", first.scope)
                }
                startup.close(); web.assertDisposed(nativeStillActive = true)
            }
        }
        startup.requireCleanupProven()
    }

    fun createOnlyAndOlderMe(tls: VersionBoundPersistenceConnectedFixture) = olderListener(tls, replies = false)
    fun replyOnlyAndOlderReply(tls: VersionBoundPersistenceConnectedFixture) = olderListener(tls, replies = true)

    private fun olderListener(tls: VersionBoundPersistenceConnectedFixture, replies: Boolean) =
        TestRegisteredHttpStartupCasesV1.withPrepared(tls, globalScanBeforeActivation = true,
            initialCheckpointCreate = input(if (replies) VersionBoundTestInitialCheckpointCreateV1.REPLY_PROFILE else VersionBoundTestInitialCheckpointCreateV1.PROFILE)) { first, ordinary, raw ->
        val providers = providerCounts(first, ordinary, raw); val counters = first.counters()
        assertThrows<IllegalStateException> { first.assembly.beginRegisteredMeReplyEditHttpStartup(first.registration) }
        assertNull(poolTestField<Any?>(first.assembly, "httpStartup"), "An insufficient birth policy cannot retain even an inert combined child.")
        val startup = if (replies) first.assembly.beginRegisteredReplyHttpStartup(first.registration) else first.assembly.beginRegisteredMeHttpStartup(first.registration)
        startup.use {
            startup.start()
            StartedHttpView(first, startup, READ_CREATE + (if (replies) REPLY else ComplaintInstallationRoutes.ME)).use { web ->
                checked(web.get(ComplaintInstallationRoutes.BOOTSTRAP), 200)
                val id = UUID.randomUUID()
                web.refusedBeforeBody("PATCH", "${ComplaintInstallationRoutes.HISTORY}/$id/content", "not-a-token", 404)
                if (replies) web.refusedBeforeBody("GET", ComplaintInstallationRoutes.ME, "not-a-token", 404)
                else web.refusedBeforeBody("POST", "${ComplaintInstallationRoutes.HISTORY}/$id/replies", "not-a-token", 404)
                web.assertRequestsReleased()
                assertEquals(counters, first.counters()); assertEquals(providers, providerCounts(first, ordinary, raw))
                startup.close(); web.assertDisposed(nativeStillActive = true)
            }
        }
        startup.requireCleanupProven()
    }

    fun absentDeclaration(tls: VersionBoundPersistenceConnectedFixture) {
        val ordinary = TestActiveOrdinaryRawFixtureV1(); val raw = TestActiveInitialCheckpointRawFixtureV1()
        val factories = ordinary.factories.let { TestActiveOrdinaryRawHttpV1(it.sts, it.kms, it.s3, raw.input) }
        withTestActiveFirstCut(tls, ordinaryRawHttp = factories, globalScanBeforeActivation = true) { first ->
            val providers = providerCounts(first, ordinary, raw); val counters = first.counters()
            assertNull(first.process.initialCheckpointCreate)
            assertRefused { first.assembly.beginRegisteredMeReplyEditHttpStartup(first.registration) }
            assertNull(poolTestField<Any?>(first.assembly, "httpStartup"))
            assertEquals(counters, first.counters()); assertEquals(providers, providerCounts(first, ordinary, raw))
        }
    }

    private fun olderSubsetsAndOriginalResources(first: TestActiveFirstCutFixtureV1, web: StartedHttpView) {
        val r = first.registration; val a = first.assembly
        val o = web.context.getBean(PersistencePhaseOwnership::class.java); val j = web.context.getBean(JdbcTemplate::class.java)
        val audit = web.context.getBean(AuditService::class.java)
        val old = listOf(
            ComplaintTestBootstrapHttpCompositionV1.fromRegistered(r, o, j) to setOf(ComplaintInstallationRoutes.BOOTSTRAP),
            ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointCreate(r, a, o, j, audit) to CREATE,
            ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreate(r, a, o, j, audit) to READ_CREATE,
            ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateMe(r, a, o, j, audit) to (READ_CREATE + ComplaintInstallationRoutes.ME),
            ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateReply(r, a, o, j, audit) to (READ_CREATE + REPLY),
            ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateReplyEdit(r, a, o, j, audit) to (READ_CREATE + REPLY + EDIT),
        )
        val id = UUID.randomUUID()
        for ((composition, expected) in old) {
            assertEquals(expected, composition.mappedPaths)
            if (ComplaintInstallationRoutes.ME !in expected) closedBeforeBody(composition, "GET", ComplaintInstallationRoutes.ME)
            if (REPLY !in expected) closedBeforeBody(composition, "POST", "${ComplaintInstallationRoutes.HISTORY}/$id/replies")
            if (EDIT !in expected) closedBeforeBody(composition, "PATCH", "${ComplaintInstallationRoutes.HISTORY}/$id/content")
        }
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
            ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateMeReplyEdit(r, a, o, JdbcTemplate(checkNotNull(j.dataSource)), audit)
        }
        val foreignOwner = PersistencePhaseOwnership(web.admission, GuardedJpaTransactionManager(web.emf, first.process.pools.ordinary))
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
            ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateMeReplyEdit(r, a, foreignOwner, j, audit)
        }
        ComplaintTestProcessAssemblyV1.begin().use { foreign ->
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
                ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateMeReplyEdit(r, foreign, o, j, audit)
            }
        }
        r.requireIdentityAdmissionPhaseResources(o, j)
    }

    private fun exclusions(web: StartedHttpView, id: UUID, token: String) {
        val owner = "${ComplaintInstallationRoutes.HISTORY}/$id"; val admin = "/api/v1/admin/complaints"
        for ((method, path) in listOf(
            "POST" to ComplaintInstallationRoutes.ME, "GET" to "${ComplaintInstallationRoutes.ME}/",
            "GET" to "$owner/replies", "POST" to "$owner/replies/", "GET" to "$owner/content", "PATCH" to "$owner/content/",
            "DELETE" to owner, "POST" to ComplaintInstallationRoutes.DELETE_ALL,
            "GET" to "$admin/search", "GET" to "$admin/$id", "GET" to "$admin/stats",
            "PATCH" to "$admin/$id/content", "PATCH" to "$admin/$id/status", "PATCH" to "$admin/$id/closure",
            "POST" to "/api/v1/admin/step-up", "DELETE" to "$admin/$id", "POST" to "$admin/batch",
        )) web.refusedBeforeBody(method, path, token, 404)
    }

    private fun closedBeforeBody(composition: ComplaintTestBootstrapHttpCompositionV1, method: String, path: String) {
        val request = object : MockHttpServletRequest(method, path) {
            override fun getInputStream(): ServletInputStream = error("Excluded route read input.")
        }
        val response = MockHttpServletResponse()
        composition.ingressFilter.doFilter(request, response, FilterChain { _, _ -> error("Excluded route fell through.") })
        assertEquals(404, response.status)
    }

    private fun status(web: StartedHttpView, token: String, body: ByteArray, original: HttpResponse<ByteArray>) {
        val response = web.post(ComplaintInstallationRoutes.STATUS, body, token); checked(response, 200)
        val receipt = mapper.readTree(response.body())
        assertEquals("APPLIED", receipt["outcome"].textValue()); assertEquals(original.statusCode(), receipt["originalStatus"].intValue())
        assertEquals(mapper.readTree(original.body()), receipt["body"]); assertEquals(header(original, "ETag"), receipt["etag"].textValue())
        val location = header(original, "Location")
        if (location == null) assertFalse(receipt.has("location")) else assertEquals(location, receipt["location"].textValue())
    }
    private fun replay(original: HttpResponse<ByteArray>, response: HttpResponse<ByteArray>) {
        checked(response, original.statusCode()); assertArrayEquals(original.body(), response.body())
        assertEquals(header(original, "Location"), header(response, "Location")); assertEquals(header(original, "ETag"), header(response, "ETag"))
    }
    private fun applied(response: HttpResponse<ByteArray>, id: UUID, version: Long, code: Int) {
        checked(response, code); assertEquals("""{"id":"$id","version":$version}""", response.body().decodeToString())
        assertEquals("\"complaint-$id-v$version\"", header(response, "ETag"))
        assertEquals(if (code == 201) "${ComplaintInstallationRoutes.HISTORY}/$id" else null, header(response, "Location"))
    }
    private fun projection(response: HttpResponse<ByteArray>, actor: ScopedInstallationId) {
        checked(response, 200)
        assertEquals("""{"installationId":"${actor.id}","credentialVersion":1,"dataScopeId":"${actor.scope.id}"}""", response.body().decodeToString())
        assertNull(header(response, "ETag")); assertNull(header(response, "Location"))
    }
    private fun bearer(response: HttpResponse<ByteArray>, actor: ScopedInstallationId, first: TestActiveFirstCutFixtureV1): String {
        val body = mapper.readTree(response.body())
        assertEquals(actor.id.toString(), body["installationId"].textValue()); assertEquals(actor.scope.id.toString(), body["dataScopeId"].textValue())
        assertEquals(1L, body["credentialVersion"].longValue())
        return checkNotNull(body["accessToken"].textValue()).also {
            assertEquals(actor, InstallationJwtCodec(first.process.consumers.jwt.installationKeyRing, Clock.systemUTC()).verify(it).installation)
        }
    }
    private fun reportAttempt(actor: ScopedInstallationId): RegisteredInitialCreateAttemptV1 {
        val input = ComplaintOwnerCreateInput(UUID.randomUUID(), UUID.randomUUID(), ComplaintType.TECHNICAL,
            " Registered subject ", " Registered body\r\nline ", ComplaintReportMetadataInput(null, "fixture-os", "", ""))
        val identity = checkNotNull(ComplaintReportIdentity.checked(input.id.toString(), input.key.toString(), actor.scope.id.toString()))
        val request = (ComplaintReportRequest.normalize(identity, input.type, input.subject, input.body, input.metadata) as ComplaintReportRequestResult.Accepted).request
        return RegisteredInitialCreateAttemptV1(input, ComplaintOwnerCreateCandidate.prepare(actor, request))
    }
    private fun identityBody(actor: ScopedInstallationId): ByteArray = mapper.writeValueAsBytes(mapOf(
        "installationId" to actor.id.toString(), "expectedDataScopeId" to actor.scope.id.toString(), "platform" to "ANDROID",
        "secret" to Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })))
    private fun reportBody(attempt: RegisteredInitialCreateAttemptV1): ByteArray = mapper.writeValueAsBytes(mapOf(
        "id" to attempt.input.id.toString(), "type" to attempt.input.type.name, "subject" to attempt.input.subject, "body" to attempt.input.body, "metadata" to metadata))
    private fun replyPath(attempt: RegisteredInitialReplyAttemptV1) = "${ComplaintInstallationRoutes.HISTORY}/${attempt.input.parentId}/replies"
    private fun replyBody(attempt: RegisteredInitialReplyAttemptV1): ByteArray = mapper.writeValueAsBytes(mapOf(
        "id" to attempt.input.id.toString(), "body" to attempt.input.body, "metadata" to metadata))
    private fun statusBody(operation: String, key: UUID, targets: List<UUID>, fingerprint: String): ByteArray = mapper.writeValueAsBytes(mapOf(
        "operation" to operation, "key" to key.toString(), "targetIds" to targets.map(UUID::toString), "fingerprint" to fingerprint))
    private fun input(profile: String) = TestInitialCheckpointCreateInputV1(1, profile)
    private fun assertRefused(action: () -> Any?) = assertEquals(ComplaintTestDeploymentFailureV1.PROCESS_REFUSED,
        assertThrows<ComplaintTestDeploymentExceptionV1> { action() }.code)
    private fun providerCounts(first: TestActiveFirstCutFixtureV1, ordinary: TestActiveOrdinaryRawFixtureV1, raw: TestActiveInitialCheckpointRawFixtureV1) =
        listOf(first.native.sts.requests.size, first.native.kms.requests.size, first.native.requests.size, ordinary.requestBudgets.size,
            raw.sts.requests.size, raw.kms.requests.size, raw.requests.size, first.p.f.http.read.requests.size)
    private fun mutationImage(first: TestActiveFirstCutFixtureV1): List<List<String>> = listOf("complaints", "complaint_resource_ids", "complaint_idempotency_receipts", "audit_log").map { table ->
        val scope = if (table == "audit_log") "complaint_data_scope_id" else "data_scope_id"
        first.observer.queryForList("SELECT jsonb_build_array(to_jsonb(r), r.xmin::text)::text FROM $table r WHERE $scope = ? ORDER BY to_jsonb(r)::text", String::class.java, first.scope)
    }
    private fun charge(before: Map<String, CounterSnapshot>, after: Map<String, CounterSnapshot>, amount: ComplaintCapacityVector) {
        assertEquals(before.keys, after.keys)
        for (counter in ComplaintCapacityCounter.entries) {
            val old = before.getValue(counter.storedName); val current = after.getValue(counter.storedName)
            if (amount[counter] == 0L && ComplaintCapacityCharges.OWNER_CREATE[counter] == 0L) assertEquals(old, current) else {
                assertEquals(old.preserved, current.preserved)
                assertEquals(old.free - amount[counter], current.free); assertEquals(old.actual + amount[counter], current.actual)
            }
        }
    }
    private fun header(response: HttpResponse<ByteArray>, name: String): String? = response.headers().firstValue(name).orElse(null)
    private fun checked(response: HttpResponse<ByteArray>, expected: Int) {
        assertEquals(expected, response.statusCode()); assertEquals("1", header(response, "X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", header(response, "Cache-Control")); assertTrue(response.body().size in 1..16 * 1024)
        assertNull(header(response, "Set-Cookie")); assertNull(header(response, "Content-Encoding"))
    }
    private val mapper = ObjectMapper()
    private val metadata = mapOf("appVersion" to null, "osVersion" to "fixture-os", "manufacturer" to "", "deviceModel" to "")
    private val CREATE = setOf(ComplaintInstallationRoutes.BOOTSTRAP, ComplaintInstallationRoutes.ENROLLMENT, ComplaintInstallationRoutes.SESSION,
        ComplaintInstallationRoutes.HISTORY, ComplaintInstallationRoutes.STATUS)
    private val READ_CREATE = CREATE + "${ComplaintInstallationRoutes.HISTORY}/{id}"
    private val REPLY = "${ComplaintInstallationRoutes.HISTORY}/{id}/replies"
    private val EDIT = "${ComplaintInstallationRoutes.HISTORY}/{id}/content"
    private val COMBINED = READ_CREATE + ComplaintInstallationRoutes.ME + REPLY + EDIT
}
