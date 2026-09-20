package me.manga.kira.backend.complaint.domain

import kotlinx.serialization.Serializable
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.InitialJournalLocationV1
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.security.ImmutableSecretVersion
import java.util.HexFormat
import java.util.UUID

/** Immutable TEST declarations only: no registered run, provider policy proof, projection or current authority. */
internal class TestOwnerDeleteJournalConfigurationV1 private constructor(
    private val stored: TestOwnerDeleteJournalDeclarationV1,
    val ownerDeleteAll: Boolean,
    val adminDelete: Boolean = false,
    val registeredAdminDelete: Boolean = false,
) {
    val profile: String = when {
        registeredAdminDelete -> "REGISTERED_TEST_ADMIN_ERASURE"
        adminDelete -> "LOWER_TEST_ADMIN_ERASURE"
        ownerDeleteAll -> "REGISTERED_TEST_OWNER_ERASURE"
        else -> "REGISTERED_TEST_OWNER_DELETE"
    }
    val scope: ComplaintDataScope get() = stored.scope
    val ordinaryPrefix = "complaints/journal/v1/${stored.writer.generationId}/test/${scope.id}/ordinary/"
    val sealTerminalPrefix = "complaints/journal/v1/${stored.writer.generationId}/test/${scope.id}/seal-terminal/"
    private val wireDocument = TestOwnerDeleteJournalDocumentV1(
        "kira-complaint-journal-configuration", 1, "kcj-1", profile, "TEST", scope.id.toString(),
        stored.writer, stored.journalLocation, ordinaryPrefix, sealTerminalPrefix, stored.authorities,
        stored.routing.activeKeyId,
        stored.routing.keys.map { TestOwnerDeleteRoutingKeyDocumentV1(it.keyId, it.secret.resourceArn, it.secret.versionId) },
        stored.routing.retentionSeconds, stored.routing.minimumRotationIntervalSeconds, stored.encryption, stored.recovery, stored.limits,
        (if (adminDelete) "KJEV-1/OWNER_DELETE+OWNER_DELETE_ALL+ADMIN_DELETE/INSTALLATION+ADMIN"
            else (if (ownerDeleteAll) "KJEV-1/OWNER_DELETE+OWNER_DELETE_ALL" else "KJEV-1/OWNER_DELETE") + "/INSTALLATION") +
            "/TEST/LP32BE-UTF8/HMAC-SHA-256/AES-256-GCM/FRESH_PER_OBJECT_KMS_WRAPPED",
    )
    private val canonical = CanonicalJson.canonicalize(TestOwnerDeleteJournalDocumentV1.serializer(), wireDocument).toByteArray(Charsets.UTF_8)
    val sha256: String = Sha256.hex(canonical)

    fun digestBytes(): ByteArray = HexFormat.of().parseHex(sha256)
    fun canonicalBytes(): ByteArray = canonical.copyOf()
    fun declaration(): TestOwnerDeleteJournalDeclarationV1 = stored.copy(routing = stored.routing.copy(keys = stored.routing.keys.toList()))

    /** The existing flat TEST-J wire shape, not arbitrary JSON or a LIVE-to-TEST conversion. */
    fun document(): TestOwnerDeleteJournalDocumentV1 = wireDocument.snapshot()

    override fun toString(): String = "TestOwnerDeleteJournalConfigurationV1(TEST,declaration-only)"

    companion object {
        /** Re-enter all existing TEST declaration validation and require the exact canonical round-trip. */
        fun fromDocument(input: TestOwnerDeleteJournalDocumentV1): TestOwnerDeleteJournalConfigurationV1 {
            require(input.dataScopeKind == "TEST" && OfflineBootstrapGrammar.uuidV4(input.dataScopeId)) { INVALID }
            require(input.routingKeys.size in 1..4) { INVALID }
            val snapshot = input.snapshot()
            val declaration = TestOwnerDeleteJournalDeclarationV1(
                    ComplaintDataScope.of(UUID.fromString(snapshot.dataScopeId)), snapshot.writer, snapshot.journalLocation, snapshot.authorities,
                    JournalRoutingV1(
                        snapshot.activeRoutingKeyId,
                        snapshot.routingKeys.map {
                            JournalRoutingKeyV1(it.keyId, ImmutableSecretVersion.awsSecretsManager(it.resourceArn, it.versionId))
                        },
                        snapshot.routingRetentionSeconds, snapshot.routingMinimumRotationIntervalSeconds,
                    ),
                    snapshot.encryption, snapshot.recovery, snapshot.limits,
                )
            // No boolean inference from an unknown/lower profile. A new registered J is an explicit
            // declaration, not an upgrade/relabel of a retained lower or owner activation.
            val checked = when (snapshot.profile) {
                "REGISTERED_TEST_OWNER_DELETE" -> of(declaration)
                "REGISTERED_TEST_OWNER_ERASURE" -> of(declaration, ownerDeleteAll = true)
                "REGISTERED_TEST_ADMIN_ERASURE" -> registeredAdminErasure(declaration)
                else -> throw IllegalArgumentException(INVALID)
            }
            val bytes = CanonicalJson.canonicalize(TestOwnerDeleteJournalDocumentV1.serializer(), snapshot).toByteArray(Charsets.UTF_8)
            require(bytes.contentEquals(checked.canonical)) { INVALID }
            return checked
        }

        /** Lower source composition only. Existing registered decoders deliberately reject this exact profile.
         * One coherent J is retained by the same lane registry; this is not registration or activation. */
        fun lowerAdminErasure(input: TestOwnerDeleteJournalDeclarationV1): TestOwnerDeleteJournalConfigurationV1 =
            TestOwnerDeleteJournalConfigurationV1(of(input, ownerDeleteAll = true).declaration(), true, true)

        /** Distinct registered TEST declaration; retains no run/request/provider authority. */
        fun registeredAdminErasure(input: TestOwnerDeleteJournalDeclarationV1): TestOwnerDeleteJournalConfigurationV1 =
            TestOwnerDeleteJournalConfigurationV1(of(input, ownerDeleteAll = true).declaration(), true, true, true)

        fun of(input: TestOwnerDeleteJournalDeclarationV1, ownerDeleteAll: Boolean = false): TestOwnerDeleteJournalConfigurationV1 {
            require(input.scope.testOnly && OfflineBootstrapGrammar.uuidV4(input.scope.id.toString())) { INVALID }
            require(input.routing.keys.size in 1..4) { INVALID }
            val value = input.copy(routing = input.routing.copy(keys = input.routing.keys.sortedBy { it.keyId }))
            validateLocation(value)
            validateAuthorities(value)
            validateKeys(value)
            validateLimits(value.limits)
            require(value.routing.retentionSeconds >= value.limits.retention.ordinaryRetentionSeconds) { INVALID }
            val interval = value.routing.retentionSeconds / 3 + if (value.routing.retentionSeconds % 3 == 0L) 0 else 1
            require(value.routing.minimumRotationIntervalSeconds >= interval) { INVALID }
            return TestOwnerDeleteJournalConfigurationV1(value, ownerDeleteAll)
        }

        private fun validateLocation(value: TestOwnerDeleteJournalDeclarationV1) {
            require(listOf(value.writer.databaseIdentity, value.writer.restoreIdentity, value.writer.generationId).all(OfflineBootstrapGrammar::uuidV4)) { INVALID }
            val location = value.journalLocation
            require(OfflineBootstrapGrammar.bucket(location.bucket) && OfflineBootstrapGrammar.account(location.accountId)) { INVALID }
            require(OfflineBootstrapGrammar.region(location.region)) { INVALID }
        }

        private fun validateAuthorities(value: TestOwnerDeleteJournalDeclarationV1) {
            val a = value.authorities
            val roles = listOf(a.ordinary, a.sealTerminal, a.recovery)
            roles.forEach {
                require(OfflineBootstrapGrammar.referenceId(it.roleId) && OfflineBootstrapGrammar.referenceId(it.credentialId)) { INVALID }
                policy(it.policy)
            }
            require(roles.map { it.roleId }.distinct().size == 3 && roles.map { it.credentialId }.distinct().size == 3) { INVALID }
            require(roles.map { it.policy.policyId }.distinct().size == 3 && roles.none { r -> roles.any { it.credentialId == r.roleId } }) { INVALID }
            val i = a.isolation
            val administrators = listOf(i.bucketAdministratorId, i.kmsAdministratorId)
            val principals = administrators + i.deploymentPrincipalId
            require(principals.all(OfflineBootstrapGrammar::referenceId) && i.deploymentPrincipalId !in administrators) { INVALID }
            require(roles.none { it.roleId in administrators || it.credentialId in principals }) { INVALID }
            val domains = listOf(i.journalFailureDomainId, i.applicationDatabaseFailureDomainId, i.backupFailureDomainId)
            require(domains.all(OfflineBootstrapGrammar::referenceId) && domains.drop(1).none { it == i.journalFailureDomainId }) { INVALID }
            policy(i.administrationPolicy)
            require(roles.none { it.policy.policyId == i.administrationPolicy.policyId }) { INVALID }
        }

        private fun validateKeys(value: TestOwnerDeleteJournalDeclarationV1) {
            val routing = value.routing
            val location = value.journalLocation
            require(routing.keys.all { OfflineBootstrapGrammar.referenceId(it.keyId) }) { INVALID }
            require(routing.keys.map { it.keyId }.distinct().size == routing.keys.size) { INVALID }
            require(routing.keys.map { it.secret.resourceArn to it.secret.versionId }.distinct().size == routing.keys.size) { INVALID }
            require(routing.keys.count { it.keyId == routing.activeKeyId } == 1) { INVALID }
            routing.keys.forEach {
                val parts = it.secret.resourceArn.split(':')
                require(parts.size >= 6 && parts[3] == location.region && parts[4] == location.accountId) { INVALID }
            }
            val queues = listOf(value.recovery.queue, value.recovery.deadLetterQueue)
            require(queues[0].arn != queues[1].arn) { INVALID }
            queues.forEach {
                require(Regex("arn:aws:sqs:${location.region}:${location.accountId}:[A-Za-z0-9_-]{1,80}").matches(it.arn)) { INVALID }
                policy(it.policy)
            }
            val keys = listOf(value.encryption) + queues.map { it.encryption }
            keys.forEach {
                require(OfflineBootstrapGrammar.referenceId(it.keyId)) { INVALID }
                val prefix = "arn:aws:kms:${location.region}:${location.accountId}:key/"
                require(it.keyArn.startsWith(prefix) && OfflineBootstrapGrammar.uuidV4(it.keyArn.removePrefix(prefix))) { INVALID }
                policy(it.policy)
            }
            require(keys.groupBy { it.keyId }.values.all { it.distinct().size == 1 } && keys.groupBy { it.keyArn }.values.all { it.distinct().size == 1 }) { INVALID }
            require(routing.keys.none { route -> keys.any { it.keyId == route.keyId } }) { INVALID }
        }

        private fun validateLimits(limits: JournalLimitsV1) {
            val r = limits.retention
            require(r.ordinaryRetentionSeconds >= 400 * 86400L && r.maximumRestoreAgeSeconds in 0..r.ordinaryRetentionSeconds - 31 * 86400L) { INVALID }
            val c = limits.capacity
            require(c.maximumPublicationLanes >= 2 && c.routinePublicationLanes in 1 until c.maximumPublicationLanes) { INVALID }
            require(c.maximumRetainedVersions > 0 && c.maximumScanStagingBytes > 0) { INVALID }
            val d = limits.deadlines
            require(d.publicationAttemptMillis in 1..5000 && d.s3CallMillis in 1..d.publicationAttemptMillis && d.kmsCallMillis in 1..d.publicationAttemptMillis) { INVALID }
            require(d.epochRotationMillis in 1..10000 && d.scanMillis in d.epochRotationMillis..600000) { INVALID }
            require(d.epochSealMillis in d.publicationAttemptMillis..d.scanMillis && d.scanCadenceMillis in d.scanMillis..900000) { INVALID }
            require(d.checkpointMaxAgeMillis in d.scanCadenceMillis..1200000 && d.queueUnhealthyMillis in 1..30000 && d.queueCallMillis in 1..d.queueUnhealthyMillis) { INVALID }
            val l = limits.decoder
            require(l.maximumEnvelopeBytes in 1..98304 && l.maximumPlaintextBytes in 1..65536 && l.maximumPlaintextBytes < l.maximumEnvelopeBytes) { INVALID }
            require(l.maximumJsonDepth in 1..32 && l.maximumObjectFields in 1..64 && l.maximumJsonTokens in 1..l.maximumPlaintextBytes) { INVALID }
            require(l.maximumStringUtf8Bytes in 1..l.maximumPlaintextBytes && l.maximumWrappedKeyBytes in 1..l.maximumEnvelopeBytes) { INVALID }
        }

        private fun policy(p: InitialPolicyReferenceV1) {
            require(OfflineBootstrapGrammar.referenceId(p.policyId) && p.version > 0 && OfflineBootstrapGrammar.sha256(p.sha256)) { INVALID }
        }

        private const val INVALID = "Invalid TEST owner-delete journal declaration"
    }
}

