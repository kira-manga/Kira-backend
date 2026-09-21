package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.CounterSnapshot
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.InitialIdentityExchangeFixture
import me.manga.kira.backend.complaint.catalog.TestActiveOrdinaryRawHttpV1
import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainFixtureInputsV1
import me.manga.kira.backend.complaint.catalog.withTestActiveFirstCut
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintOwnerCreateInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditPrecondition
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditRequest
import me.manga.kira.backend.complaint.domain.ComplaintOwnerEditStatusQuery
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerEditAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerEditCandidate
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerEditStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerEditPhaseExecutor
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReceipt
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReplyInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerStatusQuery
import me.manga.kira.backend.complaint.domain.ComplaintReplyFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintReplyRequest
import me.manga.kira.backend.complaint.domain.ComplaintReportFingerprint
import me.manga.kira.backend.complaint.domain.ComplaintReportIdentity
import me.manga.kira.backend.complaint.domain.ComplaintReportMetadataInput
import me.manga.kira.backend.complaint.domain.ComplaintReportRequest
import me.manga.kira.backend.complaint.domain.ComplaintReportRequestResult
import me.manga.kira.backend.complaint.domain.ComplaintType
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateAdapter
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateCandidate
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerOperationIdentity
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerReplyCandidate
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerCreateStore
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerCreatePhaseExecutor
import me.manga.kira.backend.security.InstallationJwtCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.mock.web.MockHttpServletRequest
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal val REGISTERED_CREATE = PersistencePhasePath.COMPLAINT_OWNER_CREATE
internal val REGISTERED_REPLY = PersistencePhasePath.COMPLAINT_OWNER_REPLY
internal val REGISTERED_EDIT = PersistencePhasePath.COMPLAINT_OWNER_EDIT

/**
 * Genuine global G1/full-D/request/capture precedes TEST activation; no global seal/health claim.
 * Thin nesting of existing producers. The SAME enrollment ordinary owner/template stays
 * alive through capture, seal, both checkpoint passes and CREATE. Reply cases explicitly select
 * their born-with sibling on that same graph. No SQL-seeded checkpoint,
 * accepted DTO, current/healthy stub or copied registration is supplied to the registered factory.
 */
internal fun withRegisteredInitialCheckpointCreate(tls: VersionBoundPersistenceConnectedFixture,
    completeCheckpoint: Boolean = true, shortFreshness: Boolean = false,
    terminalHistory: TestOrdinaryDrainFixtureInputsV1? = null,
    initialCheckpointCreate: TestInitialCheckpointCreateInputV1 = TestInitialCheckpointCreateInputV1(1, VersionBoundTestInitialCheckpointCreateV1.PROFILE),
    action: (TestRegisteredInitialCheckpointCreateFixtureV1) -> Unit) {
    val ordinary = TestActiveOrdinaryRawFixtureV1()
    val raw = TestActiveInitialCheckpointRawFixtureV1()
    val factories = ordinary.factories.let { TestActiveOrdinaryRawHttpV1(it.sts, it.kms, it.s3, raw.input,
        initialCheckpointCreate = initialCheckpointCreate,
        shortInitialCheckpointFreshness = shortFreshness) }
    withTestActiveFirstCut(tls, ordinaryRawHttp = factories, terminalHistory = terminalHistory, globalScanBeforeActivation = true) { first ->
        first.initial.withExchange { exchange ->
            val candidate = first.initial.candidate()
            val token = exchange.enroll(candidate).session.accessToken
            exchange.assertReleased()
            val captured = first.capture()
            first.awaitNativeReclaimed()
            TestActiveOrdinarySealFixtureV1(first, captured, ordinary).use { sealer ->
                val verified = sealer.seal()
                sealer.assertReleased()
                awaitInitialCheckpointLeaseExpiry(sealer.observer, sealer.scope)
                TestActiveInitialCheckpointFixtureV1(sealer, verified, raw).use { checkpoint ->
                    TestRegisteredInitialCheckpointCreateFixtureV1(checkpoint, exchange, candidate.installation, token).use { fixture ->
                        if (completeCheckpoint) {
                            checkpoint.checkpoint() // Intentionally discard Completed; it is not a factory or CREATE argument.
                            checkpoint.assertReleased()
                        }
                        first.p.f.rows.globalPredecessor?.assertPreserved(first.observer)
                        action(fixture)
                    }
                }
            }
        }
    }
}

