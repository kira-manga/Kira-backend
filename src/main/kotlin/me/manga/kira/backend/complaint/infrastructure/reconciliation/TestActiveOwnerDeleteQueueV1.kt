package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceComplaintMaintenanceGateV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceDatabaseOutcome
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipantRole
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteApplyOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeletePhaseOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllApplyOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteApplyOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeletePhaseOperation
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteApplyStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllApplyStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllVerificationStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteApplyStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteVerificationStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteVerificationStore
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllApplyInputV1
import me.manga.kira.backend.complaint.infrastructure.TestAdminDeleteApplyInputV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteApplyInputV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteLocalGraphV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationTailV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogReadbackRefreshCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReadbackHttpPairV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationCanonicalV3
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReadbackV3
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackAdapter
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import me.manga.kira.backend.complaint.infrastructure.catalog.preferSignerRotationCleanup
import me.manga.kira.backend.complaint.infrastructure.catalog.withSignerRotationCleanup
import me.manga.kira.backend.complaint.infrastructure.journal.TestActiveQueueJournalReaderV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestActiveQueueReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestActiveOwnerDeleteSqsV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.TestActiveQueueJsonV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import me.manga.kira.backend.security.aws.EpochSealStsClientOwner
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.io.InterruptedIOException
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * Explicit bounded ACTIVE accelerator: primary1 + DLQ1, exact registered ordinary deletion families.
 * ALL requires its retained VERIFIED primary or exact completed replay; missing ALL N/P/L and
 * PREPARED-without-VERIFY remain unfinished recovery requirements, not ackable shortcuts. No loop/scheduler or
 * completeness/checkpoint/capability issuer. Every receipt is acked ONLY after its native exact
 * recovery APPLY actually commits/releases and a fresh full-D/lease check commits/releases.
 * A later observation failure cannot undo an earlier safe ack and never creates HEALTHY.
 */
