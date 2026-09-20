package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.ComplaintInstallationEnrollmentAudit
import me.manga.kira.backend.common.infrastructure.persistence.GuardedJdbcTransactionManager
import me.manga.kira.backend.common.infrastructure.persistence.NeverOwnerDeleteAllDataKeys
import me.manga.kira.backend.common.infrastructure.persistence.OrdinarySourceGrantCleanupFixture
import me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteLiteralCharges
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.StepUpPhaseObservation
import me.manga.kira.backend.common.infrastructure.persistence.SystemPersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.ownedPoolLease
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteInput
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeletePrecondition
import me.manga.kira.backend.complaint.domain.ComplaintOwnerDeleteRequest
import me.manga.kira.backend.complaint.domain.ComplaintOwnerReceipt
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.ComplaintReportIdentity
import me.manga.kira.backend.complaint.domain.ComplaintReportMetadataInput
import me.manga.kira.backend.complaint.domain.ComplaintReportRequest
import me.manga.kira.backend.complaint.domain.ComplaintReportRequestResult
import me.manga.kira.backend.complaint.domain.ComplaintType
import me.manga.kira.backend.complaint.domain.InstallationEnrollmentResult
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.infrastructure.CommittedTestOwnerDeleteWork
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerCreateCandidate
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteCandidate
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerOperationIdentity
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerCreateStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteApplyStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteReceiptStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteVerificationStore
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteAuthorizationV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteLocalGraphV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintEnrollmentAdmissionCoordinator
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintInstallationEnrollmentStore
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingResultV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunSealingV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunOwnerDeleteContinuationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunPreparedOwnerDeleteV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunVerifiedOwnerDeleteV1
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintInstallationEnrollmentPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerCreatePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeletePhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintOwnerDeleteReadPhaseExecutor
import me.manga.kira.backend.complaint.infrastructure.transaction.DeletionPersistenceAdmission
import me.manga.kira.backend.complaint.journal.TestOwnerDeleteJournalPublisherFixture
import me.manga.kira.backend.security.InstallationEnrollmentCredentials
import me.manga.kira.backend.security.InstallationJwtCodec
import me.manga.kira.backend.security.TestOwnerDeleteJournalCodecV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalTupleV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.security.MessageDigest
import java.sql.Connection
import java.time.Clock
import java.util.UUID

/** Reuses real signed PROJECT, independent raw registration, ordinary/deletion owners and SDK reply fixtures. */
internal fun withVerifiedOwnerDeleteRun(tls: VersionBoundPersistenceConnectedFixture, verified: Boolean = true,
    clock: PersistenceNanoClock = SystemPersistenceNanoClock,
    additionalVerified: Int = 0,
    action: (TestRunVerifiedOwnerDeleteFixture) -> Unit) = ComplaintTestNamespaceRegistrationCases.withRegisteredRun(
    // Select the TEST ceiling before consumers/full D/signing; one/two-history fixtures retain two.
    tls, createGlobal = maxOf(2, additionalVerified + 1),
) { p, runtime, registration, _ ->
    assertEquals(PersistenceLifecycleObservation.READY, runtime.pools.deletion.prepareDeletion())
    ComplaintTestNamespaceRegistrationCases.withOrdinaryAudit(runtime) { ordinary, audit ->
        TestRunVerifiedOwnerDeleteFixture(p, runtime, registration, ordinary, audit, clock).use { f ->
            f.authorEarlierHistory(verified, additionalVerified)
            assertEquals(TestRunSealingResultV1.SEALED_AND_AUDITED, TestRunSealingV1.begin(registration).seal())
            f.jdbc.enabled = true
            action(f)
        }
    }
}

/**
 * Historical ACTIVE/open/checkpoint/seal comparisons below are explicitly SYNTHETIC, not a gate
 * opener, scan, permanent-denial or quiescence producer. They supply no work/VERIFIED/registration
 * object. Enrollment, create, authorization, publication/readback, VERIFY, registration and sealing
 * all use the existing real producers. Full-D and closed controls are restored BEFORE real sealing.
 */
