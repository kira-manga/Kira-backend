package me.manga.kira.backend.complaint.infrastructure.reconciliation

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.http.HttpServletRequestWrapper
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.infrastructure.persistence.AdminStatusAttempt
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryResponses
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusOperation.ADMIN_CLOSURE
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusOperation.ADMIN_STATUS
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.domain.ComplaintTextRules
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentFailureV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestRegisteredAdminStatusInputV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.ADMIN
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.CONSUMED
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.CONSUMED_ID
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.ISSUED_ID
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.mapper
import me.manga.kira.backend.config.ComplaintTestBootstrapHttpCompositionV1
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import me.manga.kira.backend.security.adminStatusTestRequest
import me.manga.kira.backend.support.JwtTestSupport
import me.manga.kira.backend.user.domain.Role
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.io.IOException
import java.net.http.HttpResponse
import java.util.UUID

/** Registered wiring only: reuse actual content startup, password issuance and request/response owners, not a second harness. */
internal object TestRegisteredAdminStatusHttpCasesV1 {
    fun transitionsAndAccounting(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredAdminContentHttpV1(tls, adminStatus = statusInput()) { f ->
        f.withCurrent {
            val id = f.report(); val immutable = immutable(f, id); val providers = f.providerCounts()
            val initialProof = f.proof(); val beforeTags = f.image()
            for (operation in listOf(ADMIN_STATUS, ADMIN_CLOSURE)) {
                val attempt = AdminStatusAttempt(id, operation)
                f.problem(change(f, attempt, initialProof.token, tag = null), 428, "PRECONDITION_REQUIRED")
                f.problem(change(f, attempt, initialProof.token, tag = "W/${etag(attempt)}"), 412, "PRECONDITION_FAILED")
            }
            assertEquals(beforeTags, f.image()); f.unused(initialProof.id)
            applied(f, AdminStatusAttempt(id), proof = initialProof, bearer = f.signer.issue(f.users[0].copy(role = Role.USER)).value)
            val closed = AdminStatusAttempt(id, ADMIN_CLOSURE, version = 2, reason = "  Resolved\r\nreason  ")
            applied(f, closed)
            assertEquals("Resolved\nreason", f.json(f.detail(id))["closureReason"].textValue())
            val firstClosure = closure(f, id)
            val corrected = closed.copy(key = UUID.randomUUID(), version = 3, reason = "  Corrected\r\nreason  ")
            applied(f, corrected, actor = 1)
            assertNotEquals(firstClosure, closure(f, id))
            assertEquals(f.users[1].id, f.observer.queryForObject("SELECT closure_actor_id FROM complaints WHERE data_scope_id = ? AND id = ?", UUID::class.java, f.scope.id, id))

            // The same normalized closure cannot steal the other ADMIN's real actor/time or advance the version.
            val noChange = corrected.copy(key = UUID.randomUUID(), version = 4, reason = "Corrected\nreason")
            val proof = f.proof(); val before = f.image(); val counters = f.first.counters(); val tuple = closure(f, id)
            val rejected = change(f, noChange, proof.token)
            f.problem(rejected, 409, "COMPLAINT_NO_CHANGE", proof.id); f.used(proof.id)
            assertRejectedReceipt(f, noChange, proof.id, 409, "COMPLAINT_NO_CHANGE")
            f.assertCharge(counters, ComplaintCapacityCharges.NORMAL_RECEIPT, ComplaintCapacityCharges.ADMIN_STATUS)
            assertEquals(before.getValue("complaints"), f.image().getValue("complaints"))
            assertEquals(before.getValue("audits"), f.image().getValue("audits")); assertEquals(tuple, closure(f, id))
            val fresh = f.proof(); val unchanged = f.image(); val freshRow = f.grantRow(fresh.id)
            val replay = change(f, noChange, fresh.token)
            f.problem(replay, 409, "COMPLAINT_NO_CHANGE", proof.id); assertArrayEquals(rejected.body(), replay.body())
            assertEquals(unchanged, f.image()); assertEquals(freshRow, f.grantRow(fresh.id)); f.unused(fresh.id)

            applied(f, AdminStatusAttempt(id, version = 4, status = ComplaintStatus.OPEN), proof = fresh)
            assertEquals(immutable, immutable(f, id))
            assertEquals("[null, null, null, null]", closure(f, id))
            // A well-formed stale strong tag is a terminal business rejection on either operation, unlike the header refusals above.
            for (operation in listOf(ADMIN_STATUS, ADMIN_CLOSURE)) {
                val stale = AdminStatusAttempt(id, operation, version = 4)
                val staleProof = f.proof(); val staleBefore = f.image(); val staleCounters = f.first.counters()
                val staleResponse = change(f, stale, staleProof.token)
                f.problem(staleResponse, 412, "PRECONDITION_FAILED", staleProof.id); f.used(staleProof.id)
                assertRejectedReceipt(f, stale, staleProof.id, 412, "PRECONDITION_FAILED")
                f.assertCharge(staleCounters, ComplaintCapacityCharges.NORMAL_RECEIPT, ComplaintCapacityCharges.ADMIN_STATUS)
                assertEquals(staleBefore.getValue("complaints"), f.image().getValue("complaints"))
                assertEquals(staleBefore.getValue("audits"), f.image().getValue("audits"))
                val next = f.proof(); val nextRow = f.grantRow(next.id); val ended = f.image()
                assertTrue(f.grantRow(staleProof.id).isEmpty())
                for (supplied in listOf(null, next.token)) {
                    val replayed = change(f, stale, supplied)
                    f.problem(replayed, 412, "PRECONDITION_FAILED", staleProof.id); assertArrayEquals(staleResponse.body(), replayed.body())
                    assertEquals(ended, f.image()); assertEquals(nextRow, f.grantRow(next.id)); f.unused(next.id)
                }
            }
            assertEquals(4L, f.observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? " +
                "AND entity_id = ? AND action IN ('COMPLAINT_STATUS_CHANGED','COMPLAINT_CLOSED')", Long::class.java, f.scope.id, id.toString()))
            assertEquals(7L, f.observer.queryForObject("SELECT count(*) FROM complaint_idempotency_receipts WHERE data_scope_id = ? " +
                "AND operation IN ('ADMIN_STATUS','ADMIN_CLOSURE') AND state = 'COMPLETED'", Long::class.java, f.scope.id))
            assertEquals(providers, f.providerCounts()); f.web.assertRequestsReleased()
        }
    }

