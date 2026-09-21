package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalBundleV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryContext
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryReducer
import me.manga.kira.backend.complaint.domain.catalog.CatalogRestoreInventoryV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogRotationState
import me.manga.kira.backend.complaint.domain.catalog.CheckedOfflineCatalogInventoryChain
import me.manga.kira.backend.complaint.domain.catalog.CheckedOfflineCatalogTestRunActivationChain
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogInventoryEnvelopeV2
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogInventoryManifestV2
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationManifestV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationManifestV3
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogTestRunActivationProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.requireOfflineTrustBundle
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogInventoryParser
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogTestRunActivationParser
import me.manga.kira.backend.complaint.parsing.catalog.ParsedOfflineCatalogGeneration
import me.manga.kira.backend.complaint.parsing.catalog.ParsedOfflineTestRunCatalogGeneration

/** Supplied-chain signature evidence only: no provider observation, accepted head, capture consistency or restore permission. */
internal object OfflineCatalogInventoryChainVerifier {
    fun verifyInventoryChain(
        envelopes: Sequence<ByteArray>,
        initialBundleBytes: ByteArray,
        currentBundleBytes: ByteArray,
        policy: OfflineCatalogChainReaderPolicy,
    ): CheckedOfflineCatalogInventoryChain {
        val input = OfflineCatalogChainInput(envelopes, policy.limits)
        requireOfflineTrustBundle(input.hasNext())
        val state = OfflineCatalogChainAuthentication.bootstrap(input.next(), initialBundleBytes, currentBundleBytes, policy)
        var inventory = CatalogRestoreInventoryV1(emptyList(), emptyList())
        var inventoryProfileStarted = false
        while (input.hasNext()) {
            val bytes = input.next()
            when (val parsed = OfflineCatalogInventoryParser.parse(bytes, policy.limits.maximumManifestRecords)) {
                is ParsedOfflineCatalogGeneration.RotationV1 -> {
                    requireOfflineTrustBundle(!inventoryProfileStarted)
                    appendPrefix(parsed.envelope, bytes, state, policy)
                }

                is ParsedOfflineCatalogGeneration.InventoryV2 -> {
                    inventory = appendInventory(parsed.envelope, bytes, state, inventory, policy)
                    inventoryProfileStarted = true
                }
            }
        }
        state.finish()
        return CheckedOfflineCatalogInventoryChain(state.tail, state.trust, state.rotation, input.encodedBytes, inventory)
    }

    /**
     * Exactly one final first-TEST activation, after any prefix already supported above. Expected
     * cold process declarations bind full D/J/N; only this actual raw fold establishes Stable(active).
     * This is still supplied signature evidence, never a projected/registered run or provider proof.
     */
    fun verifyTestRunActivationChain(
        envelopes: Sequence<ByteArray>,
        initialBundleBytes: ByteArray,
        currentBundleBytes: ByteArray,
        policy: OfflineCatalogChainReaderPolicy,
        expected: CatalogTestRunActivationCanonicalV3,
    ): CheckedOfflineCatalogTestRunActivationChain {
        requireOfflineTrustBundle(
            initialBundleBytes.size in 1..OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES &&
                currentBundleBytes.size in 1..OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES,
            OfflineTrustBundleFailure.LIMIT_EXCEEDED,
        )
        val initial = initialBundleBytes.copyOf()
        val current = currentBundleBytes.copyOf()
        expected.requireReader(initial, current, policy)
        val input = OfflineCatalogChainInput(envelopes, policy.limits)
        requireOfflineTrustBundle(input.hasNext())
        val state = OfflineCatalogChainAuthentication.bootstrap(input.next(), initial, current, policy)
        expected.requireTrust(state.trust)
        var inventory = CatalogRestoreInventoryV1(emptyList(), emptyList())
        var inventoryProfileStarted = false
        var activation: OfflineCatalogTestRunActivationManifestV3? = null
        var activationManifestBytes: ByteArray? = null
        var activationEnvelopeBytes: ByteArray? = null
        while (input.hasNext()) {
            // Do not drop a suffix or turn count1 into unsupported repeated lifecycle history.
            requireOfflineTrustBundle(activation == null)
            val bytes = input.next()
            when (val parsed = OfflineCatalogTestRunActivationParser.parseGeneration(bytes, policy.limits.maximumManifestRecords, policy.limits.maximumEnvelopeBytes)) {
                is ParsedOfflineTestRunCatalogGeneration.Prefix -> when (val prefix = parsed.generation) {
                    is ParsedOfflineCatalogGeneration.RotationV1 -> {
                        requireOfflineTrustBundle(!inventoryProfileStarted)
                        appendPrefix(prefix.envelope, bytes, state, policy)
                    }

                    is ParsedOfflineCatalogGeneration.InventoryV2 -> {
                        inventory = appendInventory(prefix.envelope, bytes, state, inventory, policy)
                        inventoryProfileStarted = true
                    }
                }

                is ParsedOfflineTestRunCatalogGeneration.ActivationV3 -> {
                    val manifest = parsed.envelope.manifest
                    expected.requireManifest(manifest)
                    requireOfflineTrustBundle(manifest.restoreInventory == inventory)
                    val manifestBytes = CanonicalJson.canonicalize(OfflineCatalogTestRunActivationManifestV3.serializer(), manifest).toByteArray(Charsets.UTF_8)
                    requireOfflineTrustBundle(
                        manifestBytes.size in 1..minOf(policy.limits.maximumEnvelopeBytes, OfflineCatalogTestRunActivationProtocol.MAX_DOCUMENT_BYTES),
                        OfflineTrustBundleFailure.LIMIT_EXCEEDED,
                    )
                    state.appendTestRunActivation(manifest, parsed.envelope.signatures, manifestBytes, bytes, policy)
                    activation = manifest
                    activationManifestBytes = manifestBytes
                    activationEnvelopeBytes = bytes
                }
            }
        }
        state.finish()
        val manifest = activation ?: throw OfflineTrustBundleException(OfflineTrustBundleFailure.INVALID_DOCUMENT)
        val stable = state.rotation as? CatalogRotationState.Stable ?: throw OfflineTrustBundleException(OfflineTrustBundleFailure.INVALID_DOCUMENT)
        return CheckedOfflineCatalogTestRunActivationChain(
            state.tail, state.trust, stable, input.encodedBytes, manifest,
            checkNotNull(activationManifestBytes), checkNotNull(activationEnvelopeBytes),
        )
    }