internal class TestRunVerifiedOwnerDeleteFixture(
    val p: ProjectionActivationObservation,
    val runtime: VersionBoundPersistenceConnectedFixture,
    val registration: ComplaintTestNamespaceRegistrationV1,
    private val ordinary: OrdinarySourceGrantCleanupFixture,
    val audit: AuditService,
    clock: PersistenceNanoClock = SystemPersistenceNanoClock,
) : AutoCloseable {
    val process = registration.process
    val observer = p.f.rows.observer
    val scope = process.consumers.journalConfiguration.scope
    val actor = ScopedInstallationId(UUID.randomUUID(), scope)
    val target: UUID = UUID.randomUUID()
    val key: UUID = UUID.randomUUID()
    val admission = DeletionPersistenceAdmission()
    val ownership = PersistencePhaseOwnership.deletion(admission, GuardedJdbcTransactionManager(runtime.pools.deletion), clock)
    val jdbc = TestVerifiedDeleteProbeJdbc(p, runtime)
    private val controls = listOf(ComplaintDataScope.LIVE.id, scope.id).associateWith { id ->
        checkNotNull(observer.queryForObject("SELECT to_jsonb(c)::text FROM complaint_journal_control c WHERE data_scope_id = ?", String::class.java, id))
    }
    private val lastAudit = checkNotNull(observer.queryForObject("SELECT coalesce(max(id), 0) FROM audit_log", Long::class.java))
    private val ingress = process.consumers.ingressAdmission
    private val routing = process.consumers.journalRouting
    private val policy = process.consumers.capacityPolicy
    private val lower = TestOwnerDeleteLocalGraphV1(ordinary.jdbc, jdbc, ingress, routing, policy, process.publicationLanes, process.desiredGeneration)
    private val lowerDesired = lower.desiredSettings()
    private val noKeys = NeverOwnerDeleteAllDataKeys()
    private val codec = TestOwnerDeleteJournalCodecV1(routing, noKeys)
    private val capacity = JdbcComplaintCapacityStore(jdbc, policy.digestBytes())
    private val store = JdbcComplaintOwnerDeleteStore(jdbc, capacity, audit, lower, codec)
    private val reads = ComplaintOwnerDeleteReadPhaseExecutor(ordinary.ownership, JdbcComplaintOwnerDeleteReceiptStore(ordinary.jdbc, lower))
    private val verification = JdbcComplaintOwnerDeleteVerificationStore(jdbc, lower, store)
    private val apply = JdbcComplaintOwnerDeleteApplyStore(jdbc, capacity, audit, lower, store, verification)
    private val phases = ComplaintOwnerDeletePhaseExecutor(ownership, store, reads, verification, apply)
    private var wire: TestOwnerDeleteJournalPublisherFixture? = null
    lateinit var eventId: String
        private set
    private var expectedProviders: List<Int>? = null
    private var preparedPublication = false
    private val ownedTargets = mutableListOf(target)
    val histories = mutableListOf<History>()
    class History(val target: UUID, val key: UUID, val eventId: String)

    fun begin(actorId: UUID = actor.id, operationKey: UUID = key): TestRunVerifiedOwnerDeleteV1 =
        TestRunVerifiedOwnerDeleteV1.begin(registration, ownership, jdbc, audit, actorId, operationKey)

    val provider: TestOwnerDeleteJournalPublisherFixture get() = checkNotNull(wire)

    fun beginPrepared(actorId: UUID = actor.id, operationKey: UUID = key): TestRunPreparedOwnerDeleteV1 {
        preparedPublication = true
        return TestRunPreparedOwnerDeleteV1.withHttpFixture(registration, ownership, jdbc, audit, actorId, operationKey,
            TestOwnerDeleteJournalPublisherFixture.CREDENTIALS, { provider.beforeOpen(); provider.httpClient() }, provider.kms::httpClient, provider.clock, { provider.nanos })
    }

    fun beginPage(): TestRunPreparedOwnerDeleteV1 {
        preparedPublication = true
        return TestRunPreparedOwnerDeleteV1.pageWithHttpFixture(registration, ownership, jdbc, audit,
            TestOwnerDeleteJournalPublisherFixture.CREDENTIALS, { provider.beforeOpen(); provider.httpClient() }, provider.kms::httpClient, provider.clock, { provider.nanos })
    }

    fun authorEarlierHistory(verified: Boolean, additionalVerified: Int = 0, recoverMissingInstallation: Boolean = false) {
        require(additionalVerified in 0..2)
        require(!recoverMissingInstallation || (verified && additionalVerified == 0))
        stageSyntheticComparisons()
        val ordinaryCapacity = JdbcComplaintCapacityStore(ordinary.jdbc, policy.digestBytes())
        val enrollment = ComplaintEnrollmentAdmissionCoordinator(ingress, ComplaintInstallationEnrollmentPhaseExecutor(ordinary.ownership,
            JdbcComplaintInstallationEnrollmentStore(ordinary.jdbc, ordinaryCapacity,
                ComplaintInstallationEnrollmentAudit { scope, paid, at -> audit.recordInstallationEnrollment(scope, paid, at) }, lowerDesired)))
        val candidate = InstallationEnrollmentCredentials.prepare(actor, ComplaintPlatform.ANDROID, ByteArray(32) { it.toByte() })
        val enrolled = ingress.withIngress(request()) { context ->
            assertInstanceOf(InstallationEnrollmentResult.Enrolled::class.java, enrollment.enroll(context, enrollment.admitEnrollment(context, candidate)))
        }
        val jwt = InstallationJwtCodec(process.consumers.jwt.installationKeyRing, Clock.systemUTC())
        val token = jwt.issue(enrolled.installation, enrolled.credentialVersion, enrolled.issuedAt).value
        fun identity() = jwt.verify(token).let { ComplaintOwnerOperationIdentity(it.installation, it.credentialVersion, it.issuedAt, it.expiresAt) }
        val create = ComplaintOwnerCreatePhaseExecutor(ordinary.ownership, JdbcComplaintOwnerCreateStore(ordinary.jdbc, ordinaryCapacity, audit, lowerDesired))
        val targets = listOf(target to key) + List(additionalVerified) { UUID.randomUUID().also { ownedTargets.add(it) } to UUID.randomUUID() }
        targets.forEachIndexed { index, (target, key) ->
            val shouldVerify = verified || index > 0
            val report = (ComplaintReportRequest.normalize(checkNotNull(ComplaintReportIdentity.checked(target.toString(), UUID.randomUUID().toString(), scope.id.toString())),
                ComplaintType.TECHNICAL, "Synthetic retained report", "Synthetic content to erase", ComplaintReportMetadataInput(null, "fixture", "", "")) as ComplaintReportRequestResult.Accepted).request
            val createCandidate = ComplaintOwnerCreateCandidate.prepare(actor, report)
            ingress.withIngress(request()) { context ->
                ingress.startOwnerCreate(context)
                val identity = identity()
                assertEquals(ComplaintPlatform.ANDROID, create.authenticate(identity).platform)
                assertNull(create.preflight(identity, createCandidate.tuple).receipt)
                val result = create.create(identity, createCandidate, ComplaintPlatform.ANDROID, ingress.admitOwnerCreate(context, createCandidate.tuple))
                assertNull(result.failure)
                assertEquals(target, assertInstanceOf(ComplaintOwnerReceipt.Applied::class.java, result.receipt).id)
            }
            val deletion = ComplaintOwnerDeleteCandidate.prepare(actor, ComplaintOwnerDeleteRequest.normalize(scope,
                ComplaintOwnerDeleteInput(target, key, ComplaintOwnerDeletePrecondition.parse(target, "\"complaint-$target-v1\""))))
            val event = codec.canonicalize(TestOwnerDeleteJournalTupleV1(11, actor.id, enrolled.credentialVersion, key, deletion.tuple.fingerprintBytes(), scope), listOf(target))
            if (index == 0) eventId = event.route.eventId
            val provider = TestOwnerDeleteJournalPublisherFixture(routing, event).also { if (index == 0) wire = it }
            provider.wall = p.databaseTime()
            provider.beforeOpen = { requireConnectionFree(); provider.wall = p.databaseTime() }
            provider.beforePrepare = ::assertDatabaseReleased
            provider.factory(store, process.publicationLanes).use { publishers ->
                val work = publishers.reserve().use {
                    ingress.withIngress(request()) { context ->
                        ingress.startOwnerDelete(context)
                        val identity = identity()
                        assertEquals(ComplaintPlatform.ANDROID, reads.authenticate(identity).platform)
                        val preflight = reads.preflight(identity, deletion.tuple)
                        assertNull(preflight.failure); assertNull(preflight.receipt); assertFalse(preflight.authorized)
                        val result = phases.authorize(identity, deletion, preflight, ingress.admitOwnerDelete(context, deletion.tuple))
                        assertInstanceOf(CommittedTestOwnerDeleteWork.Prepared::class.java, assertInstanceOf(TestOwnerDeleteAuthorizationV1.Continue::class.java, result).work)
                    }
                }
                if (shouldVerify) phases.verify(publishers.reserve().use { it.publish(work) })
                if (recoverMissingInstallation) {
                    forgetInstallationSnapshot(target)
                    val before = p.counters()
                    val requests = provider.requests.size
                    phases.recover(publishers.readExisting(event.tuple, target, event.route.routingKeyId))
                    assertDatabaseReleased()
                    assertEquals(listOf("LIST", "GET"), provider.requests.drop(requests).map { it.kind })
                    val after = p.counters()
                    ComplaintCapacityCounter.entries.forEach { counter ->
                        val old = before.getValue(counter.storedName); val now = after.getValue(counter.storedName)
                        val spent = OwnerDeleteLiteralCharges.reconstructAbsent[counter]
                        assertEquals(old.free, now.free, counter.storedName)
                        assertEquals(old.actual + spent, now.actual, counter.storedName)
                        assertEquals(old.recovery - spent, now.recovery, counter.storedName)
                        assertEquals(old.reserved, now.reserved, "Recovery does not spend TEST terminal reserve.")
                        assertEquals(old.hard, now.free + now.actual + now.recovery + now.reserved)
                    }
                }
            }
            provider.assertClientsClosed()
            assertEquals(if (recoverMissingInstallation) "APPLIED" else if (shouldVerify) "VERIFIED" else "PREPARED", publicationState(event.route.eventId))
            assertEquals(if (recoverMissingInstallation) "COMPLETED" else "AUTHORIZED_DELETE", receiptState(key))
            histories.add(History(target, key, event.route.eventId))
        }
        assertReleased()
        // Preserve the genuine paid history, but put the REAL registered D back before the new source runs.
        assertEquals(1, observer.update("UPDATE complaint_test_runs SET configuration_hash = ? WHERE data_scope_id = ?", process.configurationHashBytes(), scope.id))
        assertEquals(1, observer.update("UPDATE complaint_journal_control SET desired_configuration_hash = ?, checkpoint_configuration_hash = ? WHERE data_scope_id = ?",
            process.configurationHashBytes(), process.configurationHashBytes(), scope.id))
        assertEquals(2, observer.update("UPDATE complaint_journal_control SET maintenance_closed = true, creation_closed = true WHERE data_scope_id IN (?, ?)", ComplaintDataScope.LIVE.id, scope.id))
        expectedProviders = providerImage()
    }

    /** Synthetic old-snapshot domain loss only; the retained primary receipt/publication/reserve confer no new authority. */
    private fun forgetInstallationSnapshot(target: UUID) = checkNotNull(observer.dataSource).connection.use { connection ->
        connection.autoCommit = false
        try {
            val sql = JdbcTemplate(SingleConnectionDataSource(connection, true)).apply { exceptionTranslator = SQLExceptionSubclassTranslator() }
            assertEquals(1, sql.update("DELETE FROM complaints WHERE id = ?", target))
            assertEquals(1, sql.update("DELETE FROM complaint_resource_ids WHERE id = ?", target))
            assertEquals(1, sql.update("DELETE FROM app_installations WHERE id = ?", actor.id))
            assertEquals(1, sql.update("DELETE FROM complaint_installation_ids WHERE id = ?", actor.id))
            val forgotten = OwnerDeleteLiteralCharges.content + OwnerDeleteLiteralCharges.resource + OwnerDeleteLiteralCharges.credential + OwnerDeleteLiteralCharges.installation
            ComplaintCapacityCounter.entries.forEach { counter ->
                if (forgotten[counter] != 0L) assertEquals(1, sql.update(
                    "UPDATE complaint_capacity_counters SET free_units = free_units + ?, actual_units = actual_units - ? WHERE name = ? AND actual_units >= ?",
                    forgotten[counter], forgotten[counter], counter.storedName, forgotten[counter]))
            }
            connection.commit()
        } catch (problem: Throwable) {
            connection.rollback()
            throw problem
        }
    }

    fun assertReleased() {
        assertDatabaseReleased()
        expectedProviders?.let {
            assertEquals(it.first(), providerImage().first(), "Continuation cannot renew registration or obtain another catalog observation.")
            if (!preparedPublication) assertEquals(it, providerImage(), "VERIFIED continuation/refusal cannot open a provider.")
            checkNotNull(wire).assertClientsClosed()
        }
    }

    fun assertDatabaseReleased() {
        requireConnectionFree()
        assertNull(PersistencePhaseOwnership.current())
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        assertEquals(0, admission.activeOwners().totalOwners)
        assertTrue(jdbc.observations.values.all { it.lease.completion.quiescent() })
        assertEquals(0, noKeys.calls.get())
        jdbc.assertNoLostAssertions()
    }

    fun assertSuccessfulPhases(original: TestRunOwnerDeleteContinuationV1, published: Boolean = false) {
        assertReleased()
        val expected = if (published) listOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD, PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY, PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY)
            else listOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD, PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY)
        assertEquals(expected, jdbc.observations.keys.map { path(it) })
        jdbc.observations.keys.forEach {
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, it.databaseOutcome())
            assertTrue(it.testRunOwnerDeleteCleanupProven(original))
        }
        if (!preparedPublication) assertEquals(checkNotNull(expectedProviders), providerImage(), "No fresh authorization/provider/registration activity.")
        checkNotNull(wire).assertClientsClosed()
        assertEquals(0L, process.publicationLanes.activeOwners().totalOwners)
    }

    fun assertApplied(before: Map<String, ProjectionCounterObservation>) {
        assertAppliedRows()
        assertApplyCounters(before)
    }

    fun assertAppliedRows(target: UUID = this.target, key: UUID = this.key, eventId: String = this.eventId) {
        assertEquals("APPLIED", publicationState(eventId)); assertEquals("COMPLETED", receiptState(key))
        assertEquals("DELETED", observer.queryForObject("SELECT state FROM complaint_resource_ids WHERE id = ?", String::class.java, target))
        assertEquals(0L, observer.queryForObject("SELECT count(*) FROM complaints WHERE id = ?", Long::class.java, target))
        assertEquals("PARTIAL", observer.queryForObject("SELECT state FROM complaint_recovery_capacity_reservations WHERE event_id = ?", String::class.java, eventId))
        assertEquals(array(OwnerDeleteLiteralCharges.ordinaryApply.toLongArray()), observer.queryForObject(
            "SELECT converted_amounts::text FROM complaint_recovery_capacity_reservations WHERE event_id = ?", String::class.java, eventId))
        assertEquals(array(OwnerDeleteLiteralCharges.promise.toLongArray()), observer.queryForObject(
            "SELECT reserved_amounts::text FROM complaint_recovery_capacity_reservations WHERE event_id = ?", String::class.java, eventId))
        assertFalse((OwnerDeleteLiteralCharges.promise - OwnerDeleteLiteralCharges.ordinaryApply).isZero(), "Unspent identity/alias capacity is deliberately retained.")
        assertEquals(1L, observer.queryForObject("SELECT count(*) FROM complaint_deletion_journal_applied WHERE event_id = ?", Long::class.java, eventId))
        assertEquals(1L, observer.queryForObject("SELECT count(*) FROM audit_log WHERE complaint_data_scope_id = ? AND entity_id = ? AND action = 'COMPLAINT_DELETED'",
            Long::class.java, scope.id, target.toString()))
    }

    fun assertApplyCounters(before: Map<String, ProjectionCounterObservation>, count: Int = 1) {
        val after = p.counters()
        ComplaintCapacityCounter.entries.forEach { counter ->
            val old = before.getValue(counter.storedName)
            val now = after.getValue(counter.storedName)
            val spent = OwnerDeleteLiteralCharges.ordinaryApply[counter] * count
            val refund = OwnerDeleteLiteralCharges.content[counter] * count
            assertEquals(old.free + refund, now.free, counter.storedName)
            assertEquals(old.actual + spent - refund, now.actual, counter.storedName)
            assertEquals(old.recovery - spent, now.recovery, counter.storedName)
            assertEquals(old.reserved, now.reserved, "TEST terminal reserve is not spent by ordinary APPLY.")
            assertEquals(old.hard, now.free + now.actual + now.recovery + now.reserved)
            if (spent == 0L && refund == 0L) assertEquals(old.full, now.full, "No unrelated counter churn: ${counter.storedName}")
        }
    }

    fun publicationState(eventId: String = this.eventId): String = checkNotNull(observer.queryForObject("SELECT state FROM complaint_journal_publications WHERE event_id = ?", String::class.java, eventId))
    fun receiptState(key: UUID = this.key): String = checkNotNull(observer.queryForObject("SELECT state FROM complaint_idempotency_receipts WHERE actor_id = ? AND idempotency_key = ?", String::class.java, actor.id, key))

    /** Byte/xmin images from an independent connection, including the tables absent from the projection-only image. */
    fun image(): Map<String, List<String>> = checkNotNull(observer.dataSource).connection.use { connection ->
        p.image(connection).toMutableMap().also { result ->
            for (table in listOf("complaint_installation_ids", "app_installations", "complaint_idempotency_receipts", "complaint_journal_publications",
                "complaint_recovery_capacity_reservations", "complaint_deletion_journal_applied", "complaint_deletion_journal_retirements")) {
                result[table] = strings(connection, "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM $table t WHERE data_scope_id = '${scope.id}' ORDER BY to_jsonb(t)::text")
            }
            result["global-full"] = strings(connection, "SELECT jsonb_build_array(to_jsonb(t), t.xmin::text)::text FROM complaint_journal_control t WHERE data_scope_id = '${ComplaintDataScope.LIVE.id}'")
            result["counter-metadata"] = strings(connection, "SELECT (to_jsonb(c) - ARRAY['free_units','actual_units','recovery_reserved_units','updated_at'])::text " +
                "FROM complaint_capacity_counters c ORDER BY ordinal")
        }
    }

    fun providerImage(): List<Int> = listOf(p.f.http.read.requests.size, checkNotNull(wire).requests.size, checkNotNull(wire).kms.requests.size,
        checkNotNull(wire).s3ClientsCreated, checkNotNull(wire).s3ClientsClosed)

    private fun stageSyntheticComparisons() {
        val bytes = "synthetic-historical-checkpoint-and-seal-NOT-external-evidence".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        assertEquals(1, observer.update("UPDATE complaint_journal_control SET desired_configuration_hash = ? WHERE data_scope_id = ?", lowerDesired.configurationHashBytes(), scope.id))
        assertEquals(1, observer.update("UPDATE complaint_test_runs SET configuration_hash = ? WHERE data_scope_id = ?", lowerDesired.configurationHashBytes(), scope.id))
        assertEquals(2, observer.update("UPDATE complaint_journal_control SET publication_epoch = 11, maintenance_closed = false, creation_closed = false, scan_requested = false, " +
            "lease_token = greatest(lease_token, 3), seal_state = 'SEAL_VERIFIED', seal_epoch = 9, seal_writer_generation = event_writer_generation, seal_operation_token = ?, " +
            "seal_object_key = 'synthetic/seal', seal_bytes = ?, seal_hash = ?, seal_object_version = 'synthetic-seal-v1', seal_ciphertext_hash = ?, " +
            "seal_retain_until = clock_timestamp() + interval '70 days', seal_verified_at = clock_timestamp(), seal_verification_bytes = ?, seal_verification_hash = ?, " +
            "checkpoint_generation = 1, checkpoint_fencing_token = 3, checkpoint_catalog_generation = accepted_catalog_generation, checkpoint_catalog_hash = accepted_catalog_hash, " +
            "checkpoint_writer_generation = event_writer_generation, checkpoint_cutoff_epoch = 9, checkpoint_configuration_hash = desired_configuration_hash, " +
            "checkpoint_database_identity = database_identity, checkpoint_restore_identity = restore_identity, checkpoint_schema = implementation_schema, " +
            "checkpoint_started_at = clock_timestamp(), checkpoint_completed_at = clock_timestamp(), checkpoint_object_count = 0, checkpoint_byte_count = 0, " +
            "checkpoint_result = 'SUCCESS', checkpoint_bytes = ?, checkpoint_hash = ? WHERE data_scope_id IN (?, ?)",
            UUID.randomUUID(), bytes, digest, digest, bytes, digest, bytes, digest, ComplaintDataScope.LIVE.id, scope.id))
    }

    override fun close() {
        jdbc.before = {}; jdbc.after = {}; jdbc.enabled = false
        assertReleased()
        // This fixture owns only its added actor/report/history; outer PROJECT fixture owns notices/run/control/counter teardown.
        observer.update("DELETE FROM complaint_idempotency_receipts WHERE actor_kind = 'INSTALLATION' AND actor_id = ?", actor.id)
        observer.update("DELETE FROM complaint_recovery_capacity_reservations WHERE data_scope_id = ?", scope.id)
        observer.update("DELETE FROM complaint_deletion_journal_retirements WHERE data_scope_id = ?", scope.id)
        observer.update("DELETE FROM complaint_deletion_journal_applied WHERE data_scope_id = ?", scope.id)
        observer.update("DELETE FROM complaint_journal_publications WHERE data_scope_id = ?", scope.id)
        ownedTargets.forEach { target ->
            observer.update("DELETE FROM complaints WHERE id = ?", target)
            observer.update("DELETE FROM complaint_resource_ids WHERE id = ?", target)
        }
        observer.update("DELETE FROM app_installations WHERE id = ?", actor.id)
        observer.update("DELETE FROM complaint_installation_ids WHERE id = ?", actor.id)
        observer.update("DELETE FROM audit_log WHERE complaint_data_scope_id = ? AND id > ?", scope.id, lastAudit)
        controls.forEach { (id, json) ->
            assertEquals(1, observer.update("UPDATE complaint_journal_control SET ($HISTORICAL_FIELDS) = " +
                "(SELECT $HISTORICAL_FIELDS FROM jsonb_populate_record(NULL::complaint_journal_control, ?::jsonb)) WHERE data_scope_id = ?", json, id))
        }
    }

    private fun request() = MockHttpServletRequest().apply { remoteAddr = "192.0.2.48" }
    private fun array(values: LongArray): String = values.joinToString(",", "{", "}")
    private fun strings(connection: Connection, sql: String): List<String> = connection.createStatement().use { statement ->
        statement.queryTimeout = 1
        statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }
    companion object {
        fun path(phase: PersistencePhaseContext): PersistencePhasePath = ownedCutField(phase, "path") as PersistencePhasePath
        private val HISTORICAL_FIELDS = "desired_configuration_hash, publication_epoch, maintenance_closed, creation_closed, scan_requested, lease_token, " +
            "seal_state, seal_epoch, seal_writer_generation, seal_operation_token, seal_object_key, seal_bytes, seal_hash, seal_object_version, seal_ciphertext_hash, " +
            "seal_retain_until, seal_verified_at, seal_verification_bytes, seal_verification_hash, checkpoint_generation, checkpoint_fencing_token, " +
            "checkpoint_catalog_generation, checkpoint_catalog_hash, checkpoint_writer_generation, checkpoint_cutoff_epoch, checkpoint_configuration_hash, " +
            "checkpoint_database_identity, checkpoint_restore_identity, checkpoint_schema, checkpoint_started_at, checkpoint_completed_at, " +
            "checkpoint_object_count, checkpoint_byte_count, checkpoint_result, checkpoint_bytes, checkpoint_hash"
    }
}

