package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.common.Sha256
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
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDenialStatementV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCompletedCutV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialPrefixV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProgressV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeleteApplyOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintAdminDeletePhaseOperation
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteVerificationStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintAdminDeleteApplyStore
import me.manga.kira.backend.complaint.infrastructure.TestAdminDeleteApplyInputV1
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteApplyOperation
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeletePhaseOperation
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteApplyStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllVerificationStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteAllApplyStore
import me.manga.kira.backend.complaint.infrastructure.ComplaintOwnerDeleteAllApplyOperation
import me.manga.kira.backend.complaint.infrastructure.OwnerDeleteAllApplyInputV1
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteStore
import me.manga.kira.backend.complaint.infrastructure.JdbcComplaintOwnerDeleteVerificationStore
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteApplyInputV1
import me.manga.kira.backend.complaint.infrastructure.TestOwnerDeleteLocalGraphV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.journal.OrdinaryJournalRetentionV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOrdinaryInventoryReadbackV1
import me.manga.kira.backend.complaint.infrastructure.journal.TestOrdinaryInventoryReaderV1
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.security.TestOwnerDeleteJournalEventV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.io.InterruptedIOException
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * One registered original: primaries, independently admitted denial, native pair, paid durable cut,
 * exact residual recovery, P-U conversion, paid scan recycle, and the actual strict native successor.
 * First-same-process/single-writer owner deletion with FOUR paid exact versions per family.
 * ALL and single Admin require their explicit profiles and typed owners; no BATCH/retirement, restart or purge.
 */
