package me.manga.kira.backend.complaint.infrastructure.reconciliation

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManagerFactory
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.infrastructure.persistence.CounterSnapshot
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJpaTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.OrdinaryPersistenceAdmission
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipantRole
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
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
import me.manga.kira.backend.config.ComplaintTestBootstrapHttpCompositionV1
import me.manga.kira.backend.config.ComplaintTestRegisteredHttpStartupV1
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import me.manga.kira.backend.security.InstallationJwtCodec
import me.manga.kira.backend.security.JwtKeyProvider
import org.apache.catalina.LifecycleState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.orm.jpa.EntityManagerFactoryInfo
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * SOURCE ONLY / NOT_COMPILED / NOT_RUN. Actual loopback TCP, the product-created JPA graph and the
 * existing genuine PG/TLS -> registration -> identity-release -> native-seal -> checkpoint chain.
 * No MockMvc, prebound ordinary fixture, fake server/EMF/audit, result or cleanup substitution.
 */
internal object TestRegisteredHttpStartupCasesV1 {
    fun identityCreateAndReceipts(tls: VersionBoundPersistenceConnectedFixture) =
        withPrepared(tls, globalScanBeforeActivation = true) { first, ordinary, raw ->
        val providers = providerCounts(first, ordinary, raw)
        assertThrows<IllegalStateException> { first.assembly.beginRegisteredReplyHttpStartup(first.registration) }
        assertThrows<IllegalStateException> { first.assembly.beginRegisteredEditHttpStartup(first.registration) }
        assertNull(poolTestField<Any?>(first.assembly, "httpStartup"), "The CREATE-only profile cannot retain a reply startup.")
        first.assembly.beginRegisteredHttpStartup(first.registration).use { startup ->
            assertSame(startup, poolTestField<ComplaintTestRegisteredHttpStartupV1>(first.assembly, "httpStartup"))
            assertRefused { first.assembly.beginRegisteredHttpStartup(first.registration) }
            OwnedCallerTestScope().use { callers -> callers.launch {
                assertRefused { startup.start() }
                assertRefused { startup.close() }
                assertRefused { startup.localPort }
                assertRefused { first.assembly.beginRegisteredHttpStartup(first.registration) }
            }.value() }
            assertNull(poolTestField<Any?>(startup, "factory"), "A foreign caller must not initialize original JPA.")
            assertEquals(providers, providerCounts(first, ordinary, raw))
            startup.start()
            assertRefused { startup.start() }
            StartedHttpView(first, startup).use { web ->
                val bootstrap = web.get(ComplaintInstallationRoutes.BOOTSTRAP)
                checked(bootstrap, 200)
                assertEquals("""{"dataScopeId":"${first.scope}","contractVersion":1}""", bootstrap.body().decodeToString())
                for (path in listOf("/api/v1/auth/me", ComplaintInstallationRoutes.ME, "${ComplaintInstallationRoutes.BOOTSTRAP}/")) {
                    checked(web.get(path), 404) // Standalone TEST context does not host the full account/source/Admin app.
                }
                checked(web.get(ComplaintInstallationRoutes.HISTORY), 401)
                val actor = first.initial.candidate().installation // Register identity with the existing fixture's exact cleanup.
                try {
                    val beforeEnrollment = first.p.counters()
                    val enrolled = web.post(ComplaintInstallationRoutes.ENROLLMENT, identityBody(actor, enrollment = true))
                    checked(enrolled, 201)
                    token(enrolled, actor, first)
                    val share = ComplaintCapacityCharges.INSTALLATION_ID + ComplaintCapacityCharges.INSTALLATION_CREDENTIAL
                    val afterEnrollment = first.p.counters()
                    for (counter in ComplaintCapacityCounter.entries) {
                        val old = beforeEnrollment.getValue(counter.storedName); val current = afterEnrollment.getValue(counter.storedName)
                        assertEquals(old.free - ComplaintCapacityCharges.AUDIT[counter], current.free)
                        assertEquals(old.actual + share[counter] + ComplaintCapacityCharges.AUDIT[counter], current.actual)
                        assertEquals(old.reserved - share[counter], current.reserved)
                        assertEquals(old.recovery, current.recovery)
                    }
                    assertEquals(1L, first.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? " +
                        "AND action = 'COMPLAINT_INSTALLATION_ENROLLED' AND complaint_actor_kind = 'INSTALLATION'", Long::class.java, first.scope))
                    assertEquals(1L, first.observer.queryForObject("SELECT enrolled_count FROM complaint_test_runs WHERE data_scope_id = ?", Long::class.java, first.scope))
                    val session = web.post(ComplaintInstallationRoutes.SESSION, identityBody(actor))
                    checked(session, 200)
                    val bearer = token(session, actor, first)
                    assertEquals(afterEnrollment, first.p.counters())
                    val attempt = attempt(actor)
                    val missing = first.counters()
                    checked(web.post(ComplaintInstallationRoutes.HISTORY, createBody(attempt), bearer, attempt.input.key), 503)
                    assertEquals(missing, first.counters(), "Registration/identity release cannot replace the current checkpoint.")
                    web.assertRequestsReleased()
                    assertEquals(providers, providerCounts(first, ordinary, raw), "HTTP and startup do not reacquire provider credentials.")

                    val captured = first.capture()
                    first.awaitNativeReclaimed()
                    TestActiveOrdinarySealFixtureV1(first, captured, ordinary).use { sealer ->
                        val verified = sealer.seal()
                        sealer.assertReleased()
                        awaitInitialCheckpointLeaseExpiry(sealer.observer, sealer.scope)
                        TestActiveInitialCheckpointFixtureV1(sealer, verified, raw).use { checkpoint ->
                            checkpoint.checkpoint() // Completed is intentionally discarded, never supplied as HTTP authority.
                            checkpoint.assertReleased()
                            first.p.f.rows.globalPredecessor?.assertPreserved(first.observer)
                            val afterProviders = providerCounts(first, ordinary, raw)
                            val beforeCreate = first.counters()
                            val created = web.post(ComplaintInstallationRoutes.HISTORY, createBody(attempt), bearer, attempt.input.key)
                            checked(created, 201)
                            assertEquals("""{"id":"${attempt.input.id}","version":1}""", created.body().decodeToString())
                            assertEquals("/api/v1/complaints/${attempt.input.id}", header(created, "Location"))
                            assertTrue(checkNotNull(header(created, "ETag")).isNotEmpty())
                            assertCreateCharge(beforeCreate, first.counters())
                            assertEquals(actor.id, first.observer.queryForObject("SELECT owner_id FROM complaints WHERE id = ?", UUID::class.java, attempt.input.id))
                            // Two activation notices plus this new CREATE; enrollment has its own action.
                            assertEquals(3L, first.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? " +
                                "AND action = 'COMPLAINT_CREATED'", Long::class.java, first.scope))
                            assertEquals(1, first.observer.update("UPDATE complaint_journal_control SET maintenance_closed = true, creation_closed = true WHERE data_scope_id = ?", first.scope))
                            val closed = first.counters()
                            checked(web.post(ComplaintInstallationRoutes.HISTORY, createBody(attempt), bearer, attempt.input.key), 201)
                            val status = web.post(ComplaintInstallationRoutes.STATUS, statusBody(attempt), bearer)
                            checked(status, 200)
                            val receipt = mapper.readTree(status.body())
                            assertEquals("APPLIED", receipt["outcome"].textValue()); assertEquals(201, receipt["originalStatus"].intValue())
                            assertEquals(attempt.input.id.toString(), receipt["body"]["id"].textValue())
                            assertEquals(header(created, "ETag"), receipt["etag"].textValue())
                            assertEquals(closed, first.counters()); assertEquals(afterProviders, providerCounts(first, ordinary, raw))
                            web.assertRequestsReleased(); checkpoint.assertReleased()
                        }
                    }
                } finally {
                    web.assertRequestsReleased()
                    // Disposable-scope teardown only. No product erasure/refund or checkpoint proof.
                    first.observer.update("DELETE FROM complaints WHERE data_scope_id = ?", first.scope)
                    first.observer.update("DELETE FROM complaint_resource_ids WHERE data_scope_id = ?", first.scope)
                    first.observer.update("DELETE FROM complaint_idempotency_receipts WHERE data_scope_id = ? AND actor_id = ?", first.scope, actor.id)
                    first.observer.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_CREATED'", first.scope)
                }
                startup.close()
                web.assertDisposed(nativeStillActive = true) // Spring must not infer close on any borrowed pool.
                assertRefused { startup.start() }; assertRefused { startup.localPort }
            }
        }
    }

    fun ownerReadsNoticesAndCursor(tls: VersionBoundPersistenceConnectedFixture) =
        withPrepared(tls, globalScanBeforeActivation = true) { first, ordinary, raw ->
        val providers = providerCounts(first, ordinary, raw)
        first.assembly.beginRegisteredHttpStartup(first.registration).use { startup ->
            startup.start()
            StartedHttpView(first, startup).use { web ->
                val actor = first.initial.candidate().installation
                val foreign = first.initial.candidate().installation
                try {
                    val enrolled = web.post(ComplaintInstallationRoutes.ENROLLMENT, identityBody(actor, enrollment = true))
                    checked(enrolled, 201)
                    val bearer = token(enrolled, actor, first)
                    val other = web.post(ComplaintInstallationRoutes.ENROLLMENT, identityBody(foreign, enrollment = true))
                    checked(other, 201)
                    val foreignBearer = token(other, foreign, first)
                    web.assertRequestsReleased()
                    assertEquals(providers, providerCounts(first, ordinary, raw))
                    val captured = first.capture()
                    first.awaitNativeReclaimed()
                    TestActiveOrdinarySealFixtureV1(first, captured, ordinary).use { sealer ->
                        val verified = sealer.seal()
                        sealer.assertReleased()
                        awaitInitialCheckpointLeaseExpiry(sealer.observer, sealer.scope)
                        TestActiveInitialCheckpointFixtureV1(sealer, verified, raw).use { checkpoint ->
                            checkpoint.checkpoint() // Genuine producer; no Completed object is passed to the HTTP composition.
                            checkpoint.assertReleased()
                            first.p.f.rows.globalPredecessor?.assertPreserved(first.observer)
                            val afterProviders = providerCounts(first, ordinary, raw)
                            // Spend exactly the existing two CREATE admissions, not a reset or a raised ceiling.
                            val attempts = listOf(attempt(actor), attempt(actor))
                            for (attempt in attempts) {
                                val before = first.counters()
                                checked(web.post(ComplaintInstallationRoutes.HISTORY, createBody(attempt), bearer, attempt.input.key), 201)
                                assertCreateCharge(before, first.counters())
                            }
                            web.assertRequestsReleased()
                            val counters = first.counters()
                            val audits = auditImage(first)
                            val history = web.get(ComplaintInstallationRoutes.HISTORY, bearer)
                            checked(history, 200)
                            val page = mapper.readTree(history.body())
                            assertEquals(setOf("notices", "items", "nextCursor"), page.fieldNames().asSequence().toSet())
                            assertEquals(attempts.map { it.input.id.toString() }.toSet(), page["items"].map { it["id"].textValue() }.toSet())
                            assertEquals(2, page["items"].size()); assertTrue(page["nextCursor"].isNull)
                            for (item in page["items"]) {
                                assertEquals("REPORT", item["kind"].textValue()); assertEquals(1L, item["version"].longValue())
                                assertEquals(attempts.first().candidate.request.subject, item["subject"].textValue())
                                assertEquals(attempts.first().candidate.request.body, item["body"].textValue())
                                val detail = web.get("${ComplaintInstallationRoutes.HISTORY}/${item["id"].textValue()}", bearer)
                                checked(detail, 200)
                                assertEquals(item, mapper.readTree(detail.body()))
                                assertEquals(item["actionTag"].textValue(), header(detail, "ETag"))
                            }
                            assertEquals(2, page["notices"].size())
                            assertEquals(setOf("complaints.notice.content-policy", "complaints.notice.source-requirements"),
                                page["notices"].map { it["noticeKey"].textValue() }.toSet())
                            for (notice in page["notices"]) {
                                assertEquals(setOf("id", "kind", "noticeKey", "status", "createdAt", "updatedAt", "version"), notice.fieldNames().asSequence().toSet())
                                assertEquals("NOTICE", notice["kind"].textValue()); assertEquals("PINNED", notice["status"].textValue())
                                val detail = web.get("${ComplaintInstallationRoutes.HISTORY}/${notice["id"].textValue()}", bearer)
                                checked(detail, 200)
                                assertEquals(notice, mapper.readTree(detail.body())); assertNull(header(detail, "ETag"))
                            }
                            val firstResponse = web.get("${ComplaintInstallationRoutes.HISTORY}?limit=1", bearer)
                            checked(firstResponse, 200)
                            val firstPage = mapper.readTree(firstResponse.body())
                            assertEquals(1, firstPage["items"].size()); assertEquals(page["notices"], firstPage["notices"])
                            val cursor = checkNotNull(firstPage["nextCursor"].textValue())
                            val nextPath = "${ComplaintInstallationRoutes.HISTORY}?limit=1&cursor=$cursor"
                            val nextResponse = web.get(nextPath, bearer)
                            checked(nextResponse, 200)
                            val nextPage = mapper.readTree(nextResponse.body())
                            assertEquals(1, nextPage["items"].size()); assertEquals(0, nextPage["notices"].size())
                            assertTrue(nextPage["nextCursor"].isNull)
                            assertEquals(page["items"].toList(), firstPage["items"].toList() + nextPage["items"].toList())
                            val foreignHistory = web.get(ComplaintInstallationRoutes.HISTORY, foreignBearer)
                            checked(foreignHistory, 200)
                            val foreignPage = mapper.readTree(foreignHistory.body())
                            assertEquals(0, foreignPage["items"].size()); assertEquals(page["notices"], foreignPage["notices"])
                            val detailPath = "${ComplaintInstallationRoutes.HISTORY}/${attempts.first().input.id}"
                            val foreignDetail = web.get(detailPath, foreignBearer)
                            checked(foreignDetail, 404); assertNull(header(foreignDetail, "ETag"))
                            val foreignCursor = web.get(nextPath, foreignBearer)
                            checked(foreignCursor, 400)
                            assertEquals("INVALID_CURSOR", mapper.readTree(foreignCursor.body())["errors"][0]["code"].textValue())

                            // Actual incomplete TCP requests: aliases/methods stay closed before body or bearer.
                            for ((method, path) in listOf(
                                "HEAD" to detailPath, "GET" to "$detailPath/",
                                "GET" to "${ComplaintInstallationRoutes.HISTORY}/AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA",
                                "GET" to ComplaintInstallationRoutes.ME, "POST" to ComplaintInstallationRoutes.DELETE_ALL,
                                "POST" to "$detailPath/replies", "PATCH" to "$detailPath/content", "DELETE" to detailPath,
                            )) web.refusedBeforeBody(method, path, bearer, 404)
                            web.refusedBeforeBody("GET", detailPath, bearer, 400) // Canonical GET still requires the existing bodyless contract.

                            assertEquals(1, first.observer.update("UPDATE app_installations SET credential_version = credential_version + 1 WHERE id = ? AND data_scope_id = ?",
                                actor.id, first.scope)) // Negative current-row drift only, never a positive identity seed.
                            checked(web.get(ComplaintInstallationRoutes.HISTORY, bearer), 401)
                            checked(web.get(detailPath, bearer), 401)
                            first.registration.close() // Retaining the same handlers cannot revive a closed registration.
                            checked(web.get(ComplaintInstallationRoutes.HISTORY, foreignBearer), 503)
                            checked(web.get(detailPath, foreignBearer), 503)
                            web.assertRequestsReleased(); checkpoint.assertReleased()
                            assertEquals(counters, first.counters()); assertEquals(audits, auditImage(first))
                            assertEquals(afterProviders, providerCounts(first, ordinary, raw))
                        }
                    }
                } finally {
                    web.assertRequestsReleased()
                    // Owned disposable scope only; no product erasure, counter refund or read authority.
                    first.observer.update("DELETE FROM complaints WHERE data_scope_id = ?", first.scope)
                    first.observer.update("DELETE FROM complaint_resource_ids WHERE data_scope_id = ?", first.scope)
                    first.observer.update("DELETE FROM complaint_idempotency_receipts WHERE data_scope_id = ? AND actor_id = ?", first.scope, actor.id)
                    first.observer.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_CREATED'", first.scope)
                }
                startup.close()
                web.assertDisposed(nativeStillActive = true)
            }
        }
    }

    fun ownReportReply(tls: VersionBoundPersistenceConnectedFixture) = ownerReplies(tls, ReplyParent.OWN_REPORT)
    fun noticeReplyThread(tls: VersionBoundPersistenceConnectedFixture) = ownerReplies(tls, ReplyParent.NOTICE)
    fun foreignParentReply(tls: VersionBoundPersistenceConnectedFixture) = ownerReplies(tls, ReplyParent.FOREIGN_REPORT)

    private fun ownerReplies(tls: VersionBoundPersistenceConnectedFixture, kind: ReplyParent) =
        withPrepared(tls, globalScanBeforeActivation = true,
            initialCheckpointCreate = TestInitialCheckpointCreateInputV1(1, VersionBoundTestInitialCheckpointCreateV1.REPLY_PROFILE)) { first, ordinary, raw ->
        assertThrows<IllegalStateException> { first.assembly.beginRegisteredEditHttpStartup(first.registration) }
        assertNull(poolTestField<Any?>(first.assembly, "httpStartup"), "The REPLY-only profile cannot retain EDIT startup.")
        first.assembly.beginRegisteredReplyHttpStartup(first.registration).use { startup ->
            startup.start()
            StartedHttpView(first, startup, SUBSET + "${ComplaintInstallationRoutes.HISTORY}/{id}/replies").use { web ->
                val actor = first.initial.candidate().installation
                val foreign = if (kind === ReplyParent.FOREIGN_REPORT) first.initial.candidate().installation else null
                try {
                    val enrolled = web.post(ComplaintInstallationRoutes.ENROLLMENT, identityBody(actor, enrollment = true))
                    checked(enrolled, 201)
                    val bearer = token(enrolled, actor, first)
                    val foreignBearer = foreign?.let {
                        val other = web.post(ComplaintInstallationRoutes.ENROLLMENT, identityBody(it, enrollment = true))
                        checked(other, 201); token(other, it, first)
                    }
                    web.assertRequestsReleased()
                    val captured = first.capture()
                    first.awaitNativeReclaimed()
                    TestActiveOrdinarySealFixtureV1(first, captured, ordinary).use { sealer ->
                        val verified = sealer.seal(); sealer.assertReleased()
                        awaitInitialCheckpointLeaseExpiry(sealer.observer, sealer.scope)
                        TestActiveInitialCheckpointFixtureV1(sealer, verified, raw).use { checkpoint ->
                            checkpoint.checkpoint(); checkpoint.assertReleased() // Discard Completed; retain only the genuine original graph.
                            first.p.f.rows.globalPredecessor?.assertPreserved(first.observer)
                            val providers = providerCounts(first, ordinary, raw)
                            val applied = mutableListOf<Pair<RegisteredInitialReplyAttemptV1, HttpResponse<ByteArray>>>()
                            var noticeKey: String? = null
                            // Exactly two new admissions per fixture: report+reply, two notice-thread replies,
                            // or report+rejected foreign reply. No raised createGlobal2 or cleared quota history.
                            val parent = if (kind === ReplyParent.NOTICE) {
                                val page = web.get(ComplaintInstallationRoutes.HISTORY, bearer)
                                checked(page, 200)
                                val notice = mapper.readTree(page.body())["notices"].first()
                                noticeKey = notice["noticeKey"].textValue()
                                val initial = registeredReplyAttempt(actor, UUID.fromString(notice["id"].textValue()))
                                val before = first.counters()
                                val reply = web.post(replyPath(initial), replyBody(initial), bearer, initial.input.key)
                                checked(reply, 201); assertCreateCharge(before, first.counters())
                                applied += initial to reply
                                initial.input.id
                            } else {
                                val report = attempt(actor)
                                val before = first.counters()
                                checked(web.post(ComplaintInstallationRoutes.HISTORY, createBody(report), bearer, report.input.key), 201)
                                assertCreateCharge(before, first.counters())
                                report.input.id
                            }
                            val selectedActor = foreign ?: actor
                            val selectedBearer = foreignBearer ?: bearer
                            val attempt = registeredReplyAttempt(selectedActor, parent)
                            val before = first.counters(); val auditBefore = auditImage(first)
                            val reply = web.post(replyPath(attempt), replyBody(attempt), selectedBearer, attempt.input.key)
                            val rejected = kind === ReplyParent.FOREIGN_REPORT
                            checked(reply, if (rejected) 404 else 201)
                            assertCreateCharge(before, first.counters(), if (rejected) ComplaintCapacityCharges.NORMAL_RECEIPT else ComplaintCapacityCharges.OWNER_CREATE)
                            if (rejected) {
                                assertEquals("COMPLAINT_PARENT_NOT_FOUND", mapper.readTree(reply.body())["errors"][0]["code"].textValue())
                                assertEquals(auditBefore, auditImage(first))
                                assertEquals(0L, first.observer.queryForObject("SELECT count(*) FROM complaint_resource_ids WHERE id = ?", Long::class.java, attempt.input.id))
                                checked(web.get("${ComplaintInstallationRoutes.HISTORY}/$parent", selectedBearer), 404)
                            } else applied += attempt to reply
                            for ((created, response) in applied) {
                                assertEquals("""{"id":"${created.input.id}","version":1}""", response.body().decodeToString())
                                assertEquals("${ComplaintInstallationRoutes.HISTORY}/${created.input.id}", header(response, "Location"))
                                val detail = web.get("${ComplaintInstallationRoutes.HISTORY}/${created.input.id}", bearer)
                                checked(detail, 200)
                                val item = mapper.readTree(detail.body())
                                assertEquals("REPLY", item["kind"].textValue()); assertEquals(created.input.parentId.toString(), item["replyToId"].textValue())
                                assertEquals(created.candidate.request.body, item["body"].textValue()); assertEquals(1L, item["version"].longValue())
                                assertEquals(header(response, "ETag"), header(detail, "ETag"))
                                assertEquals("ANDROID", item["platform"].textValue())
                                if (kind === ReplyParent.NOTICE) {
                                    assertEquals("CUSTOM", item["type"].textValue()); assertTrue(item["subject"].isNull)
                                    assertEquals(noticeKey, item["noticeKey"].textValue())
                                } else {
                                    assertEquals("TECHNICAL", item["type"].textValue()); assertEquals("Registered subject", item["subject"].textValue())
                                    assertFalse(item.has("noticeKey"))
                                }
                            }
                            // Earlier read/CREATE factory remains narrow even on a reply-capable birth profile.
                            val old = ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreate(first.registration, first.assembly,
                                web.context.getBean(PersistencePhaseOwnership::class.java), web.context.getBean(JdbcTemplate::class.java), web.context.getBean(AuditService::class.java))
                            assertEquals(SUBSET, old.mappedPaths)
                            assertFalse(old.mapsRequest(MockHttpServletRequest("POST", replyPath(attempt))))
                            for ((method, path) in listOf(
                                "GET" to replyPath(attempt), "POST" to "${replyPath(attempt)}/",
                                "POST" to "${ComplaintInstallationRoutes.HISTORY}/AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA/replies",
                                "GET" to ComplaintInstallationRoutes.ME, "PATCH" to "${ComplaintInstallationRoutes.HISTORY}/$parent/content",
                                "DELETE" to "${ComplaintInstallationRoutes.HISTORY}/$parent", "POST" to ComplaintInstallationRoutes.DELETE_ALL,
                            )) web.refusedBeforeBody(method, path, selectedBearer, 404)
                            val counters = first.counters(); val audits = auditImage(first)
                            assertEquals(1, first.observer.update("UPDATE complaint_journal_control SET maintenance_closed = true, creation_closed = true WHERE data_scope_id = ?", first.scope))
                            val replay = web.post(replyPath(attempt), replyBody(attempt), selectedBearer, attempt.input.key)
                            checked(replay, reply.statusCode()); assertArrayEquals(reply.body(), replay.body())
                            assertEquals(header(reply, "Location"), header(replay, "Location")); assertEquals(header(reply, "ETag"), header(replay, "ETag"))
                            val status = web.post(ComplaintInstallationRoutes.STATUS, statusBody(attempt), selectedBearer)
                            checked(status, 200)
                            val receipt = mapper.readTree(status.body())
                            assertEquals(if (rejected) "REJECTED" else "APPLIED", receipt["outcome"].textValue())
                            assertEquals(reply.statusCode(), receipt["originalStatus"].intValue())
                            if (rejected) assertEquals("COMPLAINT_PARENT_NOT_FOUND", receipt["problemCode"].textValue())
                            else { assertEquals(attempt.input.id.toString(), receipt["body"]["id"].textValue()); assertEquals(header(reply, "ETag"), receipt["etag"].textValue()) }
                            checked(web.post(ComplaintInstallationRoutes.STATUS, statusBody(attempt, reversed = true), selectedBearer), 409)
                            first.registration.close() // Even an exact receipt cannot revive the original closed registration.
                            checked(web.post(replyPath(attempt), replyBody(attempt), selectedBearer, attempt.input.key), 503)
                            assertEquals(counters, first.counters()); assertEquals(audits, auditImage(first))
                            assertEquals(providers, providerCounts(first, ordinary, raw))
                            web.assertRequestsReleased(); checkpoint.assertReleased()
                        }
                    }
                } finally {
                    web.assertRequestsReleased()
                    // Disposable scope cleanup, not product erasure/refund, gate repair or checkpoint authority.
                    first.observer.update("DELETE FROM complaints WHERE data_scope_id = ?", first.scope)
                    first.observer.update("DELETE FROM complaint_resource_ids WHERE data_scope_id = ?", first.scope)
                    for (owner in listOfNotNull(actor, foreign)) first.observer.update("DELETE FROM complaint_idempotency_receipts WHERE data_scope_id = ? AND actor_id = ?", first.scope, owner.id)
                    first.observer.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_CREATED'", first.scope)
                }
                startup.close(); web.assertDisposed(nativeStillActive = true)
            }
        }
    }

    private enum class ReplyParent { OWN_REPORT, NOTICE, FOREIGN_REPORT }

    fun ownReportEdit(tls: VersionBoundPersistenceConnectedFixture) = ownerEdits(tls, EditTarget.OWN_REPORT)
    fun noticeReplyEdit(tls: VersionBoundPersistenceConnectedFixture) = ownerEdits(tls, EditTarget.NOTICE_REPLY)
    fun foreignAndSystemEdit(tls: VersionBoundPersistenceConnectedFixture) = ownerEdits(tls, EditTarget.FOREIGN_AND_SYSTEM)

    private fun ownerEdits(tls: VersionBoundPersistenceConnectedFixture, kind: EditTarget) =
        withPrepared(tls, globalScanBeforeActivation = true,
            initialCheckpointCreate = TestInitialCheckpointCreateInputV1(1, VersionBoundTestInitialCheckpointCreateV1.EDIT_PROFILE)) { first, ordinary, raw ->
        first.assembly.beginRegisteredEditHttpStartup(first.registration).use { startup ->
            startup.start()
            val paths = SUBSET + "${ComplaintInstallationRoutes.HISTORY}/{id}/replies" + "${ComplaintInstallationRoutes.HISTORY}/{id}/content"
            StartedHttpView(first, startup, paths).use { web ->
                val actor = first.initial.candidate().installation
                val foreign = if (kind === EditTarget.FOREIGN_AND_SYSTEM) first.initial.candidate().installation else null
                try {
                    val enrolled = web.post(ComplaintInstallationRoutes.ENROLLMENT, identityBody(actor, enrollment = true))
                    checked(enrolled, 201)
                    val bearer = token(enrolled, actor, first)
                    val foreignBearer = foreign?.let {
                        val other = web.post(ComplaintInstallationRoutes.ENROLLMENT, identityBody(it, enrollment = true))
                        checked(other, 201); token(other, it, first)
                    }
                    web.assertRequestsReleased()
                    val captured = first.capture(); first.awaitNativeReclaimed()
                    TestActiveOrdinarySealFixtureV1(first, captured, ordinary).use { sealer ->
                        val verified = sealer.seal(); sealer.assertReleased()
                        awaitInitialCheckpointLeaseExpiry(sealer.observer, sealer.scope)
                        TestActiveInitialCheckpointFixtureV1(sealer, verified, raw).use { checkpoint ->
                            checkpoint.checkpoint(); checkpoint.assertReleased()
                            first.p.f.rows.globalPredecessor?.assertPreserved(first.observer)
                            val providers = providerCounts(first, ordinary, raw)
                            val page = web.get(ComplaintInstallationRoutes.HISTORY, bearer); checked(page, 200)
                            val notice = mapper.readTree(page.body())["notices"].first()
                            val noticeId = UUID.fromString(notice["id"].textValue())
                            val noticeBefore = first.observer.queryForObject("SELECT to_jsonb(c)::text FROM complaints c WHERE id = ? AND data_scope_id = ?", String::class.java, noticeId, first.scope)
                            // One ordinary create/reply admission, then the existing separate edit60 family; no limit or history reset.
                            val target = if (kind === EditTarget.NOTICE_REPLY) {
                                val reply = registeredReplyAttempt(actor, noticeId)
                                checked(web.post(replyPath(reply), replyBody(reply), bearer, reply.input.key), 201)
                                reply.input.id
                            } else {
                                val report = attempt(actor)
                                checked(web.post(ComplaintInstallationRoutes.HISTORY, createBody(report), bearer, report.input.key), 201)
                                report.input.id
                            }
                            val selectedActor = foreign ?: actor; val selectedBearer = foreignBearer ?: bearer
                            val receipts = mutableListOf<Pair<RegisteredInitialEditAttemptV1, HttpResponse<ByteArray>>>()
                            if (kind === EditTarget.FOREIGN_AND_SYSTEM) {
                                val contentBefore = first.observer.queryForList("SELECT to_jsonb(c)::text FROM complaints c WHERE data_scope_id = ? ORDER BY id", String::class.java, first.scope)
                                val audits = auditImage(first)
                                checkNotNull(first.observer.dataSource).connection.use { locker ->
                                    locker.autoCommit = false
                                    try {
                                        // Hold BOTH actual foreign/System resource and content rows. A request that tries
                                        // to lock either must fail its existing100ms limit, not return the receipted404.
                                        for (id in listOf(target, noticeId).sortedBy(UUID::toString)) {
                                            for (table in listOf("complaint_resource_ids", "complaints")) {
                                                locker.prepareStatement("SELECT id FROM $table WHERE id = ? AND data_scope_id = ? FOR UPDATE").use { statement ->
                                                    statement.queryTimeout = 1; statement.setObject(1, id); statement.setObject(2, first.scope)
                                                    statement.executeQuery().use { row -> assertTrue(row.next()); assertFalse(row.next()) }
                                                }
                                            }
                                        }
                                        for (id in listOf(target, noticeId)) {
                                            val attempt = registeredEditAttempt(selectedActor, id, subject = if (id == noticeId) null else "Not owned")
                                            val before = first.counters()
                                            val response = web.patch(attempt, selectedBearer)
                                            checked(response, 404)
                                            assertEquals("COMPLAINT_NOT_FOUND", mapper.readTree(response.body())["errors"][0]["code"].textValue())
                                            assertCreateCharge(before, first.counters(), ComplaintCapacityCharges.NORMAL_RECEIPT)
                                            receipts += attempt to response
                                        }
                                    } finally { locker.rollback() }
                                }
                                assertEquals(contentBefore, first.observer.queryForList("SELECT to_jsonb(c)::text FROM complaints c WHERE data_scope_id = ? ORDER BY id", String::class.java, first.scope))
                                assertEquals(audits, auditImage(first))
                            } else {
                                val subject = if (kind === EditTarget.NOTICE_REPLY) null else " Registered edited subject "
                                val firstEdit = registeredEditAttempt(actor, target, subject = subject)
                                var before = first.counters()
                                val edited = web.patch(firstEdit, bearer); checked(edited, 200)
                                assertCreateCharge(before, first.counters(), ComplaintCapacityCharges.OWNER_EDIT)
                                receipts += firstEdit to edited
                                val secondEdit = registeredEditAttempt(actor, target, version = 2, subject = subject, body = " Later registered edit ")
                                before = first.counters()
                                val later = web.patch(secondEdit, bearer); checked(later, 200)
                                assertCreateCharge(before, first.counters(), ComplaintCapacityCharges.OWNER_EDIT)
                                receipts += secondEdit to later
                                val stale = registeredEditAttempt(actor, target, subject = subject, body = "Stale new key")
                                before = first.counters()
                                val rejected = web.patch(stale, bearer); checked(rejected, 412)
                                assertCreateCharge(before, first.counters(), ComplaintCapacityCharges.NORMAL_RECEIPT)
                                receipts += stale to rejected
                                // Exact first receipt is historical version2, never current version3 or a fresh If-Match check.
                                val historical = web.patch(firstEdit, bearer); checked(historical, 200); assertArrayEquals(edited.body(), historical.body())
                                val detail = web.get("${ComplaintInstallationRoutes.HISTORY}/$target", bearer); checked(detail, 200)
                                val item = mapper.readTree(detail.body())
                                assertEquals(3L, item["version"].longValue()); assertEquals(secondEdit.candidate.request.body, item["body"].textValue())
                                assertEquals("ANDROID", item["platform"].textValue()); assertEquals(header(later, "ETag"), header(detail, "ETag"))
                                if (kind === EditTarget.NOTICE_REPLY) {
                                    assertEquals("REPLY", item["kind"].textValue()); assertEquals("CUSTOM", item["type"].textValue()); assertTrue(item["subject"].isNull)
                                    assertEquals(notice["noticeKey"].textValue(), item["noticeKey"].textValue()); assertEquals(noticeId.toString(), item["replyToId"].textValue())
                                } else {
                                    assertEquals("REPORT", item["kind"].textValue()); assertEquals("TECHNICAL", item["type"].textValue())
                                    assertEquals(secondEdit.candidate.request.subject, item["subject"].textValue()); assertFalse(item.has("noticeKey"))
                                }
                            }
                            assertEquals(noticeBefore, first.observer.queryForObject("SELECT to_jsonb(c)::text FROM complaints c WHERE id = ? AND data_scope_id = ?", String::class.java, noticeId, first.scope))
                            val old = ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateReply(first.registration, first.assembly,
                                web.context.getBean(PersistencePhaseOwnership::class.java), web.context.getBean(JdbcTemplate::class.java), web.context.getBean(AuditService::class.java))
                            assertEquals(SUBSET + "${ComplaintInstallationRoutes.HISTORY}/{id}/replies", old.mappedPaths)
                            assertFalse(old.mapsRequest(MockHttpServletRequest("PATCH", editPath(receipts.first().first))))
                            for ((method, path) in listOf("GET" to editPath(receipts.first().first), "POST" to editPath(receipts.first().first),
                                "PATCH" to "${editPath(receipts.first().first)}/", "PATCH" to "${ComplaintInstallationRoutes.HISTORY}/AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA/content",
                                "GET" to ComplaintInstallationRoutes.ME, "DELETE" to "${ComplaintInstallationRoutes.HISTORY}/$target", "POST" to ComplaintInstallationRoutes.DELETE_ALL)) {
                                web.refusedBeforeBody(method, path, selectedBearer, 404)
                            }
                            val counters = first.counters(); val audits = auditImage(first)
                            assertEquals(1, first.observer.update("UPDATE complaint_journal_control SET maintenance_closed = true, creation_closed = true WHERE data_scope_id = ?", first.scope))
                            for ((attempt, original) in receipts) {
                                val replay = web.patch(attempt, selectedBearer); checked(replay, original.statusCode())
                                assertArrayEquals(original.body(), replay.body()); assertEquals(header(original, "ETag"), header(replay, "ETag")); assertNull(header(replay, "Location"))
                                val status = web.post(ComplaintInstallationRoutes.STATUS, statusBody(attempt), selectedBearer); checked(status, 200)
                                val receipt = mapper.readTree(status.body())
                                assertEquals(original.statusCode(), receipt["originalStatus"].intValue())
                                if (original.statusCode() == 200) {
                                    assertEquals("APPLIED", receipt["outcome"].textValue()); assertEquals(attempt.input.precondition.version + 1, receipt["body"]["version"].longValue())
                                    assertEquals(header(original, "ETag"), receipt["etag"].textValue())
                                } else {
                                    assertEquals("REJECTED", receipt["outcome"].textValue())
                                    assertEquals(mapper.readTree(original.body())["errors"][0]["code"].textValue(), receipt["problemCode"].textValue())
                                }
                            }
                            checked(web.patch(registeredEditAttempt(selectedActor, target, version = 3, body = "Closed new edit"), selectedBearer), 503)
                            first.registration.close()
                            checked(web.patch(receipts.first().first, selectedBearer), 503)
                            checked(web.post(ComplaintInstallationRoutes.STATUS, statusBody(receipts.first().first), selectedBearer), 503)
                            assertEquals(counters, first.counters()); assertEquals(audits, auditImage(first))
                            assertEquals(providers, providerCounts(first, ordinary, raw)); web.assertRequestsReleased(); checkpoint.assertReleased()
                        }
                    }
                } finally {
                    web.assertRequestsReleased()
                    first.observer.update("DELETE FROM complaints WHERE data_scope_id = ?", first.scope)
                    first.observer.update("DELETE FROM complaint_resource_ids WHERE data_scope_id = ?", first.scope)
                    for (owner in listOfNotNull(actor, foreign)) first.observer.update("DELETE FROM complaint_idempotency_receipts WHERE data_scope_id = ? AND actor_id = ?", first.scope, owner.id)
                    first.observer.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND action IN ('COMPLAINT_CREATED', 'COMPLAINT_CONTENT_EDITED')", first.scope)
                }
                startup.close(); web.assertDisposed(nativeStillActive = true)
            }
        }
    }

    private enum class EditTarget { OWN_REPORT, NOTICE_REPLY, FOREIGN_AND_SYSTEM }

    fun heldRequestDrainsBeforeJpaClose(tls: VersionBoundPersistenceConnectedFixture) = withPrepared(tls) { first, _, _ ->
        first.assembly.beginRegisteredHttpStartup(first.registration).use { startup ->
            startup.start()
            StartedHttpView(first, startup).use { web ->
                val actor = first.initial.candidate().installation
                val body = identityBody(actor, enrollment = true)
                val before = first.counters()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(loopback(), web.port), 2_000)
                    socket.soTimeout = 5_000
                    socket.tcpNoDelay = true
                    val output = socket.getOutputStream()
                    output.write(("POST ${ComplaintInstallationRoutes.ENROLLMENT} HTTP/1.1\r\nHost: 127.0.0.1:${web.port}\r\n" +
                        "Content-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    output.write(body, 0, 1); output.flush() // Real servlet ingress owns the unfinished body, before any JPA work.
                    awaitLifecycleFact(5_000) { web.ingressSnapshot().let { it.reservations == 1 && it.contexts == 1 } }
                    assertEquals(0, web.admission.activeOwners())
                    OwnedCallerTestScope().use { callers ->
                        callers.beforeClose(socket::close)
                        val releasing = callers.launch {
                            try {
                                awaitLifecycleFact(5_000) { web.ingressSnapshot().stopped }
                                val held = web.ingressSnapshot()
                                assertEquals(1, held.reservations); assertEquals(1, held.contexts)
                                assertTrue(web.emf.isOpen); assertTrue(web.context.isActive)
                                assertEquals(LifecycleState.STARTED, web.server.tomcat.server.state)
                                assertFalse(first.process.pools.shutdownRequested())
                                assertTrue(first.process.pools.ordinary.businessReady())
                                assertFalse(poolTestField<Boolean>(startup, "cleanupProven"))
                                output.write(body, 1, body.size - 1); output.flush()
                            } finally {
                                // EOF releases the actual blocked body read. Shutdown need not deliver an HTTP response.
                                socket.close()
                            }
                        }
                        startup.close() // Original assembly caller; no background close or reset of the original 10s allowance.
                        releasing.value()
                    }
                }
                web.assertDisposed(nativeStillActive = true)
                assertEquals(before, first.counters())
                assertEquals(0L, first.observer.queryForObject("SELECT count(*) FROM app_installations WHERE id = ? AND data_scope_id = ?", Long::class.java, actor.id, first.scope))
                assertEquals(0L, first.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? " +
                    "AND action = 'COMPLAINT_INSTALLATION_ENROLLED'", Long::class.java, first.scope))
            }
        }
    }

    fun assemblyClosesRetainedStartup(tls: VersionBoundPersistenceConnectedFixture) {
        var retained: StartedHttpView? = null
        withPrepared(tls) { first, _, _ ->
            val startup = first.assembly.beginRegisteredHttpStartup(first.registration)
            startup.start()
            StartedHttpView(first, startup).use { web ->
                checked(web.get(ComplaintInstallationRoutes.BOOTSTRAP), 200)
                web.assertRequestsReleased()
                retained = web
                assertFalse(first.process.pools.shutdownRequested())
                // Deliberately leave the actual startup to its original assembly. The existing fixture
                // closes its peer first, then that assembly, and checks native/trust disposal before fallback.
            }
        }
        checkNotNull(retained).assertDisposed(nativeStillActive = false)
    }

    // CREATE and read-after-CREATE cases need the captured global predecessor. Lifecycle-only cases keep the old prerequisite.
    internal fun withPrepared(tls: VersionBoundPersistenceConnectedFixture, globalScanBeforeActivation: Boolean = false,
        initialCheckpointCreate: TestInitialCheckpointCreateInputV1 = TestInitialCheckpointCreateInputV1(1, VersionBoundTestInitialCheckpointCreateV1.PROFILE),
        adminRead: me.manga.kira.backend.complaint.infrastructure.admission.TestRegisteredAdminReadInputV1? = null,
        action: (TestActiveFirstCutFixtureV1, TestActiveOrdinaryRawFixtureV1, TestActiveInitialCheckpointRawFixtureV1) -> Unit) {
        val ordinary = TestActiveOrdinaryRawFixtureV1()
        val raw = TestActiveInitialCheckpointRawFixtureV1()
        val factories = ordinary.factories.let { TestActiveOrdinaryRawHttpV1(it.sts, it.kms, it.s3, raw.input,
            initialCheckpointCreate = initialCheckpointCreate, adminRead = adminRead) }
        withTestActiveFirstCut(tls, ordinaryRawHttp = factories, globalScanBeforeActivation = globalScanBeforeActivation) {
            first -> action(first, ordinary, raw)
        }
    }

    /** Passive references to the actual product graph; this view owns only its real TCP client. */
    internal class StartedHttpView(val first: TestActiveFirstCutFixtureV1, val startup: ComplaintTestRegisteredHttpStartupV1,
        expectedPaths: Set<String> = SUBSET) : AutoCloseable {
        val context = poolTestField<AnnotationConfigServletWebServerApplicationContext>(startup, "context")
        val emf = poolTestField<EntityManagerFactory>(startup, "emf")
        val server = poolTestField<TomcatWebServer>(startup, "server")
        val admission = poolTestField<OrdinaryPersistenceAdmission>(startup, "admission")
        val port = startup.localPort
        private val ingress = first.process.consumers.ingressAdmission
        private val client: HttpClient

        init {
            assertTrue(context.isActive); assertTrue(emf.isOpen)
            assertSame(server, context.webServer)
            assertSame(first.process.pools.ordinary, context.getBean("dataSource"))
            assertSame(first.process.pools.ordinary, (emf as EntityManagerFactoryInfo).dataSource)
            assertSame(emf, context.getBean("entityManagerFactory"))
            val manager = context.getBean(GuardedJpaTransactionManager::class.java)
            val ownership = context.getBean(PersistencePhaseOwnership::class.java)
            assertSame(emf, manager.entityManagerFactory); assertSame(manager, ownership.manager)
            assertSame(ownership, poolTestField<PersistencePhaseOwnership>(startup, "ownership"))
            assertSame(admission, context.getBean(OrdinaryPersistenceAdmission::class.java))
            val jdbc = context.getBean(JdbcTemplate::class.java)
            assertSame(jdbc, poolTestField<JdbcTemplate>(startup, "jdbc"))
            first.registration.requireInstallationResources(ownership, jdbc)
            val size = first.process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.ORDINARY }.hikari.sizing.maximumPoolSize
            assertTrue(admission.matchesComplaintPool(size))
            assertSame(first.process.consumers.jwt.boundUserKeyProvider, context.getBean(JwtKeyProvider::class.java))
            assertEquals(1, context.getBeansOfType(EntityManagerFactory::class.java).size)
            assertEquals(2, context.getBeansOfType(SecurityFilterChain::class.java).size)
            val composition = context.getBean(ComplaintTestBootstrapHttpCompositionV1::class.java)
            val mapping = context.getBean("complaintTestBootstrapHandlerMapping", SimpleUrlHandlerMapping::class.java)
            assertEquals(expectedPaths, mapping.urlMap.keys)
            mapping.urlMap.values.forEach { assertSame(composition.handler, it) }
            val listener = context.getBean(TomcatServletWebServerFactory::class.java)
            assertEquals("127.0.0.1", checkNotNull(listener.address).hostAddress); assertEquals(0, listener.port)
            assertTrue(port in 1..65_535)
            for (bean in listOf("dataSource", "entityManagerFactory", "transactionManager", "jwtKeyProvider")) {
                assertEquals("", (context.beanFactory.getBeanDefinition(bean) as RootBeanDefinition).destroyMethodName)
            }
            client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(direct).connectTimeout(Duration.ofSeconds(2)).build()
        }

        fun get(path: String, bearer: String? = null): HttpResponse<ByteArray> =
            send(HttpRequest.newBuilder(uri(path)).apply { bearer?.let { header("Authorization", "Bearer $it") } }.GET())
        fun post(path: String, body: ByteArray, bearer: String? = null, key: UUID? = null): HttpResponse<ByteArray> =
            send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").apply {
                bearer?.let { header("Authorization", "Bearer $it") }
                key?.let { header("X-Kira-Idempotency-Key", it.toString()) }
            }.POST(HttpRequest.BodyPublishers.ofByteArray(body)))

        fun patch(attempt: RegisteredInitialEditAttemptV1, bearer: String): HttpResponse<ByteArray> =
            send(HttpRequest.newBuilder(uri(editPath(attempt))).header("Content-Type", "application/json")
                .header("Authorization", "Bearer $bearer").header("X-Kira-Idempotency-Key", attempt.input.key.toString())
                .header("If-Match", attempt.input.precondition.canonical).method("PATCH", HttpRequest.BodyPublishers.ofByteArray(editBody(attempt))))

        /** No body is sent. An optional container 100 Continue is not the required final refusal. */
        fun refusedBeforeBody(method: String, path: String, bearer: String, expected: Int) = Socket().use { socket ->
            socket.connect(InetSocketAddress(loopback(), port), 2_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(("$method $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $bearer\r\n" +
                    "Content-Type: application/json\r\nContent-Length: 2\r\nExpect: 100-continue\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                flush()
            }
            val input = socket.getInputStream()
            repeat(2) { index ->
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) {
                    val next = input.read()
                    assertTrue(next >= 0 && head.length < 4096, "Expected a bounded final response before sending any request body.")
                    head.append(next.toChar())
                }
                val lines = head.toString().split("\r\n")
                if (index == 0 && lines.first().startsWith("HTTP/1.1 100 ")) return@repeat
                assertTrue(lines.first().startsWith("HTTP/1.1 $expected "), "Body admission must not precede the final refusal.")
                assertTrue(lines.any { it.equals("X-Kira-Complaint-Contract: 1", ignoreCase = true) })
                assertTrue(lines.any { it.equals("Cache-Control: no-store, no-transform", ignoreCase = true) })
                return@use
            }
            error("No final refusal before request body.")
        }

        private fun uri(path: String) = URI.create("http://127.0.0.1:$port$path")
        private fun send(request: HttpRequest.Builder): HttpResponse<ByteArray> =
            client.send(request.timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofByteArray())
        fun ingressSnapshot(): IngressSnapshot = snapshot(ingress)
        fun assertRequestsReleased() {
            awaitLifecycleFact(5_000) { ingressSnapshot().let { it.reservations == 0 && it.contexts == 0 } && admission.activeOwners() == 0 }
            requireConnectionFree()
            assertNull(PersistencePhaseOwnership.current())
        }
        fun assertDisposed(nativeStillActive: Boolean) {
            startup.requireCleanupProven()
            assertFalse(context.isActive); assertFalse(emf.isOpen)
            assertEquals(LifecycleState.DESTROYED, server.tomcat.server.state)
            assertTrue(ingress.registeredStartupAdmissionReleased()); assertEquals(0, admission.activeOwners())
            assertEquals(!nativeStillActive, first.process.pools.shutdownRequested())
            assertEquals(nativeStillActive, first.process.pools.ordinary.businessReady())
        }
        override fun close() = client.close() // No application, JPA, native-pool or trust teardown in this view.
    }

    internal data class IngressSnapshot(val stopped: Boolean, val reservations: Int, val contexts: Int)
    private fun snapshot(ingress: ComplaintIngressAdmission): IngressSnapshot = synchronized(poolTestField<Any>(ingress, "lock")) {
        IngressSnapshot(poolTestField(ingress, "closed"), poolTestField(ingress, "reservations"), poolTestField<Map<*, *>>(ingress, "contexts").size)
    }
    private fun assertRefused(action: () -> Any?) = assertEquals(ComplaintTestDeploymentFailureV1.PROCESS_REFUSED,
        assertThrows<ComplaintTestDeploymentExceptionV1> { action() }.code)
    private fun providerCounts(first: TestActiveFirstCutFixtureV1, ordinary: TestActiveOrdinaryRawFixtureV1, raw: TestActiveInitialCheckpointRawFixtureV1) =
        listOf(first.native.sts.requests.size, first.native.kms.requests.size, first.native.requests.size, ordinary.requestBudgets.size,
            raw.sts.requests.size, raw.kms.requests.size, raw.requests.size, first.p.f.http.read.requests.size)

    private fun auditImage(first: TestActiveFirstCutFixtureV1): List<String> = first.observer.queryForList(
        "SELECT jsonb_build_array(to_jsonb(a), a.xmin::text)::text FROM audit_log a WHERE complaint_data_scope_id = ? ORDER BY id", String::class.java, first.scope)

    private fun attempt(actor: ScopedInstallationId): RegisteredInitialCreateAttemptV1 {
        val input = ComplaintOwnerCreateInput(UUID.randomUUID(), UUID.randomUUID(), ComplaintType.TECHNICAL,
            " Registered subject ", " Registered body\r\nline ", ComplaintReportMetadataInput(null, "fixture-os", "", ""))
        val identity = checkNotNull(ComplaintReportIdentity.checked(input.id.toString(), input.key.toString(), actor.scope.id.toString()))
        val request = (ComplaintReportRequest.normalize(identity, input.type, input.subject, input.body, input.metadata) as ComplaintReportRequestResult.Accepted).request
        return RegisteredInitialCreateAttemptV1(input, ComplaintOwnerCreateCandidate.prepare(actor, request))
    }
    private fun identityBody(actor: ScopedInstallationId, enrollment: Boolean = false): ByteArray = mapper.writeValueAsBytes(
        linkedMapOf("installationId" to actor.id.toString(), "expectedDataScopeId" to actor.scope.id.toString(),
            "secret" to Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })).also { if (enrollment) it["platform"] = "ANDROID" })
    private fun createBody(attempt: RegisteredInitialCreateAttemptV1): ByteArray = mapper.writeValueAsBytes(mapOf(
        "id" to attempt.input.id.toString(), "type" to attempt.input.type.name, "subject" to attempt.input.subject, "body" to attempt.input.body,
        "metadata" to mapOf("appVersion" to null, "osVersion" to "fixture-os", "manufacturer" to "", "deviceModel" to "")))
    private fun statusBody(attempt: RegisteredInitialCreateAttemptV1): ByteArray = mapper.writeValueAsBytes(mapOf(
        "operation" to "OWNER_CREATE", "key" to attempt.input.key.toString(), "targetIds" to listOf(attempt.input.id.toString()),
        "fingerprint" to ComplaintReportFingerprint.of(attempt.candidate.request).encoded))
    private fun replyPath(attempt: RegisteredInitialReplyAttemptV1) = "${ComplaintInstallationRoutes.HISTORY}/${attempt.input.parentId}/replies"
    private fun replyBody(attempt: RegisteredInitialReplyAttemptV1): ByteArray = mapper.writeValueAsBytes(mapOf(
        "id" to attempt.input.id.toString(), "body" to attempt.input.body,
        "metadata" to mapOf("appVersion" to null, "osVersion" to "fixture-os", "manufacturer" to "", "deviceModel" to "")))
    private fun statusBody(attempt: RegisteredInitialReplyAttemptV1, reversed: Boolean = false): ByteArray = mapper.writeValueAsBytes(mapOf(
        "operation" to "OWNER_REPLY", "key" to attempt.input.key.toString(),
        "targetIds" to listOf(attempt.input.parentId.toString(), attempt.input.id.toString()).let { if (reversed) it.reversed() else it },
        "fingerprint" to ComplaintReplyFingerprint.of(attempt.candidate.request).encoded))
    private fun editPath(attempt: RegisteredInitialEditAttemptV1) = "${ComplaintInstallationRoutes.HISTORY}/${attempt.input.targetId}/content"
    private fun editBody(attempt: RegisteredInitialEditAttemptV1): ByteArray = mapper.writeValueAsBytes(linkedMapOf<String, Any?>().apply {
        if (attempt.input.subject != null) put("subject", attempt.input.subject)
        put("body", attempt.input.body)
    })
    private fun statusBody(attempt: RegisteredInitialEditAttemptV1): ByteArray = mapper.writeValueAsBytes(mapOf(
        "operation" to "OWNER_EDIT", "key" to attempt.input.key.toString(), "targetIds" to listOf(attempt.input.targetId.toString()),
        "fingerprint" to ComplaintOwnerEditFingerprint.of(attempt.candidate.request).encoded))
    private fun token(response: HttpResponse<ByteArray>, actor: ScopedInstallationId, first: TestActiveFirstCutFixtureV1): String {
        val parsed = mapper.readTree(response.body())
        assertEquals(actor.id.toString(), parsed["installationId"].textValue()); assertEquals(actor.scope.id.toString(), parsed["dataScopeId"].textValue())
        assertEquals(1L, parsed["credentialVersion"].longValue())
        return parsed["accessToken"].textValue().also {
            assertEquals(actor, InstallationJwtCodec(first.process.consumers.jwt.installationKeyRing, Clock.systemUTC()).verify(it).installation)
        }
    }
    private fun assertCreateCharge(before: Map<String, CounterSnapshot>, after: Map<String, CounterSnapshot>, amount: ComplaintCapacityVector = ComplaintCapacityCharges.OWNER_CREATE) {
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
        assertEquals(expected, response.statusCode())
        assertEquals("1", header(response, "X-Kira-Complaint-Contract")); assertEquals("no-store, no-transform", header(response, "Cache-Control"))
        assertTrue(response.body().size in 1..16 * 1024)
        assertNull(header(response, "Set-Cookie")); assertNull(header(response, "Content-Encoding"))
    }
    private fun loopback(): InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val mapper = ObjectMapper()
    private val SUBSET = setOf(ComplaintInstallationRoutes.BOOTSTRAP, ComplaintInstallationRoutes.ENROLLMENT, ComplaintInstallationRoutes.SESSION,
        ComplaintInstallationRoutes.HISTORY, ComplaintInstallationRoutes.STATUS, "${ComplaintInstallationRoutes.HISTORY}/{id}")
    private val direct = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
    }
}
