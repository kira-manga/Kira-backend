package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLifecycleOwner
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePoolLaunchProfile
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePublicTrustPreparation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePublicTrustRelease
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.SystemPersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConfiguration
import me.manga.kira.backend.common.infrastructure.persistence.bindDesiredInstallationOperatorPools
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogSignerRotationConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundEpochSealAcquisitionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.preferCatalogFreezeCleanup
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.VersionBoundLiveJournalCoverageV1
import me.manga.kira.backend.security.AcquiredVersionedSecret
import me.manga.kira.backend.security.JwtKeyProvider
import me.manga.kira.backend.security.VersionBoundComplaintConsumerConfiguration
import me.manga.kira.backend.security.VersionBoundComplaintConsumerInputs
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import me.manga.kira.backend.security.VersionBoundInstallationJwtConfiguration
import me.manga.kira.backend.security.VersionedSecretBinding
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import java.security.MessageDigest

/** Retained before even the first cold root binds. All partial resources stay here; no graph supplied by an HTTP/startup caller. */
internal class ComplaintDesiredProcessAssemblyV1 private constructor(
    private val nanoClock: PersistenceNanoClock,
    private val runtimeLaunchProfile: PersistencePoolLaunchProfile,
) : AutoCloseable {
    constructor() : this(SystemPersistenceNanoClock, PersistencePoolLaunchProfile.UNKNOWN)
    private var entered = false
    private var stopping = false
    private var targetOwner: PersistenceJdbcLifecycleOwner? = null
    private var operatorOwner: PersistenceJdbcLifecycleOwner? = null
    private var lanes: JournalPublicationLanesV1? = null
    private var sealer: VersionBoundEpochSealAcquisitionV1? = null
    private var assembled: VersionBoundComplaintProcessConfiguration? = null
    private var closeFailure: Throwable? = null

    val target: VersionBoundComplaintProcessConfiguration
        get() {
            requireDesiredInstallation(!stopping, ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
            return checkNotNull(assembled)
        }
    val coordinator: CatalogCoordinatorPersistence
        get() = checkNotNull(operatorOwner?.versionBoundPools).catalogCoordinator

    /** Real acquired owners only. Test input acquisition may use the existing resolver SPI; no fake descriptors or D. */
    fun assemble(inputs: ComplaintDesiredDeploymentInputsV1, acquired: List<AcquiredVersionedSecret>, sealerCredentials: AwsSessionCredentials?) =
        assemble(inputs, acquired, sealerCredentials, finalizer = false)

    /** Fixed TARGET-only composition; reuse the exact target recipe, not the operator getter or an alternate D profile. */
    internal fun assembleTargetFinalizer(
        inputs: ComplaintDesiredDeploymentInputsV1,
        acquired: List<AcquiredVersionedSecret>,
        sealerCredentials: AwsSessionCredentials?,
    ) {
        inputs.requireTargetFinalizerProfile()
        assemble(inputs, acquired, sealerCredentials, finalizer = true)
    }

    /** Identical TARGET D7 inventory, with only the concrete existing-PREPARED no-Sign recovery purpose runnable. */
    internal fun assembleTargetSignerRotationRecovery(
        inputs: ComplaintDesiredDeploymentInputsV1,
        acquired: List<AcquiredVersionedSecret>,
        sealerCredentials: AwsSessionCredentials?,
    ) {
        inputs.requireTargetSignerRotationRecoveryProfile()
        assemble(inputs, acquired, sealerCredentials, finalizer = false, signerRotationRecovery = true)
    }

    /** Initial author is a distinct immutable purpose, not the no-Sign PREPARED2 recovery route. */
    internal fun assembleTargetSignerRotationAuthor(
        inputs: ComplaintDesiredDeploymentInputsV1,
        acquired: List<AcquiredVersionedSecret>,
        sealerCredentials: AwsSessionCredentials?,
    ) {
        inputs.requireTargetSignerRotationAuthorProfile()
        assemble(inputs, acquired, sealerCredentials, finalizer = false, signerRotationAuthoring = true)
    }

    /** Same acquired TARGET D7 inventory; only fixed signed-overlap2 delivery/finalization is runnable. */
    internal fun assembleTargetSignerRotationDelivery(
        inputs: ComplaintDesiredDeploymentInputsV1,
        acquired: List<AcquiredVersionedSecret>,
        sealerCredentials: AwsSessionCredentials?,
    ) {
        inputs.requireTargetSignerRotationDeliveryProfile()
        assemble(inputs, acquired, sealerCredentials, finalizer = false, signerRotationDelivery = true)
    }

    private fun assemble(
        inputs: ComplaintDesiredDeploymentInputsV1,
        acquired: List<AcquiredVersionedSecret>,
        sealerCredentials: AwsSessionCredentials?,
        finalizer: Boolean,
        signerRotationRecovery: Boolean = false,
        signerRotationAuthoring: Boolean = false,
        signerRotationDelivery: Boolean = false,
    ) {
        requireConnectionFree()
        requireDesiredInstallation(!entered && !stopping, ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
        entered = true
        val targetOnly = finalizer || signerRotationRecovery || signerRotationAuthoring || signerRotationDelivery
        val secrets = matchAcquired(if (targetOnly) inputs.targetBindings() else inputs.allBindings(), acquired)
        fun secret(binding: VersionedSecretBinding): AcquiredVersionedSecret = checkNotNull(secrets[binding])
        // Distinct immutable references alone do not prove separate actual DB password material.
        if (!targetOnly) requireDistinctDatabasePasswords(secret(inputs.runtimePassword), secret(inputs.operatorPassword))
        val consumers = createConsumers(inputs, secrets)
        val routing = consumers.journalRouting
        val db = inputs.database
        val runtimeConfiguration = VersionBoundPersistenceConfiguration.fromAcquired(
            secret(inputs.runtimePassword),
            db.host,
            db.port,
            db.name,
            db.runtimeUsername,
            db.ordinaryCapacity,
            inputs.publicTrustPem(),
            inputs.protectedTrustParent,
        )
        val runtime = bindTargetOwner(inputs, runtimeConfiguration, finalizer, signerRotationRecovery, signerRotationAuthoring, signerRotationDelivery)
        targetOwner = runtime // Before shell binding, including a failed/partly constructed pool composition.
        val pools = when {
            signerRotationDelivery -> runtime.bindCatalogSignerRotationDeliveryPools(nanoClock)
            signerRotationAuthoring -> runtime.bindCatalogSignerRotationAuthoringPools(nanoClock)
            signerRotationRecovery -> runtime.bindCatalogSignerRotationRecoveryPools(nanoClock)
            finalizer -> runtime.bindCatalogGenesisFinalizationPools(nanoClock)
            else -> runtime.bindVersionBoundPools(runtimeLaunchProfile, nanoClock)
        }
        // Still cold: no target participant, driver, trust-file I/O or pool preparation. Default launch remains UNKNOWN.
        val mapping = inputs.sealerMapping
        requireDesiredInstallation((mapping == null) == (sealerCredentials == null), ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
        val seal = if (mapping == null) {
            null
        } else {
            val publication = JournalPublicationLanesV1(inputs.journal)
            lanes = publication
            VersionBoundEpochSealAcquisitionV1.fromIndependentInputs(
                routing,
                publication,
                mapping,
                checkNotNull(sealerCredentials),
                inputs.sealerSessionName,
                checkNotNull(inputs.sealerLimits),
            ).also { sealer = it }
        }
        val coverage = inputs.livePolicy?.let { policy ->
            VersionBoundLiveJournalCoverageV1.fromIndependentInputs(routing, checkNotNull(inputs.catalog), checkNotNull(lanes), policy)
        }
        val writer = inputs.catalogSignerRotation?.let {
            VersionBoundCatalogSignerRotationConfigurationV1.fromRetained(pools, checkNotNull(inputs.catalog), it)
        }
        assembled = when {
            writer != null -> VersionBoundComplaintProcessConfiguration.fromRetainedWithSignerRotation(
                consumers, pools, inputs.implementationSchema, inputs.desiredGeneration, inputs.databaseIdentity, inputs.restoreIdentity,
                checkNotNull(inputs.catalog), writer, lanes, seal, coverage,
            )

            coverage != null -> VersionBoundComplaintProcessConfiguration.fromRetainedWithLiveCoverage(
                consumers, pools, inputs.implementationSchema, inputs.desiredGeneration, inputs.databaseIdentity, inputs.restoreIdentity,
                checkNotNull(inputs.catalog), checkNotNull(lanes), checkNotNull(seal), coverage,
            )

            seal != null -> VersionBoundComplaintProcessConfiguration.fromRetainedWithEpochSealAcquisition(
                consumers, pools, inputs.implementationSchema, inputs.desiredGeneration, inputs.databaseIdentity, inputs.restoreIdentity,
                checkNotNull(inputs.catalog), checkNotNull(lanes), seal,
            )

            inputs.epochRotation -> VersionBoundComplaintProcessConfiguration.fromRetainedWithEpochRotation(
                consumers,
                pools,
                inputs.implementationSchema,
                inputs.desiredGeneration,
                inputs.databaseIdentity,
                inputs.restoreIdentity,
                checkNotNull(inputs.catalog),
            )

            else -> VersionBoundComplaintProcessConfiguration.fromRetained(
                consumers,
                pools,
                inputs.implementationSchema,
                inputs.desiredGeneration,
                inputs.databaseIdentity,
                inputs.restoreIdentity,
                inputs.catalog,
            )
        }
        if (targetOnly) return
        val operatorConfiguration = VersionBoundPersistenceConfiguration.forDesiredInstallationOperator(
            secret(inputs.operatorPassword),
            db.host,
            db.port,
            db.name,
            inputs.publicTrustPem(),
            inputs.protectedTrustParent,
        )
        val operator = operatorConfiguration.bindDesiredInstallationOperatorOwner()
        operatorOwner = operator
        operator.bindDesiredInstallationOperatorPools()
    }

    private fun bindTargetOwner(
        inputs: ComplaintDesiredDeploymentInputsV1,
        configuration: VersionBoundPersistenceConfiguration,
        finalizer: Boolean,
        signerRotationRecovery: Boolean,
        signerRotationAuthoring: Boolean,
        signerRotationDelivery: Boolean,
    ): PersistenceJdbcLifecycleOwner = when {
        signerRotationDelivery -> configuration.bindCatalogSignerRotationDeliveryOwner(inputs.epochRotation)
        signerRotationAuthoring -> configuration.bindCatalogSignerRotationAuthoringOwner(inputs.epochRotation)
        signerRotationRecovery -> configuration.bindCatalogSignerRotationRecoveryOwner(inputs.epochRotation)
        finalizer && inputs.epochRotation -> configuration.bindCatalogGenesisFinalizationOwnerWithEpochRotation()
        finalizer -> configuration.bindCatalogGenesisFinalizationOwner()
        inputs.epochRotation -> configuration.bindLifecycleOwnerWithEpochRotation()
        else -> configuration.bindLifecycleOwner()
    }

    private fun requireDistinctDatabasePasswords(runtimePassword: AcquiredVersionedSecret, operatorPassword: AcquiredVersionedSecret) {
        runtimePassword.useMaterial { runtime ->
            operatorPassword.useMaterial { operator ->
                requireDesiredInstallation(!MessageDigest.isEqual(runtime, operator), ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
            }
        }
    }

    private fun createConsumers(
        inputs: ComplaintDesiredDeploymentInputsV1,
        secrets: Map<VersionedSecretBinding, AcquiredVersionedSecret>,
    ): VersionBoundComplaintConsumerConfiguration {
        fun secret(binding: VersionedSecretBinding): AcquiredVersionedSecret = checkNotNull(secrets[binding])
        val user = JwtKeyProvider.fromAcquired(secret(inputs.userKey), inputs.jwtSettings)
        val jwt = VersionBoundInstallationJwtConfiguration.fromAcquired(inputs.installationActiveKeyId, inputs.installationKeys().map(::secret), user)
        val routing = VersionBoundComplaintJournalRouting.fromAcquired(inputs.journal, inputs.routingKeys().map(::secret))
        return VersionBoundComplaintConsumerConfiguration.fromAcquired(
            jwt,
            inputs.capacity,
            inputs.journal,
            VersionBoundComplaintConsumerInputs(
                secret(inputs.admissionCurrent),
                inputs.admissionPrevious?.let(::secret),
                inputs.cursorActiveKeyId,
                inputs.cursorKeys().map(::secret),
                routing,
            ),
            inputs.consumerSettings,
        )
    }

    private fun matchAcquired(
        expected: List<VersionedSecretBinding>,
        acquired: List<AcquiredVersionedSecret>,
    ): Map<VersionedSecretBinding, AcquiredVersionedSecret> {
        requireDesiredInstallation(acquired.size == expected.size, ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
        return expected.associateWith { requested ->
            acquired.single { actual -> sameBinding(actual.descriptor, requested) }
        }
    }

    fun prepareOperator(budget: PersistenceTimeBudget) {
        requireConnectionFree()
        target.requireUnchangedConfiguration()
        val operator = checkNotNull(operatorOwner)
        requireDesiredInstallation(
            operator.preparePublicTrust() === PersistencePublicTrustPreparation.READY,
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
        // The unchanged pool preparer has a <=10s local cap. Require room in the ORIGINAL command,
        // then charge its whole blocking construction/return tail again; it never renews command time.
        requireDesiredInstallation(budget.remainingMillis(60_000) > 10_000, ComplaintDesiredInstallationFailureV1.TIME_BUDGET_EXHAUSTED)
        requireDesiredInstallation(
            operator.prepareDesiredInstallationOperator() === PersistenceLifecycleObservation.READY,
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
        budget.remainingMillis(1)
        requireDesiredInstallation(!Thread.currentThread().isInterrupted, ComplaintDesiredInstallationFailureV1.INTERRUPTED)
        target.requireUnchangedConfiguration()
    }

    internal fun prepareTargetFinalizer(budget: PersistenceTimeBudget) {
        requireConnectionFree()
        val retained = target
        val canonical = retained.canonicalBytes()
        val hash = retained.configurationHashBytes()
        val owner = checkNotNull(targetOwner)
        requireDesiredInstallation(owner.catalogGenesisFinalization && operatorOwner == null, ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
        requireDesiredInstallation(
            owner.preparePublicTrust() === PersistencePublicTrustPreparation.READY,
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
        requireDesiredInstallation(budget.remainingMillis(60_000) > 10_000, ComplaintDesiredInstallationFailureV1.TIME_BUDGET_EXHAUSTED)
        requireDesiredInstallation(
            owner.prepareCatalogGenesisFinalization() === PersistenceLifecycleObservation.READY,
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
        budget.remainingMillis(1)
        requireDesiredInstallation(!Thread.currentThread().isInterrupted, ComplaintDesiredInstallationFailureV1.INTERRUPTED)
        retained.requireUnchangedConfiguration()
        requireDesiredInstallation(
            canonical.contentEquals(retained.canonicalBytes()) && hash.contentEquals(retained.configurationHashBytes()),
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
    }

    /** Explicit retained-root preparation, not startup/HTTP activation or general UNKNOWN business access. */
    internal fun prepareTargetSignerRotationRecovery(budget: PersistenceTimeBudget) {
        requireConnectionFree()
        val retained = target
        val canonical = retained.canonicalBytes()
        val hash = retained.configurationHashBytes()
        val owner = checkNotNull(targetOwner)
        requireDesiredInstallation(
            owner.catalogSignerRotationRecovery && operatorOwner == null && retained.catalogSignerRotation != null &&
                retained.desiredSettings().desiredGeneration == 1L && retained.catalogReadback?.projectedCurrent == false,
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
        requireDesiredInstallation(!Thread.currentThread().isInterrupted, ComplaintDesiredInstallationFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
        requireDesiredInstallation(
            owner.preparePublicTrust() === PersistencePublicTrustPreparation.READY,
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
        requireDesiredInstallation(budget.remainingMillis(60_000) > 10_000, ComplaintDesiredInstallationFailureV1.TIME_BUDGET_EXHAUSTED)
        requireDesiredInstallation(
            owner.prepareCatalogSignerRotationRecovery() === PersistenceLifecycleObservation.READY,
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
        budget.remainingMillis(1)
        requireDesiredInstallation(!Thread.currentThread().isInterrupted, ComplaintDesiredInstallationFailureV1.INTERRUPTED)
        retained.requireUnchangedConfiguration()
        requireDesiredInstallation(
            canonical.contentEquals(retained.canonicalBytes()) && hash.contentEquals(retained.configurationHashBytes()),
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
    }

    /** Explicit retained-root preparation, not startup/HTTP activation or general UNKNOWN business access. */
    internal fun prepareTargetSignerRotationAuthor(budget: PersistenceTimeBudget) {
        requireConnectionFree()
        val retained = target
        val canonical = retained.canonicalBytes()
        val hash = retained.configurationHashBytes()
        val owner = checkNotNull(targetOwner)
        requireDesiredInstallation(
            owner.catalogSignerRotationAuthoring && operatorOwner == null && retained.catalogSignerRotation != null &&
                retained.desiredSettings().desiredGeneration == 1L && retained.catalogReadback?.projectedCurrent == false,
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
        requireDesiredInstallation(!Thread.currentThread().isInterrupted, ComplaintDesiredInstallationFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
        requireDesiredInstallation(
            owner.preparePublicTrust() === PersistencePublicTrustPreparation.READY,
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
        requireDesiredInstallation(budget.remainingMillis(60_000) > 10_000, ComplaintDesiredInstallationFailureV1.TIME_BUDGET_EXHAUSTED)
        requireDesiredInstallation(
            owner.prepareCatalogSignerRotationAuthoring() === PersistenceLifecycleObservation.READY,
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
        budget.remainingMillis(1)
        requireDesiredInstallation(!Thread.currentThread().isInterrupted, ComplaintDesiredInstallationFailureV1.INTERRUPTED)
        retained.requireUnchangedConfiguration()
        requireDesiredInstallation(
            canonical.contentEquals(retained.canonicalBytes()) && hash.contentEquals(retained.configurationHashBytes()),
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
    }

    /** Explicit retained-root preparation, not startup/HTTP activation or general UNKNOWN business access. */
    internal fun prepareTargetSignerRotationDelivery(budget: PersistenceTimeBudget) {
        requireConnectionFree()
        val retained = target
        val canonical = retained.canonicalBytes()
        val hash = retained.configurationHashBytes()
        val owner = checkNotNull(targetOwner)
        requireDesiredInstallation(
            owner.catalogSignerRotationDelivery && operatorOwner == null && retained.catalogSignerRotation != null &&
                retained.desiredSettings().desiredGeneration == 1L && retained.catalogReadback?.projectedCurrent == false,
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
        requireDesiredInstallation(!Thread.currentThread().isInterrupted, ComplaintDesiredInstallationFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
        requireDesiredInstallation(
            owner.preparePublicTrust() === PersistencePublicTrustPreparation.READY,
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
        requireDesiredInstallation(budget.remainingMillis(60_000) > 10_000, ComplaintDesiredInstallationFailureV1.TIME_BUDGET_EXHAUSTED)
        requireDesiredInstallation(
            owner.prepareCatalogSignerRotationDelivery() === PersistenceLifecycleObservation.READY,
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
        budget.remainingMillis(1)
        requireDesiredInstallation(!Thread.currentThread().isInterrupted, ComplaintDesiredInstallationFailureV1.INTERRUPTED)
        retained.requireUnchangedConfiguration()
        requireDesiredInstallation(
            canonical.contentEquals(retained.canonicalBytes()) && hash.contentEquals(retained.configurationHashBytes()),
            ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED,
        )
    }

    /** All original roots are permanently stopped before ANY shared-Timer proof is awaited. */
    override fun close() {
        stopping = true
        fun retain(problem: Throwable) {
            if (problem is ComplaintDesiredInstallationExceptionV1 && problem.code === ComplaintDesiredInstallationFailureV1.INTERRUPTED) {
                Thread.currentThread().interrupt()
            }
            closeFailure = preferCatalogFreezeCleanup(closeFailure, problem) // Restore interruption before attempting the next actual close.
        }
        runCatching { targetOwner?.requestShutdown() }.onFailure(::retain)
        runCatching { operatorOwner?.requestShutdown() }.onFailure(::retain)
        runCatching { sealer?.close() }.onFailure(::retain)
        runCatching { lanes?.close() }.onFailure(::retain)
        runCatching { operatorOwner?.versionBoundPools?.close() }.onFailure(::retain)
        runCatching { targetOwner?.versionBoundPools?.close() }.onFailure(::retain)
        closeFailure = closeFailure?.let {
            preferCatalogFreezeCleanup(it, ComplaintDesiredInstallationExceptionV1(ComplaintDesiredInstallationFailureV1.CLEANUP_UNPROVEN))
        }
        closeFailure?.let { throw it } // Fatal > cancellation > interruption > bounded ordinary failure; a later close cannot erase uncertainty.
    }

    fun requireCleanup(budget: PersistenceTimeBudget) {
        requireDesiredInstallation(stopping, ComplaintDesiredInstallationFailureV1.CLEANUP_UNPROVEN)
        requireConnectionFree()
        listOfNotNull(operatorOwner, targetOwner).forEach { owner ->
            requireDesiredInstallation(
                owner.observeShutdown(budget) === PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED,
                ComplaintDesiredInstallationFailureV1.CLEANUP_UNPROVEN,
            )
            requireDesiredInstallation(
                owner.releasePublicTrustAfterShutdown() === PersistencePublicTrustRelease.RELEASED,
                ComplaintDesiredInstallationFailureV1.CLEANUP_UNPROVEN,
            )
        }
        budget.remainingMillis(1)
    }

    override fun toString(): String = "ComplaintDesiredProcessAssemblyV1(cold-target,separate-fixed-operator,redacted)"

    private fun sameBinding(left: VersionedSecretBinding, right: VersionedSecretBinding): Boolean =
        left.family == right.family && left.purpose == right.purpose && left.logicalKeyId == right.logicalKeyId && left.version == right.version

    companion object {
        /** Clock is selected BEFORE the actual cold resource/phase binding, never swapped on a live process or campaign. */
        internal fun withClockFixture(nanoClock: PersistenceNanoClock): ComplaintDesiredProcessAssemblyV1 =
            ComplaintDesiredProcessAssemblyV1(nanoClock, PersistencePoolLaunchProfile.UNKNOWN)

        /** Explicit controlled integration only, selected before ANY pool binds; never a production launch qualification or retained-profile mutation. */
        internal fun withControlledIntegrationFixture(nanoClock: PersistenceNanoClock): ComplaintDesiredProcessAssemblyV1 =
            ComplaintDesiredProcessAssemblyV1(nanoClock, PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY)
    }
}
