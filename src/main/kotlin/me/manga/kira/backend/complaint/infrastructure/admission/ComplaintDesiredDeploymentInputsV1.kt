package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConfiguration
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.InitialLiveJournalDeclarationV1
import me.manga.kira.backend.complaint.domain.JournalRoutingKeyV1
import me.manga.kira.backend.complaint.domain.JournalRoutingV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainLimits
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundlePolicy
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationDeploymentV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationKeyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealBootstrapOriginV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealCatalogPrincipalV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealDeploymentMappingV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealEventPrincipalV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealProviderPrincipalV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalCopyPolicyV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalHmacRetentionV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalKmsRetentionV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalLockPolicyV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalPolicyDeploymentV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalTimeBoundV1
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.security.ImmutableSecretVersion
import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.security.SecretMaterialFamily
import me.manga.kira.backend.security.SecretMaterialPurpose
import me.manga.kira.backend.security.VersionBoundComplaintConsumerSettings
import me.manga.kira.backend.security.VersionedSecretBinding
import me.manga.kira.backend.security.aws.AwsEpochSealStsLimits
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.UUID

/** Validated independent intent. It contains neither a supplied D nor an observed database binding. */
internal class ComplaintDesiredDeploymentInputsV1 private constructor(document: ComplaintDesiredDeploymentDocumentV1) {
    val profile = DesiredProcessProfileV1.entries.singleOrNull { it.name == document.profile }
        ?: throw ComplaintDesiredInstallationExceptionV1(ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
    val implementationSchema = document.implementationSchema
    val desiredGeneration = document.desiredGeneration
    val databaseIdentity = uuid(document.databaseIdentity)
    val restoreIdentity = uuid(document.restoreIdentity)
    val database = document.database
    val protectedTrustParent: Path = Path.of(database.protectedTrustParent)
    private val publicTrust = binary(database.publicTrustPemBase64, 131_072)
    val runtimePassword = reference(database.runtimePassword, SecretMaterialFamily.DATABASE)
    val operatorPassword = reference(database.operatorPassword, SecretMaterialFamily.DATABASE)
    val userKey = reference(document.jwt.userKey, SecretMaterialFamily.USER_ADMIN_JWT)
    private val installations = references(document.jwt.installationKeys, SecretMaterialFamily.INSTALLATION_JWT, 8)
    val installationActiveKeyId = document.jwt.installationActiveKeyId
    val admissionCurrent = reference(document.admission.currentKey, SecretMaterialFamily.COMPLAINT_ADMISSION)
    val admissionPrevious = document.admission.previousKey?.let { reference(it, SecretMaterialFamily.COMPLAINT_ADMISSION) }
    private val cursors = references(document.admission.cursorKeys, SecretMaterialFamily.COMPLAINT_CURSOR, 8)
    val cursorActiveKeyId = document.admission.cursorActiveKeyId
    private val routing = references(document.journal.routing.keys, SecretMaterialFamily.COMPLAINT_JOURNAL_ROUTING, 4)
    val epochRotation = document.epochRotation

    val capacity = ComplaintCapacityPolicyV1.of(
        ComplaintCapacityVector.of(document.capacity.hardLimits.toLongArray()),
        ComplaintCapacityVector.of(document.capacity.creationLimits.toLongArray()),
        document.capacity.dailyEnrollmentLimit,
    )
    val jwtSettings = KiraSecurityProperties(
        issuer = document.jwt.issuer,
        audience = document.jwt.audience,
        accessTokenTtl = Duration.ofSeconds(document.jwt.accessTokenTtlSeconds),
        clockSkew = Duration.ofSeconds(document.jwt.clockSkewSeconds),
    )
    val consumerSettings = document.admission.let {
        VersionBoundComplaintConsumerSettings(
            it.coordinationMode, it.declaredInstances, it.concurrentLimit, it.ingressBucketLimit, it.ingressPerMinute,
            it.semanticBucketLimit, it.semanticEventLimit, it.pruneBatch, it.enrollmentGlobalPerHour, it.ownerCreateGlobalPerHour,
            it.ownerCreateMemberLimit, it.ownerCreatePruneBatch, it.trustForwardedHeaders, it.trustedProxies.toList(),
        )
    }
    val journal = document.journal.let {
        ComplaintJournalConfigurationV1.of(
            InitialLiveJournalDeclarationV1(
                it.writer,
                it.journalLocation,
                it.authorities,
                JournalRoutingV1(
                    it.routing.activeKeyId,
                    routing.map { key -> JournalRoutingKeyV1(key.logicalKeyId, key.version) },
                    it.routing.retentionSeconds,
                    it.routing.minimumRotationIntervalSeconds,
                ),
                it.encryption,
                it.recovery,
                it.limits,
            ),
        )
    }
    val catalog = document.catalog?.let(::catalog)
    val sealerMapping = document.sealer?.let { sealer ->
        val authorities = journal.declaration().authorities
        EpochSealDeploymentMappingV1(
            EpochSealEventPrincipalV1(authorities.ordinary, principal(sealer.ordinaryPrincipal)),
            EpochSealEventPrincipalV1(authorities.sealTerminal, principal(sealer.sealTerminalPrincipal)),
            EpochSealEventPrincipalV1(authorities.recovery, principal(sealer.recoveryPrincipal)),
            EpochSealCatalogPrincipalV1(sealer.catalogPut.reference, principal(sealer.catalogPut.principal)),
            EpochSealCatalogPrincipalV1(sealer.catalogSign.reference, principal(sealer.catalogSign.principal)),
            sealer.bootstrap.let { EpochSealBootstrapOriginV1(it.originId, it.version, it.credentialReferenceId, principal(it.principal)) },
            sealer.installedPolicyBundle,
        )
    }
    val sealerSessionName = document.sealer?.bootstrapSessionName
    val sealerLimits = document.sealer?.sdkLimits?.let {
        AwsEpochSealStsLimits(it.requestTimeoutMillis, it.connectTimeoutMillis, it.readTimeoutMillis, it.maxResponseBytes, it.clockUncertaintyMillis)
    }
    val livePolicy = document.livePolicy?.let(::livePolicy)
    val catalogSignerRotation = document.catalogSignerRotation?.let { writer ->
        CatalogSignerRotationDeploymentV1(
            writer.catalogWriterGenerationId,
            writer.signAuthority,
            writer.orderedSigningKeys.map { key ->
                CatalogSignerRotationKeyV1(key.keyId, key.keyArn, key.algorithmId, binary(key.publicKeySpkiBase64, 422), key.publicKeySha256)
            },
            writer.totalAttemptMillis,
        ).also { it.requireReader(checkNotNull(catalog)) }
    }

    init {
        valid(document.schemaVersion == 1 && implementationSchema == 1 && desiredGeneration > 0)
        valid(database.runtimeUsername != VersionBoundPersistenceConfiguration.DESIRED_INSTALLATION_OPERATOR_USERNAME)
        valid(database.host.length in 1..253 && database.host.split('.').all { DNS_LABEL.matches(it) })
        valid(database.port in 1..65_535 && DATABASE_NAME.matches(database.name) && DATABASE_NAME.matches(database.runtimeUsername))
        valid(database.ordinaryCapacity in 1..64 && protectedTrustParent.isAbsolute && protectedTrustParent.normalize() == protectedTrustParent)
        valid(document.admission.coordinationMode == "memory" && document.admission.declaredInstances == 1)
        valid(document.admission.trustedProxies.size <= 64 && document.admission.trustedProxies.all { it.length in 1..128 })
        valid(document.jwt.issuer.length in 1..256 && document.jwt.audience.length in 1..256 && userKey.logicalKeyId == JwtService.KEY_ID)
        valid(installations.any { it.logicalKeyId == installationActiveKeyId } && cursors.any { it.logicalKeyId == cursorActiveKeyId })
        valid(admissionPrevious == null || admissionCurrent.logicalKeyId != admissionPrevious.logicalKeyId)
        val all = allBindings()
        valid(all.map { it.version }.distinct().size == all.size)
        valid(journal.declaration().writer.databaseIdentity == databaseIdentity.toString())
        valid(journal.declaration().writer.restoreIdentity == restoreIdentity.toString())
        valid((profile == DesiredProcessProfileV1.D7) == (catalogSignerRotation != null))
        if (profile != DesiredProcessProfileV1.D7) valid((profile == DesiredProcessProfileV1.D6) == (livePolicy != null))
        valid(
            when (profile) {
                DesiredProcessProfileV1.D1 -> catalog == null && !epochRotation && sealerMapping == null
                DesiredProcessProfileV1.D2 -> catalog?.projectedCurrent == false && !epochRotation && sealerMapping == null
                DesiredProcessProfileV1.D3 -> catalog?.projectedCurrent == false && epochRotation && sealerMapping == null
                DesiredProcessProfileV1.D4 -> catalog?.projectedCurrent == false && epochRotation && sealerMapping != null
                DesiredProcessProfileV1.D5 -> catalog?.projectedCurrent == true && (sealerMapping == null || epochRotation)
                DesiredProcessProfileV1.D6 -> catalog != null && epochRotation && sealerMapping != null
                DesiredProcessProfileV1.D7 -> desiredGeneration == 1L && catalog?.projectedCurrent == false && (sealerMapping == null || epochRotation) &&
                    (livePolicy == null || (epochRotation && sealerMapping != null))
            },
        )
        livePolicy?.requireJournal(journal, checkNotNull(catalog))
        // Trigger actual cold consumer-policy validation before any secret provider/DB acquisition.
        consumerSettings.admissionPolicy(capacity)
        consumerSettings.ownerCreatePolicy(capacity)
        consumerSettings.clientIpResolver()
    }

    fun publicTrustPem(): ByteArray = publicTrust.copyOf()
    fun installationKeys(): List<VersionedSecretBinding> = installations.toList()
    fun cursorKeys(): List<VersionedSecretBinding> = cursors.toList()
    fun routingKeys(): List<VersionedSecretBinding> = routing.toList()
    fun allBindings(): List<VersionedSecretBinding> =
        listOf(runtimePassword, operatorPassword, userKey) + installations + listOfNotNull(admissionCurrent, admissionPrevious) + cursors + routing

    /** Same TARGET secret inventory, without acquiring or composing the separate configuration operator. */
    internal fun targetBindings(): List<VersionedSecretBinding> =
        listOf(runtimePassword, userKey) + installations + listOfNotNull(admissionCurrent, admissionPrevious) + cursors + routing

    /** Refuse an unprogressable pristine install before even secret acquisition. No D1 or projected-only D5 escape. */
    fun requireBootstrapProfile() {
        valid(
            desiredGeneration == 1L &&
                profile in setOf(
                    DesiredProcessProfileV1.D2, DesiredProcessProfileV1.D3, DesiredProcessProfileV1.D4, DesiredProcessProfileV1.D6, DesiredProcessProfileV1.D7,
                ),
        )
        valid(catalog?.projectedCurrent == false)
    }

    internal fun requireTargetFinalizerProfile() {
        requireBootstrapProfile()
        valid(
            database.runtimeUsername != VersionBoundPersistenceConfiguration.CATALOG_GENESIS_AUTHOR_USERNAME &&
                database.runtimeUsername != VersionBoundPersistenceConfiguration.DESIRED_INSTALLATION_OPERATOR_USERNAME,
        )
    }

    private fun catalog(input: DesiredCatalogInputV1): VersionBoundCatalogReadbackConfigurationV1 {
        valid(input.readerProfile == "G1" || input.readerProfile == "PROJECTED_CURRENT")
        val trust = OfflineTrustBundlePolicy(
            binary(input.rootPublicKeySpkiBase64, 4096),
            input.rootPublicKeySha256,
            input.rootKeyId,
            input.rootAlgorithmId,
            input.expectedEnvironment,
            input.expectedCatalogLocations.toList(),
            input.minimumBundleVersion,
        )
        val chain = OfflineCatalogChainReaderPolicy(
            trust,
            input.currentWriterGenerationIds.toList(),
            input.currentApproverIds.toList(),
            input.chainLimits.let {
                OfflineCatalogChainLimits(it.maximumEnvelopeBytes, it.maximumManifestRecords, it.maximumGenerations, it.maximumEncodedBytes)
            },
        )
        val sdk = input.sdkLimits.let {
            S3CatalogReadbackLimits(
                it.requestTimeoutMillis,
                it.connectTimeoutMillis,
                it.readTimeoutMillis,
                it.maximumListBytes,
                it.maximumErrorBytes,
                it.maximumObjectBytes,
            )
        }
        val initial = binary(input.initialBundleBase64, 131_072)
        val current = binary(input.currentBundleBase64, 131_072)
        return if (input.readerProfile == "PROJECTED_CURRENT") {
            VersionBoundCatalogReadbackConfigurationV1.fromIndependentProjectedInputs(
                initial,
                current,
                chain,
                input.expectedGenesisEnvelopeSha256,
                sdk,
                input.totalAttemptMillis,
                input.pageSize,
                input.maximumPagesPerLocation,
            )
        } else {
            VersionBoundCatalogReadbackConfigurationV1.fromIndependentInputs(
                initial,
                current,
                chain,
                input.expectedGenesisEnvelopeSha256,
                sdk,
                input.totalAttemptMillis,
                input.pageSize,
                input.maximumPagesPerLocation,
            )
        }
    }

    private fun livePolicy(input: DesiredLivePolicyInputV1): LiveJournalPolicyDeploymentV1 = LiveJournalPolicyDeploymentV1(
        input.environment,
        input.copyPolicies.map {
            LiveJournalCopyPolicyV1(it.sourceKind, it.locationClass, it.accountId, it.region, it.bucket, it.prefix, it.policy, it.maximumAgeSeconds)
        },
        input.journalLock.let { LiveJournalLockPolicyV1(it.location, it.ordinaryPrefix, it.sealTerminalPrefix, it.authorities, it.policy) },
        input.hmacKeys.map {
            LiveJournalHmacRetentionV1(
                JournalRoutingKeyV1(it.key.keyId, ImmutableSecretVersion.awsSecretsManager(it.key.resourceArn, it.key.versionId)),
                it.policy,
            )
        },
        input.kmsKeys.map { LiveJournalKmsRetentionV1(it.key, it.policy) },
        input.acceptedRequestLateArrival.let { LiveJournalTimeBoundV1(it.profileId, it.policy, it.maximumMillis) },
        input.utcUncertainty.let { LiveJournalTimeBoundV1(it.profileId, it.policy, it.maximumMillis) },
    )

    override fun toString(): String = "ComplaintDesiredDeploymentInputsV1(independent-intent,redacted,no-authority)"

    companion object {
        internal fun fromDecoded(document: ComplaintDesiredDeploymentDocumentV1): ComplaintDesiredDeploymentInputsV1 {
            requireConnectionFree()
            return ComplaintDesiredDeploymentInputsV1(document)
        }

        private fun uuid(input: String): UUID {
            valid(OfflineBootstrapGrammar.uuidV4(input))
            return UUID.fromString(input)
        }

        private fun reference(input: DesiredSecretReferenceV1, family: SecretMaterialFamily): VersionedSecretBinding = VersionedSecretBinding.of(
            family,
            if (family == SecretMaterialFamily.DATABASE) SecretMaterialPurpose.AUTHENTICATION_PASSWORD else SecretMaterialPurpose.HMAC_SHA256,
            input.keyId,
            ImmutableSecretVersion.awsSecretsManager(input.resourceArn, input.versionId),
        )

        private fun references(inputs: List<DesiredSecretReferenceV1>, family: SecretMaterialFamily, maximum: Int): List<VersionedSecretBinding> {
            valid(inputs.size in 1..maximum && inputs.map { it.keyId }.distinct().size == inputs.size)
            return inputs.map { reference(it, family) }
        }

        private fun binary(input: String, maximumBytes: Int): ByteArray {
            valid(input.length in 1..((maximumBytes + 2) / 3 * 4))
            val decoded = Base64.getDecoder().decode(input)
            valid(decoded.size in 1..maximumBytes && Base64.getEncoder().encodeToString(decoded) == input)
            return decoded
        }

        private fun principal(input: DesiredIamPrincipalInputV1): EpochSealProviderPrincipalV1 = when (input.kind) {
            "ROLE" -> EpochSealProviderPrincipalV1.role(input.arn, input.stableId)
            "USER" -> EpochSealProviderPrincipalV1.user(input.arn, input.stableId)
            else -> throw ComplaintDesiredInstallationExceptionV1(ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
        }

        private fun valid(condition: Boolean) = requireDesiredInstallation(condition, ComplaintDesiredInstallationFailureV1.INPUT_REFUSED)
        private val DNS_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        private val DATABASE_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,62}")
    }
}

/** Each spelling selects a genuine retained factory, never a replacement schema tag on a smaller graph. */
internal enum class DesiredProcessProfileV1 { D1, D2, D3, D4, D5, D6, D7 }