internal class TestRunOrdinaryDrainV1 private constructor(
    internal val registration: ComplaintTestNamespaceRegistrationV1,
    private val deletionOwner: PersistencePhaseOwnership,
    private val deletionJdbc: JdbcTemplate,
    private val audit: AuditService,
    private val clock: Clock,
    private val nanoTime: () -> Long,
    private val s3: (() -> SdkHttpClient)?,
    private val kms: (() -> SdkHttpClient)?,
) {
    private val caller = Thread.currentThread()
    internal val coordinator = registration.process.pools.catalogCoordinator
    internal val routing = registration.process.consumers.journalRouting
    internal val scope = routing.journalConfiguration.scope.id
    internal val writer = routing.journalConfiguration.declaration().writer.generationId
    internal val maximumVersions = routing.journalConfiguration.declaration().limits.capacity.maximumRetainedVersions
    internal val maximumFramedBytes = routing.journalConfiguration.declaration().limits.capacity.maximumScanStagingBytes
    internal val maximumCiphertextBytes = Math.multiplyExact(maximumVersions, routing.journalConfiguration.declaration().limits.decoder.maximumEnvelopeBytes.toLong())
    internal val budget = PersistenceTimeBudget.start(routing.journalConfiguration.declaration().limits.deadlines.scanMillis.toLong(), coordinator.ownership.nanoClock)
    internal val runContext: TestTerminalRunContextV1
    internal val attemptId = UUID.randomUUID()
    internal val captureId = UUID.randomUUID()
    internal var scanId = UUID.randomUUID()
        private set
    internal var leaseToken = 0L
        private set
    internal val cutoff: Long get() = checkNotNull(capturedControl).cutoff
    internal val currentEpoch: Long get() = checkNotNull(capturedControl).epoch
    internal val path: PersistencePhasePath get() = if (step === TestOrdinaryDrainStepV1.RECOVERY_APPLY) when (checkNotNull(currentEntry).kind) {
        "OWNER_DELETE" -> PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY
        "OWNER_DELETE_ALL" -> PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY
        "ADMIN_DELETE", "ADMIN_BATCH_DELETE" -> PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY
        else -> throw TestOrdinaryDrainExceptionV1()
    } else PersistencePhasePath.COMPLAINT_TEST_ORDINARY_DRAIN
    internal var step = TestOrdinaryDrainStepV1.OPEN
        private set
    private val authority = registration.process.ordinaryDenial ?: throw TestOrdinaryDrainExceptionV1()
    private val failure = AtomicReference<Throwable?>()
    private var started = false
    private var finished = false
    private var completedStrictDrain = false
    private var completedManifestPreparation: TestRunInstallationManifestV1? = null
    private var cleanupUncertain = false
    private var phaseEntered = false
    private var phase: PersistencePhaseContext? = null
    private var coordinatorJdbc: JdbcTemplate? = null
    private var capturedControl: TestOrdinaryDrainRowsV1.Control? = null
    private var retainedRun: TestOrdinaryDrainRowsV1.Run? = null
    private var admitted: AdmittedOrdinaryDenialV1? = null
    private var paidProgress: TestTerminalProgressV1? = null
    private var native: TestOrdinaryInventoryReaderV1? = null
    private var currentReadback: TestOrdinaryInventoryReadbackV1? = null
    private var currentEntry: TestOrdinaryDrainRowsV1.Entry? = null
    private var currentRecoveryInput: TestOwnerDeleteApplyInputV1? = null
    private var currentRecoveryOperation: ComplaintOwnerDeleteApplyOperation? = null
    private var currentAllRecoveryInput: OwnerDeleteAllApplyInputV1? = null
    private var currentAllRecoveryOperation: ComplaintOwnerDeleteAllApplyOperation? = null
    private var currentAdminRecoveryInput: TestAdminDeleteApplyInputV1? = null
    private var currentAdminRecoveryOperation: ComplaintAdminDeleteApplyOperation? = null
    private var recoveryControls = false
    private var recoveryRun = false
    private var primaryContinuation: TestRunOwnerDeleteContinuationV1? = null
    private var allContinuation: TestRunOwnerDeleteAllContinuationV1? = null
    private var selectedAllLocator: Pair<UUID, UUID>? = null
    private var adminContinuation: TestRunAdminDeleteContinuationV1? = null
    private var selectedAdminLocator: Pair<UUID, UUID>? = null
    private var lastUtc: Instant? = null
    private var closedSeal: TestRunOrdinarySealV1? = null
    private var closeoutReady = false
    internal var inventoryPass = 0
        private set
    internal var inventoryTime: Instant? = null
        private set
    internal var nativeVersionCount = 0L
        private set
    internal var nativeCiphertextBytes = 0L
        private set
    internal var afterEntry: Pair<String, String>? = null
        private set
    internal var afterPrimary: String? = null
        private set
    internal var selectedPrimary: String? = null
        private set
    private val passSummaries = arrayOfNulls<TestOrdinaryDrainRowsV1.Summary>(2)

    private val graph: TestOwnerDeleteLocalGraphV1
    private val apply: JdbcComplaintOwnerDeleteApplyStore
    private val allApply: JdbcComplaintOwnerDeleteAllApplyStore?
    private val adminApply: JdbcComplaintAdminDeleteApplyStore?

    init {
        requireConnectionFree()
        registration.requireUsable()
        registration.requireOwnerDeleteContinuationResources(deletionOwner, deletionJdbc)
        requireDrain((s3 == null) == (kms == null) && routing.journalConfiguration.declaration().routing.keys.size == 4)
        requireDrain(!routing.journalConfiguration.adminDelete || routing.journalConfiguration.registeredAdminDelete)
        authority.requireJournal(routing.journalConfiguration)
        requireDrain(registration.process.catalogActivation.initialWriterRegistry().eventWriter.generationId == writer)
        val arguments = registration.sealingRunArguments()
        runContext = TestTerminalRunContextV1(scope.toString(), arguments[3] as Long, HexFormat.of().formatHex(arguments[4] as ByteArray),
            HexFormat.of().formatHex(arguments[1] as ByteArray), TestTerminalProfileV1.encodingSha256)
        val process = registration.process
        graph = TestOwnerDeleteLocalGraphV1(JdbcTemplate(process.pools.ordinary), deletionJdbc, process.consumers.ingressAdmission,
            routing, process.consumers.capacityPolicy, process.publicationLanes, process.desiredGeneration, registration)
        val capacity = JdbcComplaintCapacityStore(deletionJdbc, process.consumers.capacityPolicy.digestBytes())
        val store = JdbcComplaintOwnerDeleteStore(deletionJdbc, capacity, audit, graph, codec = null)
        val verification = JdbcComplaintOwnerDeleteVerificationStore(deletionJdbc, graph, store)
        apply = JdbcComplaintOwnerDeleteApplyStore(deletionJdbc, capacity, audit, graph, store, verification)
        adminApply = if (routing.journalConfiguration.registeredAdminDelete) {
            val adminStore = JdbcComplaintAdminDeleteStore(deletionJdbc, capacity, audit, graph, codec = null)
            JdbcComplaintAdminDeleteApplyStore(deletionJdbc, capacity, audit, graph, adminStore,
                JdbcComplaintAdminDeleteVerificationStore(deletionJdbc, graph, adminStore))
        } else null
        allApply = if (routing.journalConfiguration.ownerDeleteAll) {
            val allStore = JdbcComplaintOwnerDeleteAllStore(deletionJdbc, capacity, audit, graph, codec = null)
            JdbcComplaintOwnerDeleteAllApplyStore(deletionJdbc, capacity, audit, graph,
                JdbcComplaintOwnerDeleteAllVerificationStore(deletionJdbc, graph, allStore))
        } else null
    }

    fun drain(approval: ByteArray, rawEvidence: List<ByteArray>, primaryCredentials: AwsSessionCredentials,
        readCredentials: AwsSessionCredentials): TestRunOrdinaryDrainResultV1 {
        requireDrain(caller === Thread.currentThread() && !started)
        started = true
        var complete = false
        try {
            requireRunning()
            val opening = execute(TestOrdinaryDrainStepV1.OPEN)
            retainedRun = opening.run
            if (checkNotNull(capturedControl).sequence == 0L) {
                requireDrain(opening.run.progress == null)
                val publication = TestRunPreparedOwnerDeleteV1.Publication(primaryCredentials, s3, kms, clock, nanoTime)
                do {
                    step = TestOrdinaryDrainStepV1.PRIMARIES
                    primaryContinuation = null
                    val page = TestRunPreparedOwnerDeleteV1.forDrain(this, registration, deletionOwner, deletionJdbc, audit, publication).completePage()
                    requireRunning()
                } while (page.moreObserved)
                primaryContinuation = null
                // Finish single-resource primaries before an owner's full erasure.
                if (routing.journalConfiguration.registeredAdminDelete) completeAdminPrimaries(primaryCredentials)
                if (routing.journalConfiguration.ownerDeleteAll) completeAllPrimaries(primaryCredentials)
                execute(TestOrdinaryDrainStepV1.CAPTURE) // Exact locked completeness, never the page's diagnostic zero.
            }
            step = TestOrdinaryDrainStepV1.ADMISSION
            admitted = authority.admit(this, approval, rawEvidence)
            val previous = checkNotNull(retainedRun).progress
            if (previous == null) {
                // Never adopt unwitnessed COMPLETE rows. Supersede under current fence and repay exact deleted rows.
                do { val cleaned = execute(TestOrdinaryDrainStepV1.ABANDON); requireRunning() } while (!cleaned.scanCharge.isZero())
                step = TestOrdinaryDrainStepV1.NATIVE
                native = TestOrdinaryInventoryReaderV1.begin(this, readCredentials, s3, kms, clock, nanoTime)
                waitUntil(checkNotNull(admitted).firstStartAfter())
                checkNotNull(native).scanPass(1)
                val first = checkNotNull(passSummaries[0]).witness
                val statement = checkNotNull(admitted).statement
                val secondAfter = Math.addExact(first.completedAtEpochSecond,
                    Math.addExact(statement.acceptedRequestBoundSeconds, Math.multiplyExact(2L, statement.utcUncertaintySeconds)))
                waitUntil(Instant.ofEpochSecond(secondAfter))
                step = TestOrdinaryDrainStepV1.NATIVE
                checkNotNull(native).scanPass(2)
                // Completion is rounded conservatively upward for the durable witness. Do not
                // publish a future-dated cut just because the native call ended within that second.
                waitUntil(java.time.Instant.ofEpochSecond(checkNotNull(passSummaries[1]).witness.completedAtEpochSecond))
                paidProgress = execute(TestOrdinaryDrainStepV1.WITNESS).progress
            } else {
                bindPaidProgress(previous)
                step = TestOrdinaryDrainStepV1.NATIVE
                native = TestOrdinaryInventoryReaderV1.begin(this, readCredentials, s3, kms, clock, nanoTime)
                paidProgress = previous
            }
            recoverEntries()
            convertPrimaries()
            execute(TestOrdinaryDrainStepV1.CLOSEOUT)
            closeoutReady = true
            do { val recycled = execute(TestOrdinaryDrainStepV1.RECYCLE); requireRunning() } while (!recycled.scanCharge.isZero())
            execute(TestOrdinaryDrainStepV1.READY)
            checkNotNull(native).close()
            step = TestOrdinaryDrainStepV1.SEAL
            TestRunClosedOrdinarySealV1.complete(this) // Connected actual consumer, not an unused completion token.
            requireRunning()
            complete = true
        } catch (problem: Throwable) { observeFailure(problem) }
        finally {
            runCatching { native?.close() }.exceptionOrNull()?.let(::observeFailure)
            currentReadback = null; currentEntry = null; currentRecoveryInput = null; currentAllRecoveryInput = null; currentAdminRecoveryInput = null
            finished = true
        }
        throwIfSignalled()
        requireDrain(complete && !cleanupUncertain && phase == null && !phaseEntered)
        completedStrictDrain = true
        return TestRunOrdinaryDrainResultV1.POST_DENIAL_ORDINARY_SEAL_VERIFIED
    }

    /** Actual next edge, not an enum-based admission. No terminal network dispatch is performed. */
    fun prepareInstallationManifest(): TestRunInstallationManifestResultV1 {
        val original = TestRunInstallationManifestV1.begin(this)
        val result = original.prepare()
        completedManifestPreparation = original
        return result
    }

    /** Retry publication from the retained successful PREPARE; never rerun canonical-only PREPARE on mixed winners. */
    fun publishInstallationManifest(): TestRunInstallationManifestPublicationResultV1 {
        requireManifestPredecessor()
        val preparation = completedManifestPreparation ?: throw TestInstallationManifestExceptionV1()
        return preparation.beginPublication().publish()
    }

    /** Receiptless purge publication only; never terminal-seal, deletion or PURGED authority. */
    fun publishTestRunPurge(): TestRunPurgePublicationResultV1 {
        requireConnectionFree(); requireManifestPredecessor()
        val preparation = completedManifestPreparation ?: throw TestRunPurgeExceptionV1()
        return preparation.beginPurgePublication().publish()
    }

    internal fun requireManifestPredecessor() {
        throwIfSignalled()
        requireDrain(caller === Thread.currentThread() && started && finished && completedStrictDrain && !cleanupUncertain &&
            phase == null && !phaseEntered && step === TestOrdinaryDrainStepV1.SEAL && closeoutReady)
        registration.requireSealingOwner(coordinator.ownership)
        checkNotNull(closedSeal).completedStrictReference(this)
    }
    internal fun manifestControl(): TestOrdinaryDrainRowsV1.Control {
        requireManifestPredecessor()
        return checkNotNull(capturedControl)
    }
    internal fun manifestCut(): TestTerminalCompletedCutV1 {
        requireManifestPredecessor()
        return checkNotNull(paidProgress).completedCuts().single()
    }
    internal fun manifestDenialRetainUntil(): Long {
        requireManifestPredecessor()
        return checkNotNull(admitted).statement.evidenceRetainUntilEpochSecond
    }
    internal fun manifestSeal() = checkNotNull(closedSeal).completedStrictReference(this).also { requireManifestPredecessor() }

    private fun completeAllPrimaries(credentials: AwsSessionCredentials) {
        val publication = TestRunPreparedOwnerDeleteAllV1.Publication(credentials, s3, kms, clock, nanoTime)
        do {
            val page = execute(TestOrdinaryDrainStepV1.ALL_PRIMARY_PAGE).allPrimaries
            for (locator in page.take(2)) {
                step = TestOrdinaryDrainStepV1.PRIMARIES
                selectedAllLocator = locator
                val child = TestRunPreparedOwnerDeleteAllV1.forDrain(this, registration, deletionOwner, deletionJdbc, audit,
                    locator.first, locator.second, publication)
                child.complete()
                requireRunning(); child.requireCompletedForDrain(this)
                allContinuation = null; selectedAllLocator = null
            }
        } while (page.size > 2)
    }

    private fun completeAdminPrimaries(credentials: AwsSessionCredentials) {
        val publication = TestRunPreparedAdminDeleteV1.Publication(credentials, s3, kms, clock, nanoTime)
        do {
            val page = execute(TestOrdinaryDrainStepV1.ADMIN_PRIMARY_PAGE).adminPrimaries
            for (locator in page.take(2)) {
                step = TestOrdinaryDrainStepV1.PRIMARIES
                selectedAdminLocator = locator
                val child = TestRunPreparedAdminDeleteV1.forDrain(this, registration, deletionOwner, deletionJdbc, audit,
                    locator.first, locator.second, publication)
                child.complete()
                requireRunning(); child.requireCompletedForDrain(this)
                adminContinuation = null; selectedAdminLocator = null
            }
        } while (page.size > 2)
    }

    private fun execute(selected: TestOrdinaryDrainStepV1): TestOrdinaryDrainOperationV1 {
        requireConnectionFree(); requireRunning(); requireDrain(phase == null && !phaseEntered)
        step = selected
        return coordinator.testOrdinaryDrain.execute(this).also { it.requireReleased(); requireRunning() }
    }

    internal fun retainCapture(operation: TestOrdinaryDrainOperationV1, control: TestOrdinaryDrainRowsV1.Control, token: Long) {
        requireRunning(); requireDrain(operation.original === this && operation.step === step && step in setOf(TestOrdinaryDrainStepV1.OPEN, TestOrdinaryDrainStepV1.CAPTURE))
        if (step === TestOrdinaryDrainStepV1.OPEN) requireDrain(capturedControl == null && leaseToken == 0L && token > 0)
        else requireDrain(leaseToken == token && checkNotNull(capturedControl).historyHash == control.historyHash &&
            checkNotNull(capturedControl).cutoff == control.cutoff && control.sequence == 1L)
        capturedControl = control; leaseToken = token
    }
    internal fun requireCapturedControl(control: TestOrdinaryDrainRowsV1.Control) { requireRunning(); control.requireSame(checkNotNull(capturedControl)) }
    internal fun capturedControl(): TestOrdinaryDrainRowsV1.Control { requireRunning(); return checkNotNull(capturedControl) }

    internal fun requireAuthority(candidate: TestOrdinaryDenialAuthorityPolicyV1) {
        requireRunning(); requireDrain(candidate === authority && registration.process.ordinaryDenial === candidate && capturedControl?.sequence == 1L)
    }
    internal fun requireDenialContext(statement: TestOrdinaryDenialStatementV1) {
        requireAuthority(authority)
        val declaration = routing.journalConfiguration.declaration()
        val role = declaration.authorities.ordinary
        requireDrain(statement.dataScopeId == runContext.dataScopeId && statement.activationCatalogGeneration == runContext.activationCatalogGeneration &&
            statement.activationCatalogSha256 == runContext.activationCatalogSha256 && statement.configurationSha256 == runContext.configurationSha256 &&
            statement.terminalEncodingSha256 == runContext.terminalEncodingSha256 &&
            statement.initialWriterRegistrySha256 == registration.process.catalogActivation.initialWriterRegistrySha256 && statement.writerGeneration == writer &&
            statement.databaseIdentity == declaration.writer.databaseIdentity && statement.restoreIdentity == declaration.writer.restoreIdentity &&
            statement.epochStartInclusive == 1L && statement.epochEndInclusive == cutoff && statement.ordinaryPrefix == routing.journalConfiguration.ordinaryPrefix &&
            statement.bucket == declaration.journalLocation.bucket && statement.accountId == declaration.journalLocation.accountId &&
            statement.region == declaration.journalLocation.region && statement.roleId == role.roleId &&
            statement.policy.policyId == role.policy.policyId && statement.policy.version == role.policy.version && statement.policy.sha256 == role.policy.sha256)
        requireDrain(statement.evidenceRetainUntilEpochSecond > sampleUtc().epochSecond)
    }
    internal fun admittedDenial(): AdmittedOrdinaryDenialV1 = checkNotNull(admitted).also {
        it.requireOriginal(this)
        // Admission alone cannot carry expired retention through a slow inventory, takeover or
        // native successor. The same finite raw-closure grant must still apply at every use.
        requireDrain(it.statement.evidenceRetainUntilEpochSecond > sampleUtc().epochSecond)
    }
    internal fun paidCut(): TestTerminalCompletedCutV1 = checkNotNull(paidProgress).completedCuts().single()
    internal fun requirePaidProgress(actual: TestTerminalProgressV1?) {
        requireRunning(); val expected = checkNotNull(paidProgress)
        requireDrain(actual != null && actual.context() == expected.context() && actual.completedCuts() == expected.completedCuts() && actual.installationReads().isEmpty())
        bindPaidProgress(checkNotNull(actual))
    }
    private fun bindPaidProgress(value: TestTerminalProgressV1) {
        requireDrain(value.context() == runContext && value.installationReads().isEmpty() && value.completedCuts().size == 1)
        val cut = value.completedCuts().single()
        val admitted = admittedDenial()
        val statement = admitted.statement
        requireDrain(cut.prefixKind === TestTerminalDenialPrefixV1.ORDINARY && cut.writerGeneration == writer && cut.epochStartInclusive == 1L && cut.epochEndInclusive == cutoff &&
            cut.databaseIdentity == statement.databaseIdentity && cut.restoreIdentity == statement.restoreIdentity &&
            cut.desiredGeneration == registration.process.desiredGeneration && cut.fencingToken in 1..leaseToken &&
            cut.denial.roleId == statement.roleId && cut.denial.policy == statement.policy && cut.denial.policyEvidence == admitted.policyEvidence &&
            cut.denial.boundEvidence == statement.boundEvidence && cut.denial.denialEffectiveAtEpochSecond == statement.denialEffectiveAtEpochSecond &&
            cut.denial.lastSessionExpiryEpochSecond == statement.lastSessionExpiryEpochSecond && cut.denial.acceptedRequestBoundSeconds == statement.acceptedRequestBoundSeconds &&
            cut.denial.firstInventory.startedAtEpochSecond >= admitted.firstStartAfter().epochSecond && cut.framedByteCount <= maximumFramedBytes &&
            cut.denial.firstInventory.versionCount <= maximumVersions && cut.denial.firstInventory.byteCount <= maximumCiphertextBytes)
        val earliestSecond = Math.addExact(cut.denial.firstInventory.completedAtEpochSecond,
            Math.addExact(statement.acceptedRequestBoundSeconds, Math.multiplyExact(2L, statement.utcUncertaintySeconds)))
        requireDrain(cut.denial.secondInventory.startedAtEpochSecond >= earliestSecond && cut.denial.secondInventory.completedAtEpochSecond <= sampleUtc().epochSecond)
        scanId = UUID.fromString(cut.scanId)
    }

    internal fun requireInventoryStart() {
        requireConnectionFree(); requireRunning(); admittedDenial()
        requireDrain(step === TestOrdinaryDrainStepV1.NATIVE && native == null && phase == null)
    }
    internal fun requireInventoryReader(reader: TestOrdinaryInventoryReaderV1) {
        requireConnectionFree(); requireRunning(); admittedDenial()
        requireDrain(native === reader && phase == null && !phaseEntered && step in setOf(
            TestOrdinaryDrainStepV1.NATIVE, TestOrdinaryDrainStepV1.RECOVERY_NATIVE, TestOrdinaryDrainStepV1.WITNESS))
    }
    internal fun requireInventoryEvent(event: TestOwnerDeleteJournalEventV1) {
        requireRunning()
        requireInventoryKind(event.comparison.eventKind.name)
        requireDrain(event.belongsTo(routing) && event.comparison.scope == routing.journalConfiguration.scope &&
            event.comparison.epoch in 1..cutoff && when (event.comparison.eventKind) {
                ComplaintJournalDeletionKindV1.OWNER_DELETE, ComplaintJournalDeletionKindV1.ADMIN_DELETE -> event.complaintIds().size == 1
                ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL -> event.complaintIds().size in 0..100
                ComplaintJournalDeletionKindV1.ADMIN_BATCH_DELETE -> event.complaintIds().size in 1..50
                else -> false
            })
    }
    internal fun requireInventoryKind(kind: String) {
        requireRunning()
        requireDrain(kind == "OWNER_DELETE" || (kind == "OWNER_DELETE_ALL" && routing.journalConfiguration.ownerDeleteAll) ||
            (kind == "ADMIN_DELETE" && routing.journalConfiguration.registeredAdminDelete) ||
            (kind == "ADMIN_BATCH_DELETE" && routing.journalConfiguration.registeredAdminBatchDelete))
    }
    internal fun beginInventoryPass(reader: TestOrdinaryInventoryReaderV1, pass: Int, startedAt: Instant) {
        requireInventoryReader(reader)
        requireDrain(pass in 1..2 && passSummaries[pass - 1] == null && (pass == 1 && inventoryPass == 0 || pass == 2 && inventoryPass == 1 && passSummaries[0] != null))
        inventoryPass = pass; inventoryTime = startedAt
        requireDrain(!startedAt.isAfter(sampleUtc()) && startedAt.epochSecond >= admittedDenial().firstStartAfter().epochSecond)
        if (pass == 2) {
            val s = admittedDenial().statement
            requireDrain(startedAt.epochSecond >= Math.addExact(checkNotNull(passSummaries[0]).witness.completedAtEpochSecond,
                Math.addExact(s.acceptedRequestBoundSeconds, Math.multiplyExact(2L, s.utcUncertaintySeconds))))
        }
        execute(TestOrdinaryDrainStepV1.BEGIN_PASS)
        step = TestOrdinaryDrainStepV1.NATIVE
    }
    internal fun stageInventoryVersion(reader: TestOrdinaryInventoryReaderV1, pass: Int, readback: TestOrdinaryInventoryReadbackV1) {
        requireInventoryReader(reader); readback.requireOriginal(this)
        requireDrain(pass == inventoryPass && currentReadback == null && paidProgress == null)
        currentReadback = readback; currentEntry = TestOrdinaryDrainRowsV1.Entry.observed(this, readback)
        execute(TestOrdinaryDrainStepV1.APPEND)
        currentReadback = null; currentEntry = null; step = TestOrdinaryDrainStepV1.NATIVE
    }
    internal fun completeInventoryPass(reader: TestOrdinaryInventoryReaderV1, pass: Int, completedAt: Instant, versionCount: Long, ciphertextBytes: Long) {
        requireInventoryReader(reader); reader.requireCompletedPass(this, pass)
        requireDrain(pass == inventoryPass && currentReadback == null && !completedAt.isAfter(sampleUtc()))
        inventoryTime = OrdinaryJournalRetentionV1.ceilingSecond(completedAt)
        nativeVersionCount = versionCount; nativeCiphertextBytes = ciphertextBytes
        passSummaries[pass - 1] = execute(TestOrdinaryDrainStepV1.COMPLETE_PASS).summary
        step = TestOrdinaryDrainStepV1.NATIVE
    }
    internal fun requireReleasedNativePair() {
        requireConnectionFree(); requireRunning()
        checkNotNull(native).requireCompletedPass(this, 1); checkNotNull(native).requireCompletedPass(this, 2)
        requireDrain(passSummaries.all { it != null })
    }
    internal fun nativeSummary(pass: Int): TestOrdinaryDrainRowsV1.Summary = checkNotNull(passSummaries[pass - 1])
    internal fun pendingEntry(operation: TestOrdinaryDrainOperationV1): TestOrdinaryDrainRowsV1.Entry {
        requireDrain(operation.original === this && step === TestOrdinaryDrainStepV1.APPEND && currentReadback != null)
        return checkNotNull(currentEntry)
    }

    private fun recoverEntries() {
        afterEntry = null
        while (true) {
            val page = execute(TestOrdinaryDrainStepV1.RECOVERY_PAGE).entries
            if (page.isEmpty()) break
            page.forEach { entry ->
                requireRunning()
                currentEntry = entry
                if (entry.replay != "APPLIED") {
                    step = TestOrdinaryDrainStepV1.RECOVERY_NATIVE
                    val observed = checkNotNull(native).readForRecovery(entry.key, entry.version)
                    checkNotNull(native).requireReleasedReadback(this, observed); observed.requireOriginal(this)
                    entry.requireSame(TestOrdinaryDrainRowsV1.Entry.observed(this, observed))
                    currentReadback = observed
                    when (entry.kind) {
                        "OWNER_DELETE" -> recoverOwnerEntry()
                        "OWNER_DELETE_ALL" -> recoverAllEntry()
                        "ADMIN_DELETE", "ADMIN_BATCH_DELETE" -> recoverAdminEntry()
                        else -> throw TestOrdinaryDrainExceptionV1()
                    }
                    currentReadback = null; currentRecoveryInput = null; currentRecoveryOperation = null
                    currentAllRecoveryInput = null; currentAllRecoveryOperation = null
                    currentAdminRecoveryInput = null; currentAdminRecoveryOperation = null
                    recoveryControls = false; recoveryRun = false
                }
                afterEntry = entry.locator; currentEntry = null
            }
        }
    }
    private fun recoverOwnerEntry() {
        currentRecoveryInput = apply.captureRegisteredInventoryRecovery(this)
        step = TestOrdinaryDrainStepV1.RECOVERY_APPLY
        val selected = deletionOwner.enterTestOrdinaryInventoryRecovery(this)
        var operation: ComplaintOwnerDeleteApplyOperation? = null
        try { selected.begin(); operation = apply.apply(checkNotNull(currentRecoveryInput)); selected.commit() }
        catch (problem: Throwable) { observeFailure(problem); selected.recordFailure(problem) }
        finally { try { selected.finish() } finally { observePhaseCleanup(selected) } }
        throwIfSignalled(); checkNotNull(operation).requireRecovered()
    }
    private fun recoverAllEntry() {
        currentAllRecoveryInput = checkNotNull(allApply).captureRegisteredInventoryRecovery(this)
        step = TestOrdinaryDrainStepV1.RECOVERY_APPLY
        val selected = deletionOwner.enterTestOrdinaryAllInventoryRecovery(this)
        var operation: ComplaintOwnerDeleteAllApplyOperation? = null
        try { selected.begin(); operation = checkNotNull(allApply).apply(checkNotNull(currentAllRecoveryInput)); selected.commit() }
        catch (problem: Throwable) { observeFailure(problem); selected.recordFailure(problem) }
        finally { try { selected.finish() } finally { observePhaseCleanup(selected) } }
        throwIfSignalled(); checkNotNull(operation).requireRecovered()
    }
    private fun recoverAdminEntry() {
        currentAdminRecoveryInput = checkNotNull(adminApply).captureRegisteredInventoryRecovery(this)
        step = TestOrdinaryDrainStepV1.RECOVERY_APPLY
        val selected = deletionOwner.enterTestOrdinaryAdminDeleteRecovery(this)
        var operation: ComplaintAdminDeleteApplyOperation? = null
        try { selected.begin(); operation = checkNotNull(adminApply).apply(checkNotNull(currentAdminRecoveryInput)); selected.commit() }
        catch (problem: Throwable) { observeFailure(problem); selected.recordFailure(problem) }
        finally { try { selected.finish() } finally { observePhaseCleanup(selected) } }
        throwIfSignalled(); checkNotNull(operation).requireRecovered()
    }
    private fun convertPrimaries() {
        afterPrimary = null
        while (true) {
            val page = execute(TestOrdinaryDrainStepV1.PRIMARY_PAGE).primaries
            if (page.isEmpty()) break
            page.forEach { id -> selectedPrimary = id; execute(TestOrdinaryDrainStepV1.CONVERT); afterPrimary = id; selectedPrimary = null }
        }
    }
    internal fun requireRecoveryLocator(reader: TestOrdinaryInventoryReaderV1, key: String, version: String) {
        requireInventoryReader(reader)
        requireDrain(step === TestOrdinaryDrainStepV1.RECOVERY_NATIVE && checkNotNull(currentEntry).locator == (key to version) && paidProgress != null)
    }
    internal fun ownedRecoveryReadback(store: JdbcComplaintOwnerDeleteApplyStore, selectedGraph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate): TestOrdinaryInventoryReadbackV1 {
        requireConnectionFree(); requireRecoveryPersistence(deletionOwner, jdbc, selectedGraph)
        requireDrain(store === apply && step === TestOrdinaryDrainStepV1.RECOVERY_NATIVE && currentRecoveryInput == null && checkNotNull(currentEntry).kind == "OWNER_DELETE")
        return checkNotNull(currentReadback)
    }
    internal fun ownedRecoveryReadback(store: JdbcComplaintOwnerDeleteAllApplyStore, selectedGraph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate): TestOrdinaryInventoryReadbackV1 {
        requireConnectionFree(); requireRecoveryPersistence(deletionOwner, jdbc, selectedGraph)
        requireDrain(store === allApply && step === TestOrdinaryDrainStepV1.RECOVERY_NATIVE && currentAllRecoveryInput == null && checkNotNull(currentEntry).kind == "OWNER_DELETE_ALL")
        return checkNotNull(currentReadback)
    }
    internal fun ownedRecoveryReadback(store: JdbcComplaintAdminDeleteApplyStore, selectedGraph: TestOwnerDeleteLocalGraphV1, jdbc: JdbcTemplate): TestOrdinaryInventoryReadbackV1 {
        requireConnectionFree(); requireRecoveryPersistence(deletionOwner, jdbc, selectedGraph)
        requireDrain(store === adminApply && step === TestOrdinaryDrainStepV1.RECOVERY_NATIVE && currentAdminRecoveryInput == null && checkNotNull(currentEntry).kind in setOf("ADMIN_DELETE", "ADMIN_BATCH_DELETE"))
        return checkNotNull(currentReadback)
    }
    internal fun requireRecoveryInput(input: TestOwnerDeleteApplyInputV1) {
        requireRunning(); requireDrain(step === TestOrdinaryDrainStepV1.RECOVERY_APPLY && currentRecoveryInput === input && currentReadback != null && currentEntry != null)
    }
    internal fun requireRecoveryInput(input: OwnerDeleteAllApplyInputV1) {
        requireRunning(); requireDrain(step === TestOrdinaryDrainStepV1.RECOVERY_APPLY && currentAllRecoveryInput === input &&
            currentReadback != null && checkNotNull(currentEntry).kind == "OWNER_DELETE_ALL" && currentRecoveryInput == null)
    }
    internal fun requireRecoveryInput(input: TestAdminDeleteApplyInputV1) {
        requireRunning(); requireDrain(step === TestOrdinaryDrainStepV1.RECOVERY_APPLY && currentAdminRecoveryInput === input &&
            currentReadback != null && checkNotNull(currentEntry).kind in setOf("ADMIN_DELETE", "ADMIN_BATCH_DELETE") && currentRecoveryInput == null && currentAllRecoveryInput == null)
    }
    internal fun requireRecoveryPersistence(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate, selectedGraph: TestOwnerDeleteLocalGraphV1) {
        requireRunning(); requireDrain(ownership === deletionOwner && jdbc === deletionJdbc && selectedGraph === graph)
        registration.requireOwnerDeleteContinuationResources(ownership, jdbc); graph.requireDeletion(jdbc)
    }
    internal fun authenticateRecoveryAndControls(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate, operation: ComplaintOwnerDeletePhaseOperation) {
        requireRecoveryPersistence(ownership, jdbc, graph)
        requireDrain(step === TestOrdinaryDrainStepV1.RECOVERY_APPLY && currentRecoveryOperation == null && !recoveryControls)
        val actual = operation as? ComplaintOwnerDeleteApplyOperation ?: throw TestOrdinaryDrainExceptionV1()
        actual.requireRegisteredInventoryRecovery(this)
        currentRecoveryOperation = actual
        authenticateAs(jdbc, PersistenceJdbcParticipantRole.DELETION)
        TestOrdinaryDrainPersistenceV1.requireControls(jdbc, this)
        recoveryControls = true
    }
    internal fun authenticateRecoveryAndControls(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate, operation: ComplaintAdminDeletePhaseOperation) {
        requireRecoveryPersistence(ownership, jdbc, graph)
        requireDrain(step === TestOrdinaryDrainStepV1.RECOVERY_APPLY && currentAdminRecoveryOperation == null && currentRecoveryOperation == null && currentAllRecoveryOperation == null && !recoveryControls)
        val actual = operation as? ComplaintAdminDeleteApplyOperation ?: throw TestOrdinaryDrainExceptionV1()
        actual.requireRegisteredInventoryRecovery(this)
        currentAdminRecoveryOperation = actual
        authenticateAs(jdbc, PersistenceJdbcParticipantRole.DELETION)
        TestOrdinaryDrainPersistenceV1.requireControls(jdbc, this)
        recoveryControls = true
    }
    internal fun authenticateRecoveryAndControls(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate, operation: ComplaintOwnerDeleteAllApplyOperation) {
        requireRecoveryPersistence(ownership, jdbc, graph)
        requireDrain(step === TestOrdinaryDrainStepV1.RECOVERY_APPLY && currentAllRecoveryOperation == null && currentRecoveryOperation == null && !recoveryControls)
        operation.requireRegisteredInventoryRecovery(this)
        currentAllRecoveryOperation = operation
        authenticateAs(jdbc, PersistenceJdbcParticipantRole.DELETION)
        TestOrdinaryDrainPersistenceV1.requireControls(jdbc, this)
        recoveryControls = true
    }
    internal fun requireRecoveryControls(jdbc: JdbcTemplate): Long {
        requireRecoveryPersistence(deletionOwner, jdbc, graph)
        requireDrain(recoveryControls && listOfNotNull(currentRecoveryOperation, currentAllRecoveryOperation, currentAdminRecoveryOperation).size == 1 && phase != null)
        TestOrdinaryDrainPersistenceV1.requireLease(jdbc, this)
        return currentEpoch
    }
    internal fun requireRecoveryRun(jdbc: JdbcTemplate) {
        requireRecoveryControls(jdbc)
        requireDrain(!recoveryRun)
        TestOrdinaryDrainPersistenceV1.requireRecoveryRun(jdbc, this, checkNotNull(currentEntry))
        recoveryRun = true
    }
    internal fun recordRecoveredVersion(operation: ComplaintOwnerDeleteApplyOperation, jdbc: JdbcTemplate) {
        requireRecoveryControls(jdbc)
        requireDrain(operation === currentRecoveryOperation && recoveryRun)
        TestOrdinaryDrainPersistenceV1.markRecovered(jdbc, this, checkNotNull(currentEntry))
    }
    internal fun recordRecoveredVersion(operation: ComplaintOwnerDeleteAllApplyOperation, jdbc: JdbcTemplate) {
        requireRecoveryControls(jdbc)
        requireDrain(operation === currentAllRecoveryOperation && recoveryRun)
        TestOrdinaryDrainPersistenceV1.markRecovered(jdbc, this, checkNotNull(currentEntry))
    }

    internal fun recordRecoveredVersion(operation: ComplaintAdminDeleteApplyOperation, jdbc: JdbcTemplate) {
        requireRecoveryControls(jdbc)
        requireDrain(operation === currentAdminRecoveryOperation && recoveryRun)
        TestOrdinaryDrainPersistenceV1.markRecovered(jdbc, this, checkNotNull(currentEntry))
    }

    internal fun retainPrimaryContinuation(original: TestRunOwnerDeleteContinuationV1, candidate: ComplaintTestNamespaceRegistrationV1,
        ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireConnectionFree(); requireRunning()
        requireDrain(step === TestOrdinaryDrainStepV1.PRIMARIES && primaryContinuation == null && candidate === registration && ownership === deletionOwner && jdbc === deletionJdbc)
        primaryContinuation = original
    }
    internal fun requirePrimaryContinuation(original: TestRunOwnerDeleteContinuationV1) {
        requireRunning(); requireDrain(step === TestOrdinaryDrainStepV1.PRIMARIES && primaryContinuation === original && phase == null)
    }
    internal fun retainPrimaryContinuation(original: TestRunOwnerDeleteAllContinuationV1, candidate: ComplaintTestNamespaceRegistrationV1,
        ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireConnectionFree(); requireRunning()
        requireDrain(step === TestOrdinaryDrainStepV1.PRIMARIES && allContinuation == null && primaryContinuation == null &&
            selectedAllLocator == (original.actorId to original.operationKey) && candidate === registration && ownership === deletionOwner && jdbc === deletionJdbc)
        allContinuation = original
    }
    internal fun requirePrimaryContinuation(original: TestRunOwnerDeleteAllContinuationV1) {
        requireRunning(); requireDrain(step === TestOrdinaryDrainStepV1.PRIMARIES && allContinuation === original && phase == null)
    }

    internal fun retainPrimaryContinuation(original: TestRunAdminDeleteContinuationV1, candidate: ComplaintTestNamespaceRegistrationV1,
        ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireConnectionFree(); requireRunning()
        requireDrain(step === TestOrdinaryDrainStepV1.PRIMARIES && adminContinuation == null && allContinuation == null && primaryContinuation == null &&
            selectedAdminLocator == (original.actorId to original.operationKey) && candidate === registration && ownership === deletionOwner && jdbc === deletionJdbc)
        adminContinuation = original
    }
    internal fun requirePrimaryContinuation(original: TestRunAdminDeleteContinuationV1) {
        requireRunning(); requireDrain(step === TestOrdinaryDrainStepV1.PRIMARIES && adminContinuation === original && phase == null)
    }

    internal fun requirePersistence(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireRunning(); registration.requireSealingOwner(ownership)
        requireDrain(jdbc.dataSource === coordinator.dataSource && path === PersistencePhasePath.COMPLAINT_TEST_ORDINARY_DRAIN)
        if (coordinatorJdbc == null) coordinatorJdbc = jdbc
        requireDrain(coordinatorJdbc === jdbc)
    }
    internal fun requirePhaseEntry(ownership: PersistencePhaseOwnership, selected: PersistencePhasePath) {
        requireConnectionFree(); requireRunning(); requireDrain(selected === path && phase == null && !phaseEntered)
        when (selected) {
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY -> requireDrain(ownership === deletionOwner && currentRecoveryInput != null && currentAllRecoveryInput == null && currentAdminRecoveryInput == null)
            PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY -> requireDrain(ownership === deletionOwner && currentAllRecoveryInput != null && currentRecoveryInput == null && currentAdminRecoveryInput == null)
            PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY -> requireDrain(ownership === deletionOwner && currentAdminRecoveryInput != null && currentRecoveryInput == null && currentAllRecoveryInput == null)
            else -> registration.requireSealingOwner(ownership)
        }
        if (step === TestOrdinaryDrainStepV1.WITNESS) requireReleasedNativePair()
        phaseEntered = true
    }
    internal fun retainPhase(selected: PersistencePhaseContext) { requireRunning(); requireDrain(phaseEntered && phase == null); phase = selected }
    internal fun observePhaseCleanup(selected: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testOrdinaryDrainCleanupProven(this) || selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) cleanupUncertain = true
            else { phase = null; phaseEntered = false }
        } catch (problem: Throwable) { cleanupUncertain = true; observeFailure(problem) }
    }
    internal fun authenticate(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requirePersistence(ownership, jdbc); authenticateAs(jdbc, PersistenceJdbcParticipantRole.CATALOG_COORDINATOR); requirePersistence(ownership, jdbc)
    }
    private fun authenticateAs(jdbc: JdbcTemplate, role: PersistenceJdbcParticipantRole) {
        val openings = registration.process.pools.descriptors().single { it.role === role }.openings().map { it.publicDriverProperties() }
        val user = openings.map { it["user"] }.distinct().single(); val database = openings.map { it["PGDBNAME"] }.distinct().single()
        requireDrain(jdbc.query("SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated",
            ResultSetExtractor { rows -> rows.next() && rows.getBoolean("authenticated") && !rows.wasNull() && !rows.next() }, user, user, database) == true)
    }
    internal fun requireMaintenanceGate(ownership: PersistencePhaseOwnership, selected: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireRunning(); requireDrain(selected === path && phaseEntered && phase != null)
        if (selected in setOf(PersistencePhasePath.COMPLAINT_OWNER_DELETE_APPLY, PersistencePhasePath.COMPLAINT_OWNER_DELETE_ALL_APPLY, PersistencePhasePath.COMPLAINT_ADMIN_DELETE_APPLY)) {
            requireDrain(ownership === deletionOwner); registration.requireOwnerDeleteContinuationGate(gate)
        } else { registration.requireSealingOwner(ownership); registration.requireSealingGate(gate) }
    }
    internal fun retainClosedSeal(original: TestRunOrdinarySealV1) {
        requireConnectionFree(); requireRunning()
        requireDrain(step === TestOrdinaryDrainStepV1.SEAL && closeoutReady && closedSeal == null && phase == null && paidProgress != null)
        closedSeal = original
    }
    internal fun requireClosedSeal(original: TestRunOrdinarySealV1) {
        requireRunning(); requireDrain(step === TestOrdinaryDrainStepV1.SEAL && closeoutReady && closedSeal === original && phase == null)
        admittedDenial()
    }
    internal fun requireCloseoutReady() { requireRunning(); requireDrain(closeoutReady) }

    private fun sampleUtc(): Instant {
        val at = clock.instant()
        requireDrain(at.epochSecond in 0..253_402_300_799L && lastUtc?.let { !at.isBefore(it) } != false)
        lastUtc = at; return at
    }
    private fun waitUntil(after: Instant) {
        requireDrain(after.epochSecond in 0..253_402_300_799L)
        while (true) {
            requireConnectionFree(); requireRunning()
            if (!sampleUtc().isBefore(after)) return
            val remaining = budget.remainingMillis(50)
            LockSupport.parkNanos(remaining * 1_000_000L)
        }
    }
    private fun requireRunning() {
        throwIfSignalled()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("TEST ordinary drain interrupted.")
        requireDrain(caller === Thread.currentThread() && !finished && !cleanupUncertain)
        budget.remainingMillis(1)
        registration.requireSealingOwner(coordinator.ownership)
    }
    internal fun observeFailure(problem: Throwable) {
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("TEST ordinary drain cancelled.")
            problem is InterruptedException || problem is InterruptedIOException || (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) ->
                InterruptedException("TEST ordinary drain interrupted.")
            else -> TestOrdinaryDrainExceptionV1()
        }
        while (true) {
            val old = failure.get()
            if (old is Error || old is CancellationException && retained !is Error || old is InterruptedException && retained !is Error && retained !is CancellationException ||
                old != null && retained is TestOrdinaryDrainExceptionV1) return
            if (failure.compareAndSet(old, retained)) return
        }
    }
    internal fun throwIfSignalled() { failure.get()?.let { if (it is InterruptedException) Thread.currentThread().interrupt(); throw it } }
    override fun toString(): String = "TestRunOrdinaryDrainV1(registered-closed-owner-families,redacted)"
    companion object {
        fun begin(registration: ComplaintTestNamespaceRegistrationV1, deletionOwner: PersistencePhaseOwnership, deletionJdbc: JdbcTemplate,
            audit: AuditService): TestRunOrdinaryDrainV1 = TestRunOrdinaryDrainV1(registration, deletionOwner, deletionJdbc, audit, Clock.systemUTC(), System::nanoTime, null, null)
        internal fun withHttpFixture(registration: ComplaintTestNamespaceRegistrationV1, deletionOwner: PersistencePhaseOwnership, deletionJdbc: JdbcTemplate,
            audit: AuditService, clock: Clock, nanoTime: () -> Long, s3: () -> SdkHttpClient, kms: () -> SdkHttpClient): TestRunOrdinaryDrainV1 =
            TestRunOrdinaryDrainV1(registration, deletionOwner, deletionJdbc, audit, clock, nanoTime, s3, kms)
    }
}

internal enum class TestOrdinaryDrainStepV1 {
    OPEN, PRIMARIES, ALL_PRIMARY_PAGE, ADMIN_PRIMARY_PAGE, CAPTURE, ADMISSION, ABANDON, NATIVE, BEGIN_PASS, APPEND, COMPLETE_PASS, WITNESS,
    RECOVERY_PAGE, RECOVERY_NATIVE, RECOVERY_APPLY, PRIMARY_PAGE, CONVERT, CLOSEOUT, RECYCLE, READY, SEAL,
}
internal enum class TestRunOrdinaryDrainResultV1 { POST_DENIAL_ORDINARY_SEAL_VERIFIED }
internal class TestOrdinaryDrainExceptionV1 : RuntimeException("TEST ordinary drain refused.", null, false, false)
internal fun requireDrain(allowed: Boolean) { if (!allowed) throw TestOrdinaryDrainExceptionV1() }
