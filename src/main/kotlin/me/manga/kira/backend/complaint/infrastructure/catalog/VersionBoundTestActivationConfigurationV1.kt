package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcParticipantRole
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.CheckedOfflineBootstrapRegistry
import me.manga.kira.backend.complaint.domain.catalog.InitialCatalogPrincipalV1
import me.manga.kira.backend.complaint.domain.catalog.InitialSignerPolicyV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapRegistryV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineRequiredSignerV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.snapshot
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogSigningKeyV1
import java.util.Base64

/**
 * Complete cold TEST activation policy, not a publisher or capability. Retains one actual signing
 * key, the original coordinator/reader/TEST J and independently verified historical registry bytes.
 * Current-bundle membership is NOT stable-predecessor proof: a later raw fold must establish SINGLE.
 * No generation-1, original-signer or never-closed restriction is imposed on that later predecessor.
 * No Sign, PUT, SQL, provider session, operation token, intent, approval or registration is retained.
 */
internal class VersionBoundTestActivationConfigurationV1 private constructor(
    internal val pools: VersionBoundPersistencePools,
    internal val reader: VersionBoundCatalogReadbackConfigurationV1,
    internal val journal: TestOwnerDeleteJournalConfigurationV1,
    internal val signingKey: CatalogSigningKeyV1,
    checkedRegistry: CheckedOfflineBootstrapRegistry,
    publicKeySpki: ByteArray,
    val totalAttemptMillis: Long,
) {
    internal val coordinator = pools.catalogCoordinator
    private val descriptors = pools.descriptors()
    private val rotation = pools.epochRotation
    private val rotationDescriptor = rotation?.descriptor()
    private val registry = checkedRegistry.registry
    private val registryBytes = checkedRegistry.canonicalRegistryBytes
    private val publicKey = publicKeySpki.copyOf()

    // These immutable owner fields are the sole policy source for both D and a future fixed operation.
    // The low-level standalone adapters permit larger budgets; they do not enforce this enclosing cap.
    val profileVersion = 1
    val profile = "TEST_RUN_ACTIVATION_SINGLE_SIGNER"
    val operation = "TEST_RUN_ACTIVATION"
    val catalogWriterGenerationId: String = registry.catalogWriter.generationId
    val initialWriterRegistrySha256: String = Sha256.hex(registryBytes)
    val initialWriterRegistryByteCount: Int = registryBytes.size
    val signAuthority: InitialCatalogPrincipalV1 = registry.catalogWriter.signAuthority
    val putAuthority: InitialCatalogPrincipalV1 = registry.catalogWriter.putAuthority
    val requiredSignerMode = "SINGLE"
    val requiredSignerThreshold = "ALL_MEMBERS"
    private val requiredSigner = OfflineRequiredSignerV1(signingKey.keyId, signingKey.algorithmId)
    val publicKeySha256: String = Sha256.hex(publicKey)
    val publicKeyByteCount: Int = publicKey.size
    val predecessorPolicy = "STABLE_SINGLE_SIGNER"

    val approvalInput = "EXISTING_CANONICAL_ID_TIME_ARRAY"
    val approvalCount = 2
    val approvalEligibility = "INITIAL_AND_CURRENT"
    val creatorMustApprove = true
    val sdkMaximumCallMillis = 10_000L
    val sdkMaximumAttempts = 1
    val maximumIntentBytes: Int = reader.chainPolicy.limits.maximumEnvelopeBytes
    val maximumApprovalBytes = 4096
    val maximumReadbackRounds = 5

    val coordinatorRole = PersistenceJdbcParticipantRole.CATALOG_COORDINATOR
    val credentialSelection = "EXPLICIT_SIGN_PRIMARY_PUT_PRIMARY_READ_REPLICA_READ_SESSIONS"
    val putLocationRole = "PRIMARY"
    val replicaPutAllowed = false
    val keyPrefix: String = CatalogReadbackProtocol.PREFIX
    val generationDigits: Int = CatalogReadbackProtocol.GENERATION_DIGITS
    val keySuffix = ".json"
    val conditionalPut = "IF_NONE_MATCH_STAR"
    val creationAnchor = "SIGNED_TEST_RUN_ACTIVATION_CREATION"
    val routingAndRetention = "RETAINED_CATALOG_READER"

    fun initialWriterRegistryBytes(): ByteArray = registryBytes.copyOf()

    internal fun initialWriterRegistry(): OfflineBootstrapRegistryV1 = registry.snapshot()

    internal fun initialApproverIds(): List<String> = registry.catalogWriter.catalogApproverIds.toList()

    internal fun requiredSignerPolicy(): InitialSignerPolicyV1 = InitialSignerPolicyV1(
        requiredSignerMode, requiredSignerThreshold, listOf(requiredSigner),
    )

    /** Local graph/descriptors only: no parser, JSON, hashing, crypto, checkout or provider work. */
    internal fun requireRetained(
        selectedPools: VersionBoundPersistencePools,
        selectedReader: VersionBoundCatalogReadbackConfigurationV1,
        selectedJournal: TestOwnerDeleteJournalConfigurationV1,
    ) {
        require(selectedPools === pools && selectedReader === reader && selectedJournal === journal && pools.catalogCoordinator === coordinator) {
            INVALID_TEST_ACTIVATION_CONFIGURATION
        }
        require(pools.epochRotation === rotation && rotation?.descriptor() === rotationDescriptor) { INVALID_TEST_ACTIVATION_CONFIGURATION }
        val current = pools.descriptors()
        require(current.size == descriptors.size && current.indices.all { current[it] === descriptors[it] }) { INVALID_TEST_ACTIVATION_CONFIGURATION }
    }

    /** Fresh serialization output only; no caller JSON or asserted signer-policy input is accepted. */
    internal fun inventory(): JsonObject = buildJsonObject {
        put("profileVersion", profileVersion)
        put("profile", profile)
        put("operation", operation)
        put("catalogWriterGenerationId", catalogWriterGenerationId)
        put("initialWriterRegistry", artifact(initialWriterRegistrySha256, initialWriterRegistryByteCount))
        put("signAuthority", authority(signAuthority))
        put("putAuthority", authority(putAuthority))
        put("initialApproverIds", JsonArray(initialApproverIds().map(::JsonPrimitive)))
        put(
            "requiredSignerPolicy",
            buildJsonObject {
                val policy = requiredSignerPolicy()
                put("mode", policy.mode)
                put("threshold", policy.threshold)
                put(
                    "members",
                    JsonArray(policy.members.map { member ->
                        buildJsonObject {
                            put("keyId", member.keyId)
                            put("algorithmId", member.algorithmId)
                        }
                    }),
                )
            },
        )
        put(
            "signingKey",
            buildJsonObject {
                put("keyId", signingKey.keyId)
                put("keyArn", signingKey.keyArn)
                put("algorithmId", signingKey.algorithmId)
                put("publicKeySha256", publicKeySha256)
                put("publicKeyByteCount", publicKeyByteCount)
            },
        )
        put("predecessorPolicy", predecessorPolicy)
        put("approvalPolicy", approvalInventory())
        put("limits", limitsInventory())
        put("transport", transportInventory())
    }

    private fun approvalInventory(): JsonObject = buildJsonObject {
        put("input", approvalInput)
        put("count", approvalCount)
        put("eligibility", approvalEligibility)
        put("creatorMustApprove", creatorMustApprove)
    }

    private fun limitsInventory(): JsonObject = buildJsonObject {
        put("totalAttemptMillis", totalAttemptMillis)
        put("sdkMaximumCallMillis", sdkMaximumCallMillis)
        put("sdkMaximumAttempts", sdkMaximumAttempts)
        put("maximumIntentBytes", maximumIntentBytes)
        put("maximumApprovalBytes", maximumApprovalBytes)
        put("maximumReadbackRounds", maximumReadbackRounds)
    }

    private fun transportInventory(): JsonObject = buildJsonObject {
        put("coordinatorRole", coordinatorRole.name)
        put("credentialSelection", credentialSelection)
        put("putLocationRole", putLocationRole)
        put("replicaPutAllowed", replicaPutAllowed)
        put("keyPrefix", keyPrefix)
        put("generationDigits", generationDigits)
        put("keySuffix", keySuffix)
        put("conditionalPut", conditionalPut)
        put("creationAnchor", creationAnchor)
        put("routingAndRetention", routingAndRetention)
    }

    override fun toString(): String = "VersionBoundTestActivationConfigurationV1(cold-TEST-policy,redacted,no-authority)"

    companion object {
        fun fromRetained(
            pools: VersionBoundPersistencePools,
            reader: VersionBoundCatalogReadbackConfigurationV1,
            journal: TestOwnerDeleteJournalConfigurationV1,
            signingKey: CatalogSigningKeyV1,
            initialWriterRegistryBytes: ByteArray,
            totalAttemptMillis: Long,
            activeFirstCut: me.manga.kira.backend.complaint.domain.reconciliation.TestActiveFirstCutInputV1? = null,
        ): VersionBoundTestActivationConfigurationV1 {
            requireConnectionFree()
            require((pools.epochRotation == null) == (activeFirstCut == null)) { INVALID_TEST_ACTIVATION_CONFIGURATION }
            activeFirstCut?.let {
                me.manga.kira.backend.complaint.infrastructure.reconciliation.VersionBoundTestActiveFirstCutV1.requireInput(it)
                require(journal.registeredAdminBatchDelete) { INVALID_TEST_ACTIVATION_CONFIGURATION }
                checkNotNull(pools.epochRotation).requireUnchangedConfiguration()
            }
            val (checked, spki) = checkIndependentInputs(reader, journal, signingKey, initialWriterRegistryBytes, totalAttemptMillis)
            return VersionBoundTestActivationConfigurationV1(pools, reader, journal, signingKey, checked, spki, totalAttemptMillis).also {
                it.requireRetained(pools, reader, journal)
            }
        }

        /** Shared cold validation before intake acquires secrets; the actual retained owner rechecks the same raw inputs. */
        internal fun checkIndependentInputs(
            reader: VersionBoundCatalogReadbackConfigurationV1,
            journal: TestOwnerDeleteJournalConfigurationV1,
            signingKey: CatalogSigningKeyV1,
            initialWriterRegistryBytes: ByteArray,
            totalAttemptMillis: Long,
        ): Pair<CheckedOfflineBootstrapRegistry, ByteArray> {
            requireConnectionFree()
            require(totalAttemptMillis in 1..30_000) { INVALID_TEST_ACTIVATION_CONFIGURATION }
            require(initialWriterRegistryBytes.size in 1..OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES) { INVALID_TEST_ACTIVATION_CONFIGURATION }
            val rawRegistry = initialWriterRegistryBytes.copyOf()
            val currentBytes = reader.currentBundleBytes()
            val policy = reader.chainPolicy
            val checked = OfflineBootstrapRegistryVerifier.verify(rawRegistry, currentBytes, policy.trustBundlePolicy)
            val registry = checked.registry
            requireWriter(registry, reader, journal)
            requireSeparation(registry, journal)

            // The initial registry is historical. Select only from independently verified CURRENT trust,
            // never from registry.requiredSignerPolicy or an alleged accepted/current-head observation.
            val current = OfflineTrustBundleVerifier.verify(currentBytes, policy.trustBundlePolicy).body
            val member = requireNotNull(current.signers.singleOrNull { it.keyId == signingKey.keyId }) { INVALID_TEST_ACTIVATION_CONFIGURATION }
            require(member.algorithmId == signingKey.algorithmId) { INVALID_TEST_ACTIVATION_CONFIGURATION }
            val spki = signingKey.publicKey().encoded
            require(spki.contentEquals(Base64.getDecoder().decode(member.publicKeySpkiBase64))) {
                INVALID_TEST_ACTIVATION_CONFIGURATION
            }
            return checked to spki
        }

        private fun requireWriter(
            registry: OfflineBootstrapRegistryV1,
            reader: VersionBoundCatalogReadbackConfigurationV1,
            journal: TestOwnerDeleteJournalConfigurationV1,
        ) {
            val writer = journal.declaration().writer
            require(
                registry.databaseIdentity == writer.databaseIdentity && registry.restoreIdentity == writer.restoreIdentity &&
                    registry.eventWriter.generationId == writer.generationId && registry.eventWriter.databaseIdentity == writer.databaseIdentity &&
                    registry.eventWriter.restoreIdentity == writer.restoreIdentity &&
                    registry.catalogWriter.generationId in reader.chainPolicy.currentWriterGenerationIds,
            ) { INVALID_TEST_ACTIVATION_CONFIGURATION }
        }

        private fun requireSeparation(registry: OfflineBootstrapRegistryV1, journal: TestOwnerDeleteJournalConfigurationV1) {
            val catalog = registry.catalogWriter
            val authorities = journal.declaration().authorities
            val roles = listOf(authorities.ordinary, authorities.sealTerminal, authorities.recovery)
            val catalogPrincipals = listOf(catalog.signAuthority, catalog.putAuthority)
            val roleIds = catalogPrincipals.map { it.principalId } + roles.map { it.roleId }
            val credentialIds = roles.map { it.credentialId }
            val policyIds = catalogPrincipals.map { it.policy.policyId } + roles.map { it.policy.policyId }
            require(roleIds.distinct().size == roleIds.size && credentialIds.none { it in roleIds }) { INVALID_TEST_ACTIVATION_CONFIGURATION }
            require(policyIds.distinct().size == policyIds.size && authorities.isolation.administrationPolicy.policyId !in policyIds) {
                INVALID_TEST_ACTIVATION_CONFIGURATION
            }
            val administrators = listOf(authorities.isolation.bucketAdministratorId, authorities.isolation.kmsAdministratorId)
            require(roleIds.none { it in administrators }) { INVALID_TEST_ACTIVATION_CONFIGURATION }
        }

        private fun artifact(hash: String, byteCount: Int): JsonObject = buildJsonObject {
            put("sha256", hash)
            put("byteCount", byteCount)
        }

        private fun authority(value: InitialCatalogPrincipalV1): JsonObject = buildJsonObject {
            put("principalId", value.principalId)
            put(
                "policy",
                buildJsonObject {
                    put("policyId", value.policy.policyId)
                    put("version", value.policy.version)
                    put("sha256", value.policy.sha256)
                },
            )
        }
    }
}

private const val INVALID_TEST_ACTIVATION_CONFIGURATION = "Invalid version-bound TEST activation configuration"
