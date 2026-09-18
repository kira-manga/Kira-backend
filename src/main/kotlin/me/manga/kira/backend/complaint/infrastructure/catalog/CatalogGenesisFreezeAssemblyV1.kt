package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorPersistence
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLifecycleOwner
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePublicTrustPreparation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePublicTrustRelease
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConfiguration
import me.manga.kira.backend.common.infrastructure.persistence.bindCatalogGenesisAuthoringPools
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.AwsCatalogSigningAdapterV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackAdapter
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.catalogUrlConnectionClient
import me.manga.kira.backend.security.AcquiredVersionedSecret
import me.manga.kira.backend.security.aws.AwsSecretVersionLimits
import me.manga.kira.backend.security.aws.AwsSecretsManagerVersionResolver
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient

/** One retained, pin-free author graph and its real acquisition/provider owners. No TARGET process is constructed here. */
internal class CatalogGenesisFreezeAssemblyV1(
    private val budget: PersistenceTimeBudget,
    private val clock: PersistenceNanoClock,
    private val secretHttpFactory: (() -> SdkHttpClient)?,
    private val signingHttpFactory: (() -> SdkHttpClient)?,
    private val namespaceHttpFactory: (() -> SdkHttpClient)?,
) : AutoCloseable {
    private val files = CatalogGenesisFreezeInputFilesV1(budget)
    private val signingConstruction = AwsCatalogSigningAdapterV1.Construction(budget)
    private val namespaceConstruction = S3CatalogReadbackAdapter.Construction()
    private var resolver: AwsSecretsManagerVersionResolver? = null
    private var resolverOpening = false
    private var resolverCloseIssued = false
    private var stopping = false
    private var owner: PersistenceJdbcLifecycleOwner? = null
    private var acquiredInputs: CatalogGenesisFreezeInputsV1? = null
    val inputs: CatalogGenesisFreezeInputsV1 get() = checkNotNull(acquiredInputs)
    val coordinator: CatalogCoordinatorPersistence get() = checkNotNull(owner).versionBoundPools!!.catalogCoordinator

    fun acquire(request: CatalogGenesisFreezeRequestV1, credentials: AwsSessionCredentials) {
        checkpoint()
        requireCatalogFreeze(acquiredInputs == null && owner == null, CatalogGenesisFreezeFailureV1.PROCESS_REFUSED)
        acquiredInputs = files.readInputs(request)
        checkpoint()
        val binding = request.database.authenticationPassword
        val millis = budget.remainingMillis(5_000)
        val limits = AwsSecretVersionLimits(millis, minOf(millis, 2_000).toInt(), minOf(millis, 2_000).toInt())
        resolverOpening = true
        val opened = if (secretHttpFactory == null) {
            AwsSecretsManagerVersionResolver.open(binding.version.resourceArn.split(':')[3], credentials, limits)
        } else {
            AwsSecretsManagerVersionResolver.withHttpFixture(binding.version.resourceArn.split(':')[3], credentials, limits, secretHttpFactory)
        }
        resolver = opened // Retain before the first post-construction deadline check or provider request.
        resolverOpening = false
        val acquired = withCatalogFreezeCleanup(
            {
                checkpoint()
                AcquiredVersionedSecret.acquire(binding, opened).also { checkpoint() }
            },
            ::closeResolver,
        )
        checkpoint()
        val database = request.database
        val configuration = VersionBoundPersistenceConfiguration.forCatalogGenesisAuthoring(
            acquired,
            database.host,
            database.port,
            database.database,
            inputs.publicTrust,
            database.protectedTrustParent,
        )
        val retained = configuration.bindCatalogGenesisAuthoringOwner()
        owner = retained // Before cold pool/manager/phase binding, including a partially failed construction.
        checkpoint()
        retained.bindCatalogGenesisAuthoringPools(clock)
        checkpoint()
    }

    fun prepare() {
        checkpoint()
        requireCatalogFreeze(
            checkNotNull(owner).preparePublicTrust() === PersistencePublicTrustPreparation.READY,
            CatalogGenesisFreezeFailureV1.PROCESS_REFUSED,
        )
        checkpoint()
        // The unchanged preparer is locally capped at 10s; reserve that room in the ORIGINAL stage, then charge its full return tail.
        requireCatalogFreeze(budget.remainingMillis(60_000) > 10_000, CatalogGenesisFreezeFailureV1.TIME_BUDGET_EXHAUSTED)
        requireCatalogFreeze(
            checkNotNull(owner).prepareCatalogGenesisAuthoring() === PersistenceLifecycleObservation.READY,
            CatalogGenesisFreezeFailureV1.PROCESS_REFUSED,
        )
        checkpoint()
    }

    fun observeEmptyNamespaces(primaryCredentials: AwsSessionCredentials, replicaCredentials: AwsSessionCredentials) {
        checkpoint()
        val millis = budget.remainingMillis(10_000)
        val limits = S3CatalogReadbackLimits(millis, minOf(millis, 2_000).toInt(), minOf(millis, 2_000).toInt(), maximumListBytes = 65_536)
        val adapter = S3CatalogReadbackAdapter.openOwned(
            namespaceConstruction,
            inputs.currentBytes(),
            inputs.chain.trustBundlePolicy,
            primaryCredentials,
            replicaCredentials,
            limits,
            {
                checkpoint()
                namespaceHttpFactory?.invoke() ?: catalogUrlConnectionClient(limits)
            },
            {
                checkpoint()
                System.nanoTime()
            }, // Every request/body elapsed check also checks the original stage, not just a renewed request timeout.
        )
        withCatalogFreezeCleanup(
            {
                checkpoint()
                CatalogGenesisNamespaceObservationV1.observe(adapter, inputs.currentBytes(), inputs.chain.trustBundlePolicy, budget)
                checkpoint()
            },
            namespaceConstruction::close,
        )
        checkpoint()
    }

    fun sign(credentials: AwsSessionCredentials): ByteArray {
        checkpoint()
        val key = inputs.request.signingKey
        val adapter = if (signingHttpFactory == null) {
            AwsCatalogSigningAdapterV1.openOwned(signingConstruction, key, credentials)
        } else {
            AwsCatalogSigningAdapterV1.withHttpFixture(signingConstruction, key, credentials, signingHttpFactory)
        }
        // This genuine adapter constructs the fixed catalog frame itself and closes actual SDK/HTTP owners before returning bytes.
        return adapter.sign(inputs.intentBytes()).also { checkpoint() }
    }

    fun independentPin(): ByteArray? = inputs.request.independentPin?.let(files::readPin)

    private fun closeResolver() {
        if (!resolverCloseIssued) {
            resolverCloseIssued = true
            resolver?.close()
            resolver = null
        }
        requireCatalogFreeze(resolver == null && !resolverOpening, CatalogGenesisFreezeFailureV1.CLEANUP_UNPROVEN)
    }

    override fun close() {
        stopping = true
        val outcomes = listOf(
            runCatching { owner?.requestShutdown() },
            runCatching(files::close),
            runCatching(::closeResolver),
            runCatching(signingConstruction::close),
            runCatching(namespaceConstruction::close),
            runCatching { owner?.versionBoundPools?.close() },
        )
        requireCatalogFreezeCleanup(outcomes)
    }

    fun requireCleanup() {
        requireConnectionFree()
        requireCatalogFreeze(stopping, CatalogGenesisFreezeFailureV1.CLEANUP_UNPROVEN)
        owner?.let { retained ->
            requireCatalogFreeze(
                retained.observeShutdown(budget) === PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED,
                CatalogGenesisFreezeFailureV1.CLEANUP_UNPROVEN,
            )
            requireCatalogFreeze(
                retained.releasePublicTrustAfterShutdown() === PersistencePublicTrustRelease.RELEASED,
                CatalogGenesisFreezeFailureV1.CLEANUP_UNPROVEN,
            )
        }
        budget.remainingMillis(1)
    }

    private fun checkpoint() {
        requireConnectionFree()
        requireCatalogFreeze(!stopping, CatalogGenesisFreezeFailureV1.PROCESS_REFUSED)
        requireCatalogFreeze(!Thread.currentThread().isInterrupted, CatalogGenesisFreezeFailureV1.INTERRUPTED)
        budget.remainingMillis(1)
    }

    override fun toString(): String = "CatalogGenesisFreezeAssemblyV1(separate-author-only,redacted)"
}
