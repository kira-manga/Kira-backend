package me.manga.kira.backend.complaint.infrastructure.reconciliation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.api.ComplaintAdminReadHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintAdminReadResponses
import me.manga.kira.backend.complaint.api.ComplaintOwnerDetailHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryResponses
import me.manga.kira.backend.complaint.application.ComplaintAdminReadService
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutFixtureV1
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadPort
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRejected
import me.manga.kira.backend.complaint.domain.ComplaintAdminSearchQuery
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatsQuery
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminJwtIdentityDecoder
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadAdapter
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentFailureV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestRegisteredAdminReadInputV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredHttpStartupCasesV1.StartedHttpView
import me.manga.kira.backend.config.ComplaintTestBootstrapHttpCompositionV1
import me.manga.kira.backend.config.ComplaintTestRegisteredAdminReadsV1
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.security.ComplaintAdminReadAdmissionPolicy
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import me.manga.kira.backend.security.InstallationJwtCodec
import me.manga.kira.backend.security.JwtKeyProvider
import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.security.adminReadStatsRequest
import me.manga.kira.backend.security.historyTestRequest
import me.manga.kira.backend.support.JwtTestSupport
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.io.IOException
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

/**
 * SOURCE ONLY / NOT_COMPILED / NOT_RUN. Reuses the genuine pre-D/PROJECT/registration/identity-release
 * fixture and its original loopback/JPA view. No replacement authority, current-row result or cleanup.
 * G1 remains: Delegated model safety refusal; no implementation retry or workaround.
 */
