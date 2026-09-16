package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.complaint.domain.catalog.CheckedOfflineCatalogRotationChain
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationManifestV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.requireOfflineTrustBundle
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser

/** Original closed schema-1 API. Sharing authentication never admits schema-2 inventory operations here. */
internal object OfflineCatalogRotationChainVerifier {
    fun verifyRotationChain(
        envelopes: Sequence<ByteArray>,
        initialBundleBytes: ByteArray,
        currentBundleBytes: ByteArray,
        policy: OfflineCatalogChainReaderPolicy,
    ): CheckedOfflineCatalogRotationChain {
        val input = OfflineCatalogChainInput(envelopes, policy.limits)
        requireOfflineTrustBundle(input.hasNext())
        val state = OfflineCatalogChainAuthentication.bootstrap(input.next(), initialBundleBytes, currentBundleBytes, policy)
        while (input.hasNext()) {
            val bytes = input.next()
            val envelope = OfflineTrustBundleParser.parseRotation(bytes, policy.limits.maximumManifestRecords)
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
        state.finish()
        return CheckedOfflineCatalogRotationChain(state.tail, state.trust, state.rotation, input.encodedBytes)
    }
}
