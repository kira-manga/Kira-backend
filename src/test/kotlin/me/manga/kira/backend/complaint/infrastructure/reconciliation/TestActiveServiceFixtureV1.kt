package me.manga.kira.backend.complaint.infrastructure.reconciliation

import jakarta.persistence.EntityManagerFactory
import kotlinx.serialization.json.Json
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.infrastructure.persistence.DeleteAllCounter
import me.manga.kira.backend.common.infrastructure.persistence.OrdinaryPersistenceAdmission
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLifecycleOwner
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipant
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLeaseCompletion
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePoolLaunchProfile
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTestScope
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.ColdIdentityRawProvidersV1
import me.manga.kira.backend.complaint.catalog.S3CatalogReadbackFixture
import me.manga.kira.backend.complaint.catalog.TestActiveFirstCutInputFixtureV1
import me.manga.kira.backend.complaint.catalog.TestOrdinaryDrainFixtureInputsV1
import me.manga.kira.backend.complaint.catalog.coldCatalogObjectsV1
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentInputV1
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentStorageV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentDocumentV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestDeploymentInputsV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceActiveRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestDeploymentInputFixture
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1
import me.manga.kira.backend.complaint.infrastructure.transaction.DeletionPersistenceAdmission
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import me.manga.kira.backend.security.fullTestJournal
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal fun testActiveServiceSessionsV1() = TestActiveServiceSessionsV1(
    secrets = AwsSecretVersionFixture.CREDENTIALS,
    sealBootstrap = AwsJournalKmsFixture.CREDENTIALS,
    ordinaryPublication = TestActiveFirstCutInputFixtureV1.ordinaryCredentials,
    recoveryRead = TestActiveInitialCheckpointHttpInputV1.CREDENTIALS,
    queueRecovery = TestActiveOwnerDeleteQueueHttpInputV1.CREDENTIALS,
    catalogPrimaryRead = S3CatalogReadbackFixture.credentials,
    catalogReplicaRead = S3CatalogReadbackFixture.credentials,
)

/**
 * Cold input grammar only, following TestActiveOwnerDeleteQueueColdInputsV1Test. All service
 * profiles are declared at birth; no actor, scope row, registration or successful assembly is made.
 * Used only to reach the first real Secrets-close refusal, before any persistence construction.
 */
internal fun testActiveServiceColdDocumentV1(): ComplaintTestDeploymentDocumentV1 {
    val scanner = TestActiveInitialCheckpointHttpInputV1(
        { error("Cold document cannot open STS.") }, { error("Cold document cannot open KMS.") }, { error("Cold document cannot open S3.") })
    val queue = TestActiveOwnerDeleteQueueHttpInputV1(
        { error("Cold document cannot open STS.") }, { error("Cold document cannot open KMS.") },
        { error("Cold document cannot open S3.") }, { error("Cold document cannot open SQS.") })
    val journal = TestOwnerDeleteJournalConfigurationV1.registeredAdminBatchErasure(fullTestJournal().declaration())
    val base = TestDeploymentInputFixture.document(journal)
    return base.copy(profile = ComplaintTestDeploymentInputsV1.INITIAL_CHECKPOINT_PROFILE,
        ordinaryDenial = TestOrdinaryDrainFixtureInputsV1().authorityInput(journal, base.retention.environment),
        activeFirstCut = TestActiveFirstCutInputFixtureV1.input(), ordinaryPublication = TestActiveFirstCutInputFixtureV1.ordinaryInput(),
        initialCheckpoint = scanner.input, activeOwnerDeleteQueue = queue.input,
        activeRecurrent = TestActiveRecurrentInputV1(1, TestActiveRecurrentStorageV1.PROFILE, TestActiveInitialCheckpointHttpInputV1.SESSION))
}

/**
 * The only MAIN inputs are protected original bytes, original synthetic session transport, raw
 * HTTP factories and an unchanged System.nanoTime sample. Reflection only observes originals;
 * it never installs a B JDBC template/owner, invokes B work, or supplies a cleanup result.
 */
