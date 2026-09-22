package me.manga.kira.backend.complaint.infrastructure.reconciliation

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.http.HttpServletRequestWrapper
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.infrastructure.persistence.AdminBatchStatusAttempt
import me.manga.kira.backend.common.infrastructure.persistence.AdminStatusAttempt
import me.manga.kira.backend.common.infrastructure.persistence.OwnedCallerTestScope
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.awaitLifecycleFact
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryHttpHandler
import me.manga.kira.backend.complaint.api.ComplaintOwnerHistoryResponses
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusOperation.ADMIN_CLOSURE
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatusOperation.ADMIN_STATUS
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintStatus
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentFailureV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestRegisteredAdminBatchStatusInputV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestRegisteredAdminStatusInputV1
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.ADMIN
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.CONSUMED
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.CONSUMED_ID
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.ISSUED_ID
import me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpFixtureV1.Companion.mapper
import me.manga.kira.backend.config.ComplaintTestBootstrapHttpCompositionV1
import me.manga.kira.backend.security.ComplaintInstallationRoutes
import me.manga.kira.backend.security.InstallationJwtCodec
import me.manga.kira.backend.security.adminBatchStatusTestRequest
import me.manga.kira.backend.security.adminStatusTestRequest
import me.manga.kira.backend.support.JwtTestSupport
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
import java.time.Clock
import java.util.Base64
import java.util.UUID

