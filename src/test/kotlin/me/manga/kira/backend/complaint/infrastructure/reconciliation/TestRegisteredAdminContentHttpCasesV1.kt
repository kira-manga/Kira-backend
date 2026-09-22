package me.manga.kira.backend.complaint.infrastructure.reconciliation

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.http.HttpServletRequestWrapper
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryResponses
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentFailureV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.ADMIN
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.CONSUMED
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.CONSUMED_ID
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.ISSUED_ID
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.PASSWORD
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.PROOF_SCOPE
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.STEP_UP
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.mapper
import me.manga.kira.backend.config.ComplaintTestBootstrapHttpCompositionV1
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import me.manga.kira.backend.support.JwtTestSupport
import me.manga.kira.backend.user.domain.Role
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.io.IOException
import java.util.UUID

/** Genuine registered TEST graph only. No seeded grants, fabricated ACTIVE rows, authority or lower-layer matrix reruns. */
internal object TestRegisteredAdminContentHttpCasesV1 {
    fun passwordEditAccountingAndReplay(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredAdminContentHttpV1(tls) { f ->
        f.withCurrent {
            val id = f.report(); val attempt = RegisteredAdminContentAttemptV1(id)
            val providers = f.providerCounts(); val immutable = f.immutable(id)
            val beforeGrant = f.first.counters(); val issued = f.proof()
            f.assertCharge(beforeGrant, ComplaintCapacityCharges.MODERATION_GRANT)
            val beforeInvalidTag = f.image()
            f.problem(f.edit(attempt, issued.token, etag = null), 428, "PRECONDITION_REQUIRED")
            f.problem(f.edit(attempt, issued.token, etag = "W/${attempt.etag}"), 412, "PRECONDITION_FAILED")
            assertEquals(beforeInvalidTag, f.image()); f.unused(issued.id)
            val beforeEdit = f.first.counters(); val before = f.image()
            val edited = f.edit(attempt, issued.token)
            f.acknowledged(edited, attempt, issued.id); f.used(issued.id)
            f.assertCharge(beforeEdit, ComplaintCapacityCharges.ADMIN_EDIT)
            assertEquals(immutable, f.immutable(id))
            val detail = f.detail(id); f.checked(detail, 200)
            assertEquals(2L, f.json(detail)["version"].longValue())
            assertEquals("Registered Admin subject", f.json(detail)["subject"].textValue())
            assertEquals("Registered Admin body\nline", f.json(detail)["body"].textValue())
            assertEquals(f.header(edited, "ETag"), f.header(detail, "ETag"))
            val ownerDetail = f.web.get("${ComplaintInstallationRoutes.HISTORY}/$id", f.ownerBearer)
            f.checked(ownerDetail, 200); assertEquals(f.json(detail)["body"], f.json(ownerDetail)["body"])
            assertEquals(true, f.observer.queryForObject(
                "SELECT count(*) = 1 AND bool_and(complaint_actor_kind = 'ADMIN' AND actor_user_id = ? " +
                    "AND detail = jsonb_build_object('version', 2::bigint)) FROM audit_log " +
                    "WHERE complaint_data_scope_id = ? AND entity_id = ? AND action = 'COMPLAINT_CONTENT_EDITED'",
                Boolean::class.java, f.users[0].id, f.scope.id, id.toString()))
            assertEquals(true, f.observer.queryForObject(
                "SELECT test_only AND operation = 'ADMIN_EDIT' AND state = 'COMPLETED' AND outcome = 'APPLIED' " +
                    "AND response_status = 200 AND target_ids = ARRAY[?::uuid] AND ack_ids = target_ids AND ack_versions = ARRAY[2::bigint] " +
                    "AND consumed_grant_id = ? AND publication_ref IS NULL AND external_event_id IS NULL " +
                    "AND expires_at = completed_at + interval '192 hours' FROM complaint_idempotency_receipts " +
                    "WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ? AND data_scope_id = ?",
                Boolean::class.java, id, issued.id, f.users[0].id, attempt.key, f.scope.id))
            for (table in listOf("complaint_resource_ids", "complaint_test_runs", "complaint_journal_control", "catalog",
                "complaint_test_active_seal_intents", "complaint_test_active_checkpoint_history")) {
                assertEquals(before.getValue(table), f.image().getValue(table), "Content edit must not change $table.")
            }

            // B is genuinely password-issued. Its cleanup may retire used A, but A's receipt retains the exact association.
            val fresh = f.proof(); val freshRow = f.grantRow(fresh.id)
            assertTrue(f.grantRow(issued.id).isEmpty(), "Actual complaint grant cleanup must have removed used A before B's issuance.")
            val replayImage = f.image()
            for (proof in listOf(null, fresh.token)) {
                val replay = f.edit(attempt, proof)
                f.acknowledged(replay, attempt, issued.id); assertArrayEquals(edited.body(), replay.body())
                assertEquals(replayImage, f.image()); assertEquals(freshRow, f.grantRow(fresh.id)); f.unused(fresh.id)
            }
            f.problem(f.edit(attempt.copy(body = "Different normalized content"), fresh.token), 409, "IDEMPOTENCY_KEY_REUSED")
            assertEquals(replayImage, f.image()); assertEquals(freshRow, f.grantRow(fresh.id))

            // A new stale key is a counted REJECTED terminal receipt, not a malformed-header refusal or a content mutation.
            val stale = attempt.copy(key = UUID.randomUUID(), body = "Stale edit")
            val staleProof = f.proof(); val beforeStale = f.image(); val staleCounters = f.first.counters()
            val rejected = f.edit(stale, staleProof.token)
            f.problem(rejected, 412, "PRECONDITION_FAILED", staleProof.id); f.used(staleProof.id)
            f.assertCharge(staleCounters, ComplaintCapacityCharges.NORMAL_RECEIPT, ComplaintCapacityCharges.ADMIN_EDIT)
            assertEquals(beforeStale.getValue("complaints"), f.image().getValue("complaints"))
            assertEquals(beforeStale.getValue("audits"), f.image().getValue("audits"))
            val ended = f.image()
            val replay = f.edit(stale, fresh.token)
            f.problem(replay, 412, "PRECONDITION_FAILED", staleProof.id); assertArrayEquals(rejected.body(), replay.body())
            assertEquals(ended, f.image()); assertEquals(freshRow, f.grantRow(fresh.id)); f.unused(fresh.id)
            assertEquals(2L, f.observer.queryForObject("SELECT count(*) FROM complaint_idempotency_receipts WHERE data_scope_id = ? " +
                "AND actor_kind = 'ADMIN' AND actor_id = ? AND operation = 'ADMIN_EDIT' AND state = 'COMPLETED'", Long::class.java, f.scope.id, f.users[0].id))
            assertEquals(providers, f.providerCounts()); f.web.assertRequestsReleased()
        }
    }

    fun currentIdentityPasswordAndProof(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredAdminContentHttpV1(tls) { f ->
        f.withCurrent {
            val attempt = RegisteredAdminContentAttemptV1(f.report())
            val admin = f.admin(); val proof = f.proof(); val other = f.proof(index = 1)
            val before = f.image(); val providers = f.providerCounts()
            f.problem(f.issue(password = "incorrect-synthetic-password"), 401, "INVALID_STEP_UP_CREDENTIALS")
            f.problem(f.issue(selectedScope = "source-admin-mutation"), 400, "VALIDATION_FAILED")
            for (bad in listOf(null, JwtTestSupport.tamperSignature(admin), f.ownerBearer,
                f.signer.issue(f.users[0].copy(id = UUID.randomUUID())).value)) {
                f.problem(f.issue(bearer = bad), 401, "UNAUTHORIZED")
                f.problem(f.edit(attempt, proof.token, bad), 401, "UNAUTHORIZED")
            }
            for (supplied in listOf(null, "unknown-printable-proof", other.token)) {
                f.problem(f.edit(attempt, supplied), 401, "ADMIN_STEP_UP_REQUIRED")
                assertEquals(before, f.image()); f.unused(proof.id); f.unused(other.id)
            }
            f.problem(f.edit(attempt, proof.token, selectedScope = ComplaintDataScope.of(UUID.randomUUID())), 404, "NOT_FOUND")
            assertEquals(before, f.image())
            for ((change, restore, status, code) in listOf(
                CurrentUserChange("role = 'USER'", "role = 'ADMIN'", 403, "FORBIDDEN"),
                CurrentUserChange("enabled = false", "enabled = true", 401, "UNAUTHORIZED"),
                CurrentUserChange("credential_version = 1", "credential_version = 0", 401, "UNAUTHORIZED"),
            )) {
                assertEquals(1, f.observer.update("UPDATE users SET $change WHERE id = ?", f.users[0].id))
                try {
                    f.problem(f.issue(bearer = admin), status, code)
                    f.problem(f.edit(attempt, proof.token, admin), status, code)
                    assertEquals(before, f.image())
                } finally { assertEquals(1, f.observer.update("UPDATE users SET $restore WHERE id = ?", f.users[0].id)) }
            }
            changedPasswordSnapshot(f, admin)
            assertEquals(before, f.image()); f.unused(proof.id); f.unused(other.id)
            // Conversely, a signed diagnostic USER claim must not replace the actual current DB ADMIN.
            val diagnosticUser = f.signer.issue(f.users[0].copy(role = Role.USER)).value
            val current = f.proof(bearer = diagnosticUser)
            f.acknowledged(f.edit(attempt, current.token, diagnosticUser), attempt, current.id)
            val applied = f.image()
            f.problem(f.edit(attempt.copy(key = UUID.randomUUID(), version = 2, body = "Cannot reuse used proof"), current.token), 401, "ADMIN_STEP_UP_REQUIRED")
            assertEquals(applied, f.image()); f.unused(proof.id); f.unused(other.id)
            assertEquals(providers, f.providerCounts()); f.web.assertRequestsReleased()
        }
    }

    fun originalPairCheckpointAndReplay(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredAdminContentHttpV1(tls) { f ->
        val web = f.web; val r = f.first.registration; val a = f.first.assembly
        val ownership = web.context.getBean(PersistencePhaseOwnership::class.java)
        val jdbc = web.context.getBean(JdbcTemplate::class.java); val audit = web.context.getBean(AuditService::class.java)
        val decoder = web.context.getBean("jwtDecoder", JwtDecoder::class.java)
        val passwords = web.context.getBean(PasswordEncoder::class.java)
        val old = listOf(ComplaintTestBootstrapHttpCompositionV1.fromRegistered(r, ownership, jdbc),
            ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreate(r, a, ownership, jdbc, audit))
        for (narrow in old) {
            assertTrue(narrow.mappedPaths.intersect(setOf(STEP_UP, "$ADMIN/{id}/content")).isEmpty())
            val request = object : MockHttpServletRequest("PATCH", "$ADMIN/${UUID.randomUUID()}/content") {
                override fun getInputStream(): ServletInputStream = error("Unselected Admin content read body.")
                override fun getHeader(name: String): String? = error("Unselected Admin content read header.")
            }
            val response = MockHttpServletResponse()
            narrow.ingressFilter.doFilter(request, response, FilterChain { _, _ -> error("Unselected Admin content fell through.") })
            assertEquals(404, response.status)
        }
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
            ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateAdminReadContent(r, a, ownership,
                JdbcTemplate(checkNotNull(jdbc.dataSource)), audit, web.startup, decoder, passwords)
        }
        ComplaintTestProcessAssemblyV1.begin().use { foreign ->
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
                ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateAdminReadContent(r, foreign, ownership, jdbc,
                    audit, web.startup, decoder, passwords)
            }
        }
        assertEquals(ComplaintTestDeploymentFailureV1.PROCESS_REFUSED, assertThrows<ComplaintTestDeploymentExceptionV1> {
            ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateAdminReadContent(r, a, ownership, jdbc,
                audit, web.startup, decoder, passwords)
        }.code) // Even the exact pair/configured providers cannot remint the refresh's once-only claim.
        r.requireIdentityAdmissionPhaseResources(ownership, jdbc)

        f.ownerBearer
        val beforeCurrent = f.image(); val native = f.providerCounts()
        f.problem(f.issue(), 503, "SERVICE_UNAVAILABLE") // Counted issuance is new work too; it cannot create its own checkpoint.
        f.problem(f.edit(RegisteredAdminContentAttemptV1(UUID.randomUUID())), 503, "SERVICE_UNAVAILABLE")
        assertEquals(beforeCurrent, f.image()); assertEquals(native, f.providerCounts())
        f.withCurrent {
            val attempt = RegisteredAdminContentAttemptV1(f.report())
            val proof = f.proof()
            val edited = f.edit(attempt, proof.token); f.acknowledged(edited, attempt, proof.id)
            val unused = f.proof(); val unusedRow = f.grantRow(unused.id)
            val generation = checkNotNull(f.observer.queryForObject(
                "SELECT checkpoint_generation FROM complaint_journal_control WHERE data_scope_id = ?", Long::class.java, f.scope.id))
            assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET checkpoint_generation = checkpoint_generation + 1 WHERE data_scope_id = ?", f.scope.id))
            try {
                // Negative scalar drift of the real persisted checkpoint, not supplied/fabricated replacement evidence.
                val drifted = f.image()
                f.acknowledged(f.edit(attempt, unused.token), attempt, proof.id)
                f.problem(f.edit(attempt.copy(key = UUID.randomUUID(), version = 2), unused.token), 503, "SERVICE_UNAVAILABLE")
                f.problem(f.issue(), 503, "SERVICE_UNAVAILABLE")
                assertEquals(drifted, f.image()); assertEquals(unusedRow, f.grantRow(unused.id)); f.unused(unused.id)
            } finally {
                assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET checkpoint_generation = ? WHERE data_scope_id = ?", generation, f.scope.id))
            }
            assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET maintenance_closed = true, creation_closed = true WHERE data_scope_id = ?", f.scope.id))
            val closed = f.image(); val providers = f.providerCounts()
            val replay = f.edit(attempt, unused.token)
            f.acknowledged(replay, attempt, proof.id); assertArrayEquals(edited.body(), replay.body())
            f.problem(f.edit(attempt.copy(key = UUID.randomUUID(), version = 2, body = "Closed new claim"), unused.token), 503, "SERVICE_UNAVAILABLE")
            f.problem(f.issue(), 503, "SERVICE_UNAVAILABLE")
            assertEquals(closed, f.image()); assertEquals(unusedRow, f.grantRow(unused.id)); f.unused(unused.id)
            r.close()
            f.problem(f.edit(attempt, unused.token), 503, "SERVICE_UNAVAILABLE")
            f.problem(f.issue(), 503, "SERVICE_UNAVAILABLE")
            assertEquals(closed, f.image()); assertEquals(providers, f.providerCounts()); web.assertRequestsReleased()
        }
    }

    fun boundedBodiesSharedResponsesAndCleanup(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredAdminContentHttpV1(tls, perHour = 1) { f ->
        f.withCurrent {
            val attempt = RegisteredAdminContentAttemptV1(f.report()); val proof = f.proof()
            f.acknowledged(f.edit(attempt, proof.token), attempt, proof.id)
            val admin = f.admin()
            val image = f.image()
            val validStepUp = mapper.writeValueAsBytes(mapOf("password" to PASSWORD, "scope" to PROOF_SCOPE)).decodeToString()
            for (invalid in listOf(
                "{\"password\":\"$PASSWORD\"}",
                "{\"password\":\"$PASSWORD\",\"scope\":null}",
                validStepUp.dropLast(1) + ",\"password\":\"$PASSWORD\"}",
                validStepUp.dropLast(1) + ",\"grantId\":\"caller-selected\"}",
                "$validStepUp{}",
                mapper.writeValueAsString(mapOf("password" to " ", "scope" to PROOF_SCOPE)),
                mapper.writeValueAsString(mapOf("password" to "x".repeat(257), "scope" to PROOF_SCOPE)),
            )) {
                f.problem(f.web.post(STEP_UP, invalid.toByteArray(), admin), 400, "VALIDATION_FAILED")
                assertEquals(image, f.image())
            }
            for ((template, maximum) in listOf(f.contentRequest(attempt, bearer = admin) to 16_384, f.stepUpRequest(admin) to 4_096)) {
                template.addHeader("Content-Length", (maximum + 1).toString())
                val response = f.dispatch(object : HttpServletRequestWrapper(template) {
                    override fun getInputStream(): ServletInputStream = error("Declared oversize body was acquired.")
                })
                assertEquals(413, response.status); assertNull(response.getHeader(ISSUED_ID)); assertNull(response.getHeader(CONSUMED))
                assertEquals(image, f.image())
            }
            val raw = mapper.writeValueAsBytes(mapOf("password" to PASSWORD, "scope" to PROOF_SCOPE))
            val chunked = f.stepUpRequest(admin).apply { addHeader("Transfer-Encoding", "chunked") }
            var read = 0
            var owned: ByteArray? = null
            val response = f.dispatch(object : HttpServletRequestWrapper(chunked) {
                override fun getContentLengthLong(): Long = -1
                override fun getContentLength(): Int = -1
                override fun getInputStream(): ServletInputStream = object : ServletInputStream() {
                    override fun isFinished(): Boolean = read == 5_000
                    override fun isReady(): Boolean = true
                    override fun setReadListener(listener: ReadListener) = Unit
                    override fun read(): Int = if (read == 5_000) -1 else { val at = read++; if (at < raw.size) raw[at].toInt() and 255 else 32 }
                    override fun readNBytes(length: Int): ByteArray {
                        assertEquals(4_097, length)
                        return super.readNBytes(length).also { owned = it }
                    }
                }
            })
            assertEquals(413, response.status); assertEquals(4_097, read)
            assertTrue(checkNotNull(owned).all { it == 0.toByte() }); assertEquals(image, f.image())
            val exact = f.body(attempt).let { it + ByteArray(16_384 - it.size) { 32 } }
            f.acknowledged(f.web.patchAdminContent(f.path(attempt), exact, admin, attempt.key, attempt.etag, null), attempt, proof.id)
            assertEquals(image, f.image())
            val exactIssuance = raw + ByteArray(4_096 - raw.size) { 32 }
            f.checked(f.web.post(STEP_UP, exactIssuance, admin), 200)
            val afterExact = f.image()
            f.problem(f.edit(attempt.copy(key = UUID.randomUUID(), version = 2), bearer = admin), 429, "RATE_LIMITED")
            assertEquals(afterExact, f.image()) // Same hourly content owner; terminal replay above did not need a new quota slot.

            val reads = poolTestField<Any>(f.composition, "reads")
            val history = poolTestField<ComplaintOwnerHistoryHttpHandler>(reads, "history")
            val owner = poolTestField<ComplaintOwnerHistoryResponses>(history, "responses")
            // Two real ingress contexts at most: seven retained response permits and one actual final delivery.
            for (issuance in listOf(false, true)) {
                val held = List(7) { checkNotNull(owner.acquire()) }
                OwnedCallerTestScope().use { callers ->
                    callers.beforeClose { held.forEach { it.close() } }
                    val gate = callers.gate()
                    val worker = callers.launch {
                        val selected = if (issuance) f.stepUpRequest(admin) else f.contentRequest(attempt, bearer = admin)
                        val delivery = object : MockHttpServletResponse() {
                            override fun getOutputStream(): ServletOutputStream {
                                requireConnectionFree(); assertEquals(0, f.web.admission.activeOwners())
                                gate.hold(); requireConnectionFree()
                                return super.getOutputStream()
                            }
                        }
                        f.dispatch(selected, delivery); assertEquals(200, delivery.status)
                        true
                    }
                    gate.awaitEntered()
                    try {
                        assertEquals(1, f.web.ingressSnapshot().reservations); assertEquals(1, f.web.ingressSnapshot().contexts)
                        assertNull(owner.acquire()); assertEquals(0, f.web.admission.activeOwners())
                        val beforeRefusal = f.image()
                        for (selected in listOf(f.stepUpRequest(admin), f.contentRequest(attempt, bearer = admin))) {
                            val denied = f.dispatch(object : HttpServletRequestWrapper(selected) {
                                override fun getInputStream(): ServletInputStream = error("Exhausted shared response owner read request body.")
                            })
                            assertEquals(503, denied.status); assertNull(denied.getHeader(ISSUED_ID)); assertNull(denied.getHeader(CONSUMED))
                        }
                        f.problem(f.web.get("$ADMIN/stats?dataScopeId=${f.scope.id}", admin), 503, "SERVICE_UNAVAILABLE")
                        assertEquals(beforeRefusal, f.image())
                    } finally { gate.release(); held.forEach { it.close() } }
                    assertTrue(worker.value())
                }
                f.web.assertRequestsReleased(); List(8) { checkNotNull(owner.acquire()) }.forEach { it.close() }
            }
            val ended = f.image()
            val failed = object : MockHttpServletResponse() {
                override fun getOutputStream(): ServletOutputStream = throw IllegalStateException("Synthetic response delivery failure.")
            }
            val failure = assertThrows<IOException> {
                f.dispatch(f.contentRequest(attempt, bearer = admin), failed)
            }
            assertEquals("Complaint response delivery failed.", failure.message)
            assertEquals("true", failed.getHeader(CONSUMED)); assertEquals(proof.id.toString(), failed.getHeader(CONSUMED_ID))
            assertFalse(owner.isOpen()); assertNull(owner.acquire())
            f.problem(f.issue(bearer = admin), 503, "SERVICE_UNAVAILABLE")
            f.problem(f.edit(attempt, bearer = admin), 503, "SERVICE_UNAVAILABLE")
            f.checked(f.web.get(ComplaintInstallationRoutes.HISTORY, f.ownerBearer), 503)
            assertEquals(ended, f.image()); f.web.assertRequestsReleased()
        }
    }

    /** One wiring race, not the lower-layer matrix: only the genuine password-verified final user lock can wait here. */
    private fun changedPasswordSnapshot(f: TestRegisteredAdminContentHttpFixtureV1, bearer: String) {
        val before = f.image()
        val replacement = f.web.context.getBean(PasswordEncoder::class.java).encode("different-synthetic-registered-password")
        try {
            f.first.independentTransaction { blocker, jdbc, blockerPid ->
                // Uncommitted change is invisible to the real released snapshot/password check, but holds the final user lock.
                assertEquals(1, jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", replacement, f.users[0].id))
                OwnedCallerTestScope().use { callers ->
                    callers.beforeClose { blocker.rollback() }
                    val worker = callers.launch { f.issue(bearer = bearer) }
                    awaitLifecycleFact(2_000) {
                        f.observer.queryForObject(
                            "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() " +
                                "AND wait_event_type = 'Lock' AND ? = ANY(pg_blocking_pids(pid)) " +
                                "AND query LIKE 'SELECT id, password_hash, enabled, role FROM users WHERE id = % FOR UPDATE')",
                            Boolean::class.java, blockerPid) == true
                    }
                    blocker.commit() // The old password really matched before this visible change; no provider/phase substitution.
                    f.problem(worker.value(), 503, "SERVICE_UNAVAILABLE")
                }
            }
            assertEquals(before, f.image(), "Changed verified snapshot must roll back issuance's counters and grant.")
            f.web.assertRequestsReleased()
        } finally {
            assertEquals(1, f.observer.update("UPDATE users SET password_hash = ? WHERE id = ?", f.users[0].passwordHash, f.users[0].id))
        }
    }

    private data class CurrentUserChange(val change: String, val restore: String, val status: Int, val code: String)
}