internal object TestRegisteredAdminReadHttpCasesV1 {
    fun createdOwnersSearchDetailStats(tls: VersionBoundPersistenceConnectedFixture) = withStartup(tls) { first, ordinary, raw, web ->
        withUsers(web, listOf(Role.ADMIN, Role.ADMIN)) { users, signer ->
            val actor = first.initial.candidate().installation
            val other = first.initial.candidate().installation
            val owners = listOf(actor, other)
            val bearers = owners.map { enroll(web, it) }
            val admin = signer.issue(users[0]).value
            val otherAdmin = signer.issue(users[1]).value
            val scope = actor.scope
            try {
                val captured = first.capture()
                first.awaitNativeReclaimed()
                TestActiveOrdinarySealFixtureV1(first, captured, ordinary).use { sealer ->
                    val verified = sealer.seal()
                    sealer.assertReleased()
                    awaitInitialCheckpointLeaseExpiry(sealer.observer, sealer.scope)
                    TestActiveInitialCheckpointFixtureV1(sealer, verified, raw).use { checkpoint ->
                        checkpoint.checkpoint() // Discard Completed; the actual registered CREATE obtains its own current proof.
                        checkpoint.assertReleased()
                        first.p.f.rows.globalPredecessor?.assertPreserved(first.observer)
                        val ids = owners.mapIndexed { index, _ ->
                            val id = UUID.randomUUID()
                            val body = mapper.writeValueAsBytes(mapOf("id" to id.toString(), "type" to "TECHNICAL",
                                "subject" to "Registered Admin read $index", "body" to "Counted report $index",
                                "metadata" to mapOf("appVersion" to "registered-read-fixture", "osVersion" to "fixture-os",
                                    "manufacturer" to "", "deviceModel" to "")))
                            val created = web.post(ComplaintInstallationRoutes.HISTORY, body, bearers[index], UUID.randomUUID())
                            checked(created, 201)
                            assertEquals(id.toString(), json(created)["id"].textValue())
                            assertEquals(1L, json(created)["version"].longValue())
                            id
                        }
                        web.assertRequestsReleased()
                        val image = readImage(first)
                        val providers = providerCounts(first, ordinary, raw)
                        val allResponse = search(web, admin)
                        checked(allResponse, 200)
                        val all = json(allResponse)["items"].toList()
                        assertEquals(4, all.size)
                        assertEquals(ids.map(UUID::toString).toSet(), all.filter { it["kind"].textValue() == "REPORT" }.map { it["id"].textValue() }.toSet())
                        assertEquals(2, all.count { it["kind"].textValue() == "NOTICE" })
                        assertEquals(2, all.filter { it["kind"].textValue() == "REPORT" }.map { it["ownerReference"].textValue() }.toSet().size)
                        assertNull(header(allResponse, "ETag"))

                        val firstPage = json(search(web, admin, limit = 2).also { checked(it, 200) })
                        val cursor = checkNotNull(firstPage["nextCursor"].textValue())
                        assertTrue(cursor.length <= 2048)
                        val position = checkNotNull(first.process.consumers.adminCursorCodec)
                            .decode(cursor, users[0].id, ComplaintAdminSearchQuery(scope, limit = 2))
                        assertEquals(firstPage["items"].last()["id"].textValue(), position.id.toString())
                        val tail = json(search(web, admin, limit = 2, cursor = cursor).also { checked(it, 200) })
                        assertEquals(all, firstPage["items"].toList() + tail["items"].toList())
                        assertTrue(tail["nextCursor"].isNull)
                        // The same genuine cursor cannot move to another ADMIN, scope, or selected page size.
                        problem(search(web, otherAdmin, limit = 2, cursor = cursor), 400, "INVALID_CURSOR")
                        problem(search(web, admin, limit = 1, cursor = cursor), 400, "INVALID_CURSOR")
                        problem(search(web, admin, scope = ComplaintDataScope.of(UUID.randomUUID()), limit = 2, cursor = cursor), 400, "INVALID_CURSOR")

                        for (item in all) {
                            val detail = web.get(detailPath(scope, UUID.fromString(item["id"].textValue())), admin)
                            checked(detail, 200)
                            assertEquals(item, json(detail))
                            assertEquals(item["actionTag"]?.textValue(), header(detail, "ETag"))
                        }
                        val stats = web.get(statsPath(scope), admin)
                        checked(stats, 200)
                        val counts = json(stats)
                        assertEquals(scope.id.toString(), counts["dataScopeId"].textValue())
                        assertEquals(4L, counts["total"].longValue())
                        assertEquals(mapOf("OPEN" to 2L, "PINNED" to 2L), nonzeroBuckets(counts, "byStatus", "status"))
                        assertEquals(mapOf("INSTALLATION" to 2L, "SYSTEM" to 2L), nonzeroBuckets(counts, "byOwnership", "ownership"))
                        assertNull(header(stats, "ETag"))
                        owners.forEachIndexed { index, _ ->
                            val own = web.get(ComplaintInstallationRoutes.HISTORY, bearers[index])
                            checked(own, 200)
                            assertEquals(listOf(ids[index].toString()), json(own)["items"].map { it["id"].textValue() })
                            assertEquals(2, json(own)["notices"].size())
                            checked(web.get("${ComplaintInstallationRoutes.HISTORY}/${ids[1 - index]}", bearers[index]), 404)
                        }
                        web.assertRequestsReleased(); checkpoint.assertReleased()
                        assertEquals(image, readImage(first), "Search, paging, details, stats and owner reads cannot mutate stored state.")
                        assertEquals(providers, providerCounts(first, ordinary, raw))
                    }
                }
            } finally {
                web.assertRequestsReleased()
                // Existing disposable-scope teardown only; no product erasure, counter refund, or replacement authority.
                first.observer.update("DELETE FROM complaints WHERE data_scope_id = ?", first.scope)
                first.observer.update("DELETE FROM complaint_resource_ids WHERE data_scope_id = ?", first.scope)
                owners.forEach { first.observer.update("DELETE FROM complaint_idempotency_receipts WHERE data_scope_id = ? AND actor_id = ?", first.scope, it.id) }
                first.observer.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND action = 'COMPLAINT_CREATED'", first.scope)
            }
        }
    }