/** Registered batch wiring only. Reuse real startup, password issuance and owners; lower request types carry no authority. */
internal object TestRegisteredAdminBatchStatusHttpCasesV1 {
    fun atomicAccountingAndBounds(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredAdminContentHttpV1(tls,
        adminStatus = statusInput(), adminBatchStatus = batchInput(), createGlobal = 50, enrollmentGlobal = 5, mutationMemberLimit = 128) { f ->
        // All three bounds were selected before protected intake/full D, never raised on the live original.
        assertEquals(50, f.first.process.consumers.ownerCreatePolicy.globalPerHour)
        assertEquals(5, f.first.process.consumers.enrollmentPolicy.globalPerHour)
        assertEquals(128, f.first.process.consumers.ownerCreatePolicy.memberLimit)
        val bearers = listOf(f.ownerBearer) + List(4) { otherOwner(f) }
        f.withCurrent {
            val beforeCreate = f.first.counters()
            val reports = bearers.map { bearer -> List(10) { f.report(bearer) } } // Exactly the existing per-owner10/h, all genuine CREATEs.
            val owners = reports.zip(bearers).flatMap { (ids, bearer) -> ids.map { it to bearer } }.toMap()
            assertEquals(50, owners.size); f.assertCharge(beforeCreate, ComplaintCapacityCharges.OWNER_CREATE.scaled(50))
            val a = reports[0].first(); val b = reports[1].first()
            val providers = f.providerCounts()
            assertNotEquals(f.observer.queryForObject("SELECT owner_id FROM complaints WHERE id = ?", UUID::class.java, a),
                f.observer.queryForObject("SELECT owner_id FROM complaints WHERE id = ?", UUID::class.java, b))
            applied(f, AdminBatchStatusAttempt(listOf(a to 1L)), owners) // Real accepted one-target boundary.
            singleApplied(f, AdminStatusAttempt(b, ADMIN_CLOSURE, reason = "Original other-owner closure"))
            applied(f, AdminBatchStatusAttempt(listOf(a to 2L, b to 2L).sortedByDescending { it.first.toString() }, ComplaintStatus.PLANNED), owners)

            val ids = listOf(a, b).sortedBy(UUID::toString); val good = ids.first(); val bad = ids.last()
            val stale = rejected(f, AdminBatchStatusAttempt(listOf(good to 3L, bad to 2L)), 412, "PRECONDITION_FAILED")
            val fresh = f.proof(); val freshRow = f.grantRow(fresh.id); val ended = f.image()
            assertTrue(f.grantRow(stale.grant).isEmpty())
            for (proof in listOf(null, fresh.token)) replay(f, stale, proof)
            assertEquals(ended, f.image()); assertEquals(freshRow, f.grantRow(fresh.id)); f.unused(fresh.id)
            rejected(f, AdminBatchStatusAttempt(listOf(good to 3L, UUID.randomUUID() to 3L)), 404, "COMPLAINT_NOT_FOUND")
            // Make only the final sorted target a real no-op using the existing producer, never a seeded status/version.
            singleApplied(f, AdminStatusAttempt(bad, version = 3, status = ComplaintStatus.RESOLVED))
            rejected(f, AdminBatchStatusAttempt(listOf(good to 3L, bad to 4L), ComplaintStatus.RESOLVED), 409, "COMPLAINT_NO_CHANGE")
            val history = f.web.get(ComplaintInstallationRoutes.HISTORY, bearers.first()); f.checked(history, 200)
            val notice = f.json(history)["notices"].first()
            rejected(f, AdminBatchStatusAttempt(listOf(good to 3L, UUID.fromString(notice["id"].textValue()) to notice["version"].longValue())),
                404, "COMPLAINT_NOT_FOUND")

            // Exercise every registered per-target current recheck at the successful50 boundary under the unchanged phase deadline.
            val fifty = AdminBatchStatusAttempt(owners.keys.sortedByDescending(UUID::toString).map { id ->
                id to when (id) { good -> 3L; bad -> 4L; else -> 1L }
            }, ComplaintStatus.NOT_PLANNED)
            val maximum = applied(f, fifty, owners, detailIds = reports.map { it.first() }.toSet())
            val unspent = f.proof(); val beforeBounds = f.image()
            assertTrue(f.grantRow(maximum.grant).isEmpty())
            for (proof in listOf(null, unspent.token)) replay(f, maximum, proof, fifty.copy(targets = fifty.targets.reversed()))
            for (targets in listOf(emptyList(), fifty.targets + (UUID.randomUUID() to 1L), listOf(good to 3L, good to 3L))) {
                f.problem(change(f, fifty.copy(key = UUID.randomUUID(), targets = targets), unspent.token), 400, "VALIDATION_FAILED")
            }
            assertEquals(beforeBounds, f.image()); f.unused(unspent.id)
            assertEquals(7L, f.observer.queryForObject("SELECT count(*) FROM complaint_idempotency_receipts WHERE data_scope_id = ? " +
                "AND operation = 'ADMIN_BATCH_STATUS' AND state = 'COMPLETED'", Long::class.java, f.scope.id))
            assertEquals(providers, f.providerCounts()); f.web.assertRequestsReleased()
        }
    }

    fun replayAndCurrentState(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredAdminContentHttpV1(tls,
        adminStatus = statusInput(), adminBatchStatus = batchInput()) { f ->
        val beforeCurrent = f.image(); val beforeProviders = f.providerCounts()
        f.problem(change(f, AdminBatchStatusAttempt(listOf(UUID.randomUUID() to 1L))), 503, "SERVICE_UNAVAILABLE")
        assertEquals(beforeCurrent, f.image()); assertEquals(beforeProviders, f.providerCounts())
        f.withCurrent {
            val a = f.report(); val b = f.report()
            val original = applied(f, AdminBatchStatusAttempt(listOf(a to 1L, b to 1L)))
            val content = RegisteredAdminContentAttemptV1(a, version = 2)
            val contentProof = f.proof(); f.acknowledged(f.edit(content, contentProof.token), content, contentProof.id)
            val status = AdminStatusAttempt(b, version = 2, status = ComplaintStatus.PLANNED); singleApplied(f, status)
            val closure = AdminStatusAttempt(b, ADMIN_CLOSURE, version = 3); singleApplied(f, closure)
            // Another actual ADMIN may use the same key; the receipt namespace includes the actor.
            applied(f, AdminBatchStatusAttempt(listOf(a to 3L, b to 4L), ComplaintStatus.RESOLVED, original.attempt.key), actor = 1)
            val fresh = f.proof(); val other = f.proof(index = 1); val freshRow = f.grantRow(fresh.id); val otherRow = f.grantRow(other.id)
            assertTrue(f.grantRow(original.grant).isEmpty())
            val before = f.image()
            for (proof in listOf(null, fresh.token)) replay(f, original, proof, original.attempt.copy(targets = original.attempt.targets.reversed()))
            for (variant in listOf(
                original.attempt.copy(status = ComplaintStatus.PLANNED), original.attempt.copy(targets = listOf(a to 4L, b to 5L)),
                original.attempt.copy(targets = listOf(a to 1L)), original.attempt.copy(targets = listOf(UUID.randomUUID() to 1L)),
                original.attempt.copy(key = content.key), original.attempt.copy(key = status.key), original.attempt.copy(key = closure.key),
            )) f.problem(change(f, variant, fresh.token), 409, "IDEMPOTENCY_KEY_REUSED")
            f.problem(f.edit(content.copy(key = original.attempt.key), fresh.token), 409, "IDEMPOTENCY_KEY_REUSED")
            for (attempt in listOf(status, closure)) f.problem(single(f, attempt.copy(key = original.attempt.key), fresh.token), 409, "IDEMPOTENCY_KEY_REUSED")
            f.problem(change(f, original.attempt, fresh.token, scope = ComplaintDataScope.of(UUID.randomUUID())), 404, "NOT_FOUND")
            for (bearer in listOf(null, JwtTestSupport.tamperSignature(f.admin()), f.ownerBearer)) {
                f.problem(change(f, original.attempt, fresh.token, bearer), 401, "UNAUTHORIZED")
            }
            val current = AdminBatchStatusAttempt(listOf(a to 4L, b to 5L), ComplaintStatus.PLANNED)
            for (proof in listOf(null, other.token)) f.problem(change(f, current.copy(key = UUID.randomUUID()), proof), 401, "ADMIN_STEP_UP_REQUIRED")
            assertEquals(before, f.image()); assertEquals(freshRow, f.grantRow(fresh.id)); assertEquals(otherRow, f.grantRow(other.id))
            for ((sqlChange, restore, code, statusCode) in listOf(
                UserChange("role = 'USER'", "role = 'ADMIN'", "FORBIDDEN", 403),
                UserChange("enabled = false", "enabled = true", "UNAUTHORIZED", 401),
                UserChange("credential_version = 1", "credential_version = 0", "UNAUTHORIZED", 401),
            )) {
                assertEquals(1, f.observer.update("UPDATE users SET $sqlChange WHERE id = ?", f.users[0].id))
                try {
                    f.problem(change(f, original.attempt, fresh.token), statusCode, code); assertEquals(before, f.image())
                } finally { assertEquals(1, f.observer.update("UPDATE users SET $restore WHERE id = ?", f.users[0].id)) }
            }
            currentAdminAfterGrantWait(f, current, fresh)
            checkpointAfterControlWait(f, original, current, fresh)
            assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET maintenance_closed = true, creation_closed = true WHERE data_scope_id = ?", f.scope.id))
            replayWithoutNewWork(f, original, current, fresh)
            val closed = f.image(); val providers = f.providerCounts()
            f.first.registration.close()
            f.problem(change(f, original.attempt, fresh.token), 503, "SERVICE_UNAVAILABLE"); f.problem(f.issue(), 503, "SERVICE_UNAVAILABLE")
            assertEquals(closed, f.image()); assertEquals(providers, f.providerCounts()); f.unused(fresh.id); f.unused(other.id); f.web.assertRequestsReleased()
        }
    }

    fun quotaBodiesResponsesAndCleanup(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredAdminContentHttpV1(tls,
        perHour = 1, adminStatus = statusInput(1), adminBatchStatus = batchInput(1)) { f ->
        f.withCurrent {
            val id = f.report(); val original = applied(f, AdminBatchStatusAttempt(listOf(id to 1L)))
            val fresh = f.proof(); val beforeQuota = f.image()
            f.problem(change(f, AdminBatchStatusAttempt(listOf(id to 2L), ComplaintStatus.PLANNED), fresh.token), 429, "RATE_LIMITED")
            replay(f, original, fresh.token); assertEquals(beforeQuota, f.image()); f.unused(fresh.id)
            val status = AdminStatusAttempt(id, version = 2, status = ComplaintStatus.PLANNED)
            singleApplied(f, status, fresh) // Separate batch/single/content hourly allowances, original shared finite stores.
            val content = RegisteredAdminContentAttemptV1(id, version = 3); val contentProof = f.proof()
            f.acknowledged(f.edit(content, contentProof.token), content, contentProof.id)
            val unused = f.proof(); val image = f.image(); val unusedRow = f.grantRow(unused.id)
            val current = AdminBatchStatusAttempt(listOf(id to 4L), ComplaintStatus.RESOLVED)
            val raw = bytes(current).decodeToString()
            val target = targets(current).single()
            for (invalid in listOf(
                mapper.writeValueAsString(mapOf("action" to "DELETE", "targets" to listOf(target))),
                raw.replace("\"RESOLVED\"", "\"CLOSED\""),
                raw.dropLast(1) + ",\"action\":\"STATUS\"}",
                raw.dropLast(1) + ",\"actorId\":\"caller-selected\"}",
                "$raw{}",
            )) f.problem(f.web.post(path(f), invalid.toByteArray(), f.admin(), UUID.randomUUID(), unused.token), 400, "VALIDATION_FAILED")
            val missingTag = mapper.writeValueAsBytes(mapOf("action" to "STATUS", "status" to "RESOLVED", "targets" to listOf(mapOf("id" to id.toString()))))
            f.problem(f.web.post(path(f), missingTag, f.admin(), UUID.randomUUID(), unused.token), 428, "PRECONDITION_REQUIRED")
            val weakTag = mapper.writeValueAsBytes(mapOf("action" to "STATUS", "status" to "RESOLVED",
                "targets" to listOf(target + ("actionTag" to "W/${target.getValue("actionTag")}"))))
            f.problem(f.web.post(path(f), weakTag, f.admin(), UUID.randomUUID(), unused.token), 412, "PRECONDITION_FAILED")
            val aggregate = request(f, current).apply { addHeader("If-Match", "\"complaint-$id-v4\"") }
            assertEquals(400, f.dispatch(object : HttpServletRequestWrapper(aggregate) {
                override fun getInputStream(): ServletInputStream = error("Batch aggregate precondition acquired a body.")
            }).status)
            val exact = bytes(original.attempt).let { it + ByteArray(32_768 - it.size) { 32 } }
            acknowledge(f, original.attempt, f.web.post(path(f), exact, f.admin(), original.attempt.key, unused.token), original.grant)
            val declared = request(f, original.attempt).apply { removeHeader("Content-Length"); addHeader("Content-Length", "32769") }
            assertEquals(413, f.dispatch(object : HttpServletRequestWrapper(declared) {
                override fun getInputStream(): ServletInputStream = error("Declared oversized batch body was acquired.")
            }).status)
            val chunked = request(f, original.attempt).apply { removeHeader("Content-Length"); addHeader("Transfer-Encoding", "chunked") }
            var read = 0; var owned: ByteArray? = null
            val denied = f.dispatch(object : HttpServletRequestWrapper(chunked) {
                override fun getContentLengthLong(): Long = -1
                override fun getContentLength(): Int = -1
                override fun getInputStream(): ServletInputStream = object : ServletInputStream() {
                    override fun isFinished(): Boolean = read == 40_000
                    override fun isReady(): Boolean = true
                    override fun setReadListener(listener: ReadListener) = Unit
                    override fun read(): Int = if (read == 40_000) -1 else { read++; 32 }
                    override fun readNBytes(length: Int): ByteArray {
                        assertEquals(32_769, length)
                        return super.readNBytes(length).also { owned = it }
                    }
                }
            })
            assertEquals(413, denied.status); assertEquals(32_769, read); assertTrue(checkNotNull(owned).all { it == 0.toByte() })
            assertEquals(image, f.image()); assertEquals(unusedRow, f.grantRow(unused.id)); f.unused(unused.id)

            val reads = poolTestField<Any>(f.composition, "reads")
            val owner = poolTestField<ComplaintOwnerHistoryResponses>(poolTestField<ComplaintOwnerHistoryHttpHandler>(reads, "history"), "responses")
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
                    assertEquals(200, output.status); assertEquals("true", output.getHeader(CONSUMED)); assertEquals(original.grant.toString(), output.getHeader(CONSUMED_ID))
                    true
                }
                gate.awaitEntered()
                try {
                    assertEquals(1, f.web.ingressSnapshot().reservations); assertEquals(1, f.web.ingressSnapshot().contexts)
                    assertNull(owner.acquire()); assertEquals(0, f.web.admission.activeOwners())
                    for (selected in listOf(request(f, current), f.contentRequest(content), f.stepUpRequest(),
                        adminStatusTestRequest(ADMIN_STATUS, f.scope, id, bearer = f.admin()),
                        adminStatusTestRequest(ADMIN_CLOSURE, f.scope, id, bearer = f.admin()))) {
                        val refused = f.dispatch(object : HttpServletRequestWrapper(selected) {
                            override fun getInputStream(): ServletInputStream = error("Full shared response owner acquired a body.")
                        })
                        assertEquals(503, refused.status); assertNull(refused.getHeader(CONSUMED)); assertNull(refused.getHeader(CONSUMED_ID)); assertNull(refused.getHeader(ISSUED_ID))
                    }
                    f.problem(f.web.get("$ADMIN/stats?dataScopeId=${f.scope.id}", f.admin()), 503, "SERVICE_UNAVAILABLE")
                    assertEquals(image, f.image())
                } finally { gate.release(); held.forEach { it.close() } }
                assertTrue(worker.value())
            }
            f.web.assertRequestsReleased(); List(8) { checkNotNull(owner.acquire()) }.forEach { it.close() }
            val failed = object : MockHttpServletResponse() {
                override fun getOutputStream(): ServletOutputStream = throw IllegalStateException("Synthetic batch delivery failure.")
            }
            assertEquals("Complaint response delivery failed.", assertThrows<IOException> { f.dispatch(request(f, original.attempt, unused.token), failed) }.message)
            assertEquals("true", failed.getHeader(CONSUMED)); assertEquals(original.grant.toString(), failed.getHeader(CONSUMED_ID))
            assertFalse(owner.isOpen()); assertNull(owner.acquire())
            f.problem(change(f, original.attempt, unused.token), 503, "SERVICE_UNAVAILABLE")
            f.problem(single(f, status, unused.token), 503, "SERVICE_UNAVAILABLE")
            f.problem(f.edit(content, unused.token), 503, "SERVICE_UNAVAILABLE"); f.problem(f.issue(), 503, "SERVICE_UNAVAILABLE")
            f.checked(f.web.get(ComplaintInstallationRoutes.HISTORY, f.ownerBearer), 503)
            assertEquals(image, f.image()); assertEquals(unusedRow, f.grantRow(unused.id)); f.unused(unused.id); f.web.assertRequestsReleased()
        }
    }

    fun oldStatusSelectionAndOriginalPair(tls: VersionBoundPersistenceConnectedFixture) = withRegisteredAdminContentHttpV1(tls,
        adminStatus = statusInput(), adminBatchStatus = batchInput(), selectAdminBatchStatus = false) { f ->
        assertFalse(f.composition.mappedPaths.contains(BATCH))
        val before = f.image(); val providers = f.providerCounts()
        f.web.refusedBeforeBody("POST", BATCH, f.admin(), 404)
        assertEquals(404, f.dispatch(object : MockHttpServletRequest("POST", BATCH) {
            override fun getInputStream(): ServletInputStream = error("Unselected batch route read its body.")
            override fun getHeader(name: String): String? = error("Unselected batch route read a header.")
        }).status)
        assertEquals(before, f.image()); assertEquals(providers, f.providerCounts())
        f.withCurrent {
            val id = f.report(); singleApplied(f, AdminStatusAttempt(id))
            val content = RegisteredAdminContentAttemptV1(id, version = 2); val proof = f.proof()
            f.acknowledged(f.edit(content, proof.token), content, proof.id)
        }
        val registration = f.first.registration; val assembly = f.first.assembly
        val ownership = f.web.context.getBean(PersistencePhaseOwnership::class.java)
        val jdbc = f.web.context.getBean(JdbcTemplate::class.java); val audit = f.web.context.getBean(AuditService::class.java)
        val decoder = f.web.context.getBean("jwtDecoder", JwtDecoder::class.java); val passwords = f.web.context.getBean(PasswordEncoder::class.java)
        val image = f.image()
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
            ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateAdminReadContentStatusBatchStatus(registration,
                assembly, ownership, JdbcTemplate(checkNotNull(jdbc.dataSource)), audit, f.web.startup, decoder, passwords)
        }
        ComplaintTestProcessAssemblyV1.begin().use { foreign ->
            assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> {
                ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateAdminReadContentStatusBatchStatus(registration,
                    foreign, ownership, jdbc, audit, f.web.startup, decoder, passwords)
            }
        }
        assertEquals(ComplaintTestDeploymentFailureV1.PROCESS_REFUSED, assertThrows<ComplaintTestDeploymentExceptionV1> {
            ComplaintTestBootstrapHttpCompositionV1.fromRegisteredInitialCheckpointReadCreateAdminReadContentStatusBatchStatus(registration,
                assembly, ownership, jdbc, audit, f.web.startup, decoder, passwords)
        }.code)
        registration.requireIdentityAdmissionPhaseResources(ownership, jdbc)
        assertEquals(image, f.image()); f.web.assertRequestsReleased()
    }

