package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogListedVersion
import me.manga.kira.backend.complaint.domain.catalog.CatalogObjectMetadata
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.SignatureCheckedOfflineTrustBundle
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback

internal data class ReadCatalogPair(val primary: CatalogObjectMetadata, val replica: CatalogObjectMetadata)

/** One bounded page per location and the current closed readback only, never an aggregate chain or an open yielded body. */
internal class CatalogReadbackStream(
    provider: CatalogReadbackPort,
    trust: SignatureCheckedOfflineTrustBundle,
    private val policy: CatalogReadbackPolicy,
    private val local: ValidatedLocalCatalog,
) {
    private val locations = trust.body.catalogLocations
    private val primaryListing = CatalogVersionListing(provider, locations[0], policy)
    private val replicaListing = CatalogVersionListing(provider, locations[1], policy)
    private val primaryReader = CatalogVersionReadback(provider, locations[0], policy)
    private val replicaReader = CatalogVersionReadback(provider, locations[1], policy)
    private var generation = 0L
    private var ended = false
    var waitingForReplica = false
        private set
    var lastRead: ReadCatalogVersion? = null
        private set
    var lastPair: ReadCatalogPair? = null
        private set
    val primaryEncodedBytes: Long get() = primaryReader.encodedBytes
    val replicaEncodedBytes: Long get() = replicaReader.encodedBytes

    fun nextOrNull(): ByteArray? {
        if (ended) return null
        val primary = primaryListing.nextOrNull()
        val replica = replicaListing.nextOrNull()
        if (primary == null) {
            requireCatalogReadback(replica == null, CatalogReadbackFailure.HEAD_CONFLICT)
            ended = true
            return null
        }
        generation++
        val readback = if (replica == null) readPreparedTail(primary) else readPair(primary, replica)
        val hash = Sha256.hex(readback.bytes)
        if (generation == 1L) {
            requireCatalogReadback(hash == policy.expectedGenesisEnvelopeSha256, CatalogReadbackFailure.HEAD_CONFLICT)
        }
        val head = local.head
        if (head != null && generation == head.generation) {
            requireCatalogReadback(hash == head.envelopeSha256, CatalogReadbackFailure.HEAD_CONFLICT)
        }
        lastRead = readback
        return readback.bytes
    }

    private fun readPair(primary: CatalogListedVersion, replica: CatalogListedVersion): ReadCatalogVersion {
        requireCatalogReadback(
            primary.versionId == replica.versionId && primary.contentLength == replica.contentLength,
            CatalogReadbackFailure.REPLICATION_MISMATCH,
        )
        val source = primaryReader.read(primary, generation, allowPending = false)
        val destination = replicaReader.read(replica, generation, allowPending = false)
        requireCatalogReadback(source.bytes.contentEquals(destination.bytes), CatalogReadbackFailure.REPLICATION_MISMATCH)
        requireCatalogReadback(
            source.metadata.retainUntilEpochSecond == destination.metadata.retainUntilEpochSecond,
            CatalogReadbackFailure.RETENTION_MISMATCH,
        )
        lastPair = ReadCatalogPair(source.metadata, destination.metadata)
        return source
    }

    private fun readPreparedTail(primary: CatalogListedVersion): ReadCatalogVersion {
        val expectedGeneration = when (val supplied = local.supplied) {
            is LocalCatalogSnapshot.Prepared -> supplied.head.generation + 1
            is LocalCatalogSnapshot.PreparedGenesis -> 1L
            else -> conflict()
        }
        val envelope = local.frozen?.envelopeBytes ?: conflict()
        requireCatalogReadback(generation == expectedGeneration, CatalogReadbackFailure.HEAD_CONFLICT)
        requireCatalogReadback(primaryListing.nextOrNull() == null, CatalogReadbackFailure.HEAD_CONFLICT)
        val candidate = primaryReader.read(primary, generation, allowPending = true)
        requireCatalogReadback(candidate.bytes.contentEquals(envelope), CatalogReadbackFailure.HEAD_CONFLICT)
        waitingForReplica = true
        ended = true
        return candidate
    }

    private fun conflict(): Nothing = throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)
}