    fun currentRoleBearerAndScope(tls: VersionBoundPersistenceConnectedFixture) = withStartup(tls) { first, _, _, web ->
        withUsers(web, listOf(Role.ADMIN, Role.USER)) { users, signer ->
            val admin = signer.issue(users[0]).value
            val diagnosticUser = signer.issue(users[1]).value
            val owner = first.initial.candidate().installation
            val installationToken = enroll(web, owner)
            val scope = owner.scope
            val notice = noticeId(web, admin)
            val image = readImage(first)
            allReads(web, diagnosticUser, scope, notice).forEach { problem(it, 403, "FORBIDDEN") }
            assertEquals(1, first.observer.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", users[1].id))
            allReads(web, diagnosticUser, scope, notice).forEach { checked(it, 200) } // Token still says USER.
            assertEquals(1, first.observer.update("UPDATE users SET role = 'USER' WHERE id = ?", users[0].id))
            try { allReads(web, admin, scope, notice).forEach { problem(it, 403, "FORBIDDEN") } } // Stale ADMIN claim cannot grant access.
            finally { assertEquals(1, first.observer.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", users[0].id)) }

            val properties = web.context.getBean(KiraSecurityProperties::class.java)
            val expiredSigner = JwtService(web.context.getBean(JwtKeyProvider::class.java), properties,
                Clock.fixed(Instant.now().minus(properties.accessTokenTtl).minus(properties.clockSkew).minusSeconds(61), ZoneOffset.UTC))
            for (bad in listOf(null, "not-a-token", JwtTestSupport.tamperSignature(admin), installationToken, expiredSigner.issue(users[0]).value)) {
                allReads(web, bad, scope, notice).forEach { problem(it, 401, "UNAUTHORIZED") }
            }
            for ((change, restore) in listOf(
                "enabled = false" to "enabled = true",
                "credential_version = credential_version + 1" to "credential_version = 0",
            )) {
                assertEquals(1, first.observer.update("UPDATE users SET $change WHERE id = ?", users[0].id))
                try { allReads(web, admin, scope, notice).forEach { problem(it, 401, "UNAUTHORIZED") } }
                finally { assertEquals(1, first.observer.update("UPDATE users SET $restore WHERE id = ?", users[0].id)) }
            }
            allReads(web, admin, ComplaintDataScope.of(UUID.randomUUID()), notice).forEach { problem(it, 404, "NOT_FOUND") }

            // Real AUTH has committed/released. The data phase must recheck the database, not reuse that old role.
            val reader = guardedReader(web)
            first.process.consumers.ingressAdmission.withIngress(adminReadStatsRequest(scope, admin)) { context ->
                val authenticated = reader.authenticate(context, admin, ComplaintAdminStatsQuery(scope))
                requireConnectionFree(); assertEquals(0, web.admission.activeOwners())
                assertEquals(1, first.observer.update("UPDATE users SET role = 'USER' WHERE id = ?", users[0].id))
                try { assertEquals(ComplaintAdminReadFailure.FORBIDDEN, assertThrows<ComplaintAdminReadRejected> { reader.read(context, authenticated) }.failure) }
                finally { assertEquals(1, first.observer.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", users[0].id)) }
            }
            web.assertRequestsReleased()
            assertEquals(image, readImage(first))
        }
    }

    fun exactSubsetResourcesAndLifetime(tls: VersionBoundPersistenceConnectedFixture) = withStartup(tls,
        createProfile = VersionBoundTestInitialCheckpointCreateV1.EDIT_PROFILE) { first, _, _, web ->
        withUsers(web) { users, signer ->
            val composition = composition(web)
            val r = first.registration; val a = first.assembly
            val o = web.context.getBean(PersistencePhaseOwnership::class.java)
            val j = web.context.getBean(JdbcTemplate::class.java)
            val audit = web.context.getBean(AuditService::class.java)
            val decoder = web.context.getBean("jwtDecoder", JwtDecoder::class.java)
            val old = listOf(
                ComplaintTestBootstrapHttpCompositionV1.fromRegistered(r, o, j),
                ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointCreate(r, a, o, j, audit),
                ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreate(r, a, o, j, audit),
                ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateReply(r, a, o, j, audit),
                ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateReplyEdit(r, a, o, j, audit),
                ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateMe(r, a, o, j, audit),
            )
            val id = UUID.randomUUID()
            val adminRoutes = listOf("POST" to SEARCH, "GET" to "$ADMIN/$id", "GET" to STATS)
            old.forEach { narrow ->
                assertTrue(narrow.mappedPaths.intersect(ADMIN_PATHS).isEmpty())
                adminRoutes.forEach { (method, path) -> closedBeforeBody(narrow, method, path) }
            }
            for ((method, path) in listOf(
                "GET" to SEARCH, "HEAD" to STATS, "POST" to STATS, "GET" to "$STATS/",
                "GET" to "$ADMIN/$id/", "DELETE" to "$ADMIN/$id", "PATCH" to "$ADMIN/$id/content",
                "POST" to "$ADMIN/batch", "POST" to "/api/v1/admin/step-up", "GET" to ComplaintInstallationRoutes.ME,
                "POST" to "${ComplaintInstallationRoutes.HISTORY}/$id/replies", "PATCH" to "${ComplaintInstallationRoutes.HISTORY}/$id/content",
            )) {
                // Step-up is outside the complaint namespace; the real standalone listener denies it instead.
                if (path.endsWith("/step-up")) checked(web.post(path, byteArrayOf()), 404)
                else closedBeforeBody(composition, method, path)
            }
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
                ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateAdminRead(r, a, o, JdbcTemplate(checkNotNull(j.dataSource)), audit, web.startup, decoder)
            }
            ComplaintTestProcessAssemblyV1.begin().use { foreign ->
                assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
                    ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateAdminRead(r, foreign, o, j, audit, web.startup, decoder)
                }
            }
            assertEquals(ComplaintTestDeploymentFailureV1.PROCESS_REFUSED, assertThrows<ComplaintTestDeploymentExceptionV1> {
                // Even the exact configured decoder/pair cannot mint another composition after the original refresh claim.
                ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateAdminRead(r, a, o, j, audit, web.startup, decoder)
            }.code)
            r.requireIdentityAdmissionPhaseResources(o, j)
            val cohort = poolTestField<ComplaintTestRegisteredAdminReadsV1>(composition, "adminReads")
            val actual = poolTestField<ComplaintAdminReadAdapter>(cohort, "reader")
            val identities = poolTestField<ComplaintAdminJwtIdentityDecoder>(actual, "identities")
            assertSame(decoder, poolTestField<JwtDecoder>(identities, "userDecoder"))
            assertSame(first.process.consumers.adminCursorCodec, poolTestField<Any>(actual, "cursors"))
            val admin = signer.issue(users.single()).value
            val scope = first.process.desiredSettings().scope
            val notice = noticeId(web, admin)
            val image = readImage(first)
            val reader = guardedReader(web)
            first.process.consumers.ingressAdmission.withIngress(adminReadStatsRequest(scope, admin)) { context ->
                val authenticated = reader.authenticate(context, admin, ComplaintAdminStatsQuery(scope))
                requireConnectionFree(); assertEquals(0, web.admission.activeOwners())
                r.close()
                assertEquals(ComplaintAdminReadFailure.UNAVAILABLE, assertThrows<ComplaintAdminReadRejected> { reader.read(context, authenticated) }.failure)
            }
            allReads(web, admin, scope, notice).forEach { problem(it, 503, "SERVICE_UNAVAILABLE") }
            web.assertRequestsReleased()
            assertEquals(image, readImage(first))
        }
    }

    fun sharedResponsesQuotaAndCleanup(tls: VersionBoundPersistenceConnectedFixture) = withStartup(tls, perMinute = 3) { first, _, _, web ->
        withUsers(web) { users, signer ->
            val admin = signer.issue(users.single()).value
            val actor = first.initial.candidate().installation
            val bearer = enroll(web, actor)
            val scope = actor.scope
            // The genuine activation notices are enough here; this test does not manufacture a CREATE checkpoint.
            val notice = checkNotNull(first.observer.queryForObject("SELECT id FROM complaints WHERE data_scope_id = ? AND kind = 'NOTICE' ORDER BY id LIMIT 1", UUID::class.java, first.scope))
            val composition = composition(web)
            val reads = poolTestField<Any>(composition, "reads")
            val history = poolTestField<ComplaintOwnerHistoryHttpHandler>(reads, "history")
            val detail = poolTestField<ComplaintOwnerDetailHttpHandler>(reads, "detail")
            val owner = poolTestField<ComplaintOwnerHistoryResponses>(history, "responses")
            val adminResponses = poolTestField<ComplaintAdminReadResponses>(adminHandler(web), "responses")
            assertSame(owner, poolTestField<ComplaintOwnerHistoryResponses>(detail, "responses"))
            assertSame(owner, poolTestField<ComplaintOwnerHistoryResponses>(adminResponses, "owner"))
            assertEquals(3, (first.process.consumers.adminReadPolicy as ComplaintAdminReadAdmissionPolicy.Bounded).perMinute)
            val image = readImage(first)
            val deliveries: List<(MockHttpServletResponse) -> Unit> = listOf(
                { response -> history.handleRequest(historyTestRequest(bearer), response) },
                { response -> detail.handleRequest(ownerDetailRequest(notice, bearer), response) },
                { response -> composition.ingressFilter.doFilter(adminReadStatsRequest(scope, admin), response, noFallthrough) },
            )
            // Keep the existing born-with two-ingress ceiling: one real held delivery plus seven actual
            // response permits. The ninth request has ingress capacity but no shared response slot.
            for (deliver in deliveries) {
                val held = List(3) { checkNotNull(owner.acquire()) } + List(4) { checkNotNull(adminResponses.acquire()) }
                OwnedCallerTestScope().use { callers ->
                    callers.beforeClose { held.forEach { it.close() } }
                    val gate = callers.gate()
                    val worker = callers.launch {
                        val response = object : MockHttpServletResponse() {
                            override fun getOutputStream(): ServletOutputStream {
                                requireConnectionFree(); assertEquals(0, web.admission.activeOwners())
                                gate.hold()
                                requireConnectionFree()
                                return super.getOutputStream()
                            }
                        }
                        deliver(response)
                        assertEquals(200, response.status)
                        true
                    }
                    gate.awaitEntered()
                    try {
                        assertEquals(1, web.ingressSnapshot().reservations); assertEquals(1, web.ingressSnapshot().contexts)
                        assertEquals(0, web.admission.activeOwners())
                        assertNull(owner.acquire()); assertNull(adminResponses.acquire())
                        val request = object : MockHttpServletRequest("POST", SEARCH) {
                            override fun getInputStream(): ServletInputStream = error("Exhausted shared response owner read search input.")
                        }.apply {
                            remoteAddr = "192.0.2.1"; contentType = "application/json"
                            addHeader("Authorization", "Bearer $admin")
                        }
                        val response = MockHttpServletResponse()
                        composition.ingressFilter.doFilter(request, response, noFallthrough)
                        assertEquals(503, response.status)
                        val ownerResponse = MockHttpServletResponse()
                        history.handleRequest(historyTestRequest(bearer), ownerResponse)
                        assertEquals(503, ownerResponse.status)
                        assertEquals(1, web.ingressSnapshot().reservations); assertEquals(1, web.ingressSnapshot().contexts)
                    } finally { gate.release(); held.forEach { it.close() } }
                    assertTrue(worker.value())
                }
                web.assertRequestsReleased()
                List(8) { checkNotNull(owner.acquire()) }.forEach { it.close() }
            }
            checked(search(web, admin), 200) // Successful held stats + search + detail exhaust one actor/scope quota.
            checked(web.get(detailPath(scope, notice), admin), 200)
            allReads(web, admin, scope, notice).forEach {
                problem(it, 429, "RATE_LIMITED")
                assertTrue(checkNotNull(header(it, "Retry-After")).toInt() in 1..60)
            }
            checked(web.get(ComplaintInstallationRoutes.HISTORY, bearer), 200) // Owner family is not the Admin rate key.
            val failure = assertThrows<IOException> {
                history.handleRequest(historyTestRequest(bearer), object : MockHttpServletResponse() {
                    override fun getOutputStream(): ServletOutputStream = throw IllegalStateException("Synthetic response writer failure.")
                })
            }
            assertEquals("Complaint response delivery failed.", failure.message)
            assertFalse(owner.isOpen()); assertFalse(adminResponses.isOpen())
            assertNull(owner.acquire()); assertNull(adminResponses.acquire())
            problem(search(web, admin), 503, "SERVICE_UNAVAILABLE")
            checked(web.get(ComplaintInstallationRoutes.HISTORY, bearer), 503)
            web.assertRequestsReleased()
            assertEquals(image, readImage(first))
        }
    }

    private fun withStartup(tls: VersionBoundPersistenceConnectedFixture, perMinute: Int = 60,
        createProfile: String = VersionBoundTestInitialCheckpointCreateV1.PROFILE,
        action: (TestActiveFirstCutFixtureV1, TestActiveOrdinaryRawFixtureV1, TestActiveInitialCheckpointRawFixtureV1, StartedHttpView) -> Unit) =
        TestRegisteredHttpStartupCasesV1.withPrepared(tls, globalScanBeforeActivation = true,
            initialCheckpointCreate = TestInitialCheckpointCreateInputV1(1, createProfile),
            adminRead = TestRegisteredAdminReadInputV1(1, TestRegisteredAdminReadInputV1.PROFILE, perMinute)) { first, ordinary, raw ->
            val startup = first.assembly.beginRegisteredAdminReadHttpStartup(first.registration)
            startup.use {
                startup.start()
                StartedHttpView(first, startup, BASE_PATHS + ADMIN_PATHS).use { web ->
                    action(first, ordinary, raw, web)
                    web.assertRequestsReleased()
                    startup.close()
                    web.assertDisposed(nativeStillActive = true)
                }
            }
            startup.requireCleanupProven()
        }

    /** Normal disposable bcrypt user rows are token/auth input only, never TEST namespace or phase authority. */
    private fun withUsers(web: StartedHttpView, roles: List<Role> = listOf(Role.ADMIN), action: (List<User>, JwtService) -> Unit) {
        val users = mutableListOf<User>()
        try {
            roles.forEach { role ->
                val id = UUID.randomUUID()
                val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
                val hash = web.context.getBean(PasswordEncoder::class.java).encode("synthetic-registered-admin-password")
                val user = User(id, "registered-admin-$id@example.invalid", hash, role, true, now, now)
                assertEquals(1, web.first.observer.update("INSERT INTO users (id,email,password_hash,role,enabled,created_at,updated_at) VALUES (?,?,?,?,true,?,?)",
                    id, user.email, hash, role.name, Timestamp.from(now), Timestamp.from(now)))
                users.add(user)
            }
            val keys = web.context.getBean(JwtKeyProvider::class.java)
            assertSame(web.first.process.consumers.jwt.boundUserKeyProvider, keys)
            action(users, JwtService(keys, web.context.getBean(KiraSecurityProperties::class.java), Clock.systemUTC()))
        } finally {
            web.assertRequestsReleased()
            users.forEach { assertEquals(1, web.first.observer.update("DELETE FROM users WHERE id = ?", it.id)) }
        }
    }

    private fun enroll(web: StartedHttpView, actor: ScopedInstallationId): String {
        val body = mapper.writeValueAsBytes(mapOf("installationId" to actor.id.toString(), "expectedDataScopeId" to actor.scope.id.toString(),
            "platform" to "ANDROID", "secret" to Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })))
        val enrolled = web.post(ComplaintInstallationRoutes.ENROLLMENT, body)
        checked(enrolled, 201)
        val token = checkNotNull(json(enrolled)["accessToken"].textValue())
        assertEquals(actor, InstallationJwtCodec(web.first.process.consumers.jwt.installationKeyRing, Clock.systemUTC()).verify(token).installation)
        web.assertRequestsReleased()
        return token
    }

    private fun search(web: StartedHttpView, bearer: String?, scope: ComplaintDataScope = web.first.process.desiredSettings().scope,
        limit: Int = 50, cursor: String? = null): HttpResponse<ByteArray> = web.post(SEARCH, mapper.writeValueAsBytes(
        linkedMapOf<String, Any>("dataScopeId" to scope.id.toString(), "limit" to limit).also { if (cursor != null) it["cursor"] = cursor }), bearer)
    private fun allReads(web: StartedHttpView, bearer: String?, scope: ComplaintDataScope, id: UUID) = listOf(
        search(web, bearer, scope), web.get(detailPath(scope, id), bearer), web.get(statsPath(scope), bearer))
    private fun detailPath(scope: ComplaintDataScope, id: UUID) = "$ADMIN/$id?dataScopeId=${scope.id}"
    private fun statsPath(scope: ComplaintDataScope) = "$STATS?dataScopeId=${scope.id}"
    private fun noticeId(web: StartedHttpView, bearer: String) = UUID.fromString(json(search(web, bearer).also { checked(it, 200) })["items"]
        .first { it["kind"].textValue() == "NOTICE" }["id"].textValue())
    private fun composition(web: StartedHttpView) = web.context.getBean(ComplaintTestBootstrapHttpCompositionV1::class.java)
    private fun adminHandler(web: StartedHttpView): ComplaintAdminReadHttpHandler =
        poolTestField(poolTestField<ComplaintTestRegisteredAdminReadsV1>(composition(web), "adminReads"), "handler")
    private fun guardedReader(web: StartedHttpView): ComplaintAdminReadPort =
        poolTestField(poolTestField<ComplaintAdminReadService>(adminHandler(web), "service"), "reader")
    private fun ownerDetailRequest(id: UUID, bearer: String) = MockHttpServletRequest("GET", "${ComplaintInstallationRoutes.HISTORY}/$id").apply {
        remoteAddr = "192.0.2.1"; addHeader("Authorization", "Bearer $bearer")
    }
    private fun closedBeforeBody(composition: ComplaintTestBootstrapHttpCompositionV1, method: String, path: String) {
        val request = object : MockHttpServletRequest(method, path) {
            override fun getInputStream(): ServletInputStream = error("Excluded route read input.")
            override fun getHeader(name: String): String? = error("Excluded route read bearer/header.")
        }
        val response = MockHttpServletResponse()
        composition.ingressFilter.doFilter(request, response, noFallthrough)
        assertEquals(404, response.status)
    }
    private fun readImage(first: TestActiveFirstCutFixtureV1): Map<String, List<String>> = first.p.image() +
        listOf("app_installations", "complaint_installation_ids", "complaint_idempotency_receipts").associateWith { table ->
            first.observer.queryForList("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM $table t WHERE data_scope_id = ? ORDER BY to_jsonb(t)::text", String::class.java, first.scope)
        }
    private fun providerCounts(first: TestActiveFirstCutFixtureV1, ordinary: TestActiveOrdinaryRawFixtureV1, raw: TestActiveInitialCheckpointRawFixtureV1) =
        listOf(first.native.sts.requests.size, first.native.kms.requests.size, first.native.requests.size, ordinary.requestBudgets.size,
            raw.sts.requests.size, raw.kms.requests.size, raw.requests.size, first.p.f.http.read.requests.size)
    private fun nonzeroBuckets(value: JsonNode, field: String, key: String) = value[field].filter { it["count"].longValue() != 0L }
        .associate { it[key].textValue() to it["count"].longValue() }
    private fun json(response: HttpResponse<ByteArray>): JsonNode = mapper.readTree(response.body())
    private fun header(response: HttpResponse<ByteArray>, name: String): String? = response.headers().firstValue(name).orElse(null)
    private fun checked(response: HttpResponse<ByteArray>, expected: Int) {
        assertEquals(expected, response.statusCode())
        assertEquals("1", header(response, "X-Kira-Complaint-Contract"))
        assertEquals("no-store, no-transform", header(response, "Cache-Control"))
        assertNull(header(response, "Set-Cookie")); assertNull(header(response, "Content-Encoding"))
        assertTrue(response.body().size in 1..2 * 1024 * 1024)
    }
    private fun problem(response: HttpResponse<ByteArray>, expected: Int, code: String) {
        checked(response, expected)
        assertEquals(code, json(response)["errors"][0]["code"].textValue())
        assertNull(header(response, "ETag"))
    }
    private val mapper = ObjectMapper()
    private const val ADMIN = "/api/v1/admin/complaints"
    private const val SEARCH = "$ADMIN/search"
    private const val STATS = "$ADMIN/stats"
    private val ADMIN_PATHS = setOf(SEARCH, STATS, "$ADMIN/{id}")
    private val BASE_PATHS = setOf(ComplaintInstallationRoutes.BOOTSTRAP, ComplaintInstallationRoutes.ENROLLMENT,
        ComplaintInstallationRoutes.SESSION, ComplaintInstallationRoutes.HISTORY, ComplaintInstallationRoutes.STATUS, "${ComplaintInstallationRoutes.HISTORY}/{id}")
    private val noFallthrough = FilterChain { _, _ -> error("Selected or excluded Admin route escaped the pre-buffer boundary.") }
}
