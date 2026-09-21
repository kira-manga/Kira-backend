package me.manga.kira.backend.complaint.infrastructure.reconciliation

import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.DeleteAllCounter
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJdbcTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.NeverOwnerDeleteAllDataKeys
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.ScopedStepUpFixture
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.InitialIdentityExchangeFixture
import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainFixtureInputsV1
import me.manga.kira.backend.complaint.catalog.withTestActiveFirstCut
import me.manga.kira.backend.complaint.domain.AdminBatchDeleteCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminBatchDeleteTarget
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeletePrecondition
import me.manga.kira.backend.complaint.domain.ComplaintAdminDeleteRequest
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeletePrecondition
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteRequest
import me.manga.kira.backend.complaint.domain.InstallationDeletionCandidate
import me.manga.kira.backend.complaint.domain.InstallationDeletionPreflightResult
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.domain.OwnerDeleteCapacityCharges
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import me.manga.kira.backend.complaint.infrastructure.CommittedTestAdminDeleteWork
import me.manga.kira.backend.complaint.infrastructure.CommittedTestOwnerDeleteWork
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteCandidate
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminJwtIdentityDecoder
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteCandidate
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteApplyStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteReceiptStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteVerificationStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintInstallationDeletionPreflightStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllVerificationStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteApplyStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteReceiptStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteVerificationStore
import me.manga.kira.backend.complaint.infrastructure.TestAdminDeleteAuthorizationV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteAuthorizationV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteProcessBindingV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestAdminDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteAllJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalPublisherFactoryV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOwnerDeleteJournalReadbackV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminDeletePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminDeleteReadPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationDeletionPreflightPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteAllVerificationPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeletePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteReadPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.DeletionPersistenceAdmission
import me.manga.kira.backend.security.AdminReadTestUserJwt
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import me.manga.kira.backend.security.ScopedAdminStepUpScope
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.User
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Genuine global G1/D/request/capture -> fresh TEST root/activation/registration -> RELEASE ->
 * actual enrollment(s) -> scoped empty capture/seal/two-pass checkpoint -> registered CREATE(s).
 * No old TLS handle or supplied checkpoint/authority crosses the reviewed C predecessor handoff.
 * One primary per invocation; optional B raw input is selected BEFORE full D, never installed later.
 */
internal fun withRegisteredInitialCheckpointDeletion(
    tls: VersionBoundPersistenceConnectedFixture,
    family: ComplaintJournalDeletionKindV1,
    queueHttp: TestActiveOwnerDeleteQueueHttpInputV1? = null,
    shortFreshness: Boolean = false,
    terminalHistory: TestOrdinaryDrainFixtureInputsV1? = null,
    action: (TestRegisteredInitialCheckpointDeletionFixtureV1) -> Unit,
) {
    val native = TestRegisteredInitialCheckpointDeletionRawFixtureV1(queueHttp, shortFreshness)
    withTestActiveFirstCut(tls, ordinaryRawHttp = native.factories, terminalHistory = terminalHistory, globalScanBeforeActivation = true) { first ->
        first.initial.withExchange { exchange ->
            val owners = List(if (family == ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE) 2 else 1) {
                val candidate = first.initial.candidate()
                candidate.installation to exchange.enroll(candidate).session.accessToken
            }
            exchange.assertReleased()
            val captured = first.capture()
            first.awaitNativeReclaimed()
            TestActiveOrdinarySealFixtureV1(first, captured, native.ordinary).use { sealer ->
                val sealed = sealer.seal()
                sealer.assertReleased()
                awaitInitialCheckpointLeaseExpiry(sealer.observer, sealer.scope)
                TestActiveInitialCheckpointFixtureV1(sealer, sealed, native.checkpoint).use { checkpoint ->
                    checkpoint.checkpoint() // Completed is deliberately discarded, not passed as AUTH eligibility.
                    checkpoint.assertReleased()
                    checkNotNull(first.p.f.rows.globalPredecessor).assertPreserved(first.observer)
                    val creators = owners.map { (actor, token) ->
                        TestRegisteredInitialCheckpointCreateFixtureV1(checkpoint, exchange, actor, token)
                    }
                    AutoCloseable { creators.asReversed().forEach { it.close() } }.use {
                        val reports = creators.map { creator -> creator.attempt().also { creator.assertApplied(creator.create(it), it) } }
                        assertEquals(PersistenceLifecycleObservation.READY, first.runtime.pools.deletion.prepareDeletion())
                        TestRegisteredInitialCheckpointDeletionFixtureV1(checkpoint, exchange, creators, reports, native, family).use(action)
                    }
                }
            }
        }
    }
}

