package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalBundleV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryContext
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryReducer
import me.manga.kira.backend.complaint.domain.catalog.CatalogRestoreInventoryV1
import me.manga.kira.backend.complaint.domain.catalog.CheckedOfflineCatalogInventoryChain
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogInventoryEnvelopeV2
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogInventoryManifestV2
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationManifestV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.requireOfflineTrustBundle
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogInventoryParser
import me.manga.kira.backend.complaint.parsing.catalog.ParsedOfflineCatalogGeneration

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

    private fun appendPrefix(
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

    private fun appendInventory(
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