internal class TestActiveServiceFixtureV1(private val a: TestActiveRecurrentFixtureV1) : AutoCloseable {
    private val caller = Thread.currentThread()
    private val inputBytes = a.first.p.f.rows.evidence.coldInputBytes()
    private val input = Json.decodeFromString(ComplaintTestDeploymentDocumentV1.serializer(), inputBytes.toString(Charsets.UTF_8))
    private val trustParent = Path.of(input.database.protectedTrustParent)
    private val originalD = a.process.canonicalBytes()
    private val originalRecurrentInventory = checkNotNull(a.process.activeRecurrent).inventory()
    private val originalQueueInventory = checkNotNull(a.process.activeOwnerDeleteQueue).inventory()
    private val raw = TestActiveRecurrentRawFixtureV1()
    private val identity = ColdIdentityRawProvidersV1(a.first.p.f.rows.evidence.coldSecretObjects(),
        coldCatalogObjectsV1(a.first.p.f.http.read).values.toList(), ::assertSqlReleased)
    private val phases = linkedMapOf<PersistencePhaseContext, Phase>()
    private val assertion = AtomicReference<AssertionError?>()
    private val attachments = mutableListOf<AutoCloseable>()
    private var parentCreated = false
    private var serveEntered = false
    private var independentMarkerRecheck = false
    private lateinit var service: TestActiveServiceV1
    private val process get() = field<VersionBoundTestNamespaceProcessV1>("process")
    private val registration get() = field<ComplaintTestNamespaceRegistrationV1>("registration")
    private val assembly get() = field<ComplaintTestProcessAssemblyV1>("assembly")