    private fun applied(f: TestRegisteredAdminContentHttpFixtureV1, attempt: AdminBatchStatusAttempt,
        owners: Map<UUID, String> = attempt.targets.associate { it.first to f.ownerBearer }, actor: Int = 0,
        detailIds: Set<UUID> = attempt.targets.map { it.first }.toSet()): ObservedBatch {
        val proof = f.proof(actor); val before = f.image(); val counters = f.first.counters()
        val admission = admissionCounts(f)
        val prior = attempt.targets.associate { it.first to immutable(f, it.first) }
        val states = attempt.targets.associate { (id, _) -> id to checkNotNull(f.observer.queryForObject("SELECT status FROM complaints WHERE id = ?", String::class.java, id)) }
        val response = change(f, attempt, proof.token, f.admin(actor))
        acknowledge(f, attempt, response, proof.id); f.used(proof.id); f.assertCharge(counters, ComplaintCapacityCharges.adminBatchStatus(attempt.targets.size))
        for ((id, version) in attempt.targets) {
            assertEquals(prior.getValue(id), immutable(f, id))
            assertEquals(true, f.observer.queryForObject("SELECT status = ? AND version = ? AND closure_reason IS NULL AND closure_provenance IS NULL " +
                "AND closure_actor_id IS NULL AND closed_at IS NULL FROM complaints WHERE data_scope_id = ? AND id = ?", Boolean::class.java, attempt.status.name, version + 1, f.scope.id, id))
            assertEquals(true, f.observer.queryForObject("SELECT count(*) = 1 AND bool_and(a.complaint_actor_kind = 'ADMIN' AND a.actor_user_id = ? " +
                "AND a.created_at = c.updated_at AND a.detail = jsonb_build_object('version', ?::bigint, 'fromStatus', ?::text, 'toStatus', ?::text)) " +
                "FROM audit_log a JOIN complaints c ON c.id::text = a.entity_id AND c.data_scope_id = a.complaint_data_scope_id " +
                "WHERE a.complaint_data_scope_id = ? AND c.id = ? AND a.action = 'COMPLAINT_STATUS_CHANGED' AND a.detail ->> 'version' = ?",
                Boolean::class.java, f.users[actor].id, version + 1, states.getValue(id), attempt.status.name, f.scope.id, id, (version + 1).toString()))
            for (detail in if (id in detailIds) listOf(f.detail(id), f.web.get("${ComplaintInstallationRoutes.HISTORY}/$id", owners.getValue(id))) else emptyList()) {
                f.checked(detail, 200); assertEquals(attempt.status.name, f.json(detail)["status"].textValue()); assertEquals(version + 1, f.json(detail)["version"].longValue())
                assertEquals("\"complaint-$id-v${version + 1}\"", f.header(detail, "ETag"))
            }
        }
        val sorted = attempt.targets.sortedBy { it.first.toString() }
        assertEquals(true, f.observer.queryForObject("SELECT test_only AND operation = 'ADMIN_BATCH_STATUS' AND state = 'COMPLETED' AND outcome = 'APPLIED' " +
            "AND response_status = 200 AND target_ids = ?::uuid[] AND ack_ids = target_ids AND ack_versions = ?::bigint[] " +
            "AND consumed_grant_id = ? AND expires_at = completed_at + interval '192 hours' AND response_etag IS NULL AND problem_code IS NULL " +
            "AND publication_ref IS NULL AND external_event_id IS NULL FROM complaint_idempotency_receipts " +
            "WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ? AND data_scope_id = ?", Boolean::class.java,
            array(sorted.map { it.first }), array(sorted.map { it.second + 1 }), proof.id, f.users[actor].id, attempt.key, f.scope.id))
        val after = f.image(); assertEquals(before.getValue("audits").size + attempt.targets.size, after.getValue("audits").size)
        assertTrue(after.getValue("audits").containsAll(before.getValue("audits"))); preserved(before, after)
        oneAdmission(f, admission)
        f.web.assertRequestsReleased()
        return ObservedBatch(attempt, response, proof.id)
    }

