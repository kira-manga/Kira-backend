package me.manga.kira.backend.complaint.infrastructure.reconciliation

import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletRequest
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestGate
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryResponses
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditFingerprint
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminJwtIdentityDecoder
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminReadStore
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.ADMIN
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.mapper
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredCompleteHttpFixtureV1.Companion.PREVIEW
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredCompleteHttpFixtureV1.Companion.authorization
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredCompleteHttpFixtureV1.Companion.promise
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredOwnerDeleteAllHttpFixtureV1.Companion.SECRET
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredOwnerDeleteAllHttpFixtureV1.Companion.assertCharge
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredOwnerDeleteAllHttpFixtureV1.Companion.checked
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import me.manga.kira.backend.user.domain.Role
import org.apache.catalina.LifecycleState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.core.MethodParameter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.AsyncHandlerMethodReturnValueHandler
import org.springframework.web.method.support.HandlerMethodReturnValueHandler
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Focused wiring/ownership scenarios; lower parser/max50 and owner DELETE/ALL suites are carried, not copied. */
internal object TestRegisteredCompleteHttpCasesV1 {
    fun sharedLoginSourceAndBoundComplaint(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredCompleteHttp(tls) { f ->
        val a = f.admin
        a.withCurrent {
            val bearer = f.login()
            val me = f.web.get("/api/v1/auth/me", bearer); f.ordinary(me, 200)
            assertEquals(a.users[0].id.toString(), a.json(me)["id"].textValue())
            assertEquals(a.users[0].email, a.json(me)["email"].textValue()); assertEquals("ADMIN", a.json(me)["role"].textValue())
            f.assertPreview(f.preview(bearer)); f.assertReleased()
            val attempt = RegisteredAdminContentAttemptV1(a.report())
            val uncreatedApi = "AbsentShared${UUID.randomUUID().toString().replace("-", "")}"
            assertEquals(0L, f.jdbc.queryForObject("SELECT count(*) FROM source_configs WHERE api = ?", Long::class.java, uncreatedApi))
            val sourceState = f.sourceImage(); val beforeSource = f.first.counters()
            val source = f.sourceProof(bearer); val explicitSource = f.sourceProof(bearer, explicitScope = true)
            assertEquals(beforeSource, f.first.counters(), "SOURCE issuance must not charge complaint capacity.")
            val complaint = a.proof(bearer = bearer)
            a.assertCharge(beforeSource, ComplaintCapacityCharges.MODERATION_GRANT)
            val beforeDenials = f.image(); val counters = f.counters(); val native = f.raw.counts()

            // Both families are genuine HTTP credentials. Neither can become the other family's principal.
            f.ordinaryProblem(f.web.get("/api/v1/auth/me", a.ownerBearer), 401)
            f.ordinaryProblem(f.sourceIssue(a.ownerBearer), 401)
            a.problem(a.edit(attempt, complaint.token, a.ownerBearer), 401, "UNAUTHORIZED")
            a.problem(f.web.get(ComplaintInstallationRoutes.HISTORY, bearer), 401, "UNAUTHORIZED")
            a.problem(a.edit(attempt, source.token, bearer), 401, "ADMIN_STEP_UP_REQUIRED")
            f.ordinaryProblem(f.sourceMode(uncreatedApi, bearer, complaint.token), 401, "ADMIN_STEP_UP_REQUIRED")
            f.assertReleased()
            assertEquals(beforeDenials, f.image()); assertEquals(counters, f.counters()); assertEquals(native, f.raw.counts())
            a.unused(source.id); a.unused(explicitSource.id); a.unused(complaint.id)

            // The real HTTP controller commits SOURCE proof consumption before the original source lookup.
            // A real missing-source 404 is deliberate: no fabricated COMPLETE/source history or publication claim.
            f.ordinaryProblem(f.sourceMode(uncreatedApi, bearer, source.token), 404, "SOURCE_NOT_FOUND")
            a.used(source.id); val consumed = f.image(); val originalSourceGrant = a.grantRow(source.id)
            f.ordinaryProblem(f.sourceMode(uncreatedApi, bearer, source.token), 401, "ADMIN_STEP_UP_REQUIRED")
            assertEquals(consumed, f.image()); assertEquals(originalSourceGrant, a.grantRow(source.id))
            assertEquals(sourceState, f.sourceImage()); assertEquals(counters, f.counters()); assertEquals(native, f.raw.counts())
            a.unused(complaint.id); a.unused(explicitSource.id); f.assertReleased()

            val immutable = a.immutable(attempt.id); val beforeEdit = f.first.counters()
            val edited = a.edit(attempt, complaint.token, bearer)
            a.acknowledged(edited, attempt, complaint.id); a.used(complaint.id)
            a.assertCharge(beforeEdit, ComplaintCapacityCharges.ADMIN_EDIT)
            assertEquals(immutable, a.immutable(attempt.id))
            val detail = a.detail(attempt.id, bearer); a.checked(detail, 200)
            assertEquals(2L, a.json(detail)["version"].longValue()); assertEquals("Registered Admin subject", a.json(detail)["subject"].textValue())
            assertEquals(true, f.jdbc.queryForObject("SELECT test_only AND operation = 'ADMIN_EDIT' AND state = 'COMPLETED' AND outcome = 'APPLIED' " +
                "AND response_status = 200 AND target_ids = ARRAY[?::uuid] AND ack_ids = target_ids AND ack_versions = ARRAY[2::bigint] " +
                "AND consumed_grant_id = ? AND publication_ref IS NULL AND external_event_id IS NULL FROM complaint_idempotency_receipts " +
                "WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ? AND data_scope_id = ?",
                Boolean::class.java, attempt.id, complaint.id, a.users[0].id, attempt.key, f.scope))
            assertEquals(true, f.jdbc.queryForObject("SELECT count(*) = 1 AND bool_and(complaint_actor_kind = 'ADMIN' AND actor_user_id = ?) " +
                "FROM audit_log WHERE complaint_data_scope_id = ? AND entity_id = ? AND action = 'COMPLAINT_CONTENT_EDITED'",
                Boolean::class.java, a.users[0].id, f.scope, attempt.id.toString()))
            assertEquals(originalSourceGrant, a.grantRow(source.id)); a.unused(explicitSource.id)
            assertEquals(sourceState, f.sourceImage()); assertEquals(native, f.raw.counts()); assertNull(f.raw.publisher)
            f.assertReleased()
        }
    }

    fun sharedPreviewAsyncAndCompletionTailDrain(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredCompleteHttp(tls) { f ->
        val bearer = f.login(); val web = f.web
        val sourceState = f.sourceImage(); val image = f.image(); val counters = f.counters(); val native = f.raw.counts()
        val adapter = web.context.getBean(RequestMappingHandlerAdapter::class.java)
        val originalHandlers = checkNotNull(adapter.returnValueHandlers)
        try {
            OwnedCallerTestScope().use { callers ->
                val responseGate = callers.gate(); val completionGate = callers.gate()
                val hold = ActualPreviewAsyncHold(responseGate, completionGate)
                // Public MVC SPI only. Every original supports/handle call remains authoritative.
                adapter.setReturnValueHandlers(originalHandlers.map(hold::wrap))
                val preview = callers.launch { f.preview(bearer) }
                responseGate.awaitEntered()
                assertTrue(hold.listenerAdded.get())
                assertTrue(web.startup.ordinaryHttpObservation().accepting)
                assertEquals(1, web.startup.ordinaryHttpObservation().activeRequests)
                val releasing = callers.launch {
                    try {
                        awaitLifecycleFact(5_000) { !web.startup.ordinaryHttpObservation().accepting && web.ingressSnapshot().stopped }
                        assertOrdinaryHeld(f)
                        // The listener remains alive to refuse fresh intake, not to admit another ordinary owner.
                        f.ordinaryProblem(web.get("/api/v1/auth/me", bearer), 503)
                        assertOrdinaryHeld(f)
                        responseGate.release()
                        completionGate.awaitEntered()
                        assertOrdinaryHeld(f) // This late real onComplete callback is not terminal request destruction.
                    } finally {
                        responseGate.release(); completionGate.release()
                    }
                }
                web.startup.close() // Only the original assembly caller may close; no background close/reset.
                releasing.value()
                f.assertPreview(preview.value())
                hold.failure.get()?.let { throw it } // Servlet containers may otherwise absorb callback failures.
            }
        } finally {
            adapter.setReturnValueHandlers(originalHandlers) // Owned callers have exited before this restoration.
        }
        assertFalse(web.startup.ordinaryHttpObservation().accepting)
        assertEquals(0, web.startup.ordinaryHttpObservation().activeRequests)
        web.assertDisposed(nativeStillActive = true)
        assertEquals(sourceState, f.sourceImage()); assertEquals(image, f.image()); assertEquals(counters, f.counters())
        assertEquals(native, f.raw.counts()); assertNull(f.raw.publisher); f.assertReleased()
    }

    private fun assertOrdinaryHeld(f: TestRegisteredCompleteHttpFixtureV1) {
        val web = f.web; val observed = web.startup.ordinaryHttpObservation()
        assertFalse(observed.accepting); assertEquals(1, observed.activeRequests)
        val ingress = web.ingressSnapshot(); assertTrue(ingress.stopped)
        assertEquals(0, ingress.reservations); assertEquals(0, ingress.contexts)
        f.assertSqlReleased()
        assertTrue(web.context.isActive); assertTrue(web.emf.isOpen)
        assertEquals(LifecycleState.STARTED, web.server.tomcat.server.state)
        assertFalse(f.first.process.pools.shutdownRequested()); assertTrue(f.first.process.pools.ordinary.businessReady())
        assertFalse(poolTestField<Boolean>(web.startup, "cleanupProven"))
    }

    /** Two real servlet lifetime cuts, not a fake controller/result or a coroutine-cancellation harness. */
    private class ActualPreviewAsyncHold(private val response: OwnedCallerTestGate, private val completion: OwnedCallerTestGate) {
        val listenerAdded = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        private val dispatched = AtomicBoolean()
        private val completed = AtomicBoolean()
        private val tail = object : AsyncListener {
            override fun onComplete(event: AsyncEvent) = recordCallback {
                check(completed.compareAndSet(false, true))
                completion.hold()
            }
            override fun onTimeout(event: AsyncEvent) { failure.compareAndSet(null, AssertionError("Actual preview async request timed out.")) }
            override fun onError(event: AsyncEvent) { failure.compareAndSet(null, AssertionError("Actual preview async request failed.")) }
            override fun onStartAsync(event: AsyncEvent) = recordCallback { event.asyncContext.addListener(this) }
        }

        fun wrap(original: HandlerMethodReturnValueHandler): HandlerMethodReturnValueHandler =
            if (original is AsyncHandlerMethodReturnValueHandler) object : AsyncHandlerMethodReturnValueHandler by original {
                override fun handleReturnValue(value: Any?, type: MethodParameter, container: ModelAndViewContainer, request: NativeWebRequest) =
                    handle(original, value, type, container, request)
            } else object : HandlerMethodReturnValueHandler by original {
                override fun handleReturnValue(value: Any?, type: MethodParameter, container: ModelAndViewContainer, request: NativeWebRequest) =
                    handle(original, value, type, container, request)
            }

        private fun handle(original: HandlerMethodReturnValueHandler, value: Any?, type: MethodParameter, container: ModelAndViewContainer, request: NativeWebRequest) {
            val servlet = request.getNativeRequest(HttpServletRequest::class.java)
            if (servlet == null || servlet.requestURI != PREVIEW) return original.handleReturnValue(value, type, container, request)
            try {
                if (servlet.dispatcherType == DispatcherType.ASYNC) {
                    check(dispatched.compareAndSet(false, true))
                    response.hold() // Real redispatch follows the initial REQUEST chain's return.
                }
                original.handleReturnValue(value, type, container, request)
                if (servlet.dispatcherType == DispatcherType.REQUEST && servlet.isAsyncStarted) {
                    check(listenerAdded.compareAndSet(false, true))
                    servlet.asyncContext.addListener(tail) // Later than Spring's own listener; never complete/dispatch it ourselves.
                }
            } catch (problem: Throwable) {
                failure.compareAndSet(null, problem)
                throw problem
            }
        }

        private fun recordCallback(action: () -> Unit) {
            try { action() } catch (problem: Throwable) { failure.compareAndSet(null, problem) }
        }
    }

    fun separateBAndExactReceipt(tls: VersionBoundPersistenceConnectedFixture, batch: Boolean) = withRegisteredCompleteHttp(tls) { f ->
        val a = f.admin
        val bearers = listOf(a.ownerBearer) + if (batch) listOf(otherOwner(f)) else emptyList()
        a.withCurrent {
            val attempt = RegisteredAdminDeleteHttpAttemptV1(bearers.map { a.report(it) to 1L }.reversed(), batch)
            assertEquals(bearers.size.toLong(), f.jdbc.queryForObject("SELECT count(DISTINCT owner_id) FROM complaints " +
                "WHERE data_scope_id = ? AND ownership = 'INSTALLATION'", Long::class.java, f.scope))
            val original = a.proof(); val replacement = a.proof(); val freshRow = a.grantRow(replacement.id)
            val image = f.image(); val providers = f.raw.counts()
            if (!batch) {
                a.problem(a.issue(password = "incorrect-synthetic-password"), 401, "INVALID_STEP_UP_CREDENTIALS")
                a.problem(f.delete(attempt), 401, "ADMIN_STEP_UP_REQUIRED")
                a.problem(f.delete(attempt, original.token, a.ownerBearer), 401, "UNAUTHORIZED")
                assertEquals(image, f.image()); assertEquals(providers, f.raw.counts()); a.unused(original.id)
            }
            f.raw.expected = attempt; f.raw.grant = original.id
            val before = f.counters()
            // The signed diagnostic USER role does not replace the actual current database ADMIN role.
            val bearer = a.signer.issue(a.users[0].copy(role = Role.USER)).value
            a.problem(f.delete(attempt, original.token, bearer), 503, "SERVICE_UNAVAILABLE", original.id)
            f.assertReleased(); f.assertPending(attempt, original.id, verified = true)
            assertCharge(before, f.counters(), authorization(attempt), promise(attempt))
            val record = f.raw.record()
            assertEquals(bearers.size, record.event.adminComparison.ownerInstallationIds().size)
            assertTrue(f.raw.queue.order.isEmpty()); assertTrue(f.raw.queue.ackRequests.isEmpty())
            val pending = f.image(); val native = f.raw.counts()
            a.problem(f.delete(attempt, replacement.token), 503, "SERVICE_UNAVAILABLE", original.id)
            f.assertReleased(); assertEquals(pending, f.image()); assertEquals(native, f.raw.counts())
            assertEquals(freshRow, a.grantRow(replacement.id)); a.unused(replacement.id)

            f.completeThroughQueue(attempt, original.id, record) // Not called by any HTTP request or A continuation.
            val completed = f.image(); val paid = f.counters(); val afterB = f.raw.counts()
            val firstReplay = f.delete(attempt.copy(targets = attempt.targets.reversed()))
            receipt(f, firstReplay, attempt, original.id)
            val repeated = f.delete(attempt, replacement.token)
            receipt(f, repeated, attempt, original.id); assertArrayEquals(firstReplay.body(), repeated.body())
            a.problem(f.delete(attempt.copy(targets = attempt.targets.map { it.first to it.second + 1 }), replacement.token), 409, "IDEMPOTENCY_KEY_REUSED")
            a.problem(f.delete(attempt.copy(batch = !batch, targets = attempt.targets.take(1)), replacement.token), 409, "IDEMPOTENCY_KEY_REUSED")
            assertEquals(completed, f.image()); assertEquals(paid, f.counters()); assertEquals(afterB, f.raw.counts())
            assertEquals(freshRow, a.grantRow(replacement.id)); a.unused(replacement.id)
            // Negative current-principal change only; never restore it to manufacture a fresh successful replay.
            val stale = a.admin()
            assertEquals(1, f.jdbc.update("UPDATE users SET credential_version = credential_version + 1 WHERE id = ?", a.users[0].id))
            a.problem(f.delete(attempt, replacement.token, stale), 401, "UNAUTHORIZED")
            assertEquals(completed, f.image()); assertEquals(afterB, f.raw.counts()); a.unused(replacement.id)
            f.assertReleased()
        }
    }

    fun nativeFailureKeepsOriginalGrant(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredCompleteHttp(tls) { f ->
        f.admin.withCurrent {
            val attempt = RegisteredAdminDeleteHttpAttemptV1(listOf(f.admin.report() to 1L), batch = true)
            val proof = f.admin.proof()
            f.raw.expected = attempt; f.raw.grant = proof.id; f.raw.failReadback = true
            val before = f.counters()
            f.admin.problem(f.delete(attempt, proof.token), 503, "SERVICE_UNAVAILABLE", proof.id)
            f.assertReleased(); f.assertPending(attempt, proof.id, verified = false)
            assertCharge(before, f.counters(), authorization(attempt), promise(attempt))
            val p = checkNotNull(f.raw.publisher)
            assertEquals(1, p.requests.count { it.kind == "PUT" }); assertEquals(1, p.objects.size)
            assertTrue(p.requests.any { it.kind == "GET" }); assertEquals(1, p.generated()); assertEquals(0, p.decrypted())
            assertTrue(f.raw.queue.order.isEmpty()); assertTrue(f.raw.queue.ackRequests.isEmpty())
            // No retry, forged readback, aged proof or test-written receipt. Original lane/client release is required above.
        }
    }

    fun priorFamiliesShareTheCompleteListener(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredCompleteHttp(tls) { f ->
        val a = f.admin; val token = a.ownerBearer
        val session = f.web.post(ComplaintInstallationRoutes.SESSION, mapper.writeValueAsBytes(mapOf(
            "installationId" to a.owner.id.toString(), "expectedDataScopeId" to f.scope.toString(), "secret" to SECRET)))
        a.checked(session, 200); assertEquals(a.owner.id.toString(), a.json(session)["installationId"].textValue())
        val me = f.web.get(ComplaintInstallationRoutes.ME, token); a.checked(me, 200)
        assertEquals("""{"installationId":"${a.owner.id}","credentialVersion":1,"dataScopeId":"${f.scope}"}""", me.body().decodeToString())
        assertSharedResponseOwner(f)
        a.withCurrent {
            val providers = f.raw.counts()
            val report = a.report()
            val reply = registeredReplyAttempt(a.owner, report)
            val replied = f.web.post("${ComplaintInstallationRoutes.HISTORY}/$report/replies", mapper.writeValueAsBytes(mapOf(
                "id" to reply.input.id.toString(), "body" to reply.input.body,
                "metadata" to mapOf("appVersion" to null, "osVersion" to "fixture-os", "manufacturer" to "", "deviceModel" to ""))), token, reply.input.key)
            a.checked(replied, 201); assertEquals(reply.input.id.toString(), a.json(replied)["id"].textValue())
            val edit = registeredEditAttempt(a.owner, reply.input.id, subject = "Original registered report")
            val edited = f.web.patch(edit, token); a.checked(edited, 200); assertEquals(2L, a.json(edited)["version"].longValue())
            val status = f.web.post(ComplaintInstallationRoutes.STATUS, mapper.writeValueAsBytes(mapOf(
                "operation" to "OWNER_EDIT", "key" to edit.input.key.toString(), "targetIds" to listOf(edit.input.targetId.toString()),
                "fingerprint" to ComplaintOwnerEditFingerprint.of(edit.candidate.request).encoded)), token)
            a.checked(status, 200); assertEquals("APPLIED", a.json(status)["outcome"].textValue())
            assertEquals(a.json(edited), a.json(status)["body"])
            val content = RegisteredAdminContentAttemptV1(report)
            val contentProof = a.proof(); a.acknowledged(a.edit(content, contentProof.token), content, contentProof.id)
            changeStatus(f, report, 2, "status", mapOf("status" to "PLANNED"))
            changeStatus(f, report, 3, "closure", mapOf("reason" to "Coherent listener closure"))
            val proof = a.proof()
            val batch = f.web.post("$ADMIN/batch?dataScopeId=${f.scope}", mapper.writeValueAsBytes(mapOf("action" to "STATUS", "status" to "RESOLVED",
                "targets" to listOf(report to 4L, reply.input.id to 2L).map { mapOf("id" to it.first.toString(), "actionTag" to "\"complaint-${it.first}-v${it.second}\"") })),
                a.admin(), UUID.randomUUID(), proof.token)
            a.checked(batch, 200); a.association(batch, proof.id); a.used(proof.id)
            assertEquals(mapOf(report.toString() to 5L, reply.input.id.toString() to 3L), a.json(batch)["items"].associate { it["id"].textValue() to it["version"].longValue() })
            for ((id, version) in listOf(report to 5L, reply.input.id to 3L)) {
                for (response in listOf(a.detail(id), f.web.get("${ComplaintInstallationRoutes.HISTORY}/$id", token))) {
                    a.checked(response, 200); assertEquals("RESOLVED", a.json(response)["status"].textValue()); assertEquals(version, a.json(response)["version"].longValue())
                }
            }
            val history = f.web.get(ComplaintInstallationRoutes.HISTORY, token); a.checked(history, 200)
            assertEquals(setOf(report.toString(), reply.input.id.toString()), a.json(history)["items"].map { it["id"].textValue() }.toSet())
            val search = f.web.post("$ADMIN/search", mapper.writeValueAsBytes(mapOf("dataScopeId" to f.scope.toString(), "limit" to 50)), a.admin())
            a.checked(search, 200); assertTrue(a.json(search)["items"].map { it["id"].textValue() }.containsAll(listOf(report.toString(), reply.input.id.toString())))
            a.checked(f.web.get("$ADMIN/stats?dataScopeId=${f.scope}", a.admin()), 200)
            assertEquals(providers, f.raw.counts()); assertNull(f.raw.publisher); f.assertReleased()
        }
    }

    fun olderSelectorStaysNarrow(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredCompleteHttp(tls, oldStatusOnly = true) { f ->
        val a = f.admin
        val image = f.image(); val native = f.raw.counts()
        a.problem(f.delete(RegisteredAdminDeleteHttpAttemptV1(listOf(UUID.randomUUID() to 1L))), 404, "NOT_FOUND")
        a.problem(f.delete(RegisteredAdminDeleteHttpAttemptV1(listOf(UUID.randomUUID() to 1L), batch = true)), 400, "VALIDATION_FAILED")
        for ((method, path) in listOf("GET" to ComplaintInstallationRoutes.ME, "POST" to ComplaintInstallationRoutes.DELETE_ALL,
            "PATCH" to "${ComplaintInstallationRoutes.HISTORY}/${UUID.randomUUID()}/content",
            "POST" to "${ComplaintInstallationRoutes.HISTORY}/${UUID.randomUUID()}/replies")) {
            f.web.refusedBeforeBody(method, path, a.admin(), 404)
        }
        assertEquals(image, f.image()); assertEquals(native, f.raw.counts()); assertNull(f.raw.publisher)
        a.withCurrent {
            val beforeAuth = f.image(); val counters = f.counters(); val providers = f.raw.counts()
            val owner = f.web.context.getBean(PersistencePhaseOwnership::class.java)
            val jdbc = f.web.context.getBean(JdbcTemplate::class.java)
            assertSame(f.first.process.pools.ordinary, jdbc.dataSource)
            checkNotNull(f.first.process.initialCheckpointDeletion)
            val authentication = JdbcComplaintAdminReadStore(jdbc, a.scope)
            val ingress = f.first.process.consumers.ingressAdmission
            val bearer = a.admin()
            val decoder = ComplaintAdminJwtIdentityDecoder(a.scope, f.web.context.getBean("jwtDecoder", JwtDecoder::class.java),
                checkNotNull(f.first.process.consumers.jwt.boundUserKeyProvider).versionBoundClockSkew)
            ingress.withIngress(a.stepUpRequest(bearer)) { context ->
                ingress.requireLiveContext(context)
                val identity = decoder.decode(bearer)
                val phase = owner.enterComplaintAdminReadAuthentication()
                AutoCloseable { phase.finish() }.use {
                    phase.begin() // Real original AUTH entry must succeed; the shared unbound operation is what must refuse.
                    for (field in listOf("registeredAdminContent", "registeredAdminStatus", "registeredAdminBatchStatus", "registeredInitialDeletion")) {
                        assertNull(poolTestField<Any?>(phase, field))
                    }
                    assertThrows<PersistencePhaseException> { authentication.authenticateContentIdentity(identity) }
                    assertFalse(poolTestField<Boolean>(phase.adminRead, "issued"))
                    assertNull(poolTestField<Any?>(phase.adminRead, "retained"))
                    assertFalse(phase.adminRead.completed())
                }
                assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, phase.databaseOutcome())
                assertTrue(phase.failureException(PersistencePhaseFailureCode.WORK_FAILED).cleanupProven)
            }
            f.assertReleased()
            assertEquals(beforeAuth, f.image()); assertEquals(counters, f.counters()); assertEquals(providers, f.raw.counts())
            val id = a.report(); val proof = a.proof()
            val response = f.web.post("$ADMIN/batch?dataScopeId=${f.scope}", mapper.writeValueAsBytes(mapOf("action" to "STATUS", "status" to "RESOLVED",
                "targets" to listOf(mapOf("id" to id.toString(), "actionTag" to "\"complaint-$id-v1\"")))), a.admin(), UUID.randomUUID(), proof.token)
            a.checked(response, 200); a.association(response, proof.id); a.used(proof.id)
            assertEquals(2L, a.json(response)["items"].single()["version"].longValue())
        }
    }

    private fun receipt(f: TestRegisteredCompleteHttpFixtureV1, response: HttpResponse<ByteArray>, attempt: RegisteredAdminDeleteHttpAttemptV1, grant: UUID) {
        checked(response, if (attempt.batch) 200 else 204); f.admin.association(response, grant)
        assertNull(f.admin.header(response, "ETag")); assertNull(f.admin.header(response, "Location"))
        if (attempt.batch) assertEquals("{\"items\":[${attempt.ids.joinToString(",") { "{\"id\":\"$it\"}" }}]}", response.body().decodeToString()) else {
            assertNull(f.admin.header(response, "Content-Type")); assertNull(f.admin.header(response, "Content-Length"))
        }
    }
    private fun otherOwner(f: TestRegisteredCompleteHttpFixtureV1): String {
        val actor = f.first.initial.candidate().installation
        val response = f.web.post(ComplaintInstallationRoutes.ENROLLMENT, mapper.writeValueAsBytes(mapOf("installationId" to actor.id.toString(),
            "expectedDataScopeId" to f.scope.toString(), "platform" to "ANDROID", "secret" to SECRET)))
        f.admin.checked(response, 201); assertEquals(actor.id.toString(), f.admin.json(response)["installationId"].textValue())
        return f.admin.json(response)["accessToken"].textValue()
    }
    private fun changeStatus(f: TestRegisteredCompleteHttpFixtureV1, id: UUID, version: Long, suffix: String, body: Map<String, String>) {
        val proof = f.admin.proof()
        val response = f.web.patchAdminContent("$ADMIN/$id/$suffix?dataScopeId=${f.scope}", mapper.writeValueAsBytes(body), f.admin.admin(),
            UUID.randomUUID(), "\"complaint-$id-v$version\"", proof.token)
        f.admin.checked(response, 200); f.admin.association(response, proof.id); f.admin.used(proof.id)
        assertEquals(version + 1, f.admin.json(response)["version"].longValue())
    }
    private fun assertSharedResponseOwner(f: TestRegisteredCompleteHttpFixtureV1) {
        val reads = poolTestField<Any>(f.admin.composition, "reads")
        val owner = poolTestField<ComplaintOwnerHistoryResponses>(poolTestField<ComplaintOwnerHistoryHttpHandler>(reads, "history"), "responses")
        val content = poolTestField<Any>(f.admin.composition, "adminContent")
        for (name in listOf("contentHandler", "statusHandler", "batchStatusHandler", "deleteHandler")) {
            val responses = poolTestField<Any>(poolTestField<Any>(content, name), "responses")
            assertSame(owner, poolTestField<ComplaintOwnerHistoryResponses>(responses, "owner"))
        }
    }
}
