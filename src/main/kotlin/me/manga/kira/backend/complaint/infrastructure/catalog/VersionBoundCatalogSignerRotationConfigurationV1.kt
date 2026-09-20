package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.InitialCatalogPrincipalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapRegistryV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.CatalogSigningKeyV1
import java.util.Base64

/** Public immutable routing pins. Neither a KMS ARN nor a role reference authenticates the supplied AWS session. */
internal class CatalogSignerRotationKeyV1(
    val keyId: String,
    val keyArn: String,
    val algorithmId: String,
    publicKeySpki: ByteArray,
    val publicKeySha256: String,
) {
    internal val signingKey = CatalogSigningKeyV1(keyId, keyArn, algorithmId, publicKeySpki, publicKeySha256)

    init {
        requireConnectionFree()
        signingKey.publicKey() // Validate the exact canonical public material before retaining the cold graph.
    }

    internal fun inventory(): JsonObject = buildJsonObject {
        put("keyId", keyId)
        put("keyArn", keyArn)
        put("algorithmId", algorithmId)
        put("publicKeySha256", publicKeySha256)
        put("publicKeyByteCount", OfflineTrustBundleProtocol.PUBLIC_KEY_BYTES)
    }

    override fun toString(): String = "CatalogSignerRotationKeyV1(public-pins,redacted,no-authority)"
}

/** Initial old/new pair only. The existing raw ID/time approval array remains the operation input, not a new approval protocol. */
internal class CatalogSignerRotationDeploymentV1(
    val catalogWriterGenerationId: String,
    val signAuthority: InitialCatalogPrincipalV1,
    keys: List<CatalogSignerRotationKeyV1>,
    val totalAttemptMillis: Long,
) {
    private val retainedKeys = keys.toList()

    init {
        require(OfflineBootstrapGrammar.uuidV4(catalogWriterGenerationId))
        require(OfflineBootstrapGrammar.referenceId(signAuthority.principalId))
        require(OfflineBootstrapGrammar.referenceId(signAuthority.policy.policyId) && signAuthority.policy.version > 0)
        require(OfflineBootstrapGrammar.sha256(signAuthority.policy.sha256))
        require(retainedKeys.size == 2 && retainedKeys.map { it.keyId }.distinct().size == 2)
        require(retainedKeys.map { it.keyArn }.distinct().size == 2)
        require(totalAttemptMillis in 1..30_000) // No heartbeat or renewed stage deadline is introduced by authoring.
    }

    internal fun keys(): List<CatalogSignerRotationKeyV1> = retainedKeys.toList()

    internal fun requireReader(reader: VersionBoundCatalogReadbackConfigurationV1) {
        requireConnectionFree()
        require(!reader.projectedCurrent && catalogWriterGenerationId in reader.chainPolicy.currentWriterGenerationIds)
        val current = OfflineTrustBundleVerifier.verify(reader.currentBundleBytes(), reader.chainPolicy.trustBundlePolicy).body
        require(current.bootstrapAuthority.catalogWriterGenerationId == catalogWriterGenerationId && current.minimumCatalogHeadGeneration == 1L)
        require(current.bootstrapAuthority.requiredSigner.keyId == retainedKeys[0].keyId)
        retainedKeys.forEach { key ->
            val signer = current.signers.single { it.keyId == key.keyId }
            require(signer.algorithmId == key.algorithmId && signer.publicKeySha256 == key.publicKeySha256)
            require(key.signingKey.publicKey().encoded.contentEquals(Base64.getDecoder().decode(signer.publicKeySpkiBase64)))
        }
    }

    internal fun requireRegistry(registry: OfflineBootstrapRegistryV1) {
        require(registry.catalogWriter.generationId == catalogWriterGenerationId && registry.catalogWriter.signAuthority == signAuthority)
        require(registry.catalogWriter.requiredSignerPolicy.members.single().keyId == retainedKeys[0].keyId)
    }

    internal fun inventory(): JsonObject = buildJsonObject {
        put("profileVersion", 1)
        put("profile", "INITIAL_SIGNER_ROTATION_AUTHOR")
        put("catalogWriterGenerationId", catalogWriterGenerationId)
        put("signPrincipalId", signAuthority.principalId)
        put("signPolicyId", signAuthority.policy.policyId)
        put("signPolicyVersion", signAuthority.policy.version)
        put("signPolicySha256", signAuthority.policy.sha256)
        put("orderedSigningKeys", JsonArray(retainedKeys.map { it.inventory() }))
        put("totalAttemptMillis", totalAttemptMillis)
        put("sdkMaximumCallMillis", 10_000)
        put("sdkMaximumAttempts", 1)
        put("credentialSelection", "EXPLICIT_ORDERED_SIGNER_SESSIONS")
        put("approvalInput", "EXISTING_CANONICAL_ID_TIME_ARRAY")
        put("publicationRoutingAndRetention", "RETAINED_CATALOG_READER")
    }

    override fun toString(): String = "CatalogSignerRotationDeploymentV1(immutable-old-new,redacted,no-authority)"
}

/** Exact cold D7 inventory. Construct before initial D/G1; no method adds a writer to an existing process. */
internal class VersionBoundCatalogSignerRotationConfigurationV1 private constructor(
    private val pools: VersionBoundPersistencePools,
    private val reader: VersionBoundCatalogReadbackConfigurationV1,
    internal val deployment: CatalogSignerRotationDeploymentV1,
) {
    private val coordinator = pools.catalogCoordinator
    private val descriptors = pools.descriptors()
    private val canonicalInventory = deployment.inventory()

    internal fun requireRetained(selectedPools: VersionBoundPersistencePools, selectedReader: VersionBoundCatalogReadbackConfigurationV1) {
        require(selectedPools === pools && selectedReader === reader && pools.catalogCoordinator === coordinator)
        val current = pools.descriptors()
        require(current.size == descriptors.size && current.indices.all { current[it] === descriptors[it] })
    }

    internal fun inventory(): JsonObject = canonicalInventory

    override fun toString(): String = "VersionBoundCatalogSignerRotationConfigurationV1(cold-D7,redacted,no-authority)"

    companion object {
        fun fromRetained(
            pools: VersionBoundPersistencePools,
            reader: VersionBoundCatalogReadbackConfigurationV1,
            deployment: CatalogSignerRotationDeploymentV1,
        ): VersionBoundCatalogSignerRotationConfigurationV1 {
            requireConnectionFree()
            deployment.requireReader(reader)
            return VersionBoundCatalogSignerRotationConfigurationV1(pools, reader, deployment)
        }
    }
}
