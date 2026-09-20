package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogObjectMetadata
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat

/**
 * TEST-only actual raw delivery bridge. No caller-supplied "checked" result or schema3-to-schema1/2 adapter.
 * Exact signed bytes are independently tied to a full raw Stable prefix, including the SQL raw fingerprints.
 * A dual result additionally uses the unchanged complete schema3 raw verifier, not a PREPARED-shaped proxy.
 * Neither absent versions nor a raw dual result grants a PUT, COMPLETE, projected run or global drain.
 */
internal class CatalogTestRunActivationDeliveryReadbackV1 private constructor(
    val state: State,
    private val snapshot: CatalogTestRunActivationSnapshotV1,
    private val input: CatalogTestRunActivationFrozenV1,
    private val signed: CatalogTestRunActivationSignedV1,
    val evaluatedAtEpochSecond: Long,
    val requiredRetainUntilEpochSecond: Long,
    primary: CatalogObjectMetadata?,
    replica: CatalogObjectMetadata?,
) {
    enum class State { UNPUBLISHED, AWAIT_REPLICATION, DUAL_COPY }

    val objectVersion: String? = primary?.requestBinding?.versionId
    val retainUntilEpochSecond: Long? = primary?.retainUntilEpochSecond
    val envelopeSha256: String = signed.envelopeSha256
    private val primaryBytes = if (state === State.DUAL_COPY) copyEvidence(checkNotNull(primary), signed.envelopeSha256) else null
    private val replicaBytes = if (state === State.DUAL_COPY) copyEvidence(checkNotNull(replica), signed.envelopeSha256) else null
    private val primaryHash = primaryBytes?.let { HexFormat.of().parseHex(Sha256.hex(it)) }
    private val replicaHash = replicaBytes?.let { HexFormat.of().parseHex(Sha256.hex(it)) }

    fun primaryEvidenceBytes(): ByteArray? = primaryBytes?.copyOf()
    fun replicaEvidenceBytes(): ByteArray? = replicaBytes?.copyOf()
    fun primaryEvidenceHash(): ByteArray? = primaryHash?.copyOf()
    fun replicaEvidenceHash(): ByteArray? = replicaHash?.copyOf()

    fun completionArguments(): Array<Any?> {
        check(state === State.DUAL_COPY)
        return arrayOf(checkNotNull(objectVersion), Timestamp.from(Instant.ofEpochSecond(checkNotNull(retainUntilEpochSecond))),
            checkNotNull(primaryBytes).copyOf(), checkNotNull(primaryHash).copyOf(), checkNotNull(replicaBytes).copyOf(), checkNotNull(replicaHash).copyOf())
    }

    /** Identity/byte facts only, also usable under the original fixed SQL holder after all providers actually closed. */
    fun requireSource(selected: CatalogTestRunActivationSnapshotV1, frozen: CatalogTestRunActivationFrozenV1, value: CatalogTestRunActivationSignedV1) {
        check(snapshot === selected && input === frozen && signed === value)
    }

    override fun toString(): String = "CatalogTestRunActivationDeliveryReadbackV1(original-raw-observation,no-PUT-or-SQL-authority)"

    companion object {
        @Suppress("TooGenericExceptionCaught", "LongMethod")
        internal fun verify(
            original: CatalogTestRunActivationV1,
            provider: CatalogReadbackPort,
            policy: CatalogReadbackPolicy,
            snapshot: CatalogTestRunActivationSnapshotV1,
            input: CatalogTestRunActivationFrozenV1,
            signed: CatalogTestRunActivationSignedV1,
        ): CatalogTestRunActivationDeliveryReadbackV1 {
            requireConnectionFree()
            original.requireProviderRunning()
            original.requireRawDeliverySnapshot(snapshot, input, signed)
            val reader = original.process.catalogReadback
            val initial = reader.initialBundleBytes()
            val current = reader.currentBundleBytes()
            original.expectedDeclaration.requireReader(initial, current, policy.chain)
            original.expectedDeclaration.requireReadbackPolicy(policy)
            val trust = OfflineTrustBundleVerifier.verify(current, policy.chain.trustBundlePolicy)
            val locations = trust.body.catalogLocations
            val primaryListing = CatalogVersionListing(provider, locations[0], policy)
            val replicaListing = CatalogVersionListing(provider, locations[1], policy)
            val primaryReader = CatalogVersionReadback(provider, locations[0], policy)
            val replicaReader = CatalogVersionReadback(provider, locations[1], policy)
            var oldest: Long? = null
            var latestApproval: Long? = null
            var originalFailure: Throwable? = null
            var count = 0
            val envelopes = sequence {
                while (count.toLong() < input.generation - 1L) {
                    val bytes = try {
                        original.requireProviderRunning()
                        val primary = checkNotNull(primaryListing.nextOrNull())
                        val replica = checkNotNull(replicaListing.nextOrNull())
                        requireTestActivation(primary.versionId == replica.versionId && primary.contentLength == replica.contentLength)
                        val source = primaryReader.read(primary, count + 1L, allowPending = false)
                        val destination = replicaReader.read(replica, count + 1L, allowPending = false)
                        requireTestActivation(source.bytes.contentEquals(destination.bytes) &&
                            source.metadata.retainUntilEpochSecond == destination.metadata.retainUntilEpochSecond)
                        // Only the genuine schema1/2 prefix enters the existing parser. The TEST tail never does.
                        val parsed = CatalogFrozenManifestParser.signed(source.bytes, policy.chain.limits)
                        snapshot.history.requireRaw(count, parsed, source.metadata)
                        requireRetention(parsed.claims.creation.createdAtEpochSecond, source.metadata, policy)
                        oldest = oldest ?: parsed.claims.oldestRestoreTimeEpochSecond
                        requireTestActivation(oldest == parsed.claims.oldestRestoreTimeEpochSecond)
                        latestApproval = parsed.claims.approvals.maxOf { it.approvedAtEpochSecond }
                        count++
                        original.requireProviderRunning()
                        source.bytes
                    } catch (failure: Throwable) {
                        originalFailure = failure
                        original.observeFailure(failure)
                        throw failure
                    }
                    yield(bytes) // Actual exact-version bodies are closed before the raw chain sees the bytes.
                }
            }
            val chain = try {
                OfflineCatalogInventoryChainVerifier.verifyInventoryChain(envelopes, initial, current, policy.chain)
            } catch (failure: OfflineTrustBundleException) {
                original.throwIfSignalled()
                throw (originalFailure ?: failure)
            }
            requireTestActivation(count.toLong() == input.generation - 1L && chain.tail.generation == input.generation - 1L &&
                chain.tail.envelopeSha256 == input.predecessorHash && primaryReader.encodedBytes == chain.encodedBytes && replicaReader.encodedBytes == chain.encodedBytes)
            val manifest = input.manifest()
            requireTestActivation(manifest.oldestRestoreTimeEpochSecond == checkNotNull(oldest) &&
                manifest.creation.createdAtEpochSecond >= checkNotNull(latestApproval) &&
                manifest.approvals.all { it.approvedAtEpochSecond <= policy.evaluatedAtEpochSecond })
            // Genuine prefix fold binds Stable/current key, inventory, full D/J/N and exact approved canonical bytes.
            requireTestActivation(original.expectedDeclaration.assemble(chain, checkNotNull(oldest), manifest.operationToken, manifest.creation, manifest.approvals)
                .contentEquals(input.unsignedBytes()))
            val primaryTail = primaryListing.nextOrNull()
            val replicaTail = replicaListing.nextOrNull()
            if (primaryTail == null) {
                requireTestActivation(replicaTail == null && snapshot.completedTail == null)
                return CatalogTestRunActivationDeliveryReadbackV1(State.UNPUBLISHED, snapshot, input, signed,
                    policy.evaluatedAtEpochSecond, policy.requiredRetainUntilEpochSecond, null, null)
            }
            requireTestActivation(primaryListing.nextOrNull() == null && replicaListing.nextOrNull() == null)
            val source = primaryReader.read(primaryTail, input.generation, allowPending = replicaTail == null)
            requireRetention(manifest.creation.createdAtEpochSecond, source.metadata, policy)
            requireTestActivation(source.bytes.contentEquals(signed.envelopeBytes()))
            if (replicaTail == null) {
                requireTestActivation(snapshot.completedTail == null)
                return CatalogTestRunActivationDeliveryReadbackV1(State.AWAIT_REPLICATION, snapshot, input, signed,
                    policy.evaluatedAtEpochSecond, policy.requiredRetainUntilEpochSecond, source.metadata, null)
            }
            requireTestActivation(primaryTail.versionId == replicaTail.versionId && primaryTail.contentLength == replicaTail.contentLength)
            val destination = replicaReader.read(replicaTail, input.generation, allowPending = false)
            requireTestActivation(source.bytes.contentEquals(destination.bytes) &&
                source.metadata.retainUntilEpochSecond == destination.metadata.retainUntilEpochSecond)

            // A second real raw traversal, within this SAME original provider allowance, authenticates the
            // complete schema3 chain through its existing strict entry. No supplied body/list/result proxy.
            val dual = CatalogTestRunActivationReadbackV3.verify(provider, initial, current, policy,
                CatalogLocalHead(input.generation, signed.envelopeSha256), original.expectedDeclaration)
            requireTestActivation(dual.signedEnvelopeBytes().contentEquals(signed.envelopeBytes()) &&
                dual.primaryMetadata == source.metadata && dual.replicaMetadata == destination.metadata &&
                dual.primaryEncodedBytes == primaryReader.encodedBytes && dual.replicaEncodedBytes == replicaReader.encodedBytes)
            original.requireProviderRunning()
            return CatalogTestRunActivationDeliveryReadbackV1(State.DUAL_COPY, snapshot, input, signed,
                policy.evaluatedAtEpochSecond, policy.requiredRetainUntilEpochSecond, dual.primaryMetadata, dual.replicaMetadata).also {
                snapshot.completedTail?.requireExact(it)
            }
        }

        private fun requireRetention(createdAt: Long, metadata: CatalogObjectMetadata, policy: CatalogReadbackPolicy) {
            requireTestActivation(createdAt in 0L..policy.evaluatedAtEpochSecond)
            val floor = Instant.ofEpochSecond(createdAt).atOffset(ZoneOffset.UTC)
                .plusYears(VersionBoundCatalogReadbackConfigurationV1.CREATION_MINIMUM_YEARS).toEpochSecond()
            requireTestActivation(checkNotNull(metadata.retainUntilEpochSecond) >= maxOf(floor, policy.requiredRetainUntilEpochSecond))
        }
    }
}
