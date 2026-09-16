package me.manga.kira.backend.complaint.domain

import kotlinx.serialization.Serializable
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.InitialEventRoleV1
import me.manga.kira.backend.complaint.domain.catalog.InitialJournalLocationV1
import me.manga.kira.backend.complaint.domain.catalog.InitialLiveScopeV1
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.security.ImmutableSecretVersion
import java.util.HexFormat

/** Complete declarations for the supported initial-LIVE profile only; not provider proof, D or activation authority. */
internal class ComplaintJournalConfigurationV1 private constructor(private val stored: InitialLiveJournalDeclarationV1) {
    private val canonical = CanonicalJson.canonicalize(
        InitialLiveJournalDocument.serializer(),
        InitialLiveJournalDocument(
            kind = "kira-complaint-journal-configuration",
            schemaVersion = 1,
            canonicalizerId = "kcj-1",
            profile = "INITIAL_LIVE",
            scope = InitialLiveScopeV1("LIVE", OfflineBootstrapGrammar.LIVE_SCOPE_ID),
            writer = stored.writer,
            journalLocation = stored.journalLocation,
            ordinaryPrefix = OfflineBootstrapGrammar.ordinaryPrefix(stored.writer.generationId),
            sealTerminalPrefix = OfflineBootstrapGrammar.sealTerminalPrefix(stored.writer.generationId),
            authorities = stored.authorities,
            routing = JournalRoutingDocument(
                stored.routing.activeKeyId,
                stored.routing.keys.map { JournalRoutingKeyDocument(it.keyId, it.secret.resourceArn, it.secret.versionId) },
                stored.routing.retentionSeconds,
                stored.routing.minimumRotationIntervalSeconds,
            ),
            encryption = stored.encryption,
            recovery = stored.recovery,
            limits = stored.limits,
            protocol = initialLiveJournalProtocol(),
        ),
    ).toByteArray(Charsets.UTF_8)

    val sha256: String = Sha256.hex(canonical)

    fun canonicalBytes(): ByteArray = canonical.copyOf()

    fun digestBytes(): ByteArray = HexFormat.of().parseHex(sha256)

    fun declaration(): InitialLiveJournalDeclarationV1 = stored.copy(routing = stored.routing.copy(keys = stored.routing.keys.toList()))

    override fun toString(): String = "ComplaintJournalConfigurationV1(INITIAL_LIVE,redacted,no-authority)"

