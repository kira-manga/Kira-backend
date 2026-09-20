package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.builtins.ListSerializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundComplaintProcessConfiguration
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** Detached exact raw tuple for one history-only observation, never a mutation producer, lease binding or result factory. */
internal class CatalogProjectedHeadInputV1 private constructor(
    private val process: VersionBoundComplaintProcessConfiguration,
    private val readback: CatalogDualLocationVerifier.ProjectedHeadReadback,
    internal val attempt: CatalogReadbackRefreshCustodyV1.Attempt,
) {
    private val settings = checkNotNull(process.catalogReadback)
    private val binding = CatalogGenesisInitialLiveBinding.fromRetained(process)
    private val frozen = readback.generation()
    private val claims = frozen.claims.also { binding.requireMatchingRegistry(it.initialWriterRegistry) }
    private val desired = process.desiredSettings()
    private val control = arrayOf<Any?>(
        desired.desiredGeneration,
        process.configurationHashBytes(),
        desired.databaseIdentity,
        desired.restoreIdentity,
        UUID.fromString(claims.initialWriterRegistry.eventWriter.generationId),
        claims.generation,
        digest(readback.envelopeSha256),
        digest(readback.currentTrustBundleSha256),
        UUID.fromString(claims.catalogWriterGenerationId),
    )
    private val mutation = mutationArguments()

    init {
        requireCatalogReadback(settings.projectedCurrent && claims.generation > 1, CatalogReadbackFailure.INVALID_POLICY)
        attempt.requireProjectedProcess(process)
        attempt.requireProviderClosed()
    }

    internal fun requirePersistence(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) {
        attempt.requireProjectedProcess(process)
        attempt.requireProviderClosed()
        binding.requirePersistence(ownership, jdbc)
    }

    internal fun controlArguments(operation: CatalogProjectedHeadReadOperationV1, jdbc: JdbcTemplate): Array<Any?> {
        operation.requireInput(this, jdbc)
        return control
    }

    internal fun mutationArguments(operation: CatalogProjectedHeadReadOperationV1, jdbc: JdbcTemplate): Array<Any?> {
        operation.requireInput(this, jdbc)
        return mutation
    }

    private fun mutationArguments(): Array<Any?> {
        val approval = CanonicalJson.canonicalize(ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), claims.approvals).toByteArray(Charsets.UTF_8)
        val first = frozen.signatures.first()
        val second = frozen.signatures.getOrNull(1)
        val envelope = checkNotNull(frozen.envelopeBytes)
        val primary = readback.primaryEvidenceBytes()
        val replica = readback.replicaEvidenceBytes()
        return arrayOf(
            UUID.fromString(claims.operationToken), sqlOperation(), claims.generation - 1, digest(claims.previousEnvelopeSha256), claims.generation,
            UUID.fromString(claims.catalogWriterGenerationId), approval, hash(approval), frozen.manifestBytes, hash(frozen.manifestBytes),
            claims.requiredSignerPolicy.mode, first.keyId, first.algorithmId, Base64.getDecoder().decode(first.signatureBase64),
            second?.keyId, second?.algorithmId, second?.let { Base64.getDecoder().decode(it.signatureBase64) }, envelope, hash(envelope),
            CatalogReadbackProtocol.key(claims.generation), Timestamp.from(Instant.ofEpochSecond(claims.creation.createdAtEpochSecond)),
            readback.objectVersion, Timestamp.from(Instant.ofEpochSecond(readback.retainUntilEpochSecond)), primary, hash(primary), replica, hash(replica),
        )
    }

    /** Same closed wire/SQL bridge as snapshot validation; logical ACCEPTED claims are not physical backup-acceptance provenance. */
    private fun sqlOperation(): String = when (claims.operation) {
        OfflineCatalogChainProtocol.ROTATION_OVERLAP -> "SIGNER_ROTATION_OVERLAP"
        OfflineCatalogChainProtocol.ROTATION_ACTIVATE -> "SIGNER_ROTATION_ACTIVATION"
        CatalogLogicalInventoryProtocol.REGISTER_SOURCE, CatalogLogicalInventoryProtocol.ADD_COPY -> "RESTORE_SOURCE_ACCEPTANCE"
        else -> throw CatalogReadbackException(CatalogReadbackFailure.INVALID_LOCAL_STATE)
    }

    override fun toString(): String = "CatalogProjectedHeadInputV1(exact-history-read,no-current-authority)"

    companion object {
        internal fun fromRetained(
            process: VersionBoundComplaintProcessConfiguration,
            readback: CatalogDualLocationVerifier.ProjectedHeadReadback,
            attempt: CatalogReadbackRefreshCustodyV1.Attempt,
        ): CatalogProjectedHeadInputV1 {
            requireConnectionFree()
            return CatalogProjectedHeadInputV1(process, readback, attempt)
        }

        private fun digest(value: String): ByteArray = HexFormat.of().parseHex(value)
        private fun hash(bytes: ByteArray): ByteArray = digest(Sha256.hex(bytes))
    }
}
