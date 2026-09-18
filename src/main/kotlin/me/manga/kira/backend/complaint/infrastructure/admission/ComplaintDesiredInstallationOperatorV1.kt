package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.SystemPersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.security.AcquiredVersionedSecret
import me.manga.kira.backend.security.VersionedSecretBinding
import me.manga.kira.backend.security.aws.AwsSecretVersionLimits
import me.manga.kira.backend.security.aws.AwsSecretsManagerVersionResolver
import org.springframework.jdbc.core.JdbcTemplate
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient
import java.util.UUID

/** Explicit one-shot non-web job owner. Nothing here is a bean, startup runner, reusable authority receipt or deployment action. */
internal class ComplaintDesiredInstallationOperatorV1 private constructor(
    internal val budget: PersistenceTimeBudget,
    private val secretHttpFixture: (() -> SdkHttpClient)?,
) : AutoCloseable {
    private val caller = Thread.currentThread()
    private val assembly = ComplaintDesiredProcessAssemblyV1() // Retained before any provider or root construction.
    private var entered = false
    private var closed = false
    private var closeFailure: ComplaintDesiredInstallationExceptionV1? = null
    private var resolver: AwsSecretsManagerVersionResolver? = null
    private var resolverCloseIssued = false
    private var resolverCloseFailed = false
    private var attempt: ComplaintDesiredInstallAttemptV1? = null
    private var firstDAttempt: ComplaintSignedGenesisFirstDAttemptV1? = null

    fun bootstrap(
        inputs: ComplaintDesiredDeploymentInputsV1,
        secretCredentials: AwsSessionCredentials,
        sealerCredentials: AwsSessionCredentials?,
    ): ComplaintDesiredInstallationResultV1 = install(inputs, null, secretCredentials, sealerCredentials)

    fun supersede(
        inputs: ComplaintDesiredDeploymentInputsV1,
        expectedGeneration: Long,
        secretCredentials: AwsSessionCredentials,
        sealerCredentials: AwsSessionCredentials?,
    ): ComplaintDesiredInstallationResultV1 = install(inputs, expectedGeneration, secretCredentials, sealerCredentials)

    /** Separate signed-PREPARED entry, never a relaxed pristine bootstrap or a supplied D/verification receipt. */
    @Suppress("TooGenericExceptionCaught")
    fun selectSignedGenesisFirst(
        inputs: ComplaintDesiredDeploymentInputsV1,
        release: ComplaintSignedGenesisFirstDInputsV1,
        secretCredentials: AwsSessionCredentials,
        sealerCredentials: AwsSessionCredentials?,
    ): ComplaintSignedGenesisFirstDResultV1 {
        var result: ComplaintSignedGenesisFirstDResultV1? = null
        var failure: Throwable? = null
        try {
            requireConnectionFree()
            requireRunning()
            requireDesiredInstallation(!entered, ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
            entered = true
            inputs.requireBootstrapProfile()
            val acquired = inputs.allBindings().map { acquire(it, secretCredentials) }
            assembly.assemble(inputs, acquired, sealerCredentials)
            val verified = release.verifyFor(assembly.target) // Raw crypto/J/P checks before even preparing the operator pool.
            requireRunning()
            assembly.prepareOperator(budget)
            val retained = ComplaintSignedGenesisFirstDAttemptV1(this, assembly.coordinator, assembly.target, verified, budget)
            firstDAttempt = retained
            result = assembly.coordinator.signedGenesisFirstDesired.select(retained)
            requireRunning()
        } catch (problem: Throwable) {
            firstDAttempt?.abort()
            failure = problem
        } finally {
            try {
                close()
            } catch (cleanup: Throwable) {
                failure = cleanup
            }
        }
        failure?.let { throw boundedDesiredInstallationFailure(it) }
        return checkNotNull(result)
    }

    @Suppress("TooGenericExceptionCaught") // Never expose SQL/provider/path/credential text; cleanup takes precedence over a historical DB outcome.
    private fun install(
        inputs: ComplaintDesiredDeploymentInputsV1,
        expectedGeneration: Long?,
        secretCredentials: AwsSessionCredentials,
        sealerCredentials: AwsSessionCredentials?,
    ): ComplaintDesiredInstallationResultV1 {
        var result: ComplaintDesiredInstallationResultV1? = null
        var failure: Throwable? = null
        try {
            requireConnectionFree()
            requireRunning()
            requireDesiredInstallation(!entered, ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
            entered = true
            if (expectedGeneration == null) {
                inputs.requireBootstrapProfile()
            } else {
                requireDesiredInstallation(
                    expectedGeneration in 1 until Long.MAX_VALUE && inputs.desiredGeneration == expectedGeneration + 1,
                    ComplaintDesiredInstallationFailureV1.INPUT_REFUSED,
                )
            }
            val acquired = inputs.allBindings().map { acquire(it, secretCredentials) }
            assembly.assemble(inputs, acquired, sealerCredentials)
            requireRunning()
            assembly.prepareOperator(budget)
            val retained = ComplaintDesiredInstallAttemptV1(this, assembly.coordinator, assembly.target, expectedGeneration, budget)
            attempt = retained
            result = assembly.coordinator.desiredInstallation.install(retained)
            requireRunning()
        } catch (problem: Throwable) {
            attempt?.abort()
            failure = problem
        } finally {
            try {
                close()
            } catch (cleanup: Throwable) {
                failure = cleanup
            }
        }
        failure?.let { throw boundedDesiredInstallationFailure(it) }
        return checkNotNull(result)
    }

    private fun acquire(binding: VersionedSecretBinding, credentials: AwsSessionCredentials): AcquiredVersionedSecret {
        requireRunning()
        requireConnectionFree()
        requireDesiredInstallation(resolver == null && !resolverCloseFailed, ComplaintDesiredInstallationFailureV1.CLEANUP_UNPROVEN)
        val requestMillis = budget.remainingMillis(5_000)
        val limits = AwsSecretVersionLimits(requestMillis, minOf(2_000L, requestMillis).toInt(), minOf(2_000L, requestMillis).toInt())
        val region = binding.version.resourceArn.split(':')[3]
        val fixture = secretHttpFixture
        val opened = if (fixture == null) {
            AwsSecretsManagerVersionResolver.open(region, credentials, limits)
        } else {
            AwsSecretsManagerVersionResolver.withHttpFixture(region, credentials, limits, fixture)
        }
        resolver = opened // Before the first actual provider request.
        resolverCloseIssued = false
        return try {
            AcquiredVersionedSecret.acquire(binding, opened).also { requireRunning() }
        } finally {
            closeResolver()
            requireRunning()
        }
    }

    private fun closeResolver() {
        val selected = resolver ?: return
        if (!resolverCloseIssued) {
            resolverCloseIssued = true
            val closing = runCatching(selected::close)
            if (closing.isSuccess) resolver = null else resolverCloseFailed = true
        }
        requireDesiredInstallation(resolver == null && !resolverCloseFailed, ComplaintDesiredInstallationFailureV1.CLEANUP_UNPROVEN)
    }

    internal fun owns(candidate: ComplaintDesiredInstallAttemptV1): Boolean = !closed && attempt === candidate && caller === Thread.currentThread()

    internal fun owns(candidate: ComplaintSignedGenesisFirstDAttemptV1): Boolean = !closed && firstDAttempt === candidate && caller === Thread.currentThread()

    internal fun requireRunning() {
        requireDesiredInstallation(caller === Thread.currentThread() && !closed, ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
        requireDesiredInstallation(!Thread.currentThread().isInterrupted, ComplaintDesiredInstallationFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
    }

    /** No timeout/exception skips stop/close. Expired or ambiguous cleanup never emits success, nor retries the original DB operation. */
    override fun close() {
        if (closed) {
            closeFailure?.let { throw it }
            return
        }
        requireDesiredInstallation(caller === Thread.currentThread(), ComplaintDesiredInstallationFailureV1.CLEANUP_UNPROVEN)
        closed = true
        attempt?.abort()
        firstDAttempt?.abort()
        val provider = runCatching { closeResolver() }
        val roots = runCatching { assembly.close() }
        val proof = runCatching { assembly.requireCleanup(budget) }
        val failure = when {
            !provider.isSuccess || !roots.isSuccess || !proof.isSuccess -> ComplaintDesiredInstallationFailureV1.CLEANUP_UNPROVEN
            Thread.currentThread().isInterrupted -> ComplaintDesiredInstallationFailureV1.INTERRUPTED
            else -> null
        }
        if (failure != null) {
            val refused = ComplaintDesiredInstallationExceptionV1(failure)
            closeFailure = refused
            throw refused
        }
        val onTime = runCatching { budget.remainingMillis(1) }
        if (onTime.isFailure) {
            val refused = ComplaintDesiredInstallationExceptionV1(ComplaintDesiredInstallationFailureV1.TIME_BUDGET_EXHAUSTED)
            closeFailure = refused
            throw refused
        }
    }

    override fun toString(): String = "ComplaintDesiredInstallationOperatorV1(one-shot,redacted,no-runtime-authority)"

    companion object {
        /** Start BEFORE bounded manifest reading/parsing. Tests may vary only the original monotonic clock. */
        fun begin(clock: PersistenceNanoClock = SystemPersistenceNanoClock): ComplaintDesiredInstallationOperatorV1 =
            ComplaintDesiredInstallationOperatorV1(PersistenceTimeBudget.start(60_000, clock), null)

        /** Raw HTTP test substitution only; actual SDK/version acquisition, cold graph, SQL authentication and phase ownership are unchanged. */
        internal fun withSecretHttpFixture(
            httpFactory: () -> SdkHttpClient,
            clock: PersistenceNanoClock = SystemPersistenceNanoClock,
        ): ComplaintDesiredInstallationOperatorV1 = ComplaintDesiredInstallationOperatorV1(PersistenceTimeBudget.start(60_000, clock), httpFactory)
    }
}

/** Retained only by the exact original operator call. An independently constructed attempt cannot enter a phase. */
internal class ComplaintDesiredInstallAttemptV1 internal constructor(
    private val operator: ComplaintDesiredInstallationOperatorV1,
    private val coordinator: CatalogCoordinatorPersistence,
    private val target: VersionBoundComplaintProcessConfiguration,
    internal val expectedGeneration: Long?,
    internal val budget: PersistenceTimeBudget,
) {
    private val caller = Thread.currentThread()
    private val ownership = coordinator.ownership
    private val desired = target.desiredSettings() // D was computed from THIS retained cold graph before any SQL.
    private val hash = desired.configurationHashBytes()
    internal val databaseIdentity = desired.databaseIdentity
    internal val restoreIdentity = desired.restoreIdentity
    internal val eventWriterGeneration = UUID.fromString(target.consumers.journalConfiguration.declaration().writer.generationId)
    internal val desiredGeneration = desired.desiredGeneration
    internal val implementationSchema = desired.implementationSchema
    internal val databaseName = checkNotNull(
        target.pools.descriptors().flatMap { it.openings() }.map { it.publicDriverProperties()["PGDBNAME"] }.distinct().single(),
    )
    internal var path = if (expectedGeneration == null) PersistencePhasePath.COMPLAINT_DESIRED_BOOTSTRAP else PersistencePhasePath.COMPLAINT_DESIRED_CLOSE
        private set
    private var failed = false
    private var retained: ComplaintDesiredInstallOperationV1? = null
    private var closedOld: ComplaintDesiredInstallOperationV1? = null

    internal fun requirePhaseEntry(selected: PersistencePhaseOwnership, selectedPath: PersistencePhasePath) {
        requireRunning()
        requireDesiredInstallation(
            selected === ownership && path === selectedPath && coordinator.desiredInstallationOperator,
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
    }

    internal fun requirePersistence(selected: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        requireRunning()
        requireDesiredInstallation(
            selected === ownership && jdbc.dataSource === coordinator.dataSource && coordinator.desiredInstallationOperator,
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
        coordinator.requireResources()
    }

    internal fun requireRunning() {
        requireDesiredInstallation(!failed && caller === Thread.currentThread() && operator.owns(this), ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
        operator.requireRunning()
        target.requireUnchangedConfiguration() // Local actual-owner/settings checks only; no JSON/hash/provider work.
    }

    internal fun retain(operation: ComplaintDesiredInstallOperationV1) {
        requireRunning()
        requireDesiredInstallation(retained == null && operation.belongsTo(this), ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
        retained = operation
    }

    internal fun requireOperation(operation: ComplaintDesiredInstallOperationV1) {
        requireRunning()
        requireDesiredInstallation(retained === operation && operation.belongsTo(this), ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
    }

    internal fun continueAfterClose(operation: ComplaintDesiredInstallOperationV1) {
        requireConnectionFree()
        requireOperation(operation)
        requireDesiredInstallation(path === PersistencePhasePath.COMPLAINT_DESIRED_CLOSE, ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
        operation.requireClosedOldBinding() // Known commit AND actual original holder/permit release.
        closedOld = operation
        retained = null
        path = PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE
    }

    internal fun oldBinding(operation: ComplaintDesiredInstallOperationV1): DesiredOldBindingV1 {
        requireOperation(operation)
        requireDesiredInstallation(path === PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE, ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
        return checkNotNull(closedOld).requireClosedOldBinding()
    }

    internal fun configurationHash(): ByteArray = hash.copyOf()
    internal fun matchesHash(value: ByteArray?): Boolean = hash.contentEquals(value)
    internal fun hasInitialReader(): Boolean = target.catalogReadback?.projectedCurrent == false
    internal fun abort() {
        failed = true
    }
    override fun toString(): String = "ComplaintDesiredInstallAttemptV1(original-budget-and-graph,private-old-comparison)"
}

internal class ComplaintDesiredInstallationResultV1(val transition: ComplaintDesiredInstallationTransitionV1, val desiredGeneration: Long) {
    override fun toString(): String = "ComplaintDesiredInstallationResultV1(historical-only,no-authority)"
}

internal enum class ComplaintDesiredInstallationTransitionV1 { BOOTSTRAPPED, SUPERSEDED, ALREADY_SELECTED }
internal enum class ComplaintDesiredInstallationFailureV1 {
    INPUT_REFUSED,
    PROCESS_REFUSED,
    AUTHENTICATION_REFUSED,
    STATE_REFUSED,
    PROVIDER_REFUSED,
    TIME_BUDGET_EXHAUSTED,
    INTERRUPTED,
    DATABASE_REFUSED,
    CLEANUP_UNPROVEN,
}
internal class ComplaintDesiredInstallationExceptionV1(val code: ComplaintDesiredInstallationFailureV1) :
    RuntimeException("Desired configuration installation refused.", null, false, false)

internal fun requireDesiredInstallation(condition: Boolean, code: ComplaintDesiredInstallationFailureV1) {
    if (!condition) throw ComplaintDesiredInstallationExceptionV1(code)
}

internal fun boundedDesiredInstallationFailure(problem: Throwable): ComplaintDesiredInstallationExceptionV1 = when (problem) {
    is ComplaintDesiredInstallationExceptionV1 -> problem

    is InterruptedException -> {
        Thread.currentThread().interrupt()
        ComplaintDesiredInstallationExceptionV1(ComplaintDesiredInstallationFailureV1.INTERRUPTED)
    }

    is PersistencePhaseException -> ComplaintDesiredInstallationExceptionV1(
        if (problem.cleanupProven) ComplaintDesiredInstallationFailureV1.DATABASE_REFUSED else ComplaintDesiredInstallationFailureV1.CLEANUP_UNPROVEN,
    )

    is PersistenceBoundaryException -> ComplaintDesiredInstallationExceptionV1(
        if (problem.code ==
            PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED
        ) {
            ComplaintDesiredInstallationFailureV1.TIME_BUDGET_EXHAUSTED
        } else {
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED
        },
    )

    else -> ComplaintDesiredInstallationExceptionV1(ComplaintDesiredInstallationFailureV1.PROVIDER_REFUSED)
}