    internal fun appendPrefix(
        envelope: OfflineCatalogRotationEnvelopeV1,
        bytes: ByteArray,
        state: OfflineCatalogChainAuthentication,
        policy: OfflineCatalogChainReaderPolicy,
    ) {
        val manifest = envelope.manifest
        requireOfflineTrustBundle(
            envelope.schemaVersion == 1 && manifest.schemaVersion == 1 && manifest.canonicalizerId == CanonicalJson.CANON_VERSION,
            OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA,
        )
        requireOfflineTrustBundle(
            manifest.operation == OfflineCatalogChainProtocol.ROTATION_OVERLAP || manifest.operation == OfflineCatalogChainProtocol.ROTATION_ACTIVATE,
        )
        requireOfflineTrustBundle(manifest.restoreInventory == state.emptyInventory)
        val manifestBytes = CanonicalJson.canonicalize(OfflineCatalogRotationManifestV1.serializer(), manifest).toByteArray(Charsets.UTF_8)
        state.append(manifest.authenticationClaims(), envelope.signatures, manifestBytes, bytes, policy)
    }

    internal fun appendInventory(
        envelope: OfflineCatalogInventoryEnvelopeV2,
        bytes: ByteArray,
        state: OfflineCatalogChainAuthentication,
        previous: CatalogRestoreInventoryV1,
        policy: OfflineCatalogChainReaderPolicy,
    ): CatalogRestoreInventoryV1 {
        val manifest = envelope.manifest
        requireOfflineTrustBundle(
            envelope.schemaVersion == OfflineCatalogInventoryProtocol.SCHEMA_VERSION &&
                manifest.schemaVersion == OfflineCatalogInventoryProtocol.SCHEMA_VERSION &&
                manifest.profile == OfflineCatalogInventoryProtocol.PROFILE && manifest.canonicalizerId == CanonicalJson.CANON_VERSION,
            OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA,
        )
        val context = CatalogLogicalInventoryContext(
            state.registry.databaseIdentity,
            state.registry.restoreIdentity,
            state.oldestRestoreTimeEpochSecond,
            manifest.creation.createdAtEpochSecond,
            policy.limits.maximumManifestRecords,
        )
        val next = CatalogLogicalInventoryReducer.reduce(previous, manifest.restoreInventory, manifest.operation, manifest.inventoryDelta, context)
        next.sources.forEach { source ->
            val canonical = CanonicalJson.canonicalize(CatalogLogicalBundleV1.serializer(), source.bundle)
            requireOfflineTrustBundle(Sha256.hexUtf8(canonical) == source.bundleSha256, OfflineTrustBundleFailure.BUNDLE_HASH_MISMATCH)
        }
        val manifestBytes = CanonicalJson.canonicalize(OfflineCatalogInventoryManifestV2.serializer(), manifest).toByteArray(Charsets.UTF_8)
        state.append(manifest.authenticationClaims(), envelope.signatures, manifestBytes, bytes, policy)
        return next
    }
}
