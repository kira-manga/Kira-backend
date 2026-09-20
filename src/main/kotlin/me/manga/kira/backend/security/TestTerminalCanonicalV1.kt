package me.manga.kira.backend.security

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEpochSealV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationManifestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPurgeV1

/** Fixed typed canonicalization and exact retained-route restore. No key port, SQL or publication authority. */
internal class TestTerminalCanonicalV1(
    private val owner: Any,
    journal: TestOwnerDeleteJournalConfigurationV1,
    private val routing: TestTerminalRoutingV1,
) {
    private val json = TestTerminalJsonV1(journal)
    private val maximumBytes = minOf(journal.declaration().limits.decoder.maximumPlaintextBytes, TestTerminalProfileV1.MAX_PLAINTEXT_BYTES)

    fun canonicalizeInstallationManifest(
        value: TestTerminalInstallationManifestV1,
        attempt: TestTerminalAttemptV1,
        selectedRoutingKeyId: String?,
    ): TestTerminalContentV1 = testTerminalCodecBoundary {
        val kind = TestTerminalCodecKindV1.INSTALLATION_MANIFEST
        checkAttempt(attempt, kind)
        val route = select(routing.deriveInstallationManifest(value), selectedRoutingKeyId)
        val selected = TestTerminalInstallationManifestV1.create(
            value.context().copy(eventId = route.journalId), value.chunkIndex, value.chunkCount, value.installationsSha256, value.entries(),
        )
        withTestTerminalBuffers { buffers ->
            val bytes = buffers.own(json.encodeInstallationManifest(selected))
            requireTestTerminalCodec(bindBytes(kind, bytes, route.routingKeyId).route == route)
            content(kind, route, bytes, attempt)
        }
    }

    fun canonicalizePurge(
        value: TestTerminalPurgeV1,
        attempt: TestTerminalAttemptV1,
        selectedRoutingKeyId: String?,
    ): TestTerminalContentV1 = testTerminalCodecBoundary {
        val kind = TestTerminalCodecKindV1.TEST_RUN_PURGE
        checkAttempt(attempt, kind)
        val route = select(routing.derivePurge(value), selectedRoutingKeyId)
        val selected = TestTerminalPurgeV1.create(
            value.context().copy(eventId = route.journalId), value.finalOrdinaryEpoch, value.finalOrdinarySeal,
            value.preTerminalSeals, value.preTerminalInventory, value.installationManifest,
        )
        withTestTerminalBuffers { buffers ->
            val bytes = buffers.own(json.encodePurge(selected))
            requireTestTerminalCodec(bindBytes(kind, bytes, route.routingKeyId).route == route)
            content(kind, route, bytes, attempt)
        }
    }

    fun canonicalizeEpochSeal(
        value: TestTerminalEpochSealV1,
        attempt: TestTerminalAttemptV1,
        selectedRoutingKeyId: String?,
    ): TestTerminalContentV1 = testTerminalCodecBoundary {
        val kind = TestTerminalCodecKindV1.EPOCH_SEAL
        checkAttempt(attempt, kind)
        val route = select(routing.deriveEpochSeal(value), selectedRoutingKeyId)
        val selected = value.copy(sealId = route.journalId)
        withTestTerminalBuffers { buffers ->
            val bytes = buffers.own(json.encodeEpochSeal(selected))
            requireTestTerminalCodec(bindBytes(kind, bytes, route.routingKeyId).route == route)
            content(kind, route, bytes, attempt)
        }
    }

    fun restoreCanonical(
        kind: TestTerminalCodecKindV1,
        canonicalBytes: ByteArray,
        selectedRoutingKeyId: String,
        expectedObjectKey: String,
        expectedCanonicalSha256: String,
        attempt: TestTerminalAttemptV1,
    ): TestTerminalContentV1 = testTerminalCodecBoundary {
        checkAttempt(attempt, kind)
        requireTestTerminalCodec(canonicalBytes.size in 1..maximumBytes, TestTerminalCodecFailureV1.LIMIT_EXCEEDED)
        requireTestTerminalCodec(OfflineBootstrapGrammar.sha256(expectedCanonicalSha256))
        withTestTerminalBuffers { buffers ->
            val bytes = buffers.own(canonicalBytes.copyOf())
            requireTestTerminalCodec(Sha256.hex(bytes) == expectedCanonicalSha256)
            val binding = bindBytes(kind, bytes, selectedRoutingKeyId)
            requireTestTerminalCodec(binding.route.objectKey == expectedObjectKey)
            content(kind, binding.route, bytes, attempt)
        }
    }

    internal fun checkAttempt(attempt: TestTerminalAttemptV1, kind: TestTerminalCodecKindV1) {
        requireConnectionFree()
        attempt.requireOwner(owner, kind)
        attempt.remainingMillis(1)
    }

    /** Canonical storage is private, yet every use independently rebinds exact bytes/hash/kind/retained route. */
    internal fun checkedContent(content: TestTerminalContentV1, buffers: TestTerminalBuffersV1): Checked {
        requireConnectionFree()
        requireTestTerminalCodec(content.belongsTo(owner))
        requireTestTerminalCodec(content.byteCount in 1..maximumBytes, TestTerminalCodecFailureV1.LIMIT_EXCEEDED)
        val bytes = buffers.own(content.canonicalBytes())
        requireTestTerminalCodec(content.canonicalSha256 == Sha256.hex(bytes))
        val binding = bindBytes(content.kind, bytes, content.route.routingKeyId)
        requireTestTerminalCodec(binding.route == content.route)
        return Checked(bytes, binding)
    }

    /** Only these three parsers and descriptor grammars are possible; restored IDs are never repaired. */
    internal fun bindBytes(kind: TestTerminalCodecKindV1, bytes: ByteArray, selectedId: String): Binding = when (kind) {
        TestTerminalCodecKindV1.INSTALLATION_MANIFEST -> {
            val value = json.installationManifest(bytes)
            val route = select(routing.deriveInstallationManifest(value), selectedId)
            requireTestTerminalCodec(value.eventId == route.journalId)
            Binding(kind, route, value.publicationEpoch, value.publicationEpoch)
        }
        TestTerminalCodecKindV1.TEST_RUN_PURGE -> {
            val value = json.purge(bytes)
            val route = select(routing.derivePurge(value), selectedId)
            requireTestTerminalCodec(value.eventId == route.journalId)
            Binding(kind, route, value.publicationEpoch, value.publicationEpoch)
        }
        TestTerminalCodecKindV1.EPOCH_SEAL -> {
            val value = json.epochSeal(bytes)
            val route = select(routing.deriveEpochSeal(value), selectedId)
            requireTestTerminalCodec(value.sealId == route.journalId)
            Binding(kind, route, value.epochStartInclusive, value.epochEndInclusive)
        }
    }

    private fun select(routes: TestTerminalRoutesV1, selectedId: String?): TestTerminalRouteV1 {
        if (selectedId == null) return routes.active
        return routes.candidates().singleOrNull { it.routingKeyId == selectedId }
            ?: throw TestTerminalCodecExceptionV1(TestTerminalCodecFailureV1.INVALID_INPUT)
    }

    private fun content(
        kind: TestTerminalCodecKindV1,
        route: TestTerminalRouteV1,
        bytes: ByteArray,
        attempt: TestTerminalAttemptV1,
    ): TestTerminalContentV1 {
        attempt.remainingMillis(1)
        val content = TestTerminalContentV1(owner, kind, route, bytes)
        return runCatching {
            attempt.remainingMillis(1)
            content
        }.getOrElse { failure ->
            content.close()
            throw failure
        }
    }

    internal class Checked(val bytes: ByteArray, val binding: Binding)
    internal data class Binding(
        val kind: TestTerminalCodecKindV1,
        val route: TestTerminalRouteV1,
        val epochStartInclusive: Long,
        val epochEndInclusive: Long,
    )

    override fun toString(): String = "TestTerminalCanonicalV1(portless,redacted,no-authority)"
}
