package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogChainTrustEvidence
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogRotationState
import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerPolicyV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogTailEvidence
import me.manga.kira.backend.complaint.domain.catalog.GenesisEmptyHeadV1
import me.manga.kira.backend.complaint.domain.catalog.GenesisEmptyHistoryV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapRegistryV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainLimits
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisCreationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisSignatureV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogInventoryManifestV2
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationManifestV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineRequiredSignerV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.requireOfflineTrustBundle
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import java.security.interfaces.RSAPublicKey

/** Shared raw bootstrap/authentication mechanics. Each closed reader separately validates its inventory and schema. */
internal class OfflineCatalogChainAuthentication private constructor(
    val registry: OfflineBootstrapRegistryV1,
    val emptyInventory: GenesisEmptyHeadV1,
    val history: GenesisEmptyHistoryV1,
    val oldestRestoreTimeEpochSecond: Long,
    private val keys: Map<OfflineRequiredSignerV1, RSAPublicKey>,
    val trust: CatalogChainTrustEvidence,
    initialTail: CatalogTailEvidence,
    private var latestApproval: Long,
    initialSigner: OfflineRequiredSignerV1,
) {
    var tail: CatalogTailEvidence = initialTail
        private set
    var rotation: CatalogRotationState = CatalogRotationState.Stable(initialSigner)
        private set
    private val seenSignerIds = mutableSetOf(initialSigner.keyId)

    fun append(
        claims: CatalogGenerationAuthenticationClaims,
        signatures: List<OfflineCatalogGenesisSignatureV1>,
        manifestBytes: ByteArray,
        envelopeBytes: ByteArray,
        policy: OfflineCatalogChainReaderPolicy,
    ) {
        validateClaims(claims, policy)
        val next = nextRotation(claims)
        verifySignatures(claims.requiredSignerPolicy, signatures, manifestBytes)
        if (next is CatalogRotationState.AwaitingActivation) seenSignerIds.add(next.next.keyId)
        rotation = next
        latestApproval = claims.approvals.maxOf { it.approvedAtEpochSecond }
        tail = CatalogTailEvidence(claims.generation, Sha256.hex(manifestBytes), Sha256.hex(envelopeBytes), claims.catalogWriterGenerationId)
    }

    fun finish() {
        requireOfflineTrustBundle(tail.generation >= trust.minimumHeadGeneration, OfflineTrustBundleFailure.POLICY_MISMATCH)
    }

    /** No signatures or state advancement: the fixed author reuses the exact reader's claims/chronology/ordered-key checks before Sign. */
    internal fun requireInitialOverlap(manifest: OfflineCatalogRotationManifestV1, policy: OfflineCatalogChainReaderPolicy) {
        requireOfflineTrustBundle(tail.generation == 1L && rotation is CatalogRotationState.Stable)
        requireOfflineTrustBundle(manifest.schemaVersion == 1 && manifest.operation == OfflineCatalogChainProtocol.ROTATION_OVERLAP)
        requireOfflineTrustBundle(manifest.restoreInventory == emptyInventory)
        val claims = manifest.authenticationClaims()
        validateClaims(claims, policy)
        requireOfflineTrustBundle(nextRotation(claims) is CatalogRotationState.AwaitingActivation)
    }

    /** Fixed immediate2->3 author precondition, reusing unchanged schema1/current-policy/chronology checks. */
    internal fun requireImmediateActivation(manifest: OfflineCatalogRotationManifestV1, policy: OfflineCatalogChainReaderPolicy) {
        requireOfflineTrustBundle(tail.generation == 2L && rotation is CatalogRotationState.AwaitingActivation)
        requireOfflineTrustBundle(manifest.schemaVersion == 1 && manifest.generation == 3L &&
            manifest.operation == OfflineCatalogChainProtocol.ROTATION_ACTIVATE && manifest.restoreInventory == emptyInventory)
        val claims = manifest.authenticationClaims()
        validateClaims(claims, policy)
        requireOfflineTrustBundle(nextRotation(claims) is CatalogRotationState.Stable)
    }

    private fun validateClaims(claims: CatalogGenerationAuthenticationClaims, policy: OfflineCatalogChainReaderPolicy) {
        requireOfflineTrustBundle(OfflineBootstrapGrammar.uuidV4(claims.operationToken))
        // Both raw readers bound the generation count before next(); the predecessor begins at one.
        requireOfflineTrustBundle(claims.generation == tail.generation + 1 && claims.previousEnvelopeSha256 == tail.envelopeSha256)
        requireOfflineTrustBundle(
            claims.initialTrustBundleEnvelopeSha256 == trust.initialBundleEnvelopeSha256,
            OfflineTrustBundleFailure.BUNDLE_HASH_MISMATCH,
        )
        requireOfflineTrustBundle(
            claims.initialWriterRegistry == registry && claims.history == history &&
                claims.oldestRestoreTimeEpochSecond == oldestRestoreTimeEpochSecond,
        )
        requireOfflineTrustBundle(
            claims.catalogWriterGenerationId == registry.catalogWriter.generationId &&
                claims.catalogWriterGenerationId in policy.currentWriterGenerationIds,
            OfflineTrustBundleFailure.POLICY_MISMATCH,
        )
        val created = claims.creation.createdAtEpochSecond
        requireOfflineTrustBundle(created in latestApproval..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND)
        val ids = claims.approvals.map { it.approverId }
        requireOfflineTrustBundle(ids.size == 2 && ids.distinct().size == 2 && ids == ids.sorted())
        requireOfflineTrustBundle(
            ids.all { it in registry.catalogWriter.catalogApproverIds && it in policy.currentApproverIds },
            OfflineTrustBundleFailure.POLICY_MISMATCH,
        )
        requireOfflineTrustBundle(claims.creation.creatorId in ids)
        requireOfflineTrustBundle(claims.approvals.all { it.approvedAtEpochSecond in created..CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND })
        requireOfflineTrustBundle(claims.requiredSignerPolicy.threshold == "ALL_MEMBERS")
        requireOfflineTrustBundle(
            claims.requiredSignerPolicy.members.all { it.algorithmId == OfflineTrustBundleProtocol.ALGORITHM_ID },
            OfflineTrustBundleFailure.UNSUPPORTED_ALGORITHM,
        )
        requireOfflineTrustBundle(claims.requiredSignerPolicy.members.all { it in keys }, OfflineTrustBundleFailure.POLICY_MISMATCH)
    }

    private fun nextRotation(claims: CatalogGenerationAuthenticationClaims): CatalogRotationState {
        val required = claims.requiredSignerPolicy
        return when (val current = rotation) {
            is CatalogRotationState.Stable -> when (claims.operation) {
                OfflineCatalogChainProtocol.ROTATION_OVERLAP -> {
                    requireOfflineTrustBundle(required.mode == "ROTATION_OVERLAP" && required.members.size == 2)
                    requireOfflineTrustBundle(required.members[0] == current.active && required.members[1].keyId !in seenSignerIds)
                    requireOfflineTrustBundle(seenSignerIds.size < OfflineTrustBundleProtocol.MAX_SIGNERS, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
                    CatalogRotationState.AwaitingActivation(current.active, required.members[1])
                }

                CatalogLogicalInventoryProtocol.REGISTER_SOURCE, CatalogLogicalInventoryProtocol.ADD_COPY -> {
                    requireOfflineTrustBundle(required.mode == "SINGLE" && required.members == listOf(current.active))
                    current
                }

                else -> throw OfflineTrustBundleException(OfflineTrustBundleFailure.INVALID_DOCUMENT)
            }

            is CatalogRotationState.AwaitingActivation -> {
                requireOfflineTrustBundle(
                    claims.operation == OfflineCatalogChainProtocol.ROTATION_ACTIVATE &&
                        required.mode == "SINGLE" && required.members == listOf(current.next),
                )
                CatalogRotationState.Stable(current.next)
            }
        }
    }

    private fun verifySignatures(required: CatalogSignerPolicyV1, signatures: List<OfflineCatalogGenesisSignatureV1>, manifestBytes: ByteArray) {
        requireOfflineTrustBundle(signatures.map { OfflineRequiredSignerV1(it.keyId, it.algorithmId) } == required.members)
        signatures.forEach { signature ->
            val key = keys.getValue(OfflineRequiredSignerV1(signature.keyId, signature.algorithmId))
            val bytes = OfflineTrustBundleCrypto.decodeBase64(
                signature.signatureBase64,
                OfflineTrustBundleProtocol.SIGNATURE_BYTES,
                OfflineTrustBundleFailure.INVALID_SIGNATURE,
            )
            OfflineTrustBundleCrypto.verify(key, OfflineCatalogGenesisCrypto.signatureFrame(signature.keyId, manifestBytes), bytes)
        }
    }

    companion object {
        fun bootstrap(
            input: ByteArray,
            initialBundleBytes: ByteArray,
            currentBundleBytes: ByteArray,
            policy: OfflineCatalogChainReaderPolicy,
        ): OfflineCatalogChainAuthentication {
            OfflineTrustBundleParser.parseChainGenesis(input, policy.limits.maximumManifestRecords)
            val checked = OfflineTrustBundleVerifier.verifyBootstrapEvidence(input, initialBundleBytes, currentBundleBytes, policy.trustBundlePolicy)
            val manifest = checked.genesis.manifest
            val current = checked.currentTrustBundle.body
            val trust = CatalogChainTrustEvidence(
                checked.initialTrustBundle.envelopeSha256,
                checked.currentTrustBundle.envelopeSha256,
                current.version,
                checked.genesis.envelopeSha256,
                current.minimumCatalogHeadGeneration,
            )
            val keys = current.signers.associate { signer ->
                val bytes = OfflineTrustBundleCrypto.decodeBase64(
                    signer.publicKeySpkiBase64,
                    OfflineTrustBundleProtocol.PUBLIC_KEY_BYTES,
                    OfflineTrustBundleFailure.INVALID_PUBLIC_KEY,
                )
                OfflineRequiredSignerV1(signer.keyId, signer.algorithmId) to OfflineTrustBundleCrypto.publicKey(bytes)
            }
            return OfflineCatalogChainAuthentication(
                manifest.initialWriterRegistry,
                manifest.restoreInventory,
                manifest.history,
                manifest.oldestRestoreTimeEpochSecond,
                keys,
                trust,
                CatalogTailEvidence(1, checked.genesis.manifestSha256, checked.genesis.envelopeSha256, manifest.catalogWriterGenerationId),
                manifest.approvals.maxOf { it.approvedAtEpochSecond },
                manifest.requiredSignerPolicy.members.single(),
            )
        }
    }
}

/** Internal claims view, never a public raw-reader input or caller-supplied verification result. */
internal data class CatalogGenerationAuthenticationClaims(
    val operation: String,
    val operationToken: String,
    val generation: Long,
    val previousEnvelopeSha256: String,
    val initialTrustBundleEnvelopeSha256: String,
    val catalogWriterGenerationId: String,
    val requiredSignerPolicy: CatalogSignerPolicyV1,
    val creation: OfflineCatalogGenesisCreationV1,
    val approvals: List<OfflineCatalogGenesisApprovalV1>,
    val oldestRestoreTimeEpochSecond: Long,
    val initialWriterRegistry: OfflineBootstrapRegistryV1,
    val history: GenesisEmptyHistoryV1,
)

internal fun OfflineCatalogRotationManifestV1.authenticationClaims(): CatalogGenerationAuthenticationClaims = CatalogGenerationAuthenticationClaims(
    operation,
    operationToken,
    generation,
    previousEnvelopeSha256,
    initialTrustBundleEnvelopeSha256,
    catalogWriterGenerationId,
    requiredSignerPolicy,
    creation,
    approvals,
    oldestRestoreTimeEpochSecond,
    initialWriterRegistry,
    history,
)

internal fun OfflineCatalogInventoryManifestV2.authenticationClaims(): CatalogGenerationAuthenticationClaims = CatalogGenerationAuthenticationClaims(
    operation,
    operationToken,
    generation,
    previousEnvelopeSha256,
    initialTrustBundleEnvelopeSha256,
    catalogWriterGenerationId,
    requiredSignerPolicy,
    creation,
    approvals,
    oldestRestoreTimeEpochSecond,
    initialWriterRegistry,
    history,
)

/** One iterator and one bounded raw snapshot at a time; no aggregate chain retention. */
internal class OfflineCatalogChainInput(envelopes: Sequence<ByteArray>, private val limits: OfflineCatalogChainLimits) {
    private val iterator = supplied { envelopes.iterator() }
    private var generations = 0
    var encodedBytes = 0L
        private set

    fun hasNext(): Boolean = supplied { iterator.hasNext() }

    fun next(): ByteArray {
        requireOfflineTrustBundle(generations < limits.maximumGenerations, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        val bytes = supplied { iterator.next() }
        val maximumBytes = if (generations == 0) {
            minOf(limits.maximumEnvelopeBytes, OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES)
        } else {
            limits.maximumEnvelopeBytes
        }
        requireOfflineTrustBundle(bytes.size <= maximumBytes, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        requireOfflineTrustBundle(bytes.size.toLong() <= limits.maximumEncodedBytes - encodedBytes, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        generations++
        encodedBytes += bytes.size.toLong()
        return bytes.copyOf()
    }
}

/** Only the untrusted iterator boundary is caught; validation failures retain their own safe codes. */
@Suppress("TooGenericExceptionCaught")
private fun <T> supplied(action: () -> T): T {
    try {
        return action()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        throw OfflineTrustBundleException(OfflineTrustBundleFailure.SUPPLIER_FAILURE)
    } catch (_: Exception) {
        throw OfflineTrustBundleException(OfflineTrustBundleFailure.SUPPLIER_FAILURE)
    }
}
