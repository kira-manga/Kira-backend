package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLifecycleOwner
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePublicTrustPreparation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePublicTrustRelease
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConfiguration
import me.manga.kira.backend.common.infrastructure.persistence.bindDesiredInstallationOperatorPools
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundEpochSealAcquisitionV1
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
internal class ComplaintDesiredProcessAssemblyV1 : AutoCloseable {
    private var entered = false
    private var stopping = false
    private var targetOwner: PersistenceJdbcLifecycleOwner? = null
    private var operatorOwner: PersistenceJdbcLifecycleOwner? = null
    private var lanes: JournalPublicationLanesV1? = null
    private var sealer: VersionBoundEpochSealAcquisitionV1? = null
    private var assembled: VersionBoundComplaintProcessConfiguration? = null

    val target: VersionBoundComplaintProcessConfiguration
        get() {
            requireDesiredInstallation(!stopping, ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
            return checkNotNull(assembled)
        }
    val coordinator: CatalogCoordinatorPersistence
        get() = checkNotNull(operatorOwner?.versionBoundPools).catalogCoordinator

    /** Real acquired owners only. Test input acquisition may use the existing resolver SPI; no fake descriptors or D. */
    fun assemble(inputs: ComplaintDesiredDeploymentInputsV1, acquired: List<AcquiredVersionedSecret>, sealerCredentials: AwsSessionCredentials?) {
        requireConnectionFree()
        requireDesiredInstallation(!entered && !stopping, ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
        entered = true
        val secrets = matchAcquired(inputs, acquired)
        fun secret(binding: VersionedSecretBinding): AcquiredVersionedSecret = checkNotNull(secrets[binding])
        // Distinct immutable references alone do not prove separate actual DB password material.
        secret(inputs.runtimePassword).useMaterial { runtime ->
            secret(inputs.operatorPassword).useMaterial { operator ->
                requireDesiredInstallation(!MessageDigest.isEqual(runtime, operator), ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED)
            }
        }
        val user = JwtKeyProvider.fromAcquired(secret(inputs.userKey), inputs.jwtSettings)
        val jwt = VersionBoundInstallationJwtConfiguration.fromAcquired(inputs.installationActiveKeyId, inputs.installationKeys().map(::secret), user)
        val routing = VersionBoundComplaintJournalRouting.fromAcquired(inputs.journal, inputs.routingKeys().map(::secret))
        val consumers = VersionBoundComplaintConsumerConfiguration.fromAcquired(
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
        val runtime = if (inputs.epochRotation) runtimeConfiguration.bindLifecycleOwnerWithEpochRotation() else runtimeConfiguration.bindLifecycleOwner()
        targetOwner = runtime // Before shell binding, including a failed/partly constructed pool composition.
        val pools = runtime.bindVersionBoundPools() // UNKNOWN; no target participant, driver, trust-file I/O or pool preparation.
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
        assembled = when {
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

    private fun matchAcquired(
        inputs: ComplaintDesiredDeploymentInputsV1,
        acquired: List<AcquiredVersionedSecret>,
    ): Map<VersionedSecretBinding, AcquiredVersionedSecret> {
        val expected = inputs.allBindings()
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

    /** All original roots are permanently stopped before ANY shared-Timer proof is awaited. */
    override fun close() {
        stopping = true
        val outcomes = listOf(
            runCatching { targetOwner?.requestShutdown() },
            runCatching { operatorOwner?.requestShutdown() },
            runCatching { sealer?.close() },
            runCatching { lanes?.close() },
            runCatching { operatorOwner?.versionBoundPools?.close() },
            runCatching { targetOwner?.versionBoundPools?.close() },
        )
        requireDesiredInstallation(outcomes.all { it.isSuccess }, ComplaintDesiredInstallationFailureV1.CLEANUP_UNPROVEN)
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
}