    companion object {
        private const val DAY_SECONDS = 86_400L
        private val kmsArn = Regex("arn:aws:kms:([^:]+):([0-9]{12}):key/([^:]+)")
        private val queueArn = Regex("arn:aws:sqs:([^:]+):([0-9]{12}):([A-Za-z0-9_-]{1,80})")

        fun of(declaration: InitialLiveJournalDeclarationV1): ComplaintJournalConfigurationV1 {
            valid(CanonicalJson.CANON_VERSION == "kcj-1" && declaration.routing.keys.size in 1..4)
            val snapshot = declaration.copy(routing = declaration.routing.copy(keys = declaration.routing.keys.sortedBy { it.keyId }))
            validateWriterAndLocation(snapshot)
            validateAuthorities(snapshot.authorities)
            validateKeysAndRecovery(snapshot)
            validateLimits(snapshot.limits)
            val routing = snapshot.routing
            valid(routing.retentionSeconds >= snapshot.limits.retention.ordinaryRetentionSeconds)
            val minimumInterval = routing.retentionSeconds / 3 + if (routing.retentionSeconds % 3 == 0L) 0 else 1
            valid(routing.minimumRotationIntervalSeconds >= minimumInterval)
            return ComplaintJournalConfigurationV1(snapshot)
        }

        private fun validateWriterAndLocation(input: InitialLiveJournalDeclarationV1) {
            val writer = input.writer
            valid(listOf(writer.databaseIdentity, writer.restoreIdentity, writer.generationId).all(OfflineBootstrapGrammar::uuidV4))
            val location = input.journalLocation
            valid(OfflineBootstrapGrammar.bucket(location.bucket))
            valid(OfflineBootstrapGrammar.account(location.accountId) && OfflineBootstrapGrammar.region(location.region))
        }

        private fun validateAuthorities(authorities: JournalAuthoritiesV1) {
            val roles = listOf(authorities.ordinary, authorities.sealTerminal, authorities.recovery)
            roles.forEach {
                valid(OfflineBootstrapGrammar.referenceId(it.roleId) && OfflineBootstrapGrammar.referenceId(it.credentialId))
                validatePolicy(it.policy)
            }
            valid(roles.map { it.roleId }.distinct().size == 3 && roles.map { it.credentialId }.distinct().size == 3)
            valid(roles.none { role -> roles.any { it.credentialId == role.roleId } })
            valid(roles.map { it.policy.policyId }.distinct().size == 3)
            val isolation = authorities.isolation
            val administrators = listOf(isolation.bucketAdministratorId, isolation.kmsAdministratorId)
            val principals = administrators + isolation.deploymentPrincipalId
            valid(principals.all(OfflineBootstrapGrammar::referenceId))
            valid(isolation.deploymentPrincipalId !in administrators)
            valid(roles.none { it.roleId in administrators || it.credentialId in principals })
            val domains = listOf(
                isolation.journalFailureDomainId,
                isolation.applicationDatabaseFailureDomainId,
                isolation.backupFailureDomainId,
            )
            valid(domains.all(OfflineBootstrapGrammar::referenceId))
            valid(domains.drop(1).none { it == isolation.journalFailureDomainId })
            validatePolicy(isolation.administrationPolicy)
            valid(roles.none { it.policy.policyId == isolation.administrationPolicy.policyId })
        }

        private fun validateKeysAndRecovery(input: InitialLiveJournalDeclarationV1) {
            val routing = input.routing
            valid(routing.keys.size in 1..4 && routing.keys.all { OfflineBootstrapGrammar.referenceId(it.keyId) })
            valid(routing.keys.map { it.keyId }.distinct().size == routing.keys.size)
            valid(routing.keys.map { it.secret.resourceArn to it.secret.versionId }.distinct().size == routing.keys.size)
            valid(routing.keys.count { it.keyId == routing.activeKeyId } == 1)
            routing.keys.forEach {
                val parts = it.secret.resourceArn.split(':')
                valid(parts[3] == input.journalLocation.region && parts[4] == input.journalLocation.accountId)
            }
            val recovery = input.recovery
            valid(recovery.queue.arn != recovery.deadLetterQueue.arn)
            listOf(recovery.queue, recovery.deadLetterQueue).forEach {
                val match = queueArn.matchEntire(it.arn)
                valid(match != null)
                validateProviderLocation(checkNotNull(match), input.journalLocation)
                validatePolicy(it.policy)
            }
            val keys = listOf(input.encryption, recovery.queue.encryption, recovery.deadLetterQueue.encryption)
            keys.forEach {
                valid(OfflineBootstrapGrammar.referenceId(it.keyId))
                val match = kmsArn.matchEntire(it.keyArn)
                valid(match != null && OfflineBootstrapGrammar.uuidV4(match.groupValues[3]))
                validateProviderLocation(checkNotNull(match), input.journalLocation)
                validatePolicy(it.policy)
            }
            valid(keys.groupBy { it.keyId }.values.all { it.distinct().size == 1 })
            valid(keys.groupBy { it.keyArn }.values.all { it.distinct().size == 1 })
            valid(routing.keys.none { route -> keys.any { it.keyId == route.keyId } })
        }

        private fun validateProviderLocation(match: MatchResult, location: InitialJournalLocationV1) {
            valid(match.groupValues[1] == location.region && match.groupValues[2] == location.accountId)
        }

        private fun validatePolicy(policy: InitialPolicyReferenceV1) {
            valid(OfflineBootstrapGrammar.referenceId(policy.policyId) && policy.version > 0 && OfflineBootstrapGrammar.sha256(policy.sha256))
        }

        private fun validateLimits(limits: JournalLimitsV1) {
            val retention = limits.retention
            valid(retention.ordinaryRetentionSeconds >= 400 * DAY_SECONDS)
            valid(retention.maximumRestoreAgeSeconds >= 0 && retention.maximumRestoreAgeSeconds <= retention.ordinaryRetentionSeconds - 31 * DAY_SECONDS)
            val capacity = limits.capacity
            valid(capacity.maximumPublicationLanes >= 2 && capacity.routinePublicationLanes in 1 until capacity.maximumPublicationLanes)
            valid(capacity.maximumRetainedVersions > 0 && capacity.maximumScanStagingBytes > 0)
            validateDeadlines(limits.deadlines)
            validateDecoder(limits.decoder)
        }

        private fun validateDeadlines(deadlines: JournalDeadlinesV1) {
            valid(deadlines.publicationAttemptMillis in 1..5000)
            valid(deadlines.s3CallMillis in 1..deadlines.publicationAttemptMillis && deadlines.kmsCallMillis in 1..deadlines.publicationAttemptMillis)
            valid(deadlines.epochRotationMillis in 1..10_000 && deadlines.scanMillis in 1..600_000)
            valid(deadlines.epochRotationMillis <= deadlines.scanMillis)
            valid(deadlines.epochSealMillis in deadlines.publicationAttemptMillis..deadlines.scanMillis)
            valid(deadlines.scanCadenceMillis in deadlines.scanMillis..900_000)
            valid(deadlines.checkpointMaxAgeMillis in deadlines.scanCadenceMillis..1_200_000)
            valid(deadlines.queueUnhealthyMillis in 1..30_000 && deadlines.queueCallMillis in 1..deadlines.queueUnhealthyMillis)
        }

        private fun validateDecoder(decoder: JournalDecoderLimitsV1) {
            valid(decoder.maximumEnvelopeBytes in 1..98_304 && decoder.maximumPlaintextBytes in 1..65_536)
            valid(decoder.maximumPlaintextBytes < decoder.maximumEnvelopeBytes)
            valid(decoder.maximumJsonDepth in 1..32 && decoder.maximumObjectFields in 1..64)
            valid(decoder.maximumJsonTokens in 1..decoder.maximumPlaintextBytes)
            valid(decoder.maximumStringUtf8Bytes in 1..decoder.maximumPlaintextBytes)
            valid(decoder.maximumWrappedKeyBytes in 1..decoder.maximumEnvelopeBytes)
        }

        private fun valid(condition: Boolean) {
            require(condition) { "Invalid initial LIVE journal configuration" }
        }
    }
}