internal class TestRegisteredInitialCheckpointCreateFixtureV1(
    val checkpoint: TestActiveInitialCheckpointFixtureV1,
    val exchange: InitialIdentityExchangeFixture,
    val actor: ScopedInstallationId,
    val token: String,
) : AutoCloseable {
    val registration = checkpoint.registration
    val process = registration.process
    val jdbc = exchange.jdbc
    val observer = checkpoint.observer
    val scope = checkpoint.scope
    val ingress = process.consumers.ingressAdmission
    val jwt = InstallationJwtCodec(process.consumers.jwt.installationKeyRing, Clock.systemUTC())
    val store = JdbcComplaintOwnerCreateStore.registeredInitialCheckpoint(jdbc, exchange.service, exchange.ordinary.ownership,
        registration, checkpoint.assembly)
    val phases = ComplaintOwnerCreatePhaseExecutor(exchange.ordinary.ownership, store)
    val adapter = ComplaintOwnerCreateAdapter(actor.scope, jwt, phases, ingress)
    val replyStore by lazy { JdbcComplaintOwnerCreateStore.registeredInitialCheckpointWithReplies(jdbc, exchange.service, exchange.ordinary.ownership,
        registration, checkpoint.assembly) }
    val replyExecutor by lazy { ComplaintOwnerCreatePhaseExecutor(exchange.ordinary.ownership, replyStore) }
    private val replyAdapter by lazy { ComplaintOwnerCreateAdapter(actor.scope, jwt, replyExecutor, ingress) }

    val editStore by lazy { JdbcComplaintOwnerEditStore.registeredInitialCheckpoint(jdbc, exchange.service, exchange.ordinary.ownership,
        registration, checkpoint.assembly) }
    val editExecutor by lazy { ComplaintOwnerEditPhaseExecutor(exchange.ordinary.ownership, editStore) }
    private val editAdapter by lazy { ComplaintOwnerEditAdapter(actor.scope, jwt, editExecutor, ingress) }

    init {
        assertSame(jdbc.dataSource, process.pools.ordinary)
        assertEquals(actor, jwt.verify(token).installation)
        jdbc.calls.clear()
    }

    fun attempt(id: UUID = UUID.randomUUID(), key: UUID = UUID.randomUUID()): RegisteredInitialCreateAttemptV1 {
        val input = ComplaintOwnerCreateInput(id, key, ComplaintType.TECHNICAL, " Registered subject ", " Registered body\r\nline ",
            ComplaintReportMetadataInput(null, "fixture-os", "", ""))
        val identity = checkNotNull(ComplaintReportIdentity.checked(id.toString(), key.toString(), scope.toString()))
        val request = (ComplaintReportRequest.normalize(identity, input.type, input.subject, input.body, input.metadata)
            as ComplaintReportRequestResult.Accepted).request
        return RegisteredInitialCreateAttemptV1(input, ComplaintOwnerCreateCandidate.prepare(actor, request))
    }

    fun create(attempt: RegisteredInitialCreateAttemptV1): ComplaintOwnerReceipt =
        ingress.withIngress(request()) { adapter.create(it, token, attempt.input) }
    fun status(attempt: RegisteredInitialCreateAttemptV1): ComplaintOwnerReceipt = ingress.withIngress(request()) {
        adapter.status(it, token, ComplaintOwnerStatusQuery("OWNER_CREATE", attempt.input.key.toString(), attempt.input.id.toString(),
            ComplaintReportFingerprint.of(attempt.candidate.request).encoded))
    }
    fun reply(attempt: RegisteredInitialReplyAttemptV1): ComplaintOwnerReceipt =
        ingress.withIngress(request()) { replyAdapter.reply(it, token, attempt.input) }
    fun replyStatus(attempt: RegisteredInitialReplyAttemptV1): ComplaintOwnerReceipt = ingress.withIngress(request()) {
        replyAdapter.status(it, token, ComplaintOwnerStatusQuery("OWNER_REPLY", attempt.input.key.toString(),
            listOf(attempt.input.parentId.toString(), attempt.input.id.toString()), ComplaintReplyFingerprint.of(attempt.candidate.request).encoded))
    }
    fun assertApplied(receipt: ComplaintOwnerReceipt, attempt: RegisteredInitialReplyAttemptV1) {
        assertTrue(receipt is ComplaintOwnerReceipt.Applied)
        receipt as ComplaintOwnerReceipt.Applied
        assertEquals(attempt.input.id, receipt.id); assertEquals(1L, receipt.version)
    }
    fun edit(attempt: RegisteredInitialEditAttemptV1): ComplaintOwnerEditReceipt =
        ingress.withIngress(request()) { editAdapter.edit(it, token, attempt.input) }
    fun editStatus(attempt: RegisteredInitialEditAttemptV1): ComplaintOwnerEditReceipt = ingress.withIngress(request()) {
        editAdapter.status(it, token, ComplaintOwnerEditStatusQuery(attempt.input.key.toString(), listOf(attempt.input.targetId.toString()),
            ComplaintOwnerEditFingerprint.of(attempt.candidate.request).encoded))
    }
    fun assertApplied(receipt: ComplaintOwnerEditReceipt, attempt: RegisteredInitialEditAttemptV1) {
        assertTrue(receipt is ComplaintOwnerEditReceipt.Applied)
        receipt as ComplaintOwnerEditReceipt.Applied
        assertEquals(attempt.input.targetId, receipt.id); assertEquals(attempt.input.precondition.version + 1, receipt.version)
    }
    fun editSql(): List<String> = jdbc.calls.filter { it.first === REGISTERED_EDIT }.map { it.second }
    fun editPhases() = jdbc.observations.keys.filter { poolTestField<PersistencePhasePath>(it, "path") === REGISTERED_EDIT }

    fun notice(): UUID = checkNotNull(observer.queryForObject("SELECT id FROM complaints WHERE data_scope_id = ? AND ownership = 'SYSTEM' AND kind = 'NOTICE' ORDER BY id LIMIT 1",
        UUID::class.java, scope)) // The genuine activation's scoped notice, never a synthetic seed.
    fun identity(): ComplaintOwnerOperationIdentity = jwt.verify(token).let {
        ComplaintOwnerOperationIdentity(it.installation, it.credentialVersion, it.issuedAt, it.expiresAt)
    }
    fun request() = MockHttpServletRequest().apply { remoteAddr = "192.0.2.43" }

    fun counters() = checkpoint.counters()
    fun state(): Map<String, List<String>> = checkpoint.image().toMutableMap().also { image ->
        raw { connection ->
            for ((table, column) in listOf("complaints" to "data_scope_id", "complaint_resource_ids" to "data_scope_id",
                "audit_log" to "complaint_data_scope_id")) {
                connection.prepareStatement("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM $table t WHERE $column = ? ORDER BY to_jsonb(t)::text").use { statement ->
                    statement.queryTimeout = 1; statement.setObject(1, scope)
                    statement.executeQuery().use { rows -> image[table] = buildList { while (rows.next()) add(rows.getString(1)) } }
                }
            }
        }
    }
    fun providerCounts(): List<Int> = checkpoint.sealer.let { seal -> listOf(
        seal.native.sts.requests.size, seal.native.kms.requests.size, seal.native.requests.size, seal.ordinary.requestBudgets.size,
        checkpoint.raw.sts.requests.size, checkpoint.raw.kms.requests.size, checkpoint.raw.requests.size, seal.p.f.http.read.requests.size) }

    fun assertCharge(before: Map<String, CounterSnapshot>, amount: ComplaintCapacityVector) {
        val after = counters()
        assertEquals(before.keys, after.keys)
        for (counter in ComplaintCapacityCounter.entries) {
            val old = before.getValue(counter.storedName); val current = after.getValue(counter.storedName)
            if (amount[counter] == 0L && ComplaintCapacityCharges.OWNER_CREATE[counter] == 0L) assertEquals(old, current)
            else {
                assertEquals(old.preserved, current.preserved)
                assertEquals(old.free - amount[counter], current.free); assertEquals(old.actual + amount[counter], current.actual)
            }
        }
    }
    fun assertApplied(receipt: ComplaintOwnerReceipt, attempt: RegisteredInitialCreateAttemptV1) {
        assertTrue(receipt is ComplaintOwnerReceipt.Applied)
        receipt as ComplaintOwnerReceipt.Applied
        assertEquals(attempt.input.id, receipt.id); assertEquals(1L, receipt.version)
    }
    fun assertReleased() {
        exchange.assertReleased(); checkpoint.assertSqlReleased()
        assertNull(PersistencePhaseOwnership.current()); requireConnectionFree()
        assertEquals(0L, process.publicationLanes.activeOwners().totalOwners)
    }
    fun createSql(): List<String> = jdbc.calls.filter { it.first === REGISTERED_CREATE }.map { it.second }
    fun assertNoCounterSql() = assertFalse(createSql().any { "complaint_capacity_counters" in it })
    fun createPhases() = jdbc.observations.keys.filter { poolTestField<PersistencePhasePath>(it, "path") === REGISTERED_CREATE }
    fun replySql(): List<String> = jdbc.calls.filter { it.first === REGISTERED_REPLY }.map { it.second }
    fun replyPhases() = jdbc.observations.keys.filter { poolTestField<PersistencePhasePath>(it, "path") === REGISTERED_REPLY }

    /** Observation oracle only, sampled outside a request; old profiles must still select the initial query. */
    fun currentCheckpointSql(): String {
        requireConnectionFree()
        val profile = checkNotNull(process.initialCheckpointCreate).inventory().getValue("profile").jsonPrimitive.content
        return if (profile == VersionBoundTestInitialCheckpointCreateV1.RECURRENT_PROFILE &&
            (checkpoint.control().getValue("rotation_sequence") as Number).toLong() > 1L) {
            TestRegisteredRecurrentCheckpointCurrentSqlV1.current
        } else TestActiveInitialCheckpointSqlV1.currentForOwnerCreate
    }

    /** Independent observer only. Never enlisted into the original phase's Spring resource map. */
    fun <T> raw(action: (Connection) -> T): T = checkNotNull(observer.dataSource).connection.use(action)
    fun databaseNow(): Instant = raw { connection -> connection.createStatement().use { statement ->
        statement.queryTimeout = 1
        statement.executeQuery("SELECT clock_timestamp()").use { row -> assertTrue(row.next()); row.getTimestamp(1).toInstant() }
    } }

    override fun close() {
        jdbc.before = { _, _ -> }; jdbc.after = { _, _ -> }
        assertReleased()
        // Explicit disposable-scope teardown AFTER assertions, never product erasure/refund/proof.
        observer.update("DELETE FROM complaints WHERE data_scope_id = ?", scope)
        observer.update("DELETE FROM complaint_resource_ids WHERE data_scope_id = ?", scope)
        observer.update("DELETE FROM complaint_idempotency_receipts WHERE data_scope_id = ? AND actor_id = ?", scope, actor.id)
        observer.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND action IN ('COMPLAINT_CREATED', 'COMPLAINT_CONTENT_EDITED')", scope)
    }
}

