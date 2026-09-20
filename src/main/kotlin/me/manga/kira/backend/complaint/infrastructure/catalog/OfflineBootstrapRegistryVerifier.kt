package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CheckedOfflineBootstrapRegistry
import me.manga.kira.backend.complaint.domain.catalog.InitialLiveRangeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapAuthorityV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapRegistryV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundlePolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.requireOfflineTrustBundle
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser

/** Real root verification precedes binding; no overload accepts a caller-constructed checked-policy token. */
internal object OfflineBootstrapRegistryVerifier {
    private val emptySealHistorySha256 = Sha256.hexUtf8("[]")

    fun verify(registryBytes: ByteArray, trustBundleBytes: ByteArray, policy: OfflineTrustBundlePolicy): CheckedOfflineBootstrapRegistry {
        requireOfflineTrustBundle(registryBytes.size <= OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES, OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        val input = registryBytes.copyOf()
        val bundle = OfflineTrustBundleVerifier.verify(trustBundleBytes, policy)
        val registry = OfflineTrustBundleParser.parseRegistry(input)
        val registryHash = Sha256.hex(input)
        validateClaims(registry, bundle.body.bootstrapAuthority)
        return CheckedOfflineBootstrapRegistry(registry, input, registryHash, bundle.envelopeSha256, bundle.body.version)
    }

    /** Validation only: caller-asserted authority cannot obtain a checked result or skip either raw API's root verification. */
    fun validateClaims(registry: OfflineBootstrapRegistryV1, authority: OfflineBootstrapAuthorityV1) {
        val canonical = CanonicalJson.canonicalize(OfflineBootstrapRegistryV1.serializer(), registry).toByteArray(Charsets.UTF_8)
        requireOfflineTrustBundle(
            Sha256.hex(canonical) == authority.initialWriterRegistrySha256,
            OfflineTrustBundleFailure.REGISTRY_HASH_MISMATCH,
        )
        requireOfflineTrustBundle(
            registry.schemaVersion == 1 && registry.canonicalizerId == CanonicalJson.CANON_VERSION,
            OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA,
        )
        requireOfflineTrustBundle(OfflineBootstrapGrammar.uuidV4(registry.databaseIdentity) && OfflineBootstrapGrammar.uuidV4(registry.restoreIdentity))
        val catalog = registry.catalogWriter
        val event = registry.eventWriter
        requireOfflineTrustBundle(catalog.generationId == authority.catalogWriterGenerationId && catalog.registration == "ACTIVE")
        requireOfflineTrustBundle(catalog.catalogApproverIds == authority.catalogApproverIds)
        val signerPolicy = catalog.requiredSignerPolicy
        requireOfflineTrustBundle(signerPolicy.mode == "SINGLE" && signerPolicy.threshold == "ALL_MEMBERS")
        requireOfflineTrustBundle(signerPolicy.members.size == 1 && signerPolicy.members.single() == authority.requiredSigner)
        requireOfflineTrustBundle(OfflineBootstrapGrammar.uuidV4(event.generationId) && event.generationId != catalog.generationId)
        requireOfflineTrustBundle(event.databaseIdentity == registry.databaseIdentity && event.restoreIdentity == registry.restoreIdentity)
        requireOfflineTrustBundle(event.registration == "ACTIVE")
        validateLiveRange(event.liveRange, event.generationId)

        // Check ALL four role identities together, not just catalog/event pairs independently.
        val roles = listOf(
            catalog.putAuthority.principalId,
            catalog.signAuthority.principalId,
            event.liveRange.ordinaryAuthority.roleId,
            event.liveRange.sealTerminalAuthority.roleId,
        )
        requireOfflineTrustBundle(roles.all(OfflineBootstrapGrammar::referenceId) && roles.distinct().size == 4)
        val credentials = listOf(event.liveRange.ordinaryAuthority.credentialId, event.liveRange.sealTerminalAuthority.credentialId)
        requireOfflineTrustBundle(credentials.all(OfflineBootstrapGrammar::referenceId) && credentials.distinct().size == 2)
        requireOfflineTrustBundle(credentials.none { it in roles })
        val policies = listOf(
            catalog.putAuthority.policy,
            catalog.signAuthority.policy,
            event.liveRange.ordinaryAuthority.policy,
            event.liveRange.sealTerminalAuthority.policy,
        )
        requireOfflineTrustBundle(policies.map { it.policyId }.distinct().size == 4)
        policies.forEach {
            requireOfflineTrustBundle(OfflineBootstrapGrammar.referenceId(it.policyId) && it.version > 0 && OfflineBootstrapGrammar.sha256(it.sha256))
        }
    }

    private fun validateLiveRange(range: InitialLiveRangeV1, generationId: String) {
        requireOfflineTrustBundle(range.scope.kind == "LIVE" && range.scope.id == OfflineBootstrapGrammar.LIVE_SCOPE_ID && range.state == "OPEN")
        requireOfflineTrustBundle(
            OfflineBootstrapGrammar.bucket(range.journalLocation.bucket) && OfflineBootstrapGrammar.account(range.journalLocation.accountId) &&
                OfflineBootstrapGrammar.region(range.journalLocation.region),
        )
        requireOfflineTrustBundle(range.ordinaryPrefix == OfflineBootstrapGrammar.ordinaryPrefix(generationId))
        requireOfflineTrustBundle(range.sealTerminalPrefix == OfflineBootstrapGrammar.sealTerminalPrefix(generationId))
        requireOfflineTrustBundle(OfflineBootstrapGrammar.referenceId(range.routingKeyId) && OfflineBootstrapGrammar.referenceId(range.encryptionKeyId))
        requireOfflineTrustBundle(OfflineBootstrapGrammar.sha256(range.configurationSha256) && range.firstEpoch == 1L)
        requireOfflineTrustBundle(range.sealHistory.count == 0L && range.sealHistory.sha256 == emptySealHistorySha256)
    }
}