    private fun rejected(f: TestRegisteredAdminContentHttpFixtureV1, attempt: AdminBatchStatusAttempt, status: Int, code: String): ObservedBatch {
        val proof = f.proof(); val before = f.image(); val counters = f.first.counters()
        val admission = admissionCounts(f)
        val response = change(f, attempt, proof.token)
        f.problem(response, status, code, proof.id); f.used(proof.id)
        f.assertCharge(counters, ComplaintCapacityCharges.NORMAL_RECEIPT, ComplaintCapacityCharges.adminBatchStatus(attempt.targets.size))
        val after = f.image(); assertEquals(before.getValue("complaints"), after.getValue("complaints")); assertEquals(before.getValue("audits"), after.getValue("audits")); preserved(before, after)
        assertEquals(true, f.observer.queryForObject("SELECT test_only AND operation = 'ADMIN_BATCH_STATUS' AND state = 'COMPLETED' AND outcome = 'REJECTED' " +
            "AND response_status = ? AND problem_code = ? AND target_ids = ?::uuid[] AND consumed_grant_id = ? " +
            "AND ack_ids IS NULL AND ack_versions IS NULL AND response_etag IS NULL AND expires_at = completed_at + interval '192 hours' " +
            "AND publication_ref IS NULL AND external_event_id IS NULL FROM complaint_idempotency_receipts " +
            "WHERE actor_kind = 'ADMIN' AND actor_id = ? AND idempotency_key = ? AND data_scope_id = ?", Boolean::class.java,
            status, code, array(attempt.targets.map { it.first }.sortedBy(UUID::toString)), proof.id, f.users[0].id, attempt.key, f.scope.id))
        oneAdmission(f, admission)
        f.web.assertRequestsReleased()
        return ObservedBatch(attempt, response, proof.id)
    }

