package me.manga.kira.backend.complaint.infrastructure.admission

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
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogReadbackRefreshCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReadbackHttpPairV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationCanonicalV3
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationReadbackV3
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationCustodyExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationCustodyFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunFirstProjectionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackAdapter
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import me.manga.kira.backend.complaint.infrastructure.catalog.preferSignerRotationCleanup
import me.manga.kira.backend.complaint.infrastructure.catalog.copyEvidence
import me.manga.kira.backend.complaint.infrastructure.catalog.withSignerRotationCleanup
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.io.InterruptedIOException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Same-process, freshly PROJECTed TEST only. The target owns its own actual ordinary/coordinator
 * graph. This is not an operator-pool alias, restart recovery, gate opener or provider-health proof.
 * Closing revokes further use, not already issued JWTs; ordinary transactions retain their guards.
 */
internal class ComplaintTestNamespaceRegistrationV1 private constructor(
    internal val process: VersionBoundTestNamespaceProcessV1,
    private val activation: Activation,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val installation = AtomicReference<InstallationResources?>()
    private val ownerDeleteContinuation = AtomicReference<InstallationResources?>()

    internal fun requireUsable() {
        try {
            requireConnectionFree()
            requireLifetime()
        } catch (failure: RuntimeException) {
            if (failure is CancellationException) throw failure
            throw ComplaintTestNamespaceRegistrationExceptionV1()
        }
    }

    /** Local lifetime checks also used inside the original sealing holder; never requires another checkout. */
    private fun requireLifetime() {
        requireRegistration(!closed.get())
        process.requireRegistrationTarget()
        requireRegistration(process.pools.ordinary.businessReady() && process.pools.catalogCoordinator.dataSource.businessReady())
        requireRegistration(activation.scope == process.consumers.journalConfiguration.scope.id &&
            activation.configurationHash == HexFormat.of().formatHex(process.configurationHashBytes()))
        requireRegistration(!closed.get())
    }

    internal fun requireSealingOwner(ownership: PersistencePhaseOwnership) {
        requireLifetime()
        val coordinator = process.pools.catalogCoordinator
        requireRegistration(ownership === coordinator.ownership && ownership.manager === coordinator.manager && ownership.dataSource === coordinator.dataSource)
    }

    internal fun requireSealingGate(gate: PersistenceComplaintMaintenanceGateV1) {
        requireLifetime()
        requireRegistration(gate.matchesProjected(activation.token, activation.scope, activation.unsigned, activation.unsignedHash))
    }

    /** Detached, fixed SQL arguments only. The privately issued registration, not these values, admits sealing. */
    internal fun sealingRunArguments(): Array<Any?> = copySealingArguments(activation.runArguments)
    internal fun sealingControlArguments(): Array<Any?> = copySealingArguments(activation.controlArguments)
    internal fun sealingAuditArguments(sealedAt: Instant): Array<Any?> {
        requireLifetime()
        return arrayOf(activation.scope, activation.generation, Timestamp.from(sealedAt))
    }

    private fun copySealingArguments(values: Array<Any?>): Array<Any?> {
        requireLifetime()
        return values.map { value -> when (value) {
            is ByteArray -> value.copyOf()
            is Timestamp -> Timestamp.from(value.toInstant())
            else -> value
        } }.toTypedArray()
    }

    internal fun requireInstallationResources(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        try {
            requireUsable()
            ownership.requireBoundComplaintOrdinary(process.pools)
            requireRegistration(jdbc.dataSource === process.pools.ordinary && ownership.dataSource === jdbc.dataSource)
            // One actual admission/manager/template pair for this binding, not another permit owner on the same pool.
            installation.compareAndSet(null, InstallationResources(ownership, jdbc))
            val retained = checkNotNull(installation.get())
            requireRegistration(retained.ownership === ownership && retained.jdbc === jdbc)
            requireUsable() // A concurrent close cannot rebind or revive the original binding.
        } catch (failure: RuntimeException) {
            if (failure is CancellationException) throw failure
            throw ComplaintTestNamespaceRegistrationExceptionV1()
        }
    }

    /** The already-retained ordinary pair only; safe inside its read phase, and never a binding/checkout path. */
    internal fun requireInstallationPhaseResources(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireLifetime()
        val retained = installation.get()
        requireRegistration(retained != null && retained.ownership === ownership && retained.jdbc === jdbc)
        ownership.requireBoundComplaintOrdinary(process.pools)
        requireRegistration(jdbc.dataSource === process.pools.ordinary && ownership.dataSource === jdbc.dataSource)
        requireLifetime()
    }

    /** Comparison inputs only. Only the registered, committed and released concrete read can emit bootstrap data. */
    internal fun bootstrapExpectedArguments(): Array<Any?> =
        arrayOf(*copySealingArguments(activation.runArguments), *copySealingArguments(activation.controlArguments))

    /** Same retained deletion permit/manager/template only; no new pool, request identity or gate opener. */
    internal fun requireOwnerDeleteContinuationResources(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireLifetime()
        ownership.requireBoundComplaintDeletion(process.pools)
        requireRegistration(process.pools.deletion.businessReady() && jdbc.dataSource === process.pools.deletion)
        ownerDeleteContinuation.compareAndSet(null, InstallationResources(ownership, jdbc))
        val retained = checkNotNull(ownerDeleteContinuation.get())
        requireRegistration(retained.ownership === ownership && retained.jdbc === jdbc)
        requireLifetime()
    }

    internal fun requireOwnerDeleteContinuationGate(gate: PersistenceComplaintMaintenanceGateV1) {
        requireLifetime()
        requireRegistration(gate.matchesProjected(activation.token, activation.scope, activation.unsigned, activation.unsignedHash))
    }

    override fun close() { closed.set(true) }
    override fun toString(): String = "ComplaintTestNamespaceRegistrationV1(first-same-process,closed-gates,redacted)"

    /** Bounded detached identity only; no closed projector, provider/phase graph or global-accounting snapshot. */
    private class Activation(completed: CatalogTestRunFirstProjectionV1.State) {
        val token = completed.frozen.token
        val scope = completed.frozen.scope
        val generation = completed.frozen.generation
        val configurationHash = completed.frozen.manifest().activationRecord.run.configurationSha256
        val unsigned = completed.frozen.unsignedBytes()
        val unsignedHash = completed.frozen.unsignedHash()
        val runArguments: Array<Any?>
        val controlArguments: Array<Any?>

        init {
            val projectedAt = checkNotNull(checkNotNull(completed.snapshot.completedTail).projectedAt)
            val run = completed.frozen.manifest().activationRecord.run
            val envelopeHash = HexFormat.of().parseHex(completed.signed.envelopeSha256)
            runArguments = arrayOf(
                scope, HexFormat.of().parseHex(configurationHash), run.installationLimit, generation, envelopeHash,
                Timestamp.from(projectedAt), completed.frozen.reserve.toLongArray().joinToString(",", "{", "}"),
            )
            controlArguments = arrayOf(
                scope, run.desiredGeneration, run.implementationSchema, HexFormat.of().parseHex(configurationHash),
                completed.frozen.databaseIdentity, completed.frozen.restoreIdentity, completed.frozen.eventWriter,
                completed.frozen.catalogWriter, HexFormat.of().parseHex(completed.frozen.currentTrustHash), generation, envelopeHash,
            )
        }
    }

    private class InstallationResources(val ownership: PersistencePhaseOwnership, val jdbc: JdbcTemplate)

    companion object {
        internal fun issuedBy(original: ComplaintTestNamespaceRegistrationAttemptV1): ComplaintTestNamespaceRegistrationV1 {
            val completed = original.consumeRegistration()
            return ComplaintTestNamespaceRegistrationV1(original.process, Activation(completed))
        }
    }
}