/** Observer only: same original JdbcTemplate holder and actual SQL results; no success/identity/cleanup substitution. */
internal class TestVerifiedDeleteProbeJdbc(private val p: ProjectionActivationObservation, private val runtime: VersionBoundPersistenceConnectedFixture) : JdbcTemplate(runtime.pools.deletion) {
    var enabled = false
    val observations = linkedMapOf<PersistencePhaseContext, StepUpPhaseObservation>()
    val calls = mutableListOf<TestVerifiedDeleteSqlCall>()
    var before: (TestVerifiedDeleteSqlCall) -> Unit = {}
    var after: (TestVerifiedDeleteSqlCall) -> Unit = {}
    private var lostAssertion: Error? = null
    init { exceptionTranslator = SQLExceptionSubclassTranslator() }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>): List<T> = around(sql) { super.query(sql, mapper) }
    override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> = around(sql) { super.query(sql, mapper, *args) }
    override fun update(sql: String, vararg args: Any?): Int = around(sql) { super.update(sql, *args) }
    private fun <T> around(sql: String, action: () -> T): T {
        if (!enabled) return action()
        try {
            val phase = checkNotNull(PersistencePhaseOwnership.current())
            val path = TestRunVerifiedOwnerDeleteFixture.path(phase)
            assertTrue(path in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_RELOAD, PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY, PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY))
            val connection = (TransactionSynchronizationManager.getResource(runtime.pools.deletion) as ConnectionHolder).connection
            val lease = ownedPoolLease(connection)
            val observed = observations.getOrPut(phase) { StepUpPhaseObservation(phase, lease, connection.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_backend_pid(), txid_current()").use { rows -> check(rows.next()); rows.getInt(1) to rows.getLong(2) }
            }) }
            assertSame(observed.lease, lease)
            assertFalse(lease.completion.quiescent())
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, connection.transactionIsolation)
            assertTrue(p.advisory(connection, "complaint-maintenance-v1", "ShareLock"))
            assertEquals(path !== PersistencePhasePath.COMPLAINT_OWNER_DELETE_VERIFY, p.advisory(connection, "complaint-journal-epoch", "ShareLock"),
                "Short VERIFY participates in M but must not acquire E.")
            val call = TestVerifiedDeleteSqlCall(phase, path, sql)
            calls.add(call)
            before(call)
            return action().also { after(call) }
        } catch (problem: Error) { lostAssertion = problem; throw problem }
    }
    fun reset() { requireConnectionFree(); assertNoLostAssertions(); calls.clear(); observations.clear() }
    fun assertNoLostAssertions() { lostAssertion?.let { throw it } }
}

internal class TestVerifiedDeleteSqlCall(val phase: PersistencePhaseContext, val path: PersistencePhasePath, val sql: String)