    fun serveAndStopDuringOriginalQueue() {
        a.assertReleased()
        assertNull(a.original); assertNull(a.queue.original)
        assertEquals("AUTHORIZED_DELETE", a.queue.receipt()["state"])
        assertEquals("VERIFIED", a.precursor.publication()["state"])
        assertEquals(0L, a.queue.count("complaint_deletion_journal_applied"))
        assertEquals(0L, a.first.native.offsetNanos, "No clock aging is used for this handoff.")
        val originalPutCount = a.raw.deletion.publisher.requests.count { it.kind == "PUT" }
        val originalKeyCount = a.raw.deletion.publisher.generated()

        // Retire A BEFORE B exists. This is not a workaround for B's owned teardown; shared driver
        // Timer custody forbids treating two live same-JVM roots as independent process evidence.
        val oldEmf = a.precursor.exchange.ordinary.entityManagerFactory
        oldEmf.close(); assertFalse(oldEmf.isOpen)
        a.registration.close()
        a.runtime.closeRegisteredRuntimeForRecovery()
        a.queue.assertReleasedAfterRuntimeRetirement()
        assertThrows<ComplaintTestNamespaceRegistrationExceptionV1> { a.registration.requireUsable() }
        assertTrue(Files.notExists(trustParent))
        Files.createDirectory(trustParent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        parentCreated = true // Empty host installation directory only; B owns its actual new generation.

        val scanner = checkNotNull(raw.factories.initialCheckpoint)
        service = TestActiveServiceV1.withHttpFixture(identity.secrets::httpClient, identity.catalog::httpClient,
            nanoClock = PersistenceNanoClock(::sample), wallClock = a.first.native::now,
            runtimeLaunchProfile = PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY,
            sts = a.first.native::nativeSts, kms = a.first.native::nativeKms, s3 = a.first.native::nativeS3,
            ordinarySts = { error("Service B cannot issue a new ordinary AUTH/PUT.") },
            ordinaryKms = { error("Service B cannot issue a new ordinary AUTH/PUT.") },
            ordinaryS3 = { error("Service B cannot issue a new ordinary AUTH/PUT.") },
            scannerSts = { remaining -> attachReaders(); scanner.sts(remaining) },
            scannerKms = { remaining -> attachReaders(); scanner.kms(remaining) },
            scannerS3 = { remaining -> attachReaders(); scanner.s3(remaining) },
            queueSqs = raw.queue.input.sqs, queueSts = raw.queue.input.sts,
            queueKms = raw.queue.input.kms, queueS3 = raw.queue.input.s3)

        val held = AtomicBoolean()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val ownerReturned = AtomicBoolean()
        val stoppedWhileHeld = AtomicBoolean()
        val stopperFailure = AtomicReference<Throwable?>()
        val observedCompletion = AtomicReference<TestActiveServiceStatusV1?>()
        var beforeQueue: Map<String, List<String>>? = null
        var beforeQueueCounters: Map<ComplaintCapacityCounter, DeleteAllCounter>? = null
        raw.queue.beforeSqs = { request -> observed {
            if (request.target() == "AmazonSQS.ReceiveMessage" && held.compareAndSet(false, true)) {
                assertRecurrentApplied()
                beforeQueue = a.queue.domainImage()
                beforeQueueCounters = a.counters() // The original queue observation is already reserved.
                entered.countDown()
                assertTrue(released.await(5, TimeUnit.SECONDS), "Foreign stop observer did not release the held native call.")
            }
        } }
        val stopper = Thread.ofPlatform().name("test-active-service-stop-observer").unstarted {
            try {
                assertTrue(entered.await(75, TimeUnit.SECONDS))
                assertTrue(held.get() && !ownerReturned.get())
                val originalQueue = field<TestActiveOwnerDeleteQueueV1>("queueOriginal")
                val originalRecurrent = field<TestActiveRecurrentV1>("recurrentOriginal")
                assertHeldParent(originalQueue)
                service.requestStop() // The only foreign-thread product control; never interrupt or close.
                assertHeldParent(originalQueue)
                assertSame(originalQueue, field<TestActiveOwnerDeleteQueueV1>("queueOriginal"))
                assertSame(originalRecurrent, field<TestActiveRecurrentV1>("recurrentOriginal"))
                stoppedWhileHeld.set(true)
            } catch (problem: Throwable) { stopperFailure.set(problem) }
            finally { service.requestStop(); released.countDown() }
            observedCompletion.set(service.awaitOwnerCompletion())
        }
        TestDeploymentInputFixture.withManifest(inputBytes) { path ->
            stopper.start()
            AutoCloseable {
                ownerReturned.set(true); entered.countDown(); released.countDown()
                stopper.join(5_000)
                assertFalse(stopper.isAlive, "Foreign observer cannot outlive the original owner completion.")
                stopperFailure.get()?.let { throw it }
            }.use {
                serveEntered = true
                assertEquals(TestActiveServiceStatusV1.STOPPED, service.serve(path, testActiveServiceSessionsV1()))
            }
            assertTrue(stoppedWhileHeld.get())
            assertEquals(TestActiveServiceStatusV1.STOPPED, observedCompletion.get())
            assertNull(service.failureCode)
            assertOriginalCleanup()
            assertEquals(checkNotNull(beforeQueue), a.queue.domainImage(), "Queue redelivery cannot reapply the original deletion.")
            assertEquals(checkNotNull(beforeQueueCounters), a.counters(), "The held queue cannot charge recovery or observation twice.")
            assertDurableAppliedIdentity()
            assertEquals(originalPutCount, a.raw.deletion.publisher.requests.count { it.kind == "PUT" })
            assertEquals(originalKeyCount, a.raw.deletion.publisher.generated())
            assertTrue(a.raw.order.isEmpty() && a.raw.requests.isEmpty() && a.raw.queue.order.isEmpty())
            assertNull(a.original); assertNull(a.queue.original)
            val native = providerCounts()
            val originalQueue = field<TestActiveOwnerDeleteQueueV1>("queueOriginal")
            val originalRecurrent = field<TestActiveRecurrentV1>("recurrentOriginal")
            service.close(); service.requestStop()
            assertThrows<TestActiveServiceExceptionV1> { service.serve(path, testActiveServiceSessionsV1()) }
            assertSame(originalQueue, field<TestActiveOwnerDeleteQueueV1>("queueOriginal"))
            assertSame(originalRecurrent, field<TestActiveRecurrentV1>("recurrentOriginal"))
            assertEquals(native, providerCounts(), "No redispatch or replacement cleanup original after stop.")
        }
    }

    private fun attachReaders() {
        if (attachments.isNotEmpty()) return
        assertSqlReleased(); assertFreshResources()
        // These raw servers stay bound to A's original event/PUT/key map, not B's distinct routing
        // object. MAIN receives only HTTP; B authenticates those bytes with its own acquired routing.
        attachments.add(raw.attachProducerForService(a.process, a.record, ::assertSqlReleased) {
            (ownedCutField(field<TestActiveRecurrentV1>("recurrentOriginal"), "scan") as? TestActiveRecurrentScanV1)?.passNumber ?: 0
        })
        attachments.add(raw.queue.attachRegisteredHttp(a.process, a.record, ::assertSqlReleased, ::assertSqlReleased, ::assertAckBoundary))
    }

    private fun assertFreshResources() {
        assertSame(caller, Thread.currentThread())
        assertNotSame(a.assembly, assembly); assertNotSame(a.process, process); assertNotSame(a.registration, registration)
        assertNotSame(a.process.pools, process.pools); assertNotSame(a.process.consumers, process.consumers)
        assertNotSame(a.process.consumers.journalRouting, process.consumers.journalRouting)
        assertTrue(a.record.event.belongsTo(a.process.consumers.journalRouting))
        assertFalse(a.record.event.belongsTo(process.consumers.journalRouting))
        assertNotSame(a.process.publicationLanes, process.publicationLanes); assertNotSame(a.process.ordinarySeal, process.ordinarySeal)
        assertNotSame(a.process.activeRecurrent, process.activeRecurrent)
        assertNotSame(a.process.activeOwnerDeleteQueue, process.activeOwnerDeleteQueue)
        assertArrayEquals(originalD, process.canonicalBytes())
        assertEquals(originalRecurrentInventory, checkNotNull(process.activeRecurrent).inventory())
        assertEquals(originalQueueInventory, checkNotNull(process.activeOwnerDeleteQueue).inventory())
        val ordinary = field<PersistencePhaseOwnership>("ordinaryOwnership")
        val deletion = field<PersistencePhaseOwnership>("deletionOwnership")
        assertNotSame(a.precursor.exchange.ordinary.ownership, ordinary); assertNotSame(a.precursor.deletionOwner, deletion)
        assertNotSame(a.precursor.deletion, field<JdbcTemplate>("deletionJdbc"))
        assertNotSame(a.precursor.audit, field<AuditService>("audit"))
        assertNotSame(a.precursor.exchange.ordinary.entityManagerFactory, field<EntityManagerFactory>("emf"))
        assertSame(process.pools.ordinary, ordinary.dataSource); assertSame(process.pools.deletion, deletion.dataSource)
        assertSame(process.pools.ordinary, field<JdbcTemplate>("ordinaryJdbc").dataSource)
        assertSame(process.pools.deletion, field<JdbcTemplate>("deletionJdbc").dataSource)
        assertSame(field<EntityManagerFactory>("emf"), ordinary.entityManagerFactory)
        val installed = checkNotNull((ownedCutField(registration, "installation") as AtomicReference<*>).get())
        assertSame(ordinary, ownedCutField(installed, "ownership"))
        assertSame(field<JdbcTemplate>("ordinaryJdbc"), ownedCutField(installed, "jdbc"))
        val attempt = field<ComplaintTestNamespaceActiveRegistrationAttemptV1>("registrationAttempt")
        assertSame(assembly, attempt.assembly); assertSame(process, attempt.process)
        assertSame(process, registration.process); attempt.requireActualCleanup()
        assertNotSame(checkNotNull(ownedCutField(attempt, "captured")), checkNotNull(ownedCutField(attempt, "rechecked")))
        assertEquals(true, ownedCutField(attempt, "issued"))
        assertEquals(2, phases.values.count { ownedCutField(it.phase, "testActiveRegistration") != null })
        assertTrue(process.consumers.journalConfiguration.declaration().limits.deadlines.scanCadenceMillis.toLong() >
            checkNotNull(process.activeRecurrent).totalAttemptMillis + checkNotNull(process.activeOwnerDeleteQueue).totalAttemptMillis)
    }

    /** Passive real-clock observer, including original B APPLY identity; no time adjustment or JDBC seam. */
    private fun sample(): Long = observed {
        if (::service.isInitialized && Thread.currentThread() === caller) PersistencePhaseOwnership.current()?.let { phase ->
            val entry = phases.getOrPut(phase) { observePhase(phase) }
            (ownedCutField(phase, "acquisition") as? PersistenceLeaseCompletion)?.let { lease ->
                entry.lease?.let { assertSame(it, lease) }; entry.lease = lease
            }
        }
        System.nanoTime()
    }

    private fun observePhase(phase: PersistencePhaseContext): Phase {
        val path = ownedCutField(phase, "path") as PersistencePhasePath
        val owner = ownedCutField(phase, "ownership")
        val active = ownedCutField(phase, "testActiveRegistration") as? ComplaintTestNamespaceActiveRegistrationAttemptV1
        val recurrent = ownedCutField(phase, "testRecurrent") as? TestActiveRecurrentV1
        val apply = ownedCutField(phase, "testRecurrentApply") as? TestActiveRecurrentApplyV1
        val queue = ownedCutField(phase, "testActiveQueue") as? TestActiveOwnerDeleteQueueV1
        assertEquals(1, listOfNotNull(active, recurrent, apply, queue).size)
        assertSame(if (apply != null || queue?.step === TestActiveOwnerDeleteQueueStepV1.APPLY)
            field<PersistencePhaseOwnership>("deletionOwnership") else process.pools.catalogCoordinator.ownership, owner)
        active?.let { assertSame(field<ComplaintTestNamespaceActiveRegistrationAttemptV1>("registrationAttempt"), it) }
        recurrent?.let {
            assertSame(field<TestActiveRecurrentV1>("recurrentOriginal"), it)
            assertNull(ownedCutField(service, "queueOriginal"), "Initial recurrent dispatch precedes every queue attempt.")
            if (it.step === TestActiveRecurrentStepV1.RECHECK_ENTRY) phases.values.singleOrNull {
                ownedCutField(it.phase, "testRecurrentApply") != null
            }?.let { prior -> prior.assertReleased(); independentMarkerRecheck = true }
        }
        apply?.let {
            val original = field<TestActiveRecurrentV1>("recurrentOriginal")
            val waiting = field<TestActiveRecurrentV1.RecoveryRequired>("recurrentResult")
            assertSame(original, it.original); assertSame(original.budget, it.budget)
            assertSame(waiting.nativeInput, it.input); assertSame(waiting, ownedCutField(original, "waiting"))
            assertSame(it, ownedCutField(original, "applying"))
            assertSame(field<PersistencePhaseOwnership>("deletionOwnership"), ownedCutField(it, "deletionOwner"))
            assertSame(field<JdbcTemplate>("deletionJdbc"), ownedCutField(it, "deletionJdbc"))
            assertSame(field<AuditService>("audit"), ownedCutField(checkNotNull(ownedCutField(it, "ownerApply")), "audit"))
        }
        queue?.let {
            assertSame(field<TestActiveOwnerDeleteQueueV1>("queueOriginal"), it)
            assertSame(field<PersistencePhaseOwnership>("deletionOwnership"), ownedCutField(it, "deletionOwner"))
            assertSame(field<JdbcTemplate>("deletionJdbc"), ownedCutField(it, "deletionJdbc"))
            assertSame(field<AuditService>("audit"), ownedCutField(checkNotNull(ownedCutField(it, "apply")), "audit"))
            assertInstanceOf(TestActiveRecurrentV1.Completed::class.java, ownedCutField(service, "recurrentResult"))
        }
        return Phase(phase, path, recurrent?.step, queue?.step)
    }

    private fun assertRecurrentApplied() {
        assertSqlReleased()
        val completed = field<TestActiveRecurrentV1.Completed>("recurrentResult")
        assertEquals(a.scope, completed.scope); assertEquals(2L, completed.cutoffEpoch)
        field<TestActiveRecurrentV1>("recurrentOriginal").requireActualCleanup()
        assertTrue(independentMarkerRecheck)
        assertEquals(1, phases.values.count { ownedCutField(it.phase, "testRecurrentApply") != null })
        assertEquals(TestActiveRecurrentStepV1.SUCCESS, phases.values.last { it.recurrentStep != null }.recurrentStep)
        assertEquals("SUCCESS", a.control()["checkpoint_result"])
        assertEquals(completed.fencingToken, a.control()["checkpoint_fencing_token"])
        assertEquals("COMPLETED", a.queue.receipt()["state"])
        assertEquals("APPLIED", a.precursor.publication()["state"])
        a.queue.assertExpectedAppliedObjects(); a.queue.assertOnlyAuthorizedReportsErased()
        assertTrue(a.scans().isEmpty() && a.entries().isEmpty())
        assertEquals(listOf("STS", "PASS1", "GET1", "DECRYPT", "GET1", "DECRYPT", "PASS2", "GET2", "DECRYPT"), raw.order)
        raw.assertDisposed()
    }

    private fun assertDurableAppliedIdentity() {
        listOf("complaint_idempotency_receipts", "installation_deletion_receipts", "complaint_journal_publications",
            "complaint_recovery_capacity_reservations", "complaint_installation_ids", "app_installations", "complaint_resource_ids").forEach {
            assertEquals(a.queue.countsBeforeQueue.getValue(it), a.queue.count(it), "No new N/P/L or credential authority: $it")
        }
        val receipt = a.queue.receipt()
        assertEquals(a.record.stored.version, receipt["external_object_version"])
        assertEquals(a.record.event.route.eventId, receipt["external_event_id"])
        assertEquals(a.queue.receiptBeforeQueue, a.queue.receiptIdentity())
        assertEquals(a.queue.proofBeforeQueue, a.queue.publicationProof())
        assertEquals(a.queue.grantBeforeQueue, a.queue.grantImage())
        val audits = a.queue.auditsBeforeQueue.toMutableMap()
        audits["COMPLAINT_DELETED"] = audits.getOrDefault("COMPLAINT_DELETED", 0L) + a.precursor.reports.size
        audits["COMPLAINT_RECOVERY_APPLIED"] = audits.getOrDefault("COMPLAINT_RECOVERY_APPLIED", 0L) + 1
        assertEquals(audits, a.queue.audits(), "Recurrent recovery plus queue redelivery applies/audits only once.")
    }

    /** Foreign read-only check while CountDownLatch holds the actual ReceiveMessage call. */
    private fun assertHeldParent(original: TestActiveOwnerDeleteQueueV1) {
        assertEquals(TestActiveServiceStatusV1.RUNNING, service.status)
        assertEquals(TestActiveOwnerDeleteQueueStepV1.RECEIVE, original.step)
        assertTrue(raw.queue.sqs.createdClients > raw.queue.sqs.closedClients)
        assertNull(ownedCutField(service, "queueCompleted"))
        assertFalse(process.pools.shutdownRequested())
        assertTrue(field<EntityManagerFactory>("emf").isOpen)
        assertFalse((ownedCutField(registration, "closed") as AtomicBoolean).get())
        listOf("registrationClosed", "factoryCloseReturned", "assemblyClosed").forEach { assertEquals(false, ownedCutField(service, it)) }
        assertSame(original, (ownedCutField(checkNotNull(process.activeOwnerDeleteQueue), "active") as AtomicReference<*>).get())
        listOf(process.pools.ordinary, process.pools.deletion, process.pools.catalogCoordinator.dataSource).forEach { assertFalse(actualPool(it).isClosed) }
    }

    private fun assertAckBoundary(deadLetter: Boolean) = observed {
        assertFalse(deadLetter); assertSqlReleased()
        val original = field<TestActiveOwnerDeleteQueueV1>("queueOriginal")
        assertEquals(TestActiveOwnerDeleteQueueStepV1.ACK, original.step)
        assertEquals(0, original.primaryAcked)
        phases.values.last { it.queueStep === TestActiveOwnerDeleteQueueStepV1.APPLY }.assertReleased()
        val recheck = phases.values.last()
        assertEquals(TestActiveOwnerDeleteQueueStepV1.RECHECK, recheck.queueStep); recheck.assertReleased()
        a.queue.assertExpectedAppliedObjects()
        assertEquals(raw.queue.s3Created, raw.queue.s3CloseReturned)
        assertEquals(raw.queue.kms.createdClients, raw.queue.kms.returnedClientCloses)
    }

    private fun assertOriginalCleanup() {
        service.requireCleanupProven(); assertSqlReleased()
        field<TestActiveRecurrentV1>("recurrentOriginal").requireActualCleanup()
        field<TestActiveOwnerDeleteQueueV1>("queueOriginal").requireActualCleanup()
        field<ComplaintTestNamespaceActiveRegistrationAttemptV1>("registrationAttempt").requireActualCleanup()
        phases.values.forEach { it.assertReleased() }
        val completed = field<TestActiveOwnerDeleteQueueV1.Completed>("queueCompleted")
        assertEquals(a.scope, completed.scope); assertEquals(1, completed.primaryAcknowledged); assertEquals(0, completed.dlqAcknowledged)
        assertEquals(listOf("synthetic-primary-receipt-1"), raw.queue.ackRequests)
        assertEquals(listOf("STS", "GetQueueUrl:PRIMARY", "GetQueueAttributes:PRIMARY", "ReceiveMessage:PRIMARY", "GET", "DECRYPT",
            "DeleteMessage:PRIMARY", "GetQueueUrl:DLQ", "GetQueueAttributes:DLQ", "ReceiveMessage:DLQ"), raw.queue.order)
        assertEquals("SETTLED", a.queue.observation()?.get("state"))
        assertNull(a.control()["lease_owner"]); assertNull(a.control()["lease_expires_at"])
        assertFalse(field<EntityManagerFactory>("emf").isOpen)
        assertTrue((ownedCutField(registration, "closed") as AtomicBoolean).get())
        assertEquals(0, field<OrdinaryPersistenceAdmission>("ordinaryAdmission").activeOwners())
        assertEquals(0, field<DeletionPersistenceAdmission>("deletionAdmission").activeOwners().totalOwners)
        assertTrue(process.pools.shutdownRequested() && process.pools.poolsEndedForTrust())
        val lifecycle = PgLifecycleTestScope(ownedCutField(assembly, "owner") as PersistenceJdbcLifecycleOwner)
        // Observation only: never start/close this assertion handle or invoke a fallback owner shutdown.
        assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, lifecycle.root.shutdownObservation())
        assertEquals(PersistenceLifecycleObservation.EPOCH_ROTATION_LOCAL_ENDED, lifecycle.root.epochRotationShutdownObservation())
        val epoch = ownedCutField(lifecycle.root, "epochRotationParticipant") as PersistenceJdbcParticipant
        assertTrue((lifecycle.actors() + lifecycle.actors(epoch)).all { it.termination().ended() && !it.thread.isAlive })
        listOf(process.pools.ordinary, process.pools.deletion, process.pools.catalogCoordinator.dataSource).forEach {
            assertTrue(actualPool(it).isClosed); assertFalse(actualPool(it).isRunning)
        }
        Files.list(trustParent).use { assertEquals(0L, it.count(), "Only B's own cleanup may remove its trust generation.") }
        identity.assertClosed(); raw.assertDisposed(); raw.queue.assertDisposed(); a.first.native.assertDisposed()
        assertion.get()?.let { throw it }
    }