/** All inputs are explicit declarations. There are no observed timestamps, secrets or caller-supplied digests for J. */
internal data class InitialLiveJournalDeclarationV1(
    val writer: JournalWriterV1,
    val journalLocation: InitialJournalLocationV1,
    val authorities: JournalAuthoritiesV1,
    val routing: JournalRoutingV1,
    val encryption: JournalKmsKeyV1,
    val recovery: JournalRecoveryV1,
    val limits: JournalLimitsV1,
)

@Serializable
internal data class JournalWriterV1(val databaseIdentity: String, val restoreIdentity: String, val generationId: String)

@Serializable
internal data class JournalAuthoritiesV1(
    val ordinary: InitialEventRoleV1,
    val sealTerminal: InitialEventRoleV1,
    val recovery: InitialEventRoleV1,
    val isolation: JournalIsolationV1,
)

@Serializable
internal data class JournalIsolationV1(
    val deploymentPrincipalId: String,
    val bucketAdministratorId: String,
    val kmsAdministratorId: String,
    val journalFailureDomainId: String,
    val applicationDatabaseFailureDomainId: String,
    val backupFailureDomainId: String,
    val administrationPolicy: InitialPolicyReferenceV1,
)

internal data class JournalRoutingV1(
    val activeKeyId: String,
    val keys: List<JournalRoutingKeyV1>,
    val retentionSeconds: Long,
    val minimumRotationIntervalSeconds: Long,
)

internal data class JournalRoutingKeyV1(val keyId: String, val secret: ImmutableSecretVersion)

/** An exact nonexportable KMS key identity and declared policy, not a version of KMS's backing key material. */
@Serializable
internal data class JournalKmsKeyV1(val keyId: String, val keyArn: String, val policy: InitialPolicyReferenceV1)

@Serializable
internal data class JournalRecoveryV1(val queue: JournalQueueV1, val deadLetterQueue: JournalQueueV1)

@Serializable
internal data class JournalQueueV1(val arn: String, val policy: InitialPolicyReferenceV1, val encryption: JournalKmsKeyV1)

@Serializable
internal data class JournalLimitsV1(
    val retention: JournalRetentionV1,
    val deadlines: JournalDeadlinesV1,
    val capacity: JournalRemoteCapacityV1,
    val decoder: JournalDecoderLimitsV1,
)

@Serializable
internal data class JournalRetentionV1(val ordinaryRetentionSeconds: Long, val maximumRestoreAgeSeconds: Long)

@Serializable
internal data class JournalDeadlinesV1(
    val s3CallMillis: Int,
    val kmsCallMillis: Int,
    val queueCallMillis: Int,
    val publicationAttemptMillis: Int,
    val epochRotationMillis: Int,
    val epochSealMillis: Int,
    val scanMillis: Int,
    val scanCadenceMillis: Int,
    val queueUnhealthyMillis: Int,
    val checkpointMaxAgeMillis: Int,
)