    /** One registered wait boundary, not the existing lower race matrix or a substituted user/issuer provider. */
    private fun currentAdminAfterGrantWait(f: TestRegisteredAdminContentHttpFixtureV1, attempt: AdminBatchStatusAttempt, proof: RegisteredAdminIssuedProofV1) {
        val before = f.image(); val row = f.grantRow(proof.id)
        try {
            f.first.independentTransaction { blocker, jdbc, blockerPid ->
                assertEquals(proof.id, jdbc.queryForObject("SELECT id FROM admin_step_up_grants WHERE id = ? FOR UPDATE", UUID::class.java, proof.id))
                assertEquals(1, jdbc.update("UPDATE users SET role = 'USER' WHERE id = ?", f.users[0].id))
                OwnedCallerTestScope().use { callers ->
                    callers.beforeClose { blocker.rollback() }
                    val worker = callers.launch { change(f, attempt, proof.token) }
                    awaitLifecycleFact(2_000) {
                        f.observer.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() " +
                            "AND wait_event_type = 'Lock' AND ? = ANY(pg_blocking_pids(pid)) " +
                            "AND query LIKE '%FROM admin_step_up_grants%FOR UPDATE%')", Boolean::class.java, blockerPid) == true
                    }
                    blocker.commit() // Initial DB authentication saw ADMIN; the actual post-grant-wait check must now see USER.
                    f.problem(worker.value(), 403, "FORBIDDEN")
                }
            }
            assertEquals(before, f.image()); assertEquals(row, f.grantRow(proof.id)); f.unused(proof.id); f.web.assertRequestsReleased()
        } finally { assertEquals(1, f.observer.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", f.users[0].id)) }
    }

    private fun checkpointAfterControlWait(f: TestRegisteredAdminContentHttpFixtureV1, original: ObservedBatch,
        attempt: AdminBatchStatusAttempt, proof: RegisteredAdminIssuedProofV1) {
        val generation = checkNotNull(f.observer.queryForObject("SELECT checkpoint_generation FROM complaint_journal_control WHERE data_scope_id = ?", Long::class.java, f.scope.id))
        val before = f.image().filterKeys { it != "complaint_journal_control" }; val grant = f.grantRow(proof.id)
        try {
            f.first.independentTransaction { blocker, jdbc, blockerPid ->
                assertEquals(1, jdbc.update("UPDATE complaint_journal_control SET checkpoint_generation = checkpoint_generation + 1 WHERE data_scope_id = ?", f.scope.id))
                OwnedCallerTestScope().use { callers ->
                    callers.beforeClose { blocker.rollback() }
                    val worker = callers.launch { change(f, attempt.copy(key = UUID.randomUUID()), proof.token) }
                    awaitLifecycleFact(2_000) {
                        f.observer.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() " +
                            "AND wait_event_type = 'Lock' AND ? = ANY(pg_blocking_pids(pid)) " +
                            "AND query LIKE '%FROM complaint_journal_control%FOR UPDATE%')", Boolean::class.java, blockerPid) == true
                    }
                    // The actual new claim is waiting on its control, not holding/consuming the original B grant early.
                    assertEquals(proof.id, jdbc.queryForObject("SELECT id FROM admin_step_up_grants WHERE id = ? AND used_at IS NULL FOR UPDATE NOWAIT",
                        UUID::class.java, proof.id))
                    blocker.commit()
                    f.problem(worker.value(), 503, "SERVICE_UNAVAILABLE")
                }
            }
            assertEquals(before, f.image().filterKeys { it != "complaint_journal_control" })
            assertEquals(grant, f.grantRow(proof.id)); f.unused(proof.id); f.web.assertRequestsReleased()
            replayWithoutNewWork(f, original, attempt, proof)
        } finally {
            assertEquals(1, f.observer.update("UPDATE complaint_journal_control SET checkpoint_generation = ? WHERE data_scope_id = ?", generation, f.scope.id))
        }
    }

