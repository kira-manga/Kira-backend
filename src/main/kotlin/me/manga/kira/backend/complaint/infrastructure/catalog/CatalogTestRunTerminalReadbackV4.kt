package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogObjectMetadata
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CheckedOfflineCatalogTestRunTerminalChain
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.parsing.catalog.OfflineCatalogTestRunTerminalParser
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import me.manga.kira.backend.complaint.parsing.catalog.ParsedOfflineCatalogGeneration
import me.manga.kira.backend.complaint.parsing.catalog.ParsedOfflineTerminalCatalogGeneration
import me.manga.kira.backend.complaint.parsing.catalog.ParsedOfflineTestRunCatalogGeneration
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneOffset
import java.sql.Timestamp
import java.util.concurrent.CancellationException

/**
 * Actual complete raw dual-location traversal through the distinct schema4 verifier. No supplied
 * metadata/checked-chain/Boolean constructor. An expected byte-address is not a local acceptance,
 * a denial capability, a PREPARED continuation, a lease, or permission to project a run.
 */
internal class CatalogTestRunTerminalReadbackV4 private constructor(
    val chain: CheckedOfflineCatalogTestRunTerminalChain,
    val expectedHead: CatalogLocalHead,
    val evaluatedAtEpochSecond: Long,
    val requiredRetainUntilEpochSecond: Long,
    val primaryEncodedBytes: Long,
    val replicaEncodedBytes: Long,
    val primaryMetadata: CatalogObjectMetadata,
    val replicaMetadata: CatalogObjectMetadata,
) {
    val objectVersion: String get() = primaryMetadata.requestBinding.versionId
    val retainUntilEpochSecond: Long = checkNotNull(primaryMetadata.retainUntilEpochSecond)
    fun signedEnvelopeBytes(): ByteArray = chain.canonicalEnvelopeBytes

    override fun toString(): String = "CatalogTestRunTerminalReadbackV4(actual-raw-dual,no-local-or-denial-authority)"

    companion object {
        fun verify(
            provider: CatalogReadbackPort,
            initialBundleBytes: ByteArray,
            currentBundleBytes: ByteArray,
            policy: CatalogReadbackPolicy,
            expectedHead: CatalogLocalHead,
            expected: CatalogTestRunTerminalCanonicalV4,
        ): CatalogTestRunTerminalReadbackV4 {
            requireConnectionFree()
            requireCatalogReadback(initialBundleBytes.size in 1..OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES &&
                currentBundleBytes.size in 1..OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES, CatalogReadbackFailure.LIMIT_EXCEEDED)
            val initial = initialBundleBytes.copyOf()
            val current = currentBundleBytes.copyOf()
            expected.requireReader(initial, current, policy.chain)
            expected.requireReadbackPolicy(policy)
            requireCatalogReadback(expectedHead.generation >= 3, CatalogReadbackFailure.INVALID_LOCAL_STATE)
            val trust = OfflineTrustBundleVerifier.verify(current, policy.chain.trustBundlePolicy)
            // Shape adapter only: selects exact-head/no-missing-replica traversal, never SQL authority.
            val expectation = CatalogLocalSnapshotVerifier.validate(LocalCatalogSnapshot.Accepted(expectedHead), initial, trust, policy)
            val stream = CatalogReadbackStream(provider, trust, policy, expectation)
            val failures = TerminalReadbackFailureRelay()
            val envelopes = sequence {
                var generation = 0L
                while (true) {
                    val bytes = failures.produce {
                        stream.nextOrNull()?.also { raw ->
                            generation++
                            val pair = stream.lastPair ?: throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)
                            requireCatalogReadback(!stream.waitingForReplica, CatalogReadbackFailure.HEAD_CONFLICT)
                            requireCreationRetention(raw, generation, pair.primary, policy)
                        }
                    } ?: break
                    yield(bytes) // Both actual exact-version bodies have returned and closed.
                }
            }
            val chain = try {
                OfflineCatalogTestRunTerminalChainVerifier.verify(envelopes, initial, current, policy.chain, expected)
            } catch (failure: OfflineTrustBundleException) {
                failures.restore(failure)
            }
            requireCatalogReadback(chain.tail.generation == expectedHead.generation &&
                chain.tail.envelopeSha256 == expectedHead.envelopeSha256 && !stream.waitingForReplica, CatalogReadbackFailure.HEAD_CONFLICT)
            val pair = stream.lastPair ?: throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)
            val read = stream.lastRead ?: throw CatalogReadbackException(CatalogReadbackFailure.HEAD_CONFLICT)
            requireCatalogReadback(read.bytes.contentEquals(chain.canonicalEnvelopeBytes) &&
                stream.primaryEncodedBytes == chain.encodedBytes && stream.replicaEncodedBytes == chain.encodedBytes, CatalogReadbackFailure.HEAD_CONFLICT)
            return CatalogTestRunTerminalReadbackV4(chain, expectedHead, policy.evaluatedAtEpochSecond,
                policy.requiredRetainUntilEpochSecond, stream.primaryEncodedBytes, stream.replicaEncodedBytes, pair.primary, pair.replica)
        }

        /** Dispatch is explicit: schema3/4 must never enter CatalogFrozenManifestParser's old grammar. */
        internal fun requireCreationRetention(bytes: ByteArray, generation: Long, primary: CatalogObjectMetadata, policy: CatalogReadbackPolicy) {
            val createdAt = if (generation == 1L) {
                OfflineTrustBundleParser.parseChainGenesis(bytes, policy.chain.limits.maximumManifestRecords).manifest.creation.createdAtEpochSecond
            } else when (val parsed = OfflineCatalogTestRunTerminalParser.parseGeneration(bytes,
                policy.chain.limits.maximumManifestRecords, policy.chain.limits.maximumEnvelopeBytes)) {
                is ParsedOfflineTerminalCatalogGeneration.TerminalV4 -> parsed.envelope.manifest.creation.createdAtEpochSecond
                is ParsedOfflineTerminalCatalogGeneration.Predecessor -> when (val previous = parsed.generation) {
                    is ParsedOfflineTestRunCatalogGeneration.ActivationV3 -> previous.envelope.manifest.creation.createdAtEpochSecond
                    is ParsedOfflineTestRunCatalogGeneration.Prefix -> when (val prefix = previous.generation) {
                        is ParsedOfflineCatalogGeneration.RotationV1 -> prefix.envelope.manifest.creation.createdAtEpochSecond
                        is ParsedOfflineCatalogGeneration.InventoryV2 -> prefix.envelope.manifest.creation.createdAtEpochSecond
                    }
                }
            }
            requireCatalogReadback(createdAt in 0..policy.evaluatedAtEpochSecond, CatalogReadbackFailure.RETENTION_MISMATCH)
            val creationFloor = try {
                Instant.ofEpochSecond(createdAt).atOffset(ZoneOffset.UTC)
                    .plusYears(VersionBoundCatalogReadbackConfigurationV1.CREATION_MINIMUM_YEARS).toEpochSecond()
            } catch (_: DateTimeException) {
                throw CatalogReadbackException(CatalogReadbackFailure.RETENTION_MISMATCH)
            } catch (_: ArithmeticException) {
                throw CatalogReadbackException(CatalogReadbackFailure.RETENTION_MISMATCH)
            }
            val retainUntil = primary.retainUntilEpochSecond ?: throw CatalogReadbackException(CatalogReadbackFailure.RETENTION_MISMATCH)
            requireCatalogReadback(creationFloor <= CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND &&
                retainUntil >= maxOf(creationFloor, policy.requiredRetainUntilEpochSecond), CatalogReadbackFailure.RETENTION_MISMATCH)
        }
    }
}

