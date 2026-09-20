package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEpochSealV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalExceptionV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalFailureV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationManifestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPurgeV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSyntaxV1
import me.manga.kira.backend.complaint.domain.terminal.requireTestTerminal
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Actual immutable TEST routing consumer; local candidates only, never a prepared intent or publication authority. */
internal class TestTerminalRoutingV1 private constructor(
    journal: TestOwnerDeleteJournalConfigurationV1,
    private val keys: List<RoutingKey>,
    private val retainedOwner: TestOwnerDeleteJournalRoutingV1? = null,
) {
    private val declaration = journal.declaration()
    private val json = TestTerminalJsonV1(journal)
    private val writer = declaration.writer.generationId
    private val scope = journal.scope.id.toString()
    private val prefix = journal.sealTerminalPrefix

    fun descriptors(): List<VersionedSecretBinding> = keys.map { it.binding }

    fun deriveInstallationManifest(value: TestTerminalInstallationManifestV1): TestTerminalRoutesV1 =
        routes(Kind.INSTALLATION_MANIFEST, value.publicationEpoch, json.installationDescriptorSha256(value))

    fun derivePurge(value: TestTerminalPurgeV1): TestTerminalRoutesV1 =
        routes(Kind.PURGE, value.publicationEpoch, json.purgeDescriptorSha256(value))

    fun deriveEpochSeal(value: TestTerminalEpochSealV1): TestTerminalRoutesV1 =
        routes(Kind.SEAL, value.epochEndInclusive, json.sealDescriptorSha256(value))

    private fun routes(kind: Kind, epoch: Long, descriptor: String): TestTerminalRoutesV1 {
        val candidates = keys.map { key ->
            val keyId = key.binding.logicalKeyId
            val objectId = mac(key, kind, epoch, descriptor, true)
            val journalId = mac(key, kind, epoch, descriptor, false)
            TestTerminalRouteV1(keyId, "$prefix$epoch/$keyId/${kind.path}/$objectId.kjev", journalId)
        }
        return TestTerminalRoutesV1(candidates.single { it.routingKeyId == declaration.routing.activeKeyId }, candidates)
    }

    private fun mac(key: RoutingKey, kind: Kind, epoch: Long, descriptor: String, objectKey: Boolean): String {
        val domain = if (objectKey) kind.keyDomain else kind.idDomain
        val frame = TestTerminalFramesV1.bytes(listOf(domain, kind.wireKind, writer, "TEST", scope, epoch.toString(), key.binding.logicalKeyId, descriptor))
        try {
            val digest = if (retainedOwner != null) {
                requireTestTerminal(kind === Kind.SEAL, TestTerminalFailureV1.KEY_FAILURE)
                retainedOwner.epochSealMac(key.binding.logicalKeyId, epoch, descriptor, objectKey)
            } else Mac.getInstance("HmacSHA256").run {
                init(checkNotNull(key.secret))
                doFinal(frame)
            }
            return try {
                Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
            } finally {
                digest.fill(0)
            }
        } catch (_: GeneralSecurityException) {
            throw TestTerminalExceptionV1(TestTerminalFailureV1.KEY_FAILURE)
        } finally {
            frame.fill(0)
        }
    }

    override fun toString(): String = "TestTerminalRoutingV1(TEST,redacted,no-intent-or-provider-authority)"

    private class RoutingKey(val binding: VersionedSecretBinding, val secret: SecretKeySpec?)

    private enum class Kind(val wireKind: String, val path: String, val idDomain: String, val keyDomain: String) {
        INSTALLATION_MANIFEST(
            "INSTALLATION_MANIFEST", "installation-manifest", "kira-test-terminal-event-id-v1", "kira-test-terminal-object-key-v1",
        ),
        PURGE("TEST_RUN_PURGE", "test-run-purge", "kira-test-terminal-event-id-v1", "kira-test-terminal-object-key-v1"),
        SEAL("EPOCH_SEAL", "epoch-seal", "kira-test-epoch-seal-id-v1", "kira-test-epoch-seal-object-key-v1"),
    }

    companion object {
        /** Retained production-key bridge is deliberately seal-only; dormant event fixtures are unchanged. */
        internal fun fromRetained(owner: TestOwnerDeleteJournalRoutingV1): TestTerminalRoutingV1 =
            TestTerminalRoutingV1(owner.journalConfiguration, owner.descriptors().map { RoutingKey(it, null) }, owner)

        fun fromAcquired(journal: TestOwnerDeleteJournalConfigurationV1, secrets: List<AcquiredVersionedSecret>): TestTerminalRoutingV1 {
            val inputs = TestTerminalSyntaxV1.snapshot(secrets, 4).sortedBy { it.descriptor.logicalKeyId }
            val declared = journal.declaration().routing.keys
            requireTestTerminal(inputs.isNotEmpty() && inputs.size == declared.size, TestTerminalFailureV1.KEY_FAILURE)
            inputs.zip(declared).forEach { (input, expected) ->
                val binding = input.descriptor
                requireTestTerminal(
                    binding.family == SecretMaterialFamily.COMPLAINT_JOURNAL_ROUTING && binding.purpose == SecretMaterialPurpose.HMAC_SHA256 &&
                        binding.logicalKeyId == expected.keyId && binding.version == expected.secret,
                    TestTerminalFailureV1.KEY_FAILURE,
                )
            }
            val keys = inputs.map { input ->
                input.useMaterial { bytes ->
                    requireTestTerminal(bytes.size in 32..128, TestTerminalFailureV1.KEY_FAILURE)
                    RoutingKey(input.descriptor, SecretKeySpec(bytes, "HmacSHA256"))
                }
            }
            requireDistinctEffectiveKeys(keys)
            return TestTerminalRoutingV1(journal, keys)
        }

        private fun requireDistinctEffectiveKeys(keys: List<RoutingKey>) {
            val comparisons = ArrayList<ComplaintAdmissionKey>(keys.size)
            try {
                keys.forEach { key ->
                    val bytes = checkNotNull(key.secret).encoded
                    val comparison = try {
                        ComplaintAdmissionKey(key.binding.logicalKeyId, bytes)
                    } finally {
                        bytes.fill(0)
                    }
                    comparisons.add(comparison)
                    requireTestTerminal(comparisons.dropLast(1).none { it.sameSecret(comparison) }, TestTerminalFailureV1.KEY_FAILURE)
                }
            } catch (_: IllegalArgumentException) {
                throw TestTerminalExceptionV1(TestTerminalFailureV1.KEY_FAILURE)
            } finally {
                comparisons.forEach { it.destroy() }
            }
        }
    }
}

/** Declaration-selected active candidate plus every retained-key candidate; neither has been committed. */
internal class TestTerminalRoutesV1(val active: TestTerminalRouteV1, candidates: List<TestTerminalRouteV1>) {
    private val stored = TestTerminalSyntaxV1.snapshot(candidates, 4)
    init { requireTestTerminal(stored.isNotEmpty() && stored.count { it == active } == 1) }
    fun candidates(): List<TestTerminalRouteV1> = stored.toList()
    override fun toString(): String = "TestTerminalRoutesV1(redacted,local-only)"
}

/** journalId is the payload's eventId or sealId; the independently derived object-key ID remains in objectKey. */
internal data class TestTerminalRouteV1(val routingKeyId: String, val objectKey: String, val journalId: String) {
    override fun toString(): String = "TestTerminalRouteV1(redacted,local-only)"
}
