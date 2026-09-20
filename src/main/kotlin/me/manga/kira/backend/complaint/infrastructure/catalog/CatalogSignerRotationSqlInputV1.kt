package me.manga.kira.backend.complaint.infrastructure.catalog

import kotlinx.serialization.builtins.ListSerializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisApprovalV1
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** Fixed pre-entry SQL buffers. No caller-selected SQL, arbitrary generation/profile, completion or head update. */
internal class CatalogSignerRotationSqlInputV1 private constructor(
    internal val attempt: CatalogSignerRotationFreezeAttemptV1,
    internal val path: PersistencePhasePath,
    internal val expected: CatalogSignerRotationObservationV1?,
    internal val requirePrepared: Boolean,
    internal val after: CatalogFrozenMutation?,
) {
    private val inputs = attempt.inputs
    private val manifest = inputs.manifest
    private val first = manifest.requiredSignerPolicy.members[0]
    private val second = manifest.requiredSignerPolicy.members[1]
    private val approval = inputs.approvalBytes()
    private val unsigned = inputs.intentBytes()
    private val token = UUID.fromString(manifest.operationToken)
    private val writer = UUID.fromString(manifest.catalogWriterGenerationId)
    private val predecessorHash = digest(manifest.previousEnvelopeSha256)
    private val binding = attempt.bindingArguments()
    private val frozen = arrayOf<Any?>(
        token, writer, approval, digest(Sha256.hex(approval)), unsigned, digest(inputs.unsignedHash),
        first.keyId, first.algorithmId, second.keyId, second.algorithmId,
        CatalogReadbackProtocol.key(2), Timestamp.from(Instant.ofEpochSecond(manifest.creation.createdAtEpochSecond)), predecessorHash,
    )
    private val beforeSignatures = signatures(expected?.mutation)
    private val afterSignatures = signatures(after)
    private val genesisFields = expected?.genesis?.let { genesis ->
        val manifest = OfflineTrustBundleParser.parseGenesis(checkNotNull(genesis.signedEnvelopeBytes)).manifest
        arrayOf<Any?>(
            UUID.fromString(genesis.operationToken),
            genesis.unsignedManifestBytes,
            digest(genesis.unsignedManifestSha256),
            genesis.signatureSlots.single().signatureBytes,
            genesis.signedEnvelopeBytes,
            genesis.signedEnvelopeSha256?.let(::digest),
            CanonicalJson.canonicalize(ListSerializer(OfflineCatalogGenesisApprovalV1.serializer()), manifest.approvals).toByteArray(),
        )
    }
    private val history = arrayOf<Any?>(
        predecessorHash,
        first.keyId,
        first.algorithmId,
        writer,
        expected?.genesis?.signedEnvelopeBytes,
        expected?.genesis?.signedEnvelopeBytes,
        *frozen,
    )
    private val signatureWrite = arrayOf<Any?>(*afterSignatures, *frozen, *beforeSignatures)

    init {
        requireConnectionFree()
        attempt.requireRunning()
        if (path === PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE) {
            val before = checkNotNull(expected?.mutation)
            val next = checkNotNull(after)
            inputs.requireMutation(before)
            inputs.requireMutation(next)
            val old = before.signatureSlots.map { it.signatureBytes }
            val new = next.signatureSlots.map { it.signatureBytes }
            old.forEachIndexed { index, bytes -> requireSignerRotation(bytes == null || bytes.contentEquals(new[index])) }
            requireSignerRotation(
                (old[0] == null && old[1] == null && new[0] != null && new[1] == null) ||
                    (old[0] != null && new[0].contentEquals(old[0]) && new[1] != null),
            )
            before.signedEnvelopeBytes?.let { requireSignerRotation(it.contentEquals(next.signedEnvelopeBytes)) }
        }
    }

    internal fun requirePersistence(ownership: PersistencePhaseOwnership, jdbc: JdbcTemplate) = attempt.requirePersistence(ownership, jdbc, this)

    internal fun bindingArguments(): Array<Any?> = binding
    internal fun historyArguments(): Array<Any?> = history
    internal fun insertArguments(): Array<Any?> = frozen
    internal fun signatureArguments(): Array<Any?> = signatureWrite
    internal fun expectedSignatures(): Array<Any?> = beforeSignatures
    internal fun nextSignatures(): Array<Any?> = afterSignatures
    internal fun expectedGenesisFields(): Array<Any?>? = genesisFields

    override fun toString(): String = "CatalogSignerRotationSqlInputV1(fixed-pre-entry-buffers,redacted)"

    companion object {
        internal fun initial(attempt: CatalogSignerRotationFreezeAttemptV1) =
            CatalogSignerRotationSqlInputV1(attempt, PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ, null, false, null)

        internal fun prepared(attempt: CatalogSignerRotationFreezeAttemptV1) =
            CatalogSignerRotationSqlInputV1(attempt, PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ, null, true, null)

        internal fun recheck(attempt: CatalogSignerRotationFreezeAttemptV1, before: CatalogSignerRotationObservationV1) =
            CatalogSignerRotationSqlInputV1(attempt, PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_READ, before, true, null)

        internal fun prepare(attempt: CatalogSignerRotationFreezeAttemptV1, before: CatalogSignerRotationObservationV1) =
            CatalogSignerRotationSqlInputV1(attempt, PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE, before, false, attempt.inputs.unsigned())

        internal fun signature(attempt: CatalogSignerRotationFreezeAttemptV1, before: CatalogSignerRotationObservationV1, after: CatalogFrozenMutation) =
            CatalogSignerRotationSqlInputV1(attempt, PersistencePhasePath.COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE, before, true, after)

        private fun digest(hash: String): ByteArray = HexFormat.of().parseHex(hash)
        private fun signatures(value: CatalogFrozenMutation?): Array<Any?> = arrayOf(
            value?.signatureSlots?.get(0)?.signatureBytes,
            value?.signatureSlots?.get(1)?.signatureBytes,
            value?.signedEnvelopeBytes,
            value?.signedEnvelopeSha256?.let(::digest),
        )
    }
}
