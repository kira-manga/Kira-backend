package me.manga.kira.backend.complaint.infrastructure.reconciliation

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
import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointDocumentV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestProcessAssemblyV1
import me.manga.kira.backend.complaint.infrastructure.admission.TestNamespaceRecoveryRegistrationTailV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogReadbackRefreshCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReadbackHttpPairV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationCanonicalV3
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReadbackV3
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackAdapter
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import me.manga.kira.backend.complaint.infrastructure.catalog.preferSignerRotationCleanup
import me.manga.kira.backend.complaint.infrastructure.catalog.withSignerRotationCleanup
import me.manga.kira.backend.complaint.infrastructure.journal.TestActiveInitialCheckpointReaderV1
import me.manga.kira.backend.security.EpochSealFramesV1
import me.manga.kira.backend.security.TestTerminalCodecKindV1
import me.manga.kira.backend.security.TestTerminalCodecV1
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * First EMPTY epoch1 only. Every attempt is new; durable scan rows are paid locators, never proof.
 * Current SEAL_VERIFIED/WIRE_FROZEN + raw catalog/seal and TWO released native empty passes precede
 * the fixed SUCCESS CAS. Unknown COMMIT/cleanup cannot be repaired by an observed durable SUCCESS.
 */
internal class TestActiveInitialCheckpointV1 private constructor(
    internal val registration: ComplaintTestNamespaceRegistrationV1,
    private val assembly: ComplaintTestProcessAssemblyV1,
    private val historical: TestActiveOrdinarySealV1.Verified?,
    private val httpFixture: (() -> SdkHttpClient)?, private val clock: Clock,
) {
    internal val process = registration.process
    internal val identity = TestActiveFirstCutIdentityV1.fromRegistration(registration)
    internal val coordinator = process.pools.catalogCoordinator
    internal val routing = process.consumers.journalRouting
    internal val recipe = checkNotNull(process.initialCheckpoint)
    internal val scope = identity.scope
    internal val writer = identity.writer.toString()
    internal val attemptId = UUID.randomUUID()
    private val nanoClock = TestActiveSealNanoClockV1(coordinator.ownership.nanoClock)
    internal val budget = PersistenceTimeBudget.start(routing.journalConfiguration.declaration().limits.deadlines.scanMillis.toLong(), nanoClock)
    internal val codec = TestTerminalCodecV1.fromRetained(routing, recipe.nanoTime)
    internal val codecAttempt = codec.startAttempt(TestTerminalCodecKindV1.EPOCH_SEAL, budget)
    internal val runContext = TestTerminalRunContextV1(scope.toString(), identity.generation, hex(identity.activationCatalogHash()),
        hex(identity.configurationHash()), identity.sealEncodingSha256)
    internal val maximumEntries = routing.journalConfiguration.declaration().limits.capacity.maximumRetainedVersions
    internal val maximumBytes = routing.journalConfiguration.declaration().limits.capacity.maximumScanStagingBytes
    private val manifest = emptyManifest()
    internal val manifestSha256 = manifest.first
    internal val manifestFramedBytes = manifest.second
    private val renewal = TestActiveSealRenewalWindowV1(budget)
    internal var leaseToken = 0L
        private set
    internal var step = TestActiveInitialCheckpointStepV1.READ
        private set
    internal val path = PersistencePhasePath.COMPLAINT_TEST_ACTIVE_INITIAL_CHECKPOINT
    internal var passNumber = 0
        private set
    private val caller = Thread.currentThread()
    private val failure = AtomicReference<Throwable?>()
    private var started = false
    private var finished = false
    private var successful = false
    private var phase: PersistencePhaseContext? = null
    private var phaseEntered = false
    private var cleanupUncertain = false
    private var jdbc: JdbcTemplate? = null
    private var captured: TestActiveInitialCheckpointOperationV1? = null
    private var committed: TestActiveInitialCheckpointOperationV1? = null
    private var native: TestActiveInitialCheckpointReaderV1? = null
    private var nativeCreating = false
    private var nativeClaimed = false
    private var sealProof: TestActiveInitialCheckpointReaderV1.SealProof? = null
    private val staged = arrayOfNulls<TestActiveInitialCheckpointOperationV1>(2)
    private val passes = arrayOfNulls<TestActiveInitialCheckpointReaderV1.EmptyPass>(2)
    private var priorScans: List<TestActiveInitialCheckpointRowsV1.Scan> = emptyList()
    private var document: TestActiveInitialCheckpointDocumentV1? = null
    private var documentBytes: ByteArray? = null
    private var documentSha256: String? = null
    private val readbackIdentity = registration.activeReadbackArguments()
    private val expected = CatalogTestRunActivationCanonicalV3.fromRetained(process, identity.installationLimit)
    private val construction = S3CatalogReadbackAdapter.Construction()
    private val readbackBudget = budget.capped(process.catalogReadback.totalAttemptMillis)
    private val http = CatalogSignerRotationReadbackHttpPairV1(this, readbackBudget)
    private var readbackStage = false
    private var readbackReserved = false
    private var providerClosed = false
    private var providerFailure: Throwable? = null
    private var raw: CatalogTestRunActivationReadbackV3? = null

    init {
        requireConnectionFree(); registration.requireActiveIdentityTarget(assembly)
        requireInitialCheckpoint(scope == routing.journalConfiguration.scope.id && writer == routing.journalConfiguration.declaration().writer.generationId &&
            process.activeFirstCut != null && process.activeCutoffPublication != null && routing.journalConfiguration.registeredAdminBatchDelete)
        recipe.requireRetained(routing, process.pools, process.ordinarySeal)
        historical?.let { requireInitialCheckpoint(it.scope == scope) }
    }

    fun checkpoint(primary: AwsSessionCredentials, replica: AwsSessionCredentials): Completed {
        throwIfSignalled()
        requireInitialCheckpoint(caller === Thread.currentThread() && !started)
        started = true
        try {
            requireRunning()
            recipe.claim(this); nativeClaimed = true
            coordinator.catalogRefreshCustody.reserveTestActiveInitialCheckpoint(this); readbackReserved = true
            captured = execute(TestActiveInitialCheckpointStepV1.READ)
            requireHistorical()
            observe(primary, replica)
            renewal.beginDispatch(first = true)
            val acquired = execute(TestActiveInitialCheckpointStepV1.ACQUIRE)
            renewal.committedAndReleased()
            priorScans = acquired.scans
            requireInitialCheckpoint(priorScans.isEmpty())
            nativeCreating = true
            val reader = recipe.reader(this).also { native = it; nativeCreating = false }
            sealProof = reader.verifySeal()
            sealControl().requireNative(this, checkNotNull(sealProof))
            for (number in 1..2) {
                renew()
                passNumber = number
                val start = execute(TestActiveInitialCheckpointStepV1.START_PASS)
                priorScans = start.scans; staged[number - 1] = start
                passes[number - 1] = reader.emptyPass(number)
                val end = execute(TestActiveInitialCheckpointStepV1.COMPLETE_PASS)
                priorScans = end.scans
            }
            reader.close() // All native clients, exchanges, plaintext/key leases and owned bytes before SUCCESS SQL.
            requireNativeReleased(recipe)
            renew()
            bindDocument()
            committed = execute(TestActiveInitialCheckpointStepV1.SUCCESS)
            committed?.requireReleased(); requireCurrentRunning()
            successful = true
        } catch (problem: Throwable) { observeFailure(problem) }
        finally {
            runCatching { native?.close() }.exceptionOrNull()?.let(::observeFailure)
            runCatching(::closeReadback).exceptionOrNull()?.let(::observeFailure)
            if (nativeClaimed) runCatching { recipe.release(this); nativeClaimed = false }.exceptionOrNull()?.let(::observeFailure)
            if (readbackReserved) runCatching {
                coordinator.catalogRefreshCustody.releaseTestActiveInitialCheckpointAfterCleanup(this); readbackReserved = false
            }.exceptionOrNull()?.let(::observeFailure)
            captured?.discardDetached(); documentBytes?.fill(0); documentBytes = null
            readbackIdentity.filterIsInstance<ByteArray>().forEach { it.fill(0) }
            runCatching { budget.remainingMillis(1) }.exceptionOrNull()?.let(::observeFailure)
            finished = true
        }
        throwIfSignalled(); requireInitialCheckpoint(successful)
        return Completed.issue(this)
    }

    private fun emptyManifest(): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = EpochSealFramesV1.update(digest, listOf(EpochSealFramesV1.DOMAIN, "1", "manifest", writer,
            routing.journalConfiguration.ordinaryPrefix, "TEST", scope.toString(), "1", "1", "0"))
        requireInitialCheckpoint(bytes in 1..maximumBytes)
        return HexFormat.of().formatHex(digest.digest()) to bytes
    }
    private fun execute(selected: TestActiveInitialCheckpointStepV1): TestActiveInitialCheckpointOperationV1 {
        requireConnectionFree(); requireRunning(); requireInitialCheckpoint(phase == null && !phaseEntered)
        step = selected
        return coordinator.testActiveInitialCheckpoint.execute(this)
    }
    private fun renew() {
        requireCurrentRunning(); renewal.beginDispatch(first = false)
        execute(TestActiveInitialCheckpointStepV1.RENEW)
        renewal.committedAndReleased()
    }
    internal fun phaseBudget(): PersistenceTimeBudget = if (step === TestActiveInitialCheckpointStepV1.READ) budget.capped(2_000) else renewal.phaseBudget()
    internal fun retainAcquiringToken(operation: TestActiveInitialCheckpointOperationV1, before: Long, token: Long) {
        requireRunning()
        requireInitialCheckpoint(operation.original === this && step === TestActiveInitialCheckpointStepV1.ACQUIRE && leaseToken == 0L &&
            before < Long.MAX_VALUE && token == before + 1 && token > checkNotNull(captured).current.preparingToken)
        leaseToken = token // No use before this exact operation's COMMIT and physical release.
    }
    internal fun requireState(value: TestActiveInitialCheckpointRowsV1.Current) {
        requireRunning()
        if (step !== TestActiveInitialCheckpointStepV1.READ) checkNotNull(captured).current.requireSame(value)
    }
    internal fun requirePriorScans(value: List<TestActiveInitialCheckpointRowsV1.Scan>) {
        requireRunning()
        requireInitialCheckpoint(value.size == priorScans.size && value.indices.all { value[it].same(priorScans[it]) })
    }
    internal fun frozenRow() = checkNotNull(checkNotNull(captured).intent)
    internal fun sealControl() = checkNotNull(checkNotNull(captured).control)
    internal fun requireHistorical() {
        historical?.let { requireInitialCheckpoint(it.scope == scope && it.objectKey == frozenRow().binding.objectKey &&
            it.version == sealControl().version && it.ciphertextSha256 == frozenRow().wireSha256) }
    }
    internal fun stagedPass(number: Int): TestActiveInitialCheckpointRowsV1.Scan {
        requireRunning(); requireInitialCheckpoint(number in 1..2)
        val operation = checkNotNull(staged[number - 1]); operation.requireCommittedComparison()
        return operation.scans.single { it.pass == number }.also { requireInitialCheckpoint(it.token == leaseToken && it.state == "SCANNING") }
    }
    internal fun completedPass(number: Int): TestActiveInitialCheckpointReaderV1.EmptyPass {
        requireRunning(); requireInitialCheckpoint(number in 1..2)
        return checkNotNull(passes[number - 1]).also { it.requireOriginal(this) }
    }
    internal fun requireRecipe(selected: VersionBoundTestActiveInitialCheckpointV1) {
        requireInitialCheckpoint(caller === Thread.currentThread() && selected === recipe && process.initialCheckpoint === selected)
    }
    internal fun requireReader(selected: TestActiveInitialCheckpointReaderV1) {
        requireConnectionFree(); requireCurrentRunning()
        requireInitialCheckpoint(native === selected && nativeClaimed && phase == null && !phaseEntered && !nativeCreating)
        recipe.requireOwned(this)
    }
    internal fun requireProofOwner(selected: TestActiveInitialCheckpointReaderV1) {
        requireRunning(); requireInitialCheckpoint(native === selected && nativeClaimed)
    }
    internal fun nativeContinuationMillis(ceiling: Int): Int {
        requireConnectionFree(); requireCurrentRunning()
        return renewal.remainingMillis(ceiling)
    }
    internal fun requireNativeReleased(selected: VersionBoundTestActiveInitialCheckpointV1) {
        requireConnectionFree(); requireRecipe(selected)
        requireInitialCheckpoint(!nativeCreating)
        native?.requireClosed() // Null is possible only before a construction began, not an accepted cleanup flag.
    }
    internal fun abortNative() {
        observeFailure(TestActiveInitialCheckpointExceptionV1())
        native?.close()
    }

    private fun bindDocument() {
        requireConnectionFree(); requireCurrentRunning(); requireNativeReleased(recipe)
        sealControl().requireNative(this, checkNotNull(sealProof))
        val first = completedPass(1); val second = completedPass(2)
        val row = frozenRow()
        val value = TestActiveInitialCheckpointDocumentV1(scope.toString(), identity.desiredGeneration, leaseToken,
            hex(identity.configurationHash()), hex(identity.journalHash()), identity.databaseIdentity.toString(), identity.restoreIdentity.toString(),
            identity.acceptedCatalogGeneration, hex(identity.acceptedCatalogHash()), hex(identity.trustBundleHash()), identity.catalogWriter.toString(), writer,
            row.binding.operationToken, row.binding.objectKey, sealControl().version, row.canonicalSha256, checkNotNull(row.wireSha256),
            manifestSha256, manifestFramedBytes, first.summary, second.summary)
        document = value
        documentBytes = value.canonicalBytes()
        documentSha256 = Sha256.hex(checkNotNull(documentBytes))
    }
    internal fun documentArguments(operation: TestActiveInitialCheckpointOperationV1): Array<Any?> {
        requireRunning(); requireInitialCheckpoint(operation.original === this && step === TestActiveInitialCheckpointStepV1.SUCCESS)
        checkNotNull(native).requireClosed()
        sealControl().requireNative(this, checkNotNull(sealProof)); completedPass(1); completedPass(2)
        val value = checkNotNull(document)
        requireInitialCheckpoint(value.fencingToken == leaseToken && value.manifestSha256 == manifestSha256)
        return TestActiveInitialCheckpointRowsV1.documentArguments(value, checkNotNull(documentBytes), checkNotNull(documentSha256))
    }

    internal fun requirePersistence(ownership: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requireRunning(); requireInitialCheckpoint(ownership === coordinator.ownership && selected.dataSource === coordinator.dataSource && ownership.manager === coordinator.manager)
        if (jdbc == null) jdbc = selected
        requireInitialCheckpoint(jdbc === selected)
    }
    internal fun requirePhaseEntry(ownership: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireConnectionFree(); requireRunning()
        requireInitialCheckpoint(ownership === coordinator.ownership && path === this.path && phase == null && !phaseEntered)
        phaseEntered = true
    }
    internal fun retainPhase(selected: PersistencePhaseContext) { requireRunning(); requireInitialCheckpoint(phaseEntered && phase == null); phase = selected }
    internal fun observePhaseCleanup(selected: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testActiveInitialCheckpointCleanupProven(this) || selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) {
                cleanupUncertain = true; observeFailure(TestActiveInitialCheckpointExceptionV1())
            } else { phase = null; phaseEntered = false }
        } catch (problem: Throwable) { cleanupUncertain = true; observeFailure(problem) }
    }
    internal fun authenticate(ownership: PersistencePhaseOwnership, selected: JdbcTemplate) {
        requirePersistence(ownership, selected)
        val openings = process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.CATALOG_COORDINATOR }.openings().map { it.publicDriverProperties() }
        val user = openings.map { it["user"] }.distinct().single(); val database = openings.map { it["PGDBNAME"] }.distinct().single()
        requireInitialCheckpoint(selected.query("SELECT session_user = ? AND current_user = ? AND current_database() = ? AS valid",
            ResultSetExtractor { rows -> rows.next() && rows.getBoolean("valid") && !rows.wasNull() && !rows.next() }, user, user, database) == true)
        requirePersistence(ownership, selected)
    }
    internal fun requireMaintenanceGate(ownership: PersistencePhaseOwnership, path: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireRunning(); requireInitialCheckpoint(ownership === coordinator.ownership && path === this.path && phaseEntered && phase != null)
        registration.requireActiveIdentityGate(gate)
    }
    private fun requireCurrentRunning() {
        requireRunning(); requireInitialCheckpoint(leaseToken > checkNotNull(captured).current.preparingToken); renewal.remainingMillis(1)
    }
    private fun requireRunning() {
        throwIfSignalled()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Initial TEST checkpoint interrupted.")
        requireInitialCheckpoint(caller === Thread.currentThread() && started && !finished && !cleanupUncertain)
        budget.remainingMillis(1)
        registration.requireActiveIdentityTarget(assembly); recipe.requireRetained(routing, process.pools, process.ordinarySeal)
        if (readbackReserved) coordinator.catalogRefreshCustody.requireTestActiveInitialCheckpoint(this)
    }
    private fun requireReleased(operation: TestActiveInitialCheckpointOperationV1?) { requireRunning(); requireInitialCheckpoint(phase == null && !phaseEntered); checkNotNull(operation).requireReleased() }
    internal fun observeFailure(problem: Throwable) {
        renewal.poison()
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("Initial TEST checkpoint cancelled.")
            problem is InterruptedException || problem is InterruptedIOException || problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED ->
                InterruptedException("Initial TEST checkpoint interrupted.")
            else -> TestActiveInitialCheckpointExceptionV1()
        }
        while (true) {
            val previous = failure.get()
            if (previous is Error || previous is CancellationException && retained !is Error || previous is InterruptedException && retained !is Error && retained !is CancellationException || previous != null && retained is TestActiveInitialCheckpointExceptionV1) return
            if (failure.compareAndSet(previous, retained)) return
        }
    }
    internal fun throwIfSignalled() { failure.get()?.let { if (it is InterruptedException) Thread.currentThread().interrupt(); throw it } }

    internal fun historyArguments(): Array<Any?> = arrayOf(readbackIdentity[0], scope, identity.generation, identity.activationCatalogHash(), process.catalogReadback.chainPolicy.limits.maximumGenerations + 1)
    internal fun requireAdmissionComparisons(operation: TestActiveInitialCheckpointOperationV1, tail: TestNamespaceRecoveryRegistrationTailV1, history: CatalogTestRunActivationHistoryV1) {
        requireRunning(); requireInitialCheckpoint(operation.original === this && tail.token == readbackIdentity[0] && tail.scope == scope && tail.generation == identity.generation &&
            tail.envelopeHash.contentEquals(readbackIdentity[3] as ByteArray) && tail.unsigned.contentEquals(readbackIdentity[4] as ByteArray) && tail.unsignedHash.contentEquals(readbackIdentity[5] as ByteArray))
        if (step !== TestActiveInitialCheckpointStepV1.READ) { checkNotNull(captured).tail.requireSame(tail); checkNotNull(captured).history.requireSame(history); requireRawReleased() }
    }
    internal fun requireRawReleased() { requireRunning(); requireInitialCheckpoint(raw != null && providerClosed && providerFailure == null && !readbackStage) }
    private fun observe(primary: AwsSessionCredentials, replica: AwsSessionCredentials) {
        readbackStage = true
        val reader = process.catalogReadback
        val at = clock.instant()
        val configured = reader.sdkLimits
        val millis = readbackBudget.remainingMillis(configured.requestTimeoutMillis)
        val limits = S3CatalogReadbackLimits(millis, minOf(configured.connectTimeoutMillis.toLong(), millis).toInt(), minOf(configured.readTimeoutMillis.toLong(), millis).toInt(),
            configured.maximumListBytes, configured.maximumErrorBytes, configured.maximumObjectBytes)
        val result = withSignerRotationCleanup({
            val adapter = S3CatalogReadbackAdapter.openOwned(construction, reader.currentBundleBytes(), reader.chainPolicy.trustBundlePolicy,
                primary, replica, limits, { http.open(limits, httpFixture) }, recipe.nanoTime)
            CatalogTestRunActivationReadbackV3.verifyActiveCurrent(TimedReadback(adapter), reader.initialBundleBytes(), reader.currentBundleBytes(), reader.policyAt(at),
                identity.head, expected, checkNotNull(captured).history)
        }, ::closeReadback)
        requireRunning(); readbackBudget.remainingMillis(1)
        requireInitialCheckpoint(!clock.instant().isBefore(at) && providerClosed && providerFailure == null)
        checkNotNull(captured).tail.requireRaw(result, reader.chainPolicy.limits.maximumManifestRecords)
        raw = result; readbackStage = false
    }
    internal fun requireProviderRunning() {
        requireConnectionFree(); requireRunning(); readbackBudget.remainingMillis(1)
        requireInitialCheckpoint(readbackStage && phase == null && !phaseEntered && raw == null && !providerClosed)
        requireReleased(captured)
    }
    internal fun requireCustody(selected: CatalogReadbackRefreshCustodyV1) = requireInitialCheckpoint(caller === Thread.currentThread() && coordinator.catalogRefreshCustody === selected)
    private fun closeReadback() {
        val problem = runCatching { withSignerRotationCleanup({ construction.close() }, http::close) }.exceptionOrNull()
        if (problem != null) providerFailure = preferSignerRotationCleanup(providerFailure, problem)
        providerFailure?.let { throw it }
        providerClosed = true
    }
    internal fun requireActualReadbackCleanup() {
        requireConnectionFree(); requireCustody(coordinator.catalogRefreshCustody)
        requireInitialCheckpoint(providerClosed && providerFailure == null && phase == null && !phaseEntered && !cleanupUncertain)
    }
    private inner class TimedReadback(private val actual: S3CatalogReadbackAdapter) : CatalogReadbackPort {
        override fun listVersions(request: CatalogListRequest) = checked { actual.listVersions(request) }
        override fun openVersion(request: CatalogGetRequest): CatalogVersionBody {
            requireProviderRunning()
            val body = actual.openVersion(request)
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


    /** Historical completion only. A current content/Admin/health consumer needs its own checked current reader. */
    class Completed private constructor(val scope: UUID, val fencingToken: Long, val checkpointSha256: String) {
        companion object {
            internal fun issue(original: TestActiveInitialCheckpointV1): Completed {
                original.throwIfSignalled(); requireConnectionFree()
                requireInitialCheckpoint(original.caller === Thread.currentThread() && original.finished && original.successful &&
                    !original.nativeClaimed && !original.readbackReserved && original.providerClosed && original.providerFailure == null &&
                    !original.cleanupUncertain && original.phase == null && !original.phaseEntered)
                runCatching { original.budget.remainingMillis(1); original.renewal.remainingMillis(1); checkNotNull(original.native).requireClosed() }
                    .exceptionOrNull()?.let(original::observeFailure)
                original.throwIfSignalled(); checkNotNull(original.committed).requireReleased()
                return Completed(original.scope, original.leaseToken, checkNotNull(original.documentSha256))
            }
        }
        override fun toString(): String = "InitialCheckpointCompleted(historical-only,redacted)"
    }
    companion object {
        fun afterSeal(verified: TestActiveOrdinarySealV1.Verified, registration: ComplaintTestNamespaceRegistrationV1,
            assembly: ComplaintTestProcessAssemblyV1): TestActiveInitialCheckpointV1 =
            TestActiveInitialCheckpointV1(registration, assembly, verified, null, checkNotNull(registration.process.initialCheckpoint).clock)
        fun restartInitial(registration: ComplaintTestNamespaceRegistrationV1, assembly: ComplaintTestProcessAssemblyV1): TestActiveInitialCheckpointV1 =
            TestActiveInitialCheckpointV1(registration, assembly, null, null, checkNotNull(registration.process.initialCheckpoint).clock)
        internal fun withHttpFixture(verified: TestActiveOrdinarySealV1.Verified?, registration: ComplaintTestNamespaceRegistrationV1,
            assembly: ComplaintTestProcessAssemblyV1, http: () -> SdkHttpClient, clock: Clock): TestActiveInitialCheckpointV1 =
            TestActiveInitialCheckpointV1(registration, assembly, verified, http, clock)
        private fun hex(value: ByteArray): String = try { HexFormat.of().formatHex(value) } finally { value.fill(0) }
    }
}

internal class TestActiveInitialCheckpointExceptionV1 : RuntimeException("Initial TEST checkpoint refused.", null, false, false)
internal fun requireInitialCheckpoint(value: Boolean) { if (!value) throw TestActiveInitialCheckpointExceptionV1() }