internal data class TestOwnerDeleteJournalDeclarationV1(
    val scope: ComplaintDataScope,
    val writer: JournalWriterV1,
    val journalLocation: InitialJournalLocationV1,
    val authorities: JournalAuthoritiesV1,
    val routing: JournalRoutingV1,
    val encryption: JournalKmsKeyV1,
    val recovery: JournalRecoveryV1,
    val limits: JournalLimitsV1,
)

@Serializable
internal data class TestOwnerDeleteRoutingKeyDocumentV1(val keyId: String, val resourceArn: String, val versionId: String)

@Serializable
internal data class TestOwnerDeleteJournalDocumentV1(
    val kind: String, val schemaVersion: Int, val canonicalizerId: String, val profile: String,
    val dataScopeKind: String, val dataScopeId: String, val writer: JournalWriterV1,
    val journalLocation: InitialJournalLocationV1, val ordinaryPrefix: String, val sealTerminalPrefix: String,
    val authorities: JournalAuthoritiesV1, val activeRoutingKeyId: String, val routingKeys: List<TestOwnerDeleteRoutingKeyDocumentV1>,
    val routingRetentionSeconds: Long, val routingMinimumRotationIntervalSeconds: Long,
    val encryption: JournalKmsKeyV1, val recovery: JournalRecoveryV1, val limits: JournalLimitsV1, val protocol: String,
)

internal fun TestOwnerDeleteJournalDocumentV1.snapshot(): TestOwnerDeleteJournalDocumentV1 = copy(routingKeys = routingKeys.toList())
