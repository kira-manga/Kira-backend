package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConfiguration
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.JournalRoutingKeyV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainLimits
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundlePolicy
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealBootstrapOriginV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealCatalogPrincipalV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealDeploymentMappingV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealEventPrincipalV1
import me.manga.kira.backend.complaint.infrastructure.catalog.EpochSealProviderPrincipalV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundCatalogReadbackConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundTestActivationConfigurationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogSigningKeyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.S3CatalogReadbackLimits
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalHmacRetentionV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalKmsRetentionV1
import me.manga.kira.backend.complaint.infrastructure.journal.LiveJournalTimeBoundV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealRetentionDeclarationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDenialAuthorityPolicyV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestTerminalDenialAuthorityPolicyV1
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.security.ImmutableSecretVersion
import me.manga.kira.backend.security.JwtService
import me.manga.kira.backend.security.SecretMaterialFamily
import me.manga.kira.backend.security.SecretMaterialPurpose
import me.manga.kira.backend.security.VersionBoundComplaintConsumerSettings
import me.manga.kira.backend.security.VersionedSecretBinding
import me.manga.kira.backend.security.aws.AwsEpochSealStsLimits
import java.nio.file.FileSystems
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CancellationException

/** Independent TEST declarations only. No current run/installed policy/effective-D or external approval is asserted. */
internal class ComplaintTestDeploymentInputsV1 private constructor(document: ComplaintTestDeploymentDocumentV1) {
    val implementationSchema = document.implementationSchema
    val desiredGeneration = document.desiredGeneration
    val dataScopeId = uuid(document.dataScopeId)
    val databaseIdentity = uuid(document.databaseIdentity)
    val restoreIdentity = uuid(document.restoreIdentity)
    val database = document.database
    val protectedTrustParent: Path = Path.of(database.protectedTrustParent)
    private val publicTrust = binary(database.publicTrustPemBase64, 131_072)
    val runtimePassword = reference(database.runtimePassword, SecretMaterialFamily.DATABASE)
    val userKey = reference(document.jwt.userKey, SecretMaterialFamily.USER_ADMIN_JWT)
    private val installations = references(document.jwt.installationKeys, SecretMaterialFamily.INSTALLATION_JWT, 8)
    val installationActiveKeyId = document.jwt.installationActiveKeyId
    val admissionCurrent = reference(document.admission.currentKey, SecretMaterialFamily.COMPLAINT_ADMISSION)
    val admissionPrevious = document.admission.previousKey?.let { reference(it, SecretMaterialFamily.COMPLAINT_ADMISSION) }
    private val cursors = references(document.admission.cursorKeys, SecretMaterialFamily.COMPLAINT_CURSOR, 8)
    val cursorActiveKeyId = document.admission.cursorActiveKeyId
    // Exact flat TEST J and canonical round-trip; no LIVE conversion or caller-supplied J hash.
    val journal = TestOwnerDeleteJournalConfigurationV1.fromDocument(document.journal)
    private val routing = journal.declaration().routing.keys.map {
        reference(DesiredSecretReferenceV1(it.keyId, it.secret.resourceArn, it.secret.versionId), SecretMaterialFamily.COMPLAINT_JOURNAL_ROUTING)
    }
    val capacity = ComplaintCapacityPolicyV1.of(
        ComplaintCapacityVector.of(document.capacity.hardLimits.toLongArray()),
        ComplaintCapacityVector.of(document.capacity.creationLimits.toLongArray()),
        document.capacity.dailyEnrollmentLimit,
    )
    val jwtSettings = KiraSecurityProperties(
        issuer = document.jwt.issuer, audience = document.jwt.audience,
        accessTokenTtl = Duration.ofSeconds(document.jwt.accessTokenTtlSeconds), clockSkew = Duration.ofSeconds(document.jwt.clockSkewSeconds),
    )
    val consumerSettings = document.admission.let {
        VersionBoundComplaintConsumerSettings(
            it.coordinationMode, it.declaredInstances, it.concurrentLimit, it.ingressBucketLimit, it.ingressPerMinute,
            it.semanticBucketLimit, it.semanticEventLimit, it.pruneBatch, it.enrollmentGlobalPerHour, it.ownerCreateGlobalPerHour,
            it.ownerCreateMemberLimit, it.ownerCreatePruneBatch, it.trustForwardedHeaders, it.trustedProxies.toList(),
        )
    }
    val catalog = catalog(document.catalog)
    val activationSigningKey = document.activation.signingKey.let {
        CatalogSigningKeyV1(it.keyId, it.keyArn, it.algorithmId, binary(it.publicKeySpkiBase64, 422), it.publicKeySha256)
    }
    private val initialRegistry = binary(document.activation.initialWriterRegistryBase64, 131_072)
    val activationTotalAttemptMillis = document.activation.totalAttemptMillis
    val activeFirstCut = document.activeFirstCut?.also(
        me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveFirstCutV1::requireInput,
    )
    val activeFirstCutSuccessor = document.activeFirstCutSuccessor?.also(
        me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveFirstCutSuccessorV1::requireInput,
    )
    val activeOrdinarySealRecovery = document.activeOrdinarySealRecovery?.also(
        me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveOrdinarySealRecoveryV1::requireInput,
    )
    val ordinaryDenial = document.ordinaryDenial?.let(TestOrdinaryDenialAuthorityPolicyV1::fromIndependentInput)
    val terminalDenial = document.terminalDenial?.let(TestTerminalDenialAuthorityPolicyV1::fromIndependentInput)
    val sealerMapping = document.sealer.let { sealer ->
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
    val ordinaryPublication = document.ordinaryPublication
    val initialCheckpoint = document.initialCheckpoint?.also(
        me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveInitialCheckpointV1::requireInput,
    )
    val initialCheckpointCreate = document.initialCheckpointCreate?.also(
        me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestInitialCheckpointCreateV1::requireInput,
    )
    val sealerSessionName = document.sealer.bootstrapSessionName
    val sealerLimits = document.sealer.sdkLimits.let {
        AwsEpochSealStsLimits(it.requestTimeoutMillis, it.connectTimeoutMillis, it.readTimeoutMillis, it.maxResponseBytes, it.clockUncertaintyMillis)
    }
    val retention = document.retention.let {
        val horizon = Instant.parse(it.lastPreRunRestoreHorizon)
        valid(horizon.toString() == it.lastPreRunRestoreHorizon)
        TestOrdinarySealRetentionDeclarationV1(
            it.environment, it.dataScopeId, it.writerGeneration, it.databaseIdentity, it.restoreIdentity,
            horizon, it.horizonPolicy, it.journalLockPolicy,
            it.hmacKeys.map { key -> LiveJournalHmacRetentionV1(
                JournalRoutingKeyV1(key.key.keyId, ImmutableSecretVersion.awsSecretsManager(key.key.resourceArn, key.key.versionId)), key.policy,
            ) },
            it.kmsKeys.map { key -> LiveJournalKmsRetentionV1(key.key, key.policy) },
            it.acceptedRequestLateArrival.let { bound -> LiveJournalTimeBoundV1(bound.profileId, bound.policy, bound.maximumMillis) },
            it.utcUncertainty.let { bound -> LiveJournalTimeBoundV1(bound.profileId, bound.policy, bound.maximumMillis) },
        )
    }

    init {
        valid(document.schemaVersion == 1 && implementationSchema == 1 && desiredGeneration > 0)
        // Old recipes/default bytes stay distinct. Admin requires its own J AND denial recipe.
        val combinedInitialRecovery = document.profile == INITIAL_RECOVERY_CHECKPOINT_PROFILE
        valid((document.profile in setOf(ACTIVE_FIRST_CUT_PROFILE, ACTIVE_FIRST_CUT_SUCCESSOR_PROFILE,
            INITIAL_CHECKPOINT_PROFILE, ACTIVE_SEAL_RECOVERY_PROFILE, INITIAL_RECOVERY_CHECKPOINT_PROFILE)) == (activeFirstCut != null))
        // Combined ownership is declared before full D. The RESERVED successor remains optional,
        // but its presence is committed in full D; it cannot be added to an already registered run.
        if (!combinedInitialRecovery) valid((document.profile == ACTIVE_FIRST_CUT_SUCCESSOR_PROFILE) == (activeFirstCutSuccessor != null))
        valid((document.profile == INITIAL_CHECKPOINT_PROFILE || combinedInitialRecovery) == (initialCheckpoint != null))
        valid(initialCheckpointCreate == null || initialCheckpoint != null)
        valid((document.profile == ACTIVE_SEAL_RECOVERY_PROFILE || combinedInitialRecovery) == (activeOrdinarySealRecovery != null))
        valid((activeFirstCut != null) == (ordinaryPublication != null))
        // Explicit independent opt-in before full D; never a replacement for the ordinary grant.
        valid(terminalDenial == null || ordinaryDenial != null)
        valid(when (document.profile) {
            PROFILE -> !journal.adminDelete && !journal.ownerDeleteAll && ordinaryDenial == null
            OWNER_ERASURE_PROFILE -> !journal.adminDelete && journal.ownerDeleteAll && ordinaryDenial == null
            DRAIN_PROFILE -> !journal.adminDelete && !journal.ownerDeleteAll && ordinaryDenial != null
            OWNER_ERASURE_DRAIN_PROFILE -> !journal.adminDelete && journal.ownerDeleteAll && ordinaryDenial != null
            ADMIN_ERASURE_DRAIN_PROFILE -> journal.registeredAdminDelete && !journal.adminBatchDelete && ordinaryDenial != null
            ADMIN_BATCH_ERASURE_DRAIN_PROFILE -> journal.registeredAdminBatchDelete && ordinaryDenial != null
            ACTIVE_FIRST_CUT_PROFILE, INITIAL_CHECKPOINT_PROFILE, ACTIVE_SEAL_RECOVERY_PROFILE, INITIAL_RECOVERY_CHECKPOINT_PROFILE -> journal.registeredAdminBatchDelete && ordinaryDenial != null && activeFirstCut != null
            ACTIVE_FIRST_CUT_SUCCESSOR_PROFILE -> journal.registeredAdminBatchDelete && ordinaryDenial != null && activeFirstCut != null && activeFirstCutSuccessor != null
            else -> false
        })
        valid(database.runtimeUsername != VersionBoundPersistenceConfiguration.DESIRED_INSTALLATION_OPERATOR_USERNAME &&
            database.runtimeUsername != VersionBoundPersistenceConfiguration.CATALOG_GENESIS_AUTHOR_USERNAME)
        valid(database.host.length in 1..253 && database.host.split('.').all { DNS_LABEL.matches(it) })
        valid(database.port in 1..65_535 && DATABASE_NAME.matches(database.name) && DATABASE_NAME.matches(database.runtimeUsername))
        // The existing complete TEST D requires an ordinary pool larger than its reserved slot.
        valid(database.ordinaryCapacity in 2..64 && protectedTrustParent.fileSystem === FileSystems.getDefault() &&
            protectedTrustParent.isAbsolute && protectedTrustParent.normalize() == protectedTrustParent && database.protectedTrustParent.length <= 4096)
        valid(document.admission.coordinationMode == "memory" && document.admission.declaredInstances == 1)
        valid(document.admission.trustedProxies.size <= 64 && document.admission.trustedProxies.all { it.length in 1..128 })
        valid(document.jwt.issuer.length in 1..256 && document.jwt.issuer.isNotBlank() &&
            document.jwt.audience.length in 1..256 && document.jwt.audience.isNotBlank() && userKey.logicalKeyId == JwtService.KEY_ID)
        valid(installations.any { it.logicalKeyId == installationActiveKeyId } && cursors.any { it.logicalKeyId == cursorActiveKeyId })
        valid(admissionPrevious == null || admissionCurrent.logicalKeyId != admissionPrevious.logicalKeyId)
        val all = allBindings()
        valid(all.map { it.version }.distinct().size == all.size)
        val declaration = journal.declaration()
        valid(journal.scope.id == dataScopeId && declaration.writer.databaseIdentity == databaseIdentity.toString() &&
            declaration.writer.restoreIdentity == restoreIdentity.toString())
        retention.requireJournal(journal)
        valid(retention.environment == catalog.chainPolicy.trustBundlePolicy.expectedEnvironment)
        ordinaryDenial?.requireEnvironment(retention.environment)
        ordinaryDenial?.requireJournal(journal)
        terminalDenial?.requireEnvironment(retention.environment)
        terminalDenial?.requireJournal(journal)
        valid(sealerMapping.sealTerminal.principal.accountId == declaration.journalLocation.accountId)
        // Validate the private source-session spelling before any secret acquisition; it is excluded from D.
        sealerMapping.bootstrap.principal.callerArn(sealerSessionName)
        sealerMapping.bootstrap.principal.callerUserId(sealerSessionName)
        ordinaryPublication?.let {
            sealerMapping.ordinary.principal.callerArn(it.sessionName)
            sealerMapping.ordinary.principal.callerUserId(it.sessionName)
        }
        initialCheckpoint?.let {
            sealerMapping.recovery.principal.callerArn(it.recoverySessionName)
            sealerMapping.recovery.principal.callerUserId(it.recoverySessionName)
        }
        val (registry, _) = VersionBoundTestActivationConfigurationV1.checkIndependentInputs(
            catalog, journal, activationSigningKey, initialRegistry, activationTotalAttemptMillis,
        )
        valid(sealerMapping.catalogPut.reference == registry.registry.catalogWriter.putAuthority &&
            sealerMapping.catalogSign.reference == registry.registry.catalogWriter.signAuthority)
        consumerSettings.admissionPolicy(capacity)
        consumerSettings.ownerCreatePolicy(capacity)
        consumerSettings.clientIpResolver()
    }

    fun publicTrustPem(): ByteArray = publicTrust.copyOf()
    fun initialWriterRegistryBytes(): ByteArray = initialRegistry.copyOf()
    fun installationKeys(): List<VersionedSecretBinding> = installations.toList()
    fun cursorKeys(): List<VersionedSecretBinding> = cursors.toList()
    fun routingKeys(): List<VersionedSecretBinding> = routing.toList()
    fun allBindings(): List<VersionedSecretBinding> =
        listOf(runtimePassword, userKey) + installations + listOfNotNull(admissionCurrent, admissionPrevious) + cursors + routing

    private fun catalog(input: DesiredCatalogInputV1): VersionBoundCatalogReadbackConfigurationV1 {
        valid(input.readerProfile == "G1" || input.readerProfile == "PROJECTED_CURRENT")
        val trust = OfflineTrustBundlePolicy(
            binary(input.rootPublicKeySpkiBase64, 4096), input.rootPublicKeySha256, input.rootKeyId, input.rootAlgorithmId,
            input.expectedEnvironment, input.expectedCatalogLocations.toList(), input.minimumBundleVersion,
        )
        val chain = OfflineCatalogChainReaderPolicy(
            trust, input.currentWriterGenerationIds.toList(), input.currentApproverIds.toList(),
            input.chainLimits.let {
                OfflineCatalogChainLimits(it.maximumEnvelopeBytes, it.maximumManifestRecords, it.maximumGenerations, it.maximumEncodedBytes)
            },
        )
        val sdk = input.sdkLimits.let { S3CatalogReadbackLimits(
            it.requestTimeoutMillis, it.connectTimeoutMillis, it.readTimeoutMillis, it.maximumListBytes, it.maximumErrorBytes, it.maximumObjectBytes,
        ) }
        val initial = binary(input.initialBundleBase64, 131_072)
        val current = binary(input.currentBundleBase64, 131_072)
        return if (input.readerProfile == "PROJECTED_CURRENT") {
            VersionBoundCatalogReadbackConfigurationV1.fromIndependentProjectedInputs(
                initial, current, chain, input.expectedGenesisEnvelopeSha256, sdk, input.totalAttemptMillis, input.pageSize, input.maximumPagesPerLocation,
            )
        } else {
            VersionBoundCatalogReadbackConfigurationV1.fromIndependentInputs(
                initial, current, chain, input.expectedGenesisEnvelopeSha256, sdk, input.totalAttemptMillis, input.pageSize, input.maximumPagesPerLocation,
            )
        }
    }

    override fun toString(): String = "ComplaintTestDeploymentInputsV1(TEST-declarations,redacted,external-verification-required)"

    companion object {
        const val PROFILE = "PRE_CUTOVER_TEST_ORDINARY_SEAL_V1"
        const val OWNER_ERASURE_PROFILE = "PRE_CUTOVER_TEST_OWNER_ERASURE_ORDINARY_SEAL_V1"
        const val DRAIN_PROFILE = "PRE_CUTOVER_TEST_ORDINARY_DRAIN_V1"
        const val OWNER_ERASURE_DRAIN_PROFILE = "PRE_CUTOVER_TEST_OWNER_ERASURE_ORDINARY_DRAIN_V1"
        const val ACTIVE_SEAL_RECOVERY_PROFILE = "PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_INITIAL_EMPTY_SEAL_RECOVERY_V1"
        const val ACTIVE_FIRST_CUT_PROFILE = "PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_ACTIVE_FIRST_CUT_V1"
        const val ACTIVE_FIRST_CUT_SUCCESSOR_PROFILE = "PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_ACTIVE_FIRST_CUT_RESERVED_RECOVERY_V1"
        const val INITIAL_RECOVERY_CHECKPOINT_PROFILE = "PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_INITIAL_EMPTY_RECOVERY_CHECKPOINT_V1"
        const val INITIAL_CHECKPOINT_PROFILE = "PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_INITIAL_EMPTY_CHECKPOINT_V1"
        const val ADMIN_ERASURE_DRAIN_PROFILE = "PRE_CUTOVER_TEST_ADMIN_ERASURE_ORDINARY_DRAIN_V1"
        const val ADMIN_BATCH_ERASURE_DRAIN_PROFILE = "PRE_CUTOVER_TEST_ADMIN_BATCH_ERASURE_ORDINARY_DRAIN_V1"

        @Suppress("TooGenericExceptionCaught")
        internal fun fromDecoded(document: ComplaintTestDeploymentDocumentV1): ComplaintTestDeploymentInputsV1 {
            requireConnectionFree()
            return try {
                ComplaintTestDeploymentInputsV1(document)
            } catch (_: CancellationException) {
                throw CancellationException("TEST deployment intake cancelled.")
            } catch (_: Exception) {
                throw ComplaintTestDeploymentExceptionV1(ComplaintTestDeploymentFailureV1.INPUT_REFUSED)
            }
        }

        private fun uuid(input: String): UUID {
            valid(OfflineBootstrapGrammar.uuidV4(input))
            return UUID.fromString(input)
        }

        private fun reference(input: DesiredSecretReferenceV1, family: SecretMaterialFamily): VersionedSecretBinding = VersionedSecretBinding.of(
            family, if (family == SecretMaterialFamily.DATABASE) SecretMaterialPurpose.AUTHENTICATION_PASSWORD else SecretMaterialPurpose.HMAC_SHA256,
            input.keyId, ImmutableSecretVersion.awsSecretsManager(input.resourceArn, input.versionId),
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
            else -> throw ComplaintTestDeploymentExceptionV1(ComplaintTestDeploymentFailureV1.INPUT_REFUSED)
        }

        private fun valid(condition: Boolean) = requireTestDeployment(condition, ComplaintTestDeploymentFailureV1.INPUT_REFUSED)
        private val DNS_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        private val DATABASE_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,62}")
    }
}