    private fun replayWithoutNewWork(f: TestRegisteredAdminContentHttpFixtureV1, original: ObservedBatch, current: AdminBatchStatusAttempt, proof: RegisteredAdminIssuedProofV1) {
        val before = f.image(); val providers = f.providerCounts(); val grant = f.grantRow(proof.id)
        replay(f, original, proof.token)
        f.problem(change(f, current.copy(key = UUID.randomUUID()), proof.token), 503, "SERVICE_UNAVAILABLE")
        f.problem(f.issue(), 503, "SERVICE_UNAVAILABLE")
        assertEquals(before, f.image()); assertEquals(providers, f.providerCounts()); assertEquals(grant, f.grantRow(proof.id)); f.unused(proof.id)
    }
    private fun replay(f: TestRegisteredAdminContentHttpFixtureV1, original: ObservedBatch, proof: String?, attempt: AdminBatchStatusAttempt = original.attempt) {
        val admission = admissionCounts(f)
        val response = change(f, attempt, proof)
        f.checked(response, original.response.statusCode()); f.association(response, original.grant); assertNull(f.header(response, "ETag"))
        assertArrayEquals(original.response.body(), response.body())
        assertEquals(admission, admissionCounts(f))
    }
    private fun acknowledge(f: TestRegisteredAdminContentHttpFixtureV1, attempt: AdminBatchStatusAttempt, response: HttpResponse<ByteArray>, grant: UUID) {
        f.checked(response, 200); f.association(response, grant); assertNull(f.header(response, "ETag")); assertNull(f.header(response, "Location"))
        val items = attempt.targets.sortedBy { it.first.toString() }.joinToString(",") { (id, version) -> "{\"id\":\"$id\",\"version\":${version + 1}}" }
        assertEquals("{\"items\":[$items]}", response.body().decodeToString())
    }
    private fun singleApplied(f: TestRegisteredAdminContentHttpFixtureV1, attempt: AdminStatusAttempt, proof: RegisteredAdminIssuedProofV1 = f.proof()) {
        val response = single(f, attempt, proof.token)
        f.checked(response, 200); f.association(response, proof.id); f.used(proof.id)
        assertEquals(attempt.id.toString(), f.json(response)["id"].textValue()); assertEquals(attempt.version + 1, f.json(response)["version"].longValue())
    }
    private fun single(f: TestRegisteredAdminContentHttpFixtureV1, attempt: AdminStatusAttempt, proof: String?): HttpResponse<ByteArray> =
        f.web.patchAdminContent("$ADMIN/${attempt.id}${attempt.operation.suffix}?dataScopeId=${f.scope.id}", mapper.writeValueAsBytes(
            if (attempt.operation == ADMIN_STATUS) mapOf("status" to attempt.status.name) else mapOf("reason" to attempt.reason)),
            f.admin(), attempt.key, "\"complaint-${attempt.id}-v${attempt.version}\"", proof)
    private fun otherOwner(f: TestRegisteredAdminContentHttpFixtureV1): String {
        val installation = f.first.initial.candidate().installation // Original fixture tracks this real enrollment for disposal.
        val response = f.web.post(ComplaintInstallationRoutes.ENROLLMENT, mapper.writeValueAsBytes(mapOf(
            "installationId" to installation.id.toString(), "expectedDataScopeId" to f.scope.id.toString(), "platform" to "ANDROID",
            "secret" to Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() }))))
        f.checked(response, 201)
        return checkNotNull(f.json(response)["accessToken"].textValue()).also {
            assertEquals(installation, InstallationJwtCodec(f.first.process.consumers.jwt.installationKeyRing, Clock.systemUTC()).verify(it).installation)
            f.web.assertRequestsReleased()
        }
    }
    private fun preserved(before: Map<String, List<String>>, after: Map<String, List<String>>) {
        for ((table, rows) in before.filterKeys { it !in setOf("complaints", "counters", "audits", "complaint_idempotency_receipts", "admin_step_up_grants") }) {
            assertEquals(rows, after.getValue(table), "Batch STATUS cannot change $table.")
        }
    }
    /** Read-only observation of the original finite stores; no key/clock/member/counter mutation. */
    private fun admissionCounts(f: TestRegisteredAdminContentHttpFixtureV1): Pair<Int, Int> {
        val ingress = f.first.process.consumers.ingressAdmission
        return synchronized(poolTestField<Any>(ingress, "lock")) {
            poolTestField<Map<*, *>>(poolTestField<Any>(ingress, "mutationMembers"), "members").size to
                poolTestField<Int>(poolTestField<Any>(ingress, "semantics"), "events")
        }
    }
    private fun oneAdmission(f: TestRegisteredAdminContentHttpFixtureV1, before: Pair<Int, Int>) {
        val keys = if (f.first.process.consumers.admissionPreviousKeyId == null) 1 else 2
        assertEquals((before.first + keys) to (before.second + keys), admissionCounts(f), "One member/event per retained key, not one per target.")
    }
    private fun immutable(f: TestRegisteredAdminContentHttpFixtureV1, id: UUID): String = checkNotNull(f.observer.queryForObject(
        "SELECT (to_jsonb(c) - ARRAY['status','closure_reason','closure_provenance','closure_actor_id','closed_at','updated_at','version'])::text " +
            "FROM complaints c WHERE data_scope_id = ? AND id = ?", String::class.java, f.scope.id, id))
    private fun targets(attempt: AdminBatchStatusAttempt): List<Map<String, String>> = attempt.targets.map { (id, version) ->
        mapOf("id" to id.toString(), "actionTag" to "\"complaint-$id-v$version\"") }
    private fun bytes(attempt: AdminBatchStatusAttempt): ByteArray = mapper.writeValueAsBytes(mapOf("action" to "STATUS", "status" to attempt.status.name, "targets" to targets(attempt)))
    private fun path(f: TestRegisteredAdminContentHttpFixtureV1, scope: ComplaintDataScope = f.scope): String = "$BATCH?dataScopeId=${scope.id}"
    private fun change(f: TestRegisteredAdminContentHttpFixtureV1, attempt: AdminBatchStatusAttempt, proof: String? = null,
        bearer: String? = f.admin(), scope: ComplaintDataScope = f.scope): HttpResponse<ByteArray> = f.web.post(path(f, scope), bytes(attempt), bearer, attempt.key, proof)
    private fun request(f: TestRegisteredAdminContentHttpFixtureV1, attempt: AdminBatchStatusAttempt, proof: String? = null): MockHttpServletRequest =
        adminBatchStatusTestRequest(f.scope, attempt.key, f.admin(), proof, bytes(attempt).decodeToString())
    private fun array(values: List<Any>): String = values.joinToString(",", "{", "}")
    private fun statusInput(perHour: Int = 60) = TestRegisteredAdminStatusInputV1(1, TestRegisteredAdminStatusInputV1.PROFILE, perHour)
    private fun batchInput(perHour: Int = 60) = TestRegisteredAdminBatchStatusInputV1(1, TestRegisteredAdminBatchStatusInputV1.PROFILE, perHour)
    private class ObservedBatch(val attempt: AdminBatchStatusAttempt, val response: HttpResponse<ByteArray>, val grant: UUID)
    private data class UserChange(val change: String, val restore: String, val code: String, val status: Int)
    private const val BATCH = "/api/v1/admin/complaints/batch"
}