/** Original target attempt: no caller-supplied readback/SQL/cleanup predicate, and no PROJECT writes. */
internal class ComplaintTestNamespaceRegistrationAttemptV1 private constructor(
    completion: CatalogTestRunFirstProjectionV1,
    private val httpFactory: (() -> SdkHttpClient)?,
    private val clock: Clock,
) : AutoCloseable {
    internal val process = completion.target
    private val caller = Thread.currentThread()
    internal val coordinator = process.pools.catalogCoordinator
    internal val budget = PersistenceTimeBudget.start(process.catalogReadback.totalAttemptMillis, coordinator.ownership.nanoClock)
    internal val completion = completion.claim(this)
    private val expected = CatalogTestRunActivationCanonicalV3.fromRetained(process, this.completion.frozen.manifest().activationRecord.run.installationLimit)
    private val construction = S3CatalogReadbackAdapter.Construction()
    private val http = CatalogSignerRotationReadbackHttpPairV1(this, budget)
    private val signal = AtomicReference<Throwable?>()
    private var stage = Stage.NEW
    private var reserved = false
    private var released = false
    private var closed = false
    private var cleanupProven = false
    private var cleanupUncertain = false
    private var providerStarted = false
    private var providerClosed = false
    private var providerFailure: Throwable? = null
    private var closeFailure: Throwable? = null
    private var phase: PersistencePhaseContext? = null
    private var phaseEntered = false
    private var captured: TestNamespaceRegistrationOperationV1? = null
    private var rechecked: TestNamespaceRegistrationOperationV1? = null
    private var proof: CatalogTestRunActivationReadbackV3? = null
    private var successful = false
    private var issued = false

    init {
        requireConnectionFree()
        process.requireRegistrationTarget()
        expected.requireManifest(this.completion.frozen.manifest()) // Comparison only; target raw verification is still mandatory.
    }

    fun register(primary: AwsSessionCredentials, replica: AwsSessionCredentials): ComplaintTestNamespaceRegistrationV1 {
        requireRegistration(caller === Thread.currentThread())
        var failure: Throwable? = null
        try {
            requireConnectionFree()
            requireRunning()
            requireRegistration(stage === Stage.NEW)
            coordinator.catalogRefreshCustody.reserveTestRegistration(this)
            reserved = true
            stage = Stage.CAPTURE
            captured = coordinator.testNamespaceRegistration.execute(this)
            stage = Stage.READBACK
            observe(primary, replica)
            stage = Stage.RECHECK
            rechecked = coordinator.testNamespaceRegistration.execute(this)
            checkNotNull(rechecked).snapshot.requireSame(checkNotNull(captured).snapshot, closed = false)
            requireRunning()
            requireRegistration(providerClosed && providerFailure == null && phase == null)
            successful = true
        } catch (problem: Throwable) {
            observeFailure(problem)
            failure = problem
        } finally {
            runCatching(::close).exceptionOrNull()?.let { failure = preferSignerRotationCleanup(failure, it) }
        }
        throwIfSignalled()
        if (failure != null || !successful) throw ComplaintTestNamespaceRegistrationExceptionV1()
        return ComplaintTestNamespaceRegistrationV1.issuedBy(this)
    }

    private fun observe(primary: AwsSessionCredentials, replica: AwsSessionCredentials) {
        requireProviderRunning()
        val reader = process.catalogReadback
        val at = clock.instant()
        val policy = reader.policyAt(at)
        val configured = reader.sdkLimits
        val millis = budget.remainingMillis(configured.requestTimeoutMillis)
        val limits = S3CatalogReadbackLimits(millis, minOf(configured.connectTimeoutMillis.toLong(), millis).toInt(),
            minOf(configured.readTimeoutMillis.toLong(), millis).toInt(), configured.maximumListBytes, configured.maximumErrorBytes, configured.maximumObjectBytes)
        providerStarted = true
        val read = withSignerRotationCleanup({
            val adapter = S3CatalogReadbackAdapter.openOwned(construction, reader.currentBundleBytes(), reader.chainPolicy.trustBundlePolicy,
                primary, replica, limits, { http.open(limits, httpFactory) }, System::nanoTime)
            CatalogTestRunActivationReadbackV3.verify(TimedReadback(adapter), reader.initialBundleBytes(), reader.currentBundleBytes(), policy,
                completion.snapshot.control.head, expected)
        }, ::closeProviders)
        requireRunning()
        requireRegistration(!clock.instant().isBefore(at) && read.signedEnvelopeBytes().contentEquals(completion.signed.envelopeBytes()))
        checkNotNull(completion.snapshot.completedTail).requireCustody(read.objectVersion, read.retainUntilEpochSecond,
            copyEvidence(read.primaryMetadata, read.tail.envelopeSha256), copyEvidence(read.replicaMetadata, read.tail.envelopeSha256))
        requireRegistration(providerClosed && providerFailure == null)
        proof = read
    }

    internal fun requireProviderRunning() {
        requireConnectionFree()
        requireRunning()
        requireRegistration(stage === Stage.READBACK && phase == null && captured != null && proof == null)
    }

    internal fun requirePersistence(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireRunning()
        requireRegistration(ownership === coordinator.ownership && jdbc.dataSource === coordinator.dataSource &&
            ownership.manager === coordinator.manager && stage in setOf(Stage.CAPTURE, Stage.RECHECK))
        if (stage === Stage.RECHECK) requireRegistration(proof != null && providerClosed && providerFailure == null && captured != null)
    }

    internal fun requirePhaseEntry(ownership: PersistencePhaseOwnership, path: PersistencePhasePath) {
        requireConnectionFree()
        requireRunning()
        requireRegistration(ownership === coordinator.ownership && path === PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_REGISTRATION &&
            !phaseEntered && phase == null && (stage === Stage.CAPTURE && captured == null || stage === Stage.RECHECK && rechecked == null && proof != null))
        phaseEntered = true
    }

    internal fun retainPhase(selected: PersistencePhaseContext) {
        requireRunning()
        requireRegistration(phaseEntered && phase == null)
        phase = selected
    }

    internal fun observePhaseCleanup(selected: PersistencePhaseContext) {
        try {
            if (caller !== Thread.currentThread() || phase !== selected || !selected.testRegistrationCleanupProven(this) ||
                selected.databaseOutcome() === PersistenceDatabaseOutcome.UNKNOWN) cleanupUncertain = true
            else { phase = null; phaseEntered = false }
        } catch (problem: Throwable) {
            cleanupUncertain = true
            observeFailure(problem)
        }
    }

    internal fun authenticate(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requirePersistence(ownership, jdbc)
        val opening = process.pools.descriptors().single { it.role === PersistenceJdbcParticipantRole.CATALOG_COORDINATOR }.openings()
            .map { it.publicDriverProperties() }
        val user = opening.map { it["user"] }.distinct().single()
        val database = opening.map { it["PGDBNAME"] }.distinct().single()
        requireRegistration(jdbc.query("SELECT session_user = ? AND current_user = ? AND current_database() = ? AS authenticated",
            ResultSetExtractor { rows -> rows.next() && rows.getBoolean("authenticated") && !rows.wasNull() && !rows.next() }, user, user, database) == true)
        requirePersistence(ownership, jdbc)
    }

    internal fun requireMaintenanceGate(ownership: PersistencePhaseOwnership, path: PersistencePhasePath, gate: PersistenceComplaintMaintenanceGateV1) {
        requireRunning()
        requireRegistration(ownership === coordinator.ownership && path === PersistencePhasePath.COMPLAINT_TEST_NAMESPACE_REGISTRATION && phaseEntered && phase != null)
        val input = completion.frozen
        requireRegistration(gate.matchesProjected(input.token, input.scope, input.unsignedBytes(), input.unsignedHash()))
    }

    internal fun requireCustody(selected: CatalogReadbackRefreshCustodyV1) = requireRegistration(
        caller === Thread.currentThread() && coordinator.catalogRefreshCustody === selected,
    )

    private fun requireRunning() {
        throwIfSignalled()
        requireRegistration(caller === Thread.currentThread() && !closed && !released && !cleanupUncertain && !Thread.currentThread().isInterrupted)
        budget.remainingMillis(1)
        process.requireRegistrationTarget()
        if (reserved) coordinator.catalogRefreshCustody.requireTestRegistration(this)
    }

    internal fun observeFailure(problem: Throwable) {
        val retained = when {
            problem is Error -> problem
            problem is CancellationException -> CancellationException("TEST registration cancelled.")
            problem is InterruptedException || problem is InterruptedIOException ||
                (problem is PersistencePhaseException && problem.code === PersistencePhaseFailureCode.INTERRUPTED) ||
                (problem is CatalogReadbackException && problem.code === CatalogReadbackFailure.INTERRUPTED) ||
                (problem is CatalogSignerRotationFreezeExceptionV1 && problem.code === CatalogSignerRotationFreezeFailureV1.INTERRUPTED) ||
                (problem is CatalogTestRunActivationCustodyExceptionV1 && problem.code === CatalogTestRunActivationCustodyFailureV1.INTERRUPTED) ||
                (problem is CatalogTestRunActivationExceptionV1 && problem.code === CatalogTestRunActivationFailureV1.INTERRUPTED) ->
                InterruptedException("TEST registration interrupted.")
            else -> return
        }
        while (true) {
            val previous = signal.get()
            if (previous is Error || (previous is CancellationException && retained !is Error) ||
                (previous is InterruptedException && retained is InterruptedException)) return
            if (signal.compareAndSet(previous, retained)) return
        }
    }

    internal fun throwIfSignalled() {
        signal.get()?.let { if (it is InterruptedException) Thread.currentThread().interrupt(); throw it }
    }

    private fun closeProviders() {
        val failure = runCatching { withSignerRotationCleanup(construction::close, http::close) }.exceptionOrNull()
        if (failure != null) { observeFailure(failure); providerFailure = preferSignerRotationCleanup(providerFailure, failure) }
        providerFailure?.let { throw it }
        providerClosed = true
    }

    override fun close() {
        requireRegistration(caller === Thread.currentThread())
        if (closed) { closeFailure?.let { throw it }; return }
        closed = true
        closeFailure = ComplaintTestNamespaceRegistrationExceptionV1()
        val outcomes = listOf(runCatching(::closeProviders), runCatching(::requireConnectionFree), runCatching {
            requireRegistration(!cleanupUncertain && phase == null && !Thread.currentThread().isInterrupted)
            budget.remainingMillis(1)
        })
        var failure: Throwable? = null
        outcomes.forEach { it.exceptionOrNull()?.let { problem -> observeFailure(problem); failure = preferSignerRotationCleanup(failure, problem) } }
        throwIfSignalled()
        failure?.let { throw ComplaintTestNamespaceRegistrationExceptionV1() }
        cleanupProven = true
        if (reserved) coordinator.catalogRefreshCustody.releaseTestRegistrationAfterCleanup(this)
        released = true
        closeFailure = null
    }

    internal fun requireActualCleanup() {
        requireConnectionFree()
        requireRegistration(caller === Thread.currentThread() && closed && cleanupProven && !cleanupUncertain && phase == null)
    }

    internal fun consumeRegistration(): CatalogTestRunFirstProjectionV1.State {
        requireActualCleanup()
        requireRegistration(successful && released && closeFailure == null && !issued && proof != null && providerStarted && providerClosed)
        checkNotNull(captured).requireReleased()
        checkNotNull(rechecked).requireReleased()
        process.requireRegistrationTarget()
        budget.remainingMillis(1)
        issued = true
        return completion
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
                override fun read(destination: ByteArray, offset: Int, length: Int): Int = checked { body.read(destination, offset, length) }
                override fun close() = body.close()
            }
        }
        private fun <T> checked(action: () -> T): T { requireProviderRunning(); return action().also { requireProviderRunning() } }
    }

    override fun toString(): String = "ComplaintTestNamespaceRegistrationAttemptV1(original-runtime-root,fresh-only,redacted)"
    private enum class Stage { NEW, CAPTURE, READBACK, RECHECK }

    companion object {
        fun begin(completion: CatalogTestRunFirstProjectionV1): ComplaintTestNamespaceRegistrationAttemptV1 =
            ComplaintTestNamespaceRegistrationAttemptV1(completion, null, Clock.systemUTC())

        /** Only raw transport/time substitution; no issuer, proof, cleanup or SQL result seam. */
        internal fun withHttpFixture(completion: CatalogTestRunFirstProjectionV1, http: () -> SdkHttpClient, clock: Clock): ComplaintTestNamespaceRegistrationAttemptV1 =
            ComplaintTestNamespaceRegistrationAttemptV1(completion, http, clock)
    }
}

internal class ComplaintTestNamespaceRegistrationExceptionV1 : RuntimeException("TEST registration refused.", null, false, false)
internal fun requireRegistration(allowed: Boolean) { if (!allowed) throw ComplaintTestNamespaceRegistrationExceptionV1() }
