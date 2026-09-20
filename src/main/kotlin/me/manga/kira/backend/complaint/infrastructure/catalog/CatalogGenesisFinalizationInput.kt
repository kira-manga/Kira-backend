package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Genuine raw readback plus explicit legacy declarations or the retained process; never diagnostic-result promotion. */
internal class CatalogGenesisFinalizationInput private constructor(
    internal val readback: CatalogDualLocationVerifier.GenesisReadback,
    internal val binding: CatalogGenesisInitialLiveBinding,
    internal val finalizer: CatalogGenesisFinalizeAttemptV1? = null,
    internal val initialAuthor: CatalogSignerRotationInitialAuthorV1? = null,
) {
    private val manifest = readback.manifest().also { binding.requireMatchingRegistry(it.initialWriterRegistry) }
    private val token = UUID.fromString(manifest.operationToken)
    private val writer = UUID.fromString(manifest.catalogWriterGenerationId)
    private val envelopeHash = HexFormat.of().parseHex(readback.envelopeSha256)
    private val trustHash = HexFormat.of().parseHex(readback.currentTrustBundleSha256)
    private val databaseIdentity = UUID.fromString(manifest.initialWriterRegistry.databaseIdentity)
    private val restoreIdentity = UUID.fromString(manifest.initialWriterRegistry.restoreIdentity)
    private val eventWriter = UUID.fromString(manifest.initialWriterRegistry.eventWriter.generationId)
    private val implementationSchema = binding.implementationSchema
    private val desiredGeneration = binding.desiredGeneration
    private val desiredConfigurationHash = binding.desiredConfigurationHashBytes()
    private val version = readback.objectVersion
    private val retainUntil = Instant.ofEpochSecond(readback.retainUntilEpochSecond)
    private val primary = readback.primaryEvidenceBytes()
    private val primaryHash = hash(primary)
    private val replica = readback.replicaEvidenceBytes()
    private val replicaHash = hash(replica)
    val replayOnly: Boolean = readback.resume == GenesisResume.PROJECTED_REPLAY

    internal fun copyArguments(): Array<Any?> {
        requireConnectionFree()
        return arrayOf(version, Timestamp.from(retainUntil), primary.copyOf(), primaryHash.copyOf(), replica.copyOf(), replicaHash.copyOf())
    }

    internal fun controlArguments(): Array<Any?> {
        requireConnectionFree()
        return arrayOf(
            envelopeHash.copyOf(), trustHash.copyOf(), writer, token, databaseIdentity, restoreIdentity, eventWriter,
            implementationSchema, desiredGeneration, desiredConfigurationHash.copyOf(),
        )
    }

    internal fun observation(state: CatalogGenesisFinalizationState): CatalogGenesisFinalizationObservation =
        CatalogGenesisFinalizationObservation(token.toString(), HexFormat.of().formatHex(envelopeHash), state)

    override fun toString(): String = "CatalogGenesisFinalizationInput(fixed-G1-SQL,no-activation-or-restore-authority)"

    companion object {
        internal fun verified(
            readback: CatalogDualLocationVerifier.GenesisReadback,
            expected: CatalogGenesisInitialLiveBinding,
        ): CatalogGenesisFinalizationInput {
            requireConnectionFree()
            return CatalogGenesisFinalizationInput(readback, expected)
        }

        internal fun durable(
            readback: CatalogDualLocationVerifier.GenesisReadback,
            expected: CatalogGenesisInitialLiveBinding,
            finalizer: CatalogGenesisFinalizeAttemptV1,
        ): CatalogGenesisFinalizationInput {
            requireConnectionFree()
            finalizer.requireBarrier(readback, expected)
            return CatalogGenesisFinalizationInput(readback, expected, finalizer)
        }

        internal fun initialAuthor(
            readback: CatalogDualLocationVerifier.GenesisReadback,
            expected: CatalogGenesisInitialLiveBinding,
            original: CatalogSignerRotationInitialAuthorV1,
        ): CatalogGenesisFinalizationInput {
            requireConnectionFree()
            original.requireBarrier(readback, expected)
            return CatalogGenesisFinalizationInput(readback, expected, initialAuthor = original)
        }

        private fun hash(bytes: ByteArray): ByteArray = HexFormat.of().parseHex(Sha256.hex(bytes))
    }
}

internal enum class CatalogGenesisFinalizationState { COMPLETED_PENDING, PROJECTED, ALREADY_PROJECTED }

/** A released transaction observation only. In particular an exact locked replay is not restore/recovery clearance. */
internal class CatalogGenesisFinalizationObservation(val operationToken: String, val envelopeSha256: String, val state: CatalogGenesisFinalizationState) {
    override fun toString(): String = "CatalogGenesisFinalizationObservation($state,no-admission-or-restore-authority)"
}