/** Fixed fixture composition, not a product issuer. AUTH and native work/proof are always actual private outputs. */
internal class TestRegisteredInitialCheckpointDeletionFixtureV1(
    val checkpoint: TestActiveInitialCheckpointFixtureV1,
    val exchange: InitialIdentityExchangeFixture,
    val creators: List<TestRegisteredInitialCheckpointCreateFixtureV1>,
    val reports: List<RegisteredInitialCreateAttemptV1>,
    val native: TestRegisteredInitialCheckpointDeletionRawFixtureV1,
    val family: ComplaintJournalDeletionKindV1,
    private val retained: TestRegisteredInitialCheckpointDeletionFixtureV1? = null,
    val expectedEpoch: Long = 2,
) : AutoCloseable {
    val first = checkpoint.sealer.first
    val initial = first.initial
    val runtime = checkpoint.runtime
    val registration = checkpoint.registration
    val assembly = checkpoint.assembly
    val process = registration.process
    val observer = checkpoint.observer
    val dataScope = process.consumers.journalConfiguration.scope
    val scope = dataScope.id
    val actor = creators.first().actor
    val audit = exchange.service
    val ingress = process.consumers.ingressAdmission
    val admission: DeletionPersistenceAdmission = retained?.admission ?: DeletionPersistenceAdmission()
    val deletionOwner: PersistencePhaseOwnership = retained?.deletionOwner ?: PersistencePhaseOwnership.deletion(admission, GuardedJdbcTransactionManager(runtime.pools.deletion))
    val deletion: TestRegisteredInitialDeletionProbeJdbcV1 = retained?.deletion ?: TestRegisteredInitialDeletionProbeJdbcV1(this)
    val binding: TestOwnerDeleteProcessBindingV1 = retained?.binding ?: TestOwnerDeleteProcessBindingV1.fromRegistered(registration, assembly, exchange.ordinary.ownership,
        exchange.jdbc, deletionOwner, deletion)
    val graph = binding.lower
    val noKeys: NeverOwnerDeleteAllDataKeys = retained?.noKeys ?: NeverOwnerDeleteAllDataKeys()
    val codec: TestOwnerDeleteJournalCodecV1 = retained?.codec ?: TestOwnerDeleteJournalCodecV1(graph.routing, noKeys)
    val capacity: JdbcComplaintCapacityStore = retained?.capacity ?: JdbcComplaintCapacityStore(deletion, graph.policy.digestBytes())
    val ownerStore: JdbcComplaintOwnerDeleteStore = retained?.ownerStore ?: JdbcComplaintOwnerDeleteStore(deletion, capacity, audit, graph, codec)
    val ownerReads: ComplaintOwnerDeleteReadPhaseExecutor = retained?.ownerReads ?: ComplaintOwnerDeleteReadPhaseExecutor(exchange.ordinary.ownership, JdbcComplaintOwnerDeleteReceiptStore(exchange.jdbc, graph))
    val ownerVerification: JdbcComplaintOwnerDeleteVerificationStore = retained?.ownerVerification ?: JdbcComplaintOwnerDeleteVerificationStore(deletion, graph, ownerStore)
    private val ownerApply: JdbcComplaintOwnerDeleteApplyStore = retained?.ownerApply ?: JdbcComplaintOwnerDeleteApplyStore(deletion, capacity, audit, graph, ownerStore, ownerVerification)
    val ownerPhases: ComplaintOwnerDeletePhaseExecutor = retained?.ownerPhases ?: ComplaintOwnerDeletePhaseExecutor(deletionOwner, ownerStore, ownerReads, ownerVerification, ownerApply)
    val allPreflights: ComplaintInstallationDeletionPreflightPhaseExecutor = retained?.allPreflights ?: ComplaintInstallationDeletionPreflightPhaseExecutor(exchange.ordinary.ownership,
        JdbcComplaintInstallationDeletionPreflightStore(exchange.jdbc, testGraph = graph))
    val allStore: JdbcComplaintOwnerDeleteAllStore = retained?.allStore ?: JdbcComplaintOwnerDeleteAllStore(deletion, capacity, audit, graph, codec)
    val allPhases: ComplaintOwnerDeleteAllPhaseExecutor = retained?.allPhases ?: ComplaintOwnerDeleteAllPhaseExecutor(deletionOwner, allStore, allPreflights)
    val allVerification: JdbcComplaintOwnerDeleteAllVerificationStore = retained?.allVerification ?: JdbcComplaintOwnerDeleteAllVerificationStore(deletion, graph, allStore)
    val allVerifyPhases: ComplaintOwnerDeleteAllVerificationPhaseExecutor = retained?.allVerifyPhases ?: ComplaintOwnerDeleteAllVerificationPhaseExecutor(deletionOwner, allVerification)
    val adminStore: JdbcComplaintAdminDeleteStore = retained?.adminStore ?: JdbcComplaintAdminDeleteStore(deletion, capacity, audit, graph, codec)
    val adminReads: ComplaintAdminDeleteReadPhaseExecutor = retained?.adminReads ?: ComplaintAdminDeleteReadPhaseExecutor(exchange.ordinary.ownership, JdbcComplaintAdminDeleteReceiptStore(exchange.jdbc, graph))
    val adminVerification: JdbcComplaintAdminDeleteVerificationStore = retained?.adminVerification ?: JdbcComplaintAdminDeleteVerificationStore(deletion, graph, adminStore)
    private val adminApply: JdbcComplaintAdminDeleteApplyStore = retained?.adminApply ?: JdbcComplaintAdminDeleteApplyStore(deletion, capacity, audit, graph, adminStore, adminVerification)
    val adminPhases: ComplaintAdminDeletePhaseExecutor = retained?.adminPhases ?: ComplaintAdminDeletePhaseExecutor(deletionOwner, adminStore, adminReads, adminVerification, adminApply)
    private val factories = mutableListOf<AutoCloseable>()
    val ownerPublisher: TestOwnerDeleteJournalPublisherFactoryV1 by lazy { retained?.ownerPublisher ?: binding.ownerPublisher(ownerStore).also(factories::add) }
    val allPublisher: TestOwnerDeleteAllJournalPublisherFactoryV1 by lazy { retained?.allPublisher ?: binding.allPublisher(allStore).also(factories::add) }
    val adminPublisher: TestAdminDeleteJournalPublisherFactoryV1 by lazy { retained?.adminPublisher ?: binding.adminPublisher(adminStore).also(factories::add) }
    var ownerLane: JournalPublicationLanesV1.TestOwnerDeleteReservation? = null
        private set
    var allLane: JournalPublicationLanesV1.TestOwnerDeleteAllReservation? = null
        private set
    var adminLane: JournalPublicationLanesV1.TestAdminDeleteReservation? = null
        private set
    var ownerWork: CommittedTestOwnerDeleteWork.Prepared? = null
        private set
    var allWork: CommittedOwnerDeleteAllWork.Prepared? = null
        private set
    var adminWork: CommittedTestAdminDeleteWork.Prepared? = null
        private set
    val key: UUID = UUID.randomUUID()
    val ownerCandidate = ownerCandidate()
    val allCandidate = InstallationDeletionCandidate(InstallationEnrollmentCredentials.prepareSession(actor, ByteArray(32) { it.toByte() }),
        creators.first().identity().credentialVersion, key)
    val adminCandidate = adminCandidate()
    val userJwt = AdminReadTestUserJwt()
    val adminDecoder = ComplaintAdminJwtIdentityDecoder(dataScope, userJwt.decoder, userJwt.properties.clockSkew)
    val adminToken by lazy { userJwt.signer.issue(adminUser()).value }
    val stepUp by lazy { ScopedStepUpFixture(exchange.ordinary, first.p.f.rows.counters, graph.policy.digestBytes(), phaseClock = Clock.systemUTC()) }
    // The pre-existing ordinary ADMIN row is authentication input only. The grant is REALLY issued:
    // snapshot/password/cleanup/counters/locked ADMIN/insert, never a seeded proof or run authority.
    val proof = if (family in ADMIN_FAMILIES) stepUp.issue(ScopedAdminStepUpScope.COMPLAINT) else null
    private val lastAudit = checkNotNull(observer.queryForObject("SELECT coalesce(max(id), 0) FROM audit_log", Long::class.java))
    var event: TestOwnerDeleteJournalEventV1? = null
        private set
    var readback: TestOwnerDeleteJournalReadbackV1? = null
        private set
    var record: TestRegisteredInitialDeletionNativeRecordV1? = null
        private set

    init {
        check(expectedEpoch == 2L || retained != null && expectedEpoch in 3L..15L)
        retained?.let { original ->
            assertSame(original.checkpoint, checkpoint); assertSame(original.exchange, exchange); assertSame(original.native, native)
            assertSame(original.graph, graph); assertSame(original.ownerStore, ownerStore); assertSame(original.adminStore, adminStore)
            assertSame(original.allStore, allStore); assertSame(original.registration, registration)
            assertEquals(expectedEpoch, (checkpoint.control().getValue("publication_epoch") as Number).toLong())
            original.assertReleased()
        }
        assertEquals(if (family == ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE) 2 else 1, creators.size)
        assertEquals(creators.size, creators.map { it.actor }.distinct().size)
        assertEquals(creators.size, reports.size)
        assertSame(runtime, first.runtime); assertSame(exchange.jdbc.dataSource, process.pools.ordinary)
        native.attach(this)
        assertSqlReleased()
    }

    fun ownerCandidate(operationKey: UUID = key, target: UUID = reports.first().input.id, version: Long = 1): ComplaintOwnerDeleteCandidate =
        ComplaintOwnerDeleteCandidate.prepare(actor, ComplaintOwnerDeleteRequest.normalize(dataScope,
            ComplaintOwnerDeleteInput(target, operationKey, ComplaintOwnerDeletePrecondition.parse(target, "\"complaint-$target-v$version\""))))

    fun adminCandidate(operationKey: UUID = key): ComplaintAdminDeleteCandidate {
        val targets = reports.map { it.input.id }.sortedBy(UUID::toString)
        val request = if (family == ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE) ComplaintAdminDeleteRequest.normalize(
            ComplaintAdminBatchDeleteInput(dataScope, operationKey, targets.map { ComplaintAdminBatchDeleteTarget(it,
                ComplaintAdminDeletePrecondition.parse(it, "\"complaint-$it-v1\"")) }))
        else ComplaintAdminDeleteRequest.normalize(ComplaintAdminDeleteInput(dataScope, targets.first(), operationKey,
            ComplaintAdminDeletePrecondition.parse(targets.first(), "\"complaint-${targets.first()}-v1\"")))
        return ComplaintAdminDeleteCandidate.prepare(exchange.ordinary.userId, request)
    }

    fun adminUser(): User = checkNotNull(observer.queryForObject(
        "SELECT id, email, password_hash, role, enabled, created_at, updated_at, credential_version FROM users WHERE id = ?",
        { row, _ -> User(row.getObject("id", UUID::class.java), row.getString("email"), row.getString("password_hash"),
            Role.valueOf(row.getString("role")), row.getBoolean("enabled"), row.getTimestamp("created_at").toInstant(),
            row.getTimestamp("updated_at").toInstant(), row.getLong("credential_version")) }, exchange.ordinary.userId))

    /** Real ingress/read/preflight/reserved native lane before the first private AUTH. No full adapter APPLY. */
    fun authorize(): TestOwnerDeleteJournalEventV1 {
        check(event == null && ownerWork == null && allWork == null && adminWork == null)
        try {
            val released = ingress.withIngress(request()) { context -> when (family) {
                ComplaintJournalDeletionKindV1.OWNER_DELETE -> {
                    ingress.startOwnerDelete(context)
                    val identity = creators.first().identity()
                    assertNull(ownerReads.authenticate(identity).failure)
                    val preflight = ownerReads.preflight(identity, ownerCandidate.tuple)
                    assertNull(preflight.failure); assertNull(preflight.receipt); assertFalse(preflight.authorized)
                    val admitted = ingress.admitOwnerDelete(context, ownerCandidate.tuple)
                    val lane = ownerPublisher.reserve().also { ownerLane = it }
                    val result = assertInstanceOf(TestOwnerDeleteAuthorizationV1.Continue::class.java,
                        ownerPhases.authorize(identity, ownerCandidate, preflight, admitted, lane))
                    val work = assertInstanceOf(CommittedTestOwnerDeleteWork.Prepared::class.java, result.work).also { ownerWork = it }
                    ownerStore.preparedEvent(work)
                }
                ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> {
                    ingress.startOwnerDeleteAll(context)
                    val preflight = assertInstanceOf(InstallationDeletionPreflightResult.Active::class.java, allPreflights.preflight(allCandidate))
                    val admitted = ingress.admitOwnerDeleteAll(context, preflight)
                    val lane = allPublisher.reserve().also { allLane = it }
                    val work = assertInstanceOf(CommittedOwnerDeleteAllWork.Prepared::class.java,
                        allPhases.authorize(allCandidate, preflight, admitted, lane)).also { allWork = it }
                    allStore.testPreparedEvent(work)
                }
                ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> {
                    if (family == ComplaintJournalDeletionKindV1.ADMIN_DELETE) ingress.startAdminDelete(context) else ingress.startAdminBatchDelete(context)
                    val identity = adminDecoder.decode(adminToken)
                    assertNull(adminReads.authenticate(identity).verdict.failure)
                    val preflight = adminReads.preflight(identity, adminCandidate.tuple)
                    assertNull(preflight.failure); assertNull(preflight.receipt); assertFalse(preflight.authorized)
                    val admitted = if (family == ComplaintJournalDeletionKindV1.ADMIN_DELETE) ingress.admitAdminDelete(context, adminCandidate.tuple)
                        else ingress.admitAdminBatchDelete(context, adminCandidate.tuple)
                    val lane = adminPublisher.reserve().also { adminLane = it }
                    val result = assertInstanceOf(TestAdminDeleteAuthorizationV1.Continue::class.java,
                        adminPhases.authorize(identity, adminCandidate, preflight, checkNotNull(proof).token, admitted, lane))
                    val work = assertInstanceOf(CommittedTestAdminDeleteWork.Prepared::class.java, result.work).also { adminWork = it }
                    assertEquals(proof.grantId, work.consumedGrantId)
                    adminStore.preparedEvent(work)
                }
            } }
            assertSqlReleased()
            event = released
            native.expect(this, released)
            return released
        } catch (failure: Throwable) {
            closeLanes()
            throw failure
        }
    }

    fun publish(): TestRegisteredInitialDeletionNativeRecordV1 {
        check(event != null && readback == null)
        val observed = native.publish(this) { when (family) {
            ComplaintJournalDeletionKindV1.OWNER_DELETE -> checkNotNull(ownerLane).publish(checkNotNull(ownerWork))
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> checkNotNull(allLane).publish(checkNotNull(allWork))
            ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> checkNotNull(adminLane).publish(checkNotNull(adminWork))
        } }
        readback = observed
        return native.observed(this, observed).also { record = it; assertReleased() }
    }

    /** Short receipt -> publication VERIFY, on the same private AUTH store/work and cleaned native lane. */
    fun verify() {
        val observed = checkNotNull(readback)
        when (family) {
            ComplaintJournalDeletionKindV1.OWNER_DELETE -> ownerPhases.verify(checkNotNull(ownerWork), observed, checkNotNull(ownerLane))
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> allVerifyPhases.verify(checkNotNull(allWork), observed, checkNotNull(allLane))
            ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE ->
                adminPhases.verify(checkNotNull(adminWork), observed, checkNotNull(adminLane))
        }
        assertReleased()
    }

    fun assertReload(verified: Boolean) {
        val bytes = ingress.withIngress(request()) { context -> when (family) {
            ComplaintJournalDeletionKindV1.OWNER_DELETE -> {
                ingress.startOwnerDelete(context)
                val identity = creators.first().identity()
                val preflight = ownerReads.preflight(identity, ownerCandidate.tuple)
                assertTrue(preflight.authorized); assertNull(preflight.failure)
                val result = assertInstanceOf(TestOwnerDeleteAuthorizationV1.Continue::class.java, ownerPhases.reload(identity, ownerCandidate, preflight)).work
                assertEquals(verified, result is CommittedTestOwnerDeleteWork.RecordedVerified)
                result.canonicalBytes()
            }
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> {
                ingress.startOwnerDeleteAll(context)
                val preflight = assertInstanceOf(InstallationDeletionPreflightResult.Authorized::class.java, allPreflights.preflight(allCandidate))
                val result = allPhases.reload(allCandidate, preflight)
                assertEquals(verified, result is CommittedOwnerDeleteAllWork.RecordedVerified)
                result.canonicalBytes()
            }
            ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> {
                if (family == ComplaintJournalDeletionKindV1.ADMIN_DELETE) ingress.startAdminDelete(context) else ingress.startAdminBatchDelete(context)
                val identity = adminDecoder.decode(adminToken)
                val preflight = adminReads.preflight(identity, adminCandidate.tuple)
                assertTrue(preflight.authorized); assertNull(preflight.failure)
                val result = assertInstanceOf(TestAdminDeleteAuthorizationV1.Continue::class.java, adminPhases.reload(identity, adminCandidate, preflight)).work
                assertEquals(verified, result is CommittedTestAdminDeleteWork.RecordedVerified)
                result.canonicalBytes()
            }
        } }
        val expected = checkNotNull(event).canonicalBytes()
        try { assertArrayEquals(expected, bytes) } finally { expected.fill(0); bytes.fill(0) }
        assertSqlReleased()
    }

    fun counters(): Map<ComplaintCapacityCounter, DeleteAllCounter> = observer.query(
        "SELECT name, free_units, actual_units, recovery_reserved_units, test_reserved_units, " +
            "(to_jsonb(c) - ARRAY['free_units','actual_units','recovery_reserved_units','updated_at'])::text AS preserved " +
            "FROM complaint_capacity_counters c ORDER BY ordinal",
        { row, _ -> ComplaintCapacityCounter.entries.single { it.storedName == row.getString(1) } to
            DeleteAllCounter(row.getLong(2), row.getLong(3), row.getLong(4), row.getLong(5), row.getString(6)) }).toMap()

    val authorizationCharge: ComplaintCapacityVector get() = when (family) {
        ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> OwnerDeleteAllCapacityCharges.AUTHORIZATION
        ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> AdminBatchDeleteCapacityCharges.authorization(2)
        else -> OwnerDeleteCapacityCharges.AUTHORIZATION
    }
    val recoveryCharge: ComplaintCapacityVector get() = when (family) {
        ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> OwnerDeleteAllCapacityCharges.RECOVERY
        ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> AdminBatchDeleteCapacityCharges.recovery(2, 2)
        else -> OwnerDeleteCapacityCharges.RECOVERY
    }
    fun assertCharge(before: Map<ComplaintCapacityCounter, DeleteAllCounter>) {
        val after = counters()
        for (counter in ComplaintCapacityCounter.entries) {
            val old = before.getValue(counter); val current = after.getValue(counter)
            assertEquals(old.preserved, current.preserved)
            assertEquals(old.free - authorizationCharge[counter] - recoveryCharge[counter], current.free)
            assertEquals(old.actual + authorizationCharge[counter], current.actual)
            assertEquals(old.recovery + recoveryCharge[counter], current.recovery)
            assertEquals(old.test, current.test)
        }
    }
    fun audits(): Map<String, Long> = observer.query("SELECT action, count(*) FROM audit_log WHERE complaint_data_scope_id = ? GROUP BY action",
        { row, _ -> row.getString(1) to row.getLong(2) }, scope).toMap()
    /** Exact immutable request key, not the former one-publication-in-the-entire-scope assumption. */
    fun publication() = observer.queryForList("SELECT p.* FROM complaint_journal_publications p WHERE p.data_scope_id = ? AND (" +
        "EXISTS (SELECT 1 FROM complaint_idempotency_receipts n WHERE n.data_scope_id = p.data_scope_id AND n.idempotency_key = ? AND n.publication_ref = p.event_id) OR " +
        "EXISTS (SELECT 1 FROM installation_deletion_receipts n WHERE n.data_scope_id = p.data_scope_id AND n.deletion_key = ? AND n.publication_ref = p.event_id))", scope, key, key).single()
    /** Passive observation sampled before a request; defaults still count only the strict initial SQL. */
    fun currentCheckpointSql(): String {
        requireConnectionFree()
        return if (checkNotNull(process.initialCheckpointDeletion).inventory().getValue("profile").jsonPrimitive.content ==
            VersionBoundTestInitialCheckpointDeletionV1.RECURRENT_PROFILE &&
            (checkpoint.control().getValue("rotation_sequence") as Number).toLong() > 1) TestRegisteredRecurrentCheckpointDeletionSqlV1.current
        else TestRegisteredInitialCheckpointDeletionSqlV1.current
    }
    fun image(): Map<String, List<String>> = raw { connection -> TABLES.associateWith { table ->
        connection.prepareStatement("SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM $table t WHERE data_scope_id = ? ORDER BY to_jsonb(t)::text").use { s ->
            s.queryTimeout = 1; s.setObject(1, scope)
            s.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    } }
    fun request() = MockHttpServletRequest().apply { remoteAddr = "192.0.2.49" }
    fun <T> raw(action: (Connection) -> T): T = checkNotNull(observer.dataSource).connection.use(action)
    fun foreignUpdate(sql: String, vararg args: Any?): Int = raw { connection -> connection.prepareStatement(sql).use { s ->
        s.queryTimeout = 1; args.forEachIndexed { i, arg -> s.setObject(i + 1, arg) }; s.executeUpdate()
    } }

    fun assertSqlReleased() {
        assertSqlQuiescent()
        deletion.assertNoLostAssertions(); native.assertNoLostAssertions(); assertEquals(0, noKeys.calls.get())
    }
    /** Physical retirement only. Every public result assertion still reports all sticky probe errors. */
    fun assertSqlQuiescent() {
        exchange.assertReleased(); checkpoint.assertSqlReleased()
        assertNull(PersistencePhaseOwnership.current()); requireConnectionFree()
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, admission.activeOwners().totalOwners)
        deletion.observations.values.forEach { assertTrue(it.lease.completion.quiescent()) }
    }
    fun assertReleased() { assertSqlReleased(); assertEquals(0L, process.publicationLanes.activeOwners().totalOwners) }
    private fun closeLanes() { ownerLane?.close(); allLane?.close(); adminLane?.close() }
    override fun close() {
        if (retained == null) {
            deletion.before = {}; deletion.after = {}; exchange.jdbc.before = { _, _ -> }; exchange.jdbc.after = { _, _ -> }
        }
        closeLanes(); factories.asReversed().forEach { it.close() }
        // Preserve the recorded test failure while allowing only this retired fixture's owned
        // children to be removed before the enclosing run/catalog rows. No unrelated reset.
        AutoCloseable {
            deletion.assertNoLostAssertions(); native.assertNoLostAssertions(); assertEquals(0, noKeys.calls.get())
        }.use {
            assertSqlQuiescent(); assertEquals(0L, process.publicationLanes.activeOwners().totalOwners)
            native.detach(this)
            if (retained != null) return // This distinct request owns no original stores, provider factories or scope teardown.
            // Explicit disposable-scope teardown AFTER outcomes. No refund or product cleanup proof.
            listOf("complaint_idempotency_receipts", "installation_deletion_receipts", "complaint_recovery_capacity_reservations",
                "complaint_deletion_journal_retirements", "complaint_deletion_journal_applied", "complaint_journal_publications").forEach {
                observer.update("DELETE FROM $it WHERE data_scope_id = ?", scope)
            }
            observer.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND id > ?", scope, lastAudit)
        }
    }
    companion object {
        private val ADMIN_FAMILIES = setOf(ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE)
        private val TABLES = listOf("complaint_idempotency_receipts", "installation_deletion_receipts", "complaint_journal_publications",
            "complaint_recovery_capacity_reservations", "complaint_deletion_journal_applied", "complaint_deletion_journal_retirements",
            "complaints", "complaint_resource_ids", "app_installations", "complaint_installation_ids")
    }
}

