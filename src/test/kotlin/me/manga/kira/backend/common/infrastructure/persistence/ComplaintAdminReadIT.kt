package me.manga.kira.backend.common.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.MACSigner
import jakarta.servlet.ServletOutputStream
import me.manga.kira.backend.complaint.domain.ComplaintAdminDetailQuery
import me.manga.kira.backend.complaint.domain.ComplaintAdminItem
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadAuthentication
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadPosition
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRejected
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadResult
import me.manga.kira.backend.complaint.domain.ComplaintAdminSearchQuery
import me.manga.kira.backend.complaint.domain.ComplaintAdminStatsQuery
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminReadVerdict
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminReadStore
import me.manga.kira.backend.security.ComplaintAdminReadAdmissionPolicy
import me.manga.kira.backend.security.ComplaintAdmissionRejected
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.security.adminReadDetailRequest
import me.manga.kira.backend.security.adminReadSearchRequest
import me.manga.kira.backend.security.adminReadTestIngress
import me.manga.kira.backend.security.historyTestUserKey
import me.manga.kira.backend.support.JwtTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Actual normal-user JWT, current ADMIN, original ordinary phases and HTTP bytes. Synthetic TEST rows are not G1/full-D authority. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class ComplaintAdminReadIT {
    private val database = lazy { PgLifecycleDatabaseFixture(ComplaintAdminReadIT::class.java).also { it.start() } }
    private val mapper = ObjectMapper()

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun statsScopeWideCountsUseTheSameVisibleRelationAsAdminRead() = withFixture(::verifyComplaintAdminStatsPopulation)

    @Test
    fun statsTopFiftyUsesUtf8TiesAndExactRemainderWithNullRankedNormally() = withFixture(::verifyComplaintAdminStatsRanking)

    @Test
    fun statsUseTheCurrentDataSnapshotAfterReleasedAuthentication() = withFixture(::verifyComplaintAdminStatsSnapshot)

    @Test
    fun statsInvalidStoredVersionFailsClosedBeforePublishingPartialCounts() = withFixture(::verifyComplaintAdminStatsInvalidRow)

    @Test
    fun `actual normal JWT and two installations produce51 tied search rows cursor and exact details without writes`() = withFixture { f ->
        val other = f.rows.enrolledSession().installation
        val low = f.rows.content(id = UUID.fromString("00000000-0000-4000-8000-000000000001"))
        val high = f.rows.content(owner = other, id = UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff"))
        assertEquals(1, f.observer.update("UPDATE complaints SET version = ? WHERE id = ?", 9_007_199_254_740_993L, low))
        assertEquals(
            1,
            f.observer.update(
                "UPDATE complaints SET status = 'CLOSED', closure_reason = 'Synthetic resolved', closed_at = ?, " +
                    "closure_provenance = 'ADMIN', closure_actor_id = ?, version = ? WHERE id = ?",
                Timestamp.from(f.ordinary.cutoff), f.ordinary.userId, Long.MAX_VALUE, high,
            ),
        )
        val notice = f.rows.notice(key = "admin.read.integration.notice")
        val noticeReply = f.rows.content(parent = notice, key = "admin.read.integration.notice")
        val transitive = f.rows.content(owner = other, parent = noticeReply, key = "admin.read.integration.notice")
        val ids = mutableListOf(low, high, notice, noticeReply, transitive)
        repeat(45) { ids.add(f.rows.content(owner = if (it % 2 == 0) other else f.rows.actor)) }
        val fifty = f.search()
        assertEquals(200, fifty.status)
        assertEquals(ids.sortedByDescending(UUID::toString), f.itemIds(fifty))
        assertNull(f.cursor(fifty))
        ids.add(f.rows.content(owner = other))
        val before = f.rows.state()
        val auditCount = auditCount(f)
        val first = f.search()
        assertEquals(200, first.status)
        val cursor = checkNotNull(f.cursor(first))
        val position = f.cursors.decode(cursor, f.ordinary.userId, ComplaintAdminSearchQuery(f.scope))
        assertEquals(f.itemIds(first).last(), position.id)
        assertEquals(f.ordinary.cutoff, position.updatedAt)
        val tail = f.search("""{"dataScopeId":"${f.scope.id}","cursor":"$cursor"}""")
        assertEquals(200, tail.status)
        assertEquals(50, f.itemIds(first).size)
        assertEquals(1, f.itemIds(tail).size)
        assertEquals(ids.sortedByDescending(UUID::toString), f.itemIds(first) + f.itemIds(tail))
        assertNull(f.cursor(tail))
        assertNull(first.getHeader("ETag"))
        assertNull(tail.getHeader("ETag"))
        val pageRows = (f.json(first)["items"].toList() + f.json(tail)["items"].toList()).associateBy { it["id"].asText() }
        val references = f.observer.queryForList("SELECT owner_reference::text FROM app_installations WHERE data_scope_id = ?", String::class.java, f.scope.id).toSet()
        assertEquals(2, references.size)
        for (id in ids) {
            val response = object : MockHttpServletResponse() {
                override fun getOutputStream(): ServletOutputStream {
                    f.rows.assertReleased()
                    return super.getOutputStream()
                }
            }
            f.handler.handleRequest(adminReadDetailRequest(f.scope, id, f.token), response)
            assertEquals(200, response.status)
            assertEquals(response.contentAsByteArray.size.toString(), response.getHeader("Content-Length"))
            assertEquals(pageRows.getValue(id.toString()), f.json(response))
            val row = f.json(response)
            if (id == notice) {
                assertNull(response.getHeader("ETag"))
                assertTrue(row["ownerReference"].isNull)
                assertFalse(row.has("actionTag") || row.has("body") || row.has("closureActorId"))
            } else {
                assertTrue(row["ownerReference"].asText() in references)
                assertTrue(row["version"].isIntegralNumber)
                assertEquals(row["actionTag"].asText(), response.getHeader("ETag"))
                assertFalse(response.contentAsString.contains(f.rows.actor.id.toString()))
                assertFalse(response.contentAsString.contains(other.id.toString()))
            }
        }
        assertEquals(9_007_199_254_740_993L, pageRows.getValue(low.toString())["version"].longValue())
        assertEquals(Long.MAX_VALUE, pageRows.getValue(high.toString())["version"].longValue())
        assertEquals(f.ordinary.userId.toString(), pageRows.getValue(high.toString())["closureActorId"].asText())
        assertEquals(noticeReply.toString(), pageRows.getValue(transitive.toString())["replyToId"].asText())
        assertEquals("admin.read.integration.notice", pageRows.getValue(transitive.toString())["noticeKey"].asText())
        assertTrue(pageRows.getValue(transitive.toString())["subject"].isNull)
        assertEquals(before, f.rows.state())
        assertEquals(auditCount, auditCount(f))
    }

    @Test
    fun `scope LIVE pending owner and pending resource are hidden while transitive children survive erased content`() = withFixture { f ->
        val other = f.rows.enrolledSession().installation
        val parent = f.rows.content(body = "Synthetic erased parent sentinel")
        val child = f.rows.content(parent = parent)
        val grandchild = f.rows.content(owner = other, parent = child)
        val pendingResource = f.rows.content(resourceState = "DELETION_PENDING")
        val liveNotice = f.rows.notice(ComplaintDataScope.LIVE)
        val foreignScope = ComplaintDataScope.of(UUID.randomUUID())
        val foreignNotice = f.rows.notice(foreignScope)
        f.rows.eraseParent(parent)
        val before = f.rows.state()
        val response = f.search()
        assertEquals(200, response.status)
        assertEquals(setOf(child, grandchild), f.itemIds(response).toSet())
        for (id in listOf(parent, pendingResource, liveNotice, foreignNotice, UUID.randomUUID())) {
            f.assertProblem(f.detail(id), 404, "NOT_FOUND")
        }
        assertFalse(response.contentAsString.contains("Synthetic erased parent sentinel"))
        assertEquals(before, f.rows.state())
        for ((change, restore) in listOf(
            "UPDATE app_installations SET state = 'DELETION_PENDING' WHERE id = ?" to "UPDATE app_installations SET state = 'ACTIVE' WHERE id = ?",
            "UPDATE complaint_installation_ids SET state = 'DELETION_PENDING' WHERE id = ?" to "UPDATE complaint_installation_ids SET state = 'ACTIVE' WHERE id = ?",
            "UPDATE complaint_installation_ids SET state = 'RETIRED', terminal_at = now() WHERE id = ?" to "UPDATE complaint_installation_ids SET state = 'ACTIVE', terminal_at = NULL WHERE id = ?",
        )) {
            assertEquals(1, f.observer.update(change, other.id))
            try {
                f.assertProblem(f.detail(grandchild), 404, "NOT_FOUND")
                assertEquals(listOf(child), f.itemIds(f.search()))
            } finally {
                assertEquals(1, f.observer.update(restore, other.id))
            }
        }
        assertEquals(setOf(child, grandchild), f.itemIds(f.search()).toSet())
    }

    @Test
    fun `actual qualified normal decoder rejects family signature issuer audience type and raw generation violations`() = withFixture { f ->
        val id = f.rows.content()
        val before = f.rows.state()
        val tokens = listOf(
            null, "not-a-token", f.rows.token.value, JwtTestSupport.tamperSignature(f.token),
            JwtTestSupport.mint(f.ordinary.userId, issuer = "wrong-issuer"), JwtTestSupport.mint(f.ordinary.userId, audience = "wrong-audience"),
            JwtTestSupport.mint(f.ordinary.userId, keyId = "wrong-key"),
            JwtTestSupport.mint(f.ordinary.userId, expiresAt = Instant.now().minusSeconds(600)),
            JwtTestSupport.mint(f.ordinary.userId, credentialVersion = 0), JwtTestSupport.mint(f.ordinary.userId, credentialVersion = 0.0),
            JwtTestSupport.mint(f.ordinary.userId, credentialVersion = null), JwtTestSupport.mint(f.ordinary.userId, includeCredentialVersion = false),
            JwtTestSupport.mint(f.ordinary.userId, credentialVersion = "00"), JwtTestSupport.mint(f.ordinary.userId, credentialVersion = "1"),
            JwtTestSupport.mint(f.ordinary.userId, credentialVersion = "9223372036854775808"),
            altered(f.token, header = JWSHeader.Builder(JWSAlgorithm.HS256).keyID(JwtService.KEY_ID).type(JOSEObjectType("installation+jwt")).build()),
            altered(f.token) { it.remove("exp") },
            altered(f.token) { it["nbf"] = Instant.now().plusSeconds(600).epochSecond },
            altered(f.token) { it["sub"] = f.ordinary.userId.toString().uppercase() },
        )
        for (token in tokens) {
            f.assertProblem(f.detail(id, token), 401, "UNAUTHORIZED")
        }
        assertEquals(200, f.detail(id).status)
        assertEquals(before, f.rows.state())
        assertTrue(f.responses.isOpen())
    }

    @Test
    fun `bounded malformed signed-token envelopes reject before invoking the actual decoder`() = withFixture { f ->
        val parts = f.token.split('.')
        val badHeader = """{"alg":"HS256","alg":"HS256","kid":"${JwtService.KEY_ID}"}"""
        val duplicate = Base64.getUrlEncoder().withoutPadding().encodeToString(badHeader.toByteArray()) + ".${parts[1]}.${parts[2]}"
        val tokens = listOf(duplicate, "a".repeat(4097), "${parts[0]}=.${parts[1]}.${parts[2]}", "${parts[0]}.${parts[1]}.AA", f.rows.token.value)
        for (token in tokens) {
            val calls = f.decoderCalls
            val response = f.search(bearer = token)
            assertTrue(response.status in setOf(400, 401))
            assertEquals(calls, f.decoderCalls)
        }
    }

    @Test
    fun `DB role enabled and credential generation remain authoritative over diagnostic ADMIN and USER claims`() = withFixture { f ->
        val id = f.rows.content()
        val userClaim = JwtTestSupport.mint(f.ordinary.userId, role = "USER")
        assertEquals(200, f.detail(id, userClaim).status)
        assertEquals(1, f.observer.update("UPDATE users SET role = 'USER' WHERE id = ?", f.ordinary.userId))
        f.assertProblem(f.detail(id), 403, "FORBIDDEN")
        f.assertProblem(f.search(), 403, "FORBIDDEN")
        assertEquals(1, f.observer.update("UPDATE users SET role = 'ADMIN', enabled = false WHERE id = ?", f.ordinary.userId))
        f.assertProblem(f.detail(id), 401, "UNAUTHORIZED")
        assertEquals(1, f.observer.update("UPDATE users SET enabled = true, credential_version = 1 WHERE id = ?", f.ordinary.userId))
        f.assertProblem(f.detail(id), 401, "UNAUTHORIZED")
        assertEquals(200, f.detail(id, f.userJwt.signer.issue(f.user()).value).status)
        f.assertProblem(f.detail(id, JwtTestSupport.mint(UUID.randomUUID(), role = "ADMIN")), 401, "UNAUTHORIZED")
    }

    @Test
    fun `released authentication cannot cache role enabled or generation for any data query including empty matches`() = withFixture { f ->
        val id = f.rows.content()
        val changes = listOf(
            Triple("role = 'USER'", "role = 'ADMIN'", ComplaintAdminReadFailure.FORBIDDEN),
            Triple("enabled = false", "enabled = true", ComplaintAdminReadFailure.UNAUTHORIZED),
            Triple("credential_version = 1", "credential_version = 0", ComplaintAdminReadFailure.UNAUTHORIZED),
        )
        for ((change, restore, failure) in changes) {
            val queries = listOf(ComplaintAdminSearchQuery(f.scope, text = "no-such-term"), ComplaintAdminDetailQuery(f.scope, id), ComplaintAdminStatsQuery(f.scope))
            for (query in queries) {
                f.ingress.withIngress(adminReadSearchRequest(f.scope, f.token)) { context ->
                    val authenticated = f.reader.authenticate(context, f.token, query)
                    f.rows.assertReleased()
                    assertEquals(1, f.observer.update("UPDATE users SET $change WHERE id = ?", f.ordinary.userId))
                    try {
                        val before = f.rows.state()
                        refused(failure) { f.reader.read(context, authenticated) }
                        assertEquals(before, f.rows.state())
                    } finally {
                        assertEquals(1, f.observer.update("UPDATE users SET $restore WHERE id = ?", f.ordinary.userId))
                    }
                }
            }
        }
        f.ingress.withIngress(adminReadSearchRequest(f.scope, f.token)) { context ->
            val authenticated = f.reader.authenticate(context, f.token, ComplaintAdminSearchQuery(f.scope))
            f.rows.assertReleased()
            f.run.terminalState("SEALED")
            refused(ComplaintAdminReadFailure.NOT_FOUND) { f.reader.read(context, authenticated) }
        }
        f.assertProblem(f.detail(id), 404, "NOT_FOUND")
    }

    @Test
    fun `data query reads current target and reservation snapshot after released preflight rather than cached content`() = withFixture { f ->
        val id = f.rows.content()
        val request = adminReadDetailRequest(f.scope, id, f.token)
        f.ingress.withIngress(request) { context ->
            val authenticated = f.reader.authenticate(context, f.token, ComplaintAdminDetailQuery(f.scope, id))
            f.rows.assertReleased()
            request.requestURI = "/api/v1/admin/complaints/${UUID.randomUUID()}"
            assertEquals(1, f.observer.update("UPDATE complaints SET body = 'Changed synthetic content', version = 2 WHERE id = ?", id))
            val detail = f.reader.read(context, authenticated) as ComplaintAdminReadResult.Detail
            val item = detail.item as ComplaintAdminItem.Content
            assertEquals(id, item.id)
            assertEquals(2L, item.version)
            assertEquals("Changed synthetic content", item.content.body)
        }
        f.ingress.withIngress(adminReadSearchRequest(f.scope, f.token)) { context ->
            val authenticated = f.reader.authenticate(context, f.token, ComplaintAdminDetailQuery(f.scope, id))
            f.rows.assertReleased()
            assertEquals(1, f.observer.update("UPDATE complaint_resource_ids SET state = 'DELETION_PENDING' WHERE id = ?", id))
            refused(ComplaintAdminReadFailure.NOT_FOUND) { f.reader.read(context, authenticated) }
        }
        f.assertProblem(f.detail(id), 404, "NOT_FOUND")
    }

    @Test
    fun `cursor validation precedes SQL but a signed matching cursor is never current ADMIN authority`() = withFixture { f ->
        val query = ComplaintAdminSearchQuery(f.scope, limit = 1)
        val cursor = f.cursors.encode(f.ordinary.userId, query, ComplaintAdminReadPosition(f.ordinary.cutoff, UUID.randomUUID()))
        val body = """{"dataScopeId":"${f.scope.id}","limit":1,"cursor":"$cursor"}"""
        withUnavailableSql(f) {
            f.assertProblem(f.search(body), 503, "SERVICE_UNAVAILABLE")
            f.assertProblem(f.search(body.replace(cursor, JwtTestSupport.tamperSignature(cursor))), 400, "INVALID_CURSOR")
            f.assertProblem(f.search(body, JwtTestSupport.tamperSignature(f.token)), 401, "UNAUTHORIZED")
            f.assertProblem(f.search("""{"dataScopeId":"${UUID.randomUUID()}"}"""), 503, "SERVICE_UNAVAILABLE")
            f.assertProblem(f.search("""{"dataScopeId":"${f.scope.id}","limit":0}"""), 400, "VALIDATION_FAILED")
            assertEquals(0L, f.ordinary.ownedPool.lifecycle.activeAcquisitions())
        }
        f.assertProblem(f.search("""{"dataScopeId":"${UUID.randomUUID()}"}"""), 404, "NOT_FOUND")
        assertEquals(1, f.observer.update("UPDATE users SET role = 'USER' WHERE id = ?", f.ordinary.userId))
        f.assertProblem(f.search(body), 403, "FORBIDDEN")
        f.assertProblem(f.search("""{"dataScopeId":"${UUID.randomUUID()}"}"""), 403, "FORBIDDEN")
    }

    @Test
    fun `only the original adapter thread ingress and one use can consume real ADMIN authentication`() = withFixture { f ->
        val id = f.rows.content()
        f.ingress.withIngress(adminReadDetailRequest(f.scope, id, f.token)) { context ->
            val authenticated = f.reader.authenticate(context, f.token, ComplaintAdminDetailQuery(f.scope, id))
            f.rows.assertReleased()
            refused(ComplaintAdminReadFailure.UNAUTHORIZED) { f.reader.read(context, object : ComplaintAdminReadAuthentication {}) }
            refused(ComplaintAdminReadFailure.UNAVAILABLE) { f.reader.read(object : ComplaintAdminReadRequestContext {}, authenticated) }
            refused(ComplaintAdminReadFailure.UNAUTHORIZED) { f.reader.read(ComplaintIngressContext(), authenticated) }
            refused(ComplaintAdminReadFailure.UNAUTHORIZED) { f.newReader().read(context, authenticated) }
            OwnedCallerTestScope().use { callers ->
                callers.launch { refused(ComplaintAdminReadFailure.UNAUTHORIZED) { f.reader.read(context, authenticated) } }.value()
            }
            assertEquals(id, (f.reader.read(context, authenticated) as ComplaintAdminReadResult.Detail).item.id)
            refused(ComplaintAdminReadFailure.UNAUTHORIZED) { f.reader.read(context, authenticated) }
        }
        val escaped = f.ingress.withIngress(adminReadSearchRequest(f.scope, f.token)) { context ->
            context to f.reader.authenticate(context, f.token, ComplaintAdminSearchQuery(f.scope))
        }
        assertThrows<ComplaintAdmissionRejected> { f.reader.read(escaped.first, escaped.second) }
    }

    @Test
    fun `original five second handoff age is not renewed before data phase admission`() = withFixture { f ->
        f.ingress.withIngress(adminReadSearchRequest(f.scope, f.token)) { context ->
            val authenticated = f.reader.authenticate(context, f.token, ComplaintAdminSearchQuery(f.scope))
            f.rows.assertReleased()
            val identity = checkNotNull(lifecycleField(authenticated, "identity"))
            // Negative-only age fault on a genuinely authenticated original; never manufactures a grant or sleeps a test worker.
            identity.javaClass.getDeclaredField("startedAt").apply { isAccessible = true }.setLong(identity, System.nanoTime() - 5_000_000_000L)
            val events = readEvents(f)
            refused(ComplaintAdminReadFailure.UNAVAILABLE) { f.reader.read(context, authenticated) }
            assertEquals(events, readEvents(f))
        }
    }

    @Test
    fun `actual authentication precedes lowerable combined read quota and Disabled stops before decoder or SQL`() = withFixture { f ->
        val id = f.rows.content()
        val lower = ComplaintAdminReadFixture(f.rows, adminReadTestIngress(adminPolicy = ComplaintAdminReadAdmissionPolicy.Bounded(3)))
        assertEquals(1, f.observer.update("UPDATE users SET role = 'USER' WHERE id = ?", f.ordinary.userId))
        lower.assertProblem(lower.search(), 403, "FORBIDDEN")
        assertEquals(0, readEvents(lower))
        assertEquals(1, f.observer.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", f.ordinary.userId))
        assertEquals(200, lower.search().status)
        assertEquals(200, lower.detail(id).status)
        assertEquals(200, lower.stats().status)
        val limited = lower.search()
        lower.assertProblem(limited, 429, "RATE_LIMITED")
        assertTrue(checkNotNull(limited.getHeader("Retry-After")).toLong() in 1..60)
        assertEquals(3, readEvents(lower))
        val disabled = ComplaintAdminReadFixture(f.rows, adminReadTestIngress(adminPolicy = ComplaintAdminReadAdmissionPolicy.Disabled))
        disabled.assertProblem(disabled.search(), 503, "SERVICE_UNAVAILABLE")
        disabled.assertProblem(disabled.stats(), 503, "SERVICE_UNAVAILABLE")
        assertEquals(0, disabled.decoderCalls)
        assertEquals(0, readEvents(disabled))
        assertEquals(0L, f.ordinary.ownedPool.lifecycle.activeAcquisitions())
    }

    @Test
    fun `concrete operations require exact phase resource and retained operation not arbitrary caller completion`() = withFixture { f ->
        val id = f.rows.content()
        assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, assertThrows<PersistencePhaseException> { f.store.detail(f.identity(), id) }.code)
        assertEquals(PersistencePhaseFailureCode.ENTRY_REFUSED, assertThrows<PersistencePhaseException> { f.store.stats(f.identity()) }.code)
        f.withPhase(enter = f.ordinary.ownership::enterComplaintOwnerHistoryPage) { phase ->
            assertThrows<PersistencePhaseException> { f.store.search(f.identity(), ComplaintAdminSearchQuery(f.scope), null) }
            assertThrows<PersistencePhaseException> { phase.commit() }
        }
        f.withPhase { phase ->
            val foreign = JdbcComplaintAdminReadStore(f.ordinary.foreignTemplate(), f.scope)
            assertThrows<PersistencePhaseException> { foreign.search(f.identity(), ComplaintAdminSearchQuery(f.scope), null) }
            assertThrows<PersistencePhaseException> { phase.commit() }
        }
        f.withPhase { phase ->
            phase.adminRead.requireSearch(f.ordinary.jdbc)
            assertFalse(phase.adminRead.completed())
            assertThrows<PersistencePhaseException> { phase.commit() }
        }
        val repeated = f.withPhase { phase ->
            val operation = f.store.search(f.identity(), ComplaintAdminSearchQuery(f.scope), null)
            assertThrows<PersistencePhaseException> { f.store.search(f.identity(), ComplaintAdminSearchQuery(f.scope), null) }
            assertThrows<PersistencePhaseException> { phase.commit() }
            operation
        }
        assertEquals(PersistenceDatabaseOutcome.ROLLED_BACK, assertThrows<PersistencePhaseException> { repeated.result }.databaseOutcome)
        f.withPhase { phase ->
            assertThrows<PersistencePhaseException> { f.store.detail(f.identity(), id) }
            assertThrows<PersistencePhaseException> { phase.commit() }
        }
        f.withPhase { phase ->
            assertThrows<PersistencePhaseException> { f.store.stats(f.identity()) }
            assertThrows<PersistencePhaseException> { phase.commit() }
        }
        assertThrows<IllegalArgumentException> { JdbcComplaintAdminReadStore(f.ordinary.jdbc, ComplaintDataScope.LIVE) }
    }

    @Test
    fun `all four ordinary observations require actual commit cleanup and original release before successful results`() = withFixture { f ->
        val id = f.rows.content()
        val before = f.rows.state()
        for ((enter, read) in phases(f, id)) {
            val operation = f.withPhase(enter) { phase ->
                assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                assertEquals("on", f.ordinary.jdbc.queryForObject("SHOW transaction_read_only", String::class.java))
                assertEquals("read committed", f.ordinary.jdbc.queryForObject("SHOW transaction_isolation", String::class.java))
                val retained = read()
                assertTrue(phase.adminRead.completed())
                val early = assertThrows<PersistencePhaseException> { retained.result }
                assertEquals(PersistenceDatabaseOutcome.NONE, early.databaseOutcome)
                assertFalse(early.cleanupProven)
                phase.commit()
                val beforeRelease = assertThrows<PersistencePhaseException> { retained.result }
                assertEquals(PersistenceDatabaseOutcome.COMMITTED, beforeRelease.databaseOutcome)
                assertFalse(beforeRelease.cleanupProven)
                retained
            }
            assertEquals(ComplaintAdminReadVerdict.ALLOWED, operation.result.verdict)
            OwnedCallerTestScope().use { callers ->
                val failure = callers.launch { assertThrows<PersistencePhaseException> { operation.result } }.value()
                assertTrue(failure.cleanupProven)
            }
            assertThrows<PersistencePhaseException> { operation.result }
        }
        assertEquals(before, f.rows.state())
    }

    @Test
    fun `real SQL rollback and committed completion tail failure never publish retained Admin success`() = withFixture { f ->
        val id = f.rows.content()
        val before = f.rows.state()
        for ((enter, read) in phases(f, id)) {
            for (sqlFailure in listOf(true, false)) {
                val operation = f.withPhase(enter) { phase ->
                    val retained = read()
                    if (sqlFailure) {
                        phase.recordFailure(assertThrows<DataAccessException> { f.ordinary.jdbc.execute("SELECT 1 / 0") })
                    } else {
                        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                            override fun afterCommit(): Unit = throw SyntheticInstallationEnrollmentFailure()
                        })
                    }
                    phase.recordFailure(checkNotNull(runCatching { phase.commit() }.exceptionOrNull()))
                    retained
                }
                val failure = assertThrows<PersistencePhaseException> { operation.result }
                assertEquals(if (sqlFailure) PersistenceDatabaseOutcome.ROLLED_BACK else PersistenceDatabaseOutcome.COMMITTED, failure.databaseOutcome)
                assertTrue(failure.cleanupProven)
                assertNull(failure.cause)
                assertTrue(failure.suppressed.isEmpty())
            }
        }
        assertEquals(before, f.rows.state())
    }

    @Test
    fun `new Admin phases request and actually use READ COMMITTED under a real REPEATABLE READ role default`() {
        var control: JdbcTemplate? = null
        withFixture { f -> control = f.observer }
        val observer = checkNotNull(control)
        observer.execute("ALTER ROLE ${PgLifecycleDatabaseSettings.CANDIDATE} SET default_transaction_isolation = 'repeatable read'")
        try {
            // New existing-owned pool sessions really inherit the role default; no mocked metadata or alternate driver owner.
            withFixture { f ->
                val id = f.rows.content()
                for ((enter, read) in phases(f, id)) {
                    val operation = f.withPhase(enter) { phase ->
                        // pgjdbc setTransactionIsolation changes the session default. reset_val
                        // retains the startup role setting, proving the non-default role was real.
                        assertEquals("repeatable read", f.ordinary.jdbc.queryForObject(
                            "SELECT reset_val FROM pg_settings WHERE name = 'default_transaction_isolation'", String::class.java,
                        ))
                        assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                        assertEquals("read committed", f.ordinary.jdbc.queryForObject("SHOW transaction_isolation", String::class.java))
                        val retained = read()
                        phase.commit()
                        retained
                    }
                    assertEquals(ComplaintAdminReadVerdict.ALLOWED, operation.result.verdict)
                }
            }
        } finally {
            observer.execute("ALTER ROLE ${PgLifecycleDatabaseSettings.CANDIDATE} RESET default_transaction_isolation")
        }
    }

    @Test
    fun `ordinary Admin reads acquire neither maintenance nor epoch fence and cannot issue DML or audit`() = withFixture { f ->
        val id = f.rows.content()
        val before = f.rows.state()
        val audits = auditCount(f)
        checkNotNull(f.observer.dataSource).connection.use { blocker ->
            blocker.autoCommit = false
            try {
                blocker.createStatement().use {
                    it.execute("SELECT pg_advisory_xact_lock(hashtextextended('complaint-maintenance-v1', 0))")
                    it.execute("SELECT pg_advisory_xact_lock(hashtextextended('complaint-journal-epoch', 0))")
                }
                assertEquals(200, f.search().status)
                assertEquals(200, f.detail(id).status)
                assertEquals(200, f.stats().status)
                for ((enter, read) in phases(f, id)) {
                    val operation = f.withPhase(enter) { phase ->
                        val pid = f.ordinary.jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!
                        assertEquals(0L, f.observer.queryForObject("SELECT count(*) FROM pg_locks WHERE pid = ? AND locktype = 'advisory'", Long::class.java, pid))
                        val retained = read()
                        phase.commit()
                        retained
                    }
                    assertEquals(ComplaintAdminReadVerdict.ALLOWED, operation.result.verdict)
                }
            } finally {
                blocker.rollback()
            }
        }
        for ((enter, read) in phases(f, id)) {
            f.withPhase(enter) { phase ->
                read()
                phase.recordFailure(assertThrows<DataAccessException> { f.ordinary.jdbc.update("UPDATE complaints SET version = version WHERE id = ?", id) })
                assertThrows<PersistencePhaseException> { phase.commit() }
            }
        }
        assertEquals(before, f.rows.state())
        assertEquals(audits, auditCount(f))
    }

    @Test
    fun `unchanged sourceOnly admission refuses all four Admin paths before a resource acquisition`() = withFixture { f ->
        val admission = OrdinaryPersistenceAdmission.sourceOnly(2)
        val manager = GuardedJpaTransactionManager(f.ordinary.entityManagerFactory, f.ordinary.pool)
        val sourceOnly = PersistencePhaseOwnership(admission, manager)
        for (enter in listOf(
            sourceOnly::enterComplaintAdminReadAuthentication, sourceOnly::enterComplaintAdminSearch,
            sourceOnly::enterComplaintAdminDetail, sourceOnly::enterComplaintAdminStats,
        )) {
            val failure = assertThrows<PersistencePhaseException> { enter() }
            assertEquals(PersistenceDatabaseOutcome.NONE, failure.databaseOutcome)
            assertTrue(failure.cleanupProven)
            assertEquals(0, admission.activeOwners())
        }
        f.rows.assertReleased()
        val paths = setOf(
            PersistencePhasePath.COMPLAINT_ADMIN_READ_AUTHENTICATION, PersistencePhasePath.COMPLAINT_ADMIN_SEARCH,
            PersistencePhasePath.COMPLAINT_ADMIN_DETAIL, PersistencePhasePath.COMPLAINT_ADMIN_STATS,
        )
        assertTrue(paths.all { it.readOnly && !it.source && !it.complaintMaintenanceWriter })
    }

    @Test
    fun `bounded invalid stored row closes shared responses without a partial page or detail`() = withFixture { f ->
        val good = f.rows.content()
        val corrupt = f.rows.content()
        val definition = f.observer.queryForObject(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'complaints'::regclass AND conname = 'chk_complaints_text'",
            String::class.java,
        )!!
        f.observer.execute("ALTER TABLE complaints DROP CONSTRAINT chk_complaints_text")
        try {
            assertEquals(1, f.observer.update("UPDATE complaints SET app_version = ? WHERE id = ?", "a".repeat(4096), corrupt))
            val before = f.rows.state()
            val response = f.search()
            f.assertProblem(response, 500, "INTERNAL_ERROR")
            assertFalse(response.contentAsString.contains(good.toString()))
            assertFalse(response.contentAsString.contains(corrupt.toString()))
            assertFalse(response.contentAsString.contains("items"))
            assertNull(response.getHeader("ETag"))
            assertFalse(f.responses.isOpen())
            assertFalse(f.rows.responses.isOpen())
            f.assertProblem(f.detail(good), 503, "SERVICE_UNAVAILABLE")
            assertEquals(before, f.rows.state())
        } finally {
            f.observer.update("UPDATE complaints SET app_version = NULL WHERE id = ?", corrupt)
            f.observer.execute("ALTER TABLE complaints ADD CONSTRAINT chk_complaints_text $definition")
        }
    }

    @Test
    fun `eight real stale-credential401 deliveries keep acquired shared slots while ninth avoids decoder and SQL`() = withFixture { f ->
        val stale = JwtTestSupport.mint(f.ordinary.userId, role = "ADMIN", credentialVersion = "1")
        val before = f.rows.state()
        OwnedCallerTestScope().use { callers ->
            val gates = List(8) { callers.gate() }
            val calls = gates.map { gate ->
                callers.launch {
                    val response = object : MockHttpServletResponse() {
                        override fun getOutputStream(): ServletOutputStream {
                            f.rows.assertReleased()
                            gate.hold()
                            return super.getOutputStream()
                        }
                    }
                    f.handler.handleRequest(adminReadSearchRequest(f.scope, stale), response)
                    f.assertProblem(response, 401, "UNAUTHORIZED")
                    true
                }.also { gate.awaitEntered() } // Serialize SQL, not delivery: each genuine preflight releases before the next begins.
            }
            try {
                assertEquals(8, f.decoderCalls)
                assertNull(f.responses.acquire())
                f.assertProblem(f.search(), 503, "SERVICE_UNAVAILABLE")
                assertEquals(8, f.decoderCalls)
                assertEquals(0, readEvents(f))
                assertEquals(0L, f.ordinary.ownedPool.lifecycle.activeAcquisitions())
            } finally {
                gates.forEach { it.release() }
                calls.forEach { it.value() }
            }
        }
        assertEquals(before, f.rows.state())
        assertEquals(200, f.search().status)
    }

    @Test
    fun `actual new SQL has bounded scoped prepared results and plans for custom generic and automatic sessions`() = withFixture { f ->
        verifyComplaintAdminReadSqlPlans(f)
    }

    private fun phases(f: ComplaintAdminReadFixture, id: UUID): List<Pair<() -> PersistencePhaseContext, () -> ComplaintAdminReadOperation>> = listOf(
        f.ordinary.ownership::enterComplaintAdminReadAuthentication to { f.store.authenticate(f.identity()) },
        f.ordinary.ownership::enterComplaintAdminSearch to { f.store.search(f.identity(), ComplaintAdminSearchQuery(f.scope), null) },
        f.ordinary.ownership::enterComplaintAdminDetail to { f.store.detail(f.identity(), id) },
        f.ordinary.ownership::enterComplaintAdminStats to { f.store.stats(f.identity()) },
    )

    private fun refused(failure: ComplaintAdminReadFailure, work: () -> Any): ComplaintAdminReadRejected =
        assertThrows<ComplaintAdminReadRejected> { work() }.also {
            assertEquals(failure, it.failure)
            assertNull(it.cause)
            assertTrue(it.suppressed.isEmpty())
            assertEquals("Complaint Admin read refused.", it.message)
        }

    private fun readEvents(f: ComplaintAdminReadFixture): Int = lifecycleField(checkNotNull(lifecycleField(f.ingress, "ownerReads")), "events") as Int

    private fun auditCount(f: ComplaintAdminReadFixture): Long = f.observer.queryForObject("SELECT count(*) FROM audit_log", Long::class.java)!!

    private fun withUnavailableSql(f: ComplaintAdminReadFixture, work: () -> Unit) {
        OwnedCallerTestScope().use { callers ->
            val gate = callers.gate()
            val held = callers.launch {
                val permit = checkNotNull(f.ordinary.admission.tryComplaintBoundary())
                try {
                    gate.hold()
                } finally {
                    assertTrue(permit.releaseAfterQuiescence())
                }
                true
            }
            gate.awaitEntered()
            try {
                work()
            } finally {
                gate.release()
                held.value()
            }
        }
    }

    private fun altered(token: String, header: JWSHeader? = null, edit: (MutableMap<String, Any?>) -> Unit = {}): String {
        val original = JWSObject.parse(token)
        val claims = linkedMapOf<String, Any?>().apply { putAll(original.payload.toJSONObject()) }
        edit(claims)
        val signed = JWSObject(header ?: original.header, Payload(mapper.writeValueAsString(claims)))
        signed.sign(MACSigner(historyTestUserKey()))
        return signed.serialize()
    }

    private fun withFixture(work: (ComplaintAdminReadFixture) -> Unit) {
        withOrdinaryComplaintInstallationEnrollment(database.value) { base ->
            OrdinaryComplaintTestInstallationFixture(base).use { run ->
                ComplaintOwnerHistoryFixture(base, run).use { rows -> work(ComplaintAdminReadFixture(rows)) }
            }
        }
    }
}