@Serializable
internal data class JournalRemoteCapacityV1(
    val maximumPublicationLanes: Int,
    val routinePublicationLanes: Int,
    val maximumRetainedVersions: Long,
    val maximumScanStagingBytes: Long,
)

@Serializable
internal data class JournalDecoderLimitsV1(
    val maximumEnvelopeBytes: Int,
    val maximumPlaintextBytes: Int,
    val maximumJsonDepth: Int,
    val maximumJsonTokens: Int,
    val maximumObjectFields: Int,
    val maximumStringUtf8Bytes: Int,
    val maximumWrappedKeyBytes: Int,
)

// Every serialized field is required; even zero values are emitted under kcj-1.
@Serializable
private data class InitialLiveJournalDocument(
    val kind: String,
    val schemaVersion: Int,
    val canonicalizerId: String,
    val profile: String,
    val scope: InitialLiveScopeV1,
    val writer: JournalWriterV1,
    val journalLocation: InitialJournalLocationV1,
    val ordinaryPrefix: String,
    val sealTerminalPrefix: String,
    val authorities: JournalAuthoritiesV1,
    val routing: JournalRoutingDocument,
    val encryption: JournalKmsKeyV1,
    val recovery: JournalRecoveryV1,
    val limits: JournalLimitsV1,
    val protocol: JournalProtocolDocument,
)

@Serializable
private data class JournalRoutingDocument(
    val activeKeyId: String,
    val keys: List<JournalRoutingKeyDocument>,
    val retentionSeconds: Long,
    val minimumRotationIntervalSeconds: Long,
)

@Serializable
private data class JournalRoutingKeyDocument(val keyId: String, val resourceArn: String, val versionId: String)

@Serializable
private data class JournalProtocolDocument(
    val payloadSchemaVersion: Int,
    val envelopeSchemaVersion: Int,
    val routingAlgorithm: String,
    val framing: JournalFramingDocument,
    val encryption: JournalEncryptionDocument,
    val storage: JournalStorageDocument,
)

@Serializable
private data class JournalFramingDocument(
    val version: Int,
    val encoding: String,
    val routingDomain: String,
    val eventIdDomain: String,
    val epochSealDomain: String,
    val aadDomain: String,
    val kmsContextDomain: String,
)

@Serializable
private data class JournalEncryptionDocument(
    val algorithm: String,
    val dataKeyMode: String,
    val dataKeyBytes: Int,
    val nonceBytes: Int,
    val tagBytes: Int,
    val envelopeHeadersAuthenticated: Boolean,
)

@Serializable
private data class JournalStorageDocument(
    val retentionMode: String,
    val putCondition: String,
    val checksumAlgorithm: String,
    val endToEndChecksumRequired: Boolean,
    val requiredObjectVersions: Int,
    val exactKeyReadbackMaximumEntries: Int,
    val versioningRequired: Boolean,
    val deleteMarkersAllowed: Boolean,
    val nativeExpirationAllowed: Boolean,
    val runtimeListingScope: String,
    val recoveryListingScope: String,
)

private fun initialLiveJournalProtocol(): JournalProtocolDocument = JournalProtocolDocument(
    payloadSchemaVersion = 1,
    envelopeSchemaVersion = 1,
    routingAlgorithm = "HMAC-SHA-256",
    framing = JournalFramingDocument(
        version = 1,
        encoding = "LP32BE-UTF8",
        routingDomain = "kira-complaint-journal-routing-v1",
        eventIdDomain = "kira-complaint-journal-event-id-v1",
        epochSealDomain = "kira-complaint-journal-epoch-seal-v1",
        aadDomain = "kira-complaint-journal-aad-v1",
        kmsContextDomain = "kira-complaint-journal-kms-context-v1",
    ),
    encryption = JournalEncryptionDocument("AES-256-GCM", "FRESH_PER_OBJECT_KMS_WRAPPED", 32, 12, 16, true),
    storage = JournalStorageDocument(
        retentionMode = "COMPLIANCE",
        putCondition = "If-None-Match:*",
        checksumAlgorithm = "SHA-256",
        endToEndChecksumRequired = true,
        requiredObjectVersions = 1,
        exactKeyReadbackMaximumEntries = 2,
        versioningRequired = true,
        deleteMarkersAllowed = false,
        nativeExpirationAllowed = false,
        runtimeListingScope = "EXACT_COMMITTED_KEY",
        recoveryListingScope = "DECLARED_LIVE_RANGE_ALL_VERSIONS",
    ),
)