/** Passive exact deletion template chosen BEFORE registration retains its pair. Every SQL result and commit is real. */
internal class TestRegisteredInitialDeletionProbeJdbcV1(private val f: TestRegisteredInitialCheckpointDeletionFixtureV1) : JdbcTemplate(f.runtime.pools.deletion) {
    val observations: MutableMap<PersistencePhaseContext, StepUpPhaseObservation> = Collections.synchronizedMap(linkedMapOf())
    val calls = CopyOnWriteArrayList<TestRegisteredInitialDeletionSqlCallV1>()
    var before: (TestRegisteredInitialDeletionSqlCallV1) -> Unit = {}
    var after: (TestRegisteredInitialDeletionSqlCallV1) -> Unit = {}
    private val assertion = AtomicReference<AssertionError?>()
    private val observing = ThreadLocal.withInitial { false }
    init { exceptionTranslator = SQLExceptionSubclassTranslator() }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>): List<T> = observed(sql, emptyArray()) { super.query(sql, mapper) }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> = observed(sql, args) { super.query(sql, mapper, *args) }
    override fun <T : Any?> query(sql: String, extractor: ResultSetExtractor<T>, vararg args: Any?): T? = observed(sql, args) { super.query(sql, extractor, *args) }
    override fun update(sql: String, vararg args: Any?): Int = observed(sql, args) { super.update(sql, *args) }
    private fun <T> observed(sql: String, args: Array<out Any?>, execute: () -> T): T {
        if (observing.get()) return execute()
        observing.set(true)
        var attempted: TestRegisteredInitialDeletionSqlCallV1? = null
        var stage = "OBSERVATION"
        try {
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            val path = poolTestField<PersistencePhasePath>(phase, "path")
            val holder = TransactionSynchronizationManager.getResource(checkNotNull(dataSource)) as ConnectionHolder
            assertEquals(setOf(dataSource), TransactionSynchronizationManager.getResourceMap().keys)
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, holder.connection.transactionIsolation)
            val lease = ownedPoolLease(holder.connection)
            val observation = observations.getOrPut(phase) {
                val identity = holder.connection.createStatement().use { statement -> statement.executeQuery("SELECT pg_backend_pid(), txid_current()").use { row ->
                    assertTrue(row.next()); (row.getInt(1) to row.getLong(2)).also { assertFalse(row.next()) }
                } }
                StepUpPhaseObservation(phase, lease, identity)
            }
            assertSame(lease, observation.lease); assertFalse(lease.completion.quiescent())
            // Passive detached new-claim comparisons only. MAIN zeroes its original byte arrays;
            // no retained argument copy is supplied back to a store, result, work or proof issuer.
            val owned = if (sql == TestRegisteredRecurrentCheckpointDeletionSqlV1.owned) {
                assertEquals(20, args.size); assertEquals(sql.count { it == '?' }, args.size)
                args.map { if (it is ByteArray) it.copyOf() else it }
            } else emptyList()
            val call = TestRegisteredInitialDeletionSqlCallV1(phase, path, sql, owned)
            attempted = call
            stage = "BEFORE_CALLBACK"
            calls.add(call); before(call)
            stage = "EXECUTE_AND_MAP"
            val result = execute()
            stage = "AFTER_CALLBACK"
            after(call)
            return result
        } catch (failure: Throwable) {
            if (failure is AssertionError) assertion.compareAndSet(null, failure)
            try {
                // Only this callback's original throw, before the phase erases its cause. This
                // can also describe an expected negative; it is not a whole-case failure verdict.
                val sqlState = ((failure as? SQLException) ?: (failure.cause as? SQLException))?.sqlState?.uppercase()
                    ?.takeIf { it.length == 5 && it.all { c -> c in 'A'..'Z' || c in '0'..'9' } } ?: "NONE"
                println("TEST_REGISTERED_DELETION_CALLBACK_FAILURE stage=$stage path=${attempted?.path?.name ?: "NONE"} " +
                    "sqlStep=${attempted?.sqlStep ?: "NONE"} kind=${testDeletionFailureKind(failure)} sqlState=$sqlState")
            } catch (_: Throwable) { /* Diagnostics must not replace the original failure. */ }
            throw failure
        }
        finally { observing.remove() }
    }
    fun assertNoLostAssertions() { assertion.get()?.let { throw it } }
}