internal class RegisteredInitialCreateAttemptV1(val input: ComplaintOwnerCreateInput, val candidate: ComplaintOwnerCreateCandidate)

/** Request data only; both HTTP and phase cases still authenticate and normalize in the existing product adapter. */
internal class RegisteredInitialReplyAttemptV1(val input: ComplaintOwnerReplyInput, val candidate: ComplaintOwnerReplyCandidate)

internal fun registeredReplyAttempt(actor: ScopedInstallationId, parent: UUID, id: UUID = UUID.randomUUID()): RegisteredInitialReplyAttemptV1 {
    val input = ComplaintOwnerReplyInput(parent, id, UUID.randomUUID(), " Registered reply\r\nline ", ComplaintReportMetadataInput(null, "fixture-os", "", ""))
    val identity = checkNotNull(ComplaintReportIdentity.checked(input.id.toString(), input.key.toString(), actor.scope.id.toString()))
    val normalized = ComplaintReplyRequest.normalize(identity, parent, input.body, input.metadata)
    return RegisteredInitialReplyAttemptV1(input, ComplaintOwnerReplyCandidate.prepare(actor, normalized))
}

/** Data-only input/candidate; original product adapter still authenticates and normalizes. */
internal class RegisteredInitialEditAttemptV1(val input: ComplaintOwnerEditInput, val candidate: ComplaintOwnerEditCandidate)

internal fun registeredEditAttempt(actor: ScopedInstallationId, target: UUID, version: Long = 1,
    subject: String? = " Registered edited subject ", body: String = " Registered edited body\r\nline ", key: UUID = UUID.randomUUID()): RegisteredInitialEditAttemptV1 {
    val input = ComplaintOwnerEditInput(target, key, subject, body, ComplaintOwnerEditPrecondition.parse(target, "\"complaint-$target-v$version\""))
    return RegisteredInitialEditAttemptV1(input, ComplaintOwnerEditCandidate.prepare(actor, ComplaintOwnerEditRequest.normalize(actor.scope, input)))
}