    fun receiptsAndCurrentState(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredAdminContentHttpV1(tls, adminStatus = statusInput()) { f ->
        val beforeCheckpoint = f.image(); val beforeProviders = f.providerCounts()
        for (operation in listOf(ADMIN_STATUS, ADMIN_CLOSURE)) {
            f.problem(change(f, AdminStatusAttempt(UUID.randomUUID(), operation)), 503, "SERVICE_UNAVAILABLE")
        }
        assertEquals(beforeCheckpoint, f.image()); assertEquals(beforeProviders, f.providerCounts())
        f.withCurrent {
            val id = f.report()
            val status = applied(f, AdminStatusAttempt(id))
            val close = applied(f, AdminStatusAttempt(id, ADMIN_CLOSURE, version = 2, reason = " Historical\r\nclosure "))
            val edit = RegisteredAdminContentAttemptV1(id, version = 3)
            val editedProof = f.proof(); f.acknowledged(f.edit(edit, editedProof.token), edit, editedProof.id)
            // Actor is part of the receipt namespace: another real ADMIN may use the same key for its own new operation.
            applied(f, AdminStatusAttempt(id, key = status.attempt.key, version = 4, status = ComplaintStatus.RESOLVED), actor = 1)
            val fresh = f.proof(); val other = f.proof(index = 1)
            val freshRow = f.grantRow(fresh.id); val otherRow = f.grantRow(other.id)
            assertTrue(f.grantRow(status.grant).isEmpty()); assertTrue(f.grantRow(close.grant).isEmpty())
            val before = f.image()
            for (original in listOf(status, close)) for (supplied in listOf(null, fresh.token)) replay(f, original, supplied)
            replay(f, close, fresh.token, close.attempt.copy(reason = "Historical\nclosure"))
            // Exact operation/fingerprint/target binding precedes proof and target/current-state work.
            for (attempt in listOf(
                status.attempt.copy(operation = ADMIN_CLOSURE), close.attempt.copy(operation = ADMIN_STATUS),
                status.attempt.copy(status = ComplaintStatus.PLANNED), close.attempt.copy(reason = "Different closure"),
                status.attempt.copy(id = UUID.randomUUID()), close.attempt.copy(id = UUID.randomUUID()),
                status.attempt.copy(version = 5), close.attempt.copy(version = 5),
                status.attempt.copy(key = edit.key), close.attempt.copy(key = edit.key),
            )) f.problem(change(f, attempt, fresh.token), 409, "IDEMPOTENCY_KEY_REUSED")
            for (key in listOf(status.attempt.key, close.attempt.key)) {
                f.problem(f.edit(edit.copy(key = key), fresh.token), 409, "IDEMPOTENCY_KEY_REUSED")
            }
            for (original in listOf(status, close)) {
                f.problem(change(f, original.attempt, fresh.token, scope = ComplaintDataScope.of(UUID.randomUUID())), 404, "NOT_FOUND")
                for (bad in listOf(null, JwtTestSupport.tamperSignature(f.admin()), f.ownerBearer)) {
                    f.problem(change(f, original.attempt, fresh.token, bearer = bad), 401, "UNAUTHORIZED")
                }
            }
            for (operation in listOf(ADMIN_STATUS, ADMIN_CLOSURE)) {
                for (supplied in listOf(null, other.token)) {
                    f.problem(change(f, AdminStatusAttempt(id, operation, version = 5), supplied), 401, "ADMIN_STEP_UP_REQUIRED")
                }
            }
            assertEquals(before, f.image()); assertEquals(freshRow, f.grantRow(fresh.id)); assertEquals(otherRow, f.grantRow(other.id))
            for ((sqlChange, restore, statusCode, code) in listOf(
                UserChange("role = 'USER'", "role = 'ADMIN'", 403, "FORBIDDEN"),
                UserChange("enabled = false", "enabled = true", 401, "UNAUTHORIZED"),
                UserChange("credential_version = 1", "credential_version = 0", 401, "UNAUTHORIZED"),
            )) {
                assertEquals(1, f.observer.update("UPDATE users SET $sqlChange WHERE id = ?", f.users[0].id))
                try {
                    for (original in listOf(status, close)) f.problem(change(f, original.attempt, fresh.token), statusCode, code)
                    assertEquals(before, f.image())
                } finally { assertEquals(1, f.observer.update("UPDATE users SET $restore WHERE id = ?", f.users[0].id)) }
            }

            val checkpoint = checkNotNull(f.observer.queryForObject("SELECT checkpoint_generation FROM complaint_journal_control WHERE data_scope_id = ?", Long::class.java, f.scope.id))
            assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET checkpoint_generation = checkpoint_generation + 1 WHERE data_scope_id = ?", f.scope.id))
            try { replayWithoutNewWork(f, listOf(status, close), fresh, 5) } finally {
                assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET checkpoint_generation = ? WHERE data_scope_id = ?", checkpoint, f.scope.id))
            }
            assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET maintenance_closed = true, creation_closed = true WHERE data_scope_id = ?", f.scope.id))
            replayWithoutNewWork(f, listOf(status, close), fresh, 5)
            val closed = f.image(); val providers = f.providerCounts()
            f.first.registration.close()
            for (original in listOf(status, close)) f.problem(change(f, original.attempt, fresh.token), 503, "SERVICE_UNAVAILABLE")
            f.problem(f.issue(), 503, "SERVICE_UNAVAILABLE")
            assertEquals(closed, f.image()); assertEquals(providers, f.providerCounts()); f.unused(fresh.id); f.unused(other.id)
            f.web.assertRequestsReleased()
        }
    }

    fun quotaBodiesResponsesAndCleanup(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredAdminContentHttpV1(tls,
        perHour = 1, adminStatus = statusInput(2)) { f ->
        f.withCurrent {
            val id = f.report(); val status = applied(f, AdminStatusAttempt(id))
            val close = applied(f, AdminStatusAttempt(id, ADMIN_CLOSURE, version = 2))
            val fresh = f.proof(); val beforeQuota = f.image()
            for (operation in listOf(ADMIN_STATUS, ADMIN_CLOSURE)) {
                f.problem(change(f, AdminStatusAttempt(id, operation, version = 3), fresh.token), 429, "RATE_LIMITED")
            }
            assertEquals(beforeQuota, f.image()); f.unused(fresh.id)
            val edit = RegisteredAdminContentAttemptV1(id, version = 3)
            f.acknowledged(f.edit(edit, fresh.token), edit, fresh.id) // Separate content quota, same original ingress/issuer.
            val unused = f.proof(); val image = f.image(); val unusedRow = f.grantRow(unused.id)
            for (original in listOf(status, close)) {
                replay(f, original, unused.token)
                val exact = bytes(original.attempt).let { it + ByteArray(16_384 - it.size) { 32 } }
                acknowledge(f, original.attempt, f.web.patchAdminContent(path(f, original.attempt), exact, f.admin(),
                    original.attempt.key, etag(original.attempt), unused.token), original.grant)
            }
            for ((attempt, invalid) in listOf(
                status.attempt to "{\"status\":\"CLOSED\"}",
                status.attempt to "{\"status\":\"OPEN\",\"status\":\"PLANNED\"}",
                status.attempt to "{\"status\":\"PLANNED\",\"reason\":\"smuggled\"}",
                close.attempt to "{\"reason\":\"valid\",\"actorId\":\"caller-selected\"}",
                close.attempt to "{\"reason\":\"valid\"}{}",
                close.attempt to "{\"reason\":\" \"}",
                close.attempt to mapper.writeValueAsString(mapOf("reason" to "🙂".repeat(501))),
            )) {
                f.problem(f.web.patchAdminContent(path(f, attempt), invalid.toByteArray(), f.admin(), attempt.key, etag(attempt), unused.token), 400, "VALIDATION_FAILED")
            }
            for (original in listOf(status, close)) {
                val template = request(f, original.attempt).apply { removeHeader("Content-Length"); addHeader("Content-Length", "16385") }
                val denied = f.dispatch(object : HttpServletRequestWrapper(template) {
                    override fun getInputStream(): ServletInputStream = error("Declared oversize status/closure body was acquired.")
                })
                assertEquals(413, denied.status); assertNull(denied.getHeader(CONSUMED)); assertNull(denied.getHeader(CONSUMED_ID))
            }
            val raw = bytes(close.attempt); var read = 0; var owned: ByteArray? = null
            val chunked = request(f, close.attempt).apply { removeHeader("Content-Length"); addHeader("Transfer-Encoding", "chunked") }
            val denied = f.dispatch(object : HttpServletRequestWrapper(chunked) {
                override fun getContentLengthLong(): Long = -1
                override fun getContentLength(): Int = -1
                override fun getInputStream(): ServletInputStream = object : ServletInputStream() {
                    override fun isFinished(): Boolean = read == 20_000
                    override fun isReady(): Boolean = true
                    override fun setReadListener(listener: ReadListener) = Unit
                    override fun read(): Int = if (read == 20_000) -1 else { val at = read++; if (at < raw.size) raw[at].toInt() and 255 else 32 }
                    override fun readNBytes(length: Int): ByteArray {
                        assertEquals(16_385, length)
                        return super.readNBytes(length).also { owned = it }
                    }
                }
            })
            assertEquals(413, denied.status); assertEquals(16_385, read); assertTrue(checkNotNull(owned).all { it == 0.toByte() })
            assertEquals(image, f.image()); assertEquals(unusedRow, f.grantRow(unused.id)); f.unused(unused.id)

            val reads = poolTestField<Any>(f.composition, "reads")
            val history = poolTestField<ComplaintOwnerHistoryHttpHandler>(reads, "history")
            val owner = poolTestField<ComplaintOwnerHistoryResponses>(history, "responses")
            for (original in listOf(status, close)) {
                val held = List(7) { checkNotNull(owner.acquire()) }
                OwnedCallerTestScope().use { callers ->
                    callers.beforeClose { held.forEach { it.close() } }
                    val gate = callers.gate()
                    val worker = callers.launch {
                        val output = object : MockHttpServletResponse() {
                            override fun getOutputStream(): ServletOutputStream {
                                requireConnectionFree(); assertEquals(0, f.web.admission.activeOwners()); gate.hold(); requireConnectionFree()
                                return super.getOutputStream()
                            }
                        }
                        f.dispatch(request(f, original.attempt, unused.token), output)
                        assertEquals(200, output.status); assertEquals("true", output.getHeader(CONSUMED))
                        assertEquals(original.grant.toString(), output.getHeader(CONSUMED_ID))
                        true
                    }
                    gate.awaitEntered()
                    try {
                        assertEquals(1, f.web.ingressSnapshot().reservations); assertEquals(1, f.web.ingressSnapshot().contexts)
                        assertEquals(0, f.web.admission.activeOwners()); assertNull(owner.acquire())
                        for (selected in listOf(request(f, status.attempt), request(f, close.attempt), f.contentRequest(edit), f.stepUpRequest())) {
                            val refused = f.dispatch(object : HttpServletRequestWrapper(selected) {
                                override fun getInputStream(): ServletInputStream = error("Full original response owner acquired another body.")
                            })
                            assertEquals(503, refused.status); assertNull(refused.getHeader(CONSUMED))
                            assertNull(refused.getHeader(CONSUMED_ID)); assertNull(refused.getHeader(ISSUED_ID))
                        }
                        f.problem(f.web.get("$ADMIN/stats?dataScopeId=${f.scope.id}", f.admin()), 503, "SERVICE_UNAVAILABLE")
                        assertEquals(image, f.image())
                    } finally { gate.release(); held.forEach { it.close() } }
                    assertTrue(worker.value())
                }
                f.web.assertRequestsReleased(); List(8) { checkNotNull(owner.acquire()) }.forEach { it.close() }
            }
            val failed = object : MockHttpServletResponse() {
                override fun getOutputStream(): ServletOutputStream = throw IllegalStateException("Synthetic status delivery failure.")
            }
            assertEquals("Complaint response delivery failed.", assertThrows<IOException> { f.dispatch(request(f, close.attempt, unused.token), failed) }.message)
            assertEquals("true", failed.getHeader(CONSUMED)); assertEquals(close.grant.toString(), failed.getHeader(CONSUMED_ID))
            assertFalse(owner.isOpen()); assertNull(owner.acquire())
            for (original in listOf(status, close)) f.problem(change(f, original.attempt, unused.token), 503, "SERVICE_UNAVAILABLE")
            f.problem(f.edit(edit, unused.token), 503, "SERVICE_UNAVAILABLE"); f.problem(f.issue(), 503, "SERVICE_UNAVAILABLE")
            f.checked(f.web.get(ComplaintInstallationRoutes.HISTORY, f.ownerBearer), 503)
            assertEquals(image, f.image()); assertEquals(unusedRow, f.grantRow(unused.id)); f.unused(unused.id); f.web.assertRequestsReleased()
        }
    }

    fun oldContentSelectionAndOriginalPair(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredAdminContentHttpV1(tls,
        adminStatus = statusInput(), selectAdminStatus = false) { f ->
        assertTrue(f.composition.mappedPaths.intersect(setOf(ADMIN_STATUS.route, ADMIN_CLOSURE.route)).isEmpty())
        val before = f.image(); val providers = f.providerCounts()
        for (operation in listOf(ADMIN_STATUS, ADMIN_CLOSURE)) {
            val route = "$ADMIN/${UUID.randomUUID()}${operation.suffix}"
            f.web.refusedBeforeBody("PATCH", route, f.admin(), 404)
            val rejected = f.dispatch(object : MockHttpServletRequest("PATCH", route) {
                override fun getInputStream(): ServletInputStream = error("Unselected status route read its body.")
                override fun getHeader(name: String): String? = error("Unselected status route read its bearer/header.")
            })
            assertEquals(404, rejected.status)
        }
        assertEquals(before, f.image()); assertEquals(providers, f.providerCounts())
        f.withCurrent {
            val attempt = RegisteredAdminContentAttemptV1(f.report()); val proof = f.proof()
            f.acknowledged(f.edit(attempt, proof.token), attempt, proof.id)
        }
        val registration = f.first.registration; val assembly = f.first.assembly
        val ownership = f.web.context.getBean(PersistencePhaseOwnership::class.java)
        val jdbc = f.web.context.getBean(JdbcTemplate::class.java); val audit = f.web.context.getBean(AuditService::class.java)
        val decoder = f.web.context.getBean("jwtDecoder", JwtDecoder::class.java)
        val passwords = f.web.context.getBean(PasswordEncoder::class.java)
        val image = f.image()
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
            ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateAdminReadContentStatus(registration, assembly,
                ownership, JdbcTemplate(checkNotNull(jdbc.dataSource)), audit, f.web.startup, decoder, passwords)
        }
        ComplaintTestProcessAssemblyV1.begin().use { foreign ->
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
                ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateAdminReadContentStatus(registration, foreign,
                    ownership, jdbc, audit, f.web.startup, decoder, passwords)
            }
        }
        assertEquals(ComplaintTestDeploymentFailureV1.PROCESS_REFUSED, assertThrows<ComplaintTestDeploymentExceptionV1> {
            ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateAdminReadContentStatus(registration, assembly,
                ownership, jdbc, audit, f.web.startup, decoder, passwords)
        }.code) // Exact pair cannot convert an already refreshed old selector into the new cohort.
        registration.requireIdentityAdmissionPhaseResources(ownership, jdbc)
        assertEquals(image, f.image()); f.web.assertRequestsReleased()
    }

    private fun applied(f: TestRegisteredAdminContentHttpFixtureV1, attempt: AdminStatusAttempt, actor: Int = 0,
        proof: RegisteredAdminIssuedProofV1 = f.proof(actor), bearer: String = f.admin(actor)): ObservedStatus {
        val before = f.image(); val counters = f.first.counters(); val immutable = immutable(f, attempt.id)
        val from = checkNotNull(f.observer.queryForObject("SELECT status FROM complaints WHERE data_scope_id = ? AND id = ?", String::class.java, f.scope.id, attempt.id))
        val to = if (attempt.operation == ADMIN_CLOSURE) "CLOSED" else attempt.status.name
        val response = change(f, attempt, proof.token, bearer)
        acknowledge(f, attempt, response, proof.id); f.used(proof.id); f.assertCharge(counters, ComplaintCapacityCharges.ADMIN_STATUS)
        assertEquals(immutable, immutable(f, attempt.id))
        val predicate = if (attempt.operation == ADMIN_CLOSURE) {
            "closure_reason = ? AND closure_provenance = 'ADMIN' AND closure_actor_id = ? AND closed_at = updated_at"
        } else "closure_reason IS NULL AND closure_provenance IS NULL AND closure_actor_id IS NULL AND closed_at IS NULL"
        val arguments = if (attempt.operation == ADMIN_CLOSURE) arrayOf(to, attempt.version + 1,
            ComplaintTextRules.closureReason(attempt.reason), f.users[actor].id, f.scope.id, attempt.id)
            else arrayOf(to, attempt.version + 1, f.scope.id, attempt.id)
        assertEquals(true, f.observer.queryForObject("SELECT status = ? AND version = ? AND $predicate FROM complaints WHERE data_scope_id = ? AND id = ?",
            Boolean::class.java, *arguments))
        assertEquals(true, f.observer.queryForObject(
            "SELECT count(*) = 1 AND bool_and(a.complaint_actor_kind = 'ADMIN' AND a.actor_user_id = ? AND a.created_at = c.updated_at " +
                "AND a.detail = jsonb_build_object('version', ?::bigint, 'fromStatus', ?::text, 'toStatus', ?::text)) " +
                "FROM audit_log a JOIN complaints c ON c.id::text = a.entity_id AND c.data_scope_id = a.complaint_data_scope_id " +
                "WHERE a.complaint_data_scope_id = ? AND c.id = ? AND a.action = ? AND a.detail ->> 'version' = ?",
            Boolean::class.java, f.users[actor].id, attempt.version + 1, from, to, f.scope.id, attempt.id,
            if (attempt.operation == ADMIN_CLOSURE) "COMPLAINT_CLOSED" else "COMPLAINT_STATUS_CHANGED", (attempt.version + 1).toString()))
        assertEquals(true, f.observer.queryForObject(
            "SELECT test_only AND operation = ? AND state = 'COMPLETED' AND outcome = 'APPLIED' AND response_status = 200 " +
                "AND target_ids = ARRAY[?::uuid] AND ack_ids = target_ids AND ack_versions = ARRAY[?::bigint] " +
                "AND consumed_grant_id = ? AND expires_at = completed_at + interval '192 hours' " +
                "AND publication_ref IS NULL AND external_event_id IS NULL FROM complaint_idempotency_receipts " +
                "WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ? AND data_scope_id = ?",
            Boolean::class.java, attempt.operation.name, attempt.id, attempt.version + 1, proof.id, f.users[actor].id, attempt.key, f.scope.id))
        val admin = f.detail(attempt.id); f.checked(admin, 200)
        val owner = f.web.get("${ComplaintInstallationRoutes.HISTORY}/${attempt.id}", f.ownerBearer); f.checked(owner, 200)
        for (detail in listOf(admin, owner)) {
            assertEquals(to, f.json(detail)["status"].textValue()); assertEquals(attempt.version + 1, f.json(detail)["version"].longValue())
            assertEquals(f.header(response, "ETag"), f.header(detail, "ETag"))
        }
        val after = f.image()
        for ((table, rows) in before.filterKeys { it !in setOf("complaints", "counters", "audits", "complaint_idempotency_receipts", "admin_step_up_grants") }) {
            assertEquals(rows, after.getValue(table), "Status/closure must not mutate $table.")
        }
        f.web.assertRequestsReleased()
        return ObservedStatus(attempt, response, proof.id)
    }

    private fun replayWithoutNewWork(f: TestRegisteredAdminContentHttpFixtureV1, originals: List<ObservedStatus>, proof: RegisteredAdminIssuedProofV1, version: Long) {
        val before = f.image(); val providers = f.providerCounts(); val grant = f.grantRow(proof.id)
        for (original in originals) {
            replay(f, original, proof.token)
            f.problem(change(f, original.attempt.copy(key = UUID.randomUUID(), version = version), proof.token), 503, "SERVICE_UNAVAILABLE")
        }
        f.problem(f.issue(), 503, "SERVICE_UNAVAILABLE")
        assertEquals(before, f.image()); assertEquals(providers, f.providerCounts()); assertEquals(grant, f.grantRow(proof.id)); f.unused(proof.id)
    }
    private fun replay(f: TestRegisteredAdminContentHttpFixtureV1, original: ObservedStatus, proof: String?, attempt: AdminStatusAttempt = original.attempt) {
        val replay = change(f, attempt, proof)
        acknowledge(f, original.attempt, replay, original.grant); assertArrayEquals(original.response.body(), replay.body())
    }
    private fun acknowledge(f: TestRegisteredAdminContentHttpFixtureV1, attempt: AdminStatusAttempt, response: HttpResponse<ByteArray>, grant: UUID) {
        f.checked(response, 200); f.association(response, grant)
        assertEquals(setOf("id", "version"), f.json(response).fieldNames().asSequence().toSet())
        assertEquals(attempt.id.toString(), f.json(response)["id"].textValue())
        assertTrue(f.json(response)["version"].isIntegralNumber); assertEquals(attempt.version + 1, f.json(response)["version"].longValue())
        assertEquals("\"complaint-${attempt.id}-v${attempt.version + 1}\"", f.header(response, "ETag"))
    }
    private fun assertRejectedReceipt(f: TestRegisteredAdminContentHttpFixtureV1, attempt: AdminStatusAttempt, grant: UUID, status: Int, code: String) =
        assertEquals(true, f.observer.queryForObject(
            "SELECT test_only AND operation = ? AND state = 'COMPLETED' AND outcome = 'REJECTED' AND response_status = ? " +
                "AND problem_code = ? AND consumed_grant_id = ? AND expires_at = completed_at + interval '192 hours' " +
                "AND target_ids = ARRAY[?::uuid] AND ack_ids IS NULL AND ack_versions IS NULL AND response_etag IS NULL " +
                "AND publication_ref IS NULL AND external_event_id IS NULL FROM complaint_idempotency_receipts WHERE actor_kind = 'ADMIN' AND actor_id = ? " +
                "AND idempotency_key = ? AND data_scope_id = ?",
            Boolean::class.java, attempt.operation.name, status, code, grant, attempt.id, f.users[0].id, attempt.key, f.scope.id))

    private fun bytes(attempt: AdminStatusAttempt): ByteArray = mapper.writeValueAsBytes(
        if (attempt.operation == ADMIN_STATUS) mapOf("status" to attempt.status.name) else mapOf("reason" to attempt.reason))
    private fun etag(attempt: AdminStatusAttempt): String = "\"complaint-${attempt.id}-v${attempt.version}\""
    private fun path(f: TestRegisteredAdminContentHttpFixtureV1, attempt: AdminStatusAttempt, scope: ComplaintDataScope = f.scope): String =
        "$ADMIN/${attempt.id}${attempt.operation.suffix}?dataScopeId=${scope.id}"
    private fun change(f: TestRegisteredAdminContentHttpFixtureV1, attempt: AdminStatusAttempt, proof: String? = null,
        bearer: String? = f.admin(), scope: ComplaintDataScope = f.scope, tag: String? = etag(attempt)): HttpResponse<ByteArray> =
        f.web.patchAdminContent(path(f, attempt, scope), bytes(attempt), bearer, attempt.key, tag, proof)
    private fun request(f: TestRegisteredAdminContentHttpFixtureV1, attempt: AdminStatusAttempt, proof: String? = null): MockHttpServletRequest =
        adminStatusTestRequest(attempt.operation, f.scope, attempt.id, attempt.key, attempt.version, f.admin(), proof, bytes(attempt).decodeToString())
    private fun immutable(f: TestRegisteredAdminContentHttpFixtureV1, id: UUID): String = checkNotNull(f.observer.queryForObject(
        "SELECT (to_jsonb(c) - ARRAY['status','closure_reason','closure_provenance','closure_actor_id','closed_at','updated_at','version'])::text " +
            "FROM complaints c WHERE data_scope_id = ? AND id = ?", String::class.java, f.scope.id, id))
    private fun closure(f: TestRegisteredAdminContentHttpFixtureV1, id: UUID): String = checkNotNull(f.observer.queryForObject(
        "SELECT jsonb_build_array(closure_reason,closure_provenance,closure_actor_id,closed_at)::text FROM complaints WHERE data_scope_id = ? AND id = ?",
        String::class.java, f.scope.id, id))
    private fun statusInput(perHour: Int = 60) = TestRegisteredAdminStatusInputV1(1, TestRegisteredAdminStatusInputV1.PROFILE, perHour)
    private class ObservedStatus(val attempt: AdminStatusAttempt, val response: HttpResponse<ByteArray>, val grant: UUID)
    private data class UserChange(val change: String, val restore: String, val status: Int, val code: String)
}
