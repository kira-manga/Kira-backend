package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.catalog.InitialCatalogPrincipalV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import me.manga.kira.backend.security.aws.AwsEpochSealStsAdapter
import me.manga.kira.backend.security.aws.AwsEpochSealStsBinding
import me.manga.kira.backend.security.aws.AwsEpochSealStsLimits
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.profiles.ProfileFile
import software.amazon.awssdk.regions.PartitionMetadata
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.ServiceMetadataConfiguration
import software.amazon.awssdk.services.sts.StsClient
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Actual cold sealer construction recipe owner, retained before D4/G1. Bootstrap credentials are only
 * transport material; usable sealer credentials still require the three fixed actual STS calls.
 * Independent deployment acceptance/installed-policy qualification is NOT manufactured here.
 *
 * Only the genuine original post-PREPARED commit/release continuation can construct a fresh lower
 * under concrete shared-lane custody. Neither a historical DTO nor this descriptor is authority.
 */
internal class VersionBoundEpochSealAcquisitionV1 private constructor(
    private val routing: VersionBoundComplaintJournalRouting,
    private val publicationLanes: JournalPublicationLanesV1,
    private val deployment: EpochSealDeploymentMappingV1,
    private val sdkLimits: AwsEpochSealStsLimits,
    private val construction: RetainedStsConstruction,
) : AutoCloseable {
    private val journal: ComplaintJournalConfigurationV1 = routing.journalConfiguration
    private val stopped = AtomicBoolean()
    private val retainedDescriptor = EpochSealAcquisitionDescriptorV1(
        journal.sha256,
        journal.declaration().journalLocation.region,
        deployment,
        sdkLimits,
    )

    init {
        requireUnchangedConfiguration()
    }

    /** Fixed local identity/scalar checks only: safe under a JDBC holder, no provider/hash/JSON/lookup or copies. */
    fun requireRetained(expectedRouting: VersionBoundComplaintJournalRouting, expectedLanes: JournalPublicationLanesV1) {
        require(routing === expectedRouting && publicationLanes === expectedLanes) { INVALID_EPOCH_SEAL_ACQUISITION }
        requireUnchangedConfiguration()
    }

    fun requireUnchangedConfiguration() {
        require(!stopped.get() && routing.journalConfiguration === journal) { INVALID_EPOCH_SEAL_ACQUISITION }
        publicationLanes.requireJournal(journal)
        construction.requireRetained(routing, deployment, sdkLimits)
    }

    fun descriptor(): EpochSealAcquisitionDescriptorV1 {
        requireUnchangedConfiguration()
        return retainedDescriptor
    }

    /** Necessary equality only. The primary G1 binding must call this on its actual authenticated registry handoff. */
    fun requireCatalogReferences(putAuthority: InitialCatalogPrincipalV1, signAuthority: InitialCatalogPrincipalV1) {
        requireUnchangedConfiguration()
        require(putAuthority == deployment.catalogPut.reference && signAuthority == deployment.catalogSign.reference) {
            INVALID_EPOCH_SEAL_ACQUISITION
        }
    }

    internal fun isClosed(): Boolean = stopped.get()

    internal fun construct(owner: CatalogEpochSealCustodyV1): AwsEpochSealStsAdapter {
        requireConnectionFree()
        owner.requireAcquisition(this)
        requireUnchangedConfiguration()
        return construction.open() // Cold only; the reservation retains it before any actual acquisition.
    }

    override fun close() {
        requireConnectionFree()
        stopped.set(true)
        withJournalPublicationCleanup({ publicationLanes.closeEpochSealAcquisition(this) }, construction::close)
        // The shared J lane owner is borrowed, never closed/replaced by this component.
    }

    override fun toString(): String = "VersionBoundEpochSealAcquisitionV1(cold-retained,redacted,no-current-or-policy-authority)"

    companion object {
        /**
         * Inputs must come from the independently accepted deployment channel. This validates their
         * consistency, not that trust assertion. Source session name is private transport binding,
         * excluded from D; stable IAM identity/origin/version remain in the descriptor.
         */
        fun fromIndependentInputs(
            routing: VersionBoundComplaintJournalRouting,
            publicationLanes: JournalPublicationLanesV1,
            deployment: EpochSealDeploymentMappingV1,
            bootstrapCredentials: AwsSessionCredentials,
            bootstrapSessionName: String?,
            sdkLimits: AwsEpochSealStsLimits = AwsEpochSealStsLimits(),
        ): VersionBoundEpochSealAcquisitionV1 = create(
            routing,
            publicationLanes,
            deployment,
            bootstrapCredentials,
            bootstrapSessionName,
            sdkLimits,
            null,
        )

        /** Raw HTTP SPI/clocks only: all current-owner, STS identity and KMS codec paths remain the real implementations. */
        fun withHttpFixture(
            routing: VersionBoundComplaintJournalRouting,
            publicationLanes: JournalPublicationLanesV1,
            deployment: EpochSealDeploymentMappingV1,
            bootstrapCredentials: AwsSessionCredentials,
            bootstrapSessionName: String?,
            stsHttpFactory: () -> SdkHttpClient,
            kmsHttpFactory: () -> SdkHttpClient,
            sdkLimits: AwsEpochSealStsLimits = AwsEpochSealStsLimits(),
            nanoTime: () -> Long = System::nanoTime,
            wallClock: () -> Instant = Instant::now,
        ): VersionBoundEpochSealAcquisitionV1 = create(
            routing,
            publicationLanes,
            deployment,
            bootstrapCredentials,
            bootstrapSessionName,
            sdkLimits,
            HttpFixture(stsHttpFactory, kmsHttpFactory, nanoTime, wallClock),
        )

        private fun create(
            routing: VersionBoundComplaintJournalRouting,
            publicationLanes: JournalPublicationLanesV1,
            deployment: EpochSealDeploymentMappingV1,
            bootstrapCredentials: AwsSessionCredentials,
            bootstrapSessionName: String?,
            sdkLimits: AwsEpochSealStsLimits,
            fixture: HttpFixture?,
        ): VersionBoundEpochSealAcquisitionV1 {
            requireConnectionFree()
            val journal = routing.journalConfiguration
            publicationLanes.requireJournal(journal)
            val declaration = journal.declaration()
            require(
                deployment.ordinary.reference == declaration.authorities.ordinary &&
                    deployment.sealTerminal.reference == declaration.authorities.sealTerminal &&
                    deployment.recovery.reference == declaration.authorities.recovery &&
                    deployment.sealTerminal.principal.accountId == declaration.journalLocation.accountId,
            ) { INVALID_EPOCH_SEAL_ACQUISITION }
            requireCommercialRegion(declaration.journalLocation.region)
            val limits = AwsEpochSealStsLimits(
                sdkLimits.requestTimeoutMillis,
                sdkLimits.connectTimeoutMillis,
                sdkLimits.readTimeoutMillis,
                sdkLimits.maxResponseBytes,
                sdkLimits.clockUncertaintyMillis,
            )
            val construction = RetainedStsConstruction(routing, deployment, bootstrapCredentials, bootstrapSessionName, limits, fixture)
            // Only immutable inputs/material exist here; no lower adapter or provider resource has been constructed.
            return VersionBoundEpochSealAcquisitionV1(routing, publicationLanes, deployment, limits, construction)
        }

        private fun requireCommercialRegion(id: String) {
            require(
                System.getProperty(SdkSystemSetting.AWS_PARTITIONS_FILE.property()) == null &&
                    System.getenv(SdkSystemSetting.AWS_PARTITIONS_FILE.environmentVariable()) == null,
            ) { INVALID_EPOCH_SEAL_ACQUISITION }
            val region = Region.regions().singleOrNull { it.id() == id }
            require(region != null && PartitionMetadata.of(region).id() == "aws") { INVALID_EPOCH_SEAL_ACQUISITION }
            val empty = ProfileFile.aggregator().build()
            val metadata = StsClient.serviceMetadata().reconfigure(
                ServiceMetadataConfiguration.builder().profileFile { empty }.profileName("complaint-epoch-seal-sts-v1").build(),
            )
            require(region in metadata.regions()) { INVALID_EPOCH_SEAL_ACQUISITION }
        }
    }

    /**
     * Fixed real STS construction inputs, not a caller-supplied adapter. Every genuine reservation
     * constructs/retains a NEW one-shot lower; one used adapter must not consume the whole process.
     */
    private class RetainedStsConstruction(
        private val routing: VersionBoundComplaintJournalRouting,
        private val deployment: EpochSealDeploymentMappingV1,
        bootstrapCredentials: AwsSessionCredentials,
        bootstrapSessionName: String?,
        private val limits: AwsEpochSealStsLimits,
        private val fixture: HttpFixture?,
    ) : AutoCloseable {
        private val credentials = AtomicReference<AwsSessionCredentials?>(bootstrapCredentials)
        private val source = deployment.bootstrap.principal
        private val target = deployment.sealTerminal.principal
        private val binding = AwsEpochSealStsBinding(
            source.accountId,
            source.callerArn(bootstrapSessionName),
            source.callerUserId(bootstrapSessionName),
            target.arn,
            target.stableId,
        )

        init {
            // The frozen lower rechecks these transport bounds on each actual construction. Never hash/serialize material into D.
            require(bootstrapCredentials.accessKeyId().length in 1..128 && bootstrapCredentials.secretAccessKey().length in 1..256) {
                INVALID_EPOCH_SEAL_ACQUISITION
            }
            require(bootstrapCredentials.sessionToken().length in 1..16_384) { INVALID_EPOCH_SEAL_ACQUISITION }
            require(
                bootstrapCredentials.accessKeyId().all { it in '!'..'~' } && bootstrapCredentials.secretAccessKey().all { it in '!'..'~' } &&
                    bootstrapCredentials.sessionToken().all { it in '!'..'~' },
            ) { INVALID_EPOCH_SEAL_ACQUISITION }
        }

        fun requireRetained(
            expectedRouting: VersionBoundComplaintJournalRouting,
            expectedDeployment: EpochSealDeploymentMappingV1,
            expectedLimits: AwsEpochSealStsLimits,
        ) {
            require(routing === expectedRouting && deployment === expectedDeployment && limits === expectedLimits) { INVALID_EPOCH_SEAL_ACQUISITION }
            require(
                credentials.get() != null && binding.sourceAccountId == source.accountId &&
                    binding.targetRoleArn == target.arn && binding.targetRoleId == target.stableId,
            ) { INVALID_EPOCH_SEAL_ACQUISITION }
        }

        fun open(): AwsEpochSealStsAdapter {
            val material = checkNotNull(credentials.get()) { INVALID_EPOCH_SEAL_ACQUISITION }
            val http = fixture
            return if (http == null) {
                AwsEpochSealStsAdapter.open(routing, material, binding, limits)
            } else {
                AwsEpochSealStsAdapter.withHttpFixture(
                    routing,
                    material,
                    binding,
                    limits,
                    http.sts,
                    http.nanoTime,
                    http.wallClock,
                    kmsHttpFactory = { http.kms() },
                )
            }
        }

        override fun close() {
            credentials.set(null) // SDK/String copies are not zeroized; this is not session revocation.
        }
        override fun toString(): String = "RetainedEpochSealStsConstruction(cold,redacted)"
    }

    private class HttpFixture(val sts: () -> SdkHttpClient, val kms: () -> SdkHttpClient, val nanoTime: () -> Long, val wallClock: () -> Instant)
}

/** Immutable stable configuration projection only. No session, access key, expiration, custody state or verification boolean. */
internal class EpochSealAcquisitionDescriptorV1 internal constructor(
    val journalConfigurationSha256: String,
    val region: String,
    val deployment: EpochSealDeploymentMappingV1,
    val sdkLimits: AwsEpochSealStsLimits,
) {
    override fun toString(): String = "EpochSealAcquisitionDescriptorV1(declared-inventory,redacted,no-authority)"
}