/**
 * One actual full predecessor traversal plus the exact deterministic successor in BOTH listings.
 * Absence/source-only is not acceptance. A dual result additionally consumes the entire genuine
 * schema1/2 -> schema3 -> schema4 raw chain within the same original native allowance.
 */
internal class CatalogTestRunTerminalDeliveryReadbackV1 private constructor(
    val state: State,
    private val snapshot: CatalogTestRunTerminalSnapshotV1,
    private val input: CatalogTestRunTerminalFrozenV1,
    private val signed: CatalogTestRunTerminalSignedV1?,
    val evaluatedAtEpochSecond: Long,
    val requiredRetainUntilEpochSecond: Long,
    primary: CatalogObjectMetadata?,
    replica: CatalogObjectMetadata?,
) {
    enum class State { UNPUBLISHED, AWAIT_REPLICATION, DUAL_COPY }
    val objectVersion: String? = primary?.requestBinding?.versionId
    val retainUntilEpochSecond: Long? = primary?.retainUntilEpochSecond
    private val primaryBytes = if (state === State.DUAL_COPY) copyEvidence(checkNotNull(primary), checkNotNull(signed).envelopeSha256) else null
    private val replicaBytes = if (state === State.DUAL_COPY) copyEvidence(checkNotNull(replica), checkNotNull(signed).envelopeSha256) else null
    fun primaryEvidenceBytes(): ByteArray? = primaryBytes?.copyOf()
    fun replicaEvidenceBytes(): ByteArray? = replicaBytes?.copyOf()
    fun completionArguments(): Array<Any?> {
        requireTestTerminalCatalog(state === State.DUAL_COPY)
        val a = checkNotNull(primaryBytes); val b = checkNotNull(replicaBytes)
        return arrayOf(checkNotNull(objectVersion), Timestamp.from(Instant.ofEpochSecond(checkNotNull(retainUntilEpochSecond))),
            a.copyOf(), terminalCatalogHex(Sha256.hex(a)), b.copyOf(), terminalCatalogHex(Sha256.hex(b)))
    }

    /** Detached facts only; the retained assembly/original separately proves current native closure. */
    fun requireSource(selected: CatalogTestRunTerminalSnapshotV1, frozen: CatalogTestRunTerminalFrozenV1, value: CatalogTestRunTerminalSignedV1?) {
        requireTestTerminalCatalog(input === frozen && signed === value)
        snapshot.requireSame(selected)
    }

    override fun toString(): String = "CatalogTestRunTerminalDeliveryReadbackV1(actual-full-raw-observation,no-PUT-or-projection-authority)"

    companion object {
        internal fun verify(
            original: CatalogTestRunTerminalV1,
            provider: CatalogReadbackPort,
            policy: CatalogReadbackPolicy,
            snapshot: CatalogTestRunTerminalSnapshotV1,
            input: CatalogTestRunTerminalFrozenV1,
            signed: CatalogTestRunTerminalSignedV1?,
        ): CatalogTestRunTerminalDeliveryReadbackV1 {
            requireConnectionFree(); original.requireRawObservation(snapshot, input, signed)
            val reader = original.process.catalogReadback
            val initial = reader.initialBundleBytes(); val current = reader.currentBundleBytes()
            val expected = original.expectedDeclaration
            expected.requireReader(initial, current, policy.chain); expected.requireReadbackPolicy(policy)
            val trust = OfflineTrustBundleVerifier.verify(current, policy.chain.trustBundlePolicy)
            val primaryListing = CatalogVersionListing(provider, trust.body.catalogLocations[0], policy)
            val replicaListing = CatalogVersionListing(provider, trust.body.catalogLocations[1], policy)
            val primaryReader = CatalogVersionReadback(provider, trust.body.catalogLocations[0], policy)
            val replicaReader = CatalogVersionReadback(provider, trust.body.catalogLocations[1], policy)
            var count = 0L
            val relay = TerminalReadbackFailureRelay()
            val envelopes = sequence {
                while (count < input.generation - 1L) {
                    val raw = relay.produce {
                        original.requireProviderRunning()
                        val primary = checkNotNull(primaryListing.nextOrNull())
                        val replica = checkNotNull(replicaListing.nextOrNull())
                        requireTestTerminalCatalog(primary.versionId == replica.versionId && primary.contentLength == replica.contentLength)
                        val source = primaryReader.read(primary, count + 1L, allowPending = false)
                        val destination = replicaReader.read(replica, count + 1L, allowPending = false)
                        requireTestTerminalCatalog(source.bytes.contentEquals(destination.bytes) &&
                            source.metadata.retainUntilEpochSecond == destination.metadata.retainUntilEpochSecond)
                        CatalogTestRunTerminalReadbackV4.requireCreationRetention(source.bytes, count + 1L, source.metadata, policy)
                        if (count + 1L == input.generation - 1L) {
                            snapshot.activation.requireRawActivation(source.bytes, source.metadata, destination.metadata,
                                expected, policy.chain.limits.maximumManifestRecords)
                        } else {
                            snapshot.history.requireRaw(Math.toIntExact(count), CatalogFrozenManifestParser.signed(source.bytes, policy.chain.limits), source.metadata)
                        }
                        count++
                        original.requireProviderRunning()
                        source.bytes
                    }
                    yield(raw) // Exact native bodies have actually returned and closed before every yield.
                }
            }
            val prefix = try {
                OfflineCatalogInventoryChainVerifier.verifyTestRunActivationChain(envelopes, initial, current, policy.chain, expected.activation)
            } catch (failure: OfflineTrustBundleException) {
                original.throwIfSignalled(); relay.restore(failure)
            }
            requireTestTerminalCatalog(count == input.generation - 1L && prefix.tail.generation == count &&
                prefix.tail.envelopeSha256 == input.predecessorHash && primaryReader.encodedBytes == prefix.encodedBytes &&
                replicaReader.encodedBytes == prefix.encodedBytes)
            val manifest = input.manifest()
            requireTestTerminalCatalog(manifest.approvals.all { it.approvedAtEpochSecond <= policy.evaluatedAtEpochSecond } &&
                expected.assemble(prefix, manifest.terminalRecord, manifest.creation, manifest.approvals).contentEquals(input.unsignedBytes()))
            val primaryTail = primaryListing.nextOrNull()
            val replicaTail = replicaListing.nextOrNull()
            if (primaryTail == null) {
                requireTestTerminalCatalog(replicaTail == null && snapshot.terminal?.completed == null)
                original.requireProviderRunning()
                return CatalogTestRunTerminalDeliveryReadbackV1(State.UNPUBLISHED, snapshot, input, signed,
                    policy.evaluatedAtEpochSecond, policy.requiredRetainUntilEpochSecond, null, null)
            }
            val value = checkNotNull(signed) // A new PREPARE never adopts any external successor.
            requireTestTerminalCatalog(primaryListing.nextOrNull() == null && replicaListing.nextOrNull() == null &&
                primaryTail.contentLength <= expected.maximumDocumentBytes &&
                (replicaTail == null || replicaTail.contentLength <= expected.maximumDocumentBytes))
            val source = primaryReader.read(primaryTail, input.generation, allowPending = replicaTail == null)
            CatalogTestRunTerminalReadbackV4.requireCreationRetention(source.bytes, input.generation, source.metadata, policy)
            requireTestTerminalCatalog(source.bytes.contentEquals(value.envelopeBytes()))
            if (replicaTail == null) {
                requireTestTerminalCatalog(snapshot.terminal?.completed == null)
                original.requireProviderRunning()
                return CatalogTestRunTerminalDeliveryReadbackV1(State.AWAIT_REPLICATION, snapshot, input, signed,
                    policy.evaluatedAtEpochSecond, policy.requiredRetainUntilEpochSecond, source.metadata, null)
            }
            requireTestTerminalCatalog(primaryTail.versionId == replicaTail.versionId && primaryTail.contentLength == replicaTail.contentLength)
            val destination = replicaReader.read(replicaTail, input.generation, allowPending = false)
            requireTestTerminalCatalog(source.bytes.contentEquals(destination.bytes) &&
                source.metadata.retainUntilEpochSecond == destination.metadata.retainUntilEpochSecond)
            val dual = CatalogTestRunTerminalReadbackV4.verify(provider, initial, current, policy,
                CatalogLocalHead(input.generation, value.envelopeSha256), expected)
            requireTestTerminalCatalog(dual.signedEnvelopeBytes().contentEquals(value.envelopeBytes()) &&
                dual.primaryMetadata == source.metadata && dual.replicaMetadata == destination.metadata &&
                dual.primaryEncodedBytes == primaryReader.encodedBytes && dual.replicaEncodedBytes == replicaReader.encodedBytes)
            original.requireProviderRunning()
            return CatalogTestRunTerminalDeliveryReadbackV1(State.DUAL_COPY, snapshot, input, signed,
                policy.evaluatedAtEpochSecond, policy.requiredRetainUntilEpochSecond, dual.primaryMetadata, dual.replicaMetadata).also {
                if (snapshot.terminal?.completed != null) snapshot.terminal.requireDual(it)
            }
        }
    }
}

/** Keep sanitized producer errors/cancellation across the unchanged raw iterator boundary. */
private class TerminalReadbackFailureRelay {
    private var recorded: RuntimeException? = null
    fun <T> produce(action: () -> T): T = try {
        action()
    } catch (failure: CatalogReadbackException) {
        recorded = failure; throw failure
    } catch (failure: OfflineTrustBundleException) {
        recorded = failure; throw failure
    } catch (failure: CancellationException) {
        recorded = failure; throw failure
    }
    fun restore(failure: OfflineTrustBundleException): Nothing = throw (recorded ?: failure)
}