internal class TestActiveOwnerDeleteQueueV1 private constructor(
    internal val registration: ComplaintTestNamespaceRegistrationV1,
    private val assembly: ComplaintTestProcessAssemblyV1,
    private val deletionOwner: PersistencePhaseOwnership,
    private val deletionJdbc: JdbcTemplate,
    audit: AuditService,
    private val catalogHttp: (() -> SdkHttpClient)?,
    private val clock: Clock,
) {
    internal val process = registration.process
    internal val recipe = checkNotNull(process.activeOwnerDeleteQueue)
    internal val coordinator = process.pools.catalogCoordinator
    internal val routing = process.consumers.journalRouting
    internal val budget = PersistenceTimeBudget.start(recipe.totalAttemptMillis, TestActiveSealNanoClockV1(coordinator.ownership.nanoClock))
    internal val identity = TestActiveFirstCutIdentityV1.fromRegistration(registration)
    internal val scope = identity.scope
    internal val attemptId = UUID.randomUUID()
    internal var leaseToken = 0L
        private set
    internal var leaseExpiresAt: Instant? = null
        private set
    internal var pollingStartedAt: Instant = Instant.EPOCH
        private set
    internal var primaryAcked = 0
        private set
    internal var dlqAcked = 0
        private set
    internal var step = TestActiveOwnerDeleteQueueStepV1.READ
        private set
    internal val path: PersistencePhasePath get() = if (step === TestActiveOwnerDeleteQueueStepV1.APPLY) when (kind()) {
        ComplaintJournalDeletionKindV1.OWNER_DELETE -> PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY
        ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY
        ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY
        else -> throw TestActiveOwnerDeleteQueueExceptionV1()
    } else PersistencePhasePath.COMPLAINT_TEST_ACTIVE_OWNER_DELETE_QUEUE
    private val caller = Thread.currentThread()
    private val failure = AtomicReference<Throwable?>()
    private var started = false
    private var finished = false
    private var successful = false
    private var phaseEntered = false
    @Volatile private var phase: PersistencePhaseContext? = null
    private var cleanupUncertain = false
    private var coordinatorJdbc: JdbcTemplate? = null
    private var captured: TestActiveOwnerDeleteQueueOperationV1? = null
    private var acquired: TestActiveOwnerDeleteQueueOperationV1? = null
    private var rechecked: TestActiveOwnerDeleteQueueOperationV1? = null
    private var settled: TestActiveOwnerDeleteQueueOperationV1? = null
    private var lastDatabaseTime: Instant? = null
    private var nativeClaimed = false
    private var nativeCreating = false
    private val principal = AtomicReference<EpochSealStsClientOwner?>()
    @Volatile private var queue: TestActiveOwnerDeleteSqsV1? = null
    @Volatile private var reader: TestActiveQueueJournalReaderV1? = null
    private var delivery: TestActiveOwnerDeleteSqsV1.Delivery? = null
    private var locator: TestActiveQueueJsonV1.Locator? = null
    private var readback: TestActiveQueueReadbackV1? = null
    private var recoveryInput: TestOwnerDeleteApplyInputV1? = null
    private var recoveryOperation: ComplaintOwnerDeleteApplyOperation? = null
    private var allRecoveryInput: OwnerDeleteAllApplyInputV1? = null
    private var allRecoveryOperation: ComplaintOwnerDeleteAllApplyOperation? = null
    private var adminRecoveryInput: TestAdminDeleteApplyInputV1? = null
    private var adminRecoveryOperation: ComplaintAdminDeleteApplyOperation? = null
    private var recoveryControls = false
    private var recoveryRun = false
    private val graph: TestOwnerDeleteLocalGraphV1
    private val apply: JdbcComplaintOwnerDeleteApplyStore
    private val allApply: JdbcComplaintOwnerDeleteAllApplyStore?
    private val adminApply: JdbcComplaintAdminDeleteApplyStore?
    private val readbackIdentity = registration.activeReadbackArguments()
    private val expected = CatalogTestRunActivationCanonicalV3.fromRetained(process, identity.installationLimit)
    private val construction = S3CatalogReadbackAdapter.Construction()
    private val readbackBudget = budget.capped(process.catalogReadback.totalAttemptMillis)
    private val http = CatalogSignerRotationReadbackHttpPairV1(this, readbackBudget)
    private var readbackReserved = false
    private var readbackStage = false
    @Volatile private var providerClosed = false
    @Volatile private var providerFailure: Throwable? = null
    private var raw: CatalogTestRunActivationReadbackV3? = null

    init {
        requireConnectionFree(); registration.requireActiveIdentityTarget(assembly)
        recipe.requireRetained(routing, process.pools)
        deletionOwner.requireBoundComplaintDeletion(process.pools)
        requireQueue(deletionJdbc.dataSource === process.pools.deletion && process.pools.deletion.businessReady() &&
            scope == routing.journalConfiguration.scope.id && identity.writer.toString() == routing.journalConfiguration.declaration().writer.generationId &&
            process.initialCheckpoint != null && process.activeFirstCut != null)
        // Deliberately NOT the SEALED/terminal continuation resource admission.
        graph = TestOwnerDeleteLocalGraphV1(JdbcTemplate(process.pools.ordinary), deletionJdbc, process.consumers.ingressAdmission,
            routing, process.consumers.capacityPolicy, process.publicationLanes, process.desiredGeneration, registration)
        val capacity = JdbcComplaintCapacityStore(deletionJdbc, process.consumers.capacityPolicy.digestBytes())
        val authorization = JdbcComplaintOwnerDeleteStore(deletionJdbc, capacity, audit, graph, codec = null)
        val verification = JdbcComplaintOwnerDeleteVerificationStore(deletionJdbc, graph, authorization)
        apply = JdbcComplaintOwnerDeleteApplyStore(deletionJdbc, capacity, audit, graph, authorization, verification)
        allApply = if (routing.journalConfiguration.ownerDeleteAll) {
            val store = JdbcComplaintOwnerDeleteAllStore(deletionJdbc, capacity, audit, graph, codec = null)
            JdbcComplaintOwnerDeleteAllApplyStore(deletionJdbc, capacity, audit, graph,
                JdbcComplaintOwnerDeleteAllVerificationStore(deletionJdbc, graph, store))
        } else null
        adminApply = if (routing.journalConfiguration.registeredAdminDelete) {
            val store = JdbcComplaintAdminDeleteStore(deletionJdbc, capacity, audit, graph, codec = null)
            JdbcComplaintAdminDeleteApplyStore(deletionJdbc, capacity, audit, graph, store,
                JdbcComplaintAdminDeleteVerificationStore(deletionJdbc, graph, store))
        } else null
    }

    fun poll(primaryCatalog: AwsSessionCredentials, replicaCatalog: AwsSessionCredentials): Completed {
        throwIfSignalled(); requireQueue(caller === Thread.currentThread() && !started); started = true
        try {
            requireRunning(); recipe.claim(this); nativeClaimed = true
            coordinator.catalogRefreshCustody.reserveTestActiveOwnerDeleteQueue(this); readbackReserved = true
            captured = execute(TestActiveOwnerDeleteQueueStepV1.READ)
            observeCatalog(primaryCatalog, replicaCatalog)
            acquired = execute(TestActiveOwnerDeleteQueueStepV1.ACQUIRE)
            step = TestActiveOwnerDeleteQueueStepV1.PRINCIPAL
            recipe.authenticate(this)
            execute(TestActiveOwnerDeleteQueueStepV1.RECHECK)
            nativeCreating = true
            queue = recipe.queue(this); nativeCreating = false
            receiveAndApply(deadLetter = false)
            receiveAndApply(deadLetter = true)
            checkNotNull(queue).allPollsReturned(); checkNotNull(queue).close()
            requireNativeReleased(recipe)
            settled = execute(TestActiveOwnerDeleteQueueStepV1.SETTLE)
            checkNotNull(settled).requireReleased(); requireRunning(); successful = true
        } catch (problem: Throwable) { observeFailure(problem) }
        finally {
            runCatching { reader?.close() }.exceptionOrNull()?.let(::observeFailure)
            runCatching { queue?.close() }.exceptionOrNull()?.let(::observeFailure)
            runCatching(::closePrincipal).exceptionOrNull()?.let(::observeFailure)
            runCatching(::closeReadback).exceptionOrNull()?.let(::observeFailure)
            if (nativeClaimed) runCatching { recipe.release(this); nativeClaimed = false }.exceptionOrNull()?.let(::observeFailure)
            if (readbackReserved) runCatching {
                coordinator.catalogRefreshCustody.releaseTestActiveOwnerDeleteQueueAfterCleanup(this); readbackReserved = false
            }.exceptionOrNull()?.let(::observeFailure)
            readbackIdentity.filterIsInstance<ByteArray>().forEach { it.fill(0) }
            runCatching { budget.remainingMillis(1) }.exceptionOrNull()?.let(::observeFailure)
            finished = true
        }
        throwIfSignalled(); requireQueue(successful)
        return Completed.issue(this)
    }

    private fun receiveAndApply(deadLetter: Boolean) {
        requireCurrentRunning(); recoveryInput = null; recoveryOperation = null; readback = null; locator = null; rechecked = null
        allRecoveryInput = null; allRecoveryOperation = null; adminRecoveryInput = null; adminRecoveryOperation = null
        recoveryControls = false; recoveryRun = false
        step = TestActiveOwnerDeleteQueueStepV1.RECEIVE
        delivery = checkNotNull(queue).receive(deadLetter)
        execute(TestActiveOwnerDeleteQueueStepV1.RECHECK) // Includes an empty poll or a late native return.
        val selected = delivery ?: return
        if (deadLetter) LOG.warn("ACTIVE TEST deletion DLQ delivery observed; bounded recovery attempted, not queue-health evidence.")
        step = TestActiveOwnerDeleteQueueStepV1.JOURNAL
        locator = selected.locator() // Untrusted notification: only the retained concrete reader can mint readback.
        reader?.requireClosed()
        nativeCreating = true
        val native = recipe.reader(this).also { reader = it; nativeCreating = false }
        readback = native.read()
        when (kind()) {
            ComplaintJournalDeletionKindV1.OWNER_DELETE -> recoveryInput = apply.captureRegisteredQueueRecovery(this)
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> allRecoveryInput = checkNotNull(allApply).captureRegisteredQueueRecovery(this)
            ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE ->
                adminRecoveryInput = checkNotNull(adminApply).captureRegisteredQueueRecovery(this)
            else -> throw TestActiveOwnerDeleteQueueExceptionV1()
        }
        native.close(); native.requireClosed() // Every S3/KMS graph/key lease/byte buffer has actually retired before APPLY.
        recover()
        rechecked = execute(TestActiveOwnerDeleteQueueStepV1.RECHECK)
        step = TestActiveOwnerDeleteQueueStepV1.ACK
        checkNotNull(queue).ack(selected)
        requireQueue(selected.acked)
        if (deadLetter) dlqAcked = 1 else primaryAcked = 1 // No count on uncertain DeleteMessage response/cleanup.
        delivery = null; locator = null; readback = null
        execute(TestActiveOwnerDeleteQueueStepV1.RECHECK)
    }

    private fun recover() {
        requireConnectionFree(); requireCurrentRunning(); checkNotNull(reader).requireClosed()
        step = TestActiveOwnerDeleteQueueStepV1.APPLY
        val selected = when (kind()) {
            ComplaintJournalDeletionKindV1.OWNER_DELETE -> deletionOwner.enterTestActiveQueueOwnerDeleteRecovery(this)
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> deletionOwner.enterTestActiveQueueOwnerDeleteAllRecovery(this)
            ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> deletionOwner.enterTestActiveQueueAdminDeleteRecovery(this)
            else -> throw TestActiveOwnerDeleteQueueExceptionV1()
        }
        var operation: ComplaintOwnerDeleteApplyOperation? = null
        var allOperation: ComplaintOwnerDeleteAllApplyOperation? = null
        var adminOperation: ComplaintAdminDeleteApplyOperation? = null
        try {
            selected.begin()
            when (kind()) {
                ComplaintJournalDeletionKindV1.OWNER_DELETE -> operation = apply.apply(checkNotNull(recoveryInput))
                ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> allOperation = checkNotNull(allApply).apply(checkNotNull(allRecoveryInput))
                ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE ->
                    adminOperation = checkNotNull(adminApply).apply(checkNotNull(adminRecoveryInput))
                else -> throw TestActiveOwnerDeleteQueueExceptionV1()
            }
            requireRunning(); selected.commit()
        } catch (problem: Throwable) { observeFailure(problem); selected.recordFailure(problem) }
        finally {
            try { runCatching(selected::finish).exceptionOrNull()?.let(::observeFailure) }
            finally { observePhaseCleanup(selected) }
        }
        throwIfSignalled(); requireRecoveredOperation()
        requireQueue(operation === recoveryOperation && allOperation === allRecoveryOperation && adminOperation === adminRecoveryOperation &&
            listOfNotNull(operation, allOperation, adminOperation).size == 1 && phase == null && !phaseEntered)
    }

    private fun kind() = checkNotNull(readback).event.comparison.eventKind
    private fun requireRecoveredOperation() {
        requireQueue(listOfNotNull(recoveryOperation, allRecoveryOperation, adminRecoveryOperation).size == 1)
        when (kind()) {
            ComplaintJournalDeletionKindV1.OWNER_DELETE -> checkNotNull(recoveryOperation).requireRecovered()
            ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> checkNotNull(allRecoveryOperation).requireRecovered()
            ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> checkNotNull(adminRecoveryOperation).requireRecovered()
            else -> throw TestActiveOwnerDeleteQueueExceptionV1()
        }
    }

    private fun execute(selected: TestActiveOwnerDeleteQueueStepV1): TestActiveOwnerDeleteQueueOperationV1 {
        requireConnectionFree(); requireRunning(); requireQueue(phase == null && !phaseEntered)
        step = selected
        return coordinator.testActiveOwnerDeleteQueue.execute(this)
    }
    internal fun requireState(value: TestActiveOwnerDeleteQueueRowsV1.Current) {
        requireRunning(); captured?.current?.requireSame(value)
        requireQueue(lastDatabaseTime?.isAfter(value.sampledAt) != true); lastDatabaseTime = value.sampledAt
    }
    internal fun retainLease(operation: TestActiveOwnerDeleteQueueOperationV1, before: Long, value: TestActiveOwnerDeleteQueueRowsV1.Current) {
        requireRunning(); requireQueue(operation.original === this && step === TestActiveOwnerDeleteQueueStepV1.ACQUIRE && leaseToken == 0L &&
            before < Long.MAX_VALUE && value.token == before + 1 && value.owner == attemptId && checkNotNull(value.expiresAt).isAfter(value.sampledAt))
        leaseToken = value.token; leaseExpiresAt = value.expiresAt
    }
    internal fun retainPolling(operation: TestActiveOwnerDeleteQueueOperationV1, value: TestActiveOwnerDeleteQueueRowsV1.Observation) {
        requireRunning(); requireQueue(operation.original === this && step === TestActiveOwnerDeleteQueueStepV1.ACQUIRE &&
            value.owner == attemptId && value.token == leaseToken && value.state == "POLLING")
        pollingStartedAt = value.startedAt
    }
    internal fun requireObservation(value: TestActiveOwnerDeleteQueueRowsV1.Observation) {
        requireCurrentRunning(); checkNotNull(acquired).requireCommittedComparison()
        checkNotNull(checkNotNull(acquired).observation).requireSame(value); value.requirePolling(this)
    }
    internal fun observationIdentityArguments(): Array<Any?> = arrayOf(scope, identity.desiredGeneration, identity.implementationSchema,
        identity.databaseIdentity, identity.restoreIdentity, identity.writer, identity.configurationHash(), identity.journalHash(),
        identity.acceptedCatalogGeneration, identity.acceptedCatalogHash(), identity.trustBundleHash(), identity.catalogWriter)

    internal fun requireQueueNative(selected: TestActiveOwnerDeleteSqsV1) {
        requireConnectionFree(); requireCurrentRunning()
        requireQueue(queue === selected && nativeClaimed && !nativeCreating && phase == null && !phaseEntered &&
            step in setOf(TestActiveOwnerDeleteQueueStepV1.RECEIVE, TestActiveOwnerDeleteQueueStepV1.JOURNAL, TestActiveOwnerDeleteQueueStepV1.ACK))
        recipe.requireOwned(this)
    }
    internal fun requireJournalNative(selected: TestActiveQueueJournalReaderV1) {
        requireConnectionFree(); requireCurrentRunning()
        requireQueue(reader === selected && step === TestActiveOwnerDeleteQueueStepV1.JOURNAL && locator != null && delivery != null &&
            principal.get() == null && nativeClaimed && !nativeCreating && phase == null && !phaseEntered)
        recipe.requireOwned(this)
    }
    internal fun journalLocator(selected: TestActiveQueueJournalReaderV1): TestActiveQueueJsonV1.Locator { requireJournalNative(selected); return checkNotNull(locator) }
    internal fun requireDecoded(selected: TestActiveQueueJournalReaderV1, event: TestOwnerDeleteJournalEventV1) {
        requireJournalNative(selected)
        val j = routing.journalConfiguration
        val count = event.complaintIds().size
        requireQueue(event.belongsTo(routing) && event.comparison.scope.id == scope &&
            event.comparison.epoch in 1..checkNotNull(captured).current.epoch && event.route.objectKey == checkNotNull(locator).key &&
            when (event.comparison.eventKind) {
                ComplaintJournalDeletionKindV1.OWNER_DELETE -> count == 1
                ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> j.ownerDeleteAll && allApply != null && count in 0..100
                ComplaintJournalDeletionKindV1.ADMIN_DELETE -> j.registeredAdminDelete && adminApply != null && count == 1
                ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> j.registeredAdminBatchDelete && adminApply != null && count in 1..50
                else -> false
            })
    }
    internal fun remainingNativeMillis(ceiling: Int): Int { requireConnectionFree(); requireCurrentRunning(); return budget.remainingMillis(ceiling.toLong()).toInt() }
    internal fun retainPrincipal(owner: EpochSealStsClientOwner) {
        requireConnectionFree(); requireCurrentRunning(); requireQueue(step === TestActiveOwnerDeleteQueueStepV1.PRINCIPAL && principal.compareAndSet(null, owner))
    }
    internal fun principalReleased(owner: EpochSealStsClientOwner) {
        requireConnectionFree(); requireCurrentRunning(); requireQueue(principal.get() === owner)
        owner.close(); requireQueue(principal.compareAndSet(owner, null))
    }
    private fun closePrincipal() { principal.get()?.let { it.close(); requireQueue(principal.compareAndSet(it, null)) } }
    internal fun requireAckInput(selected: TestActiveOwnerDeleteSqsV1, value: TestActiveOwnerDeleteSqsV1.Delivery) {
        requireQueueNative(selected); requireQueue(step === TestActiveOwnerDeleteQueueStepV1.ACK && delivery === value &&
            (if (value.deadLetter) dlqAcked == 0 else primaryAcked == 0))
        checkNotNull(reader).requireClosed(); requireRecoveredOperation()
        val check = checkNotNull(rechecked); requireQueue(check.step === TestActiveOwnerDeleteQueueStepV1.RECHECK)
        check.requireReleased(); check.current.requireLease(this); requireQueue(principal.get() == null)
    }
    internal fun requireNativeReleased(selected: VersionBoundTestActiveOwnerDeleteQueueV1) {
        requireConnectionFree(); requireRecipe(selected); requireQueue(!nativeCreating && principal.get() == null && phase == null && !phaseEntered && !cleanupUncertain)
        reader?.requireClosed(); queue?.requireClosed()
    }
    internal fun requireSettlementReady() {
        requireCurrentRunning(); requireQueue(step === TestActiveOwnerDeleteQueueStepV1.SETTLE && principal.get() == null && !nativeCreating && delivery == null)
        reader?.requireClosed(); checkNotNull(queue).allPollsReturned(); checkNotNull(queue).requireClosed()
    }
    internal fun requireRecipe(selected: VersionBoundTestActiveOwnerDeleteQueueV1) =
        requireQueue(caller === Thread.currentThread() && selected === recipe && process.activeOwnerDeleteQueue === selected)

    internal fun ownedRecoveryReadback(store: JdbcComplaintOwnerDeleteApplyStore, selectedGraph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate): TestActiveQueueReadbackV1 {
        requireRecoveryCapture(selectedGraph, jdbc)
        requireQueue(store === apply && kind() === ComplaintJournalDeletionKindV1.OWNER_DELETE)
        return checkNotNull(readback).also { it.requireOriginal(this) }
    }
    internal fun ownedRecoveryReadback(store: JdbcComplaintOwnerDeleteAllApplyStore, selectedGraph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate): TestActiveQueueReadbackV1 {
        requireRecoveryCapture(selectedGraph, jdbc)
        requireQueue(store === allApply && kind() === ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL)
        return checkNotNull(readback).also { it.requireOriginal(this) }
    }
    internal fun ownedRecoveryReadback(store: JdbcComplaintAdminDeleteApplyStore, selectedGraph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate): TestActiveQueueReadbackV1 {
        requireRecoveryCapture(selectedGraph, jdbc)
        requireQueue(store === adminApply && kind() in setOf(ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE))
        return checkNotNull(readback).also { it.requireOriginal(this) }
    }
    private fun requireRecoveryCapture(selectedGraph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate) {
        requireConnectionFree(); requireRecoveryPersistence(deletionOwner, jdbc, selectedGraph)
        requireQueue(step === TestActiveOwnerDeleteQueueStepV1.JOURNAL && recoveryInput == null && allRecoveryInput == null &&
            adminRecoveryInput == null && delivery != null)
    }
    internal fun requireRecoveryInput(input: TestOwnerDeleteApplyInputV1) {
        requireRunning(); requireQueue(step === TestActiveOwnerDeleteQueueStepV1.APPLY && input === recoveryInput && delivery != null &&
            kind() === ComplaintJournalDeletionKindV1.OWNER_DELETE && allRecoveryInput == null && adminRecoveryInput == null)
        checkNotNull(reader).requireClosed()
    }
    internal fun requireRecoveryInput(input: OwnerDeleteAllApplyInputV1) {
        requireRunning(); requireQueue(step === TestActiveOwnerDeleteQueueStepV1.APPLY && input === allRecoveryInput && delivery != null &&
            kind() === ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL && recoveryInput == null && adminRecoveryInput == null)
        checkNotNull(reader).requireClosed()
    }
    internal fun requireRecoveryInput(input: TestAdminDeleteApplyInputV1) {
        requireRunning(); requireQueue(step === TestActiveOwnerDeleteQueueStepV1.APPLY && input === adminRecoveryInput && delivery != null &&
            kind() in setOf(ComplaintJournalDeletionKindV1.ADMIN_DELETE, ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE) &&
            recoveryInput == null && allRecoveryInput == null)
        checkNotNull(reader).requireClosed()
    }
    internal fun requireRecoveryPersistence(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate, selectedGraph: TestOwnerDeleteLocalGraphV1) {
        requireRunning(); requireQueue(ownership === deletionOwner && jdbc === deletionJdbc && selectedGraph === graph && jdbc.dataSource === process.pools.deletion)
        deletionOwner.requireBoundComplaintDeletion(process.pools); graph.requireDeletion(jdbc)
    }
    internal fun authenticateRecoveryAndControls(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate, operation: ComplaintOwnerDeletePhaseOperation) {
        requireRecoveryAuthentication(ownership, jdbc)
        val selected = operation as? ComplaintOwnerDeleteApplyOperation ?: throw TestActiveOwnerDeleteQueueExceptionV1()
        selected.requireRegisteredQueueRecovery(this); recoveryOperation = selected
        authenticateAs(jdbc, PersistenceJdbcParticipantRole.DELETION)
        TestActiveOwnerDeleteQueueOperationV1.lockRecoveryControls(jdbc, this); recoveryControls = true
    }
    internal fun authenticateRecoveryAndControls(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate, operation: ComplaintOwnerDeleteAllApplyOperation) {
        requireRecoveryAuthentication(ownership, jdbc)
        operation.requireRegisteredQueueRecovery(this); allRecoveryOperation = operation
        authenticateAs(jdbc, PersistenceJdbcParticipantRole.DELETION)
        TestActiveOwnerDeleteQueueOperationV1.lockRecoveryControls(jdbc, this); recoveryControls = true
    }
    internal fun authenticateRecoveryAndControls(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate, operation: ComplaintAdminDeletePhaseOperation) {
        requireRecoveryAuthentication(ownership, jdbc)
        val selected = operation as? ComplaintAdminDeleteApplyOperation ?: throw TestActiveOwnerDeleteQueueExceptionV1()
        selected.requireRegisteredQueueRecovery(this); adminRecoveryOperation = selected
        authenticateAs(jdbc, PersistenceJdbcParticipantRole.DELETION)
        TestActiveOwnerDeleteQueueOperationV1.lockRecoveryControls(jdbc, this); recoveryControls = true
    }
    private fun requireRecoveryAuthentication(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireRecoveryPersistence(ownership, jdbc, graph)
        requireQueue(step === TestActiveOwnerDeleteQueueStepV1.APPLY && !recoveryControls &&
            recoveryOperation == null && allRecoveryOperation == null && adminRecoveryOperation == null)
    }
    internal fun requireRecoveryHolder(jdbc: JdbcTemplate) {
        requireRecoveryPersistence(deletionOwner, jdbc, graph)
        requireQueue(step === TestActiveOwnerDeleteQueueStepV1.APPLY &&
            listOfNotNull(recoveryOperation, allRecoveryOperation, adminRecoveryOperation).size == 1 && phase != null && phaseEntered)
    }
    internal fun requireRecoveryControls(jdbc: JdbcTemplate): Long {
        requireRecoveryHolder(jdbc); requireQueue(recoveryControls)
        TestActiveOwnerDeleteQueueOperationV1.requireRecoveryCurrent(jdbc, this)
        return checkNotNull(captured).current.epoch
    }
    internal fun requireRecoveryRun(jdbc: JdbcTemplate) {
        requireRecoveryHolder(jdbc); requireQueue(recoveryControls && !recoveryRun)
        TestActiveOwnerDeleteQueueOperationV1.lockRecoveryRun(jdbc, this); recoveryRun = true
    }
    internal fun requireAppliedCurrent(operation: ComplaintOwnerDeleteApplyOperation, jdbc: JdbcTemplate) {
        requireRecoveryHolder(jdbc); requireQueue(operation === recoveryOperation && recoveryControls && recoveryRun)
        TestActiveOwnerDeleteQueueOperationV1.requireRecoveryCurrent(jdbc, this)
    }
    internal fun requireAppliedCurrent(operation: ComplaintOwnerDeleteAllApplyOperation, jdbc: JdbcTemplate) {
        requireRecoveryHolder(jdbc); requireQueue(operation === allRecoveryOperation && recoveryControls && recoveryRun)
        TestActiveOwnerDeleteQueueOperationV1.requireRecoveryCurrent(jdbc, this)
    }
    internal fun requireAppliedCurrent(operation: ComplaintAdminDeleteApplyOperation, jdbc: JdbcTemplate) {
        requireRecoveryHolder(jdbc); requireQueue(operation === adminRecoveryOperation && recoveryControls && recoveryRun)
        TestActiveOwnerDeleteQueueOperationV1.requireRecoveryCurrent(jdbc, this)
    }

    internal fun requirePersistence(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireRunning(); requireQueue(ownership === coordinator.ownership && jdbc.dataSource === coordinator.dataSource && ownership.manager === coordinator.manager)
        if (coordinatorJdbc == null) coordinatorJdbc = jdbc
        requireQueue(coordinatorJdbc === jdbc)
    }
    internal fun requirePhaseEntry(ownership: PersistencePhaseOwnership, selected: PersistencePhasePath) {
        requireConnectionFree(); requireRunning(); requireQueue(selected === path && phase == null && !phaseEntered)
        if (step === TestActiveOwnerDeleteQueueStepV1.APPLY) requireRecoveryPersistence(ownership, deletionJdbc, graph)
        else requireQueue(ownership === coordinator.ownership)
        phaseEntered = true
    }
    internal fun retainPhase(selected: PersistencePhaseContext) { requireRunning(); requireQueue(phaseEntered && phase == null); phase = selected }
    internal fun observePhaseCleanup(selected: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testActiveOwnerDeleteQueueCleanupProven(this) ||
                selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) {
                cleanupUncertain = true; observeFailure(TestActiveOwnerDeleteQueueExceptionV1())
            } else { phase = null; phaseEntered = false }
        } catch (problem: Throwable) { cleanupUncertain = true; observeFailure(problem) }
    }
    internal fun authenticate(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requirePersistence(ownership, jdbc); authenticateAs(jdbc, PersistenceJdbcParticipantRole.CATALOG_COORDINATOR); requirePersistence(ownership, jdbc)
    }
    private fun authenticateAs(jdbc: JdbcTemplate, role: PersistenceJdbcParticipantRole) {
        val properties = process.pools.descriptors().single { it.role === role }.openings().map { it.publicDriverProperties() }
        val user = properties.map { it["user"] }.distinct().single(); val database = properties.map { it["PGDBNAME"] }.distinct().single()
        requireQueue(jdbc.query("SELECT session_user = ? AND current_user = ? AND current_database() = ? AS valid",
            ResultSetExtractor { rows -> rows.next() && rows.getBoolean("valid") && !rows.wasNull() && !rows.next() }, user, user, database) == true)
    }
    internal fun requireMaintenanceGate(ownership: PersistencePhaseOwnership, selected: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireRunning(); requireQueue(selected === path && phaseEntered && phase != null &&
            ownership === if (step === TestActiveOwnerDeleteQueueStepV1.APPLY) deletionOwner else coordinator.ownership)
        registration.requireActiveDeletionGate(gate)
    }
    private fun requireCurrentRunning() { requireRunning(); requireQueue(leaseToken > 0 && leaseExpiresAt != null && acquired != null) }
    private fun requireRunning() {
        throwIfSignalled()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("ACTIVE TEST queue interrupted.")
        requireQueue(caller === Thread.currentThread() && started && !finished && !cleanupUncertain)
        budget.remainingMillis(1); registration.requireActiveIdentityTarget(assembly); recipe.requireRetained(routing, process.pools)
        if (readbackReserved) coordinator.catalogRefreshCustody.requireTestActiveOwnerDeleteQueue(this)
    }
    internal fun observeFailure(problem: Throwable) {
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("ACTIVE TEST queue cancelled.")
            problem is InterruptedException || problem is InterruptedIOException || problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED ->
                InterruptedException("ACTIVE TEST queue interrupted.")
            else -> TestActiveOwnerDeleteQueueExceptionV1()
        }
        while (true) {
            val previous = failure.get()
            if (previous is Error || previous is CancellationException && retained !is Error || previous is InterruptedException && retained !is Error && retained !is CancellationException ||
                previous != null && retained is TestActiveOwnerDeleteQueueExceptionV1) return
            if (failure.compareAndSet(previous, retained)) return
        }
    }
    internal fun throwIfSignalled() { failure.get()?.let { if (it is InterruptedException) Thread.currentThread().interrupt(); throw it } }
    /** Stop is not cleanup evidence. The original caller still owns every late return and JDBC finalizer. */
    fun cancel() {
        val signal = CancellationException("ACTIVE TEST queue cancelled."); observeFailure(signal); phase?.recordFailure(signal)
        listOf<() -> Unit>({ reader?.close() }, { queue?.close() }, ::closePrincipal, ::closeReadback).forEach { close ->
            runCatching(close).exceptionOrNull()?.let(::observeFailure)
        }
    }

    internal fun historyArguments(): Array<Any?> = arrayOf(readbackIdentity[0], scope, identity.generation, identity.activationCatalogHash(), process.catalogReadback.chainPolicy.limits.maximumGenerations + 1)
    internal fun requireAdmissionComparisons(tail: TestNamespaceRecoveryRegistrationTailV1, history: CatalogTestRunActivationHistoryV1) {
        requireRunning(); requireQueue(tail.token == readbackIdentity[0] && tail.scope == scope && tail.generation == identity.generation &&
            tail.envelopeHash.contentEquals(readbackIdentity[3] as ByteArray) && tail.unsigned.contentEquals(readbackIdentity[4] as ByteArray) && tail.unsignedHash.contentEquals(readbackIdentity[5] as ByteArray))
        captured?.let { it.tail.requireSame(tail); it.history.requireSame(history); requireRawReleased() }
    }
    internal fun requireRawReleased() { requireRunning(); requireQueue(raw != null && providerClosed && providerFailure == null && !readbackStage) }
    private fun observeCatalog(primary: AwsSessionCredentials, replica: AwsSessionCredentials) {
        readbackStage = true
        val recipe = process.catalogReadback; val at = clock.instant(); val configured = recipe.sdkLimits
        val millis = readbackBudget.remainingMillis(configured.requestTimeoutMillis)
        val limits = S3CatalogReadbackLimits(millis, minOf(configured.connectTimeoutMillis.toLong(), millis).toInt(), minOf(configured.readTimeoutMillis.toLong(), millis).toInt(),
            configured.maximumListBytes, configured.maximumErrorBytes, configured.maximumObjectBytes)
        val result = withSignerRotationCleanup({
            val adapter = S3CatalogReadbackAdapter.openOwned(construction, recipe.currentBundleBytes(), recipe.chainPolicy.trustBundlePolicy,
                primary, replica, limits, { http.open(limits, catalogHttp) }, this.recipe.nanoTime)
            CatalogTestRunActivationReadbackV3.verifyActiveCurrent(TimedReadback(adapter), recipe.initialBundleBytes(), recipe.currentBundleBytes(), recipe.policyAt(at),
                identity.head, expected, checkNotNull(captured).history)
        }, ::closeReadback)
        requireRunning(); readbackBudget.remainingMillis(1)
        requireQueue(!clock.instant().isBefore(at) && providerClosed && providerFailure == null)
        checkNotNull(captured).tail.requireRaw(result, recipe.chainPolicy.limits.maximumManifestRecords)
        raw = result; readbackStage = false
    }
    internal fun requireProviderRunning() {
        requireConnectionFree(); requireRunning(); readbackBudget.remainingMillis(1)
        requireQueue(readbackStage && phase == null && !phaseEntered && raw == null && !providerClosed)
        checkNotNull(captured).requireReleased()
    }
    internal fun requireCustody(selected: CatalogReadbackRefreshCustodyV1) = requireQueue(caller === Thread.currentThread() && coordinator.catalogRefreshCustody === selected)
    @Synchronized private fun closeReadback() {
        val problem = runCatching { withSignerRotationCleanup({ construction.close() }, http::close) }.exceptionOrNull()
        if (problem != null) providerFailure = preferSignerRotationCleanup(providerFailure, problem)
        providerFailure?.let { throw it }; providerClosed = true
    }
    internal fun requireActualReadbackCleanup() {
        requireConnectionFree(); requireCustody(coordinator.catalogRefreshCustody)
        requireQueue(providerClosed && providerFailure == null && phase == null && !phaseEntered && !cleanupUncertain)
    }
    private inner class TimedReadback(private val actual: S3CatalogReadbackAdapter) : CatalogReadbackPort {
        override fun listVersions(request: CatalogListRequest) = checked { actual.listVersions(request) }
        override fun openVersion(request: CatalogGetRequest): CatalogVersionBody {
            requireProviderRunning(); val body = actual.openVersion(request)
            val check = runCatching(::requireProviderRunning)
            if (check.isFailure) return withSignerRotationCleanup({ check.getOrThrow(); body }, body::close)
            return object : CatalogVersionBody {
                override fun metadata() = checked { body.metadata() }
                override fun read(destination: ByteArray, offset: Int, length: Int) = checked { body.read(destination, offset, length) }
                override fun close() = body.close()
            }
        }
        private fun <T> checked(action: () -> T): T { requireProviderRunning(); return action().also { requireProviderRunning() } }
    }

    /** Historical per-delivery observations only. Nothing here asserts queue emptiness/completeness or HEALTHY. */
    class Completed private constructor(val scope: UUID, val primaryAcknowledged: Int, val dlqAcknowledged: Int) {
        companion object {
            internal fun issue(original: TestActiveOwnerDeleteQueueV1): Completed {
                requireConnectionFree(); original.throwIfSignalled()
                requireQueue(original.caller === Thread.currentThread() && original.finished && original.successful && !original.nativeClaimed && !original.readbackReserved &&
                    original.providerClosed && original.providerFailure == null && !original.cleanupUncertain && original.phase == null && !original.phaseEntered)
                original.budget.remainingMillis(1); original.requireNativeReleased(original.recipe); checkNotNull(original.settled).requireReleased()
                return Completed(original.scope, original.primaryAcked, original.dlqAcked)
            }
        }
        override fun toString() = "ActiveQueueCompleted(historical-partial,no-health-or-checkpoint-authority)"
    }
    companion object {
        private val LOG = LoggerFactory.getLogger(TestActiveOwnerDeleteQueueV1::class.java)
        fun begin(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1,
            deletionOwner: PersistencePhaseOwnership, deletionJdbc: JdbcTemplate, audit: AuditService): TestActiveOwnerDeleteQueueV1 =
            TestActiveOwnerDeleteQueueV1(registration, assembly, deletionOwner, deletionJdbc, audit, null, checkNotNull(registration.process.activeOwnerDeleteQueue).clock)
        internal fun withHttpFixture(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1,
            deletionOwner: PersistencePhaseOwnership, deletionJdbc: JdbcTemplate, audit: AuditService, http: () -> SdkHttpClient, clock: Clock): TestActiveOwnerDeleteQueueV1 =
            TestActiveOwnerDeleteQueueV1(registration, assembly, deletionOwner, deletionJdbc, audit, http, clock)
    }
}
