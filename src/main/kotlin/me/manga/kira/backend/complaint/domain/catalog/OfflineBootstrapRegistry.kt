package me.manga.kira.backend.complaint.domain.catalog

import kotlinx.serialization.Serializable

/** Initial-state claims only: singular writer/range fields, no terminal, test-run, legacy, or arbitrary-JSON records. */
@Serializable
internal data class OfflineBootstrapRegistryV1(
    val schemaVersion: Int,
    val canonicalizerId: String,
    val databaseIdentity: String,
    val restoreIdentity: String,
    val catalogWriter: InitialCatalogWriterV1,
    val eventWriter: InitialEventWriterV1,
)

@Serializable
internal data class InitialCatalogWriterV1(
    val generationId: String,
    val registration: String,
    val requiredSignerPolicy: InitialSignerPolicyV1,
    val catalogApproverIds: List<String>,
    val putAuthority: InitialCatalogPrincipalV1,
    val signAuthority: InitialCatalogPrincipalV1,
)

@Serializable
internal data class InitialSignerPolicyV1(val mode: String, val threshold: String, val members: List<OfflineRequiredSignerV1>)

@Serializable
internal data class InitialCatalogPrincipalV1(val principalId: String, val policy: InitialPolicyReferenceV1)

@Serializable
internal data class InitialPolicyReferenceV1(val policyId: String, val version: Long, val sha256: String)

@Serializable
internal data class InitialEventWriterV1(
    val generationId: String,
    val databaseIdentity: String,
    val restoreIdentity: String,
    val registration: String,
    val liveRange: InitialLiveRangeV1,
)

@Serializable
internal data class InitialLiveRangeV1(
    val scope: InitialLiveScopeV1,
    val state: String,
    val journalLocation: InitialJournalLocationV1,
    val ordinaryPrefix: String,
    val sealTerminalPrefix: String,
    val ordinaryAuthority: InitialEventRoleV1,
    val sealTerminalAuthority: InitialEventRoleV1,
    val routingKeyId: String,
    val encryptionKeyId: String,
    val configurationSha256: String,
    val firstEpoch: Long,
    val sealHistory: InitialSealHistoryV1,
)

@Serializable
internal data class InitialLiveScopeV1(val kind: String, val id: String)

@Serializable
internal data class InitialJournalLocationV1(val bucket: String, val accountId: String, val region: String)

@Serializable
internal data class InitialEventRoleV1(val roleId: String, val credentialId: String, val policy: InitialPolicyReferenceV1)

@Serializable
internal data class InitialSealHistoryV1(val count: Long, val sha256: String)

/** No caller can use this data as an accepted registration, credential, current writer, or catalog-head capability. */
internal class CheckedOfflineBootstrapRegistry(
    registry: OfflineBootstrapRegistryV1,
    canonicalRegistryBytes: ByteArray,
    val registrySha256: String,
    val trustBundleEnvelopeSha256: String,
    val trustBundleVersion: Long,
) {
    private val storedRegistry = registry.snapshot()
    private val storedBytes = canonicalRegistryBytes.copyOf()

    val registry: OfflineBootstrapRegistryV1 get() = storedRegistry.snapshot()
    val canonicalRegistryBytes: ByteArray get() = storedBytes.copyOf()

    override fun toString(): String = "CheckedOfflineBootstrapRegistry(no-live-authority)"
}

internal fun OfflineBootstrapRegistryV1.snapshot(): OfflineBootstrapRegistryV1 = copy(
    catalogWriter = catalogWriter.copy(
        requiredSignerPolicy = catalogWriter.requiredSignerPolicy.copy(members = catalogWriter.requiredSignerPolicy.members.toList()),
        catalogApproverIds = catalogWriter.catalogApproverIds.toList(),
    ),
)

/** Syntax and fixed namespace shape, never proof of fresh IDs or real deployed provider permissions. */
internal object OfflineBootstrapGrammar {
    const val LIVE_SCOPE_ID = "00000000-0000-0000-0000-000000000000"
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val reference = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val fingerprint = Regex("[0-9a-f]{64}")
    private val bucket = Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
    private val account = Regex("[0-9]{12}")
    private val region = Regex("[a-z]{2}(?:-[a-z0-9]+){1,3}-[0-9]{1,2}")

    fun uuidV4(value: String): Boolean = uuid.matches(value)
    fun referenceId(value: String): Boolean = reference.matches(value)
    fun sha256(value: String): Boolean = fingerprint.matches(value)
    fun approverId(value: String): Boolean = value.length in 1..256 && value.all { it in '!'..'~' }
    fun bucket(value: String): Boolean = bucket.matches(value) && !value.contains("..")
    fun account(value: String): Boolean = account.matches(value)
    fun region(value: String): Boolean = value.length <= 64 && region.matches(value)

    fun ordinaryPrefix(generationId: String): String = "complaints/journal/v1/$generationId/live/$LIVE_SCOPE_ID/ordinary/"
    fun sealTerminalPrefix(generationId: String): String = "complaints/journal/v1/$generationId/live/$LIVE_SCOPE_ID/seal-terminal/"
}