internal class TestRegisteredInitialDeletionSqlCallV1(val phase: PersistencePhaseContext, val path: PersistencePhasePath, val sql: String,
    val ownedArguments: List<Any?> = emptyList()) {
    /** Fixed labels only; the existing private SQL/argument observations are never printed. */
    val sqlStep: String get() = when (sql) {
        TestRegisteredInitialCheckpointDeletionSqlV1.current -> "INITIAL_CURRENT"
        TestRegisteredRecurrentCheckpointDeletionSqlV1.branch -> "RECURRENT_BRANCH"
        TestRegisteredRecurrentCheckpointDeletionSqlV1.current -> "RECURRENT_CURRENT"
        TestRegisteredRecurrentCheckpointDeletionSqlV1.owned -> "RECURRENT_OWNED"
        TestRegisteredRecurrentCheckpointDeletionSqlV1.initialHeaders -> "INITIAL_HEADERS"
        TestRegisteredRecurrentCheckpointDeletionSqlV1.recurrentHeaders -> "RECURRENT_HEADERS"
        TestRegisteredRecurrentCheckpointDeletionSqlV1.initialPayload -> "INITIAL_PAYLOAD"
        TestRegisteredRecurrentCheckpointDeletionSqlV1.recurrentPayload -> "RECURRENT_PAYLOAD"
        TestRegisteredRecurrentCheckpointDeletionSqlV1.history -> "CHECKPOINT_HISTORY"
        TestRegisteredRecurrentCheckpointDeletionSqlV1.historyCounts -> "PRIOR_COUNTS"
        TestRegisteredRecurrentCheckpointDeletionSqlV1.publications -> "PRIOR_IDS"
        TestRegisteredRecurrentCheckpointDeletionSqlV1.publication -> "PRIOR_PUBLICATION"
        TestRegisteredRecurrentCheckpointDeletionSqlV1.ownerReceipt -> "PRIOR_OWNER_RECEIPT"
        TestRegisteredRecurrentCheckpointDeletionSqlV1.adminReceipt -> "PRIOR_ADMIN_RECEIPT"
        TestRegisteredRecurrentCheckpointDeletionSqlV1.family -> "PRIOR_FAMILY"
        TestRegisteredRecurrentCheckpointDeletionSqlV1.appliedFamily -> "PRIOR_APPLIED_FAMILY"
        TestRegisteredRecurrentCheckpointDeletionSqlV1.recovery -> "PRIOR_RECOVERY"
        TestActiveRecurrentScanSqlV1.appliedPage -> "APPLIED_COVERAGE"
        "SELECT clock_timestamp()" -> "DB_CLOCK"
        else -> "OTHER"
    }
}

internal fun testDeletionFailureKind(failure: Throwable): String = when (failure) {
    is PersistencePhaseException -> "PERSISTENCE_PHASE"
    is TestActiveRecurrentExceptionV1 -> "RECURRENT_REFUSED"
    is DataAccessException -> "DATA_ACCESS"
    is SQLException -> "SQL"
    is AssertionError -> "ASSERTION"
    else -> "OTHER"
}