    private fun assertSqlReleased() {
        requireConnectionFree(); assertSame(caller, Thread.currentThread())
        assertNull(PersistencePhaseOwnership.current()); assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
        if (::service.isInitialized) (ownedCutField(service, "process") as? VersionBoundTestNamespaceProcessV1)?.let {
            assertEquals(0, it.pools.catalogCoordinator.activeSnapshotOwners())
        }
    }
    private fun providerCounts() = listOf(identity.secrets.requests.size, identity.catalog.requests.size, raw.requests.size,
        raw.sts.requests.size, raw.kms.requests.size, raw.queue.requests.size, raw.queue.sqs.requests.size, raw.queue.ackRequests.size,
        a.first.native.requests.size, a.first.native.sts.requests.size, a.first.native.kms.requests.size)
    private inline fun <reified T> field(name: String): T = ownedCutField(service, name) as T
    private fun <T> observed(work: () -> T): T = try { work() } catch (problem: AssertionError) { assertion.compareAndSet(null, problem); throw problem }

    private class Phase(val phase: PersistencePhaseContext, val path: PersistencePhasePath,
        val recurrentStep: TestActiveRecurrentStepV1?, val queueStep: TestActiveOwnerDeleteQueueStepV1?) {
        var lease: PersistenceLeaseCompletion? = null
        fun assertReleased() {
            assertEquals(PersistenceDatabaseOutcome.COMMITTED, phase.databaseOutcome())
            assertTrue(checkNotNull(lease).quiescent())
            val original = listOf("testActiveRegistration", "testRecurrent", "testRecurrentApply", "testActiveQueue")
                .mapNotNull { ownedCutField(phase, it) }.single()
            assertTrue(when (original) {
                is ComplaintTestNamespaceActiveRegistrationAttemptV1 -> phase.testActiveRegistrationCleanupProven(original)
                is TestActiveRecurrentV1 -> phase.testActiveRecurrentCleanupProven(original)
                is TestActiveRecurrentApplyV1 -> phase.testActiveRecurrentApplyCleanupProven(original)
                is TestActiveOwnerDeleteQueueV1 -> phase.testActiveOwnerDeleteQueueCleanupProven(original)
                else -> false
            }, path.name)
        }
    }

    override fun close() {
        // No fallback assembly/factory/registration teardown. The service's failed original remains
        // retained if its own cleanup is unproved; raw observation detachment grants no authority.
        if (::service.isInitialized && !serveEntered) service.close()
        AutoCloseable {
            val proved = !::service.isInitialized || runCatching { service.requireCleanupProven() }.isSuccess
            if (parentCreated && proved) Files.delete(trustParent) // Nonrecursive: unknown children must remain.
        }.use { attachments.asReversed().forEach { it.close() } }
    }
}
